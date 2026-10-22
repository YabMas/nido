(ns tasks.nido-test-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.config :as config]
   [nido.platform.core :as core]
   [tasks.nido-test :as nido-test]))

(def ^:private real-home
  (str (fs/path (System/getProperty "user.home") ".nido")))

(deftest this-suite-runs-in-the-home-made-for-it
  ;; The claim the runner exists for, checked from inside the run it governs.
  (is (= (System/getenv "NIDO_TEST_HOME") (core/nido-home)))
  (is (not= (str (fs/canonicalize real-home)) (str (fs/canonicalize (core/nido-home)))))
  (is (= #{"brian" "nido"} (set (keys (config/read-projects))))
      "the registry the tests see is the fixture's, not the machine's")
  (is (fs/starts-with? (fs/canonicalize (System/getenv "CODEX_HOME"))
                       (fs/canonicalize (core/nido-home)))
      "the legacy session registry is read from inside the test home")
  (is (fs/starts-with? (fs/canonicalize (fs/create-temp-dir))
                       (fs/canonicalize (core/nido-home)))
      "a temp home a test makes for itself is inside the run's, and removed with it"))

(deftest home-refusal
  (let [tmp (fs/create-temp-dir)
        home (str tmp)]
    (try
      (testing "a home bb nido:test did not make is refused, even when named as the run's"
        (is (re-find #"was not made by bb nido:test" (nido-test/home-refusal home home))))
      (testing "the marked home made for the run is accepted"
        (spit (str (fs/path tmp ".nido-test-home")) "")
        (is (nil? (nido-test/home-refusal home home))))
      (testing "no home was made — the suite was not started through bb nido:test"
        (is (re-find #"no test home" (nido-test/home-refusal nil home))))
      (testing "NIDO_HOME was pointed somewhere other than the home made for the run"
        (is (re-find #"is not the one made" (nido-test/home-refusal home real-home))))
      (testing "the real home is refused by name, marker or not"
        (is (re-find #"is the real one" (nido-test/home-refusal real-home real-home))))
      (finally (fs/delete-tree tmp)))))
