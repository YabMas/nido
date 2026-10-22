(ns nido.session.services.process-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :as bp]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.platform.process :as proc]
   [nido.session.service :as service]
   [nido.session.services.process]
   [nido.session.state :as state]))

;; wait-for-port-file! used to collapse two very different endings into a bare
;; nil: "the process is gone" and "the process is still working, we ran out of
;; patience". Every :process service failure then surfaced as "Timed out waiting
;; for .nrepl-port", which sent us hunting a slow REPL when the JVM had actually
;; died on a classpath error seconds in. The outcome has to survive the return.

(deftest wait-for-port-file-reports-process-death-distinctly
  (with-redefs [proc/process-alive? (constantly false)
                core/log-step (constantly nil)]
    (is (= {:outcome :process-died}
           (#'nido.session.services.process/wait-for-port-file!
            "/nonexistent/.nrepl-port" 999999 1000)))))

(deftest wait-for-port-file-reports-timeout-distinctly
  (with-redefs [proc/process-alive? (constantly true)
                core/log-step (constantly nil)]
    (is (= {:outcome :timeout}
           (#'nido.session.services.process/wait-for-port-file!
            "/nonexistent/.nrepl-port" 999999 300)))))

(deftest wait-for-port-file-returns-the-port-once-written
  (let [tmp (fs/create-temp-dir)
        port-file (str (fs/path tmp ".nrepl-port"))]
    (try
      (spit port-file "51818")
      (with-redefs [proc/process-alive? (constantly true)
                    core/log-step (constantly nil)]
        (is (= {:port 51818}
               (#'nido.session.services.process/wait-for-port-file!
                port-file 999999 1000))))
      (finally (fs/delete-tree tmp)))))

;; End-to-end on the shape that actually bit us: the command exits before ever
;; writing its port file. The thrown message must say the process died — not
;; blame a timeout that never happened.

(deftest start-service-blames-the-dead-process-not-the-clock
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-home (constantly (str tmp))
                    core/log-step (constantly nil)]
        (let [ctx {:session {:project-name "proj"
                             :project-dir (str tmp)
                             :instance-id "inst-dead"}}
              svc {:type :process
                   :name :repl
                   :command "exit 3"
                   :port-file ".nrepl-port"
                   :port-timeout-ms 15000}
              e (is (thrown? clojure.lang.ExceptionInfo
                             (service/start-service! svc ctx {})))
              msg (ex-message e)]
          (is (str/includes? msg "exited")
              "names process death as the cause")
          (is (not (str/includes? msg "Timed out"))
              "does not blame the timeout when the process died")))
      (finally (fs/delete-tree tmp)))))

;; A boot the `bb` running it never finished — killed, not merely failed — used
;; to leave its JVM detached with no record: the pid was written only in
;; session.edn, at the end. The pid file is written at spawn, before anything
;; slow, and removed when the service is stopped.

(deftest start-service-records-the-pid-before-waiting-and-stop-removes-it
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-home (constantly (str tmp))
                    core/log-step (constantly nil)]
        (let [ctx {:session {:project-name "proj"
                             :project-dir (str tmp)
                             :instance-id "inst-live"}}
              svc {:type :process :name :repl :command "sleep 30"}
              {:keys [state]} (service/start-service! svc ctx {})
              pid-file (state/pid-file "inst-live" :repl)]
          (try
            (is (= pid-file (:pid-file state)) "the saved state names the pid file")
            (is (= (str (:pid state)) (slurp pid-file)) "the file holds the spawned pid")
            (service/stop-service! svc state)
            (is (not (fs/exists? pid-file)) "stopping the service removes it")
            (is (not (proc/process-alive? (:pid state))) "and the process is gone")
            (finally (proc/stop-process! (:pid state))))))
      (finally (fs/delete-tree tmp)))))

(deftest start-service-removes-the-pid-file-of-a-process-that-died-at-boot
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-home (constantly (str tmp))
                    core/log-step (constantly nil)]
        (let [ctx {:session {:project-name "proj"
                             :project-dir (str tmp)
                             :instance-id "inst-dead"}}
              svc {:type :process :name :repl :command "exit 3"
                   :port-file ".nrepl-port" :port-timeout-ms 15000}]
          (is (thrown? clojure.lang.ExceptionInfo (service/start-service! svc ctx {})))
          (is (not (fs/exists? (state/pid-file "inst-dead" :repl)))
              "a failed boot leaves no pid file to be mistaken for a live process")))
      (finally (fs/delete-tree tmp)))))

;; The pid file is the only record of a process a start spawned and never saw
;; through to session.edn — its bb was killed, or its rollback could not stop it.
;; The next start of the same instance used to overwrite that record unread,
;; leaving the earlier JVM running and named by nothing: six brian REPLs ran that
;; way on 2026-10-22, burning cores the next start then timed out for lack of.

(defn- spawn-sleep!
  "A `sleep` this instance never started."
  []
  (.pid ^Process (:proc (bp/process ["sleep" "30"]))))

