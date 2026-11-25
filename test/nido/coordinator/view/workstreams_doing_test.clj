(ns nido.coordinator.view.workstreams-doing-test
  "The one phrase every surface renders. It is shared so that the board, the
   pane, the TUI and the CLI cannot describe one workstream four ways."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.record.activity :as activity]
   [nido.coordinator.view.activity :as act]
   [nido.coordinator.view.workstreams :as wsv]))

(deftest nothing-underway-renders-nothing
  (testing "nil rather than a placeholder, so a caller decides whether to draw
            anything at all — most rows have no activity and should show none"
    (is (nil? (wsv/doing-label nil)))
    (is (nil? (wsv/doing-label {})))))

(deftest every-claim-kind-reads-as-something-a-person-is-doing
  (testing "the reader of a board asks what is happening, not which subsystem
            is running, so no label is the activity's own name"
    (doseq [kind activity/kinds]
      (let [s (wsv/doing-label {:source :claim :kind kind})]
        (is (string? s) (str kind " has no label"))
        (is (not (str/includes? s (name kind)))
            (str kind " renders as its own keyword name rather than as a phrase"))))))

(deftest an-unknown-kind-degrades-to-its-name
  (testing "a claim written by a newer nido must not vanish from the board — a
            reader can act on an odd word, not on a blank"
    (is (= "some-future-round"
           (wsv/doing-label {:source :claim :kind :some-future-round})))))

(deftest merge-and-session-carry-their-phase
  (is (= "merging · driving" (wsv/doing-label {:source :merge :phase :driving})))
  (is (= "agent · running" (wsv/doing-label {:source :session :phase :running})))
  (testing "a phase-less source still reads"
    (is (= "merging" (wsv/doing-label {:source :merge})))
    (is (= "agent" (wsv/doing-label {:source :session})))))

(deftest an-unknown-source-renders-nothing-rather-than-guessing
  (is (nil? (wsv/doing-label {:source :something-else :phase :x}))))

(deftest the-merge-lanes-resting-state-has-a-phrase
  (testing "the projection answers :queued for a shipment the lane has not
            reached, and this layer's whole job is that it reads as something —
            a row that rendered nothing there would be indistinguishable from
            one with nothing happening"
    (is (= "merging · queued" (wsv/doing-label {:source :merge :phase :queued})))))

(def ^:private a-diff-review-report
  ;; The outline a running diff review persists: round 1 done, round 2 fixing
  ;; what its review raised across two layers.
  {:status "running"
   :rounds [{:round 1 :status "continued"
             :phases [{:phase "review" :status "ok"
                       :layers [{:label "a" :findings 3} {:label "b" :findings 1}]}
                      {:phase "fix" :status "ok" :layers []}]}
            {:round 2 :status "running"
             :phases [{:phase "review" :status "ok"
                       :layers [{:label "a" :findings 1} {:label "b" :findings 1}]}
                      {:phase "warden" :status "ok" :layers []}
                      {:phase "fix" :status "running" :layers []}]}]})

