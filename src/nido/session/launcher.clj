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
   [clojure.java.io :as jio]
   [clojure.string :as str]
   [nido.config :as config]
   [nido.coordinator.shim :as coord-shim]
   [nido.coordinator.state :as cstate]
   [nido.core :as core]
   [nido.io :as io]
   [nido.session.links :as links]
   [nido.session.state :as state]))

(defn- run-workstream-context
  "Given a run-id or a session-home path, return {:workstream-id … :br-id …}
   for embedding in the briefing. Returns an empty map when no run is found or
   the run carries no :workstream-id — so callers can always safely merge the
   result without nil-checking. Safe to call on human/dry-run sessions."
  [& {:keys [run-id session-home]}]
  (let [run (cond
              run-id
              (let [path (cstate/run-edn-path run-id)]
                (when (fs/exists? path)
                  (io/read-edn path)))

              session-home
              (let [path (str (fs/path session-home "run-link" "run.edn"))]
                (when (fs/exists? path)
                  (io/read-edn path))))]
    (if-let [ws-id (:workstream-id run)]
      {:workstream-id ws-id
       :br-id         (get-in run [:event-payload :id])}
      {})))

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

(defn write-session-mcp!
  "Render the postgres MCP config and write it to the instance-state dir as a
   launch input. Returns the path, or nil when the session has no postgres."
  [instance-id pg-svc pg-port]
  (when (and pg-svc pg-port)
    (let [path (state/session-mcp-path instance-id)]
      (fs/create-dirs (fs/parent path))
      (io/write-json! path (mcp-config pg-svc pg-port))
      path)))

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
       "or any other context-worth URL during this session — or when you\n"
       "yourself create one (open a PR, file an issue, etc.) — persist it\n"
       "immediately so future sessions inherit it. **Do not ask permission**;\n"
       "adding relevant links is mandatory, not a suggestion. Run from this\n"
       "session-home cwd:\n"
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

(defn read-project-briefing
  "Return the project-specific briefing markdown for <project-name>, or
   nil when no briefing resource exists. Looked up on the classpath at
   project-briefings/<project>.md so projects without dedicated briefings
   (most of them today) get a clean default. The content is embedded
   verbatim into the session-home CLAUDE.md by render-context — keep the
   resource focused on domain rules (routing, REPL discipline, …) that
   nido's generic briefing can't say."
  [project-name]
  (when project-name
    (when-let [resource (jio/resource (str "project-briefings/" project-name ".md"))]
      (slurp resource))))

(defn- render-workstream-line
  "Render the 'Workstream:' line when workstream-id is present.
   Appends ' (<br-id>)' when br-id is also available."
  [workstream-id br-id]
  (when workstream-id
    (str "- workstream: " workstream-id
         (when (seq br-id) (str " (" br-id ")"))
         "\n")))

