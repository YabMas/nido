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
   latest event itself reports (nil when that event reports none), and that latest event."
  [session-id]
  (let [evs   (:events (events/events-since session-id nil))
        start (last (filter #(= :session-start (:event %)) evs))]
    {:started?  (some? start)
     :start-cwd (:cwd start)
     :mode      (:permission-mode (last evs))
     :latest    (last evs)}))

(defn ^{:malli/schema [:=> [:cat :map [:maybe :map]] [:maybe [:tuple :keyword :string]]]}
  baseline-refusal
  "Why the session's own recorded events do not show the launch baseline, as [reason message], or
   nil when they do: its latest start recorded by nido's hooks, from the directory the listing
   `row` shows it in, and permission mode auto on its latest event — given what `baseline-shown`
   read. Each half needs evidence that shows it: a start or a row naming no directory shows none.
   The one check adoption, a send, a wake and the session list all make."
  [{:keys [started? start-cwd mode]} row]
  (cond
    (not started?)
    [:no-session-start "nido's hooks recorded no start for it — start it in a nido session home or worktree"]
    (or (nil? start-cwd) (not= (:cwd row) start-cwd))
    [:started-elsewhere (str "It runs in " (:cwd row) ", but nido's hooks last recorded its start in "
                             (or start-cwd "an unknown directory") " — start it in a nido session home or worktree")]
    (not= "auto" mode)
    [:not-auto (str "Its latest recorded permission mode is " (or mode "unknown") ", not auto — switch it to auto first")]))

(defn- left-running
  "The id of a process an adapter call that threw says it left running, or nil when it left
   none. Every catch around an adapter call reads this, so no failure that leaves a session
   running is handled as one that started nothing."
  [t]
  (:left-running (ex-data t)))

(defn- log-end [session-id] (:cursor (events/events-since session-id nil)))

;; ── the decision ────────────────────────────────────────────────────────────

(defn- read-event
  "One event folded into the facet; actions it calls for appended."
  [{:keys [facet] :as acc} ev]
  (let [{:keys [pending driver own-work? idle-seen]} facet
        driving? (= :nido driver)
        hold     (fn [acc because]
                   (cond-> acc
                     (and driving? (nil? (get-in acc [:facet :hold])))
                     (-> (assoc-in [:facet :hold] {:at (:at ev) :because because})
                         (update :actions conj [:held because]))))]
    (case (:event ev)
      :prompt
      (cond
        (and pending (not (:seen? pending)) (carries? (:prompt ev) (:token pending)))
        (assoc-in acc [:facet :pending :seen?] true)
        :else (hold acc :foreign-prompt))

      :stop
      (let [ended    (cond-> acc
                       true (update :actions conj [:turn-ended ev])
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
   do not show the launch baseline> :latest <its latest recorded event>}. Pure: it gives nothing,
   it says what to give.

   A turn nido did not give holds a session nido drives whether its prompt was recorded or only
   the listing shows it — and only a turn begun after nido last began driving it or gave it
   anything, which the facet's :idle-seen says: what nido has seen of the session idle since then
   (nil, :stopped by a recorded Stop, :listed done by the listing). A Stop with nothing
   outstanding and no work of its own holds it once :idle-seen is set; working holds it once
   :idle-seen is :listed.

   Actions: [:turn-ended event], [:denial event], [:held because], [:notify-blocked],
   [:refused why] when only the baseline keeps a queued instruction back, and at most one of
   [:send tagged-text token text] / [:wake tagged-text token text] — whose token the returned
   facet already holds as pending, so it is recorded before anything is delivered. A wake goes
   only to a session whose latest recorded event is its Stop."
  [facet row evs {:keys [now token hold-sends? baseline latest]}]
  (let [{f :facet acts :actions} (reduce read-event {:facet facet :actions []} evs)
        listed-turn? (and (= :nido (:driver f)) (nil? (:hold f)) (nil? (:pending f))
                          (not (:own-work? f)) (= :listed (:idle-seen f)) (= "working" (:state row)))
        {f :facet acts :actions} (if listed-turn?
                                   {:facet   (assoc f :hold {:at now :because :foreign-turn})
                                    :actions (conj acts [:held :foreign-turn])}
                                   {:facet f :actions acts})
        blocked?  (= "blocked" (:state row))
        announce? (and blocked? (not (:announced-blocked? f)))
        idle?     (and (= "done" (:state row)) (nil? (:pending f)))
        f         (cond-> (assoc f :announced-blocked? blocked?)
                    idle? (assoc :idle-seen :listed))
        via       (when row (if (:pid row) :send :wake))
        gated?    (and (:session-id f) (= :nido (:driver f)) (nil? (:hold f)) (nil? (:pending f))
                       (:queued f) row (= "done" (:state row)) (not (:own-work? f))
                       (not hold-sends?)
                       (or (= :send via) (= :stop (:event latest))))
        ready?    (and gated? (= :shown baseline))]
    {:facet   (cond-> f
                ready? (assoc :pending {:token token :at now :via via :seen? false}
                              :queued nil :idle-seen nil))
     :actions (cond-> acts
                announce? (conj [:notify-blocked])
                (and gated? (not ready?)) (conj [:refused baseline])
                ready?    (conj [via (tagged (:queued f) token) token (:queued f)]))}))

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
  "Whether `facet` is the facet of the session {:session-id :bg-id}: by its full id, or — while
   the facet has none yet, because the launch's listing never named it — by the :bg-id it was
   launched as. Every comparison of a session against a facet is this one."
  [{fsid :session-id fbg :bg-id} {:keys [session-id bg-id]}]
  (boolean (if fsid (= fsid session-id) (and fbg (= fbg bg-id)))))

(defn- row-for
  "A session's row in the listing, as `names?` matches it."
  [listing facet]
  (let [rows (filter #(names? facet {:session-id (:sessionId %) :bg-id (:id %)}) listing)]
    (or (first (filter #(= "background" (:kind %)) rows)) (first rows))))

;; ── the verbs ───────────────────────────────────────────────────────────────

(defn- fresh-facet [origin now]
  {:session-id nil :bg-id nil :origin origin :driver :nido :hold nil :pending nil :queued nil
   :own-work? false :policy {:auto-respawn? false} :cursor 0 :driving-since now
   :announced-blocked? false :idle-seen nil :recovered-boot nil})

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  start!
  "Start a conversation for an existing session record as a background session nido drives:
   {:project :ws-id :session-name :cwd :prompt} and optionally :policy, :name, :model, and
   :source — the repository `:cwd` was made from, which vouches for trusting `:cwd` when Claude
   Code does not trust it yet. The first prompt's token is recorded before the session starts.
   Answers the facet written. Throws when the session cannot be started, leaving the record
   without a facet — or, when the adapter says it left a session running, with the facet naming
   its :bg-id; refused, naming the hold and writing nothing, while a turn nido did not give
   holds the record's session — only hand-back! clears that."
  [{:keys [project ws-id session-name cwd prompt policy source] :as opts}]
  (when-not (substrate/trusted? cwd)
    (if (and source (substrate/trusted? source))
      (substrate/ensure-trusted! cwd opts)
      (throw (ex-info (str "Claude Code does not trust " cwd ", and nothing nido made it from does")
                      {:reason :workspace-untrusted :dir cwd :source source}))))
  (let [token (mint)
        now   (clock/now-iso)]
    (session/update-claude! project ws-id session-name
                            (fn [f]
                              (when-let [hold (:hold f)]
                                (throw (ex-info (str "A turn nido did not give holds its session ("
                                                     (name (:because hold)) " at " (:at hold)
                                                     ") — hand it back first")
                                                {:reason :held :hold hold :session session-name})))
                              (merge (fresh-facet :nido now)
                                     {:pending {:token token :at now :via :dispatch :seen? false}}
                                     (when policy {:policy policy}))))
    (try
      (let [{:keys [bg-id session-id]} (substrate/dispatch! (assoc opts :prompt (tagged prompt token)))]
        (:claude (session/update-claude! project ws-id session-name
                                         #(assoc % :session-id session-id :bg-id bg-id))))
      (catch Throwable t
        ;; A session the adapter says it left running keeps its record — nido started it and
        ;; drives it; only a start that left nothing running leaves the record without a facet.
        (session/update-claude! project ws-id session-name
                                (if-let [bg-id (left-running t)]
                                  #(assoc % :bg-id bg-id)
                                  (constantly nil)))
        (throw t)))))

(defn- outstanding
  "The instruction `facet` holds as given to the session {:session-id :bg-id} and not yet followed
   by that session's Stop — read up to now, so a Stop no pass has folded yet still ends it — or
   nil."
  [facet {:keys [session-id] :as sess}]
  (let [{:keys [pending cursor]} facet]
    (when (and pending (names? facet sess)
               (not-any? #(= :stop (:event %)) (:events (events/events-since session-id cursor))))
      pending)))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  adopt!
  "Make nido the driver of a background session that already exists — one a person moved to the
   background, or one nido released — on the session record {:project :ws-id :session-name}, by
   its :session-id. Refused, naming what fails, unless the session is a background session in the
   substrate's listing whose own recorded events show the launch baseline: its latest start
   recorded by nido's hooks from the directory it runs in, and permission mode auto on its latest
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
                  (merge (or f (fresh-facet :person now))
                         {:session-id session-id :bg-id (:id row) :driver :nido :hold nil
                          :pending (outstanding f {:session-id session-id :bg-id (:id row)})
                          :queued nil :own-work? false
                          :driving-since now :idle-seen nil :cursor (log-end session-id)}
                         (when policy {:policy policy}))))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :SessionName] :map]}
  release!
  "Stop driving a session. The session is untouched and keeps running; nothing queued for it is
   given. A hold standing on it stays: only hand-back! clears one. An instruction already given
   stays outstanding until the session's Stop."
  [project ws-id session-name]
  (:claude (session/update-claude! project ws-id session-name
                                   #(assoc % :driver :nobody :queued nil))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :SessionName] :map]}
  hand-back!
  "Clear the hold a turn nido did not give put on a session, making nido its driver again. Turns
   recorded until now never hold it again."
  [project ws-id session-name]
  (let [sid (:session-id (:claude (session/read-session project ws-id session-name)))]
    (:claude (session/update-claude! project ws-id session-name
                                     #(assoc % :driver :nido :hold nil :driving-since (clock/now-iso)
                                             :idle-seen nil
                                             :cursor (if sid (log-end sid) (:cursor %)))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :SessionName :string] :map]}
  instruct!
  "Queue `text` as the next instruction for a session nido drives; conduct! gives it once the
   session is ready for it. Refused when the session has no facet or one is already queued. A
   facet whose launch the listing has not named yet takes it too: conduct! names the session by
   its :bg-id first, and gives nothing to a facet still without a :session-id."
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
   carry :projects and :hold-sends? (true while the account is at its usage limit).

   Answers what the pass saw, by kind — :turn-ends, :denials, :held, :blocked, :delivered,
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
      (reduce
       (fn [report {:keys [project ws-id session-name facet]}]
         (let [who     {:project project :ws-id ws-id :session session-name}
               row     (row-for listing facet)
               ;; A launch the listing did not name in time is named by a later listing.
               sid     (or (:session-id facet) (:sessionId row))
               out     (atom nil)]
           (session/update-claude!
            project ws-id session-name
            (fn [f]
              (let [f (cond-> f (nil? (:session-id f)) (assoc :session-id sid))
                    {evs :events cursor :cursor} (events/events-since (:session-id f) (:cursor f))
                    shown (baseline-shown (:session-id f))
                    d (decide f row evs {:now (clock/now-iso) :token (mint)
                                         :hold-sends? (:hold-sends? opts)
                                         :baseline (or (first (baseline-refusal shown row)) :shown)
                                         :latest (:latest shown)})]
                (reset! out d)
                (assoc (:facet d) :cursor cursor))))
           (reduce
            (fn [r [kind a b c]]
              (case kind
                :turn-ended     (update r :turn-ends conj (assoc who :event a))
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
                    (update r :delivered conj (assoc who :via kind :token b))
                    ;; Something the attempt started still runs: the instruction stays outstanding,
                    ;; so nothing is given again until a person releases or hands the session back.
                    running
                    (update r :left-running conj (assoc who :via kind :running running))
                    :else
                    (do (session/update-claude! project ws-id session-name
                                                ;; Back to the queue: undelivered is not outstanding.
                                                #(assoc % :pending nil :queued c))
                        (update r :failed conj (assoc who :via kind)))))))
            (cond-> report
              (and (= :nido (:driver facet)) (= "working" (:state row)))
              (update :working conj who))
            (:actions @out))))
       {:turn-ends [] :denials [] :held [] :blocked [] :delivered [] :failed [] :left-running []
        :refused [] :working []}
       (faceted-sessions opts)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :SessionName :map] [:maybe :map]]}
  retire!
  "Stop driving a session and stop its process, before the session it belongs to is torn down —
   so nothing tears down a workspace under a session still working in it. The conversation is
   kept. Answers the facet, or nil for a session without one; throws, the session released,
   when Claude Code refuses the stop."
  [project ws-id session-name opts]
  (when (:claude (session/read-session project ws-id session-name))
    (let [f (release! project ws-id session-name)]
      (when-let [id (:bg-id f)] (substrate/stop! id opts))
      f)))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  live-states
  "Each session the substrate lists, by claude session id, as its listing row — for display and
   liveness, read from a listing at most two seconds old. Never the basis for giving one an
   instruction; conduct! reads its own."
  [opts]
  (into {} (map (juxt :sessionId identity)) (substrate/listing-cached opts)))

;; ── after a reboot ──────────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat] [:maybe :string]]}
  current-boot
  "This host boot's identity: the boot time the kernel reports, or nil when it cannot be read."
  []
  (try (second (re-find #"sec = (\d+)" (:out (p/shell {:out :string} "sysctl" "-n" "kern.boottime"))))
       (catch Exception _ nil)))

(defn- boot-marker-path [] (str (fs/path (cstate/coordinator-dir) "recovered-boot.edn")))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  recover!
  "At nido's first start after a host boot, restart once each session with a :claude facet whose
   policy is auto-respawn and that has no live process — whatever stopped it — and restart no
   other. Every other such session without a live process is answered as resumable: attaching
   to it resumes it from its saved conversation. A later start in the same boot restarts
   nothing — but a restart Claude Code refused is no restart: it is answered under :failed,
   its session is not marked, and the boot stays unrecovered so the next start tries it again.
   Answers {:boot :first-start? :restarted :failed :resumable}."
  [opts]
  (let [boot   (or (:boot opts) (current-boot))
        first? (and boot (not= boot (io/read-edn (boot-marker-path))))
        listing (substrate/listing opts)
        idle   (for [{:keys [facet] :as s} (faceted-sessions opts)
                     :when (not (:pid (row-for listing facet)))]
                 s)
        restart? (fn [{:keys [facet]}]
                   (and first? (get-in facet [:policy :auto-respawn?])
                        (not= boot (:recovered-boot facet)) (:bg-id facet)))
        attempts (doall
                  (for [{:keys [project ws-id session-name facet] :as s} idle
                        :when (restart? s)]
                    (let [who {:project project :ws-id ws-id :session session-name}]
                      (try
                        (substrate/respawn! (:bg-id facet)
                                            (assoc opts :cwd (:cwd (row-for listing facet))))
                        (session/update-claude! project ws-id session-name #(assoc % :recovered-boot boot))
                        [:restarted who]
                        (catch Exception e
                          [:failed (assoc who :because (ex-message e))])))))
        by-kind (group-by first attempts)]
    (when (and first? (empty? (:failed by-kind))) (io/write-edn! (boot-marker-path) boot))
    {:boot        boot
     :first-start? (boolean first?)
     :restarted   (mapv second (:restarted by-kind))
     :failed      (mapv second (:failed by-kind))
     :resumable   (vec (for [{:keys [project ws-id session-name] :as s} idle
                             :when (not (restart? s))]
                         {:project project :ws-id ws-id :session session-name}))}))
