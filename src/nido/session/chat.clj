(ns nido.session.chat
  "A conversation with a coding agent running in a session's worktree, spoken to over ACP.

   HIDES the conversation's lifetime: which adapter command it runs, the permission mode and deny
   list it starts under, where its ACP session id is kept, when its process is started, resumed or
   stopped, and how a change reaches whoever is watching.

   A chat is named by `{:project :session :name}`, and any map holding those three keys names it;
   the rest of the map is how to start it (`:instructions`, appended to the agent's system prompt)
   and what to do when a turn ends (`:on-turn-end`, a function of the chat). The only thing kept on
   disk is the ACP session id. The transcript lives in this process and is rebuilt when it is not
   there: the conversation is loaded again by id, and its history replays through the same fold.
   So a dashboard restart costs a conversation its replay time and nothing else.

   The agent runs with permissions skipped, as every nido session does, and is denied the commands
   that push, move main or record a landing: nothing a chat sends lands. A request for permission
   the agent still raises becomes a transcript item, answered with `answer-permission!`.

   One turn at a time: a send while a turn runs is refused by the transcript (`:busy?`), not
   queued. An agent process with no turn running is stopped after `idle-ms`, and the next send
   loads the conversation again."
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
   main, or record a landing. Claude Code applies a deny rule before it consults the mode, so
   these hold with permissions skipped."
  ["Bash(jj git push:*)" "Bash(git push:*)" "Bash(command git push:*)"
   "Bash(jj bookmark set main:*)" "Bash(jj bookmark move main:*)"
   "Bash(bb nido:land:record:*)" "Bash(nido land:record:*)"])

(def ^:private idle-ms
  "How long an agent process with no turn running is kept before it is stopped."
  (* 30 60 1000))

(defonce ^:private chats
  ;; chat key -> {:transcript :conn :acp-id :subs {token f} :last-used ms :loading? bool}
  (atom {}))

(defonce ^:private start-locks (atom {}))

(defn- chat-key [chat] (select-keys chat [:project :session :name]))

(defn- id-file [{:keys [project session name]}]
  (str (fs/path (core/nido-home) "chats" (str project) session (str name ".edn"))))

(defn- read-acp-id [k]
  (let [f (id-file k)]
    (when (fs/exists? f) (:acp-session-id (edn/read-string (slurp f))))))

(defn- write-acp-id! [k id]
  (let [f (id-file k)]
    (fs/create-dirs (fs/parent f))
    (spit f (pr-str {:acp-session-id id}))))

(defn- state [k] (get @chats k))

(defn- notify! [k]
  (let [{:keys [transcript subs loading?]} (state k)]
    (when-not loading?
      (doseq [f (vals subs)] (try (f transcript) (catch Throwable _ nil))))))

(defn- fold! [k event]
  (swap! chats update-in [k :transcript] #(transcript/fold (or % (transcript/empty-transcript)) event))
  (notify! k))

(defn- on-message [k msg]
  (case (:method msg)
    "_nido/exited" (let [busy? (get-in (state k) [:transcript :busy?])]
                     (swap! chats update k dissoc :conn :acp-id)
                     (when busy? (fold! k {:local :error :text "The agent process stopped."})))
    (fold! k msg)))

(defn- session-meta [chat]
  (cond-> {:claudeCode {:options {:disallowedTools denied-tools}}}
    (:instructions chat) (assoc :systemPrompt {:append (:instructions chat)})))

(defn- worktree!
  "The chat's session's worktree, bringing the session up first when it is down or does not exist."
  [{:keys [project session]}]
  (let [opts {:project (name project)}]
    (when-not (lifecycle/runtime-of session opts)
      (lifecycle/up! session opts))
    (:wt-path (lifecycle/session-coords session opts))))

(defn- ensure-agent!
  "The chat's connection and ACP session id, starting the agent — and loading the conversation,
   when one was started before — if no process is running. Serialised per chat, so two sends
   cannot start two agents."
  [chat]
  (let [k    (chat-key chat)
        lock (get (swap! start-locks update k #(or % (Object.))) k)]
    (locking lock
      (or (when-let [{:keys [conn acp-id]} (state k)] (when conn [conn acp-id]))
          (let [cwd    (worktree! chat)
                conn   (acp/connect! {:command adapter-command :dir cwd
                                      :on-message #(on-message k %)})
                meta   (session-meta chat)
                known  (read-acp-id k)
                acp-id (if known
                         ;; The replay is the whole conversation, so it is folded into an
                         ;; empty transcript, and subscribers hear once, when it is done.
                         (do (swap! chats update k assoc :loading? true
                                    :transcript (transcript/empty-transcript))
                             (try (acp/load-session! conn known {:cwd cwd :meta meta}) known
                                  (finally (swap! chats update k assoc :loading? false)
                                           (notify! k))))
                         (doto (acp/new-session! conn {:cwd cwd :meta meta})
                           (->> (write-acp-id! k))))]
            (acp/set-mode! conn acp-id "bypassPermissions")
            (swap! chats update k assoc :conn conn :acp-id acp-id)
            [conn acp-id])))))

(defn- reap-idle! []
  (let [now (System/currentTimeMillis)]
    (doseq [[k {:keys [conn transcript last-used]}] @chats
            :when (and conn (not (:busy? transcript))
                       (> (- now (or last-used now)) idle-ms))]
      (swap! chats update k dissoc :conn :acp-id)
      (acp/close! conn))))

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
  (or (:transcript (state (chat-key chat))) (transcript/empty-transcript)))

(defn ^{:malli/schema [:=> [:cat :map] :boolean]}
  started?
  "Whether the chat's conversation has been started — here, or by an earlier process whose
   conversation is kept by id."
  [chat]
  (boolean (or (:conn (state (chat-key chat))) (read-acp-id (chat-key chat)))))

(defn ^{:malli/schema [:=> [:cat :map :string [:maybe :string]] :any]}
  send!
  "Send the person's `text`, with the `context` it came with (or nil), and return at once. The
   message, the agent's reply and the turn's end each reach subscribers as they happen. Refused —
   nil, and nothing changes — while a turn is running."
  [chat text context]
  (let [k (chat-key chat)]
    (when-not (:busy? (transcript chat))
      @reaper
      (swap! chats update k assoc :last-used (System/currentTimeMillis))
      ;; A load replays history into a fresh transcript, so the message goes in after it.
      (let [replays? (and (not (:conn (state k))) (read-acp-id k))]
        (when-not replays? (fold! k {:local :user :text text :context context}))
        (future
          (try
            (let [[conn acp-id] (ensure-agent! chat)]
              (when replays? (fold! k {:local :user :text text :context context}))
              @(acp/prompt! conn acp-id (transcript/prompt-text text context))
              (fold! k {:local :turn-ended}))
            (catch Throwable e
              (fold! k {:local :error :text (or (ex-message e) (str e))})))
          (swap! chats update k assoc :last-used (System/currentTimeMillis))
          (when-let [f (:on-turn-end chat)]
            (try (f chat) (catch Throwable _ nil))))
        true))))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  cancel!
  "Ask the agent to stop the running turn. The turn then ends as it would have, with what it had
   done so far."
  [chat]
  (let [{:keys [conn acp-id]} (state (chat-key chat))]
    (when conn (acp/cancel! conn acp-id))))

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
      (future (try (ensure-agent! chat)
                   (catch Throwable e (fold! k {:local :error :text (or (ex-message e) (str e))})))))
    (fn [] (swap! chats update-in [k :subs] dissoc token))))
