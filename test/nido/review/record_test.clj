(ns nido.review.record-test
  "The pure half of a round over a record: whether a baseline round is worth
   running, what the prompt puts in front of the judge, and what an answer has to
   look like to be recorded. The codex call itself is a seam and is not exercised
   here."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.report :as report]
   [nido.review.codex :as codex]
   [nido.review.record :as record]
   [nido.review.stages :as stages]))

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
      (is (str/includes? p "THE PERSPECTIVES THIS BASELINE IS READ THROUGH"))
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
  ;; The failure this is aimed at: asked to check a baseline against a large
  ;; subsystem, a judge finds true things about it forever. An implementation
  ;; has no fixed point; a decomposition does.
  (let [p (record/baseline-prompt {:baseline baseline})]
    (is (str/includes? p "not reviewing the code for\ndefects"))
    (is (str/includes? p "belongs to code\nreview"))
    (is (str/includes? p "does that specific counterexample exist"))
    (is (str/includes? p "Neither is a finding that reports a bug in code the baseline"))))

(deftest a-baseline-from-before-the-move-still-makes-a-prompt
  ;; Legacy records are readable, so a round over one has to be too — it simply
  ;; has no decomposition to ask about.
  (let [p (record/baseline-prompt
           {:baseline (dissoc baseline :modules :composition)})]
    (is (not (str/includes? p "MODULES —")))
    (is (not (str/includes? p "COMPOSITION — how those are claimed")))
    (is (str/includes? p "the aggregate is the only summing path"))))

;; ── the shared model, as a judge reads it ────────────────────────────────────

(def ^:private model-baseline
  (-> baseline
      (dissoc :modules :composition :load-bearing)
      (assoc :model {:elements [{:id "canvas.order/aggregate" :sort :module
                                 :hides "the order in which lines are summed"
                                 :interface "an order's total"}]
                     :claims   [{:id "one-summing-path" :about ["canvas.order/aggregate"]
                                 :statement "the aggregate is the only summing path"
                                 :falsified-by "a caller outside the aggregate that reads lines and sums them"
                                 :evidence {:by :test :tests ["order.aggregate-test/one-path"]}
                                 :read-at ["src/order/aggregate.clj:12"]}]})))

(deftest a-model-baseline-shows-its-elements-and-claims-by-id
  (let [p (record/baseline-prompt {:baseline model-baseline})]
    (is (str/includes? p "ELEMENTS —"))
    (is (str/includes? p "[canvas.order/aggregate] (module)"))
    (is (str/includes? p "[one-summing-path] the aggregate is the only summing path"))
    (is (str/includes? p "about:      canvas.order/aggregate")
        "a claim says which elements it is about, by the ids the judge can cite back")
    (is (str/includes? p "checked by: tests order.aggregate-test/one-path"))
    (is (not (str/includes? p "LOAD-BEARING —")) "the survey section has nothing to show for a model")
    (is (str/includes? p "THE PERSPECTIVES THIS BASELINE IS READ THROUGH")
        "a model carries readings as the survey did, so the lenses are shown")))

(deftest a-model-baseline-is-worth-verifying-on-its-claims-alone
  (is (record/baseline-round-worth-running? (dissoc model-baseline :health))))

(deftest a-model-design-is-judged-claim-by-claim
  (let [d (-> design
              (dissoc :invariants)
              (assoc :model {:elements [{:id "canvas.order/aggregate" :sort :module}]
                             :claims   [{:id "rounded-once" :about ["canvas.order/aggregate"]
                                         :statement "a total is rounded exactly once"
                                         :falsified-by "two rounding calls reached for one total"
                                         :evidence {:by :round}}]}))
        p (record/design-prompt {:design d})]
    (is (str/includes? p "[rounded-once] a total is rounded exactly once"))
    (is (not (str/includes? p "Invariants:")))
    (is (str/includes? p "Populate confirmed with the claims you checked"))
    (is (str/includes? p "RULE ON EVERY CLAIM YOU ARE ASKED TO CHECK")
        "a claim left without a ruling is what stops a proceed")))

