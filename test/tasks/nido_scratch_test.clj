(ns tasks.nido-scratch-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.scratch :as scratch]
   [nido.session.lifecycle :as lifecycle]
   [tasks.nido-scratch :as task]))

(deftest backfill-births-one-loose-workstream-per-session
  (let [births (atom [])]
    (with-redefs [lifecycle/list-all-data (fn [_] {:sessions [{:name "a"} {:name "b"}]})
                  scratch/birth!          (fn [p n] (swap! births conj [p n]) "ws-x")]
      (task/backfill ":project" "brian")
      (is (= [[:brian "a"] [:brian "b"]] @births)
          "one idempotent birth! per existing session, keyword project"))))

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
                  scratch/birth! (fn [p n] (swap! births conj [p n]) "ws-x")]
      (task/backfill ":project" "brian")
      (is (= [[:brian "refshot"] [:brian "feat/course-materials-tab"]] @births)
          "run-* (coordinator) worktrees are skipped; manual one-offs are backfilled"))))
