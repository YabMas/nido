;; test/nido/review/stages_test.clj
(ns nido.review.stages-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.agent :as agent]
   [nido.platform.core :as core]
   [nido.review.cache :as cache]
   [nido.review.codex :as codex]
   [nido.review.conformance :as conformance]
   [nido.review.layers :as layers]
   [nido.review.prompts :as prompts]
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
    (is (= [{:id "aa11" :same-as nil :owner-layer "drop-legacy" :disposition :fix
             :authority nil :of nil :sweep false :because "real"}]
           (:rulings d))
        "sweep defaults false — a ruling that does not claim a class is not one")))

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

(deftest a-warden-that-never-ran-says-so-rather-than-blaming-the-json
  ;; Observed: a run hit `You've hit your session limit` (HTTP 429), the agent
  ;; produced one error turn, and the report recorded `no json decision block` —
  ;; a complaint about a block that was never going to exist, with the only
  ;; trace of the 429 in agent.log.
  (let [parsed (stages/parse-warden-decision "You've hit your session limit · resets 1:50pm")]
    (is (= {:cause :launch-failed
            :reason "You've hit your session limit · resets 1:50pm"}
           (stages/warden-failure {:num-turns 1 :result-error? true
                                   :result-text "You've hit your session limit · resets 1:50pm"}
                                  parsed)))
    (is (= :no-answer
           (:cause (stages/warden-failure {:num-turns 0 :result-error? false} parsed)))
        "and a silent agent is a third thing again")
    (is (= {:cause :unusable-answer :reason "no json decision block"}
           (stages/warden-failure {:num-turns 3 :result-error? false} parsed))
        "only an answer that came back and would not parse blames the json")))

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

