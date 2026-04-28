(ns tasks.nido-workspace
  "Bb task entry points for the per-project workspace PG cluster.

   The workspace cluster is the shared database every :pg-mode :shared
   session attaches to. It is owned by the project, not by any one
   session — its lifecycle is decoupled from session lifecycle.

   Examples:
     bb nido:workspace:pg:start   :project \"brian\"
     bb nido:workspace:pg:stop    :project \"brian\"
     bb nido:workspace:pg:status  :project \"brian\"
     bb nido:workspace:pg:refresh :project \"brian\""
  (:require
   [clojure.edn :as edn]
   [nido.workspace :as workspace]))

(defn- parse-opts [args]
  (if (empty? args)
    {}
    (let [parse-arg (fn [arg]
                      (try (edn/read-string arg) (catch Exception _ arg)))
          values (map parse-arg args)]
      (when (odd? (count values))
        (throw (ex-info "Options must be key/value pairs" {:args args})))
      (apply hash-map values))))

(defn- require-project [opts]
  (or (some-> (:project opts) name)
      (throw (ex-info "Missing :project <name>"
                      {:hint "Pass :project \"<project-name>\"."}))))

(defn start [& args]
  (workspace/start! (require-project (parse-opts args))))

(defn stop [& args]
  (workspace/stop! (require-project (parse-opts args))))

(defn status [& args]
  (workspace/status (require-project (parse-opts args))))

(defn refresh [& args]
  (workspace/refresh! (require-project (parse-opts args))))

(defn reclaim
  "Delete per-instance state dirs not referenced by any registry entry.
   Default is list-only; pass :force? true to actually delete.
   Also accepts :force (zsh users: quote it as ':force?')."
  [& args]
  (let [opts (parse-opts args)]
    (workspace/reclaim! :force? (boolean (or (:force? opts) (:force opts))))))
