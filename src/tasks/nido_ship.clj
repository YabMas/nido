(ns tasks.nido-ship
  "CLI entrypoint: hand the current session's branch to nido's merge lane.
   `nido ship` resolves the session from cwd (home-aware), writes a :ship
   envelope to the coordinator queue, and returns immediately — the daemon
   drives the branch home with /drive-home, serialized one at a time. See
   docs/superpowers/specs/2026-06-30-local-merge-queue-design.md."
  (:require
   [nido.coordinator.daemon.pid :as pid]
   [nido.coordinator.source.queue :as queue]
   [nido.coordinator.record.session :as session]
   [nido.session.lifecycle :as lifecycle]
   [nido.platform.task-args :as task-args]))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  enqueue-ship!
  "Write a :ship envelope. Returns the envelope path."
  [{:keys [project session ws-id]}]
  (queue/enqueue! {:type :ship :project project :session session :ws-id ws-id}))

(defn- resolve-coords
  "Resolve [project session] from explicit args or the home-aware cwd union
   (worktree registry OR session-home). Mirrors lifecycle/resolve-link-coords."
  [opts session-arg]
  (let [from-cwd     (lifecycle/session-from-cwd)
        home-coords  (lifecycle/session-home-coords-from-cwd)
        project-name (or (some-> (:project opts) name)
                         (:project from-cwd)
                         (first home-coords))
        session-name (or session-arg (:session from-cwd) (second home-coords))]
    [project-name session-name]))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  ship
  "`nido ship [:project <p> <session>]` — enqueue the resolved session's branch
   onto the merge lane. Run from the session home or worktree."
  [& args]
  (let [[positionals opts] (task-args/split-args args)
        session-arg        (first positionals)
        [project-name session-name] (resolve-coords opts session-arg)]
    (when-not (and project-name session-name)
      (println "nido ship: could not resolve project + session.")
      (println "  Run from a session home/worktree, or pass :project <p> <session>.")
      (System/exit 1))
    (let [project (keyword project-name)
          ws-id   (or (:ws-id opts)
                      (session/workstream-id-for project session-name))]
      (when-not ws-id
        (println (str "nido ship: no workstream found for " project-name "/" session-name "."))
        (System/exit 1))
      (enqueue-ship! {:project project :session session-name :ws-id ws-id})
      (println (str "Queued for shipping: " project-name "/" session-name))
      (when-not (pid/alive?)
        (println "  ⚠ coordinator daemon is not running — it will be picked up on next start."))
      (println "  Once driving starts you can close this tab; nido will take it home.")
      (println "  Stop editing this worktree — shipping is a handoff."))))
