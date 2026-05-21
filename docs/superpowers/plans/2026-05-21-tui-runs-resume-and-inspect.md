# TUI runs screen — resume on enter, inspect on `w` — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `↵` on a "Needs attention" row in the TUI runs surface bring the session back up automatically, and make `w` resolve to the on-disk worktree without needing a live session-home.

**Architecture:** Two narrow changes. (1) `nido.session.lifecycle/enter!` learns an `:auto-up?` option (call `up!` first, idempotent) and a session-home-independent fallback for `:cd :worktree` (resolve `worktree-path` directly when the session-home symlink is gone). (2) `tasks.nido-tui` reinterprets the `:enter-run` action: `:home` routes through `up!` + enter; `:worktree` calls `enter!` with `:auto-up? false` and surfaces the focused "worktree no longer exists" error instead of today's misleading "no session home". The TUI runs-screen legend is reworded to match the new semantics.

**Tech Stack:** Clojure (Babashka), `babashka.fs`, `clojure.test`.

**Spec:** `docs/superpowers/specs/2026-05-21-tui-runs-resume-and-inspect-design.md`

---

## File map

- Modify: `src/nido/session/lifecycle.clj` — add `:auto-up?` branch and worktree fallback in `enter!`.
- Modify: `src/tasks/nido_session.clj` — pass `:auto-up?` through from the task arg map (no change to surface flags; the TUI wrapper supplies the opt directly).
- Modify: `src/tasks/nido_tui.clj` — `:enter-run` action splits into the up+enter path (`:home`) and the worktree-direct path (`:worktree`).
- Modify: `src/nido/tui.clj` — update the runs-screen legend (the help line printed under the list).
- Modify: `test/nido/session/lifecycle_test.clj` — cover `:auto-up?` and the worktree fallback.

No new files. No new dependencies. No public API additions beyond an internal opt on `enter!`.

---

## Task 1: `enter!` learns `:auto-up?`

**Files:**
- Modify: `src/nido/session/lifecycle.clj:415-444`
- Test: `test/nido/session/lifecycle_test.clj`

- [ ] **Step 1: Start a fresh changeset**

```bash
jj new -m "feat(session/lifecycle): enter! :auto-up? brings session up before cd"
```

- [ ] **Step 2: Write the failing test**

Append to `test/nido/session/lifecycle_test.clj`:

```clojure
(deftest enter!-auto-up?-calls-up!-when-session-home-missing
  (let [tmp (fs/create-temp-dir)
        project-name "fakeproj"
        session-name "feat-x"
        session-home (str (fs/path tmp "sessions" project-name session-name))
        up-called?   (atom false)]
    (try
      (with-redefs [nido.core/nido-home              (constantly (str tmp))
                    nido.session.state/session-home-dir (fn [_ _] session-home)
                    nido.session.lifecycle/resolve-project
                    (fn [_] [project-name {:directory (str tmp)}])
                    nido.session.lifecycle/up!
                    (fn [n _]
                      (reset! up-called? true)
                      ;; simulate up! creating the session-home
                      (fs/create-dirs session-home))]
        (lifecycle/enter! session-name {:project project-name :auto-up? true})
        (is @up-called? "up! must be called when :auto-up? true")
        (is (= session-home
               (slurp (str (fs/path tmp ".last-cd"))))
            "after auto-up, .last-cd points at the session-home"))
      (finally (fs/delete-tree tmp)))))
```

- [ ] **Step 3: Run the test, expect failure**

Run: `bb test --focus nido.session.lifecycle-test/enter!-auto-up?-calls-up!-when-session-home-missing`
Expected: FAIL — current `enter!` throws "No session home" because `up!` was never invoked.

- [ ] **Step 4: Implement `:auto-up?`**

In `src/nido/session/lifecycle.clj`, modify `enter!`:

```clojure
(defn enter!
  "Hand off a cwd to the parent shell via `cd-target-file`. bb cannot change
   its parent's cwd, so a tiny zsh function (see Nido's CLAUDE.md) reads
   this file after the bb task exits and `cd`s the user there.

   `:cd` selects the target:
     :home (default) — the session-home (CLAUDE.md, .mcp.json live here)
     :worktree       — the worktree symlink inside session-home

   `:auto-up?` (default false) — call `up!` first. Idempotent on a running
   session. Used by the TUI runs screen so `↵` on an idle-stopped run
   transparently resumes the session.

   Throws if the session isn't running and `:auto-up?` is false, or if
   `:cd worktree` is requested and the worktree path cannot be resolved
   (neither session-home/worktree nor the on-disk worktree exists)."
  [name opts]
  (when (:auto-up? opts) (up! name opts))
  (let [[project-name _] (resolve-project opts)
        cd-target    (parse-cd-target (:cd opts))
        session-home (state/session-home-dir project-name name)]
    (when-not (fs/exists? session-home)
      (throw (ex-info (str "No session home for '" name "' — is it running?")
                      {:expected session-home
                       :hint "Run `bb nido:session:up` to bring it up."})))
    (let [resolved (case cd-target
                     :home     session-home
                     :worktree (str (fs/path session-home "worktree")))]
      (when (and (= :worktree cd-target) (not (fs/exists? resolved)))
        (throw (ex-info (str "No worktree symlink for '" name "'")
                        {:expected resolved
                         :hint "Run `bb nido:session:up` to refresh the session-home."})))
      (let [target (cd-target-file)]
        (fs/create-dirs (fs/parent target))
        (spit target resolved)
        (core/log-step (str "Selected " resolved))))))
```

