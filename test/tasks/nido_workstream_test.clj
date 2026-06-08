(ns tasks.nido-workstream-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]
   [tasks.nido-workstream :as task]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [cstate/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f tmp))
         (finally (fs/delete-tree tmp)))))

(deftest entry-add-stage-advance-close
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :triaging
                                  :external-refs [{:adapter :notion :id "BR-3"}]})]
        (task/entry-add* {:project "brian" :ref "BR-3" :kind "triage" :content "found a bug"})
        (let [w2 (ws/read-ws :brian (:id w))]
          (is (= 1 (count (:entries w2))))
          (is (= :triage (-> w2 :entries first :kind))))
        (task/stage-advance* {:project "brian" :ref "BR-3" :stage "investigating"})
        (is (= :investigating (:stage (ws/read-ws :brian (:id w)))))
        (task/close* {:project "brian" :ref "BR-3" :outcome "done"})
        (is (= :done (-> (ws/read-ws :brian (:id w)) :closed :outcome)))))))
