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

(deftest a-dashboard-start-is-probed-on-the-workstreams-runtime
  ;; A stopped session the workstream holds comes up on the workstream's runtime; the probe
  ;; after the start and the state the board reads must name that runtime, not its own.
  (let [up (atom nil)]
    (with-redefs [nido.session.lifecycle/session-coords
                  (fn [s o] {:wt-path (str "/wt/" s) :instance-id (str "p--" (or (:runtime-key o) s))})
                  nido.session.engine/read-profile-for-session     (constantly nil)
                  nido.coordinator.work/ensure-open!                (fn [& _] nil)
                  nido.session.lifecycle/up!                        (fn [_ o] (reset! up o))
                  dev/app-port-for-instance                         (fn [iid] (when (= "p--ws-1" iid) 4100))
                  nido.platform.process/tcp-open?                   (constantly true)]
      @(dev/dev-action! "p" "ws-1" "feat" "start"))
    (try
      (is (= "ws-1" (:runtime-key @up)))
      (is (nil? (dev/current-app-state "p--ws-1")) "the start is seen to have opened its port")
      (is (nil? (dev/current-app-state "p--feat")) "no failure is left under the session's own key")
      (finally (doseq [k ["p--ws-1" "p--feat"]] (dev/clear-app-state! k))))))

(deftest a-workstreams-sessions-are-read-on-its-runtime
  (let [asked (atom [])]
    (with-redefs [nido.session.lifecycle/session-coords
                  (fn [s o] (swap! asked conj (:runtime-key o)) {:wt-path (str "/wt/" s) :instance-id "x"})
                  nido.session.state/read-registry (constantly {})]
      (dev/ws-session-dev-states "p" {:ws-id "ws-1" :sessions [{:name "a"} {:name "b"}]}))
    (is (= ["ws-1" "ws-1"] @asked) "a stopped session's state is read where its start would put it")))
