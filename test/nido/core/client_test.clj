(ns nido.core.client-test
  "core-client answers as data: with no daemon to speak to, every request is answered
   `Unavailable`, naming the socket, and only a caller's `value!` turns it into an error."
  (:require [clojure.test :refer [deftest is]]
            [nido.core.client :as client]
            [nido.platform.core :as core]))

(defn- without-a-daemon [f]
  (let [was (client/local?)]
    (reset! @#'client/local-core false)
    (try
      (with-redefs [client/socket (constantly "/nonexistent/nido-core.sock")
                    client/daemon-answers? (constantly true)]
        (f))
      (finally (reset! @#'client/local-core was)))))

(deftest no-daemon-is-answered-not-thrown
  (without-a-daemon
   (fn []
     (let [r (client/state "p/ws-1")]
       (is (client/unavailable? r))
       (is (= "/nonexistent/nido-core.sock" (get-in r [:unavailable :socket]))))
     (is (client/unavailable? (client/states "p/")))
     (is (client/unavailable? (client/entry-contents "p/ws-1" [1])))
     (is (client/unavailable? (client/command! "p/ws-1" "k" {:command :create-workstream})))
     (let [e (try (client/value! (client/state "p/ws-1") :state) nil
                  (catch clojure.lang.ExceptionInfo e e))]
       (is (= :unreachable (:core (ex-data e))) "a caller that needs the value gets an error")
       (is (re-find #"not running at /nonexistent/nido-core.sock" (ex-message e)))))))

(defn- as-a-persons-process
  "Call `f` as a process given no in-process core would, its daemon's socket nowhere: a request
   that reached for the daemon answers `Unavailable`."
  [f]
  (let [was (client/local?)]
    (reset! @#'client/local-core false)
    (try
      (with-redefs [client/socket (constantly "/nonexistent/nido-core.sock")]
        (f))
      (finally (reset! @#'client/local-core was)))))

(deftest a-test-never-reaches-the-daemon
  ;; A test run straight from `bb -e` gets no in-process core from bb nido:test; its fixtures
  ;; once landed in the person's core as workstreams nobody started.
  (as-a-persons-process
   (fn []
     (is (not (client/unavailable? (client/state "p/ws-1")))
         "answered by the in-process core, not the daemon")
     (is (contains? (client/command! "p/ws-1" "k" {:command :create-workstream
                                                   :identity {:project :p :external-refs []
                                                              :created-at "2026-01-01T00:00:00Z"}})
                    :events)))))

(deftest a-process-pointed-elsewhere-never-reaches-the-daemon
  (let [answers? #'client/daemon-answers?
        in-test? #'client/in-a-test?]
    (as-a-persons-process
     (fn []
       (with-redefs [client/in-a-test? (constantly false)]
         (is (true? (answers?)) "the person's own home, outside a test: the daemon answers")
         (with-redefs [core/nido-root (constantly "/tmp/some-other-home")]
           (is (false? (answers?)) "a home the daemon does not serve: never the daemon")))))
    (is (true? (in-test?)) "a running deftest is seen")))
