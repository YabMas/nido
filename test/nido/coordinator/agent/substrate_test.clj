(ns nido.coordinator.agent.substrate-test
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.agent.substrate :as sub]))

(defn- fake-claude
  "A claude! stand-in answering by the first argument after the binary, recording every call."
  [calls answers]
  (fn
    ([cmd] ((fake-claude calls answers) cmd {}))
    ([cmd opts]
     (swap! calls conj [cmd opts])
     (let [k (second cmd)]
       (or (get answers k) {:exit 0 :out "" :err ""})))))

(defmacro ^:private stubbing
  "with-redefs for the namespace's private seams, each named as #'sub/<var>."
  [bindings & body]
  `(with-redefs-fn ~(into {} (map vec (partition 2 bindings)))
     (fn [] ~@body)))

(def new-enough {"--version" {:exit 0 :out "2.1.296 (Claude Code)\n" :err ""}})

(deftest versions-compare-as-numbers
  (is (= [2 1 296] (sub/parse-version "2.1.296 (Claude Code)")))
  (is (nil? (sub/parse-version "garbage")))
  (stubbing [#'sub/claude! (fake-claude (atom []) {"--version" {:exit 0 :out "2.1.40"}})]
    (is (false? (:ok? (sub/version-check {})))))
  (stubbing [#'sub/claude! (fake-claude (atom []) {"--version" {:exit 0 :out "2.10.0"}})]
    (is (true? (:ok? (sub/version-check {}))) "10 > 1, not \"10\" < \"1\"")))

(deftest dispatch-starts-a-background-session-and-names-it-by-the-listing
  (let [calls (atom [])]
    (stubbing [#'sub/claude! (fake-claude calls
                                           (merge new-enough
                                                  {"--bg"   {:exit 0 :out "backgrounded · a56cda8b · kid\n  claude agents\n"}
                                                   "agents" {:exit 0 :out (json/generate-string
                                                                           [{:id "a56cda8b" :sessionId "a56cda8b-3390"}])}}))]
      (is (= {:bg-id "a56cda8b" :session-id "a56cda8b-3390"}
             (sub/dispatch! {:cwd "/wt" :prompt "--looks like a flag" :name "kid"})))
      (let [[cmd opts] (first (filter #(= "--bg" (second (first %))) @calls))]
        (is (= ["claude" "--bg" "--name" "kid" "--" "--looks like a flag"] cmd)
            "the prompt after --, so nothing reads it as an option")
        (is (= "/wt" (:dir opts)))
        (is (not-any? #{"--permission-mode" "--settings"} cmd)
            "the launch baseline comes from the directory, never the command line")))))

(deftest dispatch-refuses-before-starting-anything
  (testing "an old Claude Code"
    (let [calls (atom [])]
      (stubbing [#'sub/claude! (fake-claude calls {"--version" {:exit 0 :out "2.1.200"}})]
        (is (= :claude-too-old
               (:reason (ex-data (try (sub/dispatch! {:cwd "/wt" :prompt "p"}) (catch Exception e e))))))
        (is (not-any? #(= "--bg" (second (first %))) @calls)))))
  (testing "an untrusted directory"
    (stubbing [#'sub/claude! (fake-claude (atom [])
                                           (merge new-enough
                                                  {"--bg" {:exit 1 :out "" :err "Workspace not trusted. Run `claude` in /wt once"}}))]
      (is (= {:reason :workspace-untrusted :dir "/wt"}
             (ex-data (try (sub/dispatch! {:cwd "/wt" :prompt "p"}) (catch Exception e e))))))))

(deftest a-listing-that-cannot-be-read-throws-rather-than-answering-empty
  (stubbing [#'sub/claude! (fake-claude (atom []) {"agents" {:exit 1 :out "" :err "boom"}})]
    (is (thrown? Exception (sub/listing {})))))

(deftest send-needs-a-live-process-with-an-inbox
  (is (= {:delivered? false :because :no-process} (sub/send! {:id "x"} "hi")))
  (stubbing [#'sub/socket-path (constantly nil)]
    (is (= {:delivered? false :because :no-inbox} (sub/send! {:pid 1} "hi")))))

(deftest send-writes-one-user-message-line-to-the-inbox
  ;; A short directory: a Unix socket's path is capped near 104 bytes, and the system temp dir
  ;; on macOS alone is longer than that leaves room for.
  (let [dir  (fs/create-temp-dir {:dir "/tmp"})
        path (str (fs/path dir "s.sock"))
        addr (java.net.UnixDomainSocketAddress/of ^String path)
        srv  (doto (java.nio.channels.ServerSocketChannel/open java.net.StandardProtocolFamily/UNIX)
               (.bind addr))
        got  (future (with-open [c (.accept srv)]
                       (let [in (java.io.BufferedReader.
                                 (java.io.InputStreamReader.
                                  (java.nio.channels.Channels/newInputStream c) "UTF-8"))]
                         (.readLine in))))]
    (try
      (stubbing [#'sub/socket-path (constantly path)]
        (is (= {:delivered? true} (sub/send! {:pid 42} "next task [nido-send t1]"))))
      (is (= {:type "user" :message {:role "user" :content "next task [nido-send t1]"}}
             (json/parse-string (deref got 5000 nil) true)))
      (finally (.close srv) (fs/delete-tree dir)))))

(deftest wake-continues-in-place-or-undoes-a-copy
  (stubbing [#'sub/claude! (fake-claude (atom []) {"--resume" {:exit 0 :out "note: woke session a56cda8b\nbackgrounded · a56cda8b\n"}})]
    (is (= {:woke? true} (sub/wake! "a56cda8b-3390" "go" {}))))
  (let [calls (atom [])]
    (stubbing [#'sub/claude! (fake-claude calls {"--resume" {:exit 0 :out "note: session a56cda8b is already running in the background, so this started a copy as 074ff0e1.\n"}})]
      (is (= {:woke? false :copy "074ff0e1"} (sub/wake! "a56cda8b-3390" "go" {})))
      (is (some #(= ["claude" "stop" "074ff0e1"] (first %)) @calls))
      (is (some #(= ["claude" "rm" "074ff0e1"] (first %)) @calls)
          "nothing is left running beside the session"))))

(deftest wake-runs-in-the-session-s-own-directory
  (let [calls (atom [])]
    (stubbing [#'sub/claude! (fake-claude calls {"--resume" {:exit 0 :out "backgrounded · a56cda8b\n"}})]
      (sub/wake! "a56cda8b-3390" "go" {:cwd "/wt"}))
    (is (= {:dir "/wt"} (second (first @calls)))
        "where Claude Code finds the conversation and the directory's settings")))

(deftest trust-is-read-for-exactly-the-directory
  (let [dir (fs/create-temp-dir)
        cfg (fs/path dir "claude.json")
        wt  (str (fs/canonicalize (fs/create-dirs (fs/path dir "wt"))))]
    (try
      (spit (str cfg) (json/generate-string {"projects" {wt {"hasTrustDialogAccepted" true}
                                                         (str (fs/parent wt)) {"hasTrustDialogAccepted" true}}}))
      (stubbing [#'sub/claude-config-path (constantly (str cfg))]
        (is (true? (sub/trusted? wt)))
        (is (false? (sub/trusted? (str (fs/create-dirs (fs/path wt "child")))))
            "a trusted parent does not make its child trusted"))
      (finally (fs/delete-tree dir)))))

(deftest dispatch-passes-the-runs-context-but-no-baseline
  (let [calls (atom [])]
    (stubbing [#'sub/claude! (fake-claude calls
                                          (merge new-enough
                                                 {"--bg"   {:exit 0 :out "backgrounded · a56cda8b\n"}
                                                  "agents" {:exit 0 :out (json/generate-string
                                                                          [{:id "a56cda8b" :sessionId "a56cda8b-3390"}])}}))]
      (sub/dispatch! {:cwd "/wt" :prompt "p" :system-prompt "S" :mcp-config "/m.json" :add-dirs ["/a" "/b"]})
      (let [cmd (ffirst (filter #(= "--bg" (second (first %))) @calls))]
        (is (= ["claude" "--bg" "--append-system-prompt" "S" "--mcp-config" "/m.json"
                "--add-dir" "/a" "--add-dir" "/b" "--" "p"] cmd))))))

(deftest dispatch-answers-only-the-id-the-listing-reports
  (testing "a session the listing shows only on a later read"
    (let [reads (atom 0)]
      (stubbing [#'sub/claude! (fn self ([cmd] (self cmd {}))
                                 ([cmd _]
                                  (case (second cmd)
                                    "--version" (get new-enough "--version")
                                    "--bg"      {:exit 0 :out "backgrounded · a56cda8b\n"}
                                    "agents"    {:exit 0 :out (if (< (swap! reads inc) 3)
                                                                "[]"
                                                                (json/generate-string
                                                                 [{:id "a56cda8b" :sessionId "a56cda8b-3390"}]))})))
                 #'sub/pause! (constantly nil)]
        (is (= {:bg-id "a56cda8b" :session-id "a56cda8b-3390"}
               (sub/dispatch! {:cwd "/wt" :prompt "p"}))))))
  (testing "a session the listing never shows"
    (stubbing [#'sub/claude! (fake-claude (atom [])
                                          (merge new-enough
                                                 {"--bg"   {:exit 0 :out "backgrounded · a56cda8b\n"}
                                                  "agents" {:exit 0 :out "[]"}}))
               #'sub/pause! (constantly nil)]
      (is (= {:reason :session-unlisted :bg-id "a56cda8b" :left-running "a56cda8b"}
             (ex-data (try (sub/dispatch! {:cwd "/wt" :prompt "p"}) (catch Exception e e))))
          "the short id is never answered as the session id")))
  (testing "a listing that cannot be read after the launch"
    (stubbing [#'sub/claude! (fake-claude (atom [])
                                          (merge new-enough
                                                 {"--bg"   {:exit 0 :out "backgrounded · a56cda8b\n"}
                                                  "agents" {:exit 1 :out "" :err "boom"}}))
               #'sub/pause! (constantly nil)]
      (is (= "a56cda8b" (:left-running (ex-data (try (sub/dispatch! {:cwd "/wt" :prompt "p"}) (catch Exception e e)))))
          "every failure after a launch names what it left running"))))

(deftest a-refused-stop-or-restart-throws
  (stubbing [#'sub/claude! (fake-claude (atom []) {"stop" {:exit 1 :out "" :err "no"}
                                                   "respawn" {:exit 1 :out "" :err "no"}})]
    (is (= :stop-refused (:reason (ex-data (try (sub/stop! "b" {}) (catch Exception e e))))))
    (is (= :respawn-refused (:reason (ex-data (try (sub/respawn! "b" {}) (catch Exception e e)))))))
  (stubbing [#'sub/claude! (fake-claude (atom []) {})]
    (is (true? (sub/stop! "b" {})))
    (is (true? (sub/respawn! "b" {})))))

(deftest wake-surfaces-a-copy-it-could-not-remove
  (stubbing [#'sub/claude! (fake-claude (atom []) {"--resume" {:exit 0 :out "note: session a56cda8b is already running in the background, so this started a copy as 074ff0e1.\n"}
                                                   "rm"       {:exit 1 :out "" :err "still running"}})]
    (let [data (ex-data (try (sub/wake! "a56cda8b-3390" "go" {}) (catch Exception e e)))]
      (is (= :copy-cleanup-failed (:reason data)))
      (is (= "074ff0e1" (:copy data)))
      (is (nil? (:left-running data)) "stopped, so not left running — only its removal failed")
      (is (= #{:rm} (set (keys (:failed data)))))))
  (stubbing [#'sub/claude! (fake-claude (atom []) {"--resume" {:exit 0 :out "note: session a56cda8b is already running in the background, so this started a copy as 074ff0e1.\n"}
                                                   "stop"     {:exit 1 :out "" :err "no"}})]
    (let [data (ex-data (try (sub/wake! "a56cda8b-3390" "go" {}) (catch Exception e e)))]
      (is (= "074ff0e1" (:left-running data)) "its stop unconfirmed, it is named as left running")
      (is (= #{:stop} (set (keys (:failed data))))))))

(deftest attach-refuses-a-session-the-listing-does-not-show
  (stubbing [#'sub/claude! (fake-claude (atom []) {"agents" {:exit 0 :out (json/generate-string
                                                                             [{:sessionId "s-1" :kind "interactive" :id "x"}])}})]
    (is (= :not-listed (:reason (ex-data (try (sub/attach! "s-1" {}) (catch Exception e e)))))
        "an interactive session is not one to attach to")))
