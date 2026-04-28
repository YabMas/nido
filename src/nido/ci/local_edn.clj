(ns nido.ci.local-edn
  "Render the project's `:services :config-file :local-edn :template`
   into a step's work-dir, with values for `{{pg.*}}` / `{{app.*}}`
   drawn from the step's isolation allocations.

   Used by any step that opts into `:isolated-pg?` / `:isolated-app?`
   — the isolated PG/app run on freshly allocated ports, and the
   cloned worktree's local.edn must point the spawned project app at
   them. The rendered values are *deep-merged* over whatever local.edn
   already exists, so a developer's main-tree settings (e.g. brian's
   `:db/restore` block) survive even when nido needs to overwrite the
   port-bearing keys.

   Falls back to a no-op when the project's session.edn doesn't declare
   a `:type :config-file` service — that's a project decision, not
   something nido should backfill."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.pprint :as pprint]
   [nido.session.context :as ctx]))

(defn- deep-merge
  [a b]
  (cond
    (and (map? a) (map? b)) (merge-with deep-merge a b)
    :else                   (if (some? b) b a)))

(defn- find-config-file-service
  "First :type :config-file entry from session.edn :services, or nil."
  [session-edn]
  (->> (:services session-edn)
       (filter #(= :config-file (:type %)))
       first))

(defn- find-postgresql-service
  [session-edn]
  (->> (:services session-edn)
       (filter #(= :postgresql (:type %)))
       first))

(defn- pg-context
  "Build the {{pg.*}} bindings for template substitution. Pulls
   defaults from session.edn `:services :postgresql :isolated-config`
   (then `:shared-config`), overlaying the step's allocated port."
  [session-edn isolation]
  (let [pg-svc  (find-postgresql-service session-edn)
        iso-cfg (or (:isolated-config pg-svc) {})
        shared  (or (:shared-config pg-svc) {})]
    {:port (or (:pg-port isolation)
               (:port shared))
     :db-name (or (:db-name iso-cfg) (:db-name shared) "postgres")
     :db-user (or (:db-user iso-cfg) (:db-user shared) "user")
     :db-password (or (:db-password iso-cfg) (:db-password shared) "password")
     ;; Always false for CI step-isolated mode — the --ci template
     ;; ships with every migration applied; running flyway again on
     ;; each test JVM mount would just re-baseline the same schema.
     :flyway-migrate? false}))

(defn render!
  "Render the project's templated local.edn into `work-dir`, with
   `{{pg.port}}` / `{{app.port}}` etc. bound to the step's allocated
   isolation values, *deep-merged* over any existing local.edn at
   that path. Returns the path written, or nil if the project declares
   no config-file template."
  [{:keys [session-edn work-dir isolation]}]
  (when-let [config-file (find-config-file-service session-edn)]
    (when-let [template (:template config-file)]
      (let [path     (or (:path config-file) "local.edn")
            context  {:pg  (pg-context session-edn isolation)
                      :app {:port (:app-port isolation)}}
            rendered (ctx/substitute context template)
            file     (str (fs/path work-dir path))
            existing (when (fs/exists? file)
                       (try (edn/read-string (slurp file)) (catch Exception _ nil)))
            merged   (if (map? existing)
                       (deep-merge existing rendered)
                       rendered)]
        (spit file (with-out-str (pprint/pprint merged)))
        file))))
