(ns nido.coordinator.sources.notion-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.sources :as sources]
   [nido.coordinator.sources.notion :as notion-src]
   [nido.coordinator.sources.state :as sst]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]
   [nido.notion.client :as client]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(defn- write-views! [tmp views-edn]
  (fs/create-dirs (str (fs/path tmp "projects" "brian")))
  (io/write-edn! (str (fs/path tmp "projects" "brian" "notion-views.edn"))
                 views-edn))

(def ^:private default-views
  {:database "db1"
   :views    {:open-bugs {:filter {:property "Type" :select {:equals "bug"}}}}})

(defn- stub-page
  ([id] (stub-page id {}))
  ([id extra]
   (merge {:id id :url (str "u" id) :created_time "t" :last_edited_time "t" :properties {}}
          extra)))

(defn- stub-query-result
  "Returns a stub fn for client/data-source-query that returns the given pages."
  [pages]
  (fn [_ds _token _opts]
    {:status 200 :results pages :has_more false}))

(defn- stub-resolve-ds
  "Returns a stub fn for client/resolve-data-source-id."
  ([] (stub-resolve-ds "ds-1"))
  ([id] (fn [_db _token] id)))

;; ---------------------------------------------------------------------------
;; Tests — snapshot / emission behaviour (rewritten using with-redefs)
;; ---------------------------------------------------------------------------

