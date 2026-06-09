(ns nido.coordinator.workstreams-view
  "Pure data layer for the TUI workstreams surface: reads a project's
   workstreams + their coordinator-sessions, projects engagement state, and
   formats display rows. No charm dependencies — the TUI's update/view
   functions consume this. Replaces runs-view + tickets-view as the
   coordination overview."
  (:require
   [clojure.string :as str]
   [nido.coordinator.session :as session]
   [nido.coordinator.tickets :as tickets]
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
  "One display row for a workstream: reads its sessions and projects engagement
   + lifecycle stage."
  [project ws]
  (let [sessions (session/list-sessions project (:id ws))
        br-id    (:id (notion-ref ws))
        status   (when br-id (tickets/status project br-id))
        proj     (session/stage-projection (:closed ws) status sessions (:stage ws))]
    {:ws-id         (:id ws)
     :project       project
     :br-id         br-id
     :label         (label ws sessions)
     :stage         (:stage proj)
     :needs-you     (:needs-you proj)
     :engagement    (session/engagement-state (:closed ws) sessions)
     :session-count (count sessions)
     :last-activity (last-activity ws sessions)}))

(defn workstream-rows
  "All workstream rows for a project, read from disk."
  [project]
  (->> (workstream/list-ids project)
       (keep #(workstream/read-ws project %))
       (mapv #(workstream-row project %))))

(defn- by-needs-then-newest
  "needs-you rows first, newest-activity first within each band. ISO strings
   sort lexically = chronologically."
  [rows]
  (let [newest (fn [rs] (sort-by :last-activity #(compare %2 %1) rs))]
    (vec (concat (newest (filter :needs-you rows))
                 (newest (remove :needs-you rows))))))

(defn grouped-by-stage
  "Partition rows by lifecycle stage for the overview. :done is intentionally
   omitted — done is done, not shown. Each band: needs-you first, then newest."
  [rows]
  (let [by (group-by :stage rows)]
    {:ready       (by-needs-then-newest (:ready by []))
     :in-progress (by-needs-then-newest (:in-progress by []))
     :triage      (by-needs-then-newest (:triage by []))}))

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

(defn engagement-substatus
  "Short per-item liveness tag shown next to the label inside a stage row."
  [eng]
  (case eng
    :parked-at-gate "parked"
    :active         "running"
    :queued         "queued"
    :idle           "idle"
    :settled        "done"
    "—"))

(defn format-row
  "Display string for a stage-grouped row: `⏸ <label>   <substatus>` (the marker
   is two spaces when the row does not need you)."
  [{:keys [label needs-you engagement]}]
  (format "%s%s   %s"
          (if needs-you "⏸ " "  ")
          (truncate (str label) title-max)
          (engagement-substatus engagement)))

(defn promote-result-message
  "Status-line string for a `promote/promote!` decision on `br`. `:promote`
   confirms the planning leg started; every refusal reads as why it wasn't
   promotable. nil br (no ticket on the workstream) is its own message."
  [br decision]
  (if (nil? br)
    "no ticket on this workstream to promote"
    (case decision
      :promote        (str "promoted " br " → in progress")
      :skip-active    (str br " already promoted")
      :skip-completed (str br " was skipped in triage — nothing to promote")
      :skip-no-record (str br " has no triage record yet")
      :skip-untriaged (str br " isn't triaged yet — not ready to pick up")
      (str "refused " br " — " (name decision)))))

(defn format-session-row
  "Display string for a session row: `<name>  ·  <phase|human>  ·  <weight>  ·  <substrate>`."
  [{:keys [name phase weight substrate]}]
  (format "%s  ·  %s  ·  %s  ·  %s"
          name
          (if phase (clojure.core/name phase) "human")
          (clojure.core/name (or weight :?))
          (clojure.core/name (or substrate :?))))
