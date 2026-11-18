(ns nido.coordinator.lane.drain
  "A drain's life: who may ask for each of its appends, how a slice starts and what its session is
   told, what the coordinator is told when it starts, when it is woken, and how a slice lands.

   WHO ASKS IS CHECKED HERE; WHAT THE ASK CITES IS CHECKED IN record-drain. The coordinator's levers
   — plan, withdraw, clear, dispose, answer a misfit, propose a recipe — are refused from any
   session but the drain's conductor (:not-the-coordinator). A misfit is refused from any session
   but one of a slice of that drain (:not-a-slice). The person's acts — start a drain, approve a
   recipe, land a slice, close the drain — are refused from the coordinator's and every slice's
   turns by the commit gate, which reads their tasks as a person's; this namespace does not try to
   tell a person from a turn on its own.

   ONE COORDINATOR. The drain workstream's conductor is its coordinator; a slice has none, and a
   slice's hand-backs are posted to its drain's coordinator — lane-conductor's to carry out, with
   this namespace's coordinator brief and slice brief.

   WAKES. The coordinator is given a turn on each new census, landing and failed landing of its
   drain: a :wake posted to the drain's conductor, keyed by the event so it is posted once. A wake
   owes no decision. While the wake for the drain's newest such event has not been delivered to the
   coordinator's session, no slice of the drain starts — the coordinator plans from what it has
   seen, and a start must not race what it has not.

   LANDING. A slice lands by a landing Run in its own session — /drive-home given to it — and never
   through the serial merge lane: landing Runs of sibling slices run side by side, and none starts
   while a slice it rests on has not landed. In this phase a landing is always the person's act;
   one of a slice the coordinator has not cleared is recorded on its Run as the person's override."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.string :as str]
   [nido.coordinator.lane.pipeline :as pipeline]
   [nido.coordinator.lane.seats :as seats]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.drain :as drain]
   [nido.coordinator.record.generation :as generation]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.seats :as rseats]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.report :as report]
   [nido.coordinator.source.drain :as source-drain]
   [nido.coordinator.source.queue :as queue]
   [nido.platform.project :as project]
   [nido.session.lifecycle :as lifecycle])
  (:import [java.util UUID]))

(defn- refuse! [message reason data]
  (throw (ex-info message (assoc data :reason reason :refused :drain-lane))))

;; ── the project's repository ────────────────────────────────────────────────

(defn- project-dir [project]
  (get-in (project/list-projects) [(name project) :directory]))

(defn- jj-repo? [dir] (fs/directory? (str (fs/path dir ".jj" "repo"))))

(defn- sh
  "Run `argv` in `dir`, answering {:exit :out :err}; never throws on a non-zero exit."
  [dir argv]
  (apply process/shell {:dir (str dir) :out :string :err :string :continue true} argv))

(defn- commit-held?
  "Whether the project's repository holds commit `id` — an id or a prefix of one, not a ref name."
  [project id]
  (let [dir (project-dir project)
        r   (sh dir (if (jj-repo? dir)
                      ["jj" "--ignore-working-copy" "log" "--no-graph" "-r" (str id) "-T" "commit_id"]
                      ["git" "rev-parse" "--verify" "--quiet" (str id "^{commit}")]))]
    (and (zero? (:exit r))
         (str/starts-with? (str/trim (:out r)) (str/lower-case (str id))))))

;; ── who is asking ───────────────────────────────────────────────────────────

(defn- here [] (System/getProperty "user.dir"))

(defn- caller
  "The session record `cwd` is in — its worktree or its home — as {:ws-id :record}, or nil."
  [project cwd]
  (let [n (or (some-> (lifecycle/session-from-cwd cwd) :session)
              (second (lifecycle/session-home-coords-from-cwd cwd)))]
    (when-let [ws-id (and n (session/workstream-id-for project n))]
      {:ws-id ws-id :record (session/read-session project ws-id n)})))

(defn- coordinating?
  "Whether the process runs in drain `drain-id`'s conductor's session — its coordinator."
  ([project drain-id] (coordinating? project drain-id (here)))
  ([project drain-id cwd]
   (let [{:keys [ws-id record]} (caller project cwd)]
     (boolean (and (= drain-id ws-id) (:conductor record))))))

