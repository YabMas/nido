# `session:enter` `:cd worktree` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user opt into landing in the worktree (the actual code) instead of the session-home when entering a session, both via `bb nido:session:enter` and via the TUI.

**Architecture:** Three narrow edits. (1) `lifecycle/enter!` grows a `:cd` opt that resolves to either the session-home (default) or its `worktree/` symlink, with validation and existence checks. (2) The TUI gains a `w` keybinding alongside `enter`/`e`, and queues the target as a third action element. (3) The TUI task forwards the target to the bb-level `enter` as `:cd <target>`. No new bb tasks; no test infra (project has none).

**Tech Stack:** Babashka + Clojure, `babashka.fs`, charm.clj TUI.

**Spec:** `docs/superpowers/specs/2026-05-04-session-enter-worktree-cd-design.md`

**A note on testing:** This project has no existing automated test infrastructure (no `test/` dir, no test runner in `bb.edn`, no `*_test.clj` files anywhere in `src/`). Adding a test framework for this small CLI surface would be unrelated scope. Each task instead specifies an exact manual verification recipe — bb commands run against a real session, with expected stdout / cwd / file-system state. If the project later grows tests, these recipes are the obvious seeds.

**Working copy at start:** Make sure `jj st` is clean. Each task ends with a `jj desc -m` (jj auto-snapshots the working copy; `desc` sets the message) followed by `jj new` to start a fresh changeset for the next task.

---

## File map

| File | Change |
|------|--------|
| `src/nido/session/lifecycle.clj` | Add private `parse-cd-target`; rewrite `enter!` to accept `:cd`, resolve the target, validate, run two existence checks. |
| `src/nido/tui.clj` | Add `w` key in `update-sessions`; thread `:home` / `:worktree` into the queued `:enter` action; extend the sessions-screen footer hint. |
| `src/tasks/nido_tui.clj` | Destructure the target in the `:enter` branch of `run-action`; forward it to `session/enter` as `:cd <name target>`. |

`src/tasks/nido_session.clj` does **not** change — `split-args` already accepts arbitrary kwargs and `enter` passes `opts` through.

---

## Task 1: `lifecycle/enter!` learns `:cd`

**Files:**
- Modify: `src/nido/session/lifecycle.clj:243-260` (the existing `enter!` definition)

**Background for the implementer:**

`bb nido:session:enter :project brian foo :cd worktree` flows through:
1. `tasks.nido-session/enter` → `split-args` parses kwargs.
2. Each kwarg value is run through `parse-token`, which is `edn/read-string` with a fallback. So `:cd worktree` lands in `opts` as `{:cd 'worktree}` — the **symbol** `worktree`, not a string and not a keyword. We must normalize.
3. `enter` calls `(lifecycle/enter! session opts)` and we read `:cd` here.

Today's `enter!` reads `session-home` and writes it to `.last-cd`. We're generalizing one resolution step.

**Reference: current `enter!` (for the rewrite below):**

```clojure
(defn enter!
  "Hand off the session-home path to the parent shell via `cd-target-file`.
   bb cannot change its parent's cwd, so a tiny zsh function (see Nido's
   CLAUDE.md) reads this file after the TUI exits and `cd`s the user there.

   Throws if the session isn't running — there's nothing to land in until
   `session:up` has populated the session-home."
  [name opts]
  (let [[project-name _] (resolve-project opts)
        session-home (state/session-home-dir project-name name)]
    (when-not (fs/exists? session-home)
      (throw (ex-info (str "No session home for '" name "' — is it running?")
                      {:expected session-home
                       :hint "Run `bb nido:session:up` to bring it up."})))
    (let [target (cd-target-file)]
      (fs/create-dirs (fs/parent target))
      (spit target session-home)
      (core/log-step (str "Selected " session-home)))))
```

- [ ] **Step 1: Add the `parse-cd-target` helper just above `enter!`**

Insert this new private fn directly above the `(defn enter! ...)` form (i.e. above line 243):

