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
            [clojure.java.io]
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

(defn- wake [{:keys [spec-fn]} ws state pos]
  (let [turn (str "conductor-" pos)
        items (vals (:inbox state))]
    {:key (str "buffer/wake/" turn)
     :command {:command :start-turn :turn turn :unit nil
               :hears (vec (sort (map :id items)))
               :spec (spec-fn ws turn state (sort-by :id items))}}))

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
                  :content (select-keys call [:question :explored :options :recommendation])}})
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
       [(wake opts ws state pos)]))))

(defn ^{:malli/schema [:=> [:cat :map [:map-of :string :map] [:sequential :string]] [:sequential :map]]} wakes
  "The conductor wakes to issue across every workstream: `states` is {ws {:state :pos}} as last
   committed, `owed-order` the workstreams in the order their wakes became owed. A wake for an
   inbox holding a person's input is issued at once; the rest only while fewer than `:wake-cap`
   conductor turns hearing no person's input run, in `owed-order`."
  [{:keys [max-failures wake-cap] :or {wake-cap 4} :as opts} states owed-order]
  (let [owed (filter #(wake-owed? (:state (states %)) max-failures) owed-order)
        {person true other false} (group-by #(person-input? (:state (states %))) owed)
        running (reduce + (map (comp background-running :state) (vals states)))
        admitted (concat person (take (max 0 (- wake-cap running)) other))]
    (for [ws admitted :let [{:keys [state pos]} (states ws)]]
      (assoc (wake opts ws state pos) :ws ws))))

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

(defn- describe-item [{:keys [kind unit turn outcome actions-unknown? key] :as item}]
  (case kind
    :turn-ended    (str "unit " unit ": turn " turn " ended " (name outcome)
                        (when actions-unknown? " — what it did is UNKNOWN; settle it before anything else runs on this unit"))
    :turn-asked    (str "unit " unit ": turn " turn " ASKED — dispose of its call exactly once")
    :unit-merged   (str "unit " unit " merged")
    :unit-released (str "unit " unit " was released by the person after a hold")
    :unknown       (str "unit " unit ": unknown recorded — " key)
    :session-failed (str "unit " unit ": its session could not be brought " (if (= :up (:want item)) "up" (name (:want item)))
                         " — " (:detail item) " (retry_session once the cause is fixed)")
    :chat          (str "the person said (chat " (:message item) "): " (:text item) " — answer with reply_chat")
    :ask-answered  (str "the person " (name (:how item)) " operator ask " (:ask item))
    :hand-over     (str "this workstream was handed over to you from the old coordinator: " (:summary item))
    :arrival       (str "work arrived and waits for the person to promote or dismiss it: " (:summary item))
    (pr-str kind)))

(defn ^{:malli/schema [:=> [:cat :string :map [:sequential :map]] :string]} brief
  "What a conductor turn is told: its role, what it has not yet heard, and the workstream now."
  [ws state items]
  (str/join
   "\n"
   (concat
    [(str "You are the conductor of nido workstream " ws ". You decide what runs next on its units of work.")
     "Act ONLY through the nido conductor tools (mcp__nido-conductor__*); read the workstream with the state tool."
     ""
     "Rules:"
     "- Every ask a unit's turn raised is disposed of exactly once: answer_call, return_call (send it to explore further), or raise_operator_ask (bind its call; you may consolidate asks of several units)."
     "- Answer a unit yourself only when the answer follows from the goal and what is recorded. Anything that is the person's to decide goes to them."
     "- For a decision of your own that only the person can make (a new unit, a change of goal, a trade-off), ask with mcp__nido__ask as the ONLY tool call in its turn. A new unit is proposed that way and opened only once granted."
     "- Every ask you raise states what was explored, the options with their consequences, and a recommendation."
     "- A unit with an unknown runs nothing until you settle it (settle_unknown) by observing what happened."
     "- Start a unit's next turn with start_unit_turn when its goal needs more work; it runs in the unit's own session. Stop when there is nothing left to decide."
     "- Answer every chat message the person sent with reply_chat. What you settle in chat takes effect only through the same tools."
     ""
     "Since you last heard:"]
    (map #(str "- " (describe-item %)) items)
    [""
     "Units:"]
    (for [u (sort-by :id (vals (:units state)))]
      (str "- " (:id u) " [" (name (:status u)) (when (:held? u) ", HELD")
           ", session " (name (get-in u [:session :status] :none)) "] in " (:project u) ": " (:goal u))))))

;; ── the conductor tools ──────────────────────────────────────────────────────

(def ^:private tools
  "Each tool: its input schema, and the command its arguments become."
  {"state"                 {:description "The workstream's committed state, as EDN."
                            :properties {} :required []}
   "answer_call"           {:description "Answer a unit turn's pending call; the turn resumes with the answer."
                            :properties {:turn {:type "string"} :answer {:type "string"}} :required ["turn" "answer"]
                            :command (fn [{:keys [turn answer]}] {:command :answer-call :turn turn :answer answer})}
   "return_call"           {:description "Send a unit turn's ask back for further exploration, with instructions."
                            :properties {:turn {:type "string"} :instructions {:type "string"}} :required ["turn" "instructions"]
                            :command (fn [{:keys [turn instructions]}] {:command :return-call :turn turn :instructions instructions})}
   "raise_operator_ask"    {:description "Put a decision to the person, bound to the pending calls of the unit turns it answers."
                            :properties {:binds {:type "array" :items {:type "string"}} :concerns {:type "string"}
                                         :question {:type "string"} :explored {:type "string"}
                                         :options {:type "array" :items {:type "string"}} :recommendation {:type "string"}}
                            :required ["binds" "question" "explored" "options" "recommendation"]
                            :command (fn [{:keys [binds concerns question explored options recommendation]}]
                                       {:command :raise-operator-ask :ask (str "ask " (random-uuid)) :binds binds
                                        :concerns (not-empty concerns)
                                        :content {:question question :explored explored :options options
                                                  :recommendation recommendation}})}
   "withdraw_operator_ask" {:description "Withdraw an operator ask whose premise changed; its calls are refused with the reason."
                            :properties {:ask {:type "string"} :reason {:type "string"}} :required ["ask" "reason"]
                            :command (fn [{:keys [ask reason]}] {:command :withdraw-operator-ask :ask ask :reason reason})}
   "open_unit"             {:description "Open a unit of work the person granted: one goal in one project."
                            :properties {:unit {:type "string"} :project {:type "string"} :goal {:type "string"}}
                            :required ["unit" "project" "goal"]
                            :command (fn [{:keys [unit project goal]}] {:command :open-unit :unit unit :project project :goal goal})}
   "start_unit_turn"       {:description "Start a unit's next turn: an agent working in the unit's session on `instructions`."
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
                                 {:name n :description description
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

(defn ^{:malli/schema [:=> [:cat :map] fn?]} conductor-spec-fn
  "The spec of a conductor turn as the daemon runs it: `executor` with the brief, in the
   workstream's own directory (`(ws-dir ws)`), with the conductor tools speaking to `socket`."
  [{:keys [executor model socket src bb-bin ws-dir] :or {executor :claude}}]
  (fn [ws turn state items]
    (cond-> {:executor executor
             :instructions (brief ws state items)
             :cwd (ws-dir ws)
             :mcp-servers {"nido-conductor" (mcp-server {:bb-bin bb-bin :src src :socket socket :ws ws :turn turn})}}
      model (assoc :model model))))

;; ── running the rules ────────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :map] :map]} start
  "Start the rules over committed changes. Options:
     :submit        (fn [envelope]) a command to the writer
     :spec-fn       (fn [ws turn state items]) -> the spec of a conductor turn hearing `items`
     :max-failures  consecutive conductor failures that raise the stop ask (default 3)
     :wake-cap      conductor turns hearing no person's input that may run at once (default 4)
   Feed it committed changes [{:ws :pos :state} ...] with `changed!`; a start hands it every
   workstream not closed the same way."
  [{:keys [submit spec-fn max-failures wake-cap] :or {max-failures 3 wake-cap 4}}]
  (let [q (java.util.concurrent.LinkedBlockingQueue.)
        opts {:spec-fn spec-fn :max-failures max-failures :wake-cap wake-cap}
        states (atom {})          ; ws -> {:state :pos}, as last committed
        owed-order (atom [])      ; workstreams owing a wake, in the order they became owed
        log! (fn [ws e] (binding [*out* *err*] (println "nido.core.buffer:" ws (.getMessage ^Throwable e))))
        t (doto (Thread. ^Runnable
                         (fn []
                           (loop []
                             (let [x (.take q)]
                               (when-not (= ::stop x)
                                 (doseq [{:keys [ws state pos]} x]
                                   (swap! states assoc ws {:state state :pos pos})
                                   (doseq [envelope (try (decisions (assoc opts :wakes? false) ws state pos)
                                                         (catch Throwable e (log! ws e) nil))]
                                     (submit envelope)))
                                 (let [owing (set (filter #(wake-owed? (:state (@states %)) max-failures) (keys @states)))]
                                   (swap! owed-order #(into (vec (filter owing %)) (remove (set %) (sort owing)))))
                                 (doseq [envelope (try (wakes opts @states @owed-order)
                                                       (catch Throwable e (log! "wakes" e) nil))]
                                   (submit envelope))
                                 (recur)))))
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
