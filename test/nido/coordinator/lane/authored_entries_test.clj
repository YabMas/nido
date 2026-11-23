(ns nido.coordinator.lane.authored-entries-test
  "Every status decision a lane or a person makes reaches the ledger as an entry, and the backfill
   records what the records held before they did."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.coordinator.lane.findings :as findings]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.standing :as standing]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.tickets :as tickets]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.source.notion :as notion-src]
   [nido.coordinator.source.queue :as queue]
   [nido.notion.client :as notion-client]
   [tasks.nido-workstream :as t]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f))
         (finally (fs/delete-tree tmp)))))

(defn- kinds [id] (mapv :kind (:entries (ws/read-ws :brian id))))

(deftest resolving-findings-records-the-resolution
  (with-tmp
    (fn []
      (with-redefs [queue/enqueue! (fn [_] "/q/x.edn")]
        (let [id (:id (ws/create! :brian {:stage :in-progress}))]
          (ws/close! :brian id :done)
          (findings/file! :brian id {:items [{:summary "a" :severity :tweak}
                                             {:summary "b" :severity :tweak}]})
          (let [item (first (sort (standing/open-findings :brian id)))]
            (findings/resolve! :brian id [item "unknown"] "PR #9")
            (is (= {:format :findings-resolved :round 1 :items [item] :by "PR #9"}
                   (select-keys (last (ws/entries-of :brian id :findings-resolved))
                                [:format :round :items :by])))
            (testing "resolving nothing records nothing"
              (findings/resolve! :brian id ["unknown"] "PR #9")
              (is (= 1 (count (ws/entries-of :brian id :findings-resolved)))))))))))

(deftest a-notion-status-change-is-recorded-once
  (with-tmp
    (fn []
      (let [id    (:id (ws/create! :brian {:stage :in-progress
                                           :external-refs [{:adapter :notion :id "BR-1" :page-id "p1"}]}))
            prior {"p1" {:status "In progress" :br "BR-1"}}
            now   {"p1" {:status "Review" :br "BR-1"}}]
        (is (= [id] (notion-src/record-status-changes! :brian prior))
            "the workstream records no status yet — a page that gained it after a poll")
        (is (= [] (notion-src/record-status-changes! :brian prior)) "no change, nothing recorded")
        (is (= [id] (notion-src/record-status-changes! :brian now)))
        (is (= "Review" (:status (last (ws/entries-of :brian id :notion-status)))))
        (testing "a second view reporting the same change records nothing"
          (is (= [] (notion-src/record-status-changes! :brian now))))
        (testing "a view whose status is unchanged still records a change another view moved past"
          (is (= [id] (notion-src/record-status-changes! :brian {"p1" {:status "Done" :br "BR-1"}})))
          (is (= [id] (notion-src/record-status-changes! :brian now)))
          (is (= ["In progress" "Review" "Done" "Review"]
                 (mapv :status (ws/entries-of :brian id :notion-status)))))))))

(deftest a-page-leaving-a-status-filtered-view-has-its-new-status-recorded
  (with-tmp
    (fn []
      (let [id    (:id (ws/create! :brian {:stage :in-progress
                                           :external-refs [{:adapter :notion :id "BR-1" :page-id "p1"}]}))
            pages (atom {"p1" {:status "In progress" :br "BR-1"} "p2" {:status "In progress" :br "BR-2"}})
            fetch (atom {:properties {:Status {:type "status" :status {:name "Review"}}}})
            poll! (:poll! (notion-src/start-instance! {:type :notion-view :project :brian :view :v}
                                                      (fn [_]) {:token "t"}))]
        (with-redefs [notion-src/poll-once!  (fn [_ _ _] {:last-poll-result :ok :pages @pages})
                      notion-client/retrieve-page (fn [_ _] @fetch)]
          (poll!)
          (is (= ["In progress"] (mapv :status (ws/entries-of :brian id :notion-status))) "seeded")
          (reset! pages {})
          (reset! fetch {:error :network})
          (binding [*err* (java.io.StringWriter.)] (poll!))
          (is (= 1 (count (ws/entries-of :brian id :notion-status))) "a failed fetch records nothing")
          (reset! fetch {:properties {:Status {:type "status" :status {:name "Review"}}}})
          (poll!)
          (is (= ["In progress" "Review"] (mapv :status (ws/entries-of :brian id :notion-status)))
              "the page that left the view is fetched, and its new status recorded")
          (poll!)
          (is (= 2 (count (ws/entries-of :brian id :notion-status))) "and only once")
          (reset! fetch {:properties {:Status {:type "status" :status {:name "Done"}}}})
          (poll!)
          (is (= ["In progress" "Review" "Done"] (mapv :status (ws/entries-of :brian id :notion-status)))
              "a page that stays outside the view still has its next edit recorded")
          (ws/close! :brian id :done)
          (reset! fetch {:error :network})
          (poll!)
          (is (= 3 (count (ws/entries-of :brian id :notion-status)))
              "once its workstream closes, the page is no longer fetched")
          (ws/reopen! :brian id :in-progress)
          (reset! fetch {:properties {:Status {:type "status" :status {:name "In progress"}}}})
          (poll!)
          (is (= ["In progress" "Review" "Done" "In progress"]
                 (mapv :status (ws/entries-of :brian id :notion-status)))
              "reopened, it is fetched again"))))))

