(ns tasks.nido-scratch-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.scratch :as scratch]
   [nido.session.lifecycle :as lifecycle]
   [tasks.nido-scratch :as task]))

(deftest backfill-births-one-loose-workstream-per-session
  (let [births (atom [])]
    (with-redefs [lifecycle/list-all-data (fn [_] {:sessions [{:name "a"} {:name "b"}]})
                  lifecycle/session-weight (fn [n _] (if (= "a" n) :heavy :light))
                  scratch/birth!          (fn [p n w] (swap! births conj [p n w]) "ws-x")]
      (task/backfill ":project" "brian")
      (is (= [[:brian "a" :heavy] [:brian "b" :light]] @births)
          "one idempotent birth! per existing session, carrying its provisioned weight"))))

(deftest backfill-skips-coordinator-run-worktrees
  ;; Coordinator runs name their worktrees `run-<project>-<trigger>-<suffix>`
  ;; (runs.clj). They own their own workstream via spawn-records!, so backfill
  ;; must NOT adopt them as scratch one-offs — an orphaned (pre-model) run would
  ;; otherwise leak into the Scratch view.
  (let [births (atom [])]
    (with-redefs [lifecycle/list-all-data
                  (fn [_] {:sessions [{:name "refshot"}
                                      {:name "run-brian-triage-teacher-bugs-a4d06d94"}
                                      {:name "feat/course-materials-tab"}]})
                  lifecycle/session-weight (constantly :heavy)
                  scratch/birth! (fn [p n _w] (swap! births conj [p n]) "ws-x")]
      (task/backfill ":project" "brian")
      (is (= [[:brian "refshot"] [:brian "feat/course-materials-tab"]] @births)
          "run-* (coordinator) worktrees are skipped; manual one-offs are backfilled"))))
