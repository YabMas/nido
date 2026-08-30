;; test/tasks/nido_land_test.clj
(ns tasks.nido-land-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.record.standing :as standing]
   [nido.coordinator.record.workstream :as cws]
   [nido.design.check :as design]
   [nido.review.stages :as stages]
   [nido.session.lifecycle :as lifecycle]
   [tasks.nido-design :as nido-design]
   [tasks.nido-land :as land]))

(def ^:private a-design {:format :design :seq 4 :summary "s"})

(defn- run
  "Drive the gate with both halves stubbed. `structure` defaults to nil = the cwd belongs to no
   registered project, which is what every standing-only case wants."
  [{:keys [session? design standing structure]}]
  (let [out (java.io.StringWriter.)]
    (binding [*out* out]
      (with-redefs [lifecycle/worktree-from-cwd (fn [g] g)
                    stages/project+ws-from-cwd (fn [_] (when session? [:nido "ws-1"]))
                    cws/latest-entry (fn [& _] design)
                    standing/of-design (constantly standing)
                    nido-design/coords (fn [_] (when structure [:nido "/wt"]))
                    design/check (constantly structure)
                    design/design-of (constantly {:files ["/wt/canvas/bands.clj"]})]
        [(land/check ":cwd" "/wt") (str out)]))))

(deftest a-standing-approved-design-lands
  (let [[code out] (run {:session? true :design a-design
                         :standing {:decided? true :approved-by 7}})]
    (is (zero? code))
    (is (str/includes? out "ok"))
    (is (str/includes? out "entry 7"))))

(deftest a-workstream-with-no-design-lands
  ;; Most have none — scratch workstreams, pickups mid-flight — and a gate that
  ;; demanded one of every branch would stop the work that has not reached a
  ;; design yet, which is not what this is for.
  (let [[code out] (run {:session? true :design nil})]
    (is (zero? code))
    (is (str/includes? out "no design"))))

(deftest a-cwd-that-is-no-session-lands
  (let [[code _] (run {:session? false})]
    (is (zero? code))))

(deftest a-design-nobody-granted-is-refused-and-told-where-to-grant-it
  (let [[code out] (run {:session? true :design a-design
                         :standing {:decidable? true :decided? false}})]
    (is (= 1 code))
    (is (str/includes? out "REFUSED"))
    (is (str/includes? out "not-approved"))
    (is (str/includes? out "gate inbox"))))

(deftest a-retracted-premise-is-refused-naming-the-entry-and-the-counterexample
  (let [[code out] (run {:session? true :design a-design
                         :standing {:decidable? false :decided? false
                                    :blocked {:reason :premise-retracted :seq 9
                                              :replaced-by 12
                                              :detail "the baseline at entry 2 was retracted by entry 9"}}})]
    (is (= 1 code))
    (is (str/includes? out "entry 9") "the entry responsible")
    (is (str/includes? out "found it FALSE") "and that this is not mere staleness")
    (is (str/includes? out "entry 12 corrects it") "and where to start from")))

(deftest an-unreadable-ledger-refuses-rather-than-waving-through
  (let [[code out] (run {:session? true :design a-design
                         :standing {:indeterminate? true
                                    :blocked {:reason :unreadable-ledger
                                              :detail "an entry could not be read"}}})]
    (is (= 1 code))
    (is (str/includes? out "fails closed"))))

(deftest every-refusal-standing-can-produce-names-a-way-out
  ;; The rule this gate is built on: an agent told only that it is blocked will
  ;; guess or stop. A reason with no route is a wall.
  (let [way-out #'land/way-out]
    (doseq [reason [:premise-unverified :premise-retracted :design-retracted
                    :no-premise :not-approved :unreadable-ledger]]
      (let [txt (way-out {:reason reason :seq 3})]
        (is (not (str/includes? txt "No route recorded"))
            (str reason " must name what to do"))
        (is (< 40 (count txt)) (str reason " must say more than a sentence fragment"))))
    (testing "and an unrecognised one says so rather than pretending"
      (is (str/includes? (way-out {:reason :something-new}) "No route recorded")))))

;; ── the second question: does the code still obey the declared structure? ──────
;; Standing asks whether anyone still believes the premise this branch was written
;; against. Structure asks whether the branch left the codebase in the shape the
;; project says it has. A branch can pass one and fail the other in either
;; direction, so neither substitutes for the other.

(deftest a-project-with-no-declared-structure-lands
  (let [[code out] (run {:session? true :design nil :structure {:status :unmodelled}})]
    (is (zero? code))
    (is (str/includes? out "no structure to check"))))

(deftest code-that-broke-the-declared-structure-is-refused-with-the-offending-edge
  (let [[code out] (run {:session? true :design nil
                         :structure {:status :violated
                                     :violations [{:law "no undeclared edge"
                                                   :vars ["?from" "?to"]
                                                   :offenders [["a.b" "c.d"]]}]}})]
    (is (= 1 code))
    (is (str/includes? out "REFUSED"))
    (is (str/includes? out "from=a.b  to=c.d") "the finding, not just its count")
    (is (str/includes? out "canvas/bands.clj") "and where the declaration lives")))

(deftest a-structure-check-that-did-not-complete-refuses-rather-than-waving-through
  (let [[code out] (run {:session? true :design nil
                         :structure {:status :undecidable :error "a law would not compile"}})]
    (is (= 2 code) "distinct from a violation: nobody could tell, which is not a clean bill")
    (is (str/includes? out "did not complete"))
    (is (str/includes? out "Fix the checker"))))

(deftest both-questions-are-asked-even-when-the-first-one-refuses
  ;; An agent that has to discover its blockers one push at a time makes one trip
  ;; per blocker.
  (let [[code out] (run {:session? true :design a-design
                         :standing {:decidable? true :decided? false}
                         :structure {:status :violated
                                     :violations [{:law "no undeclared edge"
                                                   :vars ["?from" "?to"]
                                                   :offenders [["a.b" "c.d"]]}]}})]
    (is (= 1 code))
    (is (str/includes? out "not-approved") "the standing refusal")
    (is (str/includes? out "from=a.b  to=c.d") "and the structural one, in the same run")))
