(ns nido.coordinator.source.registry-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.source.registry :as sources]))

(defn- reset-registry! [] (reset! (var-get #'nido.coordinator.source.registry/!registry) {}))

(deftest register-and-lookup-round-trip
  (reset-registry!)
  (sources/register-source! {:type    :test-src
                             :schema  [:map [:foo string?]]
                             :events  [:map [:bar string?]]
                             :start!  (fn [_ _] {:poll! (fn []) :stop! (fn [])})})
  (let [src (sources/lookup :test-src)]
    (is (= [:map [:foo string?]] (:schema src)))
    (is (= [:map [:bar string?]] (:events src)))
    (is (fn? (:start! src)))))

(deftest lookup-nil-for-unknown
  (reset-registry!)
  (is (nil? (sources/lookup :no-such-source))))

(deftest config-hash-is-deterministic
  (is (= (sources/config-hash {:database "abc" :view "v" :poll "5m"})
         (sources/config-hash {:poll "5m" :view "v" :database "abc"}))))

(deftest config-hash-strips-type
  (is (= (sources/config-hash {:database "abc" :poll "5m"})
         (sources/config-hash {:type :notion-view :database "abc" :poll "5m"}))))

(deftest config-hash-differs-for-different-configs
  (is (not= (sources/config-hash {:database "a"})
            (sources/config-hash {:database "b"}))))

(deftest config-hash-is-12-hex-chars
  (is (re-matches #"[0-9a-f]{12}" (sources/config-hash {:database "abc"}))))
