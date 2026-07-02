(ns tasks.nido-shared-pg-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.session.engine :as engine]
   [nido.shared-pg :as shared]
   [tasks.nido-shared-pg :as task]))

(deftest up-passes-role-and-source-opts-to-ensure-ready
  (let [got (atom nil)]
    (with-redefs [shared/ensure-ready! (fn [proj opts] (reset! got [proj opts]) {:port 1})
                  engine/load-session-edn
                  (fn [_] {:services [{:type :postgresql :db-name "brian" :db-user "user"
                                       :schema "brian" :app-db-user "brian_app"
                                       :app-db-password "app"}]})
                  task/project-source-dir (fn [_] "/x/Code/brian")]
      (task/up ":project" "brian")
      (let [[proj opts] @got]
        (is (= "brian" proj))
        (is (= "brian_app" (:app-user opts)))
        (is (= "app" (:app-password opts)))
        (is (= "user" (:owner-user opts)))
        (is (= "brian" (:db-name opts)))
        (is (= "brian" (:schema opts)))
        (is (= "/x/Code/brian" (:source-repo opts)))))))

(deftest reset-resets-then-passes-role-and-source-opts-to-ensure-ready
  (let [reset-called (atom nil)
        got (atom nil)]
    (with-redefs [shared/reset! (fn [proj] (reset! reset-called proj) {:port 1})
                  shared/ensure-ready! (fn [proj opts] (reset! got [proj opts]) {:port 1})
                  engine/load-session-edn
                  (fn [_] {:services [{:type :postgresql :db-name "brian" :db-user "user"
                                       :schema "brian" :app-db-user "brian_app"
                                       :app-db-password "app"}]})
                  task/project-source-dir (fn [_] "/x/Code/brian")]
      (task/reset ":project" "brian")
      (is (= "brian" @reset-called) "reset! must run before ensure-ready!")
      (let [[proj opts] @got]
        (is (= "brian" proj))
        (is (= "brian_app" (:app-user opts)))
        (is (= "/x/Code/brian" (:source-repo opts)))))))
