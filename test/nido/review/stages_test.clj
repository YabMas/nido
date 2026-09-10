;; test/nido/review/stages_test.clj
(ns nido.review.stages-test
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.agent :as agent]
   [nido.coordinator.record.workstream :as ws]
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
    (is (= {:decision :stop :reason "clean" :rulings [] :promote [] :standing []}
           (stages/parse-warden-decision txt))
        "an answer that promotes nothing and leaves nothing standing says so
         with empty lists, rather than with nil — the absence of a key and a
         warden's claim that there is nothing are different answers")))

(deftest a-standing-item-naming-nothing-is-not-carried-and-neither-is-a-repeat
  ;; The warden is asked to state its whole list every round rather than the
  ;; difference, because nothing in the loop can retract an item — so a repeat
  ;; within one answer is what that instruction produces, and it costs a reader
  ;; a line that says what the line above it said.
  (let [txt (str "```json\n{\"decision\":\"stop\",\"reason\":\"done\","
                 "\"standing\":["
                 "{\"what\":\"   \",\"why_no_finding\":\"nothing\"},"
                 "{\"what\":\"babel is pinned to an unmerged branch tip\","
                 "\"why_no_finding\":\"outside this change\"},"
                 "{\"what\":\"babel is pinned to an unmerged branch tip\","
                 "\"why_no_finding\":\"outside this change\"}]}\n```")]
    (is (= [{:what "babel is pinned to an unmerged branch tip"
             :why-no-finding "outside this change"}]
           (:standing (stages/parse-warden-decision txt))))))

(deftest a-standing-item-with-no-ground-is-still-carried
  ;; `why_no_finding` is what makes an item decidable rather than a worry, but
  ;; an item stated without one still reaches a human who had nothing before —
  ;; so the missing field is dropped, never the item.
  (let [txt (str "```json\n{\"decision\":\"stop\","
                 "\"standing\":[{\"what\":\"bb format is red on seven blocks\"}]}"
                 "\n```")]
    (is (= [{:what "bb format is red on seven blocks"}]
           (:standing (stages/parse-warden-decision txt))))))

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

(deftest a-warden-killed-on-its-budget-is-not-a-warden-that-said-nothing
  ;; The kill destroys the process before claude emits its `result` event, so
  ;; `num-turns` is nil for a warden that spent its whole budget adjudicating
  ;; and for one that never started. Read off the count alone, a run that spent
  ;; thirty minutes on the findings was reported as one where "the agent ran no
  ;; turns — nothing was asked of the findings", which is the same false
  ;; statement this function's own docstring warns about for the 429.
  (let [parsed (stages/parse-warden-decision nil)]
    (is (= :budget-spent
           (:cause (stages/warden-failure
                    {:num-turns nil :result-error? false :timed-out? true}
                    parsed))))
    (is (= :no-answer
           (:cause (stages/warden-failure
                    {:num-turns nil :result-error? false :timed-out? false}
                    parsed)))
        "and a launch that produced nothing at all still says so")))

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
      ;; Second round: a quiet round is a sample, so clean is earned by being
      ;; quiet twice — see a-flat-branch-earns-clean-by-being-quiet-twice.
      (let [ctx ((:run stages/review-stage)
                 {:config {:cwd "/w" :base "main" :run-id "r1"} :iter 2
                  :carry {:quiet-once true}})]
        (is (= :stop (:control ctx)))
        (is (= :clean (:status ctx)))))))

