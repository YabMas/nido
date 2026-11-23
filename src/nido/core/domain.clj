(ns nido.core.domain
  "The rules a workstream and its units of work change by: what each may be, which commands a state
   admits, and what each yields.

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
   two leaves an attempt pending that a start finds and makes again.

   Pure: no clock, no ids minted, no I/O. Ids and times arrive in commands."
  (:require [clojure.set :as set]))

;; ── the state ────────────────────────────────────────────────────────────────

(def empty-state
  "A workstream before its first event."
  {:units         {}    ; unit-id -> {:id :project :goal :status #{:open :merged :retracted}
                        ;             :held? bool :unknowns #{key} :landing? bool :session session}
                        ; session: {:status #{:none :ready :failed :stopped :destroyed}
                        ;           :cwd str|nil :attempt {:id :want}|nil :detail}
   :turns         {}    ; turn-id -> {:id :unit (nil = conductor) :status :spec :run
                        ;             :call :resolution :resolved-call :ended-by :detail}
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
   :conductor-failures 0 ; conductor turns failed or cancelled since one last completed or asked
   :conductor-stopped? false ; a stop ask is open on the conductor; nothing wakes it until answered
   :grants        {}    ; grant kind -> {:by str}: what the person granted and has not yet spent
   :chat          []    ; [{:id :from #{:person :conductor} :text :turn}] in the order posted
   :closed?       false
   :identity      nil   ; what the workstream is: {:project :external-refs :created-at :intake?
                        ; :facets? :title?}; nil until it is created or imported
   :ledger        []})  ; the work records' index rows, in seq order: {:seq :kind :at :ext ...}.
                        ; An entry's content is in its :entry-appended event, never in the state,
                        ; so every feed message about a workstream stays the size of its index.

(def person-input-kinds
  "The inbox items that carry a person's input. A wake for an inbox holding one is never capped."
  #{:chat :ask-answered})

(def active-statuses
  "A turn in one of these statuses is active, so it counts against its unit. Only :running counts
   against the conductor's one turn at a time."
  #{:running :asked :resolved})

(defn- conductor-turn? [turn] (nil? (:unit turn)))

(defn ^{:malli/schema [:=> [:cat :map :map] [:maybe :keyword]]} session-want
  "What unit `unit`'s session should be in `state`: :up, :stopped or :destroyed."
  [state unit]
  (cond
    (:closed? state)            :destroyed
    (= :open (:status unit))    :up
    :else                       :stopped))

(def ^:private reached
  "The session status each want is reached at."
  {:up :ready :stopped :stopped :destroyed :destroyed})

(declare active-turns)

(defn ^{:malli/schema [:=> [:cat :map] [:sequential :map]]} sessions-owed
  "Every unit of `state` whose session is not what it should be and has no attempt pending, with
   the want: [{:unit :want}]. A failed bring-up is not owed again until it is retried: the
   conductor hears the failure and decides."
  [state]
  (for [u (sort-by :id (vals (:units state)))
        :let [want (session-want state u)
              {:keys [status attempt]} (:session u)]
        :when (and (nil? attempt)
                   (not= (reached want) status)
                   ;; a session is never taken down under a turn still active in it
                   (or (= :up want) (empty? (active-turns state (:id u))))
                   (not (and (= :up want) (= :failed status)))
                   ;; nothing to stop or destroy where nothing was ever brought up
                   (not (and (not= :up want) (= :none status)))
                   (not (and (= :stopped want) (= :destroyed status))))]
    {:unit (:id u) :want want}))

(defn ^{:malli/schema [:=> [:cat :map] [:sequential :map]]} attempts-pending
  "Every provisioning attempt recorded and not yet resolved: [{:unit :attempt :want :project}]."
  [state]
  (for [u (sort-by :id (vals (:units state)))
        :let [a (get-in u [:session :attempt])]
        :when a]
    {:unit (:id u) :attempt (:id a) :want (:want a) :project (:project u)
     :cwd (get-in u [:session :cwd])}))

(defn- active-turns
  "The active turns of `unit-id`, or of the conductor when `unit-id` is nil."
  [state unit-id]
  (filter #(and (= unit-id (:unit %)) (active-statuses (:status %))) (vals (:turns state))))

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
  (assoc-in state [:units unit] (cond-> {:id unit :project project :goal goal :status :open
                                         :held? false :unknowns #{} :landing? false
                                         :session (if session-cwd
                                                    {:status :ready :cwd session-cwd}
                                                    {:status :none})}
                                  trigger    (assoc :trigger trigger)
                                  first-turn (assoc :first-turn first-turn))))

(defmethod evolve :provision-attempted [state {:keys [unit attempt want]}]
  (assoc-in state [:units unit :session :attempt] {:id attempt :want want}))

(defmethod evolve :provision-recorded [state {:keys [unit outcome cwd detail]}]
  (let [want (get-in state [:units unit :session :attempt :want])
        status (if (= :failed outcome) :failed (reached want))]
    (cond-> (update-in state [:units unit :session]
                       #(cond-> (-> % (dissoc :attempt :detail) (assoc :status status))
                          cwd    (assoc :cwd cwd)
                          detail (assoc :detail detail)))
      (= :failed outcome) (hear-later {:kind :session-failed :unit unit :want want :detail detail}))))

(defmethod evolve :provision-retried [state {:keys [unit]}]
  (update-in state [:units unit :session] #(-> % (assoc :status :none) (dissoc :detail))))

(defmethod evolve :unit-goal-retracted [state {:keys [unit]}]
  (assoc-in state [:units unit :status] :retracted))

(defmethod evolve :turn-started [state {:keys [turn unit spec hears]}]
  (let [heard (select-keys (:inbox state) hears)]
    (-> state
        (assoc-in [:turns turn] (cond-> {:id turn :unit unit :status :running :spec spec :run 1}
                                  (nil? unit) (assoc :hears heard)))
        (update :inbox #(apply dissoc % hears)))))

(defmethod evolve :turn-asked [state {:keys [turn call]}]
  (let [{:keys [unit run]} (get-in state [:turns turn])]
    (cond-> (update-in state [:turns turn] assoc :status :asked :call call)
      unit       (hear-later {:kind :turn-asked :unit unit :turn turn :run run})
      (nil? unit) (assoc :conductor-failures 0))))

(defmethod evolve :turn-resumed [state {:keys [turn]}]
  (update-in state [:turns turn] #(-> % (assoc :status :running) (update :run inc))))

(defn ^{:malli/schema [:=> [:cat :string :int] :string]} run-unknown-key
  "The unknown a turn's run leaves on its unit when what the run did cannot be known."
  [turn run]
  (str "turn " turn " run " run))

(defmethod evolve :turn-ended [state {:keys [turn outcome detail actions-unknown?]}]
  (let [{:keys [unit run hears] :as t} (get-in state [:turns turn])
        disposed? (= :completed outcome)]
    (cond-> (update-in state [:turns turn] #(cond-> (assoc % :status outcome)
                                              detail (assoc :detail detail)))
      (and actions-unknown? unit)
      (update-in [:units unit :unknowns] conj (run-unknown-key turn run))

      unit (hear-later (turn-ended-item t outcome actions-unknown?))

      (and (nil? unit) disposed?)       (assoc :conductor-failures 0)
      (and (nil? unit) (not disposed?)) (-> (update :inbox merge hears)
                                            (update :conductor-failures inc)))))

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
                          (hear-later (turn-ended-item t :cancelled false))))
            state running)))

(defn- person-closed-ask
  "After the person closed operator ask `ask-id`: a stop ask lifts the conductor's stop; any other
   is heard by the conductor as the person's input."
  [state ask-id how]
  (if (= :conductor-stopped (get-in state [:operator-asks ask-id :kind]))
    (assoc state :conductor-stopped? false :conductor-failures 0)
    (hear-later state {:kind :ask-answered :ask ask-id :how how})))

(defmethod evolve :operator-ask-answered [state {:keys [ask answer by]}]
  (cond-> (close-ask state ask :answered {:answered answer})
    (= :person by) (person-closed-ask ask :answered)))

(defmethod evolve :operator-ask-withdrawn [state {:keys [ask reason by]}]
  (cond-> (close-ask state ask :withdrawn {:refused reason})
    (= :person by) (person-closed-ask ask :withdrawn)))

(defmethod evolve :conductor-stop-raised [state {:keys [ask content]}]
  (-> state
      (assoc :conductor-stopped? true)
      (assoc-in [:operator-asks ask] {:id ask :kind :conductor-stopped :binds #{} :concerns nil
                                      :status :open :content content})))

(defmethod evolve :grant-given [state {:keys [grant by]}]
  (assoc-in state [:grants grant] {:by by}))

(defmethod evolve :grant-spent [state {:keys [grant]}]
  (update state :grants dissoc grant))

(defmethod evolve :chat-posted [state {:keys [message text]}]
  (-> state
      (update :chat (fnil conj []) {:id message :from :person :text text})
      (hear-later {:kind :chat :message message :text text})))

(defmethod evolve :chat-replied [state {:keys [message text turn]}]
  (update state :chat (fnil conj []) {:id message :from :conductor :text text :turn turn}))

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

(def decision-ready-keys
  "What every operator ask states: what was explored, the options with their consequences, and a
   recommendation."
  [:explored :options :recommendation])

(defn- decision-ready? [content]
  (and (map? content)
       (every? #(some? (get content %)) decision-ready-keys)
       (seq (:options content))))

(defmulti ^{:malli/schema [:=> [:cat :map :map] [:or [:map [:events [:vector :map]]] [:map [:refused :map]]]]} decide
  "Decide `command` against the whole workstream `state`: {:events [...]} when it is admitted,
   {:refused {:reason kw ...}} when it is not. A refused command changes nothing."
  (fn [_state command] (:command command)))

(defmethod decide :default [_ command]
  (refuse :unknown-command :command (:command command)))

(defmethod decide :open-unit [state {:keys [unit project goal session-cwd trigger first-turn]}]
  (cond
    (:closed? state)                       (refuse :workstream-closed)
    (contains? (:units state) unit)        (refuse :unit-exists :unit unit)
    (or (nil? project) (nil? goal))        (refuse :unit-needs-project-and-goal)
    (and first-turn (not (and (keyword? (get-in first-turn [:spec :executor]))
                              (string? (get-in first-turn [:spec :instructions])))))
    (refuse :turn-needs-executor-and-instructions)
    :else (accept (cond-> {:event :unit-opened :unit unit :project project :goal goal}
                    session-cwd (assoc :session-cwd session-cwd)
                    trigger     (assoc :trigger trigger)
                    first-turn  (assoc :first-turn first-turn)))))

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
      (not (#{:done :failed} outcome))          (refuse :unknown-outcome :outcome outcome)
      (and (= :done outcome) (= :up (:want a)) (not (string? cwd)))
      (refuse :ready-needs-a-cwd)
      :else (accept (cond-> {:event :provision-recorded :unit unit :attempt attempt :outcome outcome}
                      cwd    (assoc :cwd cwd)
                      detail (assoc :detail detail))))))

(defmethod decide :retry-provision [state {:keys [unit]}]
  (if (= :failed (get-in state [:units unit :session :status]))
    (accept {:event :provision-retried :unit unit})
    (refuse :session-not-failed :unit unit)))

(defmethod decide :retract-unit-goal [state {:keys [unit]}]
  (cond
    (not= :open (get-in state [:units unit :status])) (refuse :unit-not-open :unit unit)
    (seq (active-turns state unit))                   (refuse :unit-has-active-turn :unit unit)
    :else (accept {:event :unit-goal-retracted :unit unit})))

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
      ;; A unit turn works in its unit's session, whatever the command said.
      (accept {:event :turn-started :turn turn :unit unit
               :spec (assoc spec :cwd (get-in state [:units unit :session :cwd]))}))))

(defmethod decide :record-outcome [state {:keys [turn run outcome call detail actions-unknown?]}]
  (let [t (get-in state [:turns turn])]
    (cond
      (nil? t)                             (refuse :no-such-turn :turn turn)
      ;; A report from a turn a takeover cancelled, or any turn no longer running, is discarded.
      (not= :running (:status t))          (refuse :turn-not-running :turn turn :status (:status t))
      ;; So is one from an earlier run of a turn since resumed. A report that names no run is
      ;; taken as the current run's.
      (and run (not= run (:run t)))        (refuse :stale-run :turn turn :run run :current (:run t))
      (= outcome :asked)
      (if (:id call)
        (accept {:event :turn-asked :turn turn :call call})
        (refuse :ask-needs-a-call))
      (#{:completed :failed :cancelled} outcome)
      (accept (cond-> {:event :turn-ended :turn turn :outcome outcome}
                detail           (assoc :detail detail)
                actions-unknown? (assoc :actions-unknown? true)))
      :else                                (refuse :unknown-outcome :outcome outcome))))

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
      (not (decision-ready? content))               (refuse :ask-not-decision-ready)
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
    (not (decision-ready? content))           (refuse :ask-not-decision-ready)
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

(defmethod decide :post-chat [state {:keys [message text]}]
  (cond
    (some #(= message (:id %)) (:chat state)) (refuse :message-exists :message message)
    (not (string? text))                      (refuse :chat-needs-text)
    :else (accept {:event :chat-posted :message message :text text})))

(defmethod decide :reply-chat [state {:keys [message text turn]}]
  (cond
    (some #(= message (:id %)) (:chat state)) (refuse :message-exists :message message)
    (not (string? text))                      (refuse :chat-needs-text)
    (not= :running (get-in state [:turns turn :status])) (refuse :turn-not-running :turn turn)
    (some? (get-in state [:turns turn :unit]))           (refuse :only-the-conductor-replies)
    :else (accept {:event :chat-replied :message message :text text :turn turn})))

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
    (not (recorded? state))                       (refuse :no-such-workstream)
    (or (seq (:units state)) (seq (:turns state))) (refuse :work-in-flight)
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
      :else (accept {:event :entry-appended
                     :row (assoc (dissoc row :file) :seq (inc latest))
                     :content content}))))

;; A legacy ledger arrives whole, with the seqs and times it was written under, as long as the
;; workstream has no identity yet: an import is answered once.
(defmethod decide :import-ledger [state {:keys [identity entries]}]
  (let [seqs (map #(get-in % [:row :seq]) entries)]
    (cond
      (or (:identity state) (:discarded? state)) (refuse :workstream-exists)
      (not (keyword? (:project identity)))      (refuse :identity-needs-a-project)
      (not (every? pos-int? seqs))              (refuse :entry-needs-a-seq)
      (not (apply < 0 seqs))                    (refuse :seqs-not-increasing)
      (some #(entry-malformed (:row %) (:content %)) entries)
      (refuse :entry-malformed
              :seq (some #(when (entry-malformed (:row %) (:content %)) (get-in % [:row :seq])) entries))
      :else (apply accept
                   {:event :workstream-created :identity identity :imported true}
                   (for [{:keys [row content]} entries]
                     {:event :entry-appended :row (dissoc row :file) :content content})))))

(defn ^{:malli/schema [:=> [:cat :map :map] :any]} step
  "Decide `command` against `state`: [new-state events] when admitted, [state nil refusal] when not."
  [state command]
  (let [{:keys [events refused]} (decide state command)]
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
