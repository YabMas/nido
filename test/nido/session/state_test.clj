(ns nido.session.state-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.session.state :as state]))

(deftest shared-pg-paths-are-under-project-shared-dir
  (let [proj "brian"]
    (is (str/ends-with? (state/project-shared-dir proj) "/shared/brian"))
    (is (str/ends-with? (state/shared-pg-data-dir proj) "/shared/brian/pg-data"))
    (is (str/ends-with? (state/shared-log-file proj) "/shared/brian/pg.log"))
    (is (str/ends-with? (state/shared-meta-file proj) "/shared/brian/shared.edn"))
    (is (str/ends-with? (state/shared-lock-file proj) "/shared/brian/shared.lock"))))
