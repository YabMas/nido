(ns nido.coordinator.intake-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.intake :as intake]
   [nido.coordinator.session :as session]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [cstate/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f tmp))
         (finally (fs/delete-tree tmp)))))

(def ^:private routed
  {:project :brian
   :trigger {:name :triage-slack-bugs :skill :triage-bug}
   :payload {:adapter :slack-message :id "slack-C-1.0"
             :title "boom" :text "it broke" :url "u"}})

(deftest enqueue-creates-sessionless-inbox-workstream
  (with-tmp
    (fn [_]
      (let [w (intake/enqueue-inbox! routed)]
        (is (= :inbox (:stage w)))
        (is (= :triage-slack-bugs (-> w :intake :trigger)))
        (is (= "it broke" (-> w :intake :payload :text)))
        (is (= {:adapter :slack-message :id "slack-C-1.0"}
               (-> w :external-refs first (select-keys [:adapter :id]))))
        (is (empty? (session/list-sessions :brian (:id w))))))))

(deftest enqueue-dedups-on-ref
  (with-tmp
    (fn [_]
      (let [a (intake/enqueue-inbox! routed)
            b (intake/enqueue-inbox! routed)]
        (is (= (:id a) (:id b)))
        (is (= 1 (count (ws/list-ids :brian))))))))
