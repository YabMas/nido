(ns tasks.nido-pickup
  "bb task entry point for pickup: drive a Notion ticket by URL or id."
  (:require
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.work :as work]
   [nido.notion.client :as client]
   [nido.platform.task-args :as task-args]))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  pickup
  "bb nido:pickup :project <p> <notion-url-or-id>"
  [& args]
  (let [[positionals opts] (task-args/split-args args #{:url})
        project (keyword (or (:project opts) "brian"))
        input   (str (first positionals))]
    (cstate/ensure-dirs!)
    (let [r (work/pickup! project input (client/keychain-token))]
      (if (= :driving (:decision r))
        (println "driving" (:id (:ref r)) "on" (:ws-id r))
        (println "could not start:" input "(" (pr-str (:error r)) ")")))))
