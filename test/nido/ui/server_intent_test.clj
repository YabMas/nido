(ns nido.ui.server-intent-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.work :as work]
   [nido.ui.server :as server]))

(defn- post-intent [blocked-by started]
  (with-redefs [server/read-intent-blocker (fn [project]
                                             (is (= "brian" project))
                                             blocked-by)
                work/start-intent! (fn [& args]
                                     (swap! started conj (vec args))
                                     {:decision :queued
                                      :trigger work/start-intent-trigger
                                      :queued "/q/x.edn"})]
    (:body (server/handle-request
            {:request-method :post :uri "/workstreams/intent/brian"
             :body (java.io.ByteArrayInputStream.
                    (.getBytes "{\"intent\":\"make the bar work\"}" "UTF-8"))}))))

(deftest intent-post-refuses-rather-than-losing-the-description-to-an-open-breaker
  ;; The daemon deletes an envelope on drain and only then skips it on a tripped
  ;; breaker, so a description enqueued here is gone for good. Nothing may be
  ;; written, and the reply must not claim it was queued.
  (let [started (atom [])
        body    (post-intent :breaker started)]
    (is (empty? @started))
    (is (str/includes? body "breaker is open"))
    (is (str/includes? body "nothing was queued"))
    (is (not (str/includes? body "Queued —")))))

(deftest intent-post-queues-with-the-reason-it-wont-run-yet
  ;; A down or halted daemon never drains, so the envelope survives: queue it and
  ;; say what has to happen for it to run.
  (let [started (atom [])
        body    (post-intent :daemon-down started)]
    (is (= 1 (count @started)))
    (is (str/includes? body "Queued —"))
    (is (str/includes? body "daemon is down"))))

(deftest intent-post-healthy-leg-queues-clean
  (let [started (atom [])
        body    (post-intent nil started)]
    (is (= [[:brian "make the bar work"]] @started))
    (is (str/includes? body "Queued —"))
    (is (not (str/includes? body "⚠")))))
