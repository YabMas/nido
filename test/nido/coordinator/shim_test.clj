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

(deftest shim-only-resumes-no-first-enter-marker
  ;; The promote leg now launches /continue-ticket headlessly at provision time
  ;; (see core/run-blocking!), so the human's first `claude` just resumes that
  ;; conversation. The shim carries no .continue-on-first-enter marker branch.
  (let [tmp          (fs/create-temp-dir)
        session-home (str (fs/path tmp "sess"))
        run-dir      (str (fs/path tmp "run"))]
    (try
      (fs/create-dirs session-home)
      (fs/create-dirs run-dir)
      (shim/write! session-home run-dir)
      (let [content (slurp (str (fs/path session-home "bin" "claude")))]
        (is (str/includes? content "claude --resume")
            "resumes the pre-generated session-id on entry")
        (is (not (str/includes? content ".continue-on-first-enter"))
            "no first-enter marker branch — superseded by the headless burst")
        (is (not (str/includes? content "claude --session-id"))
            "the shim never starts a fresh session; the coordinator launched it"))
      (finally (fs/delete-tree tmp)))))
