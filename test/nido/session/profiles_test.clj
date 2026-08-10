(ns nido.session.profiles-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.io :as io]
   [nido.session.profiles :as profiles]
   [nido.coordinator.state :as cstate]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (fs/create-dirs (str (fs/path tmp "projects" "brian")))
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(deftest resolve-returns-builtin-full-when-no-registry-file
  (with-tmp
    (fn [_]
      (let [p (profiles/resolve-profile :brian :full)]
        (is (= :all (:services p)))
        (is (= :git-worktree (-> p :worktree :strategy)))))))

(deftest resolve-reads-registry-when-present
  (with-tmp
    (fn [tmp]
      (let [path (str (fs/path tmp "projects" "brian" "session-profiles.edn"))]
        (io/write-edn! path
          {:profiles {:lite {:services []
                             :worktree {:strategy :symlink :target "/tmp/x"}}}})
        (let [p (profiles/resolve-profile :brian :lite)]
          (is (= [] (:services p)))
          (is (= :symlink (-> p :worktree :strategy)))
          (is (= "/tmp/x" (-> p :worktree :target))))))))

(deftest profile-weight-classifies-provisioning
  (is (= :heavy (profiles/profile-weight {:services :all})))
  (is (= :heavy (profiles/profile-weight {:services [:postgresql]})))
  (is (= :light (profiles/profile-weight {:services []}))
      "the :lite shape provisions nothing runnable")
  (is (nil? (profiles/profile-weight nil))
      "no persisted profile ⇒ provisioning UNKNOWN, never a guess"))

(deftest resolve-unknown-profile-throws
  (with-tmp
    (fn [_]
      (is (thrown? clojure.lang.ExceptionInfo
                   (profiles/resolve-profile :brian :nope))))))

(deftest resolve-expands-tilde-in-symlink-target
  (with-tmp
    (fn [tmp]
      (let [path (str (fs/path tmp "projects" "brian" "session-profiles.edn"))]
        (io/write-edn! path
          {:profiles {:lite {:services []
                             :worktree {:strategy :symlink :target "~/Code/brian"}}}})
        (let [p (profiles/resolve-profile :brian :lite)]
          (is (.startsWith (-> p :worktree :target) (System/getProperty "user.home"))
              "leading ~ should be expanded to the user's home dir"))))))
