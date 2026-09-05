;; test/nido/review/verdict_test.clj
(ns nido.review.verdict-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.report :as report]
   [nido.coordinator.agent :as agent]
   [nido.review.stages :as stages]
   [nido.review.verdict :as verdict]
   [tasks.nido-review :as nido-review]))

(def ^:private design
  {:seq 3
   :shape "one rounding boundary at the order aggregate"
   :invariants ["a total is rounded exactly once"]
   :rejected [{:alternative "round at render time" :why-not "money math in the view"}]
   :assumes [{:about "line totals are computed per-item" :read ["src/order/calc.clj"]}]})

(defn- fenced [m] (str "prose before\n```json\n" m "\n```"))

(deftest prompt-carries-the-record-and-fences-off-the-stance
  (let [p (verdict/build-prompt {:design design :stance "shape of the data is the design"
                                 :findings [] :history [] :rounds 2})]
    (is (str/includes? p "a total is rounded exactly once"))
    (is (str/includes? p "round at render time"))
    (is (str/includes? p "ANSWERED"))
    (is (str/includes? p "line totals are computed per-item")
        "assumptions reach the verdict — a false premise is a different failure than a bad design")
    (is (str/includes? p "never cite it against a specific\nfinding"))
    (is (str/includes? p "THIS IS THE EXPECTED\n  OUTCOME") "sound must stay the cheap default")))

(deftest parses-a-sound-verdict-and-keeps-what-it-confirmed
  (let [v (verdict/parse (fenced "{\"verdict\":\"sound\",\"reason\":\"nits only\",\"invariants_held\":[\"a total is rounded exactly once\"]}")
                         2 3)]
    (is (= :sound (:verdict v)))
    (is (= ["a total is rounded exactly once"] (:invariants-held v)))
    (is (= 2 (:round v)))
    (is (= 3 (:design-seq v)))
    (is (= v (report/validate-event :design-verdict v)) "parse output must satisfy the schema")))

(deftest parses-standing-challenged-through-its-underscore-spelling
  (let [v (verdict/parse (fenced "{\"verdict\":\"standing_challenged\",\"reason\":\"r\",\"needs\":\"amend the stance?\"}")
                         1 nil)]
    (is (= :standing-challenged (:verdict v)))
    (is (verdict/decision? v))
    (is (= v (report/validate-event :design-verdict v)))))

(deftest invalidated-parses-with-its-broken-invariants
  (let [v (verdict/parse (fenced "{\"verdict\":\"invalidated\",\"reason\":\"r\",\"needs\":\"redesign?\",\"invariants_broken\":[{\"invariant\":\"i\",\"finding\":\"f\"}],\"findings_classified\":[{\"finding\":\"f\",\"as\":\"design\"}]}")
                         3 3)]
    (is (= :invalidated (:verdict v)))
    (is (= [{:invariant "i" :finding "f"}] (:invariants-broken v)))
    (is (= [{:finding "f" :as :design}] (:findings-classified v)))
    (is (= v (report/validate-event :design-verdict v)))))

(deftest a-non-answer-is-nil-never-a-fabricated-verdict
  (is (nil? (verdict/parse "no json here" 1 nil)))
  (is (nil? (verdict/parse (fenced "{\"verdict\":\"vibes\",\"reason\":\"r\"}") 1 nil)))
  (is (nil? (verdict/parse (fenced "{not json") 1 nil)))
  (is (nil? (verdict/parse nil 1 nil))))

(deftest sound-is-not-a-decision
  (is (not (verdict/decision? {:verdict :sound})))
  (is (not (verdict/decision? {:verdict :strained})))
  (is (verdict/decision? {:verdict :invalidated})))

