(ns nido.core.outside-test
  "The outside stratum: the turn runner against a fake executor whose runs are shell scripts, the
   Claude adapter's parsing and hook, the ask tool, and reconcile's observations and calls."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nido.core.ask-tool :as ask-tool]
            [nido.core.domain :as d]
            [nido.core.reconcile :as rec]
            [nido.core.store :as store]
            [nido.core.turn :as turn]
            [nido.core.writer :as writer]
            [nido.executor.claude :as claude])
  (:import [java.lang ProcessHandle]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir [] (str (Files/createTempDirectory "nido-outside-" (make-array FileAttribute 0))))

(def fake
  "An executor whose run is `sh -c` of the spec's instructions on the first run, and of
   :on-resume (given the resolution in $RESOLUTION) on later ones. Its outcome is the last line of
   output read as EDN, or nil when that is not an EDN map."
  {:launch  (fn [{:keys [run spec resolution]}]
              {:argv ["sh" "-c" (if (= 1 run) (:instructions spec) (:on-resume spec))]
               :env  {"RESOLUTION" (pr-str resolution)}})
   :outcome (fn [rdir]
              (let [f (io/file rdir "out")]
                (when (.exists f)
                  (let [x (try (edn/read-string (last (str/split-lines (slurp f)))) (catch Exception _ nil))]
                    (when (map? x) x)))))
   :attach  (fn [tdir] ["true" tdir])})

(defn- await-until [f ms]
  (let [end (+ (System/currentTimeMillis) ms)]
    (loop [] (or (f) (when (< (System/currentTimeMillis) end) (Thread/sleep 25) (recur))))))

