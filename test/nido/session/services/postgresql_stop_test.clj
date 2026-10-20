(ns nido.session.services.postgresql-stop-test
  (:require
   [babashka.fs :as fs]
   [babashka.process]
   [clojure.test :refer [deftest is]]
   [nido.platform.process :as proc]
   [nido.session.services.postgresql :as pg]))

(defn- with-stop-stubs
  "Run pg-ctl-stop! on a temp PGDATA holding a postmaster.pid for pid 4242,
   with `pg_ctl stop -m <mode>` exiting `(exit-for mode)`. Returns the modes
   pg_ctl was asked for and the pids raw-killed, in order."
  [exit-for]
  (let [dir    (str (fs/create-temp-dir))
        modes  (atom [])
        killed (atom [])]
    (spit (str (fs/path dir "postmaster.pid")) "4242\n/x\n0\n7010\n")
    (with-redefs [pg/find-pg-bin-dir         (constantly "/bin")
                  babashka.process/shell     (fn [_opts & args]
                                               (let [mode (second (drop-while #(not= "-m" %) args))]
                                                 (swap! modes conj mode)
                                                 {:exit (exit-for mode)}))
                  proc/stop-process!         (fn [pid] (swap! killed conj pid))]
      (pg/pg-ctl-stop! dir))
    (fs/delete-tree dir)
    {:modes @modes :killed @killed}))

(deftest a-fast-stop-that-outlasts-its-wait-escalates-to-immediate-not-a-raw-kill
  ;; A raw SIGKILL of a postmaster mid-shutdown orphans its checkpointer, which
  ;; keeps shared memory and fails the next start on this PGDATA.
  (is (= {:modes ["fast" "immediate"] :killed []}
         (with-stop-stubs {"fast" 1 "immediate" 0}))))

(deftest a-clean-fast-stop-escalates-nothing
  (is (= {:modes ["fast"] :killed []}
         (with-stop-stubs {"fast" 0}))))

(deftest the-raw-kill-is-left-for-when-pg-ctl-fails-both
  (is (= {:modes ["fast" "immediate"] :killed [4242]}
         (with-stop-stubs {"fast" 1 "immediate" 1}))))
