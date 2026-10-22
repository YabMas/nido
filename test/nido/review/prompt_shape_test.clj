(ns nido.review.prompt-shape-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.review.prompt-shape :as shape]))

(def ^:private prompt
  (str "You are judging a design.\n"
       "CLAIMS — each with the elements it is about:\n  [a] one\n"
       "PROJECT STANCE — framing only:\n# Domain Modeling\nthe shape of the data is the design\n"
       "DERIVE THESE. Each is decidable; none is a matter of taste:\n\n"
       "  relation-honest   — does it stand where it says?\n"
       "                      more text\n"
       "  data-shaped       — does the data carry the reasoning?\n\n"
       "Then recommend:\n"
       "  proceed  — nothing derivable blocks it.\n"
       "CLAIMS — the baseline's, again:\n  [b] two\n"))

(deftest a-prompt-is-read-as-its-sections
  (let [s (shape/shape prompt)]
    (is (= (count prompt) (:bytes s)))
    (is (= #{"(preamble)" "CLAIMS" "PROJECT STANCE" "DERIVE THESE"} (set (keys (:sections s))))
        "a heading is named by its capitalised words, so its trailing prose does not split it")
    (is (= (count prompt) (+ (reduce + (vals (:sections s))) (dec (count (shape/sections prompt)))))
        "every byte is in one section — a heading met twice sums")))

(deftest the-checks-asked-stop-at-the-recommendations
  (is (= ["relation-honest" "data-shaped"] (:checks (shape/shape prompt)))
      "`proceed  —` opens a line the same way and is no check"))

(deftest the-stance-is-one-variant-wherever-it-sits
  (let [moved (str "DERIVE THESE. x\n  goal-served       — y\n"
                   "PROJECT STANCE — framing only:\n# Domain Modeling\nthe shape of the data is the design\n")]
    (is (= (get-in (shape/shape prompt) [:stance :digest]) (get-in (shape/shape moved) [:stance :digest]))
        "the same stance text reads as the same stance, before the checks or after them")
    (is (nil? (:stance (shape/shape "CLAIMS — x\n"))) "a prompt without one has none")))
