;; src/tasks/nido_owed.clj
(ns tasks.nido-owed
  "bb-task entrypoint: what does this workstream owe, and does a person owe it.

   THE NON-ACTING FORM OF `bb nido:attach`. Attach asks this same question and
   then does something about the answer — follows a live run, fires a stage,
   prints what is owed. This only answers, which is what makes it safe to ask
   somewhere doing something would be the last thing anybody wants: at a turn
   boundary, on every turn, in a session a person is working in.

   It decides nothing the pipeline has not decided already. The mode is the
   whole of the answer, and who owes it comes from `work/awaiting-human` rather
   than from a test written here — the same function that puts a workstream in
   the needs-you inbox. Asking it means the inbox and the turn boundary can
   never disagree about whether a person is holding something up, which is a
   stronger property than either of them checking for itself."
  (:require
   [nido.coordinator.lane.pipeline :as pipeline]
   [nido.coordinator.record.activity :as activity]
   [nido.coordinator.work :as work]
   [nido.platform.task-args :as task-args]
   [nido.review.stages :as stages]
   [nido.session.lifecycle :as lifecycle]))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :map]}
  owed
  "What `ws-id` owes: the position, the stage and mode due, the stage a PERSON
   owes if one does, and whoever holds the activity claim.

   `:person` is a stage rather than a boolean because the two callers want
   different halves of it — a boundary wants to know THAT somebody does, a
   person wants to know WHAT — and nil is already the answer to the first.

   The claim is read here rather than left to the caller because it is part of
   the same answer: a workstream somebody else is already advancing owes this
   caller nothing, whatever its position says."
  [project ws-id]
  (let [position (pipeline/of project ws-id)]
    {:at      (:at position)
     :stage   (:stage (:next position))
     :mode    (:mode (:next position))
     :person  (work/awaiting-human position)
     :held-by (:run-id (activity/read-live project ws-id))}))

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  owed-line
  "One line saying what is owed, for a person and for a model alike.

   Both read it, which is why it names the command as well as the stage: a
   person needs to know what to type and a model needs to know what it may run,
   and neither is served by a keyword on its own."
  [{:keys [at stage mode person held-by]}]
  (cond
    held-by      (str "held by " held-by " — something is already advancing this"
                      " workstream; nothing is owed to you until it lets go")
    (nil? stage) (str "nothing owed — at " (name at) ", and this arc is over")
    person       (str "a person owes " (name person) " — at " (name at)
                      ". Nothing runs until they answer, at the gate"
                      " (it is already in the needs-you inbox: bb nido:ui)")
    :else        (str "owed: " (name stage) " (" (name mode) ") — at " (name at)
                      ". `bb nido:attach` runs whatever the ledger chooses")))

(defn ^{:malli/schema [:=> [:cat :any] [:maybe :map]]}
  owed-at
  "The answer for wherever the caller is standing, or nil when that is no
   workstream.

   Through the home-aware union, for the reason every cwd-based verb here takes
   it: a session HOME is a place an agent legitimately stands, and
   `project+ws-from-cwd` resolves only inside the worktree."
  [given]
  (let [here (or (lifecycle/worktree-from-cwd given) given)]
    (when-let [[project ws-id] (stages/project+ws-from-cwd here)]
      (assoc (owed project ws-id) :project project :ws-id ws-id))))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  owed-cmd*
  "Print what is owed. A READING, and it exits zero whatever it finds — the same
   contract `bb nido:reentry:report` and `bb nido:design:diff` hold, and for the
   same reason: a question that fails is a question nobody can ask in a script."
  [{:keys [cwd]}]
  (let [given (or cwd (System/getProperty "user.dir"))]
    (if-let [answer (owed-at given)]
      (do (println (owed-line answer)) answer)
      (do (println (str given " resolves to no workstream — nothing owes anything here"))
          nil))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  owed-cmd [& args]
  (let [[_ opts] (task-args/split-args args)]
    (owed-cmd* opts)))
