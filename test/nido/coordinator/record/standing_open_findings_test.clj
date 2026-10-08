(ns nido.coordinator.record.standing-open-findings-test
  "Open findings are a round's items no later resolution names — read off the ledger."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.record.state :as cstate]
   [nido.platform.core :as core]
   [nido.platform.io :as io]
   [nido.coordinator.record.standing :as standing]
   [nido.coordinator.record.workstream :as ws]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root (constantly (str tmp))] (f))
         (finally (fs/delete-tree tmp)))))

(defn- round! [id n ids]
  (ws/append-entry! :brian id {:kind :findings}
                    (pr-str {:format :findings :round n
                             :items (mapv (fn [i] {:id i :summary i :severity :tweak}) ids)})))

(defn- resolve! [id n ids]
  (ws/append-entry! :brian id {:kind :findings-resolved}
                    (pr-str {:format :findings-resolved :round n :items ids :by "PR #1"})))

(deftest a-round-owes-its-items-until-each-is-resolved
  (with-tmp
    (fn []
      (let [id (:id (ws/create! :brian {:stage :in-progress}))]
        (is (= #{} (standing/open-findings :brian id)) "no round, nothing owed")
        (round! id 1 ["f1" "f2" "f3"])
        (is (= #{"f1" "f2" "f3"} (standing/open-findings :brian id)))
        (resolve! id 1 ["f1" "f3"])
        (is (= #{"f2"} (standing/open-findings :brian id)))
        (resolve! id 1 ["f2"])
        (is (= #{} (standing/open-findings :brian id)))))))

(deftest only-the-newest-round-counts
  (with-tmp
    (fn []
      (let [id (:id (ws/create! :brian {:stage :in-progress}))]
        (round! id 1 ["a"])
        (round! id 2 ["b" "c"])
        (testing "a resolution naming the older round answers nothing in the newer"
          (resolve! id 1 ["b"])
          (is (= #{"b" "c"} (standing/open-findings :brian id))))))))

(deftest an-unreadable-newest-round-does-not-revive-the-older
  (with-tmp
    (fn []
      (let [id (:id (ws/create! :brian {:stage :in-progress}))]
        (round! id 1 ["a"])
        (round! id 2 ["b"])
        (let [n (:seq (last (filter #(= :findings (:kind %)) (:entries (ws/read-ws :brian id)))))]
          (io/write-text! (str (fs/path (cstate/workstream-dir :brian id)
                                        (format "entries/%04d-findings.edn" n)))
                          "{:format :findings :this-will-not"))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"could not be read"
                              (standing/open-findings :brian id)))))))
