;; src/tasks/nido_attach.clj
(ns tasks.nido-attach
  "bb-task entrypoint: attach to a workstream, and let its ledger choose the
   stage.

   NAMES NO STAGE. Every other way into this pipeline takes one as an argument —
   `bb nido:review:baseline` says which round it wants before anything has
   looked at the workstream — so a verb is an unchecked assertion about state.
   Here the caller says which workstream and `pipeline/of` says what is due,
   which is the same thing the driver does for the stages it may run unasked and
   the gate does for the ones only a person can take."
  (:require
   [nido.coordinator.lane.drive :as drive]
   [nido.coordinator.lane.pipeline :as pipeline]
   [nido.coordinator.record.activity :as activity]
   [nido.coordinator.record.session :as csession]
   [nido.platform.task-args :as task-args]
   [nido.review.frontend :as frontend]
   [nido.review.render :as render]
   [nido.review.stages :as stages]
   [nido.session.lifecycle :as lifecycle]))

(def ^:private kind-word
  "What to call a holder's activity in a sentence. The claim's own vocabulary
   read out loud — `activity/kinds` is the closed set this covers."
  {:diff-review "diff review" :baseline-round "baseline" :design-round "design"})

(defn- frame-for
  "The renderer for whatever holds the claim.

   The frame is NOT one function, and following through the wrong one is the
   thing following was meant to prevent: a record loop painted by the diff
   review's frame is headed `base nil` and has its judge and amend phases put
   through a renderer that says nothing about a verdict. The claim's :kind is
   what says which, and it is published for exactly this."
  [project ws-id {:keys [kind]}]
  (if (= :diff-review kind)
    render/frame
    (let [title (str (get kind-word kind (name kind)) " loop · "
                     (name project) "/" ws-id)]
      (fn [report now] (render/record-frame report now {:title title})))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :any] :any]}
  follow-holder!
  "Paint the holder's own run until it lets go, then answer with what it ended
   on.

   Whatever it is. Attach asked for no particular work, so any live work is the
   answer to what it asked — which is why there is no kind-and-target comparison
   here. That comparison exists in `join-or-refuse!` to protect a caller who
   NAMED work from watching something else finish, and this caller named none.

   Takes no claim: following is a read. Stopping is keyed on THIS holder rather
   than on the workstream being claimed at all, or a finished run's frozen
   report would be painted for as long as its replacement ran.

   WHOSE CLAIM IT IS ONCE FOLLOWING STOPS is what says whether the report is
   this invocation's answer. `read-live` may hand back the previous holder's
   payload while a replacement takes the claim and before it publishes, so the
   run followed here can be one that reached its verdict before this attach
   existed — and reporting that verdict would answer with work nobody asked
   for. The join protocol calls that :detached because its caller named work
   the replacement may not be doing; this caller named none, so the replacement
   is as much the answer as the first holder was, and it is followed in turn."
  [project ws-id holder]
  (loop [holder holder]
    (println (str "attached to " (:run-id holder) " (pid " (:pid holder)
                  ", since " (:started-at holder) ")"
                  " — Ctrl-C detaches, the run keeps going"))
    (frontend/follow! {:report-path (:report-path holder)
                       :render-fn   (frame-for project ws-id holder)
                       :stop? #(not= (:run-id holder)
                                     (:run-id (activity/read-live project ws-id)))})
    ;; Asked here rather than at attach time because a terminal report cannot
    ;; answer it: a run finalizes its report and then holds the claim through
    ;; its post-processing, so `already finished` is the normal state of a
    ;; holder that is still working.
    (let [now         (activity/read-live project ws-id)
          replacement (when (and now (not= (:run-id holder) (:run-id now))) now)
          s           (:status (frontend/read-report (:report-path holder)))
          status      (if (and (string? s) (not= "running" s)) (keyword s) :detached)]
      (println (str "detached — " (:run-id holder)
                    (cond
                      replacement          " was replaced while this watched it"
                      (= :detached status) " is no longer running, and left no terminal status"
                      :else                (str " ended " (name status)))))
      (if replacement
        (recur replacement)
        status))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :any :map] [:vector :string]]}
  skip-lines
  "What to tell a person about a position nothing will fire for them.

   This is where `fireable`'s skip reasons stop being a driver's shrug. The
   driver has one use for them — not this one, next tick — and discards them
   into a log line; a person needs the stage, why nobody will run it, and the
   command that would. Same decision, rendered for the other caller."
  [project ws-id position {:keys [skip]}]
  ;; The stage comes off the POSITION rather than off the decision, because
  ;; `fireable` carries one only on the branches its own caller needs it for —
  ;; :waiting-on-a-human has none, and that is precisely the case where a person
  ;; most needs to be told what they owe.
  (let [at    (name (:at position))
        stage (:stage (:next position))
        mode  (:mode (:next position))
        sname (:name (first (csession/list-sessions project ws-id)))]
    (into
     [(str "at " at (when stage (str " · next is " (name stage)
                                     (when mode (str " (" (name mode) ")")))))]
     (case skip
       :terminal
       ["nothing is owed — this workstream's arc is over"]

       :waiting-on-a-human
       ["a person owes this one; nothing will run until they answer"
        "  answer it at the gate: bb nido:ui"]

       :not-mechanical
       (if sname
         [(str "this is a turn in the tree, not a task — nothing runs it for you")
          (str "  take it: bb nido:session:enter :project " (name project) " " sname)]
         [(str "this is a turn in the tree, and there is no session to take it in")
          (str "  bring one up: bb nido:session:up :project " (name project) " <name>")])

       :no-runner
       [(str "nothing can run " (when stage (name stage)) " — it is mechanical "
             "and absent from mechanical-stages")
        "  the driver parks a driven workstream here; wiring a runner is the fix"]

       :claimed
       ["something else holds this workstream's claim — try again"]

       [(str "nothing to do: " (name skip))]))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :Path] :any]}
  attach!
  "Follow what is live, or run what is due, or say what is owed.

   FOLLOWING IS THE FIRST QUESTION, not a consequence of losing a race. Reaching
   the claim only by calling a runner would reach it only for a :mechanical
   stage, so a live round on a workstream whose position is :human or :authoring
   would never be found at all — and attach would report an approval as owed
   while a review of that very workstream painted frames somewhere else.

   Starting a second run against a claimed workstream is still prevented by the
   CLAIM rather than by that read. A holder appearing in between comes back from
   `run-stage!` as {:skip :claimed}, and the answer to it is to read again and
   follow: the same follow, reached from the other side of the race.

   AND IT RUNS WHERE THE CALLER IS STANDING, which is why `cwd` is carried this
   far rather than resolved again below. Left to itself `run-stage!` takes the
   first session `list-sessions` hands it, and that list is ordered by nothing
   and filtered by liveness not at all — so a workstream with two sessions could
   have a round, and every fixer it launches, judge a tree the person who asked
   is not looking at, or halt :no-workstream over a dead first session while the
   caller stood in a live one. The door had to resolve a worktree to find the
   workstream at all; the stage it chooses is for that worktree."
  [project ws-id cwd]
  (if-let [holder (activity/read-live project ws-id)]
    (follow-holder! project ws-id holder)
    (let [position (pipeline/of project ws-id)
          decision (drive/fireable position)]
      (if-let [stage (:fire decision)]
        (let [res (drive/run-stage! project ws-id stage {:cwd cwd})]
          (if (= :claimed (:skip res))
            (if-let [holder (activity/read-live project ws-id)]
              (follow-holder! project ws-id holder)
              :claimed)
            (:outcome res)))
        (do (run! println (skip-lines project ws-id position decision))
            (:skip decision))))))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  attach-cmd*
  "Resolve the workstream from where the caller is standing, then attach there.

   Through the home-aware resolution whether or not a :cwd was named, for the
   reason the review loops take it: a session HOME is a place an agent
   legitimately stands, and resolving from it directly would find no worktree
   and therefore no workstream.

   `here` goes on to `attach!` rather than only naming the workstream: what a
   chosen stage runs against is this worktree, and deriving it a second time
   down in the driver would pick a session by list order instead."
  [{:keys [cwd]}]
  (let [given (or cwd (System/getProperty "user.dir"))
        here  (or (lifecycle/worktree-from-cwd given) given)]
    (if-let [[project ws-id] (stages/project+ws-from-cwd here)]
      (attach! project ws-id here)
      (do (binding [*out* *err*]
            (println (str "attach: " here " resolves to no workstream — this is"
                          " not a nido session worktree, and there is nothing"
                          " whose ledger could choose a stage")))
          :no-workstream))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  attach-cmd [& args]
  (let [[_ opts] (task-args/split-args args)]
    (attach-cmd* opts)))
