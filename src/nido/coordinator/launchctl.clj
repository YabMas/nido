(ns nido.coordinator.launchctl
  "macOS LaunchAgent plist for the nido coordinator. Pure rendering plus
   thin shell wrappers around launchctl. No nio2 watchers, no state."
  (:require
   [babashka.fs :as fs]))

(defn launch-agents-dir
  "~/Library/LaunchAgents. Wrapped so tests can redirect to a tempdir."
  []
  (str (fs/path (System/getProperty "user.home") "Library" "LaunchAgents")))

(defn label
  "LaunchAgent service label for the coordinator (used in plist + launchctl targets)."
  []
  "dev.nido.coordinator")

(defn plist-path
  "Absolute path to ~/Library/LaunchAgents/dev.nido.coordinator.plist."
  []
  (str (fs/path (launch-agents-dir) (str (label) ".plist"))))

(defn installed?
  "True iff the LaunchAgent plist exists on disk."
  []
  (fs/exists? (plist-path)))

(defn render-plist
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
