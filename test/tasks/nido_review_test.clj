(ns tasks.nido-review-test
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [malli.core :as m]
   [nido.platform.core :as core]
   [nido.coordinator.report :as report]
   [nido.coordinator.record.session :as csession]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.lane.pipeline :as pipeline]
   [nido.review.layers :as layers]
   [nido.review.loop :as rloop]
   [nido.review.record :as record]
   [nido.review.report :as rreport]
   [nido.review.stages :as stages]
   [nido.review.verdict :as verdict]
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
      (with-redefs [core/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f))
      (finally (fs/delete-tree tmp)))))

(def ^:private gate
  "The real `no-yardstick`, captured at load — before `with-a-design-record`
   stubs the var for every other test in this namespace. The gate's own tests
   are the ones that have to reach the implementation."
  t/no-yardstick)

(defn- with-a-design-record
  "Clear `no-yardstick` for every test that is not about it.

   The diff loop refuses a cwd with no design record to judge against, and every
   test below drives the REAL command from a path that belongs to no workstream
   — so without this each one asserts against the refusal instead of against
   what it was written to check. Redefining the gate itself rather than the two
   ledger reads underneath it keeps the stub off `project+ws-from-cwd`, which
   several of these tests are genuinely about."
  [f]
  (with-redefs [t/no-yardstick (constantly nil)] (f)))

