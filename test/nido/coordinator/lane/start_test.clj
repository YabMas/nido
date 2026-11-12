(ns nido.coordinator.lane.start-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.lane.brief :as brief]
   [nido.coordinator.lane.conductor :as conductor]
   [nido.coordinator.lane.scratch :as scratch]
   [nido.coordinator.record.session :as csession]
   [nido.session.lifecycle :as lifecycle]
   [nido.coordinator.lane.start :as start]
   [nido.coordinator.record.standing :as standing]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.github.client :as gh]
   [nido.notion.client :as notion]
   [nido.platform.config :as config]
   [nido.platform.core :as core]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f))
         (finally (fs/delete-tree tmp)))))

(defn- kinds [ws-id] (mapv :kind (:entries (ws/read-ws :brian ws-id))))

(defn- with-start
  "Call `f` with the heavy and external legs of a start stubbed, given {:ups :briefs}: the
   session bring-up records its call in :ups, and the Notion brief transcription appends a
   :ticket entry and records its call in :briefs."
  [f]
  (let [ups (atom []) briefs (atom [])]
    (with-redefs [start/start-session!           (fn [p s o] (swap! ups conj [p s o]) (:ws-id o))
                  brief/ensure-ticket-brief!     (fn [p id ref _tok]
                                                   (swap! briefs conj [id ref])
                                                   (ws/append-entry! p id {:kind :ticket} "words"))
                  notion/keychain-token          (constantly "tok")
                  notion/update-page-properties! (fn [& _] {:ok true})
                  config/read-projects           (constantly {})]
      (f {:ups ups :briefs briefs}))))

;; ── start-session! ──────────────────────────────────────────────────────────

(deftest a-session-already-recorded-is-carried-only-when-asked
  ;; A re-start finds its impl session already recorded; a ticket start still asks for the carry,
  ;; which takes the whole seat only if nobody works there.
  (let [carried (atom [])]
    (with-redefs [csession/workstream-id-for (constantly "ws-1")
                  scratch/birth!             (fn [& _] "ws-1")
                  lifecycle/up!              (fn [& _] nil)
                  lifecycle/session-weight   (constantly :heavy)
                  conductor/carry!           (fn [_ ws s & _] (swap! carried conj [ws s]) {:carried nil})]
      (start/start-session! :brian "impl-br-1" {:ws-id "ws-1"})
      (is (= [] @carried) "a plain session start leaves a recorded session uncarried")
      (start/start-session! :brian "impl-br-1" {:ws-id "ws-1" :carry? true})
      (is (= [["ws-1" "impl-br-1"]] @carried)))))

;; ── start-ticket! ───────────────────────────────────────────────────────────

