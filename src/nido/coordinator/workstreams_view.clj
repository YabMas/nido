(ns nido.coordinator.workstreams-view
  "Pure data layer for the TUI workstreams surface: reads a project's
   workstreams + their coordinator-sessions, projects engagement state, and
   formats display rows. No charm dependencies — the TUI's update/view
   functions consume this. Replaces runs-view + tickets-view as the
   coordination overview."
  (:require
   [clojure.string :as str]
   [nido.coordinator.session :as session]
   [nido.coordinator.workstream :as workstream]))

(defn notion-ref
  "The workstream's :notion external-ref map, or nil."
  [ws]
  (some #(when (= :notion (:adapter %)) %) (:external-refs ws)))

(defn- short-suffix
  "The rand6 tail of a ws-id (segment after the final dash)."
  [ws-id]
  (last (str/split (str ws-id) #"-")))

(defn label
  "Display label for a workstream, resolved by fallback:
   1. Notion external-ref → \"BR-#### · <title>\" (or just BR-#### with no/blank title)
   2. latest ledger entry's :title (when present and non-blank)
   3. originating trigger (from a session's autonomy) + short ws-id suffix
   4. raw ws-id."
  [ws sessions]
  (let [nref        (notion-ref ws)
        entry-title (not-empty (some-> ws :entries last :title))
        trigger     (some #(get-in % [:autonomy :trigger]) sessions)]
    (cond
      nref        (if-let [t (not-empty (:title nref))]
                    (str (:id nref) " · " t)
                    (:id nref))
      entry-title entry-title
      trigger     (str (name trigger) " · " (short-suffix (:id ws)))
      :else       (:id ws))))

(defn- timestamps [ws sessions]
  (concat
   (map :at (:stage-history ws))
   (mapcat (fn [s]
             (concat (map :at (:substrate-history s))
                     (map :at (get-in s [:autonomy :phase-history]))
                     [(:created-at s)]))
           sessions)))

(defn last-activity
  "Latest ISO-8601 timestamp across the workstream's stage-history and each
   session's substrate-history / autonomy phase-history / created-at. ISO
   strings sort lexically = chronologically. nil when nothing is present."
  [ws sessions]
  (->> (timestamps ws sessions) (remove nil?) sort last))

(defn workstream-row
  "One display row for a workstream: reads its sessions and projects engagement."
  [project ws]
  (let [sessions (session/list-sessions project (:id ws))]
    {:ws-id         (:id ws)
     :project       project
     :label         (label ws sessions)
     :stage         (:stage ws)
     :engagement    (session/engagement-state (:closed ws) sessions)
     :session-count (count sessions)
     :last-activity (last-activity ws sessions)}))

(defn workstream-rows
  "All workstream rows for a project, read from disk."
  [project]
  (->> (workstream/list-ids project)
       (keep #(workstream/read-ws project %))
       (mapv #(workstream-row project %))))

(defn grouped-workstreams
  "Partition rows by engagement, newest-activity first within each group.
   :settled is capped at the 10 most recent (mirrors runs-view/tickets-view)."
  [rows]
  (let [by     (group-by :engagement rows)
        newest (fn [rs] (vec (sort-by :last-activity #(compare %2 %1) rs)))]
    {:parked  (newest (:parked-at-gate by []))
     :active  (newest (:active by []))
     :queued  (newest (:queued by []))
     :idle    (newest (:idle by []))
     :settled (vec (take 10 (newest (:settled by []))))}))

(defn session-rows
  "Display rows for one workstream's coordinator-sessions. :phase is nil for
   human (non-autonomous) sessions."
  [project ws-id]
  (->> (session/list-sessions project ws-id)
       (mapv (fn [s]
               {:name          (:name s)
                :project       project
                :phase         (get-in s [:autonomy :phase])
                :weight        (:weight s)
                :substrate     (:substrate s)
                :last-activity (or (some-> s :autonomy :phase-history last :at)
                                   (some-> s :substrate-history last :at)
                                   (:created-at s))}))))

(def ^:private title-max 52)

(defn- truncate [s n]
  (if (> (count s) n) (str (subs s 0 n) "…") s))

(defn format-row
  "Display string for a workstream row: `<label>  ·  <stage>  ·  N session(s)`."
  [{:keys [label stage session-count]}]
  (format "%s  ·  %s  ·  %d session%s"
          (truncate (str label) title-max)
          (name (or stage :?))
          (int session-count)
          (if (= 1 session-count) "" "s")))

(defn format-session-row
  "Display string for a session row: `<name>  ·  <phase|human>  ·  <weight>  ·  <substrate>`."
  [{:keys [name phase weight substrate]}]
  (format "%s  ·  %s  ·  %s  ·  %s"
          name
          (if phase (clojure.core/name phase) "human")
          (clojure.core/name (or weight :?))
          (clojure.core/name (or substrate :?))))
