(ns tasks.nido-review-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [nido.coordinator.report :as report]
   [nido.coordinator.session :as csession]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]
   [nido.review.loop :as rloop]
   [nido.review.record :as record]
   [nido.review.stages :as stages]
   [nido.session.lifecycle :as lifecycle]
   [tasks.nido-review :as t]))

(defn- with-tmp-nido-root
  "Every `loop-cmd` test drives the REAL command, so every side effect it has
   lands on the real filesystem unless the root is moved. That is not a
   hypothetical: `loop-cmd*` ends by enqueuing the run for analysis, and without
   this the suite wrote live envelopes into ~/.nido/coordinator/queue/ that the
   running daemon drained and spawned agent sessions for — one per test, every
   time anyone ran `bb nido:test`.

   Redirecting the root is the fix rather than stubbing the one function that
   bit, because the hazard is structural: a command test that writes wherever
   the command writes will bite again the next time `loop-cmd*` grows a side
   effect."
  [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f))
      (finally (fs/delete-tree tmp)))))

(use-fixtures :each with-tmp-nido-root)

(defn- queued-envelopes
  "Envelopes sitting in the (temp) queue dir."
  []
  (->> (fs/list-dir (cstate/queue-dir))
       (filter #(str/ends-with? (str %) ".edn"))
       (mapv #(clojure.edn/read-string (slurp (str %))))))

(deftest loop-cmd-passes-config-and-defaults
  (let [seen (atom nil)]
    (with-redefs [rloop/run-loop (fn [cfg] (reset! seen cfg) {:status :converged :history []})]
      (t/loop-cmd ":base" "develop" ":max-iters" "3" ":cwd" "/w")
      (is (= "develop" (:base @seen)))
      (is (= 3 (:max-iters @seen)))
      (is (= "/w" (:cwd @seen)))
      (is (string? (:run-id @seen)))
      (is (fn? (:emit @seen)) "engine is given an emit fn")
      (is (fn? (:clock @seen)) "engine is given a clock"))))

(defn- run-loop-writing-a-report
  "A stubbed engine that leaves a report where the real one would, so the
   enqueue gate — which refuses a run with no report to read — sees a run that
   produced something."
  [status]
  (fn [cfg]
    (let [rp (fs/path (cstate/run-dir (:run-id cfg)) "report.json")]
      (fs/create-dirs (fs/parent rp))
      (spit (str rp) "{}"))
    {:status status :history []}))

(deftest loop-cmd-queues-exactly-one-analysis-for-the-finished-run
  ;; Fires once per RUN, never per round, and only after the loop returns.
  (with-redefs [rloop/run-loop (run-loop-writing-a-report :converged)]
    (t/loop-cmd ":cwd" "/w")
    (let [envs (queued-envelopes)]
      (is (= 1 (count envs)))
      (is (= {:project :nido :trigger :review-analysis} (:target (first envs)))
          "aimed nido-side, never at the reviewed project")
      (is (= "converged" (get-in (first envs) [:payload :status]))))))

(deftest loop-cmd-queues-no-analysis-for-a-dry-run
  (with-redefs [rloop/run-loop (run-loop-writing-a-report :converged)]
    (t/loop-cmd ":cwd" "/w" ":dry-run?" "true")
    (is (empty? (queued-envelopes)))))

(deftest loop-cmd-queues-no-analysis-when-the-run-left-no-report
  ;; The shape that spawned phantom sessions: the command driven with the engine
  ;; stubbed out, so nothing was written and there is nothing to analyse.
  (with-redefs [rloop/run-loop (fn [_] {:status :converged :history []})]
    (t/loop-cmd ":cwd" "/w")
    (is (empty? (queued-envelopes))
        "no report on disk means no session is provisioned to go and read one")))

(deftest loop-cmd-defaults-base-to-main
  (let [seen (atom nil)]
    (with-redefs [rloop/run-loop (fn [cfg] (reset! seen cfg) {:status :clean :history []})]
      (t/loop-cmd ":cwd" "/w")
      (is (= "main" (:base @seen)))
      (is (nil? (:max-iters @seen)) "uncapped by default — runs as long as it takes"))))

(deftest loop-cmd-resolves-worktree-when-cwd-absent
  (let [seen (atom nil)]
    (with-redefs [lifecycle/worktree-from-cwd (fn [] "/resolved/wt")
                  rloop/run-loop (fn [cfg] (reset! seen cfg) {:status :clean :history []})]
      (t/loop-cmd)
      (is (= "/resolved/wt" (:cwd @seen))))))

(deftest loop-cmd-explicit-cwd-overrides-resolution
  (let [seen (atom nil)]
    (with-redefs [lifecycle/worktree-from-cwd (fn [] "/resolved/wt")
                  rloop/run-loop (fn [cfg] (reset! seen cfg) {:status :clean :history []})]
      (t/loop-cmd ":cwd" "/explicit")
      (is (= "/explicit" (:cwd @seen))))))

(deftest loop-cmd-exit-maps-status
  (with-redefs [rloop/run-loop (fn [_] {:status :clean :history []})]
    (is (zero? (t/exit-code :clean))))
  (is (zero? (t/exit-code :converged)))
  (is (zero? (t/exit-code :escalated)))
  (is (= 1 (t/exit-code :review-failed))))

(deftest review-event-derives-verdict-and-counts
  (let [final  {:status :escalated :findings [{:file "a" :line-start 1 :title "x"}
                                              {:file "b" :line-start 2 :title "y"}]}
        report {:summary {:rounds 3 :findings-fixed 4}
                :target  {:base "main" :base-rev "deadbee"}}
        ev     (t/review-event final report "/runs/r/report.json")]
    (is (= :review-report (:format ev)))
    (is (= :escalated (:status ev)))
    (is (= "main" (:base ev)))
    (is (= "deadbee" (:base-rev ev)))
    (is (= 3 (:rounds ev)))
    (is (= 4 (:findings-fixed ev)))
    (is (= 2 (:findings-remaining ev)))
    (is (= "/runs/r/report.json" (:report-path ev)))))

(deftest review-event-defaults-missing-counts-to-zero
  (let [ev (t/review-event {:status :review-failed}
                           {:target {:base "main" :base-rev nil}}
                           nil)]
    (is (= 0 (:rounds ev)))
    (is (= 0 (:findings-fixed ev)))
    (is (= 0 (:findings-remaining ev)))
    (is (nil? (:base-rev ev)))))

(deftest append-review-entry-writes-when-workstream-resolves
  (let [appended (atom nil)]
    (with-redefs [lifecycle/session-from-cwd (fn [_] {:project "brian" :session "s1"})
                  csession/workstream-id-for (fn [_ _] "ws-1")
                  ws/append-entry! (fn [p id entry content]
                                     (reset! appended {:p p :id id :entry entry :content content})
                                     "/path")]
      (let [ret (t/append-review-entry! "/w"
                                        {:status :converged :findings []}
                                        {:summary {:rounds 1 :findings-fixed 0}
                                         :target {:base "main" :base-rev "abc"}}
                                        "/runs/r/report.json")]
        (is (= "ws-1" ret))
        (is (= :brian (:p @appended)))
        (is (= :review (:kind (:entry @appended))))
        (is (str/includes? (:content @appended) ":review-report"))))))

(deftest append-review-entry-noops-without-workstream
  (let [called (atom false)]
    (with-redefs [lifecycle/session-from-cwd (fn [_] nil)
                  ws/append-entry! (fn [& _] (reset! called true) "/path")]
      (is (nil? (t/append-review-entry! "/w" {:status :clean :findings []}
                                        {:target {:base "main"}} nil)))
      (is (false? @called) "no append when cwd resolves to no session"))))

(deftest append-review-entry-swallows-append-failure
  (with-redefs [lifecycle/session-from-cwd (fn [_] {:project "brian" :session "s1"})
                csession/workstream-id-for (fn [_ _] "ws-1")
                ws/append-entry! (fn [& _] (throw (ex-info "disk boom" {})))]
    (is (nil? (t/append-review-entry! "/w" {:status :converged :findings []}
                                      {:summary {:rounds 1 :findings-fixed 0}
                                       :target {:base "main" :base-rev "abc"}}
                                      "/runs/r/report.json"))
        "a ledger-write failure is swallowed — returns nil, does not throw")))

;; ── The baseline loop ───────────────────────────────────────────────────────

(deftest baseline-cmd-drives-the-record-pipeline-not-the-review-one
  ;; The two things the engine cannot default for a record loop: which stages to
  ;; run, and what makes two findings the same finding. Getting the second wrong
  ;; is silent — every record finding collides under the review's key, so the
  ;; loop would stop after one amendment and call it progress.
  (let [seen (atom nil)]
    (with-redefs [rloop/run-loop (fn [cfg] (reset! seen cfg) {:status :sufficient})]
      (with-out-str (t/baseline-cmd ":cwd" "/w"))
      (is (= record/baseline-pipeline (:pipeline @seen)))
      (is (= record/baseline-finding-key (:finding-key @seen)))
      (is (= "/w" (:cwd @seen)))
      (is (str/starts-with? (:run-id @seen) "baseline-loop-"))
      (is (fn? (:emit @seen))))))

(deftest baseline-cmd-is-uncapped-unless-asked
  (let [seen (atom nil)]
    (with-redefs [rloop/run-loop (fn [cfg] (reset! seen cfg) {:status :sufficient})]
      (with-out-str (t/baseline-cmd ":cwd" "/w"))
      (is (nil? (:max-iters @seen)) "no default cap, same as the diff loop")
      (with-out-str (t/baseline-cmd ":cwd" "/w" ":max-iters" "2"))
      (is (= 2 (:max-iters @seen)))
      (with-out-str (t/baseline-cmd ":cwd" "/w" ":dry-run?" "true"))
      (is (true? (:dry-run? @seen))))))

(deftest baseline-cmd-resolves-the-worktree-when-cwd-is-absent
  (let [seen (atom nil)]
    (with-redefs [rloop/run-loop (fn [cfg] (reset! seen cfg) {:status :sufficient})
                  lifecycle/worktree-from-cwd (constantly "/resolved")]
      (with-out-str (t/baseline-cmd))
      (is (= "/resolved" (:cwd @seen))))))

(deftest baseline-cmd-takes-a-session-home-as-readily-as-a-worktree
  ;; A session home is a place an agent legitimately stands. Naming one used to
  ;; skip the home-aware union that the no-argument form goes through, so the
  ;; run died as :no-workstream telling the caller to go where they already were.
  (let [seen (atom nil)]
    (with-redefs [rloop/run-loop (fn [cfg] (reset! seen cfg) {:status :sufficient})
                  lifecycle/worktree-from-cwd
                  (fn [given] (when (= "/home/p/s" given) "/wt/s"))]
      (with-out-str (t/baseline-cmd ":cwd" "/home/p/s"))
      (is (= "/wt/s" (:cwd @seen))))))

(deftest a-cwd-that-belongs-to-no-session-is-passed-through-as-given
  ;; So the failure names what the caller actually typed.
  (let [seen (atom nil)]
    (with-redefs [rloop/run-loop (fn [cfg] (reset! seen cfg) {:status :sufficient})
                  lifecycle/worktree-from-cwd (fn [_] nil)]
      (with-out-str (t/baseline-cmd ":cwd" "/somewhere/else"))
      (is (= "/somewhere/else" (:cwd @seen))))))

(defn- run-loop-emitting-a-clean-round
  "A stubbed engine that emits the events a real round would, so the report the
   command renders from is the shape the fold actually produces."
  [status]
  (fn [{:keys [emit run-id cwd]}]
    (emit {:event :run-started :run-id run-id :cwd cwd :at "2026-01-01T00:00:00Z"})
    (emit {:event :phase-started :iter 1 :phase :judge :at "2026-01-01T00:00:01Z"})
    (emit {:event :phase-finished :iter 1 :phase :judge :at "2026-01-01T00:00:02Z"
           :ctx {:record {:verdict :falsified} :findings [{:cites ["a"] :claim "x"}]}})
    (emit {:event :phase-started :iter 1 :phase :amend :at "2026-01-01T00:00:03Z"})
    (emit {:event :phase-finished :iter 1 :phase :amend :at "2026-01-01T00:00:04Z"
           :ctx {:retreats []}})
    (emit {:event :run-finalized :status status :ctx {} :at "2026-01-01T00:00:05Z"})
    {:status status}))

(deftest a-loop-that-amended-and-gave-nothing-up-says-so-in-words
  ;; That a loop converged WITHOUT claiming less is the single most important
  ;; fact about it, so it is stated rather than left to an absent section.
  (with-redefs [rloop/run-loop (run-loop-emitting-a-clean-round :sufficient)]
    (let [out (with-out-str (t/baseline-cmd ":cwd" "/w"))]
      (is (str/includes? out "Weakened:"))
      (is (str/includes? out "(nothing — the record claims everything it claimed at the start)"))
      (is (str/includes? out "the survey holds against the code")))))

(deftest a-loop-that-never-amended-says-that-instead
  ;; The same distinction one level up: a run that never reached an amendment
  ;; did not decline to weaken the record.
  (with-redefs [rloop/run-loop (fn [_] {:status :no-workstream})]
    (let [out (with-out-str (t/baseline-cmd ":cwd" "/w"))]
      (is (str/includes? out "(no amendment ran — nothing here was even attempted)"))
      (is (not (str/includes? out "claims everything it claimed at the start"))))))

(deftest a-weakening-reaches-the-terminal-even-when-the-loop-continued-past-it
  (with-redefs [rloop/run-loop
                (fn [{:keys [emit run-id cwd]}]
                  (emit {:event :run-started :run-id run-id :cwd cwd :at "2026-01-01T00:00:00Z"})
                  (emit {:event :phase-started :iter 1 :phase :amend :at "2026-01-01T00:00:01Z"})
                  (emit {:event :phase-finished :iter 1 :phase :amend :at "2026-01-01T00:00:02Z"
                         :ctx {:retreats [{:what :veto-lifted :detail "h2 unmarked"}]}})
                  (emit {:event :run-finalized :status :sufficient :ctx {} :at "2026-01-01T00:00:03Z"})
                  {:status :sufficient})]
    (let [out (with-out-str (t/baseline-cmd ":cwd" "/w"))]
      (is (str/includes? out "! veto-lifted — h2 unmarked")))))

(deftest every-terminal-status-names-its-own-remedy
  ;; The rule the one-shot round already held: a round that could not run must
  ;; not read like a round that ran and found nothing. A loop adds four more ways
  ;; to stop, and two of them (:retreated, :amend-touched-code) need a human to
  ;; do something specific.
  (doseq [[status marker]
          {:sufficient           "holds against the code"
           :retreated          "below what its own round would check"
           :no-progress        "what it left"
           :amend-noop         "nothing was appended"
           :amend-unreadable   "would not parse as EDN"
           :amend-invalid      "the ledger refused"
           :amend-touched-code "still there"
           :no-workstream      "nido session"
           :no-record          "author the baseline first"
           :nothing-to-check   "refutable"
           :codex-failed       "NOT a clean result"
           ;; The rest of the ways a record loop can stop. This list is
           ;; hand-kept, which is how :max-iters — a documented flag — came to
           ;; print "unrecognised terminal status" instead of a remedy.
           :unfixable          "raised three rounds running"
           :disputed           "neither can settle it"
           :dry-run            "nothing was amended"
           :max-iters          "not convergence"
           :no-output          "wrote nothing"
           :unusable-answer    "not in a form a record accepts"
           :round-crashed      "threw before it could degrade"}]
    (with-redefs [rloop/run-loop (fn [_] {:status status})]
      (let [out (with-out-str (t/baseline-cmd ":cwd" "/w"))]
        (is (str/includes? out marker) (str status " must say what to do about it"))
        (is (str/includes? out (name status)))))))

(deftest a-loop-that-throws-still-shows-what-it-had
  (with-redefs [rloop/run-loop (fn [_] (throw (ex-info "judge exploded" {})))]
    (let [out (with-out-str
                (is (thrown? Exception (t/baseline-cmd ":cwd" "/w"))))]
      (is (str/includes? out "Weakened:")
          "the final block prints from a finally, so a crash still reports"))))

(deftest baseline-cmd-passes-a-budget-through-to-the-amender
  ;; The iteration count is uncapped by design, so the per-launch wall clock is
  ;; the only bound on a single hung round.
  (let [seen (atom nil)]
    (with-redefs [rloop/run-loop (fn [cfg] (reset! seen cfg) {:status :sufficient})]
      (with-out-str (t/baseline-cmd ":cwd" "/w" ":budget" "30m"))
      (is (= "30m" (:budget @seen)))
      (with-out-str (t/baseline-cmd ":cwd" "/w"))
      (is (nil? (:budget @seen)) "none by default, same as the diff loop"))))

;; ── The design loop ─────────────────────────────────────────────────────────

(deftest design-cmd-drives-the-design-pipeline
  (let [seen (atom nil)]
    (with-redefs [rloop/run-loop (fn [cfg] (reset! seen cfg) {:status :proceed})]
      (with-out-str (t/design-cmd ":cwd" "/w"))
      (is (= record/design-pipeline (:pipeline @seen)))
      (is (= record/design-finding-key (:finding-key @seen)))
      (is (nil? (:max-iters @seen)))
      (is (str/starts-with? (:run-id @seen) "design-loop-")))))

(deftest design-cmd-hands-over-the-question-it-could-not-answer
  ;; The whole point of the round: everything derivable is derived so that what
  ;; reaches a human is only the judgement that cannot be.
  (with-redefs [rloop/run-loop (fn [_] {:status :proceed
                                        :record {:asks "is this worth doing now, at this cost?"}})]
    (let [out (with-out-str (t/design-cmd ":cwd" "/w"))]
      (is (str/includes? out "FOR YOU TO DECIDE"))
      (is (str/includes? out "is this worth doing now, at this cost?"))
      (is (str/includes? out "the part only you can answer")))))

(deftest design-cmd-names-a-check-it-had-no-yardstick-for
  (with-redefs [rloop/run-loop (fn [_] {:status :underivable
                                        :underivable [{:check :relation-honest
                                                       :note "no stance document exists"}]})]
    (let [out (with-out-str (t/design-cmd ":cwd" "/w"))]
      (is (str/includes? out "relation-honest could not be derived: no stance document exists"))
      (is (str/includes? out "not a defect an amender can repair")))))

(deftest a-failed-resurvey-reports-which-loop-stopped-and-why
  ;; The nested statuses are open by construction, so this is a lookup fn rather
  ;; than a table. Collapsing them to "the re-survey failed" would throw away the
  ;; only useful part.
  (doseq [[status marker] {:resurvey-retreated "ended retreated"
                           :resurvey-no-progress "ended no-progress"}]
    (with-redefs [rloop/run-loop (fn [_] {:status status})]
      (let [out (with-out-str (t/design-cmd ":cwd" "/w"))]
        (is (str/includes? out marker))
        (is (str/includes? out "the premise is still wrong"))))))

(deftest every-design-terminal-status-names-its-own-remedy
  (doseq [[status marker]
          {:proceed            "only you can answer"
           :underivable        "no yardstick"
           :disputed           "neither can settle it"
           :retreated          "below what its own round would check"
           :no-record          "author the design first"
           :not-worth-running  "would not pay"
           :codex-failed       "NOT a clean result"}]
    (with-redefs [rloop/run-loop (fn [_] {:status status})]
      (let [out (with-out-str (t/design-cmd ":cwd" "/w"))]
        (is (str/includes? out marker) (str status " must say what to do about it"))))))

(deftest both-loops-share-the-terminal-statuses-they-actually-share
  (doseq [cmd [t/baseline-cmd t/design-cmd]]
    (with-redefs [rloop/run-loop (fn [_] {:status :amend-touched-code})]
      (is (str/includes? (with-out-str (cmd ":cwd" "/w")) "still there")))))

(deftest a-stuck-finding-is-named-by-its-subject-not-its-tag
  ;; Every identity key names its subject somewhere inside, but the two
  ;; closed-vocabulary keys — a design check and a survey gap — carry it as the
  ;; keyword after a tag. Printing the tag names the mechanism instead of the
  ;; thing the run could not resolve.
  (let [f #'tasks.nido-review/finding-name]
    (is (= "judge-never-writes" (f [[:claim-id "judge-never-writes"] 0])))
    (is (= "decomposable"       (f [[:blocks :decomposable] 1])))
    (is (= "routing-coherent"   (f [[:check :routing-coherent] 0])))
    (is (= "src/a.clj:1"        (f [[:evidence ["src/a.clj:1"]] 0])))))

(deftest baseline-cmd-can-name-which-survey-to-verify
  ;; A workstream can hold surveys of different areas and a design cites one
  ;; specifically. Without this the only reachable survey is the newest, so the
  ;; advice a blocked design gives — verify the survey it cites — names a
  ;; command that verifies the other one and leaves the design blocked.
  (let [seen (atom nil)
        cited {:format :baseline :area "the one the design cites" :seq 7}]
    (with-redefs [rloop/run-loop (fn [cfg] (reset! seen cfg) {:status :sufficient})
                  lifecycle/worktree-from-cwd (fn [g] g)
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/entry-at-seq (fn [_ _ n] (when (= 7 n) cited))]
      (with-out-str (t/baseline-cmd ":cwd" "/wt" ":seq" "7"))
      (is (= cited (:baseline @seen))))))

(deftest a-seq-naming-no-entry-is-refused-rather-than-falling-back
  ;; Falling back to the newest would verify the same wrong survey with no way
  ;; to tell it had happened.
  (with-redefs [lifecycle/worktree-from-cwd (fn [g] g)
                stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                ws/entry-at-seq (fn [_ _ _] nil)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"entry 99"
                          (t/baseline-cmd ":cwd" "/wt" ":seq" "99")))))

(deftest a-seq-naming-the-wrong-kind-of-entry-is-refused-and-says-which
  ;; The likelier typo: a workstream's baselines and their reviews interleave
  ;; and sit one apart, so an off-by-one lands on a review. Verifying a review
  ;; is not a smaller mistake than verifying nothing.
  (with-redefs [lifecycle/worktree-from-cwd (fn [g] g)
                stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                ws/entry-at-seq (fn [_ _ _] {:format :baseline-review :seq 55})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"it is a :baseline-review"
                          (t/baseline-cmd ":cwd" "/wt" ":seq" "55")))))

(deftest a-no-verdict-outcome-says-which-record-it-was-about
  ;; The status names the failure; only the detail names the entry, and a reader
  ;; cannot act on the first without the second.
  (with-redefs [rloop/run-loop
                (fn [_] {:status :premise-unverified
                         :record {:outcome :premise-unverified
                                  :detail "the design cites the survey at entry 4, and no round has found that survey sufficient"}})]
    (let [out (with-out-str (t/design-cmd ":cwd" "/w"))]
      (is (str/includes? out "the survey at entry 4")))))

(deftest a-capped-run-says-the-cap-was-the-reader-s-own
  ;; The loop has no default cap — it ends on its own merits — so :max-iters is
  ;; only ever the caller's bound coming back, and it printed "unrecognised
  ;; terminal status" instead of saying so.
  (with-redefs [rloop/run-loop (fn [_] {:status :max-iters})]
    (let [out (with-out-str (t/baseline-cmd ":cwd" "/w" ":max-iters" "2"))]
      (is (not (str/includes? out "unrecognised terminal status")))
      (is (str/includes? out "not convergence")))))

(deftest a-record-talked-out-of-checkability-is-reported
  ;; The Weakened section answers "did the record claim LESS", and a record that
  ;; grew claims more — so a run whose composition went from four sentences to a
  ;; page reported that it had given nothing up, which was true and told the
  ;; reader the opposite of what had happened.
  (let [authored {:format :baseline :composition (apply str (repeat 400 "x"))
                  :shape "one boundary"}
        final-rec (assoc authored :composition (apply str (repeat 4000 "x")))]
    (with-redefs [rloop/run-loop
                  (fn [_] {:status :unfixable
                           :carry {:as-authored authored :under-repair final-rec}})]
      (let [out (with-out-str (t/baseline-cmd ":cwd" "/w"))]
        (is (str/includes? out "Grown past checking"))
        (is (str/includes? out "composition"))
        (is (str/includes? out "×10.0"))))))

(deftest a-record-that-stayed-its-size-says-nothing-about-growth
  (let [r {:format :baseline :composition "short and true" :shape "one boundary"}]
    (with-redefs [rloop/run-loop
                  (fn [_] {:status :sufficient
                           :carry {:as-authored r :under-repair r}})]
      (let [out (with-out-str (t/baseline-cmd ":cwd" "/w"))]
        (is (not (str/includes? out "Grown past checking")))))))

(deftest a-repeated-finding-set-says-which-of-its-two-causes-it-was
  ;; With a record loop's identity, "the same findings again" has two causes.
  ;; Measured: two rounds on one survey, shape and composition refuted twice,
  ;; each time by a DIFFERENT counterexample, the record amended both times.
  ;; Telling that reader the amender stopped working is false.
  ;; From the history, not the terminal ctx: a run that ends on a judgement
  ;; never reaches an amend stage, so the ctx cannot say whether earlier rounds
  ;; repaired anything.
  (testing "amended, and refuted again anyway"
    (with-redefs [rloop/run-loop
                  (fn [_] {:status :no-progress
                           :history [{:iter 1 :amended? true} {:iter 2 :amended? true}]})]
      (let [out (with-out-str (t/baseline-cmd ":cwd" "/w"))]
        (is (str/includes? out "refuted again after being corrected"))
        (is (not (str/includes? out "amender stopped changing"))))))
  (testing "nothing was amended"
    (with-redefs [rloop/run-loop
                  (fn [_] {:status :no-progress :history [{:iter 1 :amended? false}]})]
      (let [out (with-out-str (t/baseline-cmd ":cwd" "/w"))]
        (is (str/includes? out "amender stopped changing"))))))

;; ---- the halt a parked finding owes a human ------------------------------

(deftest a-run-holding-nothing-parked-owes-no-halt
  (is (nil? (t/parked-blocker
             [{:disposition :closed :title "t"}
              {:disposition :declined :title "u"}
              {:disposition :recut :title "v"}]))))

(deftest a-parked-finding-becomes-an-answerable-halt
  ;; The ledger refuses a choice written as prose, and rightly — an essay can
  ;; only be answered by typing one back. The branches have to be options.
  (let [b (t/parked-blocker
           [{:disposition :park :title "the aggregate rounds twice"}
            {:disposition :fix :title "not this one"}])]
    (is (= :blocker (:format b)))
    (is (str/includes? (:summary b) "the aggregate rounds twice"))
    (is (str/includes? (:summary b) "1 finding") "counts only what is parked")
    (is (= 2 (count (:options b))))
    (is (every? :consequence (:options b))
        "a gate answered on a name alone is how the wrong branch gets clicked")))

(deftest the-halt-validates-against-the-ledger
  ;; Written by an agent, so it goes through the same write boundary every other
  ;; typed event does — including the rule that rejects branches written as
  ;; prose, which is enforced after the schema and only on write.
  (let [b (t/parked-blocker [{:disposition :park :title "t"}])]
    (is (= b (report/validate-event :blocker b)))))
