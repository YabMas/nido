(ns tasks.nido-work-test
  (:require
   [clojure.test :refer [deftest is]]
   [babashka.fs :as fs]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.launcher :as launcher]
   [nido.session.state :as state]
   [tasks.nido-work :as work]))

(deftest builds-claude-invocation-with-injected-flags
  (let [mcp (str (fs/create-temp-file))]   ; exists → --mcp-config included
    (with-redefs [lifecycle/session-from-cwd
                  (fn [] {:project "brian" :session "fix/x"
                          :worktree "/Code/brian/.worktrees/fix/x"
                          :instance-id "brian--x"})
                  launcher/session-briefing (fn [_p _s _i] "BRIEF")
                  state/session-mcp-path    (fn [_i] mcp)
                  launcher/nido-add-dirs    (fn [] ["/opt/nido"])]
      (let [{:keys [cmd dir]} (work/work-cmd* {:claude-bin "claude"})]
        (is (= "/Code/brian/.worktrees/fix/x" dir))
        (is (= "claude" (first cmd)))
        (is (= ["--append-system-prompt" "BRIEF"]
               (->> cmd (drop-while #(not= % "--append-system-prompt")) (take 2))))
        (is (= ["--mcp-config" mcp]
               (->> cmd (drop-while #(not= % "--mcp-config")) (take 2))))
        (is (= ["--add-dir" "/opt/nido"]
               (->> cmd (drop-while #(not= % "--add-dir")) (take 2))))))))

(deftest omits-mcp-when-absent
  (with-redefs [lifecycle/session-from-cwd
                (fn [] {:project "brian" :session "fix/x"
                        :worktree "/wt" :instance-id "brian--x"})
                launcher/session-briefing (fn [_ _ _] "B")
                state/session-mcp-path    (fn [_] "/does/not/exist.json")
                launcher/nido-add-dirs    (fn [] ["/opt/nido"])]
    (is (not (some #{"--mcp-config"} (:cmd (work/work-cmd* {:claude-bin "claude"})))))))

(deftest builds-codex-invocation-with-agents-and-mcp-config
  (let [tmp (fs/create-temp-dir)
        mcp (str (fs/path tmp "mcp.json"))]
    (try
      (spit mcp "{\"mcpServers\":{\"postgres\":{\"command\":\"npx\",\"args\":[\"-y\",\"@modelcontextprotocol/server-postgres\",\"postgresql://u:p@localhost:5432/db\"],\"env\":{}}}}")
      (with-redefs [lifecycle/session-from-cwd
                    (fn [] {:project "brian" :session "fix/x"
                            :worktree "/Code/brian/.worktrees/fix/x"
                            :instance-id "brian--x"})
                    state/session-mcp-path (fn [_i] mcp)
                    launcher/nido-add-dirs (fn [] ["/opt/nido"])]
        (let [{:keys [cmd dir]} (work/work-cmd* {:agent :codex :codex-bin "codex"})]
          (is (= "/Code/brian/.worktrees/fix/x" dir))
          (is (= "codex" (first cmd)))
          (is (= ["--cd" "/Code/brian/.worktrees/fix/x"]
                 (->> cmd (drop-while #(not= % "--cd")) (take 2))))
          (is (= ["--sandbox" "workspace-write"]
                 (->> cmd (drop-while #(not= % "--sandbox")) (take 2))))
          (is (= ["--add-dir" "/opt/nido"]
                 (->> cmd (drop-while #(not= % "--add-dir")) (take 2))))
          (is (some #{"mcp_servers.postgres.command=\"npx\""} cmd))
          (is (some #{"mcp_servers.postgres.args=[\"-y\",\"@modelcontextprotocol/server-postgres\",\"postgresql://u:p@localhost:5432/db\"]"} cmd))))
      (finally
        (fs/delete-tree tmp)))))
