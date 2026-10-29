(ns nido.coordinator.lane.conductor
  "Who decides what a workstream's seats do next, and what reaches the person.

   HIDES what a hand-back is. A turn end returns to whoever gave the instruction it ended, as
   conduct recorded when it queued it: one recorded as given for a Run is that Run's, and boot
   settles it; every other instruction nido gives a session it drives on the workstream — a seat
   message, a decided instruction, an occupant's brief, a gate reply no Run awaits — is the
   conductor's; a turn nido did not give is a person's, which holds the session, so its end is no
   hand-back, and the hold, handed back, is. Each act of the person's recorded on the workstream is
   a hand-back too. Every hand-back is posted once to the conductor through the seat-message log,
   carrying the position the pipeline derives at that moment, and answered by exactly one
   :conduct-decision — the conductor's, or an escalation nido writes when the conductor turn it was
   delivered for ends leaving it unanswered.

   Also the conductor's lifecycle against its workstream's: a workstream is WORKED once, since
   conductors began on this host — the instant the first conductor pass recorded — a session other
   than its conductor has been recorded on it or a hand-back has been logged on it, so a workstream
   worked only before then, however long ago, is not worked until a session starts on it or nido
   gives it an instruction no Run gave — and every open, worked workstream has one
   conductor, started by a conduct pass while its session is not running and retired once the
   workstream is closed. A workstream never worked — an item queued in the intake inbox — has
   none. It is briefed from the records and an escalation guidance document a person edits
   without changing code.

   And that a start carries its session: the first session a person's start or a Run's start
   brings up on a workstream whose generation fixes no ids becomes its whole seat's occupant.

   Which commands are a person's act is this namespace's; how a command is read for what it does
   is the commit gate's."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.coordinator.lane.drive :as drive]
   [nido.coordinator.lane.embodied-strata :as embodied]
   [nido.coordinator.lane.pipeline :as pipeline]
   [nido.coordinator.lane.seats :as seats]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.generation :as generation]
   [nido.coordinator.record.seats :as rseats]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.session-events :as session-events]
   [nido.coordinator.record.standing :as standing]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.platform.core :as core]
   [nido.platform.io :as io]
   [nido.platform.task-args :as task-args]
   [nido.session.commit-gate :as commit-gate]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.state :as sstate]))

;; ── reading the workstream ──────────────────────────────────────────────────

(defn- since-path [] (str (fs/path (cstate/coordinator-dir) "conductors-since.edn")))

(defn- conducting-since
  "The instant conductors began on this host, as an ISO string: what the first call recorded,
   and recorded now on that first call. Never moves once written — moving it forward would take a
   conductor from a workstream that already has one by right."
  []
  (or (:since (io/read-edn (since-path)))
      (let [now (clock/now-iso)]
        (fs/create-dirs (fs/parent (since-path)))
        (io/write-edn! (since-path) {:since now})
        now)))

(defn- worked?
  "Whether, at or after `since`, an ISO instant, a session other than the conductor's has been
   recorded on the workstream or a hand-back has been logged on it."
  [project ws-id since]
  (boolean (or (some #(and (not (:conductor %)) (not (neg? (compare (str (:created-at %)) since))))
                     (session/list-sessions project ws-id))
               (seats/handed-back-since? project ws-id since))))

(defn- conductor-of
  "The workstream's conductor record, or nil."
  [project ws-id]
  (first (filter :conductor (session/list-sessions project ws-id))))

(defn- position
  "Where the pipeline places the workstream now, as {:position <kw> :next <map or nil>}."
  [project ws-id]
  (let [{:keys [at next]} (pipeline/of project ws-id)]
    {:position (or at :unplaceable) :next next}))

