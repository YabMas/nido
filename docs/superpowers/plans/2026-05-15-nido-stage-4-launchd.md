# Nido Stage 4 — launchd auto-start: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a macOS LaunchAgent plist so the nido coordinator auto-starts at login and respawns on crash, with `bb nido:coordinator:install / uninstall / restart` tasks and a unified `up` / `down` / `status` surface that wraps `launchctl` when the plist is installed.

**Architecture:** New `nido.coordinator.launchctl` namespace owns plist rendering + `launchctl` shell wrappers. New task functions `install` / `uninstall` / `restart` in `tasks/nido_coordinator.clj`. Existing `up` / `down` / `status` get a single `(launchctl/installed?)` branch so the developer surface is unified across both lifecycles.

**Tech Stack:** Babashka, `babashka.process` for `launchctl` shell calls, macOS `launchctl(1)` (modern `bootstrap` / `bootout` / `kickstart` / `print` subcommands).

**Spec:** [`../specs/2026-05-15-nido-stage-4-launchd-design.md`](../specs/2026-05-15-nido-stage-4-launchd-design.md)

---

## File structure

**Create:**
- `src/nido/coordinator/launchctl.clj` — pure rendering + `launchctl` shell wrappers
- `test/nido/coordinator/launchctl_test.clj` — unit tests for the launchctl module

**Modify:**
- `src/tasks/nido_coordinator.clj` — add `install` / `uninstall` / `restart`; branch `up` / `down` / `status` on `(launchctl/installed?)`
- `bb.edn` — register the three new tasks
- `CLAUDE.md` — Stage 4 verbs + "wraps launchctl when installed" rule

The launchctl module follows the same shape as `pid.clj` / `state.clj`: pure helpers redefable via `with-redefs` for tests. The task layer stays thin and is smoke-tested manually (matches Stage 3 — there are no `test/tasks/` tests today).

---

### Task 1: launchctl module — paths, label, `installed?`

**Files:**
- Create: `src/nido/coordinator/launchctl.clj`
- Create: `test/nido/coordinator/launchctl_test.clj`

- [ ] **Step 1: Write the failing test**

```clojure
(ns nido.coordinator.launchctl-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.launchctl :as lc]))

(defn- with-tmp-home [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [lc/launch-agents-dir (constantly (str tmp))]
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(deftest plist-path-lives-under-launch-agents
  (with-tmp-home
    (fn [tmp]
      (is (= (str (fs/path tmp "dev.nido.coordinator.plist"))
             (lc/plist-path))))))

(deftest label-is-stable
  (is (= "dev.nido.coordinator" (lc/label))))

(deftest installed?-false-when-no-plist
  (with-tmp-home
    (fn [_] (is (false? (lc/installed?))))))

(deftest installed?-true-when-plist-file-exists
  (with-tmp-home
    (fn [_]
      (spit (lc/plist-path) "stub")
      (is (true? (lc/installed?))))))
```

- [ ] **Step 2: Run test to verify it fails**

```
bb nido:test :only nido.coordinator.launchctl-test
```

