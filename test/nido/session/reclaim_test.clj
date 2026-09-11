(ns nido.session.reclaim-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.platform.io :as io]
   [nido.platform.process :as proc]
   [nido.session.reclaim :as reclaim]
   [nido.session.state :as state]))

(defn- mk-dir! [root id mtime-ms]
  (let [d (str (fs/path root id))]
    (fs/create-dirs d)
    (fs/set-last-modified-time d mtime-ms)
    d))

(deftest reclaim-orphans-respects-age-grace-and-registry
  ;; The age grace is the load-bearing safety property: a session's state dir
  ;; exists for the whole boot (PGDATA clone + app migrate) BEFORE its registry
  ;; entry is written, so a young, untracked dir may be a live boot in flight
  ;; and must NOT be deleted. Only old + untracked dirs are reclaimed.
  (let [tmp     (fs/create-temp-dir)
        now     1000000000000              ; fixed clock for the test
        hour    (* 60 60 1000)
        tracked-dir (mk-dir! tmp "tracked"      (- now (* 2 hour)))  ; old but registered
        old-orph    (mk-dir! tmp "old-orphan"   (- now (* 2 hour)))  ; old + untracked → reclaim
        young-orph  (mk-dir! tmp "young-orphan" now)]                ; untracked but mid-boot → keep
    (try
      (with-redefs [state/state-dir     (constantly (str tmp))
                    state/read-registry (constantly {"/proj" {:instance-id "tracked"}})]
        (let [{:keys [deleted]} (reclaim/reclaim-orphans! {:min-age-ms hour :now-ms now})]
          (is (= ["old-orphan"] (mapv first deleted))
              "only the old, untracked dir is reclaimed")
          (is (not (fs/exists? old-orph))   "old orphan deleted")
          (is (fs/exists? young-orph)       "young orphan (possible live boot) preserved")
          (is (fs/exists? tracked-dir)      "registered session preserved regardless of age")))
      (finally (fs/delete-tree tmp)))))

(deftest reclaim-orphans-zero-age-deletes-all-untracked
  ;; With no grace (min-age 0) every untracked dir goes; tracked stays.
  (let [tmp     (fs/create-temp-dir)
        now     1000000000000
        tracked (mk-dir! tmp "tracked" now)
        o1      (mk-dir! tmp "orphan-1" now)
        o2      (mk-dir! tmp "orphan-2" now)]
    (try
      (with-redefs [state/state-dir     (constantly (str tmp))
                    state/read-registry (constantly {"/proj" {:instance-id "tracked"}})]
        (let [{:keys [deleted]} (reclaim/reclaim-orphans! {:min-age-ms 0 :now-ms now})]
          (is (= #{"orphan-1" "orphan-2"} (set (map first deleted))))
          (is (and (not (fs/exists? o1)) (not (fs/exists? o2))) "both orphans deleted")
          (is (fs/exists? tracked) "tracked dir preserved")))
      (finally (fs/delete-tree tmp)))))

(deftest reclaim-orphans-keeps-a-dir-whose-recorded-process-still-runs
  ;; Registry absence is not proof nothing runs out of the dir. A boot killed
  ;; before it registered leaves a detached JVM whose pid only the dir records;
  ;; deleting it made the JVM invisible to nido (perf-claim, 2026-09-07..11).
  ;; A live pid keeps the dir and is reported; a dead one does not.
  (let [tmp  (fs/create-temp-dir)
        now  1000000000000
        hour (* 60 60 1000)
        pid-file-live (mk-dir! tmp "pid-file-live" (- now (* 2 hour)))
        pid-file-dead (mk-dir! tmp "pid-file-dead" (- now (* 2 hour)))
        edn-live      (mk-dir! tmp "edn-live"      (- now (* 2 hour)))]
    (try
      (spit (str (fs/path pid-file-live "repl.pid")) "4242\n")
      (spit (str (fs/path pid-file-dead "repl.pid")) "999")
      (io/write-edn! (str (fs/path edn-live "session.edn"))
                     {:service-states {:repl {:pid 5151 :port 1} :pg {:mode :shared}}})
      ;; the dirs' mtimes moved when the files landed; restore "old"
      (doseq [d [pid-file-live pid-file-dead edn-live]]
        (fs/set-last-modified-time d (- now (* 2 hour))))
      (with-redefs [state/state-dir       (constantly (str tmp))
                    state/read-registry   (constantly {})
                    proc/process-alive?   (fn [pid] (contains? #{4242 5151} pid))]
        (let [{:keys [deleted live]} (reclaim/reclaim-orphans! {:min-age-ms hour :now-ms now})]
          (is (= ["pid-file-dead"] (mapv first deleted))
              "an orphan whose recorded process is gone is still reclaimed")
          (is (= {"edn-live" #{5151} "pid-file-live" #{4242}}
                 (into {} (map (fn [[id _ pids]] [id pids])) live))
              "each kept dir is reported with the pids that kept it")
          (is (fs/exists? pid-file-live) "a pid file naming a live process keeps the dir")
          (is (fs/exists? edn-live) "so does a session.edn naming one")
          (is (not (fs/exists? pid-file-dead)))))
      (finally (fs/delete-tree tmp)))))

(deftest forced-reclaim-lists-but-keeps-a-dir-whose-process-runs
  (let [tmp  (fs/create-temp-dir)
        live (mk-dir! tmp "live" 0)
        dead (mk-dir! tmp "dead" 0)]
    (try
      (spit (str (fs/path live "repl.pid")) "4242")
      (with-redefs [state/state-dir     (constantly (str tmp))
                    state/read-registry (constantly {})
                    proc/process-alive? (fn [pid] (= 4242 pid))]
        (let [out (with-out-str (reclaim/reclaim! :force? true))]
          (is (re-find #"live .*kept: pid 4242 still running" out)
              "the listing says why the dir survives")
          (is (fs/exists? live) "a forced reclaim still spares it")
          (is (not (fs/exists? dead)))))
      (finally (fs/delete-tree tmp)))))
