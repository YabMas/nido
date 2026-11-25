(ns nido.test.units
  "Opening a core unit on a workstream of the in-process core the suite runs against, so a test
   can give a workstream the sessions `nido.coordinator.record.workstream/sessions` reads."
  (:require [babashka.fs :as fs]
            [nido.core.client :as client]
            [nido.coordinator.record.workstream :as cws]))

(defn ^{:malli/schema [:=> [:cat :keyword :string :string [:? :map]] :any]} open-unit!
  "Open unit `unit` on workstream `ws-id` of `project`. Given `:session` (a name), its session is
   ready, at a worktree of that name; otherwise the core owes it one. Throws on a refusal."
  ([project ws-id unit] (open-unit! project ws-id unit {}))
  ([project ws-id unit {:keys [session]}]
   (let [o (client/command! (client/ws-key project ws-id) (str "open-" ws-id "-" unit)
                            (cond-> {:command :open-unit :unit unit :project (name project) :goal "g"}
                              session (assoc :session-cwd (str (fs/path "/worktrees" session)))))]
     (#'cws/forget! project ws-id)
     (when (:refused o) (throw (ex-info "open-unit refused" {:outcome o})))
     o)))
