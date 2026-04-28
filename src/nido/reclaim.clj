(ns nido.reclaim
  "Delete per-instance state dirs under ~/.nido/state/ that have no
   matching registry entry. Useful after a session was destroyed
   uncleanly (kill -9, host crash, manual rm) and left its PGDATA
   behind. Safe to run anytime — never touches the registry itself or
   template state."
  (:require
   [babashka.fs :as fs]
   [nido.core :as core]
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

(defn reclaim!
  "List orphaned per-instance state dirs. With :force? true, delete
   them. An instance is orphaned iff its id is not present in any
   registry entry."
  [& {:keys [force?] :or {force? false}}]
  (let [orphans (orphan-instance-dirs)]
    (if (empty? orphans)
      (core/log-step "No orphaned state dirs found.")
      (do
        (println "Orphaned instance state dirs:")
        (doseq [[id path] orphans]
          (println (str "  " id "  — " path)))
        (if force?
          (do
            (doseq [[id path] orphans]
              (when (fs/exists? path)
                (core/log-step (str "Deleting " id " (" path ")"))
                (fs/delete-tree path)))
            (core/log-step (str "Reclaimed " (count orphans) " dir(s)."))
            :reclaimed)
          (do
            (println)
            (println (str (count orphans) " dir(s) listed. "
                          "Re-run with :force? true to delete."))
            :listed))))))
