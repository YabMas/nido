(ns nido.session.services.process-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.platform.process :as proc]
   [nido.session.service :as service]
   [nido.session.services.process]))

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
