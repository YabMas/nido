(ns nido.session.launcher-test
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.config :as config]
   [nido.core :as core]
   [nido.session.launcher :as launcher]
   [nido.session.links :as links]
   [nido.session.state :as state]))

(def ^:private base-ctx
  {:session-name "doc-room"
   :project-name "brian"
   :worktree     "/tmp/wt"
   :source-dir   "/tmp/src"
   :app-port     4794
   :app-url      "http://doc-room.brian.localhost:4794"
   :nrepl-port   65250
   :pg-port      7194
   :profile      {:services :all}
   :links        []})

(deftest read-project-briefing-returns-brian-content
  (let [briefing (launcher/read-project-briefing "brian")]
    (is (some? briefing) "brian briefing resource should be present")
    (is (str/includes? briefing "datastar-dev")
        "briefing should mention the datastar-dev subagent")
    (is (str/includes? briefing "statechart-dev")
        "briefing should mention the statechart-dev subagent")
    (is (str/includes? briefing "test-dev")
        "briefing should mention the test-dev subagent")))

(deftest read-project-briefing-returns-nil-for-unknown-projects
  (is (nil? (launcher/read-project-briefing "no-such-project-xyz"))
      "unknown projects should resolve to nil, not throw")
  (is (nil? (launcher/read-project-briefing nil))
      "nil project-name should resolve to nil"))

(deftest render-context-embeds-project-briefing-when-provided
  (let [doc (@#'launcher/render-context
              (assoc base-ctx :project-briefing "## Project: brian\n\n…rules…\n"))]
    (testing "briefing section appears in the rendered CLAUDE.md"
      (is (str/includes? doc "## Project: brian"))
      (is (str/includes? doc "…rules…")))
    (testing "ordering: briefing sits between services and link tracker"
      (let [services-idx (str/index-of doc "## Services are already running")
            briefing-idx (str/index-of doc "## Project: brian")
            tracker-idx  (str/index-of doc "## Tracking session links")]
        (is (and services-idx briefing-idx tracker-idx))
        (is (< services-idx briefing-idx tracker-idx)
            "briefing should land after services and before link tracker")))))

