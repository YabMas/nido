(ns nido.coordinator.lane.resume
  "Re-engage an autonomous session waiting on a person with their input: the input is the
   session's next instruction, which conduct gives the background session once the turn is
   admitted. Which session waits is `waiting-session`'s answer — run records, the executor,
   status and the session's :claude facet — so this works whether the owning Run is
   :awaiting-review or the workstream is held by a driver's blocker. This is the :reply resolver
   behind nido.coordinator.work/resolve-gate!."
  (:require
   [nido.coordinator.agent.substrate :as substrate]
   [nido.coordinator.lane.conduct :as conduct]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.executor :as executor]
   [nido.coordinator.lane.pipeline :as pipeline]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.session :as session]))

(defn- turn-key
  "What a reply turn into session `s` is queued under: its owning run, or the session itself when
   no run owns it."
  [s run]
  (or (:id run) (str "session-" (:name s))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:maybe :Session]]}
  waiting-session
  "The first autonomous session under a workstream that waits on a person's reply, or nil: not
   archived, no reply to it already queued (in the executor, or as an instruction conduct has not
   seen the session Stop after), and either its run parked for review or the workstream's status
   owing a person its next move — a driver's blocker, which parks no run. Read off run records,
   the executor, status and the session's :claude facet; no stored phase says it."
  [project ws-id]
  (let [owed-person? (delay (= :person (:owed-by (pipeline/status project ws-id))))]
    (->> (session/list-sessions project ws-id)
         (filter #(and (session/autonomous? %) (session/live? %)))
         (filter (fn [s]
                   (let [run (runs/find-for-session project ws-id (:name s))]
                     (and (not (executor/turn-pending? (turn-key s run)))
                          ;; A reply is an instruction conduct holds until the session's Stop:
                          ;; one given or waiting is a reply in flight.
                          (not (get-in s [:claude :pending]))
                          (not (get-in s [:claude :queued]))
                          (or (= :awaiting-review (:state run)) @owed-person?)))))
         first)))

(defn ^{:malli/schema [:function
                        [:=> [:cat :ProjectName :WorkstreamId :string] :any]
                        [:=> [:cat :ProjectName :WorkstreamId :string [:maybe :Session]] :any]]}
  resume!
  "Re-engage the session waiting on a person under `ws-id` (`waiting-session`) with `input`, as
   its next instruction: handed to the executor as a turn, which queues it through conduct once a
   slot frees — and, queued there, the turn is what stops a second reply being accepted for the
   same session. Conduct gives it when the session is ready, and the session's own Stop re-parks
   it. An instruction conduct refuses is recorded as the session's error.

   Returns {:resumed <session-name> :queued? <bool> :via :substrate}, and :unaccounted? true when
   the turn ran outside the executor because nothing in this process ticks it. Throws ex-info,
   recording the reason as the session's error where there is a session, when no session waits
   (:not-parked), when the session nido started has no id from Claude Code yet
   (:session-unnamed), and when the session's record carries no :claude facet
   (:no-background-session) — a conversation from before background sessions, which nido no
   longer continues.

   The four-argument arity resumes `waiting`, a session its caller found waiting BEFORE recording
   the person's answer. It cannot look again: an answer on the ledger is what stops the workstream
   owing the person its next move, so a session held only by a driver's blocker — its run already
   settled — no longer reads as waiting once the answer that releases it is written."
  ([project ws-id input] (resume! project ws-id input (waiting-session project ws-id)))
  ([project ws-id input waiting]
   (let [s       waiting
         refuse! (fn [reason message]
                   (session/set-error! project ws-id (:name s)
                                       {:at (clock/now-iso) :reason reason :message message})
                   (throw (ex-info message {:reason reason :project project :ws-id ws-id})))]
     (when-not s
       (throw (ex-info "No parked session to resume"
                       {:reason :not-parked :project project :ws-id ws-id})))
     (cond
       ;; A start in flight: conduct names the session once a listing shows it, and there is no
       ;; facet for a reply to be queued on before then.
       (:dispatching s)
       (refuse! :session-unnamed "The session nido started has no id from Claude Code yet — reply again once it does")

       (nil? (:claude s))
       (let [run (runs/find-for-session project ws-id (:name s))
             sid (or (get-in s [:autonomy :claude-session-id]) (:claude-session-id run))]
         (refuse! :no-background-session
                  (str "This conversation predates background sessions, and nido no longer continues it"
                       (if sid (str " — continue it yourself with " (substrate/resume-command sid)) "")
                       ", or close the workstream"))))
     ;; Queued through the executor like any turn, so the reply is admitted under the global cap
     ;; before conduct may give it — its trigger's in-flight slot the parked run already holds
     ;; (see executor/submit-turn!); once given, the session holds its global slot while the
     ;; substrate reports it working. Keyed per turn, because a Run is spawned once and replied to
     ;; any number of times; keyed on the run-id alone submit!'s idempotence would swallow every
     ;; reply after the first.
     (let [run  (runs/find-for-session project ws-id (:name s))
           body #(try (conduct/instruct! project ws-id (:name s) input)
                      (catch Throwable t
                        (session/set-error! project ws-id (:name s)
                                            {:at      (clock/now-iso)
                                             :reason  (or (:reason (ex-data t)) :resume-failed)
                                             :message (ex-message t)})))]
       (session/set-error! project ws-id (:name s) nil)
       (if (executor/driven?)
         (do (executor/submit-turn!
              {:run-id  (turn-key s run)
               :turn    (str (random-uuid))
               :trigger (:trigger run)
               :body    body})
             {:resumed (:name s) :queued? true :via :substrate})
         ;; Nobody ticks the executor in this process — a standalone `bb nido:ui` rather than the
         ;; daemon — so queueing would park the turn forever and say nothing. Run it, and SAY that
         ;; it was not slot-accounted rather than letting the caller assume it was.
         (do (body)
             {:resumed (:name s) :queued? false :unaccounted? true :via :substrate}))))))
