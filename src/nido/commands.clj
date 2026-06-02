(ns nido.commands
  "Project-declared commands: a keyword-addressable layer over shell invocations
   so nido can call project-specific tasks (dump DB, restore DB, etc.) without
   knowing their shell form. Commands are declared under :project-commands in
   a project's session.edn and resolved by keyword reference."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process :refer [shell]]
   [clojure.string :as str]
   [nido.core :as core]
   [nido.session.context :as ctx]))

(defn resolve-command
  "Look up a command definition by ref in the commands map."
  [commands-map ref]
  (or (get commands-map ref)
      (throw (ex-info (str "Unknown project-command: " ref)
                      {:ref ref
                       :available (vec (keys commands-map))}))))

(defn- valid-java-home?
  "A JDK home is usable only if it actually contains bin/java."
  [home]
  (boolean (and home (seq home) (fs/exists? (fs/path home "bin" "java")))))

(defn resolve-java-home
  "Best-effort discovery of a JDK home for subprocesses that shell out to the
   Clojure CLI (e.g. brian's `bb ci`). Project commands run via `bash -lc`,
   and a login shell re-runs macOS' path_helper which reorders PATH so the
   Apple `/usr/bin/java` stub shadows a keg-only Homebrew JDK — leaving the
   Clojure launcher unable to find a runtime. Exporting JAVA_HOME sidesteps
   the PATH dance entirely (the `clojure` script prefers $JAVA_HOME/bin/java).

   Resolution order:
     1. $JAVA_HOME, if already set and valid (respect the caller's choice).
     2. `/usr/libexec/java_home` (works when a JDK is registered with macOS).
     3. Canonicalize `which java` and walk up two levels — recovers keg-only
        Homebrew JDKs that the java_home stub can't see.
   Returns the home path string, or nil if no JDK can be found."
  []
  (let [env-home (System/getenv "JAVA_HOME")]
    (if (valid-java-home? env-home)
      env-home
      (or (let [{:keys [exit out]} (process/sh ["/usr/libexec/java_home"])]
            (when (zero? exit)
              (let [home (str/trim out)]
                (when (valid-java-home? home) home))))
          (let [{:keys [exit out]} (process/sh ["which" "java"])]
            (when (zero? exit)
              ;; canonical path is <home>/bin/java → home is its grandparent
              (let [canonical (str (fs/canonicalize (str/trim out)))
                    home      (str (fs/parent (fs/parent canonical)))]
                (when (valid-java-home? home) home))))))))

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
     ;; Inject JAVA_HOME so subprocesses that shell out to the Clojure CLI find
     ;; a runtime even from a login shell whose PATH has been reordered (see
     ;; resolve-java-home). The command's own :env always wins.
     (let [java-home (when-not (get env "JAVA_HOME") (resolve-java-home))
           env       (cond-> env java-home (assoc "JAVA_HOME" java-home))
           shell-opts (cond-> {:continue continue? :out out :err err}
                        cwd (assoc :dir cwd)
                        (seq env) (assoc :extra-env env))
           result (shell shell-opts "bash" "-lc" cmd)]
       (when (and (not continue?) (not (zero? (:exit result))))
         (throw (ex-info (str "Project command failed: " ref)
                         {:ref ref
                          :exit (:exit result)
                          :cmd cmd})))
       result))))