(deftest a-running-round-reads-as-where-it-has-got
  (is (= {:round 2 :phase :fix :findings 2} (act/round-progress a-diff-review-report))
      "the current round, the phase running in it, and what its review raised —
       never the first round's findings, which that round already worked through")
  (is (= "review round 2 · fixing · 2 findings"
         (wsv/doing-label {:source :claim :kind :diff-review
                           :progress (act/round-progress a-diff-review-report)}))))

(deftest a-record-round-counts-its-judges-findings
  (let [p (act/round-progress
           {:rounds [{:round 1 :status "running"
                      :phases [{:phase "judge" :status "ok" :findings [{:id 1}]}
                               {:phase "amend" :status "running" :findings []}]}]})]
    (is (= {:round 1 :phase :amend :findings 1} p))
    (is (= "design round 1 · amending · 1 finding"
           (wsv/doing-label {:source :claim :kind :design-round :progress p})))))

(deftest between-phases-and-before-a-round-the-label-still-reads
  (is (= "review round 1 · 0 findings"
         (wsv/doing-label {:source :claim :kind :diff-review
                           :progress {:round 1 :phase nil :findings 0}}))
      "no phase running is said by leaving it out, not by guessing one")
  (is (nil? (act/round-progress {:rounds []})))
  (is (= "reviewing the diff" (wsv/doing-label {:source :claim :kind :diff-review}))
      "and a claim whose report could not be read keeps the kind's own phrase"))

(deftest a-review-still-judging-says-how-far-it-has-read
  (let [p (act/round-progress
           {:rounds [{:round 3 :status "running"
                      :phases [{:phase "review" :status "running"
                                :layers [{:label "a" :status "running" :findings nil}
                                         {:label "b" :status "reviewed" :findings 1}
                                         {:label "c" :status "reviewed" :findings 0}]}]}]})]
    (is (= {:round 3 :phase :review :findings 1 :judged 2 :of 3} p))
    (is (= "review round 3 · reviewing 2/3 · 1 finding"
           (wsv/doing-label {:source :claim :kind :diff-review :progress p}))
        "so a count of nothing yet is not read as a clean round")))

;; ---------------------------------------------------------------------------
;; doing — what is underway, from exactly one source
;; ---------------------------------------------------------------------------

(defn- doing
  "What `act/of` says is underway for an observation given as the board knew it: a :closed
   closure or a :stage band."
  [{:keys [closed stage] :as m}]
  (:doing (act/of (-> m
                      (dissoc :closed :stage)
                      (assoc :closed? (or (some? closed) (= :done stage)) :band stage)))))

(def ^:private claim
  {:kind :diff-review :run-id "review-1" :report-path "/runs/review-1/report.json"
   :started-at "2026-09-03T10:00:00Z" :pid 4242})

(defn- a-run
  "A run of the workstream in `state`, its agent holding its lock when `live?`."
  ([state] (a-run state false))
  ([state live?] {:id "r-1" :session-name "impl-1" :trigger :plan-bug :state state :live? live?}))

(defn- a-merge [state] {:id "m-1" :session-name "impl-1" :trigger :merge :mode :merge :state state})

(deftest nothing-underway-reads-as-nil
  (is (nil? (doing {:runs []})))
  (testing "a run with no agent holding its lock is not activity, whatever its state"
    (is (nil? (doing {:runs [(a-run :running)]})))))

(deftest a-closed-workstream-is-doing-nothing
  (testing "closure outranks every source, including a claim some process still
            holds — it is over, whatever any record still says"
    (is (nil? (doing {:closed {:outcome :done}
                          :runs [(a-run :running true)]
                          :stage :shipping
                          :claim claim})))
    (is (nil? (doing {:stage :done :runs [(a-run :running true)]}))
        "and so does a band of :done — a ticket settled in Notion under the work")))

(deftest a-held-claim-answers-and-carries-its-payload
  (let [d (doing {:runs [] :claim claim})]
    (is (= :claim (:source d)))
    (is (= :diff-review (:kind d)))
    (is (= "review-1" (:run-id d)))
    (is (= "/runs/review-1/report.json" (:report-path d))
        "the report path is what makes a live run joinable, so it must survive")))

(deftest the-claim-outranks-merge-and-session
  (is (= :claim (:source (doing {:runs [(a-run :running true)] :stage :shipping :claim claim})))))

(deftest merge-reads-the-merge-runs-state
  (is (= {:source :merge :phase :driving}
         (doing {:runs [(a-merge :running)] :stage :shipping})))
  (is (= {:source :merge :phase :blocked}
         (doing {:runs [(a-merge :awaiting-review)] :stage :shipping})))
  (is (= {:source :merge :phase :awaiting-merge}
         (doing {:runs [(a-merge :done)] :stage :shipping})))
  (testing "a :shipping workstream with no merge run reads :queued rather than nil — the
            lane takes one branch at a time, so waiting for it is a state the board keeps
            saying"
    (is (= {:source :merge :phase :queued} (doing {:runs [] :stage :shipping})))
    (is (= {:source :merge :phase :queued}
           (doing {:runs [(a-run :running true)] :stage :shipping}))
        "and a run that is not the merge lane's says nothing about it")))

(deftest an-agent-holding-its-runs-lock-answers-last
  (let [d (doing {:runs [(a-run :running true)]})]
    (is (= {:source :session :phase :running :session "impl-1"} d))))

(deftest a-run-parked-for-review-is-a-gate-not-an-activity
  (testing "calling it activity would say a workstream waiting on a person is busy"
    (is (nil? (doing {:runs [(a-run :awaiting-review)]})))))

(deftest a-session-answering-a-reply-is-activity
  (testing "a reply leaves its run parked and lockless; the instruction nido gave the
            session is what says the agent is at work on it"
    (let [parked [(a-run :awaiting-review)]]
      (is (= {:source :session :phase :answering :session "impl-1"}
             (doing {:runs parked
                         :sessions [{:name "impl-1" :up? true :claude {:pending {:token "t" :seen? true}}}]})))
      (is (= {:source :session :phase :answering :session "impl-1"}
             (doing {:runs parked :sessions [{:name "impl-1" :up? true :claude {:queued "go"}}]}))
          "and so does one queued and not yet given")
      (is (nil? (doing {:runs parked :sessions [{:name "impl-1" :claude {:queued "go"}}]}))
          "a message queued for a session whose process is gone is waiting, not work")
      (is (nil? (doing {:runs parked :sessions [{:name "impl-1" :up? true :claude {:pending nil}}]}))
          "a session with nothing outstanding is still only a gate")))
  (is (= "agent · answering" (wsv/doing-label {:source :session :phase :answering}))))

(deftest a-live-run-outranks-a-session-answering
  (is (= :running
         (:phase (doing {:runs     [(a-run :running true)]
                             :sessions [{:name "other" :up? true :claude {:pending {:token "t"}}}]})))))

(deftest a-seat-mid-turn-on-an-instruction-already-ended-is-activity
  (testing "a conductor-driven seat works on long after the instruction that started it
            has ended — woken by its own subagents, holding no run lock, nothing pending or
            queued — and the listing seeing its process mid-turn is what says it is at work"
    (let [seat {:name "impl-1" :claude {:pending nil :queued nil} :mid-turn? true}]
      (is (= {:source :session :phase :running :session "impl-1"}
             (doing {:runs [] :sessions [seat]})))
      (is (nil? (doing {:runs [] :sessions [(assoc seat :mid-turn? false)]}))
          "a seat whose turn has stopped — at a person's gate, or idle between turns — is not")))
  (testing "neither the conductor's turn nor a chat's is the work moving"
    (is (nil? (doing {:runs [] :sessions [{:name "cond" :conductor true :mid-turn? true}]})))
    (is (nil? (doing {:runs [] :sessions [{:name "chat" :chat true :mid-turn? true}]}))))
  (testing "it answers last: an instruction outstanding or a run's lock says more"
    (is (= :answering
           (:phase (doing {:runs []
                               :sessions [{:name "a" :mid-turn? true}
                                          {:name "b" :up? true :claude {:pending {:token "t"}}}]}))))
    (is (= :running
           (:phase (doing {:runs [(a-run :running true)]
                               :sessions [{:name "a" :mid-turn? true}]})))))
  (is (= "agent · running" (wsv/doing-label {:source :session :phase :running}))))
