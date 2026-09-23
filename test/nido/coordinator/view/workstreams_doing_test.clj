(ns nido.coordinator.view.workstreams-doing-test
  "The one phrase every surface renders. It is shared so that the board, the
   pane, the TUI and the CLI cannot describe one workstream four ways."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.record.activity :as activity]
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
  (is (= {:round 2 :phase :fix :findings 2} (wsv/round-progress a-diff-review-report))
      "the current round, the phase running in it, and what its review raised —
       never the first round's findings, which that round already worked through")
  (is (= "review round 2 · fixing · 2 findings"
         (wsv/doing-label {:source :claim :kind :diff-review
                           :progress (wsv/round-progress a-diff-review-report)}))))

(deftest a-record-round-counts-its-judges-findings
  (let [p (wsv/round-progress
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
  (is (nil? (wsv/round-progress {:rounds []})))
  (is (= "reviewing the diff" (wsv/doing-label {:source :claim :kind :diff-review}))
      "and a claim whose report could not be read keeps the kind's own phrase"))

(deftest a-review-still-judging-says-how-far-it-has-read
  (let [p (wsv/round-progress
           {:rounds [{:round 3 :status "running"
                      :phases [{:phase "review" :status "running"
                                :layers [{:label "a" :status "running" :findings nil}
                                         {:label "b" :status "reviewed" :findings 1}
                                         {:label "c" :status "reviewed" :findings 0}]}]}]})]
    (is (= {:round 3 :phase :review :findings 1 :judged 2 :of 3} p))
    (is (= "review round 3 · reviewing 2/3 · 1 finding"
           (wsv/doing-label {:source :claim :kind :diff-review :progress p}))
        "so a count of nothing yet is not read as a clean round")))
