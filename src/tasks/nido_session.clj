(ns tasks.nido-session
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [nido.session.engine :as engine]))

(defn- parse-opts
  "Parse EDN key/value CLI args, e.g. :project-dir \"/path\" :app-port 3901."
  [args]
  (if (empty? args)
    {}
    (try
      (let [parse-arg
            (fn [arg]
              (try
                (edn/read-string arg)
                (catch Exception _
                  arg)))
            values (map parse-arg args)]
        (when (odd? (count values))
          (throw (ex-info "Options must be key/value pairs" {:args args})))
        (apply hash-map values))
      (catch Exception e
        (throw (ex-info "Failed to parse task options"
                        {:args args
                         :error (ex-message e)}))))))

(defn- resolve-project-dir [opts]
  (let [raw (or (:project-dir opts) (:dir opts))]
    (when-not raw
      (throw (ex-info "Missing :project-dir"
                      {:hint "Pass :project-dir \"/abs/or/relative/path\"."})))
    (let [project-dir (-> raw fs/path fs/absolutize fs/normalize str)]
      (when-not (fs/exists? project-dir)
        (throw (ex-info "Project directory does not exist" {:project-dir project-dir})))
      project-dir)))

(defn start
  "Start an isolated session for a project worktree.

   Required args:
     :project-dir \"/path/to/worktree\"

   Optional args:
     :shared-pg? true"
  [& args]
  (let [opts (parse-opts args)
        project-dir (resolve-project-dir opts)]
    (engine/start-session! project-dir opts)))

(defn status
  "Show status for a project session.

   Usage:
     bb nido:session:status :project-dir \"/path/to/worktree\""
  [& args]
  (let [opts (parse-opts args)
        project-dir (resolve-project-dir opts)]
    (engine/session-status project-dir)))

(defn stop
  "Stop a project session.

   Usage:
     bb nido:session:stop :project-dir \"/path/to/worktree\""
  [& args]
  (let [opts (parse-opts args)
        project-dir (resolve-project-dir opts)]
    (engine/stop-session! project-dir)))

(defn list-sessions
  "List all sessions tracked by the nido registry."
  [& _]
  (engine/list-sessions))
