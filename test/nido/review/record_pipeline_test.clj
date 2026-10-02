;; test/nido/review/record_pipeline_test.clj
(ns nido.review.record-pipeline-test
  "The baseline round driven as a loop: what each stage does with an answer, and
   which of the ways it can go wrong are terminal. Both agents are seams — the
   codex judge through `baseline-review!`, the amender through `agent/launch!` —
   so nothing here spawns a process."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [malli.core :as m]
   [nido.platform.core :as core]
   [nido.coordinator.agent :as agent]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.design.check :as design-check]
   [nido.review.loop :as rloop]
   [nido.review.record :as record]
   [nido.coordinator.report :as ledger-report]
   [nido.review.report :as report]
   [nido.review.settled :as settled]
   [nido.review.stages :as stages]))

(defn- with-tmp-nido-root
  "Every stage here writes where the real stage writes — run dirs, answer files
   — so without this the suite scatters run directories through the user's live
   ~/.nido/runs/. Redirecting the root is the fix rather than stubbing the calls
   that bite, because the hazard is structural: the next side effect a stage
   grows would land there too."
  [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f))
      (finally (fs/delete-tree tmp)))))

(use-fixtures :each with-tmp-nido-root)

(def ^:private a-baseline
  {:format :baseline
   :modules [{:id "mod-the-order-aggregate" :module "the order aggregate"
              :hides "the order in which lines are summed"
              :interface "an order's total"}]
   :composition "only the aggregate sees the lines, so only it sums them"
   :area "order totalling"
   :bounded-by "money amounts on an order"
   :shape "the aggregate is the only thing that sums lines"
   :load-bearing [{:id "c1" :property "the aggregate is the only summing path"
                   :evidence ["src/order/aggregate.clj:12"]}]
   :health [{:id "invoice-resums" :axis :design :observation "two summing paths"
             :evidence ["src/order/invoice.clj:88"]}]
   :read ["src/order/aggregate.clj"]})

(def ^:private a-finding
  {:cites ["the aggregate is the only summing path"]
   :claim "the invoice renderer sums independently"
   :evidence ["src/order/invoice.clj:88"]})

(defn- ctx [& {:as over}]
  (merge {:config {:cwd "/w" :run-id "r1"} :iter 1 :control :continue} over))

(defn- run [stage c] ((:run stage) c))

;; ── What identifies a baseline finding ──────────────────────────────────────

(deftest a-finding-is-keyed-on-the-code-it-cites-not-the-prose
  ;; The amender rewrites :cites — it quotes the property being refuted — so a
  ;; key over that text would make every round look new and the stall detector
  ;; would never fire.
  (is (= (record/baseline-finding-key a-finding)
         (record/baseline-finding-key
          (assoc a-finding :cites ["the aggregate is the only summing path, restated"]
                 :claim "the invoice renderer sums independently, restated")))))

(deftest two-findings-about-different-code-are-different-findings
  (is (not= (record/baseline-finding-key a-finding)
            (record/baseline-finding-key (assoc a-finding :evidence ["src/order/csv.clj:4"])))))

(deftest a-finding-citing-no-code-falls-back-to-its-text-and-cannot-collide
  (let [no-ev (dissoc a-finding :evidence)]
    (is (not= (record/baseline-finding-key no-ev)
              (record/baseline-finding-key a-finding)))
    (is (= [:cites (:cites no-ev)] (record/baseline-finding-base-key no-ev)))))

;; ── The judge stage ─────────────────────────────────────────────────────────

(deftest the-judge-reads-the-code-cwd-and-the-ledger-reads-the-other-one
  ;; A baseline describes the area BEFORE a change. Judged against a worktree that
  ;; already carries that change, the round reports the change's own modules as
  ;; things the baseline failed to mention, and the amender folds the change into
  ;; the record it was supposed to be judged against. So the revision is its own
  ;; axis: the agents read :code-cwd, the workstream still resolves from :cwd.
  (let [seen (atom nil)]
    (with-redefs [record/run-round! (fn [opts] (reset! seen opts) {:ok "{}"})
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ _] a-baseline)]
      (record/baseline-review! {:cwd "/ledger" :code-cwd "/base" :run-id "r1"})
      (is (= "/base" (:cwd @seen))
          "the judge reads the base revision, not the tree the work is in"))))

(deftest code-cwd-defaults-to-the-ledger-cwd
  (let [seen (atom nil)]
    (with-redefs [record/run-round! (fn [opts] (reset! seen opts) {:ok "{}"})
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ _] a-baseline)]
      (record/baseline-review! {:cwd "/ledger" :run-id "r1"})
      (is (= "/ledger" (:cwd @seen))
          "they are the same tree in the ordinary case, and nothing has to say so"))))

;; ── The tree a review read ──────────────────────────────────────────────────

(defn- reviewed-over
  "baseline-review! with a judge that finds the baseline sufficient, while the code
   identity reads as `trees` in turn — the first as the judge launches, the second
   as it returns. `answer` replaces what the round returns."
  ([trees] (reviewed-over trees {:ok "{\"verdict\":\"sufficient\",\"reason\":\"ok\",\"confirmed\":[\"c1\"],\"findings\":[]}"}))
  ([trees answer]
   (let [left (atom trees)]
     (with-redefs [record/run-round! (fn [_] answer)
                   stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                   ws/latest-entry (fn [_ _ _] (assoc a-baseline :seq 2))
                   settled/code-identity (fn [_] (let [t (first @left)] (swap! left rest) t))]
       (record/baseline-review! {:cwd "/w" :run-id "r1"})))))

(deftest a-review-names-the-tree-its-judge-read
  (let [r (reviewed-over ["tree-a" "tree-a"])]
    (is (= :sufficient (:verdict r)))
    (is (= "tree-a" (:code-identity r)))))

(deftest a-tree-that-moved-under-the-judge-is-not-recorded
  ;; The judge reads the live tree, so a confirmation made while it moved names no
  ;; single tree. The verdict still stands; it just settles nothing later.
  (let [r (reviewed-over ["tree-a" "tree-b"])]
    (is (= :sufficient (:verdict r)))
    (is (not (contains? r :code-identity)))))

(deftest a-tree-with-no-identity-is-not-recorded
  (is (not (contains? (reviewed-over [nil nil]) :code-identity))))

(deftest a-round-that-did-not-run-carries-no-tree
  (let [r (reviewed-over ["tree-a" "tree-a"] {:outcome :codex-failed :detail "d"})]
    (is (= :codex-failed (:outcome r)))
    (is (not (contains? r :code-identity)))))

(deftest an-accurate-verdict-stops-the-loop
  (with-redefs [record/baseline-review! (fn [_] {:format :baseline-review
                                                 :verdict :sufficient :reason "ok"})
                record/append! (fn [_ _] nil)]
    (let [out (run record/judge-stage (ctx))]
      (is (= :stop (:control out)))
      (is (= :sufficient (:status out)))
      (is (= [] (:findings out))))))

(deftest findings-carry-into-the-context-and-the-loop-continues
  (with-redefs [record/baseline-review! (fn [_] {:format :baseline-review
                                                 :verdict :falsified
                                                 :findings [a-finding]})
                record/append! (fn [_ _] nil)]
    (let [out (run record/judge-stage (ctx))]
      (is (nil? (:status out)))
      (is (= [(assoc a-finding :disputed-n 0)] (:findings out))
          "each finding carries how many times it has been objected to"))))

(deftest a-round-that-could-not-run-keeps-its-own-name
  ;; The distinction the one-shot round already held, and which matters more in
  ;; a loop: :codex-failed on round three is not convergence.
  (doseq [outcome [:codex-failed :no-output :nothing-to-check :no-record
                   :no-workstream :round-crashed :unusable-answer
                   :subjects-undeclared :declaration-unreadable]]
    (with-redefs [record/baseline-review! (fn [_] {:outcome outcome :detail "d"})
                  record/append! (fn [_ _] nil)]
      (is (= outcome (:status (run record/judge-stage (ctx))))
          (str outcome " must terminate under its own name")))))

;; ── What a claim is about ───────────────────────────────────────────────────

(def ^:private a-model-baseline
  {:format :baseline :strata [] :area "order totalling" :bounded-by "money amounts on an order"
   :model {:elements [{:id "canvas.order/aggregate" :sort :module}
                      {:id "canvas.order/total" :sort :operation}]
           :claims   [{:id "one-summing-path"
                       :about ["canvas.order/aggregate" "canvas.order/total"]
                       :statement "the aggregate is the only summing path"
                       :falsified-by "a caller that sums lines itself"
                       :evidence {:by :round}}]}})

(def ^:private aggregate-row {:id "canvas.order/aggregate" :sort :fukan.common.vocab.code.module/Module})
(def ^:private total-row {:id "canvas.order/total" :sort :fukan.common.vocab.code.operation/Operation})
(defn- listing [& rows] {:status :listed :elements (vec rows)})

(deftest a-subject-resolves-to-a-declared-element-of-its-recorded-sort
  (is (= [] (record/unresolved-subjects a-model-baseline (listing aggregate-row total-row))))
  (is (= ["canvas.order/total"]
         (record/unresolved-subjects a-model-baseline (listing aggregate-row)))
      "an element the declaration does not hold")
  (is (= ["canvas.order/total"]
         (record/unresolved-subjects
          a-model-baseline
          (listing aggregate-row (assoc total-row :sort :fukan.common.vocab.code.kind/Kind))))
      "one it holds under another sort — the record describes something else")
  (let [total-as-kind (assoc total-row :sort :fukan.common.vocab.code.kind/Kind)]
    (is (= [] (record/unresolved-subjects a-model-baseline
                                          (listing aggregate-row total-row total-as-kind)))
        "an id listed twice keeps the row of the recorded sort, whichever comes last")
    (is (= [] (record/unresolved-subjects a-model-baseline
                                          (listing aggregate-row total-as-kind total-row)))
        "and in either order"))
  (is (= ["canvas.order/total"]
         (record/unresolved-subjects
          a-model-baseline
          (listing aggregate-row
                   (assoc total-row :sort :fukan.common.vocab.code.kind/Kind)
                   (assoc total-row :sort :fukan.common.vocab.code.module/Module))))
      "an id listed twice under no recorded sort is still unresolved")
  (is (= ["canvas.order/aggregate" "canvas.order/total"]
         (record/unresolved-subjects a-model-baseline {:status :unmodelled}))
      "a project declaring no design resolves nothing")
  (is (= [] (record/unresolved-subjects a-baseline {:status :unmodelled}))
      "an older record's claims name no subject, so none is left unresolved"))

(def ^:private a-role-baseline
  (-> a-model-baseline
      (update-in [:model :elements] conj {:id "canvas.order/summers" :sort :role
                                          :plays ["canvas.order/aggregate"]})
      (update-in [:model :claims] conj {:id "summers-sum-once" :about ["canvas.order/summers"]
                                        :statement "whatever sums lines sums each once"
                                        :falsified-by "a summer that visits a line twice"
                                        :evidence {:by :round}})))

(defn- role-row [plays]
  {:id "canvas.order/summers" :sort :canvas.vocab.claim/Role :refs {:plays plays}})

