(ns nido.vsdd.agent
  "Spawns claude subprocesses for VSDD agent roles.
   Parses stream-json output for progress display and result capture."
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

(defn invoke-agent
  "Spawn a claude subprocess with an agent definition.
   Streams progress to terminal. Returns {:exit int :result string :session-id string}.

   Options:
     :agent-name  — name of the .claude/agents/<name>.md agent definition
     :prompt      — the prompt text
     :env         — additional environment variables (merged with system env)
     :working-dir — directory to run claude in (where .claude/agents/ lives)
     :model       — optional model override"
  [{:keys [agent-name prompt env working-dir model]}]
  (let [base-env (-> (into {} (System/getenv))
                     (dissoc "CLAUDECODE")
                     (merge (or env {})))
        cmd (cond-> ["claude" "-p" prompt
                     "--agent" agent-name
                     "--permission-mode" "bypassPermissions"
                     "--verbose"
                     "--output-format" "stream-json"]
              model (into ["--model" model]))
        proc (p/process {:env base-env
                         :dir working-dir
                         :err :inherit
                         :in ""}
                        cmd)
        stream-result (read-stream (:out proc) agent-name)]
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
  (let [env (-> (into {} (System/getenv))
                (dissoc "CLAUDECODE"))
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
