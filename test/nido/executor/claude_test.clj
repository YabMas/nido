(ns nido.executor.claude-test
  "The Claude Code executor's resumption: what the agent reads as its ask's answer."
  (:require
   [babashka.fs :as fs]
   [clojure.data.json :as json]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.executor.claude :as claude]))

(defn- answer-read
  "The answer the agent reads on resuming with `resolution`, as the run's hook hands it over."
  [resolution]
  (let [dir (str (fs/create-temp-dir))]
    (try
      (spit (str (fs/path dir "claude-session-id")) "sid")
      (let [{:keys [files]} ((:launch (claude/executor {:src "/src"}))
                             {:turn-dir dir :run-dir dir :run 2 :spec {}
                              :resolution resolution :resolved-call "call-1"})]
        (doseq [[f content] files] (spit f content))
        (get-in (claude/hook-decision dir {:tool_use_id "call-1" :tool_input {:question "q"}})
                [:hookSpecificOutput :updatedInput :answer]))
      (finally (fs/delete-tree dir)))))

(deftest a-withdrawn-ask-reads-as-withdrawn-with-its-reason
  (is (= "Withdrawn without an answer: The person answered in chat."
         (answer-read {:refused "The person answered in chat."})))
  (is (= "Withdrawn without an answer: taken-over" (answer-read {:refused :taken-over}))))

