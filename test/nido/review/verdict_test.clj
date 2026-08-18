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

(deftest verdict-only-runs-when-there-were-findings-to-judge
  (is (nido-review/verdict-worth-running? :converged {:findings [{:title "x"}]}))
  (is (nido-review/verdict-worth-running? :max-iters {:history [{:iter 1}]}))
  (is (not (nido-review/verdict-worth-running? :clean {:findings [] :history []}))
      "a clean review produced no evidence either way")
  (is (not (nido-review/verdict-worth-running? :review-failed {:findings [{:title "x"}]})))
  (is (not (nido-review/verdict-worth-running? :dry-run {:findings [{:title "x"}]}))))
