(ns nido.review.record-test
  "The pure half of a round over a record: whether it is worth running, what the
   prompt puts in front of the judge, and what an answer has to look like to be
   recorded. The codex call itself is a seam and is not exercised here."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.report :as report]
   [nido.review.record :as record]))

(def ^:private baseline
  {:format       :baseline
   :seq          3
   :area         "order totalling"
   :bounded-by   "everything that reads or writes a money amount on an order"
   :shape        "The aggregate is the only thing that sums lines."
   :modules      [{:id "mod-the-order-aggregate" :module "the order aggregate"
                   :hides "the order in which lines are summed"
                   :interface "an order's total"}
                  {:id "mod-the-invoice-reader" :module "the invoice reader"
                   :hides "the invoice document's layout"
                   :interface "renders a total it is handed"}]
   :composition  "Only the aggregate can see the lines, so only it can sum them;
                  the invoice reader consumes the total it produces."
   :load-bearing [{:id "c1" :property "the aggregate is the only summing path"
                   :falsified-by "a caller outside the aggregate that reads lines and sums them"
                   :readings [{:lens :parnas/dependency :verdict :on-interface
                               :because "callers take the total, never the lines"}]
                   :evidence ["src/order/aggregate.clj:12"]}]
   :health       [{:id "invoice-resums" :axis :design
                   :observation "two summing paths where the design claims one"
                   :evidence ["src/order/invoice.clj:88"]}]
   :read         ["src/order/aggregate.clj"]
   :unknowns     ["whether the CSV importer bypasses the aggregate"]})

(def ^:private design
  {:format     :design
   :seq        4
   :summary    "Rounding moves to a single point on the order total."
   :shape      "One rounding boundary at the order aggregate."
   :invariants ["a total is rounded exactly once"]
   :standing   {:relation :conforms}
   :baseline   {:seq 3 :relation :within}
   :effort     :M})

;; ── Worth running ───────────────────────────────────────────────────────────

(deftest a-baseline-with-checkable-claims-is-worth-verifying
  (is (record/baseline-round-worth-running? baseline))
  (is (record/baseline-round-worth-running?
       (assoc baseline :load-bearing [] :health (:health baseline)))
      "health alone is checkable — every observation carries evidence"))

(deftest a-baseline-with-nothing-checkable-is-not
  (is (not (record/baseline-round-worth-running? nil)))
  (is (not (record/baseline-round-worth-running?
            (dissoc (assoc baseline :load-bearing []) :health)))
      "nothing to refute means a round could only produce prose"))

