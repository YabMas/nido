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
