(ns nido.coordinator.workstreams-view
  "Pure data layer for the TUI workstreams surface: reads a project's
   workstreams + their coordinator-sessions, projects engagement state, and
   formats display rows. No charm dependencies — the TUI's update/view
   functions consume this. Replaces runs-view + tickets-view as the
   coordination overview."
  (:require
   [clojure.string :as str]
   [nido.coordinator.notion-cache :as notion-cache]
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

(defn- github-ref
  "The workstream's :github-issue external-ref map, or nil."
  [ws]
  (some #(when (= :github-issue (:adapter %)) %) (:external-refs ws)))

(def ^:private ref-adapter-order
  "Display order for external-ref adapters in the UI's links row. An adapter not
   listed here sorts last, in stored order."
  [:notion :github-issue :github :slack-message])

(def ^:private ref-adapter-labels
  "Human key each adapter renders under. Unlisted adapters fall back to their name."
  {:notion        "notion"
   :github-issue  "GitHub issue"
   :github        "PR"
   :slack-message "slack"})

(defn- ref-url
  "Followable URL for one external ref, or nil. A :notion ref persisted before
   :url was carried on the ref falls back to a URL built from its :page-id."
  [{:keys [adapter url page-id]}]
  (or (not-empty url)
      (when (and (= :notion adapter) (not-empty page-id))
        (str "https://www.notion.so/" (str/replace page-id "-" "")))))

(defn ref-links
  "The workstream's followable external refs, display-ordered:
   [{:adapter :label :id :title :url} …]. A ref with no resolvable URL is dropped
   — there is nothing to follow. Pure and nil-safe; never returns nil."
  [ws]
  (let [rank      (into {} (map-indexed (fn [i a] [a i])) ref-adapter-order)
        last-rank (count ref-adapter-order)]
    (->> (:external-refs ws)
         (keep (fn [{:keys [adapter id title] :as ref}]
                 (when-let [url (ref-url ref)]
                   {:adapter adapter
                    :label   (get ref-adapter-labels adapter (name adapter))
                    :id      id
                    :title   (not-empty title)
                    :url     url})))
         ;; sort-by is stable, so same-rank refs keep their stored order
         (sort-by #(get rank (:adapter %) last-rank))
         vec)))

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
   ref; :review-run when it carries a :review-run ref — a workstream minted to
   HOLD a review-loop analysis rather than to do work, which is why it is a
   source of its own: every other bucket names somewhere work arrived FROM, and
   this one names a reading nido made of itself. Else :notion — the
   default/coordinator bucket, so a ref-less coordinator workstream is never
   dropped from every view."
  [ws]
  (cond
    (= :scratch (:stage ws))                                      :scratch
    (some #(= :github-issue (:adapter %)) (:external-refs ws))   :github
    (some #(= :slack-message (:adapter %)) (:external-refs ws))  :slack
    (some #(= :review-run (:adapter %)) (:external-refs ws))     :review-run
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
   1c. GitHub-issue external-ref → the issue title (falling back to repo#number,
       its :id — both read far better than the raw ws-id)
   2. latest ledger entry's :title (when present and non-blank)
   3. originating trigger (from a session's autonomy) + short ws-id suffix
   4. a session name (human one-offs have no ref/entry/trigger — the name reads
      far better than the raw ws-id)
   5. raw ws-id."
  [ws sessions]
  (let [nref        (notion-ref ws)
        sref        (slack-ref ws)
        gref        (github-ref ws)
        entry-title (not-empty (some-> ws :entries last :title))
        trigger     (some #(get-in % [:autonomy :trigger]) sessions)
        sname       (some (comp not-empty :name) sessions)]
    (cond
      nref        (if-let [t (not-empty (:title nref))]
                    (str (:id nref) " · " t)
                    (:id nref))
      sref        (or (not-empty (:title sref)) entry-title (:id ws))
      gref        (or (not-empty (:title gref)) entry-title (:id gref) (:id ws))
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
   (or pass nil) for the legacy substrate-only projection. `facts` is the
   project's Notion page-id → {:status :priority :ball-ids} cache
   (nido.coordinator.notion-cache); nil ⇒ no Notion priority is stamped."
  ([project ws] (workstream-row project ws nil nil))
  ([project ws live-names] (workstream-row project ws live-names nil))
  ([project ws live-names facts]
   (let [sessions       (session/list-sessions project (:id ws))
         eng-sessions   (if live-names (mapv #(reconcile-liveness live-names %) sessions) sessions)
         br-id          (:id (ledger-ref ws))
         local-status   (when br-id (tickets/status project br-id))
         page-id        (:page-id (notion-ref ws))
         notion-backed? (some? (notion-ref ws))
         in-cache?      (boolean (and page-id (contains? facts page-id)))
         shipping?      (and (nil? (:closed ws)) (= :shipping (:stage ws)))
         notion-driven? (and notion-backed? (or in-cache? shipping?))
         proj           (if notion-driven?
                          (session/notion-stage-projection
                            {:shipping?          shipping?
                             :in-flight?         (contains? #{:planning :implementing} local-status)
                             :notion-status      (get-in facts [page-id :status])
                             :triaged?           (= :triaged local-status)
                             :sessions           sessions})
                          (session/stage-projection
                            (:closed ws)
                            local-status
                            sessions (:stage ws)))]
     {:ws-id           (:id ws)
      :project         project
      :br-id           br-id
      ;; promote-id stays Notion-or-GitHub. A Slack inbox workstream IS promotable
      ;; (promote → start-triage!), but its promote decisions (:triaging etc.) are
      ;; reported br-independently, so it needs no promote-id.
      :promote-id      (or (:id (notion-ref ws))
                           (some #(when (= :github-issue (:adapter %)) (:id %)) (:external-refs ws)))
      :label           (label ws sessions)
      :links           (ref-links ws)
      :source          (ws-source ws)
      :stage           (:stage proj)
      :needs-you       (:needs-you proj)
      ;; The local dismiss veto. Notion-driven rows ignore nido :closed AND the
      ;; :dismissed disposition in their projection, so this flag is the ONLY
      ;; carrier of a nido-side dismiss to the board. work/to-spine reads it.
      ;; TWO carriers, because neither alone covers every row: the ticket status
      ;; needs a ledger ref (notion-or-slack) that a ref-less coordinator
      ;; workstream doesn't have, and the :closed outcome is ignored outright by
      ;; the notion-driven projection. reopen! clears :closed, so restore! undoes
      ;; both halves without a second write.
      :dismissed?      (or (= :dismissed local-status)
                           (= :dismissed (get-in ws [:closed :outcome])))
      ;; Notion-driven rows ignore nido :closed for engagement too — else a
      ;; reopened-in-Notion ticket reads :settled and to-spine folds it to :done.
      :engagement      (session/engagement-state (when-not notion-driven? (:closed ws)) eng-sessions)
      :priority        (max-priority sessions)
      :notion-priority (get-in facts [page-id :priority])
      :session-count   (count sessions)
      :last-activity   (last-activity ws sessions)
      :facets          (:facets ws)
      ;; Merge-lane sub-state: only populated when the projected stage is :shipping.
      :ship-substate   (when (= :shipping (:stage proj)) (session/ship-substate sessions))
      :open-findings   (count (:open (:findings ws)))})))

(defn bare-row
  "A display row for a watched-view Notion page with NO nido workstream, so an
   orphan / never-provisioned ticket is visible on the board. Synthetic :ws-id is
   the Notion page-id (stable + unique). Notion-driven stage; no sessions → engagement
   :idle, needs-you false. Read-only: mutations guard on the missing workstream."
  [project page-id {:keys [status priority title br]}]
  (let [tstatus  (when br (tickets/status project br))
        triaged? (= :triaged tstatus)]
    {:ws-id           page-id
     :project         project
     :br-id           br
     :promote-id      br
     :label           (or title br "(untitled)")
     :links           (ref-links {:external-refs [{:adapter :notion
                                                   :id      (or br page-id)
                                                   :title   title
                                                   :page-id page-id}]})
     :source          :notion
     :stage           (session/notion-stage status triaged?)
     :needs-you       false
     :engagement      :idle
     :priority        0
     :notion-priority priority
     :session-count   0
     :last-activity   nil
     :facets          nil
     :ship-substate   nil
     :open-findings   0
     :dismissed?      (= :dismissed tstatus)
     :bare?           true}))

(defn workstream-rows
  "All display rows for a project: one per workstream, PLUS a synthesized bare row
   for each watched-view page (from the Notion cache) that no workstream covers — so
   orphan / never-provisioned tickets are visible. `live-names` is threaded into each
   real row's engagement projection. Builds the page-facts cache once."
  ([project] (workstream-rows project nil))
  ([project live-names]
   (let [facts    (notion-cache/project-page-facts project)
         wss      (keep #(workstream/read-ws project %) (workstream/list-ids project))
         ws-rows  (mapv #(workstream-row project % live-names facts) wss)
         covered  (into #{} (mapcat (fn [w] (remove nil? [(:page-id (notion-ref w))
                                                           (:id (ledger-ref w))]))) wss)
         bare     (->> facts
                       (remove (fn [[page-id fct]]
                                 (or (contains? covered page-id)
                                     (contains? covered (:br fct)))))
                       (mapv (fn [[page-id fct]] (bare-row project page-id fct))))]
     (into ws-rows bare))))

(defn- by-newest
  "Newest-activity first. ISO strings sort lexically = chronologically; a nil
   :last-activity sorts last."
  [rows]
  (vec (sort-by :last-activity #(compare %2 %1) rows)))

(defn- by-needs-then-newest
  "needs-you rows first, newest-activity first within each band."
  [rows]
  (vec (concat (by-newest (filter :needs-you rows))
               (by-newest (remove :needs-you rows)))))

(defn- by-severity
  "Highest priority (severity) first; ties broken by longest-waiting (oldest
   activity at top). This mirrors the executor's pickup order — priority desc,
   then FIFO (executor.clj) — so the list reads top-to-bottom in the order nido
   actually works the triage queue, rather than by most-recently-touched."
  [rows]
  (vec (sort-by (juxt (comp - (fnil :priority 0)) #(or (:last-activity %) "")) rows)))

(defn- by-notion-priority
  "Notion Priority ascending (0 = Release Blocker first); rows without a Notion
   priority sort last; ties broken by severity (autonomy :priority desc) then
   longest-waiting (oldest activity first). Used for the pull queues where the
   human picks what to work next."
  [rows]
  (vec (sort-by (juxt #(or (:notion-priority %) 9999)
                      (comp - (fnil :priority 0))
                      #(or (:last-activity %) ""))
                rows)))

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
     :queued    (by-notion-priority (remove in-flight? rows))}))

(defn grouped-by-stage
  "Partition rows by lifecycle stage for the two-surface board — Intake
   (:triage/:incoming/:dismissed) and Active (:in-progress/:shipping). :done and
   :ready are omitted: :done is done; :ready (triaged, awaiting pickup) is the
   backlog, which lives in Notion, not on the nido board.

   :dismissed needs no special-casing here — work/to-spine has already stamped
   the stage, so a plain group-by collects the band for every origin. It is
   ordered newest-first rather than needs-first because :needs-you is always
   false there."
  [rows]
  (let [by (group-by :stage rows)]
    {:incoming    (by-needs-then-newest (:incoming by []))
     :in-progress (by-needs-then-newest (:in-progress by []))
     :shipping    (by-needs-then-newest (:shipping by []))
     :triage      (triage-split (:triage by []))
     :dismissed   (by-newest (:dismissed by []))}))

(def ^:private live-engagements
  "Engagement states where a session is present/working/awaiting you — the
   'active' band of the Scratch view. :idle and :settled fall to the idle band."
  #{:active :parked-at-gate :queued})

(defn grouped-by-engagement
  "Group scratch rows by liveness for the Scratch view (no lifecycle stage).
   {:active [...] :idle [...]}, each newest-activity first."
  [rows]
  (let [live? #(contains? live-engagements (:engagement %))]
    {:active (by-newest (filter live? rows))
     :idle   (by-newest (remove live? rows))}))

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
