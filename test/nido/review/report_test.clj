(ns nido.review.report-test
  (:require
   [clojure.test :refer [deftest is testing]]
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

(deftest a-converged-target-keeps-its-place-in-the-stack
  ;; to-review hands back two vectors, and the rows used to be concatenated in
  ;; that order — so a layer moved to the bottom of the list in the round it
  ;; converged, which reads as leaving the stack rather than as being unchanged.
  (let [r (drive
           [{:event :run-started :run-id "r" :cwd "/w" :base "main" :at "t0"}
            {:event :phase-started :iter 1 :phase :review :at "t1"}
            {:event :targets-resolved :iter 1 :at "t1a" :base-rev "B" :files []
             :targets [{:label "two" :index 2 :stack? false :status "pending"}
                       {:label "stack" :stack? true :status "pending"}
                       {:label "one" :index 1 :stack? false :status "skipped"}
                       {:label "three" :index 3 :stack? false :status "skipped"}]}
            {:event :phase-finished :iter 1 :phase :review :at "t2"
             :ctx {:findings []
                   :reviews [{:target {:label "two" :index 2}}
                             {:target {:label "stack" :stack? true}}]
                   :skipped [{:label "one" :index 1} {:label "three" :index 3}]}}])
        review (first (:phases (first (:rounds r))))]
    (is (= ["one" "two" "three" "stack"] (mapv :label (:layers review)))
        "the finished payload is in stack order, composition last")))

(deftest seeded-rows-are-in-the-same-order-the-finished-ones-will-be
  (let [r (drive
           [{:event :run-started :run-id "r" :cwd "/w" :base "main" :at "t0"}
            {:event :phase-started :iter 1 :phase :review :at "t1"}
            {:event :targets-resolved :iter 1 :at "t1a" :base-rev "B" :files []
             :targets [{:label "two" :index 2 :stack? false :status "pending"}
                       {:label "stack" :stack? true :status "pending"}
                       {:label "one" :index 1 :stack? false :status "skipped"}]}])
        review (first (:phases (first (:rounds r))))]
    (is (= ["one" "two" "stack"] (mapv :label (:layers review))))
    (is (= ["skipped" "pending" "pending"] (mapv :status (:layers review)))
        "a target sits in the same place from the moment it is named")))

(deftest an-unnumbered-row-keeps-the-order-it-arrived-in
  ;; A report written before :index existed, and the stack-of-one case that has
  ;; no layers to number. Stable sort, so nothing is shuffled.
  (is (= ["b" "a" "c"]
         (mapv :label (report/in-stack-order [{:label "b"} {:label "a"} {:label "c"}]))))
  (is (= ["b" "a" "stack"]
         (mapv :label (report/in-stack-order
                       [{:label "b"} {:label "stack" :stack? true} {:label "a"}])))
      "the composition still sorts last without any numbers around it"))

(deftest warden-and-fix-fill-in-the-round
  (let [r (drive
           [{:event :run-started :run-id "r" :cwd "/w" :base "main" :at "t0"}
            {:event :phase-started :iter 1 :phase :review :at "t1"}
            {:event :phase-finished :iter 1 :phase :review :at "t2"
             :ctx {:findings [{:title "b"}] :overall-correctness "x"
                   :base-rev "B" :manifest "a"}}
            {:event :phase-started :iter 1 :phase :warden :at "t3"}
            {:event :phase-finished :iter 1 :phase :warden :at "t4"
             :ctx {:warden {:decision :continue :reason "real"
                             :rulings [{:id "aa11" :owner-layer "a" :disposition :fix}]}}}
            {:event :phase-started :iter 1 :phase :fix :at "t5"}
            {:event :phase-finished :iter 1 :phase :fix :at "t6"
             :ctx {:history [{:iter 1 :fixes [{:layer "a" :commit "abc1234567"}]
                            :fixed-count 1}]}}])
        phases (:phases (first (:rounds r)))
        warden  (some #(when (= "warden" (:phase %)) %) phases)
        fix    (some #(when (= "fix" (:phase %)) %) phases)]
    (is (= "continue" (:decision warden)))
    (is (= [{:id "aa11" :owner-layer "a" :disposition :fix}] (:rulings warden)))
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

(deftest a-round-that-read-nothing-is-not-clean
  ;; Both rounds end with an empty finding list; only one of them had a reviewer
  ;; look at anything. Nothing downstream can recover the difference, so the
  ;; round has to carry it.
  (let [r (drive
           [{:event :run-started :run-id "r" :cwd "/w" :base "main" :at "t0"}
            {:event :phase-started :iter 1 :phase :review :at "t1"}
            {:event :phase-finished :iter 1 :phase :review :at "t2"
             :ctx {:findings []
                   :reviews [{:target {:label "stack" :stack? true}
                              :status :nothing-to-review}]}}
            {:event :run-finalized :status :nothing-to-review :ctx {} :at "t3"}])
        row (first (:layers (first (:phases (first (:rounds r))))))]
    (is (= "nothing-to-review" (:status (first (:rounds r)))))
    (is (= "nothing-to-review" (:status row)))
    (is (nil? (:findings row)) "no finding tally on a target nobody read")))

(deftest one-empty-layer-among-several-is-still-a-clean-round
  ;; The gate is EVERY read target, not any: a stack routinely holds one layer
  ;; whose diff a rebase emptied, and calling that round unreviewed would hide a
  ;; genuine clean bill on the layers that did have content.
  (let [r (drive
           [{:event :run-started :run-id "r" :cwd "/w" :base "main" :at "t0"}
            {:event :phase-started :iter 1 :phase :review :at "t1"}
            {:event :phase-finished :iter 1 :phase :review :at "t2"
             :ctx {:findings []
                   :reviews [{:target {:label "one" :index 1} :status :nothing-to-review}
                             {:target {:label "two" :index 2} :status :clean}]}}
            {:event :run-finalized :status :clean :ctx {} :at "t3"}])]
    (is (= "clean" (:status (first (:rounds r)))))))

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

;; ---- rows moving while the phase is still running ------------------------

(def ^:private resolved
  [{:event :run-started :run-id "r" :cwd "/w" :base "main" :at "t0"}
   {:event :phase-started :iter 1 :phase :review :at "t1"}
   {:event :targets-resolved :iter 1 :at "t1a" :base-rev "BASE" :files []
    :targets [{:label "lower" :stack? false :status "pending"}
              {:label "upper" :stack? false :status "pending"}
              {:label "stack" :stack? true  :status "pending"}]}])

(defn- rows [r]
  (:layers (first (:phases (first (:rounds r))))))

(deftest a-target-moves-its-own-row-and-only-its-own
  (let [r (drive (concat resolved
                         [{:event :target-moved :iter 1 :at "t2"
                           :label "lower" :status "running"}
                          {:event :target-moved :iter 1 :at "t3"
                           :label "lower" :status "reviewed" :findings 2}]))]
    (is (= ["reviewed" "pending" "pending"] (mapv :status (rows r))))
    (is (= 2 (:findings (first (rows r)))))))

(deftest a-row-never-moves-backwards
  ;; Targets report from worker threads; a late `running` arriving after the
  ;; `reviewed` it belongs to must not un-finish a target that is done.
  (let [r (drive (concat resolved
                         [{:event :target-moved :iter 1 :at "t2"
                           :label "lower" :status "reviewed" :findings 1}
                          {:event :target-moved :iter 1 :at "t3"
                           :label "lower" :status "running"}]))]
    (is (= "reviewed" (:status (first (rows r)))))
    (is (= 1 (:findings (first (rows r)))))))

(deftest a-move-for-an-unknown-label-changes-nothing
  (let [r (drive (concat resolved
                         [{:event :target-moved :iter 1 :at "t2"
                           :label "not-a-target" :status "reviewed" :findings 9}]))]
    (is (= ["pending" "pending" "pending"] (mapv :status (rows r))))))

(deftest a-skipped-row-is-not-reopened-by-a-move
  (let [r (drive (concat [{:event :run-started :run-id "r" :cwd "/w" :base "main" :at "t0"}
                          {:event :phase-started :iter 1 :phase :review :at "t1"}
                          {:event :targets-resolved :iter 1 :at "t1a" :base-rev "B" :files []
                           :targets [{:label "done" :stack? false :status "skipped"}]}]
                         [{:event :target-moved :iter 1 :at "t2"
                           :label "done" :status "running"}]))]
    (is (= "skipped" (:status (first (rows r)))))))

;; ── A record loop's rounds ──────────────────────────────────────────────────

(deftest a-judge-phase-keeps-its-verdict-and-what-it-found
  (let [r (-> (report/init {:run-id "r" :cwd "/w" :base nil :started-at "t0"})
              (report/apply-event {:event :phase-started :iter 1 :phase :judge :at "t1"} nil)
              (report/apply-event {:event :phase-finished :iter 1 :phase :judge :at "t2"
                                   :ctx {:record {:verdict :falsified}
                                         :findings [{:cites ["a"] :claim "x"}]}}
                                  nil))
        ph (first (:phases (first (:rounds r))))]
    (is (= "falsified" (:verdict ph)))
    (is (= 1 (count (:findings ph))))))

(deftest an-amend-phase-keeps-what-it-gave-up
  (let [r (-> (report/init {:run-id "r" :cwd "/w" :base nil :started-at "t0"})
              (report/apply-event {:event :phase-started :iter 1 :phase :amend :at "t1"} nil)
              (report/apply-event {:event :phase-finished :iter 1 :phase :amend :at "t2"
                                   :ctx {:retreats [{:what :health-dropped :detail "d"}]}}
                                  nil))
        ph (first (:phases (first (:rounds r))))]
    (is (= [{:what :health-dropped :detail "d"}] (:retreats ph)))))

(defn- record-round-status
  [judge-findings retreats]
  (let [r (-> (report/init {:run-id "r" :cwd "/w" :base nil :started-at "t0"})
              (report/apply-event {:event :phase-started :iter 1 :phase :judge :at "t1"} nil)
              (report/apply-event {:event :phase-finished :iter 1 :phase :judge :at "t2"
                                   :ctx {:record {:verdict :falsified}
                                         :findings judge-findings}} nil))
        r (if retreats
            (-> r
                (report/apply-event {:event :phase-started :iter 1 :phase :amend :at "t3"} nil)
                (report/apply-event {:event :phase-finished :iter 1 :phase :amend :at "t4"
                                     :ctx {:retreats retreats}} nil))
            r)]
    (:status (first (:rounds (report/apply-event
                              r {:event :run-finalized :status :accurate :ctx {} :at "t5"} nil))))))

(deftest a-record-round-that-found-nothing-is-clean
  (is (= "clean" (record-round-status [] nil))))

(deftest a-record-round-that-gave-something-up-says-so
  (is (= "weakened" (record-round-status [{:cites ["a"]}] [{:what :health-dropped :detail "d"}]))))

(deftest a-record-round-that-amended-cleanly-continues
  (is (= "continued" (record-round-status [{:cites ["a"]}] []))))

(deftest a-record-round-with-no-amendment-yet-is-not-mistaken-for-a-review-round
  (is (= "ended" (record-round-status [{:cites ["a"]}] nil))))

(deftest a-reporter-that-is-no-layer-still-gets-a-row
  (testing "the mechanical design reviewer contributes findings but reviews the worktree rather
            than a range, so it is in no :reviews entry. Without a row its findings would be
            listed while every counted row read zero, which is the one way a summary can be
            worse than no summary."
    (let [rows (report/review-layers
                {:reviews  [{:target {:label "stack" :stack? true}}]
                 :skipped  []
                 :findings [{:from-layer "stack"} {:from-layer "design"} {:from-layer "design"}]})
          by   (into {} (map (juxt :label identity)) rows)]
      (is (= 2 (count rows)))
      (is (= 1 (:findings (get by "stack"))))
      (is (= 2 (:findings (get by "design"))))
      (is (= "reported" (:status (get by "design")))
          "reported, not reviewed — it looked at no range"))))

(deftest a-skipped-layer-is-not-duplicated-as-a-reporter
  (let [rows (report/review-layers
              {:reviews  []
               :skipped  [{:label "base"}]
               :findings [{:from-layer "base"}]})]
    (is (= 1 (count rows)) "a converged layer's carried findings do not mint a second row")))

(deftest the-warden-phase-keeps-the-cross-round-identity
  ;; :handle and :same-as are what no-progress?, unfixable and the answered
  ;; cache all key on. Dropped from the report, every conclusion those reach is
  ;; unverifiable from the artifact that is supposed to explain the run.
  (let [r (drive
           [{:event :run-started :run-id "r" :cwd "/w" :base "main" :at "t0"}
            {:event :phase-started :iter 1 :phase :review :at "t1"}
            {:event :phase-finished :iter 1 :phase :review :at "t2" :ctx {:findings []}}
            {:event :phase-started :iter 1 :phase :warden :at "t3"}
            {:event :phase-finished :iter 1 :phase :warden :at "t4"
             :ctx {:warden {:decision :continue
                            :rulings [{:id "f9" :handle "h1" :same-as "f2"
                                       :owner-layer "core" :disposition :fix
                                       :because "same defect as round 2"}]}}}])
        ruling (-> r :rounds first :phases (nth 1) :rulings first)]
    (is (= "h1" (:handle ruling)))
    (is (= "f2" (:same-as ruling)))))

(deftest a-skipped-row-carries-the-evidence-for-its-own-skip
  ;; "skipped" alone asserts a layer needed no review and offers nothing to
  ;; check it against — and a wrongly cached convergence hides a finding for as
  ;; long as the layer sits unchanged.
  (let [r (drive
           [{:event :run-started :run-id "r" :cwd "/w" :base "main" :at "t0"}
            {:event :phase-started :iter 1 :phase :review :at "t1"}
            {:event :phase-finished :iter 1 :phase :review :at "t2"
             :ctx {:findings [] :reviews []
                   :skipped [{:label "core" :index 1
                              :patch-hash "abc123" :converged-at 4}]}}])
        row (-> r :rounds first :phases first :layers first)]
    (is (= "skipped" (:status row)))
    (is (= "abc123" (:patch-hash row)))
    (is (= 4 (:converged-at row)))))

(deftest the-terminal-clean-round-keeps-its-correctness-verdict
  ;; It was the only round that dropped it, and it is the round the verdict is
  ;; most worth keeping for: the only evidence anyone looked.
  (let [r (drive
           [{:event :run-started :run-id "r" :cwd "/w" :base "main" :at "t0"}
            {:event :phase-started :iter 1 :phase :review :at "t1"}
            {:event :phase-finished :iter 1 :phase :review :at "t2"
             :ctx {:findings [] :overall-correctness "correct"
                   :reviews [{:target {:label "stack" :stack? true} :status :clean}]}}
            {:event :run-finalized :status :clean :ctx {} :at "t3"}])]
    (is (= "correct" (-> r :rounds first :phases first :overall-correctness)))))

(deftest a-round-where-every-fixer-declined-still-records-why
  ;; Such a round writes no history entry, so the reasons have to come off the
  ;; ctx — otherwise the one round whose explanation a reader needs is the one
  ;; round that has none.
  (let [r (drive
           [{:event :run-started :run-id "r" :cwd "/w" :base "main" :at "t0"}
            {:event :phase-started :iter 1 :phase :review :at "t1"}
            {:event :phase-finished :iter 1 :phase :review :at "t2" :ctx {:findings []}}
            {:event :phase-started :iter 1 :phase :fix :at "t3"}
            {:event :phase-finished :iter 1 :phase :fix :at "t4"
             :ctx {:iter 1 :history []
                   :declined [{:layer "core" :ran? true :reason "spans two layers"}]}}])
        ph (-> r :rounds first :phases (nth 1))]
    (is (= [{:layer "core" :ran? true :reason "spans two layers"}] (:declined ph)))))
