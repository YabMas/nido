# Gate-action Feedback + Follow-handoff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every gate action (Promote/Skip/Drop/Done/Reply) confirms in the pane where you clicked, with one-click links to follow the item — replacing the silent inbox-only patch.

**Architecture:** One new view `gate-action-confirm-fragment [action-id project ws-id]` (per-action message + "open workstream →" / "board →" follow-links) that **replaces** the reply-only `gate-resuming-fragment`; the server's gate POST returns it for **all** actions. `gate-resolve!`'s async model is unchanged (the confirmation is action-keyed/optimistic). Atomic: the view + the rewire + the removal of the old fn land in one commit so the build never references a removed fn.

**Tech Stack:** Babashka/Clojure, `hiccup2.core`, `org.httpkit.server`, Datastar, `clojure.test`, jujutsu (jj). Tests: `bb nido:test :only nido.ui`.

**Spec:** `docs/superpowers/specs/2026-06-18-gate-action-feedback-design.md`.

---

## Commit hygiene (read before Task 1)

The working copy `@` holds **uncommitted planning docs** (`docs/superpowers/**`) + stray files (`resources/nido-icon.png`, `resources/Badger - Wikipedia`) that must NOT enter the code commit. Before starting:

```bash
jj log -r '@' --no-graph -T 'change_id.shortest(8) ++ "\n"'   # note the docs-bearing change
jj new                                                         # clean code changeset on top
```

The task ends with `jj commit -m "…"`. After it, verify nothing extra was swept in:

```bash
jj show -s @-   # must list ONLY src/nido/ui/{views,server}.clj + the two ui test files — never docs/** or resources/**
```

## File Structure

- **`src/nido/ui/views.clj`** (modify) — replace `gate-resuming-fragment` with `gate-action-confirm-fragment`.
- **`src/nido/ui/server.clj`** (modify) — gate POST branch returns the confirm fragment for all actions.
- **Tests:** `test/nido/ui/views_test.clj`, `test/nido/ui/server_test.clj`.

---

### Task 1: Unified gate-action confirmation in the pane

**Files:**
- Modify: `src/nido/ui/views.clj` (replace `gate-resuming-fragment` ~line 273 with `gate-action-confirm-fragment`)
- Modify: `src/nido/ui/server.clj` (gate POST branch ~line 281-289)
- Test: `test/nido/ui/views_test.clj` (add new tests; UPDATE the pre-existing `gate-resuming-fragment-targets-the-pane`), `test/nido/ui/server_test.clj` (append)

- [ ] **Step 1: Write the failing tests**

In `test/nido/ui/views_test.clj`, **replace** the pre-existing `gate-resuming-fragment-targets-the-pane` test (it references the to-be-removed fn) and **add** the action-coverage test:

```clojure
(deftest gate-action-confirm-reply-targets-the-pane
  (let [html (views/gate-action-confirm-fragment :reply "brian" "ws-1")]
    (is (str/includes? html "id=\"gate-pane\""))
    (is (str/includes? html "Resuming"))))

(deftest gate-action-confirm-renders-per-action-message-and-follow-links
  (doseq [[action needle] [[:promote "Promoting"] [:skip "Skipped"]
                           [:drop "Dropped"] [:done "done"] [:reply "Resuming"]]]
    (let [html (views/gate-action-confirm-fragment action "brian" "ws-1")]
      (is (str/includes? html needle) (str action " message"))
      (is (str/includes? html "/ws/brian/ws-1") (str action " links to the workstream"))
      (is (str/includes? html "/board") (str action " links to the board"))))
  ;; unknown action → generic fallback, still a valid pane fragment
  (let [html (views/gate-action-confirm-fragment :wat "brian" "ws-1")]
    (is (str/includes? html "id=\"gate-pane\""))))
```

In `test/nido/ui/server_test.clj`, append:

