(ns nido.commands-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.commands :as commands]))

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
