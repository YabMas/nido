(ns nido.review.claude-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core]
   [nido.platform.project]
   [nido.review.claude :as claude]))

(defn- argv [opts]
  (vec (rest (claude/claude-argv (merge {:cwd "/w" :schema "{}" :log-path "/w/l.log"
                                         :prompt "review this"}
                                        opts)))))

(defn- flag-value [args flag]
  (second (drop-while #(not= flag %) args)))

(deftest a-claude-reviewer-cannot-write
  (let [args (argv {})]
    (testing "the posture comes from the CLI, and user settings cannot widen it"
      (is (some #{"--restricted"} args)
          "--restricted ignores the settings files an interactive `allow` rule lives in")
      (is (= "dontAsk" (flag-value args "--permission-mode"))
          "anything not allowlisted is denied, not left waiting for a person")
      (is (not (some #{"--dangerously-skip-permissions"} args))
          "the fixer's launch flag is the one thing a reviewer must never carry")
      (is (= "user,project" (flag-value args "--setting-sources"))
          "a worktree's settings.local.json carries the session baseline, not for a one-shot call"))
    (testing "the only shell it gets is the two jj reads the review prompt names, and grep"
      (is (= "Bash,Read,Grep,Glob" (flag-value args "--tools")))
      (is (= ["Bash(jj --ignore-working-copy diff:*)"
              "Bash(jj --ignore-working-copy file show:*)"
              "Bash(grep:*)"]
             (vec (rest (drop-while #(not= "--allowedTools" %) args))))
          "--allowedTools is variadic, so it is last — an option after it would be read as a rule"))))

(deftest a-claude-reviewer-may-read-the-directories-it-is-given
  (let [args (argv {:add-dirs ["/Code/document-service" "/runs/r1"]})]
    (is (= [["--add-dir" "/Code/document-service"] ["--add-dir" "/runs/r1"]]
           (->> args (partition 2 1) (filter #(= "--add-dir" (first %))) (mapv vec)))
        "--restricted confines the file tools to cwd; a record citing a second repo is unreadable without these")
    (is (= "--allowedTools" (first (drop-while #(not= "--allowedTools" %) args))))
    (is (= "Bash(grep:*)" (last args)) "--allowedTools stays last")))

(deftest read-roots-are-the-registered-checkouts-and-the-run-dir
  (let [tmp (str (fs/create-temp-dir))
        a (str (fs/create-dirs (fs/path tmp "a")))
        run (str (fs/create-dirs (fs/path tmp "run")))]
    (with-redefs [nido.platform.core/nido-root (constantly (str tmp "/no-nido"))
                  nido.platform.project/list-projects
                  (constantly {"read-roots-a" {:directory a} "gone" {:directory (str tmp "/missing")}})]
      (is (= [a run] (claude/read-roots (str run "/round-1.log")))
          "a project whose checkout is gone adds nothing"))
    (with-redefs [nido.platform.core/nido-root (constantly (str tmp "/no-nido"))
                  nido.platform.project/list-projects (fn [] (throw (ex-info "unreadable" {})))]
      (is (= [run] (claude/read-roots (str run "/round-1.log")))
          "an unreadable registry never fails the review"))))

(defn- git! [dir & args]
  (apply p/shell {:dir (str dir) :out :string :err :string}
         "git" "-c" "user.email=t@t" "-c" "user.name=t" args))

(defn- argv-echoing-claude
  "A `claude` that prints each argument it was launched with on its own line — into the log, which
   is where run-claude! sends stdout."
  [dir]
  (let [bin (str (fs/path dir "fake-claude"))]
    (spit bin "#!/bin/sh\nprintf '%s\\n' \"$@\"\n")
    (p/shell "chmod" "+x" bin)
    bin))

(deftest a-reviewer-launched-over-a-second-repos-worktree-may-read-it-and-the-ledgers
  ;; BR-7085 (brian ws-20261116-325d89, entry 225): the design changes brian and document-service,
  ;; and names the latter's files in ~/Code/document-service-br-7085 — a git worktree added by hand
  ;; BESIDE the registered checkout ~/Code/document-service. read-roots granted the checkout only,
  ;; so every Read and Glob of the worktree, and of the workstream's ledger entries under
  ;; ~/.nido/projects, was refused, and six claims went out unchecked.
  (let [home (str (fs/create-temp-dir))
        svc  (str (fs/create-dirs (fs/path home "Code" "document-service")))
        wt   (str (fs/path home "Code" "document-service-br-7085"))
        loose (str (fs/create-dirs (fs/path home "Code" "unregistered" "src")))
        nido (str (fs/create-dirs (fs/path home ".nido")))
        projects (str (fs/create-dirs (fs/path nido "projects")))
        runs (str (fs/create-dirs (fs/path nido "runs")))
        run  (str (fs/create-dirs (fs/path runs "design-loop-1")))
        brian (str (fs/create-dirs (fs/path home "Code" "brian")))
        _ (do (git! svc "init" "-q" "-b" "main")
              (git! svc "commit" "-q" "--allow-empty" "-m" "root")
              (git! svc "worktree" "add" "-q" "-b" "fix/BR-7085" wt)
              (git! (fs/parent loose) "init" "-q"))
        prompt (str "Claims rest on ~/Code/document-service-br-7085/src/document_service/html_math.clj:41"
                    " and on " loose "/a.clj:3; entry 145 is under ~/.nido/projects.")]
    (with-redefs [claude/home-dir (constantly home)
                  nido.platform.core/nido-root (constantly nido)
                  nido.platform.project/list-projects
                  (constantly {"read-roots-brian" {:directory brian}
                               "read-roots-conversion" {:directory svc}})]
      (let [roots (set (map (comp str fs/canonicalize) (claude/read-roots (str run "/round-1.log") prompt)))
            canon (comp str fs/canonicalize)]
        (testing "the worktree beside the registered checkout, which git names"
          (is (contains? roots (canon wt))))
        (testing "a repo nido does not drive, because the prompt names a file in it"
          (is (contains? roots (canon (fs/parent loose))))
          (is (= [(str (fs/parent loose))] (claude/cited-repos (str loose "/a.clj:3")))))
        (testing "the ledgers and runs it is asked to cite"
          (is (contains? roots (canon projects)))
          (is (contains? roots (canon runs))))
        (is (contains? roots (canon brian))))
      (testing "the launched reviewer is given every one of them"
        (let [log (str run "/round-1.log")
              _   (claude/run-claude! {:cwd brian :schema-path (doto (str run "/s.json") (spit "{}"))
                                       :out-path (str run "/out.json") :log-path log
                                       :prompt prompt :claude-bin (argv-echoing-claude home)
                                       :budget-ms 30000})
              args (str/split-lines (slurp log))
              dirs (set (map second (filter #(= "--add-dir" (first %)) (partition 2 1 args))))]
          (is (contains? dirs wt) "the second repo's worktree")
          (is (contains? dirs projects) "the workstream's ledger entries"))))))

(deftest refused-reads-are-the-file-tool-denials-in-the-stream
  (let [dir (str (fs/create-temp-dir))
        log (str (fs/path dir "r.log"))
        den (fn [tool input] {:tool_name tool :tool_use_id "x" :tool_input input})]
    ;; The denials design-loop-b1f5a000's round 2 ended on, as the CLI listed them.
    (spit log (str "{\"type\":\"system\"}\n"
                   (json/generate-string
                    {:type "result" :is_error false
                     :permission_denials
                     [(den "Bash" {:command "ls /home/yabmas/Code/"})
                      (den "Glob" {:pattern "src/document_service/*.clj"
                                   :path "/home/yabmas/Code/document-service-br-7085"})
                      (den "Read" {:file_path "/home/yabmas/Code/document-service-br-7085/src/document_service/html_math.clj"})
                      (den "Read" {:file_path "/home/yabmas/Code/document-service-br-7085/src/document_service/html_math.clj"})]})
                   "\n"))
    (is (= ["/home/yabmas/Code/document-service-br-7085"
            "/home/yabmas/Code/document-service-br-7085/src/document_service/html_math.clj"]
           (claude/refused-reads log))
        "a refused shell command is the narrowed shell, not a missing grant")
    (is (= [] (claude/refused-reads (str (fs/path dir "absent.log")))))))

(deftest a-claude-reviewer-runs-on-opus-unless-told-otherwise
  (is (= "opus" (flag-value (argv {}) "--model")))
  (is (= "sonnet" (flag-value (argv {:model "sonnet"}) "--model"))))

(deftest the-prompt-goes-in-on-stdin-with-the-tool-limits-after-it
  (let [[opts & args] (claude/claude-argv {:cwd "/w" :schema "{\"type\":\"object\"}"
                                           :log-path "/w/l.log" :prompt "review this"})]
    (is (str/starts-with? (:in opts) "review this"))
    (is (str/includes? (:in opts) "Anything else is refused")
        "the shared prompt names rg and cat, which this reviewer's shell refuses")
    (is (not (some #{"review this"} args)) "not an argument, where its size is bounded")
    (is (= "{\"type\":\"object\"}" (flag-value (vec args) "--json-schema"))
        "the schema's text, inline")))

(defn- fake-claude
  "A `claude` that prints `events` as stream-json and exits `exit`."
  [dir events exit]
  (let [bin (str (fs/path dir "claude"))]
    (spit bin (str "#!/bin/sh\ncat > /dev/null\n"
                   (apply str (for [e events]
                                (str "printf '%s\\n' '" (json/generate-string e) "'\n")))
                   "exit " exit "\n"))
    (p/shell "chmod" "+x" bin)
    bin))

(defn- run [dir events exit]
  (let [paths {:schema-path (str (fs/path dir "schema.json"))
               :out-path    (str (fs/path dir "out.json"))
               :log-path    (str (fs/path dir "round.log"))}]
    (spit (:schema-path paths) "{\"type\":\"object\"}")
    (assoc paths :result (claude/run-claude!
                          (assoc paths :cwd (str dir) :prompt "p"
                                 :claude-bin (fake-claude dir events exit))))))

(deftest a-structured-answer-is-written-where-run-codex-would-write-it
  (let [dir (fs/create-temp-dir)]
    (try
      (let [{:keys [out-path result]}
            (run dir [{:type "system" :subtype "init"}
                      {:type "result" :is_error false
                       :structured_output {:findings [] :overall_correctness "correct"}}]
                 0)]
        (is (= {:exit 0 :timed-out? false} result))
        (is (= {:findings [] :overall_correctness "correct"}
               (json/parse-string (slurp out-path) true))))
      (finally (fs/delete-tree dir)))))

(deftest a-failed-run-writes-no-answer-and-ends-its-log-on-its-own-words
  (let [dir (fs/create-temp-dir)]
    (try
      (let [{:keys [out-path log-path result]}
            (run dir [{:type "result" :is_error true
                       :result "Claude AI usage limit reached|1790000000"}]
                 1)]
        (is (= {:exit 1 :timed-out? false} result))
        (is (not (fs/exists? out-path))
            "an absent answer is how both callers tell a failed review from an empty one")
        (is (= "Claude AI usage limit reached|1790000000"
               (last (str/split-lines (slurp log-path))))
            "the last line is what `codex/unavailability` classifies"))
      (finally (fs/delete-tree dir)))))

(deftest a-clean-exit-with-no-structured-answer-is-no-answer
  (let [dir (fs/create-temp-dir)]
    (try
      (let [{:keys [out-path]} (run dir [{:type "result" :is_error false :result "done"}] 0)]
        (is (not (fs/exists? out-path))
            "a turn that ended in prose answered nothing the schema asked for"))
      (finally (fs/delete-tree dir)))))
