;; src/nido/coordinator/lane/drive.clj
(ns nido.coordinator.lane.drive
  "Where a driven workstream stops.

   The driver itself is the layer above this one. This is deliberately the
   earlier half: a stage the driver fires can reach :escalate on its very first
   run, so the place it stops has to exist before anything can stop there. A
   driver that could fire but not park would strand a workstream mid-pipeline
   with nothing on the ledger saying why.

   Parking is two facts and no cleverness. The ledger gains a halt saying what
   was needed and what had already been tried, and the session — if one is alive
   — goes to :parked so the gate surface offers it. Neither is derivable from the
   other: the ledger entry is what the NEXT session reads, and the phase is what
   puts the row in front of a human now."
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.daemon.executor :as executor]
   [nido.coordinator.record.report :as report]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.workstream :as ws]
   [nido.platform.io :as io]
   [nido.coordinator.lane.pipeline :as pipeline]
   [nido.session.state :as session-state]))

(defn attempt
  "One entry for a halt's :tried — what a stage did and what it ended on.

   The terminal keyword is carried verbatim rather than described, so the record
   a human reads and the value `pipeline/disposition` classified are the same
   thing."
  [{:keys [stage outcome rounds detail]}]
  (cond-> {:stage stage :outcome outcome}
    (and rounds (pos? rounds)) (assoc :rounds rounds)
    detail                     (assoc :detail detail)))

(defn halt-for
  "The halt record for a stage that reached `outcome`, as a value.

   Pure, and separate from writing it, because what a halt SAYS is the part worth
   testing and the part a reader will argue with. The prose is deliberately thin:
   the outcome names itself, the attempt says what produced it, and inventing a
   fuller explanation here would be this module guessing at a judgement the round
   already made and recorded.

   `needs` is the caller's, because only the stage that ran knows what it could
   not settle. Absent, the question falls back to the honest general one — a
   reader is better served by `the driver cannot take this further` than by a
   confident sentence nobody derived."
  [{:keys [stage outcome needs summary tried options]}]
  (cond-> {:format  :blocker
           :summary (or summary
                        (str "the " (name stage) " stage stopped at "
                             (name outcome) " and the driver cannot take it further"))
           :needs   (or needs
                        (str "a decision about " (name stage)
                             " — everything derivable has been derived"))}
    (seq tried)   (assoc :tried (mapv attempt tried))
    (seq options) (assoc :options (vec options))))

