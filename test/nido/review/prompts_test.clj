(ns nido.review.prompts-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.review.prompts :as prompts]))

(def ^:private findings [{:priority 1 :title "t" :body "b"}])

(def ^:private design
  {:shape      "one rounding boundary at the order aggregate"
   :invariants ["a total is rounded exactly once"]
   :rejected   [{:alternative "round at render time" :why-not "money math in the view"}]
   :standing   {:relation :challenges :note "money math needs an accumulator"}})

(def ^:private a-toc
  "Two layers. Composition rules only exist where there is a composition, so a
   test about them has to hand the warden a stack — an empty toc now means a
   flat branch, and the prompt correctly stops talking about layers."
  [{:label "core" :claim "the ledger holds a decision"}
   {:label "wiring" :claim "the surface can reach it"}])

(def ^:private a-brief
  {:subject "refactor(pay): fold the rounding into the aggregate"
   :mode :mechanical
   :claims "the rename is uniform across all 40 call sites."
   :verify "confirm no call site got special handling."
   :lane "lane-malli"
   :out-of-scope "the new validation logic — that lands in the layer above."})

(deftest warden-prompt-inlines-the-design-record
  (let [out (prompts/warden-prompt {:findings findings :history [] :design design})]
    (is (str/includes? out "one rounding boundary at the order aggregate"))
    (is (str/includes? out "a total is rounded exactly once"))
    (is (str/includes? out "challenges — money math needs an accumulator"))
    (is (not (str/includes? out "Design doc")) "no path-handoff, no glob'd spec")))

(deftest warden-prompt-carries-rejected-alternatives-as-answered
  (let [out (prompts/warden-prompt {:findings findings :history [] :design design})]
    (is (str/includes? out "round at render time"))
    (is (str/includes? out "rejected because money math in the view"))
    (is (str/includes? out "ANSWERED, not new")
        "a finding re-proposing a rejected alternative is answered, not a new problem")))

(def ^:private design-with-seams
  "One seam of each closure kind. The permanent one is the measured case: a human
   wrote it to answer a park, naming the parked finding and arguing that a fourth
   gate would buy nothing."
  (assoc design
         :seams
         [{:what        "a client can POST startup after the audio socket has gone"
           :visible-how "the handler logs the race and returns 409"
           :closed-by   :permanent
           :why         "three rounds narrowed the window twice and a fourth would buy nothing"}
          {:what        "the legacy per-line path stays for invoices"
           :visible-how "both writers are still registered"
           :closed-by   :phase
           :phase       "retire the per-line writer"}
          {:what        "totals are not backfilled for archived orders"
           :visible-how "the archive report shows a blank column"
           :closed-by   :spun-out
           :ref         "FU-41"}]))

(deftest warden-prompt-carries-seams-as-answered
  ;; A seam answers a defect in advance, exactly as a rejected alternative does.
  ;; Unrendered, the warden reads the finding as new and rules `fix` on the gap
  ;; the record argued for.
  (let [out (prompts/warden-prompt {:findings findings :history []
                                    :design design-with-seams})]
    (is (str/includes? out "a client can POST startup after the audio socket has gone"))
    (is (str/includes?
         out
         "permanent — three rounds narrowed the window twice and a fourth would buy nothing")
        "a permanent seam closes on its own argument, so the argument is what the warden needs")
    (is (str/includes? out "Rule it `closed` on the authority `design`.")
        "a warden shown a seam and no destination still has to pick one")))

(deftest warden-prompt-sends-no-seam-to-a-fixer-or-to-a-human
  ;; The two ways a seam reopens. `fix` builds the machinery the record decided
  ;; against; `park` puts to a human the question the seam is already the answer
  ;; to — and a park is where this seam came from.
  (let [out (prompts/warden-prompt {:findings findings :history []
                                    :design design-with-seams})]
    (is (str/includes? out "Not `fix`:"))
    (is (str/includes? out "`park`: a park puts to a human a question this record answers."))
    (is (str/includes? out "The exception is a finding showing harm OUTSIDE what the seam")
        "a seam answers what it declares and no more, or it licenses anything near it")))

(deftest warden-prompt-says-where-a-scheduled-seams-closure-lives
  ;; What makes a :phase or :spun-out seam answered is that the closure is booked
  ;; somewhere else, so the place is the whole of the answer. A seam written
  ;; before :closed-by existed names no closure, and says so by absence: inventing
  ;; one would put an answer in its author's mouth.
  (let [out    (prompts/warden-prompt {:findings findings :history []
                                       :design design-with-seams})
        legacy (prompts/warden-prompt
                {:findings findings :history []
                 :design   (assoc design :seams
                                  [{:what        "the old column is still written"
                                    :visible-how "both columns appear in the schema"}])})]
    (is (str/includes? out "closed by phase — retire the per-line writer"))
    (is (str/includes? out "spun out as FU-41"))
    (is (str/includes?
         legacy
         "- the old column is still written — visible as: both columns appear in the schema\n")
        "the bullet ends where the record does")))

(deftest warden-prompt-omits-the-seams-block-when-the-record-declares-none
  ;; :seams is optional and most records carry none. A heading over an empty list
  ;; asserts the change declared no incompleteness, which is not a claim the
  ;; record made.
  (let [out (prompts/warden-prompt {:findings findings :history [] :design design})]
    (is (not (str/includes? out "DELIBERATE INCOMPLETENESS")))))

(deftest warden-prompt-asks-for-the-invariant-clause-verbatim
  ;; The rule the parser enforces has to be one the warden was told, and this is
  ;; where it is told: `stages/uncited-invariant` refuses an appeal that quotes
  ;; nothing, and a warden refused for breaking a rule nobody stated is failed
  ;; rather than checked. The cue word comes off the same var both sides read.
  (let [out (prompts/warden-prompt {:findings findings :history [] :design design})]
    (is (str/includes? out (str "A `because` using the word \""
                                prompts/invariant-citation-cue "\"")))
    (is (str/includes? out "verbatim, in double quotes or\nbackticks"))
    (is (str/includes? out "prefixes your sentence with a refusal")
        "and what it costs to paraphrase, since nothing else reports it")))

(deftest warden-prompt-renders-a-phased-invariant-as-a-clause-it-can-quote
  ;; A record written after phasing carries {:invariant :holds} maps. Bulleted
  ;; raw they reach the warden as printed EDN — unquotable, since the check
  ;; compares a span against the clause and not against the map around it. The
  ;; qualifier still has to survive: an :on-completion invariant is false for
  ;; the whole middle of a plan, and a warden shown it bare escalates a decision
  ;; that was already made.
  (let [out (prompts/warden-prompt
             {:findings findings :history []
              :design (assoc design :invariants
                             [{:invariant "a total is rounded exactly once" :holds :always}
                              {:invariant "every read goes through the aggregate"
                               :holds :on-completion}])})]
    (is (str/includes? out "- a total is rounded exactly once\n"))
    (is (not (str/includes? out ":holds")) "no EDN reaches the warden as prose")
    (is (str/includes? out "every read goes through the aggregate  [holds ON COMPLETION"))))

(deftest warden-prompt-ties-escalate-to-a-named-invariant
  (let [out (prompts/warden-prompt {:findings findings :history [] :design design})]
    (is (str/includes? out "CONTRADICTS A NAMED INVARIANT"))
    (is (str/includes? out "Do not escalate because a finding merely feels fundamental"))))

(deftest warden-prompt-without-a-design-record-forbids-escalation
  (let [out (prompts/warden-prompt {:findings findings :history [] :design nil})]
    (is (str/includes? out "No design record on this workstream"))
    (is (str/includes? out "do NOT park anything for contradicting an invariant"))
    (is (not (str/includes? out "Invariants:")))))

