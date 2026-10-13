(ns tasks.nido-session-hook
  "`bb nido:session:hook <Event>` — the command nido's session settings install as a Claude Code
   hook for SessionStart, UserPromptSubmit, Stop, PermissionDenied and Notification, in every
   nido session home and worktree, whoever starts the session there.

   It records and lets the session proceed — always. Every path exits 0, a payload it cannot
   read records nothing, and nothing it does can block a prompt or a tool call: a nido fault may
   cost an event, never a person's turn. The one thing it adds is the supervisor briefing, as
   context at session start."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [nido.coordinator.record.session-events :as events]))

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
      "PermissionDenied" (merge base {:event     :permission-denied
                                      :tool-name (:tool_name payload)
                                      :tool-input (clip (some-> (:tool_input payload) json/generate-string))})
      "Notification"     (merge base {:event             :notification
                                      :notification-type (:notification_type payload)
                                      :message           (clip (:message payload))})
      nil)))

(defn ^{:malli/schema [:=> [:cat :string [:maybe :string]] [:maybe :string]]}
  hook*
  "Record the hook `hook-name` fired with `raw` (its stdin JSON). Answers what to print on
   stdout: the briefing as SessionStart context, else nothing. Never throws."
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
    (json/generate-string {:hookSpecificOutput {:hookEventName     "SessionStart"
                                                :additionalContext supervisor-briefing}})))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  hook-cmd [& args]
  (let [out (hook* (str (first args)) (try (slurp *in*) (catch Throwable _ nil)))]
    (when out (println out))
    (System/exit 0)))
