(ns nido.coordinator.launchctl-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.launchctl :as lc]))

(defn- with-tmp-home [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [lc/launch-agents-dir (constantly (str tmp))]
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(deftest plist-path-lives-under-launch-agents
  (with-tmp-home
    (fn [tmp]
      (is (= (str (fs/path tmp "dev.nido.coordinator.plist"))
             (lc/plist-path))))))

(deftest label-is-stable
  (is (= "dev.nido.coordinator" (lc/label))))

(deftest installed?-false-when-no-plist
  (with-tmp-home
    (fn [_] (is (false? (lc/installed?))))))

(deftest installed?-true-when-plist-file-exists
  (with-tmp-home
    (fn [_]
      (spit (lc/plist-path) "stub")
      (is (true? (lc/installed?))))))