(deftest a-role-is-played-by-whom-its-declaration-says
  (is (= [] (record/misplayed-roles a-role-baseline
                                    (listing aggregate-row total-row (role-row ["canvas.order/aggregate"])))))
  (is (= ["canvas.order/summers"]
         (record/misplayed-roles a-role-baseline
                                 (listing aggregate-row total-row
                                          (role-row ["canvas.order/aggregate" "canvas.order/total"]))))
      "a declared Role with another player binds a claim about it to something else")
  (is (= [] (record/misplayed-roles a-role-baseline (listing aggregate-row total-row)))
      "a role the declaration does not hold is unresolved, not misplayed")
  (is (= [] (record/misplayed-roles a-role-baseline {:status :unmodelled})))
  (is (= [] (record/misplayed-roles (update-in a-role-baseline [:model :elements 2] dissoc :plays)
                                    (listing aggregate-row total-row (role-row ["canvas.order/aggregate"]))))
      "a role recorded before players were authored has none to compare, not an empty membership")
  (is (str/includes? (record/settled-block {"canvas.order/summers" {}} a-role-baseline)
                     "played by: canvas.order/aggregate")
      "a role set apart as settled keeps its players in front of the judge")
  (testing "a round launches no judge while a role is played otherwise, and says which"
    (with-redefs [design-check/elements (fn [_ _] (listing aggregate-row total-row
                                                           (role-row ["canvas.order/total"])))]
      (let [r (#'record/undeclared-subjects :nido "/w" a-role-baseline nil)]
        (is (= :subjects-undeclared (:outcome r)))
        (is (str/includes? (:detail r) "declares other players for the roles: canvas.order/summers"))
        (is (not (str/includes? (:detail r) "holds no element")))))))

(deftest a-baseline-round-launches-no-judge-while-a-subject-is-undeclared
  (let [launched (atom 0)
        seen     (atom nil)
        run      (fn [baseline listed]
                   (reset! launched 0)
                   (with-redefs [stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                                 stages/read-stance (constantly nil)
                                 design-check/elements (fn [_ worktree] (reset! seen worktree) listed)
                                 record/run-round! (fn [_] (swap! launched inc)
                                                     {:outcome :no-output :detail "stub"})]
                     (record/baseline-review! {:cwd "/ledger" :code-cwd "/base" :run-id "r1"
                                               :baseline baseline})))]
    (let [out (run a-model-baseline (listing aggregate-row))]
      (is (= :subjects-undeclared (:outcome out)))
      (is (str/includes? (:detail out) "canvas.order/total") "which subject is the thing to act on")
      (is (= "/base" @seen) "resolved at the tree the judge would have read")
      (is (zero? @launched)))
    (let [out (run a-model-baseline {:status :undecidable :error "extraction could not read src/x.clj"})]
      (is (= :declaration-unreadable (:outcome out))
          "a listing nobody could read is not a record naming nothing declared")
      (is (str/includes? (:detail out) "src/x.clj"))
      (is (zero? @launched)))
    ;; The control: with every subject declared, the same call reaches the judge.
    (is (= :no-output (:outcome (run a-model-baseline (listing aggregate-row total-row)))))
    (is (= 1 @launched))
    (reset! seen nil)
    (is (= :no-output (:outcome (run a-baseline {:status :undecidable :error "never asked"}))))
    (is (nil? @seen) "an older record names no subject, so fukan is never asked")
    (testing "a project that declares no design gives its elements ids of its own, and nothing is
              resolved against a declaration that is not there"
      (is (= :no-output (:outcome (run a-model-baseline {:status :unmodelled}))))
      (is (= 1 @launched)))))

;; ── The amend stage ─────────────────────────────────────────────────────────

(defn- persisted-phase
  "`out`, a stage's finished ctx, folded into a run report as `phase` and read
   back off disk — what a reader of the finished run actually has, as against
   what the stage returned."
  [phase out]
  (let [path (str (fs/create-temp-file {:suffix ".json"}))]
    (try
      (-> (report/init {:run-id "r1" :cwd "/w" :base nil :started-at "t0"})
          (report/apply-event {:event :phase-started :iter 1 :phase phase :at "t1"} nil)
          (report/apply-event {:event :phase-finished :iter 1 :phase phase :at "t2" :ctx out} nil)
          (report/persist! path))
      (-> (json/parse-string (slurp path) true) :rounds first :phases first)
      (finally (fs/delete-if-exists path)))))

(defn- tree
  "A working-copy reading holding `entries`, path to content."
  [entries]
  {:identity (str (hash entries)) :entries entries})

(defn- transcript!
  "Stand in for an amender's transcript at `path`: `calls` as the tool_use blocks claude streams."
  [path calls]
  (spit path (str/join "\n" (for [c calls]
                              (json/generate-string
                               {:type "assistant" :message {:content [(assoc c :type "tool_use")]}})))))

(defn- with-amend
  "Run amend-stage with every seam stubbed. `writes` is called with the out-path
   and stands in for the answer the amender did (or did not) leave behind;
   `tree-before` and `tree-after` are the code tree's readings either side of it,
   and `calls` the tool calls its transcript shows. The ledger refuses every
   append under `append-throws?`, or the first `refusals` of them; each prompt an
   amender is launched over is added to `prompts`."
  [{:keys [prev writes tree-before tree-after calls append-throws? refusals prompts]
    :or {prev a-baseline tree-before (tree {}) refusals 0}} c]
  (let [state (atom tree-before)
        appended (atom nil)
        refused (atom 0)]
    (with-redefs [stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ _] prev)
                  stages/working-copy-state (fn [_] @state)
                  ws/append-entry! (fn [_ _ _ payload]
                                     (when (or append-throws? (< @refused refusals))
                                       (swap! refused inc)
                                       (throw (ex-info "schema said no" {})))
                                     (reset! appended payload)
                                     "/ws/entries/0002-baseline.edn")
                  agent/launch! (fn [{:keys [first-message out-file]}]
                                  (some-> prompts (swap! conj first-message))
                                  (when writes
                                    (writes (second (re-find #"Write EDN to:\n\n  (\S+)"
                                                             first-message))))
                                  (transcript! out-file calls)
                                  (reset! state (or tree-after @state))
                                  {:num-turns 3})]
      [(run record/amend-stage c) @appended])))

(deftest an-amendment-names-the-subjects-it-moved
  ;; What the next judge re-reads is what the amendment moved, and until now that took diffing the
  ;; answer file against the entry it repaired.
  (let [amended (-> a-baseline
                    (assoc-in [:load-bearing 0 :property] "the aggregate sums; the invoice reads it")
                    (update :health conj {:id "new-obs" :axis :design :observation "o"
                                          :evidence ["src/x.clj:1"]}))
        [out _] (with-amend {:writes (fn [p] (spit p (pr-str amended)))}
                            (ctx :findings [a-finding]))]
    (is (= {:changed ["c1"] :added ["new-obs"]} (:amend-delta out)))
    (is (= {:changed ["c1"] :added ["new-obs"]} (:delta (persisted-phase :amend out)))
        "and the report a reader has keeps it")))

(deftest a-dry-run-never-launches-an-amender
  (let [launched (atom false)]
    (with-redefs [agent/launch! (fn [_] (reset! launched true) {})]
      (let [out (run record/amend-stage (ctx :config {:cwd "/w" :run-id "r1" :dry-run? true}))]
        (is (= :dry-run (:status out)))
        (is (false? @launched))))))

(deftest an-amender-that-wrote-code-is-terminal
  ;; No stage of a record loop may touch the working copy. Whatever it wrote is
  ;; left in place — this halts for a human rather than tidying up after it.
  (let [[out _] (with-amend {:tree-after (tree {"src/x.clj" "new"})
                             :calls [{:name "Edit" :input {:file_path "/w/src/x.clj"}}]}
                            (ctx :findings [a-finding]))]
    (is (= :amend-touched-code (:status out)))
    (is (= :stop (:control out)))))

(deftest an-already-dirty-worktree-is-not-blamed-on-the-amender
  ;; A session worktree routinely carries a human's uncommitted work, and it is
  ;; still there afterwards. Comparing readings rather than a dirty flag is what
  ;; lets that pass while an actual edit does not.
  (let [[out appended] (with-amend {:tree-before (tree {"src/human.clj" "work"})
                                    :writes (fn [p] (spit p (pr-str a-baseline)))}
                                   (ctx :findings [a-finding]))]
    (is (not= :amend-touched-code (:status out)))
    (is (some? appended))))

(deftest an-amender-that-writes-on-top-of-a-dirty-tree-is-caught
  ;; The hole a dirty flag left, and the one that mattered: it only fired on a
  ;; clean-to-dirty transition, so on an already-dirty tree — which is most real
  ;; sessions — an amender could write code and nothing noticed.
  (let [[out appended] (with-amend {:tree-before (tree {"src/human.clj" "work"})
                                    :tree-after (tree {"src/human.clj" "work" "src/x.clj" "new"})
                                    :calls [{:name "Write" :input {:file_path "/w/src/x.clj"}}]
                                    :writes (fn [p] (spit p (pr-str a-baseline)))}
                                   (ctx :findings [a-finding]))]
    (is (= :amend-touched-code (:status out)))
    (is (nil? appended) "and the record it returned never reaches the ledger")))

(deftest a-tree-moved-by-someone-else-keeps-the-amendment
  ;; Forty runs lost a correct amendment this way: the amender read the code and
  ;; wrote its answer into the run dir while the person working in the session
  ;; worktree went on editing it, and the guard blamed the amender for their
  ;; edits. What moved is still reported, so nothing about the tree goes unsaid.
  (let [[out appended] (with-amend {:tree-before (tree {"src/human.clj" "work"})
                                    :tree-after (tree {"src/human.clj" "more work"})
                                    :calls [{:name "Bash" :input {:command "sed -n 1,40p src/order/invoice.clj"}}]
                                    :writes (fn [p] (spit p (pr-str a-baseline)))}
                                   (ctx :findings [a-finding]))]
    (is (nil? (:status out)) "the round goes on to be judged")
    (is (some? appended) "the amendment reaches the ledger")
    (is (= ["src/human.clj"] (get-in out [:amend-tree :moved])))
    (is (= [] (get-in out [:amend-tree :attributed])))))

(deftest a-trespass-stop-names-the-paths-and-the-unappended-answer
  ;; A stop that said only its status sent every reader to the amender's
  ;; transcript and the reviewed repo's op log to learn what moved, and left the
  ;; amendment — the thing they then re-typed by hand — named by nothing.
  (let [[out appended] (with-amend {:tree-after (tree {"src/x.clj" "new" "src/human.clj" "work"})
                                    :calls [{:name "Write" :input {:file_path "/w/src/x.clj"}}]
                                    :writes (fn [p] (spit p (pr-str a-baseline)))}
                                   (ctx :findings [a-finding]))
        phase (persisted-phase :amend out)]
    (is (= :amend-touched-code (:status out)))
    (is (nil? appended))
    (is (str/includes? (:amend-error out) "src/x.clj"))
    (is (str/includes? (:amend-error out) "also moved, not by it: src/human.clj"))
    (is (str/includes? (:amend-error out) "amend-round-1.edn"))
    (is (= ["src/x.clj"] (get-in phase [:tree :attributed])) "and the report keeps what tripped it")
    (is (= "record" (get-in phase [:tree :amendment :state]))
        "and says the answer it did not append was a readable amendment")))

;; A test that does not launch the amender through the real `agent/launch!` pins the options it
;; is handed: those, not the prompt, are what keep its shell from writing.
(deftest the-amender-launches-confined-to-its-answer-file
  (let [launched (atom nil)]
    (with-redefs [stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ _] a-baseline)
                  stages/working-copy-state (fn [_] (tree {}))
                  agent/launch! (fn [opts] (reset! launched opts) {:num-turns 1})]
      (run record/amend-stage (ctx :findings [a-finding])))
    (let [{:keys [tools allowed first-message]} @launched]
      (is (some? tools) "a confined launch names its tools")
      (is (some #(and (str/starts-with? % "Edit(/") (str/ends-with? % "amend-round-1.edn)")) allowed)
          "the one file it may write is this round's answer")
      (is (some #(str/includes? % "nido:review:amend:check") allowed)
          "and the check command it is told to run is one it can run")
      (is (str/includes? first-message "Read, Grep and Glob")
          "the prompt says how it reads, so its first shell read is not spent finding out"))))

