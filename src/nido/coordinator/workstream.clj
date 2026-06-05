(ns nido.coordinator.workstream
  "The workstream: the source-agnostic spine. Owns the append-only log and the
   descriptive workflow stage. Engagement state is NOT stored here — it is a
   projection over this workstream's session records (see nido.coordinator.session).
   See spec docs/superpowers/specs/2026-06-05-workstream-session-model-design.md."
  (:require
   [clojure.string :as str]
   [malli.core :as m]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

(def ExternalRef
  [:map {:closed true}
   [:adapter keyword?]
   [:id      string?]
   [:page-id {:optional true} [:maybe string?]]
   [:url     {:optional true} [:maybe string?]]
   [:title   {:optional true} [:maybe string?]]])

(def Workstream
  [:map {:closed true}
   [:id            string?]
   [:project       keyword?]
   [:external-refs [:vector ExternalRef]]
   [:stage         keyword?]
   [:stage-history [:vector [:map [:at string?] [:stage keyword?]]]]
   [:closed        [:maybe [:map
                            [:at      string?]
                            [:outcome [:enum :done :dropped]]]]]
   [:created-at    string?]
   [:entries       [:vector [:map-of keyword? any?]]]])

(defn validate [w]
  (if (m/validate Workstream w)
    w
    (throw (ex-info "Invalid Workstream record"
                    {:errors (m/explain Workstream w) :workstream w}))))

(defn mint-id
  "ws-YYYYMMDD-<rand6>. Date from clock/now-iso (ISO-8601, first 10 chars =
   YYYY-MM-DD); dashes stripped so the id is a single token."
  []
  (let [date (-> (clock/now-iso) (subs 0 10) (str/replace "-" ""))
        suf  (subs (str (java.util.UUID/randomUUID)) 0 6)]
    (str "ws-" date "-" suf)))

(defn read-ws
  "Read a workstream.edn by project + id. Returns nil if absent."
  [project ws-id]
  (io/read-edn (cstate/workstream-edn-path project ws-id)))

(defn write!
  "Validate then write a Workstream record (atomic; parent dirs created)."
  [w]
  (validate w)
  (io/write-edn! (cstate/workstream-edn-path (:project w) (:id w)) w)
  w)

(defn create!
  "Mint an id and persist a fresh workstream at the given stage. `base` may
   carry :external-refs (default []) and must carry :stage."
  [project {:keys [stage external-refs]}]
  (let [now (clock/now-iso)
        w   {:id            (mint-id)
             :project       project
             :external-refs (vec (or external-refs []))
             :stage         stage
             :stage-history [{:at now :stage stage}]
             :closed        nil
             :created-at    now
             :entries       []}]
    (write! w)))
