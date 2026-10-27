(ns nido.coordinator.lane.conduct
  "Who drives each Claude Code background session, and what nido does about the ones it drives.

   HIDES the driving policy: a session's driver and hold, when a turn has really ended, when the
   next instruction goes out and when it is held back, when a person is told, and what is
   restarted after a reboot. ORIGIN is recorded on the session's :claude facet and decides none
   of it — a session a person started and one nido started are driven, held, handed back and
   recovered the same way.

   Three rules carry the rest:
     - nido gives an instruction only to a session it drives, with no hold standing, nothing
       outstanding, and the substrate reporting it done with no work of its own still running;
     - every instruction carries a token recorded as pending BEFORE it is given, so a prompt
       without the pending token is a turn nido did not give, and it holds the session until a
       hand-back;
     - a turn ends when the session's own Stop says so, never when a process exits."
  (:require [clojure.string :as str]
            [nido.coordinator.agent.substrate :as substrate]
            [nido.coordinator.record.clock :as clock]
            [nido.coordinator.record.session :as session]
            [nido.coordinator.record.session-events :as events]
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


;; ── the decision ────────────────────────────────────────────────────────────

(defn- taken-over
  "`facet` held by a turn nido did not give, `because` it, at `at`: from that turn a person drives
   the session, until hand-back! makes nido its driver again. Every hold is written by this."
  [facet at because]
  (assoc facet :hold {:at at :because because} :driver :person))

(defn- read-event
  "One event folded into the facet; actions it calls for appended."
  [{:keys [facet] :as acc} ev]
  (let [{:keys [pending driver own-work? idle-seen]} facet
        driving? (= :nido driver)
        hold     (fn [acc because]
                   (cond-> acc
                     (and driving? (nil? (get-in acc [:facet :hold])))
                     (-> (update :facet taken-over (:at ev) because)
                         (update :actions conj [:held because]))))]
    (case (:event ev)
      :prompt
      (cond
        (and pending (not (:seen? pending)) (carries? (:prompt ev) (:token pending)))
        (assoc-in acc [:facet :pending :seen?] true)
        :else (hold acc :foreign-prompt))

      :stop
      (let [ended    (cond-> acc
                       ;; The instruction this Stop ended: the outstanding one whose token a
                       ;; recorded prompt carried — nil for a turn nido did not give.
                       true (update :actions conj [:turn-ended ev (when (:seen? pending) pending)])
                       true (assoc-in [:facet :pending] nil))
            ;; A turn begun before nido began driving, or before nido last gave it anything, is
            ;; not one nido failed to give: only a turn after an idle nido saw can be a person's.
            foreign? (and (nil? pending) (not own-work?) (some? idle-seen))
            acc'     (if foreign? (hold ended :foreign-turn) ended)]
        (-> acc'
            ;; Seen idle by its Stop — not yet by the listing, whose working may be this turn's tail.
            (assoc-in [:facet :idle-seen] :stopped)
            (assoc-in [:facet :own-work?]
                      (pos? (+ (or (:background-tasks ev) 0) (or (:crons ev) 0))))))

      :permission-denied
      (cond-> acc driving? (update :actions conj [:denial ev]))

      acc)))

(defn ^{:malli/schema [:=> [:cat :map [:maybe :map] [:sequential :map] :map] :map]}
  decide
  "The next facet and the actions it calls for, given a session's :claude facet, its row in the
   substrate's listing (nil when the listing has none), its events since the facet's cursor, and
   {:now <iso> :token <fresh> :hold-sends? <bool> :baseline <:shown, or why the session's events
   do not show the launch baseline> :latest <its latest recorded event> :owed <the seat messages
   the session is owed, oldest first, each {:id :text}>}. Pure: it gives nothing, it says what to
   give.

   A turn nido did not give holds a session nido drives whether its prompt was recorded or only
   the listing shows it — and only a turn begun after nido last began driving it or gave it
   anything, which the facet's :idle-seen says: what nido has seen of the session idle since then
   (nil, :stopped by a recorded Stop, :listed done by the listing). A Stop with nothing
   outstanding and no work of its own holds it once :idle-seen is set; working holds it once
   :idle-seen is :listed.

   Actions: [:turn-ended event instruction] — instruction the pending nido gave whose token a
   recorded prompt carried, nil for a turn it did not give — [:denial event], [:held because], [:notify-blocked],
   [:refused why] when only the baseline keeps a queued instruction back, and at most one of
   [:send tagged-text token text message-id] / [:wake tagged-text token text message-id] —
   whose token the returned facet already holds as pending, so it is recorded before anything is
   delivered. What is given is the queued instruction, else the oldest seat message owed, whose
   :id the action carries (nil for an instruction). Only a session the listing shows done, or blocked with its Stop as its latest recorded
   event, is given anything; a wake goes only to a session whose latest recorded event is its
   Stop."
  [facet row evs {:keys [now token hold-sends? baseline latest owed]}]
  (let [{f :facet acts :actions} (reduce read-event {:facet facet :actions []} evs)
        listed-turn? (and (= :nido (:driver f)) (nil? (:hold f)) (nil? (:pending f))
                          (not (:own-work? f)) (= :listed (:idle-seen f)) (= "working" (:state row)))
        {f :facet acts :actions} (if listed-turn?
                                   {:facet   (taken-over f now :foreign-turn)
                                    :actions (conj acts [:held :foreign-turn])}
                                   {:facet f :actions acts})
        blocked?  (= "blocked" (:state row))
        announce? (and blocked? (not (:announced-blocked? f)))
        idle?     (and (= "done" (:state row)) (nil? (:pending f)))
        f         (cond-> (assoc f :announced-blocked? blocked?)
                    idle? (assoc :idle-seen :listed))
        via       (when row (if (:pid row) :send :wake))
        stopped?  (= :stop (:event latest))
        ;; Blocked after its Stop, a session is waiting on the answer a person queued for it;
        ;; blocked mid-turn, it is waiting on something a message would not answer.
        ready-state? (or (= "done" (:state row)) (and blocked? stopped?))
        message   (when (nil? (:queued f)) (first owed))
        text      (if message (:text message) (:queued f))
        gated?    (and (= :nido (:driver f)) (nil? (:hold f)) (nil? (:pending f))
                       text row ready-state? (not (:own-work? f))
                       (not hold-sends?)
                       (or (= :send via) stopped?))
        ready?    (and gated? (= :shown baseline))]
    {:facet   (cond-> f
                ready? (assoc :pending {:token token :at now :via via :seen? false} :idle-seen nil)
                (and ready? (not message)) (assoc :queued nil))
     :actions (cond-> acts
                announce? (conj [:notify-blocked])
                (and gated? (not ready?)) (conj [:refused baseline])
                ready?    (conj [via (tagged text token) token text (:id message)]))}))

;; ── finding the sessions ────────────────────────────────────────────────────

(defn- projects [opts]
  (or (:projects opts) (mapv keyword (keys (project/list-projects)))))

(defn ^{:malli/schema [:=> [:cat :map] [:sequential :map]]}
  faceted-sessions
  "Every session record carrying a :claude facet, across the projects in `opts` (default: every
   registered one), as {:project :ws-id :session-name :facet}."
  [opts]
  (for [p      (projects opts)
        ws-id  (ws/list-ids p)
        s      (session/list-sessions p ws-id)
        :when  (:claude s)]
    {:project p :ws-id ws-id :session-name (:name s) :facet (:claude s)}))

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
  {:session-id session-id :bg-id bg-id :origin origin :driver :nido :hold nil :pending nil
   :queued nil :own-work? false :policy {:auto-respawn? false} :cursor 0 :driving-since now
   :announced-blocked? false :idle-seen nil :recovered-boot nil})

(defn- dispatched-facet
  "The facet a start writes once Claude Code has named its session: nido drives it, and the first
   prompt — given under the token the :dispatching note recorded before the start — is
   outstanding."
  [{:keys [token at policy]} sess]
  (cond-> (assoc (fresh-facet :nido at sess)
                 :pending {:token token :at at :via :dispatch :seen? false})
    policy (assoc :policy policy)))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  start!
  "Start a conversation for an existing session record as a background session nido drives:
   {:project :ws-id :session-name :cwd :prompt} and optionally :policy, :name, :model, and
   :source — the repository `:cwd` was made from, which vouches for trusting `:cwd` when Claude
   Code does not trust it yet.

   The first prompt's token is recorded, in the record's :dispatching note, before the session
   starts; the facet is written — and the note cleared — only once Claude Code has named the
   session, so no facet is ever without its session's id. Nothing of the record's earlier facet
   is carried: once the listing shows that session stopped, its conversation is over, hold
   included. Answers the facet written.

   Refused, writing nothing, while the listing shows the record's session alive — naming the
   hold when a turn nido did not give holds it — whoever drives it, so a start over a released record
   never leaves the earlier session running unrecorded; retire it first — and while another
   start for the record is in flight. Throws when the session cannot be started: the note is
   cleared, or — when the adapter says it left a process running that the listing has not named
   — kept with that process's :bg-id, for conduct! to name it."
  [{:keys [project ws-id session-name cwd prompt policy source] :as opts}]
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
       ;; Whether the earlier conversation still runs decides what of its facet stands: a live one
       ;; is refused, held or not; a stopped one is over, its hold with it.
       (let [alive? (and claude (:pid (row-for listing claude)))]
         (when-let [hold (when alive? (:hold claude))]
           (refuse :held (str "A turn nido did not give holds its session (" (name (:because hold))
                              " at " (:at hold) ") — hand it back first")
                   {:hold hold}))
         (when dispatching
           (refuse :dispatching "A start for this session is already in flight" {:dispatching dispatching}))
         (when alive?
           (refuse :live (str "Its session " (:session-id claude) " is alive — retire it first")
                   {:session-id (:session-id claude)})))
       (assoc m :dispatching (cond-> {:token token :at now} policy (assoc :policy policy)))))
    (try
      (let [named (substrate/dispatch! (assoc opts :prompt (tagged prompt token)))]
        (:claude (session/update-substrate!
                  project ws-id session-name
                  (fn [{:keys [dispatching claude]}]
                    {:claude (dispatched-facet dispatching named) :dispatching nil}))))
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
              {:claude (dispatched-facet d {:session-id (:sessionId row) :bg-id (:id row)})
               :dispatching nil}
              m)))
         {:project project :ws-id ws-id :session session-name}))))

