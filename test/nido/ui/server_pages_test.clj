(ns nido.ui.server-pages-test
  "The dashboard's page list is what a census measures, so every page on it must be one
   `handle-get` renders — and every parameterised one must name a concrete instance."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.platform.core :as core]
   [nido.ui.server :as server]
   [nido.ui.structure :as structure]))

(defn- with-home [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))
                    server/read-rail-daemon (constantly {:state :up})
                    ;; The structure page starts fukan's explorer over the project's main.
                    structure/ensure! (constantly {:status :unmodelled})]
        (cstate/ensure-dirs!)
        (f))
      (finally (fs/delete-tree tmp)))))

(deftest every-listed-page-renders
  (with-home
    (fn []
      (let [w (ws/create! :nido {:external-refs []})
            {:keys [pages left-out]} (server/page-routes)]
        (is (seq left-out) "the routes it leaves out are named")
        (is (every? #(and (:route %) (not (str/blank? (:why %)))) left-out) "each with why")
        (doseq [{:keys [path instance found-by]} pages]
          (let [uri (if instance (instance {:project "nido"}) path)]
            (testing path
              (when instance
                (is (string? found-by) "a parameterised page says how its instance is found")
                (is (= (str "/workstreams/nido/" (:id w)) uri)))
              (let [resp (server/handle-request {:request-method :get :uri uri})]
                (is (= 200 (:status resp)) (str uri " renders"))
                (is (str/includes? (str (get-in resp [:headers "Content-Type"])) "text/html"))))))))))
