(ns nido.ui.dev-test
  (:require [clojure.test :refer [deftest is]]
            [nido.ui.dev :as dev]))

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

(deftest failed-ws-errors-are-the-failed-slash-keys-with-messages
  ;; Both ws-scoped actions land here: a bring-down! and a gate resolve share the
  ;; "<project>/<ws-id>" key space, and both need their failure read back out.
  (dev/set-app-state! "p/ws1" :failed "connection refused")
  (dev/set-app-state! "p/ws2" :stopping)
  (dev/set-app-state! "p/ws3" :failed "Apply failed: http 400")
  (dev/set-app-state! "plain-instance" :failed "excluded — no slash, never a ws key")
  (try
    (is (= {"p/ws1" "connection refused" "p/ws3" "Apply failed: http 400"}
           (dev/failed-ws-errors)))
    (finally
      (doseq [k ["p/ws1" "p/ws2" "p/ws3" "plain-instance"]] (dev/clear-app-state! k)))))
