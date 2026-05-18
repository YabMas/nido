(ns nido.session.launcher
  "Per-session artifacts written into the user-cd-able session home:

     ~/.nido/sessions/<project>/<session>/.mcp.json
     ~/.nido/sessions/<project>/<session>/CLAUDE.md
     ~/.nido/sessions/<project>/<session>/worktree -> <wt-path>
     ~/.nido/sessions/<project>/<session>/.claude  -> worktree/.claude

   Populated on session:up, removed on session:destroy. Internal nido
   bookkeeping (registry, session.edn, pg-data, logs) still lives under
   ~/.nido/state/<instance-id>/ — see nido.session.state."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.config :as config]
   [nido.coordinator.shim :as coord-shim]
   [nido.coordinator.state :as cstate]
   [nido.core :as core]
   [nido.io :as io]
   [nido.session.links :as links]
   [nido.session.state :as state]))

(defn- pg-service-def [session-edn]
  (->> (:services session-edn)
       (filter #(= :postgresql (:type %)))
       first))

(defn- profile-path
  "Path to the persisted profile for a session. Mirrors the logic in
   engine.clj but kept local to avoid a circular require."
  [instance-id]
  (str (fs/path (state/instance-state-dir instance-id) "profile.edn")))

(defn- read-profile-for-session
  "Return the resolved profile persisted at session-up time. nil if absent
   (e.g. legacy sessions predating this feature)."
  [instance-id]
  (let [path (profile-path instance-id)]
    (when (fs/exists? path)
      (io/read-edn path))))

(defn- jj-source-repo?
  "True if `dir` is a jj-colocated source repo. The source's `.jj/repo`
   is a directory (the actual jj data); a workspace's `.jj/repo` is a
   pointer file. Mirrors nido.session.lifecycle/jj-source-repo? — kept
   private here to avoid a require cycle (lifecycle already pulls in
   launcher)."
  [dir]
  (fs/directory? (str (fs/path dir ".jj" "repo"))))

(defn- jj-workspace?
  "True if `wt-path` is a jj workspace (has any `.jj/` shape inside)."
  [wt-path]
  (fs/exists? (str (fs/path wt-path ".jj"))))

(defn- project-source-dir
  "Look up a project's source directory from the registry. Returns nil
   when the project isn't registered (rare — usually means a hand-edited
   projects.edn) so callers can fall through to a sensible default."
  [project-name]
  (some-> (config/read-projects)
          (get project-name)
          :directory))

(defn- vcs-mode
  "Where do edits land for this session? Resolved from filesystem state:

     :jj-workspace          — worktree has its own .jj/ (jj workspace add).
                              Edits in `worktree` are tracked by jj there.
     :jj-source-git-worktree — source repo is jj-colocated but this session
                              uses a legacy git worktree (no .jj/ inside).
                              jj's working copy is the *source* dir; the
                              worktree is invisible to jj.
     :plain-git             — plain-git source + git worktree. Edits in
                              `worktree` are tracked by git there.

   `source-dir` may be nil when the project's directory cannot be resolved
   from the registry; fall back to :plain-git in that case so the briefing
   stays useful even if the project entry was hand-edited."
  [worktree source-dir]
  (cond
    (jj-workspace? worktree)              :jj-workspace
    (and source-dir
         (jj-source-repo? source-dir))    :jj-source-git-worktree
    :else                                 :plain-git))

(defn mcp-path [project-name session-name]
  (str (fs/path (state/session-home-dir project-name session-name) ".mcp.json")))

(defn claude-md-path [project-name session-name]
  (str (fs/path (state/session-home-dir project-name session-name) "CLAUDE.md")))

(defn worktree-link [project-name session-name]
  (str (fs/path (state/session-home-dir project-name session-name) "worktree")))

