;; test/nido/review/stages_test.clj
(ns nido.review.stages-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.agent :as agent]
   [nido.review.codex :as codex]
   [nido.review.layers :as layers]
   [nido.review.stages :as stages]
   [nido.vsdd.jj :as jj]))

(deftest parse-warden-decision-reads-per-finding-rulings
  (let [txt (str "Here is my call.\n\n```json\n"
                 "{\"decision\":\"continue\",\"reason\":\"2 real bugs\","
                 "\"findings\":[{\"id\":\"aa11\",\"owner_layer\":\"drop-legacy\","
                 "\"disposition\":\"fix\",\"because\":\"real\"}]}\n"
                 "```\n")
        d   (stages/parse-warden-decision txt)]
    (is (= :continue (:decision d)))
    (is (= [{:id "aa11" :owner-layer "drop-legacy" :disposition :fix
             :authority nil :of nil :because "real"}]
           (:rulings d)))))

(deftest parse-warden-decision-reads-an-unknown-disposition-as-fix
  ;; The fail-safe direction: an unrecognised ruling is worked on, never dropped.
  (let [txt (str "```json\n{\"decision\":\"continue\",\"findings\":"
                 "[{\"id\":\"aa11\",\"disposition\":\"whatever\"}]}\n```")]
    (is (= :fix (:disposition (first (:rulings (stages/parse-warden-decision txt))))))))

(deftest parse-warden-decision-stop-without-rulings
  (let [txt "```json\n{\"decision\":\"stop\",\"reason\":\"clean\"}\n```"]
    (is (= {:decision :stop :reason "clean" :rulings []}
           (stages/parse-warden-decision txt)))))

(deftest parse-warden-decision-malformed-is-indeterminate
  (is (= :indeterminate (:decision (stages/parse-warden-decision "no json here"))))
  (is (= :indeterminate (:decision (stages/parse-warden-decision nil)))))

(deftest review-stage-sets-findings
  (with-redefs [layers/patch-hash (fn [& _] nil)
                codex/merge-base (fn [& _] "BASEREV")
                codex/review! (fn [_] {:status nil :findings [{:title "x"}]
                                       :overall-correctness "incorrect"})]
    (let [ctx ((:run stages/review-stage)
               {:config {:cwd "/w" :base "main" :run-id "r1"} :iter 1})]
      (is (= [{:title "x" :from-layer "stack"}] (:findings ctx))
          "an unstacked branch is one whole-stack target")
      (is (= "incorrect" (:overall-correctness ctx)))
      (is (nil? (:control ctx))))))

(deftest review-stage-clean-diff-stops
  (with-redefs [layers/patch-hash (fn [& _] nil)
                codex/merge-base (fn [& _] "BASEREV")
                codex/review! (fn [_] {:status :clean :findings []})]
    (let [ctx ((:run stages/review-stage)
               {:config {:cwd "/w" :base "main" :run-id "r1"} :iter 1})]
      (is (= :stop (:control ctx)))
      (is (= :clean (:status ctx))))))

(deftest warden-stage-continue
  (with-redefs [agent/launch! (fn [_] {:num-turns 3 :result-error? false
                                       :result-text "```json\n{\"decision\":\"continue\",\"reason\":\"r\",\"findings\":[{\"id\":\"aa11\",\"disposition\":\"fix\"}]}\n```"})
                stages/discover-design-record (fn [_] nil)
                stages/project+ws-from-cwd (fn [_] nil)]
    (let [ctx ((:run stages/warden-stage)
               {:config {:cwd "/w" :run-id "r1"} :iter 1
                :findings [{:id "aa11" :title "x"}]})]
      (is (= :continue (:control ctx)))
      (is (= :fix (-> ctx :findings first :disposition))))))

