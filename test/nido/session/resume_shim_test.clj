(ns nido.session.resume-shim-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [babashka.process]
   [clojure.test :refer [deftest is]]
   [nido.session.resume-shim :as shim]))

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

(defn- run-shim
  "Run the shim in a session home whose run's session record is `record` (nil: none on disk),
   with a fake `claude` that records its arguments and lists `listed` sessions. Answers
   {:exit :args} — :args the arguments the last `claude` other than `agents` was given."
  [record listed]
  (let [tmp     (fs/create-temp-dir)
        root    (fs/path tmp "nido")
        run-dir (fs/path root "runs" "r1")
        home    (fs/path tmp "sess")
        fakebin (fs/path tmp "fakebin")
        argfile (str (fs/path tmp "args"))]
    (try
      (fs/create-dirs run-dir)
      (fs/create-dirs home)
      (fs/create-dirs fakebin)
      (spit (str (fs/path run-dir "run.edn"))
            (pr-str {:project :brian :workstream-id "ws-1" :session-name "feat/s"
                     :claude-session-id "0123456789abcdef"}))
      (when record
        (let [d (fs/path root "projects" "brian" "workstreams" "ws-1" "sessions" "feat%2Fs")]
          (fs/create-dirs d)
          (spit (str (fs/path d "session.edn")) (pr-str record))))
      ;; The shim reads the session record with `bb -e` — that goes to the real bb, named by the
      ;; binary running this test: a version-manager shim found on PATH would resolve `bb`
      ;; through PATH again, find this fake first, and loop. Only the attach the shim hands a
      ;; background session to is recorded.
      (spit (str (fs/path fakebin "bb"))
            (str "#!/usr/bin/env bash\n"
                 "if [ \"$1\" = -e ]; then exec "
                 (.get (.command (.info (java.lang.ProcessHandle/current)))) " \"$@\"; fi\n"
                 "echo \"bb $*\" > " argfile "\n"))
      (fs/set-posix-file-permissions (fs/path fakebin "bb") "rwxr-xr-x")
      (spit (str (fs/path fakebin "claude"))
            (str "#!/usr/bin/env bash\n"
                 "if [ \"$1\" = agents ]; then echo '" (pr-str listed) "'; exit 0; fi\n"
                 "echo \"$*\" > " argfile "\n"))
      (fs/set-posix-file-permissions (fs/path fakebin "claude") "rwxr-xr-x")
      (shim/write! (str home) (str run-dir))
      (let [{:keys [exit]} (deref (babashka.process/process
                                   {:extra-env {"PATH" (str fakebin ":" (System/getenv "PATH"))}
                                    :out :string :err :string}
                                   (str (fs/path home "bin" "claude"))))]
        {:exit exit :args (when (fs/exists? argfile) (str/trim (slurp argfile)))})
      (finally (fs/delete-tree tmp)))))

(deftest the-session-record-decides-how-the-shim-continues
  (let [{:keys [exit args]} (run-shim {:name "feat/s" :claude {:session-id "0123456789abcdef"}} [])]
    (is (= 0 exit))
    (is (re-find #"^bb --config \S+/bb\.edn nido:session:attach :session-id 0123456789abcdef$" args)
        "a background session is attached to through the substrate adapter, never resumed"))
  (is (= {:exit 0 :args "--resume 0123456789abcdef --dangerously-skip-permissions"}
         (run-shim {:name "feat/s"} []))
      "only a record without a facet resumes the old way")
  (is (= {:exit 1 :args nil} (run-shim nil []))
      "a record that cannot be read is not taken for an old one"))
