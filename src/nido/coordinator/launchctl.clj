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