;; ── carrying a start ────────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :SessionName [:? [:maybe :string]] [:? [:maybe :string]] [:? [:maybe [:enum :start]]]] :map]}
  carry!
  "Carry the workstream a start just brought `session-name` up on: when its current generation fixes
   no ids and its whole seat has no occupant, that session becomes the whole seat's occupant,
   driven by nido — its first instruction `brief` when the start was given one, on `model` when
   given, given by `given-by` (:start when the start was a person's or a spawn's not made on the
   conductor's instruction, `starter`; nil: the conductor answers for it) — and otherwise
   nothing changes. Called by the session-up verb, spawn, the work plane's start and the daemon's
   start of a Run's conversation, never by birth! or the orphan sweep. Answers {:carried {:seat
   :session}} or {:carried nil :why <reason>}; never throws for a seat it does not take."
  [project ws-id session-name & [brief model given-by]]
  (let [whole (first (filter :whole (rseats/seats project ws-id)))]
    (cond
      (nil? whole)
      {:carried nil :why :fixes-ids}

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
   records give them at that start, and the escalation guidance."
  [project ws-id]
  (let [design (standing/governing-design project ws-id)
        {:keys [position next]} (position project ws-id)]
    (str "You conduct workstream " ws-id " of " (name project) ". You occupy no seat and write no code. "
         "Each hand-back nido gives you — a seat's turn that ended, or an act of the person's — you "
         "answer with exactly one decision, recorded with `bb nido:seats:decide :hand-back <id>` and "
         "either `:seat <id> :text \"…\"` (or `:instruct '[{:seat … :text …} …]'` for several seats) "
         "or `:escalate \"…\"`, naming what the person is needed for.\n\n"
         "Seats: " (seats-line (rseats/seats project ws-id)) "\n"
         "Governing design: " (design-line design) "\n"
         "Position: " (name position) (when next (str ", next " (pr-str next))) "\n\n"
         "Decide on the position, governing design and seats each hand-back carries, read from the "
         "records at that hand-back, not on this brief or what you remember of this conversation.\n\n"
         (guidance project))))

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

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:sequential :map]]}
  conductors!
  "On every conduct pass: start, on its one conductor record and its brief, the conductor of each
   open workstream of `project` that is worked — since conductors began on this host, a session
   other than its conductor recorded on it or a hand-back logged on it — whose conductor's session
   is not running, and retire the conductor of each closed or unworked one that still has a process
   or an up session — a ticket a person settled in Notion closes its workstream here as the
   pipeline places it (`pipeline/settled-in-notion`), and one taken back out starts it again. A workstream not worked since gets none.
   Answers [{:ws-id :started|:retired :session}] for what it did; a failure is logged and the
   rest carried on with."
  [project]
  (let [live  (delay ((requiring-resolve 'nido.coordinator.lane.conduct/live-states) {}))
        since (conducting-since)]
    (vec
     ;; Sessions are read first and a ledger only where they leave a conductor to start or retire:
     ;; reading every ledger costs seconds a pass.
     (for [ws-id (ws/list-ids project)
           :let  [c (conductor-of project ws-id)]
           :when (or c (worked? project ws-id since))
           :let  [w (ws/read-ws project ws-id)
                  act (cond
                        (nil? w) nil
                        (or (ws/closure w) (pipeline/settled-in-notion project ws-id w)
                            (not (worked? project ws-id since)))
                        (when (and c (needs-retiring? project c @live)) :retired)
                        (and (worked? project ws-id since) (not (and c (running? c @live))))
                        :started)]
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
               :when (re-matches #"seat .+ could not be started" (str (:needs b)))]
           (do (ws/append-entry! project ws-id {:kind :blocker-answered}
                                 (pr-str {:format :blocker-answered :blocker-seq (:seq b) :resumed nil :by :nido
                                          :summary "the seat it named has since been occupied"}))
               (:seq b))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :map]}
  reseat!
  "On every conduct pass, for a carried workstream: retire each occupant whose seat the current
   seats do not include, and occupy each current seat with the embodiment that carries it — the
   default embodiment while the generation fixes no ids, the strata embodiment while it fixes
   strata — escalating, by name, seats no embodiment carries and a seat whose start failed in this
   pass, and answering that escalation as nido once every seat is held. A closed workstream is
   left as it is. Answers {:retired :occupied :escalated}."
  [project ws-id]
  (if-not (and (carried? project ws-id) (not (some-> (ws/read-ws project ws-id) ws/closure)))
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
                      #(when-not (some (fn [b] (= needs (:needs b))) (open-blockers project ws-id))
                         (drive/park! project ws-id
                                      {:stage   :reseat :outcome :uncarried
                                       :summary (str "the seats of this generation cannot be occupied: " (ex-message e))
                                       :needs   needs})))
                     {:retired retired :escalated ids})]
      (cond
        (empty? empty)
        (do (answer-filled! project ws-id) {:retired retired})

        ;; A launch that fails is named by an escalation in the pass it failed in, and tried again
        ;; on the next; once one succeeds, that escalation is answered.
        (:whole (first current))
        (try
          (let [occupied (seats/occupy! project ws-id (seats/default-embodiment) (:id (first current)))]
            (answer-filled! project ws-id)
            {:retired retired :occupied [occupied]})
          (catch Exception e
            (escalate (str "seat " (str/join ", " ids) " could not be started") e)))

        :else
        (try
          (let [occupied (:occupied (embodied/carry! project ws-id))]
            (answer-filled! project ws-id)
            {:retired retired :occupied occupied})
          ;; A refusal stands until the design changes; a failed launch is the same escalation
          ;; the whole seat's is, answered once a later pass holds every seat.
          (catch Exception e
            (escalate (if (embodied/refused? e)
                        (str "no embodiment carries seats " (str/join ", " ids))
                        (str "seat " (str/join ", " ids) " could not be started"))
                      e)))))))

