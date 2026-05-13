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
