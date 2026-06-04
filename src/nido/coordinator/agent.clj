(ns nido.coordinator.agent
  "Headless claude launcher (autonomous phase of a Run).

   See spec §Agent launch."
  (:require
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
  "Assemble the claude command vector. When :claude-session-id is supplied,
   pass `--session-id <uuid>` so claude records the transcript under a known
   id (the resume shim then always resolves it). first-message is the trailing
   positional argument."
  [{:keys [claude-bin first-message system-prompt claude-session-id]}]
  (cond-> [claude-bin
           "--print"
           ;; Stream-json output requires --verbose per claude-code's
           ;; --print mode validation; without it claude refuses to run.
           "--verbose"
           "--output-format=stream-json"
           "--dangerously-skip-permissions"]
    claude-session-id (into ["--session-id" claude-session-id])
    system-prompt     (into ["--append-system-prompt" system-prompt])
    :always           (conj first-message)))

(defn launch!
  "Spawn claude headlessly for a Run. Blocks until the agent exits or the
   wall-clock budget is exceeded.

   :claude-session-id (opt) — pre-generated session id; passed as --session-id
   and returned verbatim so the caller can persist it BEFORE launch (surviving
   interruption). When omitted, the id is parsed from the stream-json init event.

   opts:
     :run-id        — run id (used to locate run-dir for agent.log path)
     :cwd           — working directory the agent runs in (worktree)
     :first-message — message passed as the positional argument
     :system-prompt — optional --append-system-prompt content
     :claude-bin    — path/name of the claude binary (override for tests)
     :env           — extra env vars to merge into the child's environment
     :budget        — string like \"30m\" / \"2h\". nil → no budget.

   Returns:
     {:exit-code <int> :claude-session-id <str-or-nil> :timed-out? <bool>}"
  [{:keys [run-id cwd first-message system-prompt claude-bin env budget claude-session-id]
    :or   {claude-bin "claude"}}]
  (let [log-path  (cstate/run-agent-log run-id)
        cmd       (build-cmd {:claude-bin claude-bin :first-message first-message
                              :system-prompt system-prompt :claude-session-id claude-session-id})
        proc      (p/process cmd {:dir cwd
                                  :env (merge (into {} (System/getenv)) (or env {}))
                                  ;; Close stdin so claude doesn't wait for input
                                  ;; (it emits a "no stdin in 3s" warning otherwise).
                                  :in  ""
                                  :out :stream
                                  :err :inherit
                                  :shutdown nil})
        session   (atom nil)
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
    (try
      (with-open [w (jio/writer log-path :append true)]
        (with-open [r (jio/reader (:out proc))]
          (doseq [line (line-seq r)]
            (.write w line) (.write w "\n") (.flush w)
            (when-let [event (parse-event line)]
              (when-let [sid (session-id-from event)]
                (reset! session sid))))))
      (finally
        (when timer (future-cancel timer))))
    (let [exit (:exit @proc)]
      {:exit-code         exit
       ;; Prefer the caller-supplied id (deterministic, already persisted);
       ;; fall back to the id parsed from the init event.
       :claude-session-id (or claude-session-id @session)
       :timed-out?        @timed-out})))
