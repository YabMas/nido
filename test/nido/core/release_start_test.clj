(ns nido.core.release-start-test
  (:require [clojure.test :refer [deftest is]]
            [nido.core.release :as release]))

(deftest a-start-takes-back-a-turn-running-on-a-closed-workstream
  ;; A landing turn's own land:record closes its workstream while the turn still runs; a start
  ;; that took back only open workstreams left that turn reading running forever.
  (let [wss [{:ws "open-idle"      :state {:closed? false :turns {"t" {:status :completed :run 1 :exited 1}}}}
             {:ws "open-running"   :state {:closed? false :turns {"t" {:status :running :run 1}}}}
             {:ws "closed-running" :state {:closed? true  :turns {"t" {:status :completed :run 1 :exited 1}
                                                                  "u" {:status :running :run 1}}}}
             {:ws "closed-idle"    :state {:closed? true  :turns {}}}]]
    (is (= ["open-running" "closed-running"] (map :ws (#'release/with-running-turns wss))))))

(def ^:private wss
  [{:ws "brian/a" :state {:units {"u" {:id "u" :status :open :session {:status :ready :cwd "/w/u"}}
                                  "v" {:id "v" :status :open :session {:status :failed}}
                                  "f" {:id "f" :status :finished :session {:status :ready :cwd "/w/f"}}}}}
   {:ws "brian/b" :state {:closed? true
                          :units {"x" {:id "x" :status :open :session {:status :ready :cwd "/w/x"}}}}}])

(deftest a-host-boot-loses-every-ready-session-of-an-open-unit
  (is (= [{:key "boot/B2/brian/a/u" :ws "brian/a" :command {:command :record-session-lost :unit "u"}}]
         (release/sessions-lost "B2" wss))))

(deftest the-boot-is-accounted-for-once
  (let [f (java.io.File/createTempFile "host-boot" ".edn")
        sent (atom [])
        submit (fn [c] (swap! sent conj c) (doto (promise) (deliver {:events []})))]
    (try
      (.delete f)
      (with-redefs-fn {#'release/host-boot-id (constantly "B1")}
        #(#'release/account-for-boot! f submit wss))
      (is (empty? @sent) "a home with no boot recorded assumes nothing")
      (with-redefs-fn {#'release/host-boot-id (constantly "B1")}
        #(#'release/account-for-boot! f submit wss))
      (is (empty? @sent) "nor does a start within the same boot")
      (with-redefs-fn {#'release/host-boot-id (constantly "B2")}
        #(#'release/account-for-boot! f submit wss))
      (is (= ["boot/B2/brian/a/u"] (map :key @sent)) "a start after a host boot records the loss")
      (is (= "B2" (:id (read-string (slurp f)))))
      (finally (.delete f)))))

(deftest a-start-takes-back-a-run-the-store-cancelled-until-it-has-exited
  ;; A store-cancelled run lives on until its process ends; a start that took back only running
  ;; turns left it unwatched, and its stuck mark standing forever.
  (let [wss [{:ws "cancelled-live"   :state {:turns {"t" {:status :cancelled :ended-by :takeover :run 1}}}}
             {:ws "cancelled-exited" :state {:turns {"t" {:status :cancelled :ended-by :takeover :run 1 :exited 1}}}}
             {:ws "deadline"         :state {:turns {"t" {:status :cancelled :run 1 :exited 1}}}}]]
    (is (= ["cancelled-live"] (map :ws (#'release/with-running-turns wss))))))
