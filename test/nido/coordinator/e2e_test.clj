(ns nido.coordinator.e2e-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is use-fixtures]]
   [nido.platform.core :as nido-core]
   [nido.coordinator.lane.conduct :as conduct]
   [nido.coordinator.daemon.breakers :as breakers]
   [nido.boot.core :as core]
   [nido.coordinator.executor :as executor]
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

(defn- tick-until-idle!
  "Tick until the executor has nothing queued and every Run it started has
   finished, or throw after 30s. process-envelope! hands a Run to the executor's
   futures, so it must be waited out INSIDE the test's with-redefs: a Run still
   running once they unwind spawns a real session against the real ~/.nido."
  []
  (let [deadline (+ (System/currentTimeMillis) 30000)]
    (loop []
      (core/tick!)
      (executor/await-idle! (max 0 (- deadline (System/currentTimeMillis))))
      (when (pos? (:queued (executor/snapshot)))
        (when (> (System/currentTimeMillis) deadline)
          (throw (ex-info "executor queue never drained" (executor/snapshot))))
        (recur)))
    (core/tick!)))

(defn- stop-every-running-turn
  "A conduct/conduct! stand-in: every Run still :running had its turn ended by its session's Stop."
  [_]
  {:turn-ends (vec (for [id (runs/list-run-ids)
                         :let [r (runs/read-run id)]
                         :when (= :running (:state r))]
                     {:project (:project r) :ws-id (:workstream-id r) :session (:session-name r)
                      :event {:event :stop :last-message "done"}
                      ;; Its own instruction's Stop: given as the turn began, after the Run ran.
                      :instruction {:token "T" :at (str (java.time.Instant/now)) :seen? true :for id}
                      :run id}))
   :denials [] :held [] :blocked [] :delivered [] :failed [] :working []})

(defn- every-running-turn-outstanding
  "A conduct/faceted-sessions stand-in: each Run still :running has its instruction outstanding,
   given now — after the Run began running, so its Stop is the one that ends it."
  [_]
  (vec (for [id (runs/list-run-ids)
             :let [r (runs/read-run id)]
             :when (= :running (:state r))]
         {:project (:project r) :ws-id (:workstream-id r) :session-name (:session-name r)
          :facet {:driver :nido :pending {:token "T" :at (str (java.time.Instant/now)) :for id}}})))

(deftest manual-trigger-end-to-end
  (let [tmp     (fs/create-temp-dir)
        tmp-str (str tmp)
        started (atom nil)]   ; what the background session was started with
    (try
      (with-redefs [nido-core/nido-root         (constantly tmp-str)
                    ;; core/tick! reaches maybe-adopt! -> work/prune-dead-registry! for real,
                    ;; which would read/probe/delete from the developer's actual
                    ;; ~/.nido/state/sessions.edn — stub it out so this test stays hermetic.
                    work/prune-dead-registry! (constantly [])
                    ;; and maybe-refresh-titles!, whose sweep runs on a future that can
                    ;; outlive these redefs and call a real model over the real ~/.nido.
                    core/maybe-refresh-titles! (constantly nil)
                    ;; and the conductor pass, which would bring a conductor's session up for real;
                    ;; carrying the Run's session is the conductor lane's, tested there.
                    core/conductor-pass!       (constantly nil)
                    nido.coordinator.lane.conductor/carry! (constantly {:carried nil :why :stubbed})
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
                    ;; stub the substrate start — the skill writes its status file during
                    ;; the turn, and Claude Code names the session it started.
                    conduct/start!
                    (fn [opts]
                      (reset! started opts)
                      (let [r (runs/find-for-session (:project opts) (:ws-id opts) (:session-name opts))]
                        (io/write-edn! (cstate/run-status-path (:id r))
                                       {:phase :awaiting-input :note "from fake"}))
                      {:session-id "sess-e2e" :bg-id "sess-e2e"})
                    ;; the session's Stop, as the next conduct pass reads it
                    conduct/conduct! stop-every-running-turn
                    conduct/faceted-sessions every-running-turn-outstanding]
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
        (tick-until-idle!)
        ;; 4. assert
        (let [run-dirs (->> (fs/list-dir (cstate/runs-dir))
                            (filter fs/directory?))]
          (is (= 1 (count run-dirs)) "exactly one Run was created")
          (let [run-id (str (fs/file-name (first run-dirs)))
                run    (runs/read-run run-id)]
            (is (= :awaiting-review (:state run)))
            (is (= "/investigate-bug url=https://x" (:prompt @started))
                "the background session started on the run's first message")
            (is (= "sess-e2e" (:claude-session-id run))
                "the session's own id is on the Run (attach and replies resolve it)")
            (is (= "/investigate-bug url=https://x" (:first-message run))))))
      (finally (fs/delete-tree tmp)))))

(deftest breaker-trips-after-3-failures-on-same-trigger
  (let [tmp         (fs/create-temp-dir)
        fail-start  (fn [_opts] (throw (ex-info "claude --bg did not start a session" {})))
        no-session  (fn [_run] {})]
    (try
      (with-redefs [nido-core/nido-root            (constantly (str tmp))
                    ;; see manual-trigger-end-to-end above — same core/tick! -> real-prune exposure,
                    ;; and the same conductor pass.
                    work/prune-dead-registry!   (constantly [])
                    core/conductor-pass!        (constantly nil)
                    project/list-projects       (constantly {"brian" {:directory "/tmp"}})
                    runs/spawn-session-for-run! no-session
                    ;; stub launch-context — avoids real I/O (jj/git) that would
                    ;; add latency to every Run tick-until-idle! waits out
                    runs/launch-context         (fn [_run] {:cwd (str tmp) :briefing ""
                                                            :mcp-config nil :add-dirs []
                                                            :run-paths ""})
                    conduct/start!              fail-start
                    conduct/conduct!            stop-every-running-turn
                    conduct/faceted-sessions    every-running-turn-outstanding]
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
          (tick-until-idle!))
        (is (breakers/tripped? :brian :failing)
            "3 failures should trip the breaker")
        ;; A 4th fire should NOT create a new Run (breaker open).
        (let [runs-before (count (filter fs/directory? (fs/list-dir (cstate/runs-dir))))]
          (queue/enqueue! {:target {:project :brian :trigger :failing} :payload {}})
          (tick-until-idle!)
          (is (= runs-before (count (filter fs/directory? (fs/list-dir (cstate/runs-dir)))))
              "skipped envelope should not create a Run")))
      (finally (fs/delete-tree tmp)))))