(deftest a-decision-records-the-claims-its-judge-confirmed
  (let [r (record/parse-design-decision
           (json/generate-string {:recommend "proceed" :reason "r" :asks "worth it?"
                                  :checks [{:check "relation_honest" :status "held" :note "n"}]
                                  :findings [] :confirmed ["[rounded-once]" "lines-exact" ""]})
           4)]
    (is (= ["rounded-once" "lines-exact"] (:confirmed r)) "ids, brackets and blanks taken off")
    (is (report/validate-event :design-decision r) "and the ledger takes it")))

(deftest a-commitment-the-code-does-not-meet-yet-is-owed-not-confirmed
  ;; A pre-build round confirms claims as sound commitments, and settlement read them as true of the
  ;; tree the judge read — so a later round at that tree skipped claims the code refutes.
  (let [r (record/parse-design-decision
           (json/generate-string {:recommend "proceed" :reason "r" :asks "worth it?"
                                  :checks [{:check "relation_honest" :status "held" :note "n"}]
                                  :findings [] :unchecked []
                                  :confirmed [{:id "rounded-once" :evidence ["src/a.clj:3"] :at_this_tree "holds"}
                                              {:id "lines-exact" :evidence ["src/a.clj:9"] :at_this_tree "owed"}]})
           4)]
    (is (= ["rounded-once"] (:confirmed r)))
    (is (= {"rounded-once" ["src/a.clj:3"]} (:checked-at r)))
    (is (= ["lines-exact"] (:owed r)))
    (is (report/validate-event :design-decision r))))

(deftest a-decision-leaving-a-claim-unruled-does-not-proceed
  (let [d {:format :design-decision :recommend :proceed :design-seq 4 :reason "r" :asks "a"
           :checks [{:check :relation-honest :status :held :note "n"}]}]
    (is (report/proceeds? d))
    (is (not (report/proceeds? (assoc d :unruled ["lines-exact"])))
        "a proceed that never ruled on a claim it was handed has not derived that nothing blocks it")
    (is (report/validate-event :design-decision (assoc d :unruled ["lines-exact"])))))

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

(deftest the-decision-prompt-shows-the-baseline-it-derives-against
  ;; The defect this is aimed at: the prompt named the baseline's modules,
  ;; extension points and health observations in its instructions and printed
  ;; none of them. A judge cannot tell "the baseline does not say" from "I was not
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
    (testing "and what the baseline already declared it did not determine"
      (is (str/includes? p "DECLARED NOT DETERMINED"))
      (is (str/includes? p "whether the CSV importer bypasses the aggregate")))))

(deftest a-baseline-from-before-the-move-still-makes-a-decision-prompt
  (let [p (record/design-prompt
           {:design design
            :baseline (dissoc baseline :modules :composition :health
                              :unknowns :extension-points)})]
    (is (not (str/includes? p "MODULES —")))
    (is (not (str/includes? p "EXTENSION POINTS")))
    (is (str/includes? p "the aggregate is the only summing path")
        "what the legacy baseline does carry is still shown")))

(deftest the-decision-prompt-survives-a-design-with-no-baseline-or-stance
  (is (string? (record/design-prompt {:design design}))
      "framing is optional everywhere else in this system; it is here too"))

(def ^:private legacy-design
  "A :design from before the baseline event: no :baseline, and still readable."
  {:format :design :summary "s" :shape "sh" :invariants ["i"]
   :standing {:relation :conforms} :effort :M})

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

(deftest a-host-finding-cites-an-observation-made-on-the-host
  ;; Watched: a judge broke goal-served on `sha256sum` being absent on macOS, cited from a
  ;; Homebrew formula page; `command -v sha256sum` on the host answered /sbin/sha256sum.
  (let [p (record/design-prompt {:design design})]
    (is (str/includes? p "MISSING on this host")
        "a host-environment finding is settled by the host, never by reading about it")
    (is (str/includes? p "the output of running it here")
        "documentation describes some host, so only an observation made here can break a check")))

(deftest only-an-intent-is-a-goal
  ;; The append boundary refuses a design citing anything but an :intent, so a citation that
  ;; resolves to another kind was never written through it and projects no goal.
  (let [cited (fn [entry]
                (with-redefs [stages/project+ws-from-cwd (fn [_] [:nido "ws-1"])
                              ws/entry-at-seq            (fn [_ _ _] entry)]
                  (record/discover-intent "/w" {:intent {:seq 1}})))]
    (is (= {:goal "Checkout off by a cent" :done-when ["one rounding point"]}
           (cited {:format :intent :goal "Checkout off by a cent"
                   :done-when ["one rounding point"]})))
    (is (nil? (cited {:format :triage-report :title "Checkout off by a cent"
                      :summary "Rounding applied per line."}))
        "a triage report is not a goal, whatever it summarises")))

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
    (is (not (str/includes? p "THE PERSPECTIVES THIS BASELINE IS READ THROUGH"))
        "a vocabulary nothing is read through is noise in the prompt")
    (is (not (str/includes? p "A READING IS A CLAIM TOO")))))

