(ns nido.coordinator.lane.authored-entries-test
  "Every status decision a lane or a person makes reaches the ledger as an entry, and the backfill
   records what the records held before they did."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.coordinator.lane.backfill :as backfill]
   [nido.coordinator.lane.findings :as findings]
   [nido.coordinator.record.state :as cstate]
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
          (let [item (first (:open (:findings (ws/read-ws :brian id))))]
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

(deftest a-layer-is-recorded-under-the-newest-design
  (with-tmp
    (fn []
      (let [id (:id (ws/create! :brian {:stage :in-progress}))]
        (is (thrown-with-msg? Exception #"No design"
                              (t/layer-complete* {:project "brian" :ws-id id :layer 1 :of 3})))
        (with-redefs [ws/latest-entry (fn [_ _ kind] (when (= :design kind) {:seq 7}))]
          (t/layer-complete* {:project "brian" :ws-id id :layer 1 :of 3 :bookmark "s--a"}))
        (is (= {:design {:seq 7} :layer 1 :of 3 :bookmark "s--a"}
               (select-keys (last (ws/entries-of :brian id :layer-completed))
                            [:design :layer :of :bookmark])))))))

(deftest a-worded-answer-names-the-newest-unanswered-blocker
  (with-tmp
    (fn []
      (let [id (:id (ws/create! :brian {:stage :in-progress}))
            b! #(ws/append-entry! :brian id {:kind :blocker}
                                  (pr-str {:format :blocker :summary % :needs (str % "?")}))]
        (is (thrown-with-msg? Exception #"No unanswered blocker"
                              (t/blocker-answer* {:project "brian" :ws-id id :answer "x"})))
        (b! "first") (b! "second")
        (t/blocker-answer* {:project "brian" :ws-id id :answer "use the new column"})
        (let [[b1 b2] (map :seq (ws/entries-of :brian id :blocker))
              a       (last (ws/entries-of :brian id :blocker-answered))]
          (is (= b2 (:blocker-seq a)))
          (is (= "use the new column" (:summary a)))
          (testing "the older one is still owed, and is next"
            (t/blocker-answer* {:project "brian" :ws-id id :answer "keep it"})
            (is (= b1 (:blocker-seq (last (ws/entries-of :brian id :blocker-answered))))))
          (testing "an answer is words"
            (b! "third")
            (is (thrown? Exception (t/blocker-answer* {:project "brian" :ws-id id :answer " "})))))))))

(defn- raw-set! [id f]
  ;; A record as it stood before decisions were recorded: change it without an entry.
  (ws/write! (f (ws/read-ws :brian id))))

(deftest the-backfill-records-what-the-records-hold-once
  (with-tmp
    (fn []
      (let [closed  (:id (ws/create! :brian {:stage :triaging}))
            oneoff  (:id (ws/create! :brian {:stage :scratch}))
            staged  (:id (ws/create! :brian {:stage :triaging
                                             :external-refs [{:adapter :notion :id "BR-2" :page-id "p2"}]}))
            facts   {"p2" {:status "In progress"}}]
        (raw-set! closed #(assoc % :closed {:at "t" :outcome :between-phases :design {:seq 4}}))
        (raw-set! staged #(assoc % :stage :ready))
        (raw-set! staged #(assoc % :findings {:round 1 :open #{"f2"} :resolved {"f1" {:by "x" :at "t"}}}))
        (is (= [:closed] (backfill/backfill-workstream! :brian closed facts)))
        (is (= {:format :closed :outcome :between-phases :design {:seq 4} :by :backfill}
               (select-keys (last (ws/entries-of :brian closed :closed)) [:format :outcome :design :by])))
        (is (= [:scratch] (backfill/backfill-workstream! :brian oneoff facts)))
        (is (= [:stage-set :findings-resolved :notion-status]
               (backfill/backfill-workstream! :brian staged facts)))
        (is (= ["f1"] (:items (last (ws/entries-of :brian staged :findings-resolved)))))
        (testing "a second run appends nothing"
          (is (= {:workstreams 3 :appended {} :failed []} (backfill/backfill! :brian))))
        (testing "a cached status never overwrites one the ledger already records"
          (is (= [] (backfill/backfill-workstream! :brian staged {"p2" {:status "Backlog"}})))
          (is (= ["In progress"] (mapv :status (ws/entries-of :brian staged :notion-status)))))
        (testing "a ref with no stored page id is resolved through the cached BR"
          (let [legacy (:id (ws/create! :brian {:stage :triaging
                                                :external-refs [{:adapter :notion :id "BR-3"}]}))]
            (is (= [:notion-status]
                   (backfill/backfill-workstream! :brian legacy {"p3" {:status "Review" :br "BR-3"}})))
            (is (= "p3" (:page-id (last (ws/entries-of :brian legacy :notion-status)))))))
        (testing "a workstream reopened since its close is not closed again"
          (ws/reopen! :brian closed :in-progress)
          (is (= [:stage-set] (backfill/backfill-workstream! :brian closed facts))))))))

(deftest the-backfill-records-a-taken-verdict-and-a-ticket-only-dismissal-once
  (with-tmp
    (fn []
      (let [taken (:id (ws/create! :brian {:stage :triaging
                                           :external-refs [{:adapter :notion :id "BR-3"}]}))
            gone  (:id (ws/create! :brian {:stage :triaging
                                           :external-refs [{:adapter :notion :id "BR-4"}]}))
            verdict (pr-str {:format :triage-report :ticket-key "BR-3" :determination :bug
                             :title "V" :summary "s" :confidence {:level :high :reason "r"}
                             :routing nil :directions [] :notion-writes nil :trail []})]
        (ws/append-entry! :brian taken {:kind :triage} verdict)
        (nido.coordinator.record.tickets/open! :brian "BR-3" {:title "t"})
        (nido.coordinator.record.tickets/set-status! :brian "BR-3" :triaged)
        (nido.coordinator.record.tickets/open! :brian "BR-4" {:title "t"})
        (nido.coordinator.record.tickets/dismiss! :brian "BR-4")
        (is (= [:triage-accepted] (backfill/backfill-workstream! :brian taken {})))
        (is (= [:closed] (backfill/backfill-workstream! :brian gone {})))
        (is (= :dismissed (:outcome (last (ws/entries-of :brian gone :closed)))))
        (is (= {:workstreams 2 :appended {} :failed []} (backfill/backfill! :brian))
            "a second run appends nothing")))))
