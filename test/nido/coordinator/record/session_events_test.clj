(ns nido.coordinator.record.session-events-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.session-events :as events]
   [nido.coordinator.record.state :as cstate]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root (constantly (str tmp))
                       clock/now-iso  (constantly "2026-10-16T09:00:00Z")]
           (f tmp))
         (finally (fs/delete-tree tmp)))))

(deftest a-session-without-a-log-has-no-events
  (with-tmp
    (fn [_]
      (is (= {:events [] :cursor 0} (events/events-since "never" nil))))))

(deftest events-come-back-in-order-and-the-cursor-reads-on-from-there
  (with-tmp
    (fn [_]
      (is (true? (events/append! "s" {:event :session-start})))
      (events/append! "s" {:event :prompt :prompt "a"})
      (let [{all :events c1 :cursor} (events/events-since "s" nil)]
        (is (= [:session-start :prompt] (map :event all)))
        (is (= "2026-10-16T09:00:00Z" (:at (first all))) "stamped when it carried no time")
        (events/append! "s" {:event :stop})
        (is (= {:events [{:at "2026-10-16T09:00:00Z" :event :stop}] :cursor 3}
               (events/events-since "s" c1))
            "only what came after the cursor")))))

(deftest a-line-that-does-not-parse-is-skipped-and-still-counted
  (with-tmp
    (fn [_]
      (events/append! "s" {:event :prompt})
      (spit (cstate/session-events-path "s") "{:not closed\n" :append true)
      (events/append! "s" {:event :stop})
      (let [{evs :events c :cursor} (events/events-since "s" nil)]
        (is (= [:prompt :stop] (map :event evs)))
        (is (= 3 c) "the cursor never points back into the bad line")))))

(deftest append-never-throws
  (testing "an unwritable log answers false, and the caller — a hook — goes on"
    (with-redefs [cstate/session-events-path (fn [_] "/dev/null/cannot/exist.edn")]
      (is (false? (events/append! "s" {:event :stop}))))))

(deftest a-line-still-being-appended-is-not-counted
  (with-tmp
    (fn [_]
      (events/append! "s" {:event :prompt})
      (spit (cstate/session-events-path "s") "{:event :stop" :append true)
      (let [{evs :events c :cursor} (events/events-since "s" nil)]
        (is (= [:prompt] (map :event evs)))
        (is (= 1 c) "the cursor stops before the unfinished line")
        (spit (cstate/session-events-path "s") "}\n" :append true)
        (is (= [:stop] (map :event (:events (events/events-since "s" c))))
            "read whole once its append completes")))))

(deftest an-append-after-a-torn-line-lands-on-its-own
  (with-tmp
    (fn [_]
      (events/append! "s" {:event :prompt})
      (spit (cstate/session-events-path "s") "{:event :st" :append true)
      (events/append! "s" {:event :stop})
      (is (= [:prompt :stop] (map :event (:events (events/events-since "s" nil))))))))
