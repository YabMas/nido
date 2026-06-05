(ns nido.coordinator.shim-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.shim :as shim]))

(deftest write-shim!-creates-executable-script-and-symlink
  (let [tmp           (fs/create-temp-dir)
        session-home  (str (fs/path tmp "sess"))
        run-dir       (str (fs/path tmp "run"))]
    (try
      (fs/create-dirs session-home)
      (fs/create-dirs run-dir)
      (shim/write! session-home run-dir)
      (let [shim-path (str (fs/path session-home "bin" "claude"))]
        (is (fs/exists? shim-path))
        (is (fs/executable? shim-path))
        (let [content (slurp shim-path)]
          (is (str/includes? content "claude --resume"))
          (is (str/includes? content "run-link/run.edn"))
          (is (str/includes? content "--dangerously-skip-permissions")
              "resumes Run-owned sessions with full permissions, matching the autonomous launch"))
        (let [link (str (fs/path session-home "run-link"))]
          (is (fs/sym-link? link))
          (is (= (str (fs/canonicalize run-dir))
                 (str (fs/canonicalize link))))))
      (finally (fs/delete-tree tmp)))))

(deftest shim-has-continue-on-first-enter-branch
  (let [tmp          (fs/create-temp-dir)
        session-home (str (fs/path tmp "sess"))
        run-dir      (str (fs/path tmp "run"))]
    (try
      (fs/create-dirs session-home)
      (fs/create-dirs run-dir)
      (shim/write! session-home run-dir)
      (let [content (slurp (str (fs/path session-home "bin" "claude")))]
        ;; first-enter marker branch launches /continue-ticket under --session-id
        (is (str/includes? content ".continue-on-first-enter"))
        (is (str/includes? content "/continue-ticket"))
        (is (str/includes? content "claude --session-id"))
        ;; still resumes by session-id when there's no marker
        (is (str/includes? content "claude --resume")))
      (finally (fs/delete-tree tmp)))))

(deftest mark-continue-on-first-enter-writes-marker
  (let [tmp          (fs/create-temp-dir)
        session-home (str (fs/path tmp "sess"))]
    (try
      (fs/create-dirs session-home)
      (is (not (fs/exists? (str (fs/path session-home ".continue-on-first-enter")))))
      (shim/mark-continue-on-first-enter! session-home)
      (is (fs/exists? (str (fs/path session-home ".continue-on-first-enter"))))
      (finally (fs/delete-tree tmp)))))
