(ns nido.coordinator.sources.slack-reaction-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [babashka.fs :as fs]
            [nido.coordinator.clock :as clock]
            [nido.coordinator.state :as cstate]
            [nido.coordinator.sources :as sources]
            [nido.coordinator.sources.state :as sst]
            [nido.coordinator.sources.slack-reaction :as sr]
            [nido.slack.client :as client]))

(def ^:dynamic *tmp* nil)
(use-fixtures :each
  (fn [t] (let [d (fs/create-temp-dir)]
            (with-redefs [cstate/nido-root (fn [] (str d))]
              (cstate/ensure-dirs!) (binding [*tmp* d] (t))))))

(defn- msg [ts text owl?]
  (cond-> {:ts ts :text text :user "U1"} owl? (assoc :reactions [{:name "owl" :count 1}])))

(defn- history-stub [msgs]
  (fn [_ch _tok _opts] {:messages msgs :has_more false :next_cursor nil}))

(def sc {:type :slack-reaction :project :brian :channel "C1" :emoji "owl" :window "3d"})

(deftest cold-start-seeds-and-emits-nothing
  (with-redefs [client/conversations-history (history-stub [(msg "100.0" "bug" true)])
                client/chat-permalink (fn [_ _ _] "u")]
    (let [emitted (atom [])
          h (sr/start-instance! sc (fn [e] (swap! emitted conj e)) {:token "t"})]
      ((:poll! h))
      (is (empty? @emitted) "cold start emits nothing")
      (is (contains? (:seen (sst/read-state (sources/config-hash sc))) "100.0"))
      (is (true? (:cold-started? (sst/read-state (sources/config-hash sc))))))))

(deftest emits-once-for-a-new-owl-then-dedups
  ;; Freeze the clock near the epoch so fixture ts values like "100.0" fall
  ;; inside the 3d window (oldest = now - 3d) and survive the per-poll prune;
  ;; otherwise a fixture ts far below the real wall-clock oldest would be
  ;; pruned out and spuriously re-fire.
  (with-redefs [clock/now-iso (fn [] "1970-01-01T00:10:00Z")
                client/chat-permalink (fn [_ _ _] "u")]
    (let [emitted (atom [])
          h (sr/start-instance! sc (fn [e] (swap! emitted conj e)) {:token "t"})]
      ;; cold-start poll: no owls yet
      (with-redefs [client/conversations-history (history-stub [(msg "100.0" "chit" false)])]
        ((:poll! h)))
      (is (empty? @emitted) "cold start still emits nothing even with a non-owl message")
      ;; a human owls message 100.0
      (with-redefs [client/conversations-history (history-stub [(msg "100.0" "chit" true)])]
        ((:poll! h)))
      (is (= 1 (count @emitted)))
      (is (= :slack-reaction (:type (first @emitted))))
      (is (= "chit" (get-in (first @emitted) [:payload :text])))
      (is (= "100.0" (get-in (first @emitted) [:payload :ts])))
      ;; next poll, same owl still present -> no re-emit
      (with-redefs [client/conversations-history (history-stub [(msg "100.0" "chit" true)])]
        ((:poll! h)))
      (is (= 1 (count @emitted)) "already-actioned owl does not re-fire"))))

(deftest prunes-seen-entries-outside-the-window
  (let [now (atom (java.time.Instant/parse "2026-01-10T00:00:00Z"))]
    (with-redefs [clock/now-iso (fn [] (str @now))
                  client/chat-permalink (fn [_ _ _] "u")]
      ;; cold start with an owl'd message that will soon fall outside the 3d window
      (with-redefs [client/conversations-history (history-stub [(msg "100.0" "old" true)])]
        (let [h (sr/start-instance! sc (fn [_] nil) {:token "t"})]
          ((:poll! h))
          (is (contains? (:seen (sst/read-state (sources/config-hash sc))) "100.0"))
          ;; advance the clock 4 days -- "100.0" is now outside the 3d window
          (swap! now #(.plusSeconds % (* 4 24 60 60)))
          (with-redefs [client/conversations-history (history-stub [])]
            ((:poll! h)))
          (is (not (contains? (:seen (sst/read-state (sources/config-hash sc))) "100.0"))
              "pruned once its ts ages out of the window"))))))

(deftest breaker-opens-on-auth
  (with-redefs [client/conversations-history (fn [_ _ _] {:error :auth})]
    (let [h (sr/start-instance! sc (fn [_] nil) {:token "t"})]
      ((:poll! h))
      (is (= :open (:breaker (sst/read-state (sources/config-hash sc))))))))

(deftest breaker-opens-after-3-server-failures
  (with-redefs [client/conversations-history (fn [_ _ _] {:error :server})]
    (let [h (sr/start-instance! sc (fn [_] nil) {:token "t"})]
      (dotimes [_ 3] ((:poll! h)))
      (is (= :open (:breaker (sst/read-state (sources/config-hash sc))))))))

(deftest rate-limit-and-network-never-trip-breaker
  (doseq [err [:rate-limit :network]]
    (sst/delete-state! (sources/config-hash sc))
    (with-redefs [client/conversations-history (fn [_ _ _] {:error err})]
      (let [h (sr/start-instance! sc (fn [_] nil) {:token "t"})]
        (dotimes [_ 5] ((:poll! h)))
        (is (nil? (:breaker (sst/read-state (sources/config-hash sc))))
            (str err " must not open the breaker"))))))

(deftest error-branches-do-not-modify-seen
  (with-redefs [client/conversations-history (history-stub [(msg "100.0" "bug" true)])
                client/chat-permalink (fn [_ _ _] "u")]
    (let [h (sr/start-instance! sc (fn [_] nil) {:token "t"})]
      ((:poll! h))))
  (let [seen-before (:seen (sst/read-state (sources/config-hash sc)))]
    (with-redefs [client/conversations-history (fn [_ _ _] {:error :server})]
      (let [h (sr/start-instance! sc (fn [_] nil) {:token "t"})]
        ((:poll! h))))
    (is (= seen-before (:seen (sst/read-state (sources/config-hash sc)))))))

(deftest plugin-is-registered
  (sr/register!)
  (is (some? (sources/lookup :slack-reaction))))
