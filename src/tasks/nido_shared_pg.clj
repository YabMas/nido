(ns tasks.nido-shared-pg
  (:require
   [clojure.edn :as edn]
   [nido.session.engine :as engine]
   [nido.session.lifecycle :as lifecycle]
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

(defn- pg-service-def [project-name]
  (->> (:services (engine/load-session-edn project-name))
       (filter #(= :postgresql (:type %)))
       first))

(defn project-source-dir
  "The project's registered source checkout (its jj root)."
  [project-name]
  (:directory (second (lifecycle/resolve-project {:project project-name}))))

(defn- ready-opts [project-name]
  (let [{:keys [db-name db-user schema app-db-user app-db-password]} (pg-service-def project-name)]
    {:db-name db-name :owner-user (or db-user "user") :schema schema
     :app-user app-db-user :app-password app-db-password
     :source-repo (project-source-dir project-name)}))

(defn up
  "Ensure the shared Postgres cluster for a project is up (seed+start), advance
   it to main@origin, and ensure the DDL-less app role. Idempotent.

   Usage:
     bb nido:shared:pg:up :project \"brian\""
  [& args]
  (let [opts (parse-opts args)
        project-name (require-project opts)]
    (prn (shared/ensure-ready! project-name (ready-opts project-name)))))

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
  "Stop, drop PGDATA, re-clone from template, start — recover from a bad
   migration — then advance to main@origin and ensure the DDL-less app role.

   Usage:
     bb nido:shared:pg:reset :project \"brian\""
  [& args]
  (let [opts (parse-opts args)
        project-name (require-project opts)]
    (shared/reset! project-name)
    (prn (shared/ensure-ready! project-name (ready-opts project-name)))))

(defn destroy
  "Delete the shared cluster for a project.

   Usage:
     bb nido:shared:pg:destroy :project \"brian\""
  [& args]
  (let [opts (parse-opts args)
        project-name (require-project opts)]
    (shared/destroy! project-name)))
