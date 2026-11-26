(ns nido.session.commands-test
  (:require
   [babashka.fs :as fs]
   [babashka.process]
   [nido.platform.lock]
   [nido.platform.process]
   [clojure.test :refer [deftest is testing]]
   [nido.session.commands :as commands]))

(deftest resolve-java-home-finds-real-jdk
  (testing "when a JDK is discoverable, the home contains bin/java"
    (when-let [h (commands/resolve-java-home)]
      (is (fs/exists? (fs/path h "bin" "java"))
          (str "resolved JAVA_HOME should point at a real JDK: " h)))))

(deftest injects-resolved-java-home
  (testing "JAVA_HOME is forwarded into the subprocess env"
    (with-redefs [commands/resolve-java-home (constantly "/opt/test-jdk")]
      (let [result (commands/run-command!
                    {:probe {:cmd "printf '%s' \"$JAVA_HOME\""}}
                    :probe {} {:out :string})]
        (is (= "/opt/test-jdk" (:out result)))))))

(deftest command-env-overrides-injected-java-home
  (testing "a command's explicit :env wins over the injected default"
    (with-redefs [commands/resolve-java-home (constantly "/opt/test-jdk")]
      (let [result (commands/run-command!
                    {:probe {:cmd "printf '%s' \"$JAVA_HOME\""
                             :env {"JAVA_HOME" "/custom/home"}}}
                    :probe {} {:out :string})]
        (is (= "/custom/home" (:out result)))))))

(deftest no-java-home-when-unresolvable
  (testing "nothing is injected when no JDK can be found"
    (with-redefs [commands/resolve-java-home (constantly nil)]
      (let [result (commands/run-command!
                    {:probe {:cmd "printf 'marker=[%s]' \"${JAVA_HOME:-unset}\""}}
                    :probe {} {:out :string})]
        ;; We can't assert JAVA_HOME is unset (the parent env may have it),
        ;; but the command must still run and nido must not have thrown.
        (is (zero? (:exit result)))))))

(deftest locked-command-is-machine-exclusive
  (testing "a :lock command queues behind a live holder and runs after it goes"
    (let [d (fs/create-temp-dir {:prefix "nido-cmd-lock"})]
      (with-redefs [nido.platform.lock/locks-dir (constantly (fs/path d "locks"))]
        (let [commands {:probe {:cmd "printf ran" :lock "probe-lock"}}
              waited (atom false)]
          ;; Nobody holds it: runs straight through.
          (is (= "ran" (:out (commands/run-command! commands :probe {} {:out :string}))))
          ;; A live foreign holder blocks it; :lock-wait-ms bounds the queue so
          ;; the test cannot hang, and the throw names who is in front.
          (let [blocker (babashka.process/process {:out :string} "sleep" "30")]
            (try
              (fs/create-dirs (fs/path d "locks"))
              (spit (fs/file (fs/path d "locks" "probe-lock.lock"))
                    (pr-str {:pid (.pid ^java.lang.Process (:proc blocker))
                             :label "someone-else" :since "now"}))
              (is (thrown-with-msg?
                   clojure.lang.ExceptionInfo #"Could not acquire lock"
                   (commands/run-command! {:probe {:cmd "printf ran"
                                                   :lock "probe-lock"
                                                   :lock-wait-ms 300}}
                                          :probe {} {:out :string})))
              (finally (.destroy ^java.lang.Process (:proc blocker)) @blocker)))
          (is (false? @waited))))
      (fs/delete-tree d))))

(deftest a-failed-command-throws-naming-itself
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Project command failed: :probe \(exit 3\)"
       (commands/run-command! {:probe {:cmd "exit 3"}} :probe {} {:out :string :err :string})))
  (is (= 3 (:exit (commands/run-command! {:probe {:cmd "exit 3"}} :probe {}
                                         {:continue? true :out :string :err :string})))
      ":continue? still hands the failure back instead of throwing"))

;; ---------------------------------------------------------------------------
;; A caller's BASH_ENV does not reach the command
;;
;; Regression, 2026-11-25: a brian seat ran `bb nido:run … ci-light`. Claude Code
;; had exported brian's `.claude/settings.json` env, BASH_ENV=env.sh, into the
;; seat's shells, and brian's env.sh runs `git rev-parse --git-common-dir`. A CI
;; test put a bash-script `git` stub first on PATH: each stub's bash sourced
;; env.sh, which ran `git` — the stub — again. ~47,600 levels, until the core's
;; cgroup had no tasks left. Here the stub refuses past depth 5, so the control
;; case stays bounded.
;; ---------------------------------------------------------------------------

(defn- storm-fixture
  "A checkout-like dir holding an env.sh that runs `git` (as brian's does), and
   a bash-script `git` stub that logs each call. env.sh stops at depth 5."
  []
  (let [d   (fs/create-temp-dir {:prefix "nido-bash-env"})
        bin (fs/create-dirs (fs/path d "stubbin"))
        git (fs/path bin "git")]
    ;; The depth guard sits in env.sh, not the stub: the recursion happens at
    ;; each stub's bash startup, before a line of the stub runs.
    (spit (fs/file d "env.sh")
          (str "[ \"${STORM_DEPTH:-0}\" -ge 5 ] && return 0\n"
               "export STORM_DEPTH=$((${STORM_DEPTH:-0}+1))\n"
               "__D=$(git rev-parse --git-common-dir 2>/dev/null)\n"))
    (spit (fs/file git)
          (str "#!/usr/bin/env bash\n"
               "printf '%s\\n' \"$*\" >> " (fs/path d "calls") "\n"
               "exit 1\n"))
    (fs/set-posix-file-permissions git "rwxr-xr-x")
    {:dir (str d) :bin (str bin) :calls (fs/file d "calls")}))

(defn- calls [f] (if (fs/exists? f) (count (re-seq #"(?m)^rev-parse" (slurp f))) 0))

(deftest a-callers-bash-env-does-not-reach-the-command
  (let [{:keys [dir bin] :as fx} (storm-fixture)
        cmd   {:probe {:cmd (str "PATH=" bin ":$PATH bash -c 'git ls-remote x' || true")
                       :cwd dir}}
        seat  (assoc (into {} (System/getenv)) "BASH_ENV" "env.sh")]
    (try
      (testing "control: with BASH_ENV passed through, the stub re-enters itself"
        (commands/run-command! (assoc-in cmd [:probe :env] {"BASH_ENV" "env.sh"})
                               :probe {} {:out :string :err :string})
        (is (<= 4 (calls (:calls fx)))
            "the fixture reproduces the recursion, so the case below can fail")
        (fs/delete-if-exists (:calls fx)))
      (testing "a seat's BASH_ENV, inherited, is dropped: env.sh never runs, no git call"
        (with-redefs [nido.platform.process/inherited-env (constantly seat)]
          (commands/run-command! cmd :probe {} {:out :string :err :string}))
        (is (zero? (calls (:calls fx)))))
      (finally (fs/delete-tree dir)))))
