(ns nido.coordinator.daemon.heartbeat-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.daemon.heartbeat :as hb]
   [nido.coordinator.record.state :as cstate]
   [nido.platform.io :as io]))

(deftest write!-records-state-with-timestamp
  (let [tmp     (fs/create-temp-dir)
        fake-ts "2026-05-13T10:00:00Z"]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))
                    clock/now-iso   (constantly fake-ts)]
        (cstate/ensure-dirs!)
        (hb/write! {:status :running :slots-in-use 0})
        (let [m (io/read-edn (cstate/status-path))]
          (is (= :running (:status m)))
          (is (= 0 (:slots-in-use m)))
          (is (= fake-ts (:heartbeat-at m)))))
      (finally (fs/delete-tree tmp)))))
