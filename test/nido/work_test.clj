(ns nido.work-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.session :as session]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.tickets :as tickets]
   [nido.coordinator.workstream :as workstream]
   [nido.work :as work]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(deftest stages-is-the-canonical-spine
  (is (= [:intake :triage :ready :in-progress :done] work/stages)))

(deftest classify-origin-delegates-to-source-classifier
  (is (= :scratch (work/classify-origin {:stage :scratch :external-refs []})))
  (is (= :notion  (work/classify-origin {:stage :triaging
                                         :external-refs [{:adapter :notion :id "BR-1"}]})))
  (is (= :github  (work/classify-origin {:stage :ready
                                         :external-refs [{:adapter :github-issue :id "o/r#1"}]})))
  (is (= :slack   (work/classify-origin {:stage :triaging
                                         :external-refs [{:adapter :slack-message :id "slack-C1-1.0"}]}))))
