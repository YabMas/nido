(ns nido.ci.isolation
  "Per-step isolation: creates TMPDIR, XDG_CACHE_HOME, and an APFS-cloned
   working copy of the session worktree under the step's paths so
   concurrent step-runs don't race on shared mutable state — `.cpcache`,
   `target/`, `.shadow-cljs/`, `node_modules/`, generated resources
   (spec invariants G4, G4_IsolationEnvsAreDistinct). Also provides the
   process-group spawn wrapper used by the command (spec invariant G5)."
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [clojure.string :as str]))

(def default-clone-excludes
  "Relative paths skipped by default when cloning a worktree for step
   isolation. These are rebuildable caches and build outputs that don't
   affect step correctness when missing.

   Two caches that look like obvious excludes are deliberately KEPT:

   - `.cpcache/` — without it, brian's bb tasks that internally invoke
     `clojure -M:...` deadlock. The first cold cp-resolution under
     babashka.process apparently exposes a `:out :string` pipe stall.
     The cache is small (~6 MB, ~70 files) so cloning it is cheap.

   - `.clj-kondo/.cache/` — without it, clj-kondo re-parses every jar's
     metadata and emits megabytes of analysis JSON, which similarly
     deadlocks `:out :string` capture in `tasks.lint-deps/run-kondo-
     analysis`. Cache is large in file count (~70K) but APFS COW makes
     the clone metadata-only.

   Tracking these caches per-clone is also the right correctness move:
   each step's tooling can write its own diverged cache without racing
   against siblings."
  ;; `node_modules` and `e2e/node_modules` are deliberately *kept* — APFS
  ;; clones make even a 60-MB tree metadata-only, and excluding them
  ;; broke main-mode e2e runs where the spawned project app's playwright
  ;; needs `@playwright/test` resolvable from cwd. Symlink-bypass in
  ;; `clone-walk!` carries the session-mode `node_modules` symlink
  ;; through too, so both modes get the right tree without special-
  ;; casing.
  #{"target"
    ".shadow-cljs"
    ;; Main-mode CI runs clone the project root; if a project keeps
    ;; its session worktrees inside the repo (brian: `.worktrees/`),
    ;; we'd otherwise clone every sibling worktree's tree on each step
    ;; run — multiplying disk + cljfmt/lint scan time.
    ".worktrees"
    "e2e/playwright-report"
    "e2e/test-results"
    "e2e/.cache"})

