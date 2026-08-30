(ns tasks.nido-work
  "bb-task entrypoint: boot an interactive agent in the current worktree with
   nido's context injected via the provider's native surfaces.
   Session is resolved from cwd (session-from-cwd) or explicit args."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [nido.session.launcher :as launcher]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.state :as state]
   [nido.platform.task-args :as task-args]))

(defn- toml-value [v]
  (json/generate-string v))

(defn- codex-mcp-config-args
  "Translate nido's rendered Claude-style MCP JSON into Codex CLI config
   overrides. Codex reads MCP servers from config.toml, and `-c key=value`
   gives us a per-launch config layer without writing user/global config.

   Only stdio servers translate — these overrides describe a command to spawn.
   A server without one (an http/sse entry) is skipped rather than emitted as
   `command=null`."
  [mcp-path]
  (when (fs/exists? mcp-path)
    (let [cfg (json/parse-string (slurp mcp-path) keyword)]
      (vec
       (mapcat
        (fn [[server-name {:keys [command args env]}]]
          (let [prefix (str "mcp_servers." (name server-name))]
            (when command
              (concat
               ["-c" (str prefix ".command=" (toml-value command))]
               (when (seq args)
                 ["-c" (str prefix ".args=" (toml-value args))])
               (mapcat
                (fn [[k v]]
                  ["-c" (str prefix ".env." (name k) "=" (toml-value v))])
                env)))))
        (:mcpServers cfg))))))

(defn- session-from-cwd! []
  (let [s (lifecycle/session-from-cwd)]
    (when-not s
      (throw (ex-info "Not inside a nido session — cd into a worktree first."
                      {:hint "session-from-cwd found no registered worktree for this cwd"})))
    s))

(defn- claude-cmd
  [{:keys [project session worktree instance-id]} {:keys [claude-bin] :or {claude-bin "claude"}}]
  (let [briefing (launcher/session-briefing project session instance-id)
        mcp      (state/session-mcp-path instance-id)
        cmd      (cond-> [claude-bin "--append-system-prompt" briefing]
                   (fs/exists? mcp) (into ["--mcp-config" mcp])
                   :always          (into (mapcat (fn [d] ["--add-dir" d])
                                                  (launcher/nido-add-dirs))))]
    {:cmd cmd :dir worktree}))

(defn- codex-cmd
  [{:keys [worktree instance-id]} {:keys [codex-bin] :or {codex-bin "codex"}}]
  (let [mcp (state/session-mcp-path instance-id)]
    {:cmd (into [codex-bin
                 "--cd" worktree
                 "--sandbox" "workspace-write"]
                (concat
                 (mapcat (fn [d] ["--add-dir" d]) (launcher/nido-add-dirs))
                 (codex-mcp-config-args mcp)))
     :dir worktree}))

(defn- normalize-agent [agent]
  (keyword (or agent :claude)))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  work-cmd*
  "Resolve the session for cwd and assemble the interactive agent invocation.
   Returns {:cmd <vector> :dir <worktree>}."
  [{:keys [agent] :as opts}]
  (let [session (session-from-cwd!)]
    (case (normalize-agent agent)
      :claude (claude-cmd session opts)
      :codex  (codex-cmd session opts)
      (throw (ex-info (str "Unsupported nido work agent: " agent)
                      {:agent agent :supported [:claude :codex]})))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  work [& args]
  (let [[_ opts] (task-args/split-args args)
        {:keys [cmd dir]} (work-cmd* opts)]
    ;; Hand off to the interactive agent in the worktree (inherit the terminal).
    (p/exec cmd {:dir dir})))
