(ns nido.reclaim-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.reclaim :as reclaim]
   [nido.session.state :as state]))

(defn- mk-dir! [root id mtime-ms]
  (let [d (str (fs/path root id))]
    (fs/create-dirs d)
    (fs/set-last-modified-time d mtime-ms)
    d))

(deftest reclaim-orphans-respects-age-grace-and-registry
  ;; The age grace is the load-bearing safety property: a session's state dir
  ;; exists for the whole boot (PGDATA clone + app migrate) BEFORE its registry
  ;; entry is written, so a young, untracked dir may be a live boot in flight
  ;; and must NOT be deleted. Only old + untracked dirs are reclaimed.
  (let [tmp     (fs/create-temp-dir)
        now     1000000000000              ; fixed clock for the test
        hour    (* 60 60 1000)
        tracked-dir (mk-dir! tmp "tracked"      (- now (* 2 hour)))  ; old but registered
        old-orph    (mk-dir! tmp "old-orphan"   (- now (* 2 hour)))  ; old + untracked → reclaim
        young-orph  (mk-dir! tmp "young-orphan" now)]                ; untracked but mid-boot → keep
    (try
      (with-redefs [state/state-dir     (constantly (str tmp))
                    state/read-registry (constantly {"/proj" {:instance-id "tracked"}})]
        (let [deleted (reclaim/reclaim-orphans! {:min-age-ms hour :now-ms now})]
          (is (= ["old-orphan"] (mapv first deleted))
              "only the old, untracked dir is reclaimed")
          (is (not (fs/exists? old-orph))   "old orphan deleted")
          (is (fs/exists? young-orph)       "young orphan (possible live boot) preserved")
          (is (fs/exists? tracked-dir)      "registered session preserved regardless of age")))
      (finally (fs/delete-tree tmp)))))

(deftest reclaim-orphans-zero-age-deletes-all-untracked
  ;; With no grace (min-age 0) every untracked dir goes; tracked stays.
  (let [tmp     (fs/create-temp-dir)
        now     1000000000000
        tracked (mk-dir! tmp "tracked" now)
        o1      (mk-dir! tmp "orphan-1" now)
        o2      (mk-dir! tmp "orphan-2" now)]
    (try
      (with-redefs [state/state-dir     (constantly (str tmp))
                    state/read-registry (constantly {"/proj" {:instance-id "tracked"}})]
        (let [deleted (reclaim/reclaim-orphans! {:min-age-ms 0 :now-ms now})]
          (is (= #{"orphan-1" "orphan-2"} (set (map first deleted))))
          (is (and (not (fs/exists? o1)) (not (fs/exists? o2))) "both orphans deleted")
          (is (fs/exists? tracked) "tracked dir preserved")))
      (finally (fs/delete-tree tmp)))))
