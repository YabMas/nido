;; src/tasks/nido_activate.clj
(ns tasks.nido-activate
  "bb-task entrypoint: activate nido's recipe on the workstream a person is working in."
  (:require
   [nido.coordinator.work :as work]
   [nido.platform.task-args :as task-args]
   [nido.review.stages :as stages]
   [nido.session.lifecycle :as lifecycle]))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  activate-cmd*
  "Activate the recipe on the workstream `:cwd` (default: the process's) resolves to, or on
   `:project`/`:ws` when both are given, and print the instruction for the agent of the session
   it was run from. Exits 1 when no workstream resolves or it is closed."
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
      (let [{:keys [already? instruction]}
            (try (work/activate! p id session)
                 (catch clojure.lang.ExceptionInfo e
                   (println (str "Refused — " (ex-message e)))
                   (System/exit 1)))]
        (println (if already?
                   (str id " was already activated.")
                   (str "Activated nido's recipe on " id
                        ". Its conductor starts on the next conduct pass.")))
        (println)
        (println instruction)))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  activate-cmd [& args]
  (let [[_ opts] (task-args/split-args args)]
    (activate-cmd* opts)))
