(ns nido.coordinator.daemon.supervisor
  "Which service manager keeps the daemon alive — launchd on macOS, systemd --user on Linux.
   Its callers ask for an outcome and never name either: nothing outside this namespace and the
   launchctl and systemd adapters it composes names launchctl or systemctl. The dashboard runs
   inside the daemon process, so supervising the daemon supervises it.

   Every verb answers {:ok? :exit :err :manager :did}, :did naming in the manager's own words
   what was run, so a caller can report a failure without knowing which manager it came from."
  (:require
   [clojure.string :as str]
   [nido.coordinator.daemon.launchctl :as lc]
   [nido.coordinator.daemon.systemd :as sd]))

(defn ^{:malli/schema [:=> [:cat] [:enum :launchd :systemd]]}
  manager
  "The service manager on this host. Wrapped so tests can pick one."
  []
  (if (str/starts-with? (str/lower-case (System/getProperty "os.name" "")) "mac")
    :launchd
    :systemd))

(defn- outcome [did {:keys [exit err]}]
  {:ok? (zero? exit) :exit exit :err (str err) :manager (manager) :did did})

(def ^:private done {:exit 0 :err ""})

(defn ^{:malli/schema [:=> [:cat] :map]}
  status
  "{:manager :installed? :running?}: which manager this host has, whether the daemon's service
   is installed with it, and whether the manager has it loaded and running."
  []
  (case (manager)
    :launchd (let [installed? (lc/installed?)]
               {:manager :launchd :installed? installed?
                :running? (boolean (and installed? (lc/loaded?)))})
    :systemd (let [installed? (sd/installed?)]
               {:manager :systemd :installed? installed?
                :running? (boolean (and installed? (sd/active?)))})))

(defn ^{:malli/schema [:=> [:cat] [:maybe :string]]}
  checkout
  "The checkout the installed service loads the daemon's code from — the working directory its
   plist or unit names — or nil when no service is installed."
  []
  (case (manager)
    :launchd (lc/working-directory)
    :systemd (sd/working-directory)))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  install!
  "Install the daemon's service from `spec` — {:bb-path :nido-dir :log-path :path-env} — and
   start it, replacing a service already installed so the new definition takes effect. From then
   on the manager starts it again after it exits and at every boot or login."
  [spec]
  (case (manager)
    :launchd (do (when (lc/loaded?) (lc/bootout!))
                 (lc/write-plist! (lc/render-plist spec))
                 (outcome "launchctl bootstrap" (lc/bootstrap!)))
    :systemd (do (sd/write-unit! (sd/render-unit spec))
                 (let [r (sd/daemon-reload!)]
                   (if (zero? (:exit r))
                     (outcome "systemctl --user enable + restart" (sd/enable!))
                     (outcome "systemctl --user daemon-reload" r))))))

(defn ^{:malli/schema [:=> [:cat] :map]}
  uninstall!
  "Stop the service and remove it. No-op when absent."
  []
  (case (manager)
    :launchd (do (when (lc/loaded?) (lc/bootout!))
                 (lc/remove-plist!)
                 (outcome "launchctl bootout" done))
    :systemd (if (sd/installed?)
               (let [r (sd/disable!)]
                 (sd/remove-unit!)
                 (sd/daemon-reload!)
                 (outcome "systemctl --user disable --now" r))
               (outcome "nothing installed" done))))

(defn ^{:malli/schema [:=> [:cat] :map]}
  start!
  "Start the installed service without installing it again."
  []
  (case (manager)
    :launchd (outcome "launchctl bootstrap" (lc/bootstrap!))
    :systemd (outcome "systemctl --user start" (sd/start!))))

(defn ^{:malli/schema [:=> [:cat] :map]}
  stop!
  "Stop the service, leaving it installed: it does not come back until started, or at the next
   boot or login."
  []
  (case (manager)
    :launchd (outcome "launchctl bootout" (lc/bootout!))
    :systemd (outcome "systemctl --user stop" (sd/stop!))))

(defn ^{:malli/schema [:=> [:cat] :map]}
  restart!
  "Restart the service now: a fresh daemon in place of the running one, or started when none
   is running."
  []
  (case (manager)
    :launchd (if (lc/loaded?)
               (outcome "launchctl kickstart -k" (lc/kickstart!))
               (outcome "launchctl bootstrap" (lc/bootstrap!)))
    :systemd (outcome "systemctl --user restart" (sd/restart!))))
