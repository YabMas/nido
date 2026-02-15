(ns tasks.nido-project
  (:require [clojure.edn :as edn]
            [nido.core :as core]
            [nido.config :as config]
            [nido.project :as project]
            [nido.definitions :as defs]
            [nido.merge :as merge]
            [nido.projector.claude-code :as claude-code]
            [nido.projector.codex :as codex]
            [babashka.fs :as fs]))

(defn- parse-opts
  "Parse CLI args as EDN key/value pairs."
  [args]
  (if (empty? args)
    {}
    (let [parse-arg (fn [arg]
                      (try (edn/read-string arg)
                           (catch Exception _ arg)))
          values (map parse-arg args)]
      (when (odd? (count values))
        (throw (ex-info "Options must be key/value pairs" {:args args})))
      (apply hash-map values))))

(defn init
  "Create the ~/.nido/ skeleton directory structure."
  [& _args]
  (core/ensure-nido-home!))

(defn add
  "Register a project: <name> <directory> [:providers [:claude-code :codex]]"
  [& args]
  (when (< (count args) 2)
    (throw (ex-info "Usage: nido:project:add <name> <directory> [:providers [:claude-code]]"
                    {:args args})))
  (let [name (first args)
        directory (second args)
        opts (parse-opts (drop 2 args))
        providers (:providers opts)]
    (project/add! name directory :providers providers)))

(defn list-cmd
  "List registered projects."
  [& _args]
  (let [projects (project/list-projects)]
    (if (seq projects)
      (doseq [[name entry] (sort-by key projects)]
        (println (str "  " name))
        (println (str "    directory: " (:directory entry)))
        (println (str "    providers: " (pr-str (:providers entry)))))
      (println "No projects registered."))))

(defn remove-cmd
  "Unregister a project: <name>"
  [& args]
  (when (empty? args)
    (throw (ex-info "Usage: nido:project:remove <name>" {:args args})))
  (project/remove! (first args)))

(defn- projector-for [provider]
  (case provider
    :claude-code claude-code/project!
    :codex codex/project!
    (throw (ex-info (str "Unknown provider: " provider) {:provider provider}))))

(defn sync
  "Generate workspace configs for a project: <name> [:provider :claude-code]"
  [& args]
  (when (empty? args)
    (throw (ex-info "Usage: nido:project:sync <name> [:provider :claude-code]" {:args args})))
  (let [name (first args)
        opts (parse-opts (drop 1 args))
        entry (project/get-project name)]
    (when-not entry
      (throw (ex-info (str "Project '" name "' not found. Run nido:project:add first.")
                      {:name name})))
    (let [home (core/nido-home)
          global-defs-dir (str (fs/path home "definitions"))
          project-defs-dir (str (fs/path home "projects" name))
          global-defs (defs/load-definitions global-defs-dir)
          project-defs (defs/load-definitions project-defs-dir)
          effective (merge/merge-definitions global-defs project-defs)
          target-dir (:directory entry)
          providers (if-let [p (:provider opts)]
                      [(keyword p)]
                      (:providers entry))]
      (doseq [provider providers]
        (let [ws-dir (core/workspace-dir name provider)
              project-fn (projector-for provider)]
          (core/log-step (str "Syncing " (clojure.core/name provider) " workspace for " name))
          (project-fn ws-dir target-dir effective)
          (core/log-step (str "Workspace ready at " ws-dir)))))))
