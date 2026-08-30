(ns nido.coordinator.daemon.launchctl
  "macOS LaunchAgent plist for the nido coordinator. Pure rendering plus
   thin shell wrappers around launchctl. No nio2 watchers, no state."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]))

(defn ^{:malli/schema [:=> [:cat] :Path]}
  launch-agents-dir
  "~/Library/LaunchAgents. Wrapped so tests can redirect to a tempdir."
  []
  (str (fs/path (System/getProperty "user.home") "Library" "LaunchAgents")))

(defn ^{:malli/schema [:=> [:cat] :string]}
  label
  "LaunchAgent service label for the coordinator (used in plist + launchctl targets)."
  []
  "dev.nido.coordinator")

(defn ^{:malli/schema [:=> [:cat] :Path]}
  plist-path
  "Absolute path to ~/Library/LaunchAgents/dev.nido.coordinator.plist."
  []
  (str (fs/path (launch-agents-dir) (str (label) ".plist"))))

(defn ^{:malli/schema [:=> [:cat] :boolean]}
  installed?
  "True iff the LaunchAgent plist exists on disk."
  []
  (fs/exists? (plist-path)))

(defn ^{:malli/schema [:=> [:cat :string] :any]}
  write-plist!
  "Write the plist contents to ~/Library/LaunchAgents/dev.nido.coordinator.plist.
   Creates the parent directory if missing. Overwrites any existing file."
  [contents]
  (let [p (plist-path)]
    (fs/create-dirs (fs/parent p))
    (spit p contents)))

(defn ^{:malli/schema [:=> [:cat] :any]}
  remove-plist!
  "Delete the plist file if it exists. No-op when absent."
  []
  (when (installed?)
    (fs/delete (plist-path))))

(defn ^{:malli/schema [:=> [:cat] :string]}
  current-uid
  "Resolve the current numeric user id via `id -u`. Read at call time."
  []
  (-> (p/sh ["id" "-u"]) :out str/trim))

(defn ^{:malli/schema [:=> [:cat] :string]}
  target
  "Service target for launchctl subcommands: gui/<uid>/<label>."
  []
  (str "gui/" (current-uid) "/" (label)))

(defn ^{:malli/schema [:=> [:cat :any] :map]}
  sh!
  "Thin wrapper over babashka.process/sh that returns {:exit :out :err}.
   Wrapped so tests can stub launchctl invocations."
  [args]
  (p/sh args))

(defn ^{:malli/schema [:=> [:cat] :boolean]}
  loaded?
  "True iff `launchctl print <target>` reports the service as loaded
   (exit 0). Any non-zero exit is treated as 'not loaded'."
  []
  (zero? (:exit (sh! ["launchctl" "print" (target)]))))

(defn ^{:malli/schema [:=> [:cat] :map]}
  bootstrap!
  "Load the plist into the user's launchd domain. RunAtLoad=true in the plist
   means the daemon also starts now. Returns the sh! result."
  []
  (sh! ["launchctl" "bootstrap" (str "gui/" (current-uid)) (plist-path)]))

(defn ^{:malli/schema [:=> [:cat] :map]}
  bootout!
  "Unload the service (kills the running daemon and stops respawn).
   Returns the sh! result."
  []
  (sh! ["launchctl" "bootout" (target)]))

(defn ^{:malli/schema [:=> [:cat] :map]}
  kickstart!
  "Send SIGTERM to the running daemon and immediately start a fresh instance.
   Used by `bb nido:coordinator:restart`. Returns the sh! result."
  []
  (sh! ["launchctl" "kickstart" "-k" (target)]))

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  render-plist
  "Render the LaunchAgent plist XML for the coordinator.

   Inputs (all absolute paths / values that the plist will contain verbatim):
   - :bb-path   — absolute path to the bb binary
   - :nido-dir  — absolute path to the nido checkout (becomes WorkingDirectory)
   - :log-path  — absolute path for StandardOutPath + StandardErrorPath
   - :path-env  — PATH value injected into the daemon's environment"
  [{:keys [bb-path nido-dir log-path path-env]}]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
       "<plist version=\"1.0\">\n"
       "<dict>\n"
       "    <key>Label</key>\n"
       "    <string>" (label) "</string>\n"
       "    <key>ProgramArguments</key>\n"
       "    <array>\n"
       "        <string>" bb-path "</string>\n"
       "        <string>nido:coordinator:run</string>\n"
       "    </array>\n"
       "    <key>WorkingDirectory</key>\n"
       "    <string>" nido-dir "</string>\n"
       "    <key>RunAtLoad</key>\n"
       "    <true/>\n"
       "    <key>KeepAlive</key>\n"
       "    <true/>\n"
       "    <key>ThrottleInterval</key>\n"
       "    <integer>10</integer>\n"
       "    <key>StandardOutPath</key>\n"
       "    <string>" log-path "</string>\n"
       "    <key>StandardErrorPath</key>\n"
       "    <string>" log-path "</string>\n"
       "    <key>EnvironmentVariables</key>\n"
       "    <dict>\n"
       "        <key>PATH</key>\n"
       "        <string>" path-env "</string>\n"
       "    </dict>\n"
       "</dict>\n"
       "</plist>\n"))