(defmacro with-core
  "`binding` destructures {:s store :w writer :runner :root :cmd!}: a writer publishing to a runner
   over `root`, and `cmd!` to submit a command to a workstream and await its outcome."
  [[binding opts] & body]
  `(let [dir# (temp-dir)
         root# (str dir# "/turns")
         s# (store/open (str dir# "/core.db"))
         runner-ref# (atom nil)
         w# (writer/start s# {:publish #(turn/changed! @runner-ref# %)})
         runner# (reset! runner-ref# (turn/start (merge {:root root# :executors {:fake fake}
                                                         :submit #(writer/submit! w# %)}
                                                        ~opts)))
         n# (atom 0)
         ~binding {:s s# :w w# :runner runner# :root root#
                   :cmd! (fn [ws# c#] (deref (writer/submit! w# {:key (str "test-" (swap! n# inc)) :ws ws# :command c#})
                                             10000 nil))}]
     (try ~@body
          (finally (turn/stop! runner#) (writer/stop! w#) (store/close s#)))))

(defn- turn-of [s ws t] (get-in (store/state s ws) [:turns t]))

(defn- spec [instructions & {:as more}] (merge {:executor :fake :instructions instructions} more))

(deftest a-turn-runs-after-its-commit-and-its-outcome-is-recorded
  (with-core [{:keys [s cmd!]} {}]
    (cmd! "ws" {:command :open-unit :unit "u" :project "p" :goal "g" :session-cwd "/tmp"})
    (cmd! "ws" {:command :start-turn :turn "t" :unit "u" :spec (spec "echo '{:outcome :completed :detail {:summary \"done\"}}'")})
    (is (await-until #(= :completed (:status (turn-of s "ws" "t"))) 10000))
    (is (= {:summary "done"} (:detail (turn-of s "ws" "t"))))
    (is (= {:events [{:event :turn-ended :turn "t" :outcome :completed :detail {:summary "done"}}]}
           (store/command-outcome s "outcome/ws/t/1"))
        "the outcome is a command keyed by its run")))

(deftest an-asked-turn-ends-and-resumes-with-its-resolution
  (with-core [{:keys [s cmd! root]} {}]
    (cmd! "ws" {:command :open-unit :unit "u" :project "p" :goal "g" :session-cwd "/tmp"})
    (cmd! "ws" {:command :start-turn :turn "t" :unit "u"
                :spec (spec "echo '{:outcome :asked :call {:id \"c1\" :question \"which?\"}}'"
                            :on-resume "echo \"{:outcome :completed :detail {:resumed-with $RESOLUTION}}\"")})
    (is (await-until #(= :asked (:status (turn-of s "ws" "t"))) 10000))
    (is (= "which?" (get-in (turn-of s "ws" "t") [:call :question])))
    (cmd! "ws" {:command :answer-call :turn "t" :answer "this"})
    (Thread/sleep 300)
    (is (not (.exists (io/file (turn/run-dir (turn/turn-dir root "ws" "t") 2))))
        "a resolution alone starts nothing: no process runs the turn until it is resumed")
    (cmd! "ws" {:command :resume-turn :turn "t"})
    (is (await-until #(= :completed (:status (turn-of s "ws" "t"))) 10000))
    (is (= {:resumed-with {:answered "this"}} (:detail (turn-of s "ws" "t"))))))

(deftest output-that-is-no-outcome-fails-the-turn-and-leaves-its-actions-unknown
  (with-core [{:keys [s cmd!]} {}]
    (cmd! "ws" {:command :open-unit :unit "u" :project "p" :goal "g" :session-cwd "/tmp"})
    (cmd! "ws" {:command :start-turn :turn "t" :unit "u" :spec (spec "echo 'I did some things'")})
    (is (await-until #(= :failed (:status (turn-of s "ws" "t"))) 10000))
    (is (= #{(d/run-unknown-key "t" 1)} (get-in (store/state s "ws") [:units "u" :unknowns])))
    (testing "and a report that is not one of the four outcomes is failed"
      (is (= :failed (:outcome (turn/parse-outcome {:outcome :finished}))))
      (is (= :failed (:outcome (turn/parse-outcome {:outcome :asked}))) "an ask without its call")
      (is (= :failed (:outcome (turn/parse-outcome "text")))))))

(deftest a-run-past-its-deadline-is-cancelled
  (with-core [{:keys [s cmd!]} {}]
    (cmd! "ws" {:command :open-unit :unit "u" :project "p" :goal "g" :session-cwd "/tmp"})
    (cmd! "ws" {:command :start-turn :turn "t" :unit "u" :spec (spec "sleep 30" :deadline-ms 300)})
    (is (await-until #(= :cancelled (:status (turn-of s "ws" "t"))) 10000))
    (is (= {:reason :deadline} (:detail (turn-of s "ws" "t"))))))

(deftest a-hold-cancels-the-run-only-after-its-commit
  (with-core [{:keys [s cmd! root]} {}]
    (cmd! "ws" {:command :open-unit :unit "u" :project "p" :goal "g" :session-cwd "/tmp"})
    (cmd! "ws" {:command :start-turn :turn "t" :unit "u" :spec (spec "sleep 30")})
    (let [pid-f (io/file (turn/run-dir (turn/turn-dir root "ws" "t") 1) "pid")]
      (is (await-until #(.exists pid-f) 10000))
      (let [ph (.get (ProcessHandle/of (parse-long (str/trim (slurp pid-f)))))]
        (is (.isAlive ph))
        (cmd! "ws" {:command :hold-unit :unit "u"})
        (is (await-until #(not (.isAlive ph)) 10000) "the run is cancelled through the port")
        (Thread/sleep 300)
        (is (= {:status :cancelled :ended-by :takeover}
               (select-keys (turn-of s "ws" "t") [:status :ended-by]))
            "and what it reports afterwards is discarded")))))

(deftest a-starting-daemon-takes-back-what-the-last-one-left
  (let [dir (temp-dir) root (str dir "/turns") db (str dir "/core.db")
        s (store/open db)
        w (writer/start s {})
        n (atom 0)
        cmd! (fn [c] (deref (writer/submit! w {:key (str "k" (swap! n inc)) :ws "ws" :command c}) 10000 nil))
        ;; The daemon that dies: its runner launches, and nothing it reports reaches the store.
        dead (turn/start {:root root :executors {:fake fake} :submit (fn [_] nil)})]
    (try
      (doseq [u ["a" "b" "c" "d"]] (cmd! {:command :open-unit :unit u :project "p" :goal "g" :session-cwd "/tmp"}))
      (cmd! {:command :start-turn :turn "still-running" :unit "a" :spec (spec "sleep 1; echo '{:outcome :completed}'")})
      (cmd! {:command :start-turn :turn "ended-meanwhile" :unit "b" :spec (spec "echo '{:outcome :completed}'")})
      (cmd! {:command :start-turn :turn "ended-mute" :unit "c" :spec (spec "true")})
      (turn/changed! dead [{:ws "ws" :state (store/state s "ws")}])
      (cmd! {:command :start-turn :turn "never-started" :unit "d" :spec (spec "echo '{:outcome :completed}'")})
      (Thread/sleep 500)
      (turn/stop! dead)
      (let [live (turn/start {:root root :executors {:fake fake} :submit #(writer/submit! w %)})
            st #(get-in (store/state s "ws") [:turns % :status])]
        (try
          (turn/take-back! live "ws" (store/state s "ws"))
          (turn/changed! live [{:ws "ws" :state (store/state s "ws")}])
          (is (await-until #(= :completed (st "ended-meanwhile")) 5000) "ended while no daemon held it")
          (is (await-until #(= :failed (st "ended-mute")) 5000))
          (is (contains? (get-in (store/state s "ws") [:units "c" :unknowns]) (d/run-unknown-key "ended-mute" 1))
              "a run that ended without an outcome leaves its actions unknown")
          (is (await-until #(= :failed (st "never-started")) 5000))
          (is (= {:reason :never-started} (get-in (store/state s "ws") [:turns "never-started" :detail])))
          (is (= :running (st "still-running")) "a run that still runs is reattached")
          (is (await-until #(= :completed (st "still-running")) 5000) "and recorded when it ends")
          (is (not (.exists (io/file (turn/run-dir (turn/turn-dir root "ws" "never-started") 1) "launched")))
              "nothing is started again")
          (finally (turn/stop! live))))
      (finally (writer/stop! w) (store/close s)))))

;; ── the Claude adapter ───────────────────────────────────────────────────────

(defn- write-out! [rdir & events]
  (.mkdirs (io/file rdir))
  (spit (io/file rdir "out") (str/join "\n" (map json/write-str events))))

(deftest claude-output-is-parsed-at-the-port
  (let [{:keys [outcome launch]} (claude/executor {:claude-bin "claude" :bb-bin "bb" :src "/src"})
        rdir (str (temp-dir) "/run-1")]
    (testing "a deferred ask is the outcome asked, carrying the call"
      (write-out! rdir {:type "system" :subtype "init"}
                  {:type "result" :subtype "success" :is_error false :stop_reason "tool_deferred"
                   :deferred_tool_use {:id "toolu_1" :name "mcp__nido__ask"
                                       :input {:question "SQLite or Postgres?" :explored "x" :options ["a" "b"] :recommendation "a"}}})
      (is (= {:outcome :asked :call {:id "toolu_1" :question "SQLite or Postgres?" :explored "x" :options ["a" "b"] :recommendation "a"}}
             (outcome rdir))))
    (testing "a finished run"
      (write-out! rdir {:type "result" :subtype "success" :is_error false :stop_reason "end_turn" :result "done"})
      (is (= {:outcome :completed :detail {:summary "done"}} (outcome rdir))))
    (testing "an error, another deferred tool, and no result at all"
      (write-out! rdir {:type "result" :subtype "error_max_turns" :is_error true :result "too long"})
      (is (= :failed (:outcome (outcome rdir))))
      (write-out! rdir {:type "result" :subtype "success" :stop_reason "tool_deferred" :deferred_tool_use {:name "Bash"}})
      (is (= {:outcome :failed :detail {:reason :deferred-another-tool :tool "Bash"}} (outcome rdir)))
      (write-out! rdir {:type "assistant"})
      (is (nil? (outcome rdir)) "no outcome: the runner records it failed with its actions unknown"))
    (testing "a first run records a session; a later one resumes it without a prompt"
      (let [tdir (temp-dir)
            l1 (launch {:turn-dir tdir :run-dir (str tdir "/run-1") :run 1 :spec {:instructions "do it"}})
            sid (get (:files l1) (str tdir "/claude-session-id"))
            _ (spit (str tdir "/claude-session-id") sid)
            l2 (launch {:turn-dir tdir :run-dir (str tdir "/run-2") :run 2 :spec {:instructions "do it"}
                        :resolution {:answered "Postgres"} :resolved-call "toolu_1"})]
        (is (= ["--session-id" sid "--" "do it"] (take-last 4 (:argv l1))))
        (is (= ["--resume" sid] (take-last 2 (:argv l2))))
        (is (some #{"--dangerously-skip-permissions"} (:argv l2)) "the permission policy is passed on every run")
        (is (= {:call "toolu_1" :text "Answer: Postgres"}
               (edn/read-string (get (:files l2) (str tdir "/run-2/resolution.edn")))))))))

(deftest a-prompt-with-images-goes-in-on-stdin
  (let [png (apply str (repeat 64 "b"))
        gone (apply str (repeat 64 "c"))
        {:keys [launch]} (claude/executor {:src "/src" :image (fn [d] (when (= d png) {:mime "image/png" :data "AAAA"}))})
        tdir (temp-dir)
        in   (str tdir "/run-1/in.jsonl")
        l (launch {:turn-dir tdir :run-dir (str tdir "/run-1") :run 1
                   :spec {:instructions "what is this?" :images [png gone]}})
        msg (json/read-str (get (:files l) in) :key-fn keyword)
        [sh c script argv0 f claude-bin] (:argv l)]
    (is (= ["/bin/sh" "-c" "sh" in "claude"] [sh c argv0 f claude-bin])
        "its own command line redirects the prompt's file onto its stdin")
    (is (= ["--input-format" "stream-json"] (take-last 2 (:argv l))) "no positional prompt")
    (is (= [{:type "text" :text "what is this?"}
            {:type "image" :source {:type "base64" :media_type "image/png" :data "AAAA"}}
            {:type "text" :text (str "(image " gone " is no longer kept)")}]
           (get-in msg [:message :content])))
    (testing "the redirect gives the program the file as its stdin"
      (spit (io/file tdir "probe") "hello\n")
      (let [p (-> (ProcessBuilder. ^java.util.List [sh c script argv0 (str tdir "/probe") "cat"])
                  (.redirectInput (java.lang.ProcessBuilder$Redirect/from (io/file "/dev/null")))
                  .start)]
        (is (= "hello\n" (slurp (.getInputStream p))))))
    (testing "a prompt without images is the positional one it always was"
      (let [l (launch {:turn-dir tdir :run-dir (str tdir "/run-1") :run 1 :spec {:instructions "x"}})]
        (is (= "claude" (first (:argv l))))
        (is (= ["--" "x"] (take-last 2 (:argv l))))))))

(deftest the-hook-allows-only-the-resolved-call
  (let [rdir (temp-dir)]
    (is (= "defer" (get-in (claude/hook-decision rdir {:tool_use_id "toolu_1" :tool_input {}})
                           [:hookSpecificOutput :permissionDecision]))
        "an ask with no resolution ends the run")
    (spit (io/file rdir "resolution.edn") (pr-str {:call "toolu_1" :text "Answer: yes"}))
    (is (= {:permissionDecision "allow" :updatedInput {:question "q" :answer "Answer: yes"}}
           (select-keys (:hookSpecificOutput (claude/hook-decision rdir {:tool_use_id "toolu_1" :tool_input {:question "q"}}))
                        [:permissionDecision :updatedInput])))
    (is (= "defer" (get-in (claude/hook-decision rdir {:tool_use_id "toolu_2" :tool_input {}})
                           [:hookSpecificOutput :permissionDecision]))
        "a later ask in the resumed run is not answered by the earlier resolution")))

(deftest the-ask-tool-refuses-without-an-answer
  (is (= ["ask"] (map :name (get-in (ask-tool/respond {:id 1 :method "tools/list"}) [:result :tools]))))
  (is (true? (get-in (ask-tool/respond {:id 2 :method "tools/call" :params {:arguments {:question "q"}}}) [:result :isError])))
  (is (= "Answer: yes" (get-in (ask-tool/respond {:id 3 :method "tools/call" :params {:arguments {:answer "Answer: yes"}}})
                               [:result :content 0 :text])))
  (is (nil? (ask-tool/respond {:method "notifications/initialized"}))))

;; ── reconcile ────────────────────────────────────────────────────────────────

(deftest an-observation-expires-to-unknown
  (let [o (rec/observed :merged 1000 500)]
    (is (= :merged (rec/fact o 1200)))
    (is (rec/unknown? (rec/fact o 1500)) "past its hold it is unknown, not the last value seen")
    (is (rec/unknown? (rec/fact (rec/failed 1000 "gh timed out") 1001)))
    (is (rec/unknown? (rec/fact nil 0)) "never observed is unknown, not absent")))

(deftest a-call-past-its-deadline-is-unknown
  (is (= {:exit 0 :out "hi\n" :err ""} (rec/call! ["echo" "hi"] {:deadline-ms 5000})))
  (is (= {:unknown :deadline} (rec/call! ["sleep" "10"] {:deadline-ms 200})))
  (is (= :not-started (:unknown (rec/call! ["/no/such/binary"] {:deadline-ms 1000}))))
  (is (thrown? AssertionError (rec/call! ["echo"] {})) "no call without a deadline"))

(deftest a-reconciler-converges-on-events
  (let [seen (atom [])
        r (rec/start {:converge #(swap! seen into %)})]
    (rec/event! r :merged)
    (rec/event! r :exited)
    (rec/stop! r)
    (is (= [:merged :exited] @seen))))