(deftest first-poll-seeds-snapshot-and-emits-nothing
  (with-tmp
    (fn [tmp]
      (write-views! tmp default-views)
      (let [emitted (atom [])
            sc      {:type :notion-view :project :brian :view :open-bugs :poll "5m"}]
        (with-redefs [client/resolve-data-source-id (stub-resolve-ds)
                      client/data-source-query
                      (stub-query-result [(stub-page "p1")])]
          (let [handle (notion-src/start-instance!
                         sc
                         (fn [payload] (swap! emitted conj payload))
                         {:token "t"})]
            ((:poll! handle))
            (is (empty? @emitted))
            (let [s (sst/read-state (sources/config-hash sc))]
              (is (= #{"p1"} (:last-rows s))))))))))

(deftest second-poll-emits-additions
  (with-tmp
    (fn [tmp]
      (write-views! tmp default-views)
      (let [emitted      (atom [])
            query-result (atom [(stub-page "p1")])
            sc           {:type :notion-view :project :brian :view :open-bugs :poll "5m"}]
        (with-redefs [client/resolve-data-source-id (stub-resolve-ds)
                      client/data-source-query
                      (fn [_ds _token _opts]
                        {:status 200 :results @query-result :has_more false})]
          (let [handle (notion-src/start-instance!
                         sc
                         (fn [payload] (swap! emitted conj payload))
                         {:token "t"})]
            ((:poll! handle))    ; seeds
            (reset! query-result [(stub-page "p1") (stub-page "p2")])
            ((:poll! handle))
            (is (= 1 (count @emitted)))
            (is (= "p2" (-> @emitted first :payload :page-id)))))))))

(deftest emits-once-not-twice-on-repeated-poll
  (with-tmp
    (fn [tmp]
      (write-views! tmp default-views)
      (let [emitted (atom [])
            sc      {:type :notion-view :project :brian :view :open-bugs :poll "5m"}]
        (with-redefs [client/resolve-data-source-id (stub-resolve-ds)
                      client/data-source-query
                      (stub-query-result [(stub-page "p1") (stub-page "p2")])]
          (let [handle (notion-src/start-instance!
                         sc
                         (fn [p] (swap! emitted conj p))
                         {:token "t"})]
            ((:poll! handle))    ; seeds
            ((:poll! handle))    ; no additions
            ((:poll! handle))    ; no additions
            (is (empty? @emitted))))))))

(deftest row-leaves-and-returns-emits-again
  (with-tmp
    (fn [tmp]
      (write-views! tmp default-views)
      (let [emitted (atom [])
            qr      (atom [(stub-page "p1")])
            sc      {:type :notion-view :project :brian :view :open-bugs :poll "5m"}]
        (with-redefs [client/resolve-data-source-id (stub-resolve-ds)
                      client/data-source-query
                      (fn [_ds _token _opts]
                        {:status 200 :results @qr :has_more false})]
          (let [handle (notion-src/start-instance!
                         sc
                         (fn [p] (swap! emitted conj p))
                         {:token "t"})]
            ((:poll! handle))    ; seeds with p1
            (reset! qr [])
            ((:poll! handle))    ; p1 left (snapshot becomes empty set)
            (reset! qr [(stub-page "p1")])
            ((:poll! handle))    ; p1 returned — emit
            (is (= 1 (count @emitted)))
            (is (= "p1" (-> @emitted first :payload :page-id)))))))))

(deftest breaker-opens-after-3-consecutive-failures
  (with-tmp
    (fn [tmp]
      (write-views! tmp default-views)
      (let [emitted (atom [])
            sc      {:type :notion-view :project :brian :view :open-bugs :poll "5m"}]
        (with-redefs [client/resolve-data-source-id (stub-resolve-ds)
                      client/data-source-query
                      (fn [_ds _token _opts]
                        {:status 503 :error :server})]
          (let [handle (notion-src/start-instance!
                         sc
                         (fn [p] (swap! emitted conj p))
                         {:token "t"})]
            (dotimes [_ 3] ((:poll! handle)))
            (let [s (sst/read-state (sources/config-hash sc))]
              (is (= 3 (:consecutive-failures s)))
              (is (= :open (:breaker s))))))))))

(deftest breaker-opens-immediately-on-401
  (with-tmp
    (fn [tmp]
      (write-views! tmp default-views)
      (let [sc {:type :notion-view :project :brian :view :open-bugs :poll "5m"}]
        (with-redefs [client/resolve-data-source-id (stub-resolve-ds)
                      client/data-source-query
                      (fn [_ds _token _opts]
                        {:status 401 :error :auth})]
          (let [handle (notion-src/start-instance!
                         sc (fn [_]) {:token "t"})]
            ((:poll! handle))
            (let [s (sst/read-state (sources/config-hash sc))]
              (is (= :open (:breaker s)))
              (is (= :auth (-> s :last-poll-result :error))))))))))

(deftest open-breaker-suppresses-polling
  (with-tmp
    (fn [tmp]
      (write-views! tmp default-views)
      (let [calls (atom 0)
            sc    {:type :notion-view :project :brian :view :open-bugs :poll "5m"}]
        (with-redefs [client/resolve-data-source-id (stub-resolve-ds)
                      client/data-source-query
                      (fn [_ds _token _opts]
                        (swap! calls inc)
                        {:status 503 :error :server})]
          (let [handle (notion-src/start-instance!
                         sc (fn [_]) {:token "t"})]
            ;; trip the breaker
            (dotimes [_ 3] ((:poll! handle)))
            (let [trip-count @calls]
              ;; subsequent polls do nothing
              ((:poll! handle))
              ((:poll! handle))
              (is (= trip-count @calls)))))))))

(deftest success-after-failures-clears-counter
  (with-tmp
    (fn [tmp]
      (write-views! tmp default-views)
      (let [outcome (atom {:status 503 :error :server})
            sc      {:type :notion-view :project :brian :view :open-bugs :poll "5m"}]
        (with-redefs [client/resolve-data-source-id (stub-resolve-ds)
                      client/data-source-query
                      (fn [_ds _token _opts] @outcome)]
          (let [handle (notion-src/start-instance!
                         sc (fn [_]) {:token "t"})]
            ((:poll! handle))    ; fail
            ((:poll! handle))    ; fail
            (reset! outcome {:status 200 :results [] :has_more false})
            ((:poll! handle))    ; ok
            (let [s (sst/read-state (sources/config-hash sc))]
              (is (zero? (:consecutive-failures s)))
              (is (nil? (:breaker s))))))))))

;; ---------------------------------------------------------------------------
;; Tests — view registry + filter merging (Task 3)
;; ---------------------------------------------------------------------------

(deftest poll-once-uses-view-filter
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (fs/create-dirs (str (fs/path tmp "projects" "brian")))
        (io/write-edn! (str (fs/path tmp "projects" "brian" "notion-views.edn"))
                       {:database "db-1"
                        :views    {:new-reports
                                   {:filter {:property "Status"
                                             :status   {:equals "Needs verification"}}}}})
        (let [captured-filter (atom nil)
              captured-ds     (atom nil)]
          (with-redefs [client/resolve-data-source-id (fn [_db _token] "ds-1")
                        client/data-source-query
                        (fn [ds _token opts]
                          (reset! captured-ds ds)
                          (reset! captured-filter (:filter opts))
                          {:status 200 :results [] :has_more false})]
            (notion-src/poll-once!
              {:project :brian :view :new-reports} "token" (fn [_]))
            (is (= "ds-1" @captured-ds))
            (is (= {:property "Status" :status {:equals "Needs verification"}}
                   @captured-filter)))))
      (finally (fs/delete-tree tmp)))))

(deftest poll-once-merges-additional-filter
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (fs/create-dirs (str (fs/path tmp "projects" "brian")))
        (io/write-edn! (str (fs/path tmp "projects" "brian" "notion-views.edn"))
                       {:database "db-1"
                        :views    {:bugs {:filter {:property "Type"
                                                   :select   {:equals "bug"}}}}})
        (let [captured-filter (atom nil)]
          (with-redefs [client/resolve-data-source-id (fn [_ _] "ds-1")
                        client/data-source-query
                        (fn [_ds _token opts]
                          (reset! captured-filter (:filter opts))
                          {:status 200 :results [] :has_more false})]
            (notion-src/poll-once!
              {:project           :brian
               :view              :bugs
               :additional-filter {:property "Effort" :select {:is_empty true}}}
              "token" (fn [_]))
            (is (= {:and [{:property "Type"   :select {:equals "bug"}}
                          {:property "Effort" :select {:is_empty true}}]}
                   @captured-filter)))))
      (finally (fs/delete-tree tmp)))))
