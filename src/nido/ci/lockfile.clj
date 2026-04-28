(ns nido.ci.lockfile
  "Lockfile drift auto-sync. Runs once per Run, before per-step worktree
   clones, so all clones inherit the freshly-synced state.

   The drift scenario this guards against: developer pulls a new
   lockfile but doesn't reinstall. Every subsequent `bb nido:ci:run`
   then clones a worktree where `package-lock.json` and `node_modules`
   disagree, and steps fail with confusing rule-output mismatches that
   have nothing to do with the change being tested. Without nido this
   doesn't happen — GH CI does `npm ci` from scratch, and a developer
   linting interactively will eventually re-`npm ci`. Nido is the
   unique vector that snapshots the drifted dev state into CI.

   Drift detection uses a nido-owned marker file written next to (or
   inside) the install dir: a sha256 of the source lockfile at last
   successful sync. Direct byte-compare against `node_modules/.package-
   lock.json` is unreliable — npm strips the root entry — and other
   ecosystems (uv, clojure, …) write entirely different shapes. The
   marker is universal: nido is the sole writer, so the comparison is
   always meaningful."
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [clojure.string :as str]
   [nido.core :as core])
  (:import
   (java.security MessageDigest)))

(defn- sha256-hex
  [^String s]
  (let [md (MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes s "UTF-8"))]
    (str/join (for [b bs] (format "%02x" b)))))

(defn- check-status
  "Returns one of:
     :no-source         — source lockfile missing; check is no-op.
     :synced            — marker hash matches current source hash.
     :drift             — marker exists but source has moved on.
     :drift-no-marker   — source exists, no marker yet (fresh worktree
                          or never synced through nido)."
  [worktree {:keys [source marker]}]
  (let [src-path (fs/path worktree source)
        marker-path (fs/path worktree marker)]
    (cond
      (not (fs/exists? src-path))   :no-source
      (not (fs/exists? marker-path)) :drift-no-marker
      :else
      (let [src-hash (sha256-hex (slurp (str src-path)))
            stored (str/trim (slurp (str marker-path)))]
        (if (= src-hash stored) :synced :drift)))))

(defn- write-marker!
  [worktree {:keys [source marker]}]
  (let [src-path (fs/path worktree source)
        marker-path (fs/path worktree marker)]
    (when (fs/exists? src-path)
      (fs/create-dirs (fs/parent marker-path))
      (spit (str marker-path) (sha256-hex (slurp (str src-path)))))))

(defn- run-sync!
  "Execute the project-supplied sync command in the source worktree.
   Throws on non-zero exit so the run aborts before fanning out steps."
  [worktree {:keys [source sync-cmd] :as check}]
  (let [start (System/currentTimeMillis)
        result (shell {:dir (str worktree) :continue true} "bash" "-c" sync-cmd)
        elapsed-s (/ (- (System/currentTimeMillis) start) 1000.0)]
    (if (zero? (:exit result))
      (do
        (write-marker! worktree check)
        (core/log-step (format "Lockfile sync complete for %s (%.1fs)"
                               source elapsed-s)))
      (throw (ex-info (str "Lockfile sync failed for " source
                           " (exit " (:exit result) "): " sync-cmd)
                      {:source source :exit (:exit result) :cmd sync-cmd})))))

(defn verify-and-sync!
  "For each declared lockfile check, ensure the source worktree's
   install state matches its lockfile. On drift, log + run the sync
   command in the source worktree (so subsequent step clones inherit
   the synced state) and update the marker.

   Project config in ci.edn:

     :lockfile-checks
     [{:source   \"e2e/package-lock.json\"
       :marker   \"e2e/node_modules/.nido-lockfile-sha256\"
       :sync-cmd \"cd e2e && npm ci\"}]

   No-op when the project has no `:lockfile-checks` entries."
  [{:keys [worktree-path]} checks]
  (doseq [check checks]
    (case (check-status worktree-path check)
      :no-source nil
      :synced    nil
      :drift
      (do (core/log-step
           (str "Lockfile drift in " (:source check)
                " — running: " (:sync-cmd check)))
          (run-sync! worktree-path check))
      :drift-no-marker
      (do (core/log-step
           (str "No lockfile sync marker for " (:source check)
                " — running: " (:sync-cmd check)))
          (run-sync! worktree-path check)))))
