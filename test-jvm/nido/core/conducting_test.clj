(ns nido.core.conducting-test
  "The conductor end to end: writer, runner, rules and the api the conductor's tools speak to, with
   fake executors whose runs are shell scripts — a conductor's script acts through the real tools."
  (:require [clojure.data.json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nido.core.api :as api]
            [nido.core.buffer :as buffer]
            [nido.core.feed :as feed]
            [nido.core.store :as store]
            [nido.core.turn :as turn]
            [nido.core.writer :as writer])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def fake
  {:launch  (fn [{:keys [run spec]}]
              {:argv ["sh" "-c" (if (= 1 run) (:instructions spec) (:on-resume spec "echo '{:outcome :completed}'"))]})
   :outcome (fn [rdir]
              (let [f (io/file rdir "out")]
                (when (.exists f)
                  (let [x (try (edn/read-string (last (str/split-lines (slurp f)))) (catch Exception _ nil))]
                    (when (map? x) x)))))})

(defn- await-until [f ms]
  (let [end (+ (System/currentTimeMillis) ms)]
    (loop [] (or (f) (when (< (System/currentTimeMillis) end) (Thread/sleep 50) (recur))))))

(defn- sh-quote [s] (str "'" (str/replace s "'" "'\\''") "'"))

(defn- tool-line [sock ws turn tool args]
  (str "bb --classpath " (sh-quote (str (System/getProperty "user.dir") "/src")) " -m nido.core.buffer tool " (sh-quote sock) " " (sh-quote ws) " " (sh-quote turn) " "
       tool " " (sh-quote (clojure.data.json/write-str args)) " >&2"))

