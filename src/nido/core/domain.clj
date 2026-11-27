(ns nido.core.domain
  "The rules a workstream and its units of work change by, and a run by: what each may be, which
   commands a state admits, and what each yields.

   A workstream is the arc that composes units of work. A unit is one goal and its argument in one
   project. Agent work runs as turns, either for a unit or, with no unit, for the workstream's
   conductor. A turn that asks holds a pending call until a resolution is recorded for it. An
   operator ask is what the conductor puts to the person, and it is bound to one or more pending
   calls. Resolving a call does not restart the turn: it becomes :resolved, and resuming it is a
   command of its own, recorded before the resumption is attempted and admitted only once nothing
   else runs where it would. For a conductor turn that matters: the person may answer while
   another conductor turn runs, and the conductor still runs one turn at a time.

   Two pure functions carry everything:
     `decide` takes a state and a command to {:events [...]} or {:refused {:reason kw ...}}
     `evolve` folds one event into a state

   Every event records an input nido cannot recompute, such as a person's command, a turn's outcome
   or a conductor's decision. A consequence the fold can derive is never itself an event: holding a
   unit is one :unit-held event, and the fold cancels its turn and withdraws its asks. So a state is
   always `(reduce evolve empty-state events)`, and that equality is what core-store's rebuild
   checks.

   `violations` states the invariants as data, and an empty result means the state is legal. No
   command `decide` accepts yields a state with violations; the tests check this over generated
   command sequences, and core-store's constraints restate the same rules at the table level.

   A unit's session is part of its state. What the session should be is derived, never an event:
   up while the unit is open, stopped once it merged or its goal was retracted (its working copy
   kept on disk), destroyed once the workstream closed. What it is changes only by a recorded
   provisioning attempt and that attempt's recorded outcome, so a provisioner killed between the
   two leaves an attempt pending that a start finds and makes again. A session recorded ready that
   is known to be down — the host it ran on booted since — is recorded lost: it reads as never
   brought up, its worktree kept, so what it should be is owed again.

   A run is the other kind of stream: one fire of a trigger, with no ledger, conductor, inbox, chat
   or operator ask. `:start-run` opens it with exactly one unit, named by the run's id, whose first
   turn is the trigger's and whose session is provisioned with the trigger's profile. Only that
   unit's mechanics are admitted on it — provisioning, its first turn, that turn's outcome. Its
   turn ending, or its session failing to come up, finishes the unit, and the run is over: its
   session is destroyed and nothing on it runs again. A run has nobody to ask, so an :asked
   outcome reported for its turn is recorded as a failure.

   A journal is the third: records only, kept across runs — no unit, turn, ask or identity.
   `:open-journal` makes an empty stream one. A record is any map with a keyword `:kind`, which
   is all the core reads of it: what each kind holds is its writer's to check. Records are
   appended, never changed, on a journal and on a run while its unit is open — what that run did —
   each naming the record seq its author read, so two authors racing are ordered and the later
   refused.

   Pure: no clock, no ids minted, no I/O. Ids and times arrive in commands."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

;; ── the state ────────────────────────────────────────────────────────────────

(def empty-state
  "A stream before its first event: a workstream until `:start-run` makes it a run."
  {:units         {}    ; unit-id -> {:id :project :goal :status #{:open :merged :retracted :finished}
                        ;             :held? bool :unknowns #{key} :landing? bool :session session}
                        ; session: {:status #{:none :ready :failed :stopped :destroyed :refused}
                        ;           :cwd str|nil :attempt {:id :want}|nil :detail}
                        ; :refused: its destroy was refused because its working copy is not the
                        ; session's own to remove; terminal, never attempted again
   :turns         {}    ; turn-id -> {:id :unit (nil = conductor) :status :spec :run
                        ;             :call :resolution :resolved-call :ended-by :detail :stuck :exited
                        ;             :ended-under-merge?}
                        ; :stuck {:run :reason}: the runner ended that run and saw it alive past its grace
                        ; :exited run: the latest run whose exit is recorded (every recorded outcome sets it)
                        ; :ended-under-merge? marks a unit turn that ended other than completed
                        ; while the workstream held the :merge grant and its unit had not merged —
                        ; a failed landing, which no later state can recompute (`under-merge`).
                        ; :spec {:executor kw :instructions str ...} is what the executor runs;
                        ; :run counts the turn's runs, 1 at start and one more per resumption, so
                        ; an outcome names the run it is for and a stale one is refused.
   :operator-asks {}    ; ask-id  -> {:id :binds #{turn-id} :concerns unit-id|nil :status :content}
   :landing-order #{}   ; #{[before after]}: `after` lands only once `before` has merged
   :inbox         {}    ; item-id -> what the conductor has yet to hear: a unit turn's outcome or
                        ; ask, a merge, a release, an unknown. A conductor turn hears items by
                        ; naming them when it starts, which takes them out; one that fails or is
                        ; cancelled puts them back, because it did not dispose of them.
   :inbox-seq     0     ; how many items the inbox has ever held, which numbers the next one
   :conductor-failures 0 ; conductor turns failed or cancelled since one last completed or asked,
                        ; not counting a run the host refused resources to (`exhausted?`)
   :conductor-exhaustions 0 ; conductor turns the host refused resources to, since one last
                        ; completed or asked; each defers the next wake (`:conductor-retry-at`)
   :conductor-retry-at nil ; epoch ms before which the conductor is not woken again, or nil
   :conductor-stopped? false ; a stop ask is open on the conductor; nothing wakes it until answered
   :grants        {}    ; grant kind -> {:by str :occurrence n}: what the person granted and has not
                        ; yet spent; :occurrence numbers the giving, so a spend can name the one it read
   :grant-seq     0     ; how many grants have ever been given, which numbers the next one
   ;; :notices     #{}  ; every notice id posted to the workstream, kept for its life, so a notice
                        ; is posted once (`:post-notice`). Absent until the first notice, so a
                        ; state that never had one reads as it did before notices were a kind.
   :chat          []    ; [{:id :from #{:person :conductor} :text :turn :images}] in the order posted;
                        ; :images, on a person's message that carried any, are their digests: the
                        ; bytes and media type are kept by digest outside the events (`nido.core.images`)
   :closed?       false
   :identity      nil   ; what the workstream is: {:project :external-refs :created-at :intake?
                        ; :facets? :title?}; nil until it is created or imported
   :ledger        []})  ; the work records' index rows, in seq order: {:seq :kind :at :ext ...}.
                        ; An entry's content is in its :entry-appended event, never in the state,
                        ; so every feed message about a workstream stays the size of its index.
                        ; A run's state also carries :run, its identity {:id :project :trigger :goal
                        ; :profile :payload :fired-at}: absent on a workstream, so a workstream's
                        ; state reads as it did before runs were a kind.
                        ; A journal's carries :journal {:project :name}, and it and a run's carry
                        ; :records, in append order, each its record with the :seq it was given.

(def person-input-kinds
  "The inbox items that carry a person's input. A wake for an inbox holding one is never capped."
  #{:chat :ask-answered :blocker-answered :woken})

(def max-message-images
  "How many images one chat message may carry."
  4)

(def active-statuses
  "A turn in one of these statuses is active, so it counts against its unit. Only :running counts
   against the conductor's one turn at a time."
  #{:running :asked :resolved})

(defn- conductor-turn? [turn] (nil? (:unit turn)))

(defn ^{:malli/schema [:=> [:cat :map :map] [:maybe :keyword]]} session-want
  "What unit `unit`'s session should be in `state`: :up, :stopped or :destroyed."
  [state unit]
  (cond
    (or (:closed? state) (:discarded? state)) :destroyed
    (= :open (:status unit))    :up
    ;; A run is over once its unit is: nothing will run in its session again.
    (:run state)                :destroyed
    :else                       :stopped))

(def ^:private reached
  "The session status each want is reached at."
  {:up :ready :stopped :stopped :destroyed :destroyed})

(declare active-turns)

(defn ^{:malli/schema [:=> [:cat :map] [:sequential :map]]} sessions-owed
  "Every unit of `state` whose session is not what it should be and has no attempt pending, with
   the want: [{:unit :want}]. A failed bring-up is not owed again until it is retried: the
   conductor hears the failure and decides. A refused destroy is owed nothing ever again: the
   refusal is deterministic, and retrying it would only refuse again."
  [state]
  (for [u (sort-by :id (vals (:units state)))
        :let [want (session-want state u)
              {:keys [status attempt]} (:session u)]
        :when (and (nil? attempt)
                   (not= :refused status)
                   (not= (reached want) status)
                   ;; a session is never taken down under a turn still active in it
                   (or (= :up want) (empty? (active-turns state (:id u))))
                   (not (and (= :up want) (= :failed status)))
                   ;; nothing to stop or destroy where nothing was ever brought up
                   (not (and (not= :up want) (= :none status)))
                   (not (and (= :stopped want) (= :destroyed status))))]
    {:unit (:id u) :want want}))

(defn ^{:malli/schema [:=> [:cat :map] [:sequential :map]]} attempts-pending
  "Every provisioning attempt recorded and not yet resolved: [{:unit :attempt :want :project}], with
   :cwd once the session has one and :profile when the unit names the session profile to make it
   with."
  [state]
  (for [u (sort-by :id (vals (:units state)))
        :let [a (get-in u [:session :attempt])]
        :when a]
    (cond-> {:unit (:id u) :attempt (:id a) :want (:want a) :project (:project u)
             :cwd (get-in u [:session :cwd])}
      (:profile u) (assoc :profile (:profile u)))))

(defn- active-turns
  "The active turns of `unit-id`, or of the conductor when `unit-id` is nil."
  [state unit-id]
  (filter #(and (= unit-id (:unit %)) (active-statuses (:status %))) (vals (:turns state))))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :keyword]]} code-held
  "Why a unit's turn may not change code on this workstream, or nil when it may: nido's recipe
   runs on it — the newer of its :activated and :deactivated entries is an :activated — and no
   design stands cleared, :design-not-cleared. A design stands cleared when a :design-cleared or
   :design-approved is newer than the newest :design and the newest :retraction. Read off the
   ledger's index, so the arc is judged in the one place every turn starts through."
  [state]
  (let [l      (:ledger state)
        newest (fn [kinds] (or (:seq (last (filter #(kinds (:kind %)) l))) 0))
        act    (last (filter #(#{:activated :deactivated} (:kind %)) l))]
    (when (= :activated (:kind act))
      (let [d (newest #{:design})
            c (newest #{:design-cleared :design-approved})]
        (when-not (and (pos? d) (> c d) (> c (newest #{:retraction})))
          :design-not-cleared)))))

(defn ^{:malli/schema [:=> [:cat :map] [:sequential :map]]} first-turns-owed
  "The first turns owed: [{:unit :turn :spec}] for each open unit opened with a first turn — a
   trigger's instructions — that no turn of it has started yet and whose session is ready."
  [state]
  (for [u (sort-by :id (vals (:units state)))
        :when (and (= :open (:status u)) (:first-turn u)
                   (= :ready (get-in u [:session :status]))
                   (not-any? #(= (:id u) (:unit %)) (vals (:turns state))))]
    {:unit (:id u) :turn (str (:id u) " first") :spec (get-in u [:first-turn :spec])}))

(defn ^{:malli/schema [:=> [:cat :map] [:sequential :map]]} awaiting-person
  "The asks awaiting the person: exactly the open operator asks. A unit's call is never among
   them except through an open operator ask that binds it, so this is all a surface may show as
   needing the person."
  [state]
  (filter #(= :open (:status %)) (vals (:operator-asks state))))

(def machine-ask-kinds
  "The operator-ask kinds the machine raises about itself — a failure no decision was waiting on.
   The person answering one is what recovers it, but nothing in it asks them to decide anything:
   a surface shows it as an error, apart from what a person is asked."
  #{:conductor-stopped})

(defn ^{:malli/schema [:=> [:cat :map] :boolean]} machine-ask?
  "Whether operator ask `a` is a machine-side failure (`machine-ask-kinds`), not a question."
  [a]
  (contains? machine-ask-kinds (:kind a)))

(defn ^{:malli/schema [:=> [:cat :map] :boolean]} run-awaited?
  "Whether the core still awaits the end of turn `t`'s current run: the turn runs or was cancelled
   — a store that cancels a running turn leaves its run's process to end — and no exit of that run
   is recorded (`:exited`, which every recorded outcome of a run sets). An asked, resolved or
   finished turn recorded its run's exit. These are the runs a stuck report concerns, and the ones
   a starting daemon takes back."
  [t]
  (boolean (and (#{:running :cancelled} (:status t)) (not= (:run t) (:exited t)))))

(defn ^{:malli/schema [:=> [:cat :map] :boolean]} stuck?
  "Whether turn `t`'s current run is stuck: the runner ended it and reported it alive past its
   kill grace (`:stuck`), and its exit is not recorded (`run-awaited?`). Derived from those two
   facts alone, so it reads the same whatever the turn's status or the workstream's closure."
  [t]
  (boolean (and (run-awaited? t) (= (:run t) (get-in t [:stuck :run])))))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :map]]} stuck-run
  "The workstream's stuck run (`stuck?`) as {:turn :run :reason}, with the :unit when it has one,
   or nil. The one reading every surface's stuck mark comes from, through `activity`."
  [state]
  (when-let [t (first (filter stuck? (sort-by :id (vals (:turns state)))))]
    (cond-> {:turn (:id t) :run (:run t) :reason (get-in t [:stuck :reason])}
      (:unit t) (assoc :unit (:unit t)))))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :map]]} activity
  "What the core is doing on a workstream whose state is `state`, as a surface reads it, or nil
   when it does nothing and is owed nothing: {:kind k}, with the :turn and :unit it concerns when
   there is one. Each kind outranks the ones after it:
     :asking    an operator ask awaits the person (`awaiting-person`): the next move is theirs.
                :failed? true when one of them is a machine failure (`machine-ask?`), whose move
                is a recovery, not an answer; :failure-only? as well when every one is, so
                nothing is asked of them
     :stuck     a turn's run was ended — it gave its final answer, passed its deadline or was
                cancelled — and its process lives on regardless (`stuck?`): nothing it does is
                read until it exits, so it is neither working nor deciding
     :working   a unit's turn runs, or resumes with its call answered
     :deciding  the conductor runs or resumes, a unit's turn waits on the conductor's answer to
                its call, or the conductor is owed a wake: its inbox holds items
     :starting  an open unit's session is being brought up, or its first turn is owed
   Between a turn ending and the next starting, the core is owed a move it has not made yet;
   these kinds name that move, so a surface never reads a handover as nothing happening. A
   failed bring-up is not :starting: the conductor hears the failure, which is :deciding while
   it is owed. A closed workstream does nothing, except that a stuck run on it is still :stuck:
   closing ends no process, and only its recorded exit clears the reading. An ask outranks a stuck
   run, which it then carries as :stuck {:turn :unit}: a run cancelled because an ask about its
   unit opened is stuck still, and reads so beside the ask. :stuck and :failed? are set
   independently — a reader of :asking must accept either, both or neither."
  [state]
  (let [ts    (sort-by :id (vals (:turns state)))
        turn  (fn [pred] (first (filter pred ts)))
        of    (fn [k t] (cond-> {:kind k :turn (:id t)} (:unit t) (assoc :unit (:unit t))))
        units (filter #(= :open (:status %)) (sort-by :id (vals (:units state))))
        stuck (some-> (stuck-run state) (select-keys [:turn :unit]) (assoc :kind :stuck))]
    (if (or (:closed? state) (:discarded? state))
      stuck
      (cond
        (seq (awaiting-person state))
        (let [asks (awaiting-person state)]
          (cond-> {:kind :asking}
            (some machine-ask? asks)   (assoc :failed? true)
            (every? machine-ask? asks) (assoc :failure-only? true)
            stuck                      (assoc :stuck (dissoc stuck :kind))))

        stuck
        stuck

        (turn #(and (:unit %) (#{:running :resolved} (:status %))))
        (of :working (turn #(and (:unit %) (#{:running :resolved} (:status %)))))

        (turn #(and (conductor-turn? %) (#{:running :resolved} (:status %))))
        (of :deciding (turn #(and (conductor-turn? %) (#{:running :resolved} (:status %)))))

        (turn #(and (:unit %) (= :asked (:status %))))
        (of :deciding (turn #(and (:unit %) (= :asked (:status %)))))

        (seq (:inbox state))
        {:kind :deciding}

        (some #(not (#{:ready :failed} (get-in % [:session :status]))) units)
        {:kind :starting :unit (:id (first (filter #(not (#{:ready :failed} (get-in % [:session :status]))) units)))}

        (seq (first-turns-owed state))
        {:kind :starting :unit (:unit (first (first-turns-owed state)))}))))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :keyword]]} run-status
  "Where run `state` stands: :starting until its turn starts, :running while it runs, then :done,
   :failed or :cancelled as its turn ended — :failed too when its session never came up. Nil on a
   workstream."
  [state]
  (when-let [{:keys [id]} (:run state)]
    (let [u (get-in state [:units id])
          t (first (vals (:turns state)))]
      (cond
        (= :open (:status u)) (if t :running :starting)
        t                     (case (:status t) :completed :done :cancelled :cancelled :failed)
        :else                 :failed))))

(defn- binding-ask
  "The open operator ask that binds `turn-id`'s pending call, or nil."
  [state turn-id]
  (first (filter #(contains? (:binds %) turn-id) (awaiting-person state))))

(defn- concerned-units
  "The units operator ask `ask` holds still: the unit it names, or every unit of the workstream
   when it names none, and the units whose calls it binds."
  [state ask]
  (if (= :conductor-stopped (:kind ask))
    #{}
    (into (if-let [u (:concerns ask)] #{u} (set (keys (:units state))))
          (keep #(get-in state [:turns % :unit]) (:binds ask)))))

(defn- concerns-open-ask?
  "Whether an open operator ask holds unit `unit-id` still."
  [state unit-id]
  (some #(contains? (concerned-units state %) unit-id) (awaiting-person state)))

;; ── the fold ─────────────────────────────────────────────────────────────────

(defn- resolve-call
  "The turn `turn-id` with its pending call resolved by `resolution`, waiting to be resumed. Only an
   asked turn changes, so a turn already cancelled by a takeover stays cancelled."
  [state turn-id resolution]
  (if (= :asked (get-in state [:turns turn-id :status]))
    (update-in state [:turns turn-id] #(-> % (assoc :status :resolved :resolution resolution
                                                    :resolved-call (get-in % [:call :id]))
                                           (dissoc :call)))
    state))

(defn- hear-later
  "`item` put in the conductor's inbox, under an id no other item of the workstream has had."
  [state item]
  (let [n (inc (:inbox-seq state 0))
        id (str "#" n " " (name (:kind item)))]
    (-> state
        (assoc :inbox-seq n)
        (assoc-in [:inbox id] (assoc item :id id)))))

(defn- turn-ended-item [{:keys [id unit run]} outcome actions-unknown?]
  (cond-> {:kind :turn-ended :unit unit :turn id :run run :outcome outcome}
    actions-unknown? (assoc :actions-unknown? true)))

(defn- under-merge
  "`state` with unit turn `turn-id`, which is ending as `outcome`, marked :ended-under-merge? when it
   ends other than completed while the workstream holds the :merge grant and its unit has not
   merged: a failed landing. Marked by the fold that ends it, because the grant records no time and
   leaves :grants once spent or the unit merges, so no later state can tell."
  [state turn-id outcome]
  (let [{:keys [unit]} (get-in state [:turns turn-id])]
    (cond-> state
      (and unit
           (not= :completed outcome)
           (contains? (:grants state) :merge)
           (not= :merged (get-in state [:units unit :status])))
      (assoc-in [:turns turn-id :ended-under-merge?] true))))

(defn- close-ask
  "Close operator ask `ask-id` as `status`, and resolve every call it binds with `resolution`."
  [state ask-id status resolution]
  (let [ask (get-in state [:operator-asks ask-id])]
    (reduce #(resolve-call %1 %2 resolution)
            (assoc-in state [:operator-asks ask-id :status] status)
            (:binds ask))))

(defmulti ^{:malli/schema [:=> [:cat :map :map] :map]} evolve
  "Fold one event into a state. Total over the event kinds `decide` yields; an event decide
   would have refused is never in a ledger, so evolve does not re-check it."
  (fn [_state event] (:event event)))

(defmethod evolve :unit-opened [state {:keys [unit project goal session-cwd trigger first-turn]}]
  ;; :session-cwd is no longer admitted; a unit folded from an event that adopted one keeps it.
  (assoc-in state [:units unit] (cond-> {:id unit :project project :goal goal :status :open
                                         :held? false :unknowns #{} :landing? false
                                         :session (if session-cwd
                                                    {:status :ready :cwd session-cwd}
                                                    {:status :none})}
                                  trigger     (assoc :trigger trigger)
                                  first-turn  (assoc :first-turn first-turn))))

(defmethod evolve :journal-opened [state {:keys [journal]}]
  (assoc state :journal journal))

(defmethod evolve :record-appended [state {:keys [record]}]
  (update state :records (fnil conj []) (assoc record :seq (inc (count (:records state))))))

(defmethod evolve :run-started [state {:keys [run spec]}]
  (-> state
      (assoc :run run)
      (evolve {:event :unit-opened :unit (:id run) :project (:project run) :goal (:goal run)
               :trigger (:trigger run) :first-turn {:spec spec}})
      (cond-> (:profile run) (assoc-in [:units (:id run) :profile] (:profile run)))))

(defmethod evolve :unit-finished [state {:keys [unit]}]
  ;; Its turn's outcome is heard by no conductor: a finished run owes nobody a decision, and an
  ;; item left in the inbox would wake one the moment the workstream reopened.
  (-> state
      (assoc-in [:units unit :status] :finished)
      (update :inbox #(into {} (remove (fn [[_ item]] (= unit (:unit item)))) %))))

(defmethod evolve :provision-attempted [state {:keys [unit attempt want]}]
  (assoc-in state [:units unit :session :attempt] {:id attempt :want want}))

(defmethod evolve :provision-recorded [state {:keys [unit outcome cwd detail unit-ready?]}]
  (let [want (get-in state [:units unit :session :attempt :want])
        status (case outcome :failed :failed :refused :refused (reached want))]
    (cond-> (update-in state [:units unit :session]
                       #(cond-> (-> % (dissoc :attempt :detail) (assoc :status status))
                          ;; Only an :up that came up sets it; a ledger whose other outcomes
                          ;; carried one folds without it.
                          (and cwd (= :done outcome) (= :up want)) (assoc :cwd cwd)
                          detail (assoc :detail detail)))
      ;; A run has no conductor to hear a failure; one whose session never came up is over.
      (and (:run state) (= :failed outcome) (= :up want)) (assoc-in [:units unit :status] :finished)
      (and (not (:run state)) (= :failed outcome)) (hear-later {:kind :session-failed :unit unit :want want :detail detail})
      (and (not (:run state)) unit-ready?)         (hear-later {:kind :unit-ready :unit unit}))))

(defmethod evolve :session-lost [state {:keys [unit]}]
  ;; Its worktree is kept: bringing the session up again is bringing up the same one.
  (update-in state [:units unit :session] #(-> % (assoc :status :none) (dissoc :detail))))

(defmethod evolve :provision-retried [state {:keys [unit]}]
  (update-in state [:units unit :session] #(-> % (assoc :status :none) (dissoc :detail))))

(defmethod evolve :unit-goal-retracted [state {:keys [unit]}]
  (assoc-in state [:units unit :status] :retracted))

(defn ^{:malli/schema [:=> [:cat :map] :boolean]} exhausted?
  "Whether a turn's ending `detail` says the host refused its run resources — a process or thread
   it could not start. Such a run says nothing about the turn's work, so it is retried later rather
   than counted as the conductor failing."
  [detail]
  (= :resource-exhausted (:reason detail)))

(defn ^{:malli/schema [:=> [:cat :int] :int]} exhaustion-backoff-ms
  "How long the conductor waits before its next wake after its `n`th consecutive exhausted turn:
   30s doubling, at most 15 minutes. The host's limit is shared, so waking at once only adds to
   what exhausted it."
  [n]
  (min (* 15 60 1000) (* 30 1000 (bit-shift-left 1 (min 10 (max 0 (dec n)))))))

(defn- conductor-settled [state]
  (assoc state :conductor-failures 0 :conductor-exhaustions 0 :conductor-retry-at nil))

(defn- conductor-exhausted [state detail]
  (let [n (inc (:conductor-exhaustions state 0))]
    (assoc state :conductor-exhaustions n
           :conductor-retry-at (some-> (:at detail) (+ (exhaustion-backoff-ms n))))))

(defmethod evolve :turn-started [state {:keys [turn unit spec hears]}]
  (let [heard (select-keys (:inbox state) hears)]
    (-> state
        (assoc-in [:turns turn] (cond-> {:id turn :unit unit :status :running :spec spec :run 1}
                                  (nil? unit) (assoc :hears heard)))
        (update :inbox #(apply dissoc % hears)))))

(defmethod evolve :turn-asked [state {:keys [turn call]}]
  (let [{:keys [unit run]} (get-in state [:turns turn])]
    (cond-> (update-in state [:turns turn] assoc :status :asked :call call :exited run)
      unit       (hear-later {:kind :turn-asked :unit unit :turn turn :run run})
      (nil? unit) conductor-settled)))

(defmethod evolve :turn-resumed [state {:keys [turn]}]
  (update-in state [:turns turn] #(-> % (assoc :status :running) (update :run inc))))

(defn ^{:malli/schema [:=> [:cat :string :int] :string]} run-unknown-key
  "The unknown a turn's run leaves on its unit when what the run did cannot be known."
  [turn run]
  (str "turn " turn " run " run))

(defmethod evolve :turn-ended [state {:keys [turn outcome detail actions-unknown?]}]
  (let [{:keys [unit run hears] :as t} (get-in state [:turns turn])
        disposed? (= :completed outcome)]
    (cond-> (-> state
                (update-in [:turns turn] #(cond-> (assoc % :status outcome :exited run)
                                            detail (assoc :detail detail)))
                (under-merge turn outcome))
      (and actions-unknown? unit)
      (update-in [:units unit :unknowns] conj (run-unknown-key turn run))

      (and unit (:run state)) (assoc-in [:units unit :status] :finished)

      (and unit (not (:run state))) (hear-later (turn-ended-item t outcome actions-unknown?))

      (and (nil? unit) disposed?)       conductor-settled
      (and (nil? unit) (not disposed?)) (update :inbox merge hears)
      (and (nil? unit) (not disposed?) (exhausted? detail))       (conductor-exhausted detail)
      (and (nil? unit) (not disposed?) (not (exhausted? detail))) (update :conductor-failures inc))))

(defmethod evolve :turn-stuck [state {:keys [turn run reason]}]
  (assoc-in state [:turns turn :stuck] {:run run :reason reason}))

(defmethod evolve :turn-exited [state {:keys [turn run]}]
  ;; A store-cancelled turn's run ended: its exit is recorded, and it stays cancelled.
  (assoc-in state [:turns turn :exited] run))

(defmethod evolve :call-answered [state {:keys [turn answer]}]
  (resolve-call state turn {:answered answer}))

(defmethod evolve :call-returned [state {:keys [turn instructions]}]
  (resolve-call state turn {:returned instructions}))

(defmethod evolve :operator-ask-raised [state {:keys [ask binds concerns content]}]
  ;; An ask is a halt for what it concerns: in the same commit, every running turn of a unit it
  ;; holds still is cancelled, and the conductor hears so. Asked and resolved turns already halt.
  (let [state (assoc-in state [:operator-asks ask] {:id ask :binds (set binds) :concerns concerns
                                                    :status :open :content content})
        held  (concerned-units state (get-in state [:operator-asks ask]))
        running (filter #(and (contains? held (:unit %)) (= :running (:status %))) (vals (:turns state)))]
    (reduce (fn [s t] (-> s
                          (update-in [:turns (:id t)] assoc :status :cancelled :ended-by :concerned-ask)
                          (under-merge (:id t) :cancelled)
                          (hear-later (turn-ended-item t :cancelled false))))
            state running)))

(defn- person-closed-ask
  "After the person closed operator ask `ask-id`: a stop ask lifts the conductor's stop; any other
   is heard by the conductor as the person's input — with `answer`, the value they answered it
   with, when they answered it. The value is carried for the conductor to read; nothing here reads
   its content."
  [state ask-id how answer]
  (if (= :conductor-stopped (get-in state [:operator-asks ask-id :kind]))
    (conductor-settled (assoc state :conductor-stopped? false))
    (hear-later state (cond-> {:kind :ask-answered :ask ask-id :how how}
                        (some? answer) (assoc :answer answer)))))

(defmethod evolve :operator-ask-answered [state {:keys [ask answer by]}]
  (cond-> (close-ask state ask :answered {:answered answer})
    (= :person by) (person-closed-ask ask :answered answer)))

(defmethod evolve :operator-ask-withdrawn [state {:keys [ask reason by]}]
  (cond-> (close-ask state ask :withdrawn {:refused reason})
    (= :person by) (person-closed-ask ask :withdrawn nil)))

(defmethod evolve :conductor-stop-raised [state {:keys [ask content]}]
  (-> state
      (assoc :conductor-stopped? true)
      (assoc-in [:operator-asks ask] {:id ask :kind :conductor-stopped :binds #{} :concerns nil
                                      :status :open :content content})))

(defmethod evolve :grant-given [state {:keys [grant by]}]
  ;; A landing granted is the conductor's to direct: nothing else tells a unit to land. Each giving
  ;; is numbered, so a send-back can spend the one it read held and no grant given since.
  (cond-> (let [n (inc (:grant-seq state 0))]
            (-> state
                (assoc :grant-seq n)
                (assoc-in [:grants grant] {:by by :occurrence n})))
    (= :merge grant) (hear-later {:kind :landing-granted :by by})))

(defmethod evolve :grant-spent [state {:keys [grant]}]
  (update state :grants dissoc grant))

(defmethod evolve :chat-posted [state {:keys [message text images]}]
  ;; :images only when the message carried any, so a message recorded before images folds as it did.
  (-> state
      (update :chat (fnil conj []) (cond-> {:id message :from :person :text text}
                                     (seq images) (assoc :images (vec images))))
      (hear-later (cond-> {:kind :chat :message message :text text}
                    (seq images) (assoc :images (vec images))))))

(defmethod evolve :chat-replied [state {:keys [message text turn]}]
  (update state :chat (fnil conj []) {:id message :from :conductor :text text :turn turn}))

(defmethod evolve :notice-posted [state {:keys [notice text]}]
  ;; A notice is nido's, never the person's: kept in :notices for the workstream's life and heard
  ;; as a :notice item, and never put in :chat.
  (-> state
      (update :notices (fnil conj #{}) notice)
      (hear-later {:kind :notice :notice notice :text text})))

(defmethod evolve :handed-over [state {:keys [summary]}]
  (hear-later state {:kind :hand-over :summary summary}))

(defmethod evolve :arrival-recorded [state {:keys [arrival summary]}]
  (hear-later state {:kind :arrival :arrival arrival :summary summary}))

(defmethod evolve :unit-held [state {:keys [unit]}]
  ;; The hold's one commit: the unit is held, its active turn is cancelled by takeover, a pending
  ;; call is closed as taken over, and every open operator ask binding one of its calls is
  ;; withdrawn, which resolves that ask's sibling calls with a refusal.
  (let [unit-turns (set (map :id (active-turns state unit)))
        withdrawn  (filter #(seq (set/intersection unit-turns (:binds %))) (awaiting-person state))
        state      (reduce #(close-ask %1 (:id %2) :withdrawn {:refused :taken-over}) state withdrawn)]
    (reduce (fn [s t] (-> s
                          (update-in [:turns t] #(-> % (assoc :status :cancelled :ended-by :takeover)
                                                     (dissoc :call)))
                          (under-merge t :cancelled)
                          (hear-later (turn-ended-item (get-in s [:turns t]) :cancelled false))))
            (assoc-in state [:units unit :held?] true)
            unit-turns)))

(defmethod evolve :unit-released [state {:keys [unit]}]
  (-> state
      (assoc-in [:units unit :held?] false)
      (hear-later {:kind :unit-released :unit unit})))

(defmethod evolve :unknown-recorded [state {:keys [unit key]}]
  (-> state
      (update-in [:units unit :unknowns] conj key)
      (hear-later {:kind :unknown :unit unit :key key})))

(defmethod evolve :unknown-settled [state {:keys [unit key]}]
  (update-in state [:units unit :unknowns] disj key))

(defmethod evolve :landing-order-set [state {:keys [pairs]}]
  (assoc state :landing-order (set pairs)))

(defmethod evolve :landing-begun [state {:keys [unit]}]
  (assoc-in state [:units unit :landing?] true))

(defmethod evolve :unit-merged [state {:keys [unit]}]
  (-> state
      (update-in [:units unit] assoc :status :merged :landing? false)
      (update :grants dissoc :merge)
      (hear-later {:kind :unit-merged :unit unit})))

(defmethod evolve :workstream-created [state {:keys [identity]}]
  (assoc state :identity identity))

(defmethod evolve :identity-revised [state {:keys [changes]}]
  (update state :identity
          #(reduce-kv (fn [i k v] (if (nil? v) (dissoc i k) (assoc i k v))) % changes)))

(defmethod evolve :workstream-discarded [state _]
  (assoc state :discarded? true))

(defmethod evolve :entry-appended [state {:keys [row]}]
  (update state :ledger (fnil conj []) row))

;; A blocker answer's wake is its own event, so an :entry-appended recorded before it existed
;; folds as it always did: only new decisions yield one.
(defmethod evolve :blocker-answer-heard [state {:keys [seq]}]
  (hear-later state {:kind :blocker-answered :seq seq}))

(defmethod evolve :conductor-woken [state {:keys [by]}]
  (hear-later state {:kind :woken :by by}))

(defmethod evolve :workstream-closed [state _]
  (assoc state :closed? true))

(defmethod evolve :workstream-reopened [state _]
  (assoc state :closed? false))

(defn ^{:malli/schema [:=> [:cat [:sequential :map]] :map]} fold
  "The state `events` fold to, from the empty workstream."
  [events]
  (reduce evolve empty-state events))

;; ── deciding ─────────────────────────────────────────────────────────────────

(defn- refuse [reason & {:as data}] {:refused (merge {:reason reason} data)})
(defn- accept [& events] {:events (vec events)})

(defn- unit-blocker
  "Why no turn may start on `unit-id` now, or nil when one may. The first that applies wins, in
   the order a person reading the refusal would want them."
  [state unit-id]
  (let [unit (get-in state [:units unit-id])]
    (cond
      (nil? unit)                          :no-such-unit
      (not= :open (:status unit))          :unit-not-open
      (:held? unit)                        :unit-held
      (seq (:unknowns unit))               :unit-has-unknowns
      (seq (active-turns state unit-id))   :unit-has-active-turn
      (concerns-open-ask? state unit-id)   :unit-has-open-operator-ask)))

(def ^{:malli/schema :int} question-max
  "The longest a core ask's question may be written: the cap resources/asking/ask.md states, which
   a test holds this and the ledger's own check to."
  240)

(defn- question-problem
  "What is wrong with `q` as one question for the person, as the end of an instruction, or nil."
  [q]
  (let [t (str/trim (str q))]
    (cond
      (str/blank? t)               "is missing — write the question"
      (str/includes? t "\n")       "is more than one line — the question alone, on one line"
      (> (count t) question-max)   (str "is " (count t) " characters, over " question-max
                                        " — the question alone; move its situation and reasoning to the context")
      (not (str/ends-with? t "?")) "does not end in '?' — write it as the question it asks")))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] [:sequential :string]]} ask-problems
  "The question-form problems of an ask's `content` — its headline `question` and each entry of its
   `questions` — each an instruction naming its field: one line of at most `question-max`
   characters ending in '?'. Empty when every question is well formed."
  [content]
  (vec (concat
        (when-let [p (question-problem (:question content))]
          [(str "`question` " p)])
        (keep-indexed (fn [i e]
                        (when-let [p (question-problem (when (map? e) (:question e)))]
                          (str "`questions` entry " (inc i) " (its `question`) " p)))
                      (:questions content)))))

(defn- decision-problems
  "Why `content` does not put a decision to the person, each an instruction naming its field:
   the question-form problems (`ask-problems`), the context its answer depends on missing, or
   neither the options of one decision nor several questions. Empty when it does."
  [content]
  (if-not (map? content)
    ["the ask has no content"]
    (cond-> (ask-problems content)
      (str/blank? (str (:explored content)))
      (conj "`explored` is missing — the context the answer depends on")
      (and (empty? (:options content)) (empty? (:questions content)))
      (conj "neither `options` nor `questions` — give the branches of the decision, or one entry per decision"))))

(defmulti ^{:malli/schema [:=> [:cat :map :map] [:or [:map [:events [:vector :map]]] [:map [:refused :map]]]]} decide
  "Decide `command` against the whole workstream `state`: {:events [...]} when it is admitted,
   {:refused {:reason kw ...}} when it is not. A refused command changes nothing."
  (fn [_state command] (:command command)))

(defmethod decide :default [_ command]
  (refuse :unknown-command :command (:command command)))

(defmethod decide :open-unit [state {:keys [unit project goal session-cwd trigger first-turn]}]
  (cond
    ;; A unit's session is the core's to provision: its cwd is set only by an :up outcome. One
    ;; opened over a working copy the core did not make is one its destroy could not tell apart.
    (some? session-cwd)                    (refuse :session-cwd-not-accepted)
    (:closed? state)                       (refuse :workstream-closed)
    (contains? (:units state) unit)        (refuse :unit-exists :unit unit)
    (or (nil? project) (nil? goal))        (refuse :unit-needs-project-and-goal)
    (and first-turn (not (and (keyword? (get-in first-turn [:spec :executor]))
                              (string? (get-in first-turn [:spec :instructions])))))
    (refuse :turn-needs-executor-and-instructions)
    :else (accept (cond-> {:event :unit-opened :unit unit :project project :goal goal}
                    trigger     (assoc :trigger trigger)
                    first-turn  (assoc :first-turn first-turn)))))

(defmethod decide :start-run [state {:keys [run spec]}]
  (let [{:keys [id project goal]} run]
    (cond
      (not= empty-state state)                (refuse :stream-not-empty)
      (not (and (string? id) (string? project) (string? goal)))
      (refuse :run-needs-id-project-and-goal)
      (not (and (keyword? (:executor spec)) (string? (:instructions spec))))
      (refuse :turn-needs-executor-and-instructions)
      :else (accept {:event :run-started
                     :run (select-keys run [:id :project :trigger :goal :profile :payload :fired-at])
                     :spec spec}))))

(defmethod decide :open-journal [state {:keys [journal]}]
  (cond
    (not= empty-state state)                                    (refuse :stream-not-empty)
    (not (and (string? (:project journal)) (string? (:name journal)))) (refuse :journal-needs-project-and-name)
    :else (accept {:event :journal-opened :journal journal})))

(defmethod decide :append-record [state {:keys [record after]}]
  (let [n (count (:records state))]
    (cond
      (not (or (:journal state) (:run state)))      (refuse :records-are-a-journals-or-a-runs)
      (and (:run state) (not= :open (get-in state [:units (:id (:run state)) :status])))
      (refuse :run-is-over)
      (not (and (map? record) (keyword? (:kind record)))) (refuse :record-needs-a-kind)
      (contains? record :seq)                      (refuse :record-names-its-own-seq)
      (not= after n)                               (refuse :stale :seq n)
      :else (accept {:event :record-appended :record record}))))

(defmethod decide :record-provision-attempt [state {:keys [unit attempt want]}]
  (cond
    (nil? (get-in state [:units unit]))  (refuse :no-such-unit :unit unit)
    (not (some #(and (= unit (:unit %)) (= want (:want %))) (sessions-owed state)))
    (refuse :session-not-owed :unit unit :want want)
    :else (accept {:event :provision-attempted :unit unit :attempt attempt :want want})))

(defmethod decide :record-provision-outcome [state {:keys [unit attempt outcome cwd detail]}]
  (let [a (get-in state [:units unit :session :attempt])]
    (cond
      (not= attempt (:id a))                    (refuse :no-such-attempt :unit unit :attempt attempt)
      (not (#{:done :failed :refused} outcome)) (refuse :unknown-outcome :outcome outcome)
      ;; Only a destroy is refused as not the session's own to remove: any other attempt that
      ;; meets the refusal reports it :failed.
      (and (= :refused outcome) (not= :destroyed (:want a)))
      (refuse :refused-is-a-destroys :unit unit :want (:want a))
      (and (= :done outcome) (= :up (:want a)) (not (string? cwd)))
      (refuse :ready-needs-a-cwd)
      ;; A session's cwd is set only by an :up that came up: one carried by any other outcome
      ;; would adopt a path the core never provisioned, or overwrite the path a refusal kept.
      (and (some? cwd) (not (and (= :done outcome) (= :up (:want a)))))
      (refuse :cwd-only-from-an-up :unit unit :want (:want a) :outcome outcome)
      :else (accept (cond-> {:event :provision-recorded :unit unit :attempt attempt :outcome outcome}
                      cwd    (assoc :cwd cwd)
                      detail (assoc :detail detail)
                      ;; A unit that comes up with no first turn and none run yet has nothing to
                      ;; start it but the conductor, which wakes only on its inbox. Decided here and
                      ;; carried on the event, so a ledger recorded before the rule folds as it did.
                      (and (= :done outcome) (= :up (:want a))
                           (not (get-in state [:units unit :first-turn]))
                           (not-any? #(= unit (:unit %)) (vals (:turns state))))
                      (assoc :unit-ready? true))))))

(defmethod decide :record-session-lost [state {:keys [unit]}]
  (cond
    (not= :ready (get-in state [:units unit :session :status])) (refuse :session-not-ready :unit unit)
    ;; A turn running is running in it: its end is reported first, and the session is lost after.
    (some #(= :running (:status %)) (active-turns state unit))  (refuse :turn-running :unit unit)
    :else (accept {:event :session-lost :unit unit})))

(defmethod decide :retry-provision [state {:keys [unit]}]
  (if (= :failed (get-in state [:units unit :session :status]))
    (accept {:event :provision-retried :unit unit})
    (refuse :session-not-failed :unit unit)))

(defmethod decide :retract-unit-goal [state {:keys [unit]}]
  (cond
    (not= :open (get-in state [:units unit :status])) (refuse :unit-not-open :unit unit)
    (seq (active-turns state unit))                   (refuse :unit-has-active-turn :unit unit)
    :else (accept {:event :unit-goal-retracted :unit unit})))

(def ^:private read-only-preamble
  (str "nido's recipe runs on this workstream and its design is not cleared, so this turn may not "
       "change code: its file-editing tools are refused in its working copy, and it must not change files there any other way. Explore, "
       "and write what the recipe owes — the baseline, the design (/design) — through nido's verbs; "
       "a product question is a design question, to ask now rather than after building."))

(defmethod decide :start-turn [state {:keys [turn unit spec hears]}]
  (cond
    (:closed? state)                       (refuse :workstream-closed)
    (contains? (:turns state) turn)        (refuse :turn-exists :turn turn)
    (not (and (keyword? (:executor spec)) (string? (:instructions spec))))
    (refuse :turn-needs-executor-and-instructions)
    (nil? unit)
    (cond
      (some #(= :running (:status %)) (active-turns state nil)) (refuse :conductor-turn-running)
      (not-every? (:inbox state) hears) (refuse :not-in-inbox :items (vec (remove (:inbox state) hears)))
      :else (accept {:event :turn-started :turn turn :unit nil :spec spec :hears (vec (distinct hears))}))
    (seq hears)                            (refuse :only-the-conductor-hears)
    :else
    (if-let [why (or (unit-blocker state unit)
                     (when-not (= :ready (get-in state [:units unit :session :status])) :session-not-ready))]
      (refuse why :unit unit)
      ;; A unit turn works in its unit's session, whatever the command said — and changes no code
      ;; while the recipe holds it (`code-held`), whatever the command said either.
      (accept {:event :turn-started :turn turn :unit unit
               :spec (cond-> (assoc spec :cwd (get-in state [:units unit :session :cwd]))
                       (code-held state)
                       (assoc :read-only? true
                              :instructions (str read-only-preamble "\n\n" (:instructions spec))))}))))

(defmethod decide :record-outcome [state {:keys [turn run outcome call detail actions-unknown?]}]
  (let [t (get-in state [:turns turn])]
    (cond
      (nil? t)                             (refuse :no-such-turn :turn turn)
      ;; A store-cancelled turn's run that has exited is recorded as exited, which clears a stuck
      ;; report and refuses a later one, whichever arrives first; its outcome is discarded, so the
      ;; turn stays cancelled.
      (and (not= :running (:status t)) (run-awaited? t) (or (nil? run) (= run (:run t))))
      (accept {:event :turn-exited :turn turn :run (:run t)})
      ;; A report from a turn a takeover cancelled, or any turn no longer running, is discarded.
      (not= :running (:status t))          (refuse :turn-not-running :turn turn :status (:status t))
      ;; So is one from an earlier run of a turn since resumed. A report that names no run is
      ;; taken as the current run's.
      (and run (not= run (:run t)))        (refuse :stale-run :turn turn :run run :current (:run t))
      ;; A run has nobody to ask: its turn is offered no ask tool, and one that asks anyway failed.
      (and (:run state) (= outcome :asked))
      (accept {:event :turn-ended :turn turn :outcome :failed :detail {:reason :asked-in-a-run}})
      (and (= outcome :asked) (not (:id call)))
      (refuse :ask-needs-a-call)
      ;; An ask whose question breaks the form never reaches the person: the turn that wrote it
      ;; failed, with what was wrong, for its conductor to hear.
      (and (= outcome :asked) (seq (ask-problems call)))
      (accept {:event :turn-ended :turn turn :outcome :failed
               :detail {:reason :ask-malformed :problems (ask-problems call)}})
      (= outcome :asked)
      (accept {:event :turn-asked :turn turn :call call})
      (#{:completed :failed :cancelled} outcome)
      (let [ended (cond-> {:event :turn-ended :turn turn :outcome outcome}
                    detail           (assoc :detail detail)
                    actions-unknown? (assoc :actions-unknown? true))]
        (accept ended))
      :else                                (refuse :unknown-outcome :outcome outcome))))

(defmethod decide :record-stuck [state {:keys [turn run reason]}]
  (let [t (get-in state [:turns turn])]
    (cond
      (nil? t)                       (refuse :no-such-turn :turn turn)
      ;; A run the store cancelled is ended by the runner after its turn reads cancelled.
      (not (run-awaited? t))
      (refuse :turn-not-running :turn turn :status (:status t))
      (and run (not= run (:run t)))  (refuse :stale-run :turn turn :run run :current (:run t))
      (stuck? t)                     (refuse :already-stuck :turn turn :run (:run t))
      :else (accept {:event :turn-stuck :turn turn :run (:run t) :reason reason}))))

(defmethod decide :resume-turn [state {:keys [turn]}]
  (let [t (get-in state [:turns turn])]
    (cond
      (not= :resolved (:status t))  (refuse :turn-not-resolved :turn turn)
      (conductor-turn? t)
      (if (some #(= :running (:status %)) (active-turns state nil))
        (refuse :conductor-turn-running)
        (accept {:event :turn-resumed :turn turn}))
      (get-in state [:units (:unit t) :held?])        (refuse :unit-held :unit (:unit t))
      (concerns-open-ask? state (:unit t))            (refuse :unit-has-open-operator-ask :unit (:unit t))
      (seq (get-in state [:units (:unit t) :unknowns])) (refuse :unit-has-unknowns :unit (:unit t))
      :else (accept {:event :turn-resumed :turn turn}))))

(defn- unit-call-for-buffer
  "The refusal that keeps the conductor from disposing of `turn-id`'s call itself, or nil."
  [state turn-id]
  (let [t (get-in state [:turns turn-id])]
    (cond
      (not= :asked (:status t))  (refuse :turn-not-asked :turn turn-id)
      (conductor-turn? t)        (refuse :conductor-ask-goes-to-the-person :turn turn-id)
      (binding-ask state turn-id) (refuse :call-bound-by-operator-ask :turn turn-id))))

(defmethod decide :answer-call [state {:keys [turn answer]}]
  (or (unit-call-for-buffer state turn)
      (accept {:event :call-answered :turn turn :answer answer})))

(defmethod decide :return-call [state {:keys [turn instructions]}]
  (or (unit-call-for-buffer state turn)
      (accept {:event :call-returned :turn turn :instructions instructions})))

(defmethod decide :raise-operator-ask [state {:keys [ask binds concerns content]}]
  (let [turns (map #(get-in state [:turns %]) binds)]
    (cond
      (contains? (:operator-asks state) ask)        (refuse :ask-exists :ask ask)
      (empty? binds)                                (refuse :ask-binds-no-call)
      (not (every? #(= :asked (:status %)) turns))  (refuse :binds-a-turn-not-asked)
      (some #(binding-ask state %) binds)           (refuse :call-already-bound)
      (and (some conductor-turn? turns) (> (count binds) 1))
      (refuse :conductor-ask-binds-only-its-own-call)
      (and concerns (not= :open (get-in state [:units concerns :status])))
      (refuse :concerns-no-open-unit :unit concerns)
      (seq (decision-problems content))
      (refuse :ask-not-decision-ready :problems (decision-problems content))
      :else (accept {:event :operator-ask-raised :ask ask :binds (vec binds)
                     :concerns concerns :content content}))))

(defmethod decide :answer-operator-ask [state {:keys [ask answer by]}]
  (if (= :open (get-in state [:operator-asks ask :status]))
    (accept (cond-> {:event :operator-ask-answered :ask ask :answer answer}
              (= :person by) (assoc :by :person)))
    (refuse :ask-not-open :ask ask)))

(defmethod decide :withdraw-operator-ask [state {:keys [ask reason by]}]
  (cond
    (not= :open (get-in state [:operator-asks ask :status])) (refuse :ask-not-open :ask ask)
    (nil? reason)                                            (refuse :withdrawal-needs-a-reason)
    :else (accept (cond-> {:event :operator-ask-withdrawn :ask ask :reason reason}
                    (= :person by) (assoc :by :person)))))

(defmethod decide :raise-conductor-stop [state {:keys [ask content]}]
  (cond
    (:conductor-stopped? state)               (refuse :conductor-already-stopped)
    (contains? (:operator-asks state) ask)    (refuse :ask-exists :ask ask)
    (seq (decision-problems content))
    (refuse :ask-not-decision-ready :problems (decision-problems content))
    :else (accept {:event :conductor-stop-raised :ask ask :content content})))

(defmethod decide :give-grant [state {:keys [grant by]}]
  (cond
    (not (keyword? grant))                 (refuse :grant-needs-a-kind)
    (contains? (:grants state) grant)      (refuse :already-granted :grant grant)
    :else (accept {:event :grant-given :grant grant :by by})))

(defmethod decide :spend-grant [state {:keys [grant]}]
  (if (contains? (:grants state) grant)
    (accept {:event :grant-spent :grant grant})
    (refuse :not-granted :grant grant)))

(defn- images-malformed
  "Why `images` cannot be a chat message's images, or nil: at most `max-message-images`, each a
   hex SHA-256 digest — an image is named, never carried."
  [images]
  (cond
    (not (sequential? images))                    :images-not-a-list
    (> (count images) max-message-images)         :too-many-images
    (not (every? #(and (string? %) (re-matches #"[0-9a-f]{64}" %)) images)) :image-malformed))

(defmethod decide :post-chat [state {:keys [message text images] :or {images []}}]
  (cond
    (some #(= message (:id %)) (:chat state)) (refuse :message-exists :message message)
    (not (string? text))                      (refuse :chat-needs-text)
    (images-malformed images)                 (refuse (images-malformed images))
    :else (accept (cond-> {:event :chat-posted :message message :text text}
                    (seq images) (assoc :images (vec images))))))

(defmethod decide :reply-chat [state {:keys [message text turn]}]
  (cond
    (some #(= message (:id %)) (:chat state)) (refuse :message-exists :message message)
    (not (string? text))                      (refuse :chat-needs-text)
    (not= :running (get-in state [:turns turn :status])) (refuse :turn-not-running :turn turn)
    (some? (get-in state [:turns turn :unit]))           (refuse :only-the-conductor-replies)
    :else (accept {:event :chat-replied :message message :text text :turn turn})))

(defmethod decide :post-notice [state {:keys [notice text]}]
  ;; nido telling the conductor of an event it observed: posted once per id, for the workstream's
  ;; life, which is what lets a pass that sees the same event again post nothing.
  (cond
    (:closed? state)                          (refuse :workstream-closed)
    (not (string? notice))                    (refuse :notice-needs-an-id)
    (contains? (:notices state) notice)       (refuse :notice-exists :notice notice)
    (not (string? text))                      (refuse :notice-needs-text)
    :else (accept {:event :notice-posted :notice notice :text text})))

(defmethod decide :hand-over [state {:keys [summary]}]
  (cond
    (:closed? state)        (refuse :workstream-closed)
    (not (string? summary)) (refuse :hand-over-needs-a-summary)
    :else (accept {:event :handed-over :summary summary})))

(defmethod decide :record-arrival [state {:keys [arrival summary]}]
  (cond
    (:closed? state)        (refuse :workstream-closed)
    (nil? arrival)          (refuse :arrival-needs-an-id)
    :else (accept {:event :arrival-recorded :arrival arrival :summary summary})))

(defmethod decide :hold-unit [state {:keys [unit]}]
  (let [u (get-in state [:units unit])]
    (cond
      (nil? u)      (refuse :no-such-unit :unit unit)
      (:held? u)    (refuse :unit-held :unit unit)
      :else (accept {:event :unit-held :unit unit}))))

(defmethod decide :release-unit [state {:keys [unit]}]
  (if (get-in state [:units unit :held?])
    (accept {:event :unit-released :unit unit})
    (refuse :unit-not-held :unit unit)))

(defmethod decide :record-unknown [state {:keys [unit key]}]
  (cond
    (nil? (get-in state [:units unit]))                       (refuse :no-such-unit :unit unit)
    (contains? (get-in state [:units unit :unknowns]) key)    (refuse :already-unknown :unit unit :key key)
    :else (accept {:event :unknown-recorded :unit unit :key key})))

(defmethod decide :settle-unknown [state {:keys [unit key how]}]
  (cond
    (not (contains? (get-in state [:units unit :unknowns]) key)) (refuse :no-such-unknown :unit unit :key key)
    (nil? how)                                                   (refuse :settling-needs-how)
    :else (accept {:event :unknown-settled :unit unit :key key :how how})))

(defn- order-cycle?
  "Whether `pairs`, read as before→after edges, contain a cycle."
  [pairs]
  (let [succ (reduce (fn [m [a b]] (update m a (fnil conj #{}) b)) {} pairs)]
    (letfn [(reach [from seen]
              (reduce (fn [seen n] (if (seen n) seen (reach n (conj seen n)))) seen (succ from)))]
      (some (fn [[a _]] (contains? (reach a #{}) a)) pairs))))

(defmethod decide :set-landing-order [state {:keys [pairs]}]
  (let [units (set (keys (:units state)))]
    (cond
      (not (every? #(and (units (first %)) (units (second %))) pairs)) (refuse :order-names-no-such-unit)
      (order-cycle? pairs)                                            (refuse :order-has-a-cycle)
      ;; A merge is a fact and is never refused, but a landing in progress must not be put after a
      ;; unit that has not merged.
      (some (fn [[before after]] (and (get-in state [:units after :landing?])
                                      (not= :merged (get-in state [:units before :status]))))
            pairs)
      (refuse :order-puts-a-landing-after-unmerged)
      :else (accept {:event :landing-order-set :pairs (vec pairs)}))))

(defn- unmerged-predecessors
  "The units `unit-id` is ordered after that have no recorded merge."
  [state unit-id]
  (for [[before after] (:landing-order state)
        :when (and (= after unit-id) (not= :merged (get-in state [:units before :status])))]
    before))

(defmethod decide :begin-landing [state {:keys [unit]}]
  (let [waits (seq (unmerged-predecessors state unit))]
    (cond
      (not= :open (get-in state [:units unit :status])) (refuse :unit-not-open :unit unit)
      waits (refuse :lands-after-unmerged :unit unit :waits-on (vec (sort waits)))
      :else (accept {:event :landing-begun :unit unit}))))

;; A merge is an observed fact about the outside world, so it is recorded even when the landing
;; order was not followed; only beginning a landing is gated on the order.
(defmethod decide :record-merge [state {:keys [unit]}]
  (if (= :open (get-in state [:units unit :status]))
    (accept {:event :unit-merged :unit unit})
    (refuse :unit-not-open :unit unit)))

(defmethod decide :close-workstream [state _]
  (let [open (for [u (vals (:units state)) :when (= :open (:status u))] (:id u))]
    (cond
      (:closed? state) (refuse :workstream-closed)
      (seq open)       (refuse :units-not-landed :units (vec (sort open)))
      :else (accept {:event :workstream-closed}))))

(defmethod decide :reopen-workstream [state _]
  (if (:closed? state)
    (accept {:event :workstream-reopened})
    (refuse :workstream-not-closed)))

(defn ^{:malli/schema [:=> [:cat :map] :int]} latest-seq
  "The seq of the newest entry on the workstream's ledger; 0 when it holds none."
  [state]
  (or (:seq (peek (vec (:ledger state)))) 0))

(def ^:private birth-kinds
  "The ledger kinds that say what a workstream was born as: a drain, a slice, a one-off."
  #{:drain :slice :scratch})

(defn ^{:malli/schema [:=> [:cat :map] [:sequential :map]]} refused-sessions
  "Every unit of `state` whose session's destroy was refused, as [{:unit :cwd :detail}] by unit id:
   the working copy kept, and why. Read whatever the stream's standing — a refusal is terminal, so
   it is found on closed and discarded workstreams above all."
  [state]
  (vec (for [u (sort-by :id (vals (:units state)))
             :when (= :refused (get-in u [:session :status]))]
         {:unit (:id u) :cwd (get-in u [:session :cwd]) :detail (get-in u [:session :detail])})))

(defn- workstream-summary
  [state]
  (let [{:keys [project title external-refs facets intake created-at]} (:identity state)
        closed? (boolean (:closed? state))
        asks    (vec (sort (map :id (awaiting-person state))))
        last-e  (peek (vec (:ledger state)))
        open-us (filter #(= :open (:status %)) (vals (:units state)))
        refused (refused-sessions state)]
    (cond->
     {:project       (some-> project name)
      :title         (get title :text)
      :external-refs (vec external-refs)
      :facets        facets
      :intake?       (some? intake)
      :created-at    created-at
      :born          (into (sorted-set) (comp (map :kind) (filter birth-kinds)) (:ledger state))
      :closed?       closed?
      :discarded?    (boolean (:discarded? state))
      :last-seq      (or (:seq last-e) 0)
      :last-at       (:at last-e)
      :asks          asks
      :asking?       (boolean (and (seq asks) (not closed?)))
      :running?      (boolean (some #(= :running (:status %)) (vals (:turns state))))
      :held?         (boolean (and (seq open-us) (every? :held? open-us)))}
      (seq refused) (assoc :refused-sessions refused))))

(defn ^{:malli/schema [:=> [:cat :map] :map]} summary
  "What a reader of many workstreams needs of one, read off its state alone: its project, title,
   refs, facets and birth, whether it is closed, where its ledger stands, whether a turn runs or a
   person holds its units, and the ids of the asks awaiting the person. `:asking?` is whether a
   person owes it a move now — an open ask on a workstream that is not closed.
   `:refused-sessions` (`refused-sessions`) is there only when some unit's destroy was refused, so a
   summary kept before the key existed still reads equal to its state's.

   A run's summary is its identity under :run, its `run-status`, the detail its turn or session
   ended with, :closed? once it is over, and its records; it never asks. A journal's is its
   identity under :journal and how many records it holds — the records themselves are its state's.

   A reading, never an event: core-store keeps it beside the state it is read from, in the same
   transaction, and rebuilds it with the state."
  [state]
  (if-let [run (:run state)]
    (let [u (get-in state [:units (:id run)])
          t (first (vals (:turns state)))]
      (cond-> {:project (:project run)
               :run     run
               :status  (run-status state)
               :detail  (or (:detail t) (get-in u [:session :detail]))
               :closed? (not= :open (:status u))
               :asking? false
               :records (vec (:records state))}
        (seq (refused-sessions state)) (assoc :refused-sessions (refused-sessions state))))
    (if-let [j (:journal state)]
      {:project (:project j) :journal j :last-seq (count (:records state)) :closed? false
       :asking? false}
      (workstream-summary state))))

(def revisable-identity
  "The parts of a workstream's identity that change after it is created. Its project and
   creation time never do."
  #{:external-refs :intake :facets :title})

(defn- entry-malformed
  "Why `row` and `content` cannot be an entry, or nil."
  [row content]
  (cond
    (not (keyword? (:kind row)))  :entry-needs-a-kind
    (not (string? (:at row)))     :entry-needs-a-time
    (not (string? (:ext row)))    :entry-needs-an-extension
    (not (string? content))       :entry-needs-content))

(defmethod decide :create-workstream [state {:keys [identity]}]
  (cond
    (or (:identity state) (:discarded? state)) (refuse :workstream-exists)
    (not (keyword? (:project identity)))   (refuse :identity-needs-a-project)
    (not (string? (:created-at identity))) (refuse :identity-needs-a-creation-time)
    :else (accept {:event :workstream-created :identity identity})))

(defn- recorded? [state] (and (:identity state) (not (:discarded? state))))

;; A workstream minted for work that never started — a spawn that failed — is discarded rather
;; than kept as an empty record every reader would find. What it recorded stays in its events.
(defmethod decide :discard-workstream [state _]
  (cond
    (not (recorded? state))                                         (refuse :no-such-workstream)
    ;; An open unit or an active turn holds it; a merged or retracted unit and an ended turn do not.
    (or (some #(= :open (:status %)) (vals (:units state)))
        (some #(active-statuses (:status %)) (vals (:turns state)))) (refuse :work-in-flight)
    :else (accept {:event :workstream-discarded})))

(defmethod decide :revise-identity [state {:keys [changes]}]
  (cond
    (not (recorded? state))                           (refuse :no-such-workstream)
    (not (map? changes))                              (refuse :revision-needs-changes)
    (seq (remove revisable-identity (keys changes)))
    (refuse :identity-not-revisable :keys (vec (sort (remove revisable-identity (keys changes)))))
    :else (accept {:event :identity-revised :changes changes})))

;; An append names the ledger's newest seq as its author read it. The domain numbers the entry
;; past it, and refuses the append once anything else has been appended since: the author's
;; checks were made against a ledger that is no longer the one the entry would join.
(defmethod decide :append-entry [state {:keys [after row content]}]
  (let [latest (latest-seq state)]
    (cond
      (not (recorded? state))            (refuse :no-such-workstream)
      (not= after latest)                (refuse :stale :latest latest)
      (entry-malformed row content)      (refuse (entry-malformed row content))
      :else (let [n (inc latest)]
              (apply accept
                     (cond-> [{:event :entry-appended
                               :row (assoc (dissoc row :file) :seq n)
                               :content content}]
                       ;; The person answered a blocker: their answer is the conductor's to hear,
                       ;; whichever surface recorded it and whether or not a session is live.
                       (= :blocker-answered (:kind row))
                       (conj {:event :blocker-answer-heard :seq n})))))))

;; The person's word sent the workstream back for rework: its :sent-back entry, the operator asks
;; that word made moot and the :merge grant it read held are one commit, so no reader ever sees the
;; record without its withdrawals, or the withdrawals without the record. Refused whole — nothing
;; changes — when the ledger moved since the sender read it, or a named ask is not open or is a
;; machine failure, which no word sends back. A :merge grant held under any other occurrence than
;; the one named was given after the sender read the core, and stands.
(defmethod decide :send-back [state {:keys [after row content asks reason merge-occurrence]}]
  (let [latest (latest-seq state)
        named  (map #(get-in state [:operator-asks %]) asks)
        held   (get-in state [:grants :merge])]
    (cond
      (not (recorded? state))                    (refuse :no-such-workstream)
      (not= after latest)                        (refuse :stale :latest latest)
      (not= :sent-back (:kind row))              (refuse :send-back-needs-a-sent-back)
      (entry-malformed row content)              (refuse (entry-malformed row content))
      (str/blank? (str reason))                  (refuse :withdrawal-needs-a-reason)
      (some #(not= :open (:status %)) named)
      (refuse :ask-not-open :ask (some #(when (not= :open (get-in state [:operator-asks % :status])) %) asks))
      (some machine-ask? named)
      (refuse :ask-is-a-machine-failure :ask (:id (first (filter machine-ask? named))))
      :else (apply accept
                   (concat
                    [{:event :entry-appended
                      :row (assoc (dissoc row :file) :seq (inc latest))
                      :content content}]
                    (for [a (distinct asks)]
                      {:event :operator-ask-withdrawn :ask a :reason reason})
                    (when (and held merge-occurrence (= merge-occurrence (:occurrence held)))
                      [{:event :grant-spent :grant :merge}]))))))

;; The person wakes a conductor that nothing else will: a stalled workstream's Wake. A stopped
;; conductor is woken through its stop ask, and one whose turn runs or resumes needs no waking.
(defmethod decide :wake-conductor [state {:keys [by]}]
  (cond
    (not (recorded? state))     (refuse :no-such-workstream)
    (:closed? state)            (refuse :workstream-closed)
    (:conductor-stopped? state) (refuse :conductor-stopped)
    (some #(and (conductor-turn? %) (#{:running :resolved} (:status %))) (vals (:turns state)))
    (refuse :conductor-turn-active)
    :else (accept {:event :conductor-woken :by (or by :person)})))

(def run-commands
  "The commands a run admits: its one unit's mechanics, and the records of what it did. Every
   other command is a workstream's."
  #{:record-provision-attempt :record-provision-outcome :record-session-lost :start-turn
    :record-outcome :append-record})

(defn- not-on-a-run
  "Why `command` is refused on run state `state`, or nil when a run admits it."
  [state {:keys [command unit]}]
  (cond
    (not (run-commands command))                       {:reason :not-a-run-command :command command}
    (and (= :start-turn command) (nil? unit))          {:reason :a-run-has-no-conductor}))

(defn ^{:malli/schema [:=> [:cat :map :map] :any]} step
  "Decide `command` against `state`: [new-state events] when admitted, [state nil refusal] when not."
  [state command]
  (let [why (cond
              (:run state)     (not-on-a-run state command)
              (:journal state) (when (not= :append-record (:command command))
                                 {:reason :not-a-journal-command :command (:command command)}))
        {:keys [events refused]} (if why {:refused why} (decide state command))]
    (if refused
      [state nil refused]
      [(reduce evolve state events) events nil])))

;; ── the invariants, as data ──────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :map] [:sequential :map]]} violations
  "Every invariant `state` breaks, as data; empty when the state is legal. These are the rules the
   design states of every committed state (asks-equal-asked-turns, operator-ask-lives-with-its-call,
   workstream-composes-units, attach-is-a-hold, conductor-hears-every-outcome), in the one form both the tests and core-store's
   constraints are checked against."
  [state]
  (let [turns (vals (:turns state))
        open  (awaiting-person state)]
    (concat
     (for [t turns :when (not= (= :asked (:status t)) (some? (:call t)))]
       {:violation :ask-without-asked-turn :turn (:id t)})
     (for [[u ts] (group-by :unit (filter #(active-statuses (:status %)) turns))
           :when (and u (> (count ts) 1))]
       {:violation :unit-has-two-active-turns :unit u})
     (when (> (count (filter #(and (conductor-turn? %) (= :running (:status %))) turns)) 1)
       [{:violation :conductor-runs-two-turns}])
     (for [a open, t (:binds a) :when (not= :asked (get-in state [:turns t :status]))]
       {:violation :open-ask-binds-resolved-call :ask (:id a) :turn t})
     (for [a open :when (and (empty? (:binds a)) (not= :conductor-stopped (:kind a)))]
       {:violation :open-ask-binds-nothing :ask (:id a)})
     (for [t turns
           :when (and (:unit t) (= :running (:status t)))
           :let [s (get-in state [:units (:unit t) :session])]
           :when (or (not= :ready (:status s)) (not= (:cwd s) (get-in t [:spec :cwd])))]
       {:violation :unit-turn-outside-its-session :turn (:id t)})
     (for [[t as] (group-by identity (mapcat :binds open)) :when (> (count as) 1)]
       {:violation :call-bound-twice :turn t})
     (for [u (vals (:units state)), t (active-turns state (:id u)) :when (:held? u)]
       {:violation :held-unit-has-active-turn :unit (:id u) :turn (:id t)})
     (for [u (vals (:units state))
           :when (:landing? u)
           before (unmerged-predecessors state (:id u))]
       {:violation :landed-before-predecessor :unit (:id u) :waits-on before})
     (when (and (:closed? state) (some #(= :open (:status %)) (vals (:units state))))
       [{:violation :closed-with-open-unit}])
     (for [a open, u (concerned-units state a), t (active-turns state u)
           :when (= :running (:status t))]
       {:violation :ask-concerns-a-running-unit :ask (:id a) :unit u :turn (:id t)})
     (for [t turns
           :when (and (conductor-turn? t) (active-statuses (:status t)))
           id (keys (:hears t))
           :when (contains? (:inbox state) id)]
       {:violation :heard-twice :item id :turn (:id t)}))))
