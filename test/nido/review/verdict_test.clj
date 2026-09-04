;; test/nido/review/verdict_test.clj
(ns nido.review.verdict-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.report :as report]
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
