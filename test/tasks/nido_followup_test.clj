(ns tasks.nido-followup-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [nido.session.lifecycle :as lifecycle]
   [tasks.nido-followup :as followup]))

(deftest entry-of-coerces-kwargs-into-notion-ready-values
  ;; bb kwargs arrive EDN-parsed, so :kind cleanup is the keyword :cleanup while
  ;; the vocabularies are strings. Without coercion every select field would
  ;; fail validation for a caller who typed the documented form.
  (with-redefs [lifecycle/session-from-cwd (constantly nil)
                lifecycle/session-home-coords-from-cwd (constantly ["nido" "task-splitting"])]
    (let [entry (@#'followup/entry-of {:title      "drop the shim"
                                       :kind       :cleanup
                                       :decay      :flat
                                       :cold-start :cheap
                                       :effort     :S
                                       :reason     "revealed not caused"})]
      (is (= "cleanup" (:kind entry)))
      (is (= "flat" (:decay entry)))
      (is (= "cheap" (:cold-start entry)))
      (is (= "S" (:effort entry)))
      (testing "unknown kwargs are dropped rather than sent to Notion"
        ;; :effort/:status are absent here — defaults land in ->properties, not
        ;; in entry-of, so an unpassed field stays unpassed at this layer.
        (is (= #{:title :kind :decay :cold-start :reason :origin :project}
               (set (keys (@#'followup/entry-of
                           {:title "t" :kind :cleanup :decay :flat :cold-start :cheap
                            :reason "r" :nonsense "ignored"})))))))))

(deftest entry-of-derives-origin-and-project-from-a-session-home-cwd
  (with-redefs [lifecycle/session-from-cwd (constantly nil)
                lifecycle/session-home-coords-from-cwd (constantly ["nido" "task-splitting"])]
    (let [entry (@#'followup/entry-of {:title "t" :reason "r"})]
      (is (= "nido/task-splitting" (:origin entry)))
      (is (= "nido" (:project entry))))))

(deftest entry-of-derives-from-a-worktree-cwd-too
  (with-redefs [lifecycle/session-from-cwd (constantly {:project "brian" :session "feat/x"})
                lifecycle/session-home-coords-from-cwd (constantly nil)]
    (let [entry (@#'followup/entry-of {:title "t" :reason "r"})]
      (is (= "brian/feat/x" (:origin entry))
          "a slash-namespaced session name survives intact")
      (is (= "brian" (:project entry))))))

(deftest entry-of-never-overwrites-an-explicit-origin
  (with-redefs [lifecycle/session-from-cwd (constantly {:project "brian" :session "feat-x"})
                lifecycle/session-home-coords-from-cwd (constantly nil)]
    (let [entry (@#'followup/entry-of
                 {:title "t" :reason "r"
                  :origin "https://github.com/o/r/pull/12"})]
      (is (= "https://github.com/o/r/pull/12" (:origin entry))
          "a PR URL is a better origin than the session and must win"))))

(deftest entry-of-still-files-outside-a-session
  (with-redefs [lifecycle/session-from-cwd (constantly nil)
                lifecycle/session-home-coords-from-cwd (constantly nil)]
    (let [entry (@#'followup/entry-of {:title "t" :reason "r"})]
      (is (= "(filed outside a session)" (:origin entry))
          "origin is required, so an undetectable cwd still yields a value")
      (is (nil? (:project entry))))))
