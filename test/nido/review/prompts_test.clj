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

(def ^:private a-brief
  {:subject "refactor(pay): fold the rounding into the aggregate"
   :mode :mechanical
   :claims "the rename is uniform across all 40 call sites."
   :verify "confirm no call site got special handling."
   :lane "lane-malli"
   :out-of-scope "the new validation logic — that lands in the layer above."})

(deftest arbiter-prompt-inlines-the-design-record
  (let [out (prompts/arbiter-prompt {:findings findings :history [] :design design})]
    (is (str/includes? out "one rounding boundary at the order aggregate"))
    (is (str/includes? out "a total is rounded exactly once"))
    (is (str/includes? out "challenges — money math needs an accumulator"))
    (is (not (str/includes? out "Design doc")) "no path-handoff, no glob'd spec")))

(deftest arbiter-prompt-carries-rejected-alternatives-as-answered
  (let [out (prompts/arbiter-prompt {:findings findings :history [] :design design})]
    (is (str/includes? out "round at render time"))
    (is (str/includes? out "rejected because money math in the view"))
    (is (str/includes? out "ANSWERED, not new")
        "a finding re-proposing a rejected alternative is answered, not a new problem")))

(deftest arbiter-prompt-ties-escalate-to-a-named-invariant
  (let [out (prompts/arbiter-prompt {:findings findings :history [] :design design})]
    (is (str/includes? out "CONTRADICTS A NAMED INVARIANT"))
    (is (str/includes? out "Do not escalate because a finding merely feels fundamental"))))

(deftest arbiter-prompt-without-a-design-record-forbids-escalation
  (let [out (prompts/arbiter-prompt {:findings findings :history [] :design nil})]
    (is (str/includes? out "No design record on this workstream"))
    (is (str/includes? out "do NOT park anything for contradicting an invariant"))
    (is (not (str/includes? out "Invariants:")))))

(deftest arbiter-prompt-without-a-design-record-still-parks-a-bad-cut
  ;; The two entrances to park are independent. One turns on a named invariant
  ;; and is unavailable with no design record; the other says the loop has no
  ;; move for the finding, which is true whether or not anyone wrote a design
  ;; down. Collapsing them would send every seam finding to a fixer on exactly
  ;; the workstreams with the least written down about their shape.
  (let [out (prompts/arbiter-prompt {:findings findings :history [] :design nil})]
    (is (str/includes? out "misplaced-seam or order-dependence finding still parks"))
    (is (str/includes? out "does not turn on the design record"))))

(deftest arbiter-prompt-marks-the-stance-as-framing-not-checklist
  (let [out (prompts/arbiter-prompt {:findings findings :history [] :design design
                                   :stance "the shape of the data is the design"})]
    (is (str/includes? out "the shape of the data is the design"))
    (is (str/includes? out "NOT a checklist"))
    (is (str/includes? out "never cite it against a specific finding"))))

(deftest arbiter-prompt-omits-the-stance-block-when-absent
  (let [out (prompts/arbiter-prompt {:findings findings :history [] :design design})]
    (is (not (str/includes? out "PROJECT STANCE")))))

(deftest arbiter-prompt-names-findings-by-id-and-shows-who-reported-them
  (let [out (prompts/arbiter-prompt
             {:findings [{:id "aa11" :priority 1 :title "t" :body "b"
                          :reach :structural :from-layer "drop-legacy"}
                         {:id "bb22" :priority 2 :title "u" :body "c"}]
              :history [] :design design})]
    (is (str/includes? out "id aa11  [P1/structural] reported-by drop-legacy"))
    (is (str/includes? out "id bb22  [P2/unclear]")
        "an unlabelled finding is unclear, not local")))

(deftest arbiter-prompt-requires-an-authority-for-every-non-fix
  ;; A closed with no authority is a shrug, and is how a review quietly stops
  ;; reviewing.
  (let [out (prompts/arbiter-prompt {:findings findings :history [] :design design})]
    (is (str/includes? out "Nothing is dropped"))
    (is (str/includes? out "it is a shrug"))))