(deftest verdict-runs-when-there-is-anything-to-judge
  (is (nido-review/verdict-worth-running? :converged {:findings [{:title "x"}]} nil))
  (is (nido-review/verdict-worth-running? :max-iters {:history [{:iter 1}]} nil))
  (is (not (nido-review/verdict-worth-running? :clean {:findings [] :history []} nil))
      "no findings and no design to check against is no evidence either way")
  ;; The case the findings-only gate could not reach. Confirming an invariant
  ;; needs the code, not a finding — and on a clean run this pass is the only
  ;; thing that reads the change against what it committed to.
  (is (nido-review/verdict-worth-running?
       :clean {:findings [] :history []}
       {:invariants ["the fragment endpoint refuses exactly what the page refuses"]})
      "a clean review still has the design's invariants left to confirm")
  (is (not (nido-review/verdict-worth-running? :clean {:findings [] :history []} {:invariants []}))
      "a design naming no invariants leaves a clean run nothing to confirm")
  ;; Status still decides first: neither of these produced anything to judge,
  ;; whatever the design claims.
  (is (not (nido-review/verdict-worth-running? :review-failed {:findings [{:title "x"}]} nil)))
  (is (not (nido-review/verdict-worth-running? :dry-run {:findings [{:title "x"}]} nil)))
  (is (not (nido-review/verdict-worth-running? :dry-run {} {:invariants ["x"]}))
      "a dry run changed nothing, so there is nothing to confirm an invariant against"))

(deftest verdict-prompt-foregrounds-structural-findings
  (let [p (verdict/build-prompt
           {:design design :findings [{:priority 1 :title "t" :body "b" :reach :structural}]
            :history [] :rounds 1})]
    (is (str/includes? p "[P1/structural] t"))
    (is (str/includes? p "the reviewer\ncould not see the design, and you can"))))

(deftest still-open-drops-what-the-warden-closed
  ;; A closed finding was decided, by a named authority. Handing it to the
  ;; verdict as "still open" re-adjudicates a settled question.
  (is (= ["b"] (map :title (verdict/still-open
                            [{:title "a" :disposition :closed :authority "duplicate"}
                             {:title "b" :disposition :fix}]))))
  (is (= ["a"] (map :title (verdict/still-open [{:title "a"}])))
      "a finding with no disposition at all is still open"))

;; ── The baseline as a second yardstick ─────────────────────────────────────

(def ^:private baseline
  {:seq 2 :format :baseline
   :area "order totalling"
   :load-bearing [{:property "the aggregate is the only summing path"
                   :evidence ["src/order/aggregate.clj:12"]}
                  {:property "a line item's amount is never rounded in place"
                   :evidence ["src/order/calc.clj:41"]}]})

(def ^:private baselined-design
  (-> design
      (dissoc :assumes)
      (assoc :baseline {:seq 2 :relation :within})))

(deftest prompt-hands-the-judge-the-load-bearing-properties
  (let [p (verdict/build-prompt {:design baselined-design :baseline baseline
                                 :findings [] :history [] :rounds 2})]
    (is (str/includes? p "the aggregate is the only summing path"))
    (is (str/includes? p "src/order/aggregate.clj:12")
        "with where it was read — the judge has tools and can check")
    (is (str/includes? p "declared itself WITHIN"))
    (is (str/includes? p "needs NONE of those properties to change"))
    (is (str/includes? p "load_bearing_broken"))
    (is (str/includes? p "re-survey"))))