(deftest warden-prompt-uses-the-design-record-as-its-yardstick
  (let [captured (atom nil)]
    (with-redefs [agent/launch! (fn [{:keys [first-message]}]
                                  (reset! captured first-message)
                                  {:num-turns 1 :result-error? false
                                   :result-text "```json\n{\"decision\":\"stop\",\"reason\":\"r\"}\n```"})
                  stages/discover-design-record
                  (fn [_] {:shape "one rounding boundary at the aggregate"
                           :invariants ["a total is rounded exactly once"]
                           :rejected [{:alternative "round at render time"
                                       :why-not "money math in the view"}]
                           :standing {:relation :conforms}})
                  stages/project+ws-from-cwd (fn [_] [:brian "ws-1"])
                  stages/read-stance (fn [_] "the shape of the data is the design")]
      ((:run stages/warden-stage)
       {:config {:cwd "/w" :run-id "r1"} :iter 1 :findings [{:title "x"}]})
      (let [p @captured]
        (is (str/includes? p "a total is rounded exactly once"))
        (is (str/includes? p "round at render time") "rejected alternatives reach the warden")
        (is (str/includes? p "ANSWERED, not new"))
        (is (str/includes? p "the shape of the data is the design"))
        (is (str/includes? p "never cite it against a specific finding"))))))

(deftest warden-without-a-design-record-is-told-not-to-escalate
  (let [captured (atom nil)]
    (with-redefs [agent/launch! (fn [{:keys [first-message]}]
                                  (reset! captured first-message)
                                  {:num-turns 1 :result-error? false
                                   :result-text "```json\n{\"decision\":\"stop\",\"reason\":\"r\"}\n```"})
                  stages/discover-design-record (fn [_] nil)
                  stages/project+ws-from-cwd (fn [_] nil)]
      ((:run stages/warden-stage)
       {:config {:cwd "/w" :run-id "r1"} :iter 1 :findings [{:title "x"}]})
      (is (str/includes? @captured "do NOT park anything")
          "with no stated invariant there is nothing for a finding to contradict"))))

(deftest warden-stage-noop-is-indeterminate
  (with-redefs [agent/launch! (fn [_] {:num-turns 0 :result-error? false :result-text ""})
                stages/discover-design-record (fn [_] nil)
                stages/project+ws-from-cwd (fn [_] nil)]
    (let [ctx ((:run stages/warden-stage)
               {:config {:cwd "/w" :run-id "r1"} :iter 1 :findings []})]
      (is (= :stop (:control ctx)))
      (is (= :warden-indeterminate (:status ctx))))))

