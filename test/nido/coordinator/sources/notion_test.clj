(ns nido.coordinator.sources.notion-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.sources :as sources]
   [nido.coordinator.sources.notion :as notion-src]
   [nido.coordinator.sources.state :as sst]
   [nido.coordinator.state :as cstate]
   [nido.platform.io :as io]
   [nido.notion.client :as client]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
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

(deftest reconcile-mode-re-emits-all-members-every-poll
  ;; With :reconcile? true the source re-emits ALL current members each poll
  ;; (no diff dependence), so eligible/un-triaged tickets re-fire and failed
  ;; triages retry. The coordinator gate drops the already-handled ones.
  (with-tmp
    (fn [tmp]
      (write-views! tmp default-views)
      (let [emitted (atom [])
            sc      {:type :notion-view :project :brian :view :open-bugs
                     :poll "5m" :reconcile? true}]
        (with-redefs [client/resolve-data-source-id (stub-resolve-ds)
                      client/data-source-query
                      (stub-query-result [(stub-page "p1") (stub-page "p2")])]
          (let [handle (notion-src/start-instance!
                         sc (fn [p] (swap! emitted conj p)) {:token "t"})]
            ((:poll! handle))    ; reconcile mode emits even on the first poll
            ((:poll! handle))    ; ...and again — no "emit once on appearance"
            (is (= #{"p1" "p2"} (set (map #(-> % :payload :page-id) @emitted)))
                "every current member emitted")
            (is (= 4 (count @emitted))
                "all members re-emitted each poll (2 polls × 2 members)")))))))

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

(deftest network-errors-do-not-trip-breaker
  ;; Connectivity failures (status 0 / :network) are transient — a sleeping
  ;; laptop or a dropped WiFi must NOT permanently disable the source. The
  ;; breaker stays closed and polling continues; failures are tracked for
  ;; visibility under :network-failures.
  (with-tmp
    (fn [tmp]
      (write-views! tmp default-views)
      (let [calls (atom 0)
            sc    {:type :notion-view :project :brian :view :open-bugs :poll "5m"}]
        (with-redefs [client/resolve-data-source-id (stub-resolve-ds)
                      client/data-source-query
                      (fn [_ds _token _opts]
                        (swap! calls inc)
                        {:status 0 :error :network})]
          (let [handle (notion-src/start-instance! sc (fn [_]) {:token "t"})]
            (dotimes [_ 5] ((:poll! handle)))
            (let [s (sst/read-state (sources/config-hash sc))]
              (is (nil? (:breaker s)) "network errors must not open the breaker")
              (is (= 5 @calls) "polling must continue through connectivity failures")
              (is (= 5 (:network-failures s)) "network failures tracked separately")
              (is (= :network (-> s :last-poll-result :error))))))))))

(deftest open-breaker-probes-and-closes-after-cooldown
  ;; Half-open recovery: a tripped breaker probes once after the cooldown.
  ;; A successful probe closes it — no manual reset needed.
  (with-tmp
    (fn [tmp]
      (write-views! tmp default-views)
      (let [now     (atom (java.time.Instant/parse "2026-01-01T00:00:00Z"))
            outcome (atom {:status 503 :error :server})
            calls   (atom 0)
            sc      {:type :notion-view :project :brian :view :open-bugs :poll "5m"}]
        (with-redefs [clock/now-iso                 (fn [] (str @now))
                      client/resolve-data-source-id (stub-resolve-ds)
                      client/data-source-query
                      (fn [_ds _token _opts] (swap! calls inc) @outcome)]
          (let [handle (notion-src/start-instance! sc (fn [_]) {:token "t"})]
            (dotimes [_ 3] ((:poll! handle)))      ; trip the breaker
            (is (= :open (:breaker (sst/read-state (sources/config-hash sc)))))
            (let [tripped @calls]
              ;; within cooldown — poll is suppressed
              ((:poll! handle))
              (is (= tripped @calls) "poll suppressed during cooldown")
              ;; advance past cooldown; connectivity recovers
              (swap! now #(.plusSeconds % 600))
              (reset! outcome {:status 200 :results [] :has_more false})
              ((:poll! handle))                    ; probe → success
              (is (= (inc tripped) @calls) "probe polled once after cooldown")
              (let [s (sst/read-state (sources/config-hash sc))]
                (is (nil? (:breaker s)) "successful probe closes the breaker")
                (is (zero? (:consecutive-failures s)))))))))))

(deftest failed-probe-rearms-cooldown
  ;; If the half-open probe fails again, the breaker stays open and the
  ;; cooldown is re-armed — we don't fall back to probing every poll.
  (with-tmp
    (fn [tmp]
      (write-views! tmp default-views)
      (let [now   (atom (java.time.Instant/parse "2026-01-01T00:00:00Z"))
            calls (atom 0)
            sc    {:type :notion-view :project :brian :view :open-bugs :poll "5m"}]
        (with-redefs [clock/now-iso                 (fn [] (str @now))
                      client/resolve-data-source-id (stub-resolve-ds)
                      client/data-source-query
                      (fn [_ds _token _opts]
                        (swap! calls inc)
                        {:status 503 :error :server})]
          (let [handle (notion-src/start-instance! sc (fn [_]) {:token "t"})]
            (dotimes [_ 3] ((:poll! handle)))      ; trip
            (let [tripped @calls]
              (swap! now #(.plusSeconds % 600))
              ((:poll! handle))                    ; probe → fails again
              (is (= (inc tripped) @calls) "probe ran once after cooldown")
              (is (= :open (:breaker (sst/read-state (sources/config-hash sc))))
                  "still open after a failed probe")
              ;; immediately after, cooldown re-armed → suppressed again
              ((:poll! handle))
              (is (= (inc tripped) @calls) "failed probe re-armed the cooldown")
              ;; advance again → probes once more
              (swap! now #(.plusSeconds % 600))
              ((:poll! handle))
              (is (= (+ 2 tripped) @calls) "probes again after a second cooldown"))))))))

;; ---------------------------------------------------------------------------
;; Tests — view registry + filter merging (Task 3)
;; ---------------------------------------------------------------------------

(deftest poll-once-uses-view-filter
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
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
      (with-redefs [core/nido-root (constantly (str tmp))]
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

;; ---------------------------------------------------------------------------
;; Tests — per-event :priority-from (Task 4)
;; ---------------------------------------------------------------------------

(deftest poll-once-stamps-priority-from-property
  ;; Two-phase: seed with empty result, then a page appears → emit with :priority from formula prop.
  (with-tmp
    (fn [_tmp]
      (write-views! _tmp {:database "db-1" :views {:v {:filter {}}}})
      (let [emitted (atom [])
            pages   (atom [])
            sc      {:type :notion-view :project :brian :view :v
                     :priority-from {:property "severity-calc"}}]
        (with-redefs [client/resolve-data-source-id (fn [_ _] "ds-1")
                      client/data-source-query
                      (fn [_ds _token _opts]
                        {:status 200 :has_more false :results @pages})]
          (let [handle (notion-src/start-instance!
                         sc
                         (fn [event] (swap! emitted conj event))
                         {:token "token"})]
            ((:poll! handle))   ; seed with empty — no emission
            (reset! pages [{:id "page-1" :url "u"
                            :created_time "t" :last_edited_time "t"
                            :properties {"severity-calc"
                                         {:type "formula"
                                          :formula {:type "number" :number 5}}}}])
            ((:poll! handle))   ; page-1 newly appears → emit
            (is (= 1 (count @emitted)))
            (is (= 5 (-> @emitted first :payload :priority))
                "envelope :priority should come from the Notion formula property")))))))

(deftest poll-once-omits-priority-when-priority-from-missing
  ;; Two-phase: seed empty, page appears → emits without :priority when no :priority-from.
  (with-tmp
    (fn [_tmp]
      (write-views! _tmp {:database "db-1" :views {:v {:filter {}}}})
      (let [emitted (atom [])
            pages   (atom [])
            sc      {:type :notion-view :project :brian :view :v}]
        (with-redefs [client/resolve-data-source-id (fn [_ _] "ds-1")
                      client/data-source-query
                      (fn [_ds _token _opts]
                        {:status 200 :has_more false :results @pages})]
          (let [handle (notion-src/start-instance!
                         sc
                         (fn [event] (swap! emitted conj event))
                         {:token "token"})]
            ((:poll! handle))   ; seed with empty
            (reset! pages [{:id "page-1" :url "u"
                            :created_time "t" :last_edited_time "t"
                            :properties {}}])
            ((:poll! handle))   ; page-1 appears → emit
            (is (= 1 (count @emitted)))
            (is (nil? (-> @emitted first :payload :priority))
                "no :priority-from => no :priority stamped on event")))))))

;; ---------------------------------------------------------------------------
;; Tests — :pages cache in the source snapshot (Task 3)
;; ---------------------------------------------------------------------------

(deftest poll-stores-pages-cache
  (with-tmp
    (fn [tmp]
      (write-views! tmp default-views)
      (let [source-config {:type :notion-view :view :open-bugs :project :brian}
            pages         [(stub-page "p1" {:properties
                                            {"Status"   {:type "status" :status {:name "Not started"}}
                                             "Priority" {:type "select" :select {:name "2 - Should"}}}})]]
        (with-redefs [client/data-source-query (stub-query-result pages)
                      client/resolve-data-source-id (constantly "ds1")]
          (let [new-state (notion-src/poll-once! source-config "tok" (constantly nil))]
            (is (= {:status "Not started" :priority 2 :ball-ids #{} :title nil :br nil}
                   (get-in new-state [:pages "p1"]))
                ":pages carries parsed per-page facts")
            (is (contains? (:last-rows new-state) "p1")
                ":last-rows stays a set of page-ids (unchanged)")))))))