```clojure
(defn- parse-cd-target
  "Normalize the user-supplied `:cd` value (symbol from edn/read-string,
   keyword, or string) to one of `:home` / `:worktree`. Defaults to
   `:home` when nil. Throws on anything else with the valid set in the
   message."
  [v]
  (let [s (cond
            (nil? v)                    "home"
            (or (keyword? v) (symbol? v)) (name v)
            :else                       (str v))]
    (case s
      "home"     :home
      "worktree" :worktree
      (throw (ex-info (str "Invalid :cd value " (pr-str v))
                      {:value v
                       :valid #{"home" "worktree"}
                       :hint "Pass :cd home (default) or :cd worktree"})))))
```

- [ ] **Step 2: Replace the body of `enter!`**

Replace the current `enter!` (lines 243-260) with:

```clojure
(defn enter!
  "Hand off a cwd to the parent shell via `cd-target-file`. bb cannot change
   its parent's cwd, so a tiny zsh function (see Nido's CLAUDE.md) reads
   this file after the bb task exits and `cd`s the user there.

   `:cd` selects the target:
     :home (default) — the session-home (CLAUDE.md, .mcp.json live here)
     :worktree       — the worktree symlink inside session-home

   Throws if the session isn't running, or if `:cd worktree` is requested
   and the worktree symlink is missing or dangling."
  [name opts]
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

Note: we keep the symlink path as-is (no `realpath`). The shell follows it on `cd`, but `$PWD` shows the short symlink path — which the spec's "Path resolution" section commits to.

- [ ] **Step 3: Sanity-check the file parses**

Run: `bb -e "(require '[nido.session.lifecycle :as l]) (println (resolve 'l/enter!))"`
Expected output (a single non-nil line):
```
#'nido.session.lifecycle/enter!
```
Failure means a syntax error — re-read the diff and fix.

- [ ] **Step 4: Validate `parse-cd-target` from a REPL one-liner**

Run:
```
bb -e "(require '[nido.session.lifecycle :as l]) (def f #'l/parse-cd-target) (println (@f nil)) (println (@f 'worktree)) (println (@f :worktree)) (println (@f \"home\")) (try (@f 'bogus) (catch Exception e (println (ex-message e))))"
```

Expected output (four lines + the error):
```
:home
:worktree
:worktree
:home
Invalid :cd value bogus
```

(`#'l/parse-cd-target` is the var; `@f` derefs to call the private fn from outside the namespace. If the var deref errors, the helper isn't defined — re-check Step 1.)

- [ ] **Step 5: End-to-end CLI smoke against a live session**

Pick any running session (or `bb nido:session:up :project <p> <s>` first). Then:

```
rm -f ~/.nido/.last-cd
bb nido:session:enter :project <p> <s>
cat ~/.nido/.last-cd
```

Expected: prints `~/.nido/sessions/<p>/<s>` (no trailing newline matters not — `spit` with no `:append` writes one shot).

Then:

```
rm -f ~/.nido/.last-cd
bb nido:session:enter :project <p> <s> :cd worktree
cat ~/.nido/.last-cd
```

Expected: prints `~/.nido/sessions/<p>/<s>/worktree`.

Then negative case:

```
bb nido:session:enter :project <p> <s> :cd bogus
```

Expected: non-zero exit, message containing `Invalid :cd value bogus` and the valid set.

- [ ] **Step 6: Commit**

```
jj desc -m "feat(nido): session:enter accepts :cd worktree"
jj new
```

`jj new` starts a fresh changeset for Task 2.

---

## Task 2: TUI key `w` and footer hint

**Files:**
- Modify: `src/nido/tui.clj:153-155` (sessions screen `enter`/`e` handler)
- Modify: `src/nido/tui.clj:261` (sessions screen footer string)

**Background:**

The sessions screen is handled by `update-sessions`. The relevant block today (lines 148-174 — the surrounding `cond`) routes keys to `queue-action!`. The action queued for `enter`/`e` is `[:enter p sn]`; we'll grow it to `[:enter p sn :home]`, and add a parallel `w` arm that queues `[:enter p sn :worktree]`. Task 3 then teaches the bb-task layer to read the third element.