(defn park!
  "Stop this workstream and record why. Returns {:parked <session-or-nil>
   :seq <ledger-seq>}, or {:refused <reason>} when there is nothing to write.

   Refuses rather than writing a malformed halt: a park that threw would leave
   the workstream running with a stage already finished, which is worse than one
   that stopped and said it could not explain itself.

   Best-effort on the PHASE and strict on the LEDGER, and the asymmetry is
   deliberate. The ledger entry is the durable half — it is what the next session
   reads and what the gate renders — while a session to park may simply not exist
   (a mechanical stage runs as a task, not as an agent, so there is often nobody
   to put to sleep). Failing the whole park because there was no session would
   throw away the record that matters."
  [project ws-id halt]
  (let [record (halt-for halt)]
    (if-not (try (report/validate-event :blocker record) (catch Throwable _ nil))
      {:refused :invalid-halt :record record}
      (let [path (ws/append-entry! project ws-id {:kind :blocker} (pr-str record))
            seq-n (some->> path (re-find #"/(\d+)-blocker\.edn$") second parse-long)
            s    (->> (session/list-sessions project ws-id)
                      (remove session/parked?)
                      (filter :autonomy)
                      first)]
        (when s
          (try (session/set-phase! project ws-id (:name s) :parked)
               (catch Throwable _ nil)))
        {:parked (:name s) :seq seq-n}))))

(defn park-on-escalate!
  "Park iff this outcome's disposition is :escalate; otherwise do nothing and say
   so.

   The gate between a terminal and a halt lives here rather than at the call
   site, so every driven stage asks the question the same way and none of them
   carries its own opinion about which statuses are a person's business."
  [project ws-id {:keys [outcome] :as halt}]
  (let [d (pipeline/disposition outcome)]
    (if (= :escalate d)
      (assoc (park! project ws-id halt) :disposition d)
      {:disposition d})))

;; ── Which workstreams the driver may advance ────────────────────────────────

(defn driven
  "The workstreams the driver is allowed to advance, as #{[project ws-id]}.

   An ALLOW-LIST, empty by default, and that is the whole safety of this phase.
   Landing the driver must not start driving every open workstream at once —
   brian holds 45 — because the phase's exit criterion is one real workstream
   observed going through, and because a change that cannot be introduced to one
   thing first is a big bang whatever else it is.

   It is also the cheapest possible undo for a phase that has none: nido stops
   driving a workstream the moment it leaves this file, without a deploy. That
   is not the same as undoing the phase — records it already wrote stay written —
   but it is the difference between a bad outcome you can stop and one you watch."
  []
  (set (map vec (io/read-edn (cstate/driving-path)))))

(defn driving?
  [project ws-id]
  (contains? (driven) [(keyword project) ws-id]))

(defn drive!
  "Add a workstream to the allow-list. Returns the new set."
  [project ws-id]
  (let [next-set (conj (driven) [(keyword project) ws-id])]
    (io/write-edn! (cstate/driving-path) (vec next-set))
    next-set))

(defn undrive!
  "Take a workstream off the allow-list. It stops being advanced on the next
   tick; anything already in flight for it finishes on its own."
  [project ws-id]
  (let [next-set (disj (driven) [(keyword project) ws-id])]
    (io/write-edn! (cstate/driving-path) (vec next-set))
    next-set))

;; ── What to fire ────────────────────────────────────────────────────────────

(def mechanical-stages
  "The stages the driver may run itself, and the task each one is.

   Only stages that need no agent turn of the driver's own — the record loops
   are processes that drive their own agents internally, which is what makes
   them safe to fire without a session to fire them into.

   A stage absent here is not fired, whatever its mode says. The projection can
   name a stage this phase cannot run — that is the normal case, and it parks
   rather than pretending."
  {:verify-baseline {:task 'tasks.nido-review/baseline-cmd* :label "baseline"}
   :decide-design {:task 'tasks.nido-review/design-cmd*   :label "design"}})

(defn fireable
  "What the driver should fire for this workstream, or nil with a reason.

   Pure over the projection: it decides nothing about slots or Runs, only whether
   this workstream's next action is one this phase knows how to run. The
   in-flight check lives in the caller, because it is a fact about the executor
   rather than about the ledger."
  [position]
  (let [{:keys [stage mode]} (:next position)]
    (cond
      (nil? (:next position))            {:skip :terminal}
      (= :human mode)                    {:skip :waiting-on-a-human}
      (not= :mechanical mode)            {:skip :not-mechanical :stage stage}
      (nil? (mechanical-stages stage))   {:skip :no-runner :stage stage}
      :else                              {:fire stage})))

;; ── Firing one ──────────────────────────────────────────────────────────────

(defn- session-cwd
  "A path under this workstream that resolves back to it, or nil.

   The record loops take a :cwd and resolve the session — and therefore the
   ledger — from it. The session HOME is what answers that, and it is what
   resume.clj already uses for the same reason."
  [project ws-id]
  (when-let [s (first (session/list-sessions project ws-id))]
    (let [home (str (session-state/session-home-dir (name project) (:name s)))]
      (when (fs/exists? home) home))))

(def max-attempts
  "How many times the driver runs one stage before it stops trying.

   Three, and the number is about INFRASTRUCTURE rather than about work. A
   :retry outcome — codex exited non-zero, wrote no answer, crashed — says the
   machinery failed and not that the round decided something, so trying again is
   the right first response and trying forever is not. Two retries clears a
   transient blip; a third identical failure is a fact about the machine that a
   person needs to hear.

   The loops' own iteration counts are deliberately NOT this. They are uncapped
   because they end on their own merits — a round that changes nothing ends the
   run — and a round that never returns at all is the one failure they cannot
   detect, which is what the per-launch budget is for. This is the layer above
   both: the stage itself refusing to start."
  3)

(defn- backoff-ms
  "How long to wait before attempt `n`. Short, because this is spent holding a
   slot — at a cap of two, a driver sleeping for minutes is half the machine
   idle. Long enough to clear a blip, and no attempt to ride out an outage: an
   outage should exhaust the attempts and reach a person."
  [n]
  (* 5000 n))

(defn run-stage!
  "Run one mechanical stage to a settled outcome, then act on what it means.

   THE DRIVER DOES NOT WAIT ON A SLOT WHILE HOLDING ONE. This body runs inside
   the slot the executor gave it and calls the stage in-process; it submits
   nothing and blocks on nothing it would have to be promoted for. With
   :global-parallel-cap at 2, a driver that fired a Run and waited for it would
   wedge against a second driven chain doing the same, permanently. Nothing here
   nests, and that is a property to keep rather than an accident.

   Retries are BOUNDED and the retrying happens here, inside one Run, rather than
   across ticks. Across ticks it would need to remember how many times it had
   tried — state that is not derivable, because a :retry outcome writes no ledger
   entry to count. Inside one Run there is nothing to remember: the loop holds
   the count, the Run's own budget bounds the total, and every attempt is on the
   halt if it never settles.

   Every settled terminal goes through the same gate: :escalate parks, and the
   other three do nothing here. Advancing is not an action — the NEXT tick reads
   the ledger the stage just wrote and sees the new position — so there is
   nothing to remember between rounds and nothing to get out of step.

   And parking is itself the stop. A halt makes the workstream :blocked, whose
   next action is a person's, so `fireable` skips it: the driver does not need to
   be told to leave a parked workstream alone, because the ledger already says
   so."
  ([project ws-id stage] (run-stage! project ws-id stage {}))
  ;; :cwd and :sleep-fn are injection seams. A caller that already knows where
  ;; the workstream lives should not make this re-derive it, and a test should
  ;; not spend the backoff it is asserting about.
  ([project ws-id stage {:keys [sleep-fn cwd] :or {sleep-fn #(Thread/sleep %)}}]
   (let [{:keys [task label]} (mechanical-stages stage)
         cwd (or cwd (session-cwd project ws-id))]
     (cond
       (nil? task) {:skip :no-runner :stage stage}
       (nil? cwd)  (park-on-escalate! project ws-id
                                      {:stage stage :outcome :no-workstream
                                       :needs (str "a session for " ws-id
                                                   " — the " (name stage)
                                                   " stage needs a worktree to run in")})
       :else
       (loop [attempt 1, tried []]
         (let [status  (try ((requiring-resolve task) {:cwd cwd})
                            (catch Throwable t
                              (binding [*out* *err*]
                                (println (str "nido drive: " label " stage threw on "
                                              ws-id " — " (ex-message t))))
                              :round-crashed))
               outcome (if (keyword? status) status :unusable-answer)
               tried   (conj tried {:stage stage :outcome outcome
                                    :detail (when (> attempt 1)
                                              (str "attempt " attempt " of " max-attempts))})]
           (cond
             ;; The round said something. Whatever it said, it is not a machine
             ;; failure, so trying again would be running a decided stage twice.
             (not= :retry (pipeline/disposition outcome))
             (assoc (park-on-escalate! project ws-id
                                       {:stage stage :outcome outcome :tried tried})
                    :outcome outcome :attempts attempt)

             (< attempt max-attempts)
             (do (sleep-fn (backoff-ms attempt))
                 (recur (inc attempt) tried))

             ;; Out of attempts. The outcome still classifies :retry, so
             ;; park-on-escalate! would decline it — this is the one place that
             ;; parks something the table calls retryable, because the fact being
             ;; reported is no longer the outcome but the repetition.
             :else
             (assoc (park! project ws-id
                           {:stage stage :outcome outcome :tried tried
                            :summary (str "the " (name stage) " stage failed to run "
                                          max-attempts " times, most recently at "
                                          (name outcome))
                            :needs (str "someone to look at why " label
                                        " cannot run — this is the machinery "
                                        "failing rather than the round deciding "
                                        "anything")})
                    :outcome outcome :attempts attempt :exhausted? true))))))))

;; ── The tick ────────────────────────────────────────────────────────────────

(defn- drive-run
  "A Run for one driven stage. Reuses the workstream's existing session rather
   than provisioning one — the same thing ship.clj does for the merge lane, and
   the reason the hybrid shape was chosen: a record loop wants the warm worktree,
   not a fresh clone of it."
  [project ws-id stage session-name]
  (let [suf (subs (str (random-uuid)) 0 8)]
    {:id                (str (subs (clock/now-iso) 0 10) "-" (name project)
                             "-drive-" (name stage) "-" suf)
     :project           project
     :trigger           :drive
     :source            {:type :drive}
     :event-payload     {:id ws-id :stage (name stage)}
     :skill             :drive
     :first-message     (str "drive " (name stage))
     :agent             :claude
     :session-name      session-name
     :workstream-id     ws-id
     :claude-session-id nil
     ;; The stage's own agents are launched by the loop it runs, each already
     ;; bounded by tasks.nido-review/default-launch-budget. This bounds the whole
     ;; stage, which can be many rounds of them.
     :limits            {:budget "8h" :max-failures 3}
     :priority          0
     :session-profile   :full
     :mode              :mechanical
     :uncapped?         false
     :state             :queued
     :state-history     [{:at (clock/now-iso) :state :queued}]
     :artifacts         []
     :error             nil}))

(def ^:private claimed-states
  "Run states that mean this stage is already spoken for.

   Deliberately NOT runs/in-progress-states, which excludes :queued because a
   queued Run occupies no trigger budget — true, and the wrong question here. A
   queued drive Run is one the executor is going to promote; counting it as
   nothing fires the same stage again on the very next tick, and again, until a
   slot frees and all of them run.

   The two sets answer different questions — `is this consuming budget` and `is
   this stage already claimed` — and collapsing them is how a guard that reads
   correctly does nothing."
  (conj runs/in-progress-states :queued))

(defn in-flight?
  "Is a drive Run already claiming this workstream?

   The one thing the tick must not do is fire a second stage while the first is
   outstanding: the projection still reports the OLD position — the stage has not
   written its record yet — so an unguarded tick re-fires the same stage every
   tick until it finishes."
  [ws-id]
  (boolean
   (some (fn [rid]
           (let [r (runs/read-run rid)]
             (and (= :drive (:trigger r))
                  (= ws-id (:workstream-id r))
                  (contains? claimed-states (:state r)))))
         (runs/list-run-ids))))

(defn tick!
  "Advance every workstream on the allow-list by at most one stage.

   Submits and returns. It never waits for what it fired — see run-stage! — and
   it fires at most one stage per workstream per tick, because the projection
   cannot see a stage that has not written its record yet.

   Returns what it decided, per workstream, so an operator can read why nothing
   happened as easily as why something did."
  ([] (tick! nil))
  ([submit!]
   (let [submit! (or submit! executor/submit!)]
     (into []
           (for [[project ws-id] (driven)
                 :let [position (pipeline/of project ws-id)
                       decision (fireable position)]]
             (cond
               (:skip decision)
               {:ws-id ws-id :at (:at position) :skipped (:skip decision)}

               (in-flight? ws-id)
               {:ws-id ws-id :at (:at position) :skipped :already-running}

               :else
               (if-let [sname (:name (first (session/list-sessions project ws-id)))]
                 (let [run (drive-run project ws-id (:fire decision) sname)]
                   (fs/create-dirs (cstate/run-dir (:id run)))
                   (runs/write-run! run)
                   (submit! {:run-id (:id run) :priority 0 :uncapped? false
                             :trigger :drive})
                   {:ws-id ws-id :at (:at position) :fired (:fire decision)
                    :run-id (:id run)})
                 {:ws-id ws-id :at (:at position) :skipped :no-session})))))))