(deftest a-stopped-answer-appends-as-its-amender-wrote-it
  ;; A round stopped :amend-touched-code set aside a correct amendment, and the caller re-typed
  ;; it by hand 39s later with its claims reworded — a record no round judged, whose weakenings
  ;; nothing measured. Appending the file itself is what makes a re-type the dearer path.
  (let [prev    (assoc a-baseline :seq 4)
        amended (update a-baseline :health (constantly []))
        answer  (str (fs/create-temp-file {:suffix ".edn"}))
        payload (atom nil)]
    (spit answer (pr-str {:record amended}))
    (with-redefs [ws/append-entry! (fn [_ _ _ p] (reset! payload p) "/ws/entries/0006-baseline.edn")
                  ws/entry-at-seq  (fn [_ _ n] (case n 6 (assoc (read-string @payload) :seq 6) prev))]
      (let [{:keys [record retreats err]}
            (record/append-stopped-answer! {:project :nido :ws-id "ws-1" :kind :baseline :prev prev
                                            :answer answer :run-id "baseline-loop-r" :iter 2})
            written (read-string @payload)]
        (is (nil? err))
        (is (= (:health amended) (:health written)) "the record goes in as it was written")
        (is (= 4 (get-in written [:supersedes :seq])) "citing the record the stopped round amended")
        (is (str/includes? (get-in written [:supersedes :why]) "round 2 of run baseline-loop-r"))
        (is (= 6 (:seq record)) "and comes back stamped, so the round it re-enters can name it")
        (is (some #(str/includes? (str (:detail %)) "invoice-resums") retreats)
            "a dropped observation is still counted as the weakening it is")))
    (with-redefs [ws/append-entry! (fn [& _] (reset! payload :written))]
      (spit answer "{:disputes []}")
      (reset! payload nil)
      (is (some? (:err (record/append-stopped-answer! {:project :nido :ws-id "ws-1" :kind :baseline
                                                       :prev prev :answer answer :run-id "r" :iter 1}))))
      (is (nil? @payload) "an answer with no record appends nothing"))))

(deftest an-amender-that-wrote-nothing-leaves-the-ledger-alone
  (let [[out appended] (with-amend {} (ctx :findings [a-finding]))]
    (is (= :amend-noop (:status out)))
    (is (nil? appended))))

(deftest a-leftover-answer-is-not-mistaken-for-this-rounds
  ;; "The file is there" is the whole test for whether the amender answered, so
  ;; a leftover from an earlier run under this run-id would be appended to the
  ;; ledger as a superseding baseline nobody wrote this round.
  (let [dir  (cstate/run-dir "r1")
        _    (fs/create-dirs dir)
        _    (spit (str (fs/path dir "amend-round-1.edn"))
                   (pr-str {:record (assoc a-baseline :area "someone else's answer")}))
        [out appended] (with-amend {} (ctx :findings [a-finding]))]
    (is (= :amend-noop (:status out)))
    (is (nil? appended) "the stale record never reached the ledger")))

(deftest an-unreadable-answer-is-its-own-outcome
  (let [[out appended] (with-amend {:writes (fn [p] (spit p "{:not edn"))}
                                   (ctx :findings [a-finding]))]
    (is (= :amend-unreadable (:status out)))
    (is (nil? appended))))

(deftest a-record-the-ledger-refuses-is-its-own-outcome
  (let [[out _] (with-amend {:append-throws? true
                             :writes (fn [p] (spit p (pr-str a-baseline)))}
                            (ctx :findings [a-finding]))]
    (is (= :amend-invalid (:status out)))
    (is (= "schema said no" (:amend-error out)))
    (is (= "schema said no" (:amend-error (persisted-phase :amend out)))
        "and the report keeps it, since nothing was appended to say it")))

(deftest a-refused-amendment-is-handed-back-and-the-repair-appended
  ;; Seen live, twice on one brian workstream and once on nido: an amender demoted
  ;; a stratum's reading without the health observation the write contract ties to
  ;; it, and the round lost the whole amendment over one missing field.
  (let [prompts  (atom [])
        repaired (assoc a-baseline :area "repaired")
        [out appended] (with-amend {:refusals 1 :prompts prompts
                                    :writes (fn [p]
                                              (spit p (pr-str {:record (if (= 1 (count @prompts))
                                                                         a-baseline
                                                                         repaired)})))}
                                   (ctx :findings [a-finding]))]
    (is (nil? (:status out)) "the round goes on to be judged")
    (is (= 2 (count @prompts)))
    (is (str/includes? (second @prompts) "WHY IT WAS REFUSED:\n\n  schema said no"))
    (is (str/includes? (second @prompts) "THE RECORD THE LEDGER REFUSED"))
    (is (= "repaired" (:area (read-string appended))) "what the repair returned is what is appended")
    (is (= ["schema said no"] (:amend-refusals out)))
    (is (= ["schema said no"] (:refusals (persisted-phase :amend out)))
        "and the report says it took a repair")))

(deftest an-amendment-taken-first-time-reports-no-refusals
  (let [[out _] (with-amend {:writes (fn [p] (spit p (pr-str {:record a-baseline})))}
                            (ctx :findings [a-finding]))]
    (is (nil? (:status out)))
    (is (not (contains? (persisted-phase :amend out) :refusals)))))

(deftest a-refusal-the-amender-cannot-repair-ends-the-round
  (let [prompts (atom [])
        [out appended] (with-amend {:append-throws? true :prompts prompts
                                    :writes (fn [p] (spit p (pr-str a-baseline)))}
                                   (ctx :findings [a-finding]))]
    (is (= :amend-invalid (:status out)))
    (is (= 3 (count @prompts)) "the amendment, then two repairs")
    (is (= 2 (count (:amend-refusals out))) "the two handed back, not the one that ended the round")
    (is (nil? appended))))

(deftest a-repair-answered-with-nothing-ends-on-the-refusal
  (let [prompts (atom [])
        [out appended] (with-amend {:append-throws? true :prompts prompts
                                    :writes (fn [p] (when (= 1 (count @prompts))
                                                      (spit p (pr-str a-baseline))))}
                                   (ctx :findings [a-finding]))]
    (is (= :amend-invalid (:status out)))
    (is (= "schema said no" (:amend-error out)))
    (is (= 2 (count @prompts)))
    (is (nil? appended))))

(deftest a-repair-that-writes-code-is-caught
  (let [state    (atom (tree {}))
        launches (atom 0)
        out (with-redefs [stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                          ws/latest-entry (fn [_ _ _] a-baseline)
                          stages/working-copy-state (fn [_] @state)
                          ws/append-entry! (fn [& _] (throw (ex-info "schema said no" {})))
                          agent/launch! (fn [{:keys [first-message out-file]}]
                                          (if (= 2 (swap! launches inc))
                                            (do (reset! state (tree {"src/x.clj" "new"}))
                                                (transcript! out-file [{:name "Edit" :input {:file_path "/w/src/x.clj"}}]))
                                            (transcript! out-file []))
                                          (spit (second (re-find #"Write EDN to:\n\n  (\S+)" first-message))
                                                (pr-str a-baseline))
                                          {:num-turns 1})]
              (run record/amend-stage (ctx :findings [a-finding])))]
    (is (= :amend-touched-code (:status out)))
    (is (= ["src/x.clj"] (get-in out [:amend-tree :attributed])))))

(deftest the-amender-is-told-which-edits-travel-together
  (let [p (record/amend-prompt {:baseline (assoc a-baseline :strata []) :findings [a-finding]
                                :out-path "/x"})]
    (is (str/includes? p "EDITS THAT TRAVEL TOGETHER"))
    (is (str/includes? p "named, under :about, by a :health observation")))
  (testing "and so is the repair of a refused one"
    (is (str/includes? (record/refusal-prompt {:kind :baseline :record (assoc a-baseline :strata [])
                                               :refusal "no" :out-path "/x"})
                       "named, under :about, by a :health observation")))
  (testing "a baseline from before strata is not told a rule it cannot break"
    (is (not (str/includes? (record/amend-prompt {:baseline a-baseline :findings [a-finding]
                                                  :out-path "/x"})
                            "EDITS THAT TRAVEL TOGETHER")))))

(deftest a-corrected-record-is-appended-and-the-loop-continues
  (let [corrected (assoc-in a-baseline [:load-bearing 0 :property]
                            "the invoice renderer sums independently of the aggregate")
        [out appended] (with-amend {:writes (fn [p] (spit p (pr-str corrected)))}
                                   (ctx :findings [a-finding]))]
    (is (nil? (:status out)) "a repair is not terminal")
    (is (= corrected (read-string appended)))
    (is (= [] (:retreats out)) "correcting a property in place gives nothing up")
    (is (= 1 (count (:history out))))))

(deftest a-record-amended-below-its-own-threshold-is-a-retreat-not-a-convergence
  ;; The failure the whole stage exists to catch: strip the record and the next
  ;; judge round would refuse to run, which reads as success from every angle
  ;; except this one.
  (let [gutted (assoc a-baseline :load-bearing [] :health [])
        [out _] (with-amend {:writes (fn [p] (spit p (pr-str gutted)))}
                            (ctx :findings [a-finding]))]
    (is (= :retreated (:status out)))
    (is (= :stop (:control out)))
    (is (seq (:retreats out)) "and it says what was given up")))

(deftest a-weakening-that-stays-above-the-threshold-is-reported-and-continues
  ;; Reported, never forbidden: dropping a property the code genuinely refutes
  ;; IS the honest repair. What must not happen is one passing silently.
  (let [thinner (update a-baseline :health empty)
        [out _] (with-amend {:writes (fn [p] (spit p (pr-str thinner)))}
                            (ctx :findings [a-finding]))]
    (is (nil? (:status out)))
    (is (contains? (set (map :what (:retreats out))) :health-dropped))
    (is (= [{:what :health-dropped
             :detail "observation invoice-resums is no longer recorded"}]
           (:retreats (first (:history out)))))))

;; ── Driven by the engine ────────────────────────────────────────────────────

(deftest the-pipeline-converges-when-the-code-stops-refuting-the-record
  (let [round (atom 0)]
    (with-redefs [record/baseline-review!
                  (fn [_] (if (= 1 (swap! round inc))
                            {:format :baseline-review :verdict :falsified
                             :findings [a-finding]}
                            {:format :baseline-review :verdict :sufficient :reason "ok"}))
                  record/append! (fn [_ _] nil)
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ _] a-baseline)
                  stages/working-copy-state (fn [_] "")
                  ws/append-entry! (fn [_ _ _ _] "/ws/entries/0002-baseline.edn")
                  agent/launch! (fn [{:keys [first-message]}]
                                  (spit (second (re-find #"Write EDN to:\n\n  (\S+)" first-message))
                                        (pr-str a-baseline))
                                  {:num-turns 3})]
      (let [out (rloop/run-loop {:run-id "r-conv" :cwd "/w"
                                 :pipeline record/baseline-pipeline
                                 :finding-key record/baseline-finding-key})]
        (is (= :sufficient (:status out)))
        (is (= 3 (:iter out)) "the amendment's clean reading is read a second time before it stands")))))

(deftest a-nested-loop-launched-on-a-named-baseline-still-follows-its-amendments
  ;; This is the shape a re-survey runs in, and it could not converge. Given an
  ;; explicit :baseline, every round after the first fell back to it — the
  ;; amendment was never judged — so the run re-raised its findings and stalled
  ;; at round two. The design loop treats any non-:sufficient nested outcome as
  ;; terminal, so :resurvey could never once complete.
  ;;
  ;; The CLI hid it: it passes no :baseline, so the same fallthrough landed on
  ;; "the latest entry", which IS the amendment. Only the nested caller broke.
  (let [start  (assoc a-baseline :area "the baseline the design cites")
        fixed  (assoc a-baseline :area "corrected")
        judged (atom [])
        round  (atom 0)]
    (with-redefs [record/baseline-review!
                  (fn [{:keys [baseline]}]
                    (swap! judged conj (:area baseline))
                    (if (= 1 (swap! round inc))
                      {:format :baseline-review :verdict :falsified
                       :findings [a-finding]}
                      {:format :baseline-review :verdict :sufficient :reason "ok"}))
                  record/append! (fn [_ _] nil)
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ _] (throw (ex-info "must not be consulted" {})))
                  ws/entry-at-seq (fn [_ _ n] (assoc fixed :seq n :at "t"))
                  stages/working-copy-state (fn [_] "")
                  ws/append-entry! (fn [_ _ _ _] "/ws/entries/0007-baseline.edn")
                  agent/launch! (fn [{:keys [first-message]}]
                                  (spit (second (re-find #"Write EDN to:\n\n  (\S+)" first-message))
                                        (pr-str fixed))
                                  {:num-turns 3})]
      (let [out (rloop/run-loop {:run-id "r-nested" :cwd "/w"
                                 :baseline start
                                 :pipeline record/baseline-pipeline
                                 :finding-key record/baseline-finding-key})]
        (is (= ["the baseline the design cites" "corrected" "corrected"] @judged)
            "the amendment judged, and read a second time before it ends the run")
        (is (= :sufficient (:status out)))
        (testing "and the corrected baseline comes back carrying the seq a design must cite"
          (is (= 7 (:seq (:under-repair (:carry out))))))))))

(deftest an-amender-that-changes-nothing-stalls-instead-of-spinning
  ;; Uncapped. The only thing that ends this is the injected identity seeing the
  ;; same finding twice — which is what the diff review's key could never do
  ;; here, every record finding colliding on [nil nil nil].
  (with-redefs [record/baseline-review! (fn [_] {:format :baseline-review
                                                 :verdict :falsified
                                                 :findings [a-finding]})
                record/append! (fn [_ _] nil)
                stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                ws/latest-entry (fn [_ _ _] a-baseline)
                stages/working-copy-state (fn [_] "")
                ws/append-entry! (fn [_ _ _ _] "/ws/entries/0002-baseline.edn")
                agent/launch! (fn [{:keys [first-message]}]
                                (spit (second (re-find #"Write EDN to:\n\n  (\S+)" first-message))
                                      (pr-str a-baseline))
                                {:num-turns 3})]
    (let [out (rloop/run-loop {:run-id "r-stall" :cwd "/w"
                               :pipeline record/baseline-pipeline
                               :finding-key record/baseline-finding-key})]
      (is (= :no-progress (:status out))))))

;; ── A narrowing claim is not a stall ────────────────────────────────────────

(defn- refuting [id evidence]
  {:claim-id id :cites ["the claim"] :claim "a counterexample" :evidence evidence})

(defn- after-an-amend [& {:keys [amended? now running] :or {amended? true}}]
  (record/baseline-round-changed?
   (cond-> {:iter 2 :findings [(refuting "shape" now)]}
     running (assoc :refuted-running running))
   [{:iter 1 :amended? amended? :findings [(refuting "shape" ["src/rec.clj:1512"])]}]))

(deftest a-new-counterexample-to-an-amended-claim-is-movement
  ;; Keyed on the claim id, the second counterexample looks exactly like the first. Watched over
  ;; twenty runs: an amend fixed R1's counterexample, R2 refuted another sentence at code sharing
  ;; no line with R1's, and the run ended :no-progress at round two of the four unfixable allows.
  (is (true? (after-an-amend :now ["src/rec.clj:1153"])))
  (testing "the same code refuting it again is the old defect surviving its rewrite"
    (is (false? (after-an-amend :now ["src/rec.clj:1512" "src/rec.clj:1153"]))))
  (testing "a round that amended nothing moved nothing, whatever the judge cited"
    (is (false? (after-an-amend :amended? false :now ["src/rec.clj:1153"])))))

(deftest a-claim-no-rewording-settles-is-let-stop
  ;; Watched the other way: a claim refuted five times by five counterexamples, which nothing a
  ;; derivation needed, and whose right repair was removing it. Its amender is offered that at
  ;; two refutations running; refuted again after the offer, the veto has nothing left to protect.
  (is (true? (after-an-amend :now ["src/rec.clj:1153"] :running {"shape" 2}))
      "the round that first offers the withdrawal still runs, or the offer is never made")
  (is (false? (after-an-amend :now ["src/rec.clj:1153"] :running {"shape" 3}))))

(deftest an-objection-counts-against-the-defect-it-answered
  ;; Keyed on the claim alone, an objection to a false finding was inherited by a later, correct
  ;; one about the same claim — which the amender accepted and fixed — so two unrelated objections
  ;; would escalate a defect nobody disputed.
  (let [history [{:disputes [{:key [:claim-id "shape"] :sites ["src/rec.clj:1512"]
                              :claim "c" :because "b"}]}]]
    (is (= 1 (record/disputed-n history record/baseline-finding-base-key
                                (refuting "shape" ["src/rec.clj:1512" "src/rec.clj:9"])))
        "restated at the code it was objected to, it is the finding the objection answered")
    (is (= 0 (record/disputed-n history record/baseline-finding-base-key
                                (refuting "shape" ["src/rec.clj:1153"]))))
    (is (= 1 (record/disputed-n [{:disputes [{:key [:claim-id "shape"] :claim "c" :because "b"}]}]
                                record/baseline-finding-base-key (refuting "shape" ["src/rec.clj:1153"])))
        "an objection that says nothing of its site cannot be told apart, and still counts")))

(deftest the-loop-keeps-going-while-a-claim-narrows
  (let [round (atom 0)
        run!  (fn [changed?]
                (reset! round 0)
                (with-redefs [record/baseline-review!
                              (fn [_] (case (swap! round inc)
                                        1 {:format :baseline-review :verdict :falsified
                                           :findings [(refuting "c1" ["src/order/invoice.clj:88"])]}
                                        2 {:format :baseline-review :verdict :falsified
                                           :findings [(refuting "c1" ["src/order/csv.clj:4"])]}
                                        {:format :baseline-review :verdict :sufficient :reason "ok"}))
                              record/append! (fn [_ _] nil)
                              stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                              ws/latest-entry (fn [_ _ _] a-baseline)
                              stages/working-copy-state (fn [_] "")
                              ws/append-entry! (fn [_ _ _ _] "/ws/entries/0002-baseline.edn")
                              agent/launch! (fn [{:keys [first-message]}]
                                              (spit (second (re-find #"Write EDN to:\n\n  (\S+)" first-message))
                                                    (pr-str (assoc a-baseline :area (str "amended " @round))))
                                              {:num-turns 3})]
                  (rloop/run-loop (cond-> {:run-id "r-narrow" :cwd "/w"
                                           :pipeline record/baseline-pipeline
                                           :finding-key record/baseline-finding-key}
                                    changed? (assoc :changed? changed?)))))]
    (is (= :sufficient (:status (run! record/baseline-round-changed?)))
        "the second counterexample was repaired in round two and the third reading found none")
    (is (= :no-progress (:status (run! nil)))
        "the engine alone cannot tell it from a stall — the pipeline has to say")))

;; ── The prompt ──────────────────────────────────────────────────────────────

(deftest the-amend-prompt-names-the-job-and-the-cheap-wrong-answer
  (let [p (record/amend-prompt {:baseline a-baseline :findings [a-finding]
                                :out-path "/run/amend-round-1.edn"})]
    (testing "what it is for"
      (is (str/includes? p "make the baseline TRUE"))
      (is (str/includes? p "not the same job as making the")))
    (testing "the evidence the judge cited is in front of the amender"
      (is (str/includes? p "src/order/invoice.clj:88")))
    (testing "the weakening moves are named, with their consequence"
      (is (str/includes? p ":invisibly-incomplete?"))
      (is (str/includes? p "measured and reported to a human")))
    (testing "and it may not write code or append the record itself"
      (is (str/includes? p "Do NOT edit any source file"))
      (is (str/includes? p "Do not append it yourself")))))

(deftest the-amender-is-given-one-rule-for-an-elements-id
  ;; Told both to keep a module's id and to use canvas identities, an amender keeping the old id
  ;; stops the next round on an undeclared subject, and one renaming it reads to retreat as a
  ;; module lost.
  (testing "a project that declares its design names elements by canvas identity"
    (let [p (record/amend-prompt {:baseline a-baseline :findings [a-finding]
                                  :out-path "/x" :declared? true})]
      (is (str/includes? p "an element's id is its canvas identity"))
      (is (str/includes? p "never the id an\nolder record gave it"))
      (is (not (str/includes? p "keeps the id the record already")))))
  (testing "a project that declares none keeps the ids its record gave"
    (let [p (record/amend-prompt {:baseline a-baseline :findings [a-finding] :out-path "/x"})]
      (is (str/includes? p "an element keeps the id the record already\ngave it"))
      (is (not (str/includes? p "canvas identity"))))))

;; ── The appeal channel ──────────────────────────────────────────────────────

(deftest an-objection-is-made-by-number-so-nothing-has-to-match-text-to-text
  (let [answer (record/parse-amend-answer
                {:disputes [{:finding 1 :because "the renderer calls the aggregate"
                             :evidence ["src/order/invoice.clj:90"]}]}
                [a-finding] record/baseline-finding-base-key)]
    (is (nil? (:record answer)))
    (is (= [{:key (record/baseline-finding-base-key a-finding)
             :sites ["src/order/invoice.clj:88"]
             :claim (:claim a-finding)
             :because "the renderer calls the aggregate"
             :evidence ["src/order/invoice.clj:90"]}]
           (:disputes answer)))))

(deftest an-objection-that-cannot-be-answered-is-dropped
  (testing "out of range"
    (is (= [] (:disputes (record/parse-amend-answer
                          {:disputes [{:finding 7 :because "no"}]} [a-finding]
                          record/baseline-finding-base-key)))))
  (testing "no reason given — an objection with no reason is not an appeal"
    (is (= [] (:disputes (record/parse-amend-answer
                          {:disputes [{:finding 1 :because "  "}]} [a-finding]
                          record/baseline-finding-base-key))))
    (is (= [] (:disputes (record/parse-amend-answer
                          {:disputes [{:finding 1}]} [a-finding]
                          record/baseline-finding-base-key))))))

(deftest a-bare-record-is-still-a-valid-answer
  ;; The shape from before there was anything to say back. Records already
  ;; written must not stop being readable because the answer grew a wrapper.
  (let [answer (record/parse-amend-answer a-baseline [a-finding]
                                          record/baseline-finding-base-key)]
    (is (= a-baseline (:record answer)))
    (is (= [] (:disputes answer)))))

(deftest an-objection-round-trip-is-progress-not-a-stall
  ;; A dispute changes no record, so a judge that answers by RESTATING produces
  ;; a set identical to last round's. Without the dispute count in the identity
  ;; that reads as a stalled loop and ends before the channel completes even one
  ;; exchange.
  (let [f0 (assoc a-finding :disputed-n 0)
        f1 (assoc a-finding :disputed-n 1)]
    (is (not= (record/baseline-finding-key f0) (record/baseline-finding-key f1)))))

(deftest an-amender-that-only-objects-leaves-the-ledger-alone-and-keeps-going
  (let [[out appended]
        (with-amend {:writes (fn [p] (spit p (pr-str {:disputes [{:finding 1 :because "wrong"}]})))}
                    (ctx :findings [a-finding]))]
    (is (nil? (:status out)) "objecting is a complete answer, not an empty one")
    (is (nil? appended) "and it does not touch the record")
    (is (= 1 (count (:disputes out))))
    (is (= [] (:retreats out)))))

(deftest objections-and-an-amendment-can-arrive-together
  (let [corrected (assoc a-baseline :read ["src/order/aggregate.clj" "src/order/invoice.clj"])
        [out appended]
        (with-amend {:writes (fn [p] (spit p (pr-str {:record corrected
                                                      :disputes [{:finding 1 :because "wrong"}]})))}
                    (ctx :findings [a-finding]))]
    (is (nil? (:status out)))
    (is (= corrected (read-string appended)))
    (is (= 1 (count (:disputes out))))))

(deftest a-finding-restated-after-two-objections-goes-to-a-human
  ;; Neither side can settle it: the judge cannot be overruled by the pass it is
  ;; judging, and that pass may not amend a record it believes is already true.
  (let [disputed (fn [n] (vec (repeat n {:disputes [{:key (record/baseline-finding-base-key a-finding)
                                                     :claim "c" :because "b"}]})))]
    (with-redefs [record/baseline-review! (fn [_] {:format :baseline-review
                                                   :verdict :falsified
                                                   :findings [a-finding]})
                  record/append! (fn [_ _] nil)]
      (testing "once objected to, the judge restating it is the answer we asked for"
        (let [out (run record/judge-stage (ctx :history (disputed 1)))]
          (is (nil? (:status out)))
          (is (= 1 (:disputed-n (first (:findings out)))))))
      (testing "twice objected to and stated again, it is a human's call"
        (let [out (run record/judge-stage (ctx :history (disputed 2)))]
          (is (= :disputed (:status out)))
          (is (= :escalate (:control out))))))))

(deftest a-withdrawn-finding-never-reaches-the-escalation
  (with-redefs [record/baseline-review! (fn [_] {:format :baseline-review
                                                 :verdict :sufficient :reason "ok"})
                record/append! (fn [_ _] nil)]
    (let [out (run record/judge-stage
                   (ctx :history (vec (repeat 2 {:disputes [{:key (record/baseline-finding-base-key a-finding)
                                                             :claim "c" :because "b"}]}))))]
      (is (= :sufficient (:status out)) "withdrawing is the other honest answer"))))

(deftest standing-objections-reach-the-next-judge
  (let [seen (atom nil)]
    (with-redefs [record/baseline-review! (fn [opts] (reset! seen opts)
                                            {:format :baseline-review :verdict :sufficient})
                  record/append! (fn [_ _] nil)]
      (run record/judge-stage
           (ctx :history [{:disputes [{:key [:evidence ["x"]] :claim "c" :because "b"}]}]))
      (is (= 1 (count (:disputes @seen)))))))

(deftest the-judge-prompt-tells-the-judge-it-may-be-the-one-that-is-wrong
  (let [p (record/disputes-block [{:claim "the renderer sums independently"
                                   :because "it delegates to the aggregate"
                                   :evidence ["src/order/invoice.clj:90"]}])]
    (is (str/includes? p "may itself be mistaken")
        "the amender is not an authority — the judge is told to go look")
    (is (str/includes? p "withdraw the finding"))
    (is (str/includes? p "report it again with evidence"))
    (is (str/includes? p "src/order/invoice.clj:90"))))

(deftest no-objections-puts-nothing-in-the-prompt
  (is (nil? (record/disputes-block []))))

(def ^:private a-gap
  {:blocks :decomposable
   :cites ["the aggregate is the only summing path"]
   :claim "the aggregate is the only summing path"
   :needs "what the invoice renderer hides, so the two can be told apart"
   :evidence ["src/order/invoice.clj:88"]})

(deftest a-gap-is-not-shown-to-the-amender-as-a-refutation
  ;; :falsified and :insufficient are opposite repairs — one says a claim is
  ;; wrong, the other says every claim is right and something is missing — and
  ;; the two fields that say which, :blocks and :needs, were on the record and
  ;; printed nowhere. An amender shown a gap under the word "refutes" corrects a
  ;; claim that was already true.
  (let [p (record/amend-prompt {:baseline a-baseline :findings [a-gap]
                                :out-path "/run/amend-round-1.edn"})]
    (testing "the round is described as what it was"
      (is (str/includes? p "found the baseline TRUE"))
      (is (str/includes? p "make the baseline SAY ENOUGH"))
      (is (not (str/includes? p "refuted part of it"))))
    (testing "the derivation it blocks and what it needs are both in front of it"
      (is (str/includes? p "blocks:  decomposable"))
      (is (str/includes? p "what the invoice renderer hides")))
    (testing "the bound is stated, because completeness has no fixed point"
      (is (str/includes? p "Add what the derivation needs and stop")))
    (testing "and the objection route is the one that fits a gap"
      (is (str/includes? p "IF A DERIVATION CAN BE MADE ALREADY")))))

(deftest a-refutation-round-is-worded-exactly-as-it-was
  ;; The falsified wording is the one that converged. The gap branch is additive.
  (let [p (record/amend-prompt {:baseline a-baseline :findings [a-finding]
                                :out-path "/x"})]
    (is (str/includes? p "refuted part of it"))
    (is (str/includes? p "CHANGE ONLY WHAT WAS REFUTED"))
    (is (not (str/includes? p "blocks:")))))

(deftest the-amend-prompt-offers-the-objection-route-and-names-its-worst-abuse
  (let [p (record/amend-prompt {:baseline a-baseline :findings [a-finding]
                                :out-path "/run/a.edn"})]
    (is (str/includes? p "1. refutes:") "findings are numbered, and answered by number")
    (is (str/includes? p "IF A FINDING IS WRONG ABOUT THE CODE, SAY SO INSTEAD"))
    (is (str/includes? p "You do not settle it"))
    (is (str/includes? p "makes the record false AND ends the argument"))))

;; ── What the reader adds, the writer may not carry ──────────────────────────

(deftest a-record-read-from-the-ledger-can-be-written-back
  ;; The defect this catches took every amendment in both pipelines down, and no
  ;; fixture here had ever seen it: latest-entry stamps :seq and :at on the way
  ;; out, the write schema is closed against both, and the prompt shows the
  ;; amender exactly what it read. A faithful amender echoes them and the ledger
  ;; refuses the result.
  (let [stamped (assoc a-baseline :seq 11 :at "2026-08-24T17:27:18Z")]
    (testing "the amender is shown the record as it will be WRITTEN"
      (let [p (record/amend-prompt {:baseline stamped :findings [a-finding]
                                    :out-path "/run/a.edn"})]
        (is (not (str/includes? p ":seq 11")))
        (is (not (str/includes? p ":at \"2026-08-24")))))
    (testing "and an echoed stamp still does not reach the ledger"
      (let [[out appended]
            (with-amend {:prev stamped
                         :writes (fn [p] (spit p (pr-str {:record stamped})))}
                        (ctx :findings [a-finding]))]
        (is (nil? (:status out)) "not :amend-invalid")
        ;; Everything the amender wrote, minus the stamp — plus the correction
        ;; citation the round adds naming what it repaired, which is the one
        ;; field an amendment gains on the way to the ledger.
        (is (= a-baseline (dissoc (read-string appended) :supersedes)))
        (is (nil? (:seq (read-string appended))))
        (is (nil? (:at (read-string appended))))))))

(deftest unstamping-leaves-a-citation-alone
  ;; A design's :baseline :seq is an author's citation, not the reader's stamp,
  ;; and they are the same key one level apart.
  (is (= {:format :design :baseline {:seq 8 :relation :within}}
         (ws/unstamp {:format :design :seq 10 :at "t"
                      :baseline {:seq 8 :relation :within}}))))

;; ── Which record a run is repairing ─────────────────────────────────────────

(deftest a-loop-repairs-the-record-it-was-pointed-at-not-the-newest
  ;; The failure this catches ran for five rounds and could not have converged:
  ;; a workstream held two baselines of DIFFERENT areas — a narrow follow-up
  ;; written beside the broad one — and the loop re-read "the latest" every
  ;; round, so it repaired the follow-up while the design went on citing the
  ;; other. No amount of repair to one answers a design citing the other.
  (let [cited {:format :baseline :area "the one the design cites"
               :load-bearing [{:id "c2" :property "p" :evidence ["src/cited.clj:1"]}]
               :bounded-by "b" :shape "s" :read ["src/cited.clj"]}
        newest (assoc cited :area "a narrow follow-up, appended later")
        seen (atom [])]
    (with-redefs [record/baseline-review!
                  (fn [{:keys [baseline]}]
                    (swap! seen conj (:area baseline))
                    {:format :baseline-review :verdict :sufficient :reason "ok"})
                  record/append! (fn [_ _] nil)
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ _] newest)]
      (run record/judge-stage (ctx :config {:cwd "/w" :run-id "r1" :baseline cited}))
      (is (= ["the one the design cites"] @seen)))))

(deftest a-loop-follows-its-own-amendment-rather-than-re-reading-the-ledger
  ;; Same hazard one step later: another session appending a baseline mid-run
  ;; must not hijack the repair.
  (let [start {:format :baseline :area "round one" :bounded-by "b" :shape "s"
               :load-bearing [{:id "c3" :property "p" :evidence ["src/a.clj:1"]}] :read ["src/a.clj"]}
        mine  (assoc start :area "what I amended it to")
        other (assoc start :area "what someone else appended")
        seen  (atom [])]
    (with-redefs [record/baseline-review!
                  (fn [{:keys [baseline]}] (swap! seen conj (:area baseline))
                    {:format :baseline-review :verdict :sufficient :reason "ok"})
                  record/append! (fn [_ _] nil)
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ _] other)]
      (run record/judge-stage (ctx :carry {:under-repair mine}))
      (is (= ["what I amended it to"] @seen)))))

(deftest an-amendment-becomes-the-record-the-next-round-repairs
  (let [corrected (assoc a-baseline :area "corrected")
        [out _] (with-amend {:writes (fn [p] (spit p (pr-str {:record corrected})))}
                            (ctx :findings [a-finding]))]
    ;; In :carry, because that is the only part of the ctx the engine hands to
    ;; the next round.
    (is (= corrected (:under-repair (:carry out))))))

(deftest the-record-carried-on-is-the-one-the-ledger-stamped
  ;; The amender cannot write a :seq and must not invent one, but everything
  ;; downstream identifies the record by exactly that number — the verdict is
  ;; labelled with it and a design cites its baseline by it. Carrying the record
  ;; as written leaves all of that pointing at nothing.
  (let [corrected (assoc a-baseline :area "corrected")]
    (with-redefs [ws/entry-at-seq (fn [_ _ n] (assoc corrected :seq n :at "t"))]
      (let [[out _] (with-amend {:writes (fn [p] (spit p (pr-str {:record corrected})))}
                                (ctx :findings [a-finding]))]
        (is (= 2 (:seq (:under-repair (:carry out))))
            "the seq append-entry! answered with, read back off the path")))))

(deftest a-stamp-that-cannot-be-read-back-does-not-cost-the-round
  ;; Degrading beats throwing: the amendment is already committed to the ledger
  ;; by the time this runs, so a failed read-back must not turn a good round
  ;; into a terminal failure.
  (let [corrected (assoc a-baseline :area "corrected")]
    (with-redefs [ws/entry-at-seq (fn [_ _ _] nil)]
      (let [[out _] (with-amend {:writes (fn [p] (spit p (pr-str {:record corrected})))}
                                (ctx :findings [a-finding]))]
        (is (nil? (:status out)))
        (is (= corrected (:under-repair (:carry out))))))))

(deftest the-amender-is-told-what-a-reading-may-say
  ;; It is the pass that WRITES readings and was the only one never shown the
  ;; vocabulary. Watched live: it invented a :tarpit/control verdict and a lens
  ;; outside the registry, the ledger refused the record, and a nineteen-reading
  ;; amendment was lost whole.
  (let [p (record/amend-prompt {:baseline a-baseline :findings [a-finding] :out-path "/x"})]
    (is (str/includes? p "THE PERSPECTIVES THIS BASELINE IS READ THROUGH"))
    (is (str/includes? p "imposed") "the verdicts themselves, not just the lens names")
    (is (str/includes? p "the whole record\nis lost with it")
        "and what it costs to guess"))
  (testing "a baseline that cannot carry readings is not handed a vocabulary it cannot use"
    (let [p (record/amend-prompt {:baseline (dissoc a-baseline :modules)
                                  :findings [a-finding] :out-path "/x"})]
      (is (not (str/includes? p "THE PERSPECTIVES THIS BASELINE IS READ THROUGH"))))))

(deftest the-vocabulary-says-which-subject-each-lens-reads
  ;; Watched live: an amender attached a claim lens to a module. Only
  ;; :ousterhout/depth reads a module, the registry has always known it, and the
  ;; prompt never said so — and the cost of guessing is not a bad field but an
  ;; invalid dispatch, which refuses the whole record.
  (let [p (record/amend-prompt {:baseline a-baseline :findings [a-finding] :out-path "/x"})]
    (is (str/includes? p "a MODULE (never a claim)"))
    (is (str/includes? p "a LOAD-BEARING CLAIM (never a module)"))))

(deftest a-refused-record-says-which-field-was-wrong
  ;; "Invalid event report" names nothing an amender could fix, and the explain
  ;; data was on the exception all along.
  (let [[out _] (with-amend {:append-throws? true
                             :writes (fn [p] (spit p (pr-str a-baseline)))}
                            (ctx :findings [a-finding]))]
    (is (= :amend-invalid (:status out)))
    (is (str/includes? (str (:amend-error out)) "schema said no"))))

(deftest the-amender-is-told-to-leave-unchallenged-claims-alone
  ;; The arms race this ends, watched across five rounds: each amendment wrote
  ;; sharper claims to satisfy the last finding, and a sharper claim is a bigger
  ;; target. Findings went 7, 4, 1, 1, 3 — never to zero, because every round
  ;; created new refutable surface nobody had asked for.
  (let [p (record/amend-prompt {:baseline a-baseline :findings [a-finding] :out-path "/x"})]
    (is (str/includes? p "CHANGE ONLY WHAT WAS REFUTED"))
    (is (str/includes? p "must come\nback unchanged"))
    (is (str/includes? p "A sharper claim is a bigger target")
        "the reason, because a rule without one gets reasoned around")
    (is (str/includes? p "stays true as the code moves")
        "and what to prefer when a claim does have to change")))

;; ── What the ledger already settled ─────────────────────────────────────────

(def ^:private settling-ledger
  "A ledger on which two reviews running, both reading tree-a, confirmed c1 of `a-baseline` — the
   two readings that settle it."
  {:reviews     [{:format :baseline-review :seq 0 :baseline-seq 1 :verdict :sufficient
                  :reason "ok" :confirmed ["c1"] :checked-at {"c1" ["src/order/aggregate.clj:12"]}
                  :code-identity "tree-a"}
                 {:format :baseline-review :seq 2 :baseline-seq 1 :verdict :sufficient
                  :reason "ok" :confirmed ["c1"] :checked-at {"c1" ["src/order/aggregate.clj:12"]}
                  :code-identity "tree-a"}]
   :baselines   [(assoc a-baseline :seq 1)]
   :retractions []})

(defn- judged-at
  "Run the judge stage with the tree reading as `tree` and `settling-ledger` on
   the workstream. Returns [what baseline-review! was handed, the stage's ctx]."
  [tree]
  (let [seen (atom nil)]
    (with-redefs [record/baseline-review! (fn [opts] (reset! seen opts)
                                            {:format :baseline-review :verdict :sufficient :reason "ok"})
                  record/append! (fn [_ _] nil)
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ _] a-baseline)
                  settled/code-identity (fn [_] tree)
                  settled/ledgers (fn [_ _ _] [(assoc settling-ledger :ws-id "ws-1")])]
      (let [out (run record/judge-stage (ctx))]
        [@seen out]))))

(deftest the-judge-is-handed-what-the-ledger-settled-at-this-tree
  ;; Not what earlier rounds of this run said: that channel keyed on the id alone,
  ;; and told the judge a confirmation stood for this same record after an
  ;; amendment had changed it. The ledger reading keys on content and tree, and
  ;; still covers the run's own earlier rounds, whose tree does not move.
  (let [[seen out] (judged-at "tree-a")]
    (is (= {"c1" {:ws-id "ws-1" :seq 2}} (:settled seen)))
    (is (= "tree-a" (:code-identity seen)) "with the reading the settling was made at")
    (is (= {"c1" {:ws-id "ws-1" :seq 2}} (:settled out)) "and the report is told what was not asked, and where")))

(defn- judged-with
  "The judge stage's ctx over `a-baseline` when its judge returns `review`, starting from `c`."
  [review c]
  (with-redefs [record/baseline-review! (fn [_] review)
                record/append! (fn [_ _] nil)
                stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                ws/latest-entry (fn [_ _ _] a-baseline)
                settled/code-identity (fn [_] "tree-a")
                settled/ledgers (fn [_ _ _] [(assoc settling-ledger :ws-id "ws-1")])]
    (run record/judge-stage c)))

(deftest a-sufficient-verdict-over-unruled-checks-is-asked-again-then-refused
  ;; A sufficient verdict ended the run while checks had no ruling, and nothing recorded them. Asked
  ;; again first — what the round did confirm is settled by then, so the next judge is handed only
  ;; what was left — and the run ends :unruled, not :sufficient, if they are still unruled.
  (let [review {:format :baseline-review :verdict :sufficient :reason "ok" :unruled ["shape"]}
        first  (judged-with review (ctx))]
    (is (= :next-round (:control first)) "no amendment, another judgement")
    (is (nil? (:status first)))
    (let [second (judged-with review (ctx :carry (:carry first)))]
      (is (= :unruled (:status second))))
    (is (= :sufficient (:status (judged-with (dissoc review :unruled) (ctx))))
        "a verdict that ruled on every check ends the run as it always did")))

(deftest the-report-is-told-how-much-was-checked-and-whether-it-banks
  (let [out (judged-with {:format :baseline-review :verdict :sufficient :reason "ok"} (ctx))]
    (is (= 4 (:checks out)) "the subjects left once c1 was settled")
    (is (:unbanked out) "a review appended with no identity settles nothing, and says why")))

(deftest nothing-is-settled-at-a-tree-no-review-read
  (let [[seen _] (judged-at "tree-b")]
    (is (= {} (:settled seen)))))

(deftest a-design-claim-about-a-role-it-kept-rests-on-that-roles-players
  ;; A design restates no role it keeps, so its own model names none of the
  ;; role's players — and a player moving must unsettle a claim about it anyway.
  (let [role     {:id "canvas.order/summers" :sort :role :plays ["canvas.order/aggregate"]}
        claim    {:id "summers-sum-once" :about ["canvas.order/summers"]
                  :statement "whatever sums lines sums each once"
                  :falsified-by "a summer that visits a line twice" :evidence {:by :round}}
        design   {:format :design :strata [] :seq 1 :baseline {:seq 0} :model {:elements [] :claims [claim]}}
        ids      {"canvas.order/aggregate" "agg-1" "canvas.order/summers" "role-1"}
        decided  (fn [n] {:format :design-decision :seq n :design-seq 1 :recommend :proceed
                          :confirmed ["summers-sum-once"]
                          :checked-at {"summers-sum-once" ["src/order/aggregate.clj:3"]}
                          :subject-identities ids})
        ledger   {:ws-id "ws-1" :reviews [] :baselines [] :retractions [] :designs [design]
                  :decisions [(decided 0) (decided 2)]}
        settled-at (fn [now]
                     (let [seen (atom nil)]
                       (with-redefs [record/design-decision! (fn [opts] (reset! seen opts)
                                                               {:outcome :no-output :detail "stub"})
                                     record/append! (fn [_ _] nil)
                                     stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                                     ws/latest-entry (fn [_ _ _] design)
                                     stages/discover-baseline (fn [_ _] (update-in a-model-baseline [:model :elements]
                                                                                   conj role))
                                     design-check/elements (fn [_ _] (listing))
                                     settled/code-identity (fn [_] "tree-a")
                                     settled/subject-identities (fn [_ _] now)
                                     settled/ledgers (fn [_ _ _] [ledger])]
                         (run record/design-judge-stage (ctx))
                         (:settled @seen))))]
    (is (= {"summers-sum-once" {:ws-id "ws-1" :seq 2}} (settled-at ids)))
    (is (= {} (settled-at (assoc ids "canvas.order/aggregate" "agg-2")))
        "a player the design never restated moved, so the claim is a check again")))

(defn- reviewed
  "What `baseline-review!` appends for a judge answering `answer` over `a-baseline`, with c1 settled."
  [answer]
  (with-redefs [record/run-round! (fn [_] {:ok (json/generate-string answer)})
                stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                settled/code-identity (fn [_] "tree-a")]
    (record/baseline-review! {:cwd "/w" :run-id "r1" :baseline (assoc a-baseline :seq 3)
                              :settled {"c1" 2} :code-identity "tree-a"})))

(defn- read-at [id] {:id id :evidence ["src/order/aggregate.clj:12"]})

(deftest a-review-confirms-only-what-its-own-judge-checked
  ;; A subject outside the checks was not checked, so a judge listing it confirmed
  ;; nothing; nor does an id naming nothing — watched live, a judge answered with
  ;; health AXIS values. Either kept would let a later round settle on a check
  ;; that never happened.
  (let [r (reviewed {:verdict "sufficient" :reason "ok" :findings [] :unchecked []
                     :confirmed (mapv read-at ["c1" "mod-the-order-aggregate" "design" "shape" "shape"])})]
    (is (= ["mod-the-order-aggregate" "shape"] (:confirmed r)))
    (is (= #{"mod-the-order-aggregate" "shape"} (set (keys (:checked-at r))))
        "and where each was read, which is what lets it settle")))

(deftest a-check-left-without-a-ruling-is-named
  ;; A sufficient verdict ended runs with checks nobody confirmed or refuted, and the ledger's
  ;; confirmed list read as the whole check.
  (let [r (reviewed {:verdict "sufficient" :reason "ok" :findings [] :unchecked []
                     :confirmed [(read-at "shape")]})]
    (is (= ["composition" "invoice-resums" "mod-the-order-aggregate"] (:unruled r)))
    (is (not (ledger-report/review-holds? r)) "a sufficient verdict over an unruled check does not hold")))

(deftest a-confirmation-citing-nothing-it-read-is-no-ruling
  (let [r (reviewed {:verdict "sufficient" :reason "ok" :findings [] :unchecked []
                     :confirmed ["shape" {:id "composition" :evidence []}]})]
    (is (nil? (:confirmed r)) "the judge's word alone confirms nothing")
    (is (every? (set (:unruled r)) ["shape" "composition"]))))

(deftest a-subject-the-judge-could-not-check-is-ruled-and-not-confirmed
  (let [r (reviewed {:verdict "sufficient" :reason "ok" :findings []
                     :confirmed (mapv read-at ["shape" "composition" "mod-the-order-aggregate"])
                     :unchecked [{:id "invoice-resums" :reason "its evidence is a production log"}]})]
    (is (nil? (:unruled r)))
    (is (= [{:id "invoice-resums" :reason "its evidence is a production log"}] (:unchecked r)))
    (is (ledger-report/review-holds? r) "a declared gap is a ruling, and visible where the review is read")))

(deftest a-subject-both-found-against-and-confirmed-has-been-found
  ;; An insufficient finding dropped its claim-id, so a judge that both found against an id and
  ;; confirmed it left the id settled by the very judgement that found against it.
  (let [r (reviewed {:verdict "insufficient" :reason "gap" :unchecked []
                     :confirmed (mapv read-at ["shape" "composition" "mod-the-order-aggregate" "invoice-resums"])
                     :findings [{:claim-id "[shape]" :blocks "relation-honest" :cites ["the shape"]
                                 :claim "c" :needs "n" :evidence []}]})]
    (is (= "shape" (:claim-id (first (:findings r)))))
    (is (not (some #{"shape"} (:confirmed r))))
    (is (nil? (:unruled r)) "a finding is a ruling")))

(deftest a-review-keeps-only-the-identities-its-subjects-rest-on
  ;; One ledger held 730 KB of 790 KB in identity maps: every declared element of the project, on
  ;; every judgement, when a judgement needs only what its own subjects rest on.
  (let [agg      {:id "canvas.order/aggregate" :sort :module :hides "h" :interface "i"}
        claim    {:id "one-path" :about ["canvas.order/aggregate"] :statement "s" :falsified-by "f"
                  :evidence {:by :round}}
        baseline {:format :baseline :seq 3 :strata [] :area "a" :bounded-by "b"
                  :model {:elements [agg] :claims [claim]}}
        r (with-redefs [record/run-round! (fn [_] {:ok (json/generate-string
                                                        {:verdict "sufficient" :reason "ok" :findings []
                                                         :unchecked [] :confirmed (mapv read-at ["one-path" "canvas.order/aggregate"])})})
                        stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                        settled/code-identity (fn [_] "tree-a")]
            (record/baseline-review! {:cwd "/w" :run-id "r1" :baseline baseline :listing {:status :unmodelled}
                                      :code-identity "tree-a"
                                      :subject-identities {"canvas.order/aggregate" "agg-1"
                                                           "canvas.other/unrelated" "x-1"}}))]
    (is (= {"canvas.order/aggregate" "agg-1"} (:subject-identities r)))))

(deftest a-tree-that-moved-under-a-round-with-settled-subjects-appends-nothing
  ;; Those subjects were settled against the tree read as the judge launched, and
  ;; its judge did not read that tree throughout — so the verdict would cover
  ;; subjects nobody checked against what it did read. No review; the answer is
  ;; kept for the report.
  (with-redefs [record/run-round! (fn [_] {:ok "{\"verdict\":\"sufficient\",\"reason\":\"ok\",\"confirmed\":[],\"findings\":[]}"})
                stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                settled/code-identity (fn [_] "tree-b")]
    (let [r (record/baseline-review! {:cwd "/w" :run-id "r1" :baseline (assoc a-baseline :seq 3)
                                      :settled {"c1" 2} :code-identity "tree-a"})]
      (is (= :code-moved (:outcome r)))
      (is (nil? (:format r)) "an outcome is never appended")
      (is (= :sufficient (get-in r [:answer :verdict]))))))

(deftest the-refused-answer-is-kept-in-the-persisted-report
  ;; Nothing was appended, so the report is the only place the judgment can be
  ;; read — and a fold that kept only the outcome wrote a round that said a tree
  ;; moved and nothing about what its judge found.
  (let [moved {:outcome :code-moved :detail "the tree changed while the judge read it"
               :answer  {:format :baseline-review :verdict :falsified :reason "no"
                         :findings [a-finding]}}
        out   (with-redefs [record/baseline-review! (fn [_] moved)
                            record/append! (fn [_ _] nil)
                            stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                            ws/latest-entry (fn [_ _ _] a-baseline)
                            settled/code-identity (fn [_] "tree-a")
                            settled/ledger (fn [_ _] settling-ledger)]
                (run record/judge-stage (ctx)))
        ph    (persisted-phase :judge out)]
    (is (= "code-moved" (:outcome ph)))
    (is (= "the tree changed while the judge read it" (:detail ph))
        "why there is no verdict, which no ledger entry says either")
    (is (= "falsified" (get-in ph [:answer :verdict])))
    (is (= [(:claim a-finding)] (map :claim (get-in ph [:answer :findings])))
        "with what the judge found, since the stage copies none of it into :findings")))

(deftest a-settled-subject-is-shown-but-is-not-a-check
  (let [p (record/baseline-prompt {:baseline a-baseline :settled {"c1" 2 "shape" 2}})
        [outside checks] (-> p (str/split #"OUTSIDE THIS ROUND'S CHECKS" 2) second
                             (str/split #"WHAT YOU ARE CHECKING" 2))]
    (is (some? outside) "every subject is still shown")
    (is (str/includes? outside "[c1] the aggregate is the only summing path"))
    (is (str/includes? outside "[shape] the aggregate is the only thing that sums lines"))
    (is (not (str/includes? outside "refuted by")) "with nothing to go looking for")
    (is (not (str/includes? checks "[c1]")) "and not among the checks")
    (is (not (str/includes? checks "[shape]")))
    (is (str/includes? checks "[invoice-resums]") "an unsettled subject is still a check")))

(deftest the-checks-come-last-and-are-restated-by-id
  ;; Shown the settled block after its checks, a judge confirmed ten of those ids and skipped one of
  ;; its two checks: what it read last was the list it drew its answer from.
  (let [p     (record/baseline-prompt {:baseline a-baseline :settled {"c1" 2 "shape" 2}})
        rule  (second (str/split p #"RULE ON EVERY SUBJECT YOU ARE ASKED TO CHECK" 2))]
    (is (< (str/index-of p "OUTSIDE THIS ROUND'S CHECKS") (str/index-of p "WHAT YOU ARE CHECKING"))
        "the settled subjects come before the checks, never after them")
    (is (= #{"composition" "invoice-resums" "mod-the-order-aggregate"}
           (set (map second (re-seq #"(?m)^- ([a-z-]+)$" rule))))
        "the instructions name exactly the open checks, so the confirmed list is drawn from them")))

(def ^:private every-subject
  "Every subject of `a-baseline`, settled."
  (into {} (map (fn [id] [id {:ws-id "ws-1" :seq 5}])) (keys (settled/subjects a-baseline))))

(defn- verified-ledger
  "A ledger on which `verified` was found sufficient, by the review at entry 5."
  [verified]
  {:ws-id       "ws-1"
   :baselines   [(assoc verified :seq 2)]
   :reviews     [{:format :baseline-review :seq 5 :baseline-seq 2 :verdict :sufficient :reason "ok"}]
   :retractions []})

(defn- all-settled
  "What `baseline-review!` returns for `record`, every subject settled, over `ledger` — and the
   options the judge was launched with, or nil when none was."
  [record ledger]
  (let [launched (atom nil)]
    (with-redefs [record/run-round! (fn [opts] (reset! launched opts)
                                      {:ok (json/generate-string {:verdict "sufficient" :reason "still derivable"
                                                                  :findings [] :unchecked [] :confirmed []})})
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  stages/read-stance (constantly nil)
                  settled/ledger (fn [_ _] ledger)
                  settled/code-identity (fn [_] "tree-a")]
      [(record/baseline-review! {:cwd "/w" :run-id "r1" :baseline (assoc record :seq 7)
                                 :settled every-subject :code-identity "tree-a"})
       @launched])))

(deftest a-record-reading-as-the-verified-one-is-answered-without-a-judge
  ;; A judge handed no check re-verified nineteen settled ids at 73k tokens, and every one of its
  ;; confirmations was discarded. Nothing it could say was asked of it.
  (let [[r launched] (all-settled (assoc a-baseline :supersedes {:seq 2 :why "restated"})
                                  (verified-ledger a-baseline))]
    (is (nil? launched) "no judge is launched")
    (is (= {:verdict :sufficient :baseline-seq 7 :carried-from 5}
           (select-keys r [:verdict :baseline-seq :carried-from]))
        "the verdict the verified record reached stands for this one, naming where it was reached")
    (is (ledger-report/review-holds? r) "so a design resting on it has a verified premise")
    (is (= r (ledger-report/validate-event :baseline-review r)) "and the ledger takes it")))

(deftest a-record-that-dropped-a-subject-is-asked-only-whether-the-derivations-hold
  ;; Settlement is computed over the subjects that survive, so a removal is invisible to it: the one
  ;; live question is whether the four derivations are still makeable without what was dropped.
  (let [verified (-> a-baseline
                     (update :load-bearing conj {:id "c2" :property "the invoice asks the aggregate"})
                     (assoc :area "order totalling and invoicing"))
        [r launched] (all-settled a-baseline (verified-ledger verified))
        p (:prompt launched)]
    (is (some? launched) "a judge is asked")
    (is (str/includes? p "dropped [c2] the invoice asks the aggregate") "named with what it said")
    (is (str/includes? p "- area reads differently") "and a field no id names, which settlement cannot see")
    (is (str/includes? p "can the four derivations still be made"))
    (is (not (str/includes? p "Populate confirmed")) "it is asked to confirm nothing")
    (is (not (str/includes? p "WHAT YOU ARE CHECKING")) "and handed no check")
    (is (str/includes? p "[c1] the aggregate is the only summing path")
        "while every subject is still in front of it, since the derivations are made against them")
    (is (= :sufficient (:verdict r)))
    (is (nil? (:carried-from r)))))

(deftest an-all-settled-round-with-no-verified-record-asks-about-the-whole-record
  (let [[_ launched] (all-settled a-baseline (assoc (verified-ledger a-baseline) :reviews []))]
    (is (str/includes? (:prompt launched) "No verified record precedes this one"))
    (is (not (str/includes? (:prompt launched) "Populate confirmed")))))

(deftest a-carried-verdict-is-not-counted-as-a-judge
  (let [carried {:format :baseline-review :verdict :sufficient :reason "carried" :baseline-seq 1
                 :carried-from 5}
        out     (with-redefs [record/baseline-review! (fn [_] carried)
                              record/append! (fn [_ _] nil)
                              stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                              ws/latest-entry (fn [_ _ _] a-baseline)
                              settled/code-identity (fn [_] "tree-a")
                              settled/ledgers (fn [_ _ _] [(assoc settling-ledger :ws-id "ws-1")])]
                  (run record/judge-stage (ctx)))
        ph      (persisted-phase :judge out)]
    (is (= :sufficient (:status out)))
    (is (= 5 (:carried-from ph)) "the report says whose verdict it restated")
    (is (nil? (:unbanked ph)) "and does not blame a tree that never moved under a judge")
    (is (zero? (record/judges-launched {:rounds [{:phases [ph]}]}))
        "a run whose only round carried a verdict judged nothing")))

(deftest a-record-with-every-claim-settled-heads-no-empty-list
  ;; A header over nothing reads as a baseline that claims nothing, which is the
  ;; opposite of true: every claim is there, outside the checks.
  (let [all-claims (into {} (map (fn [c] [(:id c) 2])) (:load-bearing a-baseline))
        [_ checks] (str/split (record/baseline-prompt {:baseline a-baseline :settled all-claims})
                              #"WHAT YOU ARE CHECKING" 2)]
    ;; The header itself, not the word: the lens vocabulary above it says a claim
    ;; lens reads "a LOAD-BEARING CLAIM".
    (is (not (str/includes? checks "LOAD-BEARING — what is claimed"))))
  (testing "while a record with no claims at all keeps the header it always had"
    (is (str/includes? (record/baseline-prompt {:baseline {:format :baseline}})
                       "LOAD-BEARING — what is claimed"))))

(deftest the-judge-is-never-told-why-a-subject-is-outside-its-checks
  ;; The partition is all it learns. A judge told a subject was checked before, or
  ;; that the rest are what changed, is judging a delta.
  (let [p (record/baseline-prompt {:baseline a-baseline :settled {"c1" 2}})
        section (-> p (str/split #"OUTSIDE THIS ROUND'S CHECKS" 2) second
                    (str/split #"WHAT YOU ARE CHECKING" 2) first str/lower-case)]
    (doseq [w ["earlier" "before" "previous" "already" "amend" "chang" "settled" "entry" "round of"]]
      (is (not (str/includes? section w)) (str "the section says \"" w "\"")))))

(deftest a-gap-is-keyed-on-the-derivation-it-blocks
  ;; The one handle an amendment cannot move. A gap carries no :claim-id — the
  ;; schema has no room for one — so without this it falls back to the evidence
  ;; or the cited text, both of which the answer to the gap rewrites.
  (is (= [:blocks :decomposable] (record/baseline-finding-base-key a-gap)))
  (is (= (record/baseline-finding-base-key a-gap)
         (record/baseline-finding-base-key
          (assoc a-gap :cites ["something else entirely"]
                 :needs "worded differently" :evidence ["src/other.clj:3"])))))

(deftest a-gap-and-a-refutation-are-never-the-same-finding
  (is (not= (record/baseline-finding-base-key a-gap)
            (record/baseline-finding-base-key a-finding))))

;; ── The subjects a finding can name ─────────────────────────────────────────

(deftest every-subject-in-a-baseline-is-nameable
  ;; A finding keyed on the evidence it happens to cite is keyed on something
  ;; the amendment answering it moves — the identity-by-description failure this
  ;; loop has already paid for three times. :shape and :composition are not
  ;; items in a vector and so carry no :id of their own, and they are the two a
  ;; decomposition-level round challenges most.
  (let [ids (set (keys (settled/subjects a-baseline)))]
    (is (contains? ids "shape"))
    (is (contains? ids "composition"))
    (is (contains? ids "c1") "the claim ids are still there")
    (is (contains? ids "invoice-resums") "and the health observation ids")))

(deftest a-reserved-id-is-only-known-when-the-baseline-fills-it-in
  (is (not (contains? (settled/subjects (dissoc a-baseline :composition))
                      "composition"))))

(deftest a-model-baseline-names-its-claims-and-elements-as-subjects
  (let [ids (settled/subjects {:format :baseline :strata [] :shape "s"
                               :model {:elements [{:id "canvas.x/m" :sort :module}]
                                       :claims   [{:id "k1" :about ["canvas.x/m"] :statement "s"
                                                   :falsified-by "f" :evidence {:by :round}}]}})]
    (is (contains? ids "k1") "a claim is confirmed by its id")
    (is (contains? ids "canvas.x/m") "and so is an element")
    (is (contains? ids "shape"))))

(deftest the-judge-is-shown-the-ids-it-is-asked-to-cite
  ;; An id in the record and not in the prompt cannot be cited back. Health
  ;; observations carried one all along and it was the one subject never
  ;; printed, so a judge could not confirm one by id.
  (let [p (record/baseline-prompt {:baseline a-baseline})]
    (is (str/includes? p "[shape]"))
    (is (str/includes? p "[composition]"))
    (is (str/includes? p "[invoice-resums]"))
    (is (str/includes? p "[c1]"))))

(deftest the-amender-is-told-to-replace-a-claim-not-annotate-it
  ;; "Change only what was refuted" says WHICH statements to touch; it never
  ;; said how. Measured over six rounds on one baseline: the composition went 405
  ;; characters to 4091 because every correction was hung off the sentence that
  ;; was wrong, and each added clause became the next round's finding — the same
  ;; field refuted three rounds running for three different reasons.
  (let [p (record/amend-prompt {:baseline a-baseline :findings [a-finding]
                                :out-path "/x"})]
    (is (str/includes? p "REPLACE IT"))
    (is (str/includes? p "cannot converge"))
    (is (str/includes? p "about the length it was"))))

(deftest the-record-a-run-started-from-is-kept-for-the-whole-run
  ;; Growth is a property of the RUN, not of one amendment: 400 characters to
  ;; 800 is a correction and six of those in a row is a claim nobody can check,
  ;; so the comparison has to be against what was there at the start.
  (let [first-amend (assoc a-baseline :area "round one")
        second-amend (assoc a-baseline :area "round two")]
    (with-redefs [ws/entry-at-seq (fn [_ _ _] nil)]
      (let [[out1 _] (with-amend {:writes (fn [p] (spit p (pr-str {:record first-amend})))}
                                 (ctx :findings [a-finding]))
            ;; the next round, carrying what the first one left
            [out2 _] (with-amend {:prev first-amend
                                  :writes (fn [p] (spit p (pr-str {:record second-amend})))}
                                 (assoc (ctx :findings [a-finding])
                                        :carry (:carry out1)))]
        (is (= a-baseline (:as-authored (:carry out1))))
        (is (= a-baseline (:as-authored (:carry out2)))
            "still the record the run began with, not the previous round's")
        (is (= second-amend (:under-repair (:carry out2))))))))

;; ── Naming what a correction corrects ───────────────────────────────────────

(deftest a-corrected-baseline-names-the-baseline-it-corrects
  ;; The round is the only thing that knows the pair, and the edge is written
  ;; rather than derived: taking the newest baseline instead is exactly the
  ;; recency the ledger's citations exist to refuse.
  (let [corrected (assoc a-baseline :area "corrected")
        [_ appended] (with-amend {:prev (assoc a-baseline :seq 7 :at "t")
                                  :writes (fn [p] (spit p (pr-str {:record corrected})))}
                                 (ctx :findings [a-finding]))]
    (is (= 7 (get-in (read-string appended) [:supersedes :seq])))
    (is (str/includes? (get-in (read-string appended) [:supersedes :why]) "round 1"))))

(deftest an-amender-that-named-its-own-correction-is-left-alone
  ;; A citation is the author's, and nothing here overrules one.
  (let [corrected (assoc a-baseline :area "corrected"
                         :supersedes {:seq 3 :why "the narrow follow-up, not the broad one"})
        [_ appended] (with-amend {:prev (assoc a-baseline :seq 7 :at "t")
                                  :writes (fn [p] (spit p (pr-str {:record corrected})))}
                                 (ctx :findings [a-finding]))]
    (is (= 3 (get-in (read-string appended) [:supersedes :seq])))))

(deftest a-citation-naming-something-that-is-not-a-baseline-is-replaced
  ;; The guess an amender actually makes is the entry the findings came from —
  ;; the review — and a baseline may only supersede a baseline, so honouring it
  ;; loses the whole amendment to a ledger refusal. Nine rounds of one run were
  ;; lost exactly this way before the citation was checked rather than trusted.
  (let [corrected (assoc a-baseline :area "corrected"
                         :supersedes {:seq 3 :why "the entry I read the findings from"})
        [_ appended] (with-redefs [ws/entry-at-seq (fn [_ _ n]
                                                     (when (= 3 n)
                                                       {:format :baseline-review :seq 3}))]
                       (with-amend {:prev (assoc a-baseline :seq 7 :at "t")
                                    :writes (fn [p] (spit p (pr-str {:record corrected})))}
                                   (ctx :findings [a-finding])))]
    (is (= 7 (get-in (read-string appended) [:supersedes :seq]))
        "replaced with the record the round was asked to repair")))

(deftest a-citation-naming-another-baseline-is-still-the-authors
  ;; The capability the check must not cost: a workstream can hold baselines of
  ;; DIFFERENT areas, and an amender may deliberately supersede the narrow
  ;; follow-up rather than the broad survey it sits beside.
  (let [corrected (assoc a-baseline :area "corrected"
                         :supersedes {:seq 3 :why "the narrow follow-up, not the broad one"})
        [_ appended] (with-redefs [ws/entry-at-seq (fn [_ _ n]
                                                     (when (= 3 n)
                                                       {:format :baseline :seq 3}))]
                       (with-amend {:prev (assoc a-baseline :seq 7 :at "t")
                                    :writes (fn [p] (spit p (pr-str {:record corrected})))}
                                   (ctx :findings [a-finding])))]
    (is (= 3 (get-in (read-string appended) [:supersedes :seq])))))

(deftest a-baseline-corrected-twice-keeps-the-reason-its-author-gave
  ;; The author's :why pinned the base revision the survey was read at, and the
  ;; first amendment's boilerplate erased the only mention of it.
  (let [corrected (assoc a-baseline :area "corrected")
        [_ appended] (with-amend {:prev (assoc a-baseline :seq 7 :at "t"
                                               :supersedes {:seq 3 :why "surveyed at d045c958"})
                                  :writes (fn [p] (spit p (pr-str {:record corrected})))}
                                 (ctx :findings [a-finding]))]
    (is (= 7 (get-in (read-string appended) [:supersedes :seq])))
    (is (str/includes? (get-in (read-string appended) [:supersedes :why]) "surveyed at d045c958"))))

(deftest the-baseline-amender-is-told-what-every-stratum-owes
  ;; A new stratum declared without a reading, and a reading demoted without the
  ;; health observation naming it, each cost a whole amendment at the ledger.
  (let [p (record/amend-prompt {:baseline (assoc a-baseline :strata [] :model {:elements [] :claims []})
                                :findings [a-finding] :out-path "/x"})]
    (is (str/includes? p "must be named, under :about, by a :health observation"))
    (is (str/includes? p "carries a :stratified/level reading. A stratum you add\nneeds all four"))))

(deftest a-refusal-says-where-in-the-record-the-slip-is
  ;; `{:removed ["disallowed key"]}` read, to an amender shown a record whose
  ;; :model legally holds :removed, as a contradiction of what it was shown.
  (let [explain (m/explain [:map {:closed true} [:model [:map [:a int?]]]]
                           {:removed 1 :model {:a "x"}})
        msg     (#'record/ledger-refusal (ex-info "Invalid event report" {:explain explain}))]
    (is (str/includes? msg "[:removed]: disallowed key"))
    (is (str/includes? msg "[:model :a]: should be an int"))))

(deftest a-baseline-with-no-seq-to-name-gains-no-citation
  ;; The run was pointed at a record that is not in this ledger — a nested
  ;; loop's value, or a fixture. Inventing a number would be worse than none.
  (let [corrected (assoc a-baseline :area "corrected")
        [_ appended] (with-amend {:prev a-baseline
                                  :writes (fn [p] (spit p (pr-str {:record corrected})))}
                                 (ctx :findings [a-finding]))]
    (is (nil? (:supersedes (read-string appended))))))

;; ── Two clean readings, and what a confirmation overturns ───────────────────

(defn- judged-over
  "The judge stage's ctx over `a-baseline` at seq 1, its judge answering `review` and the
   workstream holding `reviews`, at `tree`. Everything it appends lands in `appended`; what the
   judge was handed in `seen`."
  [{:keys [review reviews tree appended seen] :or {tree "tree-a"}} c]
  (with-redefs [record/baseline-review! (fn [opts] (some-> seen (reset! opts)) review)
                record/append! (fn [_ r] (some-> appended (swap! conj r)) nil)
                stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                ws/latest-entry (fn [_ _ _] (assoc a-baseline :seq 1))
                settled/code-identity (fn [_] tree)
                settled/ledgers (fn [_ _ _] [{:ws-id "ws-1" :reviews (vec reviews)
                                              :baselines [(assoc a-baseline :seq 1)]
                                              :decisions [] :designs [] :retractions []}])]
    (run record/judge-stage c)))

(def ^:private confirming
  {:format :baseline-review :baseline-seq 1 :verdict :sufficient :reason "ok"
   :confirmed ["invoice-resums"] :checked-at {"invoice-resums" ["src/order/invoice.clj:88"]}
   :code-identity "tree-a"})

(deftest a-sufficient-verdict-on-a-first-reading-is-read-again
  ;; A baseline loop ended :sufficient on one clean reading, and the same judge refuted a claim a
  ;; round later on text nobody touched. The first reading is kept and holds nothing; the second
  ;; ends the run.
  (let [appended (atom [])
        seen     (atom nil)
        r1       (judged-over {:review confirming :appended appended} (ctx))
        reading  (first @appended)]
    (is (= :next-round (:control r1)) "another judgement, with nothing amended")
    (is (nil? (:status r1)))
    (is (= ["invoice-resums"] (:read-once reading)) "the ledger says which subjects were read once")
    (is (not (ledger-report/review-holds? reading))
        "and no reader takes the baseline as verified on it")
    (let [r2 (judged-over {:review confirming :appended appended :seen seen
                               :reviews [(assoc reading :seq 2)]}
                              (ctx :carry (:carry r1)))]
      (is (not (contains? (:settled @seen) "invoice-resums"))
          "the subject read once is put to the next judge again, not skipped")
      (is (= :sufficient (:status r2)) "the second consecutive clean reading ends the run")
      (is (nil? (:read-once (last @appended)))))))

(deftest a-tree-with-no-identity-pairs-its-readings-within-the-run
  ;; Nothing banks on the ledger without an identity, so a rule paired only there would read such a
  ;; record for ever.
  (let [review (dissoc confirming :code-identity)
        r1     (judged-over {:review review :tree nil} (ctx))]
    (is (= :next-round (:control r1)))
    (is (= :sufficient (:status (judged-over {:review review :tree nil} (ctx :carry (:carry r1))))))))

(deftest a-sufficient-verdict-on-a-baseline-this-run-amended-is-read-again
  ;; An amendment that moved no subject leaves every one settled, so the judge after it confirms
  ;; nothing and would end the run on one reading of a record no judge had read before.
  (let [appended (atom [])
        quiet    (dissoc confirming :confirmed :checked-at)
        amended  [{:iter 1 :amended? true}]
        r1       (judged-over {:review quiet :appended appended} (ctx :iter 2 :history amended))]
    (is (= :next-round (:control r1)) "another judgement, with nothing amended")
    (is (true? (:amendment-read-once (first @appended))))
    (is (not (ledger-report/review-holds? (first @appended)))
        "and no reader takes the baseline as verified on it")
    (is (= :sufficient (:status (judged-over {:review quiet :appended appended}
                                             (ctx :iter 3 :history amended :carry (:carry r1)))))
        "the second reading of the amendment ends the run")
    (testing "a run that amended nothing ends on the reading"
      (is (= :sufficient (:status (judged-over {:review quiet} (ctx))))))))

(deftest an-earlier-runs-refutation-is-put-to-the-judge-and-its-reversal-recorded
  ;; A falsified->sufficient flip on an unchanged record rested on one uninformed reading, and was
  ;; recorded with no word of what it overturned.
  (let [refuted  {:format :baseline-review :seq 2 :baseline-seq 1 :verdict :falsified :reason "no"
                  :run-id "r0" :code-identity "tree-a"
                  :findings [{:claim-id "invoice-resums" :cites ["two summing paths"]
                              :claim "the invoice no longer sums on its own"}]}
        appended (atom [])
        seen     (atom nil)]
    (judged-over {:review confirming :reviews [refuted] :appended appended :seen seen} (ctx))
    (is (= 2 (get-in @seen [:prior "invoice-resums" :seq])) "the judge is handed the finding")
    (is (= [{:id "invoice-resums" :seq 2 :ws-id "ws-1"}] (:overturns (first @appended)))
        "and the confirmation says what it overturns")
    (let [p (record/baseline-prompt {:baseline a-baseline :prior (:prior @seen)})]
      (is (str/includes? p "FOUND AGAINST BEFORE"))
      (is (str/includes? p "the invoice no longer sums on its own"))
      (is (str/includes? p "what it found against reads the same now") "an unchanged subject makes a confirmation a pure reversal"))))

;; ── Known staleness: a route out of settlement ──────────────────────────────

(deftest a-finding-against-a-settled-id-is-recorded-as-overriding-the-settlement
  ;; A false confirmation stayed settled for eight rounds and, when a judge finally found against it,
  ;; the figures read it as an ordinary refutation — so how often settlement shields a false claim
  ;; could not be counted.
  (let [appended (atom [])
        refuting {:format :baseline-review :verdict :falsified :reason "no"
                  :findings [{:claim-id "c1" :cites ["[c1] only the aggregate sums"] :claim "a second path"}]}]
    (with-redefs [record/baseline-review! (fn [_] refuting)
                  record/append! (fn [_ r] (swap! appended conj r) nil)
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ _] a-baseline)
                  settled/code-identity (fn [_] "tree-a")
                  settled/ledgers (fn [_ _ _] [(assoc settling-ledger :ws-id "ws-1")])]
      (run record/judge-stage (ctx)))
    (is (= [{:id "c1" :seq 2 :ws-id "ws-1"}] (:overrides-settled (first @appended)))
        "the finding names the confirmation it overrides, where it was made")
    (is (= {"c1" 1} (:settled-then-found (record/run-figures [(first @appended)])))
        "and the figures tally it apart from an ordinary refutation")))

(deftest an-amender-can-name-a-sibling-it-believes-stale
  (is (= #{"c1"} (:stale (record/parse-amend-answer {:record a-baseline :stale ["c1" " "]}
                                                    [a-finding] record/baseline-finding-base-key)))
      "a blank id names nothing")
  (is (= #{} (:stale (record/parse-amend-answer a-baseline [a-finding] record/baseline-finding-base-key)))
      "a bare record says nothing is stale")
  (let [[out _] (with-amend {:writes (fn [p] (spit p (pr-str {:record a-baseline :stale ["c1"]})))}
                            (ctx :findings [a-finding]))]
    (is (= #{"c1"} (get-in out [:carry :stale])) "carried to the next round's judge")
    (is (= ["c1"] (:stale (persisted-phase :amend out))) "and the report says it was named")))

(deftest a-subject-named-stale-is-put-to-the-next-judge-once
  ;; The amender that saw a confirmed sibling become false was forbidden to touch it, and unchanged
  ;; it stayed settled on a confirmation older than the finding that contradicted it.
  (let [seen (atom nil)]
    (with-redefs [record/baseline-review! (fn [opts] (reset! seen opts)
                                            {:format :baseline-review :verdict :sufficient :reason "ok"})
                  record/append! (fn [_ _] nil)
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ _] a-baseline)
                  settled/code-identity (fn [_] "tree-a")
                  settled/ledgers (fn [_ _ _] [(assoc settling-ledger :ws-id "ws-1")])]
      (let [out (run record/judge-stage (ctx :carry {:stale #{"c1"}}))]
        (is (not (contains? (:settled @seen) "c1")) "the judge is asked to check it")
        (is (nil? (get-in out [:carry :stale]))
            "and only once: the round after reads the ledger's settlement as it then stands")))))

(deftest the-amenders-are-told-how-to-name-a-stale-sibling
  (is (str/includes? (record/amend-prompt {:baseline a-baseline :findings [a-finding] :out-path "/x"})
                     ":stale"))
  (is (str/includes? (record/design-amend-prompt {:design {:format :design} :raised [] :findings []
                                                  :out-path "/x"})
                     ":stale")))

;; ── What an amendment has to stay true beside ───────────────────────────────

(def ^:private an-audit-baseline
  {:format :baseline :strata [] :area "adoption" :bounded-by "the audit table"
   :model {:elements [{:id "adoption-audit-persistence" :sort :module
                       :hides "the org_adoption_audit table"
                       :interface "record-adoption!"}
                      {:id "org-model" :sort :module :hides "organization rows"}
                      {:id "billing" :sort :module :hides "invoices"}]
           :claims   [{:id "composition"
                       :about ["adoption-audit-persistence" "org-model"]
                       :statement "the adoption audit is stored by adoption-audit-persistence alone"
                       :falsified-by "a write to org_adoption_audit outside it"
                       :evidence {:by :round}
                       :read-at ["src/org/adopt.clj:175"]}
                      {:id "audit-append-only" :about ["adoption-audit-persistence"]
                       :statement "an audit row is never updated"
                       :falsified-by "an UPDATE on org_adoption_audit"
                       :evidence {:by :round}}
                      {:id "user-writes" :about ["billing"]
                       :statement "user.clj writes no organization row"
                       :falsified-by "an insert from user.clj"
                       :evidence {:by :round}
                       :read-at ["src/model/user.clj:775"]}
                      {:id "invoices-numbered" :about ["billing"]
                       :statement "every invoice has a number"
                       :falsified-by "an invoice with no number"
                       :evidence {:by :round}}]}})

(def ^:private an-audit-finding
  {:claim-id "composition"
   :cites ["[composition] stored by adoption-audit-persistence alone"]
   :claim "model/user.clj also writes the table"
   :evidence ["src/model/user.clj:775"]})

(deftest an-amender-is-shown-what-the-claim-it-rewrites-must-agree-with
  ;; An amender wrote a composition sentence contradicting a module claim settled in the same
  ;; record, and the only check it had was its memory of a long record. The listing is the set
  ;; it checks against: the modules the claim is about, the claims about them, and the claims read
  ;; at the code the finding cites.
  (let [rows (#'record/bearing-subjects {:record an-audit-baseline :findings [an-audit-finding]
                                       :settled {"audit-append-only" {:ws-id "ws-1" :seq 3}}})
        ids  (set (map :id rows))]
    (is (contains? ids "adoption-audit-persistence")
        "the module the refuted claim is about states what it hides — the thing a rewrite contradicts")
    (is (contains? ids "audit-append-only") "a claim about the same module bears on the rewrite")
    (is (contains? ids "user-writes")
        "a claim read at the very line the counterexample cites bears on it, whatever it is about")
    (is (not (contains? ids "composition")) "the claim being rewritten is not its own constraint")
    (is (not (contains? ids "invoices-numbered"))
        "a claim sharing nothing with the finding is noise that buries the ones that matter")
    (is (not (contains? ids "billing")) "a module the claim is not about is not listed")
    (is (= {:ws-id "ws-1" :seq 3} (:settled (first (filter #(= "audit-append-only" (:id %)) rows))))
        "a settled claim says so, because the amender may not move it and must fit beside it")))

(deftest the-baseline-amend-prompt-lists-what-a-rewrite-must-agree-with
  (let [p (record/amend-prompt {:baseline an-audit-baseline :findings [an-audit-finding]
                                :out-path "/x" :settled {"audit-append-only" {:seq 3}}})]
    (is (str/includes? p "WHAT YOUR REWRITE MUST STAY TRUE BESIDE"))
    (is (str/includes? p "- [audit-append-only] (settled by entry 3) an audit row is never updated")
        "a settled claim is named with the entry that settled it")
    (is (str/includes? p "hides:      the org_adoption_audit table")
        "a module is listed by what it hides, which is what a responsibility sentence can contradict")
    (is (not (str/includes? p "[invoices-numbered] every")))))

(deftest a-round-with-nothing-bearing-lists-nothing
  ;; A heading over an empty list reads as a record with nothing to agree with.
  (is (not (str/includes? (record/amend-prompt {:baseline a-baseline :findings [a-finding]
                                                :out-path "/x"})
                          "WHAT YOUR REWRITE MUST STAY TRUE BESIDE."))))

(deftest the-baseline-amender-is-told-how-a-rewrite-stays-true
  ;; Each rule answers a rewrite that was the next round's finding: a cited site repaired while
  ;; its siblings stayed false, an `alone` nobody searched for, a new closed list false over code
  ;; just read, a :falsified-by narrower than its statement or satisfied by the record itself.
  (let [p (record/amend-prompt {:baseline a-baseline :findings [a-finding] :out-path "/x"})]
    (is (str/includes? p "REPAIR THE CLASS, NOT THE INSTANCE"))
    (is (str/includes? p "NO UNIVERSAL YOU HAVE NOT CHECKED"))
    (is (str/includes? p "name that search in :read-at")
        "an exclusivity is only as good as the search behind it, and the next reader must re-run it")
    (is (str/includes? p "do not write a new list"))
    (is (str/includes? p ":falsified-by COVERS EVERY UNIVERSAL THE STATEMENT MAKES"))
    (is (str/includes? p "a counterexample the record itself states is a contradiction"))
    (is (not (str/includes? p "a quantifier you widen must not"))
        "a baseline amender has no baseline beneath it to cross")))

;; ── A claim no rewording settles ────────────────────────────────────────────

(defn- review [& {:keys [confirmed refuted gaps]}]
  {:format :baseline-review :verdict :falsified
   :confirmed (vec confirmed)
   :findings (into (mapv #(hash-map :claim-id % :cites ["x"] :claim "y") refuted)
                   (map #(hash-map :claim-id % :blocks :goal-served :cites ["x"] :claim "y" :needs "z") gaps))})

(deftest refutations-are-counted-per-claim-across-the-readings-that-ruled-on-it
  (is (= {"c1" 2} (record/refuted-running [(review :refuted ["c1"]) (review :refuted ["c1"])])))
  (is (= {"c1" 1} (record/refuted-running [(review :refuted ["c1"]) (review :confirmed ["c1"])
                                           (review :refuted ["c1"])]))
      "a confirmation ends a run: the claim was true at some rewording")
  (is (= {"c1" 2} (record/refuted-running [(review :refuted ["c1"]) (review :confirmed ["c2"])
                                           (review :refuted ["c1"])]))
      "a review that did not rule on the claim is no reading of it, and neither breaks nor extends")
  (is (= {} (record/refuted-running [(review :refuted ["c1"]) (review :confirmed ["c1"])]))
      "a claim whose newest reading held has no run at all")
  (is (= {"c1" 1} (record/refuted-running [(review :gaps ["c1"]) (review :refuted ["c1"])]))
      "a gap refutes nothing"))

(def ^:private a-claim-finding (assoc a-finding :claim-id "c1"))

(deftest a-claim-refuted-two-readings-running-is-offered-removal-with-a-reason
  (let [p (record/amend-prompt {:baseline a-baseline :findings [a-claim-finding] :out-path "/x"
                                :refuted-running {"c1" 2}})]
    (is (str/includes? p "A CLAIM NO REWORDING HAS SETTLED. [c1] has been refuted 2 readings running"))
    (is (str/includes? p "Restating it at the same strength is off the\ntable")
        "a third rewording at the same strength is what the claim's history says will fail")
    (is (str/includes? p "WEAKEN it to what the cited code guarantees, including on its failure path"))
    (is (str/includes? p "give the reason under :withdrawn"))
    (is (str/includes? p ":withdrawn [{:id \"c1\" :because \"...\"}]")
        "the answer shape names the field the removal's reason travels in")
    (is (str/includes? p "running: refuted 2 readings in a row")
        "the finding itself says it is one of them"))
  (testing "one refutation is an ordinary round: the amender is not invited to give up on a claim"
    (let [p (record/amend-prompt {:baseline a-baseline :findings [a-claim-finding] :out-path "/x"
                                  :refuted-running {"c1" 1}})]
      (is (not (str/includes? p "A CLAIM NO REWORDING HAS SETTLED")))
      (is (not (str/includes? p ":withdrawn")))))
  (testing "a refuted health observation is not a claim, and no withdrawal of it would be reported"
    (let [p (record/amend-prompt {:baseline a-baseline
                                  :findings [(assoc a-finding :claim-id "invoice-resums")]
                                  :out-path "/x" :refuted-running {"invoice-resums" 3}})]
      (is (not (str/includes? p "A CLAIM NO REWORDING HAS SETTLED"))))))

(deftest the-judge-counts-this-round-once-beside-the-ledgers-readings
  (with-redefs [record/baseline-review! (fn [_] (review :refuted ["c1"]))
                record/append! (fn [_ _] nil)
                stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                ws/latest-entry (fn [_ _ _] a-baseline)
                ws/entries-of (fn [_ _ kind] (if (= :baseline-review kind) [(review :refuted ["c1"])] []))]
    (is (= {"c1" 2} (:refuted-running (run record/judge-stage (ctx)))))))

(defn- without-c1 [b] (assoc b :load-bearing []))

(deftest a-withdrawal-the-round-offered-is-reported-with-its-reason
  (let [[out _] (with-amend {:writes (fn [p] (spit p (pr-str {:record (without-c1 a-baseline)
                                                               :withdrawn [{:id "c1" :because "nothing rests on it"}]})))}
                            (ctx :findings [a-claim-finding] :refuted-running {"c1" 2}))]
    (is (nil? (:status out)) "a withdrawal is a repair; the loop goes on to judge it")
    (is (= [{:what :claim-withdrawn :detail "claim c1 was removed: nothing rests on it"}]
           (:retreats out))
        "the human reads why the claim went, not three weakenings with no reason")))

(deftest a-reason-for-a-removal-the-round-did-not-offer-is-not-a-withdrawal
  (let [[out _] (with-amend {:writes (fn [p] (spit p (pr-str {:record (without-c1 a-baseline)
                                                               :withdrawn [{:id "c1" :because "nothing rests on it"}]})))}
                            (ctx :findings [a-claim-finding] :refuted-running {"c1" 1}))]
    (is (contains? (set (map :what (:retreats out))) :claim-dropped)
        "a claim refuted once has not been shown unfixable by rewording, so its removal stays a drop")
    (is (not (contains? (set (map :what (:retreats out))) :claim-withdrawn)))))
