(ns nido.coordinator.lane.github-merge-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.lane.github-merge :as gm]
   [nido.coordinator.source.state :as sstate]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.github.client :as gh]
   [nido.notion.client :as notion]))

(def ^:private cfg
  {:repo "brian-study/brian" :poll "5m"
   :on-merge {:notion-status "Code Review" :remove-ball-holder "jaap"}})

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f tmp))
         (finally (fs/delete-tree tmp)))))

(deftest first-poll-seeds-and-reacts-to-nothing
  (with-tmp
    (fn [_]
      (let [closed (atom [])]
        (with-redefs [gh/list-merged-prs (fn [_] {:status :ok
                                                  :prs [{:number 1 :url "u1" :title "t" :merged-at "x" :base "main"}]})
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
                                                  :prs [{:number 2 :url "u2" :title "t2" :merged-at "y" :base "main"}]})
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

(deftest merge-appends-the-terminal-ledger-event
  (with-tmp
    (fn [_]
      (sstate/write-state! "github-brian" {:type :github-merge :project :brian :reacted #{}})
      (let [w (ws/create! :brian {:stage :in-progress
                                  :external-refs [{:adapter :github :id "brian-study/brian#5"}]})]
        (with-redefs [gh/list-merged-prs (fn [_] {:status :ok
                                                  :prs [{:number 5 :url "https://gh/5"
                                                         :title "Fix rounding" :merged-at "2026-08-20T10:00:00Z" :base "main"}]})
                      notion/keychain-token (constantly nil)]
          (gm/poll-and-react! :brian cfg)
          (is (= {:format :merged :pr "brian-study/brian#5" :url "https://gh/5"
                  :title "Fix rounding" :merged-at "2026-08-20T10:00:00Z"}
                 (dissoc (ws/latest-entry :brian (:id w) :merged) :seq :at))))))))

(deftest a-stack-lands-one-merge-event
  ;; N layer PRs merge; the first closes the workstream and the rest hit the
  ;; already-closed no-op — so the timeline reads as one shipment, not N.
  (with-tmp
    (fn [_]
      (sstate/write-state! "github-brian" {:type :github-merge :project :brian :reacted #{}})
      (let [w (ws/create! :brian {:stage :in-progress
                                  :external-refs [{:adapter :github :id "brian-study/brian#8"}
                                                  {:adapter :github :id "brian-study/brian#9"}]})]
        (with-redefs [gh/list-merged-prs (fn [_] {:status :ok
                                                  :prs [{:number 8 :url "u8" :title "layer 1" :merged-at "z" :base "main"}
                                                        {:number 9 :url "u9" :title "layer 2" :merged-at "z" :base "main"}]})
                      notion/keychain-token (constantly nil)]
          (gm/poll-and-react! :brian cfg)
          (is (= 1 (count (filter #(= :merged (:kind %))
                                  (:entries (ws/read-ws :brian (:id w))))))))))))

(deftest ledger-failure-does-not-cost-the-close
  (with-tmp
    (fn [_]
      (sstate/write-state! "github-brian" {:type :github-merge :project :brian :reacted #{}})
      (let [w (ws/create! :brian {:stage :in-progress
                                  :external-refs [{:adapter :github :id "brian-study/brian#6"}]})]
        (with-redefs [gh/list-merged-prs (fn [_] {:status :ok
                                                  :prs [{:number 6 :url "u6" :title "t6" :merged-at "y" :base "main"}]})
                      notion/keychain-token (constantly nil)
                      ws/append-entry! (fn [& _] (throw (ex-info "disk full" {})))]
          (gm/poll-and-react! :brian cfg)
          (is (= :done (-> (ws/read-ws :brian (:id w)) :closed :outcome))
              "the close is the important write; a failed append must not undo it"))))))

(deftest already-closed-workstream-is-noop
  (with-tmp
    (fn [_]
      (sstate/write-state! "github-brian" {:type :github-merge :project :brian :reacted #{}})
      (let [w (ws/create! :brian {:stage :in-progress
                                  :external-refs [{:adapter :github :id "brian-study/brian#3"}]})]
        (ws/close! :brian (:id w) :done)
        (let [calls (atom 0)]
          (with-redefs [gh/list-merged-prs (fn [_] {:status :ok :prs [{:number 3 :url "u" :title "t" :merged-at "z" :base "main"}]})
                        notion/keychain-token (fn [] (swap! calls inc) "tok")]
            (gm/poll-and-react! :brian cfg)
            (is (zero? @calls) "already-closed ⇒ no Notion work")))))))

(deftest uncorrelated-merge-is-skipped-and-marked-seen
  (with-tmp
    (fn [_]
      (sstate/write-state! "github-brian" {:type :github-merge :project :brian :reacted #{}})
      (with-redefs [gh/list-merged-prs (fn [_] {:status :ok :prs [{:number 9 :url "u" :title "t" :merged-at "z" :base "main"}]})]
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
        (with-redefs [gh/list-merged-prs (fn [_] {:status :ok :prs [{:number 5 :url "u" :title "t" :merged-at "z" :base "main"}]})
                      notion/keychain-token (constantly nil)]   ; no notion work needed for this assertion
          ;; ... config repo is mixed-case; correlation must still match
          (gm/poll-and-react! :brian (assoc cfg :repo "Brian-Study/Brian"))
          (is (= :done (-> (ws/read-ws :brian (:id w)) :closed :outcome))
              "merge correlates despite repo-slug case mismatch"))))))

;; ── the merge TARGET, not just the ref ───────────────────────────────────────
;;
;; What stranded BR-5559: layer 2 of a two-layer stack merged into layer 1's
;; branch during a restack. It is a real merged PR carrying a ref the workstream
;; stamped, and the poller closed the workstream on it — while `main` never
;; received a line of the work.

(deftest stack-internal-merge-leaves-the-workstream-open
  (with-tmp
    (fn [_]
      (sstate/write-state! "github-brian" {:type :github-merge :project :brian :reacted #{}})
      (let [w (ws/create! :brian {:stage :in-progress
                                  :external-refs [{:adapter :notion :id "BR-5559" :page-id "PAGE"}
                                                  {:adapter :github :id "brian-study/brian#4693"}]})
            nudged (atom false)]
        (with-redefs [gh/list-merged-prs (fn [_] {:status :ok
                                                  :prs [{:number 4693 :url "u" :title "[2/2] layer two"
                                                         :merged-at "2026-08-26T15:22:57Z"
                                                         :base "impl-br-5559--enable-type-dropdown"}]})
                      notion/keychain-token (fn [] (reset! nudged true) "tok")]
          (gm/poll-and-react! :brian cfg)
          (is (nil? (:closed (ws/read-ws :brian (:id w))))
              "a merge into the layer beneath ships nothing; the workstream stays open")
          (is (nil? (ws/latest-entry :brian (:id w) :merged))
              "and no :merged event claims otherwise in the ledger")
          (is (false? @nudged) "no Notion nudge for a merge that did not land")
          (is (contains? (:reacted (sstate/read-state "github-brian")) "brian-study/brian#4693")
              "still marked seen, so the warning fires once rather than every poll"))))))

(deftest merge-with-no-reported-base-leaves-the-workstream-open
  ;; The guard reads a positive signal, so absence must withhold the close —
  ;; otherwise a `gh` that stops reporting baseRefName silently re-opens the
  ;; whole defect.
  (with-tmp
    (fn [_]
      (sstate/write-state! "github-brian" {:type :github-merge :project :brian :reacted #{}})
      (let [w (ws/create! :brian {:stage :in-progress
                                  :external-refs [{:adapter :github :id "brian-study/brian#11"}]})]
        (with-redefs [gh/list-merged-prs (fn [_] {:status :ok
                                                  :prs [{:number 11 :url "u" :title "t" :merged-at "z"}]})
                      notion/keychain-token (constantly nil)]
          (gm/poll-and-react! :brian cfg)
          (is (nil? (:closed (ws/read-ws :brian (:id w))))
              "no base reported ⇒ no close"))))))

(deftest landing-branch-is-configurable
  (with-tmp
    (fn [_]
      (sstate/write-state! "github-brian" {:type :github-merge :project :brian :reacted #{}})
      (let [w (ws/create! :brian {:stage :in-progress
                                  :external-refs [{:adapter :github :id "brian-study/brian#12"}]})]
        (with-redefs [gh/list-merged-prs (fn [_] {:status :ok
                                                  :prs [{:number 12 :url "u" :title "t" :merged-at "z"
                                                         :base "master"}]})
                      notion/keychain-token (constantly nil)]
          (gm/poll-and-react! :brian (assoc cfg :base "master"))
          (is (= :done (-> (ws/read-ws :brian (:id w)) :closed :outcome))
              "a repo whose default branch is master closes on a merge into master"))))))

;; ── resolving the ticket to nudge ────────────────────────────────────────────
;;
;; A :notion ref gets a :page-id only when the spawning event's payload carried
;; one. Older spawn paths emitted just the BR-####, so seven of brian's
;; workstreams hold a ref with a :url and nothing else — and the nudge used to
;; treat those as "no ticket" and return quietly.

(deftest notion-nudge-falls-back-to-the-ref-url
  (with-tmp
    (fn [_]
      (sstate/write-state! "github-brian" {:type :github-merge :project :brian :reacted #{}})
      (let [w (ws/create! :brian
                {:stage :in-progress
                 :external-refs
                 [{:adapter :notion :id "BR-5559"
                   :url "https://app.notion.com/p/Change-of-Question-Type-37afca9f403c80e4b658d113caf5fef6"}
                  {:adapter :github :id "brian-study/brian#20"}]})
            props (atom nil)]
        (with-redefs [gh/list-merged-prs (fn [_] {:status :ok
                                                  :prs [{:number 20 :url "u" :title "t" :merged-at "z"
                                                         :base "main"}]})
                      notion/keychain-token (constantly "tok")
                      notion/retrieve-page  (fn [_ _] {:properties {}})
                      notion/update-page-properties! (fn [pg p _] (reset! props {:page pg :props p}) {:ok true})]
          (gm/poll-and-react! :brian cfg)
          (is (= "37afca9f-403c-80e4-b658-d113caf5fef6" (:page @props))
              "the page-id comes out of the ref's URL when the ref does not store one")
          (is (= {:status {:name "Code Review"}} (get-in @props [:props "Status"]))))))))

(deftest unresolvable-notion-ref-is-reported-not-swallowed
  (with-tmp
    (fn [_]
      (sstate/write-state! "github-brian" {:type :github-merge :project :brian :reacted #{}})
      (let [w (ws/create! :brian {:stage :in-progress
                                  :external-refs [{:adapter :notion :id "BR-5477"}
                                                  {:adapter :github :id "brian-study/brian#21"}]})
            sw (java.io.StringWriter.)
            pw (java.io.PrintWriter. sw)]
        (with-redefs [gh/list-merged-prs (fn [_] {:status :ok
                                                  :prs [{:number 21 :url "u" :title "t" :merged-at "z"
                                                         :base "main"}]})
                      notion/keychain-token (constantly "tok")
                      notion/update-page-properties! (fn [& _] (is false "must not write with no page-id"))]
          (binding [*err* pw]
            (gm/poll-and-react! :brian cfg)
            (.flush pw))
          (is (= :done (-> (ws/read-ws :brian (:id w)) :closed :outcome))
              "the merge is real; the close stands")
          (is (re-find #"no resolvable page-id" (str sw))
              "and the skipped nudge is said out loud"))))))

(deftest workstream-without-a-notion-ref-nudges-nothing-quietly
  (with-tmp
    (fn [_]
      (sstate/write-state! "github-brian" {:type :github-merge :project :brian :reacted #{}})
      (let [w (ws/create! :brian {:stage :in-progress
                                  :external-refs [{:adapter :github-issue :id "brian-study/brian#99"}
                                                  {:adapter :github :id "brian-study/brian#22"}]})
            sw (java.io.StringWriter.)
            pw (java.io.PrintWriter. sw)]
        (with-redefs [gh/list-merged-prs (fn [_] {:status :ok
                                                  :prs [{:number 22 :url "u" :title "t" :merged-at "z"
                                                         :base "main"}]})
                      notion/keychain-token (constantly "tok")]
          (binding [*err* pw]
            (gm/poll-and-react! :brian cfg)
            (.flush pw))
          (is (= :done (-> (ws/read-ws :brian (:id w)) :closed :outcome)))
          (is (nil? (re-find #"page-id" (str sw)))
              "a GitHub-issue workstream has no ticket to nudge; that is not a gap"))))))

;; ── a page whose database has no Ball Holder ─────────────────────────────────
;; nido's own cross-project follow-up DB issues FU-# ids through the same
;; :notion adapter as a project ticket, and its pages define no Ball Holder.
;; Notion applies a property update as one request, so naming a property the
;; database does not define fails the WHOLE call — the status write included.
;; Measured live 2026-09-04: FU-4 and FU-15 both sit at Status=Open on a select
;; property, with merged PRs and closed workstreams behind them.

(deftest ball-holder-is-skipped-when-the-page-has-no-such-property
  (with-tmp
    (fn [_]
      (sstate/write-state! "github-brian" {:type :github-merge :project :brian :reacted #{}})
      (ws/create! :brian {:stage :in-progress
                          :external-refs [{:adapter :notion :id "FU-4" :page-id "FUPAGE"}
                                          {:adapter :github :id "brian-study/brian#9"}]})
      (let [props (atom nil)]
        (with-redefs [gh/list-merged-prs (fn [_] {:status :ok
                                                  :prs [{:number 9 :url "u9" :title "t9" :merged-at "y" :base "main"}]})
                      notion/keychain-token (constantly "tok")
                      ;; A follow-up page: no Ball Holder anywhere in :properties.
                      notion/retrieve-page  (fn [_ _] {:properties {:Status {:type "select" :select {:name "Open"}}}})
                      notion/update-page-properties! (fn [pg p _] (reset! props {:page pg :props p}) {:ok true})]
          (gm/poll-and-react! :brian cfg)
          (is (nil? (get-in @props [:props "Ball Holder"]))
              "a property the page's database does not define is never written")
          (is (= {:status {:name "Code Review"}} (get-in @props [:props "Status"]))
              "the status write still goes out — it is no longer dragged down with it"))))))

(deftest nothing-to-write-writes-nothing
  (with-tmp
    (fn [_]
      (sstate/write-state! "github-brian" {:type :github-merge :project :brian :reacted #{}})
      (let [w (ws/create! :brian {:stage :in-progress
                                  :external-refs [{:adapter :notion :id "FU-4" :page-id "FUPAGE"}
                                                  {:adapter :github :id "brian-study/brian#9"}]})
            called (atom false)]
        ;; :notion-status dropped (what brian's config becomes once its own
        ;; notifier owns the Review transition) plus a page with no Ball Holder
        ;; leaves an empty props map — and an empty map must not become a request.
        (with-redefs [gh/list-merged-prs (fn [_] {:status :ok
                                                  :prs [{:number 9 :url "u9" :title "t9" :merged-at "y" :base "main"}]})
                      notion/keychain-token (constantly "tok")
                      notion/retrieve-page  (fn [_ _] {:properties {:Status {:type "select"}}})
                      notion/update-page-properties! (fn [_ _ _] (reset! called true) {:ok true})]
          (gm/poll-and-react! :brian
                              {:repo "brian-study/brian" :poll "5m"
                               :on-merge {:remove-ball-holder "jaap"}})
          ;; Asserted BEFORE the negative one: without it, a workstream that
          ;; failed to correlate would leave @called false and the test would
          ;; pass having exercised nothing.
          (is (= :done (-> (ws/read-ws :brian (:id w)) :closed :outcome))
              "the merge did correlate and the workstream closed")
          (is (false? @called)
              "no property to write ⇒ no Notion request at all"))))))
