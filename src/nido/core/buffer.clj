(ns nido.core.buffer
  "A workstream's conductor, as the core runs it: every outcome and ask of its units reaches it,
   and only it decides what runs next.

   Two halves. The RULES decide what needs no judgement, from committed state alone:
     - a conductor turn that asked has its ask raised to the person, bound to its own call: a
       conductor never answers a conductor's ask
     - a resolved turn is resumed as soon as the domain admits it (a conductor turn once no
       other conductor turn runs)
     - while the inbox holds anything and no conductor turn runs or waits to resume, a conductor
       turn is started that hears all of it — at once when the inbox holds a person's input, and
       otherwise only while fewer than `:wake-cap` conductor turns hearing no person's input run
       across every workstream, in the order the wakes became owed
     - once the last `:max-failures` conductor turns failed, a stop ask is raised to the person;
       nothing wakes that conductor until the person answers it, a person's input included
     - a conductor turn the host refused resources to (`d/exhausted?`) is no failure: it defers
       the next wake by a doubling backoff and raises nothing
     - a unit whose session is not what it should be gets a provisioning attempt recorded, which
       core-provision then makes
   Each rule's command is keyed, so the same state seen twice issues nothing twice, and each is
   checked against the domain before it is sent, so a rule never commits a refusal.

   The JUDGEMENT is the conductor turn's: an agent with the conductor tools (this namespace's
   -main), which act only through core-api: answer or return a unit's call, raise an operator ask
   (decision-ready, binding the calls it consolidates), withdraw one whose premise moved, open a
   unit the person granted, start a unit's turn, settle an unknown. Anything only the person can
   decide it asks through the ask tool, and the first rule raises that.

   Asleep, nothing here runs: the rules run per commit, and a conductor turn per wake. Loads under
   bb as well, because the tools run under bb."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io]
            [nido.core.ask-tool :as ask-tool]
            [clojure.string :as str]
            [nido.core.api :as api]
            [nido.core.domain :as d]))

;; ── the rules ────────────────────────────────────────────────────────────────

