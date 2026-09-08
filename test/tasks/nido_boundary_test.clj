;; test/tasks/nido_boundary_test.clj
(ns tasks.nido-boundary-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [tasks.nido-boundary :as boundary]
   [tasks.nido-owed :as owed]))

;; ── What it asks for ────────────────────────────────────────────────────────

(deftest a-stage-nobody-owes-asks-the-turn-to-carry-on
  (let [d (boundary/asks-for {:at :design-approved :stage :implement
                              :mode :working-copy :person nil})]
    (is (= :continue (:ask d)))
    (is (str/includes? (:say d) "implement")
        "and says what is owed, because the reason is what reaches the model")))

(deftest a-stage-a-person-owes-waits-rather-than-asking
  (is (= :wait (:ask (boundary/asks-for {:at :design-decided :stage :approve-design
                                         :mode :human :person :approve-design})))))

(defn- are-nothing [cases]
  (doseq [[answer because] cases]
    (let [d (boundary/asks-for answer)]
      (is (= :nothing (:ask d)) (str "asks for nothing: " because))
      (is (= because (:because d))))))

(deftest everything-it-cannot-decide-asks-for-nothing
  ;; The contract that makes this safe to install everywhere: a hook that breaks
  ;; a session it cannot even resolve is worse than no hook.
  (are-nothing
   [[nil                                              :no-workstream]
    [{:at :baselined :stage :verify-baseline :mode :mechanical
      :held-by "baseline-loop-7"}                     :claimed]
    [{:at :unplaceable :stage nil :mode nil}           :unplaceable]
    [{:at :shipped :stage nil :mode nil}               :terminal]]))

;; ── The wait ────────────────────────────────────────────────────────────────

(defn- ask-over
  "Run the wait against a scripted sequence of ledger folds, with time and sleep
   supplied so the test spends neither."
  [answers & {:keys [wait-ms] :or {wait-ms 100}}]
  (let [remaining (atom answers)
        clock     (atom 0)]
    (with-redefs [owed/owed-at (fn [_] (let [a (first @remaining)]
                                         (swap! remaining #(if (next %) (next %) %))
                                         a))]
      (boundary/ask-at "/anywhere"
                       {:wait-ms  wait-ms
                        :poll-ms  10
                        :sleep-fn (fn [ms] (swap! clock + ms))
                        :now-fn   (fn [] @clock)}))))

(deftest an-answer-landing-during-the-wait-carries-the-turn-on
  ;; The whole point of the wait: an approval is an append, and the next fold
  ;; reads it. Nothing is delivered to the waiting session.
  (let [d (ask-over [{:at :design-decided :stage :approve-design :mode :human
                      :person :approve-design}
                     {:at :design-decided :stage :approve-design :mode :human
                      :person :approve-design}
                     {:at :design-approved :stage :implement :mode :working-copy
                      :person nil}])]
    (is (= :continue (:ask d)))
    (is (str/includes? (:say d) "implement")
        "and it carries on into what the answer made due, not what it waited on")))

(deftest a-wait-nobody-answers-withdraws-the-request
  (let [d (ask-over [{:at :design-decided :stage :approve-design :mode :human
                      :person :approve-design}])]
    (is (= {:ask :nothing :because :waited-out} d)
        "nido stops asking; whether the turn ends is the host's, across every
         hook that answered")))

(deftest nothing-is-remembered-between-folds
  ;; The fold is the whole state. A wait that ended is not a thing a later
  ;; reader has to reconcile, which is what lets a session die mid-wait.
  (let [seen (atom 0)]
    (with-redefs [owed/owed-at (fn [_] (swap! seen inc)
                                 {:at :design-decided :stage :approve-design
                                  :mode :human :person :approve-design})]
      (boundary/ask-at "/anywhere" {:wait-ms 30 :poll-ms 10
                                    :sleep-fn (fn [_] nil)
                                    :now-fn (let [t (atom 0)] #(swap! t + 20))}))
    (is (pos? @seen) "it re-asked rather than sleeping on a remembered answer")))

;; ── Failing open ────────────────────────────────────────────────────────────

(deftest a-throw-anywhere-asks-for-nothing
  (with-redefs [owed/owed-at (fn [_] (throw (ex-info "ledger unreadable" {})))]
    (let [d (binding [*in* (java.io.PushbackReader. (java.io.StringReader. ""))]
                (boundary/boundary-cmd* {:cwd "/anywhere"}))]
      (is (= :nothing (:ask d)))
      (is (= :threw (:because d))
          "and it says so, so a hook that is silently doing nothing is
           distinguishable from one that decided to"))))