(deftest arbiter-prompt-assigns-a-composition-finding-to-the-highest-layer
  (let [out (prompts/arbiter-prompt {:findings findings :history [] :design design})]
    (is (str/includes? out "assign it to the HIGHEST layer involved"))))

(deftest toc-block-is-a-map-of-claims-and-files-not-diffs
  (let [s (prompts/toc-block [{:label "a" :claim "the rename is uniform"
                               :files ["src/x.clj"]}])]
    (is (str/includes? s "the rename is uniform"))
    (is (str/includes? s "src/x.clj"))
    (is (str/includes? s "map only")))
  (is (nil? (prompts/toc-block []))))

(deftest arbiter-sees-each-layers-out-of-scope
  ;; It is told it may close on the authority "out-of-scope"; without the field
  ;; that is a word it can cite but never read.
  (let [out (prompts/arbiter-prompt
             {:findings findings :history [] :design design
              :toc [{:label "shape" :claim "c"
                     :out-of-scope "the new validation logic"}]})]
    (is (str/includes? out "out of scope: the new validation logic"))))

(deftest arbiter-gets-back-what-it-already-closed-grouped-by-layer
  ;; The reviewer starts fresh every round and re-reports closed findings. These
  ;; are the arbiter's OWN prior closes, so they are a default it may reverse
  ;; rather than a ruling to defer to.
  (let [out (prompts/arbiter-prompt
             {:findings findings :history [] :design design
              :answered [{:label "shape"
                          :answered [{:id "aa11" :title "t"
                                      :authority "out-of-scope"
                                      :because "the layer below owns it"}]}]})]
    (is (str/includes? out "ALREADY CLOSED IN AN EARLIER ROUND"))
    (is (str/includes? out "shape"))
    (is (str/includes? out "aa11"))
    (is (str/includes? out "out-of-scope"))
    (is (str/includes? out "say why that answer no longer"))))

(deftest arbiter-omits-the-answered-block-when-nothing-was-closed
  (let [out (prompts/arbiter-prompt {:findings findings :history [] :design design})]
    (is (not (str/includes? out "ALREADY CLOSED IN AN EARLIER ROUND")))))

(deftest arbiter-is-told-not-to-patch-a-structural-finding-away
  (let [out (prompts/arbiter-prompt {:findings findings :history [] :design design})]
    (is (str/includes? out "patching a design\nquestion makes it disappear without anyone deciding it"))))


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

(deftest arbiter-prompt-shows-a-composition-findings-kind-and-span
  (let [out (prompts/arbiter-prompt
             {:findings [{:id "aa11" :priority 2 :title "t" :body "b"
                          :reach :structural :from-layer "stack"
                          :kind :misplaced-seam :layers ["series" "banner"]}]
              :history [] :design design})]
    (is (str/includes? out "reported-by stack · misplaced-seam · across series + banner"))))

(deftest arbiter-prompt-parks-a-bad-cut-instead-of-handing-it-to-a-fixer
  ;; A fixer can only patch one side of a seam, and a patched seam converges —
  ;; so the round reports success and the wrong cut ships.
  (let [out (prompts/arbiter-prompt {:findings findings :history [] :design design})]
    (is (str/includes? out "cannot re-cut a stack"))
    (is (str/includes? out "makes the bad seam permanent"))))

(deftest arbiter-prompt-attributes-a-composition-finding-by-what-it-spans
  ;; The highest-layer rule itself is guarded above; this is the new half — the
  ;; arbiter is no longer guessing the span off file lists, the pass reports it.
  (let [out (prompts/arbiter-prompt {:findings findings :history [] :design design})]
    (is (str/includes? out "names the ones it spans after `across`"))
    (is (str/includes? out "spans only ONE layer")
        "a stack finding naming one layer is that layer's own, reported twice")))
