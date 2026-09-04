(ns nido.review.analysis-test
  (:require
   [clojure.string :as str]
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.source.queue :as queue]
   [nido.review.analysis :as analysis]))

(def a-run
  {:run-id "review-abc" :report-path "/r/report.json" :status :converged
   :base "main" :rounds 3 :findings-fixed 5 :findings-remaining 1
   :reviewed-project :brian :reviewed-session "fix/thing" :reviewed-ws-id "ws-1"})

(deftest the-external-ref-names-its-own-adapter
  ;; spawn/external-ref defaults the adapter to :notion. Left to the default,
  ;; every analysis would mint a workstream claiming a Notion identity it has
  ;; not got.
  (let [p (analysis/payload a-run)]
    (is (= :review-run (:adapter p)))
    (is (= "review-abc" (:id p)))))

(deftest the-payload-carries-the-counts-not-just-a-path-to-them
  ;; The run dir is reclaimable; being reclaimed is its normal end state. What
  ;; the payload states itself is what survives that.
  (let [p (analysis/payload a-run)]
    (is (= "converged" (:status p)))
    (is (= 3 (:rounds p)))
    (is (= 5 (:findings-fixed p)))
    (is (= 1 (:findings-remaining p)))
    (is (= "/r/report.json" (:report-path p)))))

(deftest the-reviewed-branch-is-named-never-located
  (let [p (analysis/payload a-run)]
    (is (= "brian" (:reviewed-project p)))
    (is (= "fix/thing" (:reviewed-session p)))
    (is (= "ws-1" (:reviewed-ws-id p)))
    (is (not-any? #(str/includes? (str %) "worktree") (vals p))
        "no path into the reviewed worktree reaches the analysis")))

(deftest the-title-says-which-run-this-was
  (let [p (analysis/payload a-run)]
    (is (str/includes? (:title p) "converged"))
    (is (str/includes? (:title p) "fix/thing"))
    (is (str/includes? (:title p) "3 rounds"))))

(deftest a-run-with-no-session-still-builds-a-payload
  ;; The loop runs anywhere `jj` does, including a checkout nido never
  ;; provisioned. It has a run to analyse either way.
  (let [p (analysis/payload {:run-id "r" :status :clean})]
    (is (= "r" (:id p)))
    (is (nil? (:reviewed-session p)))
    (is (str/includes? (:title p) "0 rounds"))))

(deftest a-dry-run-is-not-analysed
  ;; It drove the stages without letting a fixer touch anything, so it says how
  ;; the loop behaves under a flag rather than how it behaves.
  (is (not (analysis/worth-analysing? :converged true true))))

(deftest a-run-that-left-no-report-is-not-analysed
  ;; Queueing is cheap; what it queues is a worktree and an hour of budget for a
  ;; session whose first act is to open the report.
  (is (not (analysis/worth-analysing? :converged false false))))

(deftest a-failed-review-is-analysed
  ;; It is the outcome nobody reads a report for, which is what makes it the one
  ;; most worth reading — and it still writes one.
  (is (analysis/worth-analysing? :review-failed false true))
  (is (analysis/worth-analysing? :no-progress false true))
  (is (analysis/worth-analysing? :escalated false true)))

(deftest a-run-with-no-terminal-status-is-not-analysed
  (is (not (analysis/worth-analysing? nil false true))))

(deftest a-run-that-reviewed-nothing-is-not-analysed
  ;; Every target came back with a blank manifest, so no reviewer read a line.
  ;; There is no loop behaviour in the run to say anything about, and left in it
  ;; would provision a worktree and an hour of budget on every empty-diff review.
  (is (not (analysis/worth-analysing? :nothing-to-review false true)))
  ;; The status survives a round-trip through the report as a string, which is
  ;; the shape the enqueue site actually reads it in.
  (is (not (analysis/worth-analysing? "nothing-to-review" false true))))

(deftest a-run-that-stopped-on-a-conflicted-stack-is-not-analysed
  ;; The run exists to stop in a second rather than spend six agents on a branch
  ;; it cannot read; queueing a session with an hour of budget to say so would
  ;; give the saving straight back. What it found is a fact about the branch and
  ;; reaches a human through the ledger entry and the lane's escalation.
  (is (not (analysis/worth-analysing? :stack-conflicted false true)))
  (is (not (analysis/worth-analysing? "stack-conflicted" false true)))
  ;; The status the FIX stage produces is a different case: the loop ran, judged
  ;; and repaired, and how it got there is exactly what an analysis reads.
  (is (analysis/worth-analysing? :fix-conflicted false true)))

(defn- with-report
  "a-run pointed at a report file that actually exists — the enqueue gate now
   requires one."
  [run f]
  (let [tmp (fs/create-temp-dir)
        rp  (str (fs/path tmp "report.json"))]
    (try (spit rp "{}") (f (assoc run :report-path rp))
         (finally (fs/delete-tree tmp)))))

(deftest enqueue-writes-nothing-for-a-run-whose-report-is-gone
  (let [called (atom false)]
    (with-redefs [queue/enqueue! (fn [_] (reset! called true) "/q/1")]
      (is (nil? (analysis/enqueue! (assoc a-run :report-path "/definitely/not/here.json"))))
      (is (false? @called)))))

(deftest enqueue-returns-nil-rather-than-throwing-when-the-queue-is-unwritable
  ;; A review that finished is finished. A side record that could not be
  ;; written must never turn it into a failure.
  (with-redefs [queue/enqueue!
                (fn [_] (throw (ex-info "disk full" {})))]
    (with-report a-run #(is (nil? (analysis/enqueue! (assoc % :dry-run? false)))))))

(deftest enqueue-writes-one-envelope-aimed-at-nidos-own-trigger
  (let [seen (atom nil)]
    (with-redefs [queue/enqueue! (fn [e] (reset! seen e) "/q/1.edn")]
      (with-report a-run #(is (= "/q/1.edn" (analysis/enqueue! %))))
      (is (= {:project :nido :trigger :review-analysis} (:target @seen))
          "routing never depends on which project was reviewed")
      (is (= "review-abc" (get-in @seen [:payload :id]))))))

(deftest a-dry-run-writes-no-envelope-at-all
  (let [called (atom false)]
    (with-redefs [queue/enqueue! (fn [_] (reset! called true) "/q/1")]
      (with-report a-run #(is (nil? (analysis/enqueue! (assoc % :dry-run? true)))))
      (is (false? @called)))))
