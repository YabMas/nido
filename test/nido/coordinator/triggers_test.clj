(ns nido.coordinator.triggers-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [malli.core :as m]
   [nido.coordinator.triggers :as triggers]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

(def minimal-trigger
  {:name    :investigate-bug
   :source  {:type :manual}
   :skill   :investigate-bug
   :payload "{{event/url}}"})

(deftest schema-accepts-minimal-trigger
  (is (m/validate triggers/Trigger minimal-trigger)))

(deftest schema-rejects-missing-name
  (is (not (m/validate triggers/Trigger (dissoc minimal-trigger :name)))))

(deftest schema-accepts-optional-fields
  (let [t (merge minimal-trigger
                 {:filter      {:priority ["P0"]}
                  :payload-key :ticket-id
                  :agent       :claude
                  :limits      {:budget "45m" :max-failures 3}
                  :dry-run?    true
                  :enabled?    false})]
    (is (m/validate triggers/Trigger t))))

(deftest load-triggers-reads-file
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (fs/create-dirs (fs/parent (cstate/triggers-path :brian)))
        (io/write-edn! (cstate/triggers-path :brian)
                       {:triggers [minimal-trigger]})
        (let [loaded (triggers/load-for-project :brian)]
          (is (= 1 (count loaded)))
          (is (= :investigate-bug (-> loaded first :name)))))
      (finally (fs/delete-tree tmp)))))

(deftest load-triggers-returns-empty-when-file-missing
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (is (= [] (triggers/load-for-project :nonexistent))))
      (finally (fs/delete-tree tmp)))))

(deftest find-trigger-by-name
  (let [ts [minimal-trigger
            (assoc minimal-trigger :name :other)]]
    (is (= :investigate-bug (:name (triggers/find-by-name ts :investigate-bug))))
    (is (nil? (triggers/find-by-name ts :missing)))))

(deftest render-payload-substitutes-top-level
  (is (= "/investigate-bug url=https://example.com"
         (triggers/render-payload "/investigate-bug url={{event/url}}"
                                  {:url "https://example.com"}))))

(deftest render-payload-substitutes-nested
  (is (= "ticket=ABC priority=P0"
         (triggers/render-payload "ticket={{event/ticket/id}} priority={{event/ticket/priority}}"
                                  {:ticket {:id "ABC" :priority "P0"}}))))

(deftest render-payload-leaves-literal-text-alone
  (is (= "no placeholders here"
         (triggers/render-payload "no placeholders here" {:url "x"}))))

(deftest render-payload-missing-key-renders-empty
  (is (= "url=" (triggers/render-payload "url={{event/missing}}" {:url "x"}))))

(deftest schema-accepts-priority
  (is (m/validate triggers/Trigger
                  (assoc minimal-trigger :priority 10))))

(deftest schema-rejects-non-int-priority
  (is (not (m/validate triggers/Trigger
                       (assoc minimal-trigger :priority "high")))))

(deftest schema-accepts-session-profile
  (is (m/validate triggers/Trigger
                  (assoc minimal-trigger :session-profile :lite))))

(deftest schema-rejects-non-keyword-session-profile
  (is (not (m/validate triggers/Trigger
                       (assoc minimal-trigger :session-profile "lite")))))

(deftest schema-accepts-priority-from-map
  (is (m/validate triggers/Trigger
                  (assoc minimal-trigger :priority-from {:property "severity-calc"}))))

(deftest schema-accepts-uncapped
  (is (m/validate triggers/Trigger
                  (assoc minimal-trigger :uncapped? true))))

(deftest trigger-schema-accepts-preprocess-vector
  (let [t {:name :triage-new
           :source {:type :notion-view}
           :skill :triage-bug
           :payload ""
           :preprocess [:notion-ticket]}]
    (is (m/validate triggers/Trigger t))))

(deftest trigger-schema-rejects-non-keyword-preprocess
  (let [t {:name :triage-new
           :source {:type :notion-view}
           :skill :triage-bug
           :payload ""
           :preprocess ["notion-ticket"]}]
    (is (not (m/validate triggers/Trigger t)))))

(deftest trigger-schema-allows-no-preprocess
  (let [t {:name :smoke
           :source {:type :smoke}
           :skill :smoke
           :payload ""}]
    (is (m/validate triggers/Trigger t))))

(deftest max-in-flight-is-an-optional-pos-int
  (let [base {:name :t :source {:type :notion-view} :skill :triage-bug :payload "p"}]
    (is (m/validate triggers/Trigger (assoc base :max-in-flight 5)))
    (is (m/validate triggers/Trigger base) "absent is fine")
    (is (not (m/validate triggers/Trigger (assoc base :max-in-flight 0))) "must be positive")
    (is (not (m/validate triggers/Trigger (assoc base :max-in-flight "5"))) "must be an int")))

(deftest trigger-accepts-on-promote-and-session-name-prefix
  (is (m/validate triggers/Trigger
                  {:name :plan-bug :source {:type :manual} :skill :plan-bug
                   :payload "Plan {{event/title}}"
                   :session-profile :full
                   :session-name-prefix "impl-"
                   :on-promote {:notion-status "In progress"}})))

(deftest trigger-rejects-bad-session-name-prefix
  (is (not (m/validate triggers/Trigger
                       {:name :plan-bug :source {:type :manual} :skill :plan-bug
                        :payload "x" :session-name-prefix 42}))))

(deftest intake-queue-trigger-loads
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (let [p (cstate/triggers-path :brian)]
          (fs/create-dirs (fs/parent p))
          (spit p (pr-str {:triggers [{:name :triage-slack-bugs
                                       :source {:type :slack-channel :channel "C"}
                                       :skill :triage-bug
                                       :payload "Triage {{event/title}}"
                                       :intake :queue}]}))
          (let [t (triggers/find-by-name (triggers/load-for-project :brian)
                                         :triage-slack-bugs)]
            (is (= :queue (:intake t))))))
      (finally (fs/delete-tree tmp)))))
