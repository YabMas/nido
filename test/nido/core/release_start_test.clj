(ns nido.core.release-start-test
  (:require [clojure.test :refer [deftest is]]
            [nido.core.release :as release]))

(deftest a-start-takes-back-a-turn-running-on-a-closed-workstream
  ;; A landing turn's own land:record closes its workstream while the turn still runs; a start
  ;; that took back only open workstreams left that turn reading running forever.
  (let [wss [{:ws "open-idle"      :state {:closed? false :turns {"t" {:status :completed}}}}
             {:ws "open-running"   :state {:closed? false :turns {"t" {:status :running}}}}
             {:ws "closed-running" :state {:closed? true  :turns {"t" {:status :completed}
                                                                  "u" {:status :running}}}}
             {:ws "closed-idle"    :state {:closed? true  :turns {}}}]]
    (is (= ["open-running" "closed-running"] (map :ws (#'release/with-running-turns wss))))))
