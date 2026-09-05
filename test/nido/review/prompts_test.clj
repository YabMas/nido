(ns nido.review.prompts-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
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
  ;; down. Collapsing them would send every seam finding to a fixer on exactly
  ;; the workstreams with the least written down about their shape.
  (let [out (prompts/warden-prompt {:findings findings :history [] :design nil})]
    (is (str/includes? out "The RECUT kinds above are still `recut`"))
    (is (str/includes? out "misplaced-seam \u2192 fold")
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
                          :kind :misplaced-seam :layers ["series" "banner"]}]
              :history [] :design design})]
    (is (str/includes? out "reported-by stack · misplaced-seam · across series + banner"))))

(deftest warden-prompt-recuts-a-bad-cut-instead-of-handing-it-to-a-fixer
  ;; A fixer can only patch one side of a seam, and a patched seam converges —
  ;; so the round reports success and the wrong cut ships.
  (let [out (prompts/warden-prompt {:findings findings :history [] :design design})]
    (is (str/includes? out "the remedy is the SHAPE of the stack"))
    (is (str/includes? out "makes the bad seam permanent"))
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
    (is (str/includes? out "does NOT need a design record"))
    (is (str/includes? out "Park on RECURRENCE still applies"))))

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
    (doseq [k (filter :remedy prompts/composition-kinds)]
      (is (str/includes? out (str "- " (:kind k) " \u2192 " (name (:remedy k))))
          (str (:kind k) " is listed as a recut with its move")))))

(deftest the-warden-sees-the-designs-claimed-decomposition
  ;; Without it the warden cannot tell that the stack has three layers where the
  ;; design named two — a finding about the cut nothing else in the loop reaches.
  (let [out (prompts/warden-prompt
             {:findings findings :history [] :toc a-toc
              :design (assoc design :layers [{:claim "the ledger holds a decision"
                                              :mode :structural}])})]
    (is (str/includes? out "CLAIMED DECOMPOSITION"))
    (is (str/includes? out "the ledger holds a decision"))
    (is (str/includes? out "that is a finding about the CUT"))))

(deftest the-fixer-is-told-what-the-warden-wrote-for-it
  ;; :because is addressed to this reader — why the finding is real, or which
  ;; layer it was moved to and why. It was produced every round and rendered to
  ;; nobody.
  (let [out (prompts/fix-prompt
             {:findings [{:priority 1 :title "t" :body "b" :file "a.clj"
                          :line-start 1 :line-end 2
                          :kind "misplaced-seam" :across ["core" "wiring"]
                          :because "moved down: core is where the guarantee is dropped"}]})]
    (is (str/includes? out "moved down: core is where the guarantee is dropped"))
    (is (str/includes? out "misplaced-seam"))
    (is (str/includes? out "spans core, wiring"))))

(deftest the-fixer-is-bounded-by-the-layer-it-is-working-on
  ;; "Make the MINIMAL change" is the only guidance a fixer had, and for a defect
  ;; spanning a seam the minimal change is a patch on whichever side it was
  ;; reported from.
  (let [out (prompts/fix-prompt
             {:findings [{:priority 1 :title "t" :body "b"}]
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
    (is (str/includes? out "read every file this change touched"))
    (is (not (str/includes? out "audit this layer"))
        "a layer is its diff, so bounding the search to it excludes pre-existing siblings")
    (is (str/includes? out "NAMED in your final message")
        "a sibling out of this fixer's reach reaches the next round only if it is said")))

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
                                   :kind "misplaced-seam"}]})]
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

(deftest a-target-no-fixer-touched-is-told-nothing
  ;; nil, not an empty heading: a block saying a fixer worked here and naming
  ;; nothing reads as a repair the reviewer failed to be shown.
  (is (nil? (prompts/prior-fixes-block []))))

(deftest a-long-fixer-account-is-truncated-rather-than-inlined-whole
  ;; The prompt is otherwise sized by how talkative one agent was. The whole
  ;; text stays on the fix row in report.json.
  (let [out (prompts/prior-fixes-block
             [{:round 1 :commit "c1" :findings [{:title "t"}]
               :account (apply str (repeat 4000 "x"))}])]
    (is (str/includes? out "…[truncated]"))
    (is (< (count out) 2500))))

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
