(ns tasks.nido-pickup
  "bb task entry point for pickup: drive a Notion ticket by URL or id."
  (:require
   [nido.coordinator.pickup :as pickup]
   [nido.coordinator.state :as cstate]
   [nido.notion.client :as client]
   [nido.task-args :as task-args]))

(defn pickup
  "bb nido:pickup :project <p> <notion-url-or-id>"
  [& args]
  (let [[positionals opts] (task-args/split-args args #{:url})
        project (keyword (or (:project opts) "brian"))
        input   (str (first positionals))]
    (cstate/ensure-dirs!)
    (let [r (pickup/pickup! project input (client/keychain-token))]
      (if (= :driving (:decision r))
        (println "driving" (:id (:ref r)) "→ queued" (:queued r))
        (println "could not resolve:" input "(" (name (:error r)) ")")))))
