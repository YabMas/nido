(ns nido.core.provision-test
  (:require [clojure.test :refer [deftest is]]
            [nido.core.provision :as provision]))

(deftest the-session-verb-is-told-the-units-profile
  (let [argv (fn [a] (provision/verb-argv {:nido-dir "/n"} (merge {:unit "u" :want :up :project "brian"} a)))]
    (is (= ["bb" "--config" "/n/bb.edn" "nido:session:provision" ":project" "brian" ":want" "up" "u"]
           (argv {})))
    (is (= [":profile" "lite"] (take-last 2 (argv {:profile :lite}))))))
