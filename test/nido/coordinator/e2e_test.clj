(ns nido.coordinator.e2e-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is use-fixtures]]
   [nido.platform.core :as nido-core]
   [nido.coordinator.daemon.agent :as agent]
   [nido.coordinator.daemon.breakers :as breakers]
   [nido.boot.core :as core]
   [nido.coordinator.daemon.executor :as executor]
   [nido.coordinator.source.queue :as queue]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.state :as cstate]
   [nido.platform.io :as io]
   [nido.platform.project :as project]
   [nido.coordinator.work :as work]))

(defn- reset-executor! [f]
  (executor/configure! {:global-cap 4})
  (executor/clear!)
  (f))

(use-fixtures :each reset-executor!)

(defn- tick-until-terminal!
  "Poll for a terminal state (max 20 iterations × 50ms = 1s cap).
   Needed because process-envelope! now submits to the executor instead
   of blocking synchronously. Robust under load."
  []
  (loop [i 0]
    (when (< i 20)
      (core/tick!)
      (let [run-dirs (->> (fs/list-dir (cstate/runs-dir))
                          (filter fs/directory?))
            state (when (seq run-dirs)
                    (:state (runs/read-run (str (fs/file-name (first run-dirs))))))]
        (if (contains? #{:done :failed :awaiting-review :halted :dry-run-would-fire} state)
          (do (Thread/sleep 50) (core/tick!))
          (do (Thread/sleep 50) (recur (inc i))))))))

(deftest manual-trigger-end-to-end
  (let [tmp     (fs/create-temp-dir)
        tmp-str (str tmp)
        sid     (atom nil)]   ; capture the session-id run-blocking! generates + passes to launch!
    (try
      (with-redefs [nido-core/nido-root         (constantly tmp-str)
                    ;; core/tick! reaches maybe-adopt! -> work/prune-dead-registry! for real,
                    ;; which would read/probe/delete from the developer's actual
                    ;; ~/.nido/state/sessions.edn — stub it out so this test stays hermetic.
                    work/prune-dead-registry! (constantly [])
                    ;; stub project listing — real shape is {project-name {...}}
                    project/list-projects    (constantly {"brian" {:directory "/tmp"}})
                    ;; stub session spawn — write a minimal session-home tree so
                    ;; the agent has a worktree path to cd into and a session-home
                    ;; link from run-dir to find.
                    runs/spawn-session-for-run!
                    (fn [run]
                      (let [home (fs/path tmp-str "sessions" "brian" (:session-name run))
                            wt   (fs/path home "worktree")
                            link (cstate/run-session-home-link (:id run))]
                        (fs/create-dirs wt)
                        (fs/create-sym-link link home)
                        {}))
                    ;; stub launch-context — avoids real I/O (jj/git) that would
                    ;; add latency; cwd is ignored since agent/launch! is also stubbed.
                    runs/launch-context
                    (fn [_run] {:cwd tmp-str :briefing "" :mcp-config nil
                                :add-dirs [] :run-paths ""})
                    ;; stub the agent launcher — write a status file and a fake log line,
                    ;; return a successful result with a known claude-session-id.
                    agent/launch!
                    (fn [opts]
                      (reset! sid (:claude-session-id opts))   ; echo the caller-supplied id, like the real launch!
                      (io/write-edn! (cstate/run-status-path (:run-id opts))
                                     {:phase :awaiting-input :note "from fake"})
                      (spit (cstate/run-agent-log (:run-id opts))
                            (str "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\""
                                 (:claude-session-id opts) "\"}\n"))
                      {:exit-code 0 :claude-session-id (:claude-session-id opts)})]
        ;; 1. configure a trigger
        (fs/create-dirs (fs/parent (cstate/triggers-path :brian)))
        (io/write-edn! (cstate/triggers-path :brian)
                       {:triggers [{:name    :investigate-bug
                                    :source  {:type :manual}
                                    :skill   :investigate-bug
                                    :payload "url={{event/url}}"
                                    :limits  {:budget "8h"}}]})
        ;; 2. enqueue an envelope
        (cstate/ensure-dirs!)
        (queue/enqueue! {:target  {:project :brian :trigger :investigate-bug}
                         :payload {:url "https://x"}})
        ;; 3. tick + wait for executor future + reap
        (tick-until-terminal!)
        ;; 4. assert
        (let [run-dirs (->> (fs/list-dir (cstate/runs-dir))
                            (filter fs/directory?))]
          (is (= 1 (count run-dirs)) "exactly one Run was created")
          (let [run-id (str (fs/file-name (first run-dirs)))
                run    (runs/read-run run-id)]
            (is (= :awaiting-review (:state run)))
            (is (string? @sid) "run-blocking! generated + passed a session-id to launch!")
            (is (= @sid (:claude-session-id run))
                "the generated session-id is persisted on the Run (resume-shim resolvable)")
            (is (= "/investigate-bug url=https://x" (:first-message run)))
            (is (fs/exists? (cstate/run-agent-log run-id))))))
      (finally (fs/delete-tree tmp)))))

(deftest breaker-trips-after-3-failures-on-same-trigger
  (let [tmp         (fs/create-temp-dir)
        fail-launch (fn [_opts] {:exit-code 1 :claude-session-id nil :timed-out? false})
        no-session  (fn [_run] {})]
    (try
      (with-redefs [nido-core/nido-root            (constantly (str tmp))
                    ;; see manual-trigger-end-to-end above — same core/tick! -> real-prune exposure.
                    work/prune-dead-registry!   (constantly [])
                    project/list-projects       (constantly {"brian" {:directory "/tmp"}})
                    runs/spawn-session-for-run! no-session
                    ;; stub launch-context — avoids real I/O (jj/git) that would
                    ;; add latency and break the timing in tick-until-terminal!
                    runs/launch-context         (fn [_run] {:cwd (str tmp) :briefing ""
                                                            :mcp-config nil :add-dirs []
                                                            :run-paths ""})
                    agent/launch!               fail-launch]
        (fs/create-dirs (fs/parent (cstate/triggers-path :brian)))
        (io/write-edn! (cstate/triggers-path :brian)
                       {:triggers [{:name    :failing
                                    :source  {:type :manual}
                                    :skill   :foo
                                    :payload ""
                                    :limits  {:budget "8h"}}]})
        (cstate/ensure-dirs!)
        (dotimes [_ 3]
          (queue/enqueue! {:target  {:project :brian :trigger :failing}
                           :payload {}})
          (tick-until-terminal!))
        (is (breakers/tripped? :brian :failing)
            "3 failures should trip the breaker")
        ;; A 4th fire should NOT create a new Run (breaker open).
        (let [runs-before (count (filter fs/directory? (fs/list-dir (cstate/runs-dir))))]
          (queue/enqueue! {:target {:project :brian :trigger :failing} :payload {}})
          (tick-until-terminal!)
          (is (= runs-before (count (filter fs/directory? (fs/list-dir (cstate/runs-dir)))))
              "skipped envelope should not create a Run")))
      (finally (fs/delete-tree tmp)))))
