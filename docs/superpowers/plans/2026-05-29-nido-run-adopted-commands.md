# nido:run — adopted target-project commands Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `bb nido:run :project <p> <session> <command-ref>`, a generic passthrough that runs a project-declared `:project-commands` entry inside a session's worktree, so a nido-managed brian session can invoke brian's own `bb ci`.

**Architecture:** A thin task (`tasks.nido-run`) over a small logic namespace (`nido.run`), reusing the existing `nido.commands/run-command!` engine and the existing `:project-commands` config map. `nido.run` resolves the session worktree, builds a session-scoped substitution context (`{{session.worktree}}`), and forwards the command with inherited IO; the task propagates the command's exit code. Brian adopts CI by adding one `:ci` entry to its nido `session.edn` — nothing brian-specific lives in nido source.

**Tech Stack:** Babashka, Clojure, `clojure.test`, `babashka.fs`, `babashka.process` (via `nido.commands`).

**Spec:** `docs/superpowers/specs/2026-05-29-nido-run-adopted-commands-design.md`

---

## File Structure

- Create `src/nido/run.clj` — logic: resolve worktree, build session context, call `run-command!`. Returns the shell result (testable, no `System/exit`).
- Create `test/nido/run_test.clj` — unit tests (Docker-free; uses `touch`/marker-file to prove cwd).
- Create `src/tasks/nido_run.clj` — thin bb task: parse args, call `nido.run`, `System/exit` with the command's exit code.
- Modify `bb.edn` — require `tasks.nido-run`; register the `nido:run` task.
- Modify `src/tasks/nido_help.clj` — add a generic "Project commands" group listing `nido:run`.
- Runtime config (NOT committed): `~/.nido/projects/brian/session.edn` — add the `:ci` entry to `:project-commands`.

Pattern note: this mirrors the existing `tasks.nido-template` → `nido.template` split (thin task over logic ns). Brian's worktrees live in-repo at `~/Code/brian/.worktrees/<session>` (`:worktrees-dir ".worktrees"`), resolved for us by `nido.session.lifecycle/worktree-path`.

---

### Task 1: `nido.run/session-context` (pure)

**Files:**
- Create: `src/nido/run.clj`
- Test: `test/nido/run_test.clj`

- [ ] **Step 1: Write the failing test**

Create `test/nido/run_test.clj`:

```clojure
(ns nido.run-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.config :as config]
   [nido.run :as run]
   [nido.session.engine :as engine]
   [nido.session.lifecycle :as lifecycle]))

(deftest session-context-shape
  (is (= {:project {:name "brian" :dir "/x"}
          :session {:name "feat" :worktree "/x/.worktrees/feat"}}
         (run/session-context "brian" "/x" "feat" "/x/.worktrees/feat"))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bb nido:test :only nido.run`
Expected: FAIL — `nido.run` namespace does not exist / `session-context` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `src/nido/run.clj`:

```clojure
(ns nido.run
  "Run a project-declared :project-commands entry inside a session's worktree.
   The generic 'adopt a target-project command' primitive: nido resolves where
   to run (the session worktree) and forwards the command via nido.commands;
   the command's behaviour and output belong to the target project."
  (:require
   [babashka.fs :as fs]
   [nido.commands :as commands]
   [nido.config :as config]
   [nido.session.engine :as engine]
   [nido.session.lifecycle :as lifecycle]))

(defn session-context
  "Substitution context for a session-scoped project-command. Adds the
   :session layer (so commands can template {{session.worktree}}) on top of
   the :project layer the existing template steps already use."
  [project-name project-dir session-name worktree]
  {:project {:name project-name :dir project-dir}
   :session {:name session-name :worktree worktree}})
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bb nido:test :only nido.run`
Expected: PASS (1 test).

- [ ] **Step 5: Commit**

```bash
jj new -m "feat(run): nido.run/session-context for session-scoped commands"
# (working copy already holds the changes; jj auto-snapshots. Verify:)
jj st
```

---

### Task 2: `nido.run/run-command-in-session!` happy path

**Files:**
- Modify: `src/nido/run.clj`
- Test: `test/nido/run_test.clj`

