(ns nido.coordinator.workstream-findings-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [cstate/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f))
         (finally (fs/delete-tree tmp)))))

(deftest reopen-clears-closed-and-sets-stage
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:stage :in-progress
                                  :external-refs [{:adapter :notion :id "BR-1"}]})]
        (ws/close! :brian (:id w) :done)
        (is (= :done (-> (ws/read-ws :brian (:id w)) :closed :outcome)))
        (ws/reopen! :brian (:id w) :in-progress)
        (let [w2 (ws/read-ws :brian (:id w))]
          (is (nil? (:closed w2)))
          (is (= :in-progress (:stage w2)))
          (is (true? (-> w2 :stage-history last :reopened))))))))

(deftest set-findings-writes-and-clears-tracker
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:stage :in-progress})
            t {:round 1 :open #{"f1"} :resolved {}}]
        (ws/set-findings! :brian (:id w) t)
        (is (= t (:findings (ws/read-ws :brian (:id w)))))
        (ws/set-findings! :brian (:id w) nil)
        (is (nil? (:findings (ws/read-ws :brian (:id w)))))))))
