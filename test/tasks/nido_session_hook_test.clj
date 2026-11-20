(ns tasks.nido-session-hook-test
  (:require
   [babashka.fs :as fs]
   [babashka.process]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.coordinator.record.session-events :as events]
   [tasks.nido-session-hook :as hook]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root (constantly (str tmp))] (f))
         (finally (fs/delete-tree tmp)))))

(deftest each-hook-records-the-facts-the-driver-reads
  (is (= {:event :prompt :prompt "go [nido-send t1]" :cwd "/w" :permission-mode "auto"}
         (hook/->event "UserPromptSubmit" {:prompt "go [nido-send t1]" :cwd "/w"
                                           :permission_mode "auto" :transcript_path "/t"})))
  (is (= {:event :stop :last-message "done" :background-tasks 2 :crons 0
          :cwd nil :permission-mode "auto"}
         (hook/->event "Stop" {:last_assistant_message "done" :background_tasks [{} {}]
                               :session_crons [] :permission_mode "auto"})))
  (is (= {:event :stop :failure "server_error" :last-message "API Error: Connection lost"
          :cwd "/w" :permission-mode nil}
         (hook/->event "StopFailure" {:error "server_error" :last_assistant_message "API Error: Connection lost"
                                      :cwd "/w"}))
      "a turn an API error ended is over all the same")
  (is (= :permission-denied (:event (hook/->event "PermissionDenied" {:tool_name "Bash"}))))
  (is (= :notification (:event (hook/->event "Notification" {:notification_type "permission_prompt"}))))
  (is (= {:event :session-end :reason "prompt_input_exit" :cwd "/w" :permission-mode nil}
         (hook/->event "SessionEnd" {:reason "prompt_input_exit" :cwd "/w"}))
      "an end keeps the reason Claude Code gives")
  (is (nil? (hook/->event "PreToolUse" {})) "a hook it does not record"))

(deftest a-session-s-end-lands-in-its-log-with-its-reason
  (with-tmp
    (fn []
      (hook/hook* "SessionEnd" (json/generate-string {:session_id "s1" :reason "other" :cwd "/w"}))
      (is (= [[:session-end "other"]]
             (map (juxt :event :reason) (:events (events/events-since "s1" nil))))))))

(deftest a-long-prompt-is-clipped
  (is (= 4000 (count (:prompt (hook/->event "UserPromptSubmit" {:prompt (apply str (repeat 5000 "x"))}))))))

(deftest a-long-prompt-keeps-its-send-token
  (let [p (:prompt (hook/->event "UserPromptSubmit"
                                 {:prompt (str (apply str (repeat 5000 "x")) "\n[nido-send t9]")}))]
    (is (str/ends-with? p "[nido-send t9]") "the token nido appends survives the clip")
    (is (str/starts-with? p "xxx"))))

(deftest a-recorded-hook-lands-in-its-sessions-log
  (with-tmp
    (fn []
      (is (nil? (hook/hook* "UserPromptSubmit"
                            (json/generate-string {:session_id "s1" :prompt "hi"}))))
      (is (= ["hi"] (map :prompt (:events (events/events-since "s1" nil))))))))

(deftest session-start-gives-the-briefing-whatever-it-is-handed
  (doseq [raw [(json/generate-string {:session_id "s1" :source "startup"}) "not json" nil]]
    (let [out (some-> (with-tmp #(hook/hook* "SessionStart" raw)) (json/parse-string true))]
      (testing (pr-str raw)
        (is (= "SessionStart" (get-in out [:hookSpecificOutput :hookEventName])))
        (is (str/includes? (get-in out [:hookSpecificOutput :additionalContext]) "nido"))))))

(deftest nothing-it-is-handed-makes-it-throw
  (with-tmp
    (fn []
      (doseq [raw ["" "{" "[]" (json/generate-string {:session_id 7}) nil]]
        (is (nil? (hook/hook* "Stop" raw)) (pr-str raw))))))

(deftest the-notion-token-reaches-the-session-s-shell-and-no-one-else
  (let [dir (fs/create-temp-dir)
        f   (str (fs/path dir "sessionstart-hook-0.sh"))]
    (try
      (spit f "export ALREADY=there\n")
      (fs/set-posix-file-permissions f "rw-rw-r--")
      (is (true? (hook/export-notion-token! f "ntn_it's")))
      (testing "what was there stays, and a shell reads the token back exactly"
        (is (str/starts-with? (slurp f) "export ALREADY=there\n"))
        (is (= "ntn_it's|there"
               (str/trim (:out (babashka.process/sh
                                ["sh" "-c" (str ". '" f "'; printf '%s|%s' \"$NOTION_TOKEN\" \"$ALREADY\"")]))))))
      (testing "the file is its owner's alone once it holds a token"
        (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions f)))))
      (finally (fs/delete-tree dir)))))

(deftest without-a-file-or-a-token-nothing-is-written
  (let [dir (fs/create-temp-dir)
        f   (str (fs/path dir "env.sh"))]
    (try
      (is (false? (hook/export-notion-token! nil "ntn_x")))
      (is (false? (hook/export-notion-token! f nil)))
      (is (false? (hook/export-notion-token! f "  ")))
      (is (not (fs/exists? f)))
      (is (false? (hook/export-notion-token! (str (fs/path dir "no" "such" "dir" "env.sh")) "ntn_x")))
      (finally (fs/delete-tree dir)))))
