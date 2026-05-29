(ns nido.session.launcher-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.core :as core]
   [nido.session.launcher :as launcher]))

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
