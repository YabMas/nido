(ns nido.session.services.postgresql-mode-test
  (:require
   [babashka.fs]
   [clojure.test :refer [deftest is]]
   [nido.session.service]
   [nido.session.services.postgresql :as pg]
   [nido.session.state]
   [nido.shared-pg]))

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
    (with-redefs [nido.shared-pg/ensure-up! (fn [proj] (swap! calls conj proj) {:port 6543})]
      (let [ctx {:session {:project-name "brian" :instance-id "brian--feat-x"
                           :project-dir "/tmp/wt"}}
            res (nido.session.service/start-service!
                 {:type :postgresql :name :pg :mode :shared
                  :db-name "brian" :db-user "user" :db-password "password"}
                 ctx {})]
        (is (= ["brian"] @calls) "delegates to shared-pg/ensure-up!")
        (is (= 6543 (get-in res [:context :port])) "context carries shared port")
        (is (= :shared (get-in res [:state :mode])))
        (is (not (babashka.fs/exists?
                  (nido.session.state/pg-data-dir "brian--feat-x")))))))) ; no per-session PGDATA

(deftest shared-stop-does-not-touch-the-shared-cluster
  (let [downs (atom 0)]
    (with-redefs [nido.shared-pg/down! (fn [_] (swap! downs inc))]
      (nido.session.service/stop-service!
       {:type :postgresql :mode :shared}
       {:mode :shared :project-name "brian"})
      (is (zero? @downs) "session down must not stop the shared cluster"))))
