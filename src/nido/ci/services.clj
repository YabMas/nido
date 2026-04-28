(ns nido.ci.services
  "Service orchestration: spawn each service of a step under its own
   process group, poll its healthcheck, surface readiness via promises,
   and tear the whole graph down when the run ends.

   Chunk 4 handles the single-service case (no :depends-on fan-out, no
   :command healthcheck). Chunk 5 layers dependency-aware scheduling and
   the command probe on top of this."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.java.io :as jio]
   [nido.ci.healthcheck :as hc]
   [nido.ci.isolation :as isolation]
   [nido.ci.manifest :as manifest]
   [nido.ci.paths :as paths]
   [nido.ci.step-run :as step-run]
   [nido.core :as core]
   [nido.process :as proc]))

(defn- spawn!
  "Spawn a service process under a new process group. Returns
   {:pid :pgid}. stdout+stderr are appended to log-path."
  [svc-def env working-dir log-path]
  (fs/create-dirs (fs/parent log-path))
  (let [log-out (jio/output-stream log-path :append true)
        child (p/process {:cmd (isolation/spawn-command-vec (:start svc-def))
                          :dir working-dir
                          :extra-env env
                          :out log-out
                          :err log-out})
        pid (.pid ^java.lang.Process (:proc child))
        ;; await-own-pgid blocks until the perl wrapper's setpgrp(0,0)
        ;; has landed (pgid == pid). Without this we can race and snapshot
        ;; the *parent's* (bb) pgid — which then makes a downstream
        ;; kill-all! send SIGTERM upstream, taking down the whole CI run
        ;; and showing as a spurious :interrupted on every other step.
        pgid (or (isolation/await-own-pgid pid) pid)]
    {:pid pid :pgid pgid}))

(defn- await-deps!
  "Block until every dep's ready-promise is delivered. Returns :ready if
   all deliver :ready, :aborted on abort-promise, :failed if any dep
   failed (so the dependent can skip its spawn)."
  [svc-def all-promises abort-promise]
  (if-let [deps (seq (:depends-on svc-def))]
    (loop [remaining (vec deps)]
      (if (empty? remaining)
        :ready
        (let [dep (first remaining)
              dep-p (get all-promises dep)]
          (cond
            (nil? dep-p) :failed ; unknown dep -- validation should catch this
            (realized? abort-promise) :aborted
            :else
            (case @dep-p
              :ready (recur (rest remaining))
              :aborted :aborted
              :failed)))))
    :ready))

(defn- spawn-and-probe!
  "Spawn a service process and evaluate its healthcheck. Updates run state
   and delivers the ready-promise with :ready / :failed / :aborted."
  [{:keys [svc-def run-atom env working-dir ready-promise
           abort-promise defaults]}]
  (let [svc-name (:name svc-def)
        log-path (get-in @run-atom [:services svc-name :log-path])
        {:keys [pid pgid]} (spawn! svc-def env working-dir log-path)]
    (swap! run-atom step-run/service-starting svc-name pid pgid)
    (manifest/sync-run! @run-atom)
    (core/log-step (str "service " (name svc-name)
                        " starting (pid " pid " pgid " pgid ")"))
    (if-let [hc-spec (:healthcheck svc-def)]
      (case (hc/await-ready!
             {:healthcheck hc-spec
              :env env
              :pid pid
              :abort-promise abort-promise
              :defaults defaults})
        :ready
        (do (swap! run-atom step-run/service-ready svc-name)
            (manifest/sync-run! @run-atom)
            (core/log-step (str "service " (name svc-name) " ready"))
            (deliver ready-promise :ready))

        :aborted
        (deliver ready-promise :aborted)

        ;; :timed-out or :process-died
        (do (swap! run-atom step-run/service-exited svc-name nil)
            (manifest/sync-run! @run-atom)
            (core/log-step (str "service " (name svc-name)
                                " failed healthcheck"))
            (deliver ready-promise :failed)))

      ;; No healthcheck declared — ready once spawned.
      (do (swap! run-atom step-run/service-ready svc-name)
          (manifest/sync-run! @run-atom)
          (deliver ready-promise :ready)))))

