;; src/tasks/nido_drain.clj
(ns tasks.nido-drain
  "bb-task entrypoints: a drain's verbs — the person's (start, approve, land, close), the
   coordinator's levers (propose, plan, withdraw, clear, dispose, answer), a slice's misfit, and the
   census. Who may run each is lane-drain's and the commit gate's to refuse."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [nido.coordinator.lane.drain :as drain-lane]
   [nido.coordinator.work :as work]
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

;; ── the drain's other verbs ─────────────────────────────────────────────────

(defn- edn-arg [v] (if (string? v) (edn/read-string v) v))
(defn- int-arg [v] (if (string? v) (parse-long (str/replace v #"^:" "")) v))
(defn- kw-arg [v] (when v (keyword (str/replace (str v) #"^:" ""))))

(defn- refusing
  "Run `f`, printing what it answers; a refusal is printed and exits 1."
  [f]
  (try (prn (f))
       (catch clojure.lang.ExceptionInfo e
         (println (str "Refused — " (ex-message e)))
         (System/exit 1))))

(defn- drain-target [opts]
  (let [[p id] (target opts)]
    (when-not id
      (println "No drain resolves here — pass :project <p> :drain <ws-id>")
      (System/exit 1))
    [p id]))

(defn start-cmd
  "The person starting a drain: `:project <p> :exit \"…\"` and `:established-by-ws <ws-id>` or
   `:established-by-commit <rev>` — what established the pattern it rolls out."
  [& args]
  (let [[_ {:keys [project exit established-by-ws established-by-commit]}] (task-args/split-args args)]
    (when-not (and project exit (or established-by-ws established-by-commit))
      (println "Pass :project <p> :exit \"…\" and :established-by-ws <ws-id> or :established-by-commit <rev>")
      (System/exit 1))
    (refusing #(drain-lane/start-drain! (keyword (name project))
                                       {:exit (str exit)
                                        :established-by (if established-by-ws
                                                          {:ws-id (str established-by-ws)}
                                                          {:commit (str established-by-commit)})}))))

(defn approve-cmd
  "The person approving recipe proposal `:proposal <seq>`."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        [p id]   (drain-target opts)]
    (refusing #(drain-lane/approve! p id (int-arg (:proposal opts))))))

(defn land-cmd
  "The person landing slice `:slice <ws-id>` of the drain."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        [p _]    (drain-target opts)]
    (refusing #(work/drain-land! p (str (:slice opts))))))

(defn close-cmd
  "The person closing the drain on its observed exit."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        [p id]   (drain-target opts)]
    (refusing #(drain-lane/close! p id))))

(defn propose-cmd
  "The coordinator proposing a recipe: `:reason \"…\" :recipe '{…}'`."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        [p id]   (drain-target opts)]
    (refusing #(drain-lane/propose! p id {:reason (str (:reason opts)) :recipe (edn-arg (:recipe opts))}))))

(defn plan-cmd
  "The coordinator planning a slice: `:sites '[…]' :census <seq>` and optionally `:rests-on '[…]'
   :start on-top|after-landing :replaces <seq> :note \"…\"`."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        [p id]   (drain-target opts)]
    (refusing #(drain-lane/plan! p id (cond-> {:sites (vec (edn-arg (:sites opts)))
                                               :rests-on (vec (or (edn-arg (:rests-on opts)) []))
                                               :census (int-arg (:census opts))}
                                        (:start opts)    (assoc :start (kw-arg (:start opts)))
                                        (:replaces opts) (assoc :replaces (int-arg (:replaces opts)))
                                        (:note opts)     (assoc :note (str (:note opts))))))))

(defn withdraw-cmd
  "The coordinator withdrawing open plan `:plan <seq>` with `:why \"…\"`."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        [p id]   (drain-target opts)]
    (refusing #(drain-lane/withdraw! p id (int-arg (:plan opts)) (str (:why opts))))))

(defn clear-cmd
  "The coordinator clearing `:slice <ws-id>` with `:residue '[{:site … :disposition …} …]'`."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        [p id]   (drain-target opts)]
    (refusing #(drain-lane/clear! p id (str (:slice opts)) (vec (or (edn-arg (:residue opts)) []))))))

(defn dispose-cmd
  "The coordinator disposing of residue item `:residue <seq>` again with `:disposition '{…}'`."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        [p id]   (drain-target opts)]
    (refusing #(drain-lane/dispose! p id (int-arg (:residue opts)) (edn-arg (:disposition opts))))))

(defn answer-cmd
  "The coordinator answering open misfit `:misfit <seq>` with `:answer '{:as …}'`."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        [p id]   (drain-target opts)]
    (refusing #(drain-lane/answer-misfit! p id (int-arg (:misfit opts)) (edn-arg (:answer opts))))))

(defn misfit-cmd
  "A slice's session recording `:site \"…\" :note \"…\"` its recipe version does not cover."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        given    (or (:cwd opts) (System/getProperty "user.dir"))
        [p _]    (stages/project+ws-from-cwd (or (lifecycle/worktree-from-cwd given) given))]
    (refusing #(drain-lane/misfit! (or p (keyword (name (:project opts)))) {:site (str (:site opts)) :note (str (:note opts))}))))