The footer at line 261 is a plain string. We'll splice in `[w]orktree`.

- [ ] **Step 1: Update the existing `enter`/`e` arm to carry `:home`**

In `src/nido/tui.clj`, find:

```clojure
    (or (msg/key-match? msg "enter") (msg/key-match? msg "e"))
    (with-selected-session state
      (fn [s p sn] [s (queue-action! [:enter p sn])]))
```

Replace with:

```clojure
    (or (msg/key-match? msg "enter") (msg/key-match? msg "e"))
    (with-selected-session state
      (fn [s p sn] [s (queue-action! [:enter p sn :home])]))
```

- [ ] **Step 2: Add the `w` arm right below it**

Directly after the block above (and before the existing `"u"` arm at line 157), insert:

```clojure
    (msg/key-match? msg "w")
    (with-selected-session state
      (fn [s p sn] [s (queue-action! [:enter p sn :worktree])]))
```

- [ ] **Step 3: Extend the sessions-screen footer hint**

Find line 261:

```clojure
                    :sessions "[↵/e] enter  [a]dd  [u]p  [d]own  [x] destroy  [esc] back  [q]uit"))))
```

Replace with:

```clojure
                    :sessions "[↵/e] enter  [w]orktree  [a]dd  [u]p  [d]own  [x] destroy  [esc] back  [q]uit"))))
```

- [ ] **Step 4: Update the action-shape comment**

Find line 26:

```clojure
;; Shape: :quit | [:enter p s] | [:up p s] | [:down p s] | [:destroy p s] | [:add p s]
```

Replace with:

```clojure
;; Shape: :quit | [:enter p s target] | [:up p s] | [:down p s] | [:destroy p s] | [:add p s]
;;        target = :home | :worktree
```

- [ ] **Step 5: Sanity-check the file parses**

Run: `bb -e "(require '[nido.tui])"`
Expected: silent (no output) and zero exit. Any error means a typo — re-read the diff.

- [ ] **Step 6: Commit**

```
jj desc -m "feat(nido): tui — w key picks worktree as cd target"
jj new
```

The TUI is now broken end-to-end: it queues a 4-element vector but `tasks.nido-tui/run-action` still destructures only three. Task 3 fixes that. Don't run the TUI between Task 2 and Task 3.

---

## Task 3: TUI task forwards the target

**Files:**
- Modify: `src/tasks/nido_tui.clj:68-73` (the `case` inside `run-action`)

**Background:**

`run-action` is the bb-task-side dispatcher charm calls after exiting. The `:enter` arm currently destructures `[_ p s]` and calls `(session/enter ":project" p s)`. After Task 2, the queued action is `[:enter p s target]` with `target` ∈ `#{:home :worktree}`. We forward as a kwarg.

- [ ] **Step 1: Update the `:enter` branch to destructure and forward**

In `src/tasks/nido_tui.clj`, find:

```clojure
      :enter   (let [[_ p s] action] (session/enter ":project" p s))
```

Replace with:

```clojure
      :enter   (let [[_ p s target] action]
                 (session/enter ":project" p s ":cd" (name target)))
```

