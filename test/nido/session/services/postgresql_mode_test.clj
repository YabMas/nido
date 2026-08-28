(ns nido.session.services.postgresql-mode-test
  (:require
   [babashka.fs]
   [clojure.test :refer [deftest is]]
   [nido.session.engine]
   [nido.session.service]
   [nido.session.services.postgresql :as pg]
   [nido.session.state]
   [nido.session.shared-pg]))

(deftest resolve-pg-mode-handles-explicit-and-alias-and-default
  (is (= :shared   (pg/resolve-pg-mode {:mode :shared})))
  (is (= :isolated (pg/resolve-pg-mode {:mode :isolated})))
  (is (= :clone    (pg/resolve-pg-mode {:mode :clone})))
  ;; back-compat: :clone-from-template true → :clone
  (is (= :clone    (pg/resolve-pg-mode {:clone-from-template true})))
  ;; nothing set → :clone (today's default: a private PGDATA per session)
  (is (= :clone    (pg/resolve-pg-mode {}))))

(deftest shared-start-uses-shared-cluster-and-creates-no-session-pgdata
  (let [calls (atom [])]
    (with-redefs [nido.session.shared-pg/ensure-ready! (fn [proj opts] (swap! calls conj [proj opts]) {:port 6543})
                  nido.session.engine/source-project-root (fn [_] "/x/Code/brian")]
      (let [ctx {:session {:project-name "brian" :instance-id "brian--feat-x"
                           :project-dir "/tmp/wt"}}
            res (nido.session.service/start-service!
                 {:type :postgresql :name :pg :mode :shared
                  :db-name "brian" :db-user "user" :db-password "password"}
                 ctx {})]
        (is (= ["brian"] (map first @calls)) "delegates to shared-pg/ensure-ready!")
        (is (= 6543 (get-in res [:context :port])) "context carries shared port")
        (is (= :shared (get-in res [:state :mode])))
        (is (not (babashka.fs/exists?
                  (nido.session.state/pg-data-dir "brian--feat-x")))))))) ; no per-session PGDATA

(deftest shared-stop-does-not-touch-the-shared-cluster
  (let [downs (atom 0)]
    (with-redefs [nido.session.shared-pg/down! (fn [_] (swap! downs inc))]
      (nido.session.service/stop-service!
       {:type :postgresql :mode :shared}
       {:mode :shared :project-name "brian"})
      (is (zero? @downs) "session down must not stop the shared cluster"))))

(deftest shared-publishes-restricted-app-creds-when-configured
  (let [ready-opts (atom nil)]
    (with-redefs [nido.session.shared-pg/ensure-ready! (fn [_ opts] (reset! ready-opts opts) {:port 6543})
                  nido.session.engine/source-project-root (fn [_] "/x/Code/brian")]
      (let [ctx {:session {:project-name "brian" :instance-id "brian--feat-x"
                           :project-dir "/x/Code/brian/.worktrees/feat/x"}}
            res (nido.session.service/start-service!
                 {:type :postgresql :name :pg :mode :shared
                  :db-name "brian" :db-user "user" :db-password "password"
                  :schema "brian" :app-db-user "brian_app" :app-db-password "app"}
                 ctx {})]
        (is (= "brian_app" (get-in res [:context :db-user])))
        (is (= "app"       (get-in res [:context :db-password])))
        ;; Enforcement-critical: the cluster is prepared as the OWNER, while the
        ;; SESSION connects as the restricted app role. A swap here would both
        ;; drop the guard and make the advance run without DDL rights.
        (is (= "user"      (:owner-user @ready-opts)) "advance/role run as owner, not app role")
        (is (= "brian_app" (:app-user @ready-opts)))
        (is (= "/x/Code/brian" (:source-repo @ready-opts)))))))

(deftest shared-publishes-owner-creds-when-app-user-absent
  (with-redefs [nido.session.shared-pg/ensure-ready! (fn [_ _] {:port 6543})
                nido.session.engine/source-project-root (fn [_] "/x/Code/brian")]
    (let [ctx {:session {:project-name "brian" :instance-id "brian--feat-x"
                         :project-dir "/x/Code/brian/.worktrees/feat/x"}}
          res (nido.session.service/start-service!
               {:type :postgresql :name :pg :mode :shared
                :db-name "brian" :db-user "user" :db-password "password"}
               ctx {})]
      (is (= "user" (get-in res [:context :db-user]))))))
