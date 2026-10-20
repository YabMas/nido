(ns nido.coordinator.record.workstream-findings-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.coordinator.record.state :as cstate]
   [nido.platform.io :as io]
   [nido.coordinator.record.workstream :as ws]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f))
         (finally (fs/delete-tree tmp)))))

(deftest reopen-ends-the-close-and-chooses-the-stage
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:external-refs [{:adapter :notion :id "BR-1"}]})]
        (ws/close! :brian (:id w) :done)
        (is (= :done (:outcome (ws/closure (ws/read-ws :brian (:id w))))))
        (ws/reopen! :brian (:id w) :in-progress)
        (let [w2 (ws/read-ws :brian (:id w))]
          (is (nil? (ws/closure w2)) "the close no longer stands")
          (is (= :in-progress (:stage (ws/newest-choice w2))) "and the stage is the one chosen"))
        (let [n (count (:entries (ws/read-ws :brian (:id w))))]
          (ws/reopen! :brian (:id w) :in-progress)
          (is (= n (count (:entries (ws/read-ws :brian (:id w)))))
              "reopening an open workstream at its chosen stage records nothing"))))))

(deftest a-record-written-with-stored-status-sheds-it
  ;; Where a workstream is lives on its ledger; a record written before keeps no copy once
  ;; anything reads or rewrites it.
  (with-tmp
    (fn []
      (let [w    (ws/create! :brian {})
            path (cstate/workstream-edn-path :brian (:id w))]
        (io/write-edn! path (assoc w :stage :ready :closed {:at "t" :outcome :done}
                                     :findings {:round 1} :stage-history []))
        (is (empty? (select-keys (ws/read-ws :brian (:id w))
                                 [:stage :closed :findings :stage-history])))
        (ws/set-title! :brian (:id w) "t" "b")
        (is (empty? (select-keys (io/read-edn path) [:stage :closed :findings :stage-history]))
            "and a write leaves the file without them")))))