(defn- slice-calling
  "The slice workstream of drain `drain-id` whose session the process runs in, or nil."
  [project drain-id cwd]
  (let [{:keys [ws-id]} (caller project cwd)
        w (some->> ws-id (ws/read-ws project))]
    (when (and w (drain/slice? w) (= drain-id (drain/drain-of w)))
      ws-id)))

(defn- must-coordinate! [project drain-id]
  (when-not (coordinating? project drain-id)
    (refuse! (str "Only " drain-id "'s coordinator — its conductor's session — pulls this lever")
             :not-the-coordinator {:ws-id drain-id})))

;; ── briefs ──────────────────────────────────────────────────────────────────

(defn- bullet-list [xs] (str/join "\n" (map #(str "- " %) xs)))

(defn- recipe-text
  "A recipe body as a brief gives it: its steps, ready gates and standing orders, verbatim."
  [{:keys [steps ready-gates standing-orders]}]
  (str "Steps:\n" (bullet-list steps) "\n\n"
       "Ready gates — every one passes before you hand back ready:\n" (bullet-list ready-gates) "\n\n"
       "Standing orders:\n" (if (seq standing-orders) (bullet-list standing-orders) "- none")))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :any]}
  slice-brief!
  "Slice `slice-id`'s brief, composed from its drain's current recipe — steps, ready gates and
   standing orders verbatim — and the plan it was started from. The slice is moved to that recipe
   first (`drain/rebase-slice!`, a :slice-rebased when it ran an older version), and the brief is
   composed from the version the move answers: every brief a slice session is given — at its
   start, a reseat, a recovery or an instruction — is of the version its ledger records."
  [project slice-id]
  (let [v    (drain/rebase-slice! project slice-id)
        sw   (ws/read-ws project slice-id)
        d    (drain/drain-of sw)
        dw   (ws/read-ws project d)
        plan (first (filter #(= (:plan (drain/slice-birth sw)) (:seq %)) (drain/plans dw)))]
    (str "You are slice " slice-id " of drain " d ", under its recipe version " v ". "
         "The drain's pattern is established; your job is to apply its recipe to the sites your plan "
         "names — no more — and to write no design of your own.\n\n"
         "Your plan (entry " (:seq plan) " on the drain):\n"
         "- sites: " (str/join ", " (:sites plan)) "\n"
         (when (seq (:rests-on plan))
           (str "- rests on plans " (str/join ", " (:rests-on plan)) " (" (name (:start plan)) ")\n"))
         (when (:note plan) (str "- note: " (:note plan) "\n"))
         "\n" (recipe-text (:recipe (ws/entry-at-seq project d v))) "\n\n"
         "When a site does not fit the recipe, record it — `bb nido:drain:misfit :site \"…\" :note \"…\"` "
         "from this session — and end your turn: the drain's coordinator answers it. When every ready "
         "gate passes, end your turn saying the slice is ready. Landing is the person's; never land it.")))

(defn- slice-line [project {:keys [ws-id plan]}]
  (let [at (try (:at (pipeline/of project ws-id)) (catch Throwable _ :unplaceable))]
    (str ws-id " (plan " plan ") — " (name (or at :unplaceable))
         (when (source-drain/landed? project ws-id) ", landed"))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :any]}
  coordinator-brief
  "Drain `drain-id`'s coordinator brief, composed from the drain's records alone: its charter, its
   recipe and pending proposals, its census series, its plans, its slices and their positions, its
   misfits and residue, and its open escalations — and the levers it pulls."
  [project drain-id]
  (let [w   (ws/read-ws project drain-id)
        ch  (drain/charter w)
        cs  (drain/censuses w)
        bl  (let [answered (set (map :blocker-seq (ws/entries-of project drain-id :blocker-answered)))]
              (remove #(answered (:seq %)) (ws/entries-of project drain-id :blocker)))]
    (str "You coordinate drain " drain-id " of " (name project) ". You occupy no seat and write no code. "
         "You plan the roll-out slice by slice as the census moves, never once up front; you answer "
         "each slice's hand-back with exactly one decision (`bb nido:seats:decide :hand-back <id> …`, "
         "instructing a slice with `:instruct '[{:slice <ws-id> :text \"…\"}]'` or "
         "escalating); you clear ready slices with the residue they leave; you answer misfits. "
         "A wake (a new census, a landing, a failed landing) owes no decision.\n\n"
         "Charter: established by " (pr-str (:established-by ch)) "; exit criterion: " (:exit ch) "\n"
         "Recipe: " (if-let [v (drain/version w)]
                      (str "version " v "\n" (recipe-text (:recipe (drain/recipe w)))
                           "\nCounter: " (get-in (drain/recipe w) [:recipe :counter :command]))
                      "none approved yet — propose one") "\n"
         "Pending proposals: " (or (seq (map :seq (drain/pending-proposals w))) "none") "\n"
         "Census (oldest first): " (if (seq cs)
                                     (str/join ", " (map #(str "#" (:seq %) " v" (:recipe %) " → " (:remaining %)) cs))
                                     "none yet") "\n"
         "Plans:\n" (if-let [ps (seq (drain/plans w))]
                      (bullet-list (map #(str "#" (:seq %) " " (name (:state %)) " " (pr-str (:sites %))
                                              (when (seq (:rests-on %)) (str " rests on " (:rests-on %))))
                                        ps))
                      "- none") "\n"
         "Slices:\n" (if-let [ss (seq (drain/slices w))] (bullet-list (map #(slice-line project %) ss)) "- none") "\n"
         "Open misfits:\n" (if-let [ms (seq (drain/open-misfits w))]
                             (bullet-list (map #(str "#" (:seq %) " " (:slice %) " at " (:site %) ": " (:note %)) ms))
                             "- none") "\n"
         "Residue:\n" (if-let [rs (seq (drain/residue w))]
                        (bullet-list (map #(str "#" (:seq %) " " (:site %) " → " (pr-str (:disposition %))) rs))
                        "- none") "\n"
         "Open escalations: " (if (seq bl) (str/join "; " (map report/blocker-question-text bl)) "none") "\n\n"
         "Your levers, each run from this session:\n"
         "- bb nido:drain:propose :reason \"…\" :recipe '{:steps […] :ready-gates […] :standing-orders […] :counter {:command \"…\"}}'\n"
         "- bb nido:drain:plan :sites '[…]' :rests-on '[<plan seq> …]' :start on-top|after-landing :census <seq> [:replaces <seq>] [:note \"…\"]\n"
         "- bb nido:drain:withdraw :plan <seq> :why \"…\"\n"
         "- bb nido:drain:clear :slice <ws-id> :residue '[{:site \"…\" :disposition {:to :closing}} …]'\n"
         "- bb nido:drain:dispose :residue <seq> :disposition '{:to :follow-up :plan <seq>}'\n"
         "- bb nido:drain:answer :misfit <seq> :answer '{:as :carved :site \"…\" :disposition {:to :closing}}' "
         "(or {:as :proposed :proposal <seq>}, {:as :escalated :asks \"…\"})\n"
         "Approving a recipe, landing a slice and closing the drain are the person's: escalate when one is due.\n\n"
         "Decide on the records each hand-back and wake carries, read again then, not on this brief.")))

;; ── the coordinator's levers ────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :any] :any]}
  propose!
  "The coordinator proposing a recipe, or a change to it. Answers the proposal's seq."
  [project drain-id proposal]
  (must-coordinate! project drain-id)
  (drain/propose-recipe! project drain-id proposal))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :any] :any]}
  plan!
  "The coordinator planning a slice. Answers the plan's seq."
  [project drain-id plan]
  (must-coordinate! project drain-id)
  (drain/plan-slice! project drain-id plan))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :any :any] :any]}
  withdraw!
  "The coordinator withdrawing an open plan."
  [project drain-id plan-seq why]
  (must-coordinate! project drain-id)
  (drain/withdraw-plan! project drain-id plan-seq why))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :WorkstreamId :any] :any]}
  clear!
  "The coordinator clearing a slice for landing with the residue it leaves."
  [project drain-id slice-id residue-items]
  (must-coordinate! project drain-id)
  (drain/clear-slice! project drain-id slice-id residue-items))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :any :any] :any]}
  dispose!
  "The coordinator disposing of a residue item again."
  [project drain-id residue-seq disposition]
  (must-coordinate! project drain-id)
  (drain/dispose-residue! project drain-id residue-seq disposition))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :any :any] :any]}
  answer-misfit!
  "The coordinator answering an open misfit."
  [project drain-id misfit-seq answer]
  (must-coordinate! project drain-id)
  (drain/answer-misfit! project drain-id misfit-seq answer))

