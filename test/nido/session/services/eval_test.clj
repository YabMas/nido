(ns nido.session.services.eval-test
  (:require
   [babashka.fs :as fs]
   [babashka.process]
   [bencode.core]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.platform.process]
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
    (let [ex (try (#'eval/eval-on-repl! "brian--feat-x" "brian" 12345 1000 "(start)")
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is ex "a flyway-divergence eval result must throw")
      (is (str/includes? (ex-message ex) "nido:shared:pg:reset")
          "the thrown message itself is actionable, not the generic 'nREPL evaluation threw'")
      (is (str/includes? (ex-message ex) ":project brian")
          "project is derived from the instance-id prefix")
      (is (str/includes? (str (:error-msg (ex-data ex))) "nido:shared:pg:reset")))))

;; Hardening: a non-flyway eval failure must STILL be diagnosable. The cause
;; belongs in the THROWN message (which every surface renders), not just buried
;; in :error-msg ex-data — the TUI's failure panel only shows ex-message, so an
;; opaque "nREPL evaluation threw (eval returned exception)" left the user with
;; nothing actionable. It must, of course, not mis-suggest a cluster reset.

(deftest eval-on-repl!-surfaces-the-cause-for-non-flyway-eval-errors
  (with-redefs [babashka.process/shell
                (fn [_opts & _args] {:exit 0 :out "Execution error (NullPointerException)." :err ""})]
    (let [ex (try (#'eval/eval-on-repl! "brian--feat-x" "brian" 12345 1000 "(start)")
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is ex "a non-flyway eval error still throws")
      (is (str/includes? (ex-message ex) "Execution error (NullPointerException)")
          "the actual cause is in the THROWN message, not an opaque 'eval returned exception'")
      (is (not (str/includes? (ex-message ex) "shared:pg:reset"))
          "unrelated eval errors must NOT mis-suggest a cluster reset")
      (is (str/includes? (str (:error-msg (ex-data ex))) "NullPointerException")
          ":error-msg still carries the cause for surfaces that read ex-data"))))

;; The non-zero-shell-exit path (clj-nrepl-eval itself failed, e.g. connection
;; refused) must also be self-describing rather than a bare "(shell non-zero
;; exit)" — same reason: the TUI renders only ex-message.

(deftest eval-on-repl!-surfaces-the-cause-on-shell-failure
  (with-redefs [babashka.process/shell
                (fn [_opts & _args]
                  {:exit 1 :out "" :err "Connection refused to nREPL on port 12345"})]
    (let [ex (try (#'eval/eval-on-repl! "brian--feat-x" "brian" 12345 1000 "(start)")
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is ex "a shell failure throws")
      (is (str/includes? (ex-message ex) "Connection refused")
          "the stderr cause is woven into the thrown message"))))

;; first-meaningful-line — the cause-extractor underneath the self-describing
;; message. It must (a) recognise the same signatures nrepl-eval-error? trips on
;; ("Syntax error" / "FATAL ERROR" were missing from its keyword set), and (b)
;; never return nil when there is *any* output, so the message is never empty.

(deftest first-meaningful-line-recognises-syntax-and-fatal-errors
  (is (str/includes? (#'eval/first-meaningful-line
                      "noise\nSyntax error compiling at (foo.clj:3:1).\nmore noise")
                     "Syntax error"))
  (is (str/includes? (#'eval/first-meaningful-line
                      "boot...\nFATAL ERROR: the sky is falling\n...")
                     "FATAL ERROR")))

(deftest first-meaningful-line-falls-back-to-last-line-when-no-keyword-matches
  (is (= "the only clue we have"
         (#'eval/first-meaningful-line "blah blah\n\n  the only clue we have  \n\n"))
      "with no recognised signature, surface the last non-blank line, not nil")
  (is (nil? (#'eval/first-meaningful-line "   \n\n  "))
      "truly-empty output yields nil — nothing to surface")
  (is (nil? (#'eval/first-meaningful-line nil))))

;; Permission denied on shared cluster (when a session tries to migrate the
;; shared cluster without isolation). This is a new failure mode distinct from
;; checksum mismatch and stale-branch divergence.
(deftest permission-denied-on-shared-suggests-isolate
  (let [msg (#'eval/flyway-divergence-message
             "org.postgresql.util.PSQLException: ERROR: permission denied for schema brian"
             "brian")]
    (is (some? msg))
    (is (str/includes? msg "shared"))
    (is (str/includes? msg "bb nido:session:isolate :project brian"))))

;; The shape clj-nrepl-eval actually returns when brian's boot throws: exit 0,
;; the app's JSON log lines on stdout, and the exception on STDERR. Nine of nine
;; kept boot failures looked like this — Flyway, compile errors, an OOM — and
;; none carried a signature on stdout.

(def ^:private flyway-err
  (str "Execution error (FlywayValidateException) at org.flywaydb.core.Flyway/lambda$migrate$0 (Flyway.java:146).\n"
       "Validate failed: Migrations have failed validation\n"
       "Migration checksum mismatch for migration version 20260912093236\n"
       "-> Applied to database : 1377953232\n"
       "-> Resolved locally    : -1202459325\n"
       "Either revert the changes to the migration, or run repair to update the schema history.\n"))

(def ^:private boot-log-out
  "{\"level\":\"INFO\",\"message\":\"Running Flyway migrations\",\"ns\":\"brian.server-components.migrations\"}")

(deftest eval-on-repl!-reads-a-thrown-exception-from-stderr
  (with-redefs [babashka.process/shell
                (fn [_opts & _args] {:exit 0 :out boot-log-out :err flyway-err})]
    (let [ex (try (#'eval/eval-on-repl! "brian--feat-x" "brian" 12345 1000 "(start)")
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is ex "an exception on stderr is a failed eval, whatever the exit code")
      (is (str/includes? (ex-message ex) "20260912093236")
          "the divergence is read from stderr too")
      (is (= flyway-err (:error (ex-data ex)))
          "stderr travels with the failure"))))

(deftest eval-on-repl!-passes-a-healthy-boot-that-warns-on-stderr
  (with-redefs [babashka.process/shell
                (fn [_opts & _args]
                  {:exit 0
                   :out ":started"
                   :err "WARNING: A terminally deprecated method in sun.misc.Unsafe has been called\n"})]
    (is (= ":started" (#'eval/eval-on-repl! "brian--feat-x" "brian" 12345 1000 "(start)"))
        "stderr without a failure signature is not a failure")))

;; What clj-nrepl-eval returns when its --timeout elapses mid-boot: exit 0, the
;; boot's own logging, then its interrupt notice — all on stdout, no error
;; signature anywhere. Read as a success, the session stayed up on a JVM whose
;; interrupted `require` left namespaces half loaded, and the next start in it
;; died on "namespace 'brian.job.engagement-email' not found".

(def ^:private timed-out-out
  (str "2026-10-06T13:23:11Z INFO brian.translations Loaded translations\n"
       "\n⚠️  Timeout hit, sending nREPL :interrupt …\n"
       "✋ Evaluation interrupted.\n"))

(deftest eval-on-repl!-fails-an-eval-its-timeout-interrupted
  (with-redefs [babashka.process/shell
                (fn [_opts & _args] {:exit 0 :out timed-out-out :err ""})]
    (let [ex (try (#'eval/eval-on-repl! "brian--feat-x" "brian" 12345 180000 "(start)")
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is ex "an interrupted eval is a failed eval, whatever the exit code")
      (is (str/includes? (ex-message ex) "timed out after 180s")
          "the message names the timeout, not a compile error the interrupt caused")
      (is (:timed-out? (ex-data ex))))))

;; start-app! in a stale jj working copy: the files the REPL loaded are not the
;; branch, so whatever failed is said to be suspect FIRST — ahead of a remedy
;; (here a cluster reset) that would act on the stale files' behalf.

(def ^:private jj-stale-err
  (str "Error: The working copy is stale (not updated since operation 60d5875a1a03).\n"
       "Hint: Run `jj workspace update-stale` to update it.\n"))

(defn- start-app-failure
  "Run start-app! against an nREPL whose boot throws the Flyway mismatch, with
   `jj-result` as the answer to any jj command. Returns [thrown jj-called?]."
  [project-dir jj-result]
  (let [jj-called? (atom false)]
    (with-redefs [babashka.process/shell
                  (fn [_opts cmd & _args]
                    (if (= "jj" cmd)
                      (do (reset! jj-called? true) jj-result)
                      {:exit 0 :out boot-log-out :err flyway-err}))
                  nido.platform.process/tcp-open? (fn [port] (= port 2))]
      [(try (eval/start-app! {:start-form "(start)" :health-check-timeout-ms 1}
                             {:app-port 1 :nrepl-port 2 :host "h"
                              :instance-id "brian--feat-x"}
                             {:session {:project-dir project-dir}})
            nil
            (catch clojure.lang.ExceptionInfo e e))
       @jj-called?])))

(deftest start-app!-puts-a-stale-working-copy-ahead-of-the-failure
  (let [dir (str (fs/create-temp-dir))]
    (try
      (fs/create-dir (fs/path dir ".jj"))
      (let [[ex] (start-app-failure dir {:exit 1 :out "" :err jj-stale-err})]
        (is (str/starts-with? (ex-message ex) "This worktree's jj working copy is stale")
            "the stale working copy is said first")
        (is (str/includes? (ex-message ex) (str "`jj workspace update-stale` in " dir)))
        (is (str/includes? (ex-message ex) "20260912093236")
            "the failure itself is still there")
        (is (str/starts-with? (:error-msg (ex-data ex)) "This worktree's jj working copy is stale"))
        (is (:stale-working-copy? (ex-data ex))))
      (finally (fs/delete-tree dir)))))

(deftest start-app!-leaves-a-current-working-copy-failure-alone
  (let [dir (str (fs/create-temp-dir))]
    (try
      (fs/create-dir (fs/path dir ".jj"))
      (let [[ex jj-called?] (start-app-failure dir {:exit 0 :out "" :err ""})]
        (is jj-called? "a jj workspace is asked")
        (is (str/starts-with? (ex-message ex) "Database migration failed")
            "a current working copy adds nothing to the message")
        (is (not (:stale-working-copy? (ex-data ex)))))
      (finally (fs/delete-tree dir)))))

(deftest start-app!-asks-jj-nothing-outside-a-jj-workspace
  (let [dir (str (fs/create-temp-dir))]
    (try
      (let [[ex jj-called?] (start-app-failure dir nil)]
        (is ex "the failure still throws")
        (is (not jj-called?) "a plain-git or non-repo worktree runs no jj command"))
      (finally (fs/delete-tree dir)))))

;; A session started over a bare `ssh host '<cmd>'` runs without the login PATH,
;; so ~/.local/bin — where bbin installs clj-nrepl-eval — is not on it. The boot
;; eval must still find the script there, and hand the child a PATH that holds
;; its directory so the script's own `#!/usr/bin/env bb` lookup has a chance.

(deftest eval-on-repl!-finds-clj-nrepl-eval-in-local-bin-off-path
  (let [home (str (fs/create-temp-dir))
        exe  (str (fs/path home ".local" "bin" "clj-nrepl-eval"))
        seen (atom nil)]
    (try
      (fs/create-dirs (fs/parent exe))
      (spit exe "#!/usr/bin/env bb\n")
      (fs/set-posix-file-permissions exe "rwxr-xr-x")
      (with-redefs [fs/which (constantly nil)
                    fs/home  (constantly (fs/path home))
                    babashka.process/shell
                    (fn [opts & args]
                      (reset! seen {:opts opts :args args})
                      {:exit 0 :out ":started" :err ""})]
        (#'eval/eval-on-repl! nil "brian" 12345 1000 "(start)"))
      (is (= exe (first (:args @seen))) "the script is run by its absolute path")
      (is (some #{(str (fs/parent exe))}
                (str/split (get-in @seen [:opts :extra-env "PATH"]) #":"))
          "the child's PATH carries the script's directory")
      (finally (fs/delete-tree home)))))

(defn- silent-nrepl!
  "A filesystem-socket nREPL that reads every message sent it and answers none — when
   `clones?`, none but the session clone, so an eval never returns. Returns a fn that stops it."
  [path clones?]
  (fs/delete-if-exists path)
  (let [ss (java.nio.channels.ServerSocketChannel/open java.net.StandardProtocolFamily/UNIX)]
    (.bind ss (java.net.UnixDomainSocketAddress/of (str path)))
    (future
      (try
        (let [c   (.accept ss)
              in  (java.io.PushbackInputStream. (java.nio.channels.Channels/newInputStream c))
              out (java.nio.channels.Channels/newOutputStream c)]
          (bencode.core/read-bencode in)
          (when clones?
            (bencode.core/write-bencode out {"id" "nido-clone" "new-session" "s1" "status" ["done"]})
            (.flush out))
          (loop [] (bencode.core/read-bencode in) (recur)))
        (catch Exception _ nil)))
    (fn [] (.close ss) (fs/delete-if-exists path))))

(deftest an-eval-over-a-socket-that-never-answers-ends-at-its-timeout
  (doseq [clones? [true false]]
    (let [path (str (fs/path (str (fs/create-temp-dir)) "repl.sock"))
          stop (silent-nrepl! path clones?)]
      (try
        (let [r (deref (future (#'eval/eval-over-socket path 300 "(Thread/sleep 100000)")) 5000 ::stuck)]
          (is (not= ::stuck r)
              (str "the eval ends at its timeout, not when the JVM answers — "
                   (if clones? "an eval" "the session clone") " it never answers"))
          (is (str/includes? (str (:out r)) @#'eval/eval-timeout-signature)))
        (finally (stop))))))

(defn- recording-nrepl!
  "A filesystem-socket nREPL that answers the session clone, answers an eval `done` unless
   `silent?`, and records every op it is sent in `ops`. Returns a fn that stops it."
  [path silent? ops]
  (fs/delete-if-exists path)
  (let [ss (java.nio.channels.ServerSocketChannel/open java.net.StandardProtocolFamily/UNIX)]
    (.bind ss (java.net.UnixDomainSocketAddress/of (str path)))
    (future
      (try
        (let [c   (.accept ss)
              in  (java.io.PushbackInputStream. (java.nio.channels.Channels/newInputStream c))
              out (java.nio.channels.Channels/newOutputStream c)
              s   #(if (bytes? %) (String. ^bytes %) (str %))]
          (loop []
            (let [m  (bencode.core/read-bencode in)
                  op (s (get m "op"))]
              (swap! ops conj [op (some-> (get m "session") s)])
              (case op
                "clone" (bencode.core/write-bencode out {"id" "nido-clone" "new-session" "s1" "status" ["done"]})
                "eval"  (when-not silent?
                          (bencode.core/write-bencode out {"id" "nido-eval" "value" "1" "status" ["done"]}))
                nil)
              (.flush out)
              (recur))))
        (catch Exception _ nil)))
    (fn [] (.close ss) (fs/delete-if-exists path))))

(deftest an-eval-over-a-socket-closes-the-session-it-cloned
  (doseq [silent? [false true]]
    (let [path (str (fs/path (str (fs/create-temp-dir)) "repl.sock"))
          ops  (atom [])
          stop (recording-nrepl! path silent? ops)]
      (try
        (#'eval/eval-over-socket path 300 "1")
        (Thread/sleep 200)
        (is (some #{["close" "s1"]} @ops)
            (str "the cloned session is closed when the eval " (if silent? "times out" "returns")))
        (when silent?
          (is (some #{["interrupt" "s1"]} @ops)
              "an eval that times out is interrupted, while its reply is still being read"))
        (finally (stop))))))

(deftest a-start-form-names-the-bound-port-under-the-services-own-name
  (let [seen (atom nil)]
    (with-redefs-fn {#'eval/eval-on-repl!      (fn [_ _ _ _ form] (reset! seen form) {})
                     #'eval/nrepl-answers?     (constantly true)
                     #'eval/wait-for-app-port! (constantly true)
                     #'nido.platform.process/tcp-open? (constantly false)
                     #'nido.platform.core/log-step (constantly nil)}
      (fn []
        (eval/start-app! {:name :a :start-form "(start {{a.port}} {{app.port}})"}
                         {:app-port 3300 :bind-port 3301 :socket "/tmp/x.sock" :host "h"}
                         {:a {:port 3300 :bind-port 3301} :session {:instance-id "i" :project-name "p"}})
        (is (= "(start 3301 3301)" @seen)
            "behind a front, the app is told the port it binds, never the front's")))))

(defn- deaf-after-clone-nrepl!
  "A filesystem-socket nREPL that answers the session clone and then reads nothing more, so a
   large request fills the socket and its writer blocks. Returns a fn that stops it."
  [path]
  (fs/delete-if-exists path)
  (let [ss (java.nio.channels.ServerSocketChannel/open java.net.StandardProtocolFamily/UNIX)
        conn (atom nil)]
    (.bind ss (java.net.UnixDomainSocketAddress/of (str path)))
    (future
      (try
        (let [c   (.accept ss)
              in  (java.io.PushbackInputStream. (java.nio.channels.Channels/newInputStream c))
              out (java.nio.channels.Channels/newOutputStream c)]
          (reset! conn c)
          (bencode.core/read-bencode in)
          (bencode.core/write-bencode out {"id" "nido-clone" "new-session" "s1" "status" ["done"]})
          (.flush out))
        (catch Exception _ nil)))
    (fn [] (some-> @conn .close) (.close ss) (fs/delete-if-exists path))))

(deftest an-eval-whose-request-the-jvm-stops-reading-ends-at-its-timeout
  (let [path (str (fs/path (str (fs/create-temp-dir)) "repl.sock"))
        stop (deaf-after-clone-nrepl! path)]
    (try
      (let [form (apply str (repeat (* 16 1024 1024) "x"))
            r    (deref (future (#'eval/eval-over-socket path 300 form)) 6000 ::stuck)]
        (is (not= ::stuck r) "the cleanup does not wait on the writer the JVM stopped reading"))
      (finally (stop)))))

(deftest a-fresh-jvm-does-not-adopt-a-listener-on-its-apps-port
  (with-redefs-fn {#'eval/nrepl-answers?             (constantly true)
                   #'nido.platform.process/tcp-open? (constantly true)
                   #'nido.platform.core/log-step     (constantly nil)}
    (fn []
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"held by a process this JVM did not start"
                            (eval/start-app! {:name :a :start-form "(start)"}
                                             {:app-port 3300 :bind-port 3301 :socket "/tmp/x.sock" :host "h"
                                              :fresh-jvm? true}
                                             {:session {:instance-id "i"}})))
      (is (true? (eval/start-app! {:name :a :start-form "(start)"}
                                  {:app-port 3300 :bind-port 3301 :socket "/tmp/x.sock" :host "h"}
                                  {:session {:instance-id "i"}}))
          "a JVM that ran already keeps its listening app, as before"))))
