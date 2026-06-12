(ns nido.coordinator.github-merge-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.github-merge :as gm]
   [nido.coordinator.sources.state :as sstate]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]
   [nido.github.client :as gh]
   [nido.notion.client :as notion]))

(def ^:private cfg
  {:repo "brian-study/brian" :poll "5m"
   :on-merge {:notion-status "Code Review" :remove-ball-holder "jaap"}})

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [cstate/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f tmp))
         (finally (fs/delete-tree tmp)))))

(deftest first-poll-seeds-and-reacts-to-nothing
  (with-tmp
    (fn [_]
      (let [closed (atom [])]
        (with-redefs [gh/list-merged-prs (fn [_] {:status :ok
                                                  :prs [{:number 1 :url "u1" :title "t" :merged-at "x"}]})
                      ws/find-by-ref     (fn [& _] (do (swap! closed conj :looked) nil))]
          (gm/poll-and-react! :brian cfg)
          (is (empty? @closed) "first poll must not correlate/react")
          (is (contains? (:reacted (sstate/read-state "github-brian")) "brian-study/brian#1")
              "first poll seeds the reacted set"))))))

(deftest new-merge-closes-workstream-and-nudges-notion
  (with-tmp
    (fn [_]
      (sstate/write-state! "github-brian" {:type :github-merge :project :brian :reacted #{}})
      (let [w (ws/create! :brian {:stage :in-progress
                                  :external-refs [{:adapter :notion :id "BR-7" :page-id "PAGE7"}
                                                  {:adapter :github :id "brian-study/brian#2"}]})
            props (atom nil)]
        (with-redefs [gh/list-merged-prs (fn [_] {:status :ok
                                                  :prs [{:number 2 :url "u2" :title "t2" :merged-at "y"}]})
                      notion/keychain-token (constantly "tok")
                      notion/retrieve-page  (fn [_ _] {:properties {(keyword "Ball Holder") {:people [{:id "jaap"} {:id "other"}]}}})
                      notion/update-page-properties! (fn [pg p _] (reset! props {:page pg :props p}) {:ok true})]
          (gm/poll-and-react! :brian cfg)
          (is (= :done (-> (ws/read-ws :brian (:id w)) :closed :outcome)) "workstream closed")
          (is (= "PAGE7" (:page @props)))
          (is (= {:status {:name "Code Review"}} (get-in @props [:props "Status"])))
          (is (= {:people [{:id "other"}]} (get-in @props [:props "Ball Holder"]))
              "jaap removed from Ball Holder, other kept")
          (is (nil? (get-in @props [:props "Participants"])) "Participants untouched"))))))

(deftest already-closed-workstream-is-noop
  (with-tmp
    (fn [_]
      (sstate/write-state! "github-brian" {:type :github-merge :project :brian :reacted #{}})
      (let [w (ws/create! :brian {:stage :in-progress
                                  :external-refs [{:adapter :github :id "brian-study/brian#3"}]})]
        (ws/close! :brian (:id w) :done)
        (let [calls (atom 0)]
          (with-redefs [gh/list-merged-prs (fn [_] {:status :ok :prs [{:number 3 :url "u" :title "t" :merged-at "z"}]})
                        notion/keychain-token (fn [] (swap! calls inc) "tok")]
            (gm/poll-and-react! :brian cfg)
            (is (zero? @calls) "already-closed ⇒ no Notion work")))))))

(deftest uncorrelated-merge-is-skipped-and-marked-seen
  (with-tmp
    (fn [_]
      (sstate/write-state! "github-brian" {:type :github-merge :project :brian :reacted #{}})
      (with-redefs [gh/list-merged-prs (fn [_] {:status :ok :prs [{:number 9 :url "u" :title "t" :merged-at "z"}]})]
        (gm/poll-and-react! :brian cfg)
        (is (contains? (:reacted (sstate/read-state "github-brian")) "brian-study/brian#9")
            "uncorrelated PR still marked seen so we don't re-log it")))))

(deftest gh-auth-error-trips-breaker
  (with-tmp
    (fn [_]
      (sstate/write-state! "github-brian" {:type :github-merge :project :brian :reacted #{}})
      (with-redefs [gh/list-merged-prs (fn [_] {:error :auth})]
        (gm/poll-and-react! :brian cfg)
        (is (= :open (:breaker (sstate/read-state "github-brian"))))))))

(deftest gh-generic-error-trips-breaker-after-three
  (with-tmp
    (fn [_]
      (sstate/write-state! "github-brian" {:type :github-merge :project :brian
                                           :reacted #{} :consecutive-failures 2})
      (with-redefs [gh/list-merged-prs (fn [_] {:error :gh})]
        (gm/poll-and-react! :brian cfg)
        (let [s (sstate/read-state "github-brian")]
          (is (= 3 (:consecutive-failures s)) "counter incremented")
          (is (= :open (:breaker s)) "tripped at the 3rd consecutive failure"))))))

(deftest gh-generic-error-below-threshold-does-not-trip
  (with-tmp
    (fn [_]
      (sstate/write-state! "github-brian" {:type :github-merge :project :brian
                                           :reacted #{} :consecutive-failures 0})
      (with-redefs [gh/list-merged-prs (fn [_] {:error :gh})]
        (gm/poll-and-react! :brian cfg)
        (let [s (sstate/read-state "github-brian")]
          (is (= 1 (:consecutive-failures s)) "counter incremented")
          (is (nil? (:breaker s)) "single non-auth failure stays below threshold"))))))

(deftest breaker-open-within-cooldown-skips-poll
  (with-tmp
    (fn [_]
      (sstate/write-state! "github-brian" {:type :github-merge :project :brian :reacted #{}
                                           :breaker :open :breaker-opened-at "2026-06-12T10:00:00Z"
                                           :consecutive-failures 1})
      (let [called (atom false)]
        (with-redefs [clock/now-iso       (constantly "2026-06-12T10:10:00Z")   ; 10m < 30m cooldown
                      gh/list-merged-prs  (fn [_] (reset! called true) {:status :ok :prs []})]
          (gm/poll-and-react! :brian cfg)
          (is (false? @called) "breaker open + cooldown not elapsed ⇒ no gh poll, no warn")
          (is (= :open (:breaker (sstate/read-state "github-brian"))) "stays open, state untouched"))))))

(deftest breaker-half-open-probe-after-cooldown-clears-on-success
  (with-tmp
    (fn [_]
      (sstate/write-state! "github-brian" {:type :github-merge :project :brian :reacted #{}
                                           :breaker :open :breaker-opened-at "2026-06-12T10:00:00Z"
                                           :consecutive-failures 5})
      (let [called (atom false)]
        (with-redefs [clock/now-iso       (constantly "2026-06-12T11:00:00Z")   ; 60m > 30m cooldown
                      gh/list-merged-prs  (fn [_] (reset! called true) {:status :ok :prs []})]
          (gm/poll-and-react! :brian cfg)
          (is (true? @called) "cooldown elapsed ⇒ one probe poll runs")
          (let [s (sstate/read-state "github-brian")]
            (is (nil? (:breaker s)) "successful probe clears the breaker")
            (is (zero? (:consecutive-failures s)) "failures reset")))))))

(deftest breaker-half-open-probe-failure-rearms-cooldown
  (with-tmp
    (fn [_]
      (sstate/write-state! "github-brian" {:type :github-merge :project :brian :reacted #{}
                                           :breaker :open :breaker-opened-at "2026-06-12T10:00:00Z"
                                           :consecutive-failures 5})
      (with-redefs [clock/now-iso      (constantly "2026-06-12T11:00:00Z")   ; probe time
                    gh/list-merged-prs (fn [_] {:error :auth})]
        (gm/poll-and-react! :brian cfg)
        (let [s (sstate/read-state "github-brian")]
          (is (= :open (:breaker s)) "failed probe keeps the breaker open")
          (is (= "2026-06-12T11:00:00Z" (:breaker-opened-at s))
              "cooldown re-armed to the probe time, so the next probe waits another full cooldown"))))))

(deftest correlation-is-case-insensitive
  (with-tmp
    (fn [_]
      (sstate/write-state! "github-brian" {:type :github-merge :project :brian :reacted #{}})
      (let [w (ws/create! :brian {:stage :in-progress
                                  ;; stored ref lower-cased ...
                                  :external-refs [{:adapter :github :id "brian-study/brian#5"}]})]
        (with-redefs [gh/list-merged-prs (fn [_] {:status :ok :prs [{:number 5 :url "u" :title "t" :merged-at "z"}]})
                      notion/keychain-token (constantly nil)]   ; no notion work needed for this assertion
          ;; ... config repo is mixed-case; correlation must still match
          (gm/poll-and-react! :brian (assoc cfg :repo "Brian-Study/Brian"))
          (is (= :done (-> (ws/read-ws :brian (:id w)) :closed :outcome))
              "merge correlates despite repo-slug case mismatch"))))))
