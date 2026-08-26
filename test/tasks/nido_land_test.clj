;; test/tasks/nido_land_test.clj
(ns tasks.nido-land-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.standing :as standing]
   [nido.coordinator.workstream :as cws]
   [nido.review.stages :as stages]
   [nido.session.lifecycle :as lifecycle]
   [tasks.nido-land :as land]))

(def ^:private a-design {:format :design :seq 4 :summary "s"})

(defn- run [{:keys [session? design standing]}]
  (let [out (java.io.StringWriter.)]
    (binding [*out* out]
      (with-redefs [lifecycle/worktree-from-cwd (fn [g] g)
                    stages/project+ws-from-cwd (fn [_] (when session? [:nido "ws-1"]))
                    cws/latest-entry (fn [& _] design)
                    standing/of-design (constantly standing)]
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
                                              :detail "the survey at entry 2 was retracted by entry 9"}}})]
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
