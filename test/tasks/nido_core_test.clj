(ns tasks.nido-core-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.daemon.systemd :as sd]
   [tasks.nido-core :as nido-core]))

(deftest stopping-the-core-leaves-its-turns-running
  ;; A turn that runs `bb nido:core:upgrade` is a process the core started: under systemd's
  ;; default kill mode, the upgrade's stop of the core killed it, and the upgrade with it.
  (is (str/includes? (sd/render-unit (nido-core/service-spec "/home/me/Code/nido"))
                     "KillMode=process\n")))
