(ns nido.github.config-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.state :as cstate]
   [nido.github.config :as gh-config]
   [nido.io :as io]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [cstate/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f tmp))
         (finally (fs/delete-tree tmp)))))

(deftest load-config-absent-returns-nil
  (with-tmp
    (fn [_]
      (is (nil? (gh-config/load-config :brian)) "no github.edn ⇒ nil (feature off)"))))

(deftest load-config-reads-edn
  (with-tmp
    (fn [tmp]
      (let [dir (fs/path (str tmp) "projects" "brian")]
        (fs/create-dirs dir)
        (io/write-edn! (str (fs/path dir "github.edn"))
                       {:repo "brian-study/brian"
                        :poll "5m"
                        :on-merge {:notion-status "Code Review"
                                   :remove-ball-holder "uid-1"}}))
      (let [c (gh-config/load-config :brian)]
        (is (= "brian-study/brian" (:repo c)))
        (is (= "5m" (:poll c)))
        (is (= "Code Review" (-> c :on-merge :notion-status)))))))

(deftest load-config-rejects-missing-repo
  (with-tmp
    (fn [tmp]
      (let [dir (fs/path (str tmp) "projects" "brian")]
        (fs/create-dirs dir)
        (io/write-edn! (str (fs/path dir "github.edn")) {:poll "5m"}))
      (is (thrown-with-msg? Exception #"github.edn"
            (gh-config/load-config :brian))))))
