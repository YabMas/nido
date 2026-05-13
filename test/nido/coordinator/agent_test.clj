(ns nido.coordinator.agent-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.agent :as agent]
   [nido.coordinator.state :as cstate]))

(def fake-claude
  (str (fs/canonicalize "resources/test/fake-claude/claude")))

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