(deftest warden-prompt-without-a-design-record-still-recuts-a-bad-cut
  ;; The two destinations are independent. Park turns on a named invariant and
  ;; is unavailable with no design record; recut says the remedy is the stack's
  ;; shape, which is true whether or not anyone wrote a design
  ;; down. Collapsing them would send every cut finding to a fixer on exactly
  ;; the workstreams with the least written down about their shape.
  (let [out (prompts/warden-prompt {:findings findings :history [] :design nil})]
    (is (str/includes? out "The RECUT kinds above are still `recut`"))
    (is (str/includes? out "duplicated-across-layers \u2192 fold")
        "which kinds those are is derived from the taxonomy, not written out here")
    (is (str/includes? out "neither case turns on the"))))

(deftest warden-prompt-marks-the-stance-as-framing-not-checklist
  (let [out (prompts/warden-prompt {:findings findings :history [] :design design
                                   :stance "the shape of the data is the design"})]
    (is (str/includes? out "the shape of the data is the design"))
    (is (str/includes? out "NOT a checklist"))
    (is (str/includes? out "never cite it against a specific finding"))))

(deftest warden-prompt-omits-the-stance-block-when-absent
  (let [out (prompts/warden-prompt {:findings findings :history [] :design design})]
    (is (not (str/includes? out "PROJECT STANCE")))))

(deftest warden-prompt-names-findings-by-id-and-shows-who-reported-them
  (let [out (prompts/warden-prompt
             {:findings [{:id "aa11" :priority 1 :title "t" :body "b"
                          :reach :structural :from-layer "drop-legacy"}
                         {:id "bb22" :priority 2 :title "u" :body "c"}]
              :history [] :design design})]
    (is (str/includes? out "id aa11  [P1/structural] reported-by drop-legacy"))
    (is (str/includes? out "id bb22  [P2/unclear]")
        "an unlabelled finding is unclear, not local")))

(deftest warden-prompt-requires-an-authority-for-every-non-fix
  ;; A closed with no authority is a shrug, and is how a review quietly stops
  ;; reviewing.
  (let [out (prompts/warden-prompt {:findings findings :history [] :design design})]
    (is (str/includes? out "Nothing is dropped"))
    (is (str/includes? out "it is a shrug"))))

(deftest every-disposition-in-the-vocabulary-reaches-the-warden
  ;; The published half of the contract. A destination added to the list but not
  ;; rendered is one the warden is never told it may use, which is the drift the
  ;; list exists to make impossible.
  (let [out (prompts/warden-prompt {:findings findings :history [] :design design})]
    (doseq [{:keys [disposition]} prompts/disposition-vocabulary]
      (is (str/includes? out (str "- " (name disposition) ":"))
          (str (name disposition) " is offered in the prompt")))
    (is (str/includes? out (str/join "|" (map (comp name :disposition)
                                              prompts/disposition-vocabulary)))
        "the answer shape's enum is the list itself, in order"))
  (is (str/includes? (prompts/warden-prompt {:findings findings :history []})
                     (str "one of those " (count prompts/disposition-vocabulary)))
      "the count is read off the list, so a new destination cannot leave it stale"))

(def ^:private closing-authorities
  ;; Read off the vocabulary rather than off the helper that renders it, so this
  ;; checks the prompt against the declaration and not against itself.
  (some #(when (= :authority (:requires %)) (:one-of %))
        prompts/disposition-vocabulary))

(deftest every-authority-the-parser-accepts-is-one-the-warden-is-offered
  ;; The published half of the :one-of contract. The parser demotes a close on
  ;; an authority outside this list, so a ground the warden is never shown is a
  ;; close it cannot make — and one shown but not listed is a close the parser
  ;; silently refuses.
  (let [out (prompts/warden-prompt {:findings findings :history [] :design design})]
    (doseq [a closing-authorities]
      (is (str/includes? out a)
          (str a " is offered as an authority the warden may close on")))
    (is (str/includes? out (str/join "|" closing-authorities))
        "and the answer shape's enum is the list itself, in order")))

(deftest warden-prompt-says-what-becomes-of-a-ruling-that-omits-its-field
  ;; The demotion is not a surprise to spring on the warden: a close it thought
  ;; it made and the loop read as a fix is a disagreement about the round's
  ;; result, so the rule it will be held to is stated where it answers.
  (let [out (prompts/warden-prompt {:findings findings :history [] :design design})]
    (is (str/includes? out "is read as `fix`"))))

(deftest the-sweep-criterion-is-on-the-field-the-warden-writes-it-in
  ;; `sweep` is the one field a warden can answer without deciding — an omitted
  ;; boolean is false — so a criterion thirty-five lines below the field is a
  ;; criterion the default never meets. One run's warden wrote a `because`
  ;; granting a sweep and emitted the field false; the fixer was told nothing
  ;; and the class stayed open.
  (let [out    (prompts/warden-prompt {:findings findings :history [] :design design})
        schema (subs out 0 (str/index-of out "Every finding below"))]
    (is (str/includes? schema "could fail this same")
        "the question is asked where the value is written, not in a later section")
    (is (str/includes? schema "Unsure is true")
        "the tie-break too — a warden that never reads the section never reads it")))

(deftest a-sweep-asks-about-the-class-not-about-a-sibling-already-named
  ;; A warden withheld a sweep because the finding's own prose said the
  ;; neighbouring window was already covered, so it asked whether a sibling was
  ;; KNOWN. Round 2 then found a different hole in the same claim clause, in the
  ;; same job of the same file, and cost 18 minutes of fan-out to do it.
  (let [out (prompts/warden-prompt {:findings findings :history [] :design design})]
    (is (str/includes? out "not about whether a sibling is")
        "an unenumerated class is exactly the one worth sweeping")
    (is (not (str/includes? out "one INSTANCE of a defect"))
        "asking whether this IS an instance invites counting the ones already visible")))

(deftest a-because-cannot-grant-a-sweep-the-field-denies
  ;; The fixer's SWEEP block renders on the field alone, so the prose is not a
  ;; second channel — a ruling whose sentence orders an audit over a field that
  ;; says false leaves an account claiming a class was closed that nothing swept.
  (let [out (prompts/warden-prompt {:findings findings :history [] :design design})]
    (is (str/includes? out "`sweep` and `because` are one ruling"))
    (is (str/includes? out "your sentence is not read for it")
        "the warden cannot otherwise know its prose is decorative here")))

(deftest warden-prompt-assigns-a-composition-finding-to-the-highest-layer
  (let [out (prompts/warden-prompt {:findings findings :history [] :design design
                                    :toc a-toc})]
    (is (str/includes? out "assign it to the HIGHEST layer involved"))))

(deftest toc-block-is-a-map-of-claims-and-files-not-diffs
  (let [s (prompts/toc-block [{:label "a" :claim "the rename is uniform"
                               :files ["src/x.clj"]}])]
    (is (str/includes? s "the rename is uniform"))
    (is (str/includes? s "src/x.clj"))
    (is (str/includes? s "map only")))
  (is (nil? (prompts/toc-block []))))

(deftest warden-sees-each-layers-out-of-scope
  ;; It is told it may close on the authority "out-of-scope"; without the field
  ;; that is a word it can cite but never read.
  (let [out (prompts/warden-prompt
             {:findings findings :history [] :design design
              :toc [{:label "shape" :claim "c"
                     :out-of-scope "the new validation logic"}]})]
    (is (str/includes? out "out of scope: the new validation logic"))))

(deftest warden-gets-back-what-it-already-closed-grouped-by-layer
  ;; The reviewer starts fresh every round and re-reports closed findings. These
  ;; are the warden's OWN prior closes, so they are a default it may reverse
  ;; rather than a ruling to defer to.
  (let [out (prompts/warden-prompt
             {:findings findings :history [] :design design
              :answered [{:label "shape"
                          :answered [{:id "aa11" :title "t"
                                      :authority "out-of-scope"
                                      :because "the layer below owns it"}]}]})]
    (is (str/includes? out "ALREADY SETTLED IN AN EARLIER ROUND"))
    (is (str/includes? out "shape"))
    (is (str/includes? out "aa11"))
    (is (str/includes? out "out-of-scope"))
    (is (str/includes? out "say why that answer no\nlonger holds"))))

