(ns nido.session.reclaim
  "Delete per-instance state dirs under ~/.nido/state/ that have no
   matching registry entry. Useful after a session was destroyed
   uncleanly (kill -9, host crash, manual rm) and left its PGDATA
   behind. Safe to run anytime — never touches the registry itself or
   template state, and never deletes a dir whose recorded process still
   runs (see live-pids)."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.platform.core :as core]
   [nido.platform.io :as io]
   [nido.platform.process :as proc]
   [nido.session.state :as state]))

(defn- tracked-instance-ids []
  (->> (state/read-registry)
       vals
       (keep :instance-id)
       set))

(defn- orphan-instance-dirs
  "State dirs under ~/.nido/state/ with no matching registry entry.
   Excludes top-level files (the registry itself, legacy metadata)."
  []
  (let [root (state/state-dir)
        tracked (tracked-instance-ids)]
    (when (fs/exists? root)
      (->> (fs/list-dir root)
           (filter fs/directory?)
           (map #(vector (str (fs/file-name %)) (str %)))
           (remove (fn [[id _]] (contains? tracked id)))
           (sort-by first)))))

(defn- recorded-pids
  "Every pid an instance dir names: the pid file each :process service writes
   at spawn, and the service states of a session.edn whose registry entry is
   gone. Unreadable files name nothing."
  [dir]
  (let [from-files (for [f (fs/glob dir "*.pid")]
                     (try (some-> (slurp (str f)) str/trim parse-long)
                          (catch Exception _ nil)))
        from-state (try (some->> (io/read-edn (str (fs/path dir "session.edn")))
                                 :service-states vals (keep :pid))
                        (catch Exception _ nil))]
    (into #{} (filter pos-int?) (concat from-files from-state))))

(defn- live-pids
  "The recorded pids of `dir` that are still running.

   Registry absence says no session claims the dir; it does not say nothing is
   running out of it. A boot whose `bb` was killed half-way never registers, yet
   its JVM is already detached — and the pid this dir holds is the only record of
   it. Deleting the dir then leaves a process no nido surface can see: brian's
   invesigate/perf-claim JVM ran for four days that way. A recycled pid keeps a
   dir that could have gone, which costs disk; the opposite mistake costs a
   gigabyte-plus JVM nobody can find."
  [dir]
  (into #{} (filter proc/process-alive?) (recorded-pids dir)))

(defn- dir-age-ms
  "Wall-clock ms since the state dir was last structurally modified. A
   session's instance dir is touched when its pg-data/ + logs/ + session.edn
   are created at boot, so a mid-boot session reads as 'young' — which is
   exactly the window we must NOT reclaim (the registry entry is written LAST,
   after PGDATA clone + app boot, so a booting session has a dir but no
   registry entry yet and would otherwise look like an orphan)."
  [path now-ms]
  (- now-ms (.toMillis (fs/last-modified-time path))))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  reclaim-orphans!
  "Programmatic, age-guarded reclaim for the coordinator's periodic sweep.
   Deletes only orphan state dirs whose instance dir is older than
   :min-age-ms (the grace window that protects sessions still booting), and
   never one whose recorded process is still running. :now-ms overridable for
   tests.

   Returns {:deleted [[id path] …] :live [[id path pids] …]} — :live being the
   old orphans kept for a running process, which the caller should report: each
   is a process nothing else records.

   Distinct from reclaim! (the interactive CLI flow): no printing, no
   confirm gate, and it never deletes a young orphan."
  [{:keys [min-age-ms now-ms] :or {min-age-ms 0}}]
  (let [now-ms (or now-ms (System/currentTimeMillis))
        old    (filter (fn [[_ path]] (>= (dir-age-ms path now-ms) min-age-ms))
                       (orphan-instance-dirs))
        live   (vec (keep (fn [[id path]]
                            (when-let [pids (not-empty (live-pids path))]
                              [id path pids]))
                          old))
        kept   (set (map first live))
        doomed (vec (remove (fn [[id _]] (kept id)) old))]
    (doseq [[_ path] doomed]
      (when (fs/exists? path) (fs/delete-tree path)))
    {:deleted doomed :live live}))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  reclaim!
  "List orphaned per-instance state dirs. With :force? true, delete
   them — except any whose recorded process is still running, which is
   listed with its pids and kept. An instance is orphaned iff its id is
   not present in any registry entry."
  [& {:keys [force?] :or {force? false}}]
  (let [orphans (orphan-instance-dirs)
        live    (into {} (keep (fn [[id path]]
                                 (when-let [pids (not-empty (live-pids path))]
                                   [id pids])))
                      orphans)]
    (if (empty? orphans)
      (core/log-step "No orphaned state dirs found.")
      (do
        (println "Orphaned instance state dirs:")
        (doseq [[id path] orphans]
          (println (str "  " id "  — " path
                        (when-let [pids (live id)]
                          (str "  (kept: pid " (str/join ", " (sort pids))
                               " still running)")))))
        (if force?
          (let [doomed (remove (fn [[id _]] (live id)) orphans)]
            (doseq [[id path] doomed]
              (when (fs/exists? path)
                (core/log-step (str "Deleting " id " (" path ")"))
                (fs/delete-tree path)))
            (core/log-step (str "Reclaimed " (count doomed) " dir(s)."))
            :reclaimed)
          (do
            (println)
            (println (str (count orphans) " dir(s) listed. "
                          "Re-run with :force? true to delete."))
            :listed))))))
