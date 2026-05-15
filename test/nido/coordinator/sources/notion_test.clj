(ns nido.coordinator.sources.notion-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.sources :as sources]
   [nido.coordinator.sources.notion :as nsource]
   [nido.coordinator.sources.state :as sst]
   [nido.coordinator.state :as cstate]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(defn- stub-query [results]
  (fn [_db _token] {:status 200 :results results :has_more false}))

(deftest first-poll-seeds-snapshot-and-emits-nothing
  (with-tmp
    (fn [_]
      (let [emitted (atom [])
            handle  (nsource/start-instance!
                      {:database "db1" :poll "5m"}
                      (fn [payload] (swap! emitted conj payload))
                      {:token "t"
                       :query (stub-query [{:id "p1" :url "u1" :created_time "t"
                                            :last_edited_time "t" :properties {}}])})]
        ((:poll! handle))
        (is (empty? @emitted))
        (let [s (sst/read-state (sources/config-hash {:database "db1" :poll "5m"}))]
          (is (= #{"p1"} (:last-rows s))))))))

(deftest second-poll-emits-additions
  (with-tmp
    (fn [_]
      (let [emitted (atom [])
            query-result (atom [{:id "p1" :url "u1" :created_time "t" :last_edited_time "t" :properties {}}])
            handle  (nsource/start-instance!
                      {:database "db1" :poll "5m"}
                      (fn [payload] (swap! emitted conj payload))
                      {:token "t"
                       :query (fn [_ _] {:status 200 :results @query-result :has_more false})})]
        ((:poll! handle))                     ; seeds
        (reset! query-result [{:id "p1" :url "u1" :created_time "t" :last_edited_time "t" :properties {}}
                              {:id "p2" :url "u2" :created_time "t" :last_edited_time "t" :properties {}}])
        ((:poll! handle))
        (is (= 1 (count @emitted)))
        (is (= "p2" (-> @emitted first :page-id)))))))

(deftest emits-once-not-twice-on-repeated-poll
  (with-tmp
    (fn [_]
      (let [emitted (atom [])
            handle  (nsource/start-instance!
                      {:database "db1" :poll "5m"}
                      (fn [p] (swap! emitted conj p))
                      {:token "t"
                       :query (stub-query [{:id "p1" :url "u" :created_time "t" :last_edited_time "t" :properties {}}
                                           {:id "p2" :url "u" :created_time "t" :last_edited_time "t" :properties {}}])})]
        ((:poll! handle))   ; seeds
        ((:poll! handle))   ; no additions
        ((:poll! handle))   ; no additions
        (is (empty? @emitted))))))

(deftest row-leaves-and-returns-emits-again
  (with-tmp
    (fn [_]
      (let [emitted (atom [])
            qr      (atom [{:id "p1" :url "u" :created_time "t" :last_edited_time "t" :properties {}}])
            handle  (nsource/start-instance!
                      {:database "db1" :poll "5m"}
                      (fn [p] (swap! emitted conj p))
                      {:token "t"
                       :query (fn [_ _] {:status 200 :results @qr :has_more false})})]
        ((:poll! handle))                ; seeds with p1
        (reset! qr [])
        ((:poll! handle))                ; p1 left (snapshot becomes empty set)
        (reset! qr [{:id "p1" :url "u" :created_time "t" :last_edited_time "t" :properties {}}])
        ((:poll! handle))                ; p1 returned — emit
        (is (= 1 (count @emitted)))
        (is (= "p1" (-> @emitted first :page-id)))))))

(deftest breaker-opens-after-3-consecutive-failures
  (with-tmp
    (fn [_]
      (let [emitted (atom [])
            handle  (nsource/start-instance!
                      {:database "db1" :poll "5m"}
                      (fn [p] (swap! emitted conj p))
                      {:token "t"
                       :query (fn [_ _] {:status 503 :error :server})})]
        (dotimes [_ 3] ((:poll! handle)))
        (let [s (sst/read-state (sources/config-hash {:database "db1" :poll "5m"}))]
          (is (= 3 (:consecutive-failures s)))
          (is (= :open (:breaker s))))))))

(deftest breaker-opens-immediately-on-401
  (with-tmp
    (fn [_]
      (let [handle (nsource/start-instance!
                     {:database "db1" :poll "5m"}
                     (fn [_])
                     {:token "t"
                      :query (fn [_ _] {:status 401 :error :auth})})]
        ((:poll! handle))
        (let [s (sst/read-state (sources/config-hash {:database "db1" :poll "5m"}))]
          (is (= :open (:breaker s)))
          (is (= :auth (-> s :last-poll-result :error))))))))

(deftest open-breaker-suppresses-polling
  (with-tmp
    (fn [_]
      (let [calls   (atom 0)
            qfn     (fn [_ _] (swap! calls inc) {:status 503 :error :server})
            handle  (nsource/start-instance!
                      {:database "db1" :poll "5m"}
                      (fn [_])
                      {:token "t" :query qfn})]
        ;; trip the breaker
        (dotimes [_ 3] ((:poll! handle)))
        (let [trip-count @calls]
          ;; subsequent polls do nothing
          ((:poll! handle))
          ((:poll! handle))
          (is (= trip-count @calls)))))))

(deftest success-after-failures-clears-counter
  (with-tmp
    (fn [_]
      (let [outcome  (atom {:status 503 :error :server})
            qfn      (fn [_ _] @outcome)
            handle   (nsource/start-instance!
                       {:database "db1" :poll "5m"}
                       (fn [_])
                       {:token "t" :query qfn})]
        ((:poll! handle))                ; fail
        ((:poll! handle))                ; fail
        (reset! outcome {:status 200 :results [] :has_more false})
        ((:poll! handle))                ; ok
        (let [s (sst/read-state (sources/config-hash {:database "db1" :poll "5m"}))]
          (is (zero? (:consecutive-failures s)))
          (is (nil? (:breaker s))))))))
