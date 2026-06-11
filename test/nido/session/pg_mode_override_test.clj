(ns nido.session.pg-mode-override-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.session.engine :as engine]
   [nido.session.state :as state]))

(deftest override-file-path-is-under-instance-state-dir
  (is (str/ends-with? (state/pg-mode-override-file "brian--feat-x")
                      "/state/brian--feat-x/pg-mode-override.edn")))

(deftest override-roundtrip-write-read-clear
  ;; Redirect instance-state-dir to a temp dir so we never touch ~/.nido.
  (let [tmp (str (fs/create-temp-dir))]
    (with-redefs [state/instance-state-dir (fn [_] tmp)]
      (is (nil? (state/read-pg-mode-override "x")))
      (state/write-pg-mode-override! "x" :isolated)
      (is (= {:mode :isolated} (state/read-pg-mode-override "x")))
      (state/clear-pg-mode-override! "x")
      (is (nil? (state/read-pg-mode-override "x"))))
    (fs/delete-tree tmp)))

(deftest apply-pg-mode-override-sets-mode-on-pg-service-only
  (with-redefs [state/read-pg-mode-override (fn [_] {:mode :isolated})]
    (let [services [{:type :postgresql :name :pg :mode :shared}
                    {:type :process :name :repl}]
          out (#'engine/apply-pg-mode-override services "inst-x")]
      (is (= :isolated (:mode (first out))) "pg service mode overridden")
      (is (= {:type :process :name :repl} (second out)) "other services untouched"))))

(deftest apply-pg-mode-override-is-noop-without-override
  (with-redefs [state/read-pg-mode-override (fn [_] nil)]
    (let [services [{:type :postgresql :name :pg :mode :shared}]]
      (is (= services (#'engine/apply-pg-mode-override services "inst-x"))))))