- [ ] **Step 1: Write the failing test**

Append to `test/nido/run_test.clj`:

```clojure
(deftest runs-command-in-worktree
  (let [tmp  (fs/create-temp-dir)
        pdir (str (fs/path tmp "proj"))
        wt   (str (fs/path tmp "wt" "feat"))]
    (fs/create-dirs pdir)
    (fs/create-dirs wt)
    (try
      (with-redefs [config/read-projects   (constantly {"p" {:directory pdir}})
                    lifecycle/worktree-path (constantly wt)
                    engine/load-session-edn (constantly
                                             {:project-commands
                                              {:marker {:cwd "{{session.worktree}}"
                                                        :cmd "touch ran-here.txt"}}})]
        (let [result (run/run-command-in-session! "p" "feat" :marker)]
          (testing "command exits zero"
            (is (zero? (:exit result))))
          (testing "command ran in the worktree (cwd = {{session.worktree}})"
            (is (fs/exists? (fs/path wt "ran-here.txt"))))))
      (finally (fs/delete-tree tmp)))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bb nido:test :only nido.run`
Expected: FAIL — `run-command-in-session!` unresolved.

- [ ] **Step 3: Write minimal implementation**

Add to `src/nido/run.clj` (after `session-context`):

```clojure
(defn- resolve-project-dir
  [project-name]
  (or (get-in (config/read-projects) [project-name :directory])
      (throw (ex-info (str "Project not registered: " project-name)
                      {:hint "Run `bb nido:project:add <name> <directory>` first."
                       :project-name project-name}))))

(defn run-command-in-session!
  "Resolve the session worktree and run the named :project-commands entry there
   with live (inherited) IO. Returns the babashka.process result map (:exit ...).
   Throws if the project is unregistered, the worktree is missing, or the ref
   is not a declared command."
  [project-name session-name ref]
  (let [project-dir (resolve-project-dir project-name)
        worktree    (lifecycle/worktree-path project-name project-dir session-name)]
    (when-not (fs/exists? worktree)
      (throw (ex-info (str "Worktree not found for " project-name "/" session-name)
                      {:worktree worktree
                       :hint "Bring the session up first: bb nido:session:up :project <p> <session>"})))
    (let [session-edn (engine/load-session-edn project-name)
          context     (session-context project-name project-dir session-name worktree)]
      (commands/run-command! (:project-commands session-edn) ref context
                             {:continue? true :out :inherit :err :inherit}))))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bb nido:test :only nido.run`
Expected: PASS (2 tests). The `touch ran-here.txt` output may print to stdout (inherited IO) — that's expected.

- [ ] **Step 5: Commit**

```bash
jj new -m "feat(run): run-command-in-session! resolves worktree + forwards command"
jj st
```

---

### Task 3: Error paths (unknown ref, missing worktree)

**Files:**
- Test: `test/nido/run_test.clj`

No implementation change needed — the "unknown command" throw comes from `nido.commands/resolve-command`, and the "worktree not found" throw is already in `run-command-in-session!`. These tests lock that behavior in.

- [ ] **Step 1: Write the failing tests**

Append to `test/nido/run_test.clj`:

```clojure
(deftest unknown-ref-throws
  (let [tmp (fs/create-temp-dir)
        wt  (str (fs/path tmp "wt" "feat"))]
    (fs/create-dirs wt)
    (try
      (with-redefs [config/read-projects    (constantly {"p" {:directory (str tmp)}})
                    lifecycle/worktree-path  (constantly wt)
                    engine/load-session-edn  (constantly {:project-commands {:ci {:cmd "true"}}})]
        (is (thrown-with-msg? Exception #"Unknown project-command"
              (run/run-command-in-session! "p" "feat" :nope))))
      (finally (fs/delete-tree tmp)))))

(deftest missing-worktree-throws
  (let [tmp (fs/create-temp-dir)
        wt  (str (fs/path tmp "does-not-exist"))]
    (try
      (with-redefs [config/read-projects    (constantly {"p" {:directory (str tmp)}})
                    lifecycle/worktree-path  (constantly wt)
                    engine/load-session-edn  (constantly {:project-commands {}})]
        (is (thrown-with-msg? Exception #"Worktree not found"
              (run/run-command-in-session! "p" "feat" :ci))))
      (finally (fs/delete-tree tmp)))))
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `bb nido:test :only nido.run`
Expected: PASS (4 tests). (These assert already-implemented behavior; if either fails, the message regex or throw site is wrong — fix before continuing.)

- [ ] **Step 3: Commit**

```bash
jj new -m "test(run): lock unknown-ref and missing-worktree error paths"
jj st
```

---

### Task 4: `tasks.nido-run` bb task + registration

**Files:**
- Create: `src/tasks/nido_run.clj`
- Modify: `bb.edn` (require list + task map)

No unit test (the task's only added behavior over `nido.run` is arg-parsing + `System/exit`, which kills the test JVM). Verified manually in Step 4.

- [ ] **Step 1: Create the task namespace**

Create `src/tasks/nido_run.clj`:

```clojure
(ns tasks.nido-run
  "Bb task: run a project-declared :project-commands entry inside a session's
   worktree. The generic 'adopt a target-project command' surface.

   Usage:
     bb nido:run :project <p> <session> <command-ref>

   Example (brian adopts its CI as :ci in session.edn):
     bb nido:run :project brian feat-x ci

   The command runs in the session's worktree with live output, and the task
   exits with the command's own exit code. A ref the project hasn't declared
   lists the available commands. Only projects whose session.edn declares a
   matching :project-commands entry can run it — nothing brian-specific lives
   here.

   The command ref is a bare positional word (`ci`), coerced to a keyword. Do
   not pass it as `:ci` — a leading colon makes the CLI parser treat it as a
   kwarg key."
  (:require
   [clojure.string :as str]
   [nido.run :as run]
   [nido.task-args :as task-args]))

(defn- require-project [opts]
  (or (some-> (:project opts) name)
      (throw (ex-info "Missing :project <name>"
                      {:hint "Pass :project <project-name> — the name used in `bb nido:project:add`."}))))