(defn- mcp-config [pg-svc pg-port]
  (let [{:keys [db-name db-user db-password]} pg-svc
        url (format "postgresql://%s:%s@localhost:%s/%s"
                    db-user db-password pg-port db-name)]
    {:mcpServers
     {:postgres
      {:type    "stdio"
       :command "npx"
       :args    ["-y" "@modelcontextprotocol/server-postgres" url]
       :env     {}}}}))

(defn- render-link-line
  "One bullet for a link: '- <url>' or '- <url> — <title>'."
  [{:keys [url title]}]
  (if (seq title)
    (str "- " url " — " title)
    (str "- " url)))

(defn- render-links-section
  "Render '## Relevant links' grouped by type. Returns nil when no links
   are present so the section is omitted entirely."
  [link-entries]
  (when (seq link-entries)
    (let [groups (links/group-by-type link-entries)]
      (str "## Relevant links\n"
           "\n"
           (->> groups
                (map (fn [[t ls]]
                       (str "**" (links/display-labels t (name t)) "**\n"
                            (str/join "\n" (map render-link-line ls))
                            "\n")))
                (str/join "\n"))
           "\n"))))

(def ^:private add-link-instructions
  (str "## Tracking session links\n"
       "\n"
       "When the user introduces a notion ticket, GitHub PR, slack thread,\n"
       "or any other context-worth URL during this session, persist it so\n"
       "future sessions inherit it. Run from this session-home cwd:\n"
       "\n"
       "    bb nido:session:link:add :type <kw> :url <url> [:title \"...\"]\n"
       "\n"
       "Types: `:notion-ticket` `:pr` `:gh-issue` `:slack-thread` `:other`.\n"
       "Project + session are auto-resolved from cwd. To remove:\n"
       "`bb nido:session:link:remove :url <url>`. To inspect:\n"
       "`bb nido:session:link:list`. Existing entries are listed under\n"
       "\"Relevant links\" above (when any).\n"))

(defn- render-edit-location
  "The 'where do edits land' paragraph, conditioned on vcs-mode so the
   briefing tells the truth for legacy git-worktree sessions inside a
   jj-colocated source. `source-dir` is shown only when it differs from
   the worktree (i.e. the legacy mixed case)."
  [vcs-mode source-dir]
  (case vcs-mode
    :jj-workspace
    (str "You are working through the nido orchestrator. Source-code edits land in\n"
         "the worktree below — NOT in nido's source tree. Use absolute paths when\n"
         "reading/writing files there, or `cd worktree` from this session home.\n"
         "This worktree is a jj workspace; `jj st` / `jj log` / `jj git push` work\n"
         "in place.\n")

    :jj-source-git-worktree
    (str "You are working through the nido orchestrator. This session was created\n"
         "as a legacy git worktree, but the project's source repo is jj-colocated\n"
         "and jj's working copy is the *source* directory below — NOT the worktree.\n"
         "\n"
         "- jj working copy (edit here for jj st/absorb/squash to see changes):\n"
         "    " source-dir "\n"
         "\n"
         "Edits made directly in the worktree are invisible to jj. To resync this\n"
         "session as a jj workspace, run `bb nido:session:destroy` followed by\n"
         "`bb nido:session:up` once the source has been jj-colocated.\n")

    :plain-git
    (str "You are working through the nido orchestrator. Source-code edits land in\n"
         "the worktree below — NOT in nido's source tree. Use absolute paths when\n"
         "reading/writing files there, or `cd worktree` from this session home.\n")))

