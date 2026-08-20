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
    (is (str/includes? out "CONTRADICT A NAMED INVARIANT"))
    (is (str/includes? out "Do not escalate because findings merely feel fundamental"))))

(deftest arbiter-prompt-without-a-design-record-forbids-escalation
  (let [out (prompts/arbiter-prompt {:findings findings :history [] :design nil})]
    (is (str/includes? out "No design record on this workstream"))
    (is (str/includes? out "do NOT escalate"))
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

(deftest arbiter-prompt-tags-findings-with-their-layer
  (let [out (prompts/arbiter-prompt
             {:findings [{:priority 1 :title "t" :body "b" :reach :structural}
                         {:priority 2 :title "u" :body "c"}]
              :history [] :design design})]
    (is (str/includes? out "0: [P1/structural] t"))
    (is (str/includes? out "1: [P2/unclear] u") "an unlabelled finding is unclear, not local")))

(deftest arbiter-is-told-not-to-patch-a-structural-finding-away
  (let [out (prompts/arbiter-prompt {:findings findings :history [] :design design})]
    (is (str/includes? out "Leave such findings\nout of fix_findings and escalate instead"))
    (is (str/includes? out "patching a design\nquestion makes it disappear without anyone deciding it"))))
