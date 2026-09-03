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
