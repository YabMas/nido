(ns nido.platform.secrets-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.platform.secrets :as secrets]))

(deftest a-host-without-a-keychain-keeps-each-secret-in-an-owner-only-file
  (let [home (str (fs/create-temp-dir))]
    (try
      (with-redefs [core/nido-root (constantly home)
                    secrets/keychain? (constantly false)]
        (testing "nothing stored reads as nil"
          (is (nil? (secrets/read-secret "nido-notion"))))
        (is (zero? (:exit (secrets/store-secret! "nido-notion" "tok-1"))))
        (is (= "tok-1" (secrets/read-secret "nido-notion")))
        (testing "a second store replaces the first"
          (secrets/store-secret! "nido-notion" "tok-2\n")
          (is (= "tok-2" (secrets/read-secret "nido-notion"))))
        (testing "readable by its owner alone"
          (is (= "rwx------" (fs/posix->str (fs/posix-file-permissions (fs/path home "secrets")))))
          (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions (fs/path home "secrets" "nido-notion")))))))
      (finally (fs/delete-tree home)))))

(deftest on-macos-a-secret-is-a-keychain-generic-password
  (let [calls (atom [])]
    (with-redefs [secrets/keychain? (constantly true)
                  secrets/sh! (fn [args] (swap! calls conj args) {:exit 0 :out " tok \n" :err ""})]
      (is (= "tok" (secrets/read-secret "nido-slack")))
      (is (= ["security" "find-generic-password" "-s" "nido-slack"] (take 4 (first @calls)))))))
