(ns nido.coordinator.agent-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.agent :as agent]
   [nido.coordinator.state :as cstate]))

(def fake-claude
  (str (fs/canonicalize "resources/test/fake-claude/claude")))

(deftest build-cmd-includes-session-id-when-given
  (let [cmd (#'agent/build-cmd {:claude-bin "claude" :first-message "/x hi"
                                :system-prompt nil :claude-session-id "abc-123"})]
    (is (some #{"--session-id"} cmd) "passes --session-id flag")
    (is (some #{"abc-123"} cmd) "passes the id")
    (is (= "/x hi" (last cmd)) "first-message stays the trailing positional arg"))
  (let [cmd (#'agent/build-cmd {:claude-bin "claude" :first-message "/x"
                                :system-prompt nil :claude-session-id nil})]
    (is (not (some #{"--session-id"} cmd)) "omits --session-id when none given")))

(deftest build-cmd-resume-uses-resume-flag
  (is (= ["claude" "--print" "--verbose" "--output-format=stream-json"
          "--dangerously-skip-permissions" "--resume" "sid-1" "hi"]
         (#'agent/build-cmd {:claude-bin "claude" :first-message "hi"
                             :claude-session-id "sid-1" :resume? true}))
      "resume? routes the recorded id through --resume"))

(deftest build-cmd-without-resume-uses-session-id
  (is (= ["claude" "--print" "--verbose" "--output-format=stream-json"
          "--dangerously-skip-permissions" "--session-id" "sid-1" "hi"]
         (#'agent/build-cmd {:claude-bin "claude" :first-message "hi"
                             :claude-session-id "sid-1"}))
      "the original burst still records under --session-id"))

(deftest launch!-returns-the-given-session-id
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (fs/create-dirs (cstate/run-dir "r1"))
        (let [result (agent/launch!
                       {:run-id        "r1"
                        :cwd           (str tmp)
                        :first-message "/x"
                        :claude-bin    fake-claude
                        :claude-session-id "preset-id"
                        :env           {"FAKE_CLAUDE_SESSION_ID" "ignored-emitted-id"}})]
          (is (= "preset-id" (:claude-session-id result))
              "a pre-generated session-id is returned (not the emitted one)")))
      (finally (fs/delete-tree tmp)))))

(deftest launch!-captures-session-id-and-exits-clean
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (fs/create-dirs (cstate/run-dir "r1"))
        (let [result (agent/launch!
                       {:run-id        "r1"
                        :cwd           (str tmp)
                        :first-message "/x hi"
                        :claude-bin    fake-claude
                        :env           {"FAKE_CLAUDE_SESSION_ID" "session-xyz"}})]
          (is (= 0 (:exit-code result)))
          (is (= "session-xyz" (:claude-session-id result)))
          (is (fs/exists? (cstate/run-agent-log "r1"))
              "agent.log captured stream-json output")))
      (finally (fs/delete-tree tmp)))))

(deftest launch!-surfaces-result-event-fields
  ;; launch! pulls num_turns / is_error / result from claude's final `result`
  ;; stream-json event so the caller can detect a no-op exit (zero turns).
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (fs/create-dirs (cstate/run-dir "r1"))
        (let [worked (agent/launch!
                       {:run-id "r1" :cwd (str tmp) :first-message "/x"
                        :claude-bin fake-claude
                        :env {"FAKE_CLAUDE_SESSION_ID" "s" "FAKE_CLAUDE_NUM_TURNS" "4"}})]
          (is (= 4 (:num-turns worked)) "captures num_turns from the result event")
          (is (false? (:result-error? worked))))
        (let [no-op (agent/launch!
                      {:run-id "r1" :cwd (str tmp) :first-message "/triage-bug x"
                       :claude-bin fake-claude
                       :env {"FAKE_CLAUDE_SESSION_ID" "s" "FAKE_CLAUDE_NUM_TURNS" "0"
                             "FAKE_CLAUDE_RESULT_TEXT" "Unknown command: /triage-bug"}})]
          (is (= 0 (:num-turns no-op)) "zero turns surfaced for a no-op exit")
          (is (= "Unknown command: /triage-bug" (:result-text no-op)))))
      (finally (fs/delete-tree tmp)))))

(deftest launch!-records-non-zero-exit
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (fs/create-dirs (cstate/run-dir "r1"))
        (let [result (agent/launch!
                       {:run-id        "r1"
                        :cwd           (str tmp)
                        :first-message "/x"
                        :claude-bin    fake-claude
                        :env           {"FAKE_CLAUDE_EXIT_CODE" "7"}})]
          (is (= 7 (:exit-code result)))))
      (finally (fs/delete-tree tmp)))))

(deftest launch!-times-out-and-sigterms
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (fs/create-dirs (cstate/run-dir "r1"))
        (let [t0 (System/currentTimeMillis)
              result (agent/launch!
                       {:run-id        "r1"
                        :cwd           (str tmp)
                        :first-message "/x"
                        :claude-bin    fake-claude
                        :budget        "1s"
                        :env           {"FAKE_CLAUDE_HANG_MS" "10000"
                                        "FAKE_CLAUDE_SESSION_ID" "s"}})
              elapsed (- (System/currentTimeMillis) t0)]
          (is (true? (:timed-out? result)))
          ;; 1s budget + ≤10s SIGKILL grace; hang would be 10s. Land < 12s.
          (is (< elapsed 12000) (str "took " elapsed "ms"))))
      (finally (fs/delete-tree tmp)))))

(deftest launch!-no-timeout-by-default
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (fs/create-dirs (cstate/run-dir "r1"))
        (let [result (agent/launch!
                       {:run-id        "r1"
                        :cwd           (str tmp)
                        :first-message "/x"
                        :claude-bin    fake-claude
                        :env           {"FAKE_CLAUDE_SESSION_ID" "s"}})]
          (is (false? (:timed-out? result)))))
      (finally (fs/delete-tree tmp)))))
