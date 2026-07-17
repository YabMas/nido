(ns nido.session.dev-test
  (:require [clojure.test :refer [deftest is]]
            [nido.session.dev :as dev]))

(deftest dev-state-running-when-probe-open
  (is (= {:state :running :url "http://x"}
         (dev/dev-state-for "/wt" "iid"
                            {"/wt" {:app-port 4100 :url "http://x"}}
                            (constantly true) (constantly nil)))))

(deftest dev-state-down-when-no-port
  (is (= {:state :down}
         (dev/dev-state-for "/wt" "iid" {} (constantly false) (constantly nil)))))

(deftest dev-state-pending-from-app-state
  (is (= {:state :starting :error-msg nil}
         (dev/dev-state-for "/wt" "iid" {} (constantly false)
                            (constantly {:state :starting})))))

(deftest pending-winddown-keys-are-the-stopping-slash-keys
  (dev/set-app-state! "p/ws1" :stopping)
  (dev/set-app-state! "p/ws2" :resolving)
  (dev/set-app-state! "plain-instance" :stopping)
  (try
    (is (= #{"p/ws1"} (dev/pending-winddown-keys)))
    (finally
      (doseq [k ["p/ws1" "p/ws2" "plain-instance"]] (dev/clear-app-state! k)))))
