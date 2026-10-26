(ns nido.session.run-link-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.session.run-link :as run-link]))

(deftest a-runs-session-home-links-to-its-run-and-carries-no-claude-shim
  ;; A session's skills reach the Run's status and artifacts through run-link; a person enters a
  ;; background session by attaching, so the bin/claude an older home carried is removed.
  (let [tmp  (fs/create-temp-dir)
        home (str (fs/path tmp "home"))
        run  (str (fs/path tmp "run"))
        shim (fs/path home "bin" "claude")]
    (try
      (fs/create-dirs (fs/parent shim))
      (fs/create-dirs run)
      (spit (str shim) "#!/usr/bin/env bash\nexec claude --resume x\n")
      (fs/create-sym-link (fs/path home "run-link") (str tmp))
      (run-link/write! home run)
      (is (= (str (fs/canonicalize run)) (str (fs/canonicalize (fs/path home "run-link"))))
          "run-link points at the Run, replacing a link left from before")
      (is (not (fs/exists? shim)))
      (is (= run (run-link/run-dir home)) "and a restart reads the Run back off it")
      (finally (fs/delete-tree tmp)))))

(deftest a-home-with-no-run-link-names-no-run
  (let [tmp (fs/create-temp-dir)]
    (try (is (nil? (run-link/run-dir (str tmp))))
         (finally (fs/delete-tree tmp)))))
