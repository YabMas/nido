(ns nido.coordinator.record.triggers
  "Per-project trigger config: schema, load, validate, find.

   See spec §Triggers."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [malli.core :as m]
   [nido.coordinator.record.state :as cstate]
   [nido.platform.io :as io]))

(def Trigger
  [:map {:closed true}
   [:name           keyword?]
   [:source         [:map [:type keyword?]]]
   [:skill          keyword?]
   [:payload        string?]
   [:filter      {:optional true} [:map-of keyword? any?]]
   [:payload-key {:optional true} keyword?]
   [:agent       {:optional true} keyword?]
   ;; REQUIRED, and :budget inside it is required too. A trigger is the place a
   ;; unit of autonomous work declares its brakes, and this schema used to
   ;; constrain neither whether they were declared nor what they contained — so
   ;; omitting :budget was not an error anywhere, and agent/launch! read the
   ;; resulting nil as infinite. brian's :plan-bug omitted it for months while a
   ;; comment above it asserted it ran nothing headlessly.
   ;;
   ;; Refused HERE rather than only at launch, because here nothing has been
   ;; spawned yet: a bad trigger fails when the config is read, not after a
   ;; session, a worktree and a database have been provisioned for it. The
   ;; launch-time refusal stays as the backstop for every caller that reaches an
   ;; agent without a trigger behind it.
   [:limits      [:map
                  [:budget string?]
                  [:max-failures {:optional true} pos-int?]]]
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

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:vector :Trigger]]}
  load-for-project
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

(defn ^{:malli/schema [:=> [:cat [:vector :Trigger] :keyword] [:maybe :Trigger]]}
  find-by-name
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

(defn ^{:malli/schema [:=> [:cat :string :map] :string]}
  render-payload
  "Replace {{event/path}} placeholders in template with values from event.
   Missing values render as empty string."
  [template event]
  (str/replace template placeholder-re
               (fn [[_ path]] (str (lookup-path event path)))))

(defn ^{:malli/schema [:=> [:cat :string] [:vector :keyword]]}
  placeholder-keys
  "Return ordered vector of placeholder names from a trigger's :payload
   template. `{{event/url}}` → `:url`. Top-level keys only — slash-paths
   are not addressable from the form."
  [payload-template]
  (->> (re-seq #"\{\{event/([^}/]+)\}\}" payload-template)
       (map second)
       distinct
       (mapv keyword)))