;; ── hand-backs ──────────────────────────────────────────────────────────────

(defn- hand-back-text
  [{:keys [what session seat said position next design seats]}]
  (str "Hand-back: " what "\n"
       "Session: " session (when seat (str " (seat " seat ")")) "\n"
       "Position: " (name position) (when next (str ", next " (pr-str next))) "\n"
       "Governing design: " (design-line design) "\n"
       "Seats: " (seats-line seats) "\n"
       (when said (str "\nIts last message:\n\n" said "\n"))))

(defn- post-hand-back!
  "Post one hand-back to the workstream's conductor under `key`, carrying the position the
   pipeline derives now and the governing design and seats as the records give them now — so a
   conductor started under an earlier design decides on this one."
  [project ws-id key fields]
  (let [pos (position project ws-id)
        msg (merge fields pos {:design (standing/governing-design project ws-id)
                               :seats  (rseats/seats project ws-id)})]
    (seats/post! project ws-id {:key key :kind :hand-back :to :conductor :text (hand-back-text msg)
                                :session (:session fields) :position (:position pos)})))

(defn ^{:malli/schema [:=> [:cat :map] [:sequential :map]]}
  hand-backs!
  "From one conduct pass's report, log to the workstream's conductor each turn end, on a session
   nido drives other than the conductor, that ended an instruction nido gave and recorded as given
   for no Run — once per turn end, keyed on its instruction's token — with the session, its seat
   when it has one, the turn's last message, the position the pipeline derives at that moment, and
   the governing design and seats as the records give them then.
   A turn nido did not give is the person's, and its end is logged to no one. Answers the
   hand-backs posted."
  [{:keys [turn-ends]}]
  (vec
   (for [{:keys [project ws-id session event instruction run]} turn-ends
         :when (and instruction (nil? run))
         :let  [s (session/read-session project ws-id session)]
         :when (and s (not (:conductor s)))]
     (post-hand-back! project ws-id (str "turn:" session ":" (:token instruction))
                      {:what "a turn nido gave ended" :session session :seat (get-in s [:seat :id])
                       :said (:last-message event)}))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :SessionName] :map]}
  hand-back!
  "Log a person's hold on `session-name`, handed back, to the workstream's conductor — once per
   hand-back, keyed on when nido began driving it again — as hand-backs! logs a turn end, the
   last message its conversation's newest turn end reported among it."
  [project ws-id session-name]
  (let [s    (session/read-session project ws-id session-name)
        ;; The hold's last turn end is the newest stop its conversation reported.
        said (some->> (get-in s [:claude :session-id])
                      (#(:events (session-events/events-since % nil)))
                      (filter #(= :stop (:event %)))
                      last
                      :last-message)]
    (post-hand-back! project ws-id (str "hold:" session-name ":" (get-in s [:claude :driving-since]))
                     {:what "a person's turn held this session and was handed back"
                      :session session-name :seat (get-in s [:seat :id]) :said said})))

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

(defn- record-decision!
  "Append the :conduct-decision `entry` and carry it out, as one step under the ledger's append lock
   (`ws/appending!`), so no opening or design approval falls between: everything is checked before
   anything is written — the seats an instruction names, the decision itself, and an escalation's
   blocker — and each effect runs only once what it depends on is taken. An escalation parks the
   workstream on its blocker, which the decision then cites; an instruction is posted to each seat
   it names once the decision is recorded. A seat that is gone, or a decision or blocker the ledger
   refuses, throws with nothing written or posted. Answers the entry."
  [project ws-id {:keys [hand-back decision] :as entry}]
  (let [[verb body] decision
        halt        (when (= :escalate verb)
                      {:stage :conduct :outcome :escalated
                       :summary "the conductor escalated a hand-back" :needs (:needs body)})]
    (ws/appending!
     project ws-id
     (fn [{:keys [check! append!]}]
       (when (= :instruct verb)
         (let [seat-ids (set (map :id (rseats/seats project ws-id)))]
           (when-not (every? (comp seat-ids :seat) body)
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
             (doseq [[i {:keys [seat text]}] (map-indexed vector body)]
               (seats/post-held! project ws-id w {:key (str "decision:" hand-back ":" i) :kind :instruction
                                                  :to seat :text text}))))
         entry)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :ConductDecision] :DecideResult]}
  decide!
  "Record the conductor's answer to a hand-back — {:hand-back <message id> :decision [:instruct
   [{:seat :text} …]] | [:escalate {:needs}]} — with the position the pipeline derives now, and
   carry it out. Refused, by name, from any session but the conductor's (:not-the-conductor), for
   an id that is no hand-back (:not-a-hand-back) or one already answered (:already-answered), and
   for an instruction to anything that is not a current seat (:not-a-seat). Answers [:accepted
   entry] or [:refused reason]."
  [project ws-id {:keys [hand-back decision]}]
  (deciding
   project ws-id
   #(cond
      (not (conducting? project ws-id))           [:refused :not-the-conductor]
      (nil? (hand-back-message project ws-id hand-back)) [:refused :not-a-hand-back]
      (answered? project ws-id hand-back)         [:refused :already-answered]
      :else
      (let [{:keys [position next]} (position project ws-id)]
        (try
          [:accepted (record-decision! project ws-id (cond-> {:format :conduct-decision :hand-back hand-back
                                                               :position position :by :conductor
                                                               :decision decision}
                                                        next (assoc :next next)))]
          (catch clojure.lang.ExceptionInfo e
            (if (= :not-a-seat (:reason (ex-data e)))
              [:refused :not-a-seat]
              (throw e))))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :SessionName [:maybe :map]] [:sequential :map]]}
  close-unanswered!
  "When a conductor turn ends, escalate the hand-back delivered to it for that turn — the one
   delivered under the ended `instruction`'s token, to its running conversation — if the turn left
   it unanswered, naming that the conductor reached no decision. A hand-back not yet delivered,
   or delivered for a turn that has not ended, stays owed. Answers the escalations written."
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
          (let [{:keys [position next]} (position project ws-id)]
            [(record-decision! project ws-id (cond-> {:format :conduct-decision :hand-back (:id m)
                                                      :position position :by :nido
                                                      :decision [:escalate {:needs "the conductor reached no decision on this hand-back — decide it, or tell the conductor how"}]}
                                               next (assoc :next next)))])))
      [])))

