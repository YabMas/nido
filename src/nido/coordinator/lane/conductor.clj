(ns nido.coordinator.lane.conductor
  "Who decides what a workstream's seats do next, and what reaches the person.

   HIDES what a hand-back is. A turn end returns to whoever answers for the instruction it ended,
   as conduct recorded when it queued it: one recorded as given for a Run is that Run's, and boot
   settles it; every other turn end of a session on an activated workstream but its conductor's
   is the conductor's — one ending an instruction nido gave (a seat message, a decided
   instruction, an occupant's brief, a gate reply no Run awaits), and one ending none, a person's
   turn, marked as such. Each act of the person's recorded on the workstream is a hand-back too.
   Every hand-back is posted once to the conductor through the seat-message log, carrying the
   position the pipeline derives at that moment, and answered by exactly one :conduct-decision —
   the conductor's, or an escalation nido writes when the conductor turn it was delivered for ends
   leaving it unanswered. An escalation decided on a person's turn is answered in place: by the
   person's next prompt into that session.

   Also the conductor's lifecycle against its workstream's: a workstream is WORKED while a person
   has typed a prompt into one of its sessions, or started it (its :activated entry, or their
   :in-progress choice), within the last day (`session/worked-window-ms`) —
   a prompt nido sent, or one the harness delivered from another agent, is no person's, so an
   occupant nido drives, a spawned child and a Run make nothing worked by their own turns — and
   every activated workstream has one conductor, started by a conduct pass while the workstream
   owes it a hand-back and its session is not running — a person's turn in a worked session is
   itself one — and retired once the workstream owes it none and is closed or no longer
   worked. It is briefed from the
   records and an escalation guidance document a person edits without changing code.

   And what a stop becomes. Work that stalled on an activated workstream — its agent owing the move,
   nothing working — is one more hand-back, posted once per turn end after a grace, for the
   conductor to decide like any other. A dialog a driven session stands on is the one stop nido
   states as a question itself: only someone at that session can answer it, so it goes to the
   person as a :blocker carrying :stop, which nido answers once the dialog closes (`stops!`). A
   turn whose agent process is gone is the other: nobody but a person at that session can resume
   it, and nido does not know why it exited, so it too is a :blocker carrying :stop, with the
   command that resumes the conversation — nido starts nothing.

   And that a start carries its session: the first session a person's start or a Run's start
   brings up on a workstream whose generation fixes no ids becomes its whole seat's occupant.

   Which commands are a person's act is this namespace's; how a command is read for what it does
   is the commit gate's."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.coordinator.agent.substrate :as substrate]
   [nido.coordinator.lane.drain :as drain-lane]
   [nido.coordinator.lane.drive :as drive]
   [nido.coordinator.lane.embodied-strata :as embodied]
   [nido.coordinator.lane.pipeline :as pipeline]
   [nido.coordinator.lane.seats :as seats]
   [nido.coordinator.report :as report]
   [nido.coordinator.report.ask :as ask]
   [nido.coordinator.record.drain :as drain]
   [nido.coordinator.record.generation :as generation]
   [nido.coordinator.record.seats :as rseats]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.session-events :as session-events]
   [nido.coordinator.record.standing :as standing]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.source.drain :as source-drain]
   [nido.platform.core :as core]
   [nido.platform.io :as io]
   [nido.platform.process :as process]
   [nido.platform.task-args :as task-args]
   [nido.session.commit-gate :as commit-gate]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.state :as sstate]))

;; ── reading the workstream ──────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat] [:or [:set [:tuple :string :string]] [:enum :unread]]]}
  prompted
  "The sessions a person typed a prompt in within the last `session/worked-window-ms`, as
   #{[project session-name]} — what worked? reads — or :unread when the prompt log could not be
   read. One conduct pass reads it once and hands it to conductors! and every reseat!; on :unread
   neither decides anything, since a log nobody could read says nothing about who typed."
  []
  (try
    (session/prompted-sessions
     (session-events/prompts-since (- (System/currentTimeMillis) session/worked-window-ms)))
    (catch Exception e
      (binding [*out* *err*] (println "nido conductor: reading where a person typed failed —" (ex-message e)))
      :unread)))

(defn- started-within-window?
  "Whether the ledger records a person's start of the workstream (`standing/person-started-at`)
   within the last `session/worked-window-ms`. The ledger is read only when its entries directory
   changed within the window: every workstream is asked on every pass, and reading them all costs
   seconds, while a start inside the window always moved that directory."
  [project ws-id]
  (let [since (- (System/currentTimeMillis) session/worked-window-ms)
        dir   (cstate/ws-entries-dir project ws-id)]
    (boolean
     (and (fs/exists? dir)
          (> (.toMillis (fs/last-modified-time dir)) since)
          (when-let [at (some-> (ws/read-ws project ws-id) standing/person-started-at)]
            (> (.toEpochMilli (java.time.Instant/parse at)) since))))))

(defn- worked?
  "Whether a person worked the workstream within the window `prompted` was read over: typed a
   prompt into one of its sessions — its conductor's included, since talking to the conductor is
   work — or started it (`started-within-window?`). Nothing nido starts or sends makes a
   workstream worked, and nothing worked long ago keeps it so."
  [project ws-id prompted]
  (boolean (or (some #(prompted [(name project) (:name %)]) (session/list-sessions project ws-id))
               (started-within-window? project ws-id))))

(defn- conductor-of
  "The workstream's conductor record, or nil."
  [project ws-id]
  (first (filter :conductor (session/list-sessions project ws-id))))

(defn- position
  "Where the pipeline places the workstream now, as {:position <kw> :next <map or nil> :owes <the
   owed move's identity, or nil>}."
  [project ws-id]
  (let [{:keys [at next owes]} (pipeline/of project ws-id)]
    {:position (or at :unplaceable) :next next :owes owes}))

;; ── carrying a start ────────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :SessionName [:? [:maybe :string]] [:? [:maybe :string]] [:? [:maybe [:enum :start]]]] :map]}
  carry!
  "Carry the workstream a start just brought `session-name` up on: when its current generation fixes
   no ids and its whole seat has no occupant, that session becomes the whole seat's occupant,
   driven by nido — its first instruction `brief` when the start was given one, on `model` when
   given, given by `given-by` (:start when the start was a person's or a spawn's not made on the
   conductor's instruction, `starter`; nil: the conductor answers for it) — and otherwise
   nothing changes. A person's start (:start) on a workstream not activated is not carried
   (:not-activated): it stays a plain session, and only activation hands the recipe to nido. Called by the session-up verb, spawn, the work plane's start and the daemon's
   start of a Run's conversation, never by birth! or the orphan sweep. Answers {:carried {:seat
   :session}} or {:carried nil :why <reason>}; never throws for a seat it does not take."
  [project ws-id session-name & [brief model given-by]]
  (let [whole (first (filter :whole (rseats/seats project ws-id)))]
    (cond
      (nil? whole)
      {:carried nil :why :fixes-ids}

      (and (= :start given-by) (not (some-> (ws/read-ws project ws-id) standing/activated?)))
      {:carried nil :why :not-activated}

      (seq (seats/occupants project ws-id))
      {:carried nil :why :occupied}

      :else
      (try
        {:carried (seats/occupy! project ws-id
                                 (cond-> (seats/default-embodiment)
                                   brief    (update :practice str "\n\nYour first instruction:\n\n" brief)
                                   model    (assoc :model model)
                                   given-by (assoc :given-by given-by))
                                 (:id whole) session-name)}
        (catch clojure.lang.ExceptionInfo e
          (if (#{:occupied :not-free :fixes-ids :closed} (:reason (ex-data e)))
            {:carried nil :why (:reason (ex-data e))}
            (throw e)))))))

;; ── the conductor's brief and lifecycle ─────────────────────────────────────

(defn- guidance-path [project]
  (str (fs/path (fs/parent (cstate/workstreams-dir project)) "conductor-guidance.md")))

(defn- guidance
  "The escalation guidance: the project's own `conductor-guidance.md` beside its workstreams when
   it has one, else the one nido ships. Either is edited without changing code."
  [project]
  (let [own (guidance-path project)]
    (slurp (if (fs/exists? own)
             own
             (str (fs/path (core/nido-source-dir) "resources" "conductor" "escalation-guidance.md"))))))

