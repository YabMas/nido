(ns nido.test.runs
  "Starting a run of a trigger in the in-process core the suite runs against, and taking it as far
   as a test needs, so a reader of runs (`nido.core.client/runs`) finds it."
  (:require [nido.core.client :as client]))

(defn- send! [project id k command]
  (let [o (client/command! (client/run-key project id) (str k "/" id) command)]
    (when (:refused o) (throw (ex-info "run command refused" {:command command :outcome o})))
    o))

(defn ^{:malli/schema [:=> [:cat :keyword :keyword :map :keyword [:? :string]] :string]} run!
  "Start a run of `trigger` on `project` with `payload`, and take it to `status` — :starting,
   :running, :done, :failed or :cancelled. Answers its id; `id` names it, else it is made up."
  ([project trigger payload status] (run! project trigger payload status nil))
  ([project trigger payload status id]
   (let [id   (or id (str (name trigger) "-" (subs (str (random-uuid)) 0 8)))
         spec {:executor :claude :instructions (str "/" (name trigger)) :ask? false}]
     (send! project id "start" {:command :start-run :spec spec
                                :run {:id id :project (name project) :trigger (name trigger)
                                      :goal (name trigger) :payload payload
                                      :fired-at (str (java.time.Instant/now))}})
     (when-not (= :starting status)
       (send! project id "attempt" {:command :record-provision-attempt :unit id :want :up :attempt "a"})
       (send! project id "up" {:command :record-provision-outcome :unit id :attempt "a" :outcome :done
                               :cwd (str "/worktrees/" id)})
       (send! project id "turn" {:command :start-turn :turn (str id " first") :unit id :spec spec})
       (when-let [outcome ({:done :completed :failed :failed :cancelled :cancelled} status)]
         (send! project id "end" {:command :record-outcome :turn (str id " first") :outcome outcome})))
     id)))