(deftest views-that-last-saw-a-closed-workstreams-page-differently-record-nothing
  ;; Two views each keep the status they last saw for a page whose workstream then closed. Were
  ;; either recorded, each poll would record the opposite of the other's: one closed ticket
  ;; collected ten thousand entries that way.
  (with-tmp
    (fn []
      (let [id    (:id (ws/create! :brian {:stage :in-progress
                                           :external-refs [{:adapter :notion :id "BR-1" :page-id "p1"}]}))
            view  (fn [v status]
                    (let [pages (atom {"p1" {:status status :br "BR-1"}})
                          poll! (:poll! (notion-src/start-instance! {:type :notion-view :project :brian :view v}
                                                                    (fn [_]) {:token "t"}))]
                      [pages poll!]))
            [pa a!] (view :a "In progress")
            [pb b!] (view :b "Review")]
        (with-redefs [notion-src/poll-once!       (fn [cfg _ _] {:last-poll-result :ok
                                                                 :pages @(if (= :a (:view cfg)) pa pb)})
                      notion-client/retrieve-page (fn [_ _] (throw (ex-info "a closed page is not fetched" {})))]
          (a!) (b!)
          (reset! pa {}) (reset! pb {})
          (ws/close! :brian id :done)
          (let [before (count (ws/entries-of :brian id :notion-status))]
            (dotimes [_ 3] (a!) (b!))
            (is (= before (count (ws/entries-of :brian id :notion-status)))
                "neither view's remembered status is recorded")))))))

(deftest a-poll-reads-the-ledgers-once-however-many-pages-left-its-view
  ;; Every page that ever left a view is asked about on every poll; finding each one's workstream
  ;; by scanning every ledger held the coordinator's loop for most of each poll.
  (with-tmp
    (fn []
      (let [ids   (mapv #(:id (ws/create! :brian {:stage :in-progress
                                                  :external-refs [{:adapter :notion :id (str "BR-" %) :page-id (str "p" %)}]}))
                        (range 3))
            pages (atom (into {} (map (fn [i] [(str "p" i) {:status "In progress" :br (str "BR-" i)}])) (range 3)))
            reads (atom 0)
            read-ws ws/read-ws
            poll! (:poll! (notion-src/start-instance! {:type :notion-view :project :brian :view :v}
                                                      (fn [_]) {:token "t"}))]
        (with-redefs [notion-src/poll-once!       (fn [_ _ _] {:last-poll-result :ok :pages @pages})
                      notion-client/retrieve-page (fn [_ _] {:properties {:Status {:type "status" :status {:name "Review"}}}})]
          (poll!)
          (reset! pages {})
          (with-redefs [ws/read-ws (fn [& args] (swap! reads inc) (apply read-ws args))]
            (poll!))
          (is (= ["In progress" "Review"] (mapv :status (ws/entries-of :brian (first ids) :notion-status)))
              "each page that left is still fetched and its new status recorded")
          ;; One read of every workstream for the poll, plus the newest-status check and the append
          ;; for each page whose status moved — 9 here. A scan of every ledger per departed page
          ;; read them 21 times.
          (is (<= @reads (* 3 (count ids))) "the ledgers read once for the poll, not once per departed page"))))))

(deftest a-page-that-left-the-view-before-its-workstream-existed-is-recorded
  (with-tmp
    (fn []
      (let [pages (atom {"p1" {:status "In progress" :br "BR-1"}})
            poll! (:poll! (notion-src/start-instance! {:type :notion-view :project :brian :view :v}
                                                      (fn [_]) {:token "t"}))]
        (with-redefs [notion-src/poll-once!       (fn [_ _ _] {:last-poll-result :ok :pages @pages})
                      notion-client/retrieve-page (fn [_ _] {:properties {:Status {:type "status" :status {:name "Review"}}}})]
          (poll!)
          (reset! pages {})
          (poll!)
          (poll!)
          (let [id (:id (ws/create! :brian {:stage :in-progress
                                            :external-refs [{:adapter :notion :id "BR-1"}]}))]
            (poll!)
            (is (= ["Review"] (mapv :status (ws/entries-of :brian id :notion-status)))
                "a BR-only ref reaches the page the view last mapped its BR to")
            (poll!)
            (is (= 1 (count (ws/entries-of :brian id :notion-status))) "and only once")))))))