Expected: failure with "Could not locate nido/coordinator/launchctl" (namespace doesn't exist yet).

- [ ] **Step 3: Write minimal implementation**

```clojure
(ns nido.coordinator.launchctl
  "macOS LaunchAgent plist for the nido coordinator. Pure rendering plus
   thin shell wrappers around launchctl. No nio2 watchers, no state."
  (:require
   [babashka.fs :as fs]))

(defn launch-agents-dir
  "~/Library/LaunchAgents. Wrapped so tests can redirect to a tempdir."
  []
  (str (fs/path (System/getProperty "user.home") "Library" "LaunchAgents")))

(defn label [] "dev.nido.coordinator")

(defn plist-path []
  (str (fs/path (launch-agents-dir) (str (label) ".plist"))))

(defn installed? []
  (fs/exists? (plist-path)))
```

- [ ] **Step 4: Run test to verify it passes**

```
bb nido:test :only nido.coordinator.launchctl-test
```

Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```
jj desc -m "feat(coordinator): launchctl module — paths and installed? probe"
jj new
```

---

### Task 2: launchctl module — `render-plist`

**Files:**
- Modify: `src/nido/coordinator/launchctl.clj`
- Modify: `test/nido/coordinator/launchctl_test.clj`

- [ ] **Step 1: Write the failing test (golden plist string)**

Add to `test/nido/coordinator/launchctl_test.clj`:

```clojure
(def ^:private expected-plist
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
       "<plist version=\"1.0\">\n"
       "<dict>\n"
       "    <key>Label</key>\n"
       "    <string>dev.nido.coordinator</string>\n"
       "    <key>ProgramArguments</key>\n"
       "    <array>\n"
       "        <string>/opt/homebrew/bin/bb</string>\n"
       "        <string>nido:coordinator:run</string>\n"
       "    </array>\n"
       "    <key>WorkingDirectory</key>\n"
       "    <string>/Users/yabmas/Code/nido</string>\n"
       "    <key>RunAtLoad</key>\n"
       "    <true/>\n"
       "    <key>KeepAlive</key>\n"
       "    <true/>\n"
       "    <key>ThrottleInterval</key>\n"
       "    <integer>10</integer>\n"
       "    <key>StandardOutPath</key>\n"
       "    <string>/Users/yabmas/.nido/coordinator/coordinator.log</string>\n"
       "    <key>StandardErrorPath</key>\n"
       "    <string>/Users/yabmas/.nido/coordinator/coordinator.log</string>\n"
       "    <key>EnvironmentVariables</key>\n"
       "    <dict>\n"
       "        <key>PATH</key>\n"
       "        <string>/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin</string>\n"
       "    </dict>\n"
       "</dict>\n"
       "</plist>\n"))

(deftest render-plist-matches-golden
  (is (= expected-plist
         (lc/render-plist {:bb-path  "/opt/homebrew/bin/bb"
                           :nido-dir "/Users/yabmas/Code/nido"
                           :log-path "/Users/yabmas/.nido/coordinator/coordinator.log"
                           :path-env "/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin"}))))
```

- [ ] **Step 2: Run test to verify it fails**

```
bb nido:test :only nido.coordinator.launchctl-test
```

Expected: `render-plist-matches-golden` fails with "Unable to resolve symbol: render-plist".

- [ ] **Step 3: Implement `render-plist`**

Add to `src/nido/coordinator/launchctl.clj`:

```clojure
(defn render-plist
  "Render the LaunchAgent plist XML for the coordinator.

   Inputs (all absolute paths / values that the plist will contain verbatim):
   - :bb-path   — absolute path to the bb binary
   - :nido-dir  — absolute path to the nido checkout (becomes WorkingDirectory)
   - :log-path  — absolute path for StandardOutPath + StandardErrorPath
   - :path-env  — PATH value injected into the daemon's environment"
  [{:keys [bb-path nido-dir log-path path-env]}]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
       "<plist version=\"1.0\">\n"
       "<dict>\n"
       "    <key>Label</key>\n"
       "    <string>" (label) "</string>\n"
       "    <key>ProgramArguments</key>\n"
       "    <array>\n"
       "        <string>" bb-path "</string>\n"
       "        <string>nido:coordinator:run</string>\n"
       "    </array>\n"
       "    <key>WorkingDirectory</key>\n"
       "    <string>" nido-dir "</string>\n"
       "    <key>RunAtLoad</key>\n"
       "    <true/>\n"
       "    <key>KeepAlive</key>\n"
       "    <true/>\n"
       "    <key>ThrottleInterval</key>\n"
       "    <integer>10</integer>\n"
       "    <key>StandardOutPath</key>\n"
       "    <string>" log-path "</string>\n"
       "    <key>StandardErrorPath</key>\n"
       "    <string>" log-path "</string>\n"
       "    <key>EnvironmentVariables</key>\n"
       "    <dict>\n"
       "        <key>PATH</key>\n"
       "        <string>" path-env "</string>\n"
       "    </dict>\n"
       "</dict>\n"
       "</plist>\n"))
```

Note: inputs are absolute paths chosen at install time, never user input. If we later need user-controlled values, add XML-escaping here.

- [ ] **Step 4: Run test to verify it passes**

```
bb nido:test :only nido.coordinator.launchctl-test
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```
jj desc -m "feat(coordinator): launchctl render-plist with golden test"
jj new
```

---

### Task 3: launchctl module — `write-plist!` / `remove-plist!`

**Files:**
- Modify: `src/nido/coordinator/launchctl.clj`
- Modify: `test/nido/coordinator/launchctl_test.clj`

- [ ] **Step 1: Write the failing tests**

```clojure
(deftest write-plist!-creates-the-file
  (with-tmp-home
    (fn [_]
      (lc/write-plist! "stub-contents")
      (is (lc/installed?))
      (is (= "stub-contents" (slurp (lc/plist-path)))))))

(deftest write-plist!-creates-parent-dir-if-missing
  (with-tmp-home
    (fn [tmp]
      (fs/delete-tree tmp)
      (lc/write-plist! "stub")
      (is (fs/exists? (lc/plist-path))))))

(deftest write-plist!-overwrites-existing
  (with-tmp-home
    (fn [_]
      (lc/write-plist! "first")
      (lc/write-plist! "second")
      (is (= "second" (slurp (lc/plist-path)))))))

(deftest remove-plist!-noop-when-absent
  (with-tmp-home
    (fn [_]
      (lc/remove-plist!) ; must not throw
      (is (false? (lc/installed?))))))

(deftest remove-plist!-deletes-existing
  (with-tmp-home
    (fn [_]
      (lc/write-plist! "stub")
      (lc/remove-plist!)
      (is (false? (lc/installed?))))))
```

- [ ] **Step 2: Run tests to verify they fail**

Expected: failures on undefined `write-plist!` / `remove-plist!`.

- [ ] **Step 3: Implement**

Add to `src/nido/coordinator/launchctl.clj`:

```clojure
(defn write-plist!
  "Write the plist contents to ~/Library/LaunchAgents/dev.nido.coordinator.plist.
   Creates the parent directory if missing. Overwrites any existing file."
  [contents]
  (let [p (plist-path)]
    (fs/create-dirs (fs/parent p))
    (spit p contents)))

(defn remove-plist!
  "Delete the plist file if it exists. No-op when absent."
  []
  (when (installed?)
    (fs/delete (plist-path))))
```

- [ ] **Step 4: Run tests to verify they pass**

```
bb nido:test :only nido.coordinator.launchctl-test
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```
jj desc -m "feat(coordinator): launchctl write-plist! / remove-plist!"
jj new
```

---

### Task 4: launchctl module — `loaded?` + `bootstrap!` / `bootout!` / `kickstart!`

These shell out to `launchctl`. The strategy: a private `sh!` helper that wraps `babashka.process/sh` and is redef-friendly for tests. Each public verb is a one-liner over `sh!`.

**Files:**
- Modify: `src/nido/coordinator/launchctl.clj`
- Modify: `test/nido/coordinator/launchctl_test.clj`

- [ ] **Step 1: Write the failing tests**

```clojure
(defn- stub-sh
  "Return a fake sh! that records its calls and returns the given result."
  [calls result]
  (fn [args]
    (swap! calls conj args)
    result))

(deftest target-uses-current-uid
  (is (re-matches #"gui/\d+/dev\.nido\.coordinator" (lc/target))))

(deftest loaded?-true-when-launchctl-print-exits-0
  (with-redefs [lc/sh! (stub-sh (atom []) {:exit 0 :out "" :err ""})]
    (is (true? (lc/loaded?)))))

(deftest loaded?-false-when-launchctl-print-exits-nonzero
  (with-redefs [lc/sh! (stub-sh (atom []) {:exit 113 :out "" :err "Could not find service"})]
    (is (false? (lc/loaded?)))))

(deftest bootstrap!-shells-launchctl-bootstrap
  (let [calls (atom [])]
    (with-redefs [lc/sh! (stub-sh calls {:exit 0 :out "" :err ""})]
      (lc/bootstrap!)
      (is (= 1 (count @calls)))
      (let [[args] @calls]
        (is (= "launchctl" (first args)))
        (is (= "bootstrap" (second args)))
        (is (re-matches #"gui/\d+" (nth args 2)))
        (is (= (lc/plist-path) (nth args 3)))))))

(deftest bootout!-shells-launchctl-bootout
  (let [calls (atom [])]
    (with-redefs [lc/sh! (stub-sh calls {:exit 0 :out "" :err ""})]
      (lc/bootout!)
      (let [[args] @calls]
        (is (= ["launchctl" "bootout"] (take 2 args)))
        (is (= (lc/target) (nth args 2)))))))

(deftest kickstart!-shells-launchctl-kickstart-with--k
  (let [calls (atom [])]
    (with-redefs [lc/sh! (stub-sh calls {:exit 0 :out "" :err ""})]
      (lc/kickstart!)
      (let [[args] @calls]
        (is (= ["launchctl" "kickstart" "-k"] (take 3 args)))
        (is (= (lc/target) (nth args 3)))))))
```

- [ ] **Step 2: Run tests to verify they fail**

Expected: failures on undefined `sh!`, `target`, `loaded?`, `bootstrap!`, `bootout!`, `kickstart!`.

- [ ] **Step 3: Implement**

Add to `src/nido/coordinator/launchctl.clj`:

```clojure
;; Add p to requires:
;;   [babashka.process :as p]

(defn current-uid
  "Resolve the current numeric user id via `id -u`. Cached per JVM."
  []
  (-> (p/sh ["id" "-u"]) :out clojure.string/trim))

(defn target
  "Service target for launchctl subcommands: gui/<uid>/<label>."
  []
  (str "gui/" (current-uid) "/" (label)))

(defn sh!
  "Thin wrapper over babashka.process/sh that returns {:exit :out :err}.
   Wrapped so tests can stub launchctl invocations."
  [args]
  (p/sh args))

(defn loaded?
  "True iff `launchctl print <target>` reports the service as loaded
   (exit 0). Any non-zero exit is treated as 'not loaded'."
  []
  (zero? (:exit (sh! ["launchctl" "print" (target)]))))

(defn bootstrap!
  "Load the plist into the user's launchd domain. RunAtLoad=true in
   the plist means the daemon also starts now. Returns the sh! result."
  []
  (sh! ["launchctl" "bootstrap" (str "gui/" (current-uid)) (plist-path)]))

(defn bootout!
  "Unload the service (kills the running daemon and stops respawn).
   Returns the sh! result."
  []
  (sh! ["launchctl" "bootout" (target)]))

(defn kickstart!
  "Send SIGTERM to the running daemon and immediately start a fresh
   instance. Used by `bb nido:coordinator:restart`. Returns the sh! result."
  []
  (sh! ["launchctl" "kickstart" "-k" (target)]))
```

Add the `[babashka.process :as p]` require to the ns form.

- [ ] **Step 4: Run tests to verify they pass**

```
bb nido:test :only nido.coordinator.launchctl-test
```

Expected: all launchctl tests pass.

- [ ] **Step 5: Commit**

```
jj desc -m "feat(coordinator): launchctl loaded? + bootstrap!/bootout!/kickstart!"
jj new
```

---

### Task 5: `bb nido:coordinator:install` task

Refuse if bare daemon up. Refuse if outside a git checkout. Refuse if `which bb` fails. Otherwise render the plist with resolved paths, write it, and bootstrap.

**Files:**
- Modify: `src/tasks/nido_coordinator.clj`
- Modify: `bb.edn`

- [ ] **Step 1: Add the `install` function**

Add to `src/tasks/nido_coordinator.clj`:

```clojure
;; Add require:
;;   [nido.coordinator.launchctl :as lc]
;;   [babashka.process :as p]
;;   [clojure.string :as str]

(defn- which-bb []
  (let [{:keys [exit out]} (p/sh ["which" "bb"])]
    (when (zero? exit) (str/trim out))))

(defn- git-toplevel []
  (let [{:keys [exit out]} (p/sh ["git" "rev-parse" "--show-toplevel"])]
    (when (zero? exit) (str/trim out))))

(defn install
  "bb nido:coordinator:install — write the LaunchAgent plist and start
   the daemon. Auto-starts at every subsequent login."
  [& _args]
  (cond
    (and (pid/alive?) (not (lc/installed?)))
    (do (println "Coordinator: bare daemon already running (pid" (pid/read) "). Run `bb nido:coordinator:down`, then re-install.")
        (System/exit 1))

    (nil? (git-toplevel))
    (do (println "Coordinator: install must be run from inside the nido git checkout.")
        (System/exit 1))

    (nil? (which-bb))
    (do (println "Coordinator: `which bb` failed. Install babashka first.")
        (System/exit 1))

    :else
    (let [bb-path  (which-bb)
          nido-dir (git-toplevel)
          log-path (cstate/log-path)
          plist    (lc/render-plist
                    {:bb-path  bb-path
                     :nido-dir nido-dir
                     :log-path log-path
                     :path-env "/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin"})]
      ;; If already loaded (e.g., re-install), bootout first so bootstrap
      ;; picks up the new plist contents.
      (when (lc/loaded?)
        (lc/bootout!))
      (lc/write-plist! plist)
      (let [{:keys [exit err]} (lc/bootstrap!)]
        (if (zero? exit)
          (println "Coordinator: installed. Plist at" (lc/plist-path)
                   "— daemon will auto-start at login.")
          (do (println "Coordinator: launchctl bootstrap failed (exit" exit "). stderr:")
              (println err)
              (System/exit exit)))))))
```

- [ ] **Step 2: Register the task in `bb.edn`**

Add after the existing `nido:coordinator:logs` entry:

```clojure
  nido:coordinator:install
  {:doc "Write the LaunchAgent plist and start the daemon. Auto-starts at login."
   :task (apply nido-coordinator/install *command-line-args*)}
```

- [ ] **Step 3: Smoke test by hand**

Run from inside `~/Code/nido`:

```
bb nido:coordinator:status     # ensure no bare daemon
bb nido:coordinator:install
bb nido:coordinator:status     # heartbeat should be fresh
launchctl print "gui/$(id -u)/dev.nido.coordinator" | head -20
```

Expected: status shows `Process: alive`, `Coordinator: running`; `launchctl print` reports the service is loaded.

- [ ] **Step 4: Verify refusal paths**

```
cd /tmp && bb --config "$HOME/Code/nido/bb.edn" nido:coordinator:install
```

Expected: "install must be run from inside the nido git checkout."

(Leave the daemon running for the next task.)

- [ ] **Step 5: Commit**

```
jj desc -m "feat(coordinator): bb nido:coordinator:install — write plist + bootstrap"
jj new
```

---

### Task 6: `bb nido:coordinator:uninstall` task

**Files:**
- Modify: `src/tasks/nido_coordinator.clj`
- Modify: `bb.edn`

- [ ] **Step 1: Add the `uninstall` function**

```clojure
(defn uninstall
  "bb nido:coordinator:uninstall — bootout and remove the plist. Idempotent."
  [& _args]
  (cond
    (not (lc/installed?))
    (do (println "Coordinator: not installed. Nothing to do.")
        (System/exit 0))

    :else
    (do
      (when (lc/loaded?)
        (lc/bootout!))
      (lc/remove-plist!)
      (println "Coordinator: uninstalled. Run `bb nido:coordinator:up` to start manually."))))
```

- [ ] **Step 2: Register the task in `bb.edn`**

```clojure
  nido:coordinator:uninstall
  {:doc "Bootout and remove the LaunchAgent plist. Idempotent."
   :task (apply nido-coordinator/uninstall *command-line-args*)}
```

- [ ] **Step 3: Smoke test**

```
bb nido:coordinator:uninstall
bb nido:coordinator:status           # Process: not running; Launchd: not installed
bb nido:coordinator:uninstall        # idempotent: prints "not installed"
ls ~/Library/LaunchAgents/dev.nido.coordinator.plist 2>&1
```

Expected: status reports the daemon gone; second uninstall is a no-op; plist file is gone.

- [ ] **Step 4: Commit**

```
jj desc -m "feat(coordinator): bb nido:coordinator:uninstall — bootout + remove plist"
jj new
```

---

### Task 7: `bb nido:coordinator:restart` task

**Files:**
- Modify: `src/tasks/nido_coordinator.clj`
- Modify: `bb.edn`

- [ ] **Step 1: Add the `restart` function**

```clojure
(defn restart
  "bb nido:coordinator:restart — restart the daemon via launchctl.
   Errors when the plist is not installed (use down + up instead)."
  [& _args]
  (cond
    (not (lc/installed?))
    (do (println "Coordinator: not installed. Use `bb nido:coordinator:down` then `bb nido:coordinator:up`.")
        (System/exit 1))

    (not (lc/loaded?))
    (let [{:keys [exit err]} (lc/bootstrap!)]
      (if (zero? exit)
        (println "Coordinator: was not loaded; bootstrapped via launchctl.")
        (do (println "Coordinator: launchctl bootstrap failed (exit" exit "). stderr:")
            (println err)
            (System/exit exit))))

    :else
    (let [{:keys [exit err]} (lc/kickstart!)]
      (if (zero? exit)
        (println "Coordinator: restarted via launchctl kickstart -k.")
        (do (println "Coordinator: launchctl kickstart failed (exit" exit "). stderr:")
            (println err)
            (System/exit exit))))))
```

- [ ] **Step 2: Register the task in `bb.edn`**

```clojure
  nido:coordinator:restart
  {:doc "Restart the daemon via launchctl kickstart -k (requires install)."
   :task (apply nido-coordinator/restart *command-line-args*)}
```

- [ ] **Step 3: Smoke test**

```
bb nido:coordinator:install
OLD_PID=$(cat ~/.nido/coordinator/coordinator.pid)
bb nido:coordinator:restart
sleep 2
NEW_PID=$(cat ~/.nido/coordinator/coordinator.pid)
[ "$OLD_PID" != "$NEW_PID" ] && echo "PID changed: $OLD_PID -> $NEW_PID"

bb nido:coordinator:uninstall
bb nido:coordinator:restart        # should error with "not installed"
```

Expected: first restart prints "restarted via launchctl kickstart" and PID changes; second prints the not-installed error.

- [ ] **Step 4: Commit**

```
jj desc -m "feat(coordinator): bb nido:coordinator:restart — kickstart when installed"
jj new
```

---

### Task 8: branch `up` on `(launchctl/installed?)`

**Files:**
- Modify: `src/tasks/nido_coordinator.clj`

- [ ] **Step 1: Update `up`**

Replace the body of `up` (around lines 57-87) with a branch on `(lc/installed?)`:

```clojure
(defn up
  "bb nido:coordinator:up [:poll-ms <int>] — start the daemon.
   If the LaunchAgent plist is installed (bb nido:coordinator:install),
   delegates to launchctl. Otherwise spawns a bare background daemon
   that writes a PID file at ~/.nido/coordinator/coordinator.pid."
  [& args]
  (let [[_ opts] (task-args/split-args args)]
    (cond
      (and (lc/installed?) (lc/loaded?) (pid/alive?))
      (println "Coordinator: already managed by launchd (pid" (pid/read) ").")

      (lc/installed?)
      (let [{:keys [exit err]} (lc/bootstrap!)]
        (if (zero? exit)
          (do (Thread/sleep 500) ; give the daemon a moment to write its PID
              (println "Coordinator: started via launchd (pid" (or (pid/read) "pending") ")."))
          (do (println "Coordinator: launchctl bootstrap failed (exit" exit "). stderr:")
              (println err)
              (System/exit exit))))

      (pid/alive?)
      (do (println "Coordinator: already running (pid" (pid/read) "). Use `bb nido:coordinator:down` to stop.")
          (System/exit 1))

      :else
      ;; Stage 3 bare-spawn path (unchanged).
      (do
        (cstate/ensure-dirs!)
        (let [log-file  (java.io.File. ^String (cstate/log-path))
              cmd       (cond-> ["bb" "nido:coordinator:run"]
                          (:poll-ms opts) (into [":poll-ms" (str (:poll-ms opts))]))
              proc      (p/process cmd {:in        ""
                                        :out       :append
                                        :out-file  log-file
                                        :err       :append
                                        :err-file  log-file
                                        :shutdown  nil})
              child-pid (.pid (:proc proc))]
          (println "Coordinator: starting in background (pid" child-pid ")")
          (println "Logs: " (cstate/log-path))
          (println "Stop: bb nido:coordinator:down"))))))
```

- [ ] **Step 2: Smoke test, with and without install**

```
# When installed
bb nido:coordinator:install
bb nido:coordinator:up         # "already managed by launchd"
bb nido:coordinator:uninstall

# Without install
bb nido:coordinator:up         # Stage 3 path: "starting in background"
bb nido:coordinator:down
```

Expected: both branches behave per the spec.

- [ ] **Step 3: Commit**

```
jj desc -m "feat(coordinator): up — branch on launchctl/installed? for unified surface"
jj new
```

---

### Task 9: branch `down` on `(launchctl/installed?)`

**Files:**
- Modify: `src/tasks/nido_coordinator.clj`

- [ ] **Step 1: Update `down`**

Add a launchctl branch at the top of `down`:

```clojure
(defn down
  "bb nido:coordinator:down [:force true] — stop the background daemon.
   If the LaunchAgent plist is installed, runs `launchctl bootout` so
   the daemon stops AND does not respawn until reinstalled or `up`'d.
   Otherwise sends SIGTERM (or SIGKILL with :force true) to the bare daemon.
   Also accepts :force? (zsh users: quote it as ':force?')."
  [& args]
  (let [[_ opts]  (task-args/split-args args)
        force?    (or (= true (:force opts)) (= true (:force? opts)))
        pid       (pid/read)]
    (cond
      (lc/installed?)
      (let [{:keys [exit err]} (lc/bootout!)]
        (if (zero? exit)
          (println "Coordinator: stopped via launchctl bootout.")
          (do (println "Coordinator: launchctl bootout failed (exit" exit "). stderr:")
              (println err)
              (System/exit exit))))

      (nil? pid)
      (do (println "Coordinator: not running (no PID file).") (System/exit 0))

      (not (pid/alive?))
      (do (println "Coordinator: stale PID file (pid" pid "is not alive). Cleaning up.")
          (pid/delete!)
          (heartbeat/write! {:status :stopped :slots-in-use 0})
          (System/exit 0))

      :else
      ;; Stage 3 SIGTERM/SIGKILL path (unchanged).
      (let [proc-handle (.get ^java.util.Optional (java.lang.ProcessHandle/of (long pid)))
            signal-name (if force? "SIGKILL" "SIGTERM")]
        (println "Coordinator: sending" signal-name "to pid" pid)
        (if force?
          (.destroyForcibly ^java.lang.ProcessHandle proc-handle)
          (.destroy ^java.lang.ProcessHandle proc-handle))
        (let [deadline (+ (System/currentTimeMillis) 30000)]
          (loop []
            (cond
              (not (.isAlive ^java.lang.ProcessHandle proc-handle))
              (do (pid/delete!)
                  (heartbeat/write! {:status :stopped :slots-in-use 0})
                  (println "Coordinator: stopped."))

              (> (System/currentTimeMillis) deadline)
              (do (println "Coordinator: did not exit within 30s. Use :force true to SIGKILL.")
                  (System/exit 2))

              :else
              (do (Thread/sleep 200) (recur)))))))))
```

Note: when `installed?`, `:force` is silently ignored — `launchctl bootout` is already a hard stop. If you want a SIGKILL-equivalent for an installed daemon, uninstall first.

- [ ] **Step 2: Smoke test**

```
# When installed
bb nido:coordinator:install
bb nido:coordinator:down               # "stopped via launchctl bootout"
bb nido:coordinator:status             # Launchd: installed (not loaded); Process: not running
bb nido:coordinator:uninstall

# Without install
bb nido:coordinator:up
bb nido:coordinator:down               # Stage 3 SIGTERM path
```

Expected: both paths work; installed branch leaves the plist file on disk but unloaded.

- [ ] **Step 3: Commit**

```
jj desc -m "feat(coordinator): down — bootout when launchctl-installed"
jj new
```

---

### Task 10: surface launchd state in `status`

**Files:**
- Modify: `src/tasks/nido_coordinator.clj`

- [ ] **Step 1: Update `status` to print two new lines**

Replace the body of `status`:

```clojure
(defn status [& _args]
  (let [p          (cstate/status-path)
        h          (halt/read-halt-info)
        pid        (pid/read)
        proc-alive (pid/alive?)
        installed  (lc/installed?)
        loaded     (when installed (lc/loaded?))]
    (println "Launchd:     "
             (cond
               (and installed loaded)        "installed (loaded)"
               installed                     "installed (not loaded)"
               :else                         "not installed"))
    (println "Managed by:  " (if installed "launchd" "none"))
    (println "Process:     "
             (cond
               (and pid proc-alive) (str "alive (pid " pid ")")
               pid                  (str "stale PID file (pid " pid " is not alive)")
               :else                "not running (no PID file)"))
    (if (fs/exists? p)
      (let [s (io/read-edn p)]
        (println "Coordinator:" (some-> (:status s) name))
        (println "Heartbeat:  " (:heartbeat-at s))
        (println "Slots:      " (:slots-in-use s)))
      (println "Coordinator: no status.edn (never started or already cleaned up)"))
    (when h
      (println "Halted:     " (name (:source h)) "—" (or (:note h) "(no note)"))
      (println "Halted at:  " (:halted-at h)))))
```

- [ ] **Step 2: Smoke test**

```
bb nido:coordinator:install
bb nido:coordinator:status   # all four lines: Launchd installed(loaded), Managed by launchd, Process alive, Coordinator running
bb nido:coordinator:down
bb nido:coordinator:status   # Launchd installed(not loaded), Process not running, Coordinator stopped
bb nido:coordinator:uninstall
bb nido:coordinator:status   # Launchd not installed, Managed by none
```

Expected: all four state transitions print cleanly.

- [ ] **Step 3: Commit**

```
jj desc -m "feat(coordinator): status surfaces launchd state"
jj new
```

---

### Task 11: update CLAUDE.md with Stage 4 verbs

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Find the "Running the daemon (Stage 3)" block and add a Stage 4 block below it**

Locate the section in `CLAUDE.md` around the "Running the daemon (Stage 3):" header (currently near line 137). Add this **after** the existing Stage 3 code block:

```markdown
**Auto-start at login (Stage 4):**

```
bb nido:coordinator:install     # write LaunchAgent plist + bootstrap; daemon runs now + at every login
bb nido:coordinator:uninstall   # bootout + remove plist
bb nido:coordinator:restart     # launchctl kickstart -k (requires install)
```

Once installed, `bb nido:coordinator:up` / `down` wrap `launchctl bootstrap` / `bootout` automatically; status surfaces a `Launchd:` line so you can see which lifecycle is in charge. `install` refuses if a bare daemon is already running — `down` it first.
```

Also update the existing Stage 4 mention at the bottom (the "Stage 4 will add launchd auto-start at login" sentence) to past tense, e.g. "Stage 4 added launchd auto-start at login; stages 5+ add Notion / cron / GitHub event sources."

- [ ] **Step 2: Commit**

```
jj desc -m "docs(nido): Stage 4 launchd verbs in CLAUDE.md"
jj new
```

---

### Task 12: end-to-end manual smoke test

This is a verification checklist — no code changes, but the proof that Stage 4 actually works under launchd.

**Files:** none.

- [ ] **Step 1: Fresh install**

```
bb nido:coordinator:uninstall                  # ensure clean
bb nido:coordinator:down                       # ensure no bare daemon
bb nido:coordinator:install
bb nido:coordinator:status
```

Expected: `Launchd: installed (loaded)`, `Process: alive`, `Coordinator: running`.

- [ ] **Step 2: Verify auto-restart on crash**

```
PID=$(cat ~/.nido/coordinator/coordinator.pid)
kill -9 "$PID"
sleep 12       # ThrottleInterval=10s + spawn time
bb nido:coordinator:status
NEW_PID=$(cat ~/.nido/coordinator/coordinator.pid)
[ "$PID" != "$NEW_PID" ] && echo "Respawned: $PID -> $NEW_PID"
```

Expected: status reports `Process: alive` with a different PID; respawn message printed.

- [ ] **Step 3: Verify survives logout/login**

Log out and back in. Open a terminal.

```
bb nido:coordinator:status
```

Expected: `Launchd: installed (loaded)`, `Process: alive`. The daemon must be running without anyone having run `up`.

- [ ] **Step 4: Verify clean uninstall**

```
bb nido:coordinator:uninstall
bb nido:coordinator:status
ls ~/Library/LaunchAgents/dev.nido.coordinator.plist 2>&1
```

Expected: `Launchd: not installed`, `Process: not running`, plist file gone (`No such file or directory`).

- [ ] **Step 5: Commit nothing; mark Stage 4 done**

If everything above passes, Stage 4 is done. Update the parent spec's "Status:" line to `implemented`:

```
# In docs/superpowers/specs/2026-05-15-nido-stage-4-launchd-design.md:
# Change "Status: designed, not implemented" → "Status: implemented"

jj desc -m "docs(nido): mark Stage 4 spec as implemented"
```

---

## Self-review

**Spec coverage:**
- Goal: install writes plist + starts daemon → Task 5.
- Goal: auto-start at every login → Task 12 step 3 verifies it.
- Goal: respawn on crash → Task 12 step 2 verifies it.
- Goal: up/down/status work unchanged when not installed → Tasks 8, 9, 10 keep the Stage 3 branch.
- Goal: status surface tells which lifecycle is in charge → Task 10.
- Plist contract (every field listed in spec) → Task 2 golden test covers every field by name.
- Module layout (paths, label, target, installed?, loaded?, render-plist, write-plist!, remove-plist!, bootstrap!, bootout!, kickstart!) → Tasks 1-4 cover all of them.
- CLI changes (install/uninstall/restart) → Tasks 5, 6, 7.
- CLI changes (up/down/status modified) → Tasks 8, 9, 10.
- Failure modes table from spec → covered case-by-case in Tasks 5-9.
- Testing approach → unit tests in Tasks 1-4 cover `launchctl` module; task-layer smoke-tested manually (Tasks 5-9), matches Stage 3 precedent.

**Placeholder scan:** none found.

**Type consistency:** `lc/render-plist` keyword args (`:bb-path` / `:nido-dir` / `:log-path` / `:path-env`) match between Task 2 and Task 5. `lc/installed?` / `lc/loaded?` / `lc/bootstrap!` / `lc/bootout!` / `lc/kickstart!` named identically throughout. PID-file probe uses `pid/alive?` (matches existing module).

**One issue noted and addressed inline:** when `installed?`, `down` silently ignores `:force` because `launchctl bootout` is already a hard stop — this is called out in the Task 9 note rather than left implicit.

---

## Execution handoff

After saving the plan, the user can choose either subagent-driven execution (fresh agent per task) or inline execution (this session, batched).
