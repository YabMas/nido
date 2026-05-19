(ns nido.tasks.nido-notion-preprocess-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.notion.client :as notion]
   [nido.notion.preprocess :as pp]
   [tasks.nido-notion-preprocess :as task]))

(deftest run-requires-page-and-out
  (let [exit (atom nil)]
    (with-redefs [task/exit! (fn [c] (reset! exit c))]
      (binding [*err* (java.io.StringWriter.)]
        (task/run))
      (is (= 2 @exit)))))

(deftest run-calls-preprocess-with-keychain-token
  (let [tmp (fs/create-temp-dir)
        out (str (fs/path tmp "out"))
        captured (atom nil)]
    (fs/create-dirs out)
    (with-redefs [notion/keychain-token (constantly "secret-tok")
                  pp/preprocess-ticket! (fn [opts]
                                          (reset! captured opts)
                                          (spit (str (fs/path out "manifest.edn"))
                                                "{:videos []}")
                                          {:ok? true :manifest {:videos []}})]
      (let [stdout (with-out-str (task/run ":page" "page-id-1" ":out" out))]
        (is (= "secret-tok" (:token @captured)))
        (is (= "page-id-1" (:page-id @captured)))
        (is (= 600 (:budget-s @captured)))
        (is (re-find #"manifest.edn" stdout))))
    (fs/delete-tree tmp)))

(deftest run-exits-nonzero-on-preprocessor-failure
  (let [exit (atom nil)]
    (with-redefs [notion/keychain-token (constantly "tok")
                  pp/preprocess-ticket! (fn [_]
                                          {:ok? false
                                           :error {:reason :notion-auth}})
                  task/exit!            (fn [c] (reset! exit c))]
      (binding [*err* (java.io.StringWriter.)]
        (task/run ":page" "p1" ":out" "/tmp/x"))
      (is (= 1 @exit)))))

(deftest run-parses-budget-duration
  (let [captured (atom nil)]
    (with-redefs [notion/keychain-token (constantly "tok")
                  pp/preprocess-ticket! (fn [opts]
                                          (reset! captured opts)
                                          {:ok? true :manifest {:videos []}})]
      (with-out-str (task/run ":page" "p1" ":out" "/tmp/x" ":budget" "15m"))
      (is (= 900 (:budget-s @captured))))))
