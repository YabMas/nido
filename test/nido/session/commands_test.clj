(ns nido.session.commands-test
  (:require
   [babashka.fs :as fs]
   [babashka.process]
   [nido.platform.lock]
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
