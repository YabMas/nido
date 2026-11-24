(ns nido.session.chat
  "A conversation with a coding agent running in a session's worktree, spoken to over ACP.

   HIDES the conversation's lifetime: which adapter command it runs, the permission mode and deny
   list it starts under, where its ACP session id is kept, when its process is started, resumed or
   stopped, and how a change reaches whoever is watching.

   A chat is named by `{:project :session :name}`, and any map holding those three keys names it;
   the rest of the map is how to start it (`:instructions`, appended to the agent's system prompt;
   `:ready!`, a function of the chat that brings its session up its caller's way before each agent
   start, in place of this namespace's own bringing it up on the project's defaults — what it
   throws is the conversation's error; or `:dir`, the directory the agent runs in instead of the
   session's worktree — or a function of the chat giving it, called before each agent start, whose
   throw is the conversation's error — bringing no session up) and what to do when a turn ends (`:on-turn-end`, a
   function of the chat). The only thing kept on
   disk is the ACP session id. The transcript lives in this process and is rebuilt when it is not
   there: the conversation is loaded again by id, and its history replays through the same fold.
   So a dashboard restart costs a conversation its replay time and nothing else.

   The agent runs with permissions skipped, as every nido session does, and is denied the commands
   that push, move main or record a landing: nothing a chat sends lands. A request for permission
   the agent still raises becomes a transcript item, answered with `answer-permission!`.

   One turn at a time: a send while a turn runs, or while an earlier send is still starting its
   agent, is refused, not queued. An agent process with no turn running is stopped after `idle-ms`, and the next send
   loads the conversation again.

   A conversation ends with `reset!`: its process is stopped and its id forgotten, so the next send
   starts a new one. Only the process a chat currently holds speaks for it — what an earlier
   process says, its exit included, and what a turn on it does, its `:on-turn-end` included, is
   ignored — so a stopped conversation cannot reach the one that replaced it."
  (:refer-clojure :exclude [reset!])
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [nido.acp.client :as acp]
            [nido.acp.transcript :as transcript]
            [nido.platform.core :as core]
            [nido.session.lifecycle :as lifecycle]))

(def ^:private adapter-command
  "The ACP adapter, pinned. `npx` finds it in the npm cache after the first start; the daemon's
   PATH carries the node shims it needs."
  ["npx" "-y" "@agentclientprotocol/claude-agent-acp@0.107.0"])

(def ^:private denied-tools
  "What a chat's agent may not run, whatever its permission mode: the commands that push, move
   main, or land or record a landing. `bb nido:land` pushes from a process of its own, out of reach
   of the push rules, so it is denied by name; its rules match the bare verb and the verb with
   arguments but not `bb nido:land:check`, which only reads. Claude Code applies a deny rule before
   it consults the mode, so these hold with permissions skipped."
  ["Bash(jj git push:*)" "Bash(git push:*)" "Bash(command git push:*)"
   "Bash(jj bookmark set main:*)" "Bash(jj bookmark move main:*)"
   "Bash(bb nido:land:record:*)" "Bash(nido land:record:*)"
   "Bash(bb nido:land)" "Bash(bb nido:land :*)" "Bash(bb nido:recovery:land:*)"])

(def ^:private idle-ms
  "How long an agent process with no turn running is kept before it is stopped."
  (* 30 60 1000))

(defonce ^:private chats
  ;; chat key -> {:transcript :conn :acp-id :owner :subs {token f} :last-used ms :loading? bool
  ;;             :turn token :loads n :held {token [Image]}}. :owner is the token of the process the
  ;; chat holds, set before it connects; :turn is the token of the send whose turn the chat is busy
  ;; with, from the send's acceptance to its turn's end; :loads counts the loads that replaced the
  ;; transcript. :held keeps the images a handoff carries answerable by `image` while the
  ;; transcript may lack them — a send's, from its acceptance to its turn's end, under its turn;
  ;; the replaced transcript's, while a load replays it, under the loading process's owner.
  (atom {}))

(defonce ^:private start-locks (atom {}))

(defonce ^:private notify-locks (atom {}))

(defn- chat-key [chat] (select-keys chat [:project :session :name]))

(defn- id-file [{:keys [project session name]}]
  (str (fs/path (core/nido-home) "chats" (str project) session (str name ".edn"))))

