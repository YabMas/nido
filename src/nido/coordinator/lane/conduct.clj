(ns nido.coordinator.lane.conduct
  "Who drives each Claude Code background session, and what nido does about the ones it drives.

   HIDES the driving policy: a session's driver, when a turn has really ended, when the next
   instruction goes out and when it is held back, when a person is told a turn ended or a session
   is blocked, and what is restarted after a reboot. ORIGIN is recorded on the session's :claude
   facet and decides none of it — a session a person started and one nido started are driven and
   recovered the same way.

   Four rules carry the rest:
     - nido gives an instruction only to a session it drives, with nothing outstanding, and the
       substrate reporting it done with no work of its own still running;
     - every instruction carries a token recorded as pending BEFORE it is given, so a Stop ends
       the instruction whose token a prompt of its turn carried, and a turn whose prompts carry
       none — a person's — ends none. A person's prompt changes nothing of who drives the
       session: nido drives it from its dispatch or adoption until release!;
     - a turn ends when the session's own Stop says so, never when a process exits;
     - every conversation of every session on a nido workstream is read once, from the position
       its record keeps for it, so each turn end it reports is reported once."
  (:require [clojure.string :as str]
            [nido.coordinator.agent.substrate :as substrate]
            [nido.coordinator.record.clock :as clock]
            [nido.coordinator.record.session :as session]
            [nido.coordinator.record.session-events :as events]
            [nido.coordinator.record.standing :as standing]
            [nido.coordinator.record.state :as cstate]
            [nido.coordinator.record.workstream :as ws]
            [nido.platform.io :as io]
            [nido.platform.project :as project]
            [babashka.process :as p]
            [nido.platform.process :as process]
            [babashka.fs :as fs]))

;; ── tokens ──────────────────────────────────────────────────────────────────

(defn- mint [] (str (random-uuid)))

(defn ^{:malli/schema [:=> [:cat :string :string] :string]}
  tagged
  "`text` as nido gives it: the instruction, then a last line carrying its token. The session's
   briefing tells it the line is nido's bookkeeping."
  [text token]
  (str text "\n\n[nido-send " token "]"))

(defn- carries? [prompt token]
  (boolean (and token (str/includes? (str prompt) (str "[nido-send " token "]")))))

