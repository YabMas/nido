(ns tasks.nido-work
  "bb-task entrypoint: boot an interactive claude in the current worktree with
   nido's context injected via flags (briefing, postgres MCP, harness skills).
   Session is resolved from cwd (session-from-cwd) or explicit args."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [nido.session.launcher :as launcher]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.state :as state]
   [nido.task-args :as task-args]))

(defn work-cmd*
  "Resolve the session for cwd and assemble the interactive claude invocation.
   Returns {:cmd <vector> :dir <worktree>}."
  [{:keys [claude-bin] :or {claude-bin "claude"}}]
  (let [{:keys [project session worktree instance-id] :as s} (lifecycle/session-from-cwd)]
    (when-not s
      (throw (ex-info "Not inside a nido session — cd into a worktree first."
                      {:hint "session-from-cwd found no registered worktree for this cwd"})))
    (let [briefing (launcher/session-briefing project session instance-id)
          mcp      (state/session-mcp-path instance-id)
          cmd      (cond-> [claude-bin "--append-system-prompt" briefing]
                     (fs/exists? mcp) (into ["--mcp-config" mcp])
                     :always          (into (mapcat (fn [d] ["--add-dir" d])
                                                    (launcher/nido-add-dirs))))]
      {:cmd cmd :dir worktree})))

(defn work [& args]
  (let [[_ opts] (task-args/split-args args)
        {:keys [cmd dir]} (work-cmd* opts)]
    ;; Hand off to an interactive claude in the worktree (inherit the terminal).
    (p/exec cmd {:dir dir})))
