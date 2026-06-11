(ns tasks.nido-shared-pg
  (:require
   [clojure.edn :as edn]
   [nido.shared-pg :as shared]))

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
                      {:hint "Pass :project \"<project-name>\" — the name used in `bb nido:project:add`."}))))

(defn up
  "Ensure the shared Postgres cluster for a project is up (seed+start). Idempotent.

   Usage:
     bb nido:shared:pg:up :project \"brian\""
  [& args]
  (let [opts (parse-opts args)
        project-name (require-project opts)]
    (prn (shared/ensure-up! project-name))))

(defn status
  "Show shared cluster status for a project.

   Usage:
     bb nido:shared:pg:status :project \"brian\""
  [& args]
  (let [opts (parse-opts args)
        project-name (require-project opts)]
    (shared/status project-name)))

(defn down
  "Stop the shared cluster (preserves data).

   Usage:
     bb nido:shared:pg:down :project \"brian\""
  [& args]
  (let [opts (parse-opts args)
        project-name (require-project opts)]
    (shared/down! project-name)))

(defn reset
  "Stop, drop PGDATA, re-clone from template, start — recover from a bad migration.

   Usage:
     bb nido:shared:pg:reset :project \"brian\""
  [& args]
  (let [opts (parse-opts args)
        project-name (require-project opts)]
    (prn (shared/reset! project-name))))

(defn destroy
  "Delete the shared cluster for a project.

   Usage:
     bb nido:shared:pg:destroy :project \"brian\""
  [& args]
  (let [opts (parse-opts args)
        project-name (require-project opts)]
    (shared/destroy! project-name)))
