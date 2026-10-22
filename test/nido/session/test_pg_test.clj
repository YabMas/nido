(ns nido.session.test-pg-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.session.service :as service]
   [nido.session.services.postgresql :as pg]
   [nido.session.test-pg :as test-pg]))

(deftest session-db-name-keeps-sessions-apart
  (testing "ids that slug alike still get different names"
    (is (not= (test-pg/session-db-name "brian_test" "brian--a-b")
              (test-pg/session-db-name "brian_test" "brian--a_b"))))
  (testing "stable for one session"
    (is (= (test-pg/session-db-name "brian_test" "brian--x")
           (test-pg/session-db-name "brian_test" "brian--x"))))
  (testing "a legal unquoted identifier within Postgres's 63-byte limit"
    (let [n (test-pg/session-db-name "brian_test" (apply str "brian--" (repeat 120 "Long-Name")))]
      (is (<= (count n) 63))
      (is (re-matches #"[a-z][a-z0-9_]*" n))
      (is (str/starts-with? n "brian_test_")))))

(defn- pg-available? []
  (try (some? (pg/find-pg-bin-dir)) (catch Exception _ false)))

(defn- psql [port db sql]
  (str/trim (:out (shell {:out :string :err :string}
                         (pg/pg-cmd (pg/find-pg-bin-dir) "psql")
                         "-h" "127.0.0.1" "-p" (str port) "-U" "user" "-d" db "-At" "-c" sql))))

(deftest a-session-gets-its-own-database-in-one-disposable-cluster
  (if-not (pg-available?)
    (println "skipping: no PostgreSQL on this machine")
    (let [home     (str (fs/create-temp-dir {:prefix "nido-test-pg"}))
          baseline (str (fs/path home "V1__baseline.sql"))
          project  "tpgproj"]
      (spit baseline (str "CREATE SCHEMA app;\n"
                          "CREATE TABLE app.flyway_schema_history (installed_rank int primary key,"
                          " version text, description text, type text, script text, checksum int,"
                          " installed_by text, installed_on timestamp default now(),"
                          " execution_time int, success boolean);\n"
                          "CREATE TABLE app.thing (id int);\n"))
      (with-redefs [core/nido-home (constantly home)]
        (try
          (let [def  {:type :test-pg :name :test-pg :db-stem "app_test" :schema "app"
                      :max-connections 37
                      :baseline {:file "V1__baseline.sql" :version "1" :description "baseline"}}
                ctx  (fn [id] {:session {:project-name project :project-dir home :instance-id id}})
                a    (service/start-service! def (ctx "tpgproj--a") {})
                b    (service/start-service! def (ctx "tpgproj--b") {})
                port (get-in a [:context :port])]
            (testing "one cluster, a database per session"
              (is (= port (get-in b [:context :port])))
              (is (not= (get-in a [:context :db-name]) (get-in b [:context :db-name]))))
            (testing "each database is a clone of the template, with the schema on its search path"
              (is (= "0" (psql port (get-in a [:context :db-name]) "select count(*) from thing"))))
            (testing "durability is off and the connection limit is the declared one"
              (is (= "off" (psql port "postgres" "show fsync")))
              (is (= "off" (psql port "postgres" "show full_page_writes")))
              (is (= "off" (psql port "postgres" "show synchronous_commit")))
              (is (= "37" (psql port "postgres" "show max_connections"))))
            (testing "a restart keeps what a session wrote"
              (psql port (get-in a [:context :db-name]) "insert into thing values (1)")
              (service/start-service! def (ctx "tpgproj--a") {})
              (is (= "1" (psql port (get-in a [:context :db-name]) "select count(*) from thing"))))
            (testing "drop-session-db! drops that session's database and no other"
              (test-pg/drop-session-db! project {:db-stem "app_test" :instance-id "tpgproj--a"})
              (is (= "" (psql port "postgres"
                              (str "select 1 from pg_database where datname = '"
                                   (get-in a [:context :db-name]) "'"))))
              (is (= "1" (psql port "postgres"
                               (str "select 1 from pg_database where datname = '"
                                    (get-in b [:context :db-name]) "'"))))))
          (finally
            (test-pg/reset! project)
            (fs/delete-tree home)))))))
