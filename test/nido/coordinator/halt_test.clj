(ns nido.coordinator.halt-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.coordinator.halt :as halt]
   [nido.coordinator.state :as cstate]
   [nido.platform.io :as io]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f))
      (finally (fs/delete-tree tmp)))))

(deftest halted?-false-when-no-file
  (with-tmp (fn [] (is (false? (halt/halted?))))))

(deftest halt!-writes-the-file-with-reason
  (with-tmp
    (fn []
      (halt/halt! {:source :user :note "smoke"})
      (is (halt/halted?))
      (let [m (io/read-edn (cstate/halted-path))]
        (is (= :user (:source m)))
        (is (= "smoke" (:note m)))
        (is (string? (:halted-at m)))))))

(deftest resume!-removes-the-file
  (with-tmp
    (fn []
      (halt/halt! {:source :user})
      (is (halt/halted?))
      (halt/resume!)
      (is (false? (halt/halted?)))
      (is (false? (fs/exists? (cstate/halted-path)))))))

(deftest read-halt-info-returns-nil-when-absent
  (with-tmp (fn [] (is (nil? (halt/read-halt-info))))))

(deftest read-halt-info-returns-map-when-present
  (with-tmp
    (fn []
      (halt/halt! {:source :auto :reason :anomaly})
      (let [m (halt/read-halt-info)]
        (is (= :auto (:source m)))
        (is (= :anomaly (:reason m)))))))
