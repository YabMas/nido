(ns nido.coordinator.daemon.systemd
  "systemd --user unit for the nido coordinator — the Linux half daemon-supervisor composes, as
   launchctl is the macOS half. Pure rendering plus thin wrappers around `systemctl --user`.

   The unit is enabled under default.target, so with the user's lingering on (provisioned with
   the host) the daemon starts at boot without a login, and Restart=always brings it back after
   it exits — which is how a landing's owed restart is paid."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]))

(defn ^{:malli/schema [:=> [:cat] :Path]}
  unit-dir
  "~/.config/systemd/user. Wrapped so tests can redirect to a tempdir."
  []
  (str (fs/path (System/getProperty "user.home") ".config" "systemd" "user")))

(defn ^{:malli/schema [:=> [:cat] :string]}
  unit-name
  "The unit's name, as systemctl addresses it."
  []
  "nido-coordinator.service")

(defn ^{:malli/schema [:=> [:cat] :Path]}
  unit-path
  "Absolute path of the unit file."
  []
  (str (fs/path (unit-dir) (unit-name))))

(defn ^{:malli/schema [:=> [:cat] :boolean]}
  installed?
  "True iff the unit file exists on disk."
  []
  (fs/exists? (unit-path)))

(defn ^{:malli/schema [:=> [:cat :string] :any]}
  write-unit!
  "Write the unit file, creating its directory. Overwrites any existing file."
  [contents]
  (let [path (unit-path)]
    (fs/create-dirs (fs/parent path))
    (spit path contents)))

(defn ^{:malli/schema [:=> [:cat] :any]}
  remove-unit!
  "Delete the unit file if it exists. No-op when absent."
  []
  (when (installed?) (fs/delete (unit-path))))

(defn ^{:malli/schema [:=> [:cat] [:maybe :string]]}
  working-directory
  "The checkout the installed unit runs the daemon from, or nil when no unit is installed."
  []
  (when (installed?)
    (some->> (slurp (unit-path)) (re-find #"(?m)^WorkingDirectory=(.+)$") second str/trim)))

(defn ^{:malli/schema [:=> [:cat :any] :map]}
  sh!
  "babashka.process/sh, returning {:exit :out :err}. Wrapped so tests can stub systemctl."
  [args]
  (p/sh args))

(defn- systemctl! [& args]
  (sh! (into ["systemctl" "--user"] args)))

(defn ^{:malli/schema [:=> [:cat] :boolean]}
  active?
  "True iff systemd reports the unit active (running, or starting)."
  []
  (zero? (:exit (systemctl! "is-active" "--quiet" (unit-name)))))

(defn ^{:malli/schema [:=> [:cat] :map]}
  daemon-reload!
  "Make systemd re-read unit files after one was written or removed."
  []
  (systemctl! "daemon-reload"))

(defn ^{:malli/schema [:=> [:cat] :map]}
  enable!
  "Enable the unit at boot and (re)start it now."
  []
  (let [r (systemctl! "enable" (unit-name))]
    (if (zero? (:exit r)) (systemctl! "restart" (unit-name)) r)))

(defn ^{:malli/schema [:=> [:cat] :map]}
  disable!
  "Stop the unit and disable it at boot."
  []
  (systemctl! "disable" "--now" (unit-name)))

(defn ^{:malli/schema [:=> [:cat] :map]}
  start!
  "Start the installed unit."
  []
  (systemctl! "start" (unit-name)))

(defn ^{:malli/schema [:=> [:cat] :map]}
  stop!
  "Stop the unit, leaving it installed and enabled at boot."
  []
  (systemctl! "stop" (unit-name)))

(defn ^{:malli/schema [:=> [:cat] :map]}
  restart!
  "Stop the running daemon, if any, and start a fresh one."
  []
  (systemctl! "restart" (unit-name)))

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  render-unit
  "The unit file for the coordinator, from the same inputs as the LaunchAgent plist:
   :bb-path, :nido-dir (WorkingDirectory), :log-path (stdout and stderr, appended) and
   :path-env (the daemon's PATH), and optionally :nido-home, set as the daemon's NIDO_HOME.

   RestartSec=10 matches launchd's ThrottleInterval, so a daemon that dies at once on start is
   retried every ten seconds rather than spun."
  [{:keys [bb-path nido-dir log-path path-env nido-home]}]
  (str "[Unit]\n"
       "Description=nido coordinator\n"
       "After=network-online.target\n"
       "Wants=network-online.target\n"
       "\n"
       "[Service]\n"
       "Type=simple\n"
       "ExecStart=" bb-path " nido:coordinator:run\n"
       "WorkingDirectory=" nido-dir "\n"
       "Environment=PATH=" path-env "\n"
       (when nido-home (str "Environment=NIDO_HOME=" nido-home "\n"))
       "Restart=always\n"
       "RestartSec=10\n"
       "StandardOutput=append:" log-path "\n"
       "StandardError=append:" log-path "\n"
       "\n"
       "[Install]\n"
       "WantedBy=default.target\n"))
