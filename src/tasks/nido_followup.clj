(ns tasks.nido-followup
  "Bb task entry points for the personal follow-up DB — the horizontal
   destination of the shipping doctrine (see the `/spin-out` skill).

   Filing has to be nearly free or it won't happen, so `:origin` and `:project`
   auto-resolve from cwd (worktree or session home) when not passed.

   Surface:
     add    file a spin-out
     list   open follow-ups, worst-decay first
     check  validate the configured property names against the live DB

   Examples:
     bb nido:followup:add :title \"drop the compat shim in work.clj\" \\
       :kind cleanup :reason \"revealed, not caused; needs the callers to converge\" \\
       :decay cheaper-later :cold-start cheap :effort S
     bb nido:followup:add :title \"...\" :kind bug-found-not-caused :reason \"...\" \\
       :decay compounding :cold-start needs-context :origin https://github.com/o/r/pull/12
     bb nido:followup:list
     bb nido:followup:check

   Cwd resolution lives here rather than in nido.notion.followups: the notion
   namespace stays a pure config+API layer, and the session lookup is wiring —
   the same reason nido.coordinator.lane.scratch keeps its wiring at the task layer."
  (:require
   [clojure.string :as str]
   [nido.coordinator.record.state :as cstate]
   [nido.platform.io :as io]
   [nido.notion.followups :as followups]
   [nido.notion.client :as client]
   [nido.session.lifecycle :as lifecycle]
   [nido.platform.task-args :as task-args]))

(defn- cfg!
  "The validated :followups config. This layer is the one that legitimately
   knows where nido keeps its configuration."
  []
  (followups/config! (io/read-edn (cstate/config-path)) (cstate/config-path)))

(def ^:private raw-string-keys
  "Values that must survive verbatim: EDN-parsing a URL with a `/digits`
   suffix or a multi-word sentence loses information (read-string consumes
   only the first form). Same set the link tasks guard."
  #{:title :reason :origin :description :url})

(defn- coords-from-cwd
  "[project session] for the cwd, or [nil nil] when it belongs to no session.
   Accepts both a worktree cwd and a session-home cwd."
  []
  (let [from-wt (lifecycle/session-from-cwd)]
    (if (:project from-wt)
      [(:project from-wt) (:session from-wt)]
      (or (lifecycle/session-home-coords-from-cwd) [nil nil]))))

(defn- with-derived-origin
  "Fill :origin and :project from cwd when the caller didn't pass them. An
   origin is what makes a cold reader able to reconstruct the decision, so it
   is never left blank when it can be derived."
  [opts]
  (let [[project session] (coords-from-cwd)]
    (cond-> opts
      (str/blank? (str (:origin opts)))
      (assoc :origin (if session
                       (str project "/" session)
                       "(filed outside a session)"))

      (and project (str/blank? (str (:project opts))))
      (assoc :project project))))

(defn- guarded
  "Run `f`, turning a setup ex-info (unconfigured DB, missing token, unmapped
   property) into its message + hint and exit 1. Without this the first thing
   anyone runs — before the DB exists — is a stack trace with the hint buried
   in :data."
  [f]
  (try (f)
       (catch clojure.lang.ExceptionInfo e
         (println (ex-message e))
         (when-let [hint (:hint (ex-data e))] (println hint))
         (System/exit 1))))

(defn- entry-of [opts]
  (-> opts
      (select-keys (into [:description] (keys followups/field-types)))
      (update-vals #(if (keyword? %) (name %) %))
      with-derived-origin))

(defn- add!
  [args]
  (let [[_ opts] (task-args/split-args args raw-string-keys)
        entry    (entry-of opts)
        res      (followups/create! (cfg!) entry)]
    (cond
      (= :invalid (:error res))
      (do (println "not filed — the entry is incomplete:")
          (doseq [p (:problems res)] (println "  -" p))
          (System/exit 1))

      (:error res)
      (do (println "not filed — Notion error:" (name (:error res))
                   (or (:status res) ""))
          (System/exit 1))

      :else
      (println "filed" (:id res) "·" (:url res)))))

(defn- list! [args]
  (let [[_ opts] (task-args/split-args args raw-string-keys)
        res      (followups/list-entries (cfg!) (or (some-> (:status opts) name) "Open"))]
    (if (:error res)
      (do (println "could not read the follow-up DB:" (name (:error res)))
          (System/exit 1))
      (if (empty? res)
        (println "no follow-ups in that band.")
        (do (doseq [{:keys [id title decay cold-start effort project]} res]
              (println (format "%-8s %-13s %-14s %-9s %-8s %s"
                               (or id "?") (or decay "-") (or cold-start "-")
                               (or effort "-") (or project "-") title)))
            (println)
            (println (count res) "open · ordered by decay pressure"))))))

(defn- check! []
  (let [token (client/keychain-token)]
    (when-not token
      (println "No Notion token in keychain. Run bb nido:notion:auth:set.")
      (System/exit 1))
    (let [res (followups/check-config (cfg!) token)]
      (if (= :ok (:status res))
        (println "follow-up DB config matches the live schema.")
        (do (println "follow-up DB config drift:")
            (doseq [e (:errors res)] (println "  -" (:message e)))
            (System/exit 1))))))

;; ---------------------------------------------------------------------------
;; Task entry points
;; ---------------------------------------------------------------------------

(defn add
  "File a spin-out in the follow-up DB and print its ref."
  [& args]
  (guarded #(add! args)))

(defn list-cmd
  "Print open follow-ups, worst-decay first. `:status <s>` reads another band."
  [& args]
  (guarded #(list! args)))

(defn check-cmd
  "Validate the configured property names + vocabularies against the live DB."
  [& _args]
  (guarded check!))
