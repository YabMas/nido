(ns nido.coordinator.session
  "A work-episode against a workstream. Carries substrate state (live/archived)
   and weight (light/heavy), plus an OPTIONAL autonomy facet — the old Run,
   demoted to a field. nil autonomy ⇒ a human session.
   See spec docs/superpowers/specs/2026-06-05-workstream-session-model-design.md."
  (:require
   [babashka.fs :as fs]
   [malli.core :as m]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

(def phases
  #{:queued :preprocessing :running :parked :done :failed :halted})

(def Autonomy
  [:map {:closed true}
   [:skill             keyword?]
   [:first-message     string?]
   [:agent             keyword?]
   [:claude-session-id [:maybe string?]]
   [:trigger           keyword?]
   [:limits            [:map-of keyword? any?]]
   [:priority          int?]
   [:uncapped?         boolean?]
   [:on-promote        [:maybe [:map-of keyword? any?]]]
   [:phase             (into [:enum] phases)]
   [:phase-history     [:vector [:map [:at string?] [:phase (into [:enum] phases)]]]]
   [:error             [:maybe [:map-of keyword? any?]]]])

(def Session
  [:map {:closed true}
   [:name              string?]
   [:workstream-id     string?]
   [:project           keyword?]
   [:weight            [:enum :light :heavy]]
   [:substrate         [:enum :live :archived]]
   [:substrate-history [:vector [:map [:at string?] [:substrate [:enum :live :archived]]]]]
   [:autonomy          [:maybe Autonomy]]
   [:created-at        string?]])

(defn validate [s]
  (if (m/validate Session s)
    s
    (throw (ex-info "Invalid Session record"
                    {:errors (m/explain Session s) :session s}))))

(defn read-session
  "Read a session.edn by project + ws-id + name. Returns nil if absent."
  [project ws-id session-name]
  (io/read-edn (cstate/session-edn-path project ws-id session-name)))

(defn write! [s]
  (validate s)
  (io/write-edn! (cstate/session-edn-path (:project s) (:workstream-id s) (:name s)) s)
  s)

(defn create!
  "Persist a fresh :live session. `opts` carries :name, :weight, and :autonomy
   (nil for human sessions, or a full Autonomy map). Reads via (:name opts)
   rather than destructuring to avoid shadowing clojure.core/name."
  [project ws-id opts]
  (let [now (clock/now-iso)]
    (write! {:name              (:name opts)
             :workstream-id     ws-id
             :project           project
             :weight            (:weight opts)
             :substrate         :live
             :substrate-history [{:at now :substrate :live}]
             :autonomy          (:autonomy opts)
             :created-at        now})))

(defn list-sessions
  "Seq of session records under one workstream's sessions/ dir."
  [project ws-id]
  (let [d (cstate/ws-sessions-dir project ws-id)]
    (if (fs/exists? d)
      (->> (fs/list-dir d)
           (filter fs/directory?)
           (keep #(read-session project ws-id (str (fs/file-name %)))))
      [])))