(defn- render-context
  [{:keys [project-name session-name worktree source-dir
           app-port app-url nrepl-port pg-port
           profile links]}]
  (let [;; Lite sessions have no services; :services can be :all (full) or [] (lite)
        services-active? (and (some? profile)
                              (let [svcs (:services profile)]
                                (or (= :all svcs) (and (seq? svcs) (seq svcs)))))]
    (str
     "# Active nido session\n"
     "\n"
     (render-edit-location (vcs-mode worktree source-dir) source-dir)
     "\n"
     "- session: " session-name "\n"
     "- project: " project-name "\n"
     "- worktree: " worktree "\n"
     (when app-url    (str "- app: " app-url "\n"))
     (when app-port   (str "- app port: " app-port "\n"))
     (when nrepl-port (str "- nrepl port: " nrepl-port "\n"))
     (when pg-port    (str "- postgres port: " pg-port "\n"))
     "\n"
     (if services-active?
       "## Services are already running\n\nThe REPL, app server, and database for this worktree are managed by\nnido. Don't run project-local scripts that spin up a REPL/app/DB —\nconnect to what's already live. The postgres MCP is preconfigured to\nthis session's DB.\n\n"
       "## Lite session\n\nThis is a lite session with no background services. The worktree is a\nread-only symlink to the project source directory. To inspect the code,\nuse absolute paths or `cd worktree` from this session home.\n\n")
     (render-links-section links)
     add-link-instructions)))

(defn- ensure-worktree-symlink!
  "Create or refresh the `worktree` symlink inside the session-home so
   `cd worktree` reaches the code without callers needing to know the
   project's worktrees layout."
  [project-name session-name worktree]
  (let [link (worktree-link project-name session-name)]
    ;; fs/exists? follows symlinks; a dangling link returns false but
    ;; fs/sym-link? still recognises it. Always remove the existing link
    ;; (if any) before recreating so a moved worktree is reflected.
    (when (or (fs/exists? link) (fs/sym-link? link))
      (fs/delete link))
    (fs/create-sym-link link worktree)))

(defn- ensure-claude-symlink!
  "Create or refresh a `.claude` symlink inside the session-home pointing
   through the `worktree` symlink to the project's checked-in `.claude/`.
   Without this, Claude Code launched from the session-home cwd sees no
   project-local skills, agents, or commands. Relative target so a moved
   worktree is picked up automatically via the `worktree` symlink."
  [project-name session-name]
  (let [home (state/session-home-dir project-name session-name)
        link (str (fs/path home ".claude"))]
    (when (or (fs/exists? link) (fs/sym-link? link))
      (fs/delete link))
    (fs/create-sym-link link "worktree/.claude")))

(defn- ensure-bb-edn-symlink!
  "Create or refresh a `bb.edn` symlink inside the session-home pointing
   at nido's own bb.edn. Lets the agent run nido bb tasks (e.g.
   `bb nido:session:link:add`) directly from the session-home cwd —
   without it, bb walks up looking for bb.edn and finds none. Project +
   session can then auto-resolve from cwd."
  [project-name session-name]
  (let [home    (state/session-home-dir project-name session-name)
        link    (str (fs/path home "bb.edn"))
        target  (str (fs/path (core/nido-source-dir) "bb.edn"))]
    (when (or (fs/exists? link) (fs/sym-link? link))
      (fs/delete link))
    (fs/create-sym-link link target)))

(defn- purge-legacy-artifacts!
  "Delete pre-refactor launcher artifacts at ~/.nido/state/<instance-id>/
   that the previous launcher wrote there. Sessions created before the
   session-home migration leave these on disk with stale port info, so
   anything (or anyone) grepping ~/.nido reads obsolete data. Idempotent."
  [instance-id]
  (when instance-id
    (let [base (state/instance-state-dir instance-id)]
      (doseq [legacy ["session-context.md" "mcp.json"]
              :let [path (str (fs/path base legacy))]
              :when (fs/exists? path)]
        (fs/delete path)
        (core/log-step (str "Removed legacy " path))))))

