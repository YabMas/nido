(ns nido.coordinator.events-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.events :as events]))

(def triggers-fixture
  [{:name    :investigate-bug
    :source  {:type :manual}
    :skill   :investigate-bug
    :payload "{{event/url}}"}
   {:name    :other
    :source  {:type :manual}
    :skill   :other
    :payload "x"}])

(deftest direct-target-resolves-trigger
  (let [envelope {:target  {:project :brian :trigger :investigate-bug}
                  :payload {:url "https://x"}}
        request  (events/route envelope {:brian triggers-fixture})]
    (is (= :investigate-bug (-> request :trigger :name)))
    (is (= :brian (:project request)))
    (is (= {:url "https://x"} (:payload request)))))

(deftest direct-target-unknown-trigger-returns-error
  (let [envelope {:target {:project :brian :trigger :missing} :payload {}}
        result   (events/route envelope {:brian triggers-fixture})]
    (is (= :unknown-trigger (:error result)))))

(deftest direct-target-unknown-project-returns-error
  (let [envelope {:target {:project :nope :trigger :x} :payload {}}
        result   (events/route envelope {:brian triggers-fixture})]
    (is (= :unknown-project (:error result)))))

(deftest broadcast-envelope-stubbed
  (is (= :broadcast-not-implemented
         (:error (events/route {:broadcast {:event :x}} {})))))

(deftest unknown-envelope-shape
  (is (= :unknown-envelope
         (:error (events/route {:noise true} {})))))