```clojure
(deftest post-gate-mutation-returns-confirm-pane-with-follow-links
  (with-redefs [nido.work/resolve-gate! (fn [& _] {:decision :promote})]
    (let [resp (server/handle-request {:request-method :post :uri "/gate/brian/ws-1/promote"})]
      (Thread/sleep 50)
      (is (str/includes? (:body resp) "gate-pane"))
      (is (str/includes? (:body resp) "Promoting"))
      (is (str/includes? (:body resp) "/ws/brian/ws-1"))
      (is (str/includes? (:body resp) "/board")))))
```

- [ ] **Step 2: Run to verify it fails**

Run: `bb nido:test :only nido.ui`
Expected: FAIL — `gate-action-confirm-fragment` is undefined; the mutation POST returns the inbox fragment (no "Promoting"/follow-links); and the old `gate-resuming-fragment-targets-the-pane` test no longer exists (you replaced it).

- [ ] **Step 3: Implement the view**

In `src/nido/ui/views.clj`, **replace** `gate-resuming-fragment` entirely with:

```clojure
(defn gate-action-confirm-fragment
  "Pane confirmation after a gate action: the per-action outcome + follow-links to
   where the item now lives. Patches the pane (#gate-pane). The action runs on a
   background future; this is the immediate, action-keyed feedback."
  [action-id project ws-id]
  (let [msg (case action-id
              :promote "Promoting → in-progress… provisioning the work session."
              :skip    "✓ Skipped — dropped, not pursued."
              :drop    "✓ Dropped — not pursued."
              :done    "✓ Marked done."
              :reply   "Resuming… re-hydrating the session if needed, then resuming the conversation."
              "Done.")]
    (str
     (h/html
      [:div {:id "gate-pane"}
       [:div.breadcrumb project " / gate"]
       [:h1 msg]
       [:p.meta "ws " ws-id]
       [:p "Follow it: "
        [:a {:href (str "/ws/" project "/" ws-id)} "open workstream →"]
        " · "
        [:a {:href "/board"} "board →"]]]))))
```

- [ ] **Step 4: Rewire the server**

In `src/nido/ui/server.clj`, the gate POST branch — replace the `if`-reply-else-inbox tail with a single confirm response for all actions:

```clojure
      (and (= 4 (count segs)) (= "gate" (first segs)))
      (let [project   (nth segs 1)
            ws-id     (nth segs 2)
            action-id (keyword (nth segs 3))
            input     (when (= :reply action-id) (:reply (parse-json-body body)))]
        (gate-resolve! project ws-id action-id input)
        (sse-response (sse-fragment (views/gate-action-confirm-fragment action-id project ws-id))))
```

(The `(work/all-gates)` call that fed the old inbox-fragment response is no longer needed here. `work` is still required for other uses in `server.clj` — leave the require.)

- [ ] **Step 5: Run tests to verify they pass**

Run: `bb nido:test :only nido.ui`
Expected: PASS. The pre-existing `post-gate-reply-returns-resuming-pane` (SP1) still passes — the `:reply` case still contains "Resuming" and `id="gate-pane"`. `post-gate-mutation-calls-resolve-and-returns-sse` (Plan 2) still passes — the mutation POST still calls `resolve-gate!` and returns an `text/event-stream`. Confirm there is no remaining reference to `gate-resuming-fragment` anywhere (`grep -rn gate-resuming-fragment src test` → empty).

- [ ] **Step 6: Full suite + commit**

```bash
grep -rn "gate-resuming-fragment" src test   # must be EMPTY (the fn is fully replaced)
bb nido:test                                 # whole suite green
jj commit -m "feat(ui): every gate action confirms in the pane + follow-links (replaces reply-only resuming)"
jj show -s @-
```

---

## Self-check after the task

- [ ] `bb nido:test` — full suite green.
- [ ] `grep -rn gate-resuming-fragment src test` — empty (fully replaced).
- [ ] `jj show -s @-` — only `src/nido/ui/{views,server}.clj` + the two ui test files; no `docs/**`, no `resources/**`.
- [ ] Behaviour: clicking any gate action patches the pane with a per-action confirmation + "open workstream →" / "board →" links.

## Non-goals

Option 2 (the pane tracking the item live). No change to `gate-resolve!`'s async model, the inbox fragment, the board, or `/ws` detail beyond linking to them. The separate promote→impl-session functional question is out of scope.
