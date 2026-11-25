;; src/tasks/nido_activate.clj
(ns tasks.nido-activate
  "bb-task entrypoint: hand a workstream to the core."
  (:require
   [nido.coordinator.work :as work]
   [nido.platform.task-args :as task-args]
   [nido.review.stages :as stages]
   [nido.session.lifecycle :as lifecycle]))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  activate-cmd*
  "Hand the workstream `:cwd` (default: the process's) resolves to, or `:project`/`:ws` when both
   are given, to the core: its unit opened, in a session the core provisions — never the session
   this runs in. Exits 1 when no workstream resolves or the core refuses."
  [{:keys [cwd project ws]}]
  (let [given   (or cwd (System/getProperty "user.dir"))
        here    (or (lifecycle/worktree-from-cwd given) given)
        [p id]  (if (and project ws)
                  [(keyword (name project)) (str ws)]
                  (stages/project+ws-from-cwd here))]
    (if-not id
      (do (println (str given " resolves to no workstream — pass :project <p> :ws <ws-id>"))
          (System/exit 1))
      (let [{:keys [already?]}
            (try (work/activate! p id)
                 (catch clojure.lang.ExceptionInfo e
                   (println (str "Refused — " (ex-message e)))
                   (System/exit 1)))]
        (println (if already?
                   (str id " was already activated.")
                   (str "Activated " id ": the core holds its unit and provisions its session; "
                        "its conductor decides what it is for once that session is ready.")))))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  activate-cmd [& args]
  (let [[_ opts] (task-args/split-args args)]
    (activate-cmd* opts)))
