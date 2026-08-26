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
          "--dangerously-skip-permissions" "--resume" "sid-1" "--" "hi"]
         (#'agent/build-cmd {:claude-bin "claude" :first-message "hi"
                             :claude-session-id "sid-1" :resume? true}))
      "resume? routes the recorded id through --resume"))

(deftest build-cmd-without-resume-uses-session-id
  (is (= ["claude" "--print" "--verbose" "--output-format=stream-json"
          "--dangerously-skip-permissions" "--session-id" "sid-1" "--" "hi"]
         (#'agent/build-cmd {:claude-bin "claude" :first-message "hi"
                             :claude-session-id "sid-1"}))
      "the original burst still records under --session-id"))

(deftest build-cmd-guards-prompt-with-option-terminator
  ;; claude 2.x made --add-dir/--mcp-config variadic (<directories...>/<configs...>),
  ;; so a trailing prompt positional gets swallowed as another dir/config unless a
  ;; `--` terminates option parsing first. Regression test for the spawn fail-burst.
  (let [cmd (#'agent/build-cmd {:claude-bin "claude" :first-message "/triage-bug BR-1"
                                :mcp-config "/m.json" :add-dirs ["/a" "/b"]})]
    (is (= "/triage-bug BR-1" (last cmd)) "prompt is the final token")
    (is (= "--" (last (butlast cmd)))
        "`--` immediately precedes the prompt so no variadic flag can swallow it")
    (is (= ["--add-dir" "/a" "--add-dir" "/b" "--" "/triage-bug BR-1"]
           (take-last 6 cmd))
        "the option terminator sits between the variadic flags and the prompt")))

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
                        :budget "5m"
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
                        :budget        "5m"
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
                        :budget "5m"
                        :env {"FAKE_CLAUDE_SESSION_ID" "s" "FAKE_CLAUDE_NUM_TURNS" "4"}})]
          (is (= 4 (:num-turns worked)) "captures num_turns from the result event")
          (is (false? (:result-error? worked))))
        (let [no-op (agent/launch!
                      {:run-id "r1" :cwd (str tmp) :first-message "/triage-bug x"
                       :claude-bin fake-claude
                       :budget "5m"
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
                        :budget        "5m"
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

(deftest launch!-does-not-time-out-inside-its-budget
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (fs/create-dirs (cstate/run-dir "r1"))
        (let [result (agent/launch!
                       {:run-id        "r1"
                        :cwd           (str tmp)
                        :first-message "/x"
                        :claude-bin    fake-claude
                        :budget        "5m"
                        :env           {"FAKE_CLAUDE_SESSION_ID" "s"}})]
          (is (false? (:timed-out? result)))))
      (finally (fs/delete-tree tmp)))))

(deftest launch!-refuses-an-undeclared-or-unreadable-budget
  ;; This replaces a test called launch!-no-timeout-by-default, which asserted
  ;; the behaviour being removed: no budget meant no timer, so the default was
  ;; unbounded. The refusal has to happen BEFORE the spawn — parsed beside the
  ;; timer it arms, it would throw with claude already running and nothing left
  ;; to stop it, which is the state being refused plus an orphan.
  (doseq [[label b reason] [["absent"     nil          :budget-undeclared]
                            ["blank"      ""           :budget-undeclared]
                            ["wrong unit" "1w"         :budget-unreadable]
                            ["prose"      "30 minutes" :budget-unreadable]]]
    (let [e (try (agent/parse-budget-ms b) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e) (str label " must be refused"))
      (is (= reason (:reason (ex-data e))) label)
      (is (seq (:hint (ex-data e))) (str label " must say what to do"))))
  (is (= 28800000 (agent/parse-budget-ms "8h")))
  (is (= 1000 (agent/parse-budget-ms "1s"))))

(deftest build-cmd-includes-mcp-and-add-dirs-when-given
  (let [cmd (#'agent/build-cmd {:claude-bin "claude" :first-message "hi"
                                :mcp-config "/s/mcp.json" :add-dirs ["/opt/nido"]})]
    (is (= ["--mcp-config" "/s/mcp.json"]
           (->> cmd (drop-while #(not= % "--mcp-config")) (take 2))))
    (is (= ["--add-dir" "/opt/nido"]
           (->> cmd (drop-while #(not= % "--add-dir")) (take 2))))
    (is (= "hi" (last cmd))))                      ; positional stays last
  (let [cmd (#'agent/build-cmd {:claude-bin "claude" :first-message "hi"})]
    (is (not (some #{"--mcp-config" "--add-dir"} cmd)))))

(deftest launch!-redirects-stderr-to-err-file-when-given
  (let [tmp (fs/create-temp-dir)]
    (with-redefs [cstate/nido-root (constantly (str tmp))]
      (fs/create-dirs (cstate/run-dir "r-err"))
      (let [err-path (str (fs/path tmp "agent.err.log"))]
        (agent/launch! {:run-id "r-err" :cwd (str tmp)
                        :first-message "/x" :claude-bin fake-claude
                        :budget "5m"
                        :claude-session-id "sid" :err-file err-path})
        (is (fs/exists? err-path) "stderr is captured to the given file")))))

(deftest build-cmd-tools-flag-disables-tools
  (let [cmd (#'agent/build-cmd {:claude-bin "claude" :first-message "hi" :tools ""})]
    (is (= ["--tools" ""]
           (->> cmd (drop-while #(not= % "--tools")) (take 2)))
        "emits --tools with the given (empty) value to disable all tools")
    (is (< (.indexOf cmd "--tools") (.indexOf cmd "--"))
        "--tools precedes the -- option terminator")))

(deftest build-cmd-omits-tools-when-absent
  (let [cmd (#'agent/build-cmd {:claude-bin "claude" :first-message "hi"})]
    (is (not (some #{"--tools"} cmd)) "no --tools flag when :tools is not given")))