;; ── reading the session's own events ────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :string] :map]}
  baseline-shown
  "What a session's recorded events show of the launch baseline: whether nido's hook recorded a
   start for it and the directory its latest recorded start was in, the permission mode its
   latest event itself reports (nil when that event reports none), and that latest event.
   Notifications are not counted as its latest event: Claude Code sends one to a session sitting
   idle after its Stop, and it carries no permission mode — counted, every session idle past a
   minute would read as off the baseline and as no longer stopped."
  [session-id]
  (let [evs   (remove #(= :notification (:event %)) (:events (events/events-since session-id nil)))
        start (last (filter #(= :session-start (:event %)) evs))]
    {:started?  (some? start)
     :start-cwd (:cwd start)
     :mode      (:permission-mode (last evs))
     :latest    (last evs)}))

(def ^:private unattended-modes
  "The permission modes a session nido drives may run in: neither stops a sent turn on a prompt
   nobody is there to answer. auto is the launch baseline's; bypassPermissions is a session's a
   person started through nido work, which passes the permission skip on its command line."
  #{"bypassPermissions" "auto"})

(defn ^{:malli/schema [:=> [:cat :map [:maybe :map]] [:maybe [:tuple :keyword :string]]]}
  baseline-refusal
  "Why the session's own recorded events do not show the launch baseline, as [reason message], or
   nil when they do: its latest start recorded by nido's hooks, from the directory the listing
   `row` shows it in, and a mode that asks nobody (`unattended-modes`) on its latest event —
   given what `baseline-shown` read. Each half needs evidence that shows it: a start or a row naming no directory shows none.
   The one check adoption, a send, a wake and the session list all make."
  [{:keys [started? start-cwd mode]} row]
  (cond
    (not started?)
    [:no-session-start "nido's hooks recorded no start for it — start it in a nido session home or worktree"]
    (or (nil? start-cwd) (not= (:cwd row) start-cwd))
    [:started-elsewhere (str "It runs in " (:cwd row) ", but nido's hooks last recorded its start in "
                             (or start-cwd "an unknown directory") " — start it in a nido session home or worktree")]
    (not (unattended-modes mode))
    [:not-auto (str "Its latest recorded permission mode is " (or mode "unknown") ", not auto or bypassPermissions — switch it to one of them first")]))

(defn- left-running
  "The id of a process an adapter call that threw says it left running, or nil when it left
   none. Every catch around an adapter call reads this, so no failure that leaves a session
   running is handled as one that started nothing."
  [t]
  (:left-running (ex-data t)))


;; ── read positions ──────────────────────────────────────────────────────────

(defn- instant [at] (try (java.time.Instant/parse (str at)) (catch Exception _ nil)))

(defn- after?
  "Whether the instant `a` falls after `b`, both ISO strings; false when either does not parse."
  [a b]
  (let [a (instant a) b (instant b)]
    (boolean (and a b (.isAfter ^java.time.Instant a b)))))

(defn- not-before?
  "Whether the event time `at` is not before `since`. A time that does not parse, or no `since`,
   counts as not before: what cannot be placed is read rather than lost."
  [at since]
  (let [a (instant at) s (instant since)]
    (or (nil? a) (nil? s) (not (.isBefore ^java.time.Instant a s)))))

(defn- reading-of
  "The record `s`'s :reading, or — read for the first time — one beginning `now`, keeping as its
   driven conversation's position the cursor a facet written before positions were kept holds,
   so nothing that facet had read is replayed and nothing it had not is skipped."
  [s now]
  (or (:reading s)
      {:since     now
       :positions (if-let [c (get-in s [:claude :cursor])]
                    {(get-in s [:claude :session-id]) c}
                    {})}))

(defn- position-of
  "The read position of conversation `id` on record `s`, or nil when it has none: its entry in
   the record's :reading, else — for the driven conversation of a facet written before positions
   were kept — that facet's cursor."
  [s id]
  (or (get-in s [:reading :positions id])
      (when (= id (get-in s [:claude :session-id])) (get-in s [:claude :cursor]))))

(defn- read-conversation
  "The events of conversation `id` of record `s` past its read position, and the position after
   them, as {:events :cursor}. A conversation with no position is seeded at its first event
   recorded at or after the record's :since: what it recorded before conduct began reading the
   session is never read."
  [id s]
  (if-let [p (position-of s id)]
    (events/events-since id p)
    (let [{evs :events end :cursor} (events/events-since id nil)
          since (get-in s [:reading :since])]
      {:events (filterv #(not-before? (:at %) since) evs) :cursor end})))

;; ── the decision ────────────────────────────────────────────────────────────

(def ^:private lapsed-kept
  "How many lapsed instructions a facet keeps for a late Stop to end."
  20)

(defn- carried-instruction
  "The instruction among `facet`'s pending and lapsed ones whose token `prompt` carries, or nil."
  [facet prompt]
  (first (filter #(carries? prompt (:token %)) (cons (:pending facet) (:lapsed facet)))))

(defn- read-event
  "One event of the driven conversation folded into the facet; actions it calls for appended.
   Who prompts decides nothing of who drives: a prompt carrying an instruction's token marks that
   instruction delivered, and any other prompt only reports itself (:prompted) when a person
   typed it."
  [{:keys [facet] :as acc} ev]
  (let [driving? (= :nido (:driver facet))]
    (case (:event ev)
      :prompt
      (if-let [i (carried-instruction facet (:prompt ev))]
        (if (= i (:pending facet))
          (assoc-in acc [:facet :pending :seen?] true)
          (update-in acc [:facet :lapsed] (fn [ls] (mapv #(if (= i %) (assoc % :seen? true) %) ls))))
        (cond-> acc (session/person-prompt? ev) (update :actions conj [:prompted ev])))

      :stop
      ;; The instruction this Stop ended: the one whose token a prompt of its turn carried — nil
      ;; for a turn whose prompts carried none.
      (let [{:keys [pending lapsed]} facet
            ended (if (:seen? pending) pending (first (filter :seen? lapsed)))]
        (-> acc
            (update :actions conj [:turn-ended ev ended])
            (cond-> (and ended (= ended pending)) (assoc-in [:facet :pending] nil)
                    (and ended (not= ended pending)) (update-in [:facet :lapsed] #(vec (remove #{ended} %))))
            ;; Whether its end goes to whoever answers for the workstream — no Run's.
            (assoc-in [:facet :ended-unrun?] (nil? (:for ended)))
            (assoc-in [:facet :own-work?]
                      (pos? (+ (or (:background-tasks ev) 0) (or (:crons ev) 0))))))

      :permission-denied
      (cond-> acc driving? (update :actions conj [:denial ev]))

      acc)))

(defn- lapsed?
  "Whether the instruction `pending` has lapsed: no prompt carrying its token was recorded, a Stop
   recorded after it was given is its conversation's `latest` event, and the listing `row` shows
   the session done."
  [pending latest row]
  (boolean (and pending (not (:seen? pending))
                (= :stop (:event latest)) (after? (:at latest) (:at pending))
                (= "done" (:state row)))))

(defn- current
  "`facet` as this design reads it: a hold written before is ignored and dropped, and a :person
   driver is nobody."
  [facet]
  (cond-> (dissoc facet :hold :idle-seen)
    (= :person (:driver facet)) (assoc :driver :nobody)))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :map]]}
  outstanding
  "The instruction still outstanding on the session record `s`'s facet as its driven
   conversation's event log stands now: its :pending as folding the events recorded past that
   conversation's read position would leave it — the fold conduct! applies — so nil once the
   session has recorded the Stop that ends it, whether or not a pass has folded that Stop yet.
   Reads the log; writes nothing.

   Anything outside conduct that acts on an instruction as unfinished asks this, not the
   facet's :pending: a pass folds a session's events once, at its start, and a Stop recorded
   after that read is on the log but not yet on the facet."
  [s]
  (when-let [facet (:claude s)]
    (when (:pending facet)
      (let [{evs :events} (read-conversation (:session-id facet) s)]
        (get-in (reduce read-event {:facet (current facet) :actions []} evs) [:facet :pending])))))

(defn ^{:malli/schema [:=> [:cat :map [:maybe :map] [:sequential :map] :map] :map]}
  decide
  "The next facet and the actions it calls for, given a session's :claude facet, its row in the
   substrate's listing (nil when the listing has none), the driven conversation's events past its
   read position, and {:now <iso> :token <fresh> :hold-sends? <bool> :baseline <:shown, or why the
   session's events do not show the launch baseline> :latest <its latest recorded event> :owed
   <the seat messages the session is owed, oldest first, each {:id :text}>}. Pure: it gives
   nothing, it says what to give.

   An instruction is outstanding from when it is given until a Stop whose turn carried its token
   ends it, or until it lapses (`lapsed?`); a lapsed one keeps back no delivery and, replaced as
   :pending, is kept under :lapsed for a later Stop to end. A prompt nobody tagged ends nothing
   and changes nothing of who drives.

   Actions: [:turn-ended event instruction] — instruction the one nido gave whose token a
   recorded prompt of the turn carried, with :for naming the Run it was given for and :message
   the seat message it was, nil for a turn whose prompts carried none — [:prompted event] for a
   prompt a person typed, [:denial event], [:notify-blocked unrun?] — unrun? when nothing is
   outstanding and its last Stop ended no instruction recorded for a Run — [:refused why] when
   only the baseline keeps a queued instruction back, and at most one of
   [:send tagged-text token text message-id] / [:wake tagged-text token text message-id] —
   whose token the returned facet already holds as pending, so it is recorded before anything is
   delivered. What is given is the queued instruction, else the oldest seat message owed, whose
   :id the action carries (nil for an instruction). Only a session the listing shows done, or
   blocked with its Stop as its latest recorded event, is given anything; a wake goes only to a
   session whose latest recorded event is its Stop."
  [facet row evs {:keys [now token hold-sends? baseline latest owed]}]
  (let [{f :facet acts :actions} (reduce read-event {:facet (current facet) :actions []} evs)
        blocked?  (= "blocked" (:state row))
        announce? (and blocked? (not (:announced-blocked? f)))
        lapsed    (lapsed? (:pending f) latest row)
        free?     (or (nil? (:pending f)) lapsed)
        f         (assoc f :announced-blocked? blocked?)
        via       (when row (if (:pid row) :send :wake))
        stopped?  (= :stop (:event latest))
        ;; Blocked after its Stop, a session is waiting on the answer a person queued for it;
        ;; blocked mid-turn, it is waiting on something a message would not answer.
        ready-state? (or (= "done" (:state row)) (and blocked? stopped?))
        message   (when (nil? (:queued f)) (first owed))
        text      (if message (:text message) (:queued f))
        gated?    (and (= :nido (:driver f)) free?
                       text row ready-state? (not (:own-work? f))
                       (not hold-sends?)
                       (or (= :send via) stopped?))
        ready?    (and gated? (= :shown baseline))]
    {:facet   (cond-> f
                (and ready? lapsed) (update :lapsed #(vec (take-last lapsed-kept (conj (vec %) (:pending f)))))
                ready? (assoc :pending (cond-> {:token token :at now :via via :seen? false}
                                         (and (not message) (:queued-for f)) (assoc :for (:queued-for f))
                                         message (assoc :message (:id message))))
                (and ready? (not message)) (assoc :queued nil :queued-for nil))
     :actions (cond-> acts
                ;; Blocked after a turn that ended no Run's instruction, it waits on whoever that
                ;; turn end went to. Blocked mid-turn, nothing has gone anywhere, and only a person
                ;; can answer it.
                announce? (conj [:notify-blocked (boolean (and free? (:ended-unrun? f)))])
                (and gated? (not ready?)) (conj [:refused baseline])
                ready?    (conj [via (tagged text token) token text (:id message)]))}))

;; ── finding the sessions ────────────────────────────────────────────────────

(defn- projects [opts]
  (or (:projects opts) (mapv keyword (keys (project/list-projects)))))

(defn ^{:malli/schema [:=> [:cat :map] [:sequential :map]]}
  faceted-sessions
  "Every session record carrying a :claude facet, across the projects in `opts` (default: every
   registered one), as {:project :ws-id :session-name :facet :reading} — :reading being how far
   the record's conversations are read, which `outstanding` reads."
  [opts]
  (for [p      (projects opts)
        ws-id  (ws/list-ids p)
        s      (session/list-sessions p ws-id)
        :when  (:claude s)]
    {:project p :ws-id ws-id :session-name (:name s) :facet (:claude s) :reading (:reading s)}))

(defn- names?
  "Whether `facet` is the facet of the session {:session-id}, by its full id — which every facet
   has. Every comparison of a session against a facet is this one."
  [facet {:keys [session-id]}]
  (= (:session-id facet) session-id))

(defn- row-for
  "A session's row in the listing, as `names?` matches it."
  [listing facet]
  (let [rows (filter #(names? facet {:session-id (:sessionId %)}) listing)]
    (or (first (filter #(= "background" (:kind %)) rows)) (first rows))))

(defn ^{:malli/schema [:=> [:cat :map] [:sequential :map]]}
  dispatching-sessions
  "Every session record with a start in flight — a :dispatching note — across the projects in
   `opts`, as {:project :ws-id :session-name :dispatching}."
  [opts]
  (for [p      (projects opts)
        ws-id  (ws/list-ids p)
        s      (session/list-sessions p ws-id)
        :when  (:dispatching s)]
    {:project p :ws-id ws-id :session-name (:name s) :dispatching (:dispatching s)}))

;; ── the verbs ───────────────────────────────────────────────────────────────

(defn- fresh-facet
  "A facet for the session {:session-id :bg-id}, driven by nido from `now`."
  [origin now {:keys [session-id bg-id]}]
  {:session-id session-id :bg-id bg-id :origin origin :driver :nido :pending nil
   :queued nil :own-work? false :policy {:auto-respawn? false} :driving-since now
   :announced-blocked? false :recovered-boot nil})

(defn- dispatched-facet
  "The facet a start writes once Claude Code has named its session: nido drives it, and the first
   prompt — given under the token the :dispatching note recorded before the start — is
   outstanding."
  [{:keys [token at policy for given-by]} sess]
  (cond-> (assoc (fresh-facet :nido at sess)
                 :pending (cond-> {:token token :at at :via :dispatch :seen? false}
                            for      (assoc :for for)
                            given-by (assoc :given-by given-by)))
    policy (assoc :policy policy)))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  start!
  "Start a conversation for an existing session record as a background session nido drives:
   {:project :ws-id :session-name :cwd :prompt} and optionally :policy, :name, :model, :for —
   the Run the first prompt is given for — :given-by — :start when a person's start or spawn, not
   the conductor, gave that prompt — and :source — the repository `:cwd` was made from, which
   vouches for trusting `:cwd` when Claude Code does not trust it yet.

   The first prompt's token, with the Run it is given for, is recorded in the record's
   :dispatching note before the session starts; the facet is written — and the note cleared —
   only once Claude Code has named the session, so no facet is ever without its session's id.
   Nothing of the record's earlier facet is carried: once the listing shows that session stopped,
   its conversation is over. The record's read positions are left as they are — only which
   conversation the facet names changes — and a record conduct has not read yet begins being
   read now, so the new conversation is read from its first event. Answers the facet written.

   Refused, writing nothing, while the listing shows the record's session alive, whoever drives
   it, so a start over a released record never leaves the earlier session running unrecorded —
   retire it first — and while another start for the record is in flight. Throws when the
   session cannot be started: the note is cleared, or — when the adapter says it left a process
   running that the listing has not named — kept with that process's :bg-id, for conduct! to
   name it."
  [{:keys [project ws-id session-name cwd prompt policy source for given-by] :as opts}]
  (when-not (substrate/trusted? cwd)
    (if (and source (substrate/trusted? source))
      (substrate/ensure-trusted! cwd opts)
      (throw (ex-info (str "Claude Code does not trust " cwd ", and nothing nido made it from does")
                      {:reason :workspace-untrusted :dir cwd :source source}))))
  (let [token   (mint)
        now     (clock/now-iso)
        listing (substrate/listing opts)
        refuse  (fn [reason msg data] (throw (ex-info msg (assoc data :reason reason :session session-name))))]
    (session/update-substrate!
     project ws-id session-name
     (fn [{:keys [claude dispatching] :as m}]
       (when dispatching
         (refuse :dispatching "A start for this session is already in flight" {:dispatching dispatching}))
       (when (and claude (:pid (row-for listing claude)))
         (refuse :live (str "Its session " (:session-id claude) " is alive — retire it first")
                 {:session-id (:session-id claude)}))
       (assoc m
              :reading     (reading-of m now)
              :dispatching (cond-> {:token token :at now}
                             policy   (assoc :policy policy)
                             for      (assoc :for for)
                             given-by (assoc :given-by given-by)))))
    (try
      (let [named (substrate/dispatch! (-> opts (dissoc :for :given-by) (assoc :prompt (tagged prompt token))))]
        (:claude (session/update-substrate!
                  project ws-id session-name
                  (fn [{:keys [dispatching] :as m}]
                    (assoc m :claude (dispatched-facet dispatching named) :dispatching nil)))))
      (catch Throwable t
        (session/update-substrate!
         project ws-id session-name
         (fn [m] (update m :dispatching #(when-let [bg-id (left-running t)] (assoc % :bg-id bg-id)))))
        (throw t)))))

(defn- name-dispatched!
  "Give each start whose process the listing did not name in time its facet, once a listing
   names it by the :bg-id it was launched as. Answers who was named."
  [listing opts]
  (doall
   (for [{:keys [project ws-id session-name dispatching]} (dispatching-sessions opts)
         :let [row (first (filter #(and (:sessionId %) (= (:bg-id dispatching) (:id %))) listing))]
         :when (and (:bg-id dispatching) row)]
     (do (session/update-substrate!
          project ws-id session-name
          (fn [{d :dispatching :as m}]
            (if d
              (assoc m :claude (dispatched-facet d {:session-id (:sessionId row) :bg-id (:id row)})
                       :dispatching nil)
              m)))
         {:project project :ws-id ws-id :session session-name}))))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  adopt!
  "Make nido the driver of a background session that already exists — one a person moved to the
   background, or one nido released — on the session record {:project :ws-id :session-name}, by
   its :session-id. Refused, naming what fails, unless the session is a background session in the
   substrate's listing whose own recorded events show the launch baseline: its latest start
   recorded by nido's hooks from the directory it runs in, and a mode that asks nobody on its latest
   event. Only which conversation the facet names changes: the record's read positions are left
   as they are, so a Stop of that conversation recorded and not yet read is still read — and an
   instruction given to this same session stays outstanding until a Stop ends it. Answers the
   facet written."
  [{:keys [project ws-id session-name session-id policy] :as opts}]
  (let [row (row-for (substrate/listing opts) {:session-id session-id})
        refuse (fn [reason msg & [data]]
                 (throw (ex-info msg (merge {:reason reason :session-id session-id} data))))]
    (if (not= "background" (:kind row))
      (refuse :not-background "Not a background session — move it there first (/bg, or ← on an empty prompt)")
      (when-let [[reason msg] (baseline-refusal (baseline-shown session-id) row)]
        (refuse reason msg)))
    (let [now  (clock/now-iso)
          sess {:session-id session-id :bg-id (:id row)}]
      (:claude (session/update-substrate!
                project ws-id session-name
                (fn [{f :claude :as m}]
                  (let [same? (and f (names? f sess))]
                    (assoc m
                           :reading (reading-of m now)
                           :claude  (-> (current (or f (fresh-facet :person now sess)))
                                        (merge sess {:driver :nido :queued nil :own-work? false :driving-since now}
                                               (when policy {:policy policy}))
                                        (cond-> (not same?) (assoc :pending nil) (not same?) (dissoc :lapsed)))))))))))

(defn- redriven
  "`facet` with `driver` as its driver — unless `retire!` made it :retired. Retirement ends only
   when a conversation is driven on the record again, which a start, a naming or an adoption
   establishes by writing a facet for a session the listing names; release! only changes who
   drives the facet it finds, so it never makes a stopped conversation driven again."
  [facet driver]
  (assoc facet :driver (if (= :retired (:driver facet)) :retired driver)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :SessionName] :map]}
  release!
  "Stop driving a session: nobody drives it, whoever prompted it. The session is untouched and
   keeps running; nothing queued for it is given. A retired session stays retired: releasing it
   drives nothing. An instruction already given stays outstanding until a Stop ends it."
  [project ws-id session-name]
  (:claude (session/update-claude! project ws-id session-name
                                   #(-> (current %) (redriven :nobody) (assoc :queued nil)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :SessionName :string [:? :RunId]] :map]}
  instruct!
  "Queue `text` as the next instruction for a session nido drives; conduct! gives it once the
   session is ready for it, recorded as given for the Run `for` names — for no Run when it names
   none, so its turn end settles no Run. Refused when the session has no facet or one is already
   queued."
  [project ws-id session-name text & [for]]
  (:claude (session/update-claude!
            project ws-id session-name
            (fn [f]
              (cond
                (nil? f) (throw (ex-info "Not a substrate session" {:reason :no-facet :session session-name}))
                (:queued f) (throw (ex-info "An instruction is already queued" {:reason :already-queued :session session-name}))
                :else (assoc f :queued text :queued-for for))))))

;; ── one pass ────────────────────────────────────────────────────────────────

(def ^:private scan-margin-ms
  "How far before a pass began the next pass looks for logs written to: a log appended while a
   pass read it is read again by the next, whatever the clock's grain."
  5000)

(defn- scan-path [] (str (fs/path (cstate/coordinator-dir) "conduct-scan.edn")))

(defn- scan-from
  "The epoch milliseconds from which a pass beginning at `now-ms` reads the logs written to: where
   the last pass left off, else the earliest instant conduct began reading any of `records`, else
   now. Only where to look — every conversation's position is its record's."
  [records now-ms]
  (or (:through-ms (io/read-edn (scan-path)))
      (some->> records (keep #(some-> (get-in % [:record :reading :since]) instant (.toEpochMilli))) seq (apply min))
      (- now-ms scan-margin-ms)))

(defn- every-record [opts]
  (for [p      (projects opts)
        ws-id  (ws/list-ids p)
        s      (session/list-sessions p ws-id)]
    {:project p :ws-id ws-id :record s}))

(defn- other-actions
  "What a conversation the facet does not drive reports: each Stop a turn end that ended no
   instruction — nido gives a conversation it does not drive none — and each prompt a person
   typed."
  [evs]
  (keep (fn [ev] (case (:event ev)
                   :stop   [:turn-ended ev nil]
                   :prompt (when (session/person-prompt? ev) [:prompted ev])
                   nil))
        evs))

(defn- read-record!
  "Read every conversation of the session record `record` past its position — its facet's driven
   one among `conversations`, decided on as `decide` says — and move each position past what was
   read, in one write under the record's lock. Answers {:decided <decide's answer, or nil without
   a facet> :row <the facet's listing row> :driven <its conversation> :others [[conversation-id
   actions] …] :reading <the reading it read under>}, or nil when there was nothing to read or
   write."
  [{:keys [project ws-id record]} conversations {:keys [listing now opts]}]
  (let [n      (:name record)
        facet  (:claude record)
        others (disj (set conversations) (:session-id facet))]
    (when (or facet (seq others) (nil? (:reading record)))
      (let [row  (when facet (row-for listing facet))
            owed (if-let [f (get-in opts [:mail :owed])] (if facet (f project ws-id n) []) [])
            out  (atom nil)]
        (session/update-substrate!
         project ws-id n
         (fn [{f :claude :as m}]
           (let [reading (reading-of m now)
                 m       (assoc m :reading reading)
                 driven  (when f
                           (let [{evs :events end :cursor} (read-conversation (:session-id f) m)
                                 shown (baseline-shown (:session-id f))]
                             {:end end
                              :d   (decide f row evs {:now now :token (mint)
                                                      :hold-sends? (:hold-sends? opts)
                                                      :baseline (or (first (baseline-refusal shown row)) :shown)
                                                      :latest (:latest shown)
                                                      :owed owed})}))
                 read    (for [id (disj others (:session-id f))]
                           (let [{evs :events end :cursor} (read-conversation id m)]
                             [id end (vec (other-actions evs))]))
                 positions (cond-> (into (:positions reading) (map (juxt first second)) read)
                             driven (assoc (:session-id f) (:end driven)))]
             (reset! out {:decided (:d driven) :row row :reading reading
                          :driven (:session-id f)
                          :others (mapv (fn [[id _ acts]] [id acts]) read)})
             (cond-> (assoc m :reading (assoc reading :positions positions))
               driven (assoc :claude (dissoc (:facet (:d driven)) :cursor))))))
        @out))))

(defn- deliver!
  "Deliver what `decided` calls for to the record's session and fold every action into `report`:
   a turn end or prompt only when `counted` counts it, a turn end and a blocked session `mark`ed."
  [report who {:keys [decided row driven]} {:keys [opts project ws-id session-name counted mark]}]
  (let [sid    driven
        given! (fn [id token] (when id ((get-in opts [:mail :delivered!]) project ws-id id token)))]
    (reduce
     (fn [r [kind a b c message-id]]
       (case kind
         :turn-ended     (if (counted a b)
                           (update r :turn-ends conj (mark (assoc who :event a :instruction b :run (:for b) :conversation sid)))
                           r)
         :prompted       (if (counted a nil) (update r :prompts conj (assoc who :event a :conversation sid)) r)
         :denial         (update r :denials conj (assoc who :event a))
         :notify-blocked (update r :blocked conj (mark (assoc who :unrun? a)))
         :refused        (update r :refused conj (assoc who :because a))
         (:send :wake)
         (let [ok (if (= :send kind)
                    (:delivered? (substrate/send! row a))
                    (try (:woke? (substrate/wake! sid a (assoc opts :cwd (:cwd row))))
                         (catch Exception e e)))
               running (when (instance? Throwable ok) (left-running ok))]
           (cond
             (true? ok)
             (do (given! message-id b)
                 (update r :delivered conj (assoc who :via kind :token b)))
             ;; Something the attempt started still runs: the instruction stays outstanding,
             ;; so nothing is given again until it ends or lapses — a seat message included,
             ;; which that process may already hold.
             running
             (do (given! message-id b)
                 (update r :left-running conj (assoc who :via kind :running running)))
             :else
             (do (session/update-claude! project ws-id session-name
                                         ;; Back where it waited: undelivered is not outstanding.
                                         ;; A seat message stays owed, so it needs no putting back.
                                         #(cond-> (assoc % :pending nil)
                                            (not message-id) (assoc :queued c
                                                                    :queued-for (get-in decided [:facet :pending :for]))))
                 (update r :failed conj (assoc who :via kind)))))))
     report
     (:actions decided))))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  conduct!
  "One pass over every session record on every workstream of the projects in `opts`, faceted or
   not: read each of its conversations — a conversation is the session's whose worktree or home
   holds its recorded cwd (`session/conversation-sessions`), and the conversation its facet drives
   — past the position its record keeps for it, decide on the driven one, record the decision and
   the positions, then deliver what it calls for. `opts` may carry :projects, :hold-sends? (true
   while the account is at its usage limit) and :mail — {:owed (fn [project ws-id session-name]
   [{:id :text} …]) :delivered! (fn [project ws-id id token])}, where the seat messages a session
   is owed are kept. A message is given like any instruction, tokened, once nothing is queued; it
   is marked delivered only once the substrate took it, so one whose delivery failed is owed
   again. Without :mail no session is owed any.

   Answers what the pass saw, by kind — :named (starts the listing has now named), :turn-ends
   (each Stop read, once, with its :conversation, the :instruction it ended — nil for a turn
   whose prompts carried no instruction's token — and the :run that instruction was recorded as
   given for, nil for none; one ending no Run's instruction is reported only when it was recorded
   at or after the instant conduct began reading its session), :prompts (each prompt a person
   typed, read once, so counted), :denials, :blocked (each with :unrun? when nothing is
   outstanding and its last Stop ended no Run's instruction), :delivered, :failed, :left-running
   (a delivery that failed leaving a process it started running, named by the adapter; its
   instruction stays outstanding), :refused (a queued instruction kept back because the
   session's own events no longer show the launch baseline, naming why), and :working (sessions
   nido drives that the listing shows working). Each entry names its session, and each turn end
   and blocked session whether its workstream is :activated? and its session the :conductor?, for
   the caller to settle runs, hand back, show denials and notify. A listing that cannot be read
   ends the pass with {:skipped :listing-failed}: nothing is decided on a stale picture."
  [opts]
  (let [listing (try (substrate/listing opts) (catch Exception _ ::failed))]
    (if (= ::failed listing)
      {:skipped :listing-failed}
      (let [began-ms (System/currentTimeMillis)
            now      (clock/now-iso)
            named    (name-dispatched! listing opts)
            records  (vec (every-record opts))
            owned    (session/conversation-sessions (events/conversations (scan-from records began-ms)))
            activated? (memoize (fn [project ws-id] (boolean (some-> (ws/read-ws project ws-id) standing/activated?))))
            report
            (reduce
             (fn [report {:keys [project ws-id record] :as rec}]
               (let [n   (:name record)
                     who {:project project :ws-id ws-id :session n}
                     out (read-record! rec (get owned [(name project) n]) {:listing listing :now now :opts opts})]
                 (if-not out
                   report
                   (let [since   (get-in out [:reading :since])
                         ;; Only a Run's turn end is reported whenever it was recorded: what ends
                         ;; no Run's instruction counts from when conduct began reading here.
                         counted (fn [ev instruction] (or (:for instruction) (not-before? (:at ev) since)))
                         mark    (fn [entry] (assoc entry :activated? (activated? project ws-id)
                                                    :conductor? (boolean (:conductor record))))
                         r (if (:decided out)
                             (deliver! report who out {:opts opts :project project :ws-id ws-id :session-name n
                                                       :counted counted :mark mark})
                             report)
                         r (reduce (fn [r [id acts]]
                                     (reduce (fn [r [kind ev]]
                                               (cond
                                                 (not (counted ev nil)) r
                                                 (= :turn-ended kind)
                                                 (update r :turn-ends conj (mark (assoc who :event ev :instruction nil :run nil :conversation id)))
                                                 :else (update r :prompts conj (assoc who :event ev :conversation id))))
                                             r acts))
                                   r (:others out))]
                     (cond-> r
                       (and (= :nido (get-in out [:decided :facet :driver])) (= "working" (:state (:row out))))
                       (update :working conj who))))))
             {:turn-ends [] :prompts [] :denials [] :blocked [] :delivered [] :failed [] :left-running []
              :refused [] :working [] :named named}
             records)]
        (io/write-edn! (scan-path) {:through-ms (- began-ms scan-margin-ms)})
        report))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :SessionName :map] [:maybe :map]]}
  retire!
  "Stop driving a session and stop its process, before the session it belongs to is torn down —
   so nothing tears down a workspace under a session still working in it. A process a failed
   start left running, known only by the :bg-id its :dispatching note keeps, is a session too:
   it is stopped and the note cleared. The conversation is kept, and the facet's driver made
   :retired once its process is stopped: it drives no session until a conversation is driven on
   the record again. Answers the facet, or nil for a session without one; throws, the session
   released and not marked, when Claude Code refuses the stop."
  [project ws-id session-name opts]
  (let [s (session/read-session project ws-id session-name)]
    (when-let [bg-id (get-in s [:dispatching :bg-id])]
      (substrate/stop! bg-id opts)
      (session/update-substrate! project ws-id session-name
                                 (fn [m] (cond-> m (= bg-id (get-in m [:dispatching :bg-id])) (assoc :dispatching nil)))))
    (when (:claude s)
      (let [f (release! project ws-id session-name)]
        (when-let [id (:bg-id f)] (substrate/stop! id opts))
        (:claude (session/update-claude! project ws-id session-name
                                         #(assoc % :driver :retired)))))))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  live-states
  "Each session the substrate lists, by claude session id, as its listing row — for display and
   liveness, read from a listing at most two seconds old. Never the basis for giving one an
   instruction; conduct! reads its own."
  [opts]
  (into {} (comp (filter :sessionId) (map (juxt :sessionId identity))) (substrate/listing-cached opts)))

;; ── after a reboot ──────────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat] [:maybe :map]]}
  current-boot
  "This host boot as {:id :began-at} — the identity the kernel gives it and the instant it
   began — or nil when it cannot be read."
  []
  (process/host-boot))

(defn- boot-marker-path [] (str (fs/path (cstate/coordinator-dir) "recovered-boot.edn")))


(defn- outlived-boot?
  "Whether `latest`, the session's last recorded event, falls after the host boot began at
   `boot-at` — a notification included, since only a live session is sent one: it was alive in
   this boot, so the boot is not what stopped it."
  [latest boot-at]
  (boolean
   (when boot-at
     (when-let [at (:at latest)]
       (try (.isAfter (java.time.Instant/parse at) (java.time.Instant/parse boot-at))
            (catch Exception _ false))))))

(defn- mid-turn?
  "Whether the boot stopped `facet`'s session inside a turn: an instruction outstanding or work of
   its own running, and the last prompt or Stop among `evs`, the session's recorded events, not a
   Stop — a Stop the daemon never consumed ended the turn all the same, and a notification after
   it starts no turn. A session between turns has nothing a restart would resume."
  [facet evs]
  (and (or (some? (:pending facet)) (:own-work? facet))
       (not= :stop (:event (last (filter (comp #{:prompt :stop} :event) evs))))))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  recover!
  "At nido's first start after a host boot, restart once each session the boot stopped mid-turn:
   one with a :claude facet whose policy is auto-respawn, that has no live process, that was
   inside a turn when the boot came and recorded nothing after it began, and whose listing row is
   a background session in a directory that exists. A session between turns is left resumable:
   the auto-respawn policy outlives every turn, so on its own it would restart every Run that
   ever finished in a worktree still standing. A session it would restart but whose listed
   directory is gone cannot be restarted there and is answered under :gone, unattempted. Every
   other listed background session without a live process, in a directory that exists, is
   answered as resumable; one the listing does not name is answered as neither.

   The first start recovers the boot whatever it was refused, and a later start in the same boot
   restarts nothing. A refused restart is answered under :failed for the caller to report, and
   the session stays resumable. Retrying it at the next start is what this used to do, and a
   refusal that never clears — a deleted worktree — then kept the boot unrecovered for good, so
   every later start ran as the first: 80 sessions restarted on 2026-10-27, three days after
   the boot, 26 of which had simply finished since.

   `opts` may name the boot as :boot with its start as :boot-at; without :boot both are read
   from the host. Answers {:boot :first-start? :restarted :failed :gone :resumable}."
  [opts]
  (let [host   (when-not (:boot opts) (current-boot))
        boot   (or (:boot opts) (:id host))
        boot-at (if (:boot opts) (:boot-at opts) (:began-at host))
        first? (and boot (not= boot (io/read-edn (boot-marker-path))))
        listing (substrate/listing opts)
        idle   (for [{:keys [facet] :as s} (faceted-sessions opts)
                     :when (not (:pid (row-for listing facet)))]
                 s)
        candidate? (fn [{:keys [facet]}]
                     (and first? (get-in facet [:policy :auto-respawn?])
                          (not= boot (:recovered-boot facet)) (:bg-id facet)
                          (let [evs (:events (events/events-since (:session-id facet) nil))]
                            (and (mid-turn? facet evs)
                                 (not (outlived-boot? (last evs) boot-at))))))
        row      (fn [{:keys [facet]}] (row-for listing facet))
        ;; A listed session whose directory is gone; one the listing does not name is neither
        ;; gone nor resumable — nothing can be said of it.
        gone?    (fn [s] (when-let [r (row s)] (not (some-> (:cwd r) fs/directory?))))
        resumable? (fn [s] (when-let [r (row s)]
                             (and (= "background" (:kind r)) (some-> (:cwd r) fs/directory?))))
        restart? (fn [s] (and (candidate? s) (resumable? s)))
        who      (fn [{:keys [project ws-id session-name]}]
                   {:project project :ws-id ws-id :session session-name})
        ;; Before any respawn: a start that dies mid-recovery must not leave the boot to the next.
        _        (when first? (io/write-edn! (boot-marker-path) boot))
        attempts (doall
                  (for [{:keys [project ws-id session-name facet] :as s} idle
                        :when (restart? s)]
                    (try
                      (substrate/respawn! (:bg-id facet)
                                          (assoc opts :cwd (:cwd (row-for listing facet))))
                      (session/update-claude! project ws-id session-name #(assoc % :recovered-boot boot))
                      [:restarted (who s)]
                      (catch Exception e
                        [:failed (assoc (who s) :because (ex-message e))]))))
        by-kind (group-by first attempts)
        restarted (mapv second (:restarted by-kind))]
    {:boot        boot
     :first-start? (boolean first?)
     :restarted   restarted
     :failed      (mapv second (:failed by-kind))
     :gone        (vec (for [s idle :when (and (candidate? s) (gone? s))] (who s)))
     :resumable   (vec (for [s idle :when (and (resumable? s) (not (some #{(who s)} restarted)))]
                         (who s)))}))
