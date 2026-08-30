(ns nido.coordinator.daemon.anomaly-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.daemon.anomaly :as anomaly]
   [nido.coordinator.record.clock :as clock]))

(deftest record-and-check-spawn-burst
  (let [d (anomaly/empty-detector)
        ts-list ["2026-05-13T10:00:00Z"
                 "2026-05-13T10:00:10Z"
                 "2026-05-13T10:00:20Z"
                 "2026-05-13T10:00:30Z"
                 "2026-05-13T10:00:40Z"
                 "2026-05-13T10:00:50Z"]   ; 6 within 60s
        d' (reduce #(anomaly/record-spawn %1 %2) d ts-list)]
    (with-redefs [clock/now-iso (constantly "2026-05-13T10:00:55Z")]
      (let [check (anomaly/check d' {:spawn-window-ms 60000 :spawn-threshold 5
                                     :fail-window-ms 300000 :fail-threshold 3})]
        (is (= :spawn-burst (:trip check)))
        (is (= 6 (:count check)))))))

(deftest record-and-check-failure-burst
  (let [d  (anomaly/empty-detector)
        ts ["2026-05-13T10:00:00Z" "2026-05-13T10:01:00Z" "2026-05-13T10:02:00Z"]
        d' (reduce #(anomaly/record-failure %1 %2) d ts)]
    (with-redefs [clock/now-iso (constantly "2026-05-13T10:02:30Z")]
      (let [check (anomaly/check d' {:spawn-window-ms 60000 :spawn-threshold 5
                                     :fail-window-ms 300000 :fail-threshold 3})]
        (is (= :fail-burst (:trip check)))))))

(deftest below-threshold-returns-nil
  (let [d  (anomaly/empty-detector)
        d' (reduce #(anomaly/record-spawn %1 %2) d
                   ["2026-05-13T10:00:00Z" "2026-05-13T10:30:00Z"])]
    (with-redefs [clock/now-iso (constantly "2026-05-13T10:35:00Z")]
      (is (nil? (anomaly/check d' {:spawn-window-ms 60000 :spawn-threshold 5
                                   :fail-window-ms 300000 :fail-threshold 3}))))))

(deftest old-events-pruned
  ;; Events older than max(spawn-window, fail-window) should not count.
  (let [d  (anomaly/empty-detector)
        d' (reduce #(anomaly/record-spawn %1 %2) d
                   ["2026-05-13T08:00:00Z" "2026-05-13T08:00:10Z"
                    "2026-05-13T08:00:20Z" "2026-05-13T08:00:30Z"
                    "2026-05-13T08:00:40Z" "2026-05-13T08:00:50Z"])]
    (with-redefs [clock/now-iso (constantly "2026-05-13T10:00:00Z")]
      (is (nil? (anomaly/check d' {:spawn-window-ms 60000 :spawn-threshold 5
                                   :fail-window-ms 300000 :fail-threshold 3}))))))
