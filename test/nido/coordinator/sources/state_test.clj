(ns nido.coordinator.sources.state-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.sources.state :as sst]
   [nido.coordinator.state :as cstate]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f))
      (finally (fs/delete-tree tmp)))))

(deftest read-nil-when-absent
  (with-tmp (fn [] (is (nil? (sst/read-state "abc123"))))))

(deftest write-then-read-round-trips
  (with-tmp
    (fn []
      (sst/write-state! "abc123"
                        {:type :notion-view
                         :source-config {:database "x"}
                         :last-rows #{"p1" "p2"}
                         :last-polled-at "2026-05-15T00:00:00Z"
                         :consecutive-failures 0})
      (let [s (sst/read-state "abc123")]
        (is (= :notion-view (:type s)))
        (is (= #{"p1" "p2"} (:last-rows s)))
        (is (zero? (:consecutive-failures s)))))))

(deftest write-state!-creates-sources-dir
  (with-tmp
    (fn []
      (fs/delete-tree (sst/sources-dir))
      (sst/write-state! "xyz" {:foo 1})
      (is (fs/exists? (sst/state-path "xyz"))))))

(deftest delete-state-removes-file
  (with-tmp
    (fn []
      (sst/write-state! "del" {:foo 1})
      (sst/delete-state! "del")
      (is (nil? (sst/read-state "del"))))))

(deftest list-state-hashes-enumerates-files
  (with-tmp
    (fn []
      (sst/write-state! "h1" {:foo 1})
      (sst/write-state! "h2" {:foo 2})
      (is (= #{"h1" "h2"} (set (sst/list-state-hashes)))))))
