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

(defn- slack-ref
  "The workstream's :slack-message external-ref map, or nil."
  [ws]
  (some #(when (= :slack-message (:adapter %)) %) (:external-refs ws)))

(defn ledger-ref
  "The external-ref whose :id keys the per-ticket ledger (`bb nido:ticket:*` :br):
   the :notion ref (BR-####) or the :slack-message ref (slack-<channel>-<ts>).
   Either is the key the triage skill writes its record under."
  [ws]
  (or (notion-ref ws) (slack-ref ws)))

(defn ws-source
  "Source bucket of a workstream, classified from its RAW record (not a projected
   row — workstream-row's :stage is unreliable for scratch). :scratch when the
   stored stage is :scratch (a one-off, set by scratch/birth!); :github when it
   carries a :github-issue ref (Phase 3); :slack when it carries a :slack-message
   ref; else :notion — the default/coordinator bucket, so a ref-less coordinator
   workstream is never dropped from every view."
  [ws]
  (cond
    (= :scratch (:stage ws))                                      :scratch
    (some #(= :github-issue (:adapter %)) (:external-refs ws))   :github
    (some #(= :slack-message (:adapter %)) (:external-refs ws))  :slack
    :else                                                         :notion))

(defn- short-suffix
  "The rand6 tail of a ws-id (segment after the final dash)."
  [ws-id]
  (last (str/split (str ws-id) #"-")))

(defn label
  "Display label for a workstream, resolved by fallback:
   1. Notion external-ref → \"BR-#### · <title>\" (or just BR-#### with no/blank title)
   1b. Slack external-ref → the message text (its :title); the slack-<channel>-<ts>
       id is too noisy to show, and the title IS the report body
   2. latest ledger entry's :title (when present and non-blank)
   3. originating trigger (from a session's autonomy) + short ws-id suffix
   4. a session name (human one-offs have no ref/entry/trigger — the name reads
      far better than the raw ws-id)
   5. raw ws-id."
  [ws sessions]
  (let [nref        (notion-ref ws)
        sref        (slack-ref ws)
        entry-title (not-empty (some-> ws :entries last :title))
        trigger     (some #(get-in % [:autonomy :trigger]) sessions)
        sname       (some (comp not-empty :name) sessions)]
    (cond
      nref        (if-let [t (not-empty (:title nref))]
                    (str (:id nref) " · " t)
                    (:id nref))
      sref        (or (not-empty (:title sref)) entry-title (:id ws))
      entry-title entry-title
      trigger     (str (name trigger) " · " (short-suffix (:id ws)))
      sname       sname
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

(defn- reconcile-liveness
  "A human (non-autonomous) session carries a static :substrate that is never
   synced to real service state, so a downed one-off would read :live — and thus
   :active — forever. Downgrade a human :live session whose name is NOT in
   `live-names` (the set of sessions actually holding ports) to :archived for the
   projection only, so engagement reflects reality. Autonomous sessions are
   coordinator-managed (substrate flips via archive! on Run teardown) and pass
   through untouched."
  [live-names s]
  (if (and (nil? (:autonomy s))
           (= :live (:substrate s))
           (not (contains? live-names (:name s))))
    (assoc s :substrate :archived)
    s))

(defn workstream-row
  "One display row for a workstream: reads its sessions and projects engagement
   + lifecycle stage. `live-names` (optional) is the set of session names actually
   holding ports; when supplied, a human session not in it is treated as down so
   :engagement reflects real liveness rather than the static :substrate. Omit it
   (or pass nil) for the legacy substrate-only projection."
  ([project ws] (workstream-row project ws nil))
  ([project ws live-names]
   (let [sessions     (session/list-sessions project (:id ws))
         eng-sessions (if live-names (mapv #(reconcile-liveness live-names %) sessions) sessions)
         br-id        (:id (ledger-ref ws))
         status       (when br-id (tickets/status project br-id))
         proj         (session/stage-projection (:closed ws) status sessions (:stage ws))]
     {:ws-id         (:id ws)
      :project       project
      :br-id         br-id
      ;; promote-id stays Notion-or-GitHub. A Slack inbox workstream IS promotable
      ;; (promote → start-triage!), but its promote decisions (:triaging etc.) are
      ;; reported br-independently, so it needs no promote-id.
      :promote-id    (or (:id (notion-ref ws))
                         (some #(when (= :github-issue (:adapter %)) (:id %)) (:external-refs ws)))
      :label         (label ws sessions)
      :source        (ws-source ws)
      :stage         (:stage proj)
      :needs-you     (:needs-you proj)
      :engagement    (session/engagement-state (:closed ws) eng-sessions)
      :priority      (max-priority sessions)
      :session-count (count sessions)
      :last-activity (last-activity ws sessions)
      :facets        (:facets ws)
      ;; Merge-lane sub-state: only populated when the projected stage is :shipping.
      :ship-substate (when (= :shipping (:stage proj)) (session/ship-substate sessions))})))

(defn workstream-rows
  "All workstream rows for a project, read from disk. `live-names` (optional) is
   threaded into each row's engagement projection — see workstream-row."
  ([project] (workstream-rows project nil))
  ([project live-names]
   (->> (workstream/list-ids project)
        (keep #(workstream/read-ws project %))
        (mapv #(workstream-row project % live-names)))))

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
   omitted — done is done, not shown. :incoming/ready/in-progress/shipping: needs-you
   first, then newest. Triage is returned as {:in-flight [...] :queued [...]} — see
   triage-split — each ordered highest-severity-first."
  [rows]
  (let [by (group-by :stage rows)]
    {:incoming    (by-needs-then-newest (:incoming by []))
     :ready       (by-needs-then-newest (:ready by []))
     :in-progress (by-needs-then-newest (:in-progress by []))
     :shipping    (by-needs-then-newest (:shipping by []))
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
  "Status-line string for a `promote/promote-workstream!` decision on `br`.
   `br` is the Notion BR-#### id, GitHub issue ref (e.g. \"o/r#42\"), or nil
   for a scratch/slack/ref-less workstream. Inbox→triage decisions
   (:triaging / :skip-not-inbox / :skip-no-trigger) are reported regardless of
   `br`; `:promote` confirms the planning leg; every other refusal reads as why
   it wasn't promotable."
  [br decision]
  (case decision
    :triaging        "started triage"
    :skip-not-inbox  "already picked up — not in the queue anymore"
    :skip-no-trigger "can't start triage — its trigger is gone from triggers.edn"
    (if (nil? br)
      "nothing to promote on this workstream"
      (case decision
        :promote             (str "promoted " br " → in progress")
        :skip-active         (str br " already promoted")
        :skip-completed      (str br " was skipped in triage — nothing to promote")
        :skip-no-record      (str br " has no triage record yet")
        :skip-untriaged      (str br " isn't triaged yet — not ready to pick up")
        :gh-error            (str "couldn't reach GitHub for " br " — try again")
        :skip-not-promotable (str "nothing to promote on " br)
        (str "refused " br " — " (name decision))))))

(defn format-session-row
  "Display string for a session row: `<name>  ·  <phase|human>  ·  <weight>  ·  <substrate>`."
  [{:keys [name phase weight substrate]}]
  (format "%s  ·  %s  ·  %s  ·  %s"
          name
          (if phase (clojure.core/name phase) "human")
          (clojure.core/name (or weight :?))
          (clojure.core/name (or substrate :?))))