(deftest warden-omits-the-answered-block-when-nothing-was-closed
  (let [out (prompts/warden-prompt {:findings findings :history [] :design design})]
    (is (not (str/includes? out "ALREADY SETTLED IN AN EARLIER ROUND")))))

(deftest warden-is-told-not-to-patch-a-structural-finding-away
  (let [out (prompts/warden-prompt {:findings findings :history [] :design design})]
    (is (str/includes? out "patching a\ndesign question makes it disappear without anyone deciding it"))))


(deftest layer-brief-block-states-out-of-scope-as-a-prohibition
  ;; Given merely as context, a reviewer flags the item anyway and lets the
  ;; reader sort it out — which is the whole cost bounded review avoids.
  (let [s (prompts/layer-brief-block a-brief)]
    (is (str/includes? s "the new validation logic"))
    (is (str/includes? s "PROHIBITION"))))

(deftest a-layer-reviewer-owns-its-own-claim-and-can-say-it-cannot-check-it
  ;; A claim only falsifiable by reading the layer ABOVE was written at the wrong
  ;; altitude, and the layer reviewer is the only reader holding that claim
  ;; against that diff. Without a way to say "I cannot check this from here" the
  ;; question travelled to the composition pass, which reported it as a defect in
  ;; the cut and got it parked.
  (let [s (prompts/layer-brief-block a-brief)]
    (is (str/includes? s "CHECKING IT IS"))
    (is (str/includes? s "CANNOT check a claim from this diff alone")
        "the reviewer is given the sentence it had no way to say")
    (is (str/includes? s "wrong altitude")
        "and told what that means about the cut")
    (is (str/includes? s "`mechanical`")
        "the sharp case is named, not left to be inferred")))

(deftest layer-brief-block-carries-the-claims-and-the-review-mode
  (let [s (prompts/layer-brief-block a-brief)]
    (is (str/includes? s "uniform across all 40 call sites"))
    (is (str/includes? s "mechanical"))
    (is (str/includes? s "lane-malli"))))

(deftest layer-brief-block-makes-a-named-test-namespace-evidence-to-read
  ;; The run this defends against: a layer split a rendered `60%` into a figure
  ;; and a unit span, the reviewer had `(is (str/includes? html "60%"))` open in
  ;; front of it, and reported nothing. A second round found it.
  (let [s (prompts/layer-brief-block a-brief)]
    (is (str/includes? s "EVIDENCE you must read")
        "unmarked, a test namespace in Verify reads as background a reviewer may skip")
    (is (str/includes? s "<head>")
        "an assertion read at the working copy may be one this layer already updated")
    (is (str/includes? s "you cannot run them")
        "the sandbox forbids running a test, so discharge has to be defined as reading")
    (is (str/includes? s "left standing is a")
        "a contradicted assertion the layer did not update has to arrive as a finding")
    (is (str/includes? s "UPDATED")
        "a test the layer rewrote on purpose must not read as one")))

(deftest layer-brief-block-is-nil-for-a-whole-stack-review
  ;; Saying "no brief" would read as an instruction to go wide; saying nothing
  ;; leaves the unbounded pass unbounded, which is what it is for.
  (is (nil? (prompts/layer-brief-block nil)))
  (is (nil? (prompts/layer-brief-block {:subject "review-loop: iter 1 fixes"}))))


;; ---- the composition pass -------------------------------------------------

(def ^:private two-layers
  [{:label "series" :from "FORK" :tip "cA"
    :claim "the series entity and its migration; nothing reads it yet."
    :out-of-scope "the banner UI — that lands in the layer above."
    :files ["src/model/series.clj"]}
   {:label "banner" :from "cA" :tip "cB"
    :claim "renders the banner; no change to how a goal is computed."
    :files ["src/ui/banner.clj"]}])

