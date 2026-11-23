(ns nido.core.client-test
  "core-client answers as data: with no daemon to speak to, every request is answered
   `Unavailable`, naming the socket, and only a caller's `value!` turns it into an error."
  (:require [clojure.test :refer [deftest is]]
            [nido.core.client :as client]))

(defn- without-a-daemon [f]
  (let [was (client/local?)]
    (reset! @#'client/local-core false)
    (try
      (with-redefs [client/socket (constantly "/nonexistent/nido-core.sock")]
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
