(ns nido.commands
  "Project-declared commands: a keyword-addressable layer over shell invocations
   so nido can call project-specific tasks (dump DB, restore DB, etc.) without
   knowing their shell form. Commands are declared under :project-commands in
   a project's session.edn and resolved by keyword reference."
  (:require
   [babashka.process :refer [shell]]
   [nido.core :as core]
   [nido.session.context :as ctx]))

(defn resolve-command
  "Look up a command definition by ref in the commands map."
  [commands-map ref]
  (or (get commands-map ref)
      (throw (ex-info (str "Unknown project-command: " ref)
                      {:ref ref
                       :available (vec (keys commands-map))}))))

(defn run-command!
  "Run a named project-command.

   commands-map: the project's :project-commands map.
   ref:          keyword reference into that map.
   context:      template substitution context.
   opts:         {:continue? bool — default false (throw on non-zero exit)
                  :out       :inherit|:string (default :inherit)
                  :err       :inherit|:string (default :inherit)}

   The command def looks like:
     {:cmd \"bb db:dump staging\"   ; shell command, may contain {{refs}}
      :cwd \"{{project.dir}}\"      ; optional, substituted against context
      :env {\"KEY\" \"{{val}}\"}}   ; optional env overrides"
  ([commands-map ref context]
   (run-command! commands-map ref context {}))
  ([commands-map ref context opts]
   (let [{:keys [continue? out err]
          :or {continue? false out :inherit err :inherit}} opts
         cmd-def (resolve-command commands-map ref)
         resolved (ctx/substitute context cmd-def)
         {:keys [cmd cwd env]} resolved]
     (when-not cmd
       (throw (ex-info "Command has no :cmd" {:ref ref :def cmd-def})))
     (core/log-step (str "Running " ref
                         (when cwd (str " (cwd=" cwd ")"))
                         ": " cmd))
     (let [shell-opts (cond-> {:continue continue? :out out :err err}
                        cwd (assoc :dir cwd)
                        (seq env) (assoc :extra-env env))
           result (shell shell-opts "bash" "-lc" cmd)]
       (when (and (not continue?) (not (zero? (:exit result))))
         (throw (ex-info (str "Project command failed: " ref)
                         {:ref ref
                          :exit (:exit result)
                          :cmd cmd})))
       result))))
