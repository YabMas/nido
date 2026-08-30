(ns nido.coordinator.source.slack-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.source.registry :as sources]
   [nido.coordinator.source.slack :as slack-src]
   [nido.coordinator.source.state :as sst]
   [nido.coordinator.record.state :as cstate]
   [nido.slack.client :as client]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(def ^:private sc {:type :slack-channel :project :brian :channel "C123" :poll "2m"})

(defn- msg [ts text & {:keys [subtype]}]
  (cond-> {:type "message" :ts ts :user "U1" :text text}
    subtype (assoc :subtype subtype)))

(defn- stub-history
  "Returns a one-page conversations-history stub (newest-first)."
  [messages]
  (fn [_chan _tok _opts] {:messages messages :has_more false :next_cursor nil}))

(deftest cold-start-seeds-watermark-and-emits-nothing
  (with-tmp
    (fn [_]
      (let [emitted (atom [])]
        (with-redefs [client/conversations-history (stub-history [(msg "2.0" "newest") (msg "1.0" "older")])
                      client/chat-permalink         (fn [_ _ _] "u")]
          (let [handle (slack-src/start-instance! sc (fn [p] (swap! emitted conj p)) {:token "t"})]
            ((:poll! handle))
            (is (empty? @emitted) "cold start must not replay history")
            (is (= "2.0" (:last-seen-ts (sst/read-state (sources/config-hash sc)))))))))))

(deftest second-poll-emits-new-messages-oldest-first
  (with-tmp
    (fn [_]
      (let [emitted (atom [])]
        (with-redefs [client/chat-permalink (fn [_ _ _] "u")]
          (with-redefs [client/conversations-history (stub-history [(msg "2.0" "seed")])]
            (let [handle (slack-src/start-instance! sc (fn [p] (swap! emitted conj p)) {:token "t"})]
              ((:poll! handle))  ; seed at 2.0
              (is (empty? @emitted))
              (with-redefs [client/conversations-history (stub-history [(msg "4.0" "b") (msg "3.0" "a")])]
                ((:poll! handle))
                (is (= 2 (count @emitted)))
                (is (= ["a" "b"] (map #(get-in % [:payload :text]) @emitted))
                    "emitted oldest-first")
                (is (= "4.0" (:last-seen-ts (sst/read-state (sources/config-hash sc)))))))))))))

(deftest does-not-re-emit-already-seen-messages
  (with-tmp
    (fn [_]
      (let [emitted (atom [])]
        (with-redefs [client/chat-permalink (fn [_ _ _] "u")
                      client/conversations-history (stub-history [(msg "1.0" "seed")])]
          (let [handle (slack-src/start-instance! sc (fn [p] (swap! emitted conj p)) {:token "t"})]
            ((:poll! handle))            ; seed at 1.0
            ((:poll! handle))            ; same single message -> oldest=1.0 exclusive -> none
            (is (empty? @emitted))))))))

(deftest skips-configured-subtypes
  (with-tmp
    (fn [_]
      (let [emitted (atom [])
            sc'     (assoc sc :subtypes-skip #{"channel_join"})]
        (with-redefs [client/chat-permalink (fn [_ _ _] "u")]
          (with-redefs [client/conversations-history (stub-history [(msg "1.0" "seed")])]
            (let [handle (slack-src/start-instance! sc' (fn [p] (swap! emitted conj p)) {:token "t"})]
              ((:poll! handle))
              (with-redefs [client/conversations-history
                            (stub-history [(msg "3.0" "real")
                                           (msg "2.5" "joined" :subtype "channel_join")])]
                ((:poll! handle))
                (is (= 1 (count @emitted)))
                (is (= "real" (get-in (first @emitted) [:payload :text])))))))))))

(deftest breaker-opens-immediately-on-auth-error
  (with-tmp
    (fn [_]
      (with-redefs [client/conversations-history (fn [_ _ _] {:error :auth})]
        (let [handle (slack-src/start-instance! sc (fn [_]) {:token "t"})]
          ((:poll! handle))
          (is (= :open (:breaker (sst/read-state (sources/config-hash sc))))))))))

(deftest breaker-opens-after-3-server-failures
  (with-tmp
    (fn [_]
      (with-redefs [client/conversations-history (fn [_ _ _] {:error :server})]
        (let [handle (slack-src/start-instance! sc (fn [_]) {:token "t"})]
          (dotimes [_ 3] ((:poll! handle)))
          (is (= :open (:breaker (sst/read-state (sources/config-hash sc))))))))))

(deftest rate-limit-and-network-never-trip-breaker
  (with-tmp
    (fn [_]
      (doseq [err [:rate-limit :network]]
        (sst/delete-state! (sources/config-hash sc))
        (with-redefs [client/conversations-history (fn [_ _ _] {:error err})]
          (let [handle (slack-src/start-instance! sc (fn [_]) {:token "t"})]
            (dotimes [_ 5] ((:poll! handle)))
            (is (nil? (:breaker (sst/read-state (sources/config-hash sc))))
                (str err " must not open the breaker"))))))))

(deftest open-breaker-probes-and-closes-after-cooldown
  (with-tmp
    (fn [_]
      (let [now     (atom (java.time.Instant/parse "2026-01-01T00:00:00Z"))
            outcome (atom {:error :server})
            calls   (atom 0)]
        (with-redefs [clock/now-iso                (fn [] (str @now))
                      client/chat-permalink        (fn [_ _ _] "u")
                      client/conversations-history (fn [_ _ _] (swap! calls inc) @outcome)]
          (let [handle (slack-src/start-instance! sc (fn [_]) {:token "t"})]
            (dotimes [_ 3] ((:poll! handle)))            ; trip the breaker
            (is (= :open (:breaker (sst/read-state (sources/config-hash sc)))))
            (let [tripped @calls]
              ((:poll! handle))                          ; within cooldown — suppressed
              (is (= tripped @calls) "poll suppressed during cooldown")
              (swap! now #(.plusSeconds % 600))          ; advance past the 5min cooldown
              (reset! outcome {:messages [] :has_more false :next_cursor nil})
              ((:poll! handle))                          ; probe → success
              (is (= (inc tripped) @calls) "probe polled once after cooldown")
              (let [s (sst/read-state (sources/config-hash sc))]
                (is (nil? (:breaker s)) "successful probe closes the breaker")
                (is (zero? (:consecutive-failures s)))))))))))

(deftest plugin-is-registered
  (slack-src/register!)
  (is (some? (sources/lookup :slack-channel))))