(defn- conductor-turns [state]
  (filter #(nil? (:unit %)) (vals (:turns state))))

(defn- call-bound? [state turn-id]
  (some #(contains? (:binds %) turn-id) (d/awaiting-person state)))

(defn ^{:malli/schema [:=> [:cat :map] :boolean]} person-input?
  "Whether `state`'s inbox holds a person's input."
  [state]
  (boolean (some #(d/person-input-kinds (:kind %)) (vals (:inbox state)))))

(defn ^{:malli/schema [:=> [:cat :map :int] :boolean]} wake-owed?
  "Whether `state` owes its conductor a wake: its inbox holds items, it is not closed, no conductor
   turn runs or waits to resume, and it is not stopped."
  [state max-failures]
  (boolean (and (seq (:inbox state))
                (not (:closed? state))
                (not (:conductor-stopped? state))
                (< (:conductor-failures state 0) max-failures)
                (not-any? #(#{:running :resolved} (:status %)) (conductor-turns state)))))

(defn ^{:malli/schema [:=> [:cat :map] :int]} background-running
  "How many of `state`'s conductor turns run hearing no person's input."
  [state]
  (count (filter #(and (= :running (:status %))
                       (not-any? (fn [i] (d/person-input-kinds (:kind i))) (vals (:hears %))))
                 (conductor-turns state))))

(defn- wake
  "The keyed start of the conductor turn `state` is owed. Its spec is nil when `spec-fn` gives none
   — a drain whose coordinator brief could not be read — and such a wake is WITHHELD: marked
   `:withheld?`, never sent, so nothing is committed under its key and a later pass decides it again."
  [{:keys [spec-fn]} ws state pos]
  (let [turn (str "conductor-" pos)
        items (vals (:inbox state))
        spec  (spec-fn ws turn state (sort-by :id items))]
    (cond-> {:key (str "buffer/wake/" turn)
             :command {:command :start-turn :turn turn :unit nil
                       :hears (vec (sort (map :id items)))
                       :spec spec}}
      (nil? spec) (assoc :withheld? true))))

(defn- stop-content [n]
  {:question "This workstream's conductor has stopped. Wake it again?"
   :explored (str "Its last " n " conductor turns failed or were cancelled, so nothing wakes it; "
                  "what it had yet to hear is still in its inbox.")
   :options [{:option "Wake it" :consequence "Its next turn hears the whole inbox."}]
   :recommendation "Read why the turns failed first, then answer to wake it."})

(defn- candidates
  "The commands the rules would issue for `state`, in order, before admission is checked. The
   wake is among them only when `:wakes?` is not false."
  [{:keys [max-failures wakes?] :as opts} ws state pos]
  (let [cts (conductor-turns state)]
    (concat
     (for [t cts
           :when (and (= :asked (:status t)) (not (call-bound? state (:id t))))
           :let [call (:call t)]]
       {:key (str "buffer/raise/" (:id t) "/" (:id call))
        :command {:command :raise-operator-ask
                  :ask (str "ask " (:id t) " " (:id call))
                  :binds [(:id t)]
                  :concerns nil
                  :content (select-keys call ask-tool/content-keys)}})
     (for [t (sort-by :id (filter #(= :resolved (:status %)) (vals (:turns state))))]
       {:key (str "buffer/resume/" (:id t) "/" (:run t) "/" pos)
        :command {:command :resume-turn :turn (:id t)}})
     (when (and (>= (:conductor-failures state 0) max-failures) (not (:conductor-stopped? state)))
       [{:key (str "buffer/stop/" pos)
         :command {:command :raise-conductor-stop :ask (str "conductor stopped " pos)
                   :content (stop-content max-failures)}}])
     ;; A trigger's unit starts on its instructions once its session is up; admission refuses
     ;; it while anything else holds the unit, and the next commit offers it again.
     (for [{:keys [unit turn spec]} (d/first-turns-owed state)]
       {:key (str "buffer/first/" unit)
        :command {:command :start-turn :turn turn :unit unit :spec spec}})
     (for [{:keys [unit want]} (d/sessions-owed state)]
       {:key (str "buffer/provision/" unit "/" (name want) "/" pos)
        :command {:command :record-provision-attempt :unit unit :want want
                  :attempt (str unit " " (name want) " " pos)}})
     (when (and (not (false? wakes?)) (wake-owed? state max-failures))
       (remove :withheld? [(wake opts ws state pos)])))))

(defn- ^{:malli/schema [:=> [:cat :map :int] :boolean]} backing-off?
  "Whether `state`'s conductor waits out a backoff at `now` (epoch ms): its last turns were refused
   resources by the host, and its retry time has not come."
  [state now]
  (boolean (some-> (:conductor-retry-at state) (> now))))

(defn ^{:malli/schema [:=> [:cat :map [:map-of :string :map] [:sequential :string]] [:sequential :map]]} wakes
  "The conductor wakes to issue across every workstream: `states` is {ws {:state :pos}} as last
   committed, `owed-order` the workstreams in the order their wakes became owed. A wake for an
   inbox holding a person's input is issued at once; the rest only while fewer than `:wake-cap`
   conductor turns hearing no person's input run, in `owed-order`. A workstream backing off at
   `:now` (`backing-off?`) is woken by neither, a person's input included: the host refused the
   last run resources, and a person's message does not give them back. A wake whose spec could not
   be made is answered `:withheld?`, for the caller to leave unsent and decide again; it takes no
   place under the cap, which passes to the next workstream in `owed-order`."
  [{:keys [max-failures wake-cap now] :or {wake-cap 4} :as opts} states owed-order]
  (let [owed (filter #(and (wake-owed? (:state (states %)) max-failures)
                           (not (and now (backing-off? (:state (states %)) now))))
                     owed-order)
        {person true other false} (group-by #(person-input? (:state (states %))) owed)
        running (reduce + (map (comp background-running :state) (vals states)))
        wake-of (fn [ws] (let [{:keys [state pos]} (states ws)] (assoc (wake opts ws state pos) :ws ws)))]
    (concat (map wake-of person)
            (loop [[ws & more :as left] other, room (max 0 (- wake-cap running)), out []]
              (if (or (empty? left) (zero? room))
                out
                (let [w (wake-of ws)]
                  (recur more (if (:withheld? w) room (dec room)) (conj out w))))))))

(defn ^{:malli/schema [:=> [:cat :map :string :map :int] [:sequential :map]]} decisions
  "The keyed commands the rules issue for workstream `ws` at committed `state` (position `pos`),
   each one admitted by the domain against the state the ones before it leave."
  [opts ws state pos]
  (loop [state state, [c & more] (candidates opts ws state pos), out []]
    (if-not c
      out
      (let [[s _ refused] (d/step state (:command c))]
        (if refused
          (recur state more out)
          (recur s more (conj out (assoc c :ws ws))))))))

;; ── the conductor's brief ────────────────────────────────────────────────────

(defn- indent-answer
  "The person's answer, every line of it indented under the brief line it belongs to, so a
   several-line answer still reads as that one item."
  [answer]
  (str/join "\n" (map #(str "    " %) (str/split-lines answer))))

(defn- describe-item [{:keys [kind unit turn outcome actions-unknown? key] :as item}]
  (case kind
    :turn-ended    (str "unit " unit ": turn " turn " ended " (name outcome)
                        (when actions-unknown? " — what it did is UNKNOWN; settle it before anything else runs on this unit"))
    :turn-asked    (str "unit " unit ": turn " turn " ASKED — dispose of its call exactly once")
    :unit-merged   (str "unit " unit " merged")
    :landing-granted (str "the person pressed Land: the workstream is a release candidate and its landing is granted. "
                          "Direct it now, without asking anything: start_unit_turn on the open unit whose working copy holds the change, "
                          "instructing it to run `bb --config ~/Code/nido/bb.edn nido:workstream:pr:land` in that working copy — "
                          "it lands by the procedure the project declares, its own PR included — and to report what it printed")
    :unit-released (str "unit " unit " was released by the person after a hold")
    :unit-ready    (str "unit " unit ": its session is up and nothing has been directed there yet — "
                        "start its work with start_unit_turn, from what the workstream holds")
    :unknown       (str "unit " unit ": unknown recorded — " key)
    :session-failed (str "unit " unit ": its session could not be brought " (if (= :up (:want item)) "up" (name (:want item)))
                         " — " (:detail item) " (retry_session once the cause is fixed)")
    :chat          (str "the person said (chat " (:message item) "): " (:text item)
                        (when-let [n (seq (:images item))]
                          (str " — with " (count n) " image" (when (next n) "s")
                               " attached to this prompt, in order: " (str/join ", " n)))
                        " — answer with reply_chat")
    :ask-answered  (str "the person " (name (:how item)) " operator ask " (:ask item)
                        (when (contains? item :answer)
                          (str ", answering:\n" (indent-answer (str (:answer item))))))
    :hand-over     (str "this workstream was handed over to you from the old coordinator: " (:summary item))
    :arrival       (str "work arrived and waits for the person to promote or dismiss it: " (:summary item))
    :notice        (str "nido observed (notice " (:notice item) ", not the person's word; it owes no reply): " (:text item))
    (pr-str kind)))

(defn- recipe-running? [state]
  (= :activated (:kind (last (filter #(#{:activated :deactivated} (:kind %)) (:ledger state))))))

(defn- owed-wanted?
  "Whether a conductor turn on `state` is told what the recipe owes, read off the pipeline: the
   recipe runs and the design is cleared. Before clearance `code-held` already says it, from the
   index alone."
  [state]
  (boolean (and (recipe-running? state) (not (d/code-held state)))))

(def ^:private nido-verb "bb --config ~/Code/nido/bb.edn")

(defn- owed-direction
  "What the conductor is told the recipe owes now, from `owed` — `bb nido:owed`'s answer
   ({:at :stage :mode :person :held-by :project :ws-id}). The stage is the pipeline's; only the
   wording of how a unit does it is said here."
  [{:keys [at stage mode person held-by project ws-id]}]
  (let [at   (some-> at name)
        held (when held-by (str " A run holds the workstream (" held-by "): something is already advancing it; "
                                "direct nothing that would race it, and look again once it has let go."))]
    (str
     (cond
       (nil? stage)
       (str "  Owed: nothing — at " at ", and this arc is over.")

       person
       (str "  Owed: " (name person) " — at " at ", and it is the person's to do. Direct no unit to do it.")

       (= :release mode)
       (str "  Owed: nothing — at " at ": the workstream is a release candidate, and the person lands it with Land.")

       (= :implement stage)
       (str "  Owed now: implement — at " at ". Direct the unit whose working copy holds the change to build what "
            "the cleared design says and to RECORD it as it goes, in that working copy (the unit writes these "
            "records; you do not): each finished layer a :layer-completed, and once the whole design is built and "
            "its tests pass an :implementation-completed citing the design the :design-cleared names "
            "({:design {:seq N}}, with :design-delta), each with `" nido-verb " nido:workstream:entry:add :project "
            (some-> project name) " :ws-id " ws-id " :kind <kind> :file <edn>`. Building without recording leaves "
            "implement owed, however much is built; the implementation review is owed next.")

       (= :review-implementation stage)
       (str "  Owed now: the implementation review — at " at ". Direct the unit holding the change to run `"
            nido-verb " nido:review:loop` in its working copy and to report the status it ends at; the loop "
            "records the :review and fixes what it finds. Once it converges, the workstream is a release candidate "
            "if its QA passes or does not apply; where its project declares QA, the QA plan and its walk come first.")

       (= :plan-qa stage)
       (str "  Owed now: the QA plan — at " at ". The implementation review converged, and no QA plan with steps "
            "is recorded for the governing design. Direct the unit holding the change to record one with `" nido-verb
            " nido:workstream:entry:add :project " (some-> project name) " :ws-id " ws-id " :kind :qa-plan :file <edn>`: "
            "a :qa-plan citing the design ({:design {:seq N}}), in docs/reference/qa-brief.md's sections — :surface "
            ":product with :steps ({:id :action :expected}), or :surface :none with the :reason nothing a user sees "
            "changes. It is no release candidate yet.")

       (= :walk-qa stage)
       (str "  Owed now: the QA walk — at " at ". Direct the unit holding the change to walk the QA plan `" nido-verb
            " nido:workstream:show` prints, step by step, through agent-browser against its session's running app "
            "(the app port its briefing names); to keep a screenshot of every step with `" nido-verb
            " nido:workstream:screenshot:add :file <path>`; and to record one :qa-walk citing the plan "
            "({:plan {:seq N}}) with, for every step id, a :pass, :fail or :blocked result and its screenshot digests. "
            "It is no release candidate until a walk passes; direct no landing.")

       (= :address-qa stage)
       (str "  Owed now: what the QA walk found — at " at ". A step failed. Direct the unit holding the change to fix it "
            "and record the build (:layer-completed or :implementation-completed); the implementation review and a "
            "new walk are owed after. It is no release candidate; direct no landing.")

       (= :address-findings stage)
       (str "  Owed now: the findings the review left open — at " at ". Direct the unit holding the change to "
            "address them, then to run `" nido-verb " nido:review:loop` again.")

       (= :publish-draft-pr stage)
       (str "  Owed now: the draft PR — at " at ". Direct the unit holding the change to open it with "
            "/prepare-draft-pr, which records the :pr-opened.")

       :else
       (str "  Owed now: " (name stage) " (" (name mode) ") — at " at ". Direct a unit to do it; `" nido-verb
            " nido:owed` in its working copy names it."))
     held)))

(defn- recipe-lines
  "Where nido's recipe stands on the workstream, as the conductor is told it. `owed` is what
   `bb nido:owed` answered for it, or nil when it was not asked or could not be read."
  [state owed]
  (let [l        (:ledger state)
        kinds    (set (map :kind l))
        recorded (filter kinds [:intent :baseline :design :design-cleared])]
    (cond
      (not (recipe-running? state))
      ["Recipe: not running on this workstream; unit turns may change code."]

      (d/code-held state)
      [(str "Recipe: running, and the design is NOT cleared"
            (if (seq recorded) (str " (recorded: " (str/join ", " (map name recorded)) ")") " (nothing recorded yet)")
            ". Until it is, every unit turn runs read-only on the code — do not direct a unit to build.")
       "  Direct units through what the recipe owes, in order: the intent (the person's to state: ask them, or for an :intent grant so a unit may write it), the baseline (judged by `bb nido:review:baseline`), the design (/design, judged and cleared by `bb nido:review:design`)."
       "  A product question is a design question: put it to the person while designing, never after building."]

      :else
      ["Recipe: running, and the design is cleared: unit turns may change code, to build what the cleared design says."
       (if owed
         (owed-direction owed)
         (str "  What the recipe owes could not be read now. In order it owes: implement, recorded by the unit as "
              ":layer-completed entries and an :implementation-completed citing the cleared design; then the "
              "implementation review, `" nido-verb " nido:review:loop` in the unit's working copy, which records "
              "the :review; then, where the project declares QA, the QA plan and a walk of it that passes. `" nido-verb
              " nido:owed` in a unit's working copy says which is owed now."))
       "  Every stage advances only by its record: direct each unit through the one owed, and on its outcome through the next."])))

(def ^:private chat-rule
  "- The person's chat messages reach you as they decided, often passed on by the workstream's chat agent, which answers their questions itself; each is the person's word. Answer every chat message the person sent with reply_chat. What you settle in chat takes effect only through the same tools.")

(defn- since-and-now
  "What a conductor turn has not yet heard, and the workstream now: its units and its recipe."
  [state items owed]
  (concat
   ["Since you last heard:"]
   (map #(str "- " (describe-item %)) items)
   [""
    "Units:"]
   (for [u (sort-by :id (vals (:units state)))]
     (str "- " (:id u) " [" (name (:status u)) (when (:held? u) ", HELD")
          ", session " (name (get-in u [:session :status] :none)) "] in " (:project u) ": " (:goal u)))
   [""]
   (recipe-lines state owed)))

(defn ^{:malli/schema [:=> [:cat :string :map [:sequential :map] [:? [:maybe :map]]] :string]} brief
  "What a conductor turn is told: its role, what it has not yet heard, and the workstream now.
   `owed` is what `bb nido:owed` answered for the workstream (see `owed-wanted?`), else nil."
  [ws state items & [owed]]
  (str/join
   "\n"
   (concat
    [(str "You are the conductor of nido workstream " ws ". You decide what runs next on its units of work.")
     "Act ONLY through the nido conductor tools (mcp__nido-conductor__*); read the workstream with the state tool."
     ""
     "Rules:"
     "- Every ask a unit's turn raised is disposed of exactly once: answer_call, return_call (send it to explore further), or raise_operator_ask (bind its call; you may consolidate asks of several units), written to the asking rules below."
     "- Answer a unit yourself only when the answer follows from the goal and what is recorded. Anything that is the person's to decide goes to them."
     "- For a decision of your own that only the person can make (a new unit, a change of goal, a trade-off), ask with mcp__nido__ask as the ONLY tool call in its turn. A new unit is proposed that way and opened only once granted."
     (str "- How an ask is written and read, for every ask you raise or make:\n\n" (ask-tool/briefing) "\n")
     "- Directing a unit to ask several product questions, tell it to give each its own entry in the ask's `questions`."
     "- A unit with an unknown runs nothing until you settle it (settle_unknown) by observing what happened."
     "- Never ask the person whether to land. Once the implementation review converges and its QA passes or does not apply, the workstream is a release candidate, and the person lands it with its Land button; you hear it when they do."
     "- Start a unit's next turn with start_unit_turn when its goal needs more work; it runs in the unit's own session. Stop when there is nothing left to decide."
     chat-rule
     ""]
    (since-and-now state items owed))))

(defn- drain?
  "Whether `state` is a drain's: its ledger index holds a :drain birth entry."
  [state]
  (boolean (some #(= :drain (:kind %)) (:ledger state))))

(defn- drain-brief
  "What a conductor turn on a drain is told: the drain's `coordinator` brief, as nido's verb printed
   it, in place of the generic role and the Rules for conducting units; then the generic Rules on
   asking the person and on the person's chat, and what it has not yet heard and the workstream now
   as `brief` gives them."
  [coordinator state items & [owed]]
  (str/join
   "\n"
   (concat
    [(str/trim coordinator)
     ""
     "Rules:"
     "- For a decision only the person can make, ask with mcp__nido__ask as the ONLY tool call in its turn."
     (str "- How an ask is written and read, for every ask you make:\n\n" (ask-tool/briefing) "\n")
     chat-rule
     ""]
    (since-and-now state items owed))))

;; ── the conductor tools ──────────────────────────────────────────────────────

(def ^:private tools
  "Each tool: its input schema, and the command its arguments become. A description that is a fn is
   read when the tools are listed, so it carries the asking briefing as it is on disk then."
  {"state"                 {:description "The workstream's committed state, as EDN."
                            :properties {} :required []}
   "answer_call"           {:description "Answer a unit turn's pending call; the turn resumes with the answer."
                            :properties {:turn {:type "string"} :answer {:type "string"}} :required ["turn" "answer"]
                            :command (fn [{:keys [turn answer]}] {:command :answer-call :turn turn :answer answer})}
   "return_call"           {:description "Send a unit turn's ask back for further exploration, with instructions."
                            :properties {:turn {:type "string"} :instructions {:type "string"}} :required ["turn" "instructions"]
                            :command (fn [{:keys [turn instructions]}] {:command :return-call :turn turn :instructions instructions})}
   "raise_operator_ask"    {:description #(str "Put a decision to the person, bound to the pending calls of the unit turns it answers.\n\n"
                                                (ask-tool/briefing))
                            :properties (assoc ask-tool/properties
                                               :binds {:type "array" :items {:type "string"}} :concerns {:type "string"})
                            :required ["binds" "question" "explored"]
                            :command (fn [{:keys [binds concerns] :as args}]
                                       {:command :raise-operator-ask :ask (str "ask " (random-uuid)) :binds binds
                                        :concerns (not-empty concerns)
                                        :content (ask-tool/content args)})}
   "withdraw_operator_ask" {:description "Withdraw an operator ask whose premise changed; its calls are refused with the reason."
                            :properties {:ask {:type "string"} :reason {:type "string"}} :required ["ask" "reason"]
                            :command (fn [{:keys [ask reason]}] {:command :withdraw-operator-ask :ask ask :reason reason})}
   "open_unit"             {:description "Open a unit of work the person granted: one goal in one project."
                            :properties {:unit {:type "string"} :project {:type "string"} :goal {:type "string"}}
                            :required ["unit" "project" "goal"]
                            :command (fn [{:keys [unit project goal]}] {:command :open-unit :unit unit :project project :goal goal})}
   "start_unit_turn"       {:description "Start a unit's next turn: an agent working in the unit's session on `instructions`. While the recipe holds the workstream's design uncleared, the turn runs read-only on the code."
                            :properties {:unit {:type "string"} :instructions {:type "string"}
                                         :executor {:type "string"}}
                            :required ["unit" "instructions"]
                            :command (fn [{:keys [unit instructions executor]}]
                                       {:command :start-turn :unit unit
                                        :turn (str unit "-" (subs (str (random-uuid)) 0 8))
                                        :spec {:executor (keyword (or (not-empty executor) "claude"))
                                               :instructions instructions}})}
   "retry_session"         {:description "Bring a unit's failed session up again, once its cause is fixed."
                            :properties {:unit {:type "string"}} :required ["unit"]
                            :command (fn [{:keys [unit]}] {:command :retry-provision :unit unit})}
   "reply_chat"            {:description "Reply to the person in the workstream's chat."
                            :properties {:text {:type "string"}} :required ["text"]
                            :command (fn [{:keys [text]} turn]
                                       {:command :reply-chat :message (str "reply " (random-uuid)) :text text :turn turn})}
   "settle_unknown"        {:description "Settle an unknown on a unit, saying how you established what happened."
                            :properties {:unit {:type "string"} :key {:type "string"} :how {:type "string"}}
                            :required ["unit" "key" "how"]
                            :command (fn [{:keys [unit key how]}] {:command :settle-unknown :unit unit :key key :how how})}})

(defn ^{:malli/schema [:=> [:cat :map :string :string :string :map] :string]} call-tool
  "Run conductor tool `tool` with `args` for conductor turn `turn` of `ws`, through `conn` (an
   `nido.core.api` connection). Answers what the agent reads back."
  [conn ws turn tool args]
  (let [{:keys [command]} (get tools tool)]
    (cond
      (= "state" tool) (pr-str (:state (api/request conn {:op :state :ws ws})))
      command (pr-str (api/request conn {:op :command :ws ws
                                         :key (str "conductor/" turn "/" (random-uuid))
                                         :command (if (= "reply_chat" tool) (command args turn) (command args))}))
      :else (str "no such tool: " tool))))

(defn ^{:malli/schema [:=> [:cat :map :map] [:maybe :map]]} respond
  "The MCP response to `msg`, the conductor tools for the turn `ctx` {:socket :ws :turn} names."
  [{:keys [socket ws turn]} {:keys [id method params]}]
  (let [ok #(hash-map :jsonrpc "2.0" :id id :result %)]
    (case method
      "initialize" (ok {:protocolVersion (get params :protocolVersion "2025-06-18")
                        :capabilities {:tools {}} :serverInfo {:name "nido-conductor" :version "1"}})
      "tools/list" (ok {:tools (for [[n {:keys [description properties required]}] (sort tools)]
                                 {:name n :description (if (fn? description) (description) description)
                                  :inputSchema {:type "object" :properties properties :required required}})})
      "tools/call" (let [conn (api/connect socket)]
                     (try
                       (ok {:content [{:type "text"
                                       :text (call-tool conn ws turn (:name params) (:arguments params))}]})
                       (finally (api/disconnect conn))))
      (when id (ok {})))))

(defn- src-dir
  "The source root this namespace was loaded from, which the tools run from."
  []
  (some-> (clojure.java.io/resource "nido/core/buffer.clj") clojure.java.io/file
          .getParentFile .getParentFile .getParentFile str))

(defn ^{:malli/schema [:=> [:cat :map] :map]} mcp-server
  "The conductor tools as an MCP server entry an executor can hand its agent. :src defaults to the
   source root this namespace loaded from, :bb-bin to `bb`."
  [{:keys [bb-bin src socket ws turn]}]
  {:command (or bb-bin "bb")
   :args ["--classpath" (or src (src-dir)) "-m" "nido.core.buffer" "tools" socket ws turn]})

(defn ^:export -main
  "`tools <socket> <ws> <turn>`: serve the conductor tools over stdio.
   `tool <socket> <ws> <turn> <name> <json-args>`: run one, printing what an agent would read."
  [& [cmd socket ws turn tool args]]
  (case cmd
    "tools" (doseq [line (line-seq (java.io.BufferedReader. *in*))
                    :when (seq (str/trim line))]
              (when-let [r (respond {:socket socket :ws ws :turn turn} (json/read-str line :key-fn keyword))]
                (println (json/write-str r))
                (flush)))
    "tool"  (let [conn (api/connect socket)]
              (try (println (call-tool conn ws turn tool (json/read-str (or args "{}") :key-fn keyword)))
                   (finally (api/disconnect conn))))))

(defn ^{:malli/schema [:=> [:cat :map] fn?]} owed-reader
  "(fn [ws]) -> what `bb nido:owed` answers for core workstream `ws` (\"<project>/<ws-id>\"), or nil
   when it fails, prints no map or takes longer than `:timeout-ms`. The pipeline that places a
   workstream is not the core's to require, so it is asked through nido's own verb, as the session
   verb is (`nido.core.provision`)."
  [{:keys [bb-bin nido-dir timeout-ms] :or {timeout-ms 30000}}]
  (fn [ws]
    (try
      (let [[project ws-id] (str/split ws #"/" 2)
            p (.start (doto (ProcessBuilder. ^java.util.List
                                             [(or bb-bin "bb") "--config" (str nido-dir "/bb.edn") "nido:owed"
                                              ":project" project ":ws-id" ws-id ":edn" "true"])
                        (.redirectError java.lang.ProcessBuilder$Redirect/DISCARD)))
            out (future (slurp (.getInputStream p)))]
        (if (.waitFor p timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
          (some (fn [line] (try (let [x (edn/read-string line)] (when (map? x) x)) (catch Exception _ nil)))
                (reverse (str/split-lines (deref out 5000 ""))))
          (do (.destroyForcibly p) nil)))
      (catch Exception _ nil))))

(defn ^{:malli/schema [:=> [:cat :map] fn?]} drain-brief-reader
  "(fn [ws]) -> the coordinator brief `bb nido:drain:brief` prints for core workstream `ws`
   (\"<project>/<ws-id>\"), or nil when it exits non-zero, prints nothing or takes longer than
   `:timeout-ms`. The brief is lane-drain's, which the core may not require, so it is asked of nido's
   own verb in the checkout, as `owed-reader` asks what the recipe owes."
  [{:keys [bb-bin nido-dir timeout-ms] :or {timeout-ms 30000}}]
  (fn [ws]
    (try
      (let [[project ws-id] (str/split ws #"/" 2)
            p (.start (doto (ProcessBuilder. ^java.util.List
                                             [(or bb-bin "bb") "--config" (str nido-dir "/bb.edn") "nido:drain:brief"
                                              ":project" project ":drain" ws-id])
                        (.redirectError java.lang.ProcessBuilder$Redirect/DISCARD)))
            out (future (slurp (.getInputStream p)))]
        (if (.waitFor p timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
          (let [b (str/trim (deref out 5000 ""))]
            (when (and (zero? (.exitValue p)) (seq b)) b))
          (do (.destroyForcibly p) nil)))
      (catch Exception _ nil))))

(defn ^{:malli/schema [:=> [:cat :map] fn?]} conductor-spec-fn
  "The spec of a conductor turn as the daemon runs it: `executor` with the brief, in the
   workstream's own directory (`(ws-dir ws)`), with the conductor tools speaking to `socket`, and
   the digests of the images the chat messages it hears carry as `:images`, each once in order,
   which the executor puts in its prompt. `owed`, (fn [ws]) -> `bb nido:owed`'s answer or nil, is
   asked only where the brief says what the recipe owes (`owed-wanted?`). `drain-brief`, (fn [ws]) ->
   the drain's coordinator brief or nil, is asked only for a drain (`drain?`), whose turn is told
   `drain-brief` in place of `brief`; when it gives none the spec is nil, and the wake is withheld."
  [{:keys [executor model socket src bb-bin ws-dir owed] read-brief :drain-brief :or {executor :claude}}]
  (fn [ws turn state items]
    (let [images (vec (distinct (mapcat :images (filter #(= :chat (:kind %)) items))))
          owed   (when (and owed (owed-wanted? state)) (owed ws))
          coord  (when (and read-brief (drain? state)) (read-brief ws))]
      (when (or (not (drain? state)) (seq coord))
        (cond-> {:executor executor
                 :instructions (if (drain? state) (drain-brief coord state items owed) (brief ws state items owed))
                 :cwd (ws-dir ws)
                 :mcp-servers {"nido-conductor" (mcp-server {:bb-bin bb-bin :src src :socket socket :ws ws :turn turn})}}
          (seq images) (assoc :images images)
          model (assoc :model model))))))

;; ── running the rules ────────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat [:map-of :string :map] :int] [:maybe :int]]} next-retry-in
  "Milliseconds from `now` until the earliest backoff among `states` ({ws {:state :pos}}) ends, or
   nil when none is pending."
  [states now]
  (some->> (vals states)
           (keep #(get-in % [:state :conductor-retry-at]))
           (filter #(> % now))
           seq
           (apply min)
           (+ (- now) 1)))

(defn ^{:malli/schema [:=> [:cat :map] :map]} start
  "Start the rules over committed changes. Options:
     :submit        (fn [envelope]) a command to the writer
     :spec-fn       (fn [ws turn state items]) -> the spec of a conductor turn hearing `items`
     :max-failures  consecutive conductor failures that raise the stop ask (default 3)
     :wake-cap      conductor turns hearing no person's input that may run at once (default 4)
     :withheld-retry-ms  how soon wakes are decided again after one was withheld (default 30s)
   Feed it committed changes [{:ws :pos :state} ...] with `changed!`; a start hands it every
   workstream not closed the same way."
  [{:keys [submit spec-fn max-failures wake-cap withheld-retry-ms]
    :or {max-failures 3 wake-cap 4 withheld-retry-ms 30000}}]
  (let [q (java.util.concurrent.LinkedBlockingQueue.)
        opts {:spec-fn spec-fn :max-failures max-failures :wake-cap wake-cap}
        states (atom {})          ; ws -> {:state :pos}, as last committed
        owed-order (atom [])      ; workstreams owing a wake, in the order they became owed
        log! (fn [ws e] (binding [*out* *err*] (println "nido.core.buffer:" ws (.getMessage ^Throwable e))))
        t (doto (Thread. ^Runnable
                         (fn []
                           (loop [decided (System/currentTimeMillis) withheld? false]
                             ;; A backoff ends with no commit to say so, so wait no longer than the
                             ;; earliest one, and then decide the wakes again over the same states.
                             ;; `decided` is the time the last wakes were decided at: a backoff still
                             ;; pending then is waited for even when it has ended since. A wake
                             ;; withheld then is decided again no later than `withheld-retry-ms` on.
                             (let [x (if-let [ms (some->> [(next-retry-in @states decided)
                                                          (when withheld? withheld-retry-ms)]
                                                         (remove nil?) seq (apply min))]
                                       (or (.poll q (long (- ms (- (System/currentTimeMillis) decided)))
                                                  java.util.concurrent.TimeUnit/MILLISECONDS)
                                           [])
                                       (.take q))]
                               (when-not (= ::stop x)
                                 (doseq [{:keys [ws state pos]} x]
                                   (swap! states assoc ws {:state state :pos pos})
                                   (doseq [envelope (try (decisions (assoc opts :wakes? false) ws state pos)
                                                         (catch Throwable e (log! ws e) nil))]
                                     (submit envelope)))
                                 (let [owing (set (filter #(wake-owed? (:state (@states %)) max-failures) (keys @states)))]
                                   (swap! owed-order #(into (vec (filter owing %)) (remove (set %) (sort owing)))))
                                 (let [now (System/currentTimeMillis)
                                       ws  (try (wakes (assoc opts :now now) @states @owed-order)
                                                (catch Throwable e (log! "wakes" e) nil))]
                                   (doseq [envelope (remove :withheld? ws)]
                                     (submit envelope))
                                   (recur now (boolean (some :withheld? ws))))))))
                         "nido-buffer")
            (.setDaemon true) (.start))]
    {:queue q :thread t}))

(defn ^{:malli/schema [:=> [:cat :map [:sequential :map]] :nil]} changed!
  "Hand the rules a commit's changes. Never blocks."
  [buffer changes]
  (.put ^java.util.concurrent.LinkedBlockingQueue (:queue buffer) (vec changes))
  nil)

(defn ^{:malli/schema [:=> [:cat :map] :nil]} stop!
  "Stop applying the rules."
  [buffer]
  (.put ^java.util.concurrent.LinkedBlockingQueue (:queue buffer) ::stop)
  nil)
