(ns nido.template-test
  "Tests for the snapshot+rollback guard around `refresh!`: a failed refresh
   (e.g. the staging fetch throws) must never leave the template destroyed."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.template :as tpl]))

(defn- with-temp-template
  "Build a stopped fake template data-dir (PG_VERSION + a content marker) under
   a fresh temp dir and run (f data-dir backup-dir), cleaning up afterwards."
  [f]
  (let [tmp (fs/create-temp-dir {:prefix "nido-tpl-test"})
        data-dir (str (fs/path tmp "pg-data"))
        backup-dir (str data-dir ".refresh-bak")
        marker (str (fs/path data-dir "marker"))]
    (try
      (fs/create-dirs data-dir)
      (spit (str (fs/path data-dir "PG_VERSION")) "17")
      (spit marker "ORIGINAL")
      (f data-dir backup-dir marker)
      (finally
        (fs/delete-tree tmp)))))

(deftest rollback-discards-snapshot-and-keeps-result-on-success
  (with-temp-template
    (fn [data-dir backup-dir marker]
      (let [ret (#'tpl/with-template-rollback
                 data-dir
                 (fn [] (spit marker "REFRESHED") :ok))]
        (is (= :ok ret) "returns the thunk's value")
        (is (not (fs/exists? backup-dir)) "snapshot is deleted on success")
        (is (= "REFRESHED" (slurp marker)) "the refreshed content is kept")))))

(deftest rollback-restores-template-and-rethrows-on-failure
  (with-temp-template
    (fn [data-dir backup-dir marker]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"fetch boom"
           (#'tpl/with-template-rollback
            data-dir
            (fn []
              ;; the destructive reset wrecks the live template ...
              (spit marker "WRECKED")
              ;; ... and then the fetch step fails
              (throw (ex-info "fetch boom" {})))))
          "the original failure propagates")
      (is (= "ORIGINAL" (slurp marker))
          "the template is restored from the pre-refresh snapshot")
      (is (not (fs/exists? backup-dir)) "the snapshot is consumed by the restore"))))

(deftest rollback-aborts-before-running-thunk-if-snapshot-cannot-be-made
  ;; data-dir without a PG_VERSION → clone-pgdata! refuses. The destructive
  ;; thunk must never run, so nothing can be destroyed.
  (let [tmp (fs/create-temp-dir {:prefix "nido-tpl-test"})
        data-dir (str (fs/path tmp "pg-data"))
        ran? (atom false)]
    (try
      (fs/create-dirs data-dir)
      (is (thrown? Exception
                   (#'tpl/with-template-rollback
                    data-dir
                    (fn [] (reset! ran? true) :ok))))
      (is (false? @ran?)
          "the thunk must not run when the protective snapshot can't be made")
      (finally
        (fs/delete-tree tmp)))))
