(ns nido.coordinator.view.workstreams-findings-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.view.workstreams :as wsv]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f))
         (finally (fs/delete-tree tmp)))))

(deftest workstream-row-carries-open-findings-count
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:stage :in-progress})]
        (is (= 0 (:open-findings (wsv/workstream-row :brian (ws/read-ws :brian (:id w))))))
        (ws/set-findings! :brian (:id w) {:round 1 :open #{"f1" "f2"} :resolved {}})
        (is (= 0 (:open-findings (wsv/workstream-row :brian (ws/read-ws :brian (:id w)))))
            "the stored tracker is not what the badge counts")
        (ws/append-entry! :brian (:id w) {:kind :findings}
                          (pr-str {:format :findings :round 1
                                   :items [{:id "f1" :summary "s" :severity :tweak}
                                           {:id "f2" :summary "s" :severity :tweak}]}))
        (is (= 2 (:open-findings (wsv/workstream-row :brian (ws/read-ws :brian (:id w)))))
            "the badge counts the round the ledger holds")))))
