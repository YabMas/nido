(ns nido.session.launcher-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
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
