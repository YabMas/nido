(ns nido.executor.claude-test
  "The Claude Code executor's resumption: what the agent reads as its ask's answer."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
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
