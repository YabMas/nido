;; test/tasks/nido_owed_test.clj
(ns tasks.nido-owed-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.lane.pipeline :as pipeline]
   [nido.coordinator.record.activity :as activity]
   [tasks.nido-owed :as owed]))

(defn- at [position]
  (with-redefs [pipeline/of        (constantly position)
                activity/read-live (constantly nil)]
    (owed/owed :brian "ws-1")))

(deftest a-terminal-position-owes-nothing
  (let [a (at {:at :shipped :next nil})]
    (is (nil? (:stage a)))
    (is (nil? (:person a)))
    (is (str/includes? (owed/owed-line a) "nothing owed")
        "and the line says the arc is over rather than naming a stage")))

(deftest a-human-mode-names-the-person-and-the-stage-they-owe
  (let [a (at {:at :design-decided :next {:stage :approve-design :mode :human}})]
    (is (= :approve-design (:person a))
        "the stage itself, because a person needs to know WHICH")
    (is (str/includes? (owed/owed-line a) "a person owes approve-design"))
    (is (str/includes? (owed/owed-line a) "needs-you")
        "and is pointed at the inbox the workstream already stands in")))

(deftest a-stage-nobody-owes-is-not-a-persons
  (let [a (at {:at :design-approved :next {:stage :implement :mode :working-copy}})]
    (is (nil? (:person a)) "nobody is waited on")
    (is (= [:implement :working-copy] [(:stage a) (:mode a)]))
    (is (str/includes? (owed/owed-line a) "bb nido:attach")
        "the line names what would run it")))

(deftest whether-a-person-owes-it-is-asked-of-one-function
  ;; The property that keeps the inbox and the turn boundary from disagreeing:
  ;; this asks work/awaiting-human rather than testing the mode itself, so a
  ;; change to what counts as a person's move reaches both at once.
  (doseq [mode [:mechanical :authoring :working-copy]]
    (is (nil? (:person (at {:at :baselined :next {:stage :verify-baseline :mode mode}})))
        (str "nothing but :human is a person's — " mode)))
  (is (some? (:person (at {:at :blocked :next {:stage :answer-blocker :mode :human}})))))

(deftest a-claimed-workstream-owes-this-caller-nothing
  (let [a (with-redefs [pipeline/of (constantly {:at :baselined
                                                 :next {:stage :verify-baseline
                                                        :mode :mechanical}})
                        activity/read-live (constantly {:run-id "baseline-loop-7"})]
            (owed/owed :brian "ws-1"))]
    (is (= "baseline-loop-7" (:held-by a)))
    (is (str/includes? (owed/owed-line a) "held by baseline-loop-7")
        "the claim outranks the position in what the caller is told, because a
         stage something else is already running is not this caller's to take")))

(deftest a-path-that-is-no-workstream-answers-nil
  (is (nil? (owed/owed-at "/tmp/nowhere-that-is-a-nido-session"))
      "a reading asked outside a session answers nothing rather than throwing"))