(deftest review-stage-merges-the-mechanical-design-reviewer-into-the-round
  (testing "a design violation is an ordinary finding from the fan-out on: it gets a handle, an
            owner layer, a disposition and a fixer, and the convergence machinery can then see a
            violation the loop is failing to shift"
    (with-redefs [layers/patch-hash (fn [& _] nil)
                  codex/merge-base (fn [& _] "BASEREV")
                  codex/review! (fn [_] {:status nil :findings [{:title "x"}]})
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  cache/read-cache (fn [& _] {})
                  conformance/findings (fn [& _] [{:title "design: no undeclared edge" :id "d1"}])]
      (let [ctx ((:run stages/review-stage)
                 {:config {:cwd "/w" :base "main" :run-id "r1"} :iter 1})]
        (is (= #{"x" "design: no undeclared edge"} (set (map :title (:findings ctx)))))
        (is (= "design" (:from-layer (first (filter #(= "d1" (:id %)) (:findings ctx)))))))))

  (testing "and a broken design alone keeps the round going — a clean diff over a tree that no
            longer obeys its own design is not a clean round"
    (with-redefs [layers/patch-hash (fn [& _] nil)
                  codex/merge-base (fn [& _] "BASEREV")
                  codex/review! (fn [_] {:status :clean :findings []})
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  cache/read-cache (fn [& _] {})
                  conformance/findings (fn [& _] [{:title "design: no undeclared edge" :id "d1"}])]
      (let [ctx ((:run stages/review-stage)
                 {:config {:cwd "/w" :base "main" :run-id "r1"} :iter 1})]
        (is (nil? (:control ctx)))
        (is (= 1 (count (:findings ctx)))))))

  (testing "a project declaring no design leaves the round exactly as it was"
    (with-redefs [layers/patch-hash (fn [& _] nil)
                  codex/merge-base (fn [& _] "BASEREV")
                  codex/review! (fn [_] {:status :clean :findings []})
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  cache/read-cache (fn [& _] {})
                  conformance/findings (fn [& _] [])]
      ;; Second round: this branch is flat, and a flat branch earns clean by
      ;; being quiet twice — see a-flat-branch-earns-clean-by-being-quiet-twice.
      (let [ctx ((:run stages/review-stage)
                 {:config {:cwd "/w" :base "main" :run-id "r1"} :iter 2
                  :carry {:quiet-once true}})]
        (is (= :stop (:control ctx)))
        (is (= :clean (:status ctx)))))))

(deftest review-stage-clean-diff-stops
  (with-redefs [layers/patch-hash (fn [& _] nil)
                codex/merge-base (fn [& _] "BASEREV")
                codex/review! (fn [_] {:status :clean :findings []})]
    ;; Flat branch, second quiet round — one pass over a whole diff is a sample.
    (let [ctx ((:run stages/review-stage)
               {:config {:cwd "/w" :base "main" :run-id "r1"} :iter 2
                :carry {:quiet-once true}})]
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

(defn- jj-with-conflicts
  "A jj stub whose `conflicts()` revset names `ids`. Everything else succeeds
   silently, which is what every other fix-stage test already assumes."
  [ids]
  (fn [_dir & args]
    (if (some #(str/includes? (str %) "conflicts()") args)
      {:exit 0 :out (str/join "\n" ids) :err ""}
      {:exit 0 :out "" :err ""})))

(deftest a-fix-that-conflicts-the-stack-stops-the-round-and-names-it
  ;; A fix lands by rewriting its layer, so jj rebases every layer above it, and
  ;; nothing asked whether that came out clean. The markers rode up in committed
  ;; text and were found a round later by a reviewer reading a namespace that no
  ;; longer parsed.
  (with-redefs [agent/launch! (fn [_] {:num-turns 4 :result-error? false :result-text "done"})
                stages/working-copy-dirty? (fn [_] true)
                jj/jj! (jj-with-conflicts ["xuspsuww" "b4927669"])]
    (let [ctx ((:run stages/fix-stage)
               {:config {:cwd "/w" :run-id "r1" :base "main"} :iter 2
                :findings [{:id "aa11" :title "x" :disposition :fix}]})]
      (is (= :stop (:control ctx)))
      (is (= :fix-conflicted (:status ctx)))
      (is (= ["xuspsuww" "b4927669"] (:conflicted ctx))
          "the change ids, because the conflict is mid-stack and `jj resolve
           --list` reports the branch clean")
      (is (= 1 (count (:history ctx)))
          "the fix that landed is still recorded — it is a rebase a human
           resolves, not a round to throw away"))))

(deftest a-clean-rebase-after-a-fix-does-not-stop-the-round
  (with-redefs [agent/launch! (fn [_] {:num-turns 4 :result-error? false :result-text "done"})
                stages/working-copy-dirty? (fn [_] true)
                jj/jj! (jj-with-conflicts [])]
    (let [ctx ((:run stages/fix-stage)
               {:config {:cwd "/w" :run-id "r1" :base "main"} :iter 2
                :findings [{:id "aa11" :title "x" :disposition :fix}]})]
      (is (nil? (:control ctx)))
      (is (nil? (:conflicted ctx))))))

(deftest a-fixer-that-read-the-finding-and-refused-says-why
  ;; It ran, it decided, and its reason was the only account of why the round
  ;; did nothing. Discarded, the run ended on "no changes" with the explanation
  ;; stated on no channel at all.
  (with-redefs [agent/launch! (fn [_] {:num-turns 3 :result-error? false
                                       :result-text "the seam spans two layers; no minimal edit here is right"})
                stages/working-copy-dirty? (fn [_] false)
                jj/jj! (fn [& _] {:exit 0 :out "" :err ""})]
    (let [ctx ((:run stages/fix-stage)
               {:config {:cwd "/w" :run-id "r1"} :iter 2
                :findings [{:id "aa11" :title "x" :disposition :fix}]})
          [d] (:declined ctx)]
      (is (= :stop (:control ctx)))
      (is (= :fix-declined (:status ctx)))
      (is (true? (:ran? d)) "it ran")
      (is (str/includes? (:reason d) "spans two layers")))))

(deftest a-fixer-that-never-ran-is-not-a-fixer-that-refused
  ;; Zero turns: the agent never got going. Same empty tree, a different fact
  ;; about the loop, and one status for both told a reader neither.
  (with-redefs [agent/launch! (fn [_] {:num-turns 0 :result-error? false :result-text ""})
                stages/working-copy-dirty? (fn [_] false)
                jj/jj! (fn [& _] {:exit 0 :out "" :err ""})]
    (let [ctx ((:run stages/fix-stage)
               {:config {:cwd "/w" :run-id "r1"} :iter 2
                :findings [{:id "aa11" :title "x" :disposition :fix}]})
          [d] (:declined ctx)]
      (is (= :fix-declined (:status ctx)))
      (is (false? (:ran? d)) "it never ran"))))

(deftest nothing-routed-to-a-fixable-layer-is-its-own-status
  ;; No finding was owed to any layer, so no fixer was launched. Distinct from a
  ;; fixer declining: this one is a routing question, not a refusal.
  (with-redefs [agent/launch! (fn [_] (throw (ex-info "no fixer should launch" {})))
                stages/working-copy-dirty? (fn [_] false)
                jj/jj! (fn [& _] {:exit 0 :out "" :err ""})]
    (let [ctx ((:run stages/fix-stage)
               {:config {:cwd "/w" :run-id "r1"} :iter 2
                :findings [{:id "aa11" :title "x" :disposition :park}]})]
      (is (= :stop (:control ctx)))
      (is (= :fix-unrouted (:status ctx))))))

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

(deftest every-disposition-the-warden-is-offered-is-one-the-parser-accepts
  ;; The accepted half of the contract. A word offered to the warden that the
  ;; parser silently rewrites to :fix is a destination nobody can reach.
  (doseq [{:keys [disposition]} prompts/disposition-vocabulary]
    (let [d (stages/parse-warden-decision
             (str "```json\n{\"decision\":\"continue\",\"findings\":"
                  "[{\"id\":\"aa11\",\"disposition\":\"" (name disposition) "\"}]}\n```"))]
      (is (= disposition (:disposition (first (:rulings d))))
          (str (name disposition) " survives the parser")))))

(deftest apply-rulings-defaults-an-unruled-finding-to-fix
  ;; "Nothing is dropped" has to survive a malformed answer: a finding the
  ;; warden forgot is worked on, not silently discarded.
  (let [out (stages/apply-rulings [{:id "aa11" :title "t"} {:id "bb22" :title "u"}]
                                  [{:id "aa11" :disposition :closed :authority "duplicate"}]
                                  {})]
    (is (= :closed (:disposition (first out))))
    (is (= :fix (:disposition (second out))))
    (is (str/includes? (:because (second out)) "did not rule"))
    (is (= ["aa11" "bb22"] (map :handle out))
        "a finding the warden did not file keeps its own id as its handle")))

(deftest a-finding-the-warden-calls-a-restatement-is-filed-under-the-original
  ;; The whole point: a reviewer's new words must not produce a new identity.
  (let [round2 (stages/apply-rulings
                [{:id "new1" :title "Put the removal below the enablement"}]
                [{:id "new1" :same-as "old1" :disposition :park}]
                {"old1" "old1"})]
    (is (= ["old1"] (map :handle round2)))))

(deftest a-chain-of-restatements-collapses-onto-the-first-raising
  ;; Not onto its immediate predecessor: a defect restated in every round of a
  ;; long run has to stay one handle, or a stall check never sees a repeat.
  (let [round3 (stages/apply-rulings
                [{:id "new2" :title "Move the cleanup below the dropdown"}]
                [{:id "new2" :same-as "new1" :disposition :park}]
                {"old1" "old1" "new1" "old1"})]
    (is (= ["old1"] (map :handle round3)))))

(deftest a-same-as-naming-an-id-this-run-never-issued-is-refused
  (let [out (stages/apply-rulings [{:id "new3" :title "t"}]
                                  [{:id "new3" :same-as "hallucinated" :disposition :fix}]
                                  {"old1" "old1"})]
    (is (= ["new3"] (map :handle out))
        "an invented link welds two defects into one; its own id is the safe answer")))

(deftest seen-findings-lists-each-earlier-finding-once-at-the-round-it-arrived
  (is (= [{:round 1 :id "aa11" :title "t"}
          {:round 2 :id "bb22" :title "u"}]
         (stages/seen-findings
          [{:iter 1 :findings [{:id "aa11" :title "t"}]}
           {:iter 2 :findings [{:id "aa11" :title "t"} {:id "bb22" :title "u"}]}]))))

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

(deftest a-flat-branch-earns-clean-by-being-quiet-twice
  ;; One whole-diff pass over an unlayered branch is a sample, not a verdict:
  ;; the round that missed a change's only P1 found one of three pre-existing
  ;; defects and reported clean. A layered stack has several independent
  ;; reviewers over the same code and needs no second look; a 0-layer target has
  ;; nothing to cross-check it.
  (with-redefs [layers/patch-hash (fn [& _] nil)
                codex/merge-base     (fn [& _] "FORK")
                stages/session-stack (fn [& _] [])
                codex/review!        (fn [_] {:status :clean :findings []})]
    (let [first-pass  ((:run stages/review-stage)
                       {:config {:cwd "/w" :base "main" :run-id "r"} :iter 1})
          second-pass ((:run stages/review-stage)
                       {:config {:cwd "/w" :base "main" :run-id "r"} :iter 2
                        :carry (:carry first-pass)})]
      (is (nil? (:status first-pass)))
      (is (= :continue (:control first-pass)) "one quiet round is not a verdict")
      (is (= :clean (:status second-pass)))
      (is (= :stop (:control second-pass))))))

(deftest a-layered-stack-is-clean-on-one-quiet-round
  ;; The second look is bought by the layer reviewers, not by a second round.
  (with-redefs [layers/patch-hash (fn [& _] nil)
                codex/merge-base     (fn [& _] "FORK")
                stages/session-stack (fn [& _] [{:bookmark "s--a" :slug "a" :tip "cA"}
                                                {:bookmark "s--b" :slug "b" :tip "cB"}])
                layers/brief         (fn [& _] nil)
                codex/changed-files  (fn [& _] [])
                codex/review!        (fn [_] {:status :clean :findings []})]
    (let [ctx ((:run stages/review-stage)
               {:config {:cwd "/w" :base "main" :run-id "r"} :iter 1})]
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

(deftest converged-targets-hold-a-target-whose-finding-was-not-closed
  ;; The disposition that is neither :fix nor :closed is what this turns on.
  ;; Nobody was handed the finding, so nothing about the target will change —
  ;; and recording it converged writes the patch into an append-only store, so a
  ;; later run at the same content skips the target and the finding is gone.
  (let [reviews [{:target {:label "a" :patch-hash "h-a"}}
                 {:target {:label "b" :patch-hash "h-b"}}
                 {:target {:label "stack" :patch-hash "h-s" :stack? true}}]]
    (is (= ["b"] (map :label (stages/converged-targets
                              reviews [{:disposition :park :owner-layer "a"}])))
        "a parked finding leaves its owner open, and the stack with it")
    (is (= ["a" "b" "stack"]
           (map :label (stages/converged-targets
                        reviews [{:disposition :deviation :owner-layer "a"}])))
        "a deviation is a decision, so it settles its target")
    (is (= ["b"] (map :label (stages/converged-targets
                              reviews [{:disposition :declined :owner-layer "a"}
                                       {:disposition :park :owner-layer "a"}])))
        "and a decline settles, so only the park is still holding a")
    (is (= ["a" "b" "stack"]
           (map :label (stages/converged-targets
                        reviews [{:disposition :closed :owner-layer "a"}])))
        "a close is a decision by a named authority, so its target converges")
    (is (= ["a" "b" "stack"]
           (map :label (stages/converged-targets
                        reviews [{:disposition :declined :owner-layer "a"}])))
        "so is a decline: the finding is true and we said we are leaving it")))

(deftest converged-targets-hold-the-stack-for-a-finding-that-names-no-layer
  ;; The warden may rule on a finding without giving it an owner. It then names
  ;; no layer, so it blocks none of them — the whole-stack target is the only
  ;; thing holding it, which is why that target turns on `nothing is open`
  ;; rather than on ownership.
  (let [reviews [{:target {:label "a" :patch-hash "h-a"}}
                 {:target {:label "stack" :patch-hash "h-s" :stack? true}}]]
    (is (= ["a"] (map :label (stages/converged-targets
                              reviews [{:disposition :park :owner-layer nil}]))))))

(deftest answered-for-carries-only-what-that-target-reported-and-lost
  (is (= ["aa11"]
         (map :id (stages/answered-for
                   "a" [{:id "aa11" :from-layer "a" :disposition :closed :authority "design"}
                        {:id "bb22" :from-layer "a" :disposition :fix}
                        {:id "cc33" :from-layer "b" :disposition :closed}])))))

(deftest answered-for-carries-every-decision-not-only-a-close
  ;; A decline re-argued every round is not a decision: the reviewer has no
  ;; memory, so the reason given the first time never reaches the round that
  ;; needs it. A park is NOT carried — nothing was decided about it.
  (let [out (stages/answered-for
             "a" [{:id "aa11" :from-layer "a" :disposition :declined
                   :because "true, and this branch is leaving it"}
                  {:id "bb22" :from-layer "a" :disposition :deviation :of "the claim"}
                  {:id "cc33" :from-layer "a" :disposition :park}
                  {:id "dd44" :from-layer "a" :disposition :fix}])]
    (is (= ["aa11" "bb22"] (map :id out)))
    (is (= [:declined :deviation] (map :disposition out))
        "the disposition rides along so the next warden knows what kind of answer it is")))

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

;; ---- reshaping the stack when the remedy is its shape ---------------------

(def ^:private two-layer-stack
  [{:bookmark "s--lower" :slug "lower"} {:bookmark "s--upper" :slug "upper"}])

(def ^:private gapped-stack
  [{:bookmark "s--a" :slug "a"} {:bookmark "s--b" :slug "b"}
   {:bookmark "s--c" :slug "c"} {:bookmark "s--d" :slug "d"}])

(deftest an-order-dependence-plans-the-upper-layer-below-the-lower
  ;; `across` is in stack order, so the upper layer is the one reaching for
  ;; something the lower does not supply — moving it down is the repair.
  (is (= {:remedy :reorder :lower (first two-layer-stack) :upper (second two-layer-stack)
          :fold-legal? true}
         (stages/reshape-plan two-layer-stack
                              {:kind :order-dependence :layers ["lower" "upper"]}))))

(deftest the-span-is-read-in-stack-order-not-the-order-it-was-written
  ;; The reviewer writes the list; the stack decides which end is which. Trusting
  ;; the written order puts `lower` above `upper` and reorders the stack the
  ;; wrong way, on a finding that was right.
  (is (= (stages/reshape-plan two-layer-stack
                              {:kind :order-dependence :layers ["upper" "lower"]})
         (stages/reshape-plan two-layer-stack
                              {:kind :order-dependence :layers ["lower" "upper"]}))))

(deftest a-seam-or-a-duplication-plans-a-fold
  (doseq [k [:misplaced-seam :duplicated-across-layers]]
    (is (= :fold (:remedy (stages/reshape-plan two-layer-stack
                                               {:kind k :layers ["lower" "upper"]})))
        (str (name k) " has no order to correct — the boundary is the defect"))))

(deftest a-fold-refuses-a-span-with-holes
  ;; A fold removes every boundary it spans, so an unnamed layer in between is
  ;; absorbed with them. Attempted across a nine-layer stack it is a squash jj
  ;; can only answer with a conflict, and the round's one attempt is gone.
  (let [p (stages/reshape-plan gapped-stack
                               {:kind :misplaced-seam :layers ["a" "c" "d"]})]
    (is (= :span-has-holes (:refused p)))
    (is (re-find #"absorb b" (:because p)) "and it names what would have been absorbed"))
  (is (= :fold (:remedy (stages/reshape-plan gapped-stack
                                             {:kind :misplaced-seam :layers ["b" "c"]})))
      "adjacent layers still fold — the boundary between them is the only one lost"))

(deftest a-seam-across-a-gapped-span-is-moved-rather-than-refused
  ;; The case: an upper layer rewrote a migration whose checksum a lower layer's
  ;; deploy had already recorded. Folding them would absorb seven layers neither
  ;; the reviewer nor the warden named; moving that one file down absorbs
  ;; nothing, and is the repair both of them actually described.
  (let [p (stages/reshape-plan gapped-stack
                               {:kind :misplaced-seam :layers ["a" "d"]
                                :file "/w/resources/db/V20260825__diary.sql"})]
    (is (= :move (:remedy p)))
    (is (= "/w/resources/db/V20260825__diary.sql" (:file p)))
    (is (= "a" (:slug (:lower p))) "down into the bottom-most named layer")
    (is (= "d" (:slug (:upper p))))))

(deftest a-seam-with-no-file-to-move-is-still-a-judgement
  (let [p (stages/reshape-plan gapped-stack
                               {:kind :misplaced-seam :layers ["a" "d"] :file "  "})]
    (is (= :span-has-holes (:refused p)))))

(deftest a-duplication-across-a-gapped-span-is-not-moved
  ;; Moving one copy down puts both in one layer without removing either, which
  ;; is not what the finding asked for.
  (let [p (stages/reshape-plan gapped-stack
                               {:kind :duplicated-across-layers :layers ["a" "d"]
                                :file "/w/src/a.clj"})]
    (is (= :span-has-holes (:refused p)))))

(deftest a-move-names-a-file-the-upper-layer-does-not-touch-and-is-refused
  ;; jj squash over a fileset the source does not touch moves nothing, says so
  ;; and exits 0 — so without the precondition the round reports a move it did
  ;; not make.
  (with-redefs [jj/jj! (fn [_dir & args]
                         (if (= "diff" (first args))
                           {:exit 0 :out "src/other.clj\n" :err ""}
                           (throw (ex-info "no squash should be attempted" {}))))]
    (let [r (layers/move! "/w" "main" {:bookmark "s--d"} {:bookmark "s--a"}
                          "/w/src/a.clj")]
      (is (false? (:ok? r)))
      (is (str/includes? (:reason r) "does not change it")))))

(deftest a-move-squashes-only-the-named-file-and-keeps-both-bookmarks
  (let [calls (atom [])]
    (with-redefs [jj/jj! (fn [_dir & args]
                           (swap! calls conj (vec args))
                           (cond
                             (= "diff" (first args)) {:exit 0 :out "src/a.clj\n" :err ""}
                             ;; the conflicts() probe attempt-reshape! makes
                             (= "log" (first args))  {:exit 0 :out "" :err ""}
                             :else                   {:exit 0 :out "" :err ""}))]
      (let [r (layers/move! "/w/" "main" {:bookmark "s--d"} {:bookmark "s--a"}
                            "/w/src/a.clj")]
        (is (true? (:ok? r)))
        (is (some #(= ["squash" "--from" "s--d" "--into" "s--a"
                       "--use-destination-message" "src/a.clj"] %) @calls)
            "scoped to the file, path made workspace-relative")
        (is (not-any? #(= "delete" (second %)) @calls)
            "neither bookmark is deleted — both layers survive a move")))))

(deftest a-reorder-is-legal-across-a-span-with-holes
  ;; It moves one layer and absorbs none, so the fold's precondition is not its.
  (let [p (stages/reshape-plan gapped-stack
                               {:kind :order-dependence :layers ["a" "d"]})]
    (is (= :reorder (:remedy p)))
    (is (false? (:fold-legal? p))
        "but the fold it would fall back to is still refused")))

(deftest a-finding-this-stage-cannot-act-on-says-which-precondition-failed
  (is (= :unnamed-layers
         (:refused (stages/reshape-plan two-layer-stack
                                        {:kind :order-dependence :layers ["lower"]})))
      "one layer is by its own account not a composition defect")
  (is (= :unnamed-layers
         (:refused (stages/reshape-plan two-layer-stack
                                        {:kind :order-dependence :layers ["lower" "gone"]})))
      "a label this stack does not have cannot be acted on without guessing")
  (is (= :no-remedy
         (:refused (stages/reshape-plan two-layer-stack
                                        {:kind :broken-intermediate :layers ["lower" "upper"]})))
      "a kind whose remedy is to complete a layer is not a reshape"))

(deftest a-defect-is-reshaped-once-per-run
  ;; It comes back next round under new words if the reshape did not clear it,
  ;; and without the handle it would be reshaped again every round for as long
  ;; as the run lasted.
  (let [ctx {:config {:cwd "/w" :base "main"}
             :carry  {:reshaped #{"h-1"}}
             :findings [{:handle "h-1" :disposition :recut :kind :order-dependence
                         :layers ["lower" "upper"]}]}]
    (with-redefs [stages/session-stack (fn [_ _] two-layer-stack)]
      (let [out ((:run stages/reshape-stage) ctx)]
        (is (= ["already-attempted"] (mapv :outcome (:reshapes out)))
            "nothing is tried again — and the round says so rather than passing in silence")))))

(deftest every-recut-the-round-held-is-reported-on
  ;; The silence this closes: a recut is withheld from the fixers BECAUSE the
  ;; remedy is the shape, so a reshape phase that says nothing leaves the finding
  ;; with no path at all — which is how one was raised four rounds running with
  ;; no record that anything had ever been attempted.
  (let [ctx {:config {:cwd "/w" :base "main"}
             :findings [{:handle "h-1" :disposition :recut :kind :claim-falsified
                         :layers ["a" "d"] :title "t1"}
                        {:handle "h-2" :disposition :recut :kind :misplaced-seam
                         :layers ["a" "d"] :title "t2"}
                        {:handle "h-3" :disposition :fix :title "not a recut"}]}]
    (with-redefs [stages/session-stack (fn [_ _] gapped-stack)]
      (let [out (:reshapes ((:run stages/reshape-stage) ctx))]
        (is (= ["no-remedy" "span-has-holes"] (mapv :outcome out)))
        (is (every? (comp seq :because) out) "each with the reason it could not run")))))

(deftest a-refused-recut-becomes-a-park-carrying-the-reshape-s-own-words
  ;; A recut is withheld from the fixers on purpose, so a refusal left the
  ;; finding with no path at all: it reached the round's :reshapes array and
  ;; nothing the next warden or the termination check could see.
  (let [ctx {:config {:cwd "/w" :base "main"} :iter 2
             :findings [{:handle "h-1" :disposition :recut :kind :claim-falsified
                         :layers ["a" "d"] :title "the layer's claim is not true"}
                        {:handle "h-2" :disposition :recut :kind :misplaced-seam
                         :layers ["a" "d"] :title "the seam runs through the migration"}]}]
    (with-redefs [stages/session-stack (fn [_ _] gapped-stack)]
      (let [parks (get-in ((:run stages/reshape-stage) ctx) [:carry :parks])]
        (is (= #{"h-1" "h-2"} (set (keys parks))))
        (is (= 2 (:since (parks "h-1"))) "raised in the round that refused it")
        (is (str/includes? (:because (parks "h-2")) "absorb b")
            "carrying the refusal sentence, which is the thing a human decides on")
        (is (= "the seam runs through the migration" (:title (parks "h-2"))))))))

(deftest a-deferred-recut-is-not-parked
  ;; The one outcome that means try again: another reshape ran this round, so
  ;; the plan was made against a stack that has since moved.
  (let [ctx {:config {:cwd "/w" :base "main"} :iter 1
             :findings [{:handle "h-1" :disposition :recut :kind :order-dependence
                         :layers ["lower" "upper"] :title "t1"}
                        {:handle "h-2" :disposition :recut :kind :order-dependence
                         :layers ["lower" "upper"] :title "t2"}]}]
    (with-redefs [stages/session-stack (fn [_ _] two-layer-stack)
                  layers/reorder! (fn [& _] {:ok? true})
                  layers/restore-top! (fn [& _] nil)]
      (let [out   ((:run stages/reshape-stage) ctx)
            parks (get-in out [:carry :parks])]
        (is (= ["reorder" "deferred"] (mapv :outcome (:reshapes out))))
        (is (empty? parks) "neither the one that worked nor the one still to be tried")))))

(deftest a-park-keeps-the-round-it-was-first-raised-in
  ;; What the give-up counter reads. A refusal repeated every round would
  ;; otherwise reset it and the run would never stop for the seam.
  (let [ctx {:config {:cwd "/w" :base "main"} :iter 4
             :carry {:parks {"h-1" {:since 1 :title "t1" :because "the first reason"}}}
             :findings [{:handle "h-1" :disposition :recut :kind :claim-falsified
                         :layers ["a" "d"] :title "t1"}]}]
    (with-redefs [stages/session-stack (fn [_ _] gapped-stack)]
      (let [p (get-in ((:run stages/reshape-stage) ctx) [:carry :parks "h-1"])]
        (is (= 1 (:since p)))
        (is (= "the first reason" (:because p)))))))

(deftest a-dry-run-reshapes-nothing
  (let [ctx {:config {:cwd "/w" :base "main" :dry-run? true}
             :findings [{:handle "h-1" :disposition :recut :kind :order-dependence
                         :layers ["lower" "upper"]}]}]
    (with-redefs [stages/session-stack (fn [_ _] two-layer-stack)]
      (is (nil? (:reshapes ((:run stages/reshape-stage) ctx)))))))

(deftest stance-path-falls-back-to-the-common-stance
  ;; Every project reaches a stance. Before the fallback, a project with no file
  ;; of its own left the relation-honest derivation :underivable — a verdict
  ;; naming a missing document, which no amender can repair.
  (let [dir (fs/path (core/nido-source-dir) ".claude" "skills" "design" "stances")]
    (is (= (str (fs/path dir "default.md"))
           (str (stages/stance-path :a-project-with-no-stance-file))))
    (is (seq (stages/read-stance :a-project-with-no-stance-file)))))

(deftest stance-path-prefers-a-projects-own-file
  ;; The override is how a project DECLARES that it diverges, so it has to win.
  (let [dir  (fs/path (core/nido-source-dir) ".claude" "skills" "design" "stances")
        own  (fs/path dir "stance-override-probe.md")]
    (try
      (spit (str own) "# diverges\n")
      (is (= (str own) (str (stages/stance-path :stance-override-probe))))
      (is (str/starts-with? (stages/read-stance :stance-override-probe) "# diverges"))
      (finally (fs/delete-if-exists own)))))

(defn- stack-targets
  "A two-layer round's targets: `one`, `two`, and the composition over both.
   `revs` gives each of the three ranges, so a caller can rewrite the commit ids
   without touching anything else."
  [[one two whole]]
  [{:label "one" :from (first one) :to (second one)}
   {:label "two" :from (first two) :to (second two)}
   {:label "stack" :stack? true :from (first whole) :to (second whole)
    :composition {:layers [{:label "one" :from (first one) :tip (second one)}
                           {:label "two" :from (first two) :tip (second two)}]}}])

(defn- composition-key-of
  "The composition target's key, with each range's patch hash supplied by
   `hash-of` — which is what a rewrite that changes no content holds constant."
  [hash-of targets]
  (with-redefs [layers/patch-hash (fn [_ from to] (hash-of [from to]))]
    (:patch-hash (last (stages/with-patch-hashes "/w" targets)))))

(deftest the-composition-key-survives-a-rewrite-that-changes-no-layer
  ;; A rebase, a squash and an amend all give every layer new commit ids and
  ;; leave what they contribute alone. Keyed on composition-of's :from/:tip, the
  ;; composition target missed on all three: one run reviewed a stack that had
  ;; converged eight minutes earlier while both of its layers hit the cache at
  ;; their own unchanged hashes.
  (let [contributes {["b" "c1"] "h-one" ["c1" "c2"] "h-two" ["b" "@"] "h-whole"
                     ["b" "d1"] "h-one" ["d1" "d2"] "h-two"}
        before (composition-key-of contributes
                                   (stack-targets [["b" "c1"] ["c1" "c2"] ["b" "@"]]))
        after  (composition-key-of contributes
                                   (stack-targets [["b" "d1"] ["d1" "d2"] ["b" "@"]]))]
    (is (some? before) "a stack of two known layers has a key")
    (is (= before after)
        "same labels, same order, same contributions — the cut has not moved")))

(deftest re-cutting-a-stack-is-a-different-composition-target
  ;; Re-cutting moves code between layers and leaves base-rev..@ byte-identical.
  ;; Keyed on the patch alone, a composition pass that demanded a re-layering,
  ;; got one, and ran again would skip the very thing it asked for.
  (let [flat  (constantly "SAMEPATCH")
        cut   (fn [targets] (composition-key-of flat targets))
        as-is (cut (stack-targets [["b" "c1"] ["c1" "c2"] ["b" "@"]]))
        moved (cut (update (stack-targets [["b" "c1"] ["c1" "c2"] ["b" "@"]])
                           1 assoc :label "renamed"))]
    (is (not= as-is moved) "the cut is part of what the composition pass reviews")))

(deftest a-layer-is-keyed-on-its-patch-alone
  (with-redefs [layers/patch-hash (fn [_ _ _] "SAMEPATCH")]
    (is (= "SAMEPATCH" (:patch-hash (first (stages/with-patch-hashes
                                             "/w" [{:label "core" :from "b" :to "c"}])))))))

(deftest a-target-whose-hash-cannot-be-computed-is-never-skipped
  ;; Unknown content is reviewed content — folding the composition in must not
  ;; manufacture a key where there was none.
  (with-redefs [layers/patch-hash (fn [_ _ _] nil)]
    (let [t (first (stages/with-patch-hashes
                     "/w" [{:label "stack" :stack? true :from "b" :to "@"
                            :composition {:layers [{:label "one"}]}}]))]
      (is (nil? (:patch-hash t))))))

(deftest one-unknown-layer-leaves-the-whole-composition-unkeyed
  ;; The key is built over the layers, so a hole in them is a hole in it — and a
  ;; key over a hole would let a later run skip a layer nothing has checked.
  (let [k (composition-key-of {["b" "c1"] "h-one" ["b" "@"] "h-whole"}
                              (stack-targets [["b" "c1"] ["c1" "c2"] ["b" "@"]]))]
    (is (nil? k))))

(deftest a-skipped-target-carries-the-round-it-converged-in
  (let [{:keys [review skipped]}
        (stages/to-review {"h1" {:status :converged :round 4}}
                          [{:label "core" :patch-hash "h1"}
                           {:label "wiring" :patch-hash "h2"}])]
    (is (= ["wiring"] (mapv :label review)))
    (is (= [4] (mapv :converged-at skipped)))))

(deftest the-composition-target-carries-this-runs-earlier-composition-findings
  ;; Only findings the composition pass itself made — a layer's own finding is
  ;; already answered where it was raised, and repeating it here would tell the
  ;; pass it reported something it never did.
  (let [history [{:iter 1 :findings [{:from-layer "stack" :title "the seam" :kind "misplaced-seam"}
                                     {:from-layer "core" :title "a typo"}]}
                 {:iter 2 :findings [{:from-layer "stack" :title "the seam again"}]}]
        [layer stack] (stages/with-composition-memory
                        [{:label "core"}
                         {:label "stack" :stack? true :composition {:layers [{:label "core"}]}}]
                        history)
        prior (get-in stack [:composition :already-reported])]
    (is (= ["the seam" "the seam again"] (mapv :title prior)))
    (is (= [1 2] (mapv :round prior)))
    (is (nil? (:composition layer)) "a layer target gets no composition memory")))

(deftest composition-memory-does-not-move-the-cache-key
  ;; The key is the CUT. Fold in a value that changes every round and the cache
  ;; misses every round — switched off rather than made correct.
  (with-redefs [layers/patch-hash (fn [_ _ _] "SAMEPATCH")]
    (let [cut   {:layers [{:label "core"}]}
          cold  (first (stages/with-patch-hashes
                         "/w" [{:label "stack" :stack? true :from "b" :to "@"
                                :composition cut}]))
          warm  (first (stages/with-patch-hashes
                         "/w" [{:label "stack" :stack? true :from "b" :to "@"
                                :composition (assoc cut :already-reported
                                                    [{:round 1 :title "x"}])}]))]
      (is (= (:patch-hash cold) (:patch-hash warm))))))

(deftest a-run-with-no-prior-composition-findings-changes-nothing
  (let [targets [{:label "stack" :composition {:layers [{:label "core"}]}}]]
    (is (= targets (stages/with-composition-memory targets [])))))

(deftest a-park-is-carried-until-something-settles-it
  ;; A park is never raised again — that is what a park IS — so without carrying
  ;; it, it leaves the findings the moment the reviewer stops mentioning it and
  ;; the next warden re-adjudicates the same seam from scratch.
  (let [r1 (stages/carried-parks {} [{:handle "h1" :title "the seam"
                                      :disposition :park :because "for a human"}] 1)
        r2 (stages/carried-parks r1 [{:handle "h9" :disposition :fix}] 2)
        r3 (stages/carried-parks r2 [{:handle "h1" :title "the seam"
                                      :disposition :declined}] 3)]
    (is (= 1 (get-in r1 ["h1" :since])))
    (is (= "for a human" (get-in r1 ["h1" :because])))
    (is (= r1 r2) "a round that does not mention it does not resolve it")
    (is (empty? r3) "a later round CAN settle it — a park is a question, not a verdict")))

(deftest a-park-does-not-restart-its-clock-by-being-re-parked
  (let [r1 (stages/carried-parks {} [{:handle "h1" :disposition :park}] 1)
        r4 (stages/carried-parks r1 [{:handle "h1" :disposition :park}] 4)]
    (is (= 1 (get-in r4 ["h1" :since]))
        "it has been open since round 1, whatever this round called it")))

(deftest the-warden-is-shown-the-parks-it-is-still-holding
  (let [out (prompts/warden-prompt
             {:findings [] :history [] :design nil
              :parked [{:since 1 :title "the doc-ordering seam"
                        :because "no fixer has standing here"}]})]
    (is (str/includes? out "STILL PARKED"))
    (is (str/includes? out "since round 1"))
    (is (str/includes? out "the doc-ordering seam"))
    (is (str/includes? out "nothing raises a park twice"))))

(deftest the-fix-stage-refuses-when-the-tree-moved-under-the-round
  ;; Every finding this round holds was found in a state that is no longer what
  ;; @ means, so landing fixes now writes them onto code nobody reviewed. It
  ;; used to end as fix-noop, which says nothing a reader can act on.
  (with-redefs [stages/session-stack (fn [& _] [])
                layers/descends-from? (fn [_ _] false)
                layers/resolve-rev (fn [_ _] "NOWREV")
                agent/launch! (fn [_] (throw (ex-info "no fixer should launch" {})))]
    (let [ctx ((:run stages/fix-stage)
               {:config {:cwd "/w" :run-id "r1"} :iter 2
                :reviewed-at "THENREV"
                :findings [{:id "aa11" :title "x" :disposition :fix}]})]
      (is (= :workspace-drifted (:status ctx)))
      (is (= :stop (:control ctx)))
      (is (= {:reviewed-at "THENREV" :now "NOWREV"} (:drift ctx))
          "both revisions named — that is what makes it actionable"))))

(deftest a-round-whose-tree-did-not-move-fixes-normally
  (with-redefs [stages/session-stack (fn [& _] [])
                layers/descends-from? (fn [_ _] true)
                agent/launch! (fn [_] {:num-turns 3 :result-error? false :result-text "done"})
                stages/working-copy-dirty? (fn [_] false)
                jj/jj! (fn [& _] {:exit 0 :out "" :err ""})]
    (let [ctx ((:run stages/fix-stage)
               {:config {:cwd "/w" :run-id "r1"} :iter 2
                :reviewed-at "THENREV"
                :findings [{:id "aa11" :title "x" :disposition :fix}]})]
      (is (not= :workspace-drifted (:status ctx))))))

(deftest a-round-that-could-not-pin-the-tree-still-runs
  ;; The guard cannot become a failure of the thing it guards: an unpinnable
  ;; round proceeds exactly as it did before there was a check.
  (with-redefs [stages/session-stack (fn [& _] [])
                layers/descends-from? (fn [& _] (throw (ex-info "no jj here" {})))
                agent/launch! (fn [_] {:num-turns 3 :result-error? false :result-text "done"})
                stages/working-copy-dirty? (fn [_] false)
                jj/jj! (fn [& _] {:exit 0 :out "" :err ""})]
    (let [ctx ((:run stages/fix-stage)
               {:config {:cwd "/w" :run-id "r1"} :iter 2
                :reviewed-at nil
                :findings [{:id "aa11" :title "x" :disposition :fix}]})]
      (is (not= :workspace-drifted (:status ctx))))))
