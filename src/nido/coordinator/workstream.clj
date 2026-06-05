(ns nido.coordinator.workstream
  "The workstream: the source-agnostic spine. Owns the append-only log and the
   descriptive workflow stage. Engagement state is NOT stored here — it is a
   projection over this workstream's session records (see nido.coordinator.session).
   See spec docs/superpowers/specs/2026-06-05-workstream-session-model-design.md."
  (:require
   [babashka.fs :as fs]
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

(defn advance-stage!
  "Move a workstream to `new-stage`, appending to :stage-history. No-op (no
   history entry) when already at `new-stage`. Throws if the workstream is
   absent. Returns the updated record."
  [project ws-id new-stage]
  (let [w (or (read-ws project ws-id)
              (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))]
    (if (= new-stage (:stage w))
      w
      (write! (-> w
                  (assoc :stage new-stage)
                  (update :stage-history conj {:at (clock/now-iso) :stage new-stage}))))))

(defn close!
  "Settle a workstream terminally. `outcome` is :done or :dropped. Idempotent
   write of :closed. Returns the updated record."
  [project ws-id outcome]
  (let [w (or (read-ws project ws-id)
              (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))]
    (write! (assoc w :closed {:at (clock/now-iso) :outcome outcome}))))

(defn append-entry!
  "Write an immutable entry file under entries/ and record it in :entries.
   `entry` = {:kind <kw> :session <str>?}. Returns the absolute file path."
  [project ws-id entry content]
  (let [w     (or (read-ws project ws-id)
                  (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))
        seq-n (inc (count (:entries w)))
        fname (format "%04d-%s.md" seq-n (name (:kind entry)))
        rel   (str "entries/" fname)
        abs   (str (fs/path (cstate/workstream-dir project ws-id) rel))]
    (io/write-text! abs content)
    (write! (update w :entries (fnil conj [])
                    (assoc entry :seq seq-n :at (clock/now-iso) :file rel)))
    abs))

(defn list-ids
  "Vector of ws-ids under a project's workstreams dir; [] if none."
  [project]
  (let [d (cstate/workstreams-dir project)]
    (if (fs/exists? d)
      (->> (fs/list-dir d) (filter fs/directory?) (mapv #(str (fs/file-name %))))
      [])))

(defn add-ref!
  "Append an external ref, deduped on (adapter, id). Returns updated record."
  [project ws-id ref]
  (let [w (or (read-ws project ws-id)
              (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))
        dup? (some #(and (= (:adapter %) (:adapter ref)) (= (:id %) (:id ref)))
                   (:external-refs w))]
    (if dup?
      w
      (write! (update w :external-refs (fnil conj []) ref)))))

(defn find-by-ref
  "Scan a project's workstreams for one carrying an external ref matching
   (adapter, external-id). Returns the workstream record or nil."
  [project adapter external-id]
  (->> (list-ids project)
       (keep #(read-ws project %))
       (some (fn [w]
               (when (some #(and (= adapter (:adapter %)) (= external-id (:id %)))
                           (:external-refs w))
                 w)))))
