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
          (is (str/includes? content "run-link/run.edn")))
        (let [link (str (fs/path session-home "run-link"))]
          (is (fs/sym-link? link))
          (is (= (str (fs/canonicalize run-dir))
                 (str (fs/canonicalize link))))))
      (finally (fs/delete-tree tmp)))))