(defn- caught-up
  "`facet`, made the facet of the session {:session-id :bg-id}, with its cursor moved to the end of
   that session's event log — every advance past events no pass has folded goes through this —
   and its pending instruction kept only while it was given to this same session and no Stop
   among the events skipped ended it. One read of the log decides both, so no Stop falls between."
  [facet {:keys [session-id] :as sess}]
  (let [same? (names? facet sess)
        {evs :events end :cursor} (events/events-since session-id (when same? (:cursor facet)))]
    (assoc facet
           :cursor  end
           :pending (when (and same? (not-any? #(= :stop (:event %)) evs)) (:pending facet)))))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  adopt!
  "Make nido the driver of a background session that already exists — one a person moved to the
   background, or one nido released — on the session record {:project :ws-id :session-name}, by
   its :session-id. Refused, naming what fails, unless the session is a background session in the
   substrate's listing whose own recorded events show the launch baseline: its latest start
   recorded by nido's hooks from the directory it runs in, and a mode that asks nobody on its latest
   event. Refused too, naming the hold, while a turn nido did not give holds the record's session:
   only hand-back! clears that. Turns recorded before adoption never hold it. An instruction
   given to this same session and not yet followed by its Stop stays outstanding. Answers the
   facet written."
  [{:keys [project ws-id session-name session-id policy] :as opts}]
  (let [row (row-for (substrate/listing opts) {:session-id session-id})
        refuse (fn [reason msg & [data]]
                 (throw (ex-info msg (merge {:reason reason :session-id session-id} data))))]
    (if (not= "background" (:kind row))
      (refuse :not-background "Not a background session — move it there first (/bg, or ← on an empty prompt)")
      (when-let [[reason msg] (baseline-refusal (baseline-shown session-id) row)]
        (refuse reason msg)))
    (let [now (clock/now-iso)]
      (:claude (session/update-claude!
                project ws-id session-name
                (fn [f]
                  (when-let [hold (:hold f)]
                    (refuse :held (str "A turn nido did not give holds it (" (name (:because hold))
                                       " at " (:at hold) ") — hand it back first")
                            {:hold hold}))
                  (-> (caught-up (or f (fresh-facet :person now {:session-id session-id :bg-id (:id row)}))
                                 {:session-id session-id :bg-id (:id row)})
                      (merge {:session-id session-id :bg-id (:id row) :driver :nido :hold nil
                              :queued nil :own-work? false :driving-since now :idle-seen nil}
                             (when policy {:policy policy})))))))))

(defn- redriven
  "`facet` with `driver` as its driver — unless `retire!` made it :retired. Retirement ends only
   when a conversation is driven on the record again, which a start, a naming or an adoption
   establishes by writing a facet for a session the listing names; a verb that only changes who
   drives the facet it finds — release, hand-back — sets its driver through this, so none of
   them makes a stopped conversation hold its record again."
  [facet driver]
  (assoc facet :driver (if (= :retired (:driver facet)) :retired driver)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :SessionName] :map]}
  release!
  "Stop driving a session. The session is untouched and keeps running; nothing queued for it is
   given. A hold standing on it stays, and with it the person as its driver: only hand-back!
   clears one. A retired session stays retired: releasing it drives nothing. An instruction
   already given stays outstanding until the session's Stop."
  [project ws-id session-name]
  (:claude (session/update-claude! project ws-id session-name
                                   ;; A person who took it over still drives it.
                                   #(-> % (redriven (if (:hold %) :person :nobody)) (assoc :queued nil)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :SessionName] :map]}
  hand-back!
  "Clear the hold a turn nido did not give put on a session, making nido its driver again — a
   retired session stays retired (`redriven`). Turns recorded until now never hold it again, and
   an instruction a Stop among them ended is no longer outstanding (caught-up)."
  [project ws-id session-name]
  (:claude (session/update-claude! project ws-id session-name
                                   #(caught-up (-> % (redriven :nido)
                                                   (assoc :hold nil :driving-since (clock/now-iso) :idle-seen nil))
                                               (select-keys % [:session-id :bg-id])))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :SessionName :string] :map]}
  instruct!
  "Queue `text` as the next instruction for a session nido drives; conduct! gives it once the
   session is ready for it. Refused when the session has no facet or one is already queued."
  [project ws-id session-name text]
  (:claude (session/update-claude!
            project ws-id session-name
            (fn [f]
              (cond
                (nil? f) (throw (ex-info "Not a substrate session" {:reason :no-facet :session session-name}))
                (:queued f) (throw (ex-info "An instruction is already queued" {:reason :already-queued :session session-name}))
                :else (assoc f :queued text))))))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  conduct!
  "One pass over every session with a :claude facet: read its new events and its row in the
   substrate's listing, decide, record the decision, then deliver what it calls for. `opts` may
   carry :projects, :hold-sends? (true while the account is at its usage limit) and :mail —
   {:owed (fn [project ws-id session-name] [{:id :text} …]) :delivered! (fn [project ws-id id])},
   where the seat messages a session is owed are kept. A message is given like any instruction,
   tokened, once nothing is queued; it is marked delivered only once the substrate took it, so
   one whose delivery failed is owed again. Without :mail no session is owed any.

   Answers what the pass saw, by kind — :named (starts the listing has now named), :turn-ends (each with the :instruction it ended, nil for a
   turn nido did not give), :denials, :held, :blocked, :delivered,
   :failed, :left-running (a delivery that failed leaving a process it started running, named
   by the adapter; its instruction stays outstanding), :refused (a queued instruction kept back because the session's own events no longer
   show the launch baseline, naming why), and :working (sessions nido drives that the listing
   shows working) — each entry naming its session, for the caller to settle runs, show denials
   and notify. A listing that cannot be read ends the pass with {:skipped :listing-failed}: nothing
   is decided on a stale picture."
  [opts]
  (let [listing (try (substrate/listing opts) (catch Exception _ ::failed))]
    (if (= ::failed listing)
      {:skipped :listing-failed}
      (let [named (name-dispatched! listing opts)]
      (reduce
       (fn [report {:keys [project ws-id session-name facet]}]
         (let [who     {:project project :ws-id ws-id :session session-name}
               row     (row-for listing facet)
               sid     (:session-id facet)
               owed    (if-let [f (get-in opts [:mail :owed])] (f project ws-id session-name) [])
               given!  (fn [id] (when id ((get-in opts [:mail :delivered!]) project ws-id id)))
               out     (atom nil)]
           (session/update-claude!
            project ws-id session-name
            (fn [f]
              (let [{evs :events cursor :cursor} (events/events-since (:session-id f) (:cursor f))
                    shown (baseline-shown (:session-id f))
                    d (decide f row evs {:now (clock/now-iso) :token (mint)
                                         :hold-sends? (:hold-sends? opts)
                                         :baseline (or (first (baseline-refusal shown row)) :shown)
                                         :latest (:latest shown)
                                         :owed owed})]
                (reset! out d)
                (assoc (:facet d) :cursor cursor))))
           (reduce
            (fn [r [kind a b c message-id]]
              (case kind
                :turn-ended     (update r :turn-ends conj (assoc who :event a :instruction b))
                :denial         (update r :denials conj (assoc who :event a))
                :held           (update r :held conj (assoc who :because a))
                :notify-blocked (update r :blocked conj who)
                :refused        (update r :refused conj (assoc who :because a))
                (:send :wake)
                (let [ok (if (= :send kind)
                           (:delivered? (substrate/send! row a))
                           (try (:woke? (substrate/wake! sid a (assoc opts :cwd (:cwd row))))
                                (catch Exception e e)))
                        running (when (instance? Throwable ok) (left-running ok))]
                  (cond
                    (true? ok)
                    (do (given! message-id)
                        (update r :delivered conj (assoc who :via kind :token b)))
                    ;; Something the attempt started still runs: the instruction stays outstanding,
                    ;; so nothing is given again until a person releases or hands the session back
                    ;; — a seat message included, which that process may already hold.
                    running
                    (do (given! message-id)
                        (update r :left-running conj (assoc who :via kind :running running)))
                    :else
                    (do (session/update-claude! project ws-id session-name
                                                ;; Back where it waited: undelivered is not outstanding.
                                                ;; A seat message stays owed, so it needs no putting back.
                                                #(cond-> (assoc % :pending nil)
                                                   (not message-id) (assoc :queued c)))
                        (update r :failed conj (assoc who :via kind)))))))
            (cond-> report
              (and (= :nido (:driver facet)) (= "working" (:state row)))
              (update :working conj who))
            (:actions @out))))
       {:turn-ends [] :denials [] :held [] :blocked [] :delivered [] :failed [] :left-running []
        :refused [] :working [] :named named}
       (faceted-sessions opts))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :SessionName :map] [:maybe :map]]}
  retire!
  "Stop driving a session and stop its process, before the session it belongs to is torn down —
   so nothing tears down a workspace under a session still working in it. A process a failed
   start left running, known only by the :bg-id its :dispatching note keeps, is a session too:
   it is stopped and the note cleared. The conversation is kept, and the facet's driver made
   :retired once its process is stopped, its hold cleared with it — a hold was on a conversation
   that is over: it holds no session until a conversation is driven on the record again. Answers
   the facet, or nil for a session without one;
   throws, the session released and not marked, when Claude Code refuses the stop."
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
                                         #(assoc % :driver :retired :hold nil)))))))

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
  "Whether the session recorded anything after the host boot began at `boot-at` — a
   notification included, since only a live session is sent one: it was alive in this boot, so
   the boot is not what stopped it."
  [session-id boot-at]
  (boolean
   (when boot-at
     (when-let [at (->> (:events (events/events-since session-id nil))
                        last :at)]
       (try (.isAfter (java.time.Instant/parse at) (java.time.Instant/parse boot-at))
            (catch Exception _ false))))))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  recover!
  "At nido's first start after a host boot, restart once each session the boot stopped: one with
   a :claude facet whose policy is auto-respawn, that has no live process and that recorded
   nothing after the boot began, and whose listing row is a background session in a directory
   that exists. A listed session whose directory is gone cannot be restarted there and is
   answered under :gone, unattempted. Every other listed background session without a live
   process, in a directory that exists, is answered as resumable; one the listing does not name
   is answered as neither.

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
                          (not (outlived-boot? (:session-id facet) boot-at))))
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
