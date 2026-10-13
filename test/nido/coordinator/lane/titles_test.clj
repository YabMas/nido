(ns nido.coordinator.lane.titles-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.lane.titles :as titles]
   [nido.coordinator.record.workstream :as ws]
   [nido.platform.core :as core]))

(defn- notion-ws! [title]
  (ws/create! :brian {:stage :triaging
                      :external-refs [{:adapter :notion :id "BR-1" :title title}]}))

(deftest refresh-titles-a-workstream-from-its-basis
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (let [w (notion-ws! "fix the drift in session index")]
          (is (= 1 (titles/refresh-project! :brian (fn [b] (str "Title of " b)))))
          (is (= {:text  "Title of fix the drift in session index"
                  :basis "fix the drift in session index"}
                 (:title (ws/read-ws :brian (:id w)))))))
      (finally (fs/delete-tree tmp)))))

(deftest refresh-skips-a-title-whose-basis-is-unchanged
  (let [tmp   (fs/create-temp-dir)
        calls (atom 0)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (notion-ws! "fix the drift")
        (let [gen (fn [b] (swap! calls inc) b)]
          (titles/refresh-project! :brian gen)
          (is (= 0 (titles/refresh-project! :brian gen)))
          (is (= 1 @calls) "an unchanged basis costs no second call")))
      (finally (fs/delete-tree tmp)))))

(deftest refresh-leaves-the-record-alone-when-generation-fails
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (let [w (notion-ws! "fix the drift")]
          (is (= 0 (titles/refresh-project! :brian (constantly nil))))
          (is (nil? (:title (ws/read-ws :brian (:id w)))))))
      (finally (fs/delete-tree tmp)))))

(deftest clean-rejects-what-is-not-a-title
  (is (= "Drift in the session index" (#'titles/clean "\"Drift in the session index.\"\n")))
  (is (nil? (#'titles/clean "")))
  (is (nil? (#'titles/clean (apply str (repeat 100 "x"))))))
