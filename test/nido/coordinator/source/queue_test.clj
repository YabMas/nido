(ns nido.coordinator.source.queue-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.coordinator.source.queue :as queue]
   [nido.coordinator.record.state :as cstate]
   [nido.platform.io :as io]))

(deftest drain-reads-and-removes-files
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (let [f1 (str (fs/path (cstate/queue-dir) "a.edn"))
              f2 (str (fs/path (cstate/queue-dir) "b.edn"))]
          (io/write-edn! f1 {:target {:project :brian :trigger :x} :payload {:url "1"}})
          (io/write-edn! f2 {:target {:project :brian :trigger :x} :payload {:url "2"}})
          (let [envelopes (queue/drain!)]
            (is (= 2 (count envelopes)))
            (is (every? #(= :brian (-> % :target :project)) envelopes))
            (is (not (fs/exists? f1)))
            (is (not (fs/exists? f2))))))
      (finally (fs/delete-tree tmp)))))

(deftest drain-empty-queue
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (is (= [] (queue/drain!))))
      (finally (fs/delete-tree tmp)))))

(deftest drain-skips-and-quarantines-malformed-files
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (let [good (str (fs/path (cstate/queue-dir) "good.edn"))
              bad  (str (fs/path (cstate/queue-dir) "bad.edn"))]
          (io/write-edn! good {:target {:project :p :trigger :t} :payload {}})
          (io/write-text! bad "not-edn-at-all{{{")
          (let [envelopes (queue/drain!)]
            (is (= 1 (count envelopes)))
            (is (not (fs/exists? good)))
            (is (fs/exists? (str (fs/path (cstate/queue-dir) "bad.edn.malformed"))) "bad file renamed for inspection"))))
      (finally (fs/delete-tree tmp)))))

(deftest enqueue!-writes-an-envelope-file
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (queue/enqueue! {:target {:project :brian :trigger :x} :payload {:url "1"}})
        (is (= 1 (count (fs/list-dir (cstate/queue-dir))))))
      (finally (fs/delete-tree tmp)))))

(defn- with-tmp-nido [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f))
      (finally (fs/delete-tree tmp)))))

(deftest enqueue-sets-received-at
  (with-tmp-nido
    (fn []
      (let [path (queue/enqueue! {:target {:project :p :trigger :t} :payload {}})
            env  (io/read-edn path)]
        (is (string? (:received-at env))
            ":received-at should be set as an ISO timestamp")))))

(deftest enqueue-defaults-priority-to-zero
  (with-tmp-nido
    (fn []
      (let [path (queue/enqueue! {:target {:project :p :trigger :t} :payload {}})
            env  (io/read-edn path)]
        (is (= 0 (:priority env))
            ":priority should default to 0 when not provided")))))

(deftest enqueue-preserves-explicit-priority
  (with-tmp-nido
    (fn []
      (let [path (queue/enqueue! {:target {:project :p :trigger :t}
                                   :payload {} :priority 42})
            env  (io/read-edn path)]
        (is (= 42 (:priority env)))))))