(deftest a-stack-that-arrives-conflicted-costs-no-reviewer
  ;; Four consecutive runs on one branch fanned out six agents, ruled on what
  ;; they found, and aborted at the first landing when jj refused it — reaching
  ;; by twenty minutes an answer the same revset call gives in a second.
  (let [launched (atom 0)]
    (with-redefs [layers/conflicted (fn [_ _] ["xlortuwzrtlu" "spxkmpurtnms"])
                  codex/merge-base  (fn [& _] "BASEREV")
                  codex/review!     (fn [_] (swap! launched inc) {:findings []})]
      (let [ctx ((:run stages/review-stage)
                 {:config {:cwd "/w" :base "main" :run-id "r1"} :iter 1})]
        (is (= :stack-conflicted (:status ctx)))
        (is (= :stop (:control ctx)))
        (is (= ["xlortuwzrtlu" "spxkmpurtnms"] (:conflicted ctx))
            "the change ids, because the conflict is mid-stack and `jj resolve
             --list` reports the branch clean")
        (is (zero? @launched)
            "not one reviewer launched — the saving is the whole claim")))))

(deftest the-conflict-preflight-answers-on-a-clean-stack-too
  ;; [] and never-asked are different answers, and only the first one can settle
  ;; whether a recurring conflict was standing before a run or created by it.
  (let [emitted (atom [])]
    (with-redefs [layers/conflicted (fn [_ _] [])
                  layers/patch-hash (fn [& _] nil)
                  codex/merge-base  (fn [& _] "BASEREV")
                  codex/review!     (fn [_] {:status nil :findings []})]
      (let [ctx ((:run stages/review-stage)
                 {:config {:cwd "/w" :base "main" :run-id "r1"
                           :emit #(swap! emitted conj %)}
                  :iter 1})]
        (is (nil? (:status ctx)) "a clean stack is reviewed exactly as before")
        (is (= [[]] (mapv :conflicted
                          (filter #(= :stack-conflicts (:event %)) @emitted)))
            "the empty answer is published, so the report can record it")))))

(deftest a-workspace-that-cannot-be-asked-still-reviews
  ;; A guard that cannot run must not become a failure of the thing it guards:
  ;; a review outside a jj workspace ran before this preflight existed and runs
  ;; the same way now, with the report recording no answer rather than a clean
  ;; bill it never got.
  (let [emitted (atom [])]
    (with-redefs [layers/conflicted (fn [_ _] (throw (ex-info "no workspace" {})))
                  layers/patch-hash (fn [& _] nil)
                  codex/merge-base  (fn [& _] "BASEREV")
                  codex/review!     (fn [_] {:status nil :findings [{:title "x"}]})]
      (let [ctx ((:run stages/review-stage)
                 {:config {:cwd "/w" :base "main" :run-id "r1"
                           :emit #(swap! emitted conj %)}
                  :iter 1})]
        (is (= [{:title "x" :from-layer "stack"}] (:findings ctx)))
        (is (empty? (filter #(= :stack-conflicts (:event %)) @emitted))
            "and nothing is published, so an absent key means unanswered")))))

(deftest review-stage-clean-diff-stops
  (with-redefs [layers/patch-hash (fn [& _] nil)
                codex/merge-base (fn [& _] "BASEREV")
                codex/review! (fn [_] {:status :clean :findings []})]
    ;; Second quiet round: one pass over a range is a sample, not a verdict.
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
  "A jj stub whose `conflicts()` revset names `ids` on every call, and whose
   operation log is empty — so `restore-op!` has no point to roll back to and
   the conflict stands. Everything else succeeds silently, which is what every
   other fix-stage test already assumes."
  [ids]
  (fn [_dir & args]
    (if (some #(str/includes? (str %) "conflicts()") args)
      {:exit 0 :out (str/join "\n" ids) :err ""}
      {:exit 0 :out "" :err ""})))

(defn- jj-scripted
  "A jj stub that answers the `conflicts()` revset from `answers` in order, one
   entry per call, and [] once they run out. `op log` answers with an id so the
   rollback has somewhere to restore to; everything else succeeds silently.

   Scripted rather than fixed because the fix stage now asks the revset twice
   per landing — once for the rebase, once to confirm the rollback took — and a
   stub that gives one answer to both cannot tell those two apart."
  [answers]
  (let [remaining (atom (vec answers))]
    (fn [_dir & args]
      (cond
        (some #(str/includes? (str %) "conflicts()") args)
        (let [ids (first @remaining)]
          (swap! remaining #(if (seq %) (subvec % 1) %))
          {:exit 0 :out (str/join "\n" ids) :err ""})

        (= "op" (first args)) {:exit 0 :out "0a1b2c3d" :err ""}
        :else                 {:exit 0 :out "" :err ""}))))

(def ^:private two-layer-stack
  [{:bookmark "s--lower" :slug "lower" :tip "t1" :change "c1"}
   {:bookmark "s--upper" :slug "upper" :tip "t2" :change "c2"}])

(defn- fixers-run
  "The layer labels a fixer was launched for, in order, read off the per-layer
   error log each launch names."
  [launches]
  (mapv #(second (re-find #"fix-(.+)-round-\d+\.err\.log$" (str (:err-file %))))
        launches))

(deftest a-conflicting-fix-is-rolled-back-and-the-rest-of-the-plan-still-runs
  ;; A conflict used to `reduced` the whole remainder: across four runs on one
  ;; branch, 16 :fix rulings reached 4 fixers, and the layer owning 13 of them
  ;; was never handed to one. The conflict is a fact about the layer that just
  ;; landed, not about the repairs the round has not attempted yet.
  (let [launches (atom [])]
    (with-redefs [agent/launch! (fn [m] (swap! launches conj m)
                                  {:num-turns 4 :result-error? false :result-text "done"})
                  stages/working-copy-dirty? (fn [_] true)
                  stages/session-stack (fn [_ _] two-layer-stack)
                  ;; lower lands and conflicts; the rollback clears it; upper
                  ;; then lands clean.
                  jj/jj! (jj-scripted [["xuspsuww"] [] []])]
      (let [ctx ((:run stages/fix-stage)
                 {:config {:cwd "/w" :run-id "r1" :base "main"} :iter 2
                  :findings [{:id "aa11" :title "x" :disposition :fix :owner-layer "lower"}
                             {:id "bb22" :title "y" :disposition :fix :owner-layer "upper"}]})]
        (is (= ["lower" "upper"] (fixers-run @launches))
            "a conflict on the first layer must not cost the second its fixer")
        (is (= [{:layer "lower" :handed ["aa11"] :conflicted ["xuspsuww"]
                 :account "done"}]
               (:rolled-back ctx))
            "the repair that was undone is on the record, the fixer's reading of
             it included; nothing else holds either — the commit is out of the
             stack and the fixer's own log says it succeeded")
        (is (= ["upper"] (mapv :layer (:fixes ctx)))
            "only the fix that survived counts as landed")
        (is (nil? (:control ctx)) "the stack is clean, so the round goes on")
        (is (nil? (:conflicted ctx))
            ":conflicted means the branch is holding markers right now, which is
             what sends a human to resolve them")))))

(deftest a-rollback-that-does-not-take-still-stops-the-round-and-names-it
  ;; `restore-op!` is best-effort by design, so whether it took is asked rather
  ;; than assumed. When it did not, the markers are on the stack: the next round
  ;; would read them as source and spend its reviewers repairing them.
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

(deftest an-abort-names-the-layers-its-plan-never-reached
  ;; Stopping forfeits every fixer above the conflict, and a forfeited repair is
  ;; still owed. Recorded as nothing, it is indistinguishable from a finding a
  ;; fixer read and let stand — one run dropped three that way, and the only
  ;; trace was a fix-<layer>-round-1.err.log that did not exist.
  (with-redefs [agent/launch! (fn [_] {:num-turns 4 :result-error? false :result-text "done"})
                stages/working-copy-dirty? (fn [_] true)
                stages/session-stack (fn [_ _] two-layer-stack)
                jj/jj! (jj-with-conflicts ["xuspsuww"])]
    (let [ctx ((:run stages/fix-stage)
               {:config {:cwd "/w" :run-id "r1" :base "main"} :iter 2
                :findings [{:id "aa11" :title "x" :disposition :fix :owner-layer "lower"}
                           {:handle "bb22" :id "b2" :title "y" :disposition :fix
                            :owner-layer "upper"}]})]
      (is (= :fix-conflicted (:status ctx)))
      (is (= [{:layer "upper" :handed ["bb22"]}] (:unattempted ctx))
          "the layer the abort skipped, with the findings it was owed — by the
           same :handed key a landed fix carries, so the two join")))
  (testing "an abort on the last layer of the plan forfeits nothing"
    (with-redefs [agent/launch! (fn [_] {:num-turns 4 :result-error? false :result-text "done"})
                  stages/working-copy-dirty? (fn [_] true)
                  stages/session-stack (fn [_ _] two-layer-stack)
                  jj/jj! (jj-with-conflicts ["xuspsuww"])]
      (let [ctx ((:run stages/fix-stage)
                 {:config {:cwd "/w" :run-id "r1" :base "main"} :iter 2
                  :findings [{:id "aa11" :title "x" :disposition :fix :owner-layer "upper"}]})]
        (is (= [] (:unattempted ctx))
            "[] is the stage saying it forfeited nothing; an absent key would be
             a round that never got as far as asking")))))

(deftest a-round-whose-every-repair-was-rolled-back-is-not-a-round-of-declines
  ;; Both leave the tree as the reviewers read it, and they ask a human for
  ;; different things: a decline is a fixer arguing the finding, a rollback is
  ;; the stack refusing an edit the fixer stood behind.
  (with-redefs [agent/launch! (fn [_] {:num-turns 4 :result-error? false :result-text "done"})
                stages/working-copy-dirty? (fn [_] true)
                jj/jj! (jj-scripted [["xuspsuww"] []])]
    (let [ctx ((:run stages/fix-stage)
               {:config {:cwd "/w" :run-id "r1" :base "main"} :iter 2
                :findings [{:id "aa11" :title "x" :disposition :fix}]})]
      (is (= :stop (:control ctx)) "nothing changed, so another round reads the same code")
      (is (= :fix-rolled-back (:status ctx)))
      (is (empty? (:fixes ctx)))
      (is (= ["xuspsuww"] (:conflicted (first (:rolled-back ctx))))))))

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

(deftest a-fixer-killed-on-its-budget-lands-what-it-wrote
  ;; The budget timer destroys the process before claude emits its `result`
  ;; event, so a fixer that made 32 edits comes back with `num-turns` nil — the
  ;; same value one claude rejected at the door comes back with. Asked the count
  ;; first, the stage never looked at the tree: one completed repair, green on
  ;; the fixer's own last lap, was recorded as a fixer that never started and
  ;; left on the working copy, where `restore-top!` stranded it mid-stack as an
  ;; undescribed, unbookmarked commit inside the layer above's range.
  (let [commits (atom [])]
    (with-redefs [agent/launch! (fn [_] {:num-turns nil :result-error? false
                                         :result-text nil :timed-out? true})
                  stages/working-copy-dirty? (fn [_] true)
                  layers/conflicted (fn [& _] [])
                  jj/jj! (fn [_dir & args] (swap! commits conj (vec args))
                           {:exit 0 :out "" :err ""})]
      (let [ctx ((:run stages/fix-stage)
                 {:config {:cwd "/w" :run-id "r1" :base "main"} :iter 2
                  :findings [{:id "aa11" :title "x" :disposition :fix}]})
            [f] (:fixes ctx)]
        (is (some #(= ["commit" "-m" "review-loop: iter 2 fixes"] %) @commits)
            "the repair is on the branch under a description, which is the whole
             difference between a fix and an orphan commit")
        (is (= ["aa11"] (:handed f)))
        (is (true? (:timed-out? f))
            "and the row says the account is missing because the process was
             destroyed, not because the fixer landed its work in silence")
        (is (nil? (:control ctx)) "the round goes on — a repair landed")))))

(deftest a-fixer-killed-with-nothing-written-is-not-a-fixer-that-refused
  ;; Same empty tree as a decline and a different fact about the run: a decline
  ;; is an argument a human reads, a kill decided nothing. Recorded as a
  ;; decline, one 30-minute fixer was published as `fix-declined`, whose own
  ;; comment glosses the status as fixers reading the findings and saying no.
  (with-redefs [agent/launch! (fn [_] {:num-turns nil :result-error? false
                                       :result-text nil :timed-out? true})
                stages/working-copy-dirty? (fn [_] false)
                jj/jj! (fn [& _] {:exit 0 :out "" :err ""})]
    (let [ctx ((:run stages/fix-stage)
               {:config {:cwd "/w" :run-id "r1"} :iter 2
                :findings [{:id "aa11" :title "x" :disposition :fix}]})
          [d] (:declined ctx)]
      (is (= :fix-timed-out (:status ctx)))
      (is (true? (:ran? d)) "it ran — for the whole of its budget")
      (is (true? (:timed-out? d)))
      (is (nil? (:reason d))
          "and it argued nothing: the account was still in the process when the
           budget destroyed it, and inventing one would put words in its mouth"))))

(defn- fixer-wall
  "The wall clock the fix stage hands one fixer, for a loop `budget` and `n`
   findings all owed to the same layer.

   Read off the launch rather than off `fix-budget`, so a scale that is right and
   never reaches the agent still fails."
  [budget n]
  (let [seen (atom nil)]
    (with-redefs [agent/launch! (fn [opts]
                                  (reset! seen (:budget opts))
                                  {:num-turns 0 :result-error? false :result-text ""})
                  stages/working-copy-dirty? (fn [_] false)
                  jj/jj! (fn [& _] {:exit 0 :out "" :err ""})]
      ((:run stages/fix-stage)
       {:config {:cwd "/w" :run-id "r1" :budget budget} :iter 1
        :findings (mapv (fn [i] {:id (str "aa" i) :title "x" :disposition :fix})
                        (range n))}))
    @seen))

(deftest a-fixer-buys-its-verification-lap-once-and-its-repairs-per-finding
  ;; The loop's budget is one wall for every agent it launches and it is
  ;; calibrated on the cheapest: on the run this is measured from, the warden
  ;; answered in 34s and the design verdict in 5m, while the fixer — the only
  ;; agent that edits AND verifies — was killed at exactly 30m mid-`kaocha`, on
  ;; its final lap, with both its repairs already written.
  (is (= "20m" (fixer-wall "10m" 1))
      "the lap a fixer ends on is paid once whatever it was asked to do, so the
       floor under a one-finding repair is the loop's budget twice over")
  (is (= "30m" (fixer-wall "10m" 2))
      "and the repair, its sweep and its account are per finding")
  (is (= "40m" (fixer-wall "10m" 3)))
  (is (= "90m" (fixer-wall "30m" 2))
      "at the loop's own default, the two-finding repair that the 30m wall was
       measured to be short for now has three times it"))

(deftest a-fixer-handed-a-batch-is-still-bounded
  ;; The scale is per finding and the rounds above it are uncapped, so without a
  ;; ceiling one launch of one round can spend the 8h a driven review stage has
  ;; for all of them.
  (is (= "90m" (fixer-wall "30m" 9))
      "nine findings buy no more than three: a fixer this far past the measured
       range has hung, which is the failure a wall clock exists for"))

(deftest the-ceiling-never-cuts-below-the-wall-the-caller-named
  ;; A caller naming a longer budget — a project whose CI lap is slow — is asking
  ;; for more everywhere. Capping the fixer under it would hand the one agent
  ;; that edits and verifies less than the reviewers that only read, which is
  ;; this defect upside down.
  (is (= "120m" (fixer-wall "2h" 9))))

(deftest a-sub-minute-budget-never-rounds-down-to-none
  ;; `0m` is not a short budget: it parses, arms the kill timer at zero, and
  ;; destroys the agent before it has read anything. Only a harness names a wall
  ;; this short, and it must still get one the agent can run under.
  (is (= "90s" (fixer-wall "45s" 1)))
  (is (= "2s" (fixer-wall "1s" 1))))

(deftest an-unreadable-budget-is-refused-by-the-launch-not-by-the-scale
  ;; `agent/parse-budget-ms` refuses an undeclared or unreadable budget at the
  ;; point of launch, under a message that names the caller and says what to
  ;; declare. Scaling would move that refusal into a frame that knows neither.
  (is (= "1w" (fixer-wall "1w" 1)))
  (is (nil? (fixer-wall nil 1))))

(deftest a-kill-says-which-wall-it-was-killed-on
  ;; The wall varies per launch now, so `killed on budget` no longer carries the
  ;; number by implication — and the number is the whole of what a reader does
  ;; about the kill: raise it, or stop reading the fixer as merely slow.
  (let [run (fn [dirty?]
              (with-redefs [agent/launch! (fn [_] {:num-turns nil :result-error? false
                                                   :result-text nil :timed-out? true})
                            stages/working-copy-dirty? (fn [_] dirty?)
                            layers/conflicted (fn [& _] [])
                            jj/jj! (fn [& _] {:exit 0 :out "" :err ""})]
                ((:run stages/fix-stage)
                 {:config {:cwd "/w" :run-id "r1" :base "main" :budget "30m"} :iter 2
                  :findings [{:id "aa11" :title "x" :disposition :fix}]})))]
    (is (= "60m" (:budget (first (:fixes (run true)))))
        "on the row for a repair the kill landed half-done")
    (is (= "60m" (:budget (first (:declined (run false)))))
        "and on the row for a kill that landed nothing, which is the one a
         reader reaches for the number about")))

(deftest a-fixer-that-never-started-does-not-land-a-tree-it-never-touched
  ;; The concession the kill buys is bounded by evidence that a fixer ran. A
  ;; launch claude rejected reports zero turns in a `result` event it did emit,
  ;; so whatever the working copy holds was already there — and on an unstacked
  ;; branch `position-for-fix!` is a no-op, which makes that a human's own
  ;; uncommitted work about to be committed under a fixer's name.
  (with-redefs [agent/launch! (fn [_] {:num-turns 0 :result-error? false
                                       :result-text "" :timed-out? false})
                stages/working-copy-dirty? (fn [_] true)
                jj/jj! (fn [_dir & args]
                         (when (= "commit" (first args))
                           (throw (ex-info "nothing may be committed here" {})))
                         {:exit 0 :out "" :err ""})]
    (let [ctx ((:run stages/fix-stage)
               {:config {:cwd "/w" :run-id "r1"} :iter 2
                :findings [{:id "aa11" :title "x" :disposition :fix}]})
          [d] (:declined ctx)]
      (is (= :fix-declined (:status ctx)))
      (is (false? (:ran? d)))
      (is (nil? (:timed-out? d))))))

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

(defn- ruling-json
  "A one-finding warden answer, given the fields of the ruling."
  [ruling]
  (str "```json\n"
       (json/generate-string {:decision "continue"
                              :findings [(merge {:id "aa11"} ruling)]})
       "\n```"))

(defn- sole-ruling [ruling] (first (:rulings (stages/parse-warden-decision (ruling-json ruling)))))

(defn- satisfying
  "A value that discharges `:requires` for a vocabulary entry — the first
   permitted authority where the field is enumerated, prose where it is not."
  [{:keys [one-of]}]
  (or (first one-of) "because I say so"))

(deftest every-disposition-the-warden-is-offered-is-one-the-parser-accepts
  ;; The accepted half of the contract. A word offered to the warden that the
  ;; parser silently rewrites to :fix is a destination nobody can reach — so
  ;; each is offered WITH the field the vocabulary says it cannot decide
  ;; without, which is the only form of it that is a decision at all.
  (doseq [{:keys [disposition requires] :as entry} prompts/disposition-vocabulary]
    (let [r (cond-> {:disposition (name disposition)}
              requires (assoc requires (satisfying entry)))]
      (is (= disposition (:disposition (sole-ruling r)))
          (str (name disposition) " survives the parser")))))

(deftest a-ruling-missing-what-its-disposition-requires-is-not-a-decision
  ;; A close on no authority ENDS a finding: it is settled, the answered cache
  ;; hands it back to the next reviewer as decided, and nobody is owed
  ;; anything. Demotion to :fix is the same fail-safe an unknown disposition
  ;; takes, and it is what keeps "settled" meaning someone actually decided.
  (doseq [{:keys [disposition requires]} (filter :requires prompts/disposition-vocabulary)]
    (let [r (sole-ruling {:disposition (name disposition)})]
      (is (= :fix (:disposition r))
          (str (name disposition) " with no " (name requires) " is demoted"))
      (is (str/includes? (:because r) (str "no `" (name requires) "`"))
          "and the report names the field that was missing")))
  (is (str/includes? (:because (sole-ruling {:disposition "closed"
                                             :because "same defect as aa11"}))
                     "same defect as aa11")
      "a demotion keeps what the warden did say — the fixer is the next reader of it"))

(deftest a-close-on-an-authority-outside-the-vocabulary-is-not-a-decision
  ;; Observed: a warden returned `"authority": true`. It landed on a :fix that
  ;; round and did no harm; the same value on a :closed would end the finding
  ;; on no grounds and carry `closed (true)` into the next round as an answer
  ;; the reviewer is told not to re-argue.
  (doseq [bad [true "whatever" "" nil]]
    (let [r (sole-ruling {:disposition "closed" :authority bad})]
      (is (= :fix (:disposition r))
          (str "authority " (pr-str bad) " is no authority"))
      (is (nil? (:authority r))
          "and the rejected ground is dropped, not carried into the report")))
  (is (= :closed (:disposition (sole-ruling {:disposition "closed" :authority "spun-out"})))
      "a named authority still closes — this refuses the shrug, not the close"))

(def ^:private positional-attribution
  "The invariant a warden restated in a weaker form, verbatim from the design
   record it was shown (ws-20260908-9a9ec0, seq 45)."
  (str "positional attribution never invents an edge that crosses a namespace "
       "boundary: every cross-namespace :calls edge traces to a var with an "
       "enclosing definition, or to a call inside a method's own form"))

(def ^:private one-invariant-design
  {:shape "..." :invariants [positional-attribution]})

(defn- ruling-against
  "The sole ruling of a one-finding warden answer, parsed against `design`."
  [design r]
  (first (:rulings (stages/parse-warden-decision (ruling-json r) design))))

(deftest a-because-that-restates-an-invariant-is-refused-before-the-fixer-reads-it
  ;; The `because` is the only sentence the loop carries from the warden to the
  ;; fixer, and `fix-prompt` renders it as the reviewer of the whole stack
  ;; speaking. A round-1 warden turned "a call inside a method's own form" into
  ;; file-locality there, the fixer took the licence explicitly, and the
  ;; post-loop verdict found the repair broke the invariant the ruling had
  ;; claimed to satisfy. Nothing checked the sentence against the text it was
  ;; restating — which sat in the warden's own prompt.
  (let [r (ruling-against one-invariant-design
                          {:disposition "fix"
                           :because (str "a marker-bounded repair stays within the invariant "
                                         "against invented cross-namespace edges because every "
                                         "candidate call is in the file that writes the method")})]
    (is (str/includes? (:because r) "without quoting one the record contains")
        "the fixer is told the invariant ground was never established")
    (is (str/includes? (:because r) "every candidate call is in the file")
        "and still reads what the warden argued — the refusal is of the ground, not the sentence")))

(deftest quoting-the-clause-verbatim-is-what-discharges-the-appeal
  (let [quoted (ruling-against one-invariant-design
                               {:disposition "fix"
                                :because (str "this repair keeps the invariant that an edge traces "
                                              "\"to a call inside a method's own form\"")})]
    (is (= (:because (ruling-against one-invariant-design
                                     {:disposition "fix" :because "the guard is off by one"}))
           "the guard is off by one")
        "a because that appeals to nothing is untouched — the word is the trigger")
    (is (not (str/includes? (:because quoted) "without quoting"))
        "a verbatim clause from the record is a citation, and the loop says nothing"))

  (testing "the quote is compared the way a model copies one, not byte for byte"
    (doseq [[what because]
            {"case and wrapping"
             (str "held: \"To A Call Inside\n  A Method's Own Form\" is what this repair stays within, "
                  "so the invariant holds")
             "curly quotes around a curly apostrophe"
             "the invariant “to a call inside a method’s own form” still holds here"
             "backticks, for a warden that would rather not escape a quote"
             "the invariant `to a call inside a method's own form` still holds here"}]
      (is (not (str/includes? (:because (ruling-against one-invariant-design
                                                        {:disposition "fix" :because because}))
                              "without quoting"))
          what)))

  (testing "a span too short to be a clause is not a citation"
    ;; Every invariant contains the words a warden uses to talk about one, so a
    ;; threshold is what keeps `the invariant \"own form\" holds` from passing.
    (is (str/includes? (:because (ruling-against one-invariant-design
                                                 {:disposition "fix"
                                                  :because "the invariant `own form` holds"}))
                       "without quoting one the record contains"))))

(deftest a-refused-citation-does-not-move-the-ruling
  ;; Demotion is the fail-safe for a missing FIELD, and it is the wrong one
  ;; here: a park is how a finding that contradicts an invariant reaches a
  ;; human, and demoting it to `fix` would hand a design question to a fixer —
  ;; the one move the warden is told never to make about one.
  (doseq [d ["park" "fix"]]
    (is (= (keyword d)
           (:disposition (ruling-against one-invariant-design
                                         {:disposition d
                                          :because "it contradicts the namespace-boundary invariant"})))
        (str d " keeps its disposition; it is the ground that was refused"))))

(deftest an-appeal-to-an-invariant-on-a-workstream-that-records-none-is-refused
  ;; The warden is told in as many words that with no record there is nothing
  ;; for a finding to contradict. A sentence that appeals to one anyway is
  ;; appealing to nothing, and the fixer is the reader who needs to know.
  (doseq [design [nil {:shape "..." :invariants []}]]
    (is (str/includes? (:because (ruling-against design
                                                 {:disposition "fix"
                                                  :because "this respects the layering invariant"}))
                       "this workstream records none")
        (str "design " (pr-str design)))))

(deftest a-refused-citation-and-a-missing-field-are-both-reported
  ;; Two independent refusals of one ruling. Reporting only the first would tell
  ;; the fixer the close lacked an authority and leave it believing the
  ;; invariant argument stood.
  (let [r (ruling-against one-invariant-design
                          {:disposition "closed"
                           :because "closed: the design invariant puts this behind a boundary"})]
    (is (= :fix (:disposition r)) "the missing authority still demotes")
    (is (str/includes? (:because r) "no `authority`"))
    (is (str/includes? (:because r) "without quoting one the record contains"))))

(deftest a-packaging-finding-cannot-be-handed-work-that-outlives-the-review
  ;; The two packaging kinds were ruled `fix` 2 times in 49 and accounted for 42
  ;; of the corpus's 46 parks — and a park standing four rounds stops the whole
  ;; run while other findings are still fixable. Their defect is erased when the
  ;; stack is collapsed, and their only remedy is rearranging layers, so every
  ;; disposition that spends a round on one spends it on nothing.
  (doseq [k [:misplaced-cut :order-dependence]
          d [:fix :recut :park]]
    (let [[out] (stages/apply-rulings
                 [{:id "aa11" :title "t" :kind k}]
                 [{:id "aa11" :disposition d :because "the cut is wrong"}]
                 {})]
      (is (= :declined (:disposition out)) (str k " ruled " d))
      (is (= d (:advisory-of out)) "what it would have been is kept")
      (is (str/includes? (:because out) "the cut is wrong")
          "the reviewer's own reason survives")))

  (testing "a settling ruling is left exactly as the warden gave it"
    (doseq [d [:declined :closed :deviation]]
      (let [[out] (stages/apply-rulings
                   [{:id "aa11" :title "t" :kind :misplaced-cut}]
                   [{:id "aa11" :disposition d :because "b"}]
                   {})]
        (is (= d (:disposition out)))
        (is (nil? (:advisory-of out)))
        (is (= "b" (:because out))))))

  (testing "a kind whose defect reaches the merged tree is untouched"
    ;; aggregate is the plain case: a cost added once per layer is a sum that
    ;; lands whole, so a fixer has something to do about it.
    (doseq [k [:aggregate :duplicated-across-layers :claim-falsified
               :broken-intermediate :orphaned-by-scope]]
      (let [[out] (stages/apply-rulings
                   [{:id "aa11" :title "t" :kind k}]
                   [{:id "aa11" :disposition :fix :because "b"}]
                   {})]
        (is (= :fix (:disposition out)) (str k " keeps its fix")))))

  (testing "and an ordinary finding with no kind is untouched"
    (let [[out] (stages/apply-rulings
                 [{:id "aa11" :title "t"}]
                 [{:id "aa11" :disposition :fix :because "b"}] {})]
      (is (= :fix (:disposition out))))))

(deftest a-parked-round-aimed-no-repair-anywhere
  ;; What the give-up counter in `nido.review.loop` asks: how many repairs were
  ;; tried and failed. A park launches no fixer and orders no reshape, so the
  ;; round it was ruled in is not one of the defect's attempts — counted as one,
  ;; it stopped a run at four rounds on a defect the loop had tried exactly once.
  (is (false? (stages/repair-attempted? {:disposition :park})))
  (is (true?  (stages/repair-attempted? {:disposition :fix})))
  (is (true?  (stages/repair-attempted? {:disposition :recut}))
      "a recut is work the loop performs itself, and the next round judges it")
  (is (true?  (stages/repair-attempted? {:disposition :declined})))
  (is (true?  (stages/repair-attempted? {}))
      "an unruled finding is worked on, so nothing about it stops counting"))

(deftest a-park-about-the-cut-stops-blocking-but-keeps-standing
  ;; 42 of the corpus's 46 parks were layering findings, and a park standing
  ;; four rounds ended the whole run :unfixable while other findings were still
  ;; fixable. The boundary it asks about is collapsed before the branch lands.
  (testing "which parks the branch is really waiting on"
    (is (true?  (@#'stages/park-blocks? {:kind :aggregate})))
    (is (true?  (@#'stages/park-blocks? {:kind :orphaned-by-scope})))
    (is (true?  (@#'stages/park-blocks? {:kind :duplicated-across-layers})))
    (is (false? (@#'stages/park-blocks? {:kind :claim-falsified})))
    (is (false? (@#'stages/park-blocks? {:kind :broken-intermediate})))
    (is (false? (@#'stages/park-blocks? {:kind :misplaced-cut}))))
  (testing "an ordinary park still blocks — it names a decision, not a boundary"
    ;; A finding contradicting a design invariant, or one two fixes did not
    ;; settle, carries no kind and is exactly what a park is for.
    (is (true? (@#'stages/park-blocks? {})))
    (is (true? (@#'stages/park-blocks? {:kind nil}))))
  (testing "a string kind reads the same as a keyword one"
    ;; report.json round-trips a kind as a string, so a park rebuilt from a
    ;; carry must not silently start blocking.
    (is (true?  (@#'stages/park-blocks? {:kind "aggregate"})))
    (is (false? (@#'stages/park-blocks? {:kind "claim-falsified"}))))
  (testing "and it does not hold a target out of convergence either"
    ;; Both reads had to move together: converged-targets counts a standing park
    ;; as owed, so a park that stopped halting and kept blocking convergence
    ;; would trade an :unfixable stop for a run to max-iters.
    (let [reviews [{:target {:label "a" :patch-hash "ha"}}
                   {:target {:label "stack" :stack? true :patch-hash "hs"}}]]
      (is (= ["a" "stack"]
             (mapv :label (stages/converged-targets
                           reviews [] [{:owner-layer "a" :kind :misplaced-cut}])))
          "a cut park blocks neither its layer nor the composition")
      (is (= [] (mapv :label (stages/converged-targets
                              reviews [] [{:owner-layer "a" :kind :aggregate}])))
          "a park whose defect lands still holds both"))))

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

(defn- promotion
  [overrides]
  (merge {:title "endpoint-only strips the query string but not the credentials"
          :file "/w/src/speech/transport.clj"
          :line 288
          :priority 1
          :owner_layer "speech-transport"
          :body "The redactor's sibling call reaches a telemere ERROR event."
          :because "The round-1 fixer named this in its account and did not touch it."}
         overrides))

(deftest a-sibling-a-fixer-named-becomes-work-rather-than-prose
  ;; The waste this closes: a sibling a fixer has already located and diagnosed
  ;; used to convert only when a fresh reviewer independently rediscovered it —
  ;; two of five did, each a round later, and three were still standing at the
  ;; end of the run.
  (let [[f :as out] (stages/promoted-findings {} [] [(promotion {})])]
    (is (= 1 (count out)))
    (is (= :fix (:disposition f))
        "a promotion arrives ruled, so the fix stage hands it out this round")
    (is (= "speech-transport" (:owner-layer f))
        "the layer is what the warden adds and the fixer could not")
    (is (= "warden" (:from-layer f))
        "and no layer's reviewer reported it, which is what from-layer says")
    (is (= 288 (:line-start f)) "the fixer is told where to go")
    (is (false? (:sweep f))
        "a promoted sibling is what a sweep already left; ordering another asks
         for the search that produced it")
    (is (= (codex/finding-id f) (:id f) (:handle f))
        "identified by what it points at, exactly as a reviewer's finding is")))

(deftest a-promotion-the-loop-cannot-place-is-not-a-finding
  (testing "no title and no file name no place anything read"
    (is (= [] (stages/promoted-findings {} [] [(promotion {:title "  "})])))
    (is (= [] (stages/promoted-findings {} [] [(promotion {:file nil})]))))
  (testing "a defect a reviewer already reported is that reviewer's finding"
    ;; Promoting it too would put one defect in front of two fixers under two
    ;; ids, and the warden's job on it is to rule rather than to raise.
    (let [reported (assoc (select-keys (promotion {}) [:title :file])
                          :line-start 288)
          id (codex/finding-id reported)]
      (is (= [] (stages/promoted-findings {} [(assoc reported :id id)]
                                          [(promotion {})])))))
  (testing "and the same sibling promoted twice in one answer is one finding"
    (is (= 1 (count (stages/promoted-findings {} [] [(promotion {}) (promotion {})]))))))

(deftest a-sibling-promoted-again-is-the-same-defect-and-not-a-fresh-one
  ;; The give-up counter and the stall check key on the handle. A promotion that
  ;; landed a new id every round would make a defect the loop cannot move look
  ;; like a new one each time, and neither check would ever fire.
  (let [r1    (first (stages/promoted-findings {} [] [(promotion {})]))
        later (first (stages/promoted-findings {(:id r1) (:handle r1)} []
                                               [(promotion {})]))]
    (is (= (:id r1) (:id later)))
    (is (= (:handle r1) (:handle later))))
  (testing "and a promotion the warden says restates an earlier finding chains onto it"
    (let [out (first (stages/promoted-findings {"old1" "old1"} []
                                               [(promotion {:same_as "old1"})]))]
      (is (= "old1" (:handle out))))))

(deftest promoting-outranks-a-stop-in-the-same-answer
  ;; Two fields answering one question — is there work — and the promotion is
  ;; the specific one: it names the work, on a layer, for a fixer.
  (let [answer (json/generate-string
                {:decision "stop" :reason "nothing left" :findings []
                 :promote [(promotion {})]})]
    (with-redefs [agent/launch! (fn [_] {:num-turns 3 :result-error? false
                                         :result-text (str "```json\n" answer "\n```")})
                  stages/discover-design-record (fn [_] nil)
                  stages/project+ws-from-cwd (fn [_] nil)]
      (let [ctx ((:run stages/warden-stage)
                 {:config {:cwd "/w" :run-id "r1"} :iter 1 :findings []})]
        (is (= :continue (:control ctx)))
        (is (= :stop (:decision (:warden ctx)))
            "what the warden said is still recorded as what it said")
        (is (= ["speech-transport"] (map :owner-layer (:findings ctx)))
            "and the promotion is a finding of this round, on its layer")
        (is (= 1 (count (:promoted ctx)))
            "kept apart too, because a ruling row cannot say what was raised")))))

(deftest a-round-that-promotes-nothing-decides-for-itself
  (let [answer "```json\n{\"decision\":\"stop\",\"reason\":\"clean\",\"findings\":[]}\n```"]
    (with-redefs [agent/launch! (fn [_] {:num-turns 3 :result-error? false
                                         :result-text answer})
                  stages/discover-design-record (fn [_] nil)
                  stages/project+ws-from-cwd (fn [_] nil)]
      (let [ctx ((:run stages/warden-stage)
                 {:config {:cwd "/w" :run-id "r1"} :iter 1 :findings []})]
        (is (= :stop (:control ctx)))
        (is (empty? (:promoted ctx)))))))

(deftest the-warden-is-offered-the-promote-field-only-where-an-account-exists
  ;; Its premise is that a fixer read the code and named what its repair did not
  ;; reach. With no account in front of it there is no such reader, and the
  ;; field would be an invitation to invent findings.
  (let [args {:findings [{:id "aa11" :title "t"}] :history [] :toc []}]
    (is (not (str/includes? (prompts/warden-prompt args) "\"promote\"")))
    (is (str/includes?
         (prompts/warden-prompt
          (assoc args :fixer-accounts
                 [{:layer "speech-contract" :round 1 :commit "0c181d7"
                   :findings [{:title "the redactor strips only the query string"}]
                   :account "The surviving instance is at speech/transport.clj:288."}]))
         "\"promote\""))))

(deftest a-skipped-layer-something-owes-work-to-is-reopened
  ;; The other half, and the one that made a correct placement worthless: only a
  ;; target a reviewer READ ever rewrites its cache entry, so a defect attributed
  ;; to a converged layer was owed by a patch nothing would look at again. One
  ;; run carried an unrepaired credential leak through three rounds this way and
  ;; ended `clean` with nothing open.
  (let [skipped [{:label "speech-transport" :patch-hash "h-transport"}
                 {:label "quiet" :patch-hash "h-quiet"}
                 {:label "stack" :stack? true :patch-hash "h-stack"}]]
    (is (= ["h-transport" "h-stack"]
           (stages/reopened-patches
            skipped [{:disposition :fix :owner-layer "speech-transport"}] []))
        "the owning layer, and the composition, which converges only on nothing
         being owed anywhere")
    (is (= [] (stages/reopened-patches
               skipped [{:disposition :closed :owner-layer "speech-transport"}] []))
        "a settled finding owes nobody anything")
    (is (= ["h-transport" "h-stack"]
           (stages/reopened-patches
            skipped [] [{:owner-layer "speech-transport"}]))
        "a standing park holds a skipped layer exactly as an open finding does")
    (is (= [] (stages/reopened-patches
               skipped [] [{:owner-layer "speech-transport" :kind :misplaced-cut}]))
        "and a park about the cut holds neither, as it holds neither in
         converged-targets")))

(deftest a-promotion-revokes-the-convergence-of-the-layer-nobody-read
  ;; The two halves as the loop wires them. The promoted finding is owed of a
  ;; layer this round skipped, so the entry that would have skipped it again is
  ;; downgraded — while the layer the round did read and settled still converges.
  (let [written (atom nil)
        cached  {"h-transport" {:status :converged :label "speech-transport"
                                :round 2 :at "2026-09-01T00:00:00Z"
                                :answered [{:id "old" :disposition :declined}]}
                 "h-contract"  {:status :partial :label "speech-contract"}}]
    (with-redefs [stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  cache/read-cache (fn [& _] cached)
                  cache/write! (fn [_ _ c] (reset! written c) true)]
      (stages/record-review!
       "/w" {:iter 2 :history []
             :reviews  [{:target {:label "speech-contract" :patch-hash "h-contract"}}]
             :skipped  [{:label "speech-transport" :patch-hash "h-transport"}]
             :findings [{:id "p1" :from-layer "warden" :disposition :fix
                         :owner-layer "speech-transport"}]}))
    (is (= :partial (:status (get @written "h-transport")))
        "the placement now moves the layer, which is the whole of what it could
         not do before")
    (is (= [{:id "old" :disposition :declined}]
           (:answered (get @written "h-transport")))
        "and what an earlier run settled about this unmoved patch survives it")
    (is (= :converged (:status (get @written "h-contract")))
        "a reopen names the layers something is owed of, not every skipped one")))

(deftest reopening-a-layer-keeps-what-was-settled-about-its-patch
  ;; The patch has not moved, so the answers recorded against it are still about
  ;; this content. A reopen that rewrote the entry would make the next round
  ;; re-argue everything an earlier one decided.
  (let [c {"h" {:status :converged :label "l" :round 2 :at "2026-09-01T00:00:00Z"
                :answered [{:id "aa11" :disposition :declined :because "shipping it"}]}}
        c' (cache/reopen c "h" "2026-09-08T00:00:00Z")]
    (is (false? (cache/converged? c' "h")))
    (is (= [{:id "aa11" :disposition :declined :because "shipping it"}]
           (cache/answered c' "h")))
    (is (= "2026-09-08T00:00:00Z" (:at (get c' "h")))
        "stamped when the convergence was revoked, not when it was granted"))
  (testing "a patch this store has never seen gains no status"
    (is (= {} (cache/reopen {} "h" "2026-09-08T00:00:00Z")))))

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

(deftest settled-by-layer-reaches-the-fixer-of-the-layer-the-decision-is-about
  ;; The deviation that got trampled was reported by the whole-stack pass and
  ;; attributed by the warden to one layer. Keyed on who REPORTED it, as the
  ;; warden's own block is, it would reach that layer's fixer never.
  (let [stack [{:bookmark "s--a" :slug "a"} {:bookmark "s--b" :slug "b"}]
        by    (stages/settled-by-layer
               stack
               [[{:id "0f2" :title "changelog contradicts the branch"
                  :disposition :deviation :of "the claim" :from-layer "stack"
                  :owner-layer "a" :file "recording.clj"}]])]
    (is (= ["0f2"] (map :id (get by "a"))))
    (is (nil? (get by "b")))))

(deftest settled-by-layer-carries-the-round-the-fixer-is-standing-in
  ;; The warden rules and the fixers run inside one round, so the decision a
  ;; fixer is about to walk into is usually one taken minutes earlier. Read off
  ;; :history alone it would not exist yet — the fix stage appends the round
  ;; only once the fixes have landed.
  (let [stack [{:bookmark "s--a" :slug "a"}]
        by    (stages/settled-by-layer
               stack
               [[{:id "old" :disposition :declined :owner-layer "a"}]
                [{:id "new" :disposition :deviation :owner-layer "a"}]])]
    (is (= #{"old" "new"} (set (map :id (get by "a")))))))

(deftest settled-by-layer-holds-only-decisions-not-work
  ;; A finding at :fix is what the fixer was handed. Rendering it as settled too
  ;; would tell it to leave alone the very thing it was sent to repair.
  (let [stack [{:bookmark "s--a" :slug "a"}]
        by    (stages/settled-by-layer
               stack
               [[{:id "work" :disposition :fix :owner-layer "a"}
                 {:id "park" :disposition :park :owner-layer "a"}
                 {:id "kept" :disposition :declined :owner-layer "a"}]])]
    (is (= ["kept"] (map :id (get by "a"))))))

(deftest settled-by-layer-attributes-like-the-plan-it-is-read-beside
  ;; `fix-plan` sends an owner naming no layer of this stack to the top one.
  ;; A second rule here would put the decision on a layer whose fixer never runs
  ;; while the code it is about is handed to one that is never told.
  (let [stack [{:bookmark "s--a" :slug "a"} {:bookmark "s--b" :slug "b"}]
        by    (stages/settled-by-layer
               stack [[{:id "x" :disposition :closed :authority "design"
                        :owner-layer "gone"}]])]
    (is (= ["x"] (map :id (get by "b"))))))

(deftest settled-by-layer-keys-an-unlayered-branch-under-nil
  ;; Where `fix-plan`'s single flat entry looks for it — a branch with no layers
  ;; has one place a defect can live, and the fixer of it is told about all of
  ;; them.
  (let [by (stages/settled-by-layer
            [] [[{:id "x" :disposition :declined :because "shipping it"}]])]
    (is (= ["x"] (map :id (get by nil))))))

(deftest settled-by-layer-takes-the-latest-ruling-on-a-finding
  ;; A round that reverses an earlier decision has decided. A fixer told to
  ;; honour the ruling that was reversed would be honouring nothing.
  (let [by (stages/settled-by-layer
            [{:bookmark "s--a" :slug "a"}]
            [[{:id "x" :handle "x" :disposition :declined :owner-layer "a"}]
             [{:id "x" :handle "x" :disposition :fix :owner-layer "a"}]])]
    (is (nil? (get by "a"))
        "reopened in a later round, so it is work again and not a decision")))

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

(deftest review-stage-hands-the-reviewer-the-standing-verdicts-item
  ;; The whole chain, because every link of it is a one-liner and the feature is
  ;; dead if any one is missing: the ledger's last verdict, through the target,
  ;; into the opts the reviewer is launched with.
  (let [seen (atom nil)]
    (with-redefs [layers/patch-hash (fn [& _] nil)
                  codex/merge-base  (fn [& _] "FORK")
                  cache/read-cache  (fn [& _] {})
                  conformance/findings (fn [& _] [])
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [_ _ kind]
                                    (case kind
                                      :design         {:seq 3}
                                      :design-verdict {:verdict :strained :design-seq 3
                                                       :round 4 :reason "pressure"
                                                       :needs "close-turn! still tests (empty? open)"}
                                      nil))
                  codex/review! (fn [opts] (reset! seen opts)
                                  {:status :clean :findings []})]
      ((:run stages/review-stage) {:config {:cwd "/w" :base "main" :run-id "r1"} :iter 1})
      (is (= "close-turn! still tests (empty? open)"
             (get-in @seen [:standing :needs]))))))

;; ---- fan-out: every layer plus the whole stack ---------------------------

(deftest in-parallel-preserves-order
  ;; Order is the contract, and it is not the order they FINISH in: the sleeps
  ;; descend, so with the cap gone every thunk is in flight at once and 5 lands
  ;; first.
  (is (= [1 2 3 4 5] (stages/in-parallel (map (fn [n] #(do (Thread/sleep (- 20 (* 3 n))) n))
                                              [1 2 3 4 5])))))

(deftest in-parallel-propagates-the-original-ex-data
  ;; A bare future deref wraps in ExecutionException, which would hide the
  ;; :review-failed reason the engine branches on.
  (is (= :review-failed
         (try (stages/in-parallel [#(throw (ex-info "boom" {:reason :review-failed}))
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

(deftest a-rounds-verdict-is-the-worst-answer-its-reviewers-gave
  ;; Observed: a report row read overall-correctness "correct" beside a P1 its
  ;; own findings array held, because the round published the composition pass's
  ;; answer under the round's name while the layer pass on the same round said
  ;; "incorrect". The two fields on one row then contradicted each other with
  ;; nothing to say which was lying.
  (with-redefs [layers/patch-hash    (fn [& _] nil)
                codex/merge-base     (fn [& _] "FORK")
                codex/changed-files  (fn [& _] [])
                stages/session-stack (fn [& _] [{:bookmark "s--a" :slug "a" :tip "cA"}
                                                {:bookmark "s--b" :slug "b" :tip "cB"}])
                layers/brief         (fn [& _] nil)
                codex/review!        (fn [{:keys [label]}]
                                       (if (= "stack" label)
                                         {:status nil :findings []
                                          :overall-correctness "correct"}
                                         {:status nil
                                          :findings [{:title (str "f-" label)
                                                      :file "x.clj" :line-start 1}]
                                          :overall-correctness "incorrect"}))]
    (let [ctx ((:run stages/review-stage)
               {:config {:cwd "/w" :base "main" :run-id "r"} :iter 1})]
      (is (= "incorrect" (:overall-correctness ctx))
          "a composition pass happy with the cut cannot clear a round whose layers
           reported defects — the row would deny its own findings"))))

(deftest a-terminal-clean-round-still-publishes-a-reviewer-that-would-not-clear
  ;; The clean branch assigns the verdict separately, and it is the round whose
  ;; verdict is most worth keeping: a reviewer withholding clearance on a layer
  ;; it found nothing in is the only trace of a doubt the loop is about to end on.
  (let [answers (fn [by-label]
                  (with-redefs [layers/patch-hash    (fn [& _] nil)
                                codex/merge-base     (fn [& _] "FORK")
                                codex/changed-files  (fn [& _] [])
                                stages/session-stack (fn [& _] [{:bookmark "s--a" :slug "a" :tip "cA"}
                                                                {:bookmark "s--b" :slug "b" :tip "cB"}])
                                layers/brief         (fn [& _] nil)
                                codex/review!        (fn [{:keys [label]}]
                                                       {:status nil :findings []
                                                        :overall-correctness (by-label label)})]
                    ;; The SECOND quiet round, since that is where a round with
                    ;; no findings becomes terminal — see
                    ;; a-layered-stack-earns-clean-the-same-way.
                    ((:run stages/review-stage)
                     {:config {:cwd "/w" :base "main" :run-id "r"} :iter 2
                      :carry {:quiet-once true}})))]
    (let [ctx (answers {"a" "correct" "b" "incorrect" "stack" "correct"})]
      (is (= :clean (:status ctx)) "no findings, so the round is still terminal")
      (is (= "incorrect" (:overall-correctness ctx))
          "the withheld clearance survives the round it would otherwise be lost in"))
    (is (= "correct" (:overall-correctness (answers (constantly "correct"))))
        "and a round every reviewer cleared reads correct, as it always did")))

(deftest round-correctness-takes-the-first-dissent-verbatim
  (is (= "incorrect" (stages/round-correctness [{:overall-correctness "correct"}
                                                {:overall-correctness "incorrect"}
                                                {:overall-correctness "unsure"}]))
      "target order breaks the tie, so a round two reviewers dissented in reports the
       same answer every time it is read")
  (is (= "unsure" (stages/round-correctness [{:overall-correctness "unsure"}
                                             {:overall-correctness "incorrect"}]))
      "an answer outside the vocabulary is a reviewer that said something; flattening
       it to incorrect would hide that it was never asked for")
  (is (= "correct" (stages/round-correctness [{:overall-correctness "correct"}
                                              {:overall-correctness "correct"}])))
  (is (nil? (stages/round-correctness [{:status :nothing-to-review :findings []}]))
      "a target that read nothing reached no verdict, and a round of them has none
       to publish rather than a clearance nobody gave"))

(deftest the-table-of-contents-names-every-layer-including-the-quiet-ones
  ;; Built from the round's results, the map shrank as the run proceeded: one
  ;; four-layer stack rendered as two layers by round three, because the other
  ;; two had converged and no reviewer ran on them. A reader is then told a
  ;; layer does not exist exactly when it has gone quiet, and a quiet layer is
  ;; still in the stack and still owns its files.
  (with-redefs [layers/patch-hash    (fn [_ _ to] (str "h-" to))
                codex/merge-base     (fn [& _] "FORK")
                codex/changed-files  (fn [_ _ to] [(str to ".clj")])
                stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                cache/read-cache     (fn [& _] {"h-cA" {:status :converged :round 1}})
                stages/session-stack (fn [& _] [{:bookmark "s--a" :slug "a" :tip "cA"}
                                                {:bookmark "s--b" :slug "b" :tip "cB"}])
                layers/brief         (fn [& _] nil)
                codex/review!        (fn [{:keys [label]}]
                                       {:status nil
                                        :findings [{:title (str "f-" label) :file "x.clj"
                                                    :line-start 1}]})]
    (let [ctx ((:run stages/review-stage)
               {:config {:cwd "/w" :base "main" :run-id "r"} :iter 2})]
      (is (= ["a"] (mapv :label (:skipped ctx)))
          "the precondition: layer a converged, so this round has no result for it")
      (is (= ["a" "b"] (mapv :label (:toc ctx)))
          "the map is of the stack, not of the round that happened to read it")
      (is (= ["cA.clj"] (:files (first (:toc ctx))))
          "and a quiet layer's files are exactly what the map is consulted for"))))

(deftest a-fixer-is-told-which-files-the-layer-above-it-owns
  ;; The round that produced the rollback: the fix prompt carried no stack map,
  ;; so a fixer edited a file its own layer and the layer above both touch, the
  ;; rebase of the layer above conflicted, and the whole repair was restored
  ;; away. The run had already built the map that names the overlap.
  (let [captured (atom nil)]
    (with-redefs [agent/launch! (fn [{:keys [first-message]}]
                                  (reset! captured first-message)
                                  {:num-turns 0 :result-error? false :result-text ""})
                  stages/session-stack (fn [& _] [{:bookmark "s--bench" :slug "bench" :tip "cA"}
                                                  {:bookmark "s--doc" :slug "doc" :tip "cB"}])
                  jj/jj! (fn [& _] {:out "" :err "" :exit 0})]
      ((:run stages/fix-stage)
       {:config {:cwd "/w" :run-id "r1"} :iter 3
        :toc [{:label "bench" :files ["bench.clj" "doc.md"]}
              {:label "doc" :files ["doc.md"]}]
        :findings [{:id "aa11" :title "x" :disposition :fix :owner-layer "bench"}]})
      (is (str/includes? @captured "THE LAYERS ABOVE YOURS"))
      (is (str/includes? @captured "2. doc — doc.md")
          "the file the rollback turned on reaches the fixer as another layer's"))))

(deftest a-flat-branch-earns-clean-by-being-quiet-twice
  ;; One whole-diff pass over an unlayered branch is a sample, not a verdict:
  ;; the round that missed a change's only P1 found one of three pre-existing
  ;; defects and reported clean.
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

(deftest a-layered-stack-earns-clean-the-same-way
  ;; Layers do not cross-check each other: a layer's code is read by its own
  ;; reviewer and by nobody else, since the composition pass is asked about the
  ;; cut and told not to report what the layer reviews hold. A stack that stops
  ;; on one quiet round therefore closes on a single reading of every layer —
  ;; the sample a second quiet round exists to refuse.
  (with-redefs [layers/patch-hash (fn [& _] nil)
                codex/merge-base     (fn [& _] "FORK")
                stages/session-stack (fn [& _] [{:bookmark "s--a" :slug "a" :tip "cA"}
                                                {:bookmark "s--b" :slug "b" :tip "cB"}])
                layers/brief         (fn [& _] nil)
                codex/changed-files  (fn [& _] [])
                codex/review!        (fn [_] {:status :clean :findings []})]
    (let [first-pass  ((:run stages/review-stage)
                       {:config {:cwd "/w" :base "main" :run-id "r"} :iter 1})
          second-pass ((:run stages/review-stage)
                       {:config {:cwd "/w" :base "main" :run-id "r"} :iter 2
                        :carry (:carry first-pass)})]
      (is (nil? (:status first-pass)))
      (is (= :continue (:control first-pass))
          "a layer read once has been sampled, not verified")
      (is (= :clean (:status second-pass)))
      (is (= :stop (:control second-pass))))))

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
    (is (= ["b"] (map :label (stages/converged-targets reviews findings [])))
        "a owns the fix so it is not converged; b needs no change so it is")))

(deftest converged-targets-include-the-stack-only-when-nothing-needs-fixing
  (let [reviews [{:target {:label "stack" :patch-hash "h-s" :stack? true}}]]
    (is (= ["stack"] (map :label (stages/converged-targets reviews [] []))))
    (is (= [] (stages/converged-targets
               reviews [{:disposition :fix :owner-layer "a"}] [])))))

(deftest converged-targets-hold-a-target-whose-finding-was-not-closed
  ;; The disposition that is neither :fix nor :closed is what this turns on.
  ;; Nobody was handed the finding, so nothing about the target will change —
  ;; and recording it converged writes the patch into an append-only store, so a
  ;; later run at the same content skips the target and the finding is gone.
  (let [reviews [{:target {:label "a" :patch-hash "h-a"}}
                 {:target {:label "b" :patch-hash "h-b"}}
                 {:target {:label "stack" :patch-hash "h-s" :stack? true}}]]
    (is (= ["b"] (map :label (stages/converged-targets
                              reviews [{:disposition :park :owner-layer "a"}] [])))
        "a parked finding leaves its owner open, and the stack with it")
    (is (= ["a" "b" "stack"]
           (map :label (stages/converged-targets
                        reviews [{:disposition :deviation :owner-layer "a"}] [])))
        "a deviation is a decision, so it settles its target")
    (is (= ["b"] (map :label (stages/converged-targets
                              reviews [{:disposition :declined :owner-layer "a"}
                                       {:disposition :park :owner-layer "a"}] [])))
        "and a decline settles, so only the park is still holding a")
    (is (= ["a" "b" "stack"]
           (map :label (stages/converged-targets
                        reviews [{:disposition :closed :owner-layer "a"}] [])))
        "a close is a decision by a named authority, so its target converges")
    (is (= ["a" "b" "stack"]
           (map :label (stages/converged-targets
                        reviews [{:disposition :declined :owner-layer "a"}] [])))
        "so is a decline: the finding is true and we said we are leaving it")))

(deftest converged-targets-hold-the-stack-for-a-finding-that-names-no-layer
  ;; The warden may rule on a finding without giving it an owner. It then names
  ;; no layer, so it blocks none of them — the whole-stack target is the only
  ;; thing holding it, which is why that target turns on `nothing is open`
  ;; rather than on ownership.
  (let [reviews [{:target {:label "a" :patch-hash "h-a"}}
                 {:target {:label "stack" :patch-hash "h-s" :stack? true}}]]
    (is (= ["a"] (map :label (stages/converged-targets
                              reviews [{:disposition :park :owner-layer nil}] []))))))

(deftest converged-targets-hold-a-target-a-carried-park-names
  ;; A park is raised once and never again, so from the next round on it is in
  ;; the carry and nowhere else. A round that reads only its own findings
  ;; therefore converges the parked layer as soon as a fresh reviewer happens
  ;; not to mention the seam — and the cache only grows, so a later run at that
  ;; same patch skips the layer and the question a human was asked is gone.
  (let [reviews [{:target {:label "a" :patch-hash "h-a"}}
                 {:target {:label "b" :patch-hash "h-b"}}
                 {:target {:label "stack" :patch-hash "h-s" :stack? true}}]]
    (is (= ["b"] (map :label (stages/converged-targets
                              reviews [] [{:since 3 :owner-layer "a"}])))
        "a standing park holds its layer out of the cache with no finding to name it")
    (is (= ["a" "b"] (map :label (stages/converged-targets
                                  reviews [] [{:since 3 :owner-layer nil}])))
        "a park that names no layer still holds the stack, which is what carries it")
    (is (= ["a" "b" "stack"] (map :label (stages/converged-targets reviews [] [])))
        "and a run holding no park converges everything it reviewed clean")))

(deftest reviewed-statuses-record-a-target-that-owes-something-as-well
  ;; The two halves of the store. A converged patch is skipped, so nothing ever
  ;; reads what was answered against it; a patch still owing something is
  ;; reviewed again, which makes it the only entry the read path can reach — and
  ;; it was the one nothing was written about.
  (let [reviews [{:target {:label "a" :patch-hash "h-a"}}
                 {:target {:label "b" :patch-hash "h-b"}}
                 {:target {:label "stack" :patch-hash "h-s" :stack? true}}]
        out     (stages/reviewed-statuses
                 reviews [{:disposition :fix :owner-layer "a"}] [])]
    (is (= [["a" :partial] ["b" :converged] ["stack" :partial]]
           (map (fn [[t s]] [(:label t) s]) out))
        "every reviewed target is recorded; only the one owing nothing may be skipped")))

(deftest reviewed-statuses-drop-a-target-whose-patch-is-unknown
  ;; An entry keyed on nil is a claim about every patch and about none.
  (is (= [] (stages/reviewed-statuses [{:target {:label "a" :patch-hash nil}}] [] []))))

(deftest answered-for-carries-only-what-that-target-reported-and-lost
  (is (= ["aa11"]
         (map :id (stages/answered-for
                   "a" [[{:id "aa11" :from-layer "a" :disposition :closed :authority "design"}
                         {:id "bb22" :from-layer "a" :disposition :fix}
                         {:id "cc33" :from-layer "b" :disposition :closed}]])))))

(deftest answered-for-carries-every-decision-not-only-a-close
  ;; A decline re-argued every round is not a decision: the reviewer has no
  ;; memory, so the reason given the first time never reaches the round that
  ;; needs it. A park is NOT carried — nothing was decided about it.
  (let [out (stages/answered-for
             "a" [[{:id "aa11" :from-layer "a" :disposition :declined
                    :because "true, and this branch is leaving it"}
                   {:id "bb22" :from-layer "a" :disposition :deviation :of "the claim"}
                   {:id "cc33" :from-layer "a" :disposition :park}
                   {:id "dd44" :from-layer "a" :disposition :fix}]])]
    (is (= ["aa11" "bb22"] (map :id out)))
    (is (= [:declined :deviation] (map :disposition out))
        "the disposition rides along so the next warden knows what kind of answer it is")))

(deftest answered-for-folds-every-round-not-only-the-one-that-converged
  ;; The converging round is the one least likely to hold anything: a run ends
  ;; by finding nothing, so reading it alone records an empty answer against
  ;; every target the run spent its earlier rounds adjudicating.
  (let [out (stages/answered-for
             "a" [[{:handle "aa11" :id "aa11" :from-layer "a" :disposition :closed
                    :authority "design"}]
                  []])]
    (is (= ["aa11"] (map :id out))
        "a run that converges on an empty round still records what it settled")))

(deftest answered-for-keeps-the-latest-ruling-on-a-finding
  ;; The same identity ruled twice is one answer, and the later round is the one
  ;; that read the earlier one's fix — so a reversal in either direction stands.
  (let [reopened (stages/answered-for
                  "a" [[{:handle "aa11" :id "aa11" :from-layer "a" :disposition :closed}]
                       [{:handle "aa11" :id "aa11" :from-layer "a" :disposition :fix}]])
        settled  (stages/answered-for
                  "a" [[{:handle "bb22" :id "bb22" :from-layer "a" :disposition :fix}]
                       [{:handle "bb22" :id "bb22" :from-layer "a" :disposition :declined}]])]
    (is (= [] reopened) "a finding a later round re-opened is not an answer any more")
    (is (= ["bb22"] (map :id settled))
        "and one a later round decided is, however it was ruled before")))

(deftest answered-by-layer-reads-an-earlier-runs-answers-under-the-patch-hash
  ;; A previous run's answers were about the content it read, so they hang off
  ;; the patch: a layer that has since moved has no answer from it.
  (let [ctx {:cache   {"h-a" {:answered [{:id "aa11" :title "t" :authority "design"}]}
                       "h-b" {:answered []}}
             :reviews [{:target {:label "a" :patch-hash "h-a"}}
                       {:target {:label "b" :patch-hash "h-b"}}
                       {:target {:label "c" :patch-hash "h-moved"}}]}]
    (is (= [{:label "a" :answered [{:id "aa11" :title "t" :authority "design"}]}]
           (stages/answered-by-layer ctx))
        "a layer with nothing answered is dropped, not carried as an empty row")))

(deftest this-runs-answers-survive-the-fix-that-moved-the-layers-patch
  ;; The case the hash cannot key. Round 1 declines a finding on `a` and lands a
  ;; repair on it; round 2 therefore reads different content, so nothing in the
  ;; cache is about the patch under review — one run wrote the same declined
  ;; deviation under three successive hashes and read it back none of the times,
  ;; and the reviewer re-raised it every round at full cost.
  (let [ctx {:cache   {"h-a-round-1" {:answered [{:id "aa11" :disposition :declined}]}}
             :history [{:iter 1
                        :findings [{:id "aa11" :from-layer "a" :disposition :declined
                                    :because "true, and this branch is leaving it"}
                                   {:id "bb22" :from-layer "a" :disposition :fix}]}]
             :reviews [{:target {:label "a" :patch-hash "h-a-round-2"}}]}]
    (is (= [{:label "a"
             :answered [{:id "aa11" :disposition :declined
                         :because "true, and this branch is leaving it"}]}]
           (stages/answered-by-layer ctx))
        "the label is the identity a repair cannot move; the patch hash is not")))

(deftest the-two-sources-of-an-answer-are-joined-on-the-finding-not-appended
  ;; Finding ids are a hash of file, line and title, so the same defect at the
  ;; same site carries one id whichever run raised it. Showing a warden the same
  ;; finding twice — once as an earlier run's decline and once as this run's —
  ;; is a prompt block that argues with itself.
  (let [ctx {:cache   {"h-a" {:answered [{:id "aa11" :disposition :declined
                                          :because "the run before said no"}
                                         {:id "cc33" :disposition :closed}]}}
             :history [{:iter 1 :findings [{:id "aa11" :from-layer "a"
                                            :disposition :closed
                                            :authority "design"}
                                           {:id "bb22" :from-layer "a"
                                            :disposition :declined}]}]
             :reviews [{:target {:label "a" :patch-hash "h-a"}}]}
        answered (:answered (first (stages/answered-by-layer ctx)))]
    (is (= ["aa11" "cc33" "bb22"] (mapv :id answered))
        "one row per finding, the stored ones first so the list reads oldest-first")
    (is (= {:id "aa11" :disposition :closed :authority "design"} (first answered))
        "and where both ruled on it, this run's is the later decision")))

(deftest what-is-written-to-the-cache-is-what-the-next-round-can-read
  ;; The two ends of the answered channel, composed the way a run composes them:
  ;; a round writes, and the next round reads back what it did not skip. While
  ;; only converged targets were written, `in the cache` and `under review` were
  ;; disjoint by construction — every entry was a patch to-review skips, and this
  ;; lookup asked about precisely the complement, so it could never return
  ;; anything and the ALREADY SETTLED block never reached a warden.
  (let [reviews  [{:target {:label "a" :patch-hash "h-a"}}
                  {:target {:label "b" :patch-hash "h-b"}}]
        ;; Round 1: `a` had one finding declined and one parked, so it is
        ;; decided about the first and still owes the second; `b` owes nothing.
        findings [{:id "aa11" :title "already answered" :from-layer "a"
                   :owner-layer "a" :disposition :declined}
                  {:id "bb22" :title "the open question" :from-layer "a"
                   :owner-layer "a" :disposition :park}]
        written  (reduce (fn [c [t status]]
                           (cache/record c (:patch-hash t)
                                         {:status   status
                                          :label    (:label t)
                                          :answered (stages/answered-for (:label t)
                                                                         [findings])}))
                         {}
                         (stages/reviewed-statuses reviews findings []))
        ;; Round 2, at the same content: nothing landed on `a`, because a park
        ;; is what the loop has no move for.
        {:keys [review skipped]} (stages/to-review written
                                                   [{:label "a" :patch-hash "h-a"}
                                                    {:label "b" :patch-hash "h-b"}])]
    (is (= ["b"] (map :label skipped))
        "b owed nothing, so it converged and the next round does not look at it")
    (is (= [{:label "a"
             :answered [{:id "aa11" :title "already answered" :disposition :declined}]}]
           (stages/answered-by-layer
            {:cache written :reviews (mapv (fn [t] {:target t}) review)}))
        "a is reviewed again because a park stands on it — and it arrives carrying the answer")))

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
                  layers/current-op         (fn [_] "0a1b2c3d")
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
                layers/current-op        (fn [_] "0a1b2c3d")
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
  (doseq [k [:misplaced-cut :duplicated-across-layers]]
    (is (= :fold (:remedy (stages/reshape-plan two-layer-stack
                                               {:kind k :layers ["lower" "upper"]})))
        (str (name k) " has no order to correct — the boundary is the defect"))))

(deftest a-fold-refuses-a-span-with-holes
  ;; A fold removes every boundary it spans, so an unnamed layer in between is
  ;; absorbed with them. Attempted across a nine-layer stack it is a squash jj
  ;; can only answer with a conflict, and the round's one attempt is gone.
  (let [p (stages/reshape-plan gapped-stack
                               {:kind :misplaced-cut :layers ["a" "c" "d"]})]
    (is (= :span-has-holes (:refused p)))
    (is (re-find #"absorb b" (:because p)) "and it names what would have been absorbed"))
  (is (= :fold (:remedy (stages/reshape-plan gapped-stack
                                             {:kind :misplaced-cut :layers ["b" "c"]})))
      "adjacent layers still fold — the boundary between them is the only one lost"))

(deftest a-seam-across-a-gapped-span-is-moved-rather-than-refused
  ;; The case: an upper layer rewrote a migration whose checksum a lower layer's
  ;; deploy had already recorded. Folding them would absorb seven layers neither
  ;; the reviewer nor the warden named; moving that one file down absorbs
  ;; nothing, and is the repair both of them actually described.
  (let [p (stages/reshape-plan gapped-stack
                               {:kind :misplaced-cut :layers ["a" "d"]
                                :file "/w/resources/db/V20260825__diary.sql"})]
    (is (= :move (:remedy p)))
    (is (= "/w/resources/db/V20260825__diary.sql" (:file p)))
    (is (= "a" (:slug (:lower p))) "down into the bottom-most named layer")
    (is (= "d" (:slug (:upper p))))))

(deftest a-seam-with-no-file-to-move-is-still-a-judgement
  (let [p (stages/reshape-plan gapped-stack
                               {:kind :misplaced-cut :layers ["a" "d"] :file "  "})]
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

(deftest a-recut-asking-for-a-move-the-loop-does-not-have-is-refused
  ;; The case: a finding whose body said moving the whole document down could
  ;; not resolve the dependency, because the section assumed both fixes already
  ;; existed, and asked for a SPLIT. Its kind alone routes to a fold, and the
  ;; fold is the one move its author had ruled out — so it ran.
  (let [p (stages/reshape-plan two-layer-stack
                               {:kind :duplicated-across-layers :remedy :split
                                :layers ["lower" "upper"]})]
    (is (= :remedy-mismatch (:refused p))
        "a remedy the loop does not have is a decision for a human, not a fold")
    (is (nil? (:remedy p)) "and no plan, so `run-reshape-stage` attempts nothing")
    (is (str/includes? (:because p) "asks for a split")
        "the human deciding it is told what was actually asked for")
    (is (str/includes? (:because p) "not a move the loop has")
        "and that the loop has nothing to offer it, rather than a preference")))

(deftest a-recut-asking-for-another-kinds-move-is-refused-too
  ;; Both halves of the refusal are the same rule — the loop performs the move
  ;; the finding names — and a reorder cannot resolve a duplication any more
  ;; than a split can, so substituting the fold here is the same substitution.
  (let [p (stages/reshape-plan two-layer-stack
                               {:kind :duplicated-across-layers :remedy :reorder
                                :layers ["lower" "upper"]})]
    (is (= :remedy-mismatch (:refused p)))
    (is (str/includes? (:because p) "never another one")
        "and it says the loop does not substitute, not that the move is unknown")))

(deftest a-recut-naming-its-kinds-own-move-is-planned
  (is (= :fold (:remedy (stages/reshape-plan two-layer-stack
                                             {:kind :duplicated-across-layers
                                              :remedy :fold
                                              :layers ["lower" "upper"]})))
      "agreement between the finding and its kind is the case the stage acts on"))

(deftest a-recut-naming-no-remedy-at-all-still-plans-from-its-kind
  ;; The refusal is about a remedy a finding NAMES. A finding carrying none —
  ;; every one raised before the field existed, and every one a test writes —
  ;; is not a finding that asked for something else.
  (is (= :fold (:remedy (stages/reshape-plan two-layer-stack
                                             {:kind :duplicated-across-layers
                                              :layers ["lower" "upper"]})))))

(deftest a-kind-with-no-move-answers-with-its-own-reason
  ;; Not `remedy-mismatch`: that this kind is repaired by completing a layer is
  ;; the more useful thing to tell whoever reads the park, and it is true
  ;; whatever the finding asked for.
  (is (= :no-remedy
         (:refused (stages/reshape-plan two-layer-stack
                                        {:kind :broken-intermediate :remedy :fold
                                         :layers ["lower" "upper"]})))))

(deftest a-recut-refused-for-its-remedy-becomes-a-park
  ;; The whole point of refusing: a recut is withheld from the fixers on
  ;; purpose, so a refusal that went only to the :reshapes array would leave the
  ;; finding with no path at all.
  (let [ctx {:config {:cwd "/w" :base "main"} :iter 3
             :findings [{:handle "h-1" :disposition :recut
                         :kind :duplicated-across-layers :remedy :split
                         :layers ["lower" "upper"]
                         :title "the fix-results section belongs above both fixes"}]}]
    (with-redefs [stages/session-stack (fn [_ _] two-layer-stack)]
      (let [out   ((:run stages/reshape-stage) ctx)
            parks (get-in out [:carry :parks])]
        (is (= ["remedy-mismatch"] (mapv :outcome (:reshapes out))))
        (is (= 3 (:since (parks "h-1"))))
        (is (str/includes? (:because (parks "h-1")) "asks for a split"))))))

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
                        {:handle "h-2" :disposition :recut :kind :misplaced-cut
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
                        {:handle "h-2" :disposition :recut :kind :misplaced-cut
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

(deftest a-round-that-reshaped-re-pins-the-revision-it-reviewed
  ;; The reshape rewrites the very commit the round pinned, so a pin left alone
  ;; makes the fix stage read the loop's own rewrite as an outside rebase and
  ;; throw the round's repairs away. Two runs ended that way with a fix plan
  ;; nobody attempted.
  (let [ctx {:config {:cwd "/w" :base "main"} :iter 1
             :reviewed-at "THENREV"
             :findings [{:handle "h-1" :disposition :recut :kind :order-dependence
                         :layers ["lower" "upper"] :title "t1"}]}]
    (with-redefs [stages/session-stack (fn [_ _] two-layer-stack)
                  layers/descends-from? (fn [_ _] true)
                  layers/reorder! (fn [& _] {:ok? true})
                  layers/restore-top! (fn [& _] nil)
                  layers/resolve-rev (fn [_ _] "AFTERREV")]
      (is (= "AFTERREV" (:reviewed-at ((:run stages/reshape-stage) ctx)))
          "the reviews still stand — a reshape moves boundaries, not content"))))

(deftest a-reshape-the-stack-refused-re-pins-too
  ;; `restore-top!` parks a fresh @ whether the attempt was kept or rolled back,
  ;; and jj drops the empty commit the round pinned either way. Keying the
  ;; re-pin on the attempt having SUCCEEDED would leave a recut jj refused
  ;; ending the run on a drift the loop caused.
  (let [ctx {:config {:cwd "/w" :base "main"} :iter 1
             :reviewed-at "THENREV"
             :findings [{:handle "h-1" :disposition :recut :kind :order-dependence
                         :layers ["lower" "upper"] :title "t1"}]}]
    (with-redefs [stages/session-stack (fn [_ _] two-layer-stack)
                  layers/descends-from? (fn [_ _] true)
                  layers/reorder! (fn [& _] {:ok? false :reason "it conflicts"})
                  layers/fold! (fn [& _] {:ok? false :reason "it conflicts"})
                  layers/restore-top! (fn [& _] nil)
                  layers/resolve-rev (fn [_ _] "AFTERREV")]
      (let [out ((:run stages/reshape-stage) ctx)]
        (is (= ["refused"] (mapv :outcome (:reshapes out))))
        (is (= "AFTERREV" (:reviewed-at out)))))))

(deftest a-tree-that-moved-under-the-round-keeps-its-pin
  ;; The guard has to stay able to see the rebase it was built for. Re-pinning
  ;; on the way out regardless would launder an outside rewrite into a tree the
  ;; round believes its reviewers read, which is the failure the guard exists
  ;; to prevent rather than the one this re-pin fixes.
  (let [ctx {:config {:cwd "/w" :base "main"} :iter 1
             :reviewed-at "THENREV"
             :findings [{:handle "h-1" :disposition :recut :kind :order-dependence
                         :layers ["lower" "upper"] :title "t1"}]}]
    (with-redefs [stages/session-stack (fn [_ _] two-layer-stack)
                  layers/descends-from? (fn [_ _] false)
                  layers/reorder! (fn [& _] {:ok? true})
                  layers/restore-top! (fn [& _] nil)
                  layers/resolve-rev (fn [_ _] "AFTERREV")]
      (is (= "THENREV" (:reviewed-at ((:run stages/reshape-stage) ctx)))))))

(deftest a-round-that-reshaped-nothing-does-not-re-pin
  ;; Nothing moved the working copy, so there is nothing for the pin to follow
  ;; — and answering the drift guard with a revision no reviewer read is what
  ;; the pin exists to stop.
  (let [ctx {:config {:cwd "/w" :base "main"} :iter 1
             :reviewed-at "THENREV"
             :findings [{:handle "h-1" :disposition :recut :kind :claim-falsified
                         :layers ["a" "d"] :title "t1"}]}]
    (with-redefs [stages/session-stack (fn [_ _] gapped-stack)
                  layers/descends-from? (fn [_ _] true)
                  layers/resolve-rev (fn [_ _] "AFTERREV")]
      (is (= "THENREV" (:reviewed-at ((:run stages/reshape-stage) ctx)))))))

(defn- probe-stack!
  "A real two-layer jj stack in a temp dir — `main` at the base, `sess--lower`
   and `sess--upper` above it, and the working copy parked on an empty commit
   on top, which is the shape the review stage pins `@` from.

   Real jj because the defect is in jj's rules and not in this namespace's: the
   empty commit `@` sits on is dropped as soon as the working copy moves off
   it, and that is what takes the round's pin out of `<pinned>::@`."
  []
  (let [dir (fs/create-temp-dir {:prefix "nido-reshape-pipeline"})]
    (jj/jj! dir "git" "init" ".")
    (spit (str (fs/path dir "base.txt")) "base\n")
    (jj/jj! dir "commit" "-m" "base")
    (jj/jj! dir "bookmark" "create" "main" "-r" "@-")
    (spit (str (fs/path dir "lower.txt")) "lower\n")
    (jj/jj! dir "commit" "-m" "lower layer")
    (jj/jj! dir "bookmark" "create" "sess--lower" "-r" "@-")
    (spit (str (fs/path dir "upper.txt")) "upper\n")
    (jj/jj! dir "commit" "-m" "upper layer")
    (jj/jj! dir "bookmark" "create" "sess--upper" "-r" "@-")
    dir))

(deftest a-round-that-folded-two-layers-still-reaches-its-fixers
  ;; The interaction no unit test could see: every drift test stubs
  ;; `descends-from?` and every reshape test drives its stage alone, so the loop
  ;; folding two layers and then refusing to repair either of them stayed
  ;; invisible until a run did it — twice, and deterministically, since the
  ;; reshape stage shipped.
  (let [dir      (probe-stack!)
        launched (atom [])
        ctx      {:config {:cwd dir :base "main" :run-id "r1"} :iter 1
                  :reviewed-at (layers/resolve-rev dir "@")
                  :toc []
                  :findings [{:handle "h-1" :id "h-1" :disposition :recut
                              :kind :duplicated-across-layers
                              :layers ["lower" "upper"]
                              :title "both layers carry the same change"}
                             {:handle "h-2" :id "h-2" :disposition :fix
                              :owner-layer "lower" :priority "P2"
                              :file "lower.txt" :line 1
                              :title "[P2] the lower layer reads an undefined name"}]}]
    (try
      (with-redefs [stages/session-stack (fn [cwd base] (layers/stack cwd "sess" base))
                    agent/launch! (fn [m] (swap! launched conj m) {:num-turns 0})]
        (let [reshaped ((:run stages/reshape-stage) ctx)
              out      ((:run stages/fix-stage) reshaped)]
          (is (= ["fold"] (mapv :outcome (:reshapes reshaped)))
              "the fold is the precondition — without it the round never drifts")
          (is (not= :workspace-drifted (:status out))
              "the loop's own fold is not somebody else moving the tree")
          (is (= 1 (count @launched))
              "the round's one repair was handed to a fixer rather than discarded")))
      (finally (fs/delete-tree dir)))))

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

(deftest the-composition-target-keeps-the-range-its-key-was-built-from
  ;; The key is derived, so on its own it can say only that something moved.
  ;; Each layer's half of the cut reaches report.json on that layer's row; this
  ;; is the other half, and without it a composition that failed to skip over
  ;; unchanged layers names no component.
  (with-redefs [layers/patch-hash (fn [_ from to] (str "h-" from ".." to))]
    (let [t (last (stages/with-patch-hashes
                    "/w" (stack-targets [["b" "c1"] ["c1" "c2"] ["b" "@"]])))]
      (is (= "h-b..@" (:range-hash t)))
      (is (not= (:range-hash t) (:patch-hash t))
          "the key folds in the cut as well; the range hash is the patch alone"))))

(deftest a-skipped-target-carries-when-it-converged-not-a-foreign-runs-round
  ;; The cache outlives the run, so the round on an entry belongs to whichever
  ;; run wrote it: a one-round report carried three rows reading `converged-at
  ;; 2`. A timestamp is a fact the reader can place — this entry is six hours
  ;; old, so it is not this run's.
  (let [{:keys [review skipped]}
        (stages/to-review {"h1" {:status :converged :round 4
                                 :at "2026-09-07T10:44:41Z"}}
                          [{:label "core" :patch-hash "h1"}
                           {:label "wiring" :patch-hash "h2"}])]
    (is (= ["wiring"] (mapv :label review)))
    (is (= ["2026-09-07T10:44:41Z"] (mapv :converged-at skipped)))))

(deftest an-entry-with-no-timestamp-stamps-nothing-rather-than-nil
  ;; The store is append-only, so it holds entries written before it recorded
  ;; one. A row that says nothing is honest; a row reading `converged-at nil`
  ;; asserts the field and answers it with a hole.
  (let [{:keys [skipped]}
        (stages/to-review {"h1" {:status :converged :round 4}}
                          [{:label "core" :patch-hash "h1"}])]
    (is (= [false] (mapv #(contains? % :converged-at) skipped)))))

(deftest the-composition-target-carries-this-runs-earlier-composition-findings
  ;; Only findings the composition pass itself made — a layer's own finding is
  ;; already answered where it was raised, and repeating it here would tell the
  ;; pass it reported something it never did.
  (let [history [{:iter 1 :findings [{:from-layer "stack" :title "the seam" :kind "misplaced-cut"}
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
                                      :disposition :park :because "for a human"
                                      :owner-layer "source-row-variants"}] 1)
        r2 (stages/carried-parks r1 [{:handle "h9" :disposition :fix}] 2)
        r3 (stages/carried-parks r2 [{:handle "h1" :title "the seam"
                                      :disposition :declined}] 3)]
    (is (= 1 (get-in r1 ["h1" :since])))
    (is (= "for a human" (get-in r1 ["h1" :because])))
    (is (= "source-row-variants" (get-in r1 ["h1" :owner-layer]))
        "the layer rides along so convergence can hold that layer's patch back")
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

(deftest a-drift-refusal-records-the-fix-plan-it-never-reached
  ;; The stop happens before any fixer is positioned, so every layer the plan
  ;; held is a layer nobody was launched for. Without the list the phase reports
  ;; an empty fix list and nothing else — the same shape a round with no work in
  ;; it produces — and the run that discarded a full plan is the one whose
  ;; repairs a reader most needs named.
  (with-redefs [stages/session-stack (fn [& _] two-layer-stack)
                layers/descends-from? (fn [_ _] false)
                layers/resolve-rev (fn [_ _] "NOWREV")
                agent/launch! (fn [_] (throw (ex-info "no fixer should launch" {})))]
    (let [ctx ((:run stages/fix-stage)
               {:config {:cwd "/w" :run-id "r1"} :iter 2
                :reviewed-at "THENREV"
                :findings [{:id "aa11" :handle "h-1" :title "x" :disposition :fix
                            :owner-layer "lower"}
                           {:id "bb22" :handle "h-2" :title "y" :disposition :fix
                            :owner-layer "upper"}]})]
      (is (= [{:layer "lower" :handed ["h-1"]}
              {:layer "upper" :handed ["h-2"]}]
             (:unattempted ctx))
          "both layers, named — the whole plan, because none of it was reached"))))

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

;; ---- what an aborted round leaves behind ---------------------------------

(deftest salvaged-statuses-converge-a-layer-that-read-and-reported-nothing
  ;; A reviewer that read a manifest and found nothing has issued a clean bill
  ;; on that exact patch, and a sibling's billing quota does not retract it.
  ;; Recording it is what stops the next run re-paying for a review that already
  ;; happened — one round threw away two finished layer reviews, 265,708 codex
  ;; tokens between them, because the third reviewer could not be launched.
  (let [out (stages/salvaged-statuses
             [{:target {:label "a" :patch-hash "h-a"} :findings []}
              {:target {:label "b" :patch-hash "h-b"} :findings [{:title "a bug"}]}])]
    (is (= [["a" :converged] ["b" :partial]]
           (map (fn [[t s]] [(:label t) s]) out))
        "a target that reported something is re-reviewed; only the quiet one
         may be skipped")))

(deftest salvaged-statuses-never-converge-the-composition-target
  ;; It converges on `nothing anywhere is open`, and a round that lost a
  ;; reviewer cannot know that — the target nobody read is exactly the one that
  ;; might have had something for it.
  (is (= [["stack" :partial]]
         (map (fn [[t s]] [(:label t) s])
              (stages/salvaged-statuses
               [{:target {:label "stack" :patch-hash "h-s" :stack? true} :findings []}])))))

(deftest salvaged-statuses-record-nothing-about-a-patch-nobody-read
  ;; Convergence is a memory of content having been reviewed. An empty patch has
  ;; no content to remember, and an entry keyed on nil is a claim about every
  ;; patch and about none.
  (is (= [] (stages/salvaged-statuses
             [{:target {:label "a" :patch-hash "h-a"} :status :nothing-to-review
               :findings []}
              {:target {:label "b" :patch-hash nil} :findings []}]))))

(deftest an-aborted-fan-out-records-the-reviews-it-already-paid-for
  ;; The stage has no round after it — the engine short-circuits on the throw —
  ;; so this is the last point at which anything can be kept. Before, the whole
  ;; round evaporated: the cache file kept the previous round's mtime and a
  ;; re-run after the quota reset re-reviewed every layer from scratch.
  (let [written (atom nil)
        events  (atom [])]
    (with-redefs [layers/patch-hash    (fn [_ from to] (str "h-" from "-" to))
                  codex/merge-base     (fn [& _] "FORK")
                  layers/resolve-rev   (fn [& _] "AT")
                  layers/brief         (fn [& _] nil)
                  codex/changed-files  (fn [& _] [])
                  stages/session-stack (fn [& _] [{:bookmark "s--a" :slug "a" :tip "cA"}
                                                  {:bookmark "s--b" :slug "b" :tip "cB"}])
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  cache/read-cache     (fn [& _] {})
                  cache/write!         (fn [_ _ c] (reset! written c) true)
                  codex/review!        (fn [{:keys [label]}]
                                         (if (= "b" label)
                                           (throw (ex-info "You've hit your usage limit."
                                                           {:reason :reviewer-unavailable}))
                                           {:status nil :findings [] :manifest "x"}))]
      (let [thrown (try ((:run stages/review-stage)
                         {:config {:cwd "/w" :base "main" :run-id "r"
                                   :emit #(swap! events conj %)}
                          :iter 2 :history []})
                        nil
                        (catch clojure.lang.ExceptionInfo e e))
            entries (vals @written)]
        (is (= :reviewer-unavailable (:reason (ex-data thrown)))
            "the round still fails — salvaging is not swallowing")
        (is (= [["a" :converged]]
               (map (juxt :label :status) (filter #(= :converged (:status %)) entries)))
            "layer a read its patch and reported nothing, so a re-run skips it")
        (is (= #{"a" "stack"} (set (map :label entries)))
            "the composition target is recorded as still owing something, and
             the layer nobody reviewed is not recorded at all")
        (is (= 2 (:round (first (filter #(= "a" (:label %)) entries))))
            "stamped with the round it was reviewed in, like any other entry")
        (is (some #(and (= :target-moved (:event %)) (= "b" (:label %))
                        (= "error" (:status %)))
                  @events)
            "the report names the reviewer that died, not just the phase — an
             aborted round used to leave every unfinished row reading `running`")))))

(deftest a-layer-is-told-what-a-fixer-already-landed-on-it
  ;; :handed has recorded the join since it was added and no reviewer used it,
  ;; so nobody was ever asked whether a fix closed what it was handed — a swept
  ;; defect came back at the same window three rounds running.
  (let [history [{:iter 1
                  :fixes [{:layer "core" :commit "4d52d218" :handed ["h1"]
                           :account "fixed the enum check; V243 is untouched"}]
                  :findings [{:handle "h1" :id "aa11" :title "bad enum reaches the insert"
                              :sweep true}]}]
        [core wiring] (stages/with-fix-memory
                        [{:label "core"} {:label "wiring"}] history {})
        [prior] (:prior-fixes core)]
    (is (= 1 (:round prior)))
    (is (= "4d52d218" (:commit prior)))
    (is (= [{:title "bad enum reaches the insert" :sweep true}] (:findings prior))
        "the words the fixer saw, from the round it saw them in")
    (is (= "fixed the enum check; V243 is untouched" (:account prior)))
    (is (nil? (:prior-fixes wiring)) "a layer no fixer touched is told nothing")))

(deftest the-warden-is-shown-the-accounts-of-every-layer-not-just-one
  ;; with-fix-memory keys accounts by the layer the fix landed on, so the
  ;; sentence a fixer is ordered to write about somewhere ELSE reaches only the
  ;; reviewer that cannot go there. The warden holds the file lists; it is the
  ;; one reader that can place a named path in another layer.
  (let [captured (atom nil)]
    (with-redefs [agent/launch! (fn [{:keys [first-message]}]
                                  (reset! captured first-message)
                                  {:num-turns 1 :result-error? false
                                   :result-text "```json\n{\"decision\":\"stop\",\"reason\":\"r\"}\n```"})
                  stages/discover-design-record (fn [_] nil)
                  stages/project+ws-from-cwd (fn [_] nil)]
      ((:run stages/warden-stage)
       {:config {:cwd "/w" :run-id "r1"} :iter 2 :findings [{:title "x"}]
        :history [{:iter 1
                   :fixes [{:layer "github-outcomes" :commit "44249c19" :handed ["h1"]
                            :account "siblings outside this change: deploy_hotfix.yml:211"}
                           {:layer "notion-lifecycle" :commit "11fcfa55" :handed ["h2"]
                            :account "the reopening sweep is unchanged"}]
                   :findings [{:handle "h1" :title "the recorder is skipped" :sweep true}
                              {:handle "h2" :title "the window is wrong"}]}]})
      (is (str/includes? @captured "deploy_hotfix.yml:211")
          "the account naming a path is what the warden is here to attribute")
      (is (str/includes? @captured "notion-lifecycle")
          "every layer's account, not the one whose reviewer would have got it anyway")
      (is (= 1 (count (re-seq #"deploy_hotfix\.yml:211" @captured)))
          "and once — the accounts used to ride along whole inside the pr-str of
           the round history, where nothing told the warden they were there"))))

(deftest a-class-already-swept-is-marked-with-the-rounds-that-swept-it
  ;; A sweep that comes back has disproved its own remedy, and only the run's
  ;; history says so. The fixer starts cold every round, so without this the
  ;; third sweep of one class is asked for in exactly the words of the first.
  (let [history [{:iter 2 :findings [{:handle "h1" :id "aa11" :sweep true}
                                     ;; Two instances of one class in a single
                                     ;; round: same-as folds both onto h1, and
                                     ;; naming round 2 twice would read as two
                                     ;; failed sweeps.
                                     {:handle "h1" :id "aa12" :sweep true}
                                     {:handle "h2" :id "bb22"}]}
                 {:iter 3 :findings [{:handle "h1" :id "cc33" :sweep true}]}]
        [repeat-of-h1 h2 fresh]
        (stages/with-sweep-memory
          [{:handle "h1" :id "dd44" :sweep true}
           {:handle "h2" :id "bb22" :sweep true}
           {:id "ee55" :sweep true}]
          history)]
    (is (= [2 3] (:swept-before repeat-of-h1))
        "the handle is what survives a rewording, so the class is what is matched, not the id")
    (is (nil? (:swept-before h2))
        "a class raised in an earlier round but never swept has had no remedy fail")
    (is (nil? (:swept-before fresh)))))

(deftest a-run-that-has-swept-nothing-marks-nothing
  (let [findings [{:handle "h1" :sweep true}]]
    (is (= findings (stages/with-sweep-memory findings [])))
    (is (= findings (stages/with-sweep-memory
                      findings [{:iter 1 :findings [{:handle "h1"}]}])))))

(deftest the-fixer-is-told-when-its-sweep-is-a-repeat
  ;; :history is in scope at the fix-prompt call site and was rendered only to
  ;; the reviewer, so the one reader that could change the remedy was the one
  ;; never shown that the last remedy had failed.
  (let [seen (atom nil)]
    (with-redefs [agent/launch! (fn [opts]
                                  (reset! seen (:first-message opts))
                                  {:num-turns 3 :result-error? false :result-text "no"})
                  stages/working-copy-dirty? (fn [_] false)
                  jj/jj! (fn [& _] {:exit 0 :out "" :err ""})]
      ((:run stages/fix-stage)
       {:config {:cwd "/w" :run-id "r1"} :iter 3
        :history [{:iter 2 :findings [{:handle "h1" :id "aa11" :sweep true}]}]
        :findings [{:id "bb22" :handle "h1" :title "x" :body "y"
                    :sweep true :disposition :fix}]})
      (is (str/includes? @seen "already swept in round 2")
          "the memory has to reach the prompt, not merely be derivable beside it"))))

(deftest the-fixer-is-told-what-its-own-round-settled
  ;; The rulings are in scope at the fix-prompt call site and reached only the
  ;; reviewer and the warden, so the one reader able to undo a decision was the
  ;; one never shown it. This round's own rulings are the case that matters: the
  ;; warden rules and the fixers run inside one round, and :history does not
  ;; hold the round until the fixes have landed.
  (let [seen (atom nil)]
    (with-redefs [agent/launch! (fn [opts]
                                  (reset! seen (:first-message opts))
                                  {:num-turns 3 :result-error? false :result-text "no"})
                  stages/working-copy-dirty? (fn [_] false)
                  jj/jj! (fn [& _] {:exit 0 :out "" :err ""})]
      ((:run stages/fix-stage)
       {:config {:cwd "/w" :run-id "r1"} :iter 1
        :findings [{:id "aa11" :title "the cue is read twice" :body "y"
                    :disposition :fix}
                   {:id "0f2ea8d2" :title "changelog contradicts the branch"
                    :disposition :deviation :of "the pre-branch turn-detection modes"
                    :file "recording.clj" :line-start 1 :line-end 24}]})
      (is (str/includes? @seen "ALREADY DECIDED")
          "a decision has to reach the prompt, not merely be derivable beside it")
      (is (str/includes? @seen "recording.clj:1-24")
          "a decision the fixer cannot locate is one it can only honour by accident")
      (is (str/includes? @seen "the pre-branch turn-detection modes"))
      (is (not (str/includes? @seen "[P1] changelog contradicts the branch"))
          "settled is not work: the same finding handed as both is a contradiction"))))

(deftest a-flat-branchs-fix-reaches-the-target-that-reviews-it
  ;; fix-plan groups an unlayered branch under nil and review-targets labels its
  ;; one target "stack". The two vocabularies meet only here, and matching on
  ;; the review label alone loses the memory in the commonest case there is.
  (let [history [{:iter 1 :fixes [{:layer nil :commit "c1" :handed ["h1"]}]
                  :findings [{:handle "h1" :title "the defect"}]}]
        [whole] (stages/with-fix-memory
                  [{:label "stack" :stack? true}] history {})]
    (is (= ["the defect"] (mapv :title (:findings (first (:prior-fixes whole)))))))

  (testing "and a stacked branch's composition target is not the flat one"
    (let [history [{:iter 1 :fixes [{:layer nil :commit "c1" :handed ["h1"]}]
                    :findings [{:handle "h1" :title "the defect"}]}]
          [whole] (stages/with-fix-memory
                    [{:label "stack" :stack? true :composition {:layers [{:label "core"}]}}]
                    history {})]
      (is (nil? (:prior-fixes whole))))))

(deftest a-run-with-no-landed-fix-changes-nothing
  (let [targets [{:label "core"}]]
    (is (= targets (stages/with-fix-memory targets [] {})))
    (is (= targets (stages/with-fix-memory targets [{:iter 1 :fixes [] :findings []}] {})))))

(deftest fix-memory-does-not-move-the-cache-key
  ;; A value that changes every round folded into the key switches the cache
  ;; off rather than making it correct.
  (with-redefs [layers/patch-hash (fn [_ _ _] "SAMEPATCH")]
    (let [cold (first (stages/with-patch-hashes "/w" [{:label "core" :from "a" :to "b"}]))
          warm (first (stages/with-patch-hashes
                        "/w" [{:label "core" :from "a" :to "b"
                               :prior-fixes [{:round 1 :commit "c1"}]}]))]
      (is (= (:patch-hash cold) (:patch-hash warm))))))

(deftest the-reviewer-is-handed-what-a-fixer-landed-on-its-target
  (let [seen (atom nil)]
    (with-redefs [layers/patch-hash (fn [& _] nil)
                  codex/merge-base (fn [& _] "BASEREV")
                  codex/review! (fn [opts] (reset! seen opts)
                                  {:status :clean :findings []})]
      ((:run stages/review-stage)
       {:config {:cwd "/w" :base "main" :run-id "r1"} :iter 2
        :history [{:iter 1 :fixes [{:layer nil :commit "c1" :handed ["h1"]}]
                   :findings [{:handle "h1" :title "the defect"}]}]})
      (is (= ["the defect"] (mapv :title (:findings (first (:prior-fixes @seen)))))
          "the memory reaches the reviewer, not only the target it was stamped on"))))

(deftest the-review-stage-reads-a-refusal-off-the-carry-not-the-history
  ;; The seam the whole channel hangs on. A round appends to :history only when
  ;; a fix LANDED, so a refused repair reaches the next round's reviewer through
  ;; :carry or through nothing — and a `with-fix-memory` that is right while the
  ;; call site passes it nothing is the same silence with more code.
  (let [seen (atom nil)]
    (with-redefs [layers/patch-hash (fn [& _] nil)
                  codex/merge-base (fn [& _] "BASEREV")
                  codex/review! (fn [opts] (reset! seen opts)
                                  {:status :clean :findings []})]
      ((:run stages/review-stage)
       {:config {:cwd "/w" :base "main" :run-id "r1"} :iter 2
        :history []
        :carry {:rolled-back
                {nil {:layer nil :since 1 :conflicted ["lktsqrrn"]
                      :account "added the digest to all five contracts"
                      :findings [{:id "h1" :title "the digest is not in the contracts"}]}}}})
      (is (= ["lktsqrrn"] (:refused (first (:prior-fixes @seen))))
          "the reviewer about to read the unchanged code is the one reader that
           can turn a refused repair back into a finding"))))

(deftest a-fixer-that-refuses-names-what-it-was-handed
  ;; :handed on the decline row for the same reason it is on a landed fix and on
  ;; an unattempted layer: the four lists are one account of every :fix ruling
  ;; the round held, and this was the row that named a layer and nothing else.
  (with-redefs [agent/launch! (fn [_] {:num-turns 3 :result-error? false
                                       :result-text "the seam spans two layers"})
                stages/working-copy-dirty? (fn [_] false)
                jj/jj! (fn [& _] {:exit 0 :out "" :err ""})]
    (let [ctx ((:run stages/fix-stage)
               {:config {:cwd "/w" :run-id "r1"} :iter 2
                :findings [{:id "aa11" :handle "h1" :title "x" :disposition :fix}]})]
      (is (= ["h1"] (:handed (first (:declined ctx))))))))

(deftest a-fixers-refusal-is-carried-to-the-next-round
  ;; A fixer that changes nothing leaves the finding at :fix, so the round after
  ;; it re-derives the same repair from a fresh session and puts it to a warden
  ;; that has never read the refutation the last fixer spent its turns building.
  (with-redefs [agent/launch! (fn [_] {:num-turns 3 :result-error? false
                                       :result-text "Datastar binds $ to one global root"})
                stages/working-copy-dirty? (fn [_] false)
                jj/jj! (fn [& _] {:exit 0 :out "" :err ""})]
    (let [ctx ((:run stages/fix-stage)
               {:config {:cwd "/w" :run-id "r1"} :iter 2
                :findings [{:id "aa11" :handle "h1" :title "$ is not per element"
                            :disposition :fix}]})
          entry (get-in ctx [:carry :fixer-declines nil])]
      (is (= 2 (:since entry)))
      (is (= "Datastar binds $ to one global root" (:reason entry)))
      (is (= [{:id "h1" :title "$ is not per element"}] (:findings entry))
          "named by handle, as :handed is, so the two point at one finding"))))

(deftest a-repair-the-stack-refused-is-carried-to-the-next-round
  ;; The channel a decline already had, over the other thing a fixer can leave
  ;; behind. A rolled-back repair leaves the finding at :fix and the code
  ;; byte-identical, so without this the round after re-reads the same patch
  ;; cold: one did, and returned `correct` on a P2 whose repair had been
  ;; written and refused twenty minutes earlier.
  (with-redefs [agent/launch! (fn [_] {:num-turns 4 :result-error? false
                                       :result-text "added the digest to all five contracts"})
                stages/working-copy-dirty? (fn [_] true)
                stages/session-stack (fn [_ _] two-layer-stack)
                jj/jj! (jj-scripted [["xuspsuww"] [] []])]
    (let [ctx ((:run stages/fix-stage)
               {:config {:cwd "/w" :run-id "r1" :base "main"} :iter 2
                :findings [{:id "aa11" :handle "h1" :title "the digest is not in the contracts"
                            :sweep true :disposition :fix :owner-layer "lower"}
                           {:id "bb22" :title "y" :disposition :fix :owner-layer "upper"}]})
          entry (get-in ctx [:carry :rolled-back "lower"])]
      (is (= 2 (:since entry)))
      (is (= ["xuspsuww"] (:conflicted entry))
          "what the rebase collided with — the layer order is what has to move")
      (is (= "added the digest to all five contracts" (:account entry))
          "five minutes of the fixer's reading of an edit nobody can see again")
      (is (= [{:id "h1" :title "the digest is not in the contracts" :sweep true}]
             (:findings entry))
          "named by handle, as :handed is, so the carry and the report row point
           at one finding rather than at two spellings of it a round apart")
      (is (nil? (get-in ctx [:carry :rolled-back "upper"]))
          "the layer whose repair the stack took has nothing to carry"))))

(deftest the-next-rounds-reviewer-of-that-layer-is-told-the-repair-was-refused
  ;; The carry is only worth writing if it reaches the one reader that can act
  ;; on it. It cannot come off :history — a round appends there only when a fix
  ;; LANDED, which is exactly what did not happen.
  (let [[lower upper]
        (stages/with-fix-memory
          [{:label "lower"} {:label "upper"}]
          []
          {"lower" {:layer "lower" :since 1 :conflicted ["lktsqrrn"]
                    :account "added the digest to all five contracts"
                    :findings [{:id "h1" :title "the digest is not in the contracts"
                                :sweep true}]}})
        [prior] (:prior-fixes lower)]
    (is (= ["lktsqrrn"] (:refused prior))
        "the refusal travels with the row, so the reviewer is not left to infer
         from a missing commit that the repair is absent")
    (is (= "added the digest to all five contracts" (:account prior)))
    (is (= [{:title "the digest is not in the contracts" :sweep true}]
           (:findings prior))
        "the words the fixer saw, which is what a reviewer matches its own
         reading of those lines against")
    (is (nil? (:prior-fixes upper)) "a layer nothing was refused on is told nothing"))

  (testing "a layer that had one repair land and one refused gets both, in round order"
    (let [[core] (stages/with-fix-memory
                   [{:label "core"}]
                   [{:iter 2 :fixes [{:layer "core" :commit "c2" :handed ["h2"]}]
                     :findings [{:handle "h2" :title "the second"}]}]
                   {"core" {:layer "core" :since 1 :conflicted ["x1"]
                            :findings [{:id "h1" :title "the first"}]}})]
      (is (= [1 2] (mapv :round (:prior-fixes core)))
          "one list of what has already been tried here, oldest first")
      (is (= [["x1"] nil] (mapv :refused (:prior-fixes core)))
          "and each row says for itself whether the edit is in the range"))))

(deftest a-carried-refusal-is-dropped-once-the-finding-is-settled
  ;; Same lifetime rule as a decline: the entry is about a finding, so it is
  ;; spent the moment that finding is answered. Held longer it would send the
  ;; next reviewer looking for a defect this round has just closed.
  (let [prior {"lower" {:layer "lower" :since 1 :conflicted ["x1"]
                        :findings [{:id "h1" :title "a"}]}}]
    (is (= prior (stages/carried-while-open prior [{:handle "h1" :disposition :fix}]))
        "handed back to a fixer is not an answer")
    (is (empty? (stages/carried-while-open
                 prior [{:handle "h1" :disposition :declined :because "shipping it"}]))
        "and a decision to ship it is")))

(deftest a-fixer-that-never-ran-carries-no-argument
  ;; Zero turns is a no-show, not a refutation. Carried, it would tell the next
  ;; warden a fixer argued something nobody ever said.
  (with-redefs [agent/launch! (fn [_] {:num-turns 0 :result-error? false :result-text ""})
                stages/working-copy-dirty? (fn [_] false)
                jj/jj! (fn [& _] {:exit 0 :out "" :err ""})]
    (let [ctx ((:run stages/fix-stage)
               {:config {:cwd "/w" :run-id "r1"} :iter 2
                :findings [{:id "aa11" :title "x" :disposition :fix}]})]
      (is (empty? (get-in ctx [:carry :fixer-declines]))))))

(deftest a-layer-whose-fixer-argued-resumes-rather-than-restarts
  (let [seen (atom nil)]
    (with-redefs [agent/launch! (fn [opts] (reset! seen opts)
                                  {:num-turns 4 :result-error? false :result-text "done"})
                  stages/working-copy-dirty? (fn [_] true)
                  jj/jj! (fn [& _] {:out "cid-1" :err "" :exit 0})]
      ((:run stages/fix-stage)
       {:config {:cwd "/w" :run-id "r1" :impl-session-id "impl-1"} :iter 2
        :carry {:fixer-declines {nil {:since 1 :reason "no minimal edit here is right"}}}
        :findings [{:id "aa11" :title "x" :disposition :fix}]})
      (is (true? (:resume? @seen))
          "the session holding the argument is the one to put the finding back to"))))

(deftest a-carried-decline-lives-exactly-as-long-as-the-finding-is-open
  (let [prior {"core" {:layer "core" :since 1 :reason "the bundle says otherwise"
                       :findings [{:id "h1" :title "a"} {:id "h2" :title "b"}]}}]
    (is (= [{:id "h2" :title "b"}]
           (:findings (get (stages/carried-while-open
                            prior [{:handle "h1" :disposition :declined :because "shipping it"}])
                           "core")))
        "half the batch answered leaves the other half still argued")
    (is (empty? (stages/carried-while-open
                 prior [{:handle "h1" :disposition :closed :authority "duplicate"}
                        {:handle "h2" :disposition :deviation :of "the claim"}]))
        "a layer whose every finding was settled drops out")
    (is (= prior (stages/carried-while-open prior [{:handle "h1" :disposition :fix}]))
        "a finding handed back to a fixer is not an answer to the argument")))

(deftest a-landed-fix-keeps-the-fixers-own-account
  ;; A repair and a blocked verification arrive in one message. Kept on the
  ;; decline branch alone, the second half of every such message was deleted —
  ;; one fixer reported that neither gate it was told to run could build a
  ;; classpath in that worktree, and it survived only in agent.log.
  (with-redefs [agent/launch! (fn [_] {:num-turns 4 :result-error? false
                                       :result-text "fixed; bb lint:comments cannot resolve a private dep"})
                stages/working-copy-dirty? (fn [_] true)
                jj/jj! (fn [& _] {:out "cid-1" :err "" :exit 0})]
    (let [ctx ((:run stages/fix-stage)
               {:config {:cwd "/w" :run-id "r1"} :iter 2
                :findings [{:id "aa11" :title "x" :disposition :fix}]})]
      (is (= "fixed; bb lint:comments cannot resolve a private dep"
             (:account (first (:fixes ctx))))))))

(deftest a-silent-fixer-adds-no-empty-account
  ;; An empty string on the row reads as a fixer that was asked and said
  ;; nothing, which is not what a missing result-text means.
  (with-redefs [agent/launch! (fn [_] {:num-turns 4 :result-error? false :result-text ""})
                stages/working-copy-dirty? (fn [_] true)
                jj/jj! (fn [& _] {:out "cid-1" :err "" :exit 0})]
    (let [ctx ((:run stages/fix-stage)
               {:config {:cwd "/w" :run-id "r1"} :iter 2
                :findings [{:id "aa11" :title "x" :disposition :fix}]})]
      (is (not (contains? (first (:fixes ctx)) :account))))))

(deftest the-warden-reads-a-carried-decline-and-drops-it-once-it-has-answered
  (let [captured (atom nil)
        ruling "```json\n{\"decision\":\"stop\",\"reason\":\"r\",\"findings\":[{\"id\":\"aa11\",\"disposition\":\"declined\",\"because\":\"the bundle behaves as the fixer says\"}]}\n```"]
    (with-redefs [agent/launch! (fn [{:keys [first-message]}]
                                  (reset! captured first-message)
                                  {:num-turns 3 :result-error? false :result-text ruling})
                  stages/discover-design-record (fn [_] nil)
                  stages/project+ws-from-cwd (fn [_] nil)]
      (let [ctx ((:run stages/warden-stage)
                 {:config {:cwd "/w" :run-id "r1"} :iter 2
                  :carry {:fixer-declines
                          {"core" {:layer "core" :since 1
                                   :reason "Datastar binds $ to one global root"
                                   :findings [{:id "aa11" :title "$ is not per element"}]}}}
                  :findings [{:id "aa11" :title "$ is not per element"}]})]
        (is (str/includes? @captured "Datastar binds $ to one global root")
            "the argument reaches the reader that can settle the finding")
        (is (empty? (get-in ctx [:carry :fixer-declines]))
            "and stops being carried the moment it has")))))

(deftest a-verdict-against-a-superseded-design-record-is-not-a-standing-answer
  ;; The pass is asked whether THIS design survived. A verdict reached against
  ;; the record before it was superseded answered a different question, and
  ;; handing it back would have the judge defend a yardstick nobody is using.
  (with-redefs [stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                ws/latest-entry (fn [_ _ _] {:verdict :sound :design-seq 3 :seq 9})]
    (is (= 9 (:seq (stages/discover-prior-verdict "/w" {:seq 3}))))
    (is (nil? (stages/discover-prior-verdict "/w" {:seq 4})))
    (is (nil? (stages/discover-prior-verdict "/w" {}))
        "a design record with no seq is not a record any verdict could be about")))

;; ── The standing verdict's unanswered item, back into the loop ─────────────

(defn- ledger-of
  "A ledger holding one :design record at seq 3 and one verdict against it."
  [verdict]
  (fn [_ _ kind]
    (case kind
      :design         {:seq 3}
      :design-verdict verdict
      nil)))

(deftest a-standing-verdicts-unanswered-item-becomes-the-next-runs-question
  ;; The verdict pass runs after the loop returns, so nothing in the run that
  ;; produced one can act on it. Without this it is written, read back by
  ;; discover-prior-verdict, and dropped — which is how one item was named by
  ;; two consecutive verdicts of the same branch, both times as still unraised.
  (with-redefs [stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                ws/latest-entry (ledger-of {:verdict :strained :design-seq 3 :round 4
                                            :reason "the seam is under pressure"
                                            :needs "close-turn! still tests (empty? open)"})]
    (is (= {:round 4 :verdict :strained
            :needs "close-turn! still tests (empty? open)"}
           (stages/standing-needs "/w"))))

  (testing "a verdict that named nothing outstanding seeds nothing"
    (with-redefs [stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (ledger-of {:verdict :sound :design-seq 3 :round 2
                                              :reason "implementation details"})]
      (is (nil? (stages/standing-needs "/w"))))))

(deftest a-question-put-to-a-human-is-not-seeded-to-a-reviewer
  ;; :invalidated and :standing-challenged put their :needs to a person — that
  ;; is what makes them decisions, and parked-blocker already carries one to the
  ;; gate. Seeding it here too would have a reviewer raise, and a fixer patch,
  ;; the very question somebody was asked to answer.
  (doseq [v [:invalidated :standing-challenged]]
    (with-redefs [stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (ledger-of {:verdict v :design-seq 3 :round 1
                                              :reason "the invariant cannot hold"
                                              :needs "supersede the record"})]
      (is (nil? (stages/standing-needs "/w"))
          (str "a " (name v) " verdict is a decision, not a defect to review")))))

(deftest the-standing-item-reaches-every-reviewer-that-reads-code
  (let [[core wiring stack]
        (stages/with-standing-needs
          [{:label "core"} {:label "wiring"}
           {:label "stack" :stack? true :composition {:layers [{:label "core"}]}}]
          {:round 4 :verdict :strained :needs "the seam"})]
    (is (= "the seam" (:needs (:standing core))))
    (is (= "the seam" (:needs (:standing wiring))))
    (is (nil? (:standing stack))
        "the composition pass is asked whether the cut holds and is told not to
         report what the layer reviews hold — a defect at a line is exactly what
         it must not answer with"))

  (testing "and on a flat branch the whole-stack target is the only one there is"
    (let [[whole] (stages/with-standing-needs
                    [{:label "stack" :stack? true}]
                    {:round 4 :verdict :strained :needs "the seam"})]
      (is (= "the seam" (:needs (:standing whole))))))

  (testing "a run with no standing item changes nothing"
    (let [targets [{:label "core"}]]
      (is (= targets (stages/with-standing-needs targets nil))))))

(deftest the-standing-item-does-not-move-the-cache-key
  ;; It is the same string every round, so folding it into the key would switch
  ;; the cache off for a whole run rather than making it correct.
  (with-redefs [layers/patch-hash (fn [_ _ _] "SAMEPATCH")]
    (let [cold (first (stages/with-patch-hashes "/w" [{:label "core" :from "a" :to "b"}]))
          warm (first (stages/with-patch-hashes
                        "/w" (stages/with-standing-needs
                               [{:label "core" :from "a" :to "b"}]
                               {:round 4 :verdict :strained :needs "the seam"})))]
      (is (= (:patch-hash cold) (:patch-hash warm))
          "a target skipped on a converged hash is one whose code nobody claims
           changed, and the standing item says nothing about the code"))))

;; ── The last run's unmet obligations, back into this one ───────────────────

(defn- review-ledger
  "A ledger whose last :review entry holds `open`."
  [open]
  (fn [_ _ kind]
    (when (= :review kind) {:format :review-report :open open})))

(deftest the-last-runs-open-list-is-read-back
  ;; Every run writes it and, until this, no run read it — so a finding ruled
  ;; :fix and never repaired reached the next run through no channel at all.
  (with-redefs [stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                ws/latest-entry (review-ledger
                                 [{:id "cc56069f" :layer "method-extraction"
                                   :title "Preserve tagged-literal identity"
                                   :where "core.clj:122" :disposition :fix}])]
    (is (= ["cc56069f"] (mapv :id (stages/prior-open "/w")))))

  (testing "a row an earlier run already inherited is not inherited again"
    ;; The carry is one hop by construction. A defect whose owning layer was
    ;; handed to a reviewer and still went unreported is not evidence enough to
    ;; hold the branch open for ever, and a stale one would otherwise block
    ;; every future run with no exit but a human.
    (with-redefs [stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (review-ledger
                                   [{:id "a" :layer "core" :title "t" :disposition :fix}
                                    {:id "b" :layer "core" :title "u" :disposition :fix
                                     :inherited true}])]
      (is (= ["a"] (mapv :id (stages/prior-open "/w"))))))

  (testing "a workstream with no review behind it seeds nothing"
    (with-redefs [stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (fn [& _] nil)]
      (is (empty? (stages/prior-open "/w"))))))

(deftest an-inherited-obligation-reaches-the-reviewer-of-its-own-layer
  (let [[core wiring stack]
        (stages/with-prior-open
          [{:label "core"} {:label "wiring"}
           {:label "stack" :stack? true :composition {:layers [{:label "core"}]}}]
          [{:id "a" :layer "core" :title "the extent reader" :disposition :fix}])]
    (is (= ["a"] (mapv :id (:prior-open core)))
        "matched on the label — a repair moves the patch a hash is taken over,
         so the hash cannot carry an obligation across the repair it asks for")
    (is (nil? (:prior-open wiring)) "a layer nothing is owed of is told nothing")
    (is (nil? (:prior-open stack))
        "the composition pass is asked whether the cut holds and told not to
         answer with a defect at a line"))

  (testing "a park is withheld: it is a question already put to a human"
    ;; parked-blocker carries it to the gate. A reviewer asked to re-report it
    ;; would have a fixer patch away the very question somebody is answering.
    (let [[core] (stages/with-prior-open
                   [{:label "core"}]
                   [{:id "p" :layer "core" :title "t" :disposition :park}])]
      (is (nil? (:prior-open core)))))

  (testing "a run with nothing inherited changes nothing"
    (let [targets [{:label "core"}]]
      (is (= targets (stages/with-prior-open targets []))))))

(deftest an-obligation-this-run-ruled-on-is-this-runs
  ;; Answered means RULED, not repaired: from the moment a reviewer raises it
  ;; again the run's own accounting decides what is owed, and carrying the
  ;; inherited copy beside it would count one defect twice.
  (let [inherited [{:id "a" :layer "core" :title "t"}
                   {:id "b" :layer "core" :title "u"}]]
    (is (= ["b"] (mapv :id (stages/unanswered-of
                            inherited
                            [[{:id "a" :title "t" :disposition :fix}]]))))
    (is (= ["a" "b"] (mapv :id (stages/unanswered-of inherited [[]]))))))

(deftest an-unanswered-obligation-denies-its-layer-the-irrevocable-mark
  ;; cache.clj states :converged is not granted by an agent and cannot be
  ;; revoked by one, so a layer that takes it while a known defect stands in it
  ;; is exempt from the code lane until somebody happens to edit the file.
  (let [core   {:label "core" :patch-hash "h1"}
        wiring {:label "wiring" :patch-hash "h2"}]
    (is (= [[core :partial] [wiring :converged]]
           (stages/deny-inherited-convergence
            [[core :converged] [wiring :converged]]
            [{:id "a" :layer "core" :title "t"}])))
    (is (= [[core :converged]]
           (stages/deny-inherited-convergence [[core :converged]] []))
        "nothing inherited is nothing to deny")))

(deftest a-quiet-round-over-an-unanswered-obligation-is-not-clean
  ;; The miss whole: the reviewer of the file holding an open :fix finding
  ;; returned no findings, and the run published that nothing was owed.
  (let [ctx {:config {:cwd "/w" :base "main" :run-id "r1"} :iter 2
             :carry {:quiet-once true
                     :inherited-open [{:id "a" :layer "core" :title "t"}]}}]
    (with-redefs [layers/patch-hash (fn [& _] nil)
                  codex/merge-base  (fn [& _] "FORK")
                  cache/read-cache  (fn [& _] {})
                  conformance/findings (fn [& _] [])
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  ws/latest-entry (review-ledger [{:id "a" :layer "core" :title "t"
                                                   :disposition :fix}])
                  codex/review! (fn [_] {:status nil :findings []})]
      (is (= :unresolved (:status ((:run stages/review-stage) ctx)))
          "a quiet round is evidence about what the reviewers read, never
           evidence that a defect somebody already ruled :fix has gone")

      (testing "and a round that answered it earns clean back"
        (is (= :clean
               (:status ((:run stages/review-stage)
                         (assoc ctx :history
                                [{:iter 1 :findings [{:id "a" :title "t"
                                                      :disposition :fix}]}])))))))))

;; ── Evidence that a round moved the code ───────────────────────────────────

(deftest content-hashes-cover-the-targets-the-round-skipped
  ;; A layer left alone because it converged is still part of what the branch
  ;; holds. Read only over what was REVIEWED, the set would shrink the round a
  ;; layer starts hitting the cache and read as a change nobody made.
  (is (= #{"a" "b"}
         (stages/content-hashes [{:label "core" :patch-hash "a"}
                                 {:label "wiring" :patch-hash "b"}]))))

(deftest a-target-whose-patch-could-not-be-hashed-is-not-evidence
  (is (= #{} (stages/content-hashes [{:label "core" :patch-hash nil}]))
      "a round jj could not diff knows nothing, and nothing is not a change"))

(deftest a-round-whose-fixes-moved-the-branch-is-a-round-that-changed
  (is (stages/round-changed?
       {:iter 2 :patch-hashes #{"a992b884"}}
       [{:iter 1 :fixed-count 2 :patch-hashes #{"ad285eea"}}])
      "two repairs landed and the reviewers of this round read different code —
       the one case a repeated finding set must not be called a stall"))

(deftest a-round-whose-fixes-did-not-reach-the-branch-changed-nothing
  ;; The fixers ran and committed; the content they left is what the last round
  ;; already read. Commits are not progress.
  (is (not (stages/round-changed?
            {:iter 2 :patch-hashes #{"ad285eea"}}
            [{:iter 1 :fixed-count 2 :patch-hashes #{"ad285eea"}}]))))

(deftest a-round-that-landed-no-repair-changed-nothing
  (is (not (stages/round-changed?
            {:iter 2 :patch-hashes #{"a992b884"}}
            [{:iter 1 :fixed-count 0 :patch-hashes #{"ad285eea"}}]))
      "content that moved with no repair behind it is not the loop making
       progress — it is the worktree moving under the run"))

(deftest the-round-before-this-one-is-found-by-its-iter
  ;; `last` would answer with round 1 for a round-3 question whenever round 2
  ;; left no entry, comparing across two rounds of repairs and calling a stall
  ;; progress.
  (is (not (stages/round-changed?
            {:iter 3 :patch-hashes #{"b"}}
            [{:iter 1 :fixed-count 2 :patch-hashes #{"a"}}]))))

(deftest content-nobody-could-hash-leaves-the-stall-check-as-strict-as-it-was
  (is (not (stages/round-changed?
            {:iter 2 :patch-hashes #{}}
            [{:iter 1 :fixed-count 2 :patch-hashes #{"ad285eea"}}])))
  (is (not (stages/round-changed?
            {:iter 2 :patch-hashes #{"a992b884"}}
            [{:iter 1 :fixed-count 2 :patch-hashes #{}}]))))

(deftest a-round-stamps-what-its-reviewers-read-onto-the-ctx
  ;; What the next round compares against. Without it on the ctx there is no
  ;; second reading to take, and the fix stage has nothing to put in history.
  (with-redefs [layers/patch-hash (fn [_ _ to] (str "hash-" to))
                codex/merge-base (fn [& _] "BASEREV")
                codex/review! (fn [_] {:status :ok :findings [{:title "x" :file "a.clj"}]})]
    (let [out ((:run stages/review-stage)
               {:config {:cwd "/w" :base "main" :run-id "r1"} :iter 1})]
      (is (seq (:patch-hashes out)))
      (is (every? string? (:patch-hashes out))))))