(defn- run-one!
  "Launch a single service in its own future. Blocks on dep promises
   first, then spawns + probes. Delivers :ready / :failed / :aborted."
  [{:keys [svc-def ready-promise abort-promise all-promises] :as ctx}]
  (future
    (try
      (case (await-deps! svc-def all-promises abort-promise)
        :aborted (deliver ready-promise :aborted)
        :failed  (deliver ready-promise :failed)
        :ready   (spawn-and-probe! ctx))
      (catch Throwable t
        (core/log-step (str "service " (name (:name svc-def))
                            " crashed: " (ex-message t)))
        (when-not (realized? ready-promise)
          (deliver ready-promise :failed))))))

(defn- validate-graph!
  "Reject unknown dep references and dependency cycles before any future
   runs. Throws with an actionable message."
  [svc-defs]
  (let [names (set (map :name svc-defs))]
    (doseq [def svc-defs
            dep (:depends-on def)]
      (when-not (contains? names dep)
        (throw (ex-info (str "Service " (name (:name def))
                             " depends on unknown service " (name dep))
                        {:service (:name def) :missing-dep dep
                         :known-services (vec names)}))))
    (let [adj (into {} (for [d svc-defs] [(:name d) (or (:depends-on d) [])]))]
      ;; Detect cycle via DFS with a "visiting" set.
      (letfn [(visit [node path visited stack]
                (cond
                  (contains? stack node)
                  (throw (ex-info (str "Cycle in service dependencies: "
                                       (mapv name (conj path node)))
                                  {:cycle (conj path node)}))
                  (contains? visited node) visited
                  :else
                  (let [visited' (reduce
                                  (fn [vs child]
                                    (visit child (conj path node)
                                           vs (conj stack node)))
                                  visited
                                  (get adj node))]
                    (conj visited' node))))]
        (reduce (fn [vs d] (visit (:name d) [] vs #{}))
                #{} svc-defs)))))

(defn start-all!
  "Seed service states on the run and launch each service's future.
   Validates the dep graph first. Returns a map of svc-name ->
   ready-promise."
  [{:keys [run-atom env working-dir abort-promise defaults]}]
  (let [step (:step @run-atom)
        svc-defs (:services step)
        _ (validate-graph! svc-defs)
        initial-states
        (into {}
              (for [def svc-defs
                    :let [log-path (paths/service-log-path
                                    (:paths @run-atom) (:name def))]]
                [(:name def) (step-run/new-service def log-path)]))
        promises (into {} (for [def svc-defs] [(:name def) (promise)]))]
    (swap! run-atom step-run/with-services initial-states)
    (swap! run-atom step-run/begin-starting-services)
    (manifest/sync-run! @run-atom)
    (doseq [def svc-defs]
      (run-one! {:svc-def def
                 :run-atom run-atom
                 :env env
                 :working-dir working-dir
                 :ready-promise (get promises (:name def))
                 :abort-promise abort-promise
                 :defaults defaults
                 :all-promises promises}))
    promises))

(defn await-all-ready!
  "Block until every ready-promise is delivered. Returns :all-ready,
   :aborted, or :failed."
  [ready-promises]
  (let [results (doall (for [[_ p] ready-promises] @p))]
    (cond
      (some #{:aborted} results) :aborted
      (every? #{:ready} results) :all-ready
      :else :failed)))

(defn kill-all!
  "Terminate every service's process group. Already-dead services are
   no-ops. Call with a run snapshot, not the atom."
  [run-snapshot]
  (doseq [[_ svc] (:services run-snapshot)]
    (when-let [pgid (:pgid svc)]
      (try (proc/stop-process-group! pgid) (catch Exception _ nil)))))

(defn mark-all-killed
  "Transition any still-live services to :killed in the run state.
   Returns the updated run."
  [run]
  (reduce (fn [r [svc-name svc]]
            (if (#{:exited :killed} (:state svc))
              r
              (step-run/service-killed r svc-name)))
          run
          (:services run)))
