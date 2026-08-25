(ns nido.coordinator.agent
  "Headless claude launcher (autonomous phase of a Run).

   See spec §Agent launch."
  (:require
   [nido.process :as nprocess]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.java.io :as jio]
   [nido.coordinator.state :as cstate]))

(defn- parse-event [^String line]
  (try (json/parse-string line keyword)
       (catch Exception _ nil)))

(defn- session-id-from [event]
  (when (and (= "system" (:type event))
             (= "init"   (:subtype event)))
    (:session_id event)))

(defn- result-event? [event]
  (= "result" (:type event)))

(defn- parse-budget-ms
  "Parse a budget string like '30m', '45m', '2h' into milliseconds.
   nil → no budget (treat as infinite)."
  [s]
  (when s
    (let [[_ n unit] (re-matches #"(\d+)([smhd])" s)]
      (when n
        (let [n  (Long/parseLong n)
              ms (case unit
                   "s" 1000
                   "m" 60000
                   "h" 3600000
                   "d" 86400000)]
          (* n ms))))))

(defn- build-cmd
  "Assemble the claude command vector. With :claude-session-id, the run is
   addressed by id: :resume? true CONTINUES that transcript (--resume) — a
   gate reply; otherwise it RECORDS under it (--session-id) — the first burst.
   first-message is the trailing positional argument."
  [{:keys [claude-bin first-message system-prompt claude-session-id resume?
           mcp-config add-dirs tools]}]
  (cond-> [claude-bin
           "--print"
           ;; Stream-json output requires --verbose per claude-code's
           ;; --print mode validation; without it claude refuses to run.
           "--verbose"
           "--output-format=stream-json"
           "--dangerously-skip-permissions"]
    ;; --resume is dormant until gate-reply turns start passing :resume? true;
    ;; reactivates the moment a caller resumes a parked agent with new input.
    (and claude-session-id resume?)       (into ["--resume" claude-session-id])
    (and claude-session-id (not resume?)) (into ["--session-id" claude-session-id])
    system-prompt                          (into ["--append-system-prompt" system-prompt])
    mcp-config                             (into ["--mcp-config" mcp-config])
    (seq add-dirs)                         (into (mapcat (fn [d] ["--add-dir" d]) add-dirs))
    ;; --tools "" disables all tools (report-only launches, e.g. the review
    ;; judge). "" is truthy in Clojure, so this fires exactly when :tools is
    ;; supplied. --tools is variadic; the `--` terminator below keeps the empty
    ;; value from consuming the prompt.
    tools                                  (into ["--tools" tools])
    ;; `--` terminates option parsing so the trailing prompt positional is never
    ;; swallowed by a preceding variadic flag. claude 2.x made --add-dir
    ;; (<directories...>) and --mcp-config (<configs...>) variadic; without the
    ;; terminator they consume first-message as another dir/config, leaving claude
    ;; with no prompt → "Input must be provided … when using --print" (exit 1).
    :always                                (into ["--" first-message])))

(defn launch!
  "Spawn claude headlessly for a Run. Blocks until the agent exits or the
   wall-clock budget is exceeded.

   :claude-session-id (opt) — pre-generated session id; passed as --session-id
   (record a new transcript) or --resume (continue the recorded one) per
   :resume?, and returned verbatim so the caller can persist it BEFORE launch
   (surviving interruption). When omitted, the id is parsed from the
   stream-json init event.

   opts:
     :run-id        — run id (used to locate run-dir for agent.log path)
     :cwd           — working directory the agent runs in (worktree)
     :first-message — message passed as the positional argument
     :system-prompt — optional --append-system-prompt content
     :claude-bin    — path/name of the claude binary (override for tests)
     :env           — extra env vars to merge into the child's environment
     :budget        — string like \"30m\" / \"2h\". nil → no budget.
     :resume?       — optional; nil/false records a new transcript under
                      --session-id, true continues the recorded one via --resume
                      (a gate reply). Requires :claude-session-id.

   Returns:
     {:exit-code <int> :claude-session-id <str-or-nil> :timed-out? <bool>
      :num-turns <int-or-nil> :result-error? <bool> :result-text <str-or-nil>}

   :num-turns / :result-error? / :result-text are pulled from claude's final
   stream-json `result` event. A clean exit (exit 0) with :num-turns 0 means
   the agent did NO work — e.g. claude rejected the launch with
   \"Unknown command: /<skill>\". Callers use this to distinguish a real
   completion from a no-op exit (which must not be treated as success)."
  [{:keys [run-id cwd first-message system-prompt claude-bin env budget claude-session-id resume?
           mcp-config add-dirs tools err-file]
    :or   {claude-bin "claude"}}]
  (let [log-path  (cstate/run-agent-log run-id)
        cmd       (build-cmd {:claude-bin claude-bin :first-message first-message
                              :system-prompt system-prompt :claude-session-id claude-session-id
                              :resume? resume? :mcp-config mcp-config :add-dirs add-dirs
                              :tools tools})
        proc      (p/process cmd (cond-> {:dir cwd
                                          :env (merge (into {} (System/getenv)) (or env {}))
                                          ;; Close stdin so claude doesn't wait for input
                                          ;; (it emits a "no stdin in 3s" warning otherwise).
                                          :in  ""
                                          :out :stream
                                          :err (if err-file :write :inherit)
                                          :shutdown nil}
                                   err-file (assoc :err-file (jio/file err-file))))
        session   (atom nil)
        result-ev (atom nil)
        budget-ms (parse-budget-ms budget)
        timed-out (atom false)
        timer     (when budget-ms
                    (future
                      (Thread/sleep budget-ms)
                      (when (.isAlive ^Process (:proc proc))
                        (reset! timed-out true)
                        (p/destroy proc)
                        ;; Give claude 10s to exit on SIGTERM, then SIGKILL.
                        (Thread/sleep 10000)
                        (when (.isAlive ^Process (:proc proc))
                          (p/destroy-tree proc)))))]
    (nprocess/with-child-registered
     (:proc proc)
     (fn []
      (try
      (with-open [w (jio/writer log-path :append true)]
        (with-open [r (jio/reader (:out proc))]
          (doseq [line (line-seq r)]
            (.write w line) (.write w "\n") (.flush w)
            (when-let [event (parse-event line)]
              (when-let [sid (session-id-from event)]
                (reset! session sid))
              (when (result-event? event)
                (reset! result-ev event))))))
      (finally
        (when timer (future-cancel timer))))
    (let [exit (:exit @proc)
          rev  @result-ev]
      {:exit-code         exit
       ;; Prefer the caller-supplied id (deterministic, already persisted);
       ;; fall back to the id parsed from the init event.
       :claude-session-id (or claude-session-id @session)
       :timed-out?        @timed-out
       :num-turns         (:num_turns rev)
       :result-error?     (boolean (:is_error rev))
       :result-text       (:result rev)})))))
