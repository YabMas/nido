(ns tasks.nido-template
  (:require
   [clojure.edn :as edn]
   [nido.template :as template]))

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

(defn init
  "Initialize a fresh template cluster for a project.

   Usage:
     bb nido:template:pg:init :project \"brian\" [:force true]
   Also accepts :force? (zsh users: quote it as ':force?')."
  [& args]
  (let [opts (parse-opts args)
        project-name (require-project opts)
        force? (boolean (or (:force? opts) (:force opts)))]
    (template/init! project-name :force? force?)))

(defn refresh
  "Refresh the template cluster by running the project's declared
   :refresh-steps against a live copy of the template.

   Usage:
     bb nido:template:pg:refresh :project \"brian-next\""
  [& args]
  (let [opts (parse-opts args)
        project-name (require-project opts)]
    (template/refresh! project-name)))

(defn status
  "Show template status for a project.

   Usage:
     bb nido:template:pg:status :project \"brian-next\""
  [& args]
  (let [opts (parse-opts args)
        project-name (require-project opts)]
    (template/status project-name)))

(defn destroy
  "Delete the template cluster entirely.

   Usage:
     bb nido:template:pg:destroy :project \"brian-next\""
  [& args]
  (let [opts (parse-opts args)
        project-name (require-project opts)]
    (template/destroy! project-name)))
