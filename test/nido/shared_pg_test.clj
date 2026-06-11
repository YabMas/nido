(ns nido.shared-pg-test
  (:require
   [babashka.fs :as fs]
   [babashka.process]
   [clojure.test :refer [deftest is]]
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
