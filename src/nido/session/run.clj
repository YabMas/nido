(ns nido.session.run
  "Run a project-declared :project-commands entry inside a session's worktree.
   The generic 'adopt a target-project command' primitive: nido resolves where
   to run (the session worktree) and forwards the command via nido.session.commands;
   the command's behaviour and output belong to the target project."
  (:require
   [babashka.fs :as fs]
   [nido.session.commands :as commands]
   [nido.platform.config :as config]
   [nido.session.engine :as engine]
   [nido.session.lifecycle :as lifecycle]))

(defn session-context
  "Substitution context for a session-scoped project-command. Adds the
   :session layer (so commands can template {{session.worktree}}) on top of
   the :project layer the existing template steps already use."
  [project-name project-dir session-name worktree]
  {:project {:name project-name :dir project-dir}
   :session {:name session-name :worktree worktree}})

(defn- resolve-project-dir
  [project-name]
  (or (get-in (config/read-projects) [project-name :directory])
      (throw (ex-info (str "Project not registered: " project-name)
                      {:hint "Run `bb nido:project:add <name> <directory>` first."
                       :project-name project-name}))))

(defn run-command-in-session!
  "Resolve the session worktree and run the named :project-commands entry there
   with live (inherited) IO. Returns the babashka.process result map (:exit ...).
   Throws if the project is unregistered, the worktree is missing, or the ref
   is not a declared command."
  [project-name session-name ref]
  (let [project-dir (resolve-project-dir project-name)
        worktree    (lifecycle/worktree-path project-name project-dir session-name)]
    (when-not (fs/exists? worktree)
      (throw (ex-info (str "Worktree not found for " project-name "/" session-name)
                      {:worktree worktree
                       :hint "Bring the session up first: bb nido:session:up :project <p> <session>"})))
    (let [session-edn (engine/load-session-edn project-name)
          context     (session-context project-name project-dir session-name worktree)]
      (commands/run-command! (:project-commands session-edn) ref context
                             {:continue? true :out :inherit :err :inherit}))))
