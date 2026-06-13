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

(defn ws-source
  "Source bucket of a workstream, classified from its RAW record (not a projected
   row — workstream-row's :stage is unreliable for scratch). :scratch when the
   stored stage is :scratch (a one-off, set by scratch/birth!); :github when it
   carries a :github-issue ref (Phase 3); else :notion — the default/coordinator
   bucket, so a ref-less coordinator workstream is never dropped from every view."
  [ws]
  (cond
    (= :scratch (:stage ws))                                   :scratch
    (some #(= :github-issue (:adapter %)) (:external-refs ws)) :github
    :else                                                       :notion))

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

(defn- max-priority
  "Highest autonomy :priority across a workstream's sessions (0 when none carry
   one). This is the triage severity — `:triage-teacher-bugs` sets it from the
   ticket's severity-calc, so it drives the same ordering the executor picks by."
  [sessions]
  (->> sessions (keep #(get-in % [:autonomy :priority])) (reduce max 0)))

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
     :source        (ws-source ws)
     :stage         (:stage proj)
     :needs-you     (:needs-you proj)
     :engagement    (session/engagement-state (:closed ws) sessions)
     :priority      (max-priority sessions)
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

(defn- by-severity
  "Highest priority (severity) first; ties broken by longest-waiting (oldest
   activity at top). This mirrors the executor's pickup order — priority desc,
   then FIFO (executor.clj) — so the list reads top-to-bottom in the order nido
   actually works the triage queue, rather than by most-recently-touched."
  [rows]
  (vec (sort-by (juxt (comp - (fnil :priority 0)) #(or (:last-activity %) "")) rows)))

(def triage-in-flight-engagements
  "Engagement states that occupy a triage slot: a session is parked at the gate
   for you, or actively running. Everything else in the triage stage is queued
   backlog waiting for a free slot. Mirrors session/gating-phases."
  #{:parked-at-gate :active})

(defn- triage-split
  "Split the triage band into the in-flight slots (parked/active — capped at the
   trigger's :max-in-flight) and the queued backlog (waiting for a slot). Both
   ordered highest-severity-first. The two are surfaced under separate headers so
   the in-flight count reflects the max-5 model instead of summing the backlog."
  [rows]
  (let [in-flight? #(contains? triage-in-flight-engagements (:engagement %))]
    {:in-flight (by-severity (filter in-flight? rows))
     :queued    (by-severity (remove in-flight? rows))}))

(defn grouped-by-stage
  "Partition rows by lifecycle stage for the overview. :done is intentionally
   omitted — done is done, not shown. Ready/in-progress: needs-you first, then
   newest. Triage is returned as {:in-flight [...] :queued [...]} — see
   triage-split — each ordered highest-severity-first."
  [rows]
  (let [by (group-by :stage rows)]
    {:ready       (by-needs-then-newest (:ready by []))
     :in-progress (by-needs-then-newest (:in-progress by []))
     :triage      (triage-split (:triage by []))}))

(def ^:private live-engagements
  "Engagement states where a session is present/working/awaiting you — the
   'active' band of the Scratch view. :idle and :settled fall to the idle band."
  #{:active :parked-at-gate :queued})

(defn grouped-by-engagement
  "Group scratch rows by liveness for the Scratch view (no lifecycle stage).
   {:active [...] :idle [...]}, each newest-activity first."
  [rows]
  (let [newest (fn [rs] (sort-by :last-activity #(compare %2 %1) rs))
        live?  #(contains? live-engagements (:engagement %))]
    {:active (newest (filter live? rows))
     :idle   (newest (remove live? rows))}))

(defn session-rows
  "Display rows for one workstream's coordinator-sessions, ordered most-recently-
   active first. :phase is nil for human (non-autonomous) sessions."
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
                                   (:created-at s))}))
       ;; newest-active first; nil :last-activity sorts last. Lexical = chronological.
       (sort-by #(or (:last-activity %) "") #(compare %2 %1))
       vec))

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
