(ns nido.coordinator.triggers
  "Per-project trigger config: schema, load, validate, find.

   See spec §Triggers."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [malli.core :as m]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

(def Trigger
  [:map {:closed true}
   [:name           keyword?]
   [:source         [:map [:type keyword?]]]
   [:skill          keyword?]
   [:payload        string?]
   [:filter      {:optional true} [:map-of keyword? any?]]
   [:payload-key {:optional true} keyword?]
   [:agent       {:optional true} keyword?]
   [:limits      {:optional true} [:map-of keyword? any?]]
   [:priority       {:optional true} int?]
   [:priority-from  {:optional true} [:map [:property string?]]]
   [:session-profile {:optional true} keyword?]
   [:preprocess     {:optional true} [:vector keyword?]]
   [:intake         {:optional true} [:enum :spawn :queue]]
   [:dry-run?       {:optional true} boolean?]
   [:enabled?       {:optional true} boolean?]
   [:uncapped?      {:optional true} boolean?]
   [:max-in-flight  {:optional true} pos-int?]
   [:session-name-prefix {:optional true} string?]
   [:on-promote          {:optional true} [:map-of keyword? any?]]])

(def TriggersFile
  [:map {:closed true}
   [:triggers [:vector Trigger]]])

(defn load-for-project
  "Read triggers.edn for a project. Returns a vector of trigger maps
   (possibly empty). Invalid entries are skipped with a stderr warning."
  [project]
  (let [path (cstate/triggers-path project)]
    (if (fs/exists? path)
      (let [raw (io/read-edn path)]
        (if (m/validate TriggersFile raw)
          (:triggers raw)
          (do
            (binding [*err* *err*]
              (.println ^java.io.PrintWriter *err*
                        (str "WARN: invalid triggers.edn for project " project
                             " — " (pr-str (m/explain TriggersFile raw)))))
            (->> (:triggers raw)
                 (filter #(m/validate Trigger %))
                 vec))))
      [])))

(defn find-by-name
  "Find a trigger in a loaded vector by :name. Returns nil if absent."
  [triggers name]
  (some #(when (= name (:name %)) %) triggers))

(def ^:private placeholder-re
  #"\{\{event/([^}]+)\}\}")

(defn- lookup-path
  "Resolve a slash-delimited path like 'ticket/id' against an event map."
  [event path]
  (let [ks (mapv keyword (str/split path #"/"))]
    (get-in event ks)))

(defn render-payload
  "Replace {{event/path}} placeholders in template with values from event.
   Missing values render as empty string."
  [template event]
  (str/replace template placeholder-re
               (fn [[_ path]] (str (lookup-path event path)))))
