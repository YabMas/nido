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
   [babashka.process :as p]
   [nido.platform.config :as config]
   [nido.platform.core :as core]
   [nido.platform.io :as io]
   [nido.platform.process :as proc]
   [nido.session.state :as state]))

(defn- tracked-instance-ids []
  (->> (state/read-registry)
       vals
       (keep :instance-id)
       set))

(defn- down-not-gone?
  "Whether the instance's recorded worktree still exists — its session is down, not gone. A
   graceful down and a host boot both clear the registry entry and leave the dir, and a :clone or
   :isolated session was promised its PGDATA back; only a dir whose worktree is gone, or that
   names none, is garbage."
  [id]
  (boolean (some-> (state/read-instance-worktree id) fs/directory?)))

(defn- orphan-instance-dirs
  "State dirs under ~/.nido/state/ with no matching registry entry, whose recorded worktree is
   gone. Excludes top-level files (the registry itself, legacy metadata)."
  []
  (let [root (state/state-dir)
        tracked (tracked-instance-ids)]
    (when (fs/exists? root)
      (->> (fs/list-dir root)
           (filter fs/directory?)
           (map #(vector (str (fs/file-name %)) (str %)))
           (remove (fn [[id _]] (or (contains? tracked id) (down-not-gone? id))))
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

(defn- jj-out
  "jj's stdout in `dir` with the working copy left alone, or nil when jj refuses."
  [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true}
                 "jj" "--ignore-working-copy" args)]
    (when (zero? (long (:exit r))) (:out r))))

(defn- dangling-workspaces
  "The workspaces `repo-dir`'s repo lists whose root directory does not exist, by name — never
   `default`, and never one whose root jj does not report, since an absent root is not a gone
   one. Nil when jj will not list them."
  [repo-dir]
  (when-let [out (jj-out repo-dir "workspace" "list" "-T" "name ++ \"\\t\" ++ self.root() ++ \"\\n\"")]
    (vec (for [line (str/split-lines out)
               :let [[ws-name root] (str/split line #"\t" 2)]
               :when (and (not= "default" ws-name)
                          (not (str/blank? root))
                          (not (fs/exists? root)))]
           ws-name))))

(defn ^{:malli/schema [:=> [:cat :Path] [:maybe [:sequential :string]]]}
  forget-dangling-workspaces!
  "Forget every workspace `repo-dir`'s repo still lists whose directory is gone, and answer
   their names — nil when jj will not list them or will not forget them.

   Every path that deletes a workspace's directory is meant to forget it first, and several do
   not: reclaim deletes a runtime checkout with the state directory it sits in, destroy skips the
   forget for a worktree already gone, a run directory goes with its round's tree in it, and an
   agent's scratchpad workspace has no owner in nido at all. Whatever deleted the directory, the
   registration is then state nothing reads and every jj operation in the repo carries — jj
   records each workspace's working copy in every operation it writes. A missing directory is
   the whole test, because `jj workspace add` makes the directory before it registers the name
   and every remover forgets before it deletes: no workspace in use is ever without one.

   Forgetting keeps the working-copy commit: an empty one goes with the name, a non-empty one
   stays as a visible head, so nothing written in a lost workspace is lost with it. A repo
   colocated with git backs each workspace with a git worktree that jj removes only while its
   directory exists, so every sweep prunes registrations whose directories are gone — whether or
   not it forgot anything, so one a forget elsewhere left, or a prune that failed, goes at the
   next — against the git directory jj reports, since `repo-dir` may be a workspace of that repo
   with no `.git` of its own."
  [repo-dir]
  (when-let [names (dangling-workspaces repo-dir)]
    (when (or (empty? names) (apply jj-out repo-dir "workspace" "forget" names))
      (when-let [git-dir (some-> (jj-out repo-dir "git" "root") str/trim not-empty)]
        (p/shell {:dir (str repo-dir) :out :string :err :string :continue true}
                 "git" "--git-dir" git-dir "worktree" "prune"))
      names)))

(defn ^{:malli/schema [:=> [:cat] [:map-of :string [:maybe [:sequential :string]]]]}
  forget-dangling-everywhere!
  "`forget-dangling-workspaces!` over every registered project whose directory is a jj repo,
   by project name — the coordinator's sweep, so a registration whose owner never forgot it
   lasts at most one reclaim interval."
  []
  (into {}
        (for [[project-name {:keys [directory]}] (config/read-projects)
              :when (and directory (fs/directory? (fs/path directory ".jj")))]
          [project-name (forget-dangling-workspaces! directory)])))