(defn- run-core
  "Run `body-fn` against a whole core whose conductor turns are `conductor-script` (fn [sock ws
   turn state items] -> shell). `body-fn` gets {:s :cmd!}. `runner-opts` are the runner's; its
   :refusals, the host's count of refused forks, by default never moves, so no run reads as
   refused by this test's host."
  ([conductor-script body-fn] (run-core conductor-script body-fn {}))
  ([conductor-script body-fn runner-opts]
  (let [dir (str (Files/createTempDirectory "nido-cond-" (make-array FileAttribute 0)))
        db (str dir "/core.db")
        sock (str "/tmp/nido-cond-" (System/nanoTime) ".sock")
        s (store/open db)
        f (feed/create)
        parts (atom nil)
        w (writer/start s {:publish (fn [cs] (feed/publish! f cs)
                                      (turn/changed! (:runner @parts) cs)
                                      (buffer/changed! (:buffer @parts) cs))})
        srv (api/serve! {:socket sock :submit #(writer/submit! w %) :open-session #(feed/session f db)})
        r (turn/start (merge {:root (str dir "/turns") :executors {:fake fake} :submit #(writer/submit! w %)
                              :refusals (constantly nil)}
                             runner-opts))
        b (buffer/start {:submit #(writer/submit! w %)
                         :spec-fn (fn [ws t state items]
                                    {:executor :fake :instructions (conductor-script sock ws t state items)})})
        n (atom 0)]
    (reset! parts {:runner r :buffer b})
    (try
      (body-fn {:s s :cmd! #(deref (writer/submit! w {:key (str "person-" (swap! n inc)) :ws "ws" :command %}) 10000 nil)})
      (finally (buffer/stop! b) (turn/stop! r) (api/close! srv) (writer/stop! w) (store/close s))))))

(defn- open-up!
  "Open unit `u` with `first-turn-spec` as its first turn and record its session up: the core's
   buffer then starts that turn, as `\"u first\"`. A unit is never opened over a working copy.
   The buffer records the attempt the owed :up asks for as soon as the unit opens, so this
   resolves that attempt rather than racing it with one of its own — the loser of that race is
   refused, and nothing in this core makes the winner's."
  [s cmd! u goal first-turn-spec]
  (cmd! {:command :open-unit :unit u :project "p" :goal goal :first-turn {:spec first-turn-spec}})
  (let [attempt (await-until #(get-in (store/state s "ws") [:units u :session :attempt :id]) 10000)]
    (is attempt "the buffer recorded the attempt the unit's owed :up asks for")
    (is (:events (cmd! {:command :record-provision-outcome :unit u :attempt attempt :outcome :done :cwd "/tmp"}))
        "the unit's session came up")))

(defn- d-awaiting [s] (filter #(= :open (:status %)) (vals (:operator-asks (store/state s "ws")))))

(defn- conductor-turns [s] (filter #(nil? (:unit %)) (vals (get-in (store/state s "ws") [:turns]))))

(deftest a-unit-s-ask-is-disposed-of-by-the-conductor-and-the-unit-finishes
  (run-core
   (fn [sock ws t _state items]
     (str/join "\n" (concat (for [i items :when (= :turn-asked (:kind i))]
                              (tool-line sock ws t "answer_call" {:turn (:turn i) :answer "use Postgres"}))
                            ["echo '{:outcome :completed}'"])))
   (fn [{:keys [s cmd!]}]
     (open-up! s cmd! "u" "pick a database"
               {:executor :fake :instructions "echo '{:outcome :asked :call {:id \"c\" :question \"which db?\"}}'"
                :on-resume "echo \"{:outcome :completed :detail {:summary \\\"done\\\"}}\""})
     (is (await-until #(= :completed (get-in (store/state s "ws") [:turns "u first" :status])) 30000)
         "the unit turn asked, the conductor answered, the rules resumed it, and it finished")
     (is (= {:answered "use Postgres"} (get-in (store/state s "ws") [:turns "u first" :resolution])))
     (is (await-until #(and (empty? (:inbox (store/state s "ws")))
                            (every? (comp #{:completed} :status) (conductor-turns s))
                            (= 2 (count (conductor-turns s))))
                      30000)
         "the conductor heard the ask, then the finish, each once")
     (is (empty? (d-awaiting s)) "nothing was put to the person"))))

(deftest a-conductor-s-own-ask-goes-to-the-person
  (run-core
   (fn [_sock _ws _t _state _items]
     "echo '{:outcome :asked :call {:id \"c\" :question \"open unit b?\" :explored \"x\" :options [\"yes\" \"no\"] :recommendation \"yes\"}}'")
   (fn [{:keys [s cmd!]}]
     (open-up! s cmd! "u" "g" {:executor :fake :instructions "echo '{:outcome :completed}'"})
     (is (await-until #(seq (d-awaiting s)) 30000) "the rules raised the conductor's ask to the person")
     (let [[a] (d-awaiting s)]
       (is (= "open unit b?" (get-in a [:content :question])))
       (cmd! {:command :answer-operator-ask :ask (:id a) :answer "yes"}))
     (is (await-until #(every? (comp #{:completed} :status) (conductor-turns s)) 30000)
         "the person's answer resumed the conductor turn, which finished"))))

(deftest a-conductor-that-keeps-failing-stops-being-woken
  (run-core
   (fn [& _] "echo 'not an outcome'")
   (fn [{:keys [s cmd!]}]
     (open-up! s cmd! "u" "g" {:executor :fake :instructions "echo '{:outcome :completed}'"})
     (is (await-until #(= 3 (:conductor-failures (store/state s "ws"))) 30000))
     (Thread/sleep 1000)
     (is (= 3 (count (conductor-turns s))) "no fourth wake")
     (is (seq (:inbox (store/state s "ws"))) "and nothing it was told is lost"))))

(deftest a-conductor-the-host-refuses-is-retried-later-and-the-person-is-not-asked
  ;; Every run sees the host refuse a fork while it runs, as conductor-36575..36583 did when the
  ;; core's service cgroup sat at its TasksMax.
  (let [refused (atom 0)]
    (run-core
     (fn [& _] "echo 'no outcome, the host refused a thread' >&2")
     (fn [{:keys [s cmd!]}]
       (cmd! {:command :open-unit :unit "u" :project "p" :goal "g" :session-cwd "/tmp"})
       (cmd! {:command :start-turn :turn "t" :unit "u" :spec {:executor :fake :instructions "echo '{:outcome :completed}'"}})
       (is (await-until #(= 1 (:conductor-exhaustions (store/state s "ws"))) 30000))
       (Thread/sleep 1500)
       (let [st (store/state s "ws")
             [k] (conductor-turns s)]
         (is (= 1 (count (conductor-turns s))) "no second wake inside the backoff")
         (is (= :resource-exhausted (get-in k [:detail :reason])))
         (is (= 0 (:conductor-failures st)))
         (is (> (:conductor-retry-at st) (System/currentTimeMillis)))
         (is (empty? (d-awaiting s)) "no stop ask to the person")
         (is (seq (:inbox st)) "and nothing it was told is lost")))
     {:refusals #(swap! refused inc)})))

(deftest a-conductor-refused-at-its-own-cap-is-retried-later-and-the-person-is-not-asked
  ;; Its own scope, capped at five, refuses the sixth sleep; its err says nothing, so only the
  ;; scope's count can tell this from a conductor that failed.
  (if-let [scope (turn/probe-scope "systemd-run" 5)]
    (run-core
     (fn [& _] "exec 2>/dev/null; for i in 1 2 3 4 5 6; do sleep 1 & done; wait")
     (fn [{:keys [s cmd!]}]
       (cmd! {:command :open-unit :unit "u" :project "p" :goal "g" :session-cwd "/tmp"})
       (cmd! {:command :start-turn :turn "t" :unit "u" :spec {:executor :fake :instructions "echo '{:outcome :completed}'"}})
       (is (await-until #(= 1 (:conductor-exhaustions (store/state s "ws"))) 30000))
       (let [st (store/state s "ws")
             [k] (conductor-turns s)]
         (is (= :resource-exhausted (get-in k [:detail :reason])))
         (is (pos? (get-in k [:detail :pid-refusals])) "counted at its own scope")
         (is (= 0 (:conductor-failures st)))
         (is (> (:conductor-retry-at st) (System/currentTimeMillis)))
         (is (empty? (d-awaiting s)) "no stop ask to the person")))
     {:scope scope})
    (println "skipped: no user systemd manager to give a run a scope")))

(deftest a-run-the-host-will-not-start-is-reported-not-left-running
  (run-core
   (fn [& _] "echo '{:outcome :completed}'")
   (fn [{:keys [s cmd!]}]
     (cmd! {:command :open-unit :unit "u" :project "p" :goal "g" :session-cwd "/nonexistent/nido-test"})
     (cmd! {:command :start-turn :turn "t" :unit "u" :spec {:executor :fake :instructions "true"}})
     (is (await-until #(= :failed (get-in (store/state s "ws") [:turns "t" :status])) 30000)
         "a launch that throws ends the turn rather than leaving it running")
     (is (= :launch-failed (get-in (store/state s "ws") [:turns "t" :detail :reason])))
     (is (re-find #"No such file or directory" (str (get-in (store/state s "ws") [:turns "t" :detail :message])))
         "carrying the exception's message, which classification reads"))))

(deftest a-launch-the-host-refuses-a-fork-is-resource-exhausted
  (let [refused {:outcome :failed
                 :detail {:reason :launch-failed
                          :message "Cannot run program \"sh\": error=11, Resource temporarily unavailable"}}]
    (is (= {:outcome :failed
            :detail {:reason :resource-exhausted :at 7
                     :message "Cannot run program \"sh\": error=11, Resource temporarily unavailable"
                     :was (:detail refused)}}
           (turn/classify-exhaustion refused (get-in refused [:detail :message]) nil 7))
        "the exception's message carries the refusal")
    (let [other (assoc-in refused [:detail :message] "Cannot run program \"sh\": error=2, No such file or directory")]
      (is (= other (turn/classify-exhaustion other (get-in other [:detail :message]) nil 7))
          "a launch that threw for another reason stays :launch-failed"))))

(deftest a-taken-back-run-counts-refusals-from-before-its-launch
  ;; The daemon that launched both counted 3 refusals at the core's cgroups just before each launch.
  ;; One run failed while no daemon held it: nothing counted at its end, so the count says nothing
  ;; of it. The other is taken back still running and fails under the starting daemon, which counts
  ;; 5 when it ends: the 2 between are its, though some came before that daemon started watching.
  (let [dir (.toFile (Files/createTempDirectory "nido-take-back" (make-array FileAttribute 0)))
        root (str dir "/turns")
        s (store/open (str dir "/core.db"))
        w (writer/start s {})
        n (atom 0)
        cmd! (fn [c] (deref (writer/submit! w {:key (str "k" (swap! n inc)) :ws "ws" :command c}) 10000 nil))
        dead (turn/start {:root root :executors {:fake fake} :submit (fn [_] nil) :refusals (constantly 3)})
        detail #(get-in (store/state s "ws") [:turns % :detail])]
    (try
      (doseq [u ["a" "b"]] (cmd! {:command :open-unit :unit u :project "p" :goal "g" :session-cwd "/tmp"}))
      (cmd! {:command :start-turn :turn "ended" :unit "a" :spec {:executor :fake :instructions "echo boom >&2; exit 1"}})
      (cmd! {:command :start-turn :turn "still" :unit "b" :spec {:executor :fake :instructions "sleep 2; echo boom >&2; exit 1"}})
      (turn/changed! dead [{:ws "ws" :state (store/state s "ws")}])
      (Thread/sleep 500)
      (turn/stop! dead)
      (is (= "3" (str/trim (slurp (io/file (turn/run-dir (turn/turn-dir root "ws" "still") 1) "pid-refusals-before"))))
          "the launch kept its count in the run's directory")
      (let [live (turn/start {:root root :executors {:fake fake} :submit #(writer/submit! w %) :refusals (constantly 5)})]
        (try
          (turn/take-back! live "ws" (store/state s "ws"))
          (is (await-until #(= :failed (get-in (store/state s "ws") [:turns "still" :status])) 10000))
          (is (= {:reason :resource-exhausted :pid-refusals 2} (select-keys (detail "still") [:reason :pid-refusals]))
              "counted from just before its launch to its end")
          (is (= :failed (get-in (store/state s "ws") [:turns "ended" :status])))
          (is (not= :resource-exhausted (:reason (detail "ended")))
              "a run found already ended is not reclassified on the core's count alone")
          (finally (turn/stop! live))))
      (finally (writer/stop! w) (store/close s)))))

(defn- cgroup-tree!
  "A cgroup v2 hierarchy under a temporary root: `levels` maps a relative cgroup path to its
   pids.events and, when given, pids.events.local `max` counts."
  [levels]
  (let [root (.toFile (Files/createTempDirectory "nido-cgroup" (make-array FileAttribute 0)))]
    (doseq [[path {:keys [events local]}] levels]
      (let [d (io/file root path)]
        (.mkdirs d)
        (spit (io/file d "pids.events") (str "max " events "\n"))
        (when local (spit (io/file d "pids.events.local") (str "max " local "\n")))))
    (str root)))

(deftest each-refusal-is-counted-once-at-the-level-that-refused-it
  (testing "hierarchical counters (pids.events.local present): this host on 2026-11-25"
    ;; 3,075 refusals at nido-core.service's own limit and 15 at a sibling run's scope cap. The
    ;; hierarchical pids.events of every slice above holds all 3,090; summed per level they read
    ;; 15,435, and the sibling's 15 would reclassify any run of the core's that failed meanwhile.
    (let [root (cgroup-tree! {"user.slice"                                                   {:events 3090 :local 0}
                              "user.slice/user-1000.slice"                                   {:events 3090 :local 0}
                              "user.slice/user-1000.slice/user@1000.service"                 {:events 3090 :local 0}
                              "user.slice/user-1000.slice/user@1000.service/app.slice"       {:events 3090 :local 0}
                              "user.slice/user-1000.slice/user@1000.service/app.slice/nido-core.service" {:events 3075 :local 3075}
                              "user.slice/user-1000.slice/user@1000.service/app.slice/nido-run-t-1-x.scope" {:events 15 :local 15}})]
      (is (= 3075 (turn/pid-refusals-above root "/user.slice/user-1000.slice/user@1000.service/app.slice/nido-core.service"))
          "the core's own refusals, once — not once per slice above it")
      (is (nil? (turn/pid-refusals-above root "/nowhere")) "nothing where there is no count"))
    (let [core "/user.slice/app.slice/nido-core.service"
          before (cgroup-tree! {"user.slice"           {:events 0 :local 0}
                                "user.slice/app.slice" {:events 0 :local 0}
                                "user.slice/app.slice/nido-core.service" {:events 0 :local 0}})
          after  (cgroup-tree! {"user.slice"           {:events 4 :local 0}
                                "user.slice/app.slice" {:events 4 :local 0}
                                "user.slice/app.slice/nido-core.service" {:events 0 :local 0}
                                "user.slice/app.slice/nido-run-other.scope" {:events 4 :local 4}})]
      (is (= 0 (- (turn/pid-refusals-above after core) (turn/pid-refusals-above before core)))
          "a sibling run refused at its own cap is not a refusal of the core's runs")))
  (testing "a kernel without pids.events.local counts each level's own forks"
    (let [root (cgroup-tree! {"user.slice" {:events 0} "user.slice/nido-core.service" {:events 6}})]
      (is (= 6 (turn/pid-refusals-above root "/user.slice/nido-core.service"))))))

(deftest a-failed-run-is-exhausted-when-the-host-refused-it
  (let [failed {:outcome :failed :detail {:reason :ended-without-outcome}}]
    (is (= {:outcome :failed :detail {:reason :resource-exhausted :at 7 :pid-refusals 2
                                      :was {:reason :ended-without-outcome}}}
           (turn/classify-exhaustion failed "" 2 7))
        "the cgroup refused forks while it ran")
    (is (= :resource-exhausted
           (get-in (turn/classify-exhaustion failed "error: Failed to start HTTP Client thread: EAGAIN" nil 7)
                   [:detail :reason]))
        "its err says so")
    (is (= {:reason :resource-exhausted :message "pthread_create failed: Resource temporarily unavailable"}
           (select-keys (:detail (turn/classify-exhaustion
                                  failed (str (apply str (repeat 5000 "x")) "\npthread_create failed: Resource temporarily unavailable\n")
                                  nil 7))
                        [:reason :message]))
        "its err says so after any amount of output, and only the refusal's lines are kept")
    (doseq [err ["fork failed: errno 11" "Cannot run program \"x\": error=11, Resource temporarily unavailable"
                 "EAGAIN" "thread limit" "pthread_create failed" "unable to create native thread"
                 "bash: fork: retry: No child processes" "Cannot fork" "error: 11"]]
      (is (= :resource-exhausted (get-in (turn/classify-exhaustion failed err nil 7) [:detail :reason]))
          (str "every name of the refusal: " err)))
    (is (= failed (turn/classify-exhaustion failed "boom" 0 7)) "neither")
    (is (= failed (turn/classify-exhaustion failed "boom" nil 7)) "nor where nothing counts")
    (is (= {:outcome :completed} (turn/classify-exhaustion {:outcome :completed} "EAGAIN" 4 7))
        "a run that completed stays completed")))