(deftest render-context-includes-stacked-development-doctrine
  (let [doc (@#'launcher/render-context base-ctx)]
    (testing "the condensed doctrine is present"
      (is (str/includes? doc "## Stacked development"))
      (is (str/includes? doc "/stack")
          "should point at the full skill")
      (is (str/includes? doc "Layer:")
          "should name the trailer so commits carry it"))
    (testing "the trailer vocabulary is spelled out"
      (is (str/includes? doc "mechanical"))
      (is (str/includes? doc "structural"))
      (is (str/includes? doc "behavioral")))
    (testing "ordering: doctrine sits before the lifecycle section"
      (let [doctrine-idx  (str/index-of doc "## Stacked development")
            lifecycle-idx (str/index-of doc "## Lifecycle")]
        (is (and doctrine-idx lifecycle-idx))
        (is (< doctrine-idx lifecycle-idx)
            "doctrine must precede lifecycle")))))

(deftest render-context-omits-section-when-briefing-absent
  (let [doc (@#'launcher/render-context (assoc base-ctx :project-briefing nil))]
    (is (not (str/includes? doc "## Project: brian"))
        "no project section header when briefing is nil")
    (is (str/includes? doc "## Tracking session links")
        "the link tracker should still render")))

(deftest render-context-omits-section-when-briefing-blank
  (let [doc (@#'launcher/render-context (assoc base-ctx :project-briefing "   "))]
    (is (not (str/includes? doc "## Project:"))
        "whitespace-only briefing should be treated like nil")))

(deftest render-context-services-active-for-various-profile-shapes
  (testing ":all profile renders full-services briefing"
    (let [doc (@#'launcher/render-context (assoc base-ctx :profile {:services :all}))]
      (is (str/includes? doc "## Services are already running"))
      (is (not (str/includes? doc "## Lite session")))))
  (testing "vector allowlist with entries renders full-services briefing"
    (let [doc (@#'launcher/render-context
                (assoc base-ctx :profile {:services [:postgresql :process]}))]
      (is (str/includes? doc "## Services are already running"))
      (is (not (str/includes? doc "## Lite session")))))
  (testing "nil profile (legacy session pre-profile.edn) is treated as active"
    (let [doc (@#'launcher/render-context (assoc base-ctx :profile nil))]
      (is (str/includes? doc "## Services are already running"))
      (is (not (str/includes? doc "## Lite session")))))
  (testing "empty vector is a lite session"
    (let [doc (@#'launcher/render-context (assoc base-ctx :profile {:services []}))]
      (is (str/includes? doc "## Lite session"))
      (is (not (str/includes? doc "## Services are already running"))))))

;; ---------------------------------------------------------------------------
;; Session-home .claude composition (brian entries + nido native skills)
;; ---------------------------------------------------------------------------

(deftest compose-claude-dir-merges-brian-and-nido-skills
  (let [tmp (fs/create-temp-dir)]
    (try
      (let [home       (fs/path tmp "home")
            wt         (fs/path tmp "wt")
            nido-skill (fs/path tmp "nido-skills" "local-ci")]
        ;; fake worktree .claude: a non-skills entry + a settings file + a skill
        (fs/create-dirs (fs/path wt ".claude" "agents"))
        (spit (str (fs/path wt ".claude" "settings.json")) "{}")
        (fs/create-dirs (fs/path wt ".claude" "skills" "e2e"))
        (spit (str (fs/path wt ".claude" "skills" "e2e" "SKILL.md")) "brian e2e")
        ;; nido native skill source
        (fs/create-dirs nido-skill)
        (spit (str (fs/path nido-skill "SKILL.md")) "nido local-ci")
        ;; session home with the worktree symlink the launcher relies on
        (fs/create-dirs home)
        (fs/create-sym-link (fs/path home "worktree") (str wt))

        (@#'launcher/compose-claude-dir! (str home) [(str nido-skill)])

        (testing ".claude is a real directory, not a symlink"
          (is (fs/directory? (fs/path home ".claude")))
          (is (not (fs/sym-link? (fs/path home ".claude")))))
        (testing "brian non-skills entries link through and resolve"
          (is (fs/exists? (fs/path home ".claude" "agents")))
          (is (= "{}" (slurp (str (fs/path home ".claude" "settings.json"))))))
        (testing "skills/ merges brian's skills with nido's native skills"
          (is (fs/directory? (fs/path home ".claude" "skills")))
          (is (= "brian e2e"
                 (slurp (str (fs/path home ".claude" "skills" "e2e" "SKILL.md")))))
          (is (= "nido local-ci"
                 (slurp (str (fs/path home ".claude" "skills" "local-ci" "SKILL.md")))))))
      (finally (fs/delete-tree tmp)))))

(deftest compose-claude-dir-rebuild-preserves-brian-source
  ;; CRITICAL: recomposing must NEVER follow the old symlink and destroy the
  ;; worktree's real .claude. Start from the old single-symlink form, compose,
  ;; then compose again, and assert brian's source survived both.
  (let [tmp (fs/create-temp-dir)]
    (try
      (let [home       (fs/path tmp "home")
            wt         (fs/path tmp "wt")
            nido-skill (fs/path tmp "nido-skills" "local-ci")]
        (fs/create-dirs (fs/path wt ".claude" "skills" "e2e"))
        (spit (str (fs/path wt ".claude" "skills" "e2e" "SKILL.md")) "brian e2e")
        (fs/create-dirs nido-skill)
        (spit (str (fs/path nido-skill "SKILL.md")) "nido")
        (fs/create-dirs home)
        (fs/create-sym-link (fs/path home "worktree") (str wt))
        ;; old form: .claude is a single symlink into the worktree
        (fs/create-sym-link (fs/path home ".claude") "worktree/.claude")

        (@#'launcher/compose-claude-dir! (str home) [(str nido-skill)])  ; convert
        (@#'launcher/compose-claude-dir! (str home) [(str nido-skill)])  ; rebuild

        (testing "worktree's source .claude/skills survived both rebuilds"
          (is (= "brian e2e"
                 (slurp (str (fs/path wt ".claude" "skills" "e2e" "SKILL.md"))))))
        (testing "composed skills still resolve after rebuild"
          (is (= "brian e2e"
                 (slurp (str (fs/path home ".claude" "skills" "e2e" "SKILL.md")))))
          (is (fs/exists? (fs/path home ".claude" "skills" "local-ci" "SKILL.md")))))
      (finally (fs/delete-tree tmp)))))

(deftest nido-native-skill-dirs-skips-mirrored-symlinks
  (let [tmp (fs/create-temp-dir)]
    (try
      (let [src   (fs/path tmp "nido")
            real  (fs/path src ".claude" "skills" "local-ci")
            other (fs/path tmp "brian-skill")]
        (fs/create-dirs real)
        (fs/create-dirs other)
        ;; a mirrored skill = symlink in nido's own skills dir → must be skipped
        (fs/create-sym-link (fs/path src ".claude" "skills" "mirrored") (str other))
        (with-redefs [core/nido-source-dir (constantly (str src))]
          (let [dirs (@#'launcher/nido-native-skill-dirs)]
            (testing "only the native (non-symlink) skill is returned"
              (is (= 1 (count dirs)))
              (is (str/includes? (first dirs) "local-ci"))))))
      (finally (fs/delete-tree tmp)))))

(deftest compose-claude-dir-native-skill-wins-on-name-clash
  ;; If a nido native skill shares a name with a brian worktree skill, the
  ;; native one wins and compose must NOT throw (a throw would be swallowed by
  ;; write-artifacts! and leave the session with no composed .claude at all).
  (let [tmp (fs/create-temp-dir)]
    (try
      (let [home       (fs/path tmp "home")
            wt         (fs/path tmp "wt")
            nido-skill (fs/path tmp "nido-skills" "shared")]
        (fs/create-dirs (fs/path wt ".claude" "skills" "shared"))
        (spit (str (fs/path wt ".claude" "skills" "shared" "SKILL.md")) "brian shared")
        (fs/create-dirs nido-skill)
        (spit (str (fs/path nido-skill "SKILL.md")) "nido shared")
        (fs/create-dirs home)
        (fs/create-sym-link (fs/path home "worktree") (str wt))

        (is (nil? (@#'launcher/compose-claude-dir! (str home) [(str nido-skill)]))
            "compose returns without throwing on a name clash")
        (testing "the nido native skill wins the shared name"
          (is (= "nido shared"
                 (slurp (str (fs/path home ".claude" "skills" "shared" "SKILL.md"))))))
        (testing "brian's source skill is untouched"
          (is (= "brian shared"
                 (slurp (str (fs/path wt ".claude" "skills" "shared" "SKILL.md")))))))
      (finally (fs/delete-tree tmp)))))

;; ---------------------------------------------------------------------------
;; Workstream line in the briefing
;; ---------------------------------------------------------------------------

(deftest render-context-includes-workstream-line-when-present
  (let [doc (@#'launcher/render-context
              (assoc base-ctx
                     :workstream-id "ws-2026-06-08-brian-impl-br-4659-abc123"
                     :br-id "BR-4659"))]
    (testing "workstream line appears in the rendered CLAUDE.md"
      (is (str/includes? doc "ws-2026-06-08-brian-impl-br-4659-abc123")
          "workstream-id should appear in the briefing"))
    (testing "br-id is rendered in parentheses after the workstream-id"
      (is (str/includes? doc "BR-4659")
          "br-id should appear in the briefing"))
    (testing "workstream line format matches expected pattern"
      (is (str/includes? doc "- workstream: ws-2026-06-08-brian-impl-br-4659-abc123 (BR-4659)")
          "line should be '- workstream: <ws-id> (<br-id>)'"))))

(deftest render-context-includes-workstream-line-without-br-id
  (let [doc (@#'launcher/render-context
              (assoc base-ctx
                     :workstream-id "ws-2026-06-08-brian-impl-abc123"))]
    (testing "workstream line appears even without br-id"
      (is (str/includes? doc "- workstream: ws-2026-06-08-brian-impl-abc123")
          "workstream-id should appear without a br suffix"))))

(deftest render-context-omits-workstream-line-when-absent
  (let [doc (@#'launcher/render-context base-ctx)]
    (testing "no workstream line for human/non-run sessions"
      (is (not (str/includes? doc "workstream:"))
          "no workstream line should appear when workstream-id is nil"))))

;; ---------------------------------------------------------------------------
;; .mcp.json composition: repo-declared + registry-declared + session postgres
;; ---------------------------------------------------------------------------

(defn- write-worktree-mcp!
  "Materialise a `<worktree>/.mcp.json` and return the worktree path."
  [wt servers]
  (fs/create-dirs wt)
  (spit (str (fs/path wt ".mcp.json"))
        (json/generate-string {:mcpServers servers}))
  (str wt))

(def ^:private chiasmus-entry
  {:chiasmus {:type "stdio" :command "npx" :args ["-y" "chiasmus"]}})

(defn- with-registry
  "Run f with projects.edn stubbed to a single project carrying `extra`."
  [extra f]
  (with-redefs [config/read-projects
                (constantly {"brian" (merge {:directory "/tmp/src"} extra)})]
    (f)))

(deftest mcp-config-merges-repo-registry-and-session-postgres
  (let [tmp (fs/create-temp-dir)]
    (try
      (let [wt (write-worktree-mcp!
                (fs/path tmp "wt")
                {:chrome-devtools {:command "npx" :args ["-y" "chrome-devtools-mcp@latest"]}
                 ;; the repo's postgres points at the shared template port
                 :postgres        {:command "npx" :args ["-y" "pg" "postgresql://u:p@localhost:5433/brian"]}})]
        (with-registry
          {:mcp-servers chiasmus-entry}
          (fn []
            (let [servers (:mcpServers (@#'launcher/mcp-config
                                        "brian" wt
                                        {:db-name "d" :db-user "u" :db-password "p"} 6145))]
              (testing "servers the repo commits pass through"
                (is (= "npx" (get-in servers [:chrome-devtools :command]))))
              (testing "servers the nido registry declares are added"
                (is (= ["-y" "chiasmus"] (get-in servers [:chiasmus :args]))))
              (testing "the repo's stale postgres is replaced by this session's"
                (is (re-find #"localhost:6145/d"
                             (last (get-in servers [:postgres :args])))))))))
      (finally (fs/delete-tree tmp)))))

(deftest mcp-config-keeps-other-servers-when-session-has-no-postgres
  ;; A lite/no-DB session used to get no .mcp.json at all, losing every server.
  (let [tmp (fs/create-temp-dir)]
    (try
      (let [wt (write-worktree-mcp! (fs/path tmp "wt")
                                    {:chrome-devtools {:command "npx"}})]
        (with-registry
          {:mcp-servers chiasmus-entry}
          (fn []
            (let [servers (:mcpServers (@#'launcher/mcp-config "brian" wt nil nil))]
              (is (contains? servers :chiasmus))
              (is (contains? servers :chrome-devtools))
              (testing "no postgres entry is invented without a port"
                (is (not (contains? servers :postgres))))))))
      (finally (fs/delete-tree tmp)))))

(deftest mcp-config-nil-when-no-server-is-declared
  (let [tmp (fs/create-temp-dir)]
    (try
      (fs/create-dirs (fs/path tmp "wt"))
      (with-registry {} (fn []
                          (is (nil? (@#'launcher/mcp-config
                                     "brian" (str (fs/path tmp "wt")) nil nil)))))
      (finally (fs/delete-tree tmp)))))

(deftest mcp-config-survives-a-malformed-repo-mcp-json
  ;; session:up must not die because the project repo shipped broken JSON.
  (let [tmp (fs/create-temp-dir)]
    (try
      (let [wt (fs/path tmp "wt")]
        (fs/create-dirs wt)
        (spit (str (fs/path wt ".mcp.json")) "{not json")
        (with-registry
          {:mcp-servers chiasmus-entry}
          (fn []
            (let [servers (:mcpServers (@#'launcher/mcp-config "brian" (str wt) nil nil))]
              (is (= #{:chiasmus} (set (keys servers))))))))
      (finally (fs/delete-tree tmp)))))

(deftest mcp-config-registry-wins-over-repo-on-name-clash
  (let [tmp (fs/create-temp-dir)]
    (try
      (let [wt (write-worktree-mcp! (fs/path tmp "wt")
                                    {:chiasmus {:command "stale"}})]
        (with-registry
          {:mcp-servers chiasmus-entry}
          (fn []
            (let [servers (:mcpServers (@#'launcher/mcp-config "brian" wt nil nil))]
              (is (= "npx" (get-in servers [:chiasmus :command])))))))
      (finally (fs/delete-tree tmp)))))

(deftest write-session-mcp-writes-to-state-dir
  (let [tmp (str (fs/create-temp-dir))]
    (with-redefs [state/instance-state-dir (fn [_id] tmp)]
      (with-registry
        {}
        (fn []
          (let [path (launcher/write-session-mcp!
                      "proj--sess" "brian" "/nonexistent-wt"
                      {:db-name "d" :db-user "u" :db-password "p"} 5599)]
            (is (= (state/session-mcp-path "proj--sess") path))
            (is (fs/exists? path))
            (let [cfg  (json/parse-string (slurp path) keyword)
                  conn (-> cfg :mcpServers :postgres :args last)]
              (is (re-find #"localhost:5599/d" conn)))))))))

(deftest write-session-mcp-writes-registry-servers-without-pg
  (let [tmp (str (fs/create-temp-dir))]
    (with-redefs [state/instance-state-dir (fn [_id] tmp)]
      (with-registry
        {:mcp-servers chiasmus-entry}
        (fn []
          (let [path (launcher/write-session-mcp!
                      "proj--sess" "brian" "/nonexistent-wt" nil nil)]
            (is (some? path) "a DB-less session still needs its other servers")
            (let [cfg (json/parse-string (slurp path) keyword)]
              (is (contains? (:mcpServers cfg) :chiasmus)))))))))

(deftest write-session-mcp-noop-when-nothing-to-write
  (with-redefs [state/instance-state-dir (fn [_id] "/never")]
    (with-registry
      {}
      (fn []
        (is (nil? (launcher/write-session-mcp!
                   "x--y" "brian" "/nonexistent-wt" nil nil)))
        (is (nil? (launcher/write-session-mcp!
                   "x--y" "brian" "/nonexistent-wt" {:db-name "d"} nil)))))))

;; ---------------------------------------------------------------------------
;; Worktree-native briefing prose + session-briefing extraction
;; ---------------------------------------------------------------------------

(deftest briefing-prose-is-worktree-native
  (let [s (#'launcher/render-context
           {:project-name "brian" :session-name "fix/x"
            :worktree "/wt" :source-dir "/wt"
            :nrepl-port 5000 :app-port 6000 :pg-port 5599
            :profile {:services :all} :links [] :project-briefing nil})]
    (is (re-find #"Services are already running" s))   ; services note kept
    (is (re-find #"session:status" s))                 ; lifecycle folded in
    (is (not (re-find #"session.home" s)))             ; matches "session home" & "session-home"
    (is (not (re-find #"cd worktree" s)))))

(deftest jj-workspace-briefing-warns-off-bare-git-and-maps-to-jj
  (let [s (@#'launcher/render-edit-location :jj-workspace "/wt")]
    (testing "tells the agent bare git is wrong here"
      (is (re-find #"(?i)do not use bare `?git`?" s))
      (is (re-find #"parent source repo" s)))
    (testing "gives the jj file show mapping that was missing"
      (is (re-find #"jj file show -r <rev> <path>" s))
      (is (re-find #"NOT `git show <rev>:<path>`" s)))))

(deftest jj-workspace-briefing-warns-off-bare-gh-and-derives-the-slug
  ;; Bare `gh` fails from a non-colocated jj workspace the same way bare `git`
  ;; does, and nido has no env-injection point for a spawned agent — so the
  ;; briefing is where an ad-hoc `gh` call gets taught to pass -R.
  (let [s (@#'launcher/render-edit-location :jj-workspace "/wt")]
    (testing "names the failure mode"
      (is (re-find #"(?i)bare `?gh`?" s))
      (is (re-find #"not a git repository" s)))
    (testing "gives the slug derivation and the -R rule"
      (is (re-find #"jj git remote list" s))
      (is (str/includes? s "-R \"$SLUG\"")))
    (testing "warns that PR-resolving subcommands also need an explicit number"
      (is (re-find #"view.*edit.*ready.*merge" s))
      (is (re-find #"argument required when using the --repo flag" s)))
    (testing "repeats that shell variables do not persist between commands"
      (is (re-find #"(?i)do not persist between commands" s)))))

(deftest session-briefing-returns-rendered-string
  ;; session-briefing wires persisted state -> render-context. Stub the reads
  ;; it actually uses, matched to the real session.edn structure that
  ;; rerender-briefing! reads: {:context {:session {:project-dir …}
  ;;                                      :repl {:port …} :app {:port …}
  ;;                                      :pg {:port …}}}.
  (with-redefs [state/read-session        (fn [_id] {:context {:session {:project-dir "/wt"}
                                                               :repl {:port 5000}
                                                               :app  {:port 6000}
                                                               :pg   {:port 5599}}})
                links/read-links          (fn [_id] [])
                launcher/read-project-briefing (fn [_] nil)]
    (is (string? (launcher/session-briefing "brian" "fix/x" "brian--x")))))

(deftest rerender-briefing-writes-claude-and-agents-files
  (let [tmp  (fs/create-temp-dir)
        home (fs/path tmp "home")
        wt   (fs/path tmp "wt")]
    (try
      (fs/create-dirs home)
      (fs/create-dirs wt)
      (with-redefs [state/session-home-dir       (fn [_project _session] (str home))
                    state/read-session           (fn [_id] {:context {:session {:project-dir (str wt)}
                                                                      :repl    {:port 5000}
                                                                      :app     {:port 6000}
                                                                      :pg      {:port 5599}}})
                    links/read-links             (fn [_id] [])
                    launcher/read-project-briefing (fn [_] nil)]
        (launcher/rerender-briefing! "brian" "fix/x" "brian--x")
        (let [claude (slurp (str (fs/path home "CLAUDE.md")))
              agents (slurp (str (fs/path home "AGENTS.md")))]
          (is (= claude agents)
              "Codex and Claude briefing files should stay byte-identical")
          (is (str/includes? agents "Active nido session")
              "AGENTS.md should contain the rendered session briefing")
          (let [override (slurp (str (fs/path wt "AGENTS.override.md")))]
            (is (str/starts-with? override "<!-- nido-managed:")
                "worktree Codex override should be nido-owned")
            (is (str/includes? override "Active nido session")
                "worktree Codex override should contain the rendered session briefing"))))
      (finally
        (fs/delete-tree tmp)))))

(deftest nido-add-dirs-returns-source-dir
  (with-redefs [nido.core/nido-source-dir (fn [] "/opt/nido")]
    (is (= ["/opt/nido"] (launcher/nido-add-dirs)))))
