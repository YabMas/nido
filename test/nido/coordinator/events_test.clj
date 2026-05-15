(ns nido.coordinator.events-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.events :as events]))

(deftest route-direct-returns-singleton-vector
  (let [tbp {:brian [{:name :inv :source {:type :manual} :skill :investigate-bug
                      :payload "{{event/url}}"}]}]
    (is (= [{:project :brian
             :trigger (first (:brian tbp))
             :payload {:url "u"}}]
           (events/route
             {:target  {:project :brian :trigger :inv}
              :payload {:url "u"}}
             tbp)))))

(deftest route-direct-unknown-trigger-returns-error-vector
  (is (= [{:error :unknown-trigger :project :brian :trigger :nope}]
         (events/route
           {:target {:project :brian :trigger :nope} :payload {}}
           {:brian []}))))

(deftest route-direct-unknown-project-returns-error-vector
  (is (= [{:error :unknown-project :project :nope}]
         (events/route
           {:target {:project :nope :trigger :inv} :payload {}}
           {}))))

(deftest route-broadcast-fans-out-to-matching-triggers
  (let [t1 {:name :a :source {:type :notion-view :database "x"} :skill :s
            :payload "" :filter {:status "Open"}}
        t2 {:name :b :source {:type :notion-view :database "x"} :skill :s
            :payload "" :filter {:status "Open"}}
        t3 {:name :c :source {:type :notion-view :database "x"} :skill :s
            :payload "" :filter {:status "Done"}}     ; filter rejects
        t4 {:name :d :source {:type :notion-view :database "y"} :skill :s
            :payload "" :filter {:status "Open"}}     ; different db
        tbp {:p [t1 t2 t3 t4]}
        env {:broadcast {:type :notion-view
                         :source-config {:database "x"}
                         :payload {:status "Open"}}}]
    (is (= [{:project :p :trigger t1 :payload {:status "Open"}}
            {:project :p :trigger t2 :payload {:status "Open"}}]
           (events/route env tbp)))))

(deftest route-broadcast-empty-when-no-matches
  (is (= []
         (events/route
           {:broadcast {:type :cron :source-config {} :payload {}}}
           {:p [{:name :a :source {:type :notion-view :database "x"}
                 :skill :s :payload ""}]}))))

(deftest route-unknown-envelope-returns-error-vector
  (is (= [{:error :unknown-envelope}]
         (events/route {:nonsense true} {}))))
