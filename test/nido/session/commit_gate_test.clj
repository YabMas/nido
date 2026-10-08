(ns nido.session.commit-gate-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.session.commit-gate :as gate]))

(deftest checks-reads-what-a-command-would-publish
  (testing "a push carries its own arguments, to be asked of jj"
    (is (= [{:kind :push :dir "/s" :args ["git" "push" "-b" "feat" "-c" "@-"]}]
           (gate/checks "jj git push -b feat -c @-" "/s")))
    (is (= [["--no-pager" "git" "push"]]
           (map :args (gate/checks "FOO=1 command jj --no-pager git push" "/s")))))
  (testing "a cd earlier on the line, or -R, moves where it runs"
    (is (= ["/s/worktree" "/r"]
           (map :dir (gate/checks "cd worktree && jj git fetch && jj git push; jj -R /r git push" "/s")))))
  (testing "a titled gh pr create or edit, in either spelling"
    (is (= [{:kind :title :dir "/s" :title "Fix the thing"}]
           (gate/checks "gh pr create --title \"Fix the thing\" --body x" "/s")))
    (is (= ["fix: y"] (map :title (gate/checks "gh pr edit 12 --title='fix: y'" "/s")))))
  (testing "what the gate cannot know contributes nothing"
    (is (= [] (gate/checks "gh pr create --title \"$(head -1 msg)\"" "/s")))
    (is (= [] (gate/checks "jj git push -b \"$B\"" "/s")))
    (is (= [] (gate/checks "jj git push --dry-run" "/s")))
    (is (= [] (gate/checks "gh pr create --fill" "/s")))
    (is (= [] (gate/checks "git log | grep push" "/s")))))

(def ^:private conventional-hook
  "#!/bin/sh\ngrep -qE '^(feat|fix)(\\([a-z-]+\\))?: .+' \"$1\" || { echo \"Expected: type(scope): message\"; exit 1; }\n")

(defn- sh! [dir & cmd]
  (let [r (apply p/sh {:dir (str dir)} cmd)]
    (when-not (zero? (:exit r)) (throw (ex-info (str cmd " failed: " (:err r)) r)))
    (:out r)))

(defn- repo!
  "A jj repo backed by a git repo with a bare `origin`. `main` holds a pushed
   commit whose description NO hook accepts — so a check that reached past the
   remote would always block — and the tracked bookmark `b` an unpushed commit
   described `description`. When `hook`, that text is the executable
   commit-msg hook."
  [tmp description hook]
  (let [dir    (fs/path tmp "repo")
        remote (fs/path tmp "origin.git")]
    (fs/create-dirs dir)
    (sh! tmp "git" "init" "-q" "--bare" (str remote))
    (sh! dir "git" "init" "-q")
    (sh! dir "jj" "git" "init" "--colocate")
    (sh! dir "jj" "git" "remote" "add" "origin" (str remote))
    (spit (str (fs/path dir "a")) "a")
    (sh! dir "jj" "describe" "-m" "already pushed, never judged")
    (sh! dir "jj" "bookmark" "create" "main" "-r" "@")
    (sh! dir "jj" "git" "push" "-b" "main")
    (sh! dir "jj" "new" "main")
    (spit (str (fs/path dir "b")) "b")
    (sh! dir "jj" "describe" "-m" description)
    (sh! dir "jj" "bookmark" "create" "b" "-r" "@")
    (sh! dir "jj" "bookmark" "track" "b@origin")
    (sh! dir "jj" "new")
    (when hook
      (let [h (fs/path dir ".git" "hooks" "commit-msg")]
        (fs/create-dirs (fs/parent h))
        (spit (str h) hook)
        (fs/set-posix-file-permissions h "rwxr-xr-x")))
    (str dir)))

(defn- bash [cwd command]
  {:tool_name "Bash" :cwd cwd :tool_input {:command command}})

(deftest verdict-borrows-the-projects-commit-msg-hook
  (let [tmp (fs/create-temp-dir)]
    (try
      (let [dir (repo! tmp "Remove the note" conventional-hook)]
        (testing "a push whose range holds a description the hook rejects is blocked with the hook's output"
          (let [v (gate/verdict (bash dir "jj git push -b b"))]
            (is (str/includes? v "Remove the note"))
            (is (str/includes? v "Expected: type(scope): message"))))
        (testing "from the parent directory, through a cd"
          (is (some? (gate/verdict (bash (str tmp) "cd repo && jj git push")))))
        (testing "a PR title is judged by the same hook"
          (is (some? (gate/verdict (bash dir "gh pr create --title 'Remove the note'"))))
          (is (nil? (gate/verdict (bash dir "gh pr create --title 'fix: remove the note'")))))
        (testing "every way a push selects bookmarks is jj's to decide"
          (is (some? (gate/verdict (bash dir "jj git push --all"))))
          (is (some? (gate/verdict (bash dir "jj git push -c b")))))
        (testing "a conforming description passes"
          (sh! dir "jj" "describe" "-r" "b" "-m" "fix: remove the note")
          (is (nil? (gate/verdict (bash dir "jj git push -b b")))))
        (testing "commands it does not gate pass untouched"
          (is (nil? (gate/verdict (bash dir "jj log"))))
          (is (nil? (gate/verdict {:tool_name "Edit" :cwd dir :tool_input {}})))))
      (finally (fs/delete-tree tmp)))))

(deftest verdict-fails-open
  (let [tmp (fs/create-temp-dir)]
    (try
      (testing "a project with no commit-msg hook is unaffected"
        (let [dir (repo! tmp "Remove the note" nil)]
          (is (nil? (gate/commit-msg-hook dir)))
          (is (nil? (gate/verdict (bash dir "jj git push -b b"))))))
      (testing "a directory that is no repository"
        (is (nil? (gate/verdict (bash (str tmp) "jj git push")))))
      (testing "a cwd that does not exist"
        (is (nil? (gate/verdict (bash "/no/such/dir" "jj git push")))))
      (finally (fs/delete-tree tmp)))))

(deftest with-gate-keeps-every-hook-already-declared
  (let [s (gate/with-gate {:hooks {:Stop [{:hooks [{:type "command" :command "x"}]}]}
                           :permissions {:allow ["Read"]}})]
    (is (= "x" (get-in s [:hooks :Stop 0 :hooks 0 :command])))
    (is (= {:allow ["Read"]} (:permissions s)))
    (is (= "Bash" (get-in s [:hooks :PreToolUse 0 :matcher])))))

(deftest commit-msg-hook-is-found-in-a-plain-git-worktree
  (let [tmp (fs/create-temp-dir)]
    (try
      (let [src (fs/path tmp "src")
            wt  (fs/path tmp "wt")
            h   (fs/path src ".git" "hooks" "commit-msg")]
        (fs/create-dirs src)
        (sh! src "git" "init" "-q")
        (sh! src "git" "-c" "user.email=t@t" "-c" "user.name=t" "commit" "-q" "--allow-empty" "--no-verify" "-m" "init")
        (sh! src "git" "worktree" "add" "-q" (str wt))
        (spit (str h) conventional-hook)
        (fs/set-posix-file-permissions h "rwxr-xr-x")
        (is (= (str (fs/canonicalize h)) (gate/commit-msg-hook (str wt))))
        (is (some? (gate/verdict (bash (str wt) "gh pr create --title 'Bad title'")))))
      (finally (fs/delete-tree tmp)))))
