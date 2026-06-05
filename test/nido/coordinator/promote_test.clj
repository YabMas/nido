(ns nido.coordinator.promote-test
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.promote :as promote]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.tickets :as tickets]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [cstate/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f tmp))
         (finally (fs/delete-tree tmp)))))

(defn- queued-envelopes []
  (->> (fs/list-dir (cstate/queue-dir))
       (filter #(re-matches #".*\.edn$" (str (fs/file-name %))))
       (map #(edn/read-string (slurp (str %))))))

(deftest promote-refuses-non-triaged
  (with-tmp
    (fn [_]
      (is (= {:decision :skip-no-record} (promote/promote! :brian "BR-NONE")))
      (is (empty? (queued-envelopes))))))

(deftest promote-enqueues-direct-target-and-marks-planning
  (with-tmp
    (fn [_]
      (tickets/open! :brian "BR-7" {:notion-page-id "PG7" :url "U7" :title "T7"
                                    :opened-by :triage-new :notion-last-edited-at "t"})
      (tickets/complete! :brian "BR-7" :triaged :applied)
      (let [res (promote/promote! :brian "BR-7")]
        (is (= :promote (:decision res)))
        (is (string? (:queued res)))
        (is (= :planning (tickets/status :brian "BR-7")) "gate flips to :planning")
        (let [[env] (queued-envelopes)]
          (is (= {:project :brian :trigger :plan-bug} (:target env)))
          (is (= "BR-7"  (-> env :payload :id)))
          (is (= "PG7"   (-> env :payload :notion-page-id)))
          (is (= "T7"    (-> env :payload :title)))))
      ;; second promote now refused (already :planning) and enqueues nothing more
      (is (= {:decision :skip-active} (promote/promote! :brian "BR-7")))
      (is (= 1 (count (queued-envelopes)))))))
