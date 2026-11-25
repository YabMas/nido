(ns nido.test.units
  "Opening a core unit on a workstream of the in-process core the suite runs against, so a test
   can give a workstream the sessions and units `nido.coordinator.record.workstream/sessions` and
   `/units` read."
  (:require [babashka.fs :as fs]
            [nido.core.client :as client]
            [nido.coordinator.record.workstream :as cws]))

(defn ^{:malli/schema [:=> [:cat :keyword :string :string [:? :map]] :any]} open-unit!
  "Open unit `unit` on workstream `ws-id` of `project`. Given `:session` (a name), its session is
   ready, at a worktree of that name; otherwise the core owes it one. Given `:trigger`, a fire of
   that trigger opened it. Throws on a refusal."
  ([project ws-id unit] (open-unit! project ws-id unit {}))
  ([project ws-id unit {:keys [session trigger]}]
   (let [o (client/command! (client/ws-key project ws-id) (str "open-" ws-id "-" unit)
                            (cond-> {:command :open-unit :unit unit :project (name project) :goal "g"}
                              session (assoc :session-cwd (str (fs/path "/worktrees" session)))
                              trigger (assoc :trigger (name trigger))))]
     (#'cws/forget! project ws-id)
     (when (:refused o) (throw (ex-info "open-unit refused" {:outcome o})))
     o)))

(defn ^{:malli/schema [:=> [:cat :keyword :string :keyword] :string]} fire!
  "Open a unit on workstream `ws-id` of `project` as a fire of `trigger` does. Answers its id."
  [project ws-id trigger]
  (let [unit (str (name trigger) "-" (subs (str (random-uuid)) 0 8))]
    (open-unit! project ws-id unit {:trigger trigger})
    unit))

(defn ^{:malli/schema [:=> [:cat :keyword :string :string] :any]} retract!
  "Retract unit `unit` of workstream `ws-id`, which had no turn: it is open no more."
  [project ws-id unit]
  (let [o (client/command! (client/ws-key project ws-id) (str "retract-" ws-id "-" unit)
                           {:command :retract-unit-goal :unit unit})]
    (#'cws/forget! project ws-id)
    (when (:refused o) (throw (ex-info "retract refused" {:outcome o})))
    o))
