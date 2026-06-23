(ns nido.session.services.eval-test
  (:require
   [babashka.process]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.session.services.eval :as eval]))

;; The shape clj-nrepl-eval returns (exit 0) when brian's app boot fails because
;; the shared cluster's recorded Flyway history no longer matches this branch's
;; migration files — a different migration was applied under the same version.
(def ^:private flyway-out
  (str "DATABASE STARTUP FAILED:  #error {\n"
       " :cause \"Validate failed: Migrations have failed validation\n"
       "Migration checksum mismatch for migration version 245\n"
       "Either revert the changes to the migration, or run repair to update the schema history.\"}"))

;; flyway-divergence-message — turn that raw stack-trace signature into an
;; actionable remediation pointing at `bb nido:shared:pg:reset`, rather than the
;; generic "nREPL evaluation threw" that buries the cause.

(deftest flyway-divergence-message-detects-mismatch-and-points-at-reset
  (let [msg (#'eval/flyway-divergence-message flyway-out "brian")]
    (is (some? msg) "the checksum-mismatch signature is recognised")
    (is (str/includes? msg "245") "names the offending migration version")
    (is (str/includes? msg "nido:shared:pg:reset") "points at the reset remedy")
    (is (str/includes? msg ":project brian") "weaves in the project when known")))

(deftest flyway-divergence-message-omits-project-when-unknown
  (let [msg (#'eval/flyway-divergence-message flyway-out nil)]
    (is (some? msg))
    (is (str/includes? msg "nido:shared:pg:reset"))
    (is (not (str/includes? msg ":project")) "no dangling ':project' when project is nil")))

(deftest flyway-divergence-message-nil-on-unrelated-failures
  (is (nil? (#'eval/flyway-divergence-message "Syntax error compiling at (foo.clj:1)." "brian")))
  (is (nil? (#'eval/flyway-divergence-message "" "brian")))
  (is (nil? (#'eval/flyway-divergence-message nil "brian"))))

;; The opposite divergence: the shared cluster is AHEAD of a stale branch (a
;; newer migration was applied to it that this branch lacks). The fix is the
;; OPPOSITE of a reset — isolate this session or rebase the branch; resetting
;; the shared cluster would just re-break the up-to-date sessions.
(def ^:private flyway-stale-branch-out
  (str "DATABASE STARTUP FAILED:  #error {\n"
       " :cause \"Validate failed: Migrations have failed validation\n"
       "Detected applied migration not resolved locally: 245.\n"
       "Detected applied migration not resolved locally: 246.\"}"))

(deftest flyway-divergence-message-detects-stale-branch-and-suggests-isolate
  (let [msg (#'eval/flyway-divergence-message flyway-stale-branch-out "brian")]
    (is (some? msg) "the 'not resolved locally' signature is recognised")
    (is (str/includes? msg "nido:session:isolate")
        "a stale branch is isolated (or rebased), not fixed by resetting shared")
    (is (not (str/includes? msg "shared:pg:reset"))
        "must NOT suggest resetting shared — that re-breaks the up-to-date sessions")))

;; eval-on-repl! must surface that remediation as the THROWN message (and
;; :error-msg), so the session-start failure the user sees is actionable.
;; clj-nrepl-eval exits 0 even when the eval threw, so the failure is read from
;; stdout content.

(deftest eval-on-repl!-surfaces-flyway-remediation-as-the-thrown-message
  (with-redefs [babashka.process/shell
                (fn [_opts & _args] {:exit 0 :out flyway-out :err ""})]
    (let [ex (try (#'eval/eval-on-repl! "brian--feat-x" 12345 1000 "(start)")
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is ex "a flyway-divergence eval result must throw")
      (is (str/includes? (ex-message ex) "nido:shared:pg:reset")
          "the thrown message itself is actionable, not the generic 'nREPL evaluation threw'")
      (is (str/includes? (ex-message ex) ":project brian")
          "project is derived from the instance-id prefix")
      (is (str/includes? (str (:error-msg (ex-data ex))) "nido:shared:pg:reset")))))

(deftest eval-on-repl!-keeps-generic-message-for-non-flyway-eval-errors
  (with-redefs [babashka.process/shell
                (fn [_opts & _args] {:exit 0 :out "Execution error (NullPointerException)." :err ""})]
    (let [ex (try (#'eval/eval-on-repl! "brian--feat-x" 12345 1000 "(start)")
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is ex "a non-flyway eval error still throws")
      (is (not (str/includes? (ex-message ex) "shared:pg:reset"))
          "unrelated eval errors must NOT mis-suggest a cluster reset"))))