(deftest prompt-tells-the-judge-what-a-revisit-was-allowed-to-break
  (let [p (verdict/build-prompt
           {:design (assoc baselined-design
                           :baseline {:seq 2 :relation :revisit
                                      :breaks ["the aggregate is the only summing path"]
                                      :note "invoices need their own total"})
            :baseline baseline :findings [] :history [] :rounds 1})]
    (is (str/includes? p "declared itself REVISIT"))
    (is (str/includes? p "named these as the ones it has to break"))
    (is (str/includes? p "named and broke on purpose is NOT a finding")
        "a declared break must not come back as a finding — that is the whole
         point of declaring it")))

(deftest prompt-without-a-baseline-invents-nothing
  (let [p (verdict/build-prompt {:design design :baseline nil
                                 :findings [] :history [] :rounds 1})]
    (is (not (str/includes? p "WHAT THE AREA ALREADY WAS"))
        "a pre-baseline design has no baseline, and the judge is not handed one")
    (is (str/includes? p "line totals are computed per-item")
        "the legacy :assumes still reaches it, so an old workstream is not left blind")))

(deftest parses-load-bearing-verdict-fields
  (let [v (verdict/parse
           (fenced (str "{\"verdict\": \"invalidated\","
                        " \"reason\": \"the change moved summing into the invoice reader\","
                        " \"invariants_broken\": [{\"invariant\": \"a total is rounded exactly once\","
                        " \"finding\": \"invoice re-rounds\"}],"
                        " \"load_bearing_held\": [\"a line item's amount is never rounded in place\"],"
                        " \"load_bearing_broken\": [{\"invariant\": \"the aggregate is the only summing path\","
                        " \"finding\": \"invoice.clj sums lines directly\"}],"
                        " \"findings_classified\": [{\"finding\": \"invoice re-rounds\", \"as\": \"baseline\"}],"
                        " \"needs\": \"is the aggregate still the only summer?\"}"))
           3 7)]
    (is (= ["a line item's amount is never rounded in place"] (:load-bearing-held v)))
    (is (= [{:invariant "the aggregate is the only summing path"
             :finding   "invoice.clj sums lines directly"}]
           (:load-bearing-broken v)))
    (is (= [{:finding "invoice re-rounds" :as :baseline}] (:findings-classified v))
        ":baseline is a classification the judge can now actually record")
    (is (report/validate-event :design-verdict (dissoc v :seq))
        "and the widened schema accepts it")))

(deftest an-unknown-classification-is-still-dropped
  (let [v (verdict/parse
           (fenced (str "{\"verdict\": \"sound\", \"reason\": \"fine\","
                        " \"findings_classified\": [{\"finding\": \"x\", \"as\": \"vibes\"}]}"))
           1 2)]
    (is (empty? (:findings-classified v))
        "the vocabulary stayed closed when it widened — a value outside it is
         dropped rather than stored for the schema to reject at the boundary.
         Empty rather than absent: the key is assoc'd from the raw list being
         non-empty, which is how it behaved before :baseline was added too.")
    (is (report/validate-event :design-verdict (dissoc v :seq)))))

(deftest terminal-tells-redesign-and-re-survey-apart
  ;; Two remedies, and naming the wrong one is worse than naming none: a broken
  ;; load-bearing property means the change did not do what it said; a :baseline
  ;; finding means the baseline was wrong and the design may be fine.
  (let [out (with-out-str
              (#'tasks.nido-review/print-verdict!
                {:verdict :strained :round 2 :reason "pressure on the aggregate"
                 :load-bearing-broken [{:invariant "the aggregate is the only summing path"
                                        :finding "invoice.clj sums lines directly"}]}))]
    (is (str/includes? out "broken without being declared"))
    (is (str/includes? out "the aggregate is the only summing path"))
    (is (str/includes? out ":relation :revisit"))
    (is (not (str/includes? out "re-survey"))))
  (let [out (with-out-str
              (#'tasks.nido-review/print-verdict!
                {:verdict :sound :round 1 :reason "fine"
                 :findings-classified [{:finding "totals are not per-item" :as :baseline}]}))]
    (is (str/includes? out "BASELINE was wrong"))
    (is (str/includes? out "re-survey"))
    (is (not (str/includes? out "supersede the design record"))
        "a wrong premise under a sound design is not a supersession"))
  (let [out (with-out-str
              (#'tasks.nido-review/print-verdict!
                {:verdict :sound :round 1 :reason "findings were implementation details"}))]
    (is (not (str/includes? out "⚠")) "the expected outcome stays quiet")))

(deftest open-across-run-finds-a-park-the-final-round-cannot-show
  ;; The case the count was built for and could not see: a seam parked in round
  ;; 1 is never raised again — that is what a park IS — so the final round holds
  ;; no trace of it and `still-open` reports zero.
  (let [final {:status  :converged
               :history [{:iter 1 :findings [{:handle "h1" :title "the seam"
                                              :disposition :park
                                              :because "needs a human"}
                                             {:handle "h2" :title "a typo"
                                              :disposition :fix}]}
                         {:iter 2 :findings [{:handle "h2" :title "a typo"
                                              :disposition :closed}]}]
               :findings []}
        open  (verdict/open-across-run final)]
    (is (= 0 (count (verdict/still-open (:findings final))))
        "the final round shows nothing — this is the defect")
    (is (= ["the seam"] (mapv :title open)))
    (is (= "needs a human" (:because (first open))))))

(deftest open-across-run-takes-the-latest-ruling-per-finding
  ;; Parked in round 1, closed in round 3. It is closed.
  (let [final {:status :converged
               :history [{:iter 1 :findings [{:handle "h" :title "t" :disposition :park}]}
                         {:iter 2 :findings [{:handle "h" :title "t" :disposition :fix}]}
                         {:iter 3 :findings [{:handle "h" :title "t" :disposition :closed}]}]
               :findings []}]
    (is (empty? (verdict/open-across-run final)))))

(deftest a-fix-is-owed-only-in-the-final-round
  ;; An earlier round's fix was checked by the round after it. The last round's
  ;; was not — there is no round after it to re-report the failure.
  (let [earlier {:status :converged
                 :history [{:iter 1 :findings [{:handle "h" :title "t" :disposition :fix}]}
                           {:iter 2 :findings []}]
                 :findings []}
        latest  {:status :converged
                 :history [{:iter 1 :findings []}]
                 :findings [{:handle "h" :title "t" :disposition :fix}]}]
    (is (empty? (verdict/open-across-run earlier)))
    (is (= ["t"] (mapv :title (verdict/open-across-run latest))))))

(deftest unruled-findings-do-not-collapse-onto-each-other
  ;; No handle and no id: identity falls back to file/line/title. Keyed on nil
  ;; these would fold into one, turning three open findings into one.
  (let [final {:status :escalated
               :history []
               :findings [{:file "a.clj" :line-start 1 :title "x"}
                          {:file "b.clj" :line-start 2 :title "y"}
                          {:file "c.clj" :line-start 3 :title "z"}]}]
    (is (= 3 (count (verdict/open-across-run final))))))

(deftest a-repair-in-the-branch-is-told-from-a-finding-nobody-touched
  ;; Both are still open and one number counted both: a run that aborted its fix
  ;; plan reported `1 fixed · 11 still open` out of eleven findings, with the one
  ;; repaired-but-unchecked finding sitting in the same total as nine no fixer
  ;; was ever launched for.
  (let [final {:status  :fix-conflicted
               :history [{:iter 1 :fixed-count 1
                          :fixes [{:layer "diary-paging" :commit "d92edf80"
                                   :handed ["dd463b20"]}]
                          :findings []}]
               :findings [{:handle "dd463b20" :title "the repaired one" :disposition :fix}
                          {:handle "4a9816d2" :title "nobody reached it" :disposition :fix}]}
        handed (verdict/handed-to-a-fixer final)
        open   (verdict/open-across-run final)]
    (is (= 2 (count open)) "both are still owed; that much was already right")
    (is (= ["the repaired one"]
           (mapv :title (filter #(verdict/handed? handed %) open)))
        "the join fixes[].handed has offered since it was added and nothing read")))

(deftest a-rolled-back-repair-leaves-a-finding-as-untouched-as-any-other
  ;; The commit is gone, so the code is exactly what the reviewers read. Counting
  ;; it as repaired would tell a reader to go and check a fix that is not there.
  (let [final {:status  :fix-rolled-back
               :history []
               :rolled-back [{:layer "upper" :handed ["bb22"] :conflicted ["xuspsuww"]}]
               :findings [{:handle "bb22" :title "t" :disposition :fix}]}
        handed (verdict/handed-to-a-fixer final)]
    (is (empty? (filter #(verdict/handed? handed %) (verdict/open-across-run final))))))

(deftest a-decided-finding-is-not-owed-and-is-not-lost
  ;; The two readers disagreeing is the defect: one run reported `converged · 2
  ;; rounds · 2 fixed · 1 still open` about a finding its own convergence check
  ;; had already settled, because convergence reads `stages/settled?` and this
  ;; fold removed only :closed. Both halves of the answer are asserted — the
  ;; count has to drop the decision AND the record has to keep it, or fixing the
  ;; first deletes a defect somebody agreed to ship.
  (let [final {:status  :converged
               :history [{:iter 1 :findings [{:handle "h1" :title "the shipped defect"
                                              :disposition :declined
                                              :because "the shape is wrong, not this line"}
                                             {:handle "h2" :title "the overstated claim"
                                              :disposition :deviation
                                              :of "every envelope resolves through policy-for-reason"}
                                             {:handle "h3" :title "a duplicate"
                                              :disposition :closed :authority "duplicate"}
                                             {:handle "h4" :title "needs a human"
                                              :disposition :park}]}]
               :findings []}]
    (is (= ["needs a human"] (mapv :title (verdict/open-across-run final)))
        "only the park is still owed — a decline and a deviation were decided,
         and convergence has said so since :settles? was introduced")
    (is (= ["the shipped defect" "the overstated claim"]
           (mapv :title (verdict/kept-across-run final)))
        "and the decisions survive, because a branch shipping a known defect is
         exactly what a record is for")
    (is (empty? (filter #(= "a duplicate" (:title %)) (verdict/kept-across-run final)))
        "a close is not kept: once a duplicate is decided there is nothing left
         of it to carry")))

(deftest a-decline-reversed-in-a-later-round-is-owed-again
  ;; Both folds read the ruling that stuck, so a finding cannot sit in the two
  ;; lists at once — which is what makes the counts add up.
  (let [final {:status :escalated
               :history [{:iter 1 :findings [{:handle "h" :title "t" :disposition :declined
                                              :because "not this branch's problem"}]}]
               :findings [{:handle "h" :title "t" :disposition :park}]}]
    (is (= ["t"] (mapv :title (verdict/open-across-run final))))
    (is (empty? (verdict/kept-across-run final)))))

;; ── The standing verdict ───────────────────────────────────────────────────

(def ^:private standing
  "A verdict already on the ledger against `design`, at entry 12."
  {:format :design-verdict :verdict :strained :round 4 :design-seq 3
   :seq 12 :at "2026-09-03T22:15:00Z"
   :reason "the unread indicator is read in two places"
   :needs "reconcile the indicator with the header badge, or say why both"})

(deftest the-prompt-hands-the-judge-what-it-already-concluded
  ;; Six passes on one branch each rewrote the same outstanding question in
  ;; fresh prose, so one standing decision read as six and two of them
  ;; contradicted each other about a broken invariant.
  (let [p (verdict/build-prompt {:design design :prior standing
                                 :findings [] :history [] :rounds 5})]
    (is (str/includes? p "the unread indicator is read in two places"))
    (is (str/includes? p "reconcile the indicator with the header badge"))
    (is (str/includes? p "verdict strained"))
    (is (str/includes? p "Do NOT\n  restate the outstanding question in new words")
        "restating it is what turns one held position into several decisions")
    (is (str/includes? p "Overturning it is allowed")
        "a standing answer is a default to confirm or move, never a ruling to defer to")))

(deftest the-standing-verdict-comes-after-this-rounds-evidence
  ;; It is what the new evidence is weighed against, not the frame it is read
  ;; through — so the findings have to be read first.
  (let [p (verdict/build-prompt
           {:design design :prior standing :rounds 1 :history []
            :findings [{:priority 1 :title "a real one" :body "b" :reach :structural}]})]
    (is (< (str/index-of p "a real one") (str/index-of p "WHAT YOU CONCLUDED LAST TIME")))))

(deftest a-pass-with-no-standing-verdict-is-told-nothing
  (let [p (verdict/build-prompt {:design design :prior nil
                                 :findings [] :history [] :rounds 1})]
    (is (not (str/includes? p "WHAT YOU CONCLUDED LAST TIME"))
        "a first pass has no prior answer, and one is not invented for it")))

(deftest a-standing-verdict-answers-a-run-that-moved-nothing
  (let [quiet {:status :clean :findings [] :history []}]
    (is (verdict/still-answers? standing quiet {:summary {:findings-fixed 0}}))
    (is (not (verdict/still-answers? nil quiet {:summary {:findings-fixed 0}}))
        "no prior verdict is nothing to carry, not a licence to skip the pass")))

(deftest a-run-holding-anything-re-derives-the-verdict
  ;; Each of these is evidence the standing verdict was never shown.
  (let [rpt {:summary {:findings-fixed 0}}]
    (is (not (verdict/still-answers?
              standing
              ;; A park raised in round 1 is never raised again, so the final
              ;; round is empty and only the across-run fold can see it.
              {:status :escalated :findings []
               :history [{:iter 1 :findings [{:handle "h" :title "t" :disposition :park}]}]}
              rpt))
        "a question put to a human is exactly the evidence this pass classifies")
    (is (not (verdict/still-answers?
              standing
              {:status :converged :findings []
               :history [{:iter 1 :findings [{:handle "h" :title "the shipped defect"
                                              :disposition :declined
                                              :because "the shape is wrong, not this line"}]}]}
              rpt))
        "a defect the branch decided to ship is a real defect the last verdict never saw")
    (is (not (verdict/still-answers?
              standing {:status :converged :findings [] :history []}
              {:summary {:findings-fixed 2}}))
        "a fixer edits code, and a repair that moves a boundary is what this pass exists to catch")))

(deftest a-decision-is-re-asked-rather-than-re-asserted
  ;; :invalidated and :standing-challenged are questions owed to a human.
  ;; Carrying one unlooked-at would escalate every run over a design that may
  ;; since have been repaired in the code.
  (let [quiet {:status :clean :findings [] :history []}
        rpt   {:summary {:findings-fixed 0}}]
    (is (not (verdict/still-answers?
              (assoc standing :verdict :invalidated
                     :invariants-broken [{:invariant "i" :finding "f"}])
              quiet rpt)))
    (is (not (verdict/still-answers?
              (assoc standing :verdict :standing-challenged) quiet rpt)))))

(deftest a-carried-verdict-says-where-it-was-reached
  (let [v (verdict/carried-forward standing 7)]
    (is (= 7 (:round v)) "it answers for THIS run's rounds")
    (is (= 12 (:carried-from v)))
    (is (nil? (:seq v)) "the reader's stamp cannot be written back")
    (is (nil? (:at v)))
    (is (= v (report/validate-event :design-verdict v))
        "an unmarked carry would claim a reading of the code that never happened,
         so the write contract has to admit the mark")))

(deftest a-carry-of-a-carry-still-names-the-entry-a-judge-reached-it-at
  ;; Five runs later the pointer must still land on the one place a judgment was
  ;; made, not on the last copy of it.
  (let [once  (assoc (verdict/carried-forward standing 7) :seq 15)
        twice (verdict/carried-forward once 9)]
    (is (= 12 (:carried-from twice)))))

(deftest the-pass-is-not-launched-when-the-standing-verdict-answers
  (let [launched (atom false)]
    (with-redefs [stages/discover-design-record (fn [_] design)
                  stages/discover-prior-verdict (fn [_ _] standing)
                  agent/launch! (fn [_] (reset! launched true) {:num-turns 1 :result-text ""})]
      (let [v (verdict/run! {:cwd "/w" :run-id "r" :budget "30m"
                             :final {:status :clean :findings [] :history []}
                             :report {:summary {:rounds 2 :findings-fixed 0}}})]
        (is (false? @launched) "the minutes an agent costs are the whole point of the carry")
        (is (= 12 (:carried-from v)))
        (is (= 2 (:round v)))))))

(deftest a-run-with-something-to-judge-launches-the-pass-holding-the-prior
  (let [seen (atom nil)]
    (with-redefs [stages/discover-design-record (fn [_] design)
                  stages/discover-prior-verdict (fn [_ _] standing)
                  stages/discover-baseline (fn [_ _] nil)
                  stages/read-stance (fn [_] nil)
                  stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                  agent/launch! (fn [{:keys [first-message]}]
                                  (reset! seen first-message)
                                  {:num-turns 1
                                   :result-text (fenced "{\"verdict\":\"strained\",\"reason\":\"unmoved\"}")})]
      (let [v (verdict/run! {:cwd "/w" :run-id "r" :budget "30m"
                             :final {:status :escalated
                                     :findings [{:title "t" :body "b" :disposition :park}]
                                     :history []}
                             :report {:summary {:rounds 3 :findings-fixed 0}}})]
        (is (str/includes? @seen "reconcile the indicator with the header badge")
            "the judge that does run is still shown what it already concluded")
        (is (= :strained (:verdict v)))
        (is (nil? (:carried-from v)) "a verdict an agent reached is not marked as carried")
        (is (= 3 (:design-seq v)))))))
