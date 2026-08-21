(ns nido.review.report-test
  (:require
   [clojure.test :refer [deftest is]]
   [cheshire.core :as json]
   [babashka.fs :as fs]
   [nido.review.report :as report]))

(def clock (constantly (java.time.Instant/parse "2026-06-30T14:00:00Z")))

(defn- drive [events]
  (reduce (fn [r e] (report/apply-event r e clock))
          nil events))

(deftest init-sets-running-shape
  (let [r (report/init {:run-id "review-1" :cwd "/w" :base "main"
                        :started-at "2026-06-30T14:00:00Z"})]
    (is (= 1 (:schema r)))
    (is (= "running" (:status r)))
    (is (= {:cwd "/w" :base "main" :base-rev nil :files []} (:target r)))
    (is (= [] (:rounds r)))
    (is (nil? (:summary r)))))

(deftest review-round-records-findings-and-target
  (let [r (drive
           [{:event :run-started :run-id "r" :cwd "/w" :base "main" :at "t0"}
            {:event :phase-started :iter 1 :phase :review :at "t1"}
            {:event :targets-resolved :iter 1 :at "t1a" :base-rev "BASE"
             :files ["src/a.clj" "src/b.clj"]
             :targets [{:label "one" :stack? false :status "pending"}
                       {:label "stack" :stack? true :status "pending"}]}
            {:event :phase-finished :iter 1 :phase :review :at "t2"
             :ctx {:findings [{:title "bug" :file "/w/a.clj" :line-start 5 :line-end 6}]
                   :overall-correctness "incorrect"
                   :base-rev "BASE" :manifest "src/a.clj\nsrc/b.clj"}}])
        round (first (:rounds r))
        review (first (:phases round))]
    (is (= 1 (:round round)))
    (is (= "running" (:status round)))
    (is (= "review" (:phase review)))
    (is (= "ok" (:status review)))
    (is (= "incorrect" (:overall-correctness review)))
    (is (= 1 (count (:findings review))))
    (is (= "BASE" (:base-rev (:target r))))
    (is (= ["src/a.clj" "src/b.clj"] (:files (:target r))))
    (is (= 1 (:layers (:target r)))
        "the stack target is not a layer")))

(deftest targets-resolved-populates-the-target-before-any-review-finishes
  (let [r (drive
           [{:event :run-started :run-id "r" :cwd "/w" :base "main" :at "t0"}
            {:event :phase-started :iter 1 :phase :review :at "t1"}
            {:event :targets-resolved :iter 1 :at "t1a" :base-rev "BASE"
             :files ["src/a.clj" "src/b.clj" "src/c.clj"]
             :targets [{:label "lower" :stack? false :status "pending"}
                       {:label "upper" :stack? false :status "skipped"}
                       {:label "stack" :stack? true :status "pending"}]}])
        review (first (:phases (first (:rounds r))))]
    ;; This is the state an INTERRUPTED run leaves behind: the review phase has
    ;; not finished and never will, and the report still says what it was for.
    (is (= "running" (:status review)))
    (is (= "BASE" (:base-rev (:target r))))
    (is (= 3 (count (:files (:target r)))))
    (is (= 2 (:layers (:target r))))
    (is (= ["lower" "upper" "stack"] (mapv :label (:layers review))))
    (is (= ["pending" "skipped" "pending"] (mapv :status (:layers review))))))

(deftest a-finished-review-replaces-the-seeded-rows-with-its-own
  (let [r (drive
           [{:event :run-started :run-id "r" :cwd "/w" :base "main" :at "t0"}
            {:event :phase-started :iter 1 :phase :review :at "t1"}
            {:event :targets-resolved :iter 1 :at "t1a" :base-rev "BASE"
             :files [] :targets [{:label "one" :stack? false :status "pending"}]}
            {:event :phase-finished :iter 1 :phase :review :at "t2"
             :ctx {:findings [{:title "bug" :from-layer "one"}]
                   :reviews  [{:target {:label "one"}}]
                   :skipped  []}}])
        review (first (:phases (first (:rounds r))))]
    (is (= [{:label "one" :status "reviewed" :stack? false :findings 1}]
           (:layers review))
        "the finished payload owns the rows; the seed only carries the round")))