(use-fixtures :each with-tmp-nido-root with-a-design-record)

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
      (is (fn? (:clock @seen)) "engine is given a clock")
      ;; The engine never looks inside a finding, so this half of the give-up
      ;; counter only works if the diff loop hands it over. Dropped from the
      ;; config, a parked round silently counts as a failed repair again.
      (is (false? ((:attempted? @seen) {:disposition :park}))
          "the diff loop tells the engine a parked round attempted nothing")
      (is (true? ((:attempted? @seen) {:disposition :fix})))
      (is (false? ((:attempted? @seen) {:disposition :fix :fixer-ran? false}))
          "and that a round whose fixer never started attempted nothing either —
           the reading two runs ended `unfixable` without"))))

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

(defn- analysis-payload-for
  "Queue one analysis for a hand-built run and give back the payload it carried.
   `queue-analysis!` is where the loop's remainder and the judge's are summed, so
   it is the only place the whole count can be read."
  [final report]
  (let [run-id (str "review-" (random-uuid))
        rp     (str (fs/path (cstate/run-dir run-id) "report.json"))]
    (fs/create-dirs (fs/parent rp))
    (spit rp "{}")
    (t/queue-analysis! "/w" final report rp
                       {:run-id run-id :dry-run? false} "ws-x")
    (:payload (first (queued-envelopes)))))

(deftest the-kept-count-includes-what-the-design-judge-is-holding
  ;; The run that prompted this ended `clean · 0 still open` with nothing kept,
  ;; over a `sound` verdict naming three located defects in a layer three rounds
  ;; of reviewers had read. This payload is the only record that sees both — the
  ;; :review ledger entry is written before the pass runs — so a remainder
  ;; uncounted here is uncounted anywhere.
  (let [final  {:status :clean :history [] :findings []}
        report {:summary {:rounds 3 :fix-attempts 0}
                :target {:base "main"}
                :design-verdict {:outcome "answered"
                                 :verdict {:verdict :sound :round 3
                                           :needs "nido_attach.clj:139 has an unreachable :claimed branch"}}}]
    (is (= 1 (:findings-kept (analysis-payload-for final report)))
        "a defect the branch ships on the judge's say-so is kept, exactly as a
         declined finding is")
    (is (= 0 (:findings-remaining (analysis-payload-for final report)))
        "and it is not OWED — routing it into the remainder would ask the next
         run to repair something nobody ruled on")))

(deftest a-judged-remainder-adds-to-the-rounds-own-rather-than-replacing-it
  (let [final  {:status :converged
                :history [{:iter 1 :findings [{:handle "h1" :title "the shipped defect"
                                               :disposition :declined
                                               :because "the shape is wrong, not this line"}]}]
                :findings []}
        report {:summary {:rounds 2 :fix-attempts 1}
                :target {:base "main"}
                :design-verdict {:outcome "answered"
                                 :verdict {:verdict :strained :round 2
                                           :needs "the third call site is where the cut is failing"}}}]
    (is (= 2 (:findings-kept (analysis-payload-for final report)))
        "the two halves of the remainder are counted together or one of them
         hides the other")))

(deftest a-decision-the-gate-is-holding-is-not-counted-as-kept
  ;; It reaches a human through the blocker instead. Counted here it would read
  ;; as settled on the one verdict where nothing is settled.
  (let [final  {:status :clean :history [] :findings []}
        report {:summary {:rounds 1 :fix-attempts 0}
                :target {:base "main"}
                :design-verdict {:outcome "answered"
                                 :verdict {:verdict :invalidated :round 1
                                           :needs "supersede the record or undo the boundary move"}}}]
    (is (= 0 (:findings-kept (analysis-payload-for final report))))))

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
    (with-redefs [lifecycle/worktree-from-cwd (fn [_] "/resolved/wt")
                  rloop/run-loop (fn [cfg] (reset! seen cfg) {:status :clean :history []})]
      (t/loop-cmd)
      (is (= "/resolved/wt" (:cwd @seen))))))

(deftest loop-cmd-resolves-an-explicit-cwd-too
  (testing "an explicitly named cwd goes through the same home-aware resolution.
            A session home is a place an agent legitimately stands, and skipping
            the resolution for it meant the run found no workstream, took the
            claimless path, and two invocations given the same home both
            reviewed the same worktree without seeing each other."
    (let [seen (atom nil)]
      (with-redefs [lifecycle/worktree-from-cwd (fn [given]
                                                  (when (= "/session/home" given) "/resolved/wt"))
                    rloop/run-loop (fn [cfg] (reset! seen cfg) {:status :clean :history []})]
        (t/loop-cmd ":cwd" "/session/home")
        (is (= "/resolved/wt" (:cwd @seen))
            "the home resolves to the worktree it belongs to")))))

(deftest loop-cmd-keeps-an-explicit-cwd-that-resolves-to-nothing
  (testing "a directory belonging to no session is still reviewed where it was
            named — resolution refines the cwd, it does not overrule it"
    (let [seen (atom nil)]
      (with-redefs [lifecycle/worktree-from-cwd (fn [_] nil)
                    rloop/run-loop (fn [cfg] (reset! seen cfg) {:status :clean :history []})]
        (t/loop-cmd ":cwd" "/explicit")
        (is (= "/explicit" (:cwd @seen)))))))

(deftest loop-cmd-exit-maps-status
  (with-redefs [rloop/run-loop (fn [_] {:status :clean :history []})]
    (is (zero? (t/exit-code :clean))))
  (is (zero? (t/exit-code :converged)))
  (is (zero? (t/exit-code :escalated)))
  (is (= 1 (t/exit-code :review-failed)))
  ;; A run with no reviewer produced no review, which is the same failure to a
  ;; caller gating on the exit code — /drive-home must not treat it as a branch
  ;; that was looked at and passed.
  (is (= 1 (t/exit-code :reviewer-unavailable))))

(deftest the-ledger-entry-carries-why-no-reviewer-ran
  ;; The only durable copy. report.json lives in a run dir that is routinely
  ;; gone by the time anybody reads the workstream, and the reviewer's own log
  ;; goes with it — so a quota exhaustion recorded as a bare status leaves the
  ;; remedy and the reset hour nowhere at all.
  (let [u  {:signal :usage-limit
            :message "You've hit your usage limit. … try again at Sep 7th, 2026 9:42 AM."
            :retry-at "Sep 7th, 2026 9:42 AM"}
        ev (t/review-event {:status :reviewer-unavailable :unavailable u}
                           {:target {:base "main" :base-rev nil}}
                           "/runs/r/report.json")]
    (is (= :reviewer-unavailable (:status ev)))
    (is (= u (:unavailable ev)))
    (is (nil? (m/explain report/ReviewReport ev))
        "the enum is closed and a refused append is swallowed to stderr, so an
         entry this schema will not take is an entry that silently never exists")))

(deftest review-event-carries-the-open-findings-not-only-a-count
  ;; The handover. A run that ends holding a park is the loop asking a human for
  ;; a decision, and until now it recorded that request as the integer 1 — with
  ;; the request itself reachable only by opening a run dir that may be gone.
  (let [final  {:status :escalated
                :history [{:iter 1 :findings [{:handle "h1" :id "f1"
                                               :title "the doc-ordering seam"
                                               :file "src/a.clj" :line-start 42
                                               :disposition :park
                                               :because "no fixer has standing here"}]}]
                :findings []}
        report {:summary {:rounds 2 :fix-attempts 3}
                :target  {:base "main" :base-rev "deadbee"}}
        ev     (t/review-event final report "/runs/r/report.json")
        [o]    (:open ev)]
    (is (= 1 (:findings-remaining ev)))
    (is (= "the doc-ordering seam" (:title o)))
    (is (= "src/a.clj:42" (:where o)) "file and line are one fact to a reader")
    (is (= :park (:disposition o)))
    (is (= "no fixer has standing here" (:because o)))
    ;; It has to survive the ledger, not merely be assembled — the schema is
    ;; closed and a rejected append is swallowed to stderr, so an event that
    ;; fails here fails invisibly in production.
    (is (= ev (report/validate-event :review ev)))))

(deftest the-ledger-entry-carries-what-the-warden-left-standing
  ;; The remainder no count reaches. Nothing raised these, so they are in no
  ;; finding list and in no number — and the warden was told its `reason` was
  ;; the only place they reach a human, which is true and is a run dir. One run
  ;; grew that reason to ten numbered items and filed an entry ending
  ;; `:findings-remaining 0`.
  (let [ev (t/review-event
            {:status :converged :history [] :findings []}
            {:summary {:rounds 2 :fix-attempts 0}
             :target  {:base "main" :base-rev "x"}
             :reason  {:standing [{:what "babel is pinned to an unmerged branch tip"
                                   :why-no-finding "outside this change"}
                                  {:what "no deftest or JS test anywhere on the work"}]}}
            "/runs/r/report.json")]
    (is (= 0 (:findings-remaining ev))
        "and it stays 0 — nothing was raised, ruled or dispatched, so counting
         these would claim the loop had an answer it declined to give")
    (is (= ["babel is pinned to an unmerged branch tip"
            "no deftest or JS test anywhere on the work"]
           (mapv :what (:standing ev))))
    (is (= ev (report/validate-event :review ev))
        "the ledger schema is closed; a rejected append is swallowed to stderr")
    (let [md (report/report->markdown (assoc ev :format :review-report))]
      (is (str/includes? md "Left standing"))
      (is (str/includes? md "unmerged branch tip"))
      (is (str/includes? md "outside this change")
          "with the ground, which is what a reader decides on")
      (is (str/includes? md "no deftest or JS test anywhere on the work")
          "and an item stated without a ground still reaches them"))))

(deftest a-run-whose-warden-left-nothing-standing-carries-no-such-list
  ;; Omitted rather than empty, like every other optional key on the entry: a
  ;; heading over nothing reads as the loop asserting it left nothing behind.
  (let [ev (t/review-event {:status :clean :history [] :findings []}
                           {:summary {:rounds 1 :fix-attempts 0}
                            :target {:base "main" :base-rev "x"}}
                           "/runs/r/report.json")]
    (is (not (contains? ev :standing)))
    (is (not (str/includes? (report/report->markdown (assoc ev :format :review-report))
                            "Left standing")))))

(deftest a-conflicted-run-names-the-changes-in-the-ledger-entry
  ;; The status alone says the stack is broken and leaves finding it as an
  ;; exercise — on a branch whose conflict is mid-stack, where `jj resolve
  ;; --list` reports clean.
  (let [ev (t/review-event {:status :fix-conflicted :history [] :findings []
                            :conflicted ["xuspsuww" "b4927669"]}
                           {:summary {:rounds 2 :fix-attempts 3}
                            :target {:base "main" :base-rev "x"}}
                           "/runs/r/report.json")]
    (is (= ["xuspsuww" "b4927669"] (:conflicted ev)))
    (is (= ev (report/validate-event :review ev))
        "the ledger's status enum admits it — a closed schema swallows a
         rejected append to stderr, so an unadmitted status fails invisibly")
    (let [md (report/report->markdown (assoc ev :format :review-report))]
      (is (str/includes? md "xuspsuww") "and a reader is told where to go")
      (is (str/includes? md "conflicted")))))

(deftest a-stack-conflicted-run-reaches-the-ledger-with-its-change-ids
  ;; The status is new and the ReviewReport enum is closed, so an unadmitted one
  ;; is refused and the refusal is swallowed to stderr — the entry naming where
  ;; the conflict is would simply never appear.
  (let [ev (t/review-event {:status :stack-conflicted :history [] :findings []
                            :conflicted ["xlortuwzrtlu" "spxkmpurtnms"]}
                           {:summary {:rounds 1 :fix-attempts 0}
                            :target {:base "main" :base-rev "x"}}
                           "/runs/r/report.json")]
    (is (= ["xlortuwzrtlu" "spxkmpurtnms"] (:conflicted ev)))
    (is (= ev (report/validate-event :review ev)))
    (is (str/includes? (report/report->markdown (assoc ev :format :review-report))
                       "xlortuwzrtlu")
        "and a reader of the workstream is told where to go")))

(deftest a-refused-repair-reaches-the-ledger-entry-that-outlives-the-run-dir
  ;; The status that most needs a human carried the least: the branch is
  ;; unchanged, so the counts read exactly like a round no fixer was launched
  ;; for, and the entry named neither the layer nor what it collided with.
  ;; Recovering what the fixer had edited meant opening an agent transcript.
  (let [final {:status :fix-rolled-back
               :history []
               :findings [{:handle "d74147c1" :id "d74147c1"
                           :title "the digest is not in the contracts"
                           :disposition :fix}]
               :carry {:rolled-back
                       {"a1" {:layer "a1" :since 3 :commit "27b8d03a"
                              :conflicted ["uoorwwyp" "nzmrrztu"]
                              :account "added the digest to all five contracts"
                              :findings [{:id "d74147c1"
                                          :title "the digest is not in the contracts"}]}}}}
        ev    (t/review-event final
                              {:summary {:rounds 3 :fix-attempts 0}
                               :target {:base "main" :base-rev "x"}}
                              "/runs/r/report.json")]
    (is (= [{:layer "a1" :round 3 :commit "27b8d03a"
             :conflicted ["uoorwwyp" "nzmrrztu"] :handed ["d74147c1"]}]
           (:rolled-back ev))
        "the layer, the repair's own commit and what it collided with — the
         account stays on the report, because `jj show` on the commit is the
         edit itself rather than a claim about it")
    (is (= ev (report/validate-event :review ev))
        "the ledger's schema is closed, so an unadmitted key is not a failure —
         it silently erases the whole entry")
    (let [md (report/report->markdown (assoc ev :format :review-report))]
      (is (str/includes? md "Repairs the stack refused"))
      (is (str/includes? md "27b8d03a") "the id that recovers the edit")
      (is (str/includes? md "uoorwwyp") "and where the layer order is wrong"))))

(deftest a-refusal-answered-by-a-later-round-is-not-carried-to-the-ledger
  ;; The carry is pruned as findings settle, so what reaches the entry is the
  ;; refusals still standing over findings still open. A run that refused a
  ;; repair in round 1 and closed the finding in round 2 has nothing to hand a
  ;; reader.
  (let [ev (t/review-event {:status :converged :history [] :findings []
                            :carry {:rolled-back {}}}
                           {:summary {:rounds 2 :fix-attempts 1}
                            :target {:base "main" :base-rev "x"}}
                           "/runs/r/report.json")]
    (is (not (contains? ev :rolled-back)))))

(deftest a-launch-that-never-started-reaches-the-ledger-and-the-terminal
  ;; The status says the machinery failed; only the layer says where, and the
  ;; err.log that says why lives in a run dir routinely gone before anyone reads
  ;; the workstream.
  (let [final {:status :fix-launch-failed
               :history []
               :findings [{:handle "91241c2a" :id "91241c2a" :title "t"
                           :disposition :fix :owner-layer "speech-contract"}]
               :carry {:fixer-launches
                       {"speech-contract" [{:round 1 :handed ["91241c2a"] :ran? true}
                                           {:round 2 :handed ["91241c2a"] :ran? false :exit-code 1}
                                           {:round 3 :handed ["91241c2a"] :ran? false :exit-code 1}]
                        "continuation-value" [{:round 3 :handed ["a56316bb"] :ran? true}]}}}
        ev    (t/review-event final
                              {:summary {:rounds 3 :fix-attempts 1}
                               :target {:base "main" :base-rev "x"}}
                              "/runs/r/report.json")]
    (is (= [{:layer "speech-contract" :round 3 :exit-code 1 :handed ["91241c2a"]}]
           (:launch-failed ev))
        "the layer whose last launch never started, and only that one")
    (is (= ev (report/validate-event :review ev))
        "the ledger's schema is closed, so an unadmitted key erases the entry")
    (is (str/includes? (report/report->markdown (assoc ev :format :review-report))
                       "Fixers that never started")
        "and the entry a person reads says so, not only the data")
    (let [out (str/join "\n" (t/outcome-lines final {:rounds []} "/runs/r/report.json"))]
      (is (str/includes? out "never started: speech-contract (round 3, exit 1)"))
      (is (str/includes? out "what is broken is the machinery")
          "the remedy sentence and the layer it is about, on the same screen"))))

(deftest a-rolled-back-run-names-the-collision-on-the-terminal
  ;; The remedy line says the branch is unchanged and a re-run earns the same
  ;; refusal — which leaves the operator holding a verdict and no target. Which
  ;; layer collided with which is what a reorder would have to be aimed at.
  (let [out (str/join "\n"
                      (t/outcome-lines
                       {:status :fix-rolled-back
                        :carry {:rolled-back
                                {"a1" {:layer "a1" :since 1
                                       :conflicted ["lktsqrrn" "llqpmolo"]
                                       :findings [{:id "d74147c1"}]}}}}
                       {:rounds []}
                       "/runs/r/report.json"))]
    (is (str/includes? out "refused: a1"))
    (is (str/includes? out "lktsqrrn"))
    (is (str/includes? out "what is in question is the layer order")
        "the remedy sentence and the ids it is about, on the same screen")))

(defn- ledger-review-statuses
  "The statuses the ledger's :review entry will accept, read off the schema
   rather than restated here — a hand-kept copy is the thing that drifts."
  []
  (set (->> (m/children report/ReviewReport)
            (some (fn [[k _ s]] (when (= :status k) (m/children s)))))))

(deftest the-ledger-admits-every-status-the-loop-can-end-on
  ;; The enum is CLOSED and `append-review-entry!` is best-effort, so a status
  ;; the ledger has not been told about does not fail the run — it erases the
  ;; entry. :unfixable was produced by two namespaces, printed by a third and
  ;; classified by a fourth for as long as it existed, while this enum did not
  ;; admit it: every run that ended on it lost its :review entry, and the loss
  ;; is indistinguishable from a run that never had a workstream.
  ;;
  ;; This is the only place the two lists can be held together. The Report band
  ;; may depend on nothing above it, so the enum cannot read the loop's own
  ;; declaration, and the loop cannot narrow the enum.
  (let [admitted (ledger-review-statuses)]
    (doseq [s (into rloop/engine-statuses stages/stage-statuses)]
      (is (contains? admitted s)
          (str s " ends a review run, so the ledger has to admit it — an append"
               " it refuses is swallowed, and the entry is simply not there")))))

(deftest every-terminal-status-arrives-as-a-sentence-not-a-word
  ;; The remedy map is written here and the vocabulary is declared two bands
  ;; down in two namespaces, neither of which can read it — so nothing but this
  ;; holds them together, and an unremedied status is silent rather than broken.
  (let [statuses (into rloop/engine-statuses stages/stage-statuses)]
    (doseq [s statuses]
      (is (string? (t/diff-remedies s))
          (str s " ends a review run, and the operator standing at the terminal"
               " is told what it asks of them nowhere else")))
    (is (empty? (remove statuses (keys t/diff-remedies)))
        "a line for a status the loop can no longer reach is prose nobody will
         ever read being maintained as if somebody might")))

(deftest a-fix-conflicted-run-names-the-changes-on-the-terminal
  ;; The one status that hands the branch back holding markers, and the ids
  ;; reached report.json and the ledger alone — leaving the operator who ran it
  ;; the one reader not told. `jj resolve --list` answers `No conflicts` on a
  ;; conflict this shape, so there is no second way to find them.
  (let [lines (t/outcome-lines {:status :fix-conflicted
                                :conflicted ["nnpkqnmnkznn" "xlortuwzrtlu"]}
                               {:rounds []}
                               "/runs/r/report.json")
        out   (str/join "\n" lines)]
    (is (str/includes? out "nnpkqnmnkznn"))
    (is (str/includes? out "xlortuwzrtlu"))
    (is (str/includes? out "resolve them and re-run"))
    (is (str/includes? out "the repairs that did land are kept")
        "and whether the round's work survived, which decides whether resolving
         is worth more than abandoning the fixes")))

(deftest a-run-with-no-particulars-says-the-status-and-what-it-asks
  ;; Two lines, and the second is the whole of what most runs ask of a reader.
  (is (= ["review-loop: clean · report /runs/r/report.json"
          "  → a reviewer read the diff and reported nothing"]
         (t/outcome-lines {:status :clean} {:rounds []} "/runs/r/report.json"))))

(deftest an-unavailable-reviewer-says-what-it-wants-before-what-it-asks
  ;; The reviewer's own sentence carries the reset hour, which is the one fact
  ;; that decides whether to wait or to go and buy credits.
  (let [lines (t/outcome-lines {:status :reviewer-unavailable
                                :unavailable {:signal :usage-limit
                                              :message "You've hit your usage limit. … try again at Sep 7th, 2026 9:42 AM."}}
                               {:rounds []}
                               "/runs/r/report.json")]
    (is (str/includes? (second lines) "Sep 7th, 2026 9:42 AM"))
    (is (str/includes? (last lines) "the branch is unjudged"))))

(deftest an-unfixable-run-reaches-the-ledger-with-what-it-gave-up-on
  ;; The run that most needs a durable record is the one holding a question
  ;; only a human can answer. Every one of them was refused.
  (let [final {:status :unfixable
               :history []
               :unfixable ["09a09d87"]
               :findings [{:handle "09a09d87" :id "09a09d87" :title "the source-row seam"
                           :disposition :park :because "no fixer has standing here"}
                          {:handle "2e7041ab" :id "2e7041ab" :title "nobody reached it"
                           :disposition :fix}]}
        ev    (t/review-event final
                              {:summary {:rounds 6 :fix-attempts 4}
                               :target {:base "main" :base-rev "x"}}
                              "/runs/r/report.json")]
    (is (= ev (report/validate-event :review ev))
        "the ledger's status enum admits it — a closed schema swallows a
         rejected append to stderr, so an unadmitted status loses the entry")
    (is (= 2 (:findings-remaining ev)))
    (is (= 1 (:remaining-parked ev))
        "one of the two is a question for a human and the other is a repair
         nobody reached; a single count reads them as the same kind of work")
    (is (str/includes? (report/report->markdown (assoc ev :format :review-report))
                       "1 waiting on you")
        "and the split survives into what a reader of the workstream sees")))

(deftest a-converged-run-claims-nobody-is-waiting-on-anything
  ;; The counts are omitted at zero rather than carried as 0, so `waiting on
  ;; you` never appears on a run that is waiting on no one.
  (let [ev (t/review-event {:status :converged :history [] :findings []}
                           {:summary {:rounds 2 :fix-attempts 3}
                            :target {:base "main" :base-rev "x"}}
                           "/runs/r/report.json")]
    (is (nil? (:remaining-parked ev)))
    (is (not (str/includes? (report/report->markdown (assoc ev :format :review-report))
                            "waiting on you")))))

(deftest the-remaining-count-says-how-much-of-it-is-already-repaired
  ;; `:fix-attempts` counts work dispatched and `:findings-remaining` counts
  ;; what is owed, and a repair landed in the final round is in BOTH — nothing
  ;; re-read the layer, so it stays open. Given only the pair a reader takes them
  ;; for a partition: `1 dispatched · 11 remaining` out of eleven findings, with
  ;; the nine no fixer touched reading exactly like the one that was.
  (let [final {:status  :fix-conflicted
               :history [{:iter 1 :fixed-count 1 :findings []
                          :fixes [{:layer "diary-paging" :commit "d92edf80"
                                   :handed ["dd463b20"]}]}]
               ;; The round's own fix rows: a fix-conflicted ctx carries them
               ;; here as well as into the history entry above.
               :fixes [{:layer "diary-paging" :commit "d92edf80"
                        :handed ["dd463b20"]}]
               :findings [{:handle "dd463b20" :id "dd463b20" :title "the repaired one"
                           :disposition :fix}
                          {:handle "4a9816d2" :id "4a9816d2" :title "nobody reached it"
                           :disposition :fix}]}
        ev    (t/review-event final
                              {:summary {:rounds 1 :fix-attempts 1}
                               :target {:base "main" :base-rev "x"}}
                              "/runs/r/report.json")]
    (is (= 2 (:findings-remaining ev)))
    (is (= 1 (:remaining-handed ev)) "one of the two already has a repair in the branch")
    (is (= {"the repaired one" true "nobody reached it" nil}
           (into {} (map (juxt :title :handed)) (:open ev)))
        "and per finding, because the two ask opposite things of whoever picks
         them up — one needs checking, the other needs doing")
    (is (= ev (report/validate-event :review ev))
        "the ledger schema is closed; a rejected append is swallowed to stderr")
    (let [md (report/report->markdown (assoc ev :format :review-report))]
      (is (str/includes? md "1 already repaired, unverified")))))

(deftest a-converged-run-carries-no-repaired-count-at-all
  ;; Zero overlap is the normal case, and a `0 already repaired` on every clean
  ;; run is noise that trains a reader to skip the line where it matters.
  (let [ev (t/review-event {:status :converged :history [] :findings []}
                           {:summary {:rounds 2 :fix-attempts 3}
                            :target {:base "main" :base-rev "x"}}
                           "/runs/r/report.json")]
    (is (not (contains? ev :remaining-handed)))
    (is (not (str/includes? (report/report->markdown (assoc ev :format :review-report))
                            "unverified")))))

(deftest a-decided-finding-leaves-the-remainder-and-lands-in-its-own-list
  ;; One run wrote two counts of what it was holding into adjacent ledger
  ;; entries — `:findings-remaining 2` in one and "holding 1 finding it has no
  ;; move for" in the next — because convergence read `stages/settled?` and the
  ;; remainder removed only :closed. The count is what propagates: to the board,
  ;; to the analysis payload and to the banner of the session that reads it.
  (let [final {:status  :converged
               :history [{:iter 1 :findings [{:handle "h1" :id "h1"
                                              :title "the shipped defect"
                                              :file "src/a.clj" :line-start 12
                                              :disposition :declined
                                              :because "the shape is wrong, not this line"}
                                             {:handle "h2" :id "h2"
                                              :title "needs a human"
                                              :disposition :park}]}]
               :findings []}
        ev    (t/review-event final
                              {:summary {:rounds 2 :fix-attempts 2}
                               :target {:base "main" :base-rev "x"}}
                              "/runs/r/report.json")]
    (is (= 1 (:findings-remaining ev))
        "the park alone is owed; counting the decline beside it is the run
         contradicting its own convergence check")
    (is (= 1 (:findings-kept ev)))
    (is (= ["the shipped defect"] (mapv :title (:kept ev)))
        "and the decision stays on the record, with the reason the warden gave")
    (is (= "the shape is wrong, not this line" (:because (first (:kept ev)))))
    (is (= ev (report/validate-event :review ev))
        "the ledger schema is closed; a rejected append is swallowed to stderr")
    (let [md (report/report->markdown (assoc ev :format :review-report))]
      (is (str/includes? md "1 remaining"))
      (is (str/includes? md "1 kept"))
      (is (str/includes? md "Decided and kept")
          "under its own heading — read under `Still open` it tells whoever
           picks the entry up that a settled decision is outstanding work"))))

(deftest a-run-that-decided-nothing-carries-no-kept-list
  ;; Omitted rather than empty, like every other optional count on the entry: a
  ;; `0 kept` on every clean run trains a reader to skip the line where it says
  ;; something.
  (let [ev (t/review-event {:status :clean :history [] :findings []}
                           {:summary {:rounds 1 :fix-attempts 0}
                            :target {:base "main" :base-rev "x"}}
                           "/runs/r/report.json")]
    (is (not (contains? ev :kept)))
    (is (not (contains? ev :findings-kept)))
    (is (not (str/includes? (report/report->markdown (assoc ev :format :review-report))
                            "kept")))))

(deftest an-open-finding-records-the-layer-that-owes-it
  ;; The label is what a later run joins this list back onto its own targets
  ;; by. A patch hash cannot: a `fix` row is asking for the repair that moves it.
  (let [final {:status :escalated
               :history [{:iter 1 :findings [{:id "f1" :title "the extent reader"
                                              :from-layer "stack"
                                              :owner-layer "method-extraction"
                                              :disposition :fix}]}]
               :findings []}
        [o]   (:open (t/review-event final
                                     {:summary {:rounds 1 :fix-attempts 0}
                                      :target {:base "main" :base-rev "x"}}
                                     "/runs/r/report.json"))]
    (is (= "method-extraction" (:layer o))
        "the warden's owner is where the repair goes, whoever reported it")))

(deftest a-quiet-run-still-records-what-the-run-before-it-left-owed
  ;; Otherwise the obligation disappears at the first quiet run rather than at
  ;; the run that settles it: this entry is the whole of what the next run gets.
  (let [final {:status :unresolved :history [] :findings []
               :carry {:inherited-open
                       [{:id "cc56069f" :layer "method-extraction"
                         :title "Preserve tagged-literal identity"
                         :where "core.clj:122" :disposition :fix :handed true}]}}
        ev    (t/review-event final
                              {:summary {:rounds 1 :fix-attempts 0}
                               :target {:base "main" :base-rev "x"}}
                              "/runs/r/report.json")
        [o]   (:open ev)]
    (is (= 1 (:findings-remaining ev)))
    (is (= "cc56069f" (:id o)))
    (is (true? (:inherited o))
        "a reader has to be able to tell an obligation this run raised from one
         it merely failed to answer")
    (is (nil? (:handed o))
        "the claim that a repair sits unread was the last run's; this run put
         the row in front of the reviewer of its own layer")
    (is (= ev (report/validate-event :review ev)))
    (is (str/includes? (report/report->markdown (assoc ev :format :review-report))
                       "carried from the previous run")
        "two people having seen the defect and one having seen it twice are
         different evidence, and the entry is where a human reads which"))

  (testing "a finding this run raised again is not carried beside itself"
    (let [final {:status :escalated
                 :history [{:iter 1 :findings [{:id "cc56069f" :title "the extent reader"
                                                :owner-layer "method-extraction"
                                                :disposition :fix}]}]
                 :findings []
                 :carry {:inherited-open [{:id "cc56069f" :layer "method-extraction"
                                           :title "Preserve tagged-literal identity"
                                           :disposition :fix}]}}
          ev    (t/review-event final
                                {:summary {:rounds 1 :fix-attempts 0}
                                 :target {:base "main" :base-rev "x"}}
                                "/runs/r/report.json")]
      (is (= 1 (:findings-remaining ev)) "one defect, counted once")
      (is (= "the extent reader" (:title (first (:open ev))))
          "the run that raised it owns it, and its own ruling is the current one"))))

(deftest review-event-omits-open-when-nothing-is-owed
  (let [ev (t/review-event {:status :clean :history [] :findings []}
                           {:summary {:rounds 1 :fix-attempts 0}
                            :target {:base "main" :base-rev "x"}}
                           "/runs/r/report.json")]
    (is (= 0 (:findings-remaining ev)))
    (is (not (contains? ev :open)) "absent rather than an empty vector")))

(deftest review-event-derives-verdict-and-counts
  (let [final  {:status :escalated :findings [{:file "a" :line-start 1 :title "x"}
                                              {:file "b" :line-start 2 :title "y"}]}
        report {:summary {:rounds 3 :fix-attempts 4}
                :target  {:base "main" :base-rev "deadbee"}}
        ev     (t/review-event final report "/runs/r/report.json")]
    (is (= :review-report (:format ev)))
    (is (= :escalated (:status ev)))
    (is (= "main" (:base ev)))
    (is (= "deadbee" (:base-rev ev)))
    (is (= 3 (:rounds ev)))
    (is (= 4 (:fix-attempts ev)))
    (is (= 2 (:findings-remaining ev)))
    (is (= "/runs/r/report.json" (:report-path ev)))))

(deftest review-event-defaults-missing-counts-to-zero
  (let [ev (t/review-event {:status :review-failed}
                           {:target {:base "main" :base-rev nil}}
                           nil)]
    (is (= 0 (:rounds ev)))
    (is (= 0 (:fix-attempts ev)))
    (is (= 0 (:defects-settled ev)))
    (is (= 0 (:findings-remaining ev)))
    (is (nil? (:base-rev ev)))))

(deftest the-entry-counts-defects-removed-apart-from-repairs-dispatched
  ;; The two are different sizes and the entry published only the larger, under
  ;; the name that means the smaller: `:findings-fixed` was the sum of every
  ;; round's dispatch count, so a handle handed out twice was two "fixed" and
  ;; its defect could still be on the branch. Here the same handle is dispatched
  ;; in rounds 1 and 2, repaired in 2, and one other is dispatched once and
  ;; still reported in the final round.
  (let [final {:status  :escalated
               :history [{:iter 1 :fixed-count 1
                          :findings [{:handle "h1" :id "h1" :title "the stubborn one"
                                      :disposition :fix}]
                          :fixes [{:layer "l" :commit "c1" :handed ["h1"]}]}
                         {:iter 2 :fixed-count 2
                          :findings [{:handle "h1" :id "h1" :title "the stubborn one"
                                      :disposition :fix}
                                     {:handle "h2" :id "h2" :title "raised late"
                                      :disposition :fix}]
                          :fixes [{:layer "l" :commit "c2" :handed ["h1" "h2"]}]}]
               ;; Round 3 re-reports h2 and says nothing about h1: the repair
               ;; aimed at h1 in round 2 held.
               :findings [{:handle "h2" :id "h2" :title "raised late"
                           :disposition :fix}]}
        ev    (t/review-event final
                              {:summary {:rounds 3 :fix-attempts 3}
                               :target {:base "main" :base-rev "x"}}
                              "/runs/r/report.json")]
    (is (= 3 (:fix-attempts ev)) "three dispatches over three rounds")
    (is (= 1 (:defects-settled ev))
        "one defect actually came off the branch — h1, whose repair the next
         round read and had nothing to say about")
    (is (= 1 (:findings-remaining ev))
        "h2's repair is in the final round, so no reviewer has read it")
    (is (= ev (report/validate-event :review ev))
        "the ledger schema is closed; a rejected append is swallowed to stderr")))

(deftest the-entry-says-how-much-of-the-stack-was-read
  ;; A `clean` over three targets out of eight and one over all eight were the
  ;; same entry. The difference is whether the verdict was reached this run or
  ;; remembered from an earlier one, and report.json — which could say — lives
  ;; in a run dir that is routinely gone before anyone opens the workstream.
  (let [rpt {:summary {:rounds 1 :fix-attempts 0}
             :target  {:base "main" :base-rev "x"}
             :rounds  [{:round 1
                        :phases [{:phase "review" :status "ok"
                                  :layers [{:label "one" :status "reviewed"}
                                           {:label "two" :status "skipped"}
                                           {:label "three" :status "skipped"}
                                           {:label "stack" :stack? true
                                            :status "reviewed"}]}]}]}
        ev  (t/review-event {:status :clean :history [] :findings []} rpt
                            "/runs/r/report.json")]
    (is (= 2 (:targets-reviewed ev)))
    (is (= 2 (:targets-skipped ev)))
    (is (= ev (report/validate-event :review ev)))
    (is (str/includes? (report/report->markdown (assoc ev :format :review-report))
                       "2 of 4 targets read this run")))
  ;; A run that never resolved a target claims nothing rather than claiming zero
  ;; coverage — the second reads as a review that skipped everything.
  (let [ev (t/review-event {:status :review-failed :history [] :findings []}
                           {:summary {:rounds 1 :fix-attempts 0}
                            :target {:base "main" :base-rev "x"}}
                           "/runs/r/report.json")]
    (is (not (contains? ev :targets-reviewed)))
    (is (not (contains? ev :targets-skipped)))))

(deftest a-layer-read-once-is-read-even-if-later-rounds-skip-it
  ;; Skipped in the round it converged in is how convergence LOOKS; a layer the
  ;; loop opened at any point this run carries this run's verdict.
  (let [rpt {:summary {:rounds 2 :fix-attempts 1}
             :target  {:base "main" :base-rev "x"}
             :rounds  [{:round 1
                        :phases [{:phase "review" :status "ok"
                                  :layers [{:label "one" :status "reviewed"}
                                           {:label "two" :status "skipped"}]}]}
                       {:round 2
                        :phases [{:phase "review" :status "ok"
                                  :layers [{:label "one" :status "skipped"}
                                           {:label "two" :status "skipped"}]}]}]}
        ev  (t/review-event {:status :clean :history [] :findings []} rpt
                            "/runs/r/report.json")]
    (is (= 1 (:targets-reviewed ev)))
    (is (= 1 (:targets-skipped ev))
        "only the layer no round ever opened is carried from an earlier run")))

(deftest append-review-entry-writes-when-workstream-resolves
  (let [appended (atom nil)]
    (with-redefs [lifecycle/session-from-cwd (fn [_] {:project "brian" :session "s1"})
                  csession/workstream-id-for (fn [_ _] "ws-1")
                  ws/append-entry! (fn [p id entry content]
                                     (reset! appended {:p p :id id :entry entry :content content})
                                     "/path")]
      (let [ret (t/append-review-entry! "/w"
                                        {:status :converged :findings []}
                                        {:summary {:rounds 1 :fix-attempts 0}
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
                                      {:summary {:rounds 1 :fix-attempts 0}
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
      (is (str/includes? out "the baseline holds against the code")))))

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
  ;; the only bound on a single hung round — which is why it now DEFAULTS rather
  ;; than being absent. This test previously asserted "none by default, same as
  ;; the diff loop" directly beneath that same comment, which is the hole stated
  ;; in two sentences that contradict each other: the only bound there is, and
  ;; there is none unless you ask.
  (let [seen (atom nil)]
    (with-redefs [rloop/run-loop (fn [cfg] (reset! seen cfg) {:status :sufficient})]
      (with-out-str (t/baseline-cmd ":cwd" "/w" ":budget" "30m"))
      (is (= "30m" (:budget @seen)) "an explicit budget wins")
      (with-out-str (t/baseline-cmd ":cwd" "/w"))
      (is (= t/default-launch-budget (:budget @seen))
          "and one is declared when the caller names none")
      (is (some? t/default-launch-budget)))))

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
  ;; closed-vocabulary keys — a design check and a baseline gap — carry it as the
  ;; keyword after a tag. Printing the tag names the mechanism instead of the
  ;; thing the run could not resolve.
  (let [f #'tasks.nido-review/finding-name]
    (is (= "judge-never-writes" (f [[:claim-id "judge-never-writes"] 0])))
    (is (= "decomposable"       (f [[:blocks :decomposable] 1])))
    (is (= "routing-coherent"   (f [[:check :routing-coherent] 0])))
    (is (= "src/a.clj:1"        (f [[:evidence ["src/a.clj:1"]] 0])))))

(deftest baseline-cmd-can-name-which-baseline-to-verify
  ;; A workstream can hold baselines of different areas and a design cites one
  ;; specifically. Without this the only reachable baseline is the newest, so the
  ;; advice a blocked design gives — verify the baseline it cites — names a
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
  ;; Falling back to the newest would verify the same wrong baseline with no way
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
                                  :detail "the design cites the baseline at entry 4, and no round has found that baseline sufficient"}})]
    (let [out (with-out-str (t/design-cmd ":cwd" "/w"))]
      (is (str/includes? out "the baseline at entry 4")))))

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
  ;; Measured: two rounds on one baseline, shape and composition refuted twice,
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
              {:disposition :recut :title "v"}]
             nil))))

(deftest a-parked-finding-becomes-an-answerable-halt
  ;; The ledger refuses a choice written as prose, and rightly — an essay can
  ;; only be answered by typing one back. The branches have to be options.
  (let [b (t/parked-blocker
           [{:disposition :park :title "the aggregate rounds twice"}
            {:disposition :fix :title "not this one"}]
           nil)]
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
  (let [b (t/parked-blocker [{:disposition :park :title "t"}] nil)]
    (is (= b (report/validate-event :blocker b)))))

(deftest with-no-verdict-the-gate-asks-and-recommends-nothing
  ;; A run with no design record to judge against has derived no answer, and a
  ;; flagged branch would be a recommendation nobody made.
  (let [b (t/parked-blocker [{:disposition :park :title "t"}] nil)]
    (is (not-any? :recommended? (:options b)))
    (is (str/includes? (:needs b) "Does the design stand?"))))

(deftest a-standing-verdict-that-names-a-repair-becomes-a-third-branch
  ;; The two original branches both discard the remedy: one declines findings the
  ;; verdict had already worked out how to fix, the other throws away a record it
  ;; had just found sound. Neither is what the run concluded.
  (let [b (t/parked-blocker [{:disposition :park :title "socket ownership"}]
                            {:verdict :strained
                             :needs "fork the scope before opening the socket"})
        third (last (:options b))]
    (is (= 3 (count (:options b))))
    (is (= "fork the scope before opening the socket" (:summary third))
        "the remedy travels verbatim — option-input replays a chosen branch's summary
         to the agent, so this is how the repair survives the click")
    (is (true? (:recommended? third)))
    (is (not-any? :recommended? (butlast (:options b)))
        "one branch carries the verdict's answer, not several")
    (is (str/includes? (:needs b) "strained")
        "the gate states the answer it holds instead of asking the question again")
    (is (= b (report/validate-event :blocker b))
        "a three-branch halt is still a halt the ledger will take")))

(deftest a-verdict-that-invalidates-recommends-superseding-and-adds-no-branch
  ;; :invalidated and :standing-challenged put the record itself in question, so
  ;; a repair inside the existing design is not on offer however specific the
  ;; verdict's :needs is — offering it would be the gate contradicting the
  ;; judgment it is carrying.
  (let [b (t/parked-blocker [{:disposition :park :title "t"}]
                            {:verdict :invalidated :needs "the boundary has to move"})]
    (is (= 2 (count (:options b))))
    (is (true? (:recommended? (second (:options b)))))
    (is (nil? (:recommended? (first (:options b)))))))

(deftest a-standing-verdict-naming-no-repair-recommends-declining
  (let [b (t/parked-blocker [{:disposition :park :title "t"}] {:verdict :sound})]
    (is (= 2 (count (:options b)))
        "there is no remedy to carry, so there is no third answer")
    (is (true? (:recommended? (first (:options b)))))))

(deftest the-gate-carries-the-wardens-reason-for-stopping
  ;; The title says which defect; only `because` says what is being decided. It
  ;; already reaches report.json and the :review entry, and both of those are
  ;; read by someone who went looking — this is the artifact that arrives.
  (let [b (t/parked-blocker
           [{:disposition :park
             :title "socket ownership"
             :because (str "a client-side gate cannot cover the interval between the "
                           "browser's decision and the handler's execution")}]
           nil)]
    (is (str/includes? (:summary b) "cannot cover the interval")
        "a human deciding whether the remedy is a patch or a boundary needs the
         reason, and the gate is the only place they are shown")
    (is (= b (report/validate-event :blocker b)))))

(deftest a-park-with-no-reason-still-names-the-finding
  ;; `park` requires no `because` in the vocabulary, so a warden may omit it. The
  ;; gate degrades to what it had before rather than rendering a dangling dash.
  (let [b (t/parked-blocker [{:disposition :park :title "socket ownership"}] nil)]
    (is (str/includes? (:summary b) "socket ownership"))
    (is (not (str/includes? (:summary b) "—")))))

(deftest a-recurrence-park-is-not-asked-as-a-design-question
  ;; Ground (b): the warden set same_as, which the vocabulary says needs no design
  ;; record at all. Asking whether the design stands is how the gate offered to
  ;; supersede a record the run's own report listed as missing.
  (let [b (t/parked-blocker
           [{:disposition :park :title "start races the socket" :same-as "aeee857f"
             :because "two repairs each narrowed the window and left a smaller one"}]
           nil)]
    (is (not (str/includes? (:needs b) "design")))
    (is (str/includes? (:needs b) "third attempt")
        "the question a recurrence park asks is whether the remedy is a decision,
         and the human has to be able to tell which question they answered")
    (is (not-any? #(str/includes? (:summary %) "Supersede") (:options b))
        "no branch may offer to supersede a record this run never had")
    (is (= 2 (count (:options b))))
    (is (= b (report/validate-event :blocker b)))))

(deftest one-park-raised-against-an-invariant-keeps-the-design-question
  ;; The grounds are per finding and the gate is one artifact, so the ground has
  ;; to be decided over the whole set. A design question present anywhere in it
  ;; is the one no other branch can express, so it is the one that gets asked.
  (let [b (t/parked-blocker
           [{:disposition :park :title "start races the socket" :same-as "aeee857f"}
            {:disposition :park :title "the aggregate rounds twice"}]
           nil)]
    (is (str/includes? (:needs b) "Does the design stand?"))))

(deftest a-recurrence-gate-carries-a-verdicts-repair-as-the-third-branch
  ;; A repair the verdict names is the one move neither attempt made, which is a
  ;; direct answer to "is the remedy another patch?" — and the two ground
  ;; branches can only say "try again" and "stop trying".
  (let [b (t/parked-blocker
           [{:disposition :park :title "start races the socket" :same-as "aeee857f"}]
           {:verdict :strained :needs "let the server's start claim own transport liveness"})
        third (last (:options b))]
    (is (= 3 (count (:options b))))
    (is (= "let the server's start claim own transport liveness" (:summary third))
        "option-input replays the branch verbatim, so this is how the remedy
         survives the click")
    (is (true? (:recommended? third)))))

(deftest a-recurrence-gate-flags-the-decision-branch-when-the-record-does-not-stand
  ;; The only reading of a design verdict that bears on a recurrence park: a
  ;; record that does not stand says the remedy is a decision. One that stands
  ;; says nothing about whether a third patch would hold, so nothing is flagged.
  (let [invalidated (t/parked-blocker
                     [{:disposition :park :title "t" :same-as "aeee857f"}]
                     {:verdict :invalidated :needs "the boundary has to move"})
        sound       (t/parked-blocker
                     [{:disposition :park :title "t" :same-as "aeee857f"}]
                     {:verdict :sound})]
    (is (true? (:recommended? (second (:options invalidated)))))
    (is (nil? (:recommended? (first (:options invalidated)))))
    (is (not-any? :recommended? (:options sound))
        "a verdict that answers a different question must not appear to have
         answered this one")))

(deftest a-run-outside-a-session-says-what-it-cannot-reach
  ;; It runs — and should — but without the cache, the ledger, the design record
  ;; and the stance. It skips nothing, judges against no invariants, writes no
  ;; entry, and reports exactly as a complete run does.
  (with-redefs [stages/project+ws-from-cwd (fn [_] nil)]
    (let [c (t/run-context "/tmp/somewhere")]
      (is (empty? (:has c)))
      (is (= #{"workstream ledger" "convergence cache" "design record" "project stance"}
             (set (:missing c))))))
  (with-redefs [stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                stages/discover-design-record (fn [_] {:shape "s"})
                stages/read-stance (fn [_] "a stance")]
    (let [c (t/run-context "/w")]
      (is (empty? (:missing c))))))

(deftest the-report-records-what-the-run-could-reach
  ;; A thin run and a full one produced indistinguishable reports, and only the
  ;; second was worth trusting.
  (let [r (rreport/init {:run-id "r" :cwd "/w" :base "main" :started-at "t0"
                         :context {:has [] :missing ["design record"]}})]
    (is (= {:has [] :missing ["design record"]} (get-in r [:target :context]))))
  (is (not (contains? (:target (rreport/init {:run-id "r" :cwd "/w" :base "main"
                                              :started-at "t0"}))
                      :context))
      "absent rather than empty when nothing was asked"))

;; ── The design verdict, and where it is recorded ────────────────────────────

(def ^:private a-design {:seq 3 :invariants ["a total is rounded exactly once"]})

(def ^:private a-verdict
  {:format :design-verdict :verdict :strained :round 1 :design-seq 3
   :reason "one seam is under pressure"
   :needs "amend the invariant's wording"})

(deftest a-verdict-the-ledger-refuses-is-still-returned-for-the-report
  ;; The failure this exists to prevent: the pass ran for four minutes, produced
  ;; the run's most informative artifact, and the ledger's write contract refused
  ;; it — leaving one stderr line and no record on any channel.
  (with-redefs [stages/discover-design-record (fn [_] a-design)
                verdict/run! (fn [_] a-verdict)
                lifecycle/session-from-cwd (fn [_] {:project "nido" :session "s1"})
                csession/workstream-id-for (fn [_ _] "ws-1")
                ws/append-entry! (fn [& _]
                                   (throw (ex-info "Invalid event report"
                                                   {:explain {:errors [{:in [:needs]
                                                                        :type :malli.core/extra-key}]}})))]
    (let [outcome (t/append-design-verdict! "/w" {:status :converged :findings [{:title "x"}]}
                                            {} {:run-id "r"})]
      (is (= :answered (:outcome outcome)))
      (is (= :refused (:ledger outcome)))
      (is (= a-verdict (:verdict outcome)) "the verdict itself survives the refusal")
      (is (str/includes? (:because outcome) "[:needs]")
          "the refusal names the key the write contract would not take")
      (is (str/includes? (:because outcome) "extra-key")))))

(deftest a-verdict-outside-a-workstream-is-still-returned-for-the-report
  (with-redefs [stages/discover-design-record (fn [_] a-design)
                verdict/run! (fn [_] a-verdict)
                lifecycle/session-from-cwd (fn [_] nil)]
    (let [outcome (t/append-design-verdict! "/w" {:status :converged :findings [{:title "x"}]}
                                            {} {:run-id "r"})]
      (is (= :no-workstream (:ledger outcome)))
      (is (= a-verdict (:verdict outcome))
          "a review with no ledger to write to still has a run dir to write to"))))

(deftest a-pass-that-produced-no-verdict-says-so-rather-than-nothing
  (with-redefs [stages/discover-design-record (fn [_] a-design)
                verdict/run! (fn [_] nil)]
    (let [outcome (t/append-design-verdict! "/w" {:status :converged :findings [{:title "x"}]}
                                            {} {:run-id "r"})]
      (is (= :no-answer (:outcome outcome))
          "the budget was spent; a run has to be able to tell that from a pass that never started")
      (is (nil? (:verdict outcome))))))

(deftest a-tree-holding-conflict-markers-spends-no-design-verdict
  ;; The pass gives an agent tools and points it at the working copy, so
  ;; committed markers arrive as source and the design is judged against text jj
  ;; wrote. It ran on such a branch once and came out clean only because of
  ;; which files had happened to conflict.
  (let [ran (atom false)]
    (with-redefs [stages/discover-design-record (fn [_] a-design)
                  layers/conflicted (fn [_ _] ["xlortuwzrtlu" "spxkmpurtnms"])
                  verdict/run! (fn [_] (reset! ran true) a-verdict)]
      (doseq [status [:fix-conflicted :stack-conflicted :converged]]
        (let [outcome (t/append-design-verdict! "/w" {:status status :findings []}
                                                {} {:run-id "r" :base "main"})]
          (is (= :skipped (:outcome outcome))
              (str "the tree decides and not the status: " status
                   " reads the same markers as any other status does"))
          (is (str/includes? (:because outcome) "xlortuwzrtlu")
              "the change ids are the only pointer at what to resolve, and a
               skipped pass is recorded nowhere but here")))
      (is (false? @ran) "no agent is launched against a tree nothing can parse"))))

(deftest a-readable-tree-still-gets-its-verdict
  ;; The gate is a probe and not a blanket refusal on a status that sounds
  ;; risky. This pass is the run's most valuable artifact and a legible tree
  ;; must not lose it.
  (with-redefs [stages/discover-design-record (fn [_] a-design)
                layers/conflicted (fn [_ _] [])
                verdict/run! (fn [_] a-verdict)
                lifecycle/session-from-cwd (fn [_] nil)]
    (is (= :answered (:outcome (t/append-design-verdict!
                                "/w" {:status :clean :findings [] :history []}
                                {} {:run-id "r" :base "main"})))
        "a clean review still has the design's invariants left to confirm")))

(deftest a-workspace-that-cannot-be-asked-reads-as-legible
  ;; `layers/conflicted` already takes a non-zero exit for [], so the only thing
  ;; left to throw is jj not running at all — and a branch with no jj holds none
  ;; of jj's markers. Refusing there would cost every plain-git project its
  ;; verdict to guard against a state it cannot be in.
  (with-redefs [stages/discover-design-record (fn [_] a-design)
                layers/conflicted (fn [_ _] (throw (java.io.IOException. "no such program: jj")))
                verdict/run! (fn [_] a-verdict)
                lifecycle/session-from-cwd (fn [_] nil)]
    (is (= :answered (:outcome (t/append-design-verdict!
                                "/w" {:status :clean :findings [] :history []}
                                {} {:run-id "r" :base "main"}))))))

(deftest with-no-design-record-the-pass-never-runs-and-records-nothing
  (let [ran (atom false)]
    (with-redefs [stages/discover-design-record (fn [_] nil)
                  verdict/run! (fn [_] (reset! ran true) a-verdict)]
      (is (nil? (t/append-design-verdict! "/w" {:status :converged :findings [{:title "x"}]}
                                          {} {:run-id "r"})))
      (is (false? @ran) "nothing to judge against, so nothing is spent"))))

(deftest the-run-dir-carries-the-verdict-when-the-loop-ends
  ;; End to end: the claim is about report.json on disk, not about a return value.
  (with-redefs [rloop/run-loop (run-loop-writing-a-report :converged)
                stages/discover-design-record (fn [_] a-design)
                verdict/run! (fn [_] a-verdict)
                lifecycle/session-from-cwd (fn [_] nil)]
    (t/loop-cmd ":cwd" "/w")
    (let [report (->> (fs/list-dir (cstate/runs-dir))
                      (map #(fs/path % "report.json"))
                      (filter fs/exists?)
                      first
                      str
                      slurp
                      (#(json/parse-string % true)))]
      (is (= "strained" (get-in report [:design-verdict :verdict :verdict]))
          "report.json is where the rest of the run's evidence lives, and now the verdict too")
      (is (= "no-workstream" (get-in report [:design-verdict :ledger]))))))

(deftest the-design-verdict-is-recorded-before-the-halt-it-answers
  ;; Two failures ride on this order, and the ledger positions are the evidence
  ;; for both. The gate asks whether the design stands; written first, it asks a
  ;; question the run answers minutes later. And a :design-verdict places on the
  ;; :design stage — appended AFTER a halt it reads to pipeline/unanswered-blocker
  ;; as the work having moved on regardless, so the halt reached no gate at all.
  (let [kinds (atom [])]
    (with-redefs [rloop/run-loop (fn [cfg]
                                   (assoc ((run-loop-writing-a-report :escalated) cfg)
                                          :findings [{:disposition :park
                                                      :title "socket ownership"}]))
                  stages/discover-design-record (fn [_] a-design)
                  layers/conflicted (fn [_ _] [])
                  verdict/run! (fn [_] a-verdict)
                  lifecycle/session-from-cwd (fn [_] {:project "nido" :session "s1"})
                  csession/workstream-id-for (fn [_ _] "ws-1")
                  ws/append-entry! (fn [_ _ {:keys [kind]} _] (swap! kinds conj kind))]
      (t/loop-cmd ":cwd" "/w")
      (is (= [:review :design-verdict :blocker] @kinds)))))

;; ── An abort's own account ──────────────────────────────────────────────────
;;
;; The stop that most needs a human to act is the one that computed the facts
;; making it actionable and then dropped every one of them. Three destinations,
;; because a run dir is routinely reclaimed before anyone reads the workstream
;; and the terminal is the only reader for a round somebody ran themselves.

(defn- drifted-report
  "A folded report for a run that reshaped in an early round and drifted later —
   the shape the terminal line and the ledger entry both read."
  []
  {:target  {:base "main" :base-rev "deadbee"}
   :summary {:rounds 4 :fix-attempts 2}
   :rounds  [{:round 2
              :phases [{:phase "reshape"
                        :reshapes [{:handle "a65960a8" :title "the doc-ordering seam"
                                    :outcome "fold" :applied? true
                                    :lower "plan-stability-bench"
                                    :upper "plan-stability-doc"}
                                   {:handle "71b62ca9" :title "unreachable span"
                                    :outcome "span-has-holes"
                                    :because "the span skips two layers"}]}]}
             {:round 4 :phases [{:phase "reshape" :reshapes []}]}]})

(deftest a-drifted-run-names-both-revisions-on-the-terminal
  ;; The refusal is ABOUT a difference between two revisions, and the operator
  ;; was told only that there was one. Which revision moved is what separates a
  ;; rebase they did themselves from another session moving the tree, and it
  ;; decides whether re-running is the whole answer.
  (let [out (str/join "\n"
                      (t/outcome-lines {:status :workspace-drifted
                                        :drift {:reviewed-at "8f1c0a3d"
                                                :now "2b7e49c1"}}
                                       {:rounds []}
                                       "/runs/r/report.json"))]
    (is (str/includes? out "8f1c0a3d"))
    (is (str/includes? out "2b7e49c1"))))

(deftest an-unaskable-workspace-still-says-which-revision-was-reviewed
  ;; `resolve-rev` answers nil when jj cannot be run, and half the pair is worth
  ;; more than a line that omits both — the pinned revision is the one a reader
  ;; can go and look at.
  (let [out (str/join "\n"
                      (t/outcome-lines {:status :workspace-drifted
                                        :drift {:reviewed-at "8f1c0a3d" :now nil}}
                                       {:rounds []}
                                       "/runs/r/report.json"))]
    (is (str/includes? out "8f1c0a3d"))
    (is (str/includes? out "a revision jj would not name"))))

(deftest the-terminal-says-the-loop-reshaped-the-branch
  ;; A fold is the loop rewriting the code the operator is standing in, and it
  ;; reached report.json alone — a JSON file in a run dir nobody was told to
  ;; open. It is read off the REPORT because a context is rebuilt each round:
  ;; this fold happened in round 2 and the run ended in round 4.
  (let [out (str/join "\n"
                      (t/outcome-lines {:status :workspace-drifted}
                                       (drifted-report)
                                       "/runs/r/report.json"))]
    (is (str/includes? out "fold"))
    (is (str/includes? out "plan-stability-bench…plan-stability-doc"))
    (is (str/includes? out "round 2"))
    (is (not (str/includes? out "span-has-holes"))
        "a refused recut is a park and travels with the open findings; what is
         reported here is the branch having moved")))

(deftest a-drifted-run-names-the-layers-no-fixer-was-launched-for
  ;; Without them an abort's fix phase is indistinguishable from a round with no
  ;; work in it — same empty fix list, same silence — and this one was holding a
  ;; full plan when it stopped.
  (let [out (str/join "\n"
                      (t/outcome-lines {:status :workspace-drifted
                                        :drift {:reviewed-at "8f1c0a3d" :now "2b7e49c1"}
                                        :unattempted [{:layer "audio-start" :handed ["07f8a5ee"]}
                                                      {:layer "speech-contract" :handed ["1a476c0b"]}]}
                                       {:rounds []}
                                       "/runs/r/report.json"))]
    (is (str/includes? out "audio-start"))
    (is (str/includes? out "speech-contract"))))

(deftest a-drifted-run-reaches-the-ledger-with-both-revisions
  ;; The durable copy. report.json lives in a run dir that is routinely gone by
  ;; the time anyone opens the workstream, so an entry that carries only the
  ;; status leaves the two numbers nowhere at all.
  (let [ev (t/review-event {:status :workspace-drifted :history [] :findings []
                            :drift {:reviewed-at "8f1c0a3d" :now "2b7e49c1"}}
                           {:summary {:rounds 4 :fix-attempts 2}
                            :target {:base "main" :base-rev "deadbee"}}
                           "/runs/r/report.json")]
    (is (= {:reviewed-at "8f1c0a3d" :now "2b7e49c1"} (:drift ev)))
    (is (= ev (report/validate-event :review ev))
        "the ledger schema is closed and a refused append is swallowed to
         stderr, so an entry it will not take is an entry that never exists")
    (let [md (report/report->markdown (assoc ev :format :review-report))]
      (is (str/includes? md "8f1c0a3d"))
      (is (str/includes? md "2b7e49c1")))))

(def ^:private stale-drift
  "A drift stop on a working copy jj refused as stale, in a round that could
   not pin the revision its reviewers read — the shape with the least in it."
  {:now nil :stale? true
   :recover (str "jj refuses this working copy as stale: another operation rewrote"
                 " its commit without updating its files. Run `jj workspace"
                 " update-stale` before anything else. The fixer on middle had not"
                 " committed its edits; they are in /runs/r/fix-middle-round-1.patch.")})

(deftest a-stale-stop-tells-the-terminal-what-to-run-before-the-re-run
  ;; The remedy line says re-run, and on a stale copy the re-run cannot start:
  ;; every jj command fails until the update has run. The patch is the only copy
  ;; of a fixer's uncommitted edits, and the run dir holding it is reclaimed.
  (let [out (t/outcome-lines {:status :workspace-drifted :drift stale-drift}
                             {:rounds []} "/runs/r/report.json")
        at  #(first (keep-indexed (fn [i l] (when (str/includes? l %) i)) out))]
    (is (at "jj workspace update-stale"))
    (is (at "/runs/r/fix-middle-round-1.patch"))
    (is (< (at "update-stale") (at "→ "))
        "said before the remedy line, since it has to be done before it")
    (is (not (some #(str/includes? % "reviewed at") out))
        "no pinned revision, and no line pretending there was one")))

(deftest a-stale-stop-reaches-the-ledger-with-its-recovery
  (let [ev (t/review-event {:status :workspace-drifted :history [] :findings []
                            :drift stale-drift}
                           {:summary {:rounds 1 :fix-attempts 3}
                            :target {:base "main" :base-rev "deadbee"}}
                           "/runs/r/report.json")]
    (is (= stale-drift (:drift ev)))
    (is (= ev (report/validate-event :review ev))
        "a closed schema that refused the new keys would swallow the entry whole,
         and with it the one sentence saying what to run")
    (let [md (report/report->markdown (assoc ev :format :review-report))]
      (is (str/includes? md "jj workspace update-stale"))
      (is (str/includes? md "/runs/r/fix-middle-round-1.patch"))
      (is (not (str/includes? md "reviewed at "))))))

(deftest the-ledger-entry-says-the-loop-reshaped-the-stack
  ;; A run that squashed two layers together reported `0 fixed` and said nothing
  ;; about the rewrite. Whoever picks the branch up next is reading a stack the
  ;; loop reshaped, which no count on the entry can tell them.
  (let [ev (t/review-event {:status :workspace-drifted :history [] :findings []}
                           (drifted-report)
                           "/runs/r/report.json")
        [r] (:reshaped ev)]
    (is (= 1 (count (:reshaped ev))) "the refused recut is a park, not a rewrite")
    (is (= "fold" (:outcome r)))
    (is (= 2 (:round r)) "which round rewrote the stack, since the ids below moved with it")
    (is (= "plan-stability-bench" (:lower r)))
    (is (= ev (report/validate-event :review ev)))
    (is (str/includes? (report/report->markdown (assoc ev :format :review-report))
                       "plan-stability-doc"))))

;; ---- what a claimant is told about the runs that died on the tree ---------

(def ^:private a-dead-fixer
  {:run-id "review-3876f59b" :report-path "/runs/review-3876f59b/report.json"
   :in-flight {:round 1 :phase "fix"}})

(deftest a-run-that-died-reading-the-tree-is-reported-and-not-refused
  ;; Its reviewers mutated nothing, so the branch is exactly what they found.
  ;; It is still said out loud: the analysis it just queued appears on nido's
  ;; board, and a reader who was not told reads it as invented work.
  (let [o {:settled [{:run-id "review-f00636e7" :report-path "/runs/r/report.json"
                      :in-flight {:round 1 :phase "review"}}]
           :writing [] :proceed? true}
        out (str/join "\n" (t/orphans-settled-lines o))]
    (is (str/includes? out "review-f00636e7"))
    (is (str/includes? out "orphaned"))
    (is (str/includes? out "round 1's review phase"))
    (is (empty? (t/orphans-refusal-lines o)))))

(deftest nothing-is-said-when-nothing-died
  (is (empty? (t/orphans-settled-lines {:settled [] :writing [] :proceed? true})))
  (is (empty? (t/orphans-refusal-lines {:settled [] :writing []}))))

(deftest a-run-that-died-repairing-the-branch-says-what-the-branch-now-is
  (let [out (str/join "\n" (t/orphans-refusal-lines
                            {:settled [a-dead-fixer] :writing []}))]
    (is (str/includes? out "refused:"))
    (is (str/includes? out "landed whatever they got to"))
    (is (str/includes? out "Run this again")
        "the refusal fires once — its report has just been closed, so the next
         invocation reviews the branch as it now stands")))

(deftest an-orphan-still-being-written-to-asks-for-something-different
  ;; Not history: the fixers outlived the loop and nothing is supervising them.
  ;; Telling this reader to run it again would hand them the tree mid-edit,
  ;; which is the failure the whole check exists for.
  (let [out (str/join "\n" (t/orphans-refusal-lines
                            {:settled [] :writing [a-dead-fixer]}))]
    (is (str/includes? out "still running"))
    (is (str/includes? out "review-3876f59b"))
    (is (str/includes? out "wait for them to finish, or end them"))
    (is (not (str/includes? out "as it now stands"))
        "the branch is not going to stand still")))

;; ── the yardstick gate ──────────────────────────────────────────────────────

(deftest a-workstream-with-no-design-record-is-refused-before-anything-is-spent
  ;; The loop judges an implementation against the design it committed to. With
  ;; no record the warden is told the opposite of what the loop is for — "do NOT
  ;; park anything for contradicting an invariant" — so the one clause that keeps
  ;; a design question away from a fixer cannot fire. Measured before this
  ;; refused: 36 of 108 reviewed workstreams held a record, and of the findings a
  ;; reviewer marked `structural` with no composition kind to route them, 10 of
  ;; 13 went to a fixer and none was ever parked.
  (let [ran (atom false)]
    (with-redefs [t/no-yardstick (constantly {:reason :no-design-record :lines ["nope"]})
                  rloop/run-loop (fn [_] (reset! ran true) {:status :converged :history []})]
      (let [out (with-out-str
                  (binding [*err* *out*]
                    (is (= :no-design-record (t/loop-cmd ":cwd" "/w")))))]
        (is (false? @ran) "no reviewer is launched")
        (is (str/includes? out "nope") "the refusal says why, on stderr")))
    (is (empty? (queued-envelopes))
        "and nothing is queued for analysis — there is no run to analyse")))

(deftest a-refused-run-leaves-no-run-directory
  ;; The refusal is taken before the run id, the report and the activity claim.
  ;; One taken after them leaves a run dir and a claim for a review nobody
  ;; performed, and `reconcile/orphans` then has to tell that from a run that
  ;; died mid-flight.
  (with-redefs [t/no-yardstick (constantly {:reason :no-workstream :lines []})]
    (t/loop-cmd ":cwd" "/w")
    (is (empty? (filter #(str/starts-with? (str (fs/file-name %)) "review-")
                        (fs/list-dir (cstate/runs-dir))))
        "nothing on disk names a run that never started")))

(deftest a-refusal-exits-non-zero
  ;; A driver reads the exit code to decide whether the stage it asked for
  ;; happened. For these two it did not, and no report was written for it to
  ;; find that out from.
  (is (= 1 (t/exit-code :no-design-record)))
  (is (= 1 (t/exit-code :no-workstream)))
  (is (= 0 (t/exit-code :converged)) "an ordinary outcome is still a success"))

(deftest the-gate-names-the-stage-the-ledger-says-is-due
  ;; Guessing the remedy would tell a workstream that never got a baseline to go
  ;; and write a design. The ledger already knows which stage it is owed.
  (with-redefs [stages/project+ws-from-cwd (constantly [:p "ws-1"])
                stages/discover-design-record (constantly nil)
                pipeline/of (constantly {:next {:stage :write-baseline}})]
    (let [{:keys [reason lines]} (gate "/w")]
      (is (= :no-design-record reason))
      (is (some #(str/includes? % "owed write-baseline") lines))))
  (with-redefs [stages/project+ws-from-cwd (constantly [:p "ws-1"])
                stages/discover-design-record (constantly nil)
                pipeline/of (constantly {:next nil})]
    (is (some #(str/includes? % "Write the design record first")
              (:lines (gate "/w")))
        "a workstream owed nothing still gets an actionable line")))

(deftest a-workstream-holding-a-design-record-is-not-refused
  (with-redefs [stages/project+ws-from-cwd (constantly [:p "ws-1"])
                stages/discover-design-record (constantly {:seq 3 :shape "x"})]
    (is (nil? (gate "/w")))))

(deftest a-cwd-belonging-to-no-workstream-is-refused-with-its-own-remedy
  ;; A different ground from a missing record, because there is nowhere for one
  ;; to live — so the answer is to review from a session worktree, not to go and
  ;; write a design somewhere.
  (with-redefs [stages/project+ws-from-cwd (constantly nil)]
    (let [{:keys [reason lines]} (gate "/w")]
      (is (= :no-workstream reason))
      (is (some #(str/includes? % "session worktree") lines)))))
