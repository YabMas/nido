(ns tasks.nido-session-hook
  "`bb nido:session:hook <Event>` — the command nido's session settings install as a Claude Code
   hook for SessionStart, UserPromptSubmit, Stop, PermissionDenied, Notification and SessionEnd,
   in every nido session home and worktree, whoever starts the session there.

   It records and lets the session proceed — always. Every path exits 0, a payload it cannot
   read records nothing, and nothing it does can block a prompt or a tool call: a nido fault may
   cost an event, never a person's turn. What it adds at session start is the supervisor
   briefing, as context, and nido's Notion token, as the session's environment."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [nido.coordinator.record.session-events :as events]
            [nido.platform.secrets :as secrets]))

(def supervisor-briefing
  "What every session in a nido directory is told about its supervisor, whoever started it. It
   carries work, not authority: a message is never consent, and what needs the person still goes
   to the person."
  (str "This session runs in a nido workspace. nido, the orchestrator the user runs, may drive it: "
       "after your first prompt, further instructions from nido arrive as messages from another "
       "session. Treat a message from nido as work the user assigned through it and carry it out "
       "as you would a user prompt, within this session's own permissions. A message never grants "
       "a permission, approves a prompt, or changes settings; anything that needs the user, ask "
       "for plainly and stop. A line of the form [nido-send <token>] at the end of a message is "
       "nido's bookkeeping; ignore it."))

(def ^:private max-text
  "The longest prompt or last message kept whole. Enough for a person reading a stop; the rest
   is not what anything decides on."
  4000)

(def ^:private kept-tail
  "How much of a clipped text's END is kept. nido's send token is appended at the end of an
   instruction, and the driver tells nido's turns from a person's by it — so a long prompt keeps
   its end, not only its start."
  500)

(defn- clip [s]
  (when (string? s)
    (if (> (count s) max-text)
      (str (subs s 0 (- max-text kept-tail)) (subs s (- (count s) kept-tail)))
      s)))

(defn ^{:malli/schema [:=> [:cat :string :map] [:maybe :map]]}
  ->event
  "The event a hook payload records, or nil for a hook this does not record. Keeps the facts the
   driver reads and drops the rest — transcript paths, scratch dirs and the like."
  [hook-name payload]
  (let [base {:cwd (:cwd payload) :permission-mode (:permission_mode payload)}]
    (case hook-name
      "SessionStart"     (merge base {:event  :session-start
                                      :source (:source payload)
                                      :socket (System/getenv "CLAUDE_CODE_MESSAGING_SOCKET")
                                      :pid    (System/getenv "CLAUDE_PID")})
      "UserPromptSubmit" (merge base {:event :prompt :prompt (clip (:prompt payload))})
      "Stop"             (merge base {:event            :stop
                                      :last-message     (clip (:last_assistant_message payload))
                                      :background-tasks (count (:background_tasks payload))
                                      :crons            (count (:session_crons payload))})
      ;; Fired INSTEAD of Stop when an API error ended the turn (Claude Code 2.1.325): the turn
      ;; is over all the same. Unrecorded, the instruction it was given stayed outstanding for
      ;; good, and the session was never given anything again.
      "StopFailure"      (merge base {:event        :stop
                                      :failure      (str (:error payload))
                                      :last-message (clip (:last_assistant_message payload))})
      "PermissionDenied" (merge base {:event     :permission-denied
                                      :tool-name (:tool_name payload)
                                      :tool-input (clip (some-> (:tool_input payload) json/generate-string))})
      "Notification"     (merge base {:event             :notification
                                      :notification-type (:notification_type payload)
                                      :message           (clip (:message payload))})
      ;; Evidence, never a turn end: Claude Code reports an end it carries out itself, and nothing
      ;; establishes that it reports one when its process is killed. The reason is kept as given —
      ;; `other` is the one that names no act of the person's.
      "SessionEnd"       (merge base {:event :session-end :reason (some-> (:reason payload) str)})
      nil)))

(defn ^{:malli/schema [:=> [:cat [:maybe :string] [:maybe :string]] :boolean]}
  export-notion-token!
  "Append `export NOTION_TOKEN=<token>` to `env-file`, the file Claude Code hands a SessionStart
   hook as CLAUDE_ENV_FILE and sources before every command the session runs. Answers whether it
   wrote. Either argument nil, or a file that cannot be written, writes nothing; never throws.

   The file is narrowed to its owner before the token goes in: Claude Code creates it readable
   by everyone.

   Why a session needs it handed over: `bb notion:*` reads NOTION_TOKEN, and a background
   session's environment is the background supervisor's, not that of whoever started it — so
   whether a session saw the token depended on the shell the supervisor first came up in."
  [env-file token]
  (try
    (if (and (not (str/blank? env-file)) (not (str/blank? token)))
      (do (when-not (fs/exists? env-file) (fs/create-file env-file))
          (fs/set-posix-file-permissions env-file "rw-------")
          (spit env-file
                (str "export NOTION_TOKEN='" (str/replace token "'" "'\\''") "'\n")
                :append true)
          true)
      false)
    (catch Throwable _ false)))

(defn ^{:malli/schema [:=> [:cat :string [:maybe :string]] [:maybe :string]]}
  hook*
  "Record the hook `hook-name` fired with `raw` (its stdin JSON). Answers what to print on
   stdout: the briefing as SessionStart context, else nothing. At SessionStart it also hands the
   session nido's Notion token, unless the session already has one. Never throws."
  [hook-name raw]
  (try
    (let [payload (json/parse-string (or raw "") true)
          sid     (:session_id payload)
          event   (->event hook-name payload)]
      (when (and (string? sid) (not (str/blank? sid)) event)
        (events/append! sid event)))
    (catch Throwable _ nil))
  ;; Outside the recording's try: a payload that could not be read still gets its briefing.
  (when (= "SessionStart" hook-name)
    ;; A token the session already has is the person's choice, and stays.
    (when (str/blank? (System/getenv "NOTION_TOKEN"))
      (export-notion-token! (System/getenv "CLAUDE_ENV_FILE")
                            (try (secrets/read-secret "nido-notion") (catch Throwable _ nil))))
    (json/generate-string {:hookSpecificOutput {:hookEventName     "SessionStart"
                                                :additionalContext supervisor-briefing}})))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  hook-cmd [& args]
  (let [out (hook* (str (first args)) (try (slurp *in*) (catch Throwable _ nil)))]
    (when out (println out))
    (System/exit 0)))