- [ ] **Step 5: Run the test, expect pass**

Run: `bb test --focus nido.session.lifecycle-test/enter!-auto-up?-calls-up!-when-session-home-missing`
Expected: PASS.

- [ ] **Step 6: Run the full lifecycle test ns to confirm no regression**

Run: `bb test --focus nido.session.lifecycle-test`
Expected: PASS for all tests.

- [ ] **Step 7: Commit**

```bash
jj squash -m "feat(session/lifecycle): enter! :auto-up? brings session up before cd"
```

(If the diff already lives in `@`, just `jj desc -m "..."` to set the message.)

---

## Task 2: Session-home-independent worktree fallback

**Files:**
- Modify: `src/nido/session/lifecycle.clj` (the `enter!` body)
- Test: `test/nido/session/lifecycle_test.clj`

- [ ] **Step 1: Start a fresh changeset**

```bash
jj new -m "feat(session/lifecycle): enter! :cd worktree resolves on-disk path when session-home is gone"
```

- [ ] **Step 2: Write the failing test**

Append to `test/nido/session/lifecycle_test.clj`:

```clojure
(deftest enter!-worktree-falls-back-to-on-disk-path-when-session-home-missing
  (let [tmp (fs/create-temp-dir)
        project-name "fakeproj"
        project-dir  (str (fs/path tmp "src" project-name))
        session-name "feat-x"
        wt-root      (str (fs/path tmp "src" (str project-name "-worktrees")))
        wt-path      (str (fs/path wt-root session-name))
        session-home (str (fs/path tmp "sessions" project-name session-name))]
    (try
      (fs/create-dirs wt-path)               ; on-disk worktree exists
      (fs/create-dirs project-dir)
      ;; session-home is deliberately NOT created
      (with-redefs [nido.core/nido-home              (constantly (str tmp))
                    nido.session.state/session-home-dir (fn [_ _] session-home)
                    nido.session.lifecycle/resolve-project
                    (fn [_] [project-name {:directory project-dir}])
                    nido.session.engine/load-session-edn
                    (fn [_] {})]              ; default worktrees-dir
        (lifecycle/enter! session-name {:project project-name :cd :worktree})
        (is (= wt-path
               (slurp (str (fs/path tmp ".last-cd"))))
            ".last-cd points at the on-disk worktree, not the session-home symlink"))
      (finally (fs/delete-tree tmp)))))

(deftest enter!-worktree-throws-focused-error-when-worktree-also-gone
  (let [tmp (fs/create-temp-dir)
        project-name "fakeproj"
        project-dir  (str (fs/path tmp "src" project-name))
        session-name "feat-x"
        session-home (str (fs/path tmp "sessions" project-name session-name))]
    (try
      (fs/create-dirs project-dir)
      (with-redefs [nido.core/nido-home              (constantly (str tmp))
                    nido.session.state/session-home-dir (fn [_ _] session-home)
                    nido.session.lifecycle/resolve-project
                    (fn [_] [project-name {:directory project-dir}])
                    nido.session.engine/load-session-edn
                    (fn [_] {})]
        (let [ex (try (lifecycle/enter! session-name
                                        {:project project-name :cd :worktree})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is ex "enter! must throw when neither session-home nor worktree exists")
          (is (re-find #"Worktree no longer exists for 'feat-x'" (ex-message ex)))))
      (finally (fs/delete-tree tmp)))))
```

- [ ] **Step 3: Run the tests, expect failure**

Run: `bb test --focus nido.session.lifecycle-test/enter!-worktree-falls-back-to-on-disk-path-when-session-home-missing`
Expected: FAIL — `enter!` throws "No session home" before considering the fallback.

- [ ] **Step 4: Implement the worktree fallback**

In `src/nido/session/lifecycle.clj`, replace the `enter!` body's session-home check + worktree resolution with:

```clojure
(defn enter!
  "Hand off a cwd to the parent shell via `cd-target-file`. bb cannot change
   its parent's cwd, so a tiny zsh function (see Nido's CLAUDE.md) reads
   this file after the bb task exits and `cd`s the user there.

   `:cd` selects the target:
     :home (default) — the session-home (CLAUDE.md, .mcp.json live here)
     :worktree       — the worktree symlink inside session-home, with a
                       fallback to the on-disk worktree path when the
                       session-home is gone (idle-stopped sessions retain
                       their worktree).

   `:auto-up?` (default false) — call `up!` first. Idempotent on a running
   session. Used by the TUI runs screen so `↵` on an idle-stopped run
   transparently resumes the session.

   Throws if `:cd home` is requested without `:auto-up?` and the
   session-home is missing, or if `:cd worktree` is requested and neither
   the session-home symlink nor the on-disk worktree exists."
  [name opts]
  (when (:auto-up? opts) (up! name opts))
  (let [[project-name project] (resolve-project opts)
        cd-target    (parse-cd-target (:cd opts))
        session-home (state/session-home-dir project-name name)
        home-exists? (fs/exists? session-home)]
    (case cd-target
      :home
      (do
        (when-not home-exists?
          (throw (ex-info (str "No session home for '" name "' — is it running?")
                          {:expected session-home
                           :hint "Run `bb nido:session:up` to bring it up."})))
        (let [target (cd-target-file)]
          (fs/create-dirs (fs/parent target))
          (spit target session-home)
          (core/log-step (str "Selected " session-home))))

      :worktree
      (let [via-home   (str (fs/path session-home "worktree"))
            on-disk    (worktree-path project-name (:directory project) name)
            resolved   (cond
                         (and home-exists? (fs/exists? via-home)) via-home
                         (fs/exists? on-disk)                     on-disk
                         :else                                    nil)]
        (when-not resolved
          (throw (ex-info (str "Worktree no longer exists for '" name "'")
                          {:session-home via-home
                           :on-disk      on-disk
                           :hint "Run `bb nido:session:up` to recreate the worktree."})))
        (let [target (cd-target-file)]
          (fs/create-dirs (fs/parent target))
          (spit target resolved)
          (core/log-step (str "Selected " resolved)))))))
```

- [ ] **Step 5: Run the new tests, expect pass**

Run: `bb test --focus nido.session.lifecycle-test`
Expected: PASS for all tests including both new fallback tests.

- [ ] **Step 6: Commit**

```bash
jj squash -m "feat(session/lifecycle): enter! :cd worktree resolves on-disk path when session-home is gone"
```

---

## Task 3: TUI runs-screen action plumbing

**Files:**
- Modify: `src/tasks/nido_tui.clj:86-101`
- Modify: `src/tasks/nido_session.clj:100-114` (pass `:auto-up?` through)

- [ ] **Step 1: Start a fresh changeset**

```bash
jj new -m "feat(tui/runs): enter resumes session, w inspects worktree"
```

- [ ] **Step 2: Add `:auto-up` flag handling in the session task**

In `src/tasks/nido_session.clj`, modify `enter` to recognize `:auto-up` from the task-args opts (passed through unchanged into `lifecycle/enter!`). `task-args/split-args` already turns `:auto-up true` into `{:auto-up true}` on the opts map, but `enter!` expects the key `:auto-up?`. Translate at the task boundary:

```clojure
(defn enter
  "Hand off a cwd to the parent shell via `~/.nido/.last-cd`. Paired with
   a tiny shell function (see Nido's CLAUDE.md → Shell wrapper) the user
   lands in the chosen directory with no nested shell.

   `:cd home` (default) → session-home (CLAUDE.md, .mcp.json live here).
   `:cd worktree`       → the worktree symlink, falling back to the
                          on-disk worktree path when the session-home
                          is gone.
   `:auto-up true`      → bring the session up first (idempotent). The
                          TUI runs screen uses this so `↵` on an
                          idle-stopped run transparently resumes."
  [& args]
  (let [[pos opts] (task-args/split-args args)
        _project   (require-project opts)
        session    (require-session-name pos)
        opts'      (cond-> opts
                     (contains? opts :auto-up) (-> (assoc :auto-up? (:auto-up opts))
                                                   (dissoc :auto-up)))]
    (lifecycle/enter! session opts')))
```

- [ ] **Step 3: Rewire `:enter-run` in the TUI wrapper**

In `src/tasks/nido_tui.clj`, change the `run-action` case for `:enter-run` so `:home` requests an auto-up; `:worktree` stays a pure resolve:

