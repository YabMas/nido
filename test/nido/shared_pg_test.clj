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

(deftest migration-file-parsing
  (is (= 258 (shared/migration-file->version "V258__create_licence_expiry_notification.sql")))
  (is (= 64  (shared/migration-file->version "V64__change-language-type.sql")))
  (is (nil?  (shared/migration-file->version "R__repeatable.sql")))
  (is (= "create email delivery event"
         (shared/migration-file->description "V232__create_email_delivery_event.sql")))
  (is (= "change-language-type"
         (shared/migration-file->description "V64__change-language-type.sql"))))

(deftest pending-migrations-are-sorted-and-filtered
  (is (= ["V234__a.sql" "V258__c.sql"]
         (shared/pending-migrations 233 ["V258__c.sql" "V233__b.sql" "V234__a.sql" "not-a-migration.sql"])))
  (is (= [] (shared/pending-migrations 300 ["V258__c.sql"]))))

(deftest app-role-sql-grants-dml-and-withholds-ddl
  (let [sql (shared/app-role-sql {:schema "brian" :app-user "brian_app"})]
    (is (re-find #"(?i)create role brian_app" sql))
    (is (re-find #"(?i)grant usage on schema brian to brian_app" sql))
    (is (re-find #"(?i)grant select, insert, update, delete on all tables in schema brian to brian_app" sql))
    (is (re-find #"(?i)alter default privileges .* in schema brian grant select, insert, update, delete on tables to brian_app" sql))
    ;; MUST NOT grant CREATE on the schema — that is the whole guard
    (is (not (re-find #"(?i)grant .*create.* on schema brian to brian_app" sql)))
    ;; DEFAULT PRIVILEGES defaults to owner "user" ...
    (is (re-find #"(?i)alter default privileges for role user in schema brian" sql)))
  ;; ... and honors a non-default owner-user (so future owner-created tables
  ;; actually inherit the app role's grants).
  (let [sql (shared/app-role-sql {:schema "brian" :app-user "brian_app" :owner-user "brian_owner"})]
    (is (re-find #"(?i)alter default privileges for role brian_owner in schema brian" sql))))

(deftest ensure-app-role-noops-without-app-user
  (let [calls (atom 0)]
    (with-redefs [shared/run-owner-sql! (fn [_ _] (swap! calls inc))]
      (shared/ensure-app-role! {:port 5555 :db-name "brian" :owner-user "user"
                                :schema "brian" :app-user nil})
      (is (zero? @calls) "no app-user configured → feature off → no SQL run"))))
