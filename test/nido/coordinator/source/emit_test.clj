(ns nido.coordinator.source.emit-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.coordinator.source.queue :as queue]
   [nido.coordinator.source.registry :as sources]
   [nido.coordinator.record.state :as cstate]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f))
      (finally (fs/delete-tree tmp)))))

(deftest emit-broadcast!-writes-envelope-to-queue
  (with-tmp
    (fn []
      (sources/emit-broadcast!
        {:type :notion-view :source-config {:database "x"} :payload {:page-id "p1"}})
      (let [envelopes (queue/drain!)]
        (is (= 1 (count envelopes)))
        (is (= :notion-view (-> envelopes first :broadcast :type)))
        (is (= "p1"         (-> envelopes first :broadcast :payload :page-id)))))))

(deftest emit-broadcast!-is-idempotent-by-content
  (with-tmp
    (fn []
      (sources/emit-broadcast!
        {:type :notion-view :source-config {:database "x"} :payload {:page-id "p1"}})
      (sources/emit-broadcast!
        {:type :notion-view :source-config {:database "x"} :payload {:page-id "p1"}})
      (is (= 1 (count (queue/drain!)))))))

(deftest emit-broadcast!-different-payloads-produce-different-files
  (with-tmp
    (fn []
      (sources/emit-broadcast!
        {:type :notion-view :source-config {:database "x"} :payload {:page-id "p1"}})
      (sources/emit-broadcast!
        {:type :notion-view :source-config {:database "x"} :payload {:page-id "p2"}})
      (is (= 2 (count (queue/drain!)))))))
