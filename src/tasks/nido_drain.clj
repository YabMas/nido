;; src/tasks/nido_drain.clj
(ns tasks.nido-drain
  "bb-task entrypoints: a drain's verbs."
  (:require
   [nido.coordinator.source.drain :as source-drain]
   [nido.platform.task-args :as task-args]
   [nido.review.stages :as stages]
   [nido.session.lifecycle :as lifecycle]))

(defn- target
  "[project ws-id] of the drain the options name — `:project`/`:drain` when both are given, else
   the workstream `:cwd` (default: the process's) resolves to — or nil."
  [{:keys [cwd project drain]}]
  (if (and project drain)
    [(keyword (name project)) (str drain)]
    (let [given (or cwd (System/getProperty "user.dir"))]
      (stages/project+ws-from-cwd (or (lifecycle/worktree-from-cwd given) given)))))

(defn ^{:malli/schema [:=> [:cat :map] :keyword]}
  census-cmd*
  "Take the drain's census now — the take-census stage — and answer :census-taken for a reading,
   appended or unchanged, or the failure's cause (:counter-failed, :unreadable, :invalid,
   :no-recipe; :no-workstream when nothing resolves)."
  [opts]
  (let [[p id] (target opts)]
    (if-not id
      (do (println "census: no drain resolves here — pass :project <p> :drain <ws-id>") :no-workstream)
      (let [r (source-drain/take-census! p id)]
        (println (str "census: " (pr-str r)))
        (if (:failed r) (:failed r) :census-taken)))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  census-cmd [& args]
  (let [[_ opts] (task-args/split-args args)
        status   (census-cmd* opts)]
    (when-not (= :census-taken status) (System/exit 1))))
