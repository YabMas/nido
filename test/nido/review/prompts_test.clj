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
    (is (str/includes? out "do NOT park anything"))
    (is (not (str/includes? out "Invariants:")))))

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

(deftest warden-prompt-is-bounded-and-never-closes-without-an-authority
  (let [s (prompts/warden-prompt {:layer "drop-legacy" :brief a-brief
                                  :findings [{:id "aa11" :title "t" :body "b"}]
                                  :toc [{:label "shape" :claim "c"}]})]
    (is (str/includes? s "WARDEN of ONE layer"))
    (is (str/includes? s "Never close a finding without an authority"))
    (is (str/includes? s "whenever deciding would\nneed a view you do not have")
        "escalate means it cannot see far enough, not that the design is wrong")
    (is (str/includes? s "aa11"))))

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
