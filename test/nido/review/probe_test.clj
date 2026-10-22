(ns nido.review.probe-test
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [babashka.fs]
   [clojure.test :refer [deftest is testing]]
   [nido.review.probe :as probe]))

(def ^:private round
  {:prompt (str "You are judging.\n"
                "CLAIMS — x:\n  [a] one\n"
                "PROJECT STANCE — framing only:\n# Domain Modeling\nthe essay\n"
                "DERIVE THESE FOUR. Each is decidable:\n"
                "  goal-served       — only the goal?\n\n"
                "Then recommend:\n  proceed  — nothing blocks it.\n")
   :schema (json/generate-string
            {:properties {:checks   {:items {:properties {:check {:enum ["goal_served"]}}}}
                          :findings {:items {:properties {:check {:enum ["goal_served" ""]}}}}}})})

(def ^:private baseline-round
  {:prompt (str "CLAIMS — x:\n  [a] one\n"
                "PROJECT STANCE — the yardstick, framing\nonly.\n\n# Domain Modeling\nthe essay\n"
                "\n---\nBefore you start: the subject is the DECOMPOSITION.\n")
   :schema "{}"})

(deftest a-control-is-the-recorded-prompt-unchanged
  (is (= round (probe/apply-variant round {:edits []}))))

(deftest dropping-the-stance-keeps-the-level-reminder
  (let [p (:prompt (probe/apply-variant baseline-round {:edits [{:drop "PROJECT STANCE"}]}))]
    (is (not (str/includes? p "the essay")))
    (is (str/includes? p "Before you start: the subject is the DECOMPOSITION")
        "the reminder that primes the level is not the stance, and a variant about the stance keeps it"))
  (is (not (str/includes? (:prompt (probe/apply-variant round {:edits [{:drop "PROJECT STANCE"}]}))
                          "PROJECT STANCE"))))

(deftest a-short-lens-replaces-the-essay-at-the-top
  (let [p (:prompt (probe/apply-variant round {:edits [{:drop "PROJECT STANCE"}
                                                       {:prepend "PROJECT STANCE — lens:\nthe data is the design"}]}))]
    (is (str/starts-with? p "You are judging.\nPROJECT STANCE — lens:"))
    (is (not (str/includes? p "the essay")))))

(deftest a-new-check-is-asked-and-offered
  (let [{:keys [prompt schema]}
        (probe/apply-variant round {:edits [{:substitute "DERIVE THESE FOUR." :text "DERIVE THESE."}
                                            {:insert-before "\nThen recommend:" :text "  data-shaped       — data?\n"}
                                            {:add-check "data_shaped"}]})
        s (json/parse-string schema true)]
    (is (str/includes? prompt "  goal-served       — only the goal?\n  data-shaped       — data?\n\nThen recommend:"))
    (is (= ["goal_served" "data_shaped"] (get-in s [:properties :checks :items :properties :check :enum])))
    (is (= ["goal_served" "data_shaped" ""] (get-in s [:properties :findings :items :properties :check :enum]))
        "a check-less finding stays admitted, last")))

(deftest an-edit-that-cannot-be-made-fails-the-variant
  (testing "a variant judging the control's prompt would report an effect it never had"
    (is (thrown? Exception (probe/apply-variant round {:edits [{:drop "NO SUCH SECTION"}]})))
    (is (thrown? Exception (probe/apply-variant round {:edits [{:insert-before "nowhere" :text "x"}]})))))

(deftest an-answer-is-read-for-what-the-probe-compares
  (is (= {:recommend "amend" :checks {"goal_served" "broken"}
          :findings [{:id 0 :check "goal_served" :claim-id "a" :claim "too much"}]
          :confirmed 1 :unchecked 0 :step-back [{:area "m/op" :step "restate"}]}
         (probe/metrics (json/generate-string
                         {:recommend "amend" :checks [{:check "goal_served" :status "broken" :note "n"}]
                          :findings [{:check "goal_served" :claim-id "a" :claim "too much"}]
                          :confirmed [{:id "b"}] :unchecked []
                          :step_back [{:area "m/op" :step "restate" :classes ["x"]}]}))))
  (is (nil? (probe/metrics "not json"))))

(defn- result [variant rep recommend status & levels]
  {:round 0 :variant variant :rep rep :minutes 5.0 :tokens 100
   :metrics {:recommend recommend :checks {"goal_served" status}
             :findings (vec (map-indexed (fn [i l] {:id i :level l}) levels))}})

(deftest a-variant-is-read-against-the-controls-own-noise
  (let [[control variant] (probe/summary [(result "control" 0 "proceed" "held" :design)
                                          (result "control" 1 "amend" "broken" :design)
                                          (result "no-stance" 0 "amend" "broken" :implementation)
                                          (assoc (result "no-stance" 1 "proceed" "held") :stand-in? true)])]
    (is (= "control" (:variant control)))
    (is (= 0.0 (:same-recommend control)) "the control disagreeing with itself is the noise")
    (is (= 1.0 (:design-level control)))
    (is (= 1 (:runs variant)) "a stand-in's answer measures the judge, not the prompt")
    (is (= 0.0 (:design-level variant)))))

(deftest a-round-carries-its-own-edits-for-a-variant
  ;; A hot-area section is rendered from the ledger as it stood at each round, so no one text
  ;; serves every round.
  (let [dir   (str (babashka.fs/create-temp-dir))
        p     (str dir "/prompt.txt")
        s     (str dir "/schema.json")
        _     (spit p (:prompt baseline-round))
        _     (spit s "{\"properties\":{},\"required\":[\"recommend\"]}")
        jobs  (probe/jobs {:variants [{:name "control" :edits []}
                                      {:name "hot" :edits [{:add-property "step_back"
                                                            :from "review/design_decision_schema.json"}]}]
                           :reps {:default 1} :probe-dir dir}
                          0 {:prompt p :schema s :worktree dir
                             :edits {"hot" [{:insert-before-last "\n---\n" :text "\nHOT AREAS — here\n"}]}})
        by    (into {} (map (juxt :variant identity)) jobs)]
    (is (= (:prompt baseline-round) (get-in by ["control" :prompt])) "the control is untouched")
    (is (str/includes? (get-in by ["hot" :prompt]) "the essay\n\nHOT AREAS — here\n\n---\nBefore you start"))
    (let [schema (json/parse-string (get-in by ["hot" :schema]) true)]
      (is (= ["recommend" "step_back"] (:required schema)))
      (is (= "array" (get-in schema [:properties :step_back :type]))))))
