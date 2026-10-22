(ns nido.session.state-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.platform.io :as io]
   [nido.session.state :as state]))

(deftest shared-pg-paths-are-under-project-shared-dir
  (let [proj "brian"]
    (is (str/ends-with? (state/project-shared-dir proj) "/shared/brian"))
    (is (str/ends-with? (state/shared-pg-data-dir proj) "/shared/brian/pg-data"))
    (is (str/ends-with? (state/shared-log-file proj) "/shared/brian/pg.log"))
    (is (str/ends-with? (state/shared-meta-file proj) "/shared/brian/shared.edn"))
    (is (str/ends-with? (state/shared-lock-file proj) "/shared/brian/shared.lock"))))

(deftest remove-from-registry-also-prunes-legacy-files
  ;; read-registry merges the .codex registries UNDER the canonical file, but
  ;; write-registry! only rewrites the canonical one — so without pruning the
  ;; legacy file too, a legacy-only key comes straight back on the next read.
  (let [tmp    (fs/create-temp-dir)
        legacy (str (fs/path tmp "legacy.edn"))
        canon  (str (fs/path tmp "sessions.edn"))
        k      "/gone/worktree"]
    (try
      (io/write-edn! legacy {k {:instance-id "ghost" :app-port 4938}})
      (io/write-edn! canon {"/live/worktree" {:instance-id "real" :app-port 3000}})
      ;; Fully-qualified symbols, not the `state` alias and not #'— with-redefs
      ;; wraps each name in (var …), which reaches these private vars fine.
      (with-redefs [nido.session.state/legacy-registry-paths (constantly [legacy])
                    nido.session.state/registry-file-path    (delay canon)]
        (is (contains? (state/read-registry) k) "precondition: the legacy key reads back")
        (state/remove-from-registry! k)
        (is (not (contains? (state/read-registry) k))
            "the legacy key is gone and stays gone across a re-read")
        (is (contains? (state/read-registry) "/live/worktree")
            "unrelated canonical entries survive"))
      (finally (fs/delete-tree tmp)))))

(deftest the-worktree-record-outlives-the-session-record
  ;; A down session's state dir must still say whose it is, or the reclaim cannot tell a down
  ;; session from a gone one.
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [state/state-dir (constantly (str tmp))]
        (state/write-session! "p--a" {:project-dir "/w/a"})
        (is (= "/w/a" (state/read-instance-worktree "p--a")))
        (state/delete-session! "p--a")
        (is (= "/w/a" (state/read-instance-worktree "p--a")) "left behind by the delete")
        (fs/create-dirs (fs/path tmp "p--legacy"))
        (io/write-edn! (state/session-state-file "p--legacy") {:project-dir "/w/legacy"})
        (is (= "/w/legacy" (state/read-instance-worktree "p--legacy"))
            "a dir from before the record existed is read from its session record")
        (state/delete-session! "p--legacy")
        (is (= "/w/legacy" (state/read-instance-worktree "p--legacy"))
            "and its delete writes the record first")
        (is (nil? (state/read-instance-worktree "p--none"))))
      (finally (fs/delete-tree tmp)))))

(deftest distinct-keys-keep-distinct-ids
  (is (not= (state/keyed-id "p" "a/b") (state/keyed-id "p" "a~b")))
  (is (not= (state/keyed-id "p" "a~b") (state/keyed-id "p" "a%7Eb")))
  (is (= "p--feat~x" (state/keyed-id "p" "feat/x")) "a session name is spelled as it always was")
  (is (= "p--ws-1" (state/keyed-id "p" "ws-1"))))