(deftest arbiter-and-fix-fill-in-the-round
  (let [r (drive
           [{:event :run-started :run-id "r" :cwd "/w" :base "main" :at "t0"}
            {:event :phase-started :iter 1 :phase :review :at "t1"}
            {:event :phase-finished :iter 1 :phase :review :at "t2"
             :ctx {:findings [{:title "b"}] :overall-correctness "x"
                   :base-rev "B" :manifest "a"}}
            {:event :phase-started :iter 1 :phase :arbiter :at "t3"}
            {:event :phase-finished :iter 1 :phase :arbiter :at "t4"
             :ctx {:arbiter {:decision :continue :reason "real"
                             :rulings [{:id "aa11" :owner-layer "a" :disposition :fix}]}}}
            {:event :phase-started :iter 1 :phase :fix :at "t5"}
            {:event :phase-finished :iter 1 :phase :fix :at "t6"
             :ctx {:history [{:iter 1 :fixes [{:layer "a" :commit "abc1234567"}]
                            :fixed-count 1}]}}])
        phases (:phases (first (:rounds r)))
        arbiter  (some #(when (= "arbiter" (:phase %)) %) phases)
        fix    (some #(when (= "fix" (:phase %)) %) phases)]
    (is (= "continue" (:decision arbiter)))
    (is (= [{:id "aa11" :owner-layer "a" :disposition :fix}] (:rulings arbiter)))
    (is (= [{:layer "a" :commit "abc1234567"}] (:fixes fix)))
    (is (= 1 (:fixed-count fix)))))

(deftest new-round-closes-the-previous-as-continued
  (let [r (drive
           [{:event :run-started :run-id "r" :cwd "/w" :base "main" :at "t0"}
            {:event :phase-started :iter 1 :phase :review :at "t1"}
            {:event :phase-finished :iter 1 :phase :review :at "t2"
             :ctx {:findings [{:title "b"}] :base-rev "B" :manifest "a"}}
            {:event :phase-started :iter 1 :phase :fix :at "t3"}
            {:event :phase-finished :iter 1 :phase :fix :at "t4"
             :ctx {:history [{:iter 1 :fixes [{:layer nil :commit "c1"}] :fixed-count 1}]}}
            {:event :phase-started :iter 2 :phase :review :at "t5"}])]
    (is (= "continued" (:status (first (:rounds r)))))
    (is (= "t4" (:ended-at (first (:rounds r)))))
    (is (= 2 (count (:rounds r))))
    (is (= "running" (:status (second (:rounds r)))))))

(deftest review-error-fails-round-and-run
  (let [r (drive
           [{:event :run-started :run-id "r" :cwd "/w" :base "main" :at "t0"}
            {:event :phase-started :iter 1 :phase :review :at "t1"}
            {:event :phase-errored :iter 1 :phase :review :at "t2"
             :error "codex review failed"}
            {:event :run-finalized :status :review-failed :ctx {} :at "t3"}])
        review (first (:phases (first (:rounds r))))]
    (is (= "error" (:status review)))
    (is (= "codex review failed" (:error review)))
    (is (= "failed" (:status (first (:rounds r)))))
    (is (= "review-failed" (:status r)))
    (is (= "t3" (:ended-at r)))))

(deftest clean-review-finalizes-clean
  (let [r (drive
           [{:event :run-started :run-id "r" :cwd "/w" :base "main" :at "t0"}
            {:event :phase-started :iter 1 :phase :review :at "t1"}
            {:event :phase-finished :iter 1 :phase :review :at "t2"
             :ctx {:findings []}}
            {:event :run-finalized :status :clean :ctx {} :at "t3"}])]
    (is (= "clean" (:status (first (:rounds r)))))
    (is (= "clean" (:status r)))
    (is (= {:rounds 1 :findings-fixed 0 :final-status "clean"} (:summary r)))))

(deftest fix-noop-round-does-not-inherit-prior-commit
  (let [r (drive
           [{:event :run-started :run-id "r" :cwd "/w" :base "main" :at "t0"}
            {:event :phase-started :iter 1 :phase :review :at "t1"}
            {:event :phase-finished :iter 1 :phase :review :at "t2"
             :ctx {:findings [{:title "b"}] :base-rev "B" :manifest "a"}}
            {:event :phase-started :iter 1 :phase :fix :at "t3"}
            {:event :phase-finished :iter 1 :phase :fix :at "t4"
             :ctx {:history [{:iter 1 :commit "c1" :fixed-count 2}]}}
            {:event :phase-started :iter 2 :phase :review :at "t5"}
            {:event :phase-finished :iter 2 :phase :review :at "t6"
             :ctx {:findings [{:title "b"}] :base-rev "B" :manifest "a"}}
            {:event :phase-started :iter 2 :phase :fix :at "t7"}
            ;; round-2 fix is a NOOP: history STILL has only the iter-1 entry
            {:event :phase-finished :iter 2 :phase :fix :at "t8"
             :ctx {:history [{:iter 1 :commit "c1" :fixed-count 2}]}}
            {:event :run-finalized :status :fix-noop :at "t9"}])
        r2-fix (->> (:rounds r) second :phases (some #(when (= "fix" (:phase %)) %)))]
    (is (nil? (:commit r2-fix)) "round-2 noop fix must not inherit round-1's commit")
    (is (nil? (:fixed-count r2-fix)) "round-2 noop fix has no fixed-count")
    (is (= 2 (:findings-fixed (:summary r))) "summary counts only the one real fix")))

(deftest persist!-writes-atomic-valid-json
  (let [dir (str (fs/create-temp-dir))
        path (str (fs/path dir "report.json"))
        r (report/init {:run-id "r" :cwd "/w" :base "main" :started-at "t0"})]
    (report/persist! r path)
    (is (fs/exists? path))
    (is (not (fs/exists? (str path ".tmp"))) "tmp file is renamed away")
    (let [parsed (json/parse-string (slurp path) true)]
      (is (= "running" (:status parsed)))
      (is (= 1 (:schema parsed))))))
