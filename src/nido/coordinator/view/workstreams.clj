(ns nido.coordinator.view.workstreams
  "Pure data layer for the TUI workstreams surface: reads a project's
   workstreams + their coordinator-sessions and formats display rows; where a
   row sits and what it is doing come from status (`nido.coordinator.work`). No charm dependencies — the TUI's update/view
   functions consume this. Replaces runs-view + tickets-view as the
   coordination overview."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [nido.coordinator.source.notion-cache :as notion-cache]
   [nido.coordinator.record.activity :as activity]
   [nido.coordinator.record.phase :as phase]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.standing :as standing]
   [nido.coordinator.record.workstream :as workstream]))

(defn ^{:malli/schema [:=> [:cat :Workstream] [:maybe :map]]}
  notion-ref
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

(defn ^{:malli/schema [:=> [:cat :Workstream] [:vector :map]]}
  ref-links
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

(defn ^{:malli/schema [:=> [:cat :Workstream] [:maybe :map]]}
  ledger-ref
  "The external-ref whose :id keys the per-ticket ledger (`bb nido:ticket:*` :br):
   the :notion ref (BR-####) or the :slack-message ref (slack-<channel>-<ts>).
   Either is the key the triage skill writes its record under."
  [ws]
  (or (notion-ref ws) (slack-ref ws)))

(defn ^{:malli/schema [:=> [:cat :Workstream] :keyword]}
  ws-source
  "Source bucket of a workstream, classified from its record: :scratch when its
   ledger holds the birth entry of a one-off (scratch/birth!); :github when it
   carries a :github-issue ref (Phase 3); :slack when it carries a :slack-message
   ref; :review-run when it carries a :review-run ref — a workstream minted to
   HOLD a review-loop analysis rather than to do work, which is why it is a
   source of its own: every other bucket names somewhere work arrived FROM, and
   this one names a reading nido made of itself. Else :notion — the
   default/coordinator bucket, so a ref-less coordinator workstream is never
   dropped from every view."
  [ws]
  (cond
    (standing/born-scratch? ws)                                   :scratch
    (some #(= :github-issue (:adapter %)) (:external-refs ws))   :github
    (some #(= :slack-message (:adapter %)) (:external-refs ws))  :slack
    (some #(= :review-run (:adapter %)) (:external-refs ws))     :review-run
    :else                                                         :notion))

(defn- short-suffix
  "The rand6 tail of a ws-id (segment after the final dash)."
  [ws-id]
  (last (str/split (str ws-id) #"-")))

(defn- machine-name? [s]
  (boolean (some->> s (re-find #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}"))))

(defn ^{:malli/schema [:=> [:cat :Workstream :any] [:maybe :string]]}
  title-basis
  "The text a generated title summarises, or nil when there is nothing better
   than an id: the newest intent's goal — what the work is FOR, once anyone has
   said — else the source's own title (Notion page, Slack message, GitHub issue),
   else the newest ledger entry's :title, else a session name a person chose.

   A session name carrying a UUID is a machine's (review-analysis-design-loop-<uuid>):
   it names a run, not the work, and a title generated from it is invented.

   A ticket id is never part of it. The board shows the id beside the title, so
   a title that repeated it would say it twice."
  [ws sessions]
  (or (workstream/newest-goal ws)
      (some (comp not-empty :title) [(notion-ref ws) (slack-ref ws) (github-ref ws)])
      (not-empty (some-> ws workstream/newest-record :title))
      (some (comp not-empty :name) (remove #(machine-name? (:name %)) sessions))))

(defn ^{:malli/schema [:=> [:cat :Workstream :any] :string]}
  label
  "Display label for a workstream, resolved by fallback:
   1. the generated :title (see nido.coordinator.lane.titles) — one voice for every
      row, whatever its source; it may lag its basis until the next sweep
   2. the source's own title — Notion page, Slack message text, GitHub issue
      (the slack-<channel>-<ts> id is too noisy to show)
   3. the newest ledger entry recording the work's :title (when present and non-blank) — a
      close or a stage move is not what the work is
   4. a ticket id — Notion BR-####, GitHub repo#number
   5. originating trigger (from a session's autonomy) + short ws-id suffix
   6. a session name (human one-offs have no ref/entry/trigger — the name reads
      far better than the raw ws-id)
   7. raw ws-id.
   A ticket id is never prefixed: the row carries it as :br-id."
  [ws sessions]
  (let [nref        (notion-ref ws)
        sref        (slack-ref ws)
        gref        (github-ref ws)
        entry-title (not-empty (some-> ws workstream/newest-record :title))
        trigger     (some #(get-in % [:autonomy :trigger]) sessions)
        sname       (some (comp not-empty :name) sessions)]
    (or (not-empty (get-in ws [:title :text]))
        (some (comp not-empty :title) [nref sref gref])
        entry-title
        (:id nref)
        (:id gref)
        (when trigger (str (name trigger) " · " (short-suffix (:id ws))))
        sname
        (:id ws))))

(defn- timestamps [ws sessions]
  (concat
   [(:created-at ws)]
   (map :at (:entries ws))
   (mapcat (fn [s]
             (concat (map :at (:substrate-history s))
                     [(:created-at s)]))
           sessions)))

(defn ^{:malli/schema [:=> [:cat :Workstream :any] [:maybe :string]]}
  last-activity
  "Latest ISO-8601 timestamp across the workstream's creation, its ledger entries and each
   session's substrate-history / created-at. ISO strings sort lexically = chronologically. nil
   when nothing is present."
  [ws sessions]
  (->> (timestamps ws sessions) (remove nil?) sort last))

(defn- max-priority
  "Highest autonomy :priority across a workstream's sessions (0 when none carry
   one). This is the triage severity — `:triage-teacher-bugs` sets it from the
   ticket's severity-calc, so it drives the same ordering the executor picks by."
  [sessions]
  (->> sessions (keep #(get-in % [:autonomy :priority])) (reduce max 0)))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] [:maybe :map]]}
  round-progress
  "How far a running round has got, read off the report it keeps as it goes:
   {:round n :phase <keyword or nil> :findings n}, or nil for a report with no
   round yet.

   Both loops that take a claim persist this report on every event, and share
   its outline — rounds, each a sequence of phases — so one reading serves both.
   `:findings` counts what the CURRENT round's judging phase raised (`review`
   for a diff review, per layer; `judge` for a record round), which is what the
   rest of that round is working through. `:phase` is the one still running, nil
   between phases. While a diff review's judging is still running, `:judged` of
   `:of` layers have been read, so a count of nothing so far is not mistaken for
   a clean round."
  [report]
  (when-let [r (last (:rounds report))]
    (let [phases (:phases r)
          judged (filter #(#{"review" "judge"} (:phase %)) phases)
          open   (first (filter #(and (= "running" (:status %)) (seq (:layers %))) judged))]
      (cond->
       {:round    (:round r)
       :phase    (some #(when (= "running" (:status %)) (keyword (:phase %))) phases)
       :findings (+ (reduce + (for [p judged
                                    l (:layers p)
                                    :let [n (:findings l)]
                                    :when (number? n)]
                                n))
                    (reduce + (for [p judged
                                    :let [f (:findings p)]
                                    :when (sequential? f)]
                                (count f))))}
        open (assoc :judged (count (filter #(number? (:findings %)) (:layers open)))
                    :of     (count (:layers open)))))))

(defn- with-progress
  "`doing` with the running round's progress beside it, when it is a claim
   whose report can be read. A report that is missing or mid-write leaves the
   activity as it was: the progress decorates the label and is never why a row
   says something is running."
  [doing]
  (let [path (:report-path doing)]
    (if-let [p (when (and (= :claim (:source doing)) path (fs/exists? path))
                 (try (round-progress (json/parse-string (slurp path) true))
                      (catch Exception _ nil)))]
      (assoc doing :progress p)
      doing)))

(defn- merge-substate
  "How far the merge lane has got with the workstream, from its newest merge run's state, or nil
   when no merge run says."
  [runs]
  (when-let [r (last (filter #(or (= :merge (:mode %)) (= :merge (:trigger %))) runs))]
    (case (:state r)
      :queued                   :queued
      (:running :preprocessing) :driving
      :awaiting-review          :blocked
      :done                     :awaiting-merge
      nil)))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :map]]}
  doing
  "What this workstream is DOING right now — one source, or nil — as
   `{:source :claim|:merge|:session, …}`.

   THREE SOURCES, and the precedence is total so that exactly one ever answers: a merge run and
   an agent's burst read by different callers that never met once described one workstream two
   ways at once.

     over      — nothing. A settled close, or a band of :done, which is how a Notion-driven
                 workstream ends: its ticket goes terminal under it.
     :claim    — a named process holds the workstream's activity lock right now.
     :merge    — the workstream's band is :shipping; the merge run's own state says how far
                 along, or :queued when no merge run says — the lane takes one branch at a time,
                 so waiting for it is a state the board keeps saying.
     :session  — an agent holds its run's lock: `runs` carries each of the workstream's runs
                 with :live? beside it.

   The claim outranks the other two because it is evidence of a process alive at the instant of
   the read. A run parked for review is deliberately not `doing`: it is a gate, and calling it
   activity would report a workstream waiting on a person as one that is busy. `claim` and the
   runs' liveness arrive as ARGUMENTS, so this stays pure."
  [{:keys [closed stage claim runs]}]
  (cond
    (or (some? closed) (= :done stage)) nil

    (some? claim)
    (into {:source :claim}
          (select-keys claim [:kind :run-id :report-path :started-at :pid]))

    (= :shipping stage)
    {:source :merge :phase (or (merge-substate runs) :queued)}

    :else
    (when-let [r (last (filter :live? runs))]
      {:source :session :phase (:state r) :session (:session-name r)})))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId [:maybe :map] [:maybe :keyword]] [:maybe :map]]}
  doing-at
  "What is underway in workstream `ws-id` right now, given the closure and band the caller
   settled on — `doing` with the claim and the runs' liveness read here and the running round's
   progress beside it. The board passes status's band, so what is underway and where the row sits
   are one answer."
  [project ws-id closed stage]
  (with-progress
    (doing {:closed closed
            :stage  stage
            :claim  (activity/read-live project ws-id)
            :runs   (mapv #(assoc % :live? (runs/live? (:id %))) (runs/runs-of project ws-id))})))

(defn- standing-design
  "The design whose plan governs the workstream (`standing/governing-design` — the one
   its gate opens under) when it stands — cleared, as landing asks — or nil. Only a
   phased one is asked, so an unphased row pays no standing read: it has no progress
   whether or not its design stands."
  [project ws-id]
  (when-let [d (standing/governing-design project ws-id)]
    (when (and (seq (:phases d))
               (:cleared? (standing/of-design project ws-id d)))
      d)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :Workstream [:? :any]] :WorkstreamRow]}
  workstream-row
  "One display row for a workstream: what it is and where it came from — label, links, source,
   priorities, activity, open findings. Where it sits on the board, whether it needs a person and
   what is underway are status's to answer, and the board adds them (`work/list-workstreams`).
   `facts` is the project's Notion page-id → {:status :priority :ball-ids} cache
   (nido.coordinator.source.notion-cache); nil ⇒ no Notion priority is stamped."
  ([project ws] (workstream-row project ws nil))
  ([project ws facts]
   (let [sessions (session/list-sessions project (:id ws))
         page-id  (:page-id (notion-ref ws))]
     {:ws-id           (:id ws)
      :project         project
      :br-id           (:id (ledger-ref ws))
      ;; promote-id stays Notion-or-GitHub. A Slack inbox workstream IS promotable
      ;; (promote → start-triage!), but its promote decisions (:triaging etc.) are
      ;; reported br-independently, so it needs no promote-id.
      :promote-id      (or (:id (notion-ref ws))
                           (some #(when (= :github-issue (:adapter %)) (:id %)) (:external-refs ws)))
      :label           (label ws sessions)
      :links           (ref-links ws)
      :source          (ws-source ws)
      :priority        (max-priority sessions)
      :notion-priority (get-in facts [page-id :priority])
      :session-count   (count sessions)
      :last-activity   (last-activity ws sessions)
      :facets          (:facets ws)
      ;; Standing's answer, read off the ledger — the one placement asks. Asked only of a ledger
      ;; holding a findings round: nearly none do, and a board reads ~1000 rows.
      :open-findings   (if (some #(= :findings (:kind %)) (:entries ws))
                         (count (standing/open-findings project (:id ws)))
                         0)})))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:maybe :map]]}
  phase-progress
  "Where workstream `ws-id` is in its standing design's phase plan, nil for an
   unphased one, one whose design no longer stands, or no workstream at all.

   Not part of `workstream-row`: it reads the design's standing, the costliest
   thing a row asks, and every row paid it — ~1000 rows per board refresh, nearly
   all of them in :done, which nothing draws."
  [project ws-id]
  (when-let [ws (workstream/read-ws project ws-id)]
    (phase/progress (standing-design project ws-id) (:entries ws))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :string :map] :WorkstreamRow]}
  bare-row
  "A display row for a watched-view Notion page with NO nido workstream, so an
   orphan / never-provisioned ticket is visible on the board. Synthetic :ws-id is
   the Notion page-id (stable + unique). Its band is the Notion status's: a ticket with
   no workstream holds no triage, so nothing on it is promotable yet. No sessions →
   engagement :idle, needs-you false. Read-only: mutations guard on the missing workstream."
  [project page-id {:keys [status priority title br]}]
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
   :stage           (session/notion-stage status false)
   :needs-you       false
   :engagement      :idle
   :priority        0
   :notion-priority priority
   :session-count   0
   :last-activity   nil
   :facets          nil
   ;; No workstream exists, so nothing can hold a claim against it.
   :doing           nil
   :open-findings   0
   :bare?           true})

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:vector :WorkstreamRow]]}
  workstream-rows
  "All display rows for a project: one per workstream, PLUS a synthesized bare row
   for each watched-view page (from the Notion cache) that no workstream covers — so
   orphan / never-provisioned tickets are visible. Builds the page-facts cache once."
  [project]
  (let [facts   (notion-cache/project-page-facts project)
        wss     (keep #(workstream/read-ws project %) (workstream/list-ids project))
        ws-rows (mapv #(workstream-row project % facts) wss)
        covered (into #{} (mapcat (fn [w] (remove nil? [(:page-id (notion-ref w))
                                                         (:id (ledger-ref w))]))) wss)
        bare    (->> facts
                     (remove (fn [[page-id fct]]
                               (or (contains? covered page-id)
                                   (contains? covered (:br fct)))))
                     (mapv (fn [[page-id fct]] (bare-row project page-id fct))))]
    (into ws-rows bare)))

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
  "Engagement states that occupy a triage slot: waiting at the gate for you, or
   actively running. Everything else in the triage stage is queued backlog waiting
   for a free slot. Mirrors what runs/in-progress-count-by-trigger counts."
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

(defn ^{:malli/schema [:=> [:cat [:vector :WorkstreamRow]] :map]}
  grouped-by-stage
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
     :awaiting-gate (by-needs-then-newest (:awaiting-gate by []))
     :in-progress (by-needs-then-newest (:in-progress by []))
     :shipping    (by-needs-then-newest (:shipping by []))
     :triage      (triage-split (:triage by []))
     :dismissed   (by-newest (:dismissed by []))}))

(def ^:private live-engagements
  "Engagement states where a session is present/working/awaiting you — the
   'active' band of the Scratch view. :idle and :settled fall to the idle band."
  #{:active :parked-at-gate :queued})

(defn ^{:malli/schema [:=> [:cat [:vector :WorkstreamRow]] :map]}
  grouped-by-engagement
  "Group scratch rows by liveness for the Scratch view (no lifecycle stage).
   {:active [...] :idle [...]}, each newest-activity first."
  [rows]
  (let [live? #(contains? live-engagements (:engagement %))]
    {:active (by-newest (filter live? rows))
     :idle   (by-newest (remove live? rows))}))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId [:sequential :map]] [:vector :map]]}
  session-rows
  "Display rows for one workstream's sessions, ordered most-recently-active first, in the state
   the substrate's listing reports and with who drives each in one vocabulary whoever started it:
   :driver is what a session's :claude facet records, :nobody without one; :state is the
   listing's, nil for a session it does not list.

   `listing` is the substrate's rows for this workstream's directories — View may not reach the
   substrate, so its caller reads them and hands them down, each marked :baseline-shown? by the
   check conduct makes. A listed background session no record names gets a row of its own when it
   is so marked: a person's own session in the workstream's home or worktree, which nido could
   adopt. :recorded? says which: only a recorded row's :name is a nido session to open. A recorded
   row's :last-activity is its owning run's newest transition, else its own."
  [project ws-id listing]
  (let [by-id     (into {} (map (juxt :sessionId identity)) listing)
        sessions  (session/list-sessions project ws-id)
        ran-at    (into {} (map (juxt :session-name #(-> % :state-history last :at)))
                        (runs/runs-of project ws-id))
        on-record (set (keep #(get-in % [:claude :session-id]) sessions))
        recorded  (mapv (fn [s]
                          (let [row (get by-id (get-in s [:claude :session-id]))]
                            {:name          (:name s)
                             :project       project
                             :recorded?     true
                             :weight        (:weight s)
                             :substrate     (:substrate s)
                             :state         (:state row)
                             :driver        (or (get-in s [:claude :driver]) :nobody)
                             :held?         (some? (get-in s [:claude :hold]))
                             :last-activity (or (ran-at (:name s))
                                                (some-> s :substrate-history last :at)
                                                (:created-at s))}))
                        sessions)
        unrecorded (for [row listing
                         :when (and (= "background" (:kind row))
                                    (not (on-record (:sessionId row)))
                                    (:baseline-shown? row))]
                     {:name          (or (:name row) (:id row))
                      :project       project
                      :recorded?     false
                      :session-id    (:sessionId row)
                      :state         (:state row)
                      :driver        :nobody
                      :held?         false
                      :last-activity (some-> (:startedAt row) java.time.Instant/ofEpochMilli str)})]
    (->> (concat recorded unrecorded)
         ;; newest-active first; nil :last-activity sorts last. Lexical = chronological.
         (sort-by #(or (:last-activity %) "") #(compare %2 %1))
         vec)))

(def ^:private title-max 52)

(defn- truncate [s n]
  (if (> (count s) n) (str (subs s 0 n) "…") s))

(defn ^{:malli/schema [:=> [:cat :keyword] :string]}
  engagement-substatus
  "Short per-item liveness tag shown next to the label inside a stage row."
  [eng]
  (case eng
    :parked-at-gate "parked"
    :active         "running"
    :queued         "queued"
    :stalled        "stalled"
    :idle           "idle"
    :settled        "done"
    "—"))

(defn ^{:malli/schema [:=> [:cat :WorkstreamRow] :string]}
  format-row
  "Display string for a stage-grouped row: `⏸ <label>   <substatus>` (the marker
   is two spaces when the row does not need you)."
  [{:keys [label needs-you engagement]}]
  (format "%s%s   %s"
          (if needs-you "⏸ " "  ")
          (truncate (str label) title-max)
          (engagement-substatus engagement)))

(def ^:private claim-labels
  "What a held claim is CALLED. Phrased as what someone is doing rather than as
   the activity's name, because the reader of a board is asking what is happening
   and not which subsystem is running."
  {:diff-review    "reviewing the diff"
   :baseline-round "checking the baseline"
   :design-round   "deciding the design"})

(def ^:private claim-nouns
  "What a round of each claim kind is called in a progress line."
  {:diff-review    "review"
   :baseline-round "baseline"
   :design-round   "design"})

(def ^:private phase-labels
  "A running phase, as what the round is doing in it."
  {:review  "reviewing"
   :judge   "judging"
   :warden  "weighing findings"
   :reshape "reshaping"
   :fix     "fixing"
   :amend   "amending"})

(defn- progress-label
  "`review round 2 · fixing · 3 findings` — the round, the phase running in it
   (with how many layers it has read, while judging), and what that round's
   judging raised. The phase is left out between phases."
  [kind {:keys [round phase findings judged of]}]
  (str/join " · "
            (keep identity
                  [(str (get claim-nouns kind (some-> kind name)) " round " round)
                   (when phase (str (get phase-labels phase (name phase))
                                    (when of (str " " judged "/" of))))
                   (str findings " finding" (when (not= 1 findings) "s"))])))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] [:maybe :string]]}
  doing-label
  "One short phrase for what a workstream is doing, or nil when nothing is.

   Display only, and pure — the same string for every surface, which is what
   stops the board and the pane describing one workstream two ways. It renders
   the KIND rather than a generic `busy` on purpose: an absent label then reads
   as `none of the things that can say so are running` rather than as `nothing is
   running`, which is the honest reading while only the review loops take a claim.

   A claim carrying `:progress` (`round-progress`) reads as where its round has
   got — `review round 2 · fixing · 3 findings` — and one without falls back to
   the kind's name.

   An unrecognised kind prints its own name rather than vanishing — a claim
   written by a newer nido should degrade to something a person can act on."
  [{:keys [source kind phase progress]}]
  (case source
    :claim   (if progress
               (progress-label kind progress)
               (or (claim-labels kind) (some-> kind name)))
    :merge   (str "merging" (when phase (str " · " (name phase))))
    :session (str "agent" (when phase (str " · " (name phase))))
    nil))

(defn ^{:malli/schema [:=> [:cat :any :map] :string]}
  promote-result-message
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

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  format-session-row
  "Display string for a session row: `<name>  ·  <phase|human>  ·  <weight>  ·  <substrate>`."
  [{:keys [name phase weight substrate]}]
  (format "%s  ·  %s  ·  %s  ·  %s"
          name
          (if phase (clojure.core/name phase) "human")
          (clojure.core/name (or weight :?))
          (clojure.core/name (or substrate :?))))