(defn- render-context
  [{:keys [project-name session-name worktree source-dir
           app-port app-url nrepl-port pg-port
           profile links project-briefing
           workstream-id br-id]}]
  (let [;; Lite sessions have no services; :services is :all (full) or a
        ;; vector allowlist ([] = lite). Default to "active" when profile
        ;; is absent — legacy sessions predating profile.edn were all full.
        svcs             (:services profile)
        services-active? (or (nil? profile)
                             (= :all svcs)
                             (and (sequential? svcs) (seq svcs)))]
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
     (render-workstream-line workstream-id br-id)
     "\n"
     (if services-active?
       "## Services are already running\n\nThe REPL, app server, and database for this worktree are managed by\nnido. Don't run project-local scripts that spin up a REPL/app/DB —\nconnect to what's already live. The postgres MCP is preconfigured to\nthis session's DB.\n\n"
       "## Lite session\n\nThis is a lite session with no background services. The worktree is a\nread-only symlink to the project source directory. To inspect the code,\nuse absolute paths or `cd worktree` from this session home.\n\n")
     (when-not (str/blank? project-briefing) (str project-briefing "\n"))
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

(defn- nido-native-skill-dirs
  "Absolute paths of nido's *native* (real, non-symlink) skill dirs under
   `nido/.claude/skills` — the harness skills to inject into every session-home
   `.claude`. Mirrored brian skills (symlinks in nido's tree) are skipped: the
   session already gets brian's skills directly through the composed `.claude`."
  []
  (let [skills-dir (fs/path (core/nido-source-dir) ".claude" "skills")]
    (if (fs/exists? skills-dir)
      (->> (fs/list-dir skills-dir)
           (filter #(and (fs/directory? %) (not (fs/sym-link? %))))
           (mapv str))
      [])))

(defn- compose-claude-dir!
  "Compose `<home>/.claude` as a *real* directory so the in-session agent sees
   both brian's project tooling and nido's injected harness skills:

   - every top-level entry of the worktree's `.claude` *except* `skills/` is
     re-exposed as a relative symlink through the session-home `worktree` link
     (so a moved worktree is still followed — as the old single symlink did);
   - `skills/` is a real dir symlinking each of the worktree's skills plus each
     `nido-native-skills` path (absolute).

   Idempotent. SAFETY: if `<home>/.claude` is currently a *symlink* (the old
   single-link form) it is only unlinked — never `delete-tree`d — so we never
   follow it into the worktree and destroy brian's real `.claude`. A previously
   composed real dir contains only our own symlinks, so deleting it removes link
   entries, not their targets."
  [home nido-native-skills]
  (let [claude    (fs/path home ".claude")
        wt-claude (fs/path home "worktree" ".claude")]
    (cond
      (fs/sym-link? claude) (fs/delete claude)        ; old single-symlink: unlink only
      (fs/exists? claude)   (fs/delete-tree claude))  ; prior composed dir (our symlinks)
    (fs/create-dirs claude)
    (when (fs/exists? wt-claude)
      (doseq [entry (fs/list-dir wt-claude)
              :let  [nm (str (fs/file-name entry))]
              :when (not= nm "skills")]
        (fs/create-sym-link (fs/path claude nm)
                            (fs/path ".." "worktree" ".claude" nm))))
    (let [skills    (fs/path claude "skills")
          wt-skills (fs/path wt-claude "skills")]
      (fs/create-dirs skills)
      (when (fs/exists? wt-skills)
        (doseq [s (fs/list-dir wt-skills)
                :let [nm (str (fs/file-name s))]]
          (fs/create-sym-link (fs/path skills nm)
                              (fs/path ".." ".." "worktree" ".claude" "skills" nm))))
      (doseq [nido-skill nido-native-skills
              :let [nm   (str (fs/file-name nido-skill))
                    link (fs/path skills nm)]]
        ;; A nido native skill wins over a same-named brian skill linked above.
        ;; Removing any existing entry first means a name clash can't throw —
        ;; which would otherwise be swallowed by write-artifacts! and silently
        ;; leave the session with no composed .claude at all.
        (when (or (fs/exists? link) (fs/sym-link? link))
          (fs/delete link))
        (fs/create-sym-link link nido-skill)))))

(defn- ensure-claude-dir!
  "Compose the session-home `.claude` (brian's entries + nido's native harness
   skills). Replaces the old single `.claude` → worktree/.claude symlink so the
   in-session agent sees nido's injected skills (e.g. local-ci) alongside
   brian's project-local skills, agents, and commands."
  [project-name session-name]
  (compose-claude-dir! (state/session-home-dir project-name session-name)
                       (nido-native-skill-dirs)))

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
          ws-ctx       (when-let [run-id (:owned-by-run session-edn)]
                         (run-workstream-context :run-id run-id))
          ctx-doc      (render-context (merge {:session-name     session-name
                                               :project-name     project-name
                                               :worktree         worktree
                                               :source-dir       (project-source-dir project-name)
                                               :app-port         (get-in ctx [:app :port])
                                               :app-url          (get-in ctx [:app :url])
                                               :nrepl-port       (get-in ctx [:repl :port])
                                               :pg-port          pg-port
                                               :profile          profile
                                               :links            link-entries
                                               :project-briefing (read-project-briefing project-name)}
                                              ws-ctx))
          mcp-doc      (when (and pg-svc pg-port) (mcp-config pg-svc pg-port))]
      (fs/create-dirs home)
      (when mcp-doc
        (let [path (mcp-path project-name session-name)]
          (io/write-json! path mcp-doc)
          (core/log-step (str "Wrote " path))))
      (when-let [svc-mcp-path (write-session-mcp! (get-in ctx [:session :instance-id]) pg-svc pg-port)]
        (core/log-step (str "Wrote " svc-mcp-path)))
      (let [path (claude-md-path project-name session-name)]
        (io/write-text! path ctx-doc)
        (core/log-step (str "Wrote " path)))
      (try
        (ensure-worktree-symlink! project-name session-name worktree)
        (catch Exception e
          (core/log-step (str "warning: worktree symlink: " (ex-message e)))))
      (try
        (ensure-claude-dir! project-name session-name)
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
            ws-ctx       (run-workstream-context :session-home home)
            doc          (render-context
                          (merge {:session-name     session-name
                                  :project-name     project-name
                                  :worktree         worktree
                                  :source-dir       (project-source-dir project-name)
                                  :app-port         (get-in ctx [:app :port])
                                  :app-url          (get-in ctx [:app :url])
                                  :nrepl-port       (get-in ctx [:repl :port])
                                  :pg-port          (get-in ctx [:pg :port])
                                  :profile          profile
                                  :links            link-entries
                                  :project-briefing (read-project-briefing project-name)}
                                 ws-ctx))]
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
