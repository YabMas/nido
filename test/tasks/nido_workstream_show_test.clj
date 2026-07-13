(ns tasks.nido-workstream-show-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.findings :as findings]
   [nido.coordinator.queue :as queue]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.tickets :as tickets]
   [nido.coordinator.workstream :as ws]
   [tasks.nido-workstream :as t]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [cstate/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f))
         (finally (fs/delete-tree tmp)))))

(deftest show-surfaces-findings-from-the-active-ledger
  (with-tmp
    (fn []
      (with-redefs [queue/enqueue! (fn [_] "/q/x.edn")]
        (tickets/open! :brian "BR-9" {:title "T" :url "u"})
        (let [w (ws/create! :brian {:stage :in-progress
                                    :external-refs [{:adapter :notion :id "BR-9"}]})]
          ;; prior own-entry → active ledger is the workstream's own entries.
          ;; :note is unregistered (freeform md), so it skips :review's EDN-schema
          ;; validation — we only need SOME prior own-entry here, not a real review.
          (ws/append-entry! :brian (:id w) {:kind :note} "prior review")
          (ws/close! :brian (:id w) :done)
          (findings/file! :brian (:id w)
                          {:items [{:summary "Save button 500s" :severity :blocker}]})
          (let [out (with-out-str (t/show* {:project "brian" :ws-id (:id w)}))]
            (is (str/includes? out (:id w)))                ; prints the ws-id
            (is (str/includes? out "Findings round 1"))      ; renders the findings event
            (is (str/includes? out "Save button 500s"))))))))