;; ── a slice's misfit ────────────────────────────────────────────────────────

(defn ^{:malli/schema [:function [:=> [:cat :ProjectName :any] :any] [:=> [:cat :ProjectName :WorkstreamId :any] :any]]}
  misfit!
  "A slice's session recording a site its recipe version does not cover, on its drain. Refused
   from any session but one of a slice of the drain it names — or, given none, of the drain its
   session's slice belongs to. Answers {:drain :misfit}."
  ([project {:keys [site note]}] (misfit! project nil {:site site :note note}))
  ([project drain-id {:keys [site note]}]
   (let [cwd   (here)
         {:keys [ws-id]} (caller project cwd)
         own   (some->> ws-id (ws/read-ws project) drain/drain-of)
         d     (or drain-id own)
         slice (when d (slice-calling project d cwd))]
     (when-not slice
       (refuse! "A misfit is recorded from the session of a slice of the drain" :not-a-slice {:drain d}))
     {:drain d :misfit (drain/record-misfit! project d {:slice slice :site site :note note})})))

;; ── the person's acts ───────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :ProjectName :any] :any]}
  start-drain!
  "The person starting a drain from a pattern established elsewhere: refused when the workstream
   (:no-such-workstream) or commit (:no-such-commit) the charter names as having established it is
   not one the project holds. Answers its ws-id."
  [project {:keys [established-by] :as charter}]
  (let [{:keys [ws-id commit]} established-by]
    (when (and ws-id (nil? (ws/read-ws project ws-id)))
      (refuse! (str (name project) " holds no workstream " ws-id) :no-such-workstream {:ws-id ws-id}))
    (when (and commit (not (commit-held? project commit)))
      (refuse! (str (name project) " holds no commit " commit) :no-such-commit {:commit commit})))
  (drain/start-drain! project charter))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :any] :any]}
  approve!
  "The person approving a recipe proposal. Answers the new version."
  [project drain-id proposal-seq]
  (drain/approve-recipe! project drain-id proposal-seq))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :any]}
  close!
  "The person closing the drain: refused (:closed) on a drain closed already; then the exit observed
   — the newest census reads zero and every residue item is covered by a landed slice, or it is
   refused — then the drain closed done, the one close record-workstream takes as a drain's :done.
   Answers the :exit-observed seq."
  [project drain-id]
  (when (some-> (ws/read-ws project drain-id) ws/closure)
    (refuse! (str drain-id " is closed already") :closed {:ws-id drain-id}))
  (let [n (drain/observe-exit! project drain-id #(source-drain/landed? project %))]
    (ws/append-entry! project drain-id {:kind :closed}
                      (pr-str {:format :closed :outcome :done :by :person
                               :note (str "the drain's exit was observed at entry " n)}))
    n))

;; ── wakes ───────────────────────────────────────────────────────────────────

(defn- landing-runs
  "Every landing Run of the drain's slices, oldest first."
  [project drain-id]
  (->> (runs/list-run-ids)
       (filter #(str/includes? % (str "-" (name project) "-drain-land-")))
       (keep runs/read-run)
       (filter #(= drain-id (get-in % [:event-payload :drain])))
       (sort-by #(-> % :state-history first :at))))

(defn- wake-events
  "The events drain `w` wakes its coordinator for, each {:key :text} keyed by its event: every census
   reading, each landed slice, and each landing Run that failed and left its slice unlanded (read
   off the slice's records, as `drain/landing-outcome` settles it) — every one, never a newest per
   kind, so a reading appended before the last pass is woken too; the keys post each once. Grouped
   by kind, not ordered by when each happened."
  [project w]
  (let [d (:id w)]
    (concat
     (for [c (drain/censuses w)]
       {:key (str "wake:census:" (:seq c))
        :text (str "Census #" (:seq c) " under recipe version " (:recipe c) ": " (:remaining c) " remaining.")})
     (for [{:keys [ws-id]} (drain/slices w) :when (source-drain/landed? project ws-id)]
       {:key (str "wake:landed:" ws-id) :text (str "Slice " ws-id " landed.")})
     (for [r (landing-runs project d)
           :when (and (#{:failed :awaiting-review} (:state r))
                      (not (source-drain/landed? project (:workstream-id r))))]
       {:key (str "wake:landing-failed:" (:id r))
        :text (str "The landing of slice " (:workstream-id r) " (Run " (:id r) ") did not finish: " (name (:state r)) ".")}))))

(defn- has-coordinator? [project drain-id]
  (boolean (some :conductor (session/list-sessions project drain-id))))

(defn ^{:malli/schema [:=> [:cat :ProjectName] :any]}
  wakes!
  "Post to each open drain's coordinator the wake for each event it has not been woken for —
   through lane-seats' post!, keyed by the event, so each is posted once. A drain whose
   coordinator has never started is woken by its start's brief instead. Answers the wakes posted."
  [project]
  (vec
   (for [d (source-drain/open-drains project)
         :when (has-coordinator? project d)
         e (wake-events project (ws/read-ws project d))]
     (seats/post! project d (assoc e :kind :wake :to :conductor)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :any]}
  wake-pending?
  "Whether drain `drain-id`'s coordinator has not yet been given the wake for one of its events —
   a census reading, a landing, a failed landing: a wake not posted, or posted and not delivered.
   Every event is checked, not the last of `wake-events`, whose order is by kind and not by when
   each happened. While one is pending, no slice of the drain starts."
  [project drain-id]
  (let [w   (ws/read-ws project drain-id)
        evs (wake-events project w)]
    (boolean
     (when (seq evs)
       (let [msgs (into {} (map (juxt :key identity))
                        (mapcat #(seats/traffic project drain-id %)
                                (range 1 (inc (generation/current (:entries w))))))]
         (some #(not (:delivered? (msgs (:key %)))) evs))))))

;; ── starting a slice ────────────────────────────────────────────────────────

(defn- slice-embodiment
  "How a slice's whole seat is carried: its session named for the slice, briefed with `brief`."
  [brief]
  {:seat-name (fn [_] "slice")
   :practice  brief
   :refuse    (:refuse (seats/default-embodiment))})

(defn- slice-session
  "The session of slice `slice-id` that occupies its whole seat, or nil."
  [project slice-id]
  (:session (first (seats/occupants project slice-id))))

(defn- rested-on-sessions
  "For an :on-top plan of drain `dw`, the session of each slice it rests on that has not landed —
   the work a slice started from the plan starts on top of. Refused (:rests-unseated) when one has
   no session to start on. Nil for an :after-landing plan: what it rests on is on main."
  [project dw plan]
  (when (= :on-top (:start plan))
    (let [by-seq (into {} (map (juxt :seq identity)) (drain/plans dw))]
      (vec (for [r (:rests-on plan)
                 :let [slice (:slice (by-seq r))]
                 :when (not (source-drain/landed? project slice))]
             (or (slice-session project slice)
                 (refuse! (str "Plan " (:seq plan) " rests on " slice ", which has no session to start on")
                          :rests-unseated {:ws-id slice})))))))

(defn- in-repo!
  "Run `argv` in the project's repository — or in `dir`, a rested-on session's worktree — refusing
   (:base-failed) on a failure, as a start on the work of `bases` for `who`. Every command a
   slice's base is derived from runs through this, so none of their failures is read past."
  [project who bases argv & [dir]]
  (let [r (sh (or dir (project-dir project)) argv)]
    (when-not (zero? (:exit r))
      (refuse! (str "Could not start " who " on " (str/join ", " bases) ": " (str/trim (str (:err r) (:out r))))
               :base-failed {:bases bases}))
    r))

(defn- rested-base
  "The revision a slice resting :on-top of the sessions in `bases` starts from: the one's work, or a
   merge of several's. In a jj project that work is each base's working copy, snapshotted first; in
   a git project it is each base's branch — its committed work, since a git worktree's uncommitted
   edits are no revision, and so a start is refused (:base-uncommitted) while any base's worktree
   holds uncommitted edits, rather than starting without them; nothing is recorded, so the next
   poll's fire tries again. Refused (:base-failed) when the work cannot be read or merged — a git
   merge that conflicts among them."
  [project who bases]
  (let [what (str who " rests on " (str/join ", " bases))
        run  #(in-repo! project who bases %)]
    (if (jj-repo? (project-dir project))
      (let [jj  #(run (into ["jj" "--ignore-working-copy"] %))
            rev #(str/trim (:out (jj ["log" "--no-graph" "-r" % "-T" "commit_id"])))]
        (doseq [b bases]
          ;; The snapshot the lookup below reads: refused on a failure, never read past to the
          ;; working copy last recorded.
          (in-repo! project who bases ["jj" "status"] (:wt-path (lifecycle/session-coords b {:project (name project)}))))
        (if (= 1 (count bases))
          (rev (str (first bases) "@"))
          (rev (or (second (re-find #"Created new commit (\S+)"
                                    (:err (jj (into ["new" "--no-edit" "-m" what] (map #(str % "@") bases))))))
                   (refuse! (str "Could not read the merge of " (str/join ", " bases)) :base-failed {:bases bases})))))
      (let [git  #(run (into ["git"] %))
            head #(str/trim (:out (git ["rev-parse" "--verify" (str "refs/heads/" %)])))]
        (doseq [b bases
                :let [wt (:wt-path (lifecycle/session-coords b {:project (name project)}))]
                :when (not (str/blank? (:out (in-repo! project who bases ["git" "status" "--porcelain"] wt))))]
          (refuse! (str who " waits for " b " to commit its work: a git slice starts on committed work only")
                   :base-uncommitted {:bases bases :uncommitted b}))
        (reduce (fn [acc b]
                  (let [other (head b)
                        tree  (first (str/split-lines (:out (git ["merge-tree" "--write-tree" acc other]))))]
                    (str/trim (:out (git ["commit-tree" tree "-p" acc "-p" other "-m" what])))))
                (head (first bases))
                (rest bases))))))

(defn- place-branch!
  "Put the branch session `session-name` starts from — its jj bookmark or git branch — on revision
   `rev`, unless it is placed already, as a restart of a failed occupy finds it."
  [project session-name rev]
  (let [dir (project-dir project)]
    (if (jj-repo? dir)
      (when (str/blank? (:out (in-repo! project session-name [rev] ["jj" "--ignore-working-copy" "bookmark" "list" session-name "-T" "name"])))
        (in-repo! project session-name [rev] ["jj" "--ignore-working-copy" "bookmark" "create" session-name "-r" rev]))
      (when-not (zero? (:exit (sh dir ["git" "rev-parse" "--verify" "--quiet" (str "refs/heads/" session-name)])))
        (in-repo! project session-name [rev] ["git" "branch" session-name rev])))))

(defn- slice-session-name
  "The name seats/occupy! gives the slice embodiment's session: `<ws>--slice-g<generation>`."
  [sw]
  (str (:id sw) "--slice-g" (generation/current (:entries sw))))

(defn- occupy-slice-on!
  "occupy-slice!, on revision `base` when the slice's start already read the work it rests on
   (nil: read it here, when the slice rests :on-top of slices not yet landed)."
  [project slice-id base]
  (let [whole (first (filter :whole (rseats/seats project slice-id)))
        sw    (ws/read-ws project slice-id)
        dw    (ws/read-ws project (drain/drain-of sw))
        plan  (first (filter #(= (:plan (drain/slice-birth sw)) (:seq %)) (drain/plans dw)))]
    (when (nil? (slice-session project slice-id))
      (when-let [rev (or base
                         (let [bases (rested-on-sessions project dw plan)]
                           (when (seq bases) (rested-base project slice-id bases))))]
        (place-branch! project (slice-session-name sw) rev)))
    (seats/occupy! project slice-id (slice-embodiment (slice-brief! project slice-id)) (:id whole))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :any]}
  occupy-slice!
  "Occupy slice `slice-id`'s whole seat with a driven session briefed from its drain's recipe —
   at a slice's start, and whenever a reseat finds the seat empty. A slice whose plan rests :on-top
   of slices not yet landed starts on their work (`rested-base`). Answers {:seat :session}."
  [project slice-id]
  (occupy-slice-on! project slice-id nil))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :any] :any]}
  start-slice!
  "Start a slice from drain `drain-id`'s plan `plan-seq`: refused while the coordinator has not
   been given its newest wake (:wake-pending), when an :on-top plan rests on an unlanded slice with
   no session (:rests-unseated) or its work cannot be read or merged (:base-failed), and as
   record-drain refuses a start (no recipe, no census under
   it, the plan not open) — then the slice workstream is made and its whole seat
   occupied by a driven session briefed from the recipe. Every refusal of the plan comes before the
   slice is made; a seat that fails after it is refused :seat-failed, the slice standing for the
   conduct pass's reseat. Answers {:slice :session}."
  [project drain-id plan-seq]
  (when (wake-pending? project drain-id)
    (refuse! (str drain-id "'s coordinator has not yet been given its newest wake") :wake-pending {:ws-id drain-id}))
  ;; Read before the slice is made, so a plan that cannot be started on what it rests on is
  ;; refused while it is still open — for the coordinator to withdraw or replace.
  (let [dw    (ws/read-ws project drain-id)
        bases (rested-on-sessions project dw (first (filter #(= plan-seq (:seq %)) (drain/plans dw))))
        base  (when (seq bases) (rested-base project (str "plan " plan-seq) bases))
        slice (drain/start-slice! project drain-id plan-seq :nido)]
    ;; The plan is consumed from here: a seat that fails is the slice's, not the plan's, and
    ;; reseat seats it on a later conduct pass.
    {:slice slice
     :session (:session (try (occupy-slice-on! project slice base)
                             (catch Exception e
                               (refuse! (str "Started " slice " from plan " plan-seq ", but its seat failed: "
                                             (ex-message e) " — the conduct pass reseats it")
                                        :seat-failed {:ws-id slice}))))}))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  handle-fire!
  "Carry out one fire source-drain emitted: start the slice it names, or wake the coordinator with
   the census failure it reports. A start refused because what it decided from has moved is
   logged, not thrown: the next poll decides again. One refused because its plan's rested-on work
   cannot be merged is recorded refused (:start-refused), which source-drain then fires no more,
   and wakes the coordinator, once per plan, to withdraw or replace it."
  [{:keys [what drain plan cause detail] :as fire}]
  (let [project (keyword (:project fire))]
    (try
      (case what
        :start-slice   (start-slice! project drain plan)
        :census-failed (when (has-coordinator? project drain)
                         (seats/post! project drain
                                      {:key (str "wake:" (:id fire)) :kind :wake :to :conductor
                                       :text (str "The census could not be read (" (name cause) "): " detail
                                                  ". Propose a recipe change to its counter, or escalate.")})))
      (catch clojure.lang.ExceptionInfo e
        (binding [*out* *err*]
          (println (str "nido drain: " (name what) " on " drain " refused — " (ex-message e))))
        ;; A plan whose rested-on work cannot be merged is recorded refused, so source-drain fires
        ;; it no more, and the coordinator is told once, keyed by the fire, to replace or withdraw it.
        (when (= :base-failed (:reason (ex-data e)))
          (try (drain/refuse-start! project drain plan :base-failed (ex-message e))
               (catch Exception r
                 (binding [*out* *err*]
                   (println (str "nido drain: recording plan " plan "'s refused start failed — " (ex-message r)))))))
        (when (and (= :base-failed (:reason (ex-data e))) (has-coordinator? project drain))
          (seats/post! project drain
                       {:key (str "wake:" (:id fire)) :kind :wake :to :conductor
                        :text (str "Plan " plan " cannot be started on the work it rests on: " (ex-message e)
                                   ". Withdraw or replace it.")}))
        nil))))

;; ── landing ─────────────────────────────────────────────────────────────────

(defn- landing-in-flight? [project drain-id slice-id]
  (some #(and (= slice-id (:workstream-id %)) (#{:queued :preprocessing :running} (:state %)))
        (landing-runs project drain-id)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :any]}
  land!
  "The person asking for slice `slice-id` to land: refused while a slice it rests on has not landed
   (:rests-unlanded), until its newest review under the recipe version it runs under passed — the
   recipe's ready gates judged (:not-ready), both read by `ws/slice-unlandable` — while a landing of it is in flight (:landing-in-flight), or when it has no
   session (:no-session). A slice the coordinator has not cleared is landed as the person's
   override, recorded on its Run. The landing Run gives /drive-home to the slice's own session and
   runs beside its siblings' — never on the serial merge lane. The Run is handed to the daemon as a
   :submit-run envelope on its durable queue, so an ask from any process reaches it. Answers the
   Run."
  [project slice-id]
  (let [sw      (ws/read-ws project slice-id)
        d       (drain/drain-of sw)
        dw      (ws/read-ws project d)
        why     (ws/slice-unlandable project sw)
        sname   (slice-session project slice-id)]
    (cond
      ;; Its rests landed and its newest review under its recipe version passed — the recipe's
      ;; ready gates judged; a person's override waives the coordinator's clearance, never these.
      why
      (refuse! (:message why) (:reason why) {:ws-id slice-id})
      (landing-in-flight? project d slice-id)
      (refuse! (str "A landing of " slice-id " is in flight") :landing-in-flight {:ws-id slice-id})
      (nil? sname)
      (refuse! (str slice-id " has no session to land from") :no-session {:ws-id slice-id})
      :else
      (let [run-id (str (subs (clock/now-iso) 0 10) "-" (name project) "-drain-land-" (subs (str (UUID/randomUUID)) 0 8))
            run    {:id run-id :project project :trigger :drain-land :source {:type :drain}
                    :event-payload (cond-> {:drain d :slice slice-id :by :person}
                                     (not (drain/cleared? dw slice-id)) (assoc :override true))
                    :skill :drive-home :first-message "/drive-home" :agent :claude
                    :session-name sname :workstream-id slice-id :claude-session-id nil
                    :limits {:budget "8h" :max-failures 3} :priority 0 :session-profile :full
                    :mode :merge :uncapped? true :state :queued
                    :state-history [{:at (clock/now-iso) :state :queued}] :artifacts [] :error nil}]
        (fs/create-dirs (cstate/run-dir run-id))
        (fs/create-dirs (cstate/run-artifacts-dir run-id))
        (runs/write-run! run)
        ;; Submitted through the daemon's durable queue, not this process's executor: the person's
        ;; ask comes from a CLI process whose in-memory queue nobody ticks before it exits.
        (queue/enqueue! {:type :submit-run :run-id run-id})
        run))))

(defn ^{:malli/schema [:=> [:cat :any :map] :keyword]}
  settle-landing!
  "Settle slice landing Run `run-id` from how its /drive-home turn ended (`result`, as the merge
   lane's settle takes it) by `drain/landing-outcome`: a failed landing parks the Run for review, with
   the cause on the slice session's record. Answers the state."
  [run-id result]
  (let [run     (runs/read-run run-id)
        outcome (drain/landing-outcome run result)]
    (runs/transition! run-id outcome)
    (when (= :awaiting-review outcome)
      (try
        (session/set-error! (:project run) (:workstream-id run) (:session-name run)
                            {:at (clock/now-iso) :reason :ship-blocked
                             :message (cond
                                        (:spawn-error result) (str "the landing could not start: " (:detail result))
                                        (:timed-out? result)  (str "the landing exceeded its " (-> run :limits :budget) " budget")
                                        :else (str "the landing halted without landing the slice — fix what stopped it, "
                                                   "then `bb nido:drain:land :slice " (:workstream-id run) "` again"))})
        (catch Throwable _ nil)))
    outcome))
