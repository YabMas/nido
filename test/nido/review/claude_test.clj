(ns nido.review.claude-test
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
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
          "the fixer's launch flag is the one thing a reviewer must never carry"))
    (testing "the only shell it gets is the two jj reads the review prompt names, and grep"
      (is (= "Bash,Read,Grep,Glob" (flag-value args "--tools")))
      (is (= ["Bash(jj --ignore-working-copy diff:*)"
              "Bash(jj --ignore-working-copy file show:*)"
              "Bash(grep:*)"]
             (vec (rest (drop-while #(not= "--allowedTools" %) args))))
          "--allowedTools is variadic, so it is last — an option after it would be read as a rule"))))

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
    (fs/set-posix-file-permissions bin "rwxr-xr-x")
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
        (is (= {:exit 0} result))
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
        (is (= {:exit 1} result))
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