(deftest a-notion-status-change-a-failed-append-lost-is-recorded-by-the-next-poll
  (with-tmp
    (fn []
      (let [id    (:id (ws/create! :brian {:stage :in-progress
                                           :external-refs [{:adapter :notion :id "BR-1" :page-id "p1"}]}))
            pages (atom {"p1" {:status "In progress" :br "BR-1"}})
            fail? (atom false)
            poll! (:poll! (notion-src/start-instance! {:type :notion-view :project :brian :view :v}
                                                      (fn [_]) {:token "t"}))
            append ws/append-entry!]
        (with-redefs [notion-src/poll-once! (fn [_ _ _] {:last-poll-result :ok :pages @pages})
                      ws/append-entry!      (fn [& args]
                                              (if @fail? (throw (ex-info "disk full" {})) (apply append args)))]
          (poll!)
          (reset! pages {"p1" {:status "Review" :br "BR-1"}})
          (reset! fail? true)
          (binding [*err* (java.io.StringWriter.)] (poll!))
          (is (= ["In progress"] (mapv :status (ws/entries-of :brian id :notion-status))) "the append failed")
          (reset! fail? false)
          (poll!)
          (is (= ["In progress" "Review"] (mapv :status (ws/entries-of :brian id :notion-status)))
              "the next poll, seeing no change since the last, still records it")
          (poll!)
          (is (= 2 (count (ws/entries-of :brian id :notion-status))) "and only once"))))))

(def ^:private a-baseline
  {:format :baseline :strata [] :intent {:seq 1}
   :area "order totalling" :bounded-by "money on an order"
   :shape "one summing path"
   :model {:elements [{:id "agg" :sort :module :hides "the summing order"
                       :interface "an order's total"}]
           :claims [{:id "c1" :about ["agg"]
                     :statement "the aggregate is the only summing path"
                     :falsified-by "a second path that sums lines"
                     :evidence {:by :round} :read-at ["src/a.clj:1"]}]}
   :read ["src/a.clj"]})

(defn- a-design [baseline-seq]
  {:format :design :strata [] :summary "s" :shape "sh"
   :model {:elements [{:id "agg" :sort :module}]
           :claims [{:id "one-summing-path" :about ["agg"]
                     :statement "one summing path"
                     :falsified-by "a second path that sums lines"
                     :evidence {:by :round}}]}
   :standing {:relation :conforms}
   :baseline {:seq baseline-seq :relation :within}
   :intent {:seq 1} :effort :S})

(deftest a-layer-is-recorded-under-the-design-it-was-built-under
  (with-tmp
    (fn []
      (let [id   (:id (ws/create! :brian {:stage :in-progress}))
            add! (fn [kind record]
                   (if (= :design-decision kind) (ws/append-decision! :brian id (pr-str record)) (ws/append-entry! :brian id {:kind kind} (pr-str record)))
                   (:seq (last (:entries (ws/read-ws :brian id)))))
            done #(select-keys (last (ws/entries-of :brian id :layer-completed))
                               [:design :layer :of :bookmark])]
        (is (thrown-with-msg? Exception #"No design"
                              (t/layer-complete* {:project "brian" :ws-id id :layer 1 :of 3})))
        (add! :intent {:format :intent :goal "g" :done-when ["d"]})
        (let [b  (add! :baseline a-baseline)
              d1 (add! :design (a-design b))]
          (t/layer-complete* {:project "brian" :ws-id id :layer 1 :of 3 :bookmark "s--a"})
          (is (= {:design {:seq d1} :layer 1 :of 3 :bookmark "s--a"} (done))
              "the only design is the one it was built under")
          (let [d2 (add! :design (assoc (a-design b) :supersedes {:seq d1 :why "amended"}))]
            (is (thrown-with-msg? Exception #"name the one"
                                  (t/layer-complete* {:project "brian" :ws-id id :layer 2 :of 3}))
                "with two designs, the newest is not evidence")
            (is (thrown-with-msg? Exception #"No design at that seq"
                                  (t/layer-complete* {:project "brian" :ws-id id :layer 2 :of 3
                                                      :design (inc d2)})))
            (t/layer-complete* {:project "brian" :ws-id id :layer 2 :of 3 :design (str d1)})
            (is (= {:design {:seq d1} :layer 2 :of 3} (done))
                "a layer finished under the earlier design is recorded under it")))))))

(deftest a-seats-layer-is-recorded-within-its-seat
  (with-tmp
    (fn []
      (let [id    (:id (ws/create! :brian {:stage :in-progress}))
            add!  (fn [kind record]
                    (if (= :design-decision kind) (ws/append-decision! :brian id (pr-str record)) (ws/append-entry! :brian id {:kind kind} (pr-str record)))
                    (:seq (last (:entries (ws/read-ws :brian id)))))
            _     (add! :intent {:format :intent :goal "g" :done-when ["d"]})
            d     (add! :design (a-design (add! :baseline a-baseline)))
            layer #(t/layer-complete* (merge {:project "brian" :ws-id id :layer 1 :of 2} %))
            seats #(mapv (juxt :seat :layer) (ws/entries-of :brian id :layer-completed))]
        (layer {:seat "canvas.strata/ledger"})
        (layer {:seat "canvas.strata/seats"})
        (is (= [["canvas.strata/ledger" 1] ["canvas.strata/seats" 1]] (seats))
            "one position in two seats is two layers")
        (layer {:seat "canvas.strata/ledger"})
        (is (= 2 (count (seats))) "recording a seat's layer again appends nothing")
        (is (= {:seq d} (:design (last (ws/entries-of :brian id :layer-completed)))))))))