(defn- read-acp-id
  "The chat's kept ACP session id, or nil. A reset deletes the file without waiting for readers,
   so one gone between looking and reading is no id, not a failure."
  [k]
  (try (:acp-session-id (edn/read-string (slurp (id-file k))))
       (catch java.io.FileNotFoundException _ nil)
       (catch java.nio.file.NoSuchFileException _ nil)))

(defn- write-acp-id! [k id]
  (let [f (id-file k)]
    (fs/create-dirs (fs/parent f))
    (spit f (pr-str {:acp-session-id id}))))

(defn- state [k] (get @chats k))

(declare published)

(defn- notify!
  "Tell every subscriber the chat's transcript as it stands. Deliveries for one chat run one at a
   time, each reading the state once it holds the chat's turn, so no subscriber is told an older
   transcript after a newer one — a reset's empty log is never followed by the log it discarded."
  [k]
  (locking (get (swap! notify-locks update k #(or % (Object.))) k)
    (let [{:keys [subs loading?] :as s} (state k)
          transcript (published s)]
      (when-not loading?
        (doseq [f (vals subs)] (try (f transcript) (catch Throwable _ nil)))))))

(def ^:private ^:dynamic *owner*
  "The process on whose behalf the running code changes chat state: every change is applied only
   while the chat still holds it. Bound for a process's messages and for everything a turn does
   once its process is known — the caller's `:on-turn-end` included, so what that writes through
   `note!` or `note-error!` cannot reach a conversation that replaced the turn's. `::any` outside."
  ::any)

(defn- change!
  "Apply `f` to the chat's state — unless `*owner*` names a process the chat no longer holds,
   decided in the same swap that applies it — and tell subscribers when the transcript changed.
   True when applied."
  [k f]
  (let [owner *owner*
        [before after] (swap-vals! chats (fn [m] (if (or (= ::any owner)
                                                         (identical? owner (get-in m [k :owner])))
                                                   (update m k f)
                                                   m)))]
    (when-not (and (identical? (get-in before [k :transcript]) (get-in after [k :transcript]))
                   (identical? (get-in before [k :turn]) (get-in after [k :turn])))
      (notify! k))
    (not (identical? before after))))

(defn- fold! [k event]
  (change! k (fn [s] (update s :transcript #(transcript/fold (or % (transcript/empty-transcript)) event)))))

(defn- working?
  "Whether the chat `s` is busy with a turn: a send was accepted and its turn has not ended. Only
   that send sets and clears it — nothing folded into the transcript does, so an error noted from
   outside the turn cannot end it. While it is set nothing else may start or end a conversation —
   no second send, no reset — so whatever the send writes lands on the conversation it was sent to."
  [s]
  (some? (:turn s)))

(defn- published
  "The chat's transcript as subscribers and readers see it: busy exactly while the chat is
   `working?`, whatever the items folded into it last said."
  [s]
  (assoc (or (:transcript s) (transcript/empty-transcript)) :busy? (working? s)))

(defn- on-message
  "A message from the process `owner` started. Ignored once the chat holds another process, or
   none — a stopped conversation never speaks for the one after it."
  [k owner msg]
  (binding [*owner* owner]
    (case (:method msg)
      "_nido/exited" (change! k (fn [s]
                                  (cond-> (dissoc s :conn :acp-id :owner)
                                    (working? s)
                                    (update :transcript transcript/fold
                                            {:local :error :text "The agent process stopped."}))))
      (fold! k msg))))

(defn- session-meta [chat]
  (cond-> {:claudeCode {:options {:disallowedTools denied-tools}}}
    (:instructions chat) (assoc :systemPrompt {:append (:instructions chat)})))

(defn- worktree!
  "Where the chat's agent runs: the chat's `:dir` when it has one — a directory, or a function of
   the chat giving one; giving none fails the start, bringing no session up — else its session's
   worktree, with the session brought up first: by the
   chat's `:ready!` when it has one, else by this namespace when the session is down or does not
   exist."
  [{:keys [project session ready! dir] :as chat}]
  (if dir
    (or (if (fn? dir) (dir chat) dir)
        (throw (ex-info "This chat has no directory to run its agent in." {:chat (select-keys chat [:project :session :name])})))
    (let [opts {:project (name project)}]
      (cond
        ready!                                     (ready! chat)
        (not (lifecycle/runtime-of session opts))  (lifecycle/up! session opts))
      (:wt-path (lifecycle/session-coords session opts)))))

(defn- start-lock [k] (get (swap! start-locks update k #(or % (Object.))) k))

(defn- failed!
  "Put the failure `e` of a start in the chat's transcript and throw it on, marked as told. Called
   only under the chat's start lock, by the start that failed, so it lands on the conversation
   whose process failed: no other start can come between the failure and its telling."
  [k e]
  (let [text (or (ex-message e) (str e))]
    (fold! k {:local :error :text text})
    (throw (ex-info text {::told true} e))))

(defn- untold
  "A failure for its catcher to tell, or nil when the start that failed has told it already."
  [e]
  (when-not (::told (ex-data e)) e))

(defn- ensure-agent!
  "The chat's connection, ACP session id and the token of the process holding them, starting the
   agent — and loading the conversation, when one was started before — if no process is running.
   Serialised per chat, so two sends cannot start two agents, and a reset waits for a start to
   finish. With `load-only?`, a chat with no conversation kept by id starts nothing and answers
   nil. A process that exits before it is held is not held, and the start throws. A start that
   throws has already put its failure in the transcript, under the lock (`failed!`)."
  ([chat] (ensure-agent! chat false))
  ([chat load-only?]
   (let [k (chat-key chat)]
     (locking (start-lock k)
      (try
       (or (when-let [{:keys [conn acp-id owner]} (state k)] (when conn [conn acp-id owner]))
           (when (or (not load-only?) (read-acp-id k))
             (let [cwd    (worktree! chat)
                   owner  (Object.)
                   _      (swap! chats update k assoc :owner owner)
                   conn   (acp/connect! {:command adapter-command :dir cwd
                                         :on-message #(on-message k owner %)})
                   meta   (session-meta chat)
                   known  (read-acp-id k)
                   acp-id (if known
                            ;; The replay is the whole conversation, so it is folded into an
                            ;; empty transcript, and subscribers hear once, when it is done.
                            (do (swap! chats update k #(-> %
                                                           (assoc-in [:held owner]
                                                                     (into [] (mapcat :images) (:items (:transcript %))))
                                                           (assoc :loading? true
                                                                  :transcript (transcript/empty-transcript))
                                                           (update :loads (fnil inc 0))))
                                (try (acp/load-session! conn known {:cwd cwd :meta meta}) known
                                     (finally (swap! chats update k #(-> % (assoc :loading? false)
                                                                         (update :held dissoc owner)))
                                              (notify! k))))
                            (doto (acp/new-session! conn {:cwd cwd :meta meta})
                              (->> (write-acp-id! k))))]
               (acp/set-mode! conn acp-id "bypassPermissions")
               ;; A process that exited while it started is no longer the chat's: it is not held.
               (let [[_ after] (swap-vals! chats (fn [m] (if (identical? owner (get-in m [k :owner]))
                                                          (update m k assoc :conn conn :acp-id acp-id)
                                                          m)))]
                 (when-not (identical? conn (get-in after [k :conn]))
                   (throw (ex-info "The agent process stopped." {}))))
               [conn acp-id owner])))
       (catch Throwable e (failed! k e)))))))

(defn- reap-idle! []
  (let [now   (System/currentTimeMillis)
        idle? (fn [{:keys [last-used] :as s}]
                (and (not (working? s)) (> (- now (or last-used now)) idle-ms)))]
    (doseq [[k {:keys [conn]}] @chats
            :when conn]
      ;; Decided in the swap: the connection seen above may since have been reset and replaced.
      (let [[before after] (swap-vals! chats (fn [m] (let [s (get m k)]
                                                       (if (and (identical? conn (:conn s)) (idle? s))
                                                         (update m k dissoc :conn :acp-id :owner)
                                                         m))))]
        (when-not (identical? before after)
          (acp/close! conn))))))

(defonce ^:private reaper
  (delay (doto (Thread. #(loop [] (Thread/sleep 60000) (try (reap-idle!) (catch Throwable _ nil)) (recur))
                        "chat-reaper")
           (.setDaemon true)
           (.start))))

(defn ^{:malli/schema [:=> [:cat :map] :Conversation]}
  transcript
  "The chat's transcript as it stands in this process: empty for a conversation not yet opened
   here, even when one exists on disk — the first send loads it."
  [chat]
  (published (state (chat-key chat))))

(defn ^{:malli/schema [:=> [:cat :map :string] [:maybe :map]]}
  image
  "The Image with `digest` the chat's transcript shows, or one a handoff holds while the transcript
   lacks it — a send's images from its acceptance to its turn's end, and the images of a
   transcript a load is replaying — or nil. Nothing outlives its handoff: what the conversation
   no longer shows is answered only while that handoff runs."
  [chat digest]
  (let [s (state (chat-key chat))]
    (some #(when (= digest (:digest %)) %)
          (concat (mapcat :images (:items (:transcript s))) (mapcat val (:held s))))))

(defn ^{:malli/schema [:=> [:cat :map] :boolean]}
  started?
  "Whether the chat's conversation has been started — here, or by an earlier process whose
   conversation is kept by id."
  [chat]
  (boolean (or (:conn (state (chat-key chat))) (read-acp-id (chat-key chat)))))

(defn ^{:malli/schema [:=> [:cat :map :string [:sequential :map] [:sequential :map]] :any]}
  send!
  "Send the person's `text`, with the references it came with — each `{:label :detail}`, in the
   order the person numbered them, possibly none — and its `images`, each a trusted
   `{:mime :data :digest}`, possibly none; return at once. The message, the agent's reply and the
   turn's end each reach subscribers as they happen. Images go only to an agent that advertised it
   accepts them; to any other the turn ends with an error saying so, and nothing is sent. Refused — nil, and nothing
   changes — while a turn is running or a send accepted before it has not begun its turn."
  [chat text refs images]
  (let [k    (chat-key chat)
        turn (Object.)
        ;; Accepted with its images held, so they stay answerable from before the caller lets go
        ;; of them until the turn ends, whatever the transcript holds meanwhile.
        [before] (swap-vals! chats (fn [m] (if (working? (get m k))
                                             m
                                             (-> m (assoc-in [k :turn] turn)
                                                 (assoc-in [k :held turn] (vec images))))))]
    (when-not (working? (get before k))
      ;; Everything after the send is accepted runs in the future, inside the one scope whose
      ;; `finally` ends the turn, so nothing between its start and its end can skip the end.
      (future
        ;; While the turn is the chat's no reset and no other send is taken, so a failure to start
        ;; lands on this conversation; from the moment its process is known, everything the turn
        ;; does — the message, its end, the caller's hook — speaks only while that process is held.
        (let [owner
              (try
                (when-let [[conn acp-id owner]
                           (try @reaper
                                (swap! chats update k assoc :last-used (System/currentTimeMillis))
                                (let [message {:local :user :text text :refs (vec refs)
                                               :images (vec images)
                                               :at (System/currentTimeMillis)}
                                      ;; Folded between loads, never inside one; a load that
                                      ;; replaces the transcript after it — this send's own start
                                      ;; or anyone's — is seen by its count, and the message goes
                                      ;; in again after the history it replayed.
                                      loads   (locking (start-lock k)
                                                (fold! k message)
                                                (:loads (state k)))
                                      [_ _ owner :as started] (ensure-agent! chat)]
                                  (locking (start-lock k)
                                    ;; Told as the turn's process: when another start has
                                    ;; replaced it since, the failure is that process's, not
                                    ;; the conversation's now.
                                    (binding [*owner* owner]
                                      (when (and (not= loads (:loads (state k)))
                                                 (not (fold! k message)))
                                        (failed! k (ex-info "The agent process stopped." {})))))
                                  started)
                                (catch Throwable e
                                  (when-let [e (untold e)]
                                    (fold! k {:local :error :text (or (ex-message e) (str e))}))
                                  nil))]
                  (binding [*owner* owner]
                    (try
                      (if (and (seq images) (not (acp/accepts-images? conn)))
                        (fold! k {:local :error :text "This agent does not accept images; nothing was sent."})
                        (do @(acp/prompt! conn acp-id (transcript/prompt-blocks text refs images))
                            (fold! k {:local :turn-ended})))
                      (catch Throwable e
                        (fold! k {:local :error :text (or (ex-message e) (str e))})))
                    (change! k #(assoc % :last-used (System/currentTimeMillis))))
                  owner)
                (finally
                  (binding [*owner* ::any]
                    (change! k #(cond-> (update % :held dissoc turn)
                                   (identical? turn (:turn %)) (dissoc :turn))))))]
          (when owner
            (binding [*owner* owner]
              (when-let [f (:on-turn-end chat)]
                (try (f chat) (catch Throwable _ nil)))))))
      true)))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  cancel!
  "Ask the agent to stop the running turn. The turn then ends as it would have, with what it had
   done so far."
  [chat]
  (let [{:keys [conn acp-id]} (state (chat-key chat))]
    (when conn (acp/cancel! conn acp-id))))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  reset!
  "End the chat's conversation, so the next send starts a new one: stop its agent, forget its ACP
   session id, and empty its transcript — notes and errors put in from outside a turn included —
   telling every subscriber. True when done; refused — nil, and nothing changes — while a turn
   runs or a send accepted before it has not begun its turn. Starts nothing: no agent runs for
   the chat again until the next send."
  [chat]
  (let [k (chat-key chat)]
    (locking (start-lock k)
      (let [[before] (swap-vals! chats (fn [m]
                                         (if (working? (get m k))
                                           m
                                           (update m k #(-> %
                                                            (dissoc :conn :acp-id :owner)
                                                            (assoc :transcript (transcript/empty-transcript)
                                                                   :loading? false))))))
            {:keys [conn]} (get before k)]
        (when-not (working? (get before k))
          (fs/delete-if-exists (id-file k))
          (when conn (try (acp/close! conn) (catch Throwable _ nil)))
          (notify! k)
          true)))))

(defn ^{:malli/schema [:=> [:cat :map :string :string] :any]}
  answer-permission!
  "Answer the permission request held by transcript item `item-id` with option `option-id`. A
   request already answered, or one whose agent has gone, is left as it is."
  [chat item-id option-id]
  (let [k (chat-key chat)
        {:keys [conn transcript]} (state k)
        item (some #(when (= item-id (:id %)) %) (:items transcript))]
    (when (and conn item (= :permission (:kind item)) (nil? (:answer item)))
      (acp/respond! conn (:request-id item) {:outcome {:outcome "selected" :optionId option-id}})
      (fold! k {:local :answered :item-id item-id :option-id option-id}))))

(defn ^{:malli/schema [:=> [:cat :map :string] :any]}
  note-error!
  "Put an error its caller met on the chat's behalf — after a turn, in work the turn set off —
   into the transcript as an item under the latest turn, so every subscriber shows it and a
   page opened later still does. Like every transcript item it lasts as long as this process's
   transcript: a conversation loaded again replays the agent's history, which never held it."
  [chat text]
  (fold! (chat-key chat) {:local :error :text text}))

(defn ^{:malli/schema [:=> [:cat :map :string] :any]}
  note!
  "Put a note about something done to the chat's worktree from outside a turn — a change published
   or reverted — into the transcript, so every subscriber shows it. It does not reach the agent,
   and like every transcript item it lasts as long as this process's transcript."
  [chat text]
  (fold! (chat-key chat) {:local :note :text text}))

(defn ^{:malli/schema [:=> [:cat :map :any] :any]}
  subscribe!
  "Call `f` with the chat's transcript each time it changes, from whichever thread changed it.
   Returns a function that unsubscribes. Subscribing to a conversation this process has not
   opened yet loads it, so its history arrives as one change."
  [chat f]
  (let [k     (chat-key chat)
        token (Object.)]
    (swap! chats update-in [k :subs] assoc token f)
    ;; A conversation started before this process is loaded as soon as someone looks at it, so
    ;; its history is on the panel before the next message rather than after it.
    (when (and (not (:conn (state k))) (empty? (:items (transcript chat))) (read-acp-id k))
      @reaper
      (swap! chats update k assoc :last-used (System/currentTimeMillis))
      ;; A failed load tells its failure itself, under the start lock (`failed!`).
      (future (try (ensure-agent! chat true) (catch Throwable _ nil))))
    (fn [] (swap! chats update-in [k :subs] dissoc token))))
