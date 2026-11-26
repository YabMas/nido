(ns nido.github.client-test
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is]]
   [nido.github.client :as gh]))

(deftest list-merged-parses-json
  (with-redefs [gh/sh! (fn [args]
                         (is (= ["gh" "pr" "list" "--repo" "brian-study/brian"
                                 "--state" "merged" "--json" "number,url,title,mergedAt,baseRefName"
                                 "--limit" "50"] args))
                         {:exit 0
                          :out "[{\"number\":412,\"url\":\"https://github.com/brian-study/brian/pull/412\",\"title\":\"Fix X\",\"mergedAt\":\"2026-06-12T10:00:00Z\",\"baseRefName\":\"main\"}]"})]
    (let [res (gh/list-merged-prs "brian-study/brian")]
      (is (= :ok (:status res)))
      (is (= [{:number    412
               :url       "https://github.com/brian-study/brian/pull/412"
               :title     "Fix X"
               :merged-at "2026-06-12T10:00:00Z"
               :base      "main"}]
             (:prs res))))))

(deftest list-merged-reports-a-missing-base-as-nil
  ;; An older `gh`, or one whose --json contract drifts, simply omits the field.
  ;; It must arrive as nil rather than as a plausible default — the poller's
  ;; landing guard is what reads it, and it blocks on absence.
  (with-redefs [gh/sh! (fn [_]
                         {:exit 0
                          :out "[{\"number\":7,\"url\":\"u\",\"title\":\"t\",\"mergedAt\":\"2026-06-12T10:00:00Z\"}]"})]
    (is (nil? (:base (first (:prs (gh/list-merged-prs "o/r"))))))))

(deftest list-merged-maps-auth-error
  (with-redefs [gh/sh! (fn [_] {:exit 1 :out "" :err "gh: To get started with GitHub CLI, please run: gh auth login"})]
    (is (= :auth (:error (gh/list-merged-prs "o/r"))))))

(deftest list-merged-maps-generic-error
  (with-redefs [gh/sh! (fn [_] {:exit 1 :out "" :err "could not resolve to a Repository"})]
    (is (= :gh (:error (gh/list-merged-prs "o/r"))))))

(deftest list-assigned-issues-parses-and-shapes
  (with-redefs [gh/sh! (fn [args]
                         (is (= ["gh" "issue" "list" "--repo" "o/r"
                                 "--assignee" "@me" "--state" "open"
                                 "--json" "number,url,title" "--limit" "100"] args))
                         {:exit 0
                          :out "[{\"number\":42,\"url\":\"u\",\"title\":\"t\"}]"
                          :err ""})]
    (is (= {:status :ok :issues [{:number 42 :url "u" :title "t"}]}
           (gh/list-assigned-issues "o/r" "@me")))))

(deftest list-assigned-issues-flags-auth-errors
  (with-redefs [gh/sh! (fn [_] {:exit 1 :out "" :err "gh auth login required"})]
    (is (= :auth (:error (gh/list-assigned-issues "o/r" "@me"))))))

(deftest view-issue-parses-body
  (with-redefs [gh/sh! (fn [args]
                         (is (= ["gh" "issue" "view" "42" "--repo" "o/r"
                                 "--json" "number,url,title,body"] args))
                         {:exit 0
                          :out "{\"number\":42,\"url\":\"u\",\"title\":\"t\",\"body\":\"do the thing\"}"
                          :err ""})]
    (is (= {:status :ok :issue {:number 42 :url "u" :title "t" :body "do the thing"}}
           (gh/view-issue "o/r" 42)))))

(deftest view-issue-flags-auth-errors
  (with-redefs [gh/sh! (fn [_] {:exit 1 :out "" :err "gh auth required"})]
    (is (= :auth (:error (gh/view-issue "o/r" 42))))))

(deftest squash-pr-readies-a-draft-once-then-squash-merges
  (let [calls (atom [])]
    (with-redefs [gh/sh! (fn [args] (swap! calls conj (vec (take 3 args)))
                           (if (= "view" (nth args 2)) {:exit 0 :out "true\n"} {:exit 0 :out ""}))]
      (is (= {:status :ok :via :squash} (gh/squash-pr! "o/r" 5)))
      (is (= [["gh" "pr" "view"] ["gh" "pr" "ready"] ["gh" "pr" "merge"]] @calls))))
  (let [calls (atom [])]
    (with-redefs [gh/sh! (fn [args] (swap! calls conj (nth args 2))
                           (case (nth args 2) "view" {:exit 0 :out "false"} {:exit 1 :err "not mergeable"}))]
      (is (= {:error :gh :detail "not mergeable"} (gh/squash-pr! "o/r" 5)) "gh's refusal in its own words")
      (is (= ["view" "merge"] @calls) "a ready PR is not readied"))))

(deftest merge-queue-timeline-reads-the-merged-flag-and-queue-events-in-one-read
  (let [calls (atom [])]
    (with-redefs [gh/sh! (fn [args] (swap! calls conj args)
                           {:exit 0
                            :out  (str "{\"data\":{\"repository\":{\"pullRequest\":{\"merged\":false,\"timelineItems\":{\"nodes\":["
                                       "{\"__typename\":\"AddedToMergeQueueEvent\",\"id\":\"A1\",\"createdAt\":\"t1\"},"
                                       "{\"__typename\":\"RemovedFromMergeQueueEvent\",\"id\":\"R1\",\"createdAt\":\"t2\",\"reason\":\"CI failed\"},"
                                       "{\"__typename\":\"RemovedFromMergeQueueEvent\",\"id\":\"R2\",\"createdAt\":\"t3\",\"reason\":null}]}}}}}")})]
      (is (= {:status :ok :merged? false
              :events [{:type :added :id "A1" :at "t1"}
                       {:type :removed :id "R1" :at "t2" :reason "CI failed"}
                       {:type :removed :id "R2" :at "t3"}]}
             (gh/merge-queue-timeline "o/r" 5)))
      (is (= 1 (count @calls)) "one request answers both")
      (is (some #{"owner=o"} (first @calls)))
      (is (some #{"number=5"} (first @calls)))))
  (with-redefs [gh/sh! (fn [_] {:exit 1 :out "" :err "gh auth login required"})]
    (is (= :auth (:error (gh/merge-queue-timeline "o/r" 5))))))

(deftest merge-queue-timeline-reads-every-page-of-the-queue-history
  (let [calls (atom [])
        page  (fn [merged nodes next-cursor]
                {:exit 0
                 :out  (json/generate-string
                        {:data {:repository {:pullRequest
                                             {:merged merged
                                              :timelineItems {:nodes    nodes
                                                              :pageInfo {:hasNextPage (some? next-cursor)
                                                                         :endCursor   next-cursor}}}}}})})]
    (with-redefs [gh/sh! (fn [args]
                           (swap! calls conj args)
                           (if (some #{"cursor=C1"} args)
                             (page true [{:__typename "RemovedFromMergeQueueEvent" :id "R1" :createdAt "t2" :reason "CI failed"}] nil)
                             (page false [{:__typename "AddedToMergeQueueEvent" :id "A1" :createdAt "t1"}] "C1")))]
      (is (= {:status :ok :merged? true
              :events [{:type :added :id "A1" :at "t1"}
                       {:type :removed :id "R1" :at "t2" :reason "CI failed"}]}
             (gh/merge-queue-timeline "o/r" 5))
          "every page's events, oldest first, and the merged flag of the newest read")
      (is (= 2 (count @calls)))
      (is (not-any? #(re-find #"^cursor=" %) (first @calls)) "the first page asks from the start"))))