(defn- exclude-strategy
  "Classify how a child entry interacts with the exclude set:
     :skip    — the entry is itself excluded
     :recurse — some exclude is nested under this entry; walk into it
     :clone   — neither; clone the whole subtree with cp -cR
   `child-rel` is the entry's path relative to the clone root."
  [excludes child-rel]
  (cond
    (contains? excludes child-rel)
    :skip

    (some #(str/starts-with? % (str child-rel "/")) excludes)
    :recurse

    :else :clone))

(defn- cp-clone!
  "Run `cp -cRP <src> <dst>` for an APFS metadata-only clone. The `-P`
   flag is essential: without it, a symlink named on the command line
   would be dereferenced (cp's documented default), causing the walk to
   descend into the link target — e.g. `.local-repos` symlinks blow the
   clone size up by tens of thousands of files. Throws on failure with
   the actionable hint that APFS + same-volume is required."
  [src dst]
  (let [r (shell {:continue true :out :string :err :string :shutdown nil}
                 "cp" "-cRP" (str src) (str dst))]
    (when-not (zero? (:exit r))
      (throw (ex-info "Failed to APFS-clone worktree for step isolation"
                      {:src (str src) :dst (str dst)
                       :exit (:exit r) :err (:err r)
                       :hint (str "cp -cRP requires APFS + same volume. "
                                  "Ensure ~/.nido/state and your source "
                                  "directory are on the same APFS volume.")})))))

(defn- clone-walk!
  "Recursively clone src→dst respecting the exclude set. Entries whose
   rel path is exactly in `excludes` are skipped; entries that contain a
   nested exclude are walked one level deeper; everything else gets a
   single `cp -cR` (cheap APFS metadata clone).

   Symlinks bypass the exclude set: they're a few bytes apiece, and skipping
   one silently breaks anything that resolves through it. The exclude set is
   meant to skip large rebuildable directory trees, not 4-byte indirections
   into them — `cp -cRP` already preserves symlinks verbatim, so the only
   reason to special-case them here is to override an excludes match."
  [src dst excludes path-prefix]
  (fs/create-dirs (str dst))
  (doseq [child (fs/list-dir src)]
    (let [name (str (fs/file-name child))
          rel  (if (str/blank? path-prefix) name (str path-prefix "/" name))]
      (if (fs/sym-link? child)
        (cp-clone! child (fs/path dst name))
        (case (exclude-strategy excludes rel)
          :skip    nil
          :recurse (clone-walk! child (fs/path dst name) excludes rel)
          :clone   (cp-clone! child (fs/path dst name)))))))

(defn clone-worktree!
  "APFS-clone <src> to <dst> via `cp -cR`, skipping rebuildable caches
   and build outputs (see `default-clone-excludes`). Both paths must be
   on the same APFS volume — the normal case for ~/.nido/state and the
   user's source dir on /. Cheap: metadata-only copy-on-write; the
   clone diverges from the source only on writes inside <dst>.

   `:excludes` overrides the default set when supplied; otherwise the
   defaults apply.

   Throws with an actionable hint if <dst> already exists or cp fails
   (different volume, non-APFS, permissions). Returns <dst> on success."
  ([src dst] (clone-worktree! src dst {}))
  ([src dst {:keys [excludes] :or {excludes default-clone-excludes}}]
   (when-not (and src (fs/exists? src))
     (throw (ex-info "Source worktree to clone does not exist"
                     {:src src})))
   (when (fs/exists? dst)
     (throw (ex-info "Step clone target already exists; refusing to overwrite"
                     {:src src :dst dst})))
   (when-let [parent (fs/parent dst)]
     (fs/create-dirs (str parent)))
   (clone-walk! src dst (set excludes) "")
   (str dst)))

(defn prepare-dirs!
  "Create the step's logs/, tmp/, cache/ dirs and APFS-clone the session
   worktree into work/. Returns an IsolationEnv map; :process-group-id
   is nil until the command is launched."
  [paths source-worktree]
  (fs/create-dirs (:logs-dir paths))
  (fs/create-dirs (:tmp-dir paths))
  (fs/create-dirs (:cache-dir paths))
  (clone-worktree! source-worktree (:work-dir paths))
  {:tmpdir (:tmp-dir paths)
   :xdg-cache-home (:cache-dir paths)
   :work-dir (:work-dir paths)
   :process-group-id nil})

(defn cleanup-dirs!
  "Remove the step's scratch dirs and the cloned working copy. Logs and
   manifest survive for post-mortem inspection."
  [isolation]
  (doseq [d [(:tmpdir isolation)
             (:xdg-cache-home isolation)
             (:work-dir isolation)]]
    (when (and d (fs/exists? d))
      (try (fs/delete-tree d) (catch Exception _ nil)))))

(defn spawn-command-vec
  "Return the argv for spawning <shell-cmd> as a new process-group leader.

   Uses Perl's setpgrp(0,0) + exec so the child becomes its own PGID ==
   PID. Works on stock macOS (no util-linux `setsid`). Teardown can then
   `kill -- -<pgid>` the whole tree.

   Note: `bash -c` (not `-lc`) — we inherit the parent's PATH/env
   as-configured by the user's actual login shell (typically zsh). A
   login bash on macOS rewrites PATH via /etc/profile and ~/.bash_profile
   in a way that puts /usr/bin before brew paths, so the /usr/bin/java
   stub shadows the real JDK."
  [shell-cmd]
  ["perl" "-e" "setpgrp(0,0); exec @ARGV"
   "--" "bash" "-c" shell-cmd])

(defn pgid-of
  "Read a pid's current PGID via ps. Returns nil on failure."
  [pid]
  (let [result (shell {:continue true :out :string :err :string}
                      "ps" "-o" "pgid=" "-p" (str pid))]
    (when (zero? (:exit result))
      (some-> (:out result) str/trim parse-long))))

(defn await-own-pgid
  "Block until the child's `setpgrp(0,0)` has landed (pgid == pid), or
   until `timeout-ms` elapses. Returns the observed pgid, or nil if the
   process has already vanished. The race this guards against: after
   `p/process` returns we have the child pid, but the perl wrapper may
   not yet have executed `setpgrp(0,0)` — a `ps` call that wins the
   race reports the *parent's* inherited pgid. Recording that stale
   value makes teardown fire `kill -- -<parent-pgid>`, missing the real
   child group entirely and leaving the command orphaned."
  ([pid] (await-own-pgid pid 1000))
  ([pid timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (let [pgid (pgid-of pid)]
         (cond
           (nil? pgid)                        nil
           (= pgid pid)                       pgid
           (> (System/currentTimeMillis) deadline) pgid
           :else (do (Thread/sleep 10) (recur))))))))

(defn env-overrides
  "Env variables the child command inherits on top of the parent's env.
   Sets TMPDIR + XDG_CACHE_HOME to the per-run dirs, sets NIDO_CI=1 so
   project tooling can opt out of dev-mode behaviour, surfaces session-
   derived values, and applies the step's :env last so step config wins.

   PGPORT and NIDO_APP_PORT prefer the isolation's allocations (set
   when the step opted into `:isolated-pg?` / `:isolated-app?`),
   falling back to the session's shared values. NIDO_APP_PORT is what
   ci-step `:services` healthchecks should read via `:port-from-env`
   when waiting on a per-step app server."
  [{:keys [session isolation step]}]
  (merge
   {"NIDO_CI"         "1"
    "TMPDIR"          (:tmpdir isolation)
    "XDG_CACHE_HOME"  (:xdg-cache-home isolation)
    "NIDO_RUN_TMPDIR" (:tmpdir isolation)
    "NIDO_RUN_CACHE"  (:xdg-cache-home isolation)}
   (when-let [p (or (:pg-port isolation) (:pg-port session))]
     {"PGPORT" (str p)})
   (when-let [p (or (:app-port isolation) (:app-port session))]
     {"NIDO_APP_PORT" (str p)})
   (or (:env step) {})))
