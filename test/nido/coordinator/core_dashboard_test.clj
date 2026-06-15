(ns nido.coordinator.core-dashboard-test
  (:require [clojure.test :refer [deftest is]]
            [nido.coordinator.core :as core]))

(deftest dashboard-config-defaults-on-8800
  (is (= {:enabled? true :port 8800} (core/dashboard-config {}))))

(deftest dashboard-config-respects-overrides
  (is (= {:enabled? true :port 9001} (core/dashboard-config {:dashboard-port 9001})))
  (is (false? (:enabled? (core/dashboard-config {:no-dashboard true})))))
