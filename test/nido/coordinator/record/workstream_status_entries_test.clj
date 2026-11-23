(ns nido.coordinator.record.workstream-status-entries-test
  "A status decision is recorded as an entry in the write that makes it, and a legacy ledger's
   rows are read the way its directory always read them."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.platform.io :as io]
   [nido.core.client :as client]
   [nido.coordinator.record.legacy-ledger :as legacy]
   [nido.coordinator.record.state :as cstate]
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

(def ^:private legacy-id "ws-20260101-bbbbbb")

(defn- legacy!
  "A legacy ledger on disk: workstream.edn with `index` as its index, and each of `files`
   ({name content}) under entries/, each of `kept` ({seq row}) under entries.meta/."
  [index files kept]
  (let [dir (cstate/workstream-dir :brian legacy-id)]
    (io/write-edn! (cstate/workstream-edn-path :brian legacy-id)
                   (cond-> {:id legacy-id :project :brian :external-refs [] :created-at "t"}
                     index (assoc :entries index)))
    (fs/create-dirs (fs/path dir "entries"))
    (fs/create-dirs (fs/path dir "entries.meta"))
    (doseq [[f c] files] (spit (str (fs/path dir "entries" f)) c))
    (doseq [[n row] kept] (io/write-edn! (str (fs/path dir (format "entries.meta/%04d.edn" n))) row))))

(defn- legacy-rows [] (mapv :row (:entries (legacy/read-legacy :brian legacy-id))))

(deftest a-legacy-ledger-without-its-index-reads-the-rows-kept-beside-its-entries
  (with-tmp
    (fn [_]
      (legacy! nil {"0001-note.md" "one"}
               {1 {:kind :note :seq 1 :at "a" :file "entries/0001-note.md" :session "s1"}})
      (is (= [{:kind :note :seq 1 :at "a" :session "s1" :ext "md"}] (legacy-rows))))))

(deftest a-legacy-row-kept-beside-its-entry-wins-over-the-index
  ;; The kept row is what a rebuild took, so an index answering otherwise would make deleting it
  ;; change the answer.
  (with-tmp
    (fn [_]
      (legacy! [{:kind :note :seq 1 :at "a" :file "entries/0001-note.md"}]
               {"0001-note.md" "one"}
               {1 {:kind :note :seq 1 :at "a" :file "entries/0001-note.md" :amended-by "run-1"}})
      (is (= "run-1" (:amended-by (first (legacy-rows))))))))

(deftest a-legacy-row-only-the-index-held-is-read-from-the-index
  (with-tmp
    (fn [_]
      (legacy! [{:kind :note :seq 1 :at "a" :file "entries/0001-note.md" :amended-by "run-1"}]
               {"0001-note.md" "one"} {})
      (is (= [{:kind :note :seq 1 :at "a" :amended-by "run-1" :ext "md"}] (legacy-rows)))
      (testing "and imported, the core holds the same row and content"
        (is (contains? (legacy/import! :brian legacy-id) :events))
        (let [w (ws/read-ws :brian legacy-id)]
          (is (= (legacy-rows) (:entries w)))
          (is (= "one" (ws/entry-text w 1)))
          (is (= #{1} (ws/amended-seqs :brian legacy-id))))))))

(deftest a-legacy-row-read-off-a-file-name-mirrors-what-its-payload-names
  (with-tmp
    (fn [_]
      (legacy! nil {"0001-phase-gate.edn" (pr-str {:format :phase-gate :design {:seq 9} :opens "two"})} {})
      (let [[row] (legacy-rows)]
        (is (= {:kind :phase-gate :seq 1 :ext "edn" :under 9 :opens "two"} (dissoc row :at)))
        (is (string? (:at row)) "the file's time stands in for the :at nothing kept")))))

(deftest a-legacy-retraction-kept-before-targets-were-mirrored-reads-its-target
  ;; A row written before retractions mirrored their target holds none; the target is read from
  ;; its entry instead, so the retraction of a baseline is still read in every generation.
  (with-tmp
    (fn [_]
      (legacy! [{:kind :retraction :seq 1 :at "a" :file "entries/0001-retraction.edn"}]
               {"0001-retraction.edn" (pr-str {:format :retraction :retracts {:seq 2}})}
               {1 {:kind :retraction :seq 1 :at "a" :file "entries/0001-retraction.edn"}})
      (is (= 2 (:retracts (first (legacy-rows))))))))


(deftest a-legacy-ledger-whose-files-share-a-seq-is-refused-whole
  ;; A lost update left a second file under a seq whose kept row names the other. A ledger reaches
  ;; the core whole or not at all, so the import sends nothing and names both files.
  (with-tmp
    (fn [_]
      (legacy! [{:kind :review :seq 1 :at "a" :file "entries/0001-review.edn"}]
               {"0001-review.edn" (pr-str {:format :review}) "0001-design.edn" (pr-str {:format :design})}
               {1 {:kind :review :seq 1 :at "a" :file "entries/0001-review.edn"}})
      (let [r (legacy/read-legacy :brian legacy-id)]
        (is (= [:review] (mapv (comp :kind :row) (:entries r))))
        (is (= ["0001-design.edn" "0001-review.edn"] (:shared r)))
        (is (= {:refused {:reason :files-share-a-seq :files ["0001-design.edn" "0001-review.edn"]}}
               (legacy/import! :brian legacy-id)))
        (is (nil? (:identity (:state (client/state (client/ws-key :brian legacy-id)))))
            "nothing of it reached the core")))))