(deftest start-service-stops-the-process-an-earlier-start-left-on-record
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-home (constantly (str tmp))
                    core/log-step (constantly nil)]
        (let [ctx {:session {:project-name "proj" :project-dir (str tmp) :instance-id "inst"}}
              svc {:type :process :name :repl :command "sleep 30"}
              first-start  (:state (service/start-service! svc ctx {}))
              second-start (:state (service/start-service! svc ctx {}))]
          (try
            (is (not (proc/process-alive? (:pid first-start)))
                "the earlier start's process is stopped before the new one spawns")
            (is (proc/process-alive? (:pid second-start)))
            (is (= (str (:pid second-start)) (slurp (state/pid-file "inst" :repl)))
                "and the record names the one process left")
            (finally (proc/stop-process! (:pid first-start))
                     (proc/stop-process! (:pid second-start))))))
      (finally (fs/delete-tree tmp)))))

(deftest start-service-leaves-a-recycled-pid-running
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-home (constantly (str tmp))
                    core/log-step (constantly nil)]
        (let [ctx      {:session {:project-name "proj" :project-dir (str tmp) :instance-id "inst"}}
              svc      {:type :process :name :repl :command "sleep 30"}
              pid-file (state/pid-file "inst" :repl)
              stranger (spawn-sleep!)]
          ;; A record older than the process it names: the pid was reused after
          ;; the recorded process died.
          (fs/create-dirs (fs/parent pid-file))
          (spit pid-file (str stranger))
          (fs/set-last-modified-time pid-file (java.time.Instant/parse "2026-01-01T00:00:00Z"))
          (let [{:keys [state]} (service/start-service! svc ctx {})]
            (try
              (is (proc/process-alive? stranger) "a process this instance never started is left alone")
              (is (= (str (:pid state)) (slurp pid-file)))
              (finally (proc/stop-process! stranger)
                       (proc/stop-process! (:pid state)))))))
      (finally (fs/delete-tree tmp)))))

(deftest stop-service-with-only-a-pid-file-stops-the-process-it-names
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-home (constantly (str tmp))
                    core/log-step (constantly nil)]
        (let [ctx      {:session {:project-name "proj" :project-dir (str tmp) :instance-id "inst"}}
              svc      {:type :process :name :repl :command "sleep 30"}
              {:keys [state]} (service/start-service! svc ctx {})
              pid-file (state/pid-file "inst" :repl)]
          (try
            (service/stop-service! svc {:pid-file pid-file})
            (is (not (proc/process-alive? (:pid state))))
            (is (not (fs/exists? pid-file)))
            (finally (proc/stop-process! (:pid state))))))
      (finally (fs/delete-tree tmp)))))

(deftest stop-service-keeps-the-record-of-a-process-that-survives
  (let [tmp (fs/create-temp-dir)
        pid-file (str (fs/path tmp "repl.pid"))]
    (try
      (spit pid-file "4242")
      (with-redefs [proc/process-alive? (constantly true)
                    proc/stop-process!  (constantly :killed)
                    core/log-step       (constantly nil)]
        (is (thrown? clojure.lang.ExceptionInfo
                     (service/stop-service! {:type :process :name :repl}
                                            {:pid 4242 :pid-file pid-file})))
        (is (fs/exists? pid-file) "the only record of a running process is not thrown away"))
      (finally (fs/delete-tree tmp)))))

(deftest stop-service-stops-what-the-pid-file-names-beside-a-dead-saved-pid
  ;; session.edn from an earlier start whose process died; a later start killed
  ;; after spawning, which overwrote the pid file. Stopping from session.edn used
  ;; to stop the dead pid and delete the only record of the live one.
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-home (constantly (str tmp))
                    core/log-step (constantly nil)]
        (let [ctx      {:session {:project-name "proj" :project-dir (str tmp) :instance-id "inst"}}
              svc      {:type :process :name :repl :command "sleep 30"}
              {:keys [state]} (service/start-service! svc ctx {})
              pid-file (state/pid-file "inst" :repl)]
          (try
            (service/stop-service! svc {:pid 999999 :pid-file pid-file})
            (is (not (proc/process-alive? (:pid state))) "the process the file names is stopped")
            (is (not (fs/exists? pid-file)))
            (finally (proc/stop-process! (:pid state))))))
      (finally (fs/delete-tree tmp)))))

(deftest start-service-names-the-process-when-the-shell-output-is-not-a-pid
  ;; `bash -l` runs the login profile, which may print. Reading the pid from
  ;; stdout made such noise a start that detached a process and recorded none.
  (let [tmp (fs/create-temp-dir)
        real-shell bp/shell]
    (try
      (with-redefs [core/nido-home (constantly (str tmp))
                    core/log-step  (constantly nil)
                    bp/shell       (fn [& args]
                                     (update (apply real-shell args) :out #(str "Welcome!\n" %)))]
        (let [ctx {:session {:project-name "proj" :project-dir (str tmp) :instance-id "inst"}}
              svc {:type :process :name :repl :command "sleep 30"}
              {:keys [state]} (service/start-service! svc ctx {})]
          (try
            (is (proc/process-alive? (:pid state)))
            (is (= (str (:pid state)) (str/trim (slurp (state/pid-file "inst" :repl)))))
            (finally (proc/stop-process! (:pid state))))))
      (finally (fs/delete-tree tmp)))))