(defn- seats-line [seats]
  (str/join ", " (map #(if (:whole %) (str "the whole seat (" (:id %) ")") (:id %)) seats)))

(defn- design-line [design]
  (if design (str "entry " (:seq design) " — " (:summary design)) "none yet"))

(defn- brief
  "The conductor's brief at a start: the seats, the governing design and the position as the
   records give them at that start, the escalation guidance (when to escalate), and the asking
   guidance for an escalation (how to word one). A drain's conductor — its coordinator — is
   briefed instead with lane-drain's coordinator brief, composed from the drain's records alone,
   and the same two guidances."
  [project ws-id]
  (if (some-> (ws/read-ws project ws-id) drain/drain?)
    (str (drain-lane/coordinator-brief project ws-id) "\n\n" (guidance project) "\n\n" (ask/guidance :escalation))
  (let [design (standing/governing-design project ws-id)
        {:keys [position next]} (position project ws-id)]
    (str "You conduct workstream " ws-id " of " (name project) ". You occupy no seat and write no code. "
         "Each hand-back nido gives you — a turn nido gave that ended, a person's turn in one of its "
         "sessions (marked as the person's: they are steering it), word the workstream's chat passes "
         "on from the person talking there, or an act of the person's — you "
         "answer with exactly one decision, recorded with `bb nido:seats:decide :hand-back <id>` — "
         "the id on that hand-back's `Hand-back id:` line, never one read off the log — and "
         "either `:seat <id> :text \"…\"` (or `:instruct '[{:seat … :text …} …]'` for several seats) "
         "or `:escalate \"…?\"`, the one-sentence question the person is needed for, with "
         "`:summary \"…\"` (the context) and `:after \"…\"` (what happens once they answer), "
         "`:recommendation \"…\"` only when you genuinely hold a view on the answer, and "
         "`:options '[{:label … :summary … :consequence … :recommended? true} …]'` when it names "
         "the branches. An option that changes the goal carries `:grants [:intent]`: picking it lets "
         "the seat write that one goal amendment itself, so instruct the seat to, and do not hand "
         "the person a command to run. An option that lands the workstream's PR carries "
         "`:grants [:merge]`: picking it — or the person saying so through the chat, which records "
         "the same grant — lets the seat land its own PR once, with `nido:workstream:pr:land`, so "
         "instruct the seat to land it, and never hand the person a merge command. Nothing else can "
         "be granted: closing stays the person's. No option approves a design: a design is built "
         "once its decision round proceeds, and a person's answer to a design question is read by "
         "the next round.\n\n"
         "Seats: " (seats-line (rseats/seats project ws-id)) "\n"
         "Governing design: " (design-line design) "\n"
         "Position: " (name position) (when next (str ", next " (pr-str next))) "\n\n"
         "Decide on the position, governing design and seats each hand-back carries, read from the "
         "records at that hand-back, not on this brief or what you remember of this conversation.\n\n"
         (guidance project) "\n\n"
         (ask/guidance :escalation)))))

(defn- running?
  "Whether the conductor record `c` has a conversation running or a start in flight."
  [c live]
  (boolean (or (:dispatching c)
               (when-let [sid (get-in c [:claude :session-id])]
                 (and (not= :retired (get-in c [:claude :driver])) (:pid (get live sid)))))))

(defn- needs-retiring?
  "Whether the conductor record `c`, of a workstream closed or not worked, still has a process or
   an up session."
  [project c live]
  (or (running? c live)
      (try (let [wt (:wt-path (lifecycle/session-coords (:name c) {:project (name project)}))]
             (sstate/session-live? (get (sstate/read-registry) (str wt))))
           (catch Throwable _ false))))

(defn- owes-hand-back?
  "Whether the workstream `w` owes its conductor a hand-back: one posted after its latest :closed
   entry — ever, when it has none — that no :conduct-decision answers. Reads the seat-message log
   first and the decisions only when such a hand-back is there."
  [project ws-id w]
  (let [closed-at (some->> (:entries w) (filter #(= :closed (:kind %))) last :at)
        after?    (fn [m] (or (nil? closed-at)
                              (try (.isAfter (java.time.Instant/parse (str (:at m)))
                                             (java.time.Instant/parse (str closed-at)))
                                   (catch Exception _ true))))
        posted    (filter after? (seats/hand-backs project ws-id))]
    (boolean (when (seq posted)
               (let [answered (into #{} (map :hand-back) (ws/entries-of project ws-id :conduct-decision))]
                 (some #(not (answered (:id %))) posted))))))

(defn- drain-owed?
  "Whether open drain `w`'s coordinator is owed a turn: a hand-back no decision answers, a wake not
   yet delivered (`drain-lane/wake-pending?`), or — chartered and holding no recipe nor a pending
   proposal — its first proposal."
  [project ws-id w]
  (or (owes-hand-back? project ws-id w)
      (drain-lane/wake-pending? project ws-id)
      (and (nil? (drain/version w)) (empty? (drain/pending-proposals w)))))

(defn- class-act
  "What a conduct pass does with the conductor of drain or slice `w` — :started, :retired or nil —
   or ::standard for a workstream that is neither. A drain's coordinator is started while the
   drain is open and owes it a turn (`drain-owed?`), worked or not, and retired once it closes; a
   slice never has one."
  [project ws-id c w live]
  (cond
    (drain/drain? w) (cond
                       (ws/closure w) (when (and c (needs-retiring? project c live)) :retired)
                       (and (not (and c (running? c live))) (drain-owed? project ws-id w)) :started)
    (drain/slice? w) (when (and c (needs-retiring? project c live)) :retired)
    :else ::standard))

(defn ^{:malli/schema [:=> [:cat :ProjectName [:? [:or [:set [:tuple :string :string]] [:enum :unread]]]] [:sequential :map]]}
  conductors!
  "On every conduct pass: start, on its one conductor record and its brief, the coordinator of
   each open drain of `project` whose session is not running and that is owed something
   (`drain-owed?`), worked or not, and retire that of a closed one — and never start one on a
   slice. For every other workstream: start the conductor of each activated workstream
   (`standing/activated?`) whose conductor's session is not running and that owes its conductor a
   hand-back (`owes-hand-back?`), open or closed — and only then, since a conductor's turn is one
   answer to a hand-back and ends with it; and retire the conductor, while it still has a process
   or an up session, of each workstream that owes it no hand-back and is closed, unworked — no
   person typed into one of its sessions within the window `prompted` was read over (default: read
   now) — or not activated. A ticket a person settled in Notion closes its workstream here as the
   pipeline places it (`pipeline/settled-in-notion`). A workstream not activated gets none, and one
   that owes a hand-back keeps its own. A pass whose prompt log is :unread starts and retires none
   of these, though it still keeps every drain's coordinator. Answers [{:ws-id :started|:retired
   :session}] for what it did; a failure is logged and the rest carried on with."
  [project & [prompted-sessions]]
  (let [live     (delay ((requiring-resolve 'nido.coordinator.lane.conduct/live-states) {}))
        typed-in (or prompted-sessions (prompted))
        unread?  (= :unread typed-in)
        drains   (set (source-drain/open-drains project))]
     (vec
     ;; Sessions and the seat-message log are read first, and a ledger only where they leave a
     ;; conductor to start or retire or the workstream is an open drain: reading every ledger
     ;; costs seconds a pass.
     (for [ws-id (ws/list-ids project)
           :let  [c (conductor-of project ws-id)]
           :when (or c (drains ws-id) (seq (seats/hand-backs project ws-id)))
           :let  [w        (ws/read-ws project ws-id)
                  by-class (when w (class-act project ws-id c w @live))
                  on?      (and w (standing/activated? w) (not (standing/paused? w)))
                  owes     (and on? (owes-hand-back? project ws-id w))
                  act      (cond
                             (nil? w) nil
                             (not= ::standard by-class) by-class
                             unread? nil
                             owes
                             (when-not (and c (running? c @live)) :started)
                             ;; Owed nothing, a conductor is never started: its turn would read the
                             ;; brief, find no hand-back and end, and the next pass would start it
                             ;; again — a conversation a minute while a person works the workstream.
                             (or (ws/closure w) (pipeline/settled-in-notion project ws-id w)
                                 (not (worked? project ws-id typed-in)) (not on?))
                             (when (and c (needs-retiring? project c @live)) :retired))]
           :when act
           :let  [r (try
                      (case act
                        :started (seats/conductor! project ws-id (brief project ws-id))
                        :retired (seats/retire-conductor! project ws-id))
                      (catch Throwable t
                        (binding [*out* *err*]
                          (println (str "nido conductor: " (name act) " on " ws-id " failed — " (ex-message t))))
                        nil))]
           :when r]
       (assoc r :ws-id ws-id :act act)))))

(declare deciding open-blockers)

(defn- carried?
  "Whether any session record of the workstream ever occupied one of its seats."
  [project ws-id]
  (boolean (some #(or (:seat %) (:released %)) (session/list-sessions project ws-id))))

(defn- answer-filled!
  "Answer, as nido, each open blocker that names a seat which could not be started, once every
   current seat is held: what it asked of the person no longer stands. Answers the seqs answered."
  [project ws-id]
  (deciding
   project ws-id
   #(vec (for [b (open-blockers project ws-id)
               ;; Both wordings: blockers written before questions were one sentence still stand.
               :when (re-matches #"(?:seat .+ could not be started|How should seats? .+ be carried, now that (?:it|they) could not be started\?)"
                                 (report/blocker-question-text b))]
           (do (ws/append-entry! project ws-id {:kind :blocker-answered}
                                 (pr-str {:format :blocker-answered :blocker-seq (:seq b) :resumed nil :by :nido
                                          :summary "the seat it named has since been occupied"}))
               (:seq b))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId [:? [:or [:set [:tuple :string :string]] [:enum :unread]]]] :map]}
  reseat!
  "On every conduct pass, for a carried workstream that is worked as conductors! reads it, over
   `prompted-sessions` when the pass read them (default: read now) — so one nobody types into is
   left as it is: retire each occupant whose seat
   the current seats do not include, and occupy each current seat with the embodiment that carries it — the
   default embodiment while the generation fixes no ids, the strata embodiment while it fixes
   strata — escalating, by name, seats no embodiment carries and a seat whose start failed in this
   pass, and answering that escalation as nido once every seat is held. A closed workstream, and
   one not activated, is left as it is. A slice is reseated while its drain is open, worked or
   not and carried or not — a start whose seat failed after the slice was made has never been
   carried — its whole seat carried by a session briefed from its drain's recipe. Answers {:retired
   :occupied :escalated}."
  [project ws-id & [prompted-sessions]]
  (let [w      (delay (ws/read-ws project ws-id))
        slice? (delay (and @w (drain/slice? @w) (not (ws/closure @w))
                           (when-let [dw (some->> (drain/drain-of @w) (ws/read-ws project))]
                             (nil? (ws/closure dw)))))]
  (if-not (or @slice?
              (and (carried? project ws-id)
                   (let [typed-in (or prompted-sessions (prompted))]
                     (and (not= :unread typed-in) (worked? project ws-id typed-in)))
                   (and @w (standing/activated? @w) (not (ws/closure @w)))))
    {}
    (let [{:keys [retired]} (seats/reseat! project ws-id)
          current  (rseats/seats project ws-id)
          held     (seats/held project ws-id)
          empty    (remove #(held (:id %)) current)
          ids      (mapv :id empty)
          ;; Once while it stands: not again while this generation's blocker naming it is open.
          escalate (fn [needs ^Throwable e]
                     (deciding
                      project ws-id
                      #(when-not (some (fn [b] (= [needs] (report/asked-questions b))) (open-blockers project ws-id))
                         (drive/park! project ws-id
                                      {:stage   :reseat :outcome :uncarried
                                       :summary (str "The seats of this generation cannot be occupied: " (ex-message e))
                                       :needs   needs
                                       :recommendation (str "Fix what stopped the seat starting — the error above — "
                                                            "and let nido retry it.")
                                       :after   (str "nido tries the seat again on every conduct pass, and answers "
                                                     "this itself once every seat is held.")})))
                     {:retired retired :escalated ids})]
      (cond
        (empty? empty)
        (do (answer-filled! project ws-id) {:retired retired})

        ;; A launch that fails is named by an escalation in the pass it failed in, and tried again
        ;; on the next; once one succeeds, that escalation is answered.
        (:whole (first current))
        (try
          (let [occupied (if @slice?
                           (drain-lane/occupy-slice! project ws-id)
                           (seats/occupy! project ws-id (seats/default-embodiment) (:id (first current))))]
            (answer-filled! project ws-id)
            {:retired retired :occupied [occupied]})
          (catch Exception e
            (escalate (str "How should seat " (str/join ", " ids) " be carried, now that it could not be started?") e)))

        :else
        (try
          (let [occupied (:occupied (embodied/carry! project ws-id))]
            (answer-filled! project ws-id)
            {:retired retired :occupied occupied})
          ;; A refusal stands until the design changes; a failed launch is the same escalation
          ;; the whole seat's is, answered once a later pass holds every seat.
          (catch Exception e
            (escalate (if (embodied/refused? e)
                        (str "Which embodiment should carry seats " (str/join ", " ids) ", which none carries now?")
                        (str "How should seats " (str/join ", " ids) " be carried, now that they could not be started?"))
                      e))))))))

;; ── hand-backs ──────────────────────────────────────────────────────────────

(defn- hand-back-text
  [{:keys [what turn session seat said word position next design seats]}]
  (str "Hand-back: " what "\n"
       "Session: " session (when seat (str " (seat " seat ")")) "\n"
       (when (= :person turn)
         "Turn: the person's — nido gave this turn nothing; the person is steering this session\n")
       "Position: " (name position) (when next (str ", next " (pr-str next))) "\n"
       "Governing design: " (design-line design) "\n"
       "Seats: " (seats-line seats) "\n"
       (when said (str "\nIts last message:\n\n" said "\n"))
       (when word (str "\nWhat the chat passes on:\n\n" word "\n"))))

(defn- conducted-by
  "The workstream whose conductor answers `ws-id`'s hand-backs: a slice's drain, whose coordinator
   reaches a slice only by deciding them; otherwise `ws-id` itself. Answers [ws-id slice-or-nil]."
  [project ws-id]
  (let [w (ws/read-ws project ws-id)]
    (if-let [d (and w (drain/slice? w) (drain/drain-of w))]
      [d ws-id]
      [ws-id nil])))

(defn- post-hand-back!
  "Post one hand-back to the conductor that answers the workstream's hand-backs (`conducted-by`)
   under `key`, carrying the position the pipeline derives now and the governing design and seats
   as the records give them now — so a conductor started under an earlier design decides on this
   one. A person's turn (`:turn :person`) is marked so on the message, with the conversation it ran
   in; a slice's names the slice, so its coordinator can tell which slice it answers. A key
   already logged answers that message without deriving anything: `answers!` offers every act the
   person ever recorded on every pass, and deriving the position for each costs the coordinator's
   loop minutes."
  [project ws-id key fields]
  (let [[to slice] (conducted-by project ws-id)]
    (or (seats/posted project to key)
        (let [pos (position project ws-id)
              msg (merge fields pos {:design (standing/governing-design project ws-id)
                                     :seats  (rseats/seats project ws-id)})]
          (seats/post! project to (cond-> (merge {:key key :kind :hand-back :to :conductor
                                                  :text (str (when slice (str "Slice: " slice "\n")) (hand-back-text msg))
                                                  :session (:session fields) :position (:position pos)}
                                                 (select-keys fields [:turn :conversation]))
                                    slice (assoc :slice slice)))))))

(defn- conducted?
  "Whether `w` has a conductor to read its hand-backs: activated, or a slice, whose drain's
   coordinator reads them."
  [w]
  (boolean (and w (or (standing/activated? w) (drain/slice? w)))))

(defn ^{:malli/schema [:=> [:cat :map] [:sequential :map]]}
  hand-backs!
  "From one conduct pass's report, log to an activated workstream's conductor each turn end of a
   session on it other than the conductor, faceted or not, that ended no instruction recorded for
   a Run — once per turn end — with the session, its seat when it has one, the turn's last
   message, the position the pipeline derives at that moment, and the governing design and seats
   as the records give them then. One that ended an instruction nido gave is keyed on that
   instruction's token; one that ended none is a person's turn, marked as such and keyed on its
   conversation and the Stop's time. A workstream not activated has no conductor to read it, and
   is logged nothing; nor is a turn end of the workstream's chat, whose word reaches the conductor
   only when the chat passes it on (`tell!`). Answers the hand-backs posted."
  [{:keys [turn-ends]}]
  (let [conducted? (memoize (fn [project ws-id] (conducted? (ws/read-ws project ws-id))))]
    (vec
     (for [{:keys [project ws-id session event instruction run conversation]} turn-ends
           :when (nil? run)
           :let  [s (session/read-session project ws-id session)]
           :when (and s (not (:conductor s)) (not (:chat s)) (conducted? project ws-id))]
       (if instruction
         (post-hand-back! project ws-id (str "turn:" session ":" (:token instruction))
                          {:what "a turn nido gave ended" :session session :seat (get-in s [:seat :id])
                           :said (:last-message event)})
         (post-hand-back! project ws-id (str "person-turn:" (or conversation session) ":" (:at event))
                          {:what "a person's turn ended" :turn :person :session session
                           :conversation conversation :seat (get-in s [:seat :id])
                           :said (:last-message event)}))))))

(defn- session-here
  "The name of the nido session this process runs in — its worktree or its home — or nil."
  []
  (let [cwd (System/getProperty "user.dir")]
    (or (some-> (lifecycle/session-from-cwd cwd) :session)
        (second (lifecycle/session-home-coords-from-cwd cwd)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :string [:? [:maybe [:sequential :keyword]]]] :map]}
  tell!
  "Pass `text` on to the workstream's conductor from the workstream's chat, which this must run
   in: posted once as a hand-back naming the chat's session, which the conductor answers with one
   decision like any other. It is not marked a person's turn: an escalation decided on it waits
   for the person's answer as any blocker does, and no later chat message answers it in place.
   `grants`, when given, are acts the person's word hands the seats (report/grantable-kinds): the
   word is first recorded as a :grant holding them and the text, and the hand-back is that act's,
   so it is posted once. The chat passes them because the person said so; nothing here reads the
   text for one. Refused from any other session or from outside every session (:not-the-chat), on
   a workstream that is not activated, which nothing conducts (:not-activated), on a closed one
   (:closed), for blank text (:no-text), and for a grant of an act no grant may hand
   (:not-grantable). Answers the logged message."
  ([project ws-id text] (tell! project ws-id text nil))
  ([project ws-id text grants]
   (let [n      (session-here)
         s      (when n (session/read-session project ws-id n))
         w      (ws/read-ws project ws-id)
         grants (some-> (seq grants) distinct vec)]
     (cond
       (not (:chat s))
       (throw (ex-info (str "Only workstream " ws-id "'s chat passes word to its conductor — this runs "
                            (if n (str "in " n) "outside every session"))
                       {:reason :not-the-chat :session n}))
       (ws/closure w)
       (throw (ex-info (str "Workstream " ws-id " is closed: nothing conducts it") {:reason :closed}))
       (not (standing/activated? w))
       (throw (ex-info (str "Workstream " ws-id " is not activated, so it has no conductor to tell")
                       {:reason :not-activated}))
       (str/blank? text)
       (throw (ex-info "Nothing to pass on: the text is blank" {:reason :no-text}))
       (not-every? report/grantable-kinds grants)
       (throw (ex-info (str "Only " (str/join ", " (map name (sort report/grantable-kinds)))
                            " may be granted; " (str/join ", " (map name (remove report/grantable-kinds grants)))
                            " stays the person's own")
                       {:reason :not-grantable :grants grants})))
     (let [fields {:what "the workstream's chat has word for you, from the person talking there"
                   :session n :word (str/trim text)}]
       (if grants
         (let [path (ws/append-entry! project ws-id {:kind :grant :session n}
                                      (pr-str {:format :grant :grants grants
                                               :words (str/trim text) :session n}))
               seq  (parse-long (re-find #"^\d+" (str (fs/file-name path))))]
           (post-hand-back! project ws-id (str "act:" seq)
                            (assoc fields :what (str "the workstream's chat has word for you, from the "
                                                     "person talking there, granting the seats "
                                                     (str/join " and " (map name grants))))))
         (post-hand-back! project ws-id (str "chat-word:" n ":" (random-uuid)) fields))))))

;; ── decisions ───────────────────────────────────────────────────────────────

(defn- hand-back-message
  [project ws-id id]
  (let [g (generation/current (:entries (ws/read-ws project ws-id)))]
    (some #(when (and (= id (:id %)) (= :hand-back (:kind %))) %)
          (mapcat #(seats/traffic project ws-id %) (range 1 (inc g))))))

(defn- deciding
  "Run `f` holding the workstream's decision lock, so a check that a once-only write is still owed
   and that write are one step: every :conduct-decision, every escalation the conduct pass
   parks once, and every answer recorded to a conductor's blocker is written under it. Not
   reentrant: `f` takes it again nowhere."
  [project ws-id f]
  (io/with-file-lock (str (fs/path (cstate/workstream-dir project ws-id) ".decisions.lock")) f))

(defn- open-blockers
  "The current generation's blockers no :blocker-answered answers, oldest first — an earlier
   generation's blocker is none a person's act now can answer."
  [project ws-id]
  (let [span     (into #{} (map :seq) (generation/current-span (:entries (ws/read-ws project ws-id))))
        answered (set (map :blocker-seq (ws/entries-of project ws-id :blocker-answered)))]
    (filterv #(and (span (:seq %)) (not (answered (:seq %)))) (ws/entries-of project ws-id :blocker))))

(defn- answered? [project ws-id id]
  (boolean (some #(= id (:hand-back %)) (ws/entries-of project ws-id :conduct-decision))))

(defn- here [] (System/getProperty "user.dir"))

(defn- conducting?
  "Whether this process runs in the workstream's conductor's session."
  [project ws-id]
  (let [cwd (here)
        n   (or (some-> (lifecycle/session-from-cwd cwd) :session)
                (second (lifecycle/session-home-coords-from-cwd cwd)))]
    (boolean (and n (:conductor (session/read-session project ws-id n))))))

(defn- parks?
  "Whether an escalation decided at the position whose next move is `next` parks the workstream on
   a blocker: only when that move is not already a person's. Ledgers written before this rule hold
   escalation blockers parked at a person's move; `answers!` still answers those."
  [next]
  (not= :human (:mode next)))

(defn- slice-instruction
  "The text an instruction to `slice` carries: `text`, and — when the slice runs an older recipe
   version than its drain's current one — the slice's brief under the current version first, the
   slice moved to it with a :slice-rebased."
  [project slice text]
  (let [before (drain/slice-version (ws/read-ws project slice))
        brief  (drain-lane/slice-brief! project slice)
        now    (drain/slice-version (ws/read-ws project slice))]
    (if (< before now)
      (str "Your drain's recipe moved to version " now ". Work under it from here:\n\n"
           brief "\n\n" text)
      text)))

(defn- open-misfit?
  "Whether hand-back `m`, posted for a slice of drain `ws-id`, is from a slice with an open misfit."
  [project ws-id m]
  (boolean (when-let [slice (:slice m)]
             (some #(= slice (:slice %)) (some-> (ws/read-ws project ws-id) drain/open-misfits)))))

(defn- record-decision!
  "Append the :conduct-decision `entry` and carry it out, as one step under the ledger's append lock
   (`ws/appending!`), so no opening or design approval falls between: everything is checked before
   anything is written — the seats an instruction names, the decision itself, and an escalation's
   blocker — and each effect runs only once what it depends on is taken. An escalation parks the
   workstream on its blocker, which the decision then cites; an instruction is posted to each seat
   it names once the decision is recorded. An escalation made while the position owes a person a
   move parks nothing: that move's card already asks them, and the decision rides on it as a note.
   A seat that is gone, or a decision or blocker the ledger refuses, throws with nothing written or
   posted. Answers the entry."
  [project ws-id {:keys [hand-back decision position next] :as entry}]
  (let [[verb body] decision
        ;; A person who already owes a move has that move's card in front of them; a second
        ;; blocker re-asking it is a second copy of one question, and answering the card would
        ;; leave the copy owed. So an escalation at a person's move parks nothing: the decision is
        ;; its record, and the card shows its question as a note.
        ;; The question form is copied whole — one :needs or several :questions — since the
        ;; blocker asks exactly what the escalation asked.
        halt        (when (and (= :escalate verb) (parks? next))
                      (merge {:stage :conduct :outcome :escalated
                              :summary (:summary body)
                              :after   (:after body)}
                             (select-keys body [:needs :questions :recommendation :options])))]
    (ws/appending!
     project ws-id
     (fn [{:keys [check! append!]}]
       (when (= :instruct verb)
         (let [seat-ids (set (map :id (rseats/seats project ws-id)))
               slices   (set (map :ws-id (some-> (ws/read-ws project ws-id) drain/slices)))]
           (when-not (every? (fn [{:keys [seat slice]}]
                               (if slice
                                 (and (slices slice) (some #(= seat (:id %)) (rseats/seats project slice)))
                                 (seat-ids seat)))
                             body)
             (throw (ex-info "An instruction names no current seat" {:reason :not-a-seat})))))
       ;; Stamped: decide! and close-unanswered! are its only writers, and the generic appends
       ;; refuse it. Checked whole before the blocker joins; the :parked it then gains cites that
       ;; blocker, which is all the check could not yet see.
       (check! {:kind :conduct-decision} (pr-str entry))
       (let [parked (when halt
                      (let [{:keys [seq refused]} (drive/park-held! halt append!)]
                        (when refused (throw (ex-info "The escalation could not be parked" {:reason refused})))
                        seq))
             entry  (cond-> entry parked (assoc :parked {:seq parked}))]
         (append! {:kind :conduct-decision} (pr-str entry))
         (when (= :instruct verb)
           (let [w (ws/read-ws project ws-id)]
             (doseq [[i {:keys [seat slice text]}] (map-indexed vector body)
                     :let [k (str "decision:" hand-back ":" i)]]
               (if slice
                 (seats/post! project slice {:key k :kind :instruction :to seat
                                             :text (slice-instruction project slice text)})
                 (seats/post-held! project ws-id w {:key k :kind :instruction :to seat :text text})))))
         entry)))))

(declare person-kinds persons-act?)

(defn- overtaking-act
  "The first act of the person's recorded on the ledger after hand-back `m` was posted, or nil.
   A conductor deciding on `m` read a position that act has since moved: an answer to the blocker
   `m` stood on, a grant, a gate asserted."
  [project ws-id m]
  (let [inst #(try (java.time.Instant/parse (str %)) (catch Exception _ nil))
        m-at (inst (:at m))]
    (when m-at
      (first (sort-by :seq (filter #(and (persons-act? %)
                                         (some-> (inst (:at %)) (.isAfter ^java.time.Instant m-at)))
                                   (mapcat (fn [k] (ws/entries-of project ws-id k)) person-kinds)))))))

(defn- answered-ask
  "The current generation's answered blocker that asked what escalation `body` asks, every
   question word for word, or nil."
  [project ws-id body]
  (let [qs       (report/asked-questions body)
        span     (into #{} (map :seq) (generation/current-span (:entries (ws/read-ws project ws-id))))
        answered (set (map :blocker-seq (ws/entries-of project ws-id :blocker-answered)))]
    (when-not (every? str/blank? qs)
      (last (filter #(and (span (:seq %)) (answered (:seq %)) (= qs (report/asked-questions %)))
                    (ws/entries-of project ws-id :blocker))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :ConductDecision] :DecideResult]}
  decide!
  "Record the conductor's answer to a hand-back — {:hand-back <message id> :decision [:instruct
   [{:seat :text} …]] | [:escalate {:needs (:summary) (:options)}]} — with the position the pipeline derives now, and
   carry it out. Refused, by name, from any session but the conductor's (:not-the-conductor), for
   an id that is no hand-back (:not-a-hand-back) or one already answered (:already-answered), and
   for an instruction to anything that is not a current seat (:not-a-seat) — a drain's coordinator
   instructs a slice's seat by naming the slice too — and, for a drain's coordinator, for a
   hand-back from a slice whose misfit is open (:open-misfit), which it answers through lane-drain
   first. Answers [:accepted entry] or [:refused reason].

   An escalation on a hand-back the person has acted since (`overtaking-act`) is refused
   (:overtaken): it asks about a position that no longer holds, and parked as a blocker it
   would stay owed after the work moved on. The hand-back is recorded :covered by the act's own
   hand-back — posted now if the pass has not posted it yet — which the conductor decides
   instead, so nothing is asked twice and nothing is left owed.

   An escalation asking, word for word, a question the person already answered in this
   generation is refused (:already-asked) and the hand-back stays owed: the answer is on the
   ledger, and the decision owed is what to do with it, not asking it again."
  [project ws-id {:keys [hand-back decision]}]
  (deciding
   project ws-id
   #(cond
      (not (conducting? project ws-id))           [:refused :not-the-conductor]
      (nil? (hand-back-message project ws-id hand-back)) [:refused :not-a-hand-back]
      (answered? project ws-id hand-back)         [:refused :already-answered]
      (open-misfit? project ws-id (hand-back-message project ws-id hand-back)) [:refused :open-misfit]

      (and (= :escalate (first decision)) (answered-ask project ws-id (second decision)))
      [:refused :already-asked]

      (and (= :escalate (first decision))
           (overtaking-act project ws-id (hand-back-message project ws-id hand-back)))
      (let [act   (overtaking-act project ws-id (hand-back-message project ws-id hand-back))
            cover (post-hand-back! project ws-id (str "act:" (:seq act))
                                   {:what (str "the person's " (name (:format act)) " at entry " (:seq act))
                                    :session (:name (conductor-of project ws-id)) :said (pr-str act)})
            {:keys [position next owes]} (position project ws-id)]
        (record-decision! project ws-id (cond-> {:format :conduct-decision :hand-back hand-back
                                                 :position position :by :nido
                                                 :decision [:covered {:by (:id cover)}]}
                                          next (assoc :next next)
                                          owes (assoc :owes owes)))
        [:refused :overtaken])

      :else
      (let [{:keys [position next owes]} (position project ws-id)]
        (try
          [:accepted (record-decision! project ws-id (cond-> {:format :conduct-decision :hand-back hand-back
                                                               :position position :by :conductor
                                                               :decision decision}
                                                        next (assoc :next next)
                                                        owes (assoc :owes owes)))]
          (catch clojure.lang.ExceptionInfo e
            (if (= :not-a-seat (:reason (ex-data e)))
              [:refused :not-a-seat]
              (throw e))))))))

(defn- excerpt [s n]
  (let [s (str/trim (str/replace (str s) #"\s+" " "))]
    (if (> (count s) n) (str (subs s 0 (dec n)) "…") s)))

(defn- covering
  "The hand-back that covers hand-back `m` among `hand-backs`: a later one from the same session
   that a :conduct-decision answers — that session's newer report, decided, supersedes the
   older one. Nil when there is none."
  [project ws-id m hand-backs]
  (let [answered (into #{} (map :hand-back) (ws/entries-of project ws-id :conduct-decision))
        at       #(try (java.time.Instant/parse (str %)) (catch Exception _ nil))
        m-at     (at (:at m))]
    (last (filter #(and (:session m) (= (:session m) (:session %)) (not= (:id m) (:id %))
                        (answered (:id %))
                        (let [a (at (:at %))] (and a m-at (.isAfter ^java.time.Instant a m-at))))
                  hand-backs))))

(defn- unanswered-escalation
  "nido's escalation of hand-back `m`, which `offers` conductor turns ended without deciding:
   the question carries the seat's report, so the person reads what it was about."
  [m offers]
  {:needs   "What should the seat do next, given the report below that the conductor could not decide on?"
   :summary (str "The conductor ended " offers " turns without deciding this hand-back, so the seat that sent it is waiting. "
                 "The hand-back reads: " (excerpt (:text m) 1200))
   :after   "Your answer reaches the conductor on its next pass, which instructs the waiting seat."})

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :SessionName [:maybe :map]] [:sequential :map]]}
  close-unanswered!
  "When a conductor turn ends leaving the hand-back delivered to it for that turn — the one
   delivered under the ended `instruction`'s token, to its running conversation — unanswered,
   settle what follows. A hand-back a later, decided hand-back from the same session covers
   (`covering`) is recorded as :covered by it, and nobody is asked. Otherwise a turn that was its
   first offer to that conversation leaves it owed, so the conductor is given it again
   (`seats/hand-back-offers`); the last offer ending undecided escalates it to a person, the
   seat's report quoted. A hand-back not yet delivered, or delivered for a turn that has not
   ended, stays owed. Answers the decisions written."
  [project ws-id session-name instruction]
  (let [conversation (get-in (session/read-session project ws-id session-name) [:claude :session-id])
        token        (:token instruction)
        g            (generation/current (:entries (ws/read-ws project ws-id)))
        m            (first (for [m (mapcat #(seats/traffic project ws-id %) (range 1 (inc g)))
                                  d (:deliveries m)
                                  :when (and token conversation (= token (:token d))
                                             (= conversation (:conversation d)))]
                              m))]
    (if (and m (= :hand-back (:kind m)))
      (deciding
       project ws-id
       #(if (answered? project ws-id (:id m))
          []
          (let [cover  (covering project ws-id m (seats/hand-backs project ws-id))
                ;; Which offer to this conversation the ended turn was, counting from 1.
                offer  (inc (count (take-while (fn [d] (not= token (:token d)))
                                               (filter (fn [d] (= conversation (:conversation d)))
                                                       (:deliveries m)))))
                {:keys [position next owes]} (position project ws-id)
                decide (fn [decision]
                         [(record-decision! project ws-id (cond-> {:format :conduct-decision :hand-back (:id m)
                                                                   :position position :by :nido
                                                                   :decision decision}
                                                            next (assoc :next next)
                                                            owes (assoc :owes owes)))])]
            (cond
              cover                            (decide [:covered {:by (:id cover)}])
              (< offer seats/hand-back-offers) []
              :else                            (decide [:escalate (unanswered-escalation m offer)])))))
      [])))

;; ── stops: a dialog nobody can answer, a turn whose process is gone, and work that stalled ──

(def dialog-grace-ms
  "How long a driven session may stand on a dialog before a person is asked about it: long enough
   that one a person is answering at the session's terminal has closed, short enough that one
   nobody is watching is not lost."
  (* 2 60 1000))

(def stall-grace-ms
  "How long an activated workstream may read stalled after its last turn end, and after its
   ledger's newest entry, before the stall is handed back: whatever last moved it — the turn end's
   own hand-back and its decision, or a person's act that made the move an agent's — gets that
   long to be acted on first."
  (* 10 60 1000))

(defn- ms [iso] (try (.toEpochMilli (java.time.Instant/parse (str iso))) (catch Exception _ nil)))

(defn- question
  "`s`'s first sentence as a question that fits a blocker's :needs — 240 characters, which the
   ledger refuses past — or nil."
  [s]
  (let [s (str/trim (str/replace (str s) #"\s+" " "))
        q (if-let [i (str/index-of s "?")] (subs s 0 (inc i)) (str s "?"))]
    (when (and (< 1 (count q)) (<= (count q) 240)) q)))

(defn- dialog-blocker
  "The blocker asking a person about the dialog `session` stands on, given its facet's :waiting
   {:since :for} and what `substrate/pending-dialog` read of it (nil when nothing could be). Every
   question it asks is in :summary whole, with its options, which are not offered as branches: a
   branch answers the ledger, and only the session can answer its dialog."
  [session {:keys [since] waits :for} dialog]
  (let [qs (:questions dialog)]
    {:format  :blocker
     :summary (str session " stopped mid-turn on a dialog" (when waits (str " (" waits ")"))
                   " at " since ", and nothing proceeds until it is answered."
                   (str/join "" (for [{:keys [question options]} qs]
                                  (str " It asks: “" (excerpt question 800) "”"
                                       (when (seq options) (str " — options: " (str/join "; " options)))
                                       ".")))
                   (when (and dialog (empty? qs))
                     (str " It waits to run " (:tool dialog)
                          (when (seq (:input dialog)) (str ": " (:input dialog))) ".")))
     :needs   (or (cond
                    (seq qs) (question (:question (first qs)))
                    dialog   (question (str "Allow " session " to use " (:tool dialog)
                                            (when (seq (:input dialog)) (str ": " (excerpt (:input dialog) 160))))))
                  (str "Can you answer the dialog " session " is waiting on?"))
     :after   (str "Answer it in the session itself — open " session " from the board. nido marks "
                   "this answered when the dialog closes. A reply here is recorded, but it cannot "
                   "reach the dialog.")
     :stop    {:kind :dialog :session session :since since}}))

(defn- stop-blockers
  "nido's blockers about a stop of `kind` on a workstream, each {:seq :stop :answer <its answer
   entry, or nil>}."
  [project ws-id kind]
  (let [answers (group-by :blocker-seq (ws/entries-of project ws-id :blocker-answered))]
    (for [b (ws/entries-of project ws-id :blocker) :when (= kind (get-in b [:stop :kind]))]
      {:seq (:seq b) :stop (:stop b) :answer (first (get answers (:seq b)))})))

(defn- dialogs!
  "Ask a person about each dialog a driven session of the workstream has stood on past
   `dialog-grace-ms`, once per dialog, and answer {:by :nido} each one asked about that has closed.
   Answers [{:ws-id :session :wrote <:blocker | :blocker-answered>} …]."
  [project ws-id records now-ms rows]
  (let [asked (stop-blockers project ws-id :dialog)
        waits (into {} (keep (fn [s] (when-let [w (get-in s [:claude :waiting])] [(:name s) w])) records))]
    (concat
     (for [{:keys [seq stop answer]} asked
           :when (and (nil? answer) (not= (:since stop) (get-in waits [(:session stop) :since])))]
       (do (ws/append-entry! project ws-id {:kind :blocker-answered}
                             (pr-str {:format :blocker-answered :blocker-seq seq :by :nido :resumed nil
                                      :summary (str (:session stop) "'s dialog closed: it was answered "
                                                    "in the session, or the session stopped.")}))
           {:ws-id ws-id :session (:session stop) :wrote :blocker-answered}))
     (for [s records
           :let [w (get-in s [:claude :waiting]) since (ms (:since w))]
           :when (and w since (= :nido (get-in s [:claude :driver]))
                      (>= (- now-ms since) dialog-grace-ms)
                      (not-any? #(= {:kind :dialog :session (:name s) :since (:since w)} (:stop %)) asked))]
       (let [id  (get-in s [:claude :session-id])
             cwd (:cwd (get rows id))]
         (ws/append-entry! project ws-id {:kind :blocker}
                           (pr-str (dialog-blocker (:name s) w (when cwd (substrate/pending-dialog id cwd)))))
         {:ws-id ws-id :session (:name s) :wrote :blocker})))))

;; A dead turn: conduct holds the turn open and no witness shows its process.

(defn- process-witness
  "What this pass can say of the process of open turn `t` (one of conduct's `open-turns`), given
   `listing` — a listing read for this check, or ::unread when it could not be read: :running,
   :gone, or :undecided. :gone needs every witness there is to agree: the listing holds no row
   with a process for the conversation, and the process its latest start recorded, when it
   recorded one, is not running as a process started no later than that start was recorded — a
   pid running as a later process was given to something else. :undecided is whatever cannot be
   told — an unread listing, a recorded pid that does not read as one, a running process whose
   start the OS will not give or whose recording instant does not parse — and licenses nothing."
  [{:keys [conversation pid started-at]} listing]
  (cond
    (= ::unread listing) :undecided
    (some #(and (:pid %) (= conversation (:sessionId %))) listing) :running
    (str/blank? (str pid)) :gone
    :else
    (let [n        (parse-long (str/trim (str pid)))
          started  (when n (process/process-started n))
          recorded (ms started-at)]
      (cond
        (nil? n)            :undecided
        (= :gone started)   :gone
        (= :unknown started) :undecided
        (nil? recorded)     :undecided
        (<= started recorded) :running
        :else               :gone))))

(defn- recorded-end
  "The last end Claude Code reported for `conversation` at or after `since`, as its event, or nil."
  [conversation since]
  (let [from (ms since)]
    (last (filter #(and (= :session-end (:event %)) from (some-> (ms (:at %)) (>= from)))
                  (:events (session-events/events-since conversation nil))))))

(defn- reopen-command
  "The command line that reopens `conversation` itself, run in the directory it was recorded in:
   a resume given no flag. Claude Code continues a session in place only then — one given any
   flag keeps the session's saved options and starts a copy under a new id, and a copy would
   leave this conversation's turn open and its blocker standing with the person back at work."
  [conversation]
  (str "claude --resume " (process/quoted conversation)))

(defn- dead-turn-blocker
  "The blocker telling a person that open turn `t`'s process is gone, with what resumes it. It
   says what nido saw and not why: nothing here knows why a process exited."
  [{:keys [session-name conversation since cwd]} end]
  {:format  :blocker
   :summary (str session-name "'s agent process is gone inside a turn. Conversation " conversation
                 " began a turn at " since " and recorded no Stop, and no process is running it. "
                 (if end
                   (str "Claude Code recorded the session ending at " (:at end) " with reason `"
                        (or (:reason end) "none given") "`.")
                   "No end of the session was recorded.")
                 " Why it exited is not known. To continue the conversation where it stopped, run: "
                 (when cwd (str "cd " (process/quoted cwd) " && ")) (reopen-command conversation)
                 " — with no other flag: a resume given one starts a copy and leaves this turn open.")
   :needs   (or (question (str "Will you resume " session-name "'s conversation, whose agent exited mid-turn?"))
                "Will you resume the conversation whose agent exited mid-turn?")
   :after   (str "nido starts nothing itself. It marks this answered once the conversation runs "
                 "again or its turn has ended. A reply here is recorded, but it resumes nothing.")
   :stop    {:kind :dead-turn :session session-name :since since}})

(defn- dead-turn-asks-path [] (str (fs/path (cstate/coordinator-dir) "dead-turn-asks.edn")))

(defn- dead-turn-asks
  "The workstreams of `project` nido has asked about a dead turn on and not yet seen answered —
   where to look, never what is true: the ledger says which blockers stand. A workstream whose
   turn has closed appears in no reading of open turns, and this is what brings a pass back to
   answer its blocker."
  [project]
  (into #{} (comp (filter #(= (name project) (first %))) (map second))
        (io/read-edn (dead-turn-asks-path))))

(defn- note-dead-turn-ask!
  "Keep (`standing?`) or drop `ws-id` among the workstreams `dead-turn-asks` answers."
  [project ws-id standing?]
  (let [k   [(name project) ws-id]
        was (set (io/read-edn (dead-turn-asks-path)))
        now ((if standing? conj disj) was k)]
    (when (not= was now) (io/write-edn! (dead-turn-asks-path) now))))

(defn- asks-about?
  "Whether a dead turn of `conversation` on session `record` is a person's to resume: the session
   is neither the conductor's nor the chat's — a conductor is restarted by its own lifecycle —
   and the conversation holds no instruction given for a Run, which its budget settles — pending
   or lapsed, since conduct still ends a turn on a lapsed one a late prompt delivered."
  [record conversation]
  (boolean (and record (not (:conductor record)) (not (:chat record))
                (not (and (= conversation (get-in record [:claude :session-id]))
                          (some :for (cons (get-in record [:claude :pending])
                                           (get-in record [:claude :lapsed]))))))))

(defn- dead-turns!
  "Answer {:by :nido} each dead-turn blocker of the workstream whose turn conduct no longer holds
   open or whose process a witness shows, then — when `ask?` — append one for each of `turns`,
   the workstream's open turns, whose process is :gone by `process-witness`, whose session
   `asks-about?` allows, and that no blocker, answered or not, already names by session and the
   instant the turn began. `listing` is a delay of the listing read for this check. Starts,
   wakes and messages nothing. Answers [{:ws-id :session :wrote} …]."
  [project ws-id turns listing ask?]
  (let [asked   (stop-blockers project ws-id :dead-turn)
        witness (memoize (fn [t] (process-witness t @listing)))
        turn-of (fn [stop] (some #(when (and (= (:session stop) (:session-name %)) (= (:since stop) (:since %))) %)
                                 turns))
        answers (doall
                 (for [{:keys [seq stop answer]} asked
                       :let  [t   (turn-of stop)
                              why (cond (nil? t) "its turn is no longer open: the conversation was resumed, or the turn ended"
                                        (= :running (witness t)) "a process is running its conversation again")]
                       :when (and (nil? answer) why)]
                   (do (ws/append-entry! project ws-id {:kind :blocker-answered}
                                         (pr-str {:format :blocker-answered :blocker-seq seq :by :nido :resumed nil
                                                  :summary (str (:session stop) "'s dead turn no longer stands — " why ".")}))
                       {:ws-id ws-id :session (:session stop) :wrote :blocker-answered})))
        records (delay (into {} (map (juxt :name identity)) (session/list-sessions project ws-id)))
        named   (atom (into #{} (map (comp (juxt :session :since) :stop)) asked))
        asks    (doall
                 (for [{:keys [session-name conversation since] :as t} (when ask? turns)
                       :when (and (not (@named [session-name since]))
                                  (asks-about? (get @records session-name) conversation)
                                  (= :gone (witness t)))]
                   (do (swap! named conj [session-name since])
                       (ws/append-entry! project ws-id {:kind :blocker}
                                         (pr-str (dead-turn-blocker t (recorded-end conversation since))))
                       {:ws-id ws-id :session session-name :wrote :blocker})))]
    (when (or (seq asked) (seq asks))
      (note-dead-turn-ask! project ws-id
                           (boolean (some (comp nil? :answer) (stop-blockers project ws-id :dead-turn)))))
    (concat answers asks)))

(def stops-gap-ms
  "How far apart two successive stops! passes over a project may come before the later one says
   so: the interval the five-minute bound on finding a dead turn is stated over."
  (* 2 60 1000))

(defonce ^:private !last-stops-pass (atom {}))

(defn- note-pass!
  "Remember that stops! passes over `project` now — the clock read here, never an instant taken
   earlier in the tick, so whatever ran before this pass falls inside the interval — and print the
   gap when the previous pass in this daemon came more than `stops-gap-ms` before: a long tick, the
   sleep between ticks, a halt."
  [project]
  (let [now-ms  (System/currentTimeMillis)
        [was _] (swap-vals! !last-stops-pass assoc project now-ms)
        before  (get was project)]
    (when (and before (> (- now-ms before) stops-gap-ms))
      (binding [*out* *err*]
        (println (str "nido conductor: stops on " (name project) " came " (quot (- now-ms before) 1000)
                      "s after the previous pass — a dead turn may have gone unseen that long"))))))

(defn- stall!
  "Hand the workstream's stall back to its conductor — once per turn end — when it is activated,
   reads stalled since a recorded turn end at least `stall-grace-ms` old, has gained no ledger
   entry within that grace either, owes its conductor no hand-back already, and no session of it
   stands on a dialog. The hand-back carries status's :why."
  [project ws-id w records now-ms]
  (let [{:keys [stalled why]} (pipeline/status project ws-id)
        ended (ms (:ended-at why))
        ;; An entry without a readable :at moves nothing: it cannot say when it landed.
        moved (or (ms (:at (last (:entries w)))) 0)]
    (when (and stalled ended (= :recorded (:turn-end why))
               (>= (- now-ms (max ended moved)) stall-grace-ms)
               (not-any? #(get-in % [:claude :waiting]) records)
               (not (owes-hand-back? project ws-id w)))
      (let [s (some #(when (= (:session why) (:name %)) %) records)]
        (post-hand-back! project ws-id (str "stall:" (:session why) ":" (:ended-at why))
                         {:what (str "work stalled — " (:session why) "'s turn ended at " (:ended-at why)
                                     " owing " (if (:owes why) (name (:owes why)) "the next move")
                                     ", and nothing has worked on it since")
                          :session (:session why) :seat (get-in s [:seat :id]) :said (:said why)})
        [{:ws-id ws-id :session (:session why) :wrote :hand-back}]))))

(defn- moot-halt
  "Why the open blocker `b` no longer asks anything, when it halted a review stage and the ledger
   has since gained a record of the kind that stage reviews (`drive/reviewed-kind`) that no review
   loop's amender wrote: the stage's next run reads that record, so it is the reply to whatever the
   halt asked — a round's question, which that run poses again if it still stands, or whether to
   run the round again at all. The round's own amendment is appended before its halt and stamped
   on the index, so it is never taken for a reply. Nil for every other blocker."
  [project ws-id b]
  (let [{:keys [stage outcome]} (last (:tried b))]
    (when-let [kind (drive/reviewed-kind stage)]
      (let [amended (ws/amended-seqs project ws-id)]
        (when-let [newer (last (filter #(and (> (:seq %) (:seq b)) (not (amended (:seq %))))
                                       (ws/entries-of project ws-id kind)))]
          (str "the " (name stage) " stage it halted at " (name outcome) " now reviews the newer "
               (name kind) " at entry " (:seq newer)))))))

(defn- moot-escalation
  "Why the open blocker `b` no longer asks anything, when nido parked it escalating a hand-back the
   conductor ended its turns without deciding, and the conductor has since decided a later
   hand-back from the same session — instructing the seats, or escalating in its own words: either
   way it has taken that seat's situation up, and nido's stand-in question is superseded. Nil for
   every other blocker, the conductor's own escalations among them."
  [project ws-id b decisions]
  (let [session-of #(:session (hand-back-message project ws-id (:hand-back %)))]
    (when-let [esc (some #(when (and (= :nido (:by %)) (= (:seq b) (get-in % [:parked :seq]))) %) decisions)]
      (when-let [session (session-of esc)]
        (when-let [d (some #(when (and (= :conductor (:by %)) (> (:seq %) (:seq b)) (= session (session-of %))) %)
                           decisions)]
          (str "the conductor has since decided " session "'s later hand-back, at entry " (:seq d)))))))

(defn- answer-moot!
  "Answer, as nido, each of the workstream's open blockers that nido wrote and that no longer ask
   anything (`moot-halt`, `moot-escalation`), its summary saying why. A blocker a person owes for
   any other reason is left for them. Answers [{:ws-id :blocker :wrote :blocker-answered} …]."
  [project ws-id]
  (deciding
   project ws-id
   #(let [decisions (ws/entries-of project ws-id :conduct-decision)]
      (vec (for [b    (open-blockers project ws-id)
                 :let [why (or (moot-halt project ws-id b) (moot-escalation project ws-id b decisions))]
                 :when why]
             (do (ws/append-entry! project ws-id {:kind :blocker-answered}
                                   (pr-str {:format :blocker-answered :blocker-seq (:seq b) :resumed nil :by :nido
                                            :summary (str "it no longer asks anything: " why)}))
                 {:ws-id ws-id :blocker (:seq b) :wrote :blocker-answered}))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :int] [:sequential :map]]}
  stops!
  "One pass over `project`'s open workstreams with a session nido drives, a turn conduct holds
   open, or a dead-turn blocker still standing, at `now-ms`: answer as nido each blocker it wrote
   that no longer asks anything (`answer-moot!`); ask a person about each dialog a driven session
   has stood on past `dialog-grace-ms` — a :blocker nido writes, worded with what the dialog
   asks, answered {:by :nido} once it closes; ask a person, on an activated workstream not
   paused, about each open turn whose process is gone (`dead-turns!`) — a :blocker carrying the
   command that resumes the conversation, answered {:by :nido} once the turn closes or a process
   shows — and hand an activated workstream's stall back to its conductor (`stall!`), which
   decides it like any hand-back. Nothing is sent to a session and none is started.

   The dead-turn check runs on every pass, against a listing read for it and never the display
   cache; a listing that cannot be read decides no turn dead. A pass that comes more than
   `stops-gap-ms` after the project's previous one prints the gap. Answers [{:ws-id :session
   :wrote} …]; a workstream that throws is logged and skipped."
  [project now-ms]
  (note-pass! project)
  (let [faceted ((requiring-resolve 'nido.coordinator.lane.conduct/faceted-sessions) {:projects [project]})
        open    (group-by :ws-id ((requiring-resolve 'nido.coordinator.lane.conduct/open-turns) {:projects [project]}))
        rows    (delay ((requiring-resolve 'nido.coordinator.lane.conduct/live-states) {:projects [project]}))
        listing (delay (try (vec (substrate/listing {:projects [project]})) (catch Exception _ ::unread)))]
    (vec
     (mapcat (fn [ws-id]
               (try
                 (let [w (ws/read-ws project ws-id)]
                   ;; Closed, its blockers are nobody's to answer: nothing brings a pass back.
                   (when-not (and w (nil? (ws/closure w)))
                     (note-dead-turn-ask! project ws-id false))
                   (when (and w (nil? (ws/closure w)))
                     (let [records (filter :claude (session/list-sessions project ws-id))
                           moot    (answer-moot! project ws-id)
                           ;; An answer just written moved the ledger: the stall reads it.
                           w       (if (seq moot) (ws/read-ws project ws-id) w)
                           worked? (and (standing/activated? w) (not (standing/paused? w)))]
                       (doall (concat moot
                                      (dialogs! project ws-id records now-ms @rows)
                                      (dead-turns! project ws-id (get open ws-id) listing worked?)
                                      (when worked?
                                        (stall! project ws-id w (remove :conductor records) now-ms)))))))
                 (catch Throwable t
                   (binding [*out* *err*]
                     (println (str "nido conductor: stops on " ws-id " skipped — " (ex-message t))))
                   nil)))
             (distinct (concat (map :ws-id faceted) (keys open) (dead-turn-asks project)))))))

;; ── the person's acts ───────────────────────────────────────────────────────

(def ^:private person-kinds
  "The ledger kinds only a person's act writes."
  #{:intent :triage-accepted :phase-gate :blocker-answered :grant :merged :closed
    ;; a drain's: approving a recipe, and observing its exit
    :recipe :exit-observed})

(defn- persons-act?
  "Whether ledger entry `e` of a person kind is an act of the person's — not a blocker's answer
   nido recorded for an act, nor an entry nido wrote."
  [e]
  (and (not (:act e)) (not= :nido (:by e))))

(defn- open-escalations
  "Every open escalation a conductor made, oldest first: each of the current generation's blockers
   no :blocker-answered answers that a :conduct-decision parked, with that decision — each owed its
   own answer, whichever of them places the pipeline."
  [project ws-id]
  (let [by-parked (into {} (keep #(when-let [p (get-in % [:parked :seq])] [p %]))
                        (ws/entries-of project ws-id :conduct-decision))]
    (vec (keep #(when-let [esc (by-parked (:seq %))] {:blocker % :escalation esc})
               (open-blockers project ws-id)))))

(defn- open-conductor-blocker
  "The open escalation whose blocker places the pipeline — the current generation's newest open
   blocker — when a conductor's escalation parked it, else nil: what a reply at the gate answers."
  [project ws-id]
  (let [b (last (open-blockers project ws-id))]
    (some #(when (= (:seq b) (:seq (:blocker %))) %) (open-escalations project ws-id))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :boolean]}
  awaiting-answer?
  "Whether a reply at the gate has a conductor's escalation to answer — the one `answer!` records
   it for (`open-conductor-blocker`), whatever session the work runs in. An older escalation under
   a newer blocker is owed the person's act at its step (`answers!`), not a reply."
  [project ws-id]
  (some? (open-conductor-blocker project ws-id)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :string] [:maybe :map]]}
  answer!
  "Record the person's `reply` to the open blocker a conductor's escalation parked the workstream
   on as that blocker's answer; nil, recording nothing, when the open blocker is no conductor's."
  [project ws-id reply]
  (deciding
   project ws-id
   #(when-let [{:keys [blocker]} (open-conductor-blocker project ws-id)]
      (let [entry {:format :blocker-answered :blocker-seq (:seq blocker) :summary reply :resumed nil}]
        (ws/append-entry! project ws-id {:kind :blocker-answered} (pr-str entry))
        entry))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :int :map] [:or :nil :map :keyword]]}
  answer-escalation!
  "Record `answer` — a :blocker-answered body: a picked branch, the person's words, or one answer
   per question in :answers — as the answer to the blocker at `blocker-seq` when a conductor's
   escalation parked it, under the conductor's lock and resuming no seat: the conductor reads the
   answer on its next pass, as it reads `answer!`'s. Answers the entry written; :closed, writing
   nothing, when an escalation parked that blocker and it is no longer open; nil when no
   conductor's escalation parked it, so the caller answers it as any other blocker."
  [project ws-id blocker-seq answer]
  (when (some #(= blocker-seq (get-in % [:parked :seq])) (ws/entries-of project ws-id :conduct-decision))
    (deciding
     project ws-id
     #(if (some (fn [{:keys [blocker]}] (= blocker-seq (:seq blocker))) (open-escalations project ws-id))
        (let [entry (assoc answer :format :blocker-answered :blocker-seq blocker-seq :resumed nil)]
          (ws/append-entry! project ws-id {:kind :blocker-answered} (pr-str entry))
          entry)
        :closed))))

(defn- earlier?
  "Whether ISO instant `a` falls before `b`; false when either does not parse."
  [a b]
  (try (.isBefore (java.time.Instant/parse (str a)) (java.time.Instant/parse (str b)))
       (catch Exception _ false)))

(defn ^{:malli/schema [:=> [:cat :map] [:sequential :map]]}
  answers-in-place!
  "From one conduct pass's report, answer as nido each open escalation the conductor decided on a
   hand-back marked as a person's turn, and the blocker it parked, when the person next prompts
   that same session: the first prompt a person typed there after the decision, which the answer
   cites. No other escalation is answered by a prompt. Answers [{:ws-id :answered <blocker seq>}]."
  [{:keys [prompts]}]
  (vec
   (for [[[project ws-id session] ps] (group-by (juxt :project :ws-id :session) prompts)
         r (deciding
            project ws-id
            ;; Realized inside the lock: each answer is checked and written as one step.
            #(vec (for [{:keys [blocker escalation]} (open-escalations project ws-id)
                        :let  [m (hand-back-message project ws-id (:hand-back escalation))]
                        :when (and (= :person (:turn m)) (= session (:session m)))
                        :let  [p (first (filter (fn [{:keys [event]}] (earlier? (:at escalation) (:at event)))
                                                (sort-by (comp str :at :event) ps)))]
                        :when p]
                    (let [{:keys [event conversation]} p]
                      (ws/append-entry! project ws-id {:kind :blocker-answered}
                                        (pr-str {:format :blocker-answered :blocker-seq (:seq blocker) :by :nido
                                                 :resumed nil
                                                 :summary (str "answered by the person's prompt into " session
                                                               " at " (:at event))
                                                 :prompt (cond-> {:session session :at (str (:at event))}
                                                           conversation (assoc :conversation conversation))}))
                      {:ws-id ws-id :answered (:seq blocker)}))))]
     r)))

(defn- at-a-persons-step? [{:keys [next]}]
  (or (= :human (:mode next)) (= :establish-intent (:stage next))))

(def ^:private person-steps
  "Each of the person's steps: the kinds of their act that take it — a step's other acts answer no
   blocker escalated at it — and the kind of record the step acts on, read off the ledger as the
   escalation found it (`found-before`); nil for :establish-intent, whose act writes the goal and
   acts on no earlier record."
  {:establish-intent         {:kinds #{:intent}}
   :accept-triage            {:kinds #{:triage-accepted}  :target :verdict}
   :assert-gate              {:kinds #{:phase-gate}       :target :design}
   :land                     {:kinds #{:merged :closed}   :target :design}
   :answer-blocker           {:kinds #{:blocker-answered} :target :blocker}
   ;; a drain's: approving the recipe proposal it was escalated over, and observing its exit
   :approve-recipe           {:kinds #{:recipe}           :target :proposal}
   :close-drain              {:kinds #{:exit-observed}}})

(def ^:private act-cites
  "Where each kind of the person's act cites the record it acts on. An :intent and a :closed cite
   none — a close names a design only between phases — and take the step they are kinds of."
  {:blocker-answered [:blocker-seq]
   :triage-accepted  [:triage-seq]
   :phase-gate       [:design :seq]
   :merged           [:design :seq]
   :recipe           [:proposal]})

(def ^:private target-readings
  "How the pipeline reads, off a ledger, the record each kind of step acts on — each given the
   ledger as it stood when an escalation's blocker was parked (`found-before`): the blocker it
   places a workstream :blocked on, the newest no answer names; the triage verdict
   `standing/verdict` finds; the governing design `generation/current-design` names, which a
   paused plan makes an older one than the newest."
  {:blocker (fn [project ws-id w]
              (let [held     (into #{} (map :seq) (:entries w))
                    answered (into #{} (comp (filter #(held (:seq %))) (keep :blocker-seq))
                                   (ws/entries-of project ws-id :blocker-answered))]
                (->> (:entries w)
                     (filter #(= :blocker (:kind %)))
                     (remove #(answered (:seq %)))
                     last
                     :seq)))
   :verdict (fn [project ws-id w] (:seq (standing/verdict project ws-id w)))
   :design  (fn [_ _ w] (generation/current-design w))
   ;; the newest recipe proposal awaiting the person — the one the drain is placed at
   :proposal (fn [_ _ w] (:seq (last (drain/pending-proposals w))))})

(defn- found-before
  "The record of `kind` a step escalated over: `target-readings`' reading of the ledger as it
   stood when `blocker` was parked."
  [project ws-id blocker kind]
  ((target-readings kind) project ws-id
   (update (ws/read-ws project ws-id) :entries #(filterv (fn [e] (< (:seq e) (:seq blocker))) %))))

(defn- takes-step?
  "Whether the person's act `e`, of a kind that takes `step`, takes the step the escalation parking
   `blocker` was made at: an act citing a record (`act-cites`) cites the one the step found then
   (`person-steps`' :target, `found-before`); one citing none takes the step it is a kind of."
  [project ws-id blocker step e]
  (if-let [path (act-cites (:format e))]
    (= (get-in e path) (some->> (:target (person-steps step)) (found-before project ws-id blocker)))
    true))

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:sequential :map]]}
  answers!
  "On every conduct pass, for each workstream of `project` with a conductor, over the acts recorded
   while it was open — the one that closed it last among them: for each open escalation a
   conductor made at a person's step (`open-escalations`), whichever places the pipeline, record the
   person's act at the step it named — an act of a kind
   that takes that step, never another, and never one `persons-act?` rejects — as that escalation's blocker's answer, citing the act; and log each act of the person's recorded on the workstream
   since its conductor's record was made — a reply recorded as a blocker's answer among them,
   never a blocker's answer nido recorded for an act, an entry nido wrote (`persons-act?`), or
   the :closed entry that closes the workstream as
   its ledger stands — to its conductor as a hand-back, once per entry, with the position the
   pipeline derives at that moment. Answers what it recorded and posted."
  [project]
  (vec
   (mapcat
    (fn [ws-id]
      (let [c     (conductor-of project ws-id)
            w     (when c (ws/read-ws project ws-id))
            ;; A closed workstream's acts are still read up to the one that closed it: each was
            ;; recorded while it was open, and its close does not unrecord them.
            upto  (some-> w ws/closure :seq)
            open? #(or (nil? upto) (<= (:seq %) upto))]
        (when w
          (concat
           (deciding
            project ws-id
            ;; Realized inside the lock: each answer is checked and written as one step.
            #(vec (for [{:keys [blocker escalation]} (open-escalations project ws-id)
                   :when (at-a-persons-step? escalation)
                   r (when-let [act (let [step (get-in escalation [:next :stage])]
                                  (first (filter (fn [e] (and (> (:seq e) (:seq blocker)) (open? e) (persons-act? e)
                                                              (takes-step? project ws-id blocker step e)))
                                                 (sort-by :seq (mapcat (fn [k] (ws/entries-of project ws-id k))
                                                                       (:kinds (person-steps step)))))))]
                   (let [entry {:format :blocker-answered :blocker-seq (:seq blocker)
                                :summary (str "answered by the person's " (name (:format act)) " at entry " (:seq act))
                                :resumed nil :act {:seq (:seq act)}}]
                     (ws/append-entry! project ws-id {:kind :blocker-answered} (pr-str entry))
                     [{:ws-id ws-id :answered (:seq blocker)}]))]
               r)))
           ;; The close that closes it is posted to no one: closing settles what came before it,
           ;; and is itself nothing for a conductor to read.
           (for [e (sort-by :seq (mapcat #(ws/entries-of project ws-id %) person-kinds))
                 :when (and (pos? (compare (str (:at e)) (str (:created-at c))))
                            (open? e) (not= upto (:seq e)) (persons-act? e))]
             (assoc (post-hand-back! project ws-id (str "act:" (:seq e))
                                     {:what (str "the person's " (name (:format e)) " at entry " (:seq e))
                                      ;; No turn ended: what the person said is the act as recorded.
                                      :session (:name c) :said (pr-str e)})
                    :ws-id ws-id))))))
    (ws/list-ids project))))

;; ── refusing the person's acts ──────────────────────────────────────────────

(def ^:private person-tasks
  "The nido tasks that write a person's act on any path they can take — whatever their arguments,
   and whether or not they then exit unsuccessfully: a person-kind entry or a close. The review
   tasks are not here: what they write is a round's, never a person's act."
  #{"nido:workstream:blocker:answer" "nido:land:record" "nido:recovery:land"
    "nido:workstream:close" "nido:phase:advance" "nido:ticket:complete" "nido:ticket:apply"
    "nido:workstream:generation:open"
    ;; :closed — set-stage! to done, a dismissal, a restored recovery, a resolved between-phases
    ;; round, a vetoed reservation, a backfilled orphan ticket
    "nido:workstream:stage:advance" "nido:ticket:dismiss" "nido:recovery:restore"
    "nido:findings:resolve" "nido:improvement:reserve" "nido:status:backfill"
    ;; a close, after the push it lands
    "nido:improvement:discharge"
    ;; a child workstream's :intent
    "nido:workstream:fork"
    ;; a drain's: starting one, approving its recipe, landing a slice, closing it
    "nido:drain:start" "nido:drain:approve" "nido:drain:land" "nido:drain:close"
    ;; a landing of the workstream's own PR — a seat's only under a :merge grant (`granted?`)
    "nido:workstream:pr:land"})

(def ^:private generic-appends
  "The nido tasks that append an entry of whatever :kind their arguments name."
  #{"nido:workstream:entry:add" "nido:ticket:append"})

(defn- appends-person-kind?
  "Whether the generic append's `args` may name a kind only a person's act writes, read as both
   generic appends read them — `task-args/split-args`, then `keyword` of its :kind — so a quoting
   the task reads through is read through here too. A word the gate cannot read standing as the
   :kind, or where it could be the :kind key, may name one."
  [args]
  (let [[pos opts] (try (task-args/split-args args) (catch Exception _ nil))
        kind       (:kind opts)]
    (boolean (or (= :opaque kind)
                 (some #{:opaque} pos)
                 (person-kinds (try (keyword kind) (catch Exception _ nil)))))))

(def ^:private grant-acts
  "Each act a grant may hand a seat (report/grantable-kinds), with the ledger kind whose append
   spends it and what it admits (`granted?`): :intent is spent by the goal amendment the seat
   appends; :merge by the landing's :merged — whoever records it, the merge poller included — so
   one grant is one landing, however many times the seat must enqueue before the queue takes it."
  {:intent {:spent-by :intent :admits :append}
   :merge  {:spent-by :merged :admits :pr-land}})

(defn- unspent-grants
  "The acts the person handed to the sessions of workstream `ws-id`, each still unspent: one an
   answer of theirs to a blocker granted by the option it picked or the words it was recorded
   with (report/BlockerAnswered :grants), or a grant the chat recorded from their word
   (report/Grant), with no entry of the act's spending kind (`grant-acts`) appended after it. No
   text is read for a grant: only a :grants a person's act carries."
  [project ws-id]
  (into #{}
        (for [a (mapcat #(ws/entries-of project ws-id %) [:blocker-answered :grant])
              :when (persons-act? a)
              k (:grants a)
              :let [spent-by (get-in grant-acts [k :spent-by])]
              :when (and spent-by
                         (not-any? #(> (:seq %) (:seq a)) (ws/entries-of project ws-id spent-by)))]
          k)))

(defn- own-target?
  "Whether nido task `task`'s `opts` name no project or workstream but session record `s`'s own,
   read as that task reads them: `nido:ticket:append` writes to the workstream carrying :br in
   :project, brian when none is named; `nido:workstream:entry:add` to :ws-id, else the one carrying
   :ref as a Notion ticket; any other task is taken to target what :ws-id, :br or :ref names, else
   its own."
  [s task opts]
  (let [project (name (:project s))
        own     (:workstream-id s)
        named   (some-> (:project opts) str (str/replace #"^:" ""))
        target  (case task
                  "nido:ticket:append"
                  (some->> (:br opts) str (ws/find-by-ref-id (keyword (or named "brian"))) :id)
                  "nido:workstream:entry:add"
                  (if (:ws-id opts)
                    (str (:ws-id opts))
                    (some->> (:ref opts) str (ws/find-by-ref (keyword (or named project)) :notion) :id))
                  (cond (:ws-id opts) (str (:ws-id opts))
                        (or (:br opts) (:ref opts))
                        (:id (ws/find-by-ref-id (keyword project) (str (or (:br opts) (:ref opts)))))
                        :else own))]
    (and own (= own target)
         (= project (or named (when (= "nido:ticket:append" task) "brian") project)))))

(defn- ws-at
  "[project ws-id] of the workstream a nido task run in directory `dir` takes for its own — that of
   the session whose worktree `dir` is in, read as the task reads it (`stages/project+ws-from-cwd`)
   — or nil, as for a nil `dir`: one a `cd` left unknown."
  [dir]
  (when dir
    (try (when-let [{:keys [project session]} (lifecycle/session-from-cwd dir)]
           (when-let [ws-id (session/workstream-id-for (keyword project) session)]
             [(keyword project) ws-id]))
         (catch Throwable _ nil))))

(defn- granted?
  "Whether `act` — one of commit-gate/acts — is one the person granted to session record `s` by a
   grant still unspent on its own workstream (`unspent-grants`), read as its task reads its
   arguments: under :intent, a generic append of an :intent to the session's own workstream; under
   :merge, `nido:workstream:pr:land` of a PR `ws/own-pr` finds among that workstream's :github refs,
   run where the task takes that workstream for its own (`ws-at` of the act's :dir) — since it lands
   the PR of whichever workstream it runs in. An act naming another project, workstream or PR, run
   in another workstream's directory or one the gate cannot read, or arguments the gate cannot read, is granted
   nothing; a :gh-pr act — a raw merge — never is; and the conductor is granted nothing: a grant is
   the person's to a seat."
  [s {:keys [kind task args dir]}]
  (boolean
   (when (and (= :nido-task kind) (not (:conductor s)) (:workstream-id s))
     (let [[_ opts] (try (task-args/split-args args) (catch Exception _ nil))
           project  (:project s)
           own      (:workstream-id s)
           grants   (delay (unspent-grants project own))]
       (cond
         (generic-appends task)
         (let [entry (try (keyword (:kind opts)) (catch Exception _ nil))]
           (and (= :append (get-in grant-acts [entry :admits]))
                (own-target? s task opts)
                (contains? @grants entry)))

         (= "nido:workstream:pr:land" task)
         ;; :pr read raw, as the task reads it: an `owner/repo#n` or a bare number is no EDN form.
         (let [[_ opts] (try (task-args/split-args args #{:pr}) (catch Exception _ nil))]
           (and (string? (:pr opts))
                (own-target? s task opts)
                (= [(keyword (name project)) own] (ws-at dir))
                (contains? @grants :merge)
                (some? (ws/own-pr (ws/read-ws project own) (:pr opts)))))

         :else false)))))

(defn- merge-grant-held?
  "Whether session record `s`'s workstream holds an unspent :merge grant — whoever `s` is: the
   conductor's workstream holds the grant its seats exercise."
  [s]
  (boolean (and (:workstream-id s)
                (contains? (unspent-grants (:project s) (:workstream-id s)) :merge))))

(defn- refs-own-prs?
  "Whether `act` adds an external ref to a workstream — which, under a :merge grant, would make any
   PR the workstream's own."
  [{:keys [kind task]}]
  (and (= :nido-task kind) (= "nido:workstream:ref:add" task)))

(defn- names-merge-grant?
  "Whether a `nido:workstream:ref:add` with `args` adds its ref to a workstream holding an unspent
   :merge grant, its target read as the task reads it — :project with :ws-id, or with a :notion
   :ref — whichever session runs it. Arguments the gate cannot read may name one."
  [args]
  (let [[pos opts] (try (task-args/split-args args) (catch Exception _ nil))]
    (if (or (nil? opts) (some #{:opaque} pos) (some #{:opaque} (vals opts)))
      true
      (let [p     (some-> (:project opts) str (str/replace #"^:" "") keyword)
            ws-id (or (some-> (:ws-id opts) str)
                      (when (and p (:ref opts)) (:id (ws/find-by-ref p :notion (str (:ref opts))))))]
        (boolean (and p ws-id (contains? (unspent-grants p ws-id) :merge)))))))

(defn- persons-act
  "What a person's act `act` — one of commit-gate/acts — is, as a phrase, or nil. Pushing and
   opening or readying a PR are not a person's act; merging one is, and so is passing word on
   with a grant, which records the person's grant."
  [{:keys [kind verb task args]}]
  (case kind
    :gh-pr     (cond (= "merge" verb)   "gh pr merge"
                     (= :unread verb)   "a gh command whose verb the gate cannot read, which may merge a pull request"
                     (= "api" verb)   "a gh api write that may merge a pull request")
    :nido-task (cond (person-tasks task) task
                     (and (generic-appends task) (appends-person-kind? args)) (str task " of a person's kind")
                     ;; A tell carrying :grants records the person's grant; a word the gate cannot
                     ;; read may be that key.
                     (and (= "nido:workstream:tell" task)
                          (some #(or (not (string? %)) (re-matches #"(?i):?grants" %)) args))
                     "nido:workstream:tell with a grant")
    nil))

(defn- turn-prompt
  "The latest prompt conversation `conversation` recorded since its previous Stop — the prompt the
   turn it is in now runs on — or nil when it recorded none since."
  [conversation]
  (let [evs (:events (session-events/events-since conversation nil))]
    (->> (reverse evs)
         (take-while #(not= :stop (:event %)))
         (filter #(= :prompt (:event %)))
         first)))

(defn- answering-for-conductor?
  "Whether session record `s` is the conductor, or conversation `conversation` is in a turn the
   conductor answers for: the latest prompt that conversation recorded since its previous Stop
   carries the token of an instruction nido gave the record's session and recorded for no Run,
   not given by the start that brought the session (:given-by :start: a person's start, or a
   spawn's brief, whose turn may write the intent the brief states). A slice's session is never
   given a turn by a start: its :given-by is read from its workstream's class, not from the marker
   whoever gave the instruction wrote, so every instruction nido gave a slice for no Run is one the
   conductor answers for. A turn whose latest prompt carries no such token — a person's — is the
   person's, whatever is outstanding on the session. A conversation unknown here is taken to be
   the one the record's facet drives."
  [s conversation]
  (or (:conductor s)
      (let [{:keys [pending lapsed session-id]} (:claude s)
            conversation (or conversation session-id)
            slice?       (some-> (ws/read-ws (:project s) (:workstream-id s)) drain/slice?)
            conductors   (filter #(and % (nil? (:for %)) (or slice? (not= :start (:given-by %))))
                                 (cons pending lapsed))]
        (boolean
         (when (and conversation (seq conductors))
           (when-let [p (turn-prompt conversation)]
             (some #(str/includes? (str (:prompt p)) (str "[nido-send " (:token %) "]")) conductors)))))))

(defn- record-at
  "The session record of the nido session `cwd` is in — its worktree or its home — or nil."
  [cwd]
  (when-let [{:keys [project session]} (or (lifecycle/session-from-cwd cwd)
                                           (when-let [[p n] (lifecycle/session-home-coords-from-cwd cwd)]
                                             {:project p :session n}))]
    (let [p (keyword project)]
      (when-let [ws-id (session/workstream-id-for p session)]
        (session/read-session p ws-id session)))))

(defn ^{:malli/schema [:=> [:cat :string] [:maybe [:enum :start]]]}
  starter
  "Who gives a session's first instruction when a start is made from `cwd`: :start, unless `cwd`
   is in a nido session whose turn — its conversation unknown here, so taken to be the driven one —
   the conductor answers for (`answering-for-conductor?`); then nil, so a start made on the conductor's instruction cannot hand
   its child the person's acts the gate refuses the conductor. A cwd that cannot be read is no
   session's."
  [cwd]
  (when-not (some-> (try (record-at cwd) (catch Throwable _ nil)) (answering-for-conductor? nil))
    :start))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :string]]}
  refusal
  "Why a Bash command must not run, or nil: a person's act — an intent, a triage
   acceptance, a phase gate, a blocker's answer, a landing, a PR merge or a close — run from the
   conductor's session, or from any other session's conversation in a turn whose latest prompt
   carries an instruction the conductor answers for (`answering-for-conductor?`), judged over
   what the commit gate reads the command to do — save what an unspent grant of the person's hands
   the seat (`granted?`). A raw merge is refused under any grant, its refusal naming the verb the
   grant admits while a :merge grant stands; and adding a ref is refused too while the session's
   workstream, or the one the ref is added to, holds one, since a ref is what makes a PR its own. Pushing, whatever the branch, and
   opening or readying a PR are not a person's act. Takes a Claude Code `PreToolUse` input; nil
   for any other session, and — failing open — when the session cannot be read."
  [{:keys [tool_name tool_input cwd session_id]}]
  (when-let [s (try
                 (when (and (= "Bash" tool_name) (string? (:command tool_input)) (string? cwd))
                   (record-at cwd))
                 (catch Throwable _ nil))]
    (when (answering-for-conductor? s session_id)
      (let [acts (commit-gate/acts (:command tool_input) cwd)
            held (delay (merge-grant-held? s))]
        (if-let [[a act] (some #(when-not (granted? s %) (some->> (persons-act %) (vector %))) acts)]
          (cond
            (and (= :gh-pr (:kind a)) (not (:conductor s)) @held)
            (str "Blocked: " act " is the person's to do, even under their grant. The grant they gave "
                 "lands this workstream's own PR through `bb --config <nido>/bb.edn "
                 "nido:workstream:pr:land :pr <owner/repo#n>` — land it with that instead.")

            (and (= :gh-pr (:kind a)) @held)
            (str "Blocked: " act " is the person's to do, even under their grant. The grant they gave "
                 "lets a seat land this workstream's own PR through `nido:workstream:pr:land` — "
                 "instruct the seat to land it.")

            :else
            (str "Blocked: " act " is the person's to do, and this session "
                 (if (:conductor s) "is the workstream's conductor" "is working on the conductor's instruction")
                 ". Escalate instead: say what the person needs to do and why"
                 (when-not (:conductor s) ", and end your turn")
                 "."))
          (when (some #(and (refs-own-prs? %) (or @held (names-merge-grant? (:args %)))) acts)
            (str "Blocked: adding a ref to this workstream while the person's grant to land its "
                 "own PR stands would widen what that grant reaches. Land a PR the workstream "
                 "already records, or escalate: say which PR needs recording and why, and end your turn.")))))))
