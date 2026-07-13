(ns nido.coordinator.workstreams-view-findings-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]
   [nido.coordinator.workstreams-view :as wsv]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [cstate/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f))
         (finally (fs/delete-tree tmp)))))

(deftest workstream-row-carries-open-findings-count
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:stage :in-progress})]
        (is (= 0 (:open-findings (wsv/workstream-row :brian (ws/read-ws :brian (:id w))))))
        (ws/set-findings! :brian (:id w) {:round 1 :open #{"f1" "f2"} :resolved {}})
        (is (= 2 (:open-findings (wsv/workstream-row :brian (ws/read-ws :brian (:id w))))))))))
