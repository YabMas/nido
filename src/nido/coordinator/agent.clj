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

(defn launch!
  "Spawn claude headlessly for a Run. Blocks until the agent exits.

   opts:
     :run-id        — run id (used to locate run-dir for agent.log path)
     :cwd           — working directory the agent runs in (worktree)
     :first-message — message passed as the positional argument
     :system-prompt — optional --append-system-prompt content
     :claude-bin    — path/name of the claude binary (override for tests)
     :env           — extra env vars to merge into the child's environment

   Returns:
     {:exit-code <int> :claude-session-id <str-or-nil>}"
  [{:keys [run-id cwd first-message system-prompt claude-bin env]
    :or   {claude-bin "claude"}}]
  (let [log-path (cstate/run-agent-log run-id)
        cmd      (cond-> [claude-bin
                          "--print"
                          "--output-format=stream-json"
                          "--dangerously-skip-permissions"]
                   system-prompt (into ["--append-system-prompt" system-prompt])
                   :always       (conj first-message))
        proc     (p/process cmd {:dir cwd
                                 :env (merge (into {} (System/getenv)) (or env {}))
                                 :out :stream
                                 :err :inherit
                                 :shutdown nil})
        session  (atom nil)]
    (with-open [w (jio/writer log-path :append true)]
      (with-open [r (jio/reader (:out proc))]
        (doseq [line (line-seq r)]
          (.write w line) (.write w "\n") (.flush w)
          (when-let [event (parse-event line)]
            (when-let [sid (session-id-from event)]
              (reset! session sid))))))
    (let [exit (:exit @proc)]
      {:exit-code         exit
       :claude-session-id @session})))