```clojure
      :enter-run (let [[_ p s target] action]
                   ;; Runs-screen variant. Sessions for runs are usually
                   ;; idle-stopped by the watchdog. `:home` auto-ups so
                   ;; `↵` becomes "resume"; `:worktree` resolves the
                   ;; on-disk path without touching services so `w` is
                   ;; a cheap inspect.
                   (case target
                     :home
                     (session/enter ":project" p s
                                    ":cd" "home" ":auto-up" "true")
                     :worktree
                     (session/enter ":project" p s ":cd" "worktree")))
```

- [ ] **Step 4: Smoke-test the change against a real awaiting-review run**

(Manual — there's no fast automated test for the bb-task wrapper that doesn't require booting PG/JVM.)

1. Find an awaiting-review run: `bb nido:runs:list | grep awaiting`
2. `nido` → arrow to the run → `↵`
3. Expected output:
   ```
   Worktree already exists at … — starting session.
   ```
   followed by service-up logs, then the shell `cd`s into the session-home.
4. From the same row, press `w` instead → expected: instant cd into the worktree without any service-up log lines.
5. `bb nido:session:destroy :project <p> <session>` to remove the worktree, then in the TUI press `w` on the same row → expected: `[nido:tui] action failed: Worktree no longer exists for '<session>' (see .../tui.log)`.

- [ ] **Step 5: Commit**

```bash
jj squash -m "feat(tui/runs): enter resumes session, w inspects worktree"
```

---

## Task 4: Update the runs-screen legend

**Files:**
- Modify: `src/nido/tui.clj` (the runs-screen help line, around the bottom of `view-runs`)

- [ ] **Step 1: Start a fresh changeset**

```bash
jj new -m "docs(tui/runs): legend reflects resume/inspect semantics"
```

- [ ] **Step 2: Locate the legend**

```bash
grep -n "enter.*worktree\|details.*Delete.*fire\|\\[↵\\]" src/nido/tui.clj
```

Find the runs-screen help line. Today it reads roughly:

```
[↵] enter  [w]orktree  [d]etails  [D]elete  [f]ire  [h]alt  [c]lear breaker  [s]essions  [q]uit
```

- [ ] **Step 3: Reword the two affected keys**

Change `[↵] enter  [w]orktree` to `[↵] resume  [w] inspect worktree`. Leave everything after `[d]etails` unchanged. Example replacement:

```
[↵] resume  [w] inspect worktree  [d]etails  [D]elete  [f]ire  [h]alt  [c]lear breaker  [s]essions  [q]uit
```

- [ ] **Step 4: Visual check**

Run `nido` and switch to the runs surface (`r`). Confirm the new legend renders.

- [ ] **Step 5: Commit**

```bash
jj squash -m "docs(tui/runs): legend reflects resume/inspect semantics"
```

---

## Task 5: End-to-end smoke

This task has no code — it verifies the user-visible behavior the spec promises.

- [ ] **Step 1: Pick a Needs-attention run**

Run: `bb nido:runs:list | head`
Expected: at least one `awaiting-review` / `failed` / `halted` entry whose session is currently down.

- [ ] **Step 2: Verify session-home is gone**

Run: `ls ~/.nido/sessions/<project>/<session>/ 2>&1`
Expected: `No such file or directory`.

- [ ] **Step 3: `↵` from the TUI**

Open the TUI, switch to the runs surface, select the row, press `↵`.
Expected:
- bb prints the standard `session:up` output (PG → JVM → app).
- The shell `cd`s into `~/.nido/sessions/<project>/<session>/`.

- [ ] **Step 4: `w` from the TUI on the same row**

Re-open the TUI (or bring down the session and re-open), select the row, press `w`.
Expected:
- No `session:up` output.
- The shell `cd`s straight into `~/Code/<project>-worktrees/<session>/`.

- [ ] **Step 5: `w` after destroying the worktree**

Run: `bb nido:session:destroy :project <p> <session>` then open the TUI, press `w` on the same row.
Expected: `[nido:tui] action failed: Worktree no longer exists for '<session>'`.

If any step deviates, note it and stop — the implementation is not done.

---

## Self-review notes

- **Spec coverage:** §Resolution covered by Tasks 1–3. §Changes/`enter!` covered by Tasks 1 & 2. §Changes/TUI covered by Task 3 (plumbing) and Task 4 (legend). §Tests covered by the tests added in Tasks 1 & 2 plus the smoke in Tasks 3 & 5. Sessions-screen unchanged (no task touches it).
- **No placeholders:** all code blocks are concrete; all commands have expected outputs; no "similar to" cross-references.
- **Type consistency:** `:auto-up?` is the lifecycle-level keyword; `:auto-up` is the task-CLI form, translated at the boundary by `tasks.nido-session/enter`. The TUI wrapper passes `":auto-up" "true"`, which `task-args/split-args` parses into `{:auto-up true}`. The error message `"Worktree no longer exists for '<session>'"` is identical in the implementation, both tests, and the smoke task.
