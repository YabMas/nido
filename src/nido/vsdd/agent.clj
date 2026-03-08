(ns nido.vsdd.agent
  "Spawns claude subprocesses for VSDD agent roles.
   Parses stream-json output for progress display and result capture.

   Supports two invocation modes:
     1. Project agent: --agent <name> (resolves .claude/agents/<name>.md in working-dir)
     2. Nido-managed: --append-system-prompt <content> + --tools <list>"
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- format-stream-line
  "Parse a stream-json line into a human-readable string, or nil to skip."
  [line agent-name]
  (try
    (let [evt (json/parse-string line true)]
      (case (:type evt)
        "assistant"
        (let [content (get-in evt [:message :content])]
          (->> content
               (keep (fn [block]
                       (case (:type block)
                         "text"     (str "  [" agent-name "] " (:text block))
                         "tool_use" (str "  [" agent-name "] -> " (:name block))
                         nil)))
               (str/join "\n")
               not-empty))

        "result"
        (str "  [" agent-name "] done")

        nil))
    (catch Exception _ nil)))

(defn- extract-session-id
  "Extract the session ID from stream-json events."
  [line]
  (try
    (let [evt (json/parse-string line true)]
      (when (= "system" (:type evt))
        (get-in evt [:session_id])))
    (catch Exception _ nil)))

(defn- read-stream
  "Read stream-json from a process, printing progress and collecting result + session-id."
  [input-stream agent-name]
  (let [result-text (atom "")
        session-id (atom nil)]
    (with-open [rdr (io/reader input-stream)]
      (loop []
        (when-let [line (.readLine rdr)]
          ;; Capture session ID from system events
          (when-let [sid (extract-session-id line)]
            (reset! session-id sid))
          ;; Capture result text
          (try
            (let [evt (json/parse-string line true)]
              (when (= "result" (:type evt))
                (reset! result-text (str (:result evt)))))
            (catch Exception _))
          ;; Print progress
          (when-let [msg (format-stream-line line agent-name)]
            (println msg)
            (flush))
          (recur))))
    {:result @result-text
     :session-id @session-id}))

(defn- build-base-env
  "Build the base environment for agent subprocesses."
  [extra-env]
  (-> (into {} (System/getenv))
      (dissoc "CLAUDECODE")
      (merge (or extra-env {}))))

(defn invoke-agent
  "Spawn a claude subprocess for a VSDD role.
   Streams progress to terminal. Returns {:exit int :result string :session-id string}.

   Two modes:
     1. Project agent — set :agent-name to use .claude/agents/<name>.md
     2. Nido-managed — set :system-prompt for role instructions, :allowed-tools for restrictions

   Common options:
     :prompt      — the task prompt text
     :env         — additional environment variables
     :working-dir — project directory to run in
     :model       — optional model override
     :display-name — name shown in progress output (defaults to agent-name or \"agent\")"
  [{:keys [agent-name system-prompt prompt env working-dir model
           allowed-tools display-name]}]
  (let [base-env (build-base-env env)
        label (or display-name agent-name "agent")
        cmd (cond-> ["claude" "-p" prompt]
              ;; Mode 1: project agent
              agent-name
              (into ["--agent" agent-name])
              ;; Mode 2: nido-managed
              (and system-prompt (not agent-name))
              (into ["--append-system-prompt" system-prompt])
              ;; Tool restrictions (nido-managed mode)
              (and allowed-tools (not agent-name))
              (into ["--tools" (if (empty? allowed-tools) "" (str/join "," allowed-tools))])
              ;; Common flags
              true (into ["--permission-mode" "bypassPermissions"
                          "--verbose"
                          "--output-format" "stream-json"])
              model (into ["--model" model]))
        proc (apply p/process {:env base-env
                               :dir working-dir
                               :err :inherit
                               :in ""}
                    cmd)
        stream-result (read-stream (:out proc) label)]
    @proc
    (assoc stream-result :exit (:exit @proc))))

(defn invoke-judge
  "Lightweight LLM call for classification — no tools, no agent definition.
   Returns {:exit int :result string :session-id string}.

   Options:
     :prompt      — the prompt text
     :model       — model to use (default \"haiku\")
     :working-dir — directory to run in"
  [{:keys [prompt model working-dir]}]
  (let [env (build-base-env nil)
        proc (p/process {:env env
                         :dir working-dir
                         :err :inherit
                         :in ""}
                        "claude" "-p" prompt
                        "--model" (or model "haiku")
                        "--tools" ""
                        "--verbose"
                        "--output-format" "stream-json")
        stream-result (read-stream (:out proc) "judge")]
    @proc
    (assoc stream-result :exit (:exit @proc))))