(deftest fix-stage-commits-when-changed
  (let [commits (atom [])]
    (with-redefs [agent/launch! (fn [_] {:num-turns 4 :result-error? false :result-text "done"})
                  stages/working-copy-dirty? (fn [_] true)
                  jj/jj! (fn [_dir & args] (swap! commits conj (vec args))
                           {:exit 0 :out "" :err ""})]
      (let [ctx ((:run stages/fix-stage)
                 {:config {:cwd "/w" :run-id "r1"} :iter 2
                  :findings [{:id "aa11" :title "x" :disposition :fix}]})]
        (is (= 1 (count (:history ctx))))
        (is (some #(= "commit" (first %)) @commits))
        (is (some #(= ["commit" "-m" "review-loop: iter 2 fixes"] %) @commits))
        (is (nil? (:control ctx)))))))

(deftest fix-stage-noop-when-not-dirty
  (with-redefs [agent/launch! (fn [_] {:num-turns 3 :result-error? false :result-text "done"})
                stages/working-copy-dirty? (fn [_] false)
                jj/jj! (fn [& _] {:exit 0 :out "" :err ""})]
    (let [ctx ((:run stages/fix-stage)
               {:config {:cwd "/w" :run-id "r1"} :iter 2
                :findings [{:id "aa11" :title "x" :disposition :fix}]})]
      (is (= :stop (:control ctx)))
      (is (= :fix-noop (:status ctx))))))

(deftest fix-stage-noop-stops
  (with-redefs [agent/launch! (fn [_] {:num-turns 0 :result-error? false :result-text ""})
                stages/working-copy-dirty? (fn [_] false)
                jj/jj! (fn [& _] {:exit 0 :out "" :err ""})]
    (let [ctx ((:run stages/fix-stage)
               {:config {:cwd "/w" :run-id "r1"} :iter 2
                :findings [{:id "aa11" :title "x" :disposition :fix}]})]
      (is (= :stop (:control ctx)))
      (is (= :fix-noop (:status ctx))))))

(deftest fix-stage-dry-run-skips-fix
  (let [launched (atom false)]
    (with-redefs [agent/launch! (fn [_] (reset! launched true) {:num-turns 5 :result-error? false :result-text "x"})
                  stages/working-copy-dirty? (fn [_] true)
                  jj/jj! (fn [& _] {:exit 0 :out "" :err ""})]
      (let [ctx ((:run stages/fix-stage)
                 {:config {:cwd "/w" :run-id "r1" :dry-run? true} :iter 1
                  :findings [{:id "aa11" :title "x" :disposition :fix}]})]
        (is (= :stop (:control ctx)))
        (is (= :dry-run (:status ctx)))
        (is (false? @launched))))))

(deftest apply-rulings-defaults-an-unruled-finding-to-fix
  ;; "Nothing is dropped" has to survive a malformed answer: a finding the
  ;; warden forgot is worked on, not silently discarded.
  (let [out (stages/apply-rulings [{:id "aa11" :title "t"} {:id "bb22" :title "u"}]
                                  [{:id "aa11" :disposition :closed :authority "duplicate"}])]
    (is (= :closed (:disposition (first out))))
    (is (= :fix (:disposition (second out))))
    (is (str/includes? (:because (second out)) "did not rule"))))

(deftest fix-plan-groups-by-owner-and-orders-bottom-to-top
  (let [stack [{:bookmark "s--a" :slug "a"} {:bookmark "s--b" :slug "b"}]
        fs    [{:id "1" :disposition :fix :owner-layer "b"}
               {:id "2" :disposition :fix :owner-layer "a"}
               {:id "3" :disposition :closed :owner-layer "a"}]
        plan  (stages/fix-plan stack fs)]
    (is (= ["a" "b"] (map :label plan)) "bottom-up, so an upper fixer works against settled code")
    (is (= ["2"] (map :id (:findings (first plan)))) "a closed finding is not handed to a fixer")))

(deftest fix-plan-sends-an-unplaceable-owner-to-the-top-layer
  (let [stack [{:bookmark "s--a" :slug "a"} {:bookmark "s--b" :slug "b"}]
        plan  (stages/fix-plan stack [{:id "1" :disposition :fix :owner-layer "nope"}])]
    (is (= ["b"] (map :label plan)))))

(deftest layer-fixer-sessions-differ-per-layer-and-are-stable
  ;; One session per layer, never one across layers: a resumed fixer would carry
  ;; one layer's context into another.
  (is (= (stages/layer-fixer-session "impl-1" "a") (stages/layer-fixer-session "impl-1" "a")))
  (is (not= (stages/layer-fixer-session "impl-1" "a") (stages/layer-fixer-session "impl-1" "b"))))

(deftest review-stage-surfaces-base-rev-and-manifest
  (with-redefs [layers/patch-hash (fn [& _] nil)
                codex/merge-base (fn [& _] "BASEREV")
                codex/review! (fn [_] {:status nil :findings [{:title "x"}]
                                       :overall-correctness "incorrect"
                                       :base-rev "BASE" :manifest "src/a.clj\nsrc/b.clj"})]
    (let [ctx ((:run stages/review-stage)
               {:config {:cwd "/w" :base "main" :run-id "r1"} :iter 1})]
      (is (= "BASE" (:base-rev ctx)))
      (is (= "src/a.clj\nsrc/b.clj" (:manifest ctx))))))

(deftest fix-stage-records-implementer-session-first-round
  (let [seen (atom nil)]
    (with-redefs [agent/launch! (fn [opts] (reset! seen opts)
                                  {:num-turns 4 :result-error? false :result-text "done"})
                  stages/working-copy-dirty? (fn [_] true)
                  jj/jj! (fn [& _] {:out "cid-1" :err "" :exit 0})]
      ((:run stages/fix-stage)
       {:config {:cwd "/w" :run-id "r1" :impl-session-id "impl-1"} :iter 1
        :findings [{:id "aa11" :title "x" :disposition :fix}]})
      (is (= (stages/layer-fixer-session "impl-1" nil) (:claude-session-id @seen))
          "records under this layer's own fixer session")
      (is (false? (:resume? @seen)) "first round (empty history) records, does not resume"))))

(deftest fix-stage-resumes-implementer-session-later-rounds
  (let [seen (atom nil)]
    (with-redefs [agent/launch! (fn [opts] (reset! seen opts)
                                  {:num-turns 4 :result-error? false :result-text "done"})
                  stages/working-copy-dirty? (fn [_] true)
                  jj/jj! (fn [& _] {:out "cid-2" :err "" :exit 0})]
      ((:run stages/fix-stage)
       {:config {:cwd "/w" :run-id "r1" :impl-session-id "impl-1"} :iter 2
        :history [{:iter 1 :fixes [{:layer nil :commit "cid-1"}]}]
        :findings [{:id "aa11" :title "x" :disposition :fix}]})
      (is (= (stages/layer-fixer-session "impl-1" nil) (:claude-session-id @seen))
          "resumes this layer's own fixer session")
      (is (true? (:resume? @seen)) "a layer fixed in an earlier round resumes"))))

(deftest warden-stage-launches-report-only
  (let [seen (atom nil)]
    (with-redefs [agent/launch! (fn [opts] (reset! seen opts)
                                  {:num-turns 3 :result-error? false
                                   :result-text "```json\n{\"decision\":\"stop\"}\n```"})]
      ((:run stages/warden-stage)
       {:config {:cwd "/w" :run-id "r1"} :iter 1 :findings [{:title "x"}]})
      (is (= "" (:tools @seen)) "warden launches with tools disabled (report-only)"))))

(deftest review-stage-passes-iter-to-codex
  (let [seen (atom nil)]
    (with-redefs [layers/patch-hash (fn [& _] nil)
                  codex/merge-base (fn [& _] "BASEREV")
                  codex/review! (fn [opts] (reset! seen opts)
                                  {:status :clean :findings []})]
      ((:run stages/review-stage) {:config {:cwd "/w" :base "main" :run-id "r1"} :iter 3})
      (is (= 3 (:iter @seen)) "review! is told which round it is (for the log name)"))))

(deftest review-stage-aims-the-review-at-the-merge-base-not-the-tip-of-base
  ;; review! is aimed by its caller now; the caller must still resolve the fork
  ;; point, or main's parallel work reappears as spurious deletions.
  (let [seen (atom nil)]
    (with-redefs [layers/patch-hash (fn [& _] nil)
                  codex/merge-base (fn [_cwd base] (str "FORK-OF-" base))
                  codex/review!    (fn [opts] (reset! seen opts)
                                     {:status nil :findings []})]
      ((:run stages/review-stage) {:config {:cwd "/w" :base "main" :run-id "r1"} :iter 1})
      (is (= "FORK-OF-main" (:from @seen)))
      (is (= "@" (:to @seen))))))

;; ---- fan-out: every layer plus the whole stack ---------------------------

(deftest in-parallel-preserves-order
  (is (= [1 2 3 4 5] (stages/in-parallel 2 (map (fn [n] #(do (Thread/sleep (- 20 (* 3 n))) n))
                                                [1 2 3 4 5])))))

(deftest in-parallel-propagates-the-original-ex-data
  ;; A bare future deref wraps in ExecutionException, which would hide the
  ;; :review-failed reason the engine branches on.
  (is (= :review-failed
         (try (stages/in-parallel 2 [#(throw (ex-info "boom" {:reason :review-failed}))
                                     (fn [] :ok)])
              nil
              (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))))

(deftest review-targets-cover-each-layer-and-the-whole-stack
  (with-redefs [layers/patch-hash (fn [& _] nil)
                codex/merge-base    (fn [& _] "FORK")
                codex/changed-files  (fn [& _] [])
                stages/session-stack (fn [& _] [{:bookmark "s--a" :slug "a" :tip "cA"}
                                                {:bookmark "s--b" :slug "b" :tip "cB"}])
                layers/brief         (fn [_ rev] {:claims (str "claim of " rev)})]
    (let [ts (stages/review-targets "/w" "main")]
      (is (= ["a" "b" "stack"] (map :label ts)))
      (is (= [["FORK" "cA"] ["cA" "cB"] ["FORK" "@"]] (map (juxt :from :to) ts)))
      (is (= "claim of cA" (:claims (:brief (first ts)))) "each layer carries its own brief")
      (is (nil? (:brief (last ts))) "the whole-stack pass is deliberately unbounded")
      (is (= [1 2] (map :index (butlast ts)))
          "a layer is numbered by its place in the stack, bottom→top")
      (is (nil? (:index (last ts)))
          "the composition pass is not a layer and carries no number"))))

(deftest review-targets-skip-the-stack-pass-below-two-layers
  ;; A composition defect needs two layers to compose. With one layer the stack
  ;; pass is the same diff twice.
  (with-redefs [layers/patch-hash (fn [& _] nil)
                codex/merge-base     (fn [& _] "FORK")
                stages/session-stack (fn [& _] [{:bookmark "s" :slug nil :tip "cA"}])
                layers/brief         (fn [& _] nil)]
    (is (= ["stack"] (map :label (stages/review-targets "/w" "main")))))
  (with-redefs [layers/patch-hash (fn [& _] nil)
                codex/merge-base     (fn [& _] "FORK")
                stages/session-stack (fn [& _] [])]
    (is (= ["stack"] (map :label (stages/review-targets "/w" "main"))))))

(deftest review-stage-stamps-each-finding-with-the-layer-that-reported-it
  (with-redefs [layers/patch-hash (fn [& _] nil)
                codex/merge-base     (fn [& _] "FORK")
                stages/session-stack (fn [& _] [{:bookmark "s--a" :slug "a" :tip "cA"}
                                                {:bookmark "s--b" :slug "b" :tip "cB"}])
                layers/brief         (fn [& _] nil)
                codex/review!        (fn [{:keys [label]}]
                                       {:status nil
                                        :findings [{:title (str "f-" label) :file "x.clj"
                                                    :line-start 1}]})]
    (let [ctx ((:run stages/review-stage) {:config {:cwd "/w" :base "main" :run-id "r"} :iter 1})]
      (is (= #{"a" "b" "stack"} (set (map :from-layer (:findings ctx))))))))

(deftest review-stage-drops-a-finding-two-targets-report-identically
  (with-redefs [layers/patch-hash (fn [& _] nil)
                codex/merge-base     (fn [& _] "FORK")
                stages/session-stack (fn [& _] [{:bookmark "s--a" :slug "a" :tip "cA"}
                                                {:bookmark "s--b" :slug "b" :tip "cB"}])
                layers/brief         (fn [& _] nil)
                codex/review!        (fn [_] {:status nil
                                              :findings [{:title "same" :file "x.clj"
                                                          :line-start 7}]})]
    (let [ctx ((:run stages/review-stage) {:config {:cwd "/w" :base "main" :run-id "r"} :iter 1})]
      (is (= 1 (count (:findings ctx))))
      (is (= "a" (:from-layer (first (:findings ctx))))
          "the layer reviewer's copy wins over the whole-stack copy"))))

(deftest review-stage-is-clean-only-when-no-target-found-anything
  (with-redefs [layers/patch-hash (fn [& _] nil)
                codex/merge-base     (fn [& _] "FORK")
                stages/session-stack (fn [& _] [])
                codex/review!        (fn [_] {:status :clean :findings []})]
    (let [ctx ((:run stages/review-stage) {:config {:cwd "/w" :base "main" :run-id "r"} :iter 1})]
      (is (= :clean (:status ctx)))
      (is (= :stop (:control ctx))))))

;; ---- convergence ---------------------------------------------------------

(deftest to-review-skips-only-a-target-whose-exact-patch-converged
  (let [cached {"h-a" {:status :converged}}
        ts     [{:label "a" :patch-hash "h-a"} {:label "b" :patch-hash "h-b"}]
        {:keys [review skipped]} (stages/to-review cached ts)]
    (is (= ["b"] (map :label review)))
    (is (= ["a"] (map :label skipped)))))

(deftest to-review-always-reviews-a-target-with-no-hash
  ;; jj could not produce the diff. Unknown content is reviewed content.
  (let [{:keys [review]} (stages/to-review {"h" {:status :converged}}
                                           [{:label "a" :patch-hash nil}])]
    (is (= ["a"] (map :label review)))))

(deftest converged-targets-turn-on-ownership-not-on-who-reported
  (let [reviews [{:target {:label "a" :patch-hash "h-a"}}
                 {:target {:label "b" :patch-hash "h-b"}}
                 {:target {:label "stack" :patch-hash "h-s" :stack? true}}]
        ;; reported by b, owned by a
        findings [{:disposition :fix :from-layer "b" :owner-layer "a"}]]
    (is (= ["b"] (map :label (stages/converged-targets reviews findings)))
        "a owns the fix so it is not converged; b needs no change so it is")))

(deftest converged-targets-include-the-stack-only-when-nothing-needs-fixing
  (let [reviews [{:target {:label "stack" :patch-hash "h-s" :stack? true}}]]
    (is (= ["stack"] (map :label (stages/converged-targets reviews []))))
    (is (= [] (stages/converged-targets
               reviews [{:disposition :fix :owner-layer "a"}])))))

(deftest answered-for-carries-only-what-that-target-reported-and-lost
  (is (= ["aa11"]
         (map :id (stages/answered-for
                   "a" [{:id "aa11" :from-layer "a" :disposition :closed :authority "design"}
                        {:id "bb22" :from-layer "a" :disposition :fix}
                        {:id "cc33" :from-layer "b" :disposition :closed}])))))

(deftest answered-by-layer-reads-each-layers-answers-under-its-own-patch-hash
  ;; They hang off the layer's patch, so a layer whose content changed has no
  ;; hit and contributes nothing — the answers were about THAT content.
  (let [ctx {:cache   {"h-a" {:answered [{:id "aa11" :title "t" :authority "design"}]}
                       "h-b" {:answered []}}
             :reviews [{:target {:label "a" :patch-hash "h-a"}}
                       {:target {:label "b" :patch-hash "h-b"}}
                       {:target {:label "c" :patch-hash "h-moved"}}]}]
    (is (= [{:label "a" :answered [{:id "aa11" :title "t" :authority "design"}]}]
           (stages/answered-by-layer ctx))
        "a layer with nothing answered is dropped, not carried as an empty row")))

;; ---- announcing the round's targets before it starts ---------------------

(deftest announce-targets-publishes-every-target-before-any-agent-runs
  (let [seen (atom [])
        ctx  {:iter 1 :config {:cwd "/definitely/not/a/workspace"
                               :emit #(swap! seen conj %)}}]
    (stages/announce-targets!
     ctx {:review  [{:label "lower" :from "BASE" :to "L"}
                    {:label "stack" :from "BASE" :to "@" :stack? true}]
          :skipped [{:label "upper" :from "L" :to "U"}]})
    (let [ev (first @seen)]
      (is (= :targets-resolved (:event ev)))
      (is (= "BASE" (:base-rev ev)) "base-rev is the stack target's fork point")
      (is (= ["lower" "stack" "upper"] (mapv :label (:targets ev))))
      (is (= ["pending" "pending" "skipped"] (mapv :status (:targets ev))))
      (is (= [false true false] (mapv :stack? (:targets ev))))
      (is (= [] (:files ev))
          "an unresolvable cwd yields no file list rather than taking the run down"))))

(deftest announce-targets-is-inert-without-an-emit
  ;; run-loop defaults :emit to a no-op and tests inject their own; a stage that
  ;; assumed one was present would break every caller that does not care.
  (is (nil? (stages/announce-targets! {:iter 1 :config {:cwd "/tmp"}}
                                      {:review [] :skipped []}))))

(deftest fix-stage-returns-the-working-copy-to-the-top-when-a-layer-dies
  ;; A fix inserts onto its own layer, so a plan that dies part-way through
  ;; leaves `@` inside the stack — and from there `<base>..@` no longer spans
  ;; the branch. The next run would review a truncated stack and say nothing.
  (let [stack     [{:bookmark "sess--lower" :slug "lower" :tip "c1"}
                   {:bookmark "sess--top" :slug "top" :tip "c2"}]
        restored  (atom [])]
    (with-redefs [stages/session-stack      (fn [& _] stack)
                  layers/position-for-fix!  (fn [_ layer]
                                              (throw (ex-info (str "cannot position " (:bookmark layer))
                                                              {:reason :review-failed})))
                  layers/restore-top!       (fn [_ s] (swap! restored conj (:bookmark (last s))))
                  agent/launch!             (fn [_] {:num-turns 1})]
      (let [e (is (thrown? clojure.lang.ExceptionInfo
                           ((:run stages/fix-stage)
                            {:config {:cwd "/w" :run-id "r1" :impl-session-id "impl-1"} :iter 1
                             :findings [{:id "aa11" :disposition :fix :owner-layer "lower"}]})))]
        (is (str/includes? (ex-message e) "cannot position sess--lower")
            "the original diagnosis reaches the caller, not the restore's")
        (is (= ["sess--top"] @restored)
            "the working copy is put back on the top layer before the failure propagates")))))

(deftest fix-stage-restore-failure-never-masks-the-original-diagnosis
  (with-redefs [stages/session-stack     (fn [& _] [{:bookmark "sess--top" :slug "top" :tip "c1"}])
                layers/position-for-fix! (fn [& _] (throw (ex-info "the real problem" {:reason :review-failed})))
                layers/restore-top!      (fn [& _] (throw (ex-info "restore also failed" {})))
                agent/launch!            (fn [_] {:num-turns 1})]
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         ((:run stages/fix-stage)
                          {:config {:cwd "/w" :run-id "r1" :impl-session-id "impl-1"} :iter 1
                           :findings [{:id "aa11" :disposition :fix :owner-layer "top"}]})))]
      (is (= "the real problem" (ex-message e))))))


(deftest review-targets-prime-the-stack-pass-with-the-layers-and-their-revisions
  ;; Without :composition the whole-stack target is the flat-branch reviewer
  ;; pointed at the whole branch and never told a stack exists — so it
  ;; re-derives every layer it was supposed to trust, which is the exact cost
  ;; the layering was built to avoid.
  (with-redefs [layers/patch-hash    (fn [& _] nil)
                codex/merge-base     (fn [& _] "FORK")
                codex/changed-files  (fn [_ from _to] [(str "touched-since-" from)])
                stages/session-stack (fn [& _] [{:bookmark "s--a" :slug "a" :tip "cA"}
                                                {:bookmark "s--b" :slug "b" :tip "cB"}])
                layers/brief         (fn [_ rev] {:claims       (str "claim of " rev)
                                                  :out-of-scope (str "not " rev)})]
    (let [ls (:layers (:composition (last (stages/review-targets "/w" "main"))))]
      (is (= ["a" "b"] (mapv :label ls)) "in stack order, bottom→top")
      (is (= [["FORK" "cA"] ["cA" "cB"]] (mapv (juxt :from :tip) ls))
          "each layer's own range and the tree its PR would merge")
      (is (= ["claim of cA" "claim of cB"] (mapv :claim ls)))
      (is (= ["not cA" "not cB"] (mapv :out-of-scope ls)))
      (is (= [["touched-since-FORK"] ["touched-since-cA"]] (mapv :files ls))))))

(deftest review-targets-carry-no-composition-below-two-layers
  ;; With nothing to compose the whole-stack target IS the branch review.
  (with-redefs [layers/patch-hash    (fn [& _] nil)
                codex/merge-base     (fn [& _] "FORK")
                codex/changed-files  (fn [& _] [])
                stages/session-stack (fn [& _] [{:bookmark "s" :slug nil :tip "cA"}])
                layers/brief         (fn [& _] nil)]
    (is (nil? (:composition (first (stages/review-targets "/w" "main")))))))

(deftest review-stage-hands-the-stack-pass-its-composition
  (let [seen (atom {})]
    (with-redefs [layers/patch-hash    (fn [& _] nil)
                  codex/merge-base     (fn [& _] "FORK")
                  codex/changed-files  (fn [& _] [])
                  stages/session-stack (fn [& _] [{:bookmark "s--a" :slug "a" :tip "cA"}
                                                  {:bookmark "s--b" :slug "b" :tip "cB"}])
                  layers/brief         (fn [& _] {:claims "c"})
                  codex/review!        (fn [{:keys [label composition]}]
                                         (swap! seen assoc label (boolean composition))
                                         {:findings [] :manifest "m" :base-rev "FORK"})]
      ((:run stages/review-stage) {:config {:cwd "/w" :base "main" :run-id "r"} :iter 1})
      (is (= {"a" false "b" false "stack" true} @seen)
          "only the pass that composes layers is primed to compose them"))))