(deftest composition-block-asks-about-the-cut-and-the-wiring
  ;; Not about a wider diff. Reading the branch flat is what the layer reviews
  ;; already do between them; what nothing else in the loop is asked is whether
  ;; the change was cut into the right pieces and whether they hold together.
  (let [s (prompts/composition-block {:layers two-layers})]
    (is (str/includes? s "THE CUT — are these the right pieces?"))
    (is (str/includes? s "THE WIRING — do the pieces hold together?"))
    (is (str/includes? s "not so\nthat you can review it flat"))
    (doseq [{:keys [kind asks]} prompts/composition-kinds]
      (is (#{:cut :wiring} asks) (str kind " answers neither question")))))

(deftest composition-block-hands-over-the-intermediate-revisions
  ;; The evidence for the wiring half, and the whole difference between this
  ;; primer and the map a warden gets: `toc-block` withholds revisions so a
  ;; warden cannot re-derive its neighbours, and this hands them over so each
  ;; piece can be looked at standing on its own.
  (let [s (prompts/composition-block {:layers two-layers})]
    (is (str/includes? s "--from FORK --to cA"))
    (is (str/includes? s "--from cA --to cB"))
    (is (str/includes? s "-r cA") "the tree a layer's own PR would merge")
    (is (str/includes? s "--ignore-working-copy file show"))
    (is (str/includes? s "Never `cat`"))))

(deftest composition-block-refuses-a-defect-that-names-one-layer
  ;; Without this the pass re-derives every layer it was supposed to trust —
  ;; which is the exact cost the layering was built to avoid, paid again by the
  ;; one pass that was supposed to be buying something else.
  (let [s (prompts/composition-block {:layers two-layers})]
    (is (str/includes? s "without naming two
layers, it is not yours"))
    (is (str/includes? s "ALREADY been reviewed on its own"))
    (is (str/includes? s "Two or more, always"))))

(deftest composition-block-carries-each-layer-claim-and-exclusion
  (let [s (prompts/composition-block {:layers two-layers})]
    (is (str/includes? s "nothing reads it yet"))
    (is (str/includes? s "the banner UI — that lands in the layer above"))
    (is (str/includes? s "src/model/series.clj"))
    (is (str/includes? s "1. series"))
    (is (str/includes? s "2. banner"))))

(deftest composition-block-teaches-every-kind-the-schema-will-accept
  (let [s (prompts/composition-block {:layers two-layers})]
    (doseq [{:keys [kind]} prompts/composition-kinds]
      (is (str/includes? s kind) (str kind " is missing from the primer")))))

(deftest composition-block-asks-for-the-remedy-and-says-what-refusing-buys
  ;; The reviewer is the only reader that knows which move the defect in front
  ;; of it actually needs. Offered the values without being told that a split is
  ;; refused ON PURPOSE, it reaches for the nearest performable word and the
  ;; loop performs it.
  (let [s (prompts/composition-block {:layers two-layers})]
    (doseq [{:keys [remedy]} prompts/remedy-vocabulary]
      (is (str/includes? s remedy) (str remedy " is missing from the primer")))
    (is (str/includes? s "not for its kind"))
    (is (str/includes? s "refused and put to a human"))))

(deftest composition-block-is-nil-below-two-layers
  ;; Not a degradation: with nothing to compose the whole-stack target IS the
  ;; branch review, and priming it as a composition pass would tell it to
  ;; report only findings that cannot exist.
  (is (nil? (prompts/composition-block nil)))
  (is (nil? (prompts/composition-block {:layers []})))
  (is (nil? (prompts/composition-block {:layers [(first two-layers)]}))))

(deftest warden-prompt-shows-a-composition-findings-kind-and-span
  (let [out (prompts/warden-prompt
             {:findings [{:id "aa11" :priority 2 :title "t" :body "b"
                          :reach :structural :from-layer "stack"
                          :kind :misplaced-cut :layers ["series" "banner"]}]
              :history [] :design design})]
    (is (str/includes? out "reported-by stack · misplaced-cut · across series + banner"))))

(deftest warden-prompt-recuts-a-bad-cut-instead-of-handing-it-to-a-fixer
  ;; A fixer can only patch one side of a seam, and a patched seam converges —
  ;; so the round reports success and the wrong cut ships.
  (let [out (prompts/warden-prompt {:findings findings :history [] :design design})]
    (is (str/includes? out "the remedy is the SHAPE of the stack"))
    (is (str/includes? out "makes that cut permanent"))
    (is (str/includes? out "will move or merge them"))))

(deftest warden-prompt-attributes-a-composition-finding-by-what-it-spans
  ;; The highest-layer rule itself is guarded above; this is the new half — the
  ;; warden is no longer guessing the span off file lists, the pass reports it.
  (let [out (prompts/warden-prompt {:findings findings :history [] :design design
                                    :toc a-toc})]
    (is (str/includes? out "names the ones it spans after `across`"))
    (is (str/includes? out "spans only ONE layer")
        "a stack finding naming one layer is that layer's own, reported twice")))

(deftest an-unlayered-branch-is-never-asked-for-an-owner-layer
  ;; With no layers there is no label to attribute to, and asking anyway got a
  ;; file path back on every ruling of a run — a nonsense value the loop then
  ;; absorbed in silence.
  (let [flat (prompts/warden-prompt {:findings findings :history [] :design design})
        lay  (prompts/warden-prompt {:findings findings :history [] :design design
                                     :toc a-toc})]
    (is (not (str/includes? flat "\"owner_layer\":"))
        "the field is absent from the answer shape, not merely unexplained")
    (is (str/includes? flat "there is no owner_layer to give"))
    (is (str/includes? flat "single unlayered branch"))
    (is (str/includes? lay "\"owner_layer\":"))))

(deftest a-multi-layer-stack-finding-is-not-closed-as-a-duplicate
  ;; The one-layer rule says a stack finding naming a single layer belongs to
  ;; that layer. Applied to two, it swallows the only cut-level signal a round
  ;; produced — the remedy can be a duplicate while the observation is not.
  (let [out (prompts/warden-prompt {:findings findings :history [] :design design
                                    :toc a-toc})]
    (is (str/includes? out "spanning TWO"))
    (is (str/includes? out "Do not close it `duplicate`"))
    (is (str/includes? out "absorbs the only cut-level signal"))))

(deftest the-warden-can-park-a-recurrence-with-no-design-record
  ;; It diagnosed a three-round oscillation and had no disposition for it, so it
  ;; ruled fix a third time. The licence rests on the run's own history.
  (let [out (prompts/warden-prompt {:findings findings :history [] :design nil})]
    (is (str/includes? out "RECURRENCE"))
    (is (str/includes? out "do NOT need a design record"))
    (is (str/includes? out "Park on RECURRENCE still applies"))))

(deftest a-composition-finding-the-loop-cannot-move-is-parkable-with-no-design
  ;; Run review-74e50bd5 found an `aggregate` defect whose answer was a boundary
  ;; decision, on a branch carrying no design record. `fix` the warden ruled out
  ;; itself, `recut` wants a move the kind does not have, and park offered a
  ;; design invariant or a recurrence — neither available. It fell through to
  ;; `deviation`, which settles AND keeps, so the run reported 0 remaining and
  ;; terminated clean on a question nobody had answered.
  (let [park (some #(when (= :park (:disposition %)) (:means %))
                   prompts/disposition-vocabulary)]
    (is (str/includes? park "NO MOVE")
        "the ground has a name the rest of the prompt can refer to")
    (is (str/includes? park "do NOT need a design record")
        "and it is reachable on a branch the reviewed work is free not to give one")
    (doseq [{:keys [kind]} prompts/composition-kinds]
      (is (not (str/includes? park kind))
          (str "the ground is stated over the finding's own kind and the RECUT"
               " list, never over an enumeration: naming " kind " here is how the"
               " next kind added falls out of it in silence")))))

(deftest the-no-design-branch-keeps-every-ground-that-is-not-the-record
  ;; It removes ground (a), which is right, and used to leave RECURRENCE as the
  ;; only park a design-less run could reach — three lines above telling the
  ;; warden that the kinds no fixer should get are still not a fixer's. Both
  ;; true, and together they name no destination at all.
  (let [out (prompts/warden-prompt {:findings findings :history [] :design nil})]
    (is (str/includes? out "So does park on NO MOVE")
        "the ground that rests on the finding survives, as recurrence does")
    (is (str/includes? out "not a `deviation`")
        "and the settling door the warden went through instead is named")))

(deftest the-composition-pass-is-forbidden-the-module-boundary-subject
  ;; Where a module boundary belongs is judged before code exists, by the design
  ;; round's `decomposable`, which reads the surveyed modules. This pass has
  ;; neither. Of the 19 parked misplaced-cut findings in the corpus, several were
  ;; module-boundary observations wearing a layer's clothes — "put the lock
  ;; protocol in a database foundation layer" — and every one of them was ruled
  ;; un-actionable after costing a round.
  (let [out (prompts/composition-block
             {:layers [{:label "a" :from "x" :tip "y"}
                       {:label "b" :from "y" :tip "z"}]})]
    (is (str/includes? out "MODULE BOUNDARIES ARE NOT YOUR SUBJECT"))
    (is (str/includes? out "decomposable")
        "and it names where the subject does belong")
    (is (str/includes? out "belongs in a Y layer")
        "the phrasing to recognise it by is given")
    (testing "and the pass is told what earns a finding instead"
      (is (str/includes? out "WHAT EARNS A FINDING HERE"))
      (is (str/includes? out "INTERMEDIATE revisions")
          "its value is the reach no other reader has, not the wider range")
      (is (str/includes? out "Coming back empty is the common\ncorrect answer")))))

(deftest a-cut-kind-with-no-mechanical-remedy-is-not-a-fixers-work
  ;; claim-falsified asks about the cut and names no move the loop can make.
  ;; Hardcoding two kind names sent it to fixers, and their minimal edits made
  ;; the seam harder to see rather than gone.
  (let [out (prompts/warden-prompt {:findings findings :history [] :design design
                                    :toc a-toc})
        cut-kinds (->> prompts/composition-kinds (filter #(= :cut (:asks %))))]
    (is (str/includes? out "NOT A FIXER'S WORK"))
    (doseq [k (remove :remedy cut-kinds)]
      (is (str/includes? out (str "- " (:kind k)))
          (str (:kind k) " is listed as something no fixer should get")))
    (doseq [k (filter #(and (:remedy %) (not= :packaging (:costs %)))
                      prompts/composition-kinds)]
      (is (str/includes? out (str "- " (:kind k) " → " (name (:remedy k))))
          (str (:kind k) " is listed as a recut with its move")))
    ;; A remedy is no longer reason enough to perform one. A packaging kind has
    ;; a fold or a reorder available and is still advisory, because the layers
    ;; are collapsed before the branch lands.
    (doseq [k (filter #(= :packaging (:costs %)) prompts/composition-kinds)]
      (is (str/includes? out (str "- " (:kind k) "\n"))
          (str (:kind k) " is listed"))
      (is (not (str/includes? out (str "- " (:kind k) " → ")))
          (str (:kind k) " must not be offered as a recut")))
    (is (str/includes? out "ADVISORY")
        "the advisory destination is named")))

(deftest the-warden-is-told-what-a-recut-actually-performs
  ;; It rules `recut` and the loop decides what happens next. Told only which
  ;; move each kind maps to, it reads the routing table as a promise, and a
  ;; recut the reshape stage refuses for its remedy looks like the loop losing
  ;; the finding rather than answering it.
  (let [out (prompts/warden-prompt {:findings findings :history [] :design design
                                    :toc a-toc})]
    (is (str/includes? out "only where the finding's own"))
    (is (str/includes? out "refused and becomes a park"))
    (is (str/includes? out "Rule it `recut` all the same")
        "or the warden routes around the one record the refusal leaves")))

(deftest the-warden-sees-the-designs-claimed-decomposition
  ;; Without it the warden cannot tell that the stack has three layers where the
  ;; design named two — a finding about the cut nothing else in the loop reaches.
  (let [out (prompts/warden-prompt
             {:findings findings :history [] :toc a-toc
              :design (assoc design :layers [{:claim "the ledger holds a decision"
                                              :mode :structural}])})]
    (is (str/includes? out "CLAIMED DECOMPOSITION"))
    (is (str/includes? out "the ledger holds a decision"))
    (is (str/includes? out "A layer the design NEVER NAMED is a finding"))))

(deftest the-fixer-is-told-what-the-warden-wrote-for-it
  ;; :because is addressed to this reader — why the finding is real, or which
  ;; layer it was moved to and why. It was produced every round and rendered to
  ;; nobody.
  (let [out (prompts/fix-prompt
             {:findings [{:priority 1 :title "t" :body "b" :file "a.clj"
                          :line-start 1 :line-end 2
                          :kind "misplaced-cut" :across ["core" "wiring"]
                          :because "moved down: core is where the guarantee is dropped"}]})]
    (is (str/includes? out "moved down: core is where the guarantee is dropped"))
    (is (str/includes? out "misplaced-cut"))
    (is (str/includes? out "spans core, wiring"))))

(deftest the-fixer-is-bounded-by-the-layer-it-is-working-on
  ;; "Make the MINIMAL change" is the only guidance a fixer had, and for a defect
  ;; spanning a seam the minimal change is a patch on whichever side it was
  ;; reported from.
  (let [out (prompts/fix-prompt
             {:findings [{:priority 1 :title "t" :body "b"}]
              :stack a-toc
              :layer {:label "core" :claim "the ledger holds a decision"
                      :out-of-scope "the surface that reads it"}})]
    (is (str/includes? out "It claims: the ledger holds a decision"))
    (is (str/includes? out "OUT OF SCOPE: the surface that reads it"))
    (is (str/includes? out "That is a prohibition"))))

(deftest a-fixer-with-no-layer-gets-no-empty-brief
  ;; A flat branch has no layer to be bounded by, and a heading with nothing
  ;; under it reads as a bound that was checked and found empty.
  (let [out (prompts/fix-prompt {:findings [{:priority 1 :title "t" :body "b"}]})]
    (is (not (str/includes? out "ONE LAYER OF A STACKED CHANGE")))))

(deftest a-layer-that-declared-nothing-is-still-told-it-is-in-a-stack
  ;; The block used to be gated on the brief, so a stack whose layers carry no
  ;; Claims and no Out of scope told its fixer nothing about being in a stack at
  ;; all — which is how one edited a file the layer above it owns.
  (let [out (prompts/fix-prompt
             {:findings [{:priority 1 :title "t" :body "b"}]
              :stack [{:label "bench" :files ["bench.clj" "doc.md"]}
                      {:label "doc" :files ["doc.md"]}]
              :layer {:label "bench" :files ["bench.clj" "doc.md"]}})]
    (is (str/includes? out "ONE LAYER OF A STACKED CHANGE — bench")
        "a layer that claims nothing is still a layer, and the fixer is standing in one")
    (is (str/includes? out "It is layer 1 of 2, bottom to top")
        "which layer it is decides which rows are above it")))

(deftest the-fixer-is-given-the-files-of-the-layers-above-its-own
  ;; The rollback this exists to stop: the fixer edited a file its own layer and
  ;; the layer above both touch, the rebase conflicted, and the whole repair was
  ;; restored away. Nothing in the prompt had named the overlap, though the run
  ;; had built the map that shows it.
  (let [out (prompts/fix-prompt
             {:findings [{:priority 1 :title "t" :body "b"}]
              :stack [{:label "bench" :files ["bench.clj" "doc.md"]}
                      {:label "doc" :files ["doc.md"]}]
              :layer {:label "bench" :files ["bench.clj" "doc.md"]}})]
    (is (str/includes? out "THE LAYERS ABOVE YOURS"))
    (is (str/includes? out "2. doc — doc.md")
        "the file list is the whole point: a label cannot answer whose file this is")
    (is (str/includes? out "rolled back and lost")
        "what it costs to edit one is why the fixer should not")
    (is (str/includes? out "NAME the file and what it needs")
        "a file it may not touch reaches the layer that owns it only if it is said")))

(deftest the-top-layer-is-told-nothing-is-above-it
  ;; A block that lists layers above and then lists none reads as a lookup that
  ;; failed. The top layer has a real answer and it is worth having: nothing is
  ;; rebased onto its fix, so no file is out of reach.
  (let [out (prompts/fix-prompt
             {:findings [{:priority 1 :title "t" :body "b"}]
              :stack [{:label "core" :files ["a.clj"]}
                      {:label "wiring" :files ["b.clj"]}]
              :layer {:label "wiring" :files ["b.clj"]}})]
    (is (str/includes? out "you are the top layer"))
    (is (not (str/includes? out "THE LAYERS ABOVE YOURS")))))

(deftest a-layer-the-stack-no-longer-names-gets-no-positional-claim
  ;; The reshape stage rewrites layers between the review that built this map and
  ;; the repair that reads it. A row that no longer matches is worse than no row
  ;; when what the row asserts is which files belong to whom.
  (let [out (prompts/fix-prompt
             {:findings [{:priority 1 :title "t" :body "b"}]
              :stack [{:label "core" :files ["a.clj"]}
                      {:label "wiring" :files ["b.clj"]}]
              :layer {:label "folded"}})]
    (is (str/includes? out "ONE LAYER OF A STACKED CHANGE — folded")
        "it is still in a stack, and that much is known")
    (is (not (str/includes? out "bottom to top")))
    (is (not (str/includes? out "THE LAYERS ABOVE YOURS")))))

(deftest sweep-widens-one-finding-into-its-family
  ;; The warden sees the class and had no field to name it in, so rounds
  ;; surfaced its members one per round.
  (let [swept (prompts/fix-prompt
               {:findings [{:priority 1 :title "t" :body "b" :sweep true}]})
        plain (prompts/fix-prompt
               {:findings [{:priority 1 :title "t" :body "b"}]})]
    (is (str/includes? swept "SWEEP"))
    (is (str/includes? swept "find its siblings and fix those too"))
    (is (not (str/includes? plain "SWEEP")))))

(deftest a-sweep-searches-the-defect-class-not-the-diff
  ;; A sweep bounded to "this layer" is bounded to the patch, and the sibling
  ;; that then survives is the pre-existing line beside the one just edited —
  ;; one run swept an envelope-construction class, fixed the site in its diff,
  ;; and left the identical defect two lines above it for a design verdict to
  ;; catch two rounds later.
  (let [out (prompts/fix-prompt
             {:findings [{:priority 1 :title "t" :body "b" :sweep true}]})]
    (is (str/includes? out "over the defect CLASS, not over this")
        "the class is the search space; the diff is where the first instance happened to be")
    (is (not (str/includes? out "audit this layer"))
        "a layer is its diff, so bounding the search to it excludes pre-existing siblings")
    (is (str/includes? out "NAMED in your final message")
        "a sibling out of this fixer's reach reaches the next round only if it is said")))

(deftest a-sweep-searches-by-signature-rather-than-reading-the-files-whole
  ;; The whole-surface read is the prefix every swept finding's search shares,
  ;; so a fixer holding several front-loads all of it: one handed three findings
  ;; the warden had settled spent 42 tool calls, every one a read, and landed no
  ;; edit before a person stopped it.
  (let [out (prompts/fix-prompt
             {:findings [{:priority 1 :title "t" :body "b" :sweep true}]})]
    (is (str/includes? out "SEARCH for it rather than reading for it")
        "the sibling is found by what the class has in common, not by re-reading the surface")
    (is (str/includes? out "Name the signature the class shares")
        "a search the fixer cannot state is one nothing downstream can check")
    (is (str/includes? out "what you searched for")
        "a signature that hit nothing is an answer; a read that found nothing leaves none")
    (is (not (str/includes? out "read every file this change touched"))
        "reading the whole surface costs the round the repairs were supposed to land in")))

(deftest a-swept-repair-lands-before-the-search-that-follows-it
  ;; A fixer killed on its budget has whatever the tree holds committed as its
  ;; repair, so the phase is incremental only if the edits precede the reading.
  ;; The per-finding prose alone did not hold it: with several findings the
  ;; shared read came first and three adjudicated defects were repaired zero
  ;; times.
  (let [out (prompts/fix-prompt
             {:findings [{:priority 1 :title "t" :body "b" :sweep true}]})]
    (is (str/includes? out "ORDER — repair first, search second")
        "what survives an early ending is what is already in the tree")
    (is (str/includes? out "Fix it and land that repair; only then")
        "the sweep block orders the same thing where the search is actually given")
    (is (< (.indexOf out "ORDER — repair first") (.indexOf out "SWEEP:"))
        "the fixer reads the ordering before it reads the search it governs")))

(deftest a-fixer-with-nothing-to-sweep-is-not-told-what-to-do-second
  ;; An ordering clause naming a SWEEP section that is not there reads as a
  ;; lookup that failed, and there is no search to come second.
  (let [out (prompts/fix-prompt
             {:findings [{:priority 1 :title "t" :body "b"}]})]
    (is (not (str/includes? out "ORDER — repair first, search second")))))

(deftest a-sweep-whose-class-already-came-back-asks-for-a-different-remedy
  ;; Enumerating the instances is the remedy the class has already survived. Of
  ;; six sweeps ordered in one run three classes returned the next round, and
  ;; both that held did so because the fixer changed what the instances were
  ;; derived from rather than by finding more of them.
  (let [out (prompts/fix-prompt
             {:findings [{:priority 1 :title "t" :body "b" :sweep true
                          :swept-before [2 3]}]})]
    (is (str/includes? out "already swept in rounds 2, 3")
        "the fixer starts cold every round and cannot tell a repeat from a first sweep")
    (is (str/includes? out "are DERIVED from, so the class cannot have another")
        "the remedy a returning class needs is at the source, not at the sites")
    (is (not (str/includes? out "find its siblings and fix those too"))
        "asking again for the enumeration that just failed buys another round of it")
    (is (str/includes? out "say THAT in your final")
        "a class with no common source is answered by saying so, not by a third sweep")))

(deftest one-earlier-sweep-is-named-in-the-singular
  ;; The count is the whole force of the sentence, so a plural over one round
  ;; reads as sloppiness and undercuts it.
  (let [out (prompts/fix-prompt
             {:findings [{:priority 1 :title "t" :body "b" :sweep true
                          :swept-before [2]}]})]
    (is (str/includes? out "already swept in round 2,"))
    (is (not (str/includes? out "swept in rounds")))))

(deftest a-first-sweep-is-still-asked-to-enumerate
  ;; Finding the siblings IS the remedy until it has been tried and failed.
  (let [out (prompts/fix-prompt
             {:findings [{:priority 1 :title "t" :body "b" :sweep true}]})]
    (is (str/includes? out "find its siblings and fix those too"))
    (is (not (str/includes? out "SWEEP AGAIN")))))

(deftest a-fixer-is-told-what-the-warden-settled-on-its-own-layer
  ;; The prompt rendered the findings dispositioned :fix and nothing else, so a
  ;; decision to LIVE with a defect reached the fixer as silence. One handed a
  ;; single finding rewrote two of the three sites named by a deviation the same
  ;; round had kept, and the run still reported the deviation as standing.
  (let [out (prompts/fix-prompt
             {:findings [{:priority 1 :title "t" :body "b"}]
              :settled [{:id "0f2ea8d2" :title "changelog contradicts the branch"
                         :disposition :deviation
                         :of "recording.clj documents the pre-branch modes"
                         :file "recording.clj" :line-start 1 :line-end 24}]})]
    (is (str/includes? out "ALREADY DECIDED"))
    (is (str/includes? out "0f2ea8d2 changelog contradicts the branch → deviation"))
    (is (str/includes? out "recording.clj documents the pre-branch modes")
        "a deviation without its claim is a title the fixer cannot weigh against the code")))

(deftest a-settled-decision-is-located-in-the-tree
  ;; The one thing this block carries that the warden's does not. The warden has
  ;; the finding in front of it; the fixer has the repository, and a decision it
  ;; cannot find is one it can only honour by accident.
  (let [out (prompts/fix-prompt
             {:findings [{:priority 1 :title "t" :body "b"}]
              :settled [{:id "aa11" :title "no backoff on reconnect"
                         :disposition :declined
                         :because "the caller owns the retry budget"
                         :file "transport.clj" :line-start 410 :line-end 418}]})]
    (is (str/includes? out "transport.clj:410-418"))
    (is (str/includes? out "the caller owns the retry budget")
        "a decision a fixer is asked to honour has to come with its grounds")))

(deftest a-fixer-that-disagrees-with-a-decision-is-sent-to-the-round-not-the-code
  ;; Editing a settled finding does not retract the ruling, so the branch and the
  ;; run's published account of it come apart with nothing to notice. The fixer
  ;; in the incident DID recognise the edit as out of its brief and made it
  ;; anyway, because the prompt offered it nowhere else to put the objection.
  (let [out (prompts/fix-prompt
             {:findings [{:priority 1 :title "t" :body "b"}]
              :settled [{:id "aa11" :title "x" :disposition :declined
                         :because "shipping it"}]})]
    (is (str/includes? out "leave these exactly as they are"))
    (is (str/includes? out "does not retract the ruling")
        "why an edit is worse than useless here is the whole argument for not making one")
    (is (str/includes? out "name the id")
        "disagreement has to have a destination or it becomes an edit")))

(deftest the-deviation-clause-appears-only-where-a-deviation-does
  ;; A block that explains a word absent from its own list teaches the reader to
  ;; skim the rest of it.
  (let [with (prompts/fix-prompt
              {:findings [{:priority 1 :title "t" :body "b"}]
               :settled [{:id "a" :title "x" :disposition :deviation :of "the claim"}]})
        without (prompts/fix-prompt
                 {:findings [{:priority 1 :title "t" :body "b"}]
                  :settled [{:id "a" :title "x" :disposition :declined
                             :because "shipping it"}]})]
    (is (str/includes? with "BOTH are being kept"))
    (is (not (str/includes? without "BOTH are being kept")))))

(deftest a-fixer-with-nothing-settled-against-its-layer-gets-no-empty-block
  ;; A heading with nothing under it reads as a bound that was checked and found
  ;; empty, which is a different claim from no bound at all.
  (let [out (prompts/fix-prompt {:findings [{:priority 1 :title "t" :body "b"}]})]
    (is (not (str/includes? out "ALREADY DECIDED")))))

(deftest minimal-does-not-license-leaving-an-artifact-contradicting-itself
  ;; For a declarative artifact the smallest edit that resolves a finding is
  ;; often the one that breaks it: one round declared a field required on a
  ;; schema without supplying it at the outcomes, and the next round existed to
  ;; report the spec as self-contradictory.
  (let [out (prompts/fix-prompt {:findings [{:priority 1 :title "t" :body "b"}]})]
    (is (str/includes? out "MINIMAL bounds how much you change, not what you may leave broken")
        "minimality is about the size of the edit, not about what may be left broken")
    (is (str/includes? out "leave it self-consistent"))
    (is (str/includes? out "say so in your final\nmessage")
        "a consistency repair too big to make must be reported, not silently skipped")))

(deftest the-composition-pass-is-told-what-it-already-reported
  ;; It is the only reader that can see across layers and it starts cold every
  ;; round, so it returns the same seam rather than looking further.
  (let [layers [{:label "core" :index 1 :from "a" :tip "b"}
                {:label "wiring" :index 2 :from "b" :tip "c"}]
        cold (prompts/composition-block {:layers layers})
        warm (prompts/composition-block
              {:layers layers
               :already-reported [{:round 1 :title "the migration and its reader split"
                                   :kind "misplaced-cut"}]})]
    (is (not (str/includes? cold "WHAT YOU ALREADY REPORTED")))
    (is (str/includes? warm "WHAT YOU ALREADY REPORTED IN THIS RUN"))
    (is (str/includes? warm "the migration and its reader split"))
    (is (str/includes? warm "round 1"))
    (is (str/includes? warm "spend this round somewhere you have not looked"))))

(deftest the-reviewer-is-told-what-a-fixer-already-landed-here
  ;; Nobody in the loop was asked whether a fix closed what it was handed: the
  ;; reviewer that reads those lines next round is shown a diff and no history,
  ;; so a partially-completed sweep came back at the same window as a fresh
  ;; finding two rounds running.
  (let [out (prompts/prior-fixes-block
             [{:round 1 :commit "4d52d218"
               :findings [{:title "reject a bad enum before the blob insert" :sweep true}]
               :account "fixed the enum check; the V243 cross-field rule is untouched"}])]
    (is (str/includes? out "A FIXER ALREADY WORKED ON WHAT YOU ARE REVIEWING"))
    (is (str/includes? out "4d52d218") "the commit, so the claim can be checked against it")
    (is (str/includes? out "reject a bad enum before the blob insert"))
    (is (str/includes? out "[SWEEP]")
        "a sweep is where a partial fix costs most — its siblings are at these lines")
    (is (str/includes? out "the V243 cross-field rule is untouched")
        "the fixer's own account is what tells the reviewer where to look")
    (is (str/includes? out "CLAIM about the code, not a record")
        "a reviewer that believes the account has been talked out of the diff")))

(deftest a-repair-the-stack-refused-says-the-code-is-unchanged
  ;; A rolled-back repair leaves the range byte-identical, so the reviewer that
  ;; reads it next has no diff to notice and every finding under it is still
  ;; true. One round-2 reviewer re-read exactly such a patch with nothing to
  ;; show it and returned `correct` on a P2 the round before had ruled `fix`.
  (let [out (prompts/prior-fixes-block
             [{:round 1 :refused ["lktsqrrn" "llqpmolo"]
               :findings [{:title "add the digest to the owning contracts"}]
               :account "added the digest to all five contracts"}])]
    (is (str/includes? out "REFUSED")
        "the entry has to be readable as an attempt rather than as a repair")
    (is (str/includes? out "lktsqrrn")
        "what it conflicted with, because that is where the layer order is wrong")
    (is (str/includes? out "NOT in the range below")
        "a reviewer told a repair happened and not told it was undone believes
         the finding is closed — the failure this block exists to stop")
    (is (str/includes? out "added the digest to all five contracts")
        "the fixer's reading of an edit nobody else can now see")
    (is (str/includes? out "put back")
        "stated in the past, about an edit that is gone: present tense would
         make the account a claim about the code in front of the reviewer")
    (is (not (str/includes? out ", landed "))
        "nothing landed, and a change id beside a landed one reads as one")))

(deftest a-target-no-fixer-touched-is-told-nothing
  ;; nil, not an empty heading: a block saying a fixer worked here and naming
  ;; nothing reads as a repair the reviewer failed to be shown.
  (is (nil? (prompts/prior-fixes-block []))))

(deftest a-long-fixer-account-keeps-both-of-its-ends
  ;; An account opens with what the fixer changed and closes with what it could
  ;; not — the sibling in another layer it was ordered to name rather than
  ;; touch. Cutting the tail is the one cut that costs a reader anything, and it
  ;; is the cut a length cap makes by default: one account lost its whole sweep
  ;; section, and both siblings named in it, to exactly this.
  (let [out (prompts/prior-fixes-block
             [{:round 1 :commit "c1" :findings [{:title "t"}]
               :account (str "landed the enum guard"
                             (apply str (repeat 4000 "x"))
                             "siblings I did not touch: deploy.yml:211")}])]
    (is (str/includes? out "landed the enum guard")
        "what the fixer changed is the head of every account")
    (is (str/includes? out "siblings I did not touch: deploy.yml:211")
        "and what it could not reach is the tail — the half a cap silently ate")
    (is (str/includes? out "chars elided")
        "a reader has to be able to tell a whole account from a cut one, and that
         the two halves it is holding are not adjacent")
    (is (< (count out) 2500)
        "the prompt is otherwise sized by how talkative one agent was")))

(deftest an-accounts-budget-is-bought-by-the-findings-it-covers
  ;; An account's length is set by how much the fixer was asked to do. Held flat
  ;; at one finding's worth, every account of one run — seven, 2001 to 3251
  ;; chars — was over the cap, including the three-finding one.
  (let [account (str "opening" (apply str (repeat 2400 "x")) "closing")
        one (prompts/prior-fixes-block
             [{:round 1 :commit "c1" :findings [{:title "t"}] :account account}])
        three (prompts/prior-fixes-block
               [{:round 1 :commit "c1" :account account
                 :findings [{:title "a"} {:title "b"} {:title "c"}]}])]
    (is (str/includes? one "chars elided")
        "one finding buys one finding's worth")
    (is (not (str/includes? three "chars elided"))
        "three repairs to describe is three times the account to describe them in")
    (is (str/includes? three account)
        "and it arrives whole, which is what the next reviewer has to check")))

(deftest a-multi-finding-account-keeps-the-close-of-every-repair
  ;; An account closes each repair where that repair's treatment ends, so on a
  ;; two-finding account the FIRST close sits in the middle of the text — which
  ;; is exactly where a single cut through the whole account lands. One
  ;; 4058-char account at budget 2400 lost the entire treatment of its second
  ;; finding to one 1658-char elision, including the residual the repair had
  ;; deliberately left; the post-loop verdict then found that residual breaks a
  ;; design invariant no reviewer had ever been shown it.
  (let [section (fn [head body close]
                  (str head "\n" body " " (apply str (repeat 1500 "x"))
                       "\n" close))
        account (str "two repairs landed.\n\n"
                     (section "## 1. Marker-bounded repair"
                              "took the marker boundary;"
                              "Residual: the alias map is still read once.")
                     "\n\n"
                     (section "## 2. Nested defmethods"
                              "walked every container;"
                              (str "Residual: a sibling form written after a "
                                   "nested method is captured as its body."))
                     "\n\nkondo: clean.")
        out (prompts/prior-fixes-block
             [{:round 1 :commit "c1" :account account
               :findings [{:title "a"} {:title "b"}]}])]
    (is (str/includes? out "the alias map is still read once")
        "the first repair's residual closes its section, not the account, so a
         cut through the whole text is aimed straight at it")
    (is (str/includes? out "a sibling form written after a nested method")
        "and the second repair's residual is what the next round needs — the one
         that went unraised for a round and came back as a broken invariant")
    (is (and (str/includes? out "took the marker boundary")
             (str/includes? out "walked every container"))
        "each repair still opens with what the fixer changed there")
    (is (str/includes? out "kondo: clean.")
        "the account's own tail survives as it did before")
    (is (< (count out) 3600)
        "cutting per section spends the same budget, not a budget per section")))

(deftest an-account-of-many-sections-is-cut-into-readable-ones
  ;; A section is shown as a head, an elision marker and a tail. Split far
  ;; enough and every share is too small to hold a sentence while the markers
  ;; themselves — which are not account text — outgrow what they separate.
  (let [account (str/join "\n\n"
                          (for [i (range 100)]
                            (str i ". item " i " " (apply str (repeat 90 "z")))))
        out (prompts/prior-fixes-block
             [{:round 1 :commit "c1" :findings [{:title "t"}] :account account}])]
    (is (< (count out) 2500)
        "a hundred sections at one finding's budget is still one finding's worth
         of prompt, or the cap has stopped bounding anything")
    (is (<= (count (re-seq #"chars elided" out)) 4)
        "adjacent sections past what the budget can serve are grouped and cut as
         one: a shorter account read in whole treatments beats a longer one read
         in fragments")))

(deftest the-warden-is-shown-every-fixers-account-and-the-layer-it-landed-on
  ;; A fixer under a sweep is ordered to name any sibling it may not touch, and
  ;; that text is rendered to the next reviewer OF ITS OWN LAYER — the one
  ;; reader it is by construction not about. One run ended `clean` with two
  ;; out-of-layer siblings named in an account, in a file another layer owned,
  ;; read by nobody.
  (let [out (prompts/warden-prompt
             {:findings findings :history []
              :fixer-accounts
              [{:layer "github-outcomes" :round 1 :commit "44249c19"
                :findings [{:title "the hotfix path skips the recorder" :sweep true}]
                :account (str "closed all three call sites. Siblings I did not "
                              "touch — outside this change: "
                              ".github/workflows/deploy_hotfix.yml:211 and :565.")}]})]
    (is (str/includes? out "WHAT THE FIXERS SAID ABOUT THE REPAIRS THEY LANDED"))
    (is (str/includes? out "github-outcomes")
        "the layer is a field the warden reads, because it is being asked to place
         the account's contents against a DIFFERENT one")
    (is (str/includes? out "deploy_hotfix.yml:211")
        "the named sibling is the whole reason this reader gets the account")
    (is (str/includes? out "[SWEEP]")
        "a sweep is where an unreachable sibling is expected, not incidental")
    (is (str/includes? out "PROMOTE it")
        "a sibling the warden can place becomes a finding of the round rather
         than a sentence about one")
    (is (str/includes? out "\"promote\"")
        "and the field it goes in is offered, because there is an account to
         promote out of")
    (is (str/includes? out "What you cannot place goes in `standing`")
        "what no layer could be found for is still reported — in the slot that
         reaches the ledger, rather than in a reason that reaches the run dir")))

(deftest the-warden-is-asked-what-it-is-leaving-standing-on-every-answer
  ;; Unconditional, unlike `promote` beside it: a promotion needs an account to
  ;; promote out of, and what the branch has open is something a round with no
  ;; fixes and no accounts holds just as well. Which round turns out to be the
  ;; last one is not knowable while it is running, so asking only on a `stop`
  ;; would mean asking the one warden that has already decided to say nothing.
  (let [out (prompts/warden-prompt {:findings findings :history []})]
    (is (str/includes? out "\"standing\""))
    (is (str/includes? out "STANDING — what you know is open and are handing to nobody"))
    (is (str/includes? out "carried onto the\nworkstream's ledger")
        "the reason it is not the `reason` is the reason it exists")
    (is (not (str/includes? out "\"promote\""))
        "and this run had no account to promote out of, which is what makes
         the pair's difference visible")))

(deftest a-run-that-has-landed-no-fix-tells-the-warden-nothing-about-accounts
  ;; An empty heading reads as repairs the warden failed to be shown.
  (is (not (str/includes? (prompts/warden-prompt {:findings findings :history []})
                          "WHAT THE FIXERS SAID"))))

(deftest the-warden-is-told-which-findings-a-fixer-refused-and-why
  ;; A fixer that changes nothing leaves the finding at :fix, so without this
  ;; the next round hands the same finding to a fresh session and the warden
  ;; that could settle it never reads the argument the last fixer built.
  (let [out (prompts/warden-prompt
             {:findings findings :history []
              :fixer-declines [{:layer "teacher-diary-section" :since 1
                                :findings [{:id "5cb720f4" :title "$ is not bound per element"}]
                                :reason "Datastar 1.0.2 ships one global signal root"}]})]
    (is (str/includes? out "A FIXER WAS HANDED THESE AND CHANGED NOTHING"))
    (is (str/includes? out "teacher-diary-section"))
    (is (str/includes? out "5cb720f4"))
    (is (str/includes? out "Datastar 1.0.2 ships one global signal root"))
    (is (str/includes? out "argument, not a ruling")
        "the warden's own `declined` is a decision; a fixer refusing has decided nothing")))

(deftest a-round-no-fixer-refused-anything-in-says-so-by-silence
  (let [out (prompts/warden-prompt {:findings findings :history []})]
    (is (not (str/includes? out "A FIXER WAS HANDED THESE")))))

(deftest the-reviewer-is-told-its-jj-commands-are-the-whole-set
  ;; A skill whose description demands first activation on anything VCS-shaped
  ;; gets opened before any code is read, and this reviewer never writes a
  ;; revision — so the prohibition has to name it, and has to be paid for by the
  ;; prompt still carrying the commands it calls complete.
  (let [out prompts/review-prompt]
    (is (str/includes? out "COMPLETE set"))
    (is (str/includes? out "jujutsu")
        "a prohibition that does not name the skill does not reach the one that gets opened")
    (is (str/includes? out "jj --ignore-working-copy diff")
        "calling a set complete is a lie the moment the prompt stops carrying it")))

(deftest a-standing-verdicts-item-is-put-to-the-reviewer-as-a-question
  ;; The verdict read a different tree than the one the reviewer is shown, so
  ;; whether it is still true is what is being asked. A reviewer that reports it
  ;; back on the strength of the text has laundered an old claim into a fresh
  ;; finding, and the round after it inherits a defect nobody looked at.
  (let [out (prompts/standing-needs-block
             {:round 4 :verdict :strained
              :needs "close-turn!'s when-open? still tests (empty? open)"})]
    (is (str/includes? out "close-turn!'s when-open? still tests (empty? open)"))
    (is (str/includes? out "round 4"))
    (is (str/includes? out "strained")
        "one round's judgment, not a standing truth — a reviewer told something
         authoritative stops reading the diff")
    (is (str/includes? out "report it as a finding")
        "a finding is the only currency a fixer can be handed")
    (is (str/includes? out "say nothing")
        "out of range is the commonest answer and it is a silence, not a miss")))

(deftest a-run-with-no-standing-item-tells-the-reviewer-nothing
  (is (nil? (prompts/standing-needs-block nil)))
  (is (nil? (prompts/standing-needs-block {:round 1 :verdict :sound}))
      "saying \"nothing outstanding\" invites a reviewer to look for one"))

(deftest an-earlier-runs-unmet-obligation-is-put-to-the-layers-reviewer
  ;; Nothing else in a run knows the defect was ever ruled on, so a silence
  ;; here is read as the defect being gone.
  (let [out (prompts/prior-open-block
             [{:id "cc56069f" :title "Preserve tagged-literal identity when reading extents"
               :where "extraction/core.clj:122" :disposition :fix
               :because "the reader drops the tag"}])]
    (is (str/includes? out "Preserve tagged-literal identity when reading extents"))
    (is (str/includes? out "extraction/core.clj:122")
        "a reviewer sent to check a claim needs the site the claim was made at")
    (is (str/includes? out "ruled fix"))
    (is (str/includes? out "the reader drops the tag")
        "the warden's reason is what says which defect this is, not just where")
    (is (str/includes? out "still true is a question, not a fact")
        "that run read a different tree; a reviewer that copies it back
         unverified launders a stale claim into a fresh finding")
    (is (str/includes? out "report it as a finding")
        "a finding is the only currency a fixer can be handed")))

(deftest a-run-inheriting-nothing-tells-the-reviewer-nothing
  (is (nil? (prompts/prior-open-block nil)))
  (is (nil? (prompts/prior-open-block []))
      "an empty list rendered as a heading invites a reviewer to look for one"))

(deftest layer-brief-block-shows-a-settled-deviation-as-settled
  ;; The reviewer is told to attack the claim, so a claim already known to be too
  ;; strong is what it is likeliest to find — and a deviation is the ruling that
  ;; this one will not be repaired. Withheld, the round rediscovers it.
  (let [b (prompts/layer-brief-block
           {:subject "feat(x): y" :claims "the rename is uniform"
            :deviations ["three sites got special handling"]})]
    (is (str/includes? b "ALREADY KNOWN NOT TO HOLD"))
    (is (str/includes? b "three sites got special handling"))
    (is (str/includes? b "do not report them again"))))

(deftest layer-brief-block-with-no-deviation-says-nothing-about-one
  (let [b (prompts/layer-brief-block {:subject "s" :claims "c"})]
    (is (not (str/includes? b "ALREADY KNOWN NOT TO HOLD")))))

(deftest manifest-block-carries-the-mandate-over-its-own-list
  (let [b (prompts/manifest-block "src/a.clj\nsrc/b.clj")]
    (is (str/includes? b "Changed files:"))
    (is (str/includes? b "src/a.clj"))
    ;; The mandate is scoped to the list it is a mandate over. In the shared
    ;; prompt it reached the composition pass, which gets no list.
    (is (str/includes? b "MUST actually pull each changed file"))))
