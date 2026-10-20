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
  "`:name` sets the agent's display name, the address its peers message it by.
   `:prompt-file` is read now and becomes the first turn. It goes straight after
   the binary because `--add-dir` takes every argument up to the next flag, so a
   prompt placed last would be read as one more directory. `:model` is claude's
   own, passed through unchecked.

   Permissions bypassed, and no settings: Claude Code reads bypass only from its
   flag — `defaultMode: bypassPermissions` in a settings file is ignored and
   falls back to `default` — so the flag is the one part of the launch baseline
   on the command line. The rest, nido's hooks and the commit gate, is the
   worktree's `.claude/settings.local.json`, which `work` writes before
   starting claude.

   Returns the session home beside the command, for `work` to write it from."
  [{:keys [project session worktree instance-id]}
   {:keys [claude-bin name prompt-file model] :or {claude-bin "claude"}}]
  (let [briefing (launcher/session-briefing project session instance-id)
        mcp      (launcher/session-mcp-path project session)
        cmd      (cond-> [claude-bin]
                   prompt-file      (conj (slurp (str prompt-file)))
                   :always          (conj "--dangerously-skip-permissions")
                   :always          (into ["--append-system-prompt" briefing])
                   name             (into ["--name" (str name)])
                   model            (into ["--model" (str model)])
                   (fs/exists? mcp) (into ["--mcp-config" mcp])
                   :always          (into (mapcat (fn [d] ["--add-dir" d])
                                                  (launcher/nido-add-dirs))))]
    {:cmd cmd :dir worktree :home (str (state/session-home-dir project session))}))

(defn- codex-cmd
  [{:keys [project session worktree]} {:keys [codex-bin] :or {codex-bin "codex"}}]
  (let [mcp (launcher/session-mcp-path project session)]
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
  (let [[_ opts] (task-args/split-args args #{:name :prompt-file :model})
        {:keys [cmd dir home]} (work-cmd* opts)]
    ;; A session up before the baseline existed has no worktree settings yet; writing them here
    ;; keeps the gate from depending on when the session was last brought up.
    (when home (launcher/ensure-worktree-baseline! dir home))
    ;; Hand off to the interactive agent in the worktree (inherit the terminal).
    (p/exec cmd {:dir dir})))