`name` turns `:home` → `"home"` and `:worktree` → `"worktree"`. `session/enter`'s `split-args` parses each token through `edn/read-string`, which yields the symbol `'worktree`; `parse-cd-target` (Task 1) handles symbols.

- [ ] **Step 2: Sanity-check the file parses**

Run: `bb -e "(require '[tasks.nido-tui])"`
Expected: silent.

- [ ] **Step 3: End-to-end TUI verification (manual)**

Bring a session up first if none is running:
```
bb nido:session:up :project <p> <s>
```

Then run the TUI:
```
bb nido:tui
```

Drill into the project, select the session. Press <kbd>e</kbd>. The TUI exits.

```
cat ~/.nido/.last-cd
```

Expected: `~/.nido/sessions/<p>/<s>`.

Re-run, this time press <kbd>w</kbd>:

```
bb nido:tui   # select session, press w
cat ~/.nido/.last-cd
```

Expected: `~/.nido/sessions/<p>/<s>/worktree`.

Confirm the footer line on the sessions screen contains `[w]orktree` between `enter` and `[a]dd`.

- [ ] **Step 4: Commit**

```
jj desc -m "feat(nido): tui task forwards :cd target to session/enter"
jj new
```

---

## Task 4: End-to-end shell-wrapper smoke + docs touch

**Files:**
- Read-only smoke (no code changes in `src/`)
- Modify: `CLAUDE.md` (one-paragraph note documenting the new flag/key)

**Background:**

The shell wrapper (the `nido` zsh function in `~/.zshrc`) is what makes `cd` actually happen in the user's parent shell. We don't change the wrapper — `:cd worktree` writes the worktree path to the same `.last-cd` file the wrapper already reads. This task confirms the wrapper-driven flow works for both targets and updates `CLAUDE.md` so a fresh reader knows the option exists.

- [ ] **Step 1: Wrapper-driven smoke for `:home`**

In a clean terminal where the `nido` shell function is loaded:

```
rm -f ~/.nido/.last-cd
nido    # in the TUI: drill in, select session, press e
pwd
```

Expected: `pwd` prints the session-home path (`~/.nido/sessions/<p>/<s>`). The shell `cd`'d there because the wrapper read `.last-cd`.

- [ ] **Step 2: Wrapper-driven smoke for `:worktree`**

Open a fresh terminal (so `pwd` resets):

```
rm -f ~/.nido/.last-cd
nido    # drill in, select session, press w
pwd
ls .git 2>/dev/null && echo "in worktree" || echo "NOT in worktree"
```

Expected:
- `pwd` prints something ending in `/worktree` (the symlink path) — *not* the deep `~/Code/<p>-worktrees/<s>` realpath.
- The `ls .git` check prints `in worktree` (a worktree's root has a `.git` file).

If `pwd` shows the realpath, your shell is auto-resolving symlinks (zsh `cd -P` or similar). That's a shell config issue, not a nido bug — note it but do not "fix" it in nido.

- [ ] **Step 3: CLI-direct smoke for `:cd worktree`**

```
bb nido:session:enter :project <p> <s> :cd worktree
cat ~/.nido/.last-cd
```

Expected: ends with `/worktree`. (We covered this in Task 1 Step 5; this re-runs it post-integration to make sure no later edit broke it.)

- [ ] **Step 4: Document the option in `CLAUDE.md`**

Open `CLAUDE.md`. Find the **Session lifecycle** section's bullet for `session:enter`:

```markdown
- `bb nido:session:enter :project <p> <session>` — write the session-home path to `~/.nido/.last-cd` and exit. Pair with the `nido` shell function (see "Shell wrapper" above) to actually `cd` your shell there. Refuses if the session is down.
```

Replace with:

```markdown
- `bb nido:session:enter :project <p> <session>` — write the session-home path to `~/.nido/.last-cd` and exit. Pair with the `nido` shell function (see "Shell wrapper" above) to actually `cd` your shell there. Refuses if the session is down. Pass `:cd worktree` to land in the worktree (the actual code) instead — useful when you want to edit / git-grep without the extra `cd worktree`. The TUI exposes the same opt-in: <kbd>e</kbd> / <kbd>↵</kbd> for session-home, <kbd>w</kbd> for worktree.
```

- [ ] **Step 5: Commit**

```
jj desc -m "docs(nido): document session:enter :cd worktree + tui w key"
jj new
```

`jj new` is optional here — there are no further tasks. Skip if you'd rather leave `@` empty.

---

## Done

After Task 4, `jj log` should show four new commits on top of the spec commit. Inspect:

```
jj log
```

Expected (most recent first):
- `docs(nido): document session:enter :cd worktree + tui w key`
- `feat(nido): tui task forwards :cd target to session/enter`
- `feat(nido): tui — w key picks worktree as cd target`
- `feat(nido): session:enter accepts :cd worktree`
- `docs(nido): spec session:enter :cd worktree opt-in`