(deftest a-decomposition-with-no-analysis-is-told-to-report-itself
  ;; The gap this closes was watched, not imagined: the first baseline authored in
  ;; this shape carried five modules and zero readings. Gating the vocabulary on
  ;; readings BEING there hid it from exactly the baseline that needed it.
  (let [no-readings (update baseline :load-bearing
                            (fn [lb] (mapv #(dissoc % :readings) lb)))
        p (record/baseline-prompt {:baseline no-readings})]
    (is (str/includes? p "READS NOTHING THROUGH ANY PERSPECTIVE"))
    (is (str/includes? p "Report that as INSUFFICIENT, blocking relation-honest")
        "in a verdict the answer can carry, naming a derivation it blocks")
    (is (str/includes? p "THE PERSPECTIVES THIS BASELINE IS READ THROUGH")
        "the vocabulary is shown, because this record could carry it")
    (is (not (str/includes? p "A READING IS A CLAIM TOO"))
        "and it is not told to check readings it does not have")))

(deftest a-baseline-that-cannot-carry-readings-is-not-nagged-about-them
  (let [legacy (dissoc baseline :modules :composition)
        p (record/baseline-prompt {:baseline legacy})]
    (is (not (str/includes? p "READS NOTHING THROUGH ANY PERSPECTIVE")))
    (is (not (str/includes? p "THE PERSPECTIVES THIS BASELINE IS READ THROUGH")))))

(deftest a-current-baseline-still-gets-the-full-apparatus
  (let [p (record/baseline-prompt {:baseline baseline})]
    (is (str/includes? p "each with the\ncounterexample that would refute it"))
    (is (str/includes? p "refuted by: a caller outside the aggregate"))
    (is (str/includes? p "THE PERSPECTIVES THIS BASELINE IS READ THROUGH"))))

;; ── Insufficiency has to name what it blocks ────────────────────────────────

(deftest an-insufficient-finding-names-the-derivation-and-what-is-missing
  (let [r (record/parse-baseline-review
           (baseline-json {:verdict "insufficient" :reason "the cut cannot be stated"
                           :confirmed []
                           :findings [{:blocks "decomposable"
                                       :cites ["the aggregate is the only summing path"]
                                       :claim "the baseline never says where rounding is decided"
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
  ;; strings for a baseline of 13 claims and modules. Each round reworded them, so
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
    (is (str/includes? p "with the file:line references you read"))
    (is (str/includes? p "cannot be\nmatched to the claim"))
    (is (str/includes? p "CONFIRMED MEANS EVERY SENTENCE HELD")
        "a false clause the counterexample does not name is still a finding, not a note in reason")
    (is (str/includes? p "never\nfor a subject outside this round's checks")
        "the every-caller rule refutes only what the round checks")))

(deftest a-record-judge-rules-every-clause-of-a-compound-subject
  ;; Watched: a judge reported one counterexample to an amended composition claim and missed a
  ;; self-contradiction already in its text; the next round found it on text unchanged since.
  (doseq [[kind p] [[:baseline (record/baseline-prompt {:baseline {:format :baseline}})]
                    [:design (record/design-prompt {:design design})]]]
    (is (str/includes? p "RULED CLAUSE BY CLAUSE")
        (str kind ": a clause the judge read false and did not report costs a round to find again"))
    (is (str/includes? p "naming EVERY clause you found false")
        (str kind ": one finding per subject — a second finding on the same id is the same identity"))
    (is (str/includes? p "one the record itself already\nsatisfies is a contradiction")
        (str kind ": a refuter the record satisfies is a false clause, not a claim"))))

;; ── a record that names its strata ───────────────────────────────────────────
;; The era a record was written in is read off :strata, and it picks the yardstick: the stratified
;; check and derivation for a record that names its strata, decomposable as before for one that
;; does not.

(def ^:private a-stratum
  {:id "canvas.order.strata/totals" :sort :stratum
   :interface "an order's total, summed from exact lines"
   :readings [{:lens :stratified/level :verdict :sound :because "one vocabulary"}]})

(def ^:private strata-baseline
  (-> model-baseline
      (update-in [:model :elements] conj a-stratum)
      (assoc :strata ["canvas.order.strata/totals"])))

(def ^:private strata-design
  (-> design
      (dissoc :invariants)
      (assoc :model {:elements [{:id "canvas.order/aggregate" :sort :module}
                                {:id "canvas.order.strata/totals" :sort :stratum}]
                     :claims   [{:id "rounded-once" :about ["canvas.order/aggregate"]
                                 :statement "a total is rounded exactly once"
                                 :falsified-by "two rounding calls reached for one total"
                                 :evidence {:by :round}}]}
             :strata ["canvas.order.strata/totals"])))

(deftest a-baseline-naming-its-strata-is-asked-for-the-stratified-derivation
  (let [p (record/baseline-prompt {:baseline strata-baseline})]
    (is (str/includes? p "  stratified        does each part of the change sit in the level"))
    (is (not (str/includes? p "  decomposable      can its cut be stated")))
    (is (str/includes? p "STRATA — the levels this area declares"))
    (is (str/includes? p "a health observation names that stratum"))
    (is (str/includes? p "reads: a STRATUM (never a module or a claim)")))
  (testing "one written before names the cut it was judged by then"
    (is (str/includes? (record/baseline-prompt {:baseline model-baseline})
                       "  decomposable      can its cut be stated"))))

(deftest the-strata-list-ends-before-the-prose-that-reads-it
  ;; Glued on, the last stratum read as the first word of the next sentence; and "above" pointed a
  ;; judge whose every element was settled into the block it is told not to check.
  (let [p (record/baseline-prompt {:baseline strata-baseline})]
    (is (str/includes? p "- canvas.order.strata/totals\nEach is an element listed in this prompt")
        "the list ends its own line, and the prose names where the elements are without a direction")))

;; ── relation-honest, ruled per baseline id ──────────────────────────────────
;; Watched: one design's :breaks grew six, eight, twelve over three rounds, each round's judge and
;; amender sweeping "each claim and each module" and each finding ids the last had missed — strata
;; among them, which the instruction never named. Enumerated, every id is ruled on in one round.

(deftest relation-honest-is-ruled-on-every-baseline-id-by-name
  (let [p (record/design-prompt {:design design :baseline strata-baseline})]
    (is (str/includes? p "PER BASELINE ID, never as one reading of")
        "one judgement over the relation note lets a self-declared move pass on a lenient reading")
    (is (str/includes? p "claims:\n                          [one-summing-path]")
        "the claims are listed by id")
    (is (str/includes? p "modules:\n                          [canvas.order/aggregate]")
        "the modules are listed by id")
    (is (str/includes? p "strata:\n                          [canvas.order.strata/totals]")
        "the strata are listed too — the instruction that named only claims and modules missed them")
    (is (str/includes? p "relation_rulings") "the answer has a field to rule in")))

(deftest a-design-declaring-no-baseline-relation-is-ruled-on-no-ids
  ;; A design from before the baseline event has no :breaks to hold to any list.
  (let [p (record/design-prompt {:design (dissoc design :baseline) :baseline strata-baseline})]
    (is (not (str/includes? p "PER BASELINE ID")))
    (is (str/includes? p "relation_rulings stays empty"))))

(deftest the-legacy-baseline-shape-names-its-ids-too
  (is (= [[:claim "c1"] [:module "mod-the-order-aggregate"] [:module "mod-the-invoice-reader"]]
         (#'record/relation-subjects baseline))
      "a survey-shaped baseline is ruled on by the same ids its review settles")
  (is (= [] (#'record/relation-subjects nil))))

(defn- decided
  "A parsed proceed whose relation rulings are `rulings`."
  [rulings]
  (record/parse-design-decision
   (json/generate-string {:recommend "proceed" :reason "r" :asks "worth it?"
                          :checks [{:check "relation_honest" :status "held" :note "n"}]
                          :findings [] :confirmed [] :unchecked []
                          :relation_rulings rulings})
   4))

(def ^:private ids (#'record/relation-subjects strata-baseline))

(defn- held [decision breaks] (#'record/relation-held decision ids breaks))

(defn- every-id-stands []
  (mapv (fn [[_ id]] {:id id :ruling "stands" :reason "untouched"}) ids))

(deftest a-decision-keeps-its-relation-rulings
  (let [r (decided [{:id "[one-summing-path]" :ruling "breaks" :reason "moves"}
                    {:id "one-summing-path" :ruling "stands" :reason "a second word on it"}
                    {:id "canvas.order/aggregate" :ruling "maybe" :reason "not a ruling"}])]
    (is (= [{:id "one-summing-path" :ruling :breaks :reason "moves"}] (:relation-rulings r))
        "brackets off, one ruling per id, and only the two rulings there are")
    (is (report/validate-event :design-decision r) "and the ledger takes it")))

(deftest rulings-that-match-breaks-leave-the-decision-alone
  (let [r (decided (every-id-stands))]
    (is (= r (held r [])))))

(deftest an-id-left-without-a-ruling-stops-the-proceed
  (let [r (held (decided (rest (every-id-stands))) [])]
    (is (= ["one-summing-path"] (:unruled r))
        "a sweep that skipped an id has not derived the relation, so the decision does not proceed")
    (is (not (report/proceeds? r)))))

(deftest an-id-ruled-broken-that-breaks-omits-is-relation-honest-broken
  (let [rulings (assoc (every-id-stands) 2 {:id "canvas.order.strata/totals" :ruling "breaks"
                                            :reason "the level's vocabulary moves"})
        r       (held (decided rulings) [])]
    (is (= :broken (:status (first (filter #(= :relation-honest (:check %)) (:checks r)))))
        "the ruling and the check answer one question; the per-id one is what the check is held to")
    (is (= :amend (:recommend r)) "a proceed over an omitted break would hand the omission to nobody")
    (is (= ["canvas.order.strata/totals"] (:cites (last (:findings r)))))
    (is (= ["canvas.order.strata/totals: the level's vocabulary moves"] (:evidence (last (:findings r))))
        "the amender gets the judge's reason with the id")
    (is (report/validate-event :design-decision r) "and the ledger takes the record it becomes")))

(deftest a-breaks-naming-an-id-ruled-standing-claims-too-much
  (let [r (held (decided (every-id-stands)) ["one-summing-path"])]
    (is (str/includes? (:note (first (:checks r))) "under :breaks, ruled standing: one-summing-path"))
    (is (= :amend (:recommend r)))))

(deftest an-ask-stays-an-ask-over-a-mismatch
  (let [r (held (assoc (decided (every-id-stands)) :recommend :ask) ["one-summing-path"])]
    (is (= :ask (:recommend r)) "the question for a person still stops the run")))

(deftest a-relation-honest-with-no-yardstick-is-not-held-to-rulings
  (let [r (-> (decided [])
              (assoc :checks [{:check :relation-honest :status :underivable :note "no stance"}]))]
    (is (= r (held r ["one-summing-path"]))
        "an underivable check stays underivable, rather than ending the run :unruled")))

;; ── Every listed id ruled on, by construction ───────────────────────────────
;; Watched: told to rule on seventeen ids, a judge returned the six claims and skipped every module
;; and stratum, in five rounds of one run and in two runs running — and an unruled id stops the
;; proceed, so that lineage could never proceed.

(defn- rulings-schema [schema-json]
  (get-in (json/parse-string schema-json true) [:properties :relation_rulings]))

(deftest the-decision-schema-admits-exactly-the-listed-ids
  (let [s (rulings-schema (#'record/design-decision-schema ids))]
    (is (= (mapv second ids) (get-in s [:items :properties :id :enum]))
        "an id outside the list is no ruling the record can be held to")
    (is (= [(count ids) (count ids)] [(:minItems s) (:maxItems s)])
        "a claims-only answer is not one the schema admits")
    (is (= (set (map name (keys (get-in s [:items :properties])))) (set (get-in s [:items :required])))
        "the provider rejects a schema whose required is not every property"))
  (testing "a design ruled on no ids may rule on none"
    (let [s (rulings-schema (#'record/design-decision-schema []))]
      (is (= [0 0] [(:minItems s) (:maxItems s)]))
      (is (nil? (get-in s [:items :properties :id :enum])) "an empty enum is no schema a provider takes"))))

(defn- reasked
  "`decision` through the in-round re-ask, the judge answering `answer` (or failing, for nil).
   Returns [decision' every run-round! call]."
  [decision answer]
  (let [calls (atom [])]
    [(with-redefs [record/run-round! (fn [opts] (swap! calls conj opts)
                                       (if answer {:ok (json/generate-string answer)}
                                           {:outcome :no-output :detail "stub"}))]
       (#'record/rule-unruled-relations! decision ids {:run-id "r" :kind :design-decision :label "l"}
                                         {:design design :baseline strata-baseline}))
     @calls]))

(deftest the-ids-a-decision-skipped-are-asked-again-within-the-round
  (let [claims-only     (decided [{:id "one-summing-path" :ruling "stands" :reason "untouched"}])
        skipped         (mapv second (rest ids))
        [r [call :as cs]] (reasked claims-only
                                   {:relation_rulings
                                    (conj (mapv #(hash-map :id % :ruling "stands" :reason "kept") skipped)
                                          {:id "one-summing-path" :ruling "breaks" :reason "a second word"})})]
    (is (= 1 (count cs)) "one more ask, not a loop")
    (is (= skipped (get-in (rulings-schema (:schema call)) [:items :properties :id :enum]))
        "only the ids still owed are asked")
    (is (str/includes? (:prompt call) "Still to rule on:\n  modules:\n    [canvas.order/aggregate]"))
    (is (str/includes? (:prompt call) "[one-summing-path] stands — untouched")
        "the rulings already made are shown, not asked again")
    (is (= "l-unruled-relations" (:label call)) "its artifacts do not overwrite the decision's")
    (is (= {:id "one-summing-path" :ruling :stands :reason "untouched"}
           (first (:relation-rulings r)))
        "the re-ask cannot overturn a ruling the decision made")
    (is (empty? (:unruled (held r []))) "every id ruled, so nothing stops the proceed")
    (is (report/proceeds? (held r [])))
    (is (report/validate-event :design-decision (held r [])) "and the ledger takes it")))

(deftest a-decision-that-ruled-every-id-is-not-asked-again
  (let [r (decided (every-id-stands))]
    (is (= [r []] (reasked r {:relation_rulings []})))))

(deftest an-underivable-relation-honest-is-not-asked-for-rulings
  (let [r (-> (decided []) (assoc :checks [{:check :relation-honest :status :underivable :note "n"}]))]
    (is (= [] (second (reasked r {:relation_rulings []})))
        "relation-held holds it to no ruling, so asking for one buys nothing")))

(deftest a-failed-re-ask-leaves-the-ids-unruled
  (let [[r] (reasked (decided []) nil)]
    (is (= (mapv second ids) (:unruled (held r []))) "what two asks left open is still named")
    (is (not (report/proceeds? (held r []))))))

(deftest the-design-amender-is-handed-the-same-ids-with-the-judges-rulings
  (let [p (record/design-amend-prompt
           {:design design :baseline strata-baseline :recommend :amend :reason "r"
            :raised [{:check :relation-honest :status :broken :note "n"}]
            :rulings [{:id "canvas.order.strata/totals" :ruling :breaks :reason "the level moves"}]
            :out-path "/run/a.edn"})]
    (is (str/includes? p "THE BASELINE'S IDS, which relation-honest is ruled on one by one, as the judge ruled them"))
    (is (str/includes? p "  [canvas.order.strata/totals] breaks — the level moves"))
    (is (str/includes? p "  [one-summing-path]\n") "an id the judge did not rule is still listed")
    (is (str/includes? p "Go through the whole list")
        "a :breaks repaired one omission at a time takes a round per omission")))

(deftest a-design-naming-its-strata-is-judged-on-its-levels-not-its-cut
  (let [p (record/design-prompt {:design strata-design :baseline strata-baseline})]
    (is (str/includes? p "STRATA THIS CHANGE TOUCHES, floor first"))
    (is (str/includes? p "  stratified        — does the change sit where its levels say"))
    (doseq [q ["PLACEMENT" "BARRIER" "FIT"]]
      (is (str/includes? p q) (str "the stratified check asks " q)))
    (is (str/includes? p "This check blocks like the others."))
    (is (not (str/includes? p "AND IT NEVER BLOCKS")) "the cut's advisory rule is not this check's")
    (is (not (str/includes? p "CLAIMED DECOMPOSITION, VERTICAL"))))
  (testing "a design written with a cut is judged by decomposable, as it was"
    (let [layered (assoc design :layers [{:claim "extract the aggregate" :mode :judgment}])
          p       (record/design-prompt {:design layered :baseline baseline})]
      (is (str/includes? p "CLAIMED DECOMPOSITION, VERTICAL"))
      (is (str/includes? p "AND IT NEVER BLOCKS"))
      (is (not (str/includes? p "  stratified        —"))))))

(deftest a-stratified-check-is-read-back-and-holds-a-design
  (let [r (record/parse-design-decision
           (json/generate-string {:recommend "amend" :reason "r" :asks "a"
                                  :checks [{:check "stratified" :status "broken" :note "misplaced"}
                                           {:check "goal_served" :status "held" :note "n"}]
                                  :findings [{:cites ["x"] :claim "y" :check "stratified" :claim_id ""}]})
           12)]
    (is (= [:stratified :goal-served] (mapv :check (:checks r))))
    (is (= :stratified (:check (first (:findings r)))))
    (is (false? (report/proceeds? r))
        "unlike the cut it replaced, a broken stratified check alone holds the design")))

(deftest the-strata-a-record-names-resolve-against-the-declaration
  (let [listing {:status :listed
                 :elements [{:id "canvas.order/aggregate" :sort :fukan.common.vocab.code.module/Module}
                            {:id "canvas.order.strata/totals" :sort :fukan.common.vocab.code.stratum/Stratum}]}]
    (is (= [] (record/unresolved-subjects strata-design listing)))
    (is (= ["canvas.order.strata/totals"]
           (record/unresolved-subjects strata-design (update listing :elements pop)))
        "a named stratum the declaration does not hold is undeclared, whether or not a claim is about it")))

;; ── Who reads asks, and what is already answered ────────────────────────────

(deftest an-ask-needs-no-finding-and-the-ledger-takes-it
  ;; A doubt the build must not start without is a person's question whether or not it breaks a
  ;; check — the done-when caveat two judges in a row parked in the asks of a self-clearing design.
  (let [r (record/parse-design-decision
           (json/generate-string
            {:recommend "ask" :reason "the intent can be read two ways"
             :checks [{:check "goal_served" :status "held" :note "on one reading"}]
             :findings [] :asks "does a dead session count as working?"})
           4)]
    (is (= :ask (:recommend r)))
    (is (not (contains? r :findings)))
    (is (= r (report/validate-event :design-decision r)))))

(deftest the-judge-is-told-when-nobody-will-read-its-ask
  ;; A design declaring :conforms/:within clears on a proceed, and its required ask reached no
  ;; report, gate or view. Said before the judge answers, so a question the build must not start
  ;; without is recommended `ask` instead of parked where nobody looks.
  (let [modest (record/design-prompt {:design design})
        owing  (record/design-prompt {:design (assoc design :standing {:relation :challenges :note "n"})})]
    (is (str/includes? modest "WHO READS asks: NOBODY, on a proceed"))
    (is (str/includes? owing "WHO READS asks: a person"))
    (is (str/includes? modest "  ask      — stop for a person"))))

(deftest the-judge-is-shown-what-is-already-decided
  ;; Shown neither the record's :open nor its grant, the judge re-posed a product decision the
  ;; record stated as made, and asked the worth-executing question a person had already granted.
  (let [p (record/design-prompt
           {:design  (assoc design :open ["The name-order flip is confirmed as a product decision."])
            :answers {:grant {:seq 16 :self? false :note "approved in session"
                              :delta {:added ["drafts-not-scored"] :changed ["pipeline-boundary"]
                                      :dropped []}}
                      :asked "is the compression worth it now?"}})]
    (is (str/includes? p "- The name-order flip is confirmed as a product decision."))
    (is (str/includes? p "asks does not pose it again"))
    (is (str/includes? p "A person approved entry 16, which this design replaces"))
    (is (str/includes? p "added: drafts-not-scored"))
    (is (str/includes? p "changed: pipeline-boundary"))
    (is (str/includes? p "The grant is not evidence for any check")
        "the grant narrows the ask and nothing else; every check is still derived whole")
    (is (str/includes? p "ASKED BEFORE, AND NO GRANT WRITTEN SINCE:\n  is the compression worth it now?"))))

(deftest what-is-answered-is-read-off-the-ledger
  (let [claims  (fn [& cs] {:claims (mapv (fn [[id st]] {:id id :statement st :about ["m"]}) cs)})
        granted (assoc design :seq 16 :model (claims ["a" "one"] ["b" "two"]))
        current (assoc design :seq 20 :supersedes {:seq 16 :why "w"}
                       :model (claims ["a" "one"] ["b" "TWO"] ["c" "three"]))
        ledger  (fn [approvals decisions]
                  (with-redefs [ws/entry-at-seq (fn [_ _ n] ({16 granted 20 current} n))
                                ws/entries-of (fn [_ _ k] (case k
                                                            :design-approved approvals
                                                            :design-decision decisions
                                                            []))]
                    (record/answered :nido "ws-1" current)))]
    (testing "the nearest grant up the supersedes chain, with the claims changed since it"
      (let [a (ledger [{:seq 17 :design {:seq 16} :at-seq 16 :note "ok"}] [])]
        (is (= {:seq 16 :self? false :note "ok"
                :delta {:added ["c"] :changed ["b"] :dropped []}}
               (:grant a)))))
    (testing "a question put to a person stays open until a grant is written after it"
      (is (= "worth it?" (:asked (ledger [] [{:seq 18 :asks "worth it?"}]))))
      (is (nil? (:asked (ledger [{:seq 19 :design {:seq 16} :at-seq 18}]
                                [{:seq 18 :asks "worth it?"}])))))
    (is (= {} (ledger [] [])) "nothing granted and nothing asked")))

;; ── Who judged, and at which revision ───────────────────────────────────────

(def ^:private stand-in {:reviewer :claude :instead-of :codex :because "You've hit your usage limit"})

(deftest a-round-a-stand-in-answered-says-so
  (let [dir (str (fs/create-temp-dir))]
    (try
      (with-redefs [cstate/run-dir       (constantly dir)
                    codex/run-reviewer!  (fn [{:keys [out-path]}]
                                           (spit out-path "{}")
                                           {:exit 0 :log-path "l" :judged-by stand-in})]
        (is (= stand-in (:judged-by (#'record/run-round! {:run-id "r" :kind :baseline-review
                                                          :prompt "p"})))
            "a successful round dropped who answered, so every stand-in's verdict read as codex's"))
      (finally (fs/delete-tree dir)))))

(deftest a-judgement-keeps-who-made-it
  (let [json (baseline-json {:verdict "sufficient" :reason "r" :confirmed [] :findings []})
        r    (#'record/judged {:ok json :judged-by stand-in} #(record/parse-baseline-review % 3))]
    (is (= stand-in (:judged-by r))
        "a stand-in's confirmations settle subjects for later rounds exactly as codex's would")
    (is (= r (report/validate-event :baseline-review r))
        "the ledger refuses a judgement whole, so what is kept must be what it admits"))
  (is (= {:reviewer :claude}
         (:judged-by (#'record/judged {:ok (baseline-json {:verdict "sufficient" :reason "r"
                                                          :confirmed [] :findings []})
                                       :judged-by {:reviewer :claude :because nil}}
                                      #(record/parse-baseline-review % 3))))
      "a field the schema would refuse is left out rather than losing the entry")
  (is (nil? (:judged-by (#'record/judged {:ok (json/generate-string {:verdict "fits" :reason "r"
                                                                    :cites []})
                                         :judged-by stand-in}
                                        #(record/parse-stratum-reading % "s"))))
      "a level reading is evidence inside a decision, not a judgement the ledger holds alone"))

(deftest a-judgement-names-the-revision-its-judge-read
  (let [r (#'record/stamp-run {:format :baseline-review :baseline-seq 3 :reason "r" :verdict :sufficient}
                              {:run-id "baseline-loop-1" :judged-tree {:rev "abc123" :ahead 2}})]
    (is (= {:rev "abc123" :ahead 2} (:tree r))
        "a confirmation made at the base and one made at the tip must be told apart without a transcript")
    (is (= r (report/validate-event :baseline-review r))))
  (is (not (contains? (#'record/stamp-run {:outcome :codex-failed} {:judged-tree {:rev "a"}}) :tree))
      "an outcome is not a judgement and names no tree"))
