(ns nido.coordinator.events-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.events :as events]))

(deftest route-direct-returns-singleton-vector
  (let [tbp {:brian [{:name :inv :source {:type :manual} :skill :investigate-bug
                      :payload "{{event/url}}"}]}]
    (is (= [{:project :brian
             :trigger (first (:brian tbp))
             :payload {:url "u"}
             :priority 0
             :session-profile nil
             :uncapped? false}]
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
    (is (= [{:project :p :trigger t1 :payload {:status "Open"} :priority 0 :session-profile nil :uncapped? false}
            {:project :p :trigger t2 :payload {:status "Open"} :priority 0 :session-profile nil :uncapped? false}]
           (events/route env tbp)))))

(deftest route-broadcast-matches-despite-injected-project
  ;; discover-source-configs tags the running source-config with :project, which
  ;; rides in the broadcast; the trigger's declared :source has no :project.
  ;; Routing must still match — and only to the same project's triggers.
  (let [t   {:name :a :source {:type :notion-view :database "x"} :skill :s :payload ""}
        env {:broadcast {:type :notion-view
                         :source-config {:type :notion-view :database "x" :project :brian}
                         :payload {:status "Open"}}}]
    (is (= [:a] (mapv #(-> % :trigger :name) (events/route env {:brian [t]})))
        "matches despite the injected :project")
    (is (= [] (events/route env {:other [t]}))
        "broadcast tagged :brian does not match a different project's trigger")))

(deftest route-broadcast-empty-when-no-matches
  (is (= []
         (events/route
           {:broadcast {:type :cron :source-config {} :payload {}}}
           {:p [{:name :a :source {:type :notion-view :database "x"}
                 :skill :s :payload ""}]}))))

(deftest route-unknown-envelope-returns-error-vector
  (is (= [{:error :unknown-envelope}]
         (events/route {:nonsense true} {}))))

(deftest route-uses-trigger-priority-when-set
  (let [t   {:name :t1 :source {:type :notion-view :database "x"} :skill :s
              :payload "" :priority 10}
        tbp {:p [t]}
        env {:broadcast {:type :notion-view
                         :source-config {:database "x"}
                         :payload {:status "Open"}}}]
    (is (= 10 (-> (events/route env tbp) first :priority)))))

(deftest route-defaults-priority-to-zero
  (let [t   {:name :t2 :source {:type :notion-view :database "x"} :skill :s
              :payload ""}
        tbp {:p [t]}
        env {:broadcast {:type :notion-view
                         :source-config {:database "x"}
                         :payload {:status "Open"}}}]
    (is (= 0 (-> (events/route env tbp) first :priority)))))

(deftest route-direct-carries-trigger-priority
  (let [t   {:name :inv :source {:type :manual} :skill :investigate-bug
             :payload "{{event/url}}" :priority 5}
        tbp {:brian [t]}]
    (is (= 5 (-> (events/route
                   {:target  {:project :brian :trigger :inv}
                    :payload {:url "u"}}
                   tbp)
                 first :priority)))))

(deftest route-uses-trigger-session-profile-when-set
  (let [t   {:name :t :source {:type :notion-view :database "x"} :skill :s
             :payload "" :session-profile :lite}
        tbp {:p [t]}
        env {:broadcast {:type :notion-view
                         :source-config {:database "x"}
                         :payload {}}}
        requests (events/route env tbp)]
    (is (= :lite (-> requests first :session-profile)))))

(deftest route-direct-carries-trigger-session-profile
  (let [t   {:name :inv :source {:type :manual} :skill :investigate-bug
             :payload "{{event/url}}" :session-profile :lite}
        tbp {:brian [t]}]
    (is (= :lite (-> (events/route
                       {:target  {:project :brian :trigger :inv}
                        :payload {:url "u"}}
                       tbp)
                     first :session-profile)))))

(deftest route-event-priority-overrides-trigger-priority
  (let [t   {:name :t1 :source {:type :notion-view :database "x"} :skill :s
             :payload "" :priority 5}
        tbp {:p [t]}
        env {:broadcast {:type :notion-view
                         :source-config {:database "x"}
                         :payload {:page-id "p" :priority 100}}}
        requests (events/route env tbp)]
    (is (= 100 (-> requests first :priority))
        "event-level :priority should win over trigger-level :priority")))

(deftest route-propagates-uncapped
  (let [trigger  {:name :investigate-bug :source {:type :manual}
                  :skill :investigate-bug :payload "{{event/url}}"
                  :uncapped? true}
        tbp      {:brian [trigger]}
        req      (events/route
                   {:target {:project :brian :trigger :investigate-bug}
                    :payload {:url "u"}}
                   tbp)]
    (is (= true (-> req first :uncapped?)))))