;; ── the person's acts ───────────────────────────────────────────────────────

(def ^:private person-kinds
  "The ledger kinds only a person's act writes."
  #{:intent :design-approved :triage-accepted :phase-gate :blocker-answered :merged :closed})

(defn- persons-act?
  "Whether ledger entry `e` of a person kind is an act of the person's — not a blocker's answer
   nido recorded for an act, nor an entry nido wrote, nor a grant a design round carried forward."
  [e]
  (and (not (:act e)) (not= :nido (:by e)) (not (:carried e))))

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

(defn- at-a-persons-step? [{:keys [next]}]
  (or (= :human (:mode next)) (= :establish-intent (:stage next))))

(def ^:private person-steps
  "Each of the person's steps: the kinds of their act that take it — a step's other acts answer no
   blocker escalated at it — and the kind of record the step acts on, read off the ledger as the
   escalation found it (`found-before`); nil for :establish-intent, whose act writes the goal and
   acts on no earlier record."
  {:establish-intent         {:kinds #{:intent}}
   :accept-triage            {:kinds #{:triage-accepted}  :target :verdict}
   :approve-design           {:kinds #{:design-approved}  :target :design}
   :acknowledge-invalidation {:kinds #{:design-approved}  :target :design}
   :assert-gate              {:kinds #{:phase-gate}       :target :design}
   :land                     {:kinds #{:merged :closed}   :target :design}
   :answer-blocker           {:kinds #{:blocker-answered} :target :blocker}})

(def ^:private act-cites
  "Where each kind of the person's act cites the record it acts on. An :intent and a :closed cite
   none — a close names a design only between phases — and take the step they are kinds of."
  {:blocker-answered [:blocker-seq]
   :triage-accepted  [:triage-seq]
   :design-approved  [:design :seq]
   :phase-gate       [:design :seq]
   :merged           [:design :seq]})

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
   :design  (fn [_ _ w] (generation/current-design w))})

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
   never a blocker's answer nido recorded for an act, an entry nido wrote, or a grant a design
   round carried forward (`persons-act?`) — to its conductor as a hand-back, once per
   entry, with the position the pipeline derives at that moment. Answers what it recorded and
   posted."
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
           (for [e (sort-by :seq (mapcat #(ws/entries-of project ws-id %) person-kinds))
                 :when (and (pos? (compare (str (:at e)) (str (:created-at c))))
                            (open? e) (persons-act? e))]
             (assoc (post-hand-back! project ws-id (str "act:" (:seq e))
                                     {:what (str "the person's " (name (:format e)) " at entry " (:seq e))
                                      ;; No turn ended: what the person said is the act as recorded.
                                      :session (:name c) :said (pr-str e)})
                    :ws-id ws-id))))))
    (ws/list-ids project))))

;; ── refusing the person's acts ──────────────────────────────────────────────

(def ^:private person-tasks
  "The nido tasks that write a person's act on any path they can take — whatever their arguments,
   and whether or not they then exit unsuccessfully: a person-kind entry or a close. A carried
   `:design-approved` the design loop writes is not one — it grants nothing the person did not —
   so the review tasks are not here."
  #{"nido:design:approve" "nido:workstream:blocker:answer" "nido:land:record" "nido:recovery:land"
    "nido:workstream:close" "nido:phase:advance" "nido:ticket:complete" "nido:ticket:apply"
    "nido:workstream:generation:open"
    ;; :closed — set-stage! to done, a dismissal, a restored recovery, a resolved between-phases
    ;; round, a vetoed reservation, a backfilled orphan ticket
    "nido:workstream:stage:advance" "nido:ticket:dismiss" "nido:recovery:restore"
    "nido:findings:resolve" "nido:improvement:reserve" "nido:status:backfill"
    ;; a close, after the push it lands
    "nido:improvement:discharge"
    ;; a child workstream's :intent
    "nido:workstream:fork"})

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

(defn- persons-act
  "What a person's act `act` — one of commit-gate/acts — is, as a phrase, or nil. Pushing and
   opening or readying a PR are not a person's act; merging one is."
  [{:keys [kind verb task args]}]
  (case kind
    :gh-pr     (cond (= "merge" verb)   "gh pr merge"
                     (= :unread verb)   "a gh command whose verb the gate cannot read, which may merge a pull request"
                     (= "api" verb)   "a gh api write that may merge a pull request")
    :nido-task (cond (person-tasks task) task
                     (and (generic-appends task) (appends-person-kind? args)) (str task " of a person's kind"))
    nil))

(defn- answering-for-conductor?
  "Whether session record `s` is the conductor, or conversation `conversation` is in a turn
   following an instruction the conductor answers for — one nido gave its driven conversation and
   recorded for no Run, and not given by the start that brought the session (:given-by :start:
   a person's start, or a spawn's brief, whose turn may write the intent the brief states).
   Another conversation in the same folder received no such instruction; one whose conversation,
   or the record's, is unknown is taken to be the driven one."
  [s conversation]
  (or (:conductor s)
      (let [{:keys [pending driver session-id]} (:claude s)]
        (and pending (= :nido driver) (nil? (:for pending)) (not= :start (:given-by pending))
             (or (nil? session-id) (nil? conversation) (= session-id conversation))))))

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
   answers for the conductor; then nil, so a start made on the conductor's instruction cannot hand
   its child the person's acts the gate refuses the conductor. A cwd that cannot be read is no
   session's."
  [cwd]
  (when-not (some-> (try (record-at cwd) (catch Throwable _ nil)) (answering-for-conductor? nil))
    :start))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :string]]}
  refusal
  "Why a Bash command must not run, or nil: a person's act — an intent, a design grant, a triage
   acceptance, a phase gate, a blocker's answer, a landing, a PR merge or a close — run from the
   conductor's session, or from any other session's in a turn following an instruction the
   conductor answers for, judged over what the commit gate reads the command to do. Pushing,
   whatever the branch, and opening or readying a PR are not a person's act. Takes a Claude Code `PreToolUse` input; nil for any other session, and
   — failing open — when the session cannot be read."
  [{:keys [tool_name tool_input cwd session_id]}]
  (when-let [s (try
                 (when (and (= "Bash" tool_name) (string? (:command tool_input)) (string? cwd))
                   (record-at cwd))
                 (catch Throwable _ nil))]
    (when (answering-for-conductor? s session_id)
      (when-let [act (some persons-act (commit-gate/acts (:command tool_input) cwd))]
        (str "Blocked: " act " is the person's to do, and this session "
             (if (:conductor s) "is the workstream's conductor" "is working on the conductor's instruction")
             ". Escalate instead: say what the person needs to do and why"
             (when-not (:conductor s) ", and end your turn")
             ".")))))
