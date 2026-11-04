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

;; A postmaster in a smart shutdown is alive and still listening, but refuses
;; every new connection until its last client leaves — which a session JVM's
;; pool never does. Adopting it as running is what let six brian session starts
;; die on "the database system is shutting down" for 42 minutes.

(defn- pid-file-dir
  "A temp PGDATA whose postmaster.pid names pid 4242, with `status` on line 8
   the way PG pads it."
  [status]
  (let [dir (str (fs/create-temp-dir))]
    (spit (str (fs/path dir "postmaster.pid"))
          (str "4242\n" dir "\n0\n6145\n/tmp/s\n127.0.0.1\n1 2\n" status "   \n"))
    dir))

(deftest a-live-postmaster-whose-status-reads-stopping-is-not-running
  (with-redefs [proc/process-alive? (fn [pid] (= 4242 pid))]
    (let [dir (pid-file-dir "stopping")]
      (is (= :stopping (:status (pg/detect-running-postmaster dir))))
      (fs/delete-tree dir))
    (let [dir (pid-file-dir "ready")]
      (is (= :running (:status (pg/detect-running-postmaster dir))))
      (fs/delete-tree dir))))

(deftest finish-stopping-stops-only-a-stopping-postmaster
  (let [stopped (atom [])]
    (with-redefs [pg/pg-ctl-stop!              (fn [d] (swap! stopped conj d) true)
                  pg/detect-running-postmaster (constantly nil)]
      (is (nil? (pg/finish-stopping! "/d" {:status :stopping :pid 1 :port 2}))
          "returns the state after the stop, ready for a fresh start")
      (is (= {:status :running :pid 1 :port 2}
             (pg/finish-stopping! "/d" {:status :running :pid 1 :port 2})))
      (is (nil? (pg/finish-stopping! "/d" nil))))
    (is (= ["/d"] @stopped) "a running or absent postmaster is never stopped")))
