(ns nido.coordinator.record.workstream-status-entries-test
  "A status decision is recorded as an entry in the write that makes it, and the index is a cache
   the entries directory rebuilds."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.platform.io :as io]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(defn- fresh [] (ws/create! :brian {:stage :triaging}))

(defn- kinds-of [w] (mapv :kind (:entries w)))

(defn- entry [w kind]
  (let [row (last (filter #(= kind (:kind %)) (:entries w)))]
    (edn/read-string (slurp (str (fs/path (cstate/workstream-dir :brian (:id w)) (:file row)))))))

(deftest a-close-is-an-entry-in-the-same-write
  (with-tmp
    (fn [_]
      (let [w (fresh)
            c (ws/close! :brian (:id w) :between-phases 3 :nido)]
        (is (= {:outcome :between-phases :design {:seq 3}} (select-keys (:closed c) [:outcome :design])))
        (is (= [:closed] (kinds-of c)))
        (is (= {:format :closed :outcome :between-phases :design {:seq 3} :by :nido}
               (entry c :closed)))))))

(deftest a-reopen-is-an-entry-and-an-open-workstream-records-nothing
  (with-tmp
    (fn [_]
      (let [w (fresh)]
        (ws/close! :brian (:id w) :done)
        (let [r (ws/reopen! :brian (:id w) :in-progress :person)]
          (is (nil? (:closed r)))
          (is (= [:closed :reopened] (kinds-of r)))
          (is (= {:format :reopened :stage :in-progress :by :person} (entry r :reopened))))
        (testing "reopening at the stage it is already open at writes nothing"
          (is (= [:closed :reopened] (kinds-of (ws/reopen! :brian (:id w) :in-progress)))))))))

(deftest a-stage-move-is-an-entry-and-a-repeat-is-not
  (with-tmp
    (fn [_]
      (let [w (fresh)
            a (ws/advance-stage! :brian (:id w) :ready :person)]
        (is (= :ready (:stage a)))
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

(deftest the-index-is-a-cache-the-directory-rebuilds
  (with-tmp
    (fn [_]
      (let [w   (fresh)
            id  (:id w)
            _   (ws/append-entry! :brian id {:kind :note :session "s1"} "one")
            _   (ws/close! :brian id :done)
            idx (:entries (ws/read-ws :brian id))]
        (testing "every entry carries its own row, so the rebuild is the index"
          (is (= idx (ws/rebuilt-index :brian id {:entries []}))))
        (testing "a record that lost its index reads the rebuilt one"
          (io/write-edn! (cstate/workstream-edn-path :brian id)
                         (dissoc (ws/read-ws :brian id) :entries))
          (is (= idx (:entries (ws/read-ws :brian id)))))))))

(deftest freezing-keeps-rows-written-before-entries-carried-them
  (with-tmp
    (fn [_]
      (let [w  (fresh)
            id (:id w)
            _  (ws/append-entry! :brian id {:kind :note :amended-by "run-1"} "old")
            dir (cstate/workstream-dir :brian id)
            _  (fs/delete-tree (fs/path dir "entries.meta"))
            idx (:entries (io/read-edn (cstate/workstream-edn-path :brian id)))]
        (testing "without the kept row, a rebuild loses the facts only the index held"
          (is (not= idx (ws/rebuilt-index :brian id {:entries []}))))
        (is (= 1 (ws/freeze-index! :brian id)))
        (is (= 0 (ws/freeze-index! :brian id)) "a row already kept is never rewritten")
        (is (= idx (ws/rebuilt-index :brian id {:entries []})))
        (testing "reading the index keeps the rows it holds, so a read freezes it too"
          (fs/delete-tree (fs/path dir "entries.meta"))
          (is (= idx (:entries (ws/read-ws :brian id))))
          (is (= idx (ws/rebuilt-index :brian id {:entries []})))
          (io/write-edn! (cstate/workstream-edn-path :brian id)
                         (dissoc (ws/read-ws :brian id) :entries))
          (is (= idx (:entries (ws/read-ws :brian id)))
              "and a record that then loses its index reads the same rows"))))))

(deftest a-cached-row-reads-as-the-row-kept-beside-its-entry
  ;; A rebuild takes the kept row, so a present index answering otherwise would make deleting it
  ;; change the answer.
  (with-tmp
    (fn [_]
      (let [w    (fresh)
            id   (:id w)
            _    (ws/append-entry! :brian id {:kind :note :amended-by "run-1"} "one")
            path (cstate/workstream-edn-path :brian id)
            raw  (io/read-edn path)
            kept (first (:entries raw))]
        (io/write-edn! path (update raw :entries (fn [es] (mapv #(dissoc % :amended-by) es))))
        (is (= [kept] (:entries (ws/read-ws :brian id))))
        (is (= #{1} (ws/amended-seqs :brian id)))))))

(deftest a-row-rebuilt-from-a-file-name-mirrors-what-its-payload-names
  (with-tmp
    (fn [_]
      (let [w   (fresh)
            id  (:id w)
            dir (cstate/workstream-dir :brian id)]
        (fs/create-dirs (fs/path dir "entries"))
        (spit (str (fs/path dir "entries" "0001-phase-gate.edn"))
              (pr-str {:format :phase-gate :design {:seq 9} :opens "two"}))
        (is (= [{:kind :phase-gate :seq 1 :file "entries/0001-phase-gate.edn" :under 9 :opens "two"}]
               (ws/rebuilt-index :brian id {:entries []})))))))
