(ns nido.coordinator.record.workstream-status-entries-test
  "A status decision is recorded as an entry in the write that makes it."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.coordinator.record.workstream :as ws]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(defn- fresh [] (ws/create! :brian {}))

(defn- kinds-of [w] (mapv :kind (:entries w)))

(defn- entry [w kind]
  (let [row (last (filter #(= kind (:kind %)) (:entries w)))]
    (edn/read-string (ws/entry-text w (:seq row)))))

(deftest a-close-is-an-entry-in-the-same-write
  (with-tmp
    (fn [_]
      (let [w (fresh)
            c (ws/close! :brian (:id w) :between-phases 3 :nido)]
        (is (= {:outcome :between-phases :design {:seq 3}} (select-keys (ws/closure c) [:outcome :design])))
        (is (= [:closed] (kinds-of c)))
        (is (= {:format :closed :outcome :between-phases :design {:seq 3} :by :nido}
               (entry c :closed)))))))

(deftest a-reopen-is-an-entry-and-an-open-workstream-records-nothing
  (with-tmp
    (fn [_]
      (let [w (fresh)]
        (ws/close! :brian (:id w) :done)
        (let [r (ws/reopen! :brian (:id w) :in-progress :person)]
          (is (nil? (ws/closure r)))
          (is (= [:closed :reopened] (kinds-of r)))
          (is (= {:format :reopened :stage :in-progress :by :person} (entry r :reopened))))
        (testing "reopening at the stage it is already open at writes nothing"
          (is (= [:closed :reopened] (kinds-of (ws/reopen! :brian (:id w) :in-progress)))))))))

(deftest a-stage-move-is-an-entry-and-a-repeat-is-not
  (with-tmp
    (fn [_]
      (let [w (fresh)
            a (ws/advance-stage! :brian (:id w) :ready :person)]
        (is (= :ready (:stage (ws/newest-choice a))))
        (is (= {:format :stage-set :stage :ready :by :person} (entry a :stage-set)))
        (is (= [:stage-set] (kinds-of (ws/advance-stage! :brian (:id w) :ready))))
        (testing "nido is who decided, unless told otherwise"
          (is (= :nido (:by (entry (ws/advance-stage! :brian (:id w) :shipping) :stage-set))))))))
  (testing "a refused stage writes no entry"
    (with-tmp
      (fn [_]
        (let [w (fresh)]
          (is (thrown? Exception (ws/advance-stage! :brian (:id w) :implementing)))
          (is (empty? (:entries (ws/read-ws :brian (:id w))))))))))

(deftest the-newest-record-skips-status-entries
  (with-tmp
    (fn [_]
      (let [w (fresh)]
        (ws/append-entry! :brian (:id w) {:kind :note} "hello")
        (ws/close! :brian (:id w) :done)
        (is (= :note (:kind (ws/newest-record (ws/read-ws :brian (:id w))))))))))
