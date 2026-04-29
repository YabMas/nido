(ns tasks.nido-harness
  "Sync nido's .claude/ harness from another project's .claude/ via
   symlinks, driven by `<nido>/.claude/harness.edn`.

   nido is a thin orchestration shell — most agents and skills come from
   the target project's harness, borrowed via symlink. The set of borrowed
   files changes as the source evolves (brian, in particular, ships harness
   updates regularly), so the symlink set has to be reconciled rather than
   set up once.

   Reconciliation, per category:

     1. List entries under <source>/<category>/.
     2. Drop entries in :exclude.
     3. For each remaining entry: ensure a symlink at the matching nido
        path points at the source. If a real (non-symlink) file already
        lives there, treat it as a user override and skip — don't touch it.
     4. Prune symlinks under <nido>/.claude/<category>/ whose stored target
        is inside <source> but isn't in the desired set anymore (covers
        source deletions and newly-excluded entries). Symlinks pointing
        elsewhere are ignored.

   For :files (explicit list, e.g. dev-rules.md) the same rules apply
   without the listing step.

   Idempotent: re-running on a clean tree reports everything as unchanged."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.core :as core]
   [nido.io :as io]))

(defn- nido-claude-dir []
  (str (fs/path (core/nido-source-dir) ".claude")))

(defn- manifest-path []
  (str (fs/path (nido-claude-dir) "harness.edn")))

(defn- read-manifest []
  (or (io/read-edn (manifest-path))
      (throw (ex-info (str "No harness manifest at " (manifest-path))
                      {:hint "Create .claude/harness.edn — see comment in the file for shape."}))))

(defn- list-source-names
  "File/dir names directly under <source>/<category>/ as a sorted set."
  [source category]
  (let [dir (str (fs/path source category))]
    (if-not (fs/exists? dir)
      (sorted-set)
      (->> (fs/list-dir dir)
           (map (comp str fs/file-name))
           (into (sorted-set))))))

(defn- target-path [category entry-name]
  (str (fs/path (nido-claude-dir) category entry-name)))

(defn- source-path [source category entry-name]
  (str (fs/path source category entry-name)))

(defn- override?
  "True iff a real (non-symlink) file exists at `path` — user has taken
   ownership and sync must leave it alone."
  [path]
  (and (fs/exists? path)
       (not (fs/sym-link? path))))

(defn- existing-link-target [path]
  (when (fs/sym-link? path)
    (str (fs/read-link path))))

(defn- ensure-link!
  "Create or repoint a symlink at `target` → `src`. Returns one of
   :created, :updated, :unchanged, :overridden."
  [target src]
  (cond
    (override? target)
    :overridden

    (and (fs/sym-link? target)
         (= (existing-link-target target) src))
    :unchanged

    (fs/sym-link? target)
    (do (fs/delete target)
        (fs/create-sym-link target src)
        :updated)

    :else
    (do (when-let [parent (fs/parent target)]
          (fs/create-dirs parent))
        (fs/create-sym-link target src)
        :created)))

(defn- prune-orphans!
  "Remove symlinks under <nido>/.claude/<category>/ whose stored target
   lies within `source` but whose name is not in `desired`. Returns the
   removed names."
  [source category desired]
  (let [dir (fs/path (nido-claude-dir) category)
        source-prefix (str (fs/normalize source) "/")
        desired? (set desired)
        removed (atom [])]
    (when (fs/exists? dir)
      (doseq [path (fs/list-dir dir)
              :let [p (str path)
                    nm (str (fs/file-name path))
                    link-target (existing-link-target p)]
              :when (and link-target
                         (str/starts-with? link-target source-prefix)
                         (not (desired? nm)))]
        (fs/delete p)
        (swap! removed conj nm)))
    @removed))

(defn- resolve-wanted [include-spec available]
  (cond
    (= include-spec :all)
    available

    (sequential? include-spec)
    (into (sorted-set) (map str include-spec))

    :else
    (sorted-set)))

(defn- sync-listed-category!
  "Reconcile a directory category (agents, skills) that has both an
   :include directive and a category subdir under :from."
  [source category include-spec exclude-set]
  (let [available (list-source-names source category)
        wanted    (resolve-wanted include-spec available)
        kept      (into (sorted-set) (remove exclude-set wanted))
        actions   (atom {:created [] :updated [] :unchanged [] :overridden []})]
    (doseq [nm kept
            :let [src (source-path source category nm)]
            :when (fs/exists? src)]
      (swap! actions update (ensure-link! (target-path category nm) src) conj nm))
    (assoc @actions :pruned (prune-orphans! source category kept))))

(defn- sync-files-list!
  "Reconcile explicit top-level files (e.g. dev-rules.md). No category
   subdir, no listing step."
  [source files]
  (let [actions (atom {:created [] :updated [] :unchanged [] :overridden []})]
    (doseq [nm (map str files)
            :let [src (str (fs/path source nm))
                  tgt (str (fs/path (nido-claude-dir) nm))]
            :when (fs/exists? src)]
      (swap! actions update (ensure-link! tgt src) conj nm))
    @actions))

(defn- print-report [label {:keys [created updated unchanged overridden pruned]}]
  (let [counts (cond-> []
                 (seq unchanged)  (conj (str (count unchanged) " unchanged"))
                 (seq created)    (conj (str "+" (count created)))
                 (seq updated)    (conj (str "~" (count updated)))
                 (seq overridden) (conj (str (count overridden) " overridden"))
                 (seq pruned)     (conj (str "-" (count pruned))))]
    (println (str label ": " (if (empty? counts) "nothing to do"
                                 (str/join ", " counts)))))
  (doseq [n created]    (println (str "  +  " n)))
  (doseq [n updated]    (println (str "  ~  " n)))
  (doseq [n overridden] (println (str "  =  " n " (user override)")))
  (doseq [n pruned]     (println (str "  -  " n))))

(defn- absolute-source [from]
  (str (fs/normalize (fs/expand-home from))))

(defn sync-cmd
  "Reconcile <nido>/.claude/ against .claude/harness.edn."
  [& _args]
  (let [{:keys [from include exclude]} (read-manifest)
        source (absolute-source from)]
    (when-not (fs/exists? source)
      (throw (ex-info (str "Harness source not found: " source)
                      {:from from :resolved source})))
    (println (str "Syncing nido/.claude/ from " source))
    (doseq [category ["agents" "skills"]
            :when (contains? include (keyword category))]
      (let [include-spec (get include (keyword category))
            exclude-set  (set (get-in exclude [(keyword category)] #{}))]
        (print-report category
                      (sync-listed-category! source category include-spec exclude-set))))
    (when-let [files (:files include)]
      (print-report "files" (sync-files-list! source files)))))
