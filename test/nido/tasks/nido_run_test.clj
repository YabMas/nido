(ns nido.tasks.nido-run-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [nido.run :as run]
   [tasks.nido-run :as task]))

(deftest missing-project-throws
  (is (thrown-with-msg? Exception #"Missing :project"
        (task/run "feat-x" "ci"))))

(deftest missing-positionals-throws
  (testing "no positionals at all"
    (is (thrown-with-msg? Exception #"Usage: bb nido:run"
          (task/run ":project" "brian"))))
  (testing "only the session, no command ref"
    (is (thrown-with-msg? Exception #"Usage: bb nido:run"
          (task/run ":project" "brian" "feat-x")))))

(deftest too-many-positionals-throws
  (is (thrown-with-msg? Exception #"Too many positional args"
        (task/run ":project" "brian" "feat-x" "ci" "extra"))))

(deftest coerces-bare-ref-and-delegates
  ;; Redefine the logic layer to capture the dispatched args and throw a
  ;; sentinel BEFORE the task reaches (System/exit ...), which would kill the
  ;; test JVM. This pins the parse → coerce → delegate contract.
  (let [captured (atom nil)]
    (with-redefs [run/run-command-in-session!
                  (fn [project session ref]
                    (reset! captured {:project project :session session :ref ref})
                    (throw (ex-info "stop-before-exit" {})))]
      (is (thrown-with-msg? Exception #"stop-before-exit"
            (task/run ":project" "brian" "feat-x" "ci")))
      (is (= {:project "brian" :session "feat-x" :ref :ci} @captured)))))