(deftest a-design-claiming-it-moves-nothing-gets-no-decision-round
  (is (not (record/design-round-worth-running? design))
      ":within + :conforms + modest effort is a claim that is cheap to
       spot-check; paying for a decision round there is the cost this guards"))

(deftest every-declared-reason-to-doubt-triggers-the-round
  (is (record/design-round-worth-running?
       (assoc design :baseline {:seq 3 :relation :revisit
                                :breaks ["the aggregate is the only summing path"]
                                :note "the boundary has to move"})))
  (is (record/design-round-worth-running?
       (assoc design :standing {:relation :challenges :note "money needs mutability"})))
  (is (record/design-round-worth-running? (assoc design :effort :L)))
  (is (record/design-round-worth-running?
       (assoc design :routes [{:health-id "invoice-resums" :to :spin-out
                               :why "revealed, not created" :ref "FU-88"}]))
      "an observation routed anywhere but fix-here is a scope decision, and
       scope decisions are what the round exists to put to a human")
  (is (not (record/design-round-worth-running?
            (assoc design :routes [{:health-id "invoice-resums" :to :fix-here}])))
      "routing everything to fix-here decides nothing that needs deciding"))

;; ── What the judge is shown ─────────────────────────────────────────────────

(deftest the-baseline-prompt-asks-about-the-decomposition
  (let [p (record/baseline-prompt {:baseline baseline})]
    (testing "the modules and what each hides"
      (is (str/includes? p "MODULES — the decomposition claimed"))
      (is (str/includes? p "hides:     the order in which lines are summed"))
      (is (str/includes? p "interface: an order's total")))
    (testing "and how they are claimed to produce the behaviour"
      (is (str/includes? p "COMPOSITION — how those are claimed"))
      (is (str/includes? p "Only the aggregate can see the lines")))
    (testing "each claim arrives with what would refute it, and how it was read"
      (is (str/includes? p "the aggregate is the only summing path"))
      (is (re-find #"\[c\d+\] the aggregate" p) "and the claim carries its id")
      (is (str/includes? p "refuted by: a caller outside the aggregate"))
      (is (str/includes? p "read as parnas/dependency = on-interface")))
    (testing "and the perspectives themselves, so both sides read a verdict alike"
      (is (str/includes? p "THE PERSPECTIVES THIS SURVEY IS READ THROUGH"))
      (is (str/includes? p "from Out of the Tar Pit"))
      (is (str/includes? p "A READING IS A CLAIM TOO")))
    (testing "the refs are still handed over, as where to look"
      (is (str/includes? p "src/order/aggregate.clj:12")))
    (is (str/includes? p "SUFFICIENT IS THE EXPECTED OUTCOME"))
    (is (str/includes? p "MUST cite"))
    (is (str/includes? p "INSUFFICIENT"))
    (is (str/includes? p "whether the CSV importer bypasses the aggregate")
        "a declared unknown is honesty already recorded, not a finding to make")))

(deftest the-baseline-prompt-refuses-the-plane-that-never-converges
  ;; The failure this is aimed at: asked to check a survey against a large
  ;; subsystem, a judge finds true things about it forever. An implementation
  ;; has no fixed point; a decomposition does.
  (let [p (record/baseline-prompt {:baseline baseline})]
    (is (str/includes? p "not reviewing the code for\ndefects"))
    (is (str/includes? p "belongs to code\nreview"))
    (is (str/includes? p "does that specific counterexample exist"))
    (is (str/includes? p "Neither is a finding that reports a bug in code the survey"))))

(deftest a-survey-from-before-the-move-still-makes-a-prompt
  ;; Legacy records are readable, so a round over one has to be too — it simply
  ;; has no decomposition to ask about.
  (let [p (record/baseline-prompt
           {:baseline (dissoc baseline :modules :composition)})]
    (is (not (str/includes? p "MODULES —")))
    (is (not (str/includes? p "COMPOSITION — how those are claimed")))
    (is (str/includes? p "the aggregate is the only summing path"))))

(deftest the-decision-prompt-carries-the-four-derivations-and-the-answer-key
  (let [p (record/design-prompt
           {:design (assoc design
                           :rejected [{:alternative "round at render time"
                                       :why-not "moves money math into the view"}]
                           :layers [{:claim "extract the aggregate" :mode :judgment}])
            :baseline baseline
            :stance "two registers of data"
            :intent {:goal "stop the checkout being off by a cent"
                     :done-when ["a multi-line order's total equals its invoice"]}})]
    (is (str/includes? p "relation-honest"))
    (is (str/includes? p "goal-served"))
    (is (str/includes? p "decomposable"))
    (is (str/includes? p "routing-coherent"))
    (is (str/includes? p "ALREADY REJECTED")
        "without the answer key the round re-proposes what was already rejected")
    (is (str/includes? p "round at render time"))
    (is (str/includes? p "stop the checkout being off by a cent"))
    (is (str/includes? p "Done when:"))
    (is (str/includes? p "You do not make the decision"))
    (is (str/includes? p "Never answer it yourself"))))

(deftest the-decision-prompt-shows-the-survey-it-derives-against
  ;; The defect this is aimed at: the prompt named the survey's modules,
  ;; extension points and health observations in its instructions and printed
  ;; none of them. A judge cannot tell "the survey does not say" from "I was not
  ;; shown it", the only recommendation that fits the first is :resurvey, and
  ;; that is what one workstream got seven times running without ever reaching a
  ;; decision.
  (let [p (record/design-prompt
           {:design (assoc design :routes [{:health-id "invoice-resums" :to :fix-here}])
            :baseline (assoc baseline
                             :extension-points
                             [{:at "the aggregate's line reducer"
                               :how "a new line kind registers a contribution"}])})]
    (testing "relation-honest and decomposable read the decomposition"
      (is (str/includes? p "MODULES — the decomposition claimed"))
      (is (str/includes? p "hides:     the order in which lines are summed"))
      (is (str/includes? p "COMPOSITION — how those are claimed"))
      (is (str/includes? p "SHAPE: The aggregate is the only thing that sums lines.")))
    (testing "the declared relation has its yardstick"
      (is (str/includes? p "EXTENSION POINTS"))
      (is (str/includes? p "a new line kind registers a contribution")))
    (testing "routing-coherent can resolve the ids the design routes by"
      (is (str/includes? p "- invoice-resums [design]"))
      (is (str/includes? p "two summing paths where the design claims one")))
    (testing "a claim keeps what would refute it, not just where to look"
      (is (str/includes? p "refuted by: a caller outside the aggregate"))
      (is (str/includes? p "src/order/aggregate.clj:12")))
    (testing "and what the survey already declared it did not determine"
      (is (str/includes? p "DECLARED NOT DETERMINED"))
      (is (str/includes? p "whether the CSV importer bypasses the aggregate")))))

(deftest a-survey-from-before-the-move-still-makes-a-decision-prompt
  (let [p (record/design-prompt
           {:design design
            :baseline (dissoc baseline :modules :composition :health
                              :unknowns :extension-points)})]
    (is (not (str/includes? p "MODULES —")))
    (is (not (str/includes? p "EXTENSION POINTS")))
    (is (str/includes? p "the aggregate is the only summing path")
        "what the legacy survey does carry is still shown")))

(deftest the-decision-prompt-survives-a-design-with-no-baseline-or-stance
  (is (string? (record/design-prompt {:design design}))
      "framing is optional everywhere else in this system; it is here too"))

(def ^:private legacy-design
  "A :design from before the baseline event: no :baseline, and still readable."
  {:format :design :summary "s" :shape "sh" :invariants ["i"]
   :standing {:relation :conforms} :effort :M})

(deftest a-legacy-design-qualifies-for-a-round
  (is (record/design-round-worth-running? legacy-design)
      "its absent baseline relation is not :within, so it qualifies — which is
       exactly why the prompt has to survive it"))

(deftest a-legacy-design-does-not-crash-the-prompt
  (let [p (record/design-prompt {:design legacy-design})]
    (is (string? p))
    (is (str/includes? p "Declared against the baseline: NOTHING")
        "the degrade this area takes everywhere else — say the yardstick is
         absent rather than invent one")
    (is (str/includes? p "not itself a finding")))
  ;; The regression this pins: the prompt is built as an ARGUMENT to run-round!,
  ;; so it is evaluated outside that function's catch. A throw here does not
  ;; degrade to "no answer recorded" — it takes the whole task down. :baseline is
  ;; the only field a ledger-legal design can be missing that the prompt calls
  ;; `name` on; :standing is required by both the current and the legacy schema.
  (is (string? (record/design-prompt {:design legacy-design
                                      :baseline nil :stance nil :goals nil}))
      "every other framing input is already optional and stays so"))

;; ── What counts as an answer ────────────────────────────────────────────────

(defn- baseline-json [m] (json/generate-string m))

(deftest a-sufficient-baseline-review-parses-and-needs-no-findings
  (let [r (record/parse-baseline-review
           (baseline-json {:verdict "sufficient" :reason "both claims held"
                           :confirmed ["the aggregate is the only summing path"]
                           :findings []})
           3)]
    (is (= :sufficient (:verdict r)))
    (is (= 3 (:baseline-seq r)))
    (is (= ["the aggregate is the only summing path"] (:confirmed r)))
    (is (nil? (:findings r)))
    (is (= r (report/validate-event :baseline-review r))
        "whatever the parser emits has to satisfy the ledger write contract")))

(deftest a-falsified-baseline-review-carries-its-citations
  (let [r (record/parse-baseline-review
           (baseline-json {:verdict "falsified" :reason "invoice re-sums"
                           :confirmed []
                           :findings [{:cites ["the aggregate is the only summing path"]
                                       :claim "invoice.clj sums lines directly"
                                       :evidence ["src/order/invoice.clj:88"]}]})
           3)]
    (is (= :falsified (:verdict r)))
    (is (= 1 (count (:findings r))))
    (is (= r (report/validate-event :baseline-review r)))))

(deftest a-finding-that-cites-nothing-is-dropped
  (is (nil? (record/parse-baseline-review
             (baseline-json {:verdict "falsified" :reason "vibes"
                             :findings [{:cites [] :claim "feels shaky"
                                         :evidence []}]})
             3))
      "a non-accurate verdict whose findings all cite nothing is the theatre
       this round exists to prevent — read it as no answer, not as a verdict"))

(deftest an-unknown-verdict-is-a-non-answer
  (is (nil? (record/parse-baseline-review
             (baseline-json {:verdict "looks-fine" :reason "" :findings []}) 3)))
  (is (nil? (record/parse-baseline-review "not json at all" 3))
      "nil is a non-answer; the caller records nothing rather than inventing
       trust it did not earn"))

(deftest a-proceed-decision-parses-with-its-derivations
  (let [r (record/parse-design-decision
           (json/generate-string
            {:recommend "proceed" :reason "nothing derivable blocks it"
             :checks [{:check "relation_honest" :status "held" :note "within holds"}
                      {:check "goal_served" :status "held" :note "no smaller design"}
                      {:check "decomposable" :status "held" :note "two layers state cleanly"}
                      {:check "routing_coherent" :status "held" :note "one story"}]
             :findings []
             :asks "worth doing now, at M, given the invoice work queued behind it?"})
           4)]
    (is (= :proceed (:recommend r)))
    (is (= 4 (count (:checks r))))
    (is (every? #(= :held (:status %)) (:checks r)))
    (is (str/includes? (:asks r) "worth doing now"))
    (is (= r (report/validate-event :design-decision r)))))

(deftest a-decision-without-asks-is-a-non-answer
  (is (nil? (record/parse-design-decision
             (json/generate-string
              {:recommend "proceed" :reason "fine"
               :checks [{:check "relation_honest" :status "held" :note "ok"}]
               :findings [] :asks ""})
             4))
      "the round prepares an approval; one that asks nothing has granted it"))

(deftest a-decision-that-derived-nothing-is-a-non-answer
  (is (nil? (record/parse-design-decision
             (json/generate-string
              {:recommend "proceed" :reason "looks good to me"
               :checks [] :findings [] :asks "ship it?"})
             4))
      "handing a human an unreduced question is the rubber stamp with garnish"))

(deftest a-non-proceed-recommendation-must-carry-findings
  (is (nil? (record/parse-design-decision
             (json/generate-string
              {:recommend "recut" :reason "feels wrong"
               :checks [{:check "decomposable" :status "broken" :note "cannot state layers"}]
               :findings [] :asks "recut?"})
             4))
      "saying the design is wrong without citing anything is the same theatre"))

(deftest a-design-with-no-cited-intent-says-so-and-asks-for-underivable
  (let [p (record/design-prompt {:design design})]
    (is (str/includes? p "NO STATED INTENT"))
    (is (str/includes? p "UNDERIVABLE"))
    (is (str/includes? p "do NOT infer the goal from the design")
        "an inferred goal is the one the design serves, so the check could
         never fail")))

(deftest a-triage-cited-as-intent-contributes-no-directions
  ;; The projection happens in discover-intent; this pins what the PROMPT may
  ;; contain once it has, since that is where the damage would be done.
  (let [p (record/design-prompt
           {:design design
            :intent {:goal "Checkout off by a cent"
                     :summary "Rounding applied per line."
                     :done-when []}})]
    (is (str/includes? p "Checkout off by a cent"))
    (is (str/includes? p "Rounding applied per line."))
    (is (not (str/includes? p "round once on the total"))
        "a direction carries a proposed shape and an effort — in the goal
         yardstick it puts the answer inside the question")))

(deftest an-underivable-check-parses-and-is-not-a-failure
  (let [r (record/parse-design-decision
           (json/generate-string
            {:recommend "proceed" :reason "nothing derivable blocks it"
             :checks [{:check "goal_served" :status "underivable"
                       :note "this design cites no intent record"}]
             :findings [] :asks "worth doing without a stated goal?"})
           4)]
    (is (= :underivable (:status (first (:checks r)))))
    (is (= r (report/validate-event :design-decision r)))))

(deftest a-judge-answering-in-the-old-shape-is-still-answering
  (let [r (record/parse-design-decision
           (json/generate-string
            {:recommend "proceed" :reason "r"
             :checks [{:check "goal_served" :held true :note "n"}]
             :findings [] :asks "a?"})
           4)]
    (is (= :held (:status (first (:checks r))))
        "the schema moved; an answer in the previous shape is degraded rather
         than discarded")))

;; ── A round that could not run is not a round that found nothing ───────────
;; The one confusion a judgment surface cannot afford. Silence from a judge is
;; evidence; silence from a missing binary is not, and one nil for both invites
;; the second to be read as the first.

(deftest a-round-outside-a-session-says-so
  (let [r (record/baseline-review! {:cwd "/definitely/not/a/session" :run-id "x"})]
    (is (= :no-workstream (:outcome r)))
    (is (string? (:detail r)))
    (is (nil? (:format r)) "an outcome is never mistaken for a record")))

(deftest an-outcome-is-never-appended-as-a-record
  (is (nil? (record/append! "/definitely/not/a/session"
                            {:outcome :codex-failed :detail "exit 127"}))
      "append! writes records, and an outcome is not one — appending it would
       put 'the judge did not run' into the ledger as a judgment"))

(deftest each-remedy-parses-distinctly
  (doseq [[in out] {"amend" :amend "recut" :recut "resurvey" :resurvey}]
    (let [r (record/parse-design-decision
             (json/generate-string
              {:recommend in :reason "…"
               :checks [{:check "goal_served" :status "broken" :note "a smaller design does"}]
               :findings [{:cites ["a total is rounded exactly once"]
                           :claim "the smaller design already satisfies it"
                           :evidence ["src/order/aggregate.clj:12"]}]
               :asks "which way?"})
             4)]
      (is (= out (:recommend r))
          "redesign, recut and re-survey are different instructions; collapsing
           them is worse than saying nothing")
      (is (= r (report/validate-event :design-decision r))))))

(deftest a-prompt-never-promises-what-the-record-does-not-carry
  ;; The failure this catches is the one the whole arc is against: a header
  ;; asserting every claim names its counterexample, over claims that name none
  ;; because they were written before the rule existed.
  (let [legacy (-> baseline
                   (dissoc :modules :composition)
                   (update :load-bearing
                           (fn [lb] (mapv #(dissoc % :falsified-by :readings) lb))))
        p (record/baseline-prompt {:baseline legacy})]
    (is (not (str/includes? p "refuted by:"))
        "no empty label where the author committed to nothing")
    (is (str/includes? p "predates the rule"))
    (is (str/includes? p "treat\na claim you cannot see any way to refute as a finding")
        "and the gap becomes something to report rather than something to ignore")
    (is (not (str/includes? p "THE PERSPECTIVES THIS SURVEY IS READ THROUGH"))
        "a vocabulary nothing is read through is noise in the prompt")
    (is (not (str/includes? p "A READING IS A CLAIM TOO")))))

(deftest a-decomposition-with-no-analysis-is-told-to-report-itself
  ;; The gap this closes was watched, not imagined: the first survey authored in
  ;; this shape carried five modules and zero readings. Gating the vocabulary on
  ;; readings BEING there hid it from exactly the survey that needed it.
  (let [no-readings (update baseline :load-bearing
                            (fn [lb] (mapv #(dissoc % :readings) lb)))
        p (record/baseline-prompt {:baseline no-readings})]
    (is (str/includes? p "READS NOTHING THROUGH ANY PERSPECTIVE"))
    (is (str/includes? p "Report that as UNDERSCOPED"))
    (is (str/includes? p "THE PERSPECTIVES THIS SURVEY IS READ THROUGH")
        "the vocabulary is shown, because this record could carry it")
    (is (not (str/includes? p "A READING IS A CLAIM TOO"))
        "and it is not told to check readings it does not have")))

(deftest a-survey-that-cannot-carry-readings-is-not-nagged-about-them
  (let [legacy (dissoc baseline :modules :composition)
        p (record/baseline-prompt {:baseline legacy})]
    (is (not (str/includes? p "READS NOTHING THROUGH ANY PERSPECTIVE")))
    (is (not (str/includes? p "THE PERSPECTIVES THIS SURVEY IS READ THROUGH")))))

(deftest a-current-survey-still-gets-the-full-apparatus
  (let [p (record/baseline-prompt {:baseline baseline})]
    (is (str/includes? p "each with the\ncounterexample that would refute it"))
    (is (str/includes? p "refuted by: a caller outside the aggregate"))
    (is (str/includes? p "THE PERSPECTIVES THIS SURVEY IS READ THROUGH"))))

;; ── Insufficiency has to name what it blocks ────────────────────────────────

(deftest an-insufficient-finding-names-the-derivation-and-what-is-missing
  (let [r (record/parse-baseline-review
           (baseline-json {:verdict "insufficient" :reason "the cut cannot be stated"
                           :confirmed []
                           :findings [{:blocks "decomposable"
                                       :cites ["the aggregate is the only summing path"]
                                       :claim "the survey never says where rounding is decided"
                                       :needs "which module owns the rounding decision"
                                       :evidence ["src/order/calc.clj:41"]}]})
           3)]
    (is (= :insufficient (:verdict r)))
    (is (= :decomposable (:blocks (first (:findings r)))))
    (is (= "which module owns the rounding decision" (:needs (first (:findings r)))))
    (is (= r (report/validate-event :baseline-review r)))))

(deftest a-gap-that-blocks-nothing-is-not-a-finding
  ;; The bound, and the whole reason this verdict replaced :underscoped. Measured
  ;; against a real area a judge finds true things to add forever — five rounds
  ;; of exactly that produced twenty-four findings without one repeating. There
  ;; are four derivations, so there are four ways to be insufficient.
  (is (nil? (record/parse-baseline-review
             (baseline-json {:verdict "insufficient" :reason "could say more"
                             :findings [{:blocks "none" :cites ["a claim"]
                                         :claim "the cache is not mentioned"
                                         :needs "mention the cache" :evidence []}]})
             3))
      "'none' is what a falsified finding carries; it cannot make a gap")
  (is (nil? (record/parse-baseline-review
             (baseline-json {:verdict "insufficient" :reason "could say more"
                             :findings [{:blocks "thoroughness" :cites ["a claim"]
                                         :claim "x" :needs "y" :evidence []}]})
             3))
      "a derivation nobody derives"))

(deftest an-insufficient-finding-must-say-what-would-fix-it
  (is (nil? (record/parse-baseline-review
             (baseline-json {:verdict "insufficient" :reason "vague"
                             :findings [{:blocks "goal-served" :cites ["a claim"]
                                         :claim "not enough here" :needs "" :evidence []}]})
             3))
      "'more detail' is not a repair anyone can make"))

(deftest a-round-from-before-sufficiency-still-reads
  (let [old {:format :baseline-review :verdict :accurate :baseline-seq 3
             :reason "held" :confirmed ["a claim"]}]
    (is (thrown? clojure.lang.ExceptionInfo (report/validate-event :baseline-review old))
        "not writable any more")
    (is (= old (report/parse-event :baseline-review old)) "but still readable")))

(deftest a-cited-id-is-normalised-to-the-id-itself
  ;; The prompt renders `[engine-names-no-stage] the engine names no stage`, and
  ;; a judge told to cite the id "exactly as shown" cites the brackets with it.
  ;; An id sometimes bracketed and sometimes not is no identity at all, which is
  ;; the one thing it exists to be.
  (doseq [cited ["[engine-names-no-stage]" "engine-names-no-stage" "  [engine-names-no-stage] "]]
    (let [r (record/parse-baseline-review
             (baseline-json {:verdict "falsified" :reason "r" :confirmed []
                             :findings [{:claim-id cited :blocks "none" :cites ["c"]
                                         :claim "x" :needs "" :evidence []}]})
             3)]
      (is (= "engine-names-no-stage" (:claim-id (first (:findings r))))
          (str "from " (pr-str cited))))))

(deftest a-finding-about-no-single-claim-carries-no-id
  (let [r (record/parse-baseline-review
           (baseline-json {:verdict "falsified" :reason "r" :confirmed []
                           :findings [{:claim-id "" :blocks "none" :cites ["c"]
                                       :claim "x" :needs "" :evidence []}]})
           3)]
    (is (nil? (:claim-id (first (:findings r)))))))

(deftest a-confirmation-identifies-a-claim-rather-than-describing-it
  ;; Measured on a live run: five rounds produced 29 DISTINCT confirmation
  ;; strings for a survey of 13 claims and modules. Each round reworded them, so
  ;; the list carried forward could not be matched to anything and the judge
  ;; re-examined what it had already settled.
  (let [r (record/parse-baseline-review
           (baseline-json {:verdict "falsified" :reason "r"
                           :confirmed ["[engine-names-no-stage]" "judge-never-writes"
                                       "  [loop-engine] " "engine-names-no-stage" ""]
                           :findings [{:claim-id "x" :blocks "none" :cites ["c"]
                                       :claim "y" :needs "" :evidence []}]})
           3)]
    (is (= ["engine-names-no-stage" "judge-never-writes" "loop-engine"] (:confirmed r))
        "normalised, deduplicated, and blanks dropped — so two rounds naming the
         same claim produce the same entry")))

(deftest the-judge-is-asked-for-ids-not-sentences
  (let [p (record/baseline-prompt {:baseline {:format :baseline}})]
    (is (str/includes? p "IDS of the claims and modules"))
    (is (str/includes? p "cannot be matched to the claim"))))
