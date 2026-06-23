(ns nido.shared-pg-test
  (:require
   [babashka.fs :as fs]
   [babashka.process]
   [clojure.test :refer [deftest is]]
   [nido.process :as proc]
   [nido.session.services.postgresql :as pg]
   [nido.session.state :as state]
   [nido.shared-pg :as shared]))

(deftest with-lock-serializes-and-returns-body-value
  (let [lock (str (fs/path (fs/create-temp-dir) "t.lock"))]
    (is (= :ok (shared/with-lock lock (fn [] :ok))))
    ;; lock file is released (deletable) after the body returns
    (is (= :again (shared/with-lock lock (fn [] :again))))))

(deftest resolve-shared-port-is-deterministic-per-project
  (is (= (shared/resolve-shared-port "brian")
         (shared/resolve-shared-port "brian")))
  (is (<= 5500 (shared/resolve-shared-port "brian") 7499)))

(deftest ensure-up-requires-an-initialized-template
  ;; With no template for a bogus project, ensure-up! must fail clearly rather
  ;; than initdb a blank cluster.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"[Tt]emplate"
       (shared/ensure-up! "definitely-not-a-real-project-xyz"))))

(deftest down-removes-stale-pid-without-calling-pg-ctl-stop
  (let [tmp (str (fs/create-temp-dir))
        _ (spit (str (fs/path tmp "PG_VERSION")) "17")
        stop-called (atom false)
        deleted (atom nil)]
    (with-redefs [state/shared-pg-data-dir (fn [_] tmp)
                  pg/detect-running-postmaster (fn [_] {:status :stale :pid-file (str (fs/path tmp "postmaster.pid"))})
                  pg/pg-ctl-stop! (fn [_] (reset! stop-called true))
                  fs/delete-if-exists (fn [p] (reset! deleted p))]
      (shared/down! "proj")
      (is (false? @stop-called) "must NOT pg-ctl-stop a dead cluster")
      (is (= (str (fs/path tmp "postmaster.pid")) @deleted) "must remove the stale pid file"))
    (fs/delete-tree tmp)))

(deftest down-stops-a-running-cluster
  (let [tmp (str (fs/create-temp-dir))
        _ (spit (str (fs/path tmp "PG_VERSION")) "17")
        stop-called (atom false)]
    (with-redefs [state/shared-pg-data-dir (fn [_] tmp)
                  pg/detect-running-postmaster (fn [_] {:status :running :pid 123 :port 6145})
                  pg/pg-ctl-stop! (fn [_] (reset! stop-called true))]
      (shared/down! "proj")
      (is (true? @stop-called)))
    (fs/delete-tree tmp)))

;; pick-shared-port — the shared cluster must keep its STABLE deterministic port
;; across restart/reset so it never strands sessions pinned to it. After down!
;; stops our own postmaster (pg-ctl -w waits for full shutdown), the port can
;; linger in TIME_WAIT from the closed client connections; a bind-probe
;; (find-available-port) reads that as busy and skips ahead — the bug that
;; bumped the cluster 6145→6146 and stranded ~10 live sessions. Nothing is
;; LISTENING there and PostgreSQL (SO_REUSEADDR) can rebind it, so we keep the
;; port and only scan when a real listener actually occupies it.

(deftest pick-shared-port-keeps-the-stable-port-when-nothing-is-listening
  (with-redefs [shared/resolve-shared-port (constantly 6145)
                proc/tcp-open? (fn [_] false)                 ; free or merely TIME_WAIT
                proc/find-available-port (fn [& _] (throw (ex-info "must not scan past the stable port" {})))]
    (is (= 6145 (#'shared/pick-shared-port "brian"))
        "a TIME_WAIT-but-not-listening preferred port stays stable (PG rebinds it)")))

(deftest pick-shared-port-scans-only-when-a-real-listener-occupies-it
  (with-redefs [shared/resolve-shared-port (constantly 6145)
                proc/tcp-open? (fn [p] (= p 6145))            ; a real server is listening on 6145
                proc/find-available-port (fn [pref _] (+ pref 1))]
    (is (= 6146 (#'shared/pick-shared-port "brian"))
        "only an actively-listening preferred port forces a scan to an alternative")))
