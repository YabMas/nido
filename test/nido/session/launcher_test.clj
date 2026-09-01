(ns nido.session.launcher-test
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.config :as config]
   [nido.platform.core :as core]
   [nido.design.check]
   [nido.platform.project]
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

(deftest render-context-includes-shipping-doctrine
  (let [doc (@#'launcher/render-context base-ctx)]
    (testing "the condensed doctrine is present"
      (is (str/includes? doc "## Shipping doctrine"))
      (is (str/includes? doc "one claim")
          "should state the unit a batch asserts")
      (is (str/includes? doc "Layer:")
          "should name the trailer so commits carry it"))
    (testing "both axes point at their skill"
      (is (str/includes? doc "/stack")
          "vertical axis should cite /stack")
      (is (str/includes? doc "/spin-out")
          "horizontal axis should cite /spin-out"))
    (testing "the trailer vocabulary is spelled out"
      (is (str/includes? doc "mechanical"))
      (is (str/includes? doc "structural"))
      (is (str/includes? doc "behavioral")))
    (testing "all four destinations are named"
      (is (str/includes? doc "this layer"))
      (is (str/includes? doc "another layer"))
      (is (str/includes? doc "spun out"))
      (is (str/includes? doc "declined")))
    (testing "ordering: doctrine sits before the lifecycle section"
      (let [doctrine-idx  (str/index-of doc "## Shipping doctrine")
            lifecycle-idx (str/index-of doc "## Lifecycle")]
        (is (and doctrine-idx lifecycle-idx))
        (is (< doctrine-idx lifecycle-idx)
            "doctrine must precede lifecycle")))))

(deftest render-context-includes-comment-doctrine
  (let [doc (@#'launcher/render-context base-ctx)]
    (testing "the summary states what a comment must CARRY, not only what it must not say"
      (is (str/includes? doc "## Comments"))
      (is (str/includes? doc "carrying what the code cannot"))
      (is (str/includes? doc "precision"))
      (is (str/includes? doc "intuition")))
    (testing "the abstraction test is present — the one rule that changes the code"
      (is (str/includes? doc "the abstraction is wrong")
          "an awkward interface comment must indict the abstraction, not the prose"))
    (testing "the repair scope is bounded in both directions"
      (is (str/includes? doc "What you touch, you own")
          "a change must fix the comments on the code it changed")
      (is (str/includes? doc "stop at the edge")
          "and must not sweep past it — the bound is what makes the rule safe"))
    (testing "the pointer resolves from any session, and names what reviews against it"
      (is (str/includes? doc "~/Code/nido/docs/reference/comments.md")
          "the doctrine lives in the root checkout, which is already on --add-dir")
      (is (str/includes? doc "lane-comments")))
    (testing "spelling stays the project's"
      (is (str/includes? doc "owns the spelling")))
    (testing "the briefing carries the summary, never the whole doctrine"
      ;; A regression here is silent and expensive: it inflates EVERY session's
      ;; context. The doctrine is ~200 lines; anything near that has been pasted.
      ;; The span ends at the NEXT doctrine section, not at "## Lifecycle" — a
      ;; sibling landing in between would otherwise be measured as this one.
      (let [start (str/index-of doc "## Comments")
            end   (str/index-of doc "## Commit messages and PR descriptions")]
        (is (and start end (< start end)))
        (is (< (count (str/split-lines (subs doc start end))) 45)
            "the comment section should be a pointer plus a summary")))))

(deftest render-context-includes-description-doctrine
  (let [doc (@#'launcher/render-context base-ctx)]
    (testing "the reachability test is stated, with the artifact that inverts it"
      (is (str/includes? doc "## Commit messages and PR descriptions"))
      (is (str/includes? doc "cannot get that")
          "the rule is about what a reader can reach, not about length")
      (is (str/includes? doc "one laptop's state directory")
          "the ledger is machine-local, so the body is the only durable copy —
           this is what stops 'do not repeat yourself' being applied to it"))
    (testing "the subject-line rules a writer cannot recover afterwards"
      (is (str/includes? doc "If applied, this commit will"))
      (is (str/includes? doc "never past 72")))
    (testing "narration is evicted, but a rejected alternative is not"
      (is (str/includes? doc "never your route to it"))
      (is (str/includes? doc "MERITS")
          "an alternative rejected on the merits is design, not history"))
    (testing "the budget is a smell, and an overrun can indict the batch"
      (is (str/includes? doc "never truncate")
          "the budget is a smell — an overrun is re-read, not cut to fit")
      (is (str/includes? doc "too big")
          "an undescribable change is a /stack finding, not a prose finding"))
    (testing "the pointer resolves from any session, and the gap is stated"
      (is (str/includes? doc "~/Code/nido/docs/reference/descriptions.md")
          "the doctrine lives in the root checkout, which is already on --add-dir")
      (is (str/includes? doc "Nothing\nchecks it")
          "no lane and no gate — the briefing must say so rather than imply cover"))
    (testing "the briefing carries the summary, never the whole doctrine"
      (let [start (str/index-of doc "## Commit messages and PR descriptions")
            end   (str/index-of doc "## Lifecycle")]
        (is (and start end (< start end)))
        (is (< (count (str/split-lines (subs doc start end))) 45)
            "the description section should be a pointer plus a summary")))))

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
;; Session-home .claude composition (project entries + nido native skills/agents)
;; ---------------------------------------------------------------------------

(defn- fake-session-home!
  "A session home with the `worktree` symlink the launcher relies on, plus a
   worktree `.claude` holding one skill, one agent and one plain settings file.
   Returns the {:home :wt} paths."
  [tmp]
  (let [home (fs/path tmp "home")
        wt   (fs/path tmp "wt")]
    (spit (str (fs/path (doto (fs/path wt ".claude") fs/create-dirs) "settings.json")) "{}")
    (fs/create-dirs (fs/path wt ".claude" "skills" "e2e"))
    (spit (str (fs/path wt ".claude" "skills" "e2e" "SKILL.md")) "project e2e")
    (fs/create-dirs (fs/path wt ".claude" "agents"))
    (spit (str (fs/path wt ".claude" "agents" "lane-malli.md")) "project lane-malli")
    (fs/create-dirs home)
    (fs/create-sym-link (fs/path home "worktree") (str wt))
    {:home home :wt wt}))

(defn- native-skill! [tmp nm body]
  (let [d (fs/path tmp "nido" "skills" nm)]
    (fs/create-dirs d)
    (spit (str (fs/path d "SKILL.md")) body)
    (str d)))

(defn- native-agent! [tmp nm body]
  (let [d (fs/path tmp "nido" "agents")]
    (fs/create-dirs d)
    (spit (str (fs/path d nm)) body)
    (str (fs/path d nm))))

(deftest compose-claude-dir-merges-project-and-nido-entries
  (let [tmp (fs/create-temp-dir)]
    (try
      (let [{:keys [home]} (fake-session-home! tmp)
            skill (native-skill! tmp "local-ci" "nido local-ci")
            agent (native-agent! tmp "lane-comments.md" "nido lane-comments")]
        (@#'launcher/compose-claude-dir! (str home) {"skills" [skill] "agents" [agent]})

        (testing ".claude is a real directory, not a symlink"
          (is (fs/directory? (fs/path home ".claude")))
          (is (not (fs/sym-link? (fs/path home ".claude")))))
        (testing "unmerged entries link straight through and resolve"
          (is (= "{}" (slurp (str (fs/path home ".claude" "settings.json"))))))
        (testing "skills/ merges the project's with nido's natives"
          (is (= "project e2e"
                 (slurp (str (fs/path home ".claude" "skills" "e2e" "SKILL.md")))))
          (is (= "nido local-ci"
                 (slurp (str (fs/path home ".claude" "skills" "local-ci" "SKILL.md"))))))
        (testing "agents/ is merged the same way, not re-exposed as one symlink"
          (is (fs/directory? (fs/path home ".claude" "agents")))
          (is (not (fs/sym-link? (fs/path home ".claude" "agents"))))
          (is (= "project lane-malli"
                 (slurp (str (fs/path home ".claude" "agents" "lane-malli.md")))))
          (is (= "nido lane-comments"
                 (slurp (str (fs/path home ".claude" "agents" "lane-comments.md")))))))
      (finally (fs/delete-tree tmp)))))

(deftest compose-claude-dir-injects-agents-into-a-project-with-none
  ;; The claim the merge exists for: a nido-owned reviewer reaches a project
  ;; that ships no agents of its own (babel, fukan) without that project
  ;; carrying a copy of it.
  (let [tmp (fs/create-temp-dir)]
    (try
      (let [home  (fs/path tmp "home")
            wt    (fs/path tmp "wt")
            agent (native-agent! tmp "lane-comments.md" "nido lane-comments")]
        (fs/create-dirs (fs/path wt ".claude"))          ; a .claude with no agents/ at all
        (fs/create-dirs home)
        (fs/create-sym-link (fs/path home "worktree") (str wt))

        (@#'launcher/compose-claude-dir! (str home) {"agents" [agent]})

        (is (= "nido lane-comments"
               (slurp (str (fs/path home ".claude" "agents" "lane-comments.md")))))
        (testing "the project's tree gains nothing — the injection is composition-only"
          (is (not (fs/exists? (fs/path wt ".claude" "agents"))))))
      (finally (fs/delete-tree tmp)))))

(deftest compose-claude-dir-rebuild-preserves-project-source
  ;; CRITICAL: a rebuild must NEVER follow a symlink into the worktree and
  ;; destroy the project's real .claude. Two shapes can point there — `.claude`
  ;; itself, when a session home still carries the single-symlink form, and
  ;; every entry of a merged subdir. So this composes from that form, composes
  ;; again, and asserts the project's own skills AND agents survived both.
  (let [tmp (fs/create-temp-dir)]
    (try
      (let [{:keys [home wt]} (fake-session-home! tmp)
            skill   (native-skill! tmp "local-ci" "nido")
            agent   (native-agent! tmp "lane-comments.md" "nido")
            natives {"skills" [skill] "agents" [agent]}]
        ;; old form: .claude is a single symlink into the worktree
        (fs/delete-tree (fs/path home ".claude"))
        (fs/create-sym-link (fs/path home ".claude") "worktree/.claude")

        (@#'launcher/compose-claude-dir! (str home) natives)   ; convert
        (@#'launcher/compose-claude-dir! (str home) natives)   ; rebuild

        (testing "the worktree's real .claude survived both rebuilds"
          (is (= "project e2e"
                 (slurp (str (fs/path wt ".claude" "skills" "e2e" "SKILL.md")))))
          (is (= "project lane-malli"
                 (slurp (str (fs/path wt ".claude" "agents" "lane-malli.md"))))))
        (testing "composed entries still resolve after the rebuild"
          (is (fs/exists? (fs/path home ".claude" "skills" "local-ci" "SKILL.md")))
          (is (fs/exists? (fs/path home ".claude" "agents" "lane-comments.md")))
          (is (= "project lane-malli"
                 (slurp (str (fs/path home ".claude" "agents" "lane-malli.md")))))))
      (finally (fs/delete-tree tmp)))))

(deftest nido-native-entries-skips-mirrored-symlinks-and-wrong-shapes
  (let [tmp (fs/create-temp-dir)]
    (try
      (let [src   (fs/path tmp "nido")
            other (fs/path tmp "elsewhere")]
        (fs/create-dirs other)
        (spit (str (fs/path other "mirrored.md")) "project agent")
        ;; skills/: a native dir, a mirrored symlink, and a stray loose file
        (fs/create-dirs (fs/path src ".claude" "skills" "local-ci"))
        (fs/create-sym-link (fs/path src ".claude" "skills" "mirrored") (str other))
        (spit (str (fs/path src ".claude" "skills" "README.md")) "not a skill")
        ;; agents/: a native file, a mirrored symlink, and a stray directory
        (fs/create-dirs (fs/path src ".claude" "agents"))
        (spit (str (fs/path src ".claude" "agents" "lane-comments.md")) "nido")
        (fs/create-sym-link (fs/path src ".claude" "agents" "mirrored.md")
                            (str (fs/path other "mirrored.md")))
        (fs/create-dirs (fs/path src ".claude" "agents" "scratch"))

        (with-redefs [core/nido-source-dir (constantly (str src))]
          (testing "a skill is a real directory — mirrors and loose files are skipped"
            (let [dirs (@#'launcher/nido-native-entries "skills" fs/directory?)]
              (is (= 1 (count dirs)))
              (is (str/includes? (first dirs) "local-ci"))))
          (testing "an agent is a real file — mirrors and stray dirs are skipped"
            (let [files (@#'launcher/nido-native-entries "agents" fs/regular-file?)]
              (is (= 1 (count files)))
              (is (str/includes? (first files) "lane-comments.md"))))
          (testing "a subdir nido does not have yields no natives, not a throw"
            (is (= [] (@#'launcher/nido-native-entries "commands" fs/regular-file?))))))
      (finally (fs/delete-tree tmp)))))

(deftest agent-definition-requires-frontmatter-that-names-an-agent
  ;; nido/.claude/agents holds `architect.md`, a prompt with no frontmatter at
  ;; all. Injecting it would put an entry in every project's composed roster
  ;; that no session can dispatch, so "is a file" is not the selection rule —
  ;; "defines an agent" is.
  (let [tmp (fs/create-temp-dir)]
    (try
      (let [dir (fs/path tmp "agents")]
        (fs/create-dirs dir)
        (spit (str (fs/path dir "defines.md"))
              "---\nname: lane-x\ndescription: \"x\"\n---\n\n# Lane\n")
        (spit (str (fs/path dir "prose.md")) "# Architect\n\nYou are an architect.\n")
        (spit (str (fs/path dir "unnamed.md")) "---\ndescription: \"no name key\"\n---\n")
        (spit (str (fs/path dir "unterminated.md")) "---\nname: lane-y\n")
        (fs/create-dirs (fs/path dir "a-directory"))

        (is (@#'launcher/agent-definition? (fs/path dir "defines.md")))
        (testing "a prose document with no frontmatter defines nothing"
          (is (not (@#'launcher/agent-definition? (fs/path dir "prose.md")))))
        (testing "frontmatter without a name registers nothing"
          (is (not (@#'launcher/agent-definition? (fs/path dir "unnamed.md")))))
        (testing "an unterminated block is not frontmatter"
          (is (not (@#'launcher/agent-definition? (fs/path dir "unterminated.md")))))
        (testing "a directory is never an agent"
          (is (not (@#'launcher/agent-definition? (fs/path dir "a-directory"))))))
      (finally (fs/delete-tree tmp)))))

(deftest nido-native-entries-injects-only-files-that-define-an-agent
  (let [tmp (fs/create-temp-dir)]
    (try
      (let [src (fs/path tmp "nido")]
        (fs/create-dirs (fs/path src ".claude" "agents"))
        (spit (str (fs/path src ".claude" "agents" "lane-comments.md"))
              "---\nname: lane-comments\ndescription: \"c\"\n---\n")
        (spit (str (fs/path src ".claude" "agents" "architect.md"))
              "# Architect\n\nYou are a systems architect.\n")
        (with-redefs [core/nido-source-dir (constantly (str src))]
          (let [files (@#'launcher/nido-native-entries
                       "agents" @#'launcher/agent-definition?)]
            (is (= 1 (count files)))
            (is (str/includes? (first files) "lane-comments.md")))))
      (finally (fs/delete-tree tmp)))))

(deftest compose-claude-dir-native-wins-on-name-clash
  ;; A nido native sharing a name with one of the project's: the native wins and
  ;; compose must NOT throw (a throw would be swallowed by write-artifacts! and
  ;; leave the session with no composed .claude at all).
  (let [tmp (fs/create-temp-dir)]
    (try
      (let [{:keys [home wt]} (fake-session-home! tmp)
            skill (native-skill! tmp "e2e" "nido e2e")
            agent (native-agent! tmp "lane-malli.md" "nido lane-malli")]
        (is (nil? (@#'launcher/compose-claude-dir!
                   (str home) {"skills" [skill] "agents" [agent]}))
            "compose returns without throwing on a name clash")
        (testing "the nido native wins the shared name, in both merged subdirs"
          (is (= "nido e2e"
                 (slurp (str (fs/path home ".claude" "skills" "e2e" "SKILL.md")))))
          (is (= "nido lane-malli"
                 (slurp (str (fs/path home ".claude" "agents" "lane-malli.md"))))))
        (testing "the project's own sources are untouched"
          (is (= "project e2e"
                 (slurp (str (fs/path wt ".claude" "skills" "e2e" "SKILL.md")))))
          (is (= "project lane-malli"
                 (slurp (str (fs/path wt ".claude" "agents" "lane-malli.md")))))))
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
  (with-redefs [nido.platform.core/nido-source-dir (fn [] "/opt/nido")]
    (is (= ["/opt/nido"] (launcher/nido-add-dirs)))))

(deftest shipping-doctrine-tells-a-session-to-baseline-first
  ;; The doctrine text is the only part of this that reaches a session that never
  ;; invokes /design. If the baseline step is not stated here, nothing asks for
  ;; one and the schema requirement lands as an error at the moment of filing —
  ;; too late to have changed how the work was done.
  (let [d @#'launcher/shipping-doctrine-instructions]
    (is (str/includes? d "Baseline before you decide"))
    (is (str/includes? d "BEFORE the design and independent of it"))
    (is (str/includes? d "fillable without knowing the\n  change")
        "the test for what belongs in a baseline, not just its name")
    (is (str/includes? d "load-bearing"))
    (is (str/includes? d "extension point"))
    (is (str/includes? d ":revisit` must name what it breaks"))
    (is (str/includes? d "re-survey, not to supersede")
        "a wrong premise and a wrong design have different remedies")
    (is (str/includes? d "`/design` §4"))))

(deftest render-context-carries-the-declared-design
  (testing "a project that declares a design gets it verbatim in the briefing, because the
            declaration is already written to be read and a summary could drift from it"
    (let [wt (fs/create-temp-dir)]
      (try
        (fs/create-dirs (fs/path wt "canvas"))
        (with-redefs [nido.design.check/describe
                      (constantly {:status :described
                                   :document "(Band Platform \"the floor, depends on nothing\" {:prefix [\"p.\"]})"})
                      nido.design.check/check
                      (fn [& _] (throw (ex-info "the briefing must not run the checker" {})))]
          (let [doc (@#'launcher/render-context (assoc base-ctx :worktree (str wt)))]
            (is (str/includes? doc "## The design this project declares"))
            (is (str/includes? doc "the floor, depends on nothing")
                "verbatim — the docstrings are the half a reader most needs")
            (is (str/includes? doc "bb nido:design:check")
                "the section says how to ask, since it carries no count of its own")))
        (finally (fs/delete-tree wt))))))

(deftest render-context-omits-the-design-section-when-none-is-declared
  (let [wt (fs/create-temp-dir)]
    (try
      (with-redefs [nido.design.check/describe (constantly {:status :unmodelled})]
        (let [doc (@#'launcher/render-context (assoc base-ctx :worktree (str wt)))]
          (is (not (str/includes? doc "## The design this project declares"))
              "most projects nido drives declare nothing, and get a clean briefing")))
      (finally (fs/delete-tree wt)))))

(deftest render-context-says-so-when-the-design-could-not-be-rendered
  (testing "a render that failed gets a section of its own, because omitting it briefs a
            modelled project exactly like an unmodelled one — and the landing gate still
            refuses on a declaration the agent was never shown"
    (let [wt (fs/create-temp-dir)]
      (try
        (with-redefs [nido.design.check/describe
                      (constantly {:status :undecidable
                                   :error "the design did not render within 60s, and nothing narrowed it."})]
          (let [doc (@#'launcher/render-context (assoc base-ctx :worktree (str wt)))]
            (is (str/includes? doc "NOT SHOWN"))
            (is (str/includes? doc "did not render within 60s")
                "the reason is carried, not summarised — it names the way out")
            (is (str/includes? doc "still refuses the landing")
                "the danger is that a gate checks what the briefing could not show")))
        (finally (fs/delete-tree wt))))))
