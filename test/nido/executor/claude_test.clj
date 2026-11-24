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