(defn run
  "Run the named project-command in a session worktree and exit with its code."
  [& args]
  (let [[pos opts] (task-args/split-args args)
        project    (require-project opts)
        [session ref-str] pos]
    (when-not (and session ref-str)
      (throw (ex-info "Usage: bb nido:run :project <p> <session> <command-ref>"
                      {:positionals pos
                       :hint "e.g. bb nido:run :project brian feat-x ci"})))
    (when (> (count pos) 2)
      (throw (ex-info "Too many positional args; expected <session> <command-ref>"
                      {:positionals pos})))
    (let [ref    (keyword (str/replace (str ref-str) #"^:" ""))
          result (run/run-command-in-session! project session ref)]
      (System/exit (int (:exit result))))))
```

- [ ] **Step 2: Register in `bb.edn`**

In the `:requires` vector (after `[tasks.nido-ui :as nido-ui]`), add:

```clojure
             [tasks.nido-run :as nido-run]
```

In the `:tasks` map, add (e.g. directly before the `nido:test` entry):

```clojure
  nido:run
  {:doc "Run a project-declared command in a session worktree: :project <p> <session> <command-ref>"
   :task (apply nido-run/run *command-line-args*)}
```

- [ ] **Step 3: Verify the task loads**

Run: `bb nido:help`
Expected: runs clean (exit 0). This loads every `:requires` namespace, so a clean run confirms `tasks.nido-run` and `nido.run` compile.

- [ ] **Step 4: Verify error paths manually (no Docker)**

Run: `bb nido:run :project brian no-such-session ci`
Expected: error "Worktree not found for brian/no-such-session" with the `session:up` hint, nonzero exit.

Run (against a real, already-created brian session — substitute its name; this needs a worktree on disk but does NOT run CI): `bb nido:run :project brian <existing-session> bogus-ref`
Expected: error "Unknown project-command: :bogus-ref" listing `:available` (will include the db commands; `:ci` appears once Task 6 is done).

- [ ] **Step 5: Commit**

```bash
jj new -m "feat(run): bb nido:run task — invoke adopted commands in a worktree"
jj st
```

---

### Task 5: Help entry

**Files:**
- Modify: `src/tasks/nido_help.clj`

- [ ] **Step 1: Add a generic "Project commands" group**

In `src/tasks/nido_help.clj`, in the `groups` vector, add this group immediately before the `{:title "UI" ...}` group:

```clojure
   {:title "Project commands"
    :tasks [["nido:run" ":project <p> <session> <command-ref> — run an adopted command in the worktree"]]}
```

- [ ] **Step 2: Verify it renders**

Run: `bb nido:help`
Expected: output includes a "Project commands" section listing `nido:run`. Exit 0.

- [ ] **Step 3: Commit**

```bash
jj new -m "docs(help): list nido:run under Project commands"
jj st
```

---

### Task 6: Brian adopts its CI (runtime config — NOT committed)

**Files:**
- Modify: `~/.nido/projects/brian/session.edn` (nido runtime config, outside the repo)

- [ ] **Step 1: Add the `:ci` entry**

Edit `~/.nido/projects/brian/session.edn`. Inside the `:project-commands` map (alongside `:db/get-dump` and `:db/restore-into-template`), add:

```clojure
  ;; Brian's own local CI suite, run against the session worktree. Fully
  ;; Dockerized + self-contained (own postgres/app, path-isolated), so it
  ;; needs only the worktree on disk — no nido services required.
  :ci
  {:cwd "{{session.worktree}}"
   :cmd "bb ci --all-failures"}
```

- [ ] **Step 2: Verify the ref is now declared (no Docker)**

Run (against a real brian session worktree): `bb nido:run :project brian <existing-session> bogus-ref`
Expected: "Unknown project-command: :bogus-ref" and the `:available` list now contains `:ci`.

- [ ] **Step 3: Full end-to-end (optional — runs real, Docker-heavy CI)**

Run: `bb nido:run :project brian <existing-session> ci`
Expected: brian's `bb ci --all-failures` runs in `~/Code/brian/.worktrees/<session>`, streams its job output and `ACTION REQUIRED:` tail, and the `bb nido:run` process exits with brian CI's exit code (0 all-pass / 1 any-failure). This can take many minutes and requires Docker; skip in routine verification.

- [ ] **Step 4: No commit**

This file is nido runtime config under `~/.nido/`, outside the repo — there is nothing to commit. Note in the implementation summary that brian's adoption config was applied.

---

## Self-Review

**Spec coverage:**
- Generic passthrough task → Tasks 1–5 (`nido.run` + `tasks.nido-run` + registration). ✓
- Reuse `nido.commands` / `:project-commands`, no new noun → Task 2 calls `run-command!` against `:project-commands`. ✓
- Session-scoped context `{{session.worktree}}` → Task 1 `session-context` + Task 2 wiring + Task 2 cwd assertion. ✓
- Per-project / unknown-ref error / no brian string in source → Task 3 `unknown-ref-throws`; brian-only via config in Task 6. ✓
- No session-up dependency / worktree-only / missing-worktree error → Task 2 `fs/exists?` guard + Task 3 `missing-worktree-throws`. ✓
- Exit-code propagation → Task 4 `System/exit (int (:exit result))`. ✓
- No TUI surface → no `src/nido/tui.clj` changes in any task. ✓
- Brian adoption entry in runtime config → Task 6. ✓
- Docker-free automated tests → Tasks 1–3 use `touch`/`true`/redefs only. ✓

**Placeholder scan:** No TBD/TODO; every code step shows full code; every command shows expected output. ✓

**Type/name consistency:** `session-context` (4 args: project-name, project-dir, session-name, worktree) defined in Task 1 and called identically in Task 2. `run-command-in-session!` (3 args: project-name, session-name, ref) defined in Task 2, called identically in Tasks 2/3. `run-command!` opts `{:continue? true :out :inherit :err :inherit}` match `nido.commands/run-command!`'s documented opts. `:exit` key read from the result map matches `babashka.process` shell result. ✓