(deftest a-start-activates-and-brings-the-session-up-at-once
  (with-tmp
    (fn []
      (with-start (fn [{:keys [ups briefs]}]
        (let [w   (ws/create! :brian {:external-refs [{:adapter :notion :id "BR-7" :page-id "PG7"
                                                       :title "Firefox loading"}]})
              res (start/start-ticket! :brian {:ws-id (:id w)})
              w'  (ws/read-ws :brian (:id w))]
          (is (= {:decision :started :ws-id (:id w) :session "impl-br-7-firefox-loading"} res))
          (is (standing/activated? w') "the start itself activates the recipe")
          (is (= :in-progress (standing/chosen-stage :brian (:id w) w')))
          (is (some? (standing/person-started-at w')) "the start is a person's")
          (is (= [[:brian "impl-br-7-firefox-loading" {:ws-id (:id w) :carry? true :session-profile :full :wait false}]]
                 @ups)
              "the session comes up at the click — no envelope, no Run")
          (is (empty? (fs/list-dir (cstate/runs-dir))) "no Run is minted")
          (is (= 1 (count @briefs)))
          (is (= :ticket (first (kinds (:id w))))
              "the ticket's words land before the start's own entries")))))))

(deftest a-ticket-already-told-is-not-transcribed-again
  (with-tmp
    (fn []
      (with-start (fn [{:keys [briefs]}]
        (let [w (ws/create! :brian {:external-refs [{:adapter :notion :id "BR-8" :page-id "PG8"}]})]
          (ws/append-entry! :brian (:id w) {:kind :ticket} "already told")
          (is (= :started (:decision (start/start-ticket! :brian {:ws-id (:id w)}))))
          (is (empty? @briefs))))))))

(deftest refusals-write-nothing
  (with-tmp
    (fn []
      (with-start (fn [{:keys [ups]}]
        (testing "no such workstream"
          (is (= {:decision :no-such-workstream} (start/start-ticket! :brian {:ws-id "ws-nope"}))))
        (testing "closed"
          (let [w (ws/create! :brian {:external-refs [{:adapter :notion :id "BR-9" :page-id "PG9"}]})]
            (ws/close! :brian (:id w) :done)
            (is (= {:decision :closed} (start/start-ticket! :brian {:ws-id (:id w)})))))
        (testing "owed Notion words with no page to read them from"
          (let [w (ws/create! :brian {:external-refs [{:adapter :notion :id "BR-10"}]})]
            (is (= {:decision :ticket-unreadable} (start/start-ticket! :brian {:ws-id (:id w)})))
            (is (empty? (kinds (:id w))))))
        (testing "an unreadable GitHub issue whose words are owed"
          (with-redefs [gh/view-issue (constantly {:error :not-found})]
            (let [w (ws/create! :nido {:external-refs [{:adapter :github-issue :id "o/r#4"}]})]
              (is (= {:decision :ticket-unreadable} (start/start-ticket! :nido {:ws-id (:id w)}))))))
        (is (empty? @ups)))))))

(deftest a-start-by-ref-mints-and-fills-the-page-id-from-the-caller
  (with-tmp
    (fn []
      (with-start (fn [{:keys [briefs]}]
        (let [res (start/start-ticket! :brian {:ref {:adapter :notion :id "BR-11" :page-id "PG11"
                                                     :title "T"}})]
          (is (= :started (:decision res)))
          (is (= "PG11" (:page-id (second (first @briefs)))))))))))

(deftest a-github-issue-start-records-the-issue-as-the-brief
  (with-tmp
    (fn []
      (with-start (fn [{:keys [ups]}]
        (with-redefs [gh/view-issue (constantly {:issue {:title "Crash" :url "https://gh/4" :body "it crashes"}})]
          (let [w   (ws/create! :nido {:external-refs [{:adapter :github-issue :id "o/r#4"}]})
                res (start/start-ticket! :nido {:ws-id (:id w)})]
            (is (= "impl-o-r-4-crash" (:session res)))
            (is (= :ticket (first (mapv :kind (:entries (ws/read-ws :nido (:id w)))))))
            (is (= 1 (count @ups))))))))))

;; ── the Notion properties a start writes ───────────────────────────────────

(defn- props-for [on-start]
  (let [calls (atom [])]
    (with-redefs [config/read-projects           (constantly {"brian" {:on-start on-start}})
                  notion/keychain-token          (constantly "tok")
                  notion/retrieve-page           (fn [_ _] {:properties {:Participants {:people [{:id "existing-1"}
                                                                                                 {:id "jaap"}]}}})
                  notion/update-page-properties! (fn [pg props _] (swap! calls conj [pg props]) {:ok true})]
      (#'start/write-start-properties! :brian "PAGE1"))
    @calls))

(deftest start-properties-default-the-status-property-name
  (is (= [["PAGE1" {"Status" {:status {:name "In progress"}}}]]
         (props-for {:notion-status "In progress"}))))

(deftest start-properties-honour-an-explicit-property
  (is (= [["PAGE1" {"State" {:status {:name "In progress"}}}]]
         (props-for {:notion-status "In progress" :property "State"}))))

(deftest start-properties-set-ball-holder-and-merge-participants
  (is (= [["PAGE1" {"Status"       {:status {:name "In progress"}}
                    "Ball Holder"  {:people [{:id "jaap"}]}
                    "Participants" {:people [{:id "existing-1"} {:id "jaap"}]}}]]
         (props-for {:notion-status "In progress" :ball-holder "jaap" :add-participants ["jaap"]}))))

(deftest start-properties-skip-participants-when-the-page-is-unreadable
  (let [calls (atom [])]
    (with-redefs [config/read-projects           (constantly {"brian" {:on-start {:notion-status "In progress"
                                                                                  :ball-holder "jaap"
                                                                                  :add-participants ["jaap"]}}})
                  notion/keychain-token          (constantly "tok")
                  notion/retrieve-page           (fn [_ _] {:error :network})
                  notion/update-page-properties! (fn [pg props _] (swap! calls conj [pg props]) {:ok true})]
      (#'start/write-start-properties! :brian "PAGE1"))
    (is (= [["PAGE1" {"Status" {:status {:name "In progress"}} "Ball Holder" {:people [{:id "jaap"}]}}]]
           @calls)
        "participants are never clobbered")))

(deftest start-properties-are-best-effort
  (is (empty? (props-for nil)) "no configuration, no write")
  (with-redefs [config/read-projects           (constantly {"brian" {:on-start {:notion-status "x"}}})
                notion/keychain-token          (constantly "tok")
                notion/update-page-properties! (fn [& _] (throw (ex-info "boom" {})))]
    (is (nil? (#'start/write-start-properties! :brian "PAGE1")) "a throwing client never propagates"))
  (with-redefs [config/read-projects           (constantly {"brian" {:on-start {:notion-status "x"}}})
                notion/keychain-token          (constantly nil)
                notion/update-page-properties! (fn [& _] (throw (ex-info "must not call" {})))]
    (is (nil? (#'start/write-start-properties! :brian "PAGE1")) "no token, no write")))
