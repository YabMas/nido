(ns nido.coordinator.daemon.supervisor-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.daemon.launchctl :as lc]
   [nido.coordinator.daemon.supervisor :as sup]
   [nido.coordinator.daemon.systemd :as sd]))

(def ^:private spec
  {:bb-path "/usr/local/bin/bb" :nido-dir "/home/me/Code/nido"
   :log-path "/home/me/.nido/coordinator/coordinator.log" :path-env "/usr/bin:/bin"})

(defn- on-systemd [f]
  (let [tmp (fs/create-temp-dir) calls (atom [])]
    (try
      (with-redefs [sup/manager (constantly :systemd)
                    sd/unit-dir (constantly (str tmp))
                    sd/sh!      (fn [args] (swap! calls conj (vec (drop 2 args))) {:exit 0 :out "" :err ""})]
        (f calls))
      (finally (fs/delete-tree tmp)))))

(deftest on-linux-install-writes-a-restarting-unit-and-enables-it
  (on-systemd
   (fn [calls]
     (is (:ok? (sup/install! spec)))
     (let [unit (slurp (sd/unit-path))]
       (is (str/includes? unit "ExecStart=/usr/local/bin/bb nido:coordinator:run"))
       (is (str/includes? unit "Restart=always") "a daemon that exits is started again — how a landing's restart is paid")
       (is (str/includes? unit "WantedBy=default.target") "enabled at boot, which lingering makes login-free")
       (is (not (str/includes? unit "NIDO_HOME")) "no home named, the daemon's default"))
     (is (str/includes? (sd/render-unit (assoc spec :nido-home "/tmp/h")) "Environment=NIDO_HOME=/tmp/h\n"))
     (is (not (str/includes? (slurp (sd/unit-path)) "KillMode")) "no kill mode named, systemd's default")
     (is (str/includes? (sd/render-unit (assoc spec :kill-mode "process")) "KillMode=process\n"))
     (is (= [["daemon-reload"] ["enable" "nido-coordinator.service"] ["restart" "nido-coordinator.service"]] @calls))
     (testing "status reads the installed unit"
       (is (= {:manager :systemd :installed? true :running? true} (sup/status)))
       (is (= "/home/me/Code/nido" (sup/checkout)))))))

(deftest on-linux-stop-leaves-the-unit-and-uninstall-removes-it
  (on-systemd
   (fn [calls]
     (sup/install! spec)
     (reset! calls [])
     (is (:ok? (sup/stop!)))
     (is (sd/installed?))
     (is (:ok? (sup/start!)))
     (is (:ok? (sup/restart!)))
     (is (:ok? (sup/uninstall!)))
     (is (not (sd/installed?)))
     (is (= [["stop" "nido-coordinator.service"] ["start" "nido-coordinator.service"]
             ["restart" "nido-coordinator.service"] ["disable" "--now" "nido-coordinator.service"]
             ["daemon-reload"]]
            @calls)))))

(deftest a-failed-verb-answers-in-the-managers-words
  (with-redefs [sup/manager (constantly :systemd)
                sd/sh!      (constantly {:exit 5 :out "" :err "Unit not found"})]
    (is (= {:ok? false :exit 5 :err "Unit not found" :manager :systemd :did "systemctl --user start"}
           (sup/start!)))))

(deftest on-macos-restart-kickstarts-a-loaded-agent-and-bootstraps-one-that-is-not
  (let [did (atom [])]
    (with-redefs [sup/manager   (constantly :launchd)
                  lc/kickstart! (fn [] (swap! did conj :kickstart) {:exit 0 :err ""})
                  lc/bootstrap! (fn [] (swap! did conj :bootstrap) {:exit 0 :err ""})]
      (with-redefs [lc/loaded? (constantly true)] (sup/restart!))
      (with-redefs [lc/loaded? (constantly false)] (sup/restart!))
      (is (= [:kickstart :bootstrap] @did)))))