(deftest an-answer-and-a-return-read-as-before
  (is (= "Answer: go" (answer-read {:answered "go"})))
  (is (re-find #"^Not answered yet\. .*look again$" (answer-read {:returned "look again"}))))

(deftest a-resolution-file-is-read-back-as-written
  ;; The hook reads the file launch wrote; keep the two in one shape.
  (let [dir (str (fs/create-temp-dir))]
    (try
      (spit (str (fs/path dir "claude-session-id")) "sid")
      (let [{:keys [files]} ((:launch (claude/executor {:src "/src"}))
                             {:turn-dir dir :run-dir dir :run 2 :spec {}
                              :resolution {:refused :taken-over} :resolved-call "call-1"})
            [_ content] (first (filter #(re-find #"resolution\.edn$" (key %)) files))]
        (is (= {:call "call-1" :text "Withdrawn without an answer: taken-over"}
               (edn/read-string content))))
      (finally (fs/delete-tree dir)))))

(deftest every-run-reaches-the-directories-it-was-given
  ;; A unit's turn runs in its bare worktree; nido's skills, which a trigger's turn is told to run,
  ;; reach it only through --add-dir.
  (let [dir (str (fs/create-temp-dir))]
    (try
      (let [argv (fn [opts run]
                   (when (= 2 run) (spit (str (fs/path dir "claude-session-id")) "sid"))
                   (:argv ((:launch (claude/executor (assoc opts :src "/src")))
                           {:turn-dir dir :run-dir dir :run run :spec {:instructions "/triage-slack"}})))]
        (doseq [run [1 2]]
          (let [a (argv {:add-dirs ["/home/me/Code/nido"]} run)
                i (.indexOf ^java.util.List a "--add-dir")]
            (is (= "/home/me/Code/nido" (nth a (inc i))))
            (is (.startsWith ^String (nth a (+ 2 i)) "--") "the next option ends the variadic list")))
        (is (not-any? #{"--add-dir"} (argv {} 1))))
      (finally (fs/delete-tree dir)))))

(deftest a-read-only-turn-may-not-edit-its-working-copy
  (let [dir (str (fs/create-temp-dir))]
    (try
      (let [hooks (fn [spec]
                    (let [{:keys [files]} ((:launch (claude/executor {:src "/src"}))
                                           {:turn-dir dir :run-dir dir :run 1 :spec (merge {:instructions "i"} spec)})]
                      (->> (get files (str (fs/path dir "settings.json"))) json/read-str
                           (#(get-in % ["hooks" "PreToolUse"])) (map #(get % "matcher")))))
            guard #'claude/guard-decision]
        (is (some #{"Edit|MultiEdit|Write|NotebookEdit"} (hooks {:read-only? true :cwd "/w"})))
        (is (= 1 (count (hooks {:cwd "/w"}))) "a turn that may build has the ask hook alone")
        (is (some? (guard "/w" {:tool_input {:file_path "/w/src/a.clj"}})))
        (is (some? (guard "/w" {:cwd "/w" :tool_input {:file_path "src/a.clj"}})) "relative to the tool's cwd")
        (is (nil? (guard "/w" {:tool_input {:file_path "/tmp/report.edn"}})) "a report elsewhere is the skill's")
        (is (nil? (guard "/w" {:tool_input {:file_path "/wx/a"}}))))
      (finally (fs/delete-tree dir)))))

;; ── an ask the agent was woken past ──────────────────────────────────────────

(defn- result-line [m] (json/write-str (merge {:type "result" :subtype "success"} m)))

(def ^:private deferred-ask
  (result-line {:stop_reason "tool_deferred"
                :deferred_tool_use {:id "toolu_01EjtRm65T17YPeFTr218aSd" :name "mcp__nido__ask"
                                    :input {:question "How should the stack handle it?"
                                            :explored "The copy fails with 55P03." :recommendation "Accept it."}}}))

(def ^:private woken-past-it
  ;; ws-20261120-d22b85's run: a Monitor's notification woke the agent past its deferred ask, which
  ;; Claude answered as an internal error, and the agent ended on end_turn.
  [deferred-ask
   (json/write-str {:type "system" :subtype "task_notification" :status "stopped"})
   (json/write-str {:type "system" :subtype "init"})
   (result-line {:stop_reason "end_turn" :result "The ask card may not have been posted."})])

(defn- write-out! [dir lines]
  (fs/create-dirs dir)
  (spit (str (fs/path dir "out")) (str (str/join "\n" lines) "\n")))

(deftest an-ask-the-agent-was-woken-past-is-still-the-runs-outcome
  (let [dir (str (fs/create-temp-dir))
        outcome (:outcome (claude/executor {:src "/src"}))]
    (try
      (write-out! dir woken-past-it)
      (is (= {:outcome :asked
              :call {:id "toolu_01EjtRm65T17YPeFTr218aSd" :question "How should the stack handle it?"
                     :explored "The copy fails with 55P03." :recommendation "Accept it."}}
             (outcome dir)))
      (write-out! dir [deferred-ask])
      (is (= :asked (:outcome (outcome dir))) "a run that stopped at its ask, as before")
      (write-out! dir [(result-line {:stop_reason "end_turn" :result "done"})])
      (is (= :completed (:outcome (outcome dir))))
      (finally (fs/delete-tree dir)))))

(deftest a-resumption-re-offers-a-pending-ask-and-tells-one-that-is-not
  (let [tdir (str (fs/create-temp-dir))
        argv (fn []
               (:argv ((:launch (claude/executor {:src "/src"}))
                       {:turn-dir tdir :run-dir (str (fs/path tdir "run-2")) :run 2 :spec {}
                        :resolution {:answered "Accept it and file a follow-up."}
                        :resolved-call "toolu_01EjtRm65T17YPeFTr218aSd"})))
        prompt (fn [a] (let [i (.indexOf ^java.util.List a "--")] (when (<= 0 i) (nth a (inc i)))))]
    (try
      (spit (str (fs/path tdir "claude-session-id")) "sid")
      (write-out! (str (fs/path tdir "run-1")) [deferred-ask])
      (is (nil? (prompt (argv))) "the call is pending: resuming re-offers it")
      (write-out! (str (fs/path tdir "run-1")) woken-past-it)
      (let [p (prompt (argv))]
        (is (some? p) "no call is pending: the resolution goes in as a prompt")
        (is (str/includes? p "Answer: Accept it and file a follow-up."))
        (is (str/includes? p "toolu_01EjtRm65T17YPeFTr218aSd")))
      (finally (fs/delete-tree tdir)))))

(defn- alive? [pid] (zero? (.waitFor (.start (ProcessBuilder. ^java.util.List ["kill" "-0" (str pid)])))))

(deftest a-run-ends-with-its-background-tasks-at-its-deferral
  (let [dir (str (fs/create-temp-dir))
        pid-file (str (fs/path dir "bg.pid"))
        script (str "sleep 60 & echo $! > " pid-file "; echo '" deferred-ask "'; sleep 60; "
                    "echo '{\"type\":\"result\",\"stop_reason\":\"end_turn\"}'")]
    (try
      (let [t0 (System/currentTimeMillis)
            out (with-out-str (claude/supervise ["sh" "-c" script]))
            bg (parse-long (str/trim (slurp pid-file)))]
        (is (< (- (System/currentTimeMillis) t0) 20000) "it did not wait for the process to exit")
        (is (str/includes? out "tool_deferred") "the output is passed through")
        (is (not (str/includes? out "end_turn")) "nothing woke the agent past its ask")
        (Thread/sleep 200)
        (is (not (alive? bg)) "the background task it started is ended too"))
      (finally (fs/delete-tree dir)))))

(deftest a-turn-with-nobody-to-ask-is-offered-no-ask-tool
  (let [dir (str (fs/create-temp-dir))]
    (try
      (let [launch (fn [spec] ((:launch (claude/executor {:src "/src"}))
                               {:turn-dir dir :run-dir dir :run 1 :spec (assoc spec :instructions "/review-queue")}))
            servers (fn [{:keys [files]}]
                      (-> (get files (str (fs/path dir "mcp.json"))) (json/read-str :key-fn keyword) :mcpServers))
            disallowed (fn [{:keys [argv]}]
                         (let [i (.indexOf ^java.util.List argv "--disallowedTools")]
                           (set (take-while #(not (str/starts-with? % "--")) (drop (inc i) argv)))))]
        (is (contains? (servers (launch {})) :nido))
        (is (not (contains? (disallowed (launch {})) "mcp__nido__ask")))
        (is (not (contains? (servers (launch {:ask? false})) :nido)))
        (is (contains? (disallowed (launch {:ask? false})) "mcp__nido__ask")))
      (finally (fs/delete-tree dir)))))
