(ns nido.coordinator.source.slack-events-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [babashka.fs :as fs]
            [nido.platform.core :as core]
            [nido.coordinator.record.clock :as clock]
            [nido.coordinator.record.state :as cstate]
            [nido.coordinator.source.registry :as sources]
            [nido.coordinator.source.state :as sst]
            [nido.coordinator.source.slack-events :as se]
            [nido.slack.client :as client]
            [nido.slack.socket :as socket]))

(use-fixtures :each
  (fn [t] (let [d (fs/create-temp-dir)]
            (with-redefs [core/nido-root (fn [] (str d))]
              (cstate/ensure-dirs!) (t)))))

(def sc {:type :slack-events :project :brian :emoji "owl"})

(def thread
  {"1.1" [{:ts "1.1" :user "U1" :text "export hangs" :thread_ts "1.1"}
          {:ts "1.2" :user "U2" :text "me too" :thread_ts "1.1"}]
   ;; Slack answers a reply's ts with the reply alone; its :thread_ts names the root.
   "1.2" [{:ts "1.2" :user "U2" :text "me too" :thread_ts "1.1"}]})

(defn- stubbed [f]
  (with-redefs [client/conversations-replies (fn [_ ts _] {:messages (get thread ts)})
                client/users-info            (fn [u _] ({"U1" "ann"} u))
                client/chat-permalink        (fn [_ _ _] "https://perma")]
    (f)))

(defn- inbox [& reactions]
  (atom {:reactions (vec reactions) :connection {:state :open :since "t"}}))

(defn- owl [ts] {:channel "C1" :ts ts :user "U9" :reaction "owl"})

(deftest an-owl-on-a-reply-emits-the-whole-thread-keyed-by-its-root
  (stubbed
   (fn []
     (let [out (atom [])]
       (se/poll-once! sc (inbox (owl "1.2")) "tok" #(swap! out conj %))
       (is (= 1 (count @out)))
       (let [p (first @out)]
         (is (= "slack-C1-1.1" (:id p)))
         (is (= "1.2" (:reacted-ts p)))
         (is (true? (:once-per-ref? p)))
         (is (= "ann: export hangs\n\nU2: me too" (:text p)))
         (is (= "https://perma" (:url p))))))))

(deftest other-emoji-and-seen-reactions-emit-nothing
  (stubbed
   (fn []
     (let [out (atom [])
           ib  (inbox (owl "1.1") (assoc (owl "1.1") :reaction "eyes"))]
       (sst/write-state! (sources/config-hash sc)
                         (se/poll-once! sc ib "tok" #(swap! out conj %)))
       (is (= 1 (count @out)))
       ;; Slack redelivers, or a second person owls the same message.
       (swap! ib update :reactions conj (owl "1.1"))
       (se/poll-once! sc ib "tok" #(swap! out conj %))
       (is (= 1 (count @out)) "a reaction already seen emits nothing")))))

(deftest seen-reactions-age-out-after-the-retention
  (stubbed
   (fn []
     (let [out (atom [])
           ib  (inbox (owl "1.1"))]
       (with-redefs [clock/now-iso (fn [] "2026-01-01T00:00:00Z")]
         (sst/write-state! (sources/config-hash sc) (se/poll-once! sc ib "tok" #(swap! out conj %))))
       (swap! ib update :reactions conj (owl "1.1"))
       (with-redefs [clock/now-iso (fn [] "2026-03-01T00:00:00Z")]
         (let [st (se/poll-once! sc ib "tok" #(swap! out conj %))]
           (is (= 2 (count @out)) "re-emitted; the spawn lane's once-per-ref drops the fire")
           (is (= ["C1|1.1"] (keys (:seen st))))))))))

(deftest an-unreadable-thread-stays-queued
  (with-redefs [client/conversations-replies (fn [_ _ _] {:error :rate-limit})]
    (let [out (atom [])
          ib  (inbox (owl "1.1"))
          st  (se/poll-once! sc ib "tok" #(swap! out conj %))]
      (is (empty? @out))
      (is (= [(owl "1.1")] (:reactions @ib)))
      (is (= :thread-unreadable (-> st :last-poll-result :error)))
      (is (not (contains? (:seen st) "C1|1.1"))))))

(deftest a-refused-connection-opens-the-breaker-and-says-why
  (let [ib (atom {:reactions [] :connection {:state :refused :error :auth :since "t0"}})
        st (se/poll-once! sc ib "tok" (fn [_]))]
    (is (= :open (:breaker st)))
    (is (= :auth (-> st :last-poll-result :error)))
    (is (= :refused (-> st :connection :state)))))

(deftest one-instance-per-daemon-holds-the-connection
  (let [connects (atom 0)]
    (with-redefs [socket/connect! (fn [_ _ _] (swap! connects inc) (fn []))]
      (let [a  (se/start-instance! sc (fn [_]) {:token "t" :app-token "x"})
            b  (se/start-instance! (assoc sc :project :nido) (fn [_]) {:token "t" :app-token "x"})]
        (is (= 1 @connects))
        ((:poll! b))
        (let [st (sst/read-state (sources/config-hash (assoc sc :project :nido)))]
          (is (= :another-instance-connected (-> st :connection :error)))
          (is (= :open (:breaker st))))
        ((:stop! a)) ((:stop! b))
        ;; Once the owner stops, the next instance may connect.
        (let [c (se/start-instance! sc (fn [_]) {:token "t" :app-token "x"})]
          (is (= 2 @connects))
          ((:stop! c)))))))

(deftest no-app-token-refuses-without-connecting
  (with-redefs [socket/connect! (fn [_ _ _] (throw (ex-info "must not connect" {})))
                socket/app-token (fn [] nil)]
    (let [h (se/start-instance! sc (fn [_]) {:token "t"})]
      ((:poll! h))
      (is (= :no-app-token (-> (sst/read-state (sources/config-hash sc)) :connection :error)))
      ((:stop! h)))))