(defn write-artifacts!
  "Write per-session launcher artifacts into the session home. Called from
   start-services! after services are up. session-edn is passed in so we
   can read DB credentials without re-loading from disk."
  [ctx session-edn]
  (let [session-name (get-in ctx [:session :name])
        worktree     (get-in ctx [:session :project-dir])
        project-name (get-in ctx [:session :project-name])
        instance-id  (get-in ctx [:session :instance-id])
        pg-port      (get-in ctx [:pg :port])
        pg-svc       (pg-service-def session-edn)]
    (when-not session-name
      (throw (ex-info
              "Cannot write session-home artifacts: no :name in ctx :session"
              {:project-name project-name
               :hint (str "This session was started before the session-home "
                          "migration. Run `bb nido:session:down` then `:up` "
                          "to rebuild it.")})))
    (let [profile      (read-profile-for-session instance-id)
          home         (state/session-home-dir project-name session-name)
          link-entries (when instance-id (links/read-links instance-id))
          ctx-doc      (render-context {:session-name session-name
                                        :project-name project-name
                                        :worktree     worktree
                                        :source-dir   (project-source-dir project-name)
                                        :app-port     (get-in ctx [:app :port])
                                        :app-url      (get-in ctx [:app :url])
                                        :nrepl-port   (get-in ctx [:repl :port])
                                        :pg-port      pg-port
                                        :profile      profile
                                        :links        link-entries})
          mcp-doc      (when (and pg-svc pg-port) (mcp-config pg-svc pg-port))]
      (fs/create-dirs home)
      (when mcp-doc
        (let [path (mcp-path project-name session-name)]
          (io/write-json! path mcp-doc)
          (core/log-step (str "Wrote " path))))
      (let [path (claude-md-path project-name session-name)]
        (io/write-text! path ctx-doc)
        (core/log-step (str "Wrote " path)))
      (try
        (ensure-worktree-symlink! project-name session-name worktree)
        (catch Exception e
          (core/log-step (str "warning: worktree symlink: " (ex-message e)))))
      (try
        (ensure-claude-symlink! project-name session-name)
        (catch Exception e
          (core/log-step (str "warning: .claude symlink: " (ex-message e)))))
      (try
        (ensure-bb-edn-symlink! project-name session-name)
        (catch Exception e
          (core/log-step (str "warning: bb.edn symlink: " (ex-message e)))))
      (try
        (purge-legacy-artifacts! (get-in ctx [:session :instance-id]))
        (catch Exception e
          (core/log-step (str "warning: purge legacy artifacts: " (ex-message e)))))
      (when-let [run-id (:owned-by-run session-edn)]
        (try
          (coord-shim/write! home (cstate/run-dir run-id))
          (core/log-step (str "Wrote " home "/bin/claude (resume shim) + run-link"))
          (catch Exception e
            (core/log-step (str "warning: run-shim: " (ex-message e)))))))))

(defn rerender-briefing!
  "Re-render only the session-home CLAUDE.md briefing — used after a
   link mutation so the next session start sees the new entries.
   Reads the current ctx (ports etc.) from the persisted session.edn.
   No-op when the session is down (no session.edn) or when the
   session-home dir is missing — links land on disk regardless and the
   next `up` will rebuild the briefing fresh."
  [project-name session-name instance-id]
  (let [home     (state/session-home-dir project-name session-name)
        session  (some-> instance-id state/read-session)
        ctx      (:context session)
        worktree (get-in ctx [:session :project-dir])]
    (when (and ctx (fs/exists? home))
      (let [profile      (read-profile-for-session instance-id)
            link-entries (links/read-links instance-id)
            doc          (render-context
                          {:session-name session-name
                           :project-name project-name
                           :worktree     worktree
                           :source-dir   (project-source-dir project-name)
                           :app-port     (get-in ctx [:app :port])
                           :app-url      (get-in ctx [:app :url])
                           :nrepl-port   (get-in ctx [:repl :port])
                           :pg-port      (get-in ctx [:pg :port])
                           :profile      profile
                           :links        link-entries})]
        (io/write-text! (claude-md-path project-name session-name) doc)))))

(defn remove-artifacts!
  "Remove the session home. Called from stop-session!. No-op if the session
   was never written there (e.g. a stale session-name lookup)."
  [project-name session-name]
  (when (and project-name session-name)
    (let [home (state/session-home-dir project-name session-name)]
      (when (fs/exists? home)
        (fs/delete-tree home)
        (core/log-step (str "Removed " home))))))
