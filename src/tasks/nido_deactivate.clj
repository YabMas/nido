(ns tasks.nido-deactivate
  "bb-task entrypoint: take nido's recipe off the workstream a person is working in."
  (:require
   [nido.coordinator.work :as work]
   [nido.platform.task-args :as task-args]
   [nido.review.stages :as stages]
   [nido.session.lifecycle :as lifecycle]))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  deactivate-cmd*
  "Deactivate the recipe on the workstream `:cwd` (default: the process's) resolves to, or on
   `:project`/`:ws` when both are given. Exits 1 when no workstream resolves."
  [{:keys [cwd project ws]}]
  (let [given   (or cwd (System/getProperty "user.dir"))
        here    (or (lifecycle/worktree-from-cwd given) given)
        [p id]  (if (and project ws)
                  [(keyword (name project)) (str ws)]
                  (stages/project+ws-from-cwd here))
        session (:session (lifecycle/session-from-cwd here))]
    (if-not id
      (do (println (str given " resolves to no workstream — pass :project <p> :ws <ws-id>"))
          (System/exit 1))
      (let [{:keys [was?]} (try (work/deactivate! p id session)
                                (catch clojure.lang.ExceptionInfo e
                                  (println (str "Refused — " (ex-message e)))
                                  (System/exit 1)))]
        (println (if was?
                   (str "Deactivated nido's recipe on " id
                        ": no conductor, nothing owed, and its designs no longer gate its landing."
                        " `nido activate` turns it back on.")
                   (str id " was not running nido's recipe — nothing changed.")))))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  deactivate-cmd [& args]
  (let [[_ opts] (task-args/split-args args)]
    (deactivate-cmd* opts)))
