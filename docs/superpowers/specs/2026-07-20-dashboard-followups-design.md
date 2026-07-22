# Dashboard follow-ups — design

**Status:** approved-in-conversation, pending written review
**Date:** 2026-07-20
**Follows:** the apply-unification arc (unlanded). Two small web-dashboard fixes the user asked
for "in order", landing with the combined routing + apply stack.

Two independent fixes, both in the web dashboard:
1. **Surface board Apply failures** (apply-unification final review, Important #2).
2. **Persist scope across navigation** (the "projects filter / nav feels disjoint" report).

---

## Fix A — surface a failed gate action on the board

**Problem.** `gate-resolve!` (`src/nido/ui/server.clj:273`) runs `work/resolve-gate!` on a
future and sets the per-(project,ws-id) app-state to `:failed` only in its `catch` — i.e.
only when the call *throws*. But `work/apply!` signals a Notion-write failure by *returning*
`{:decision :notion-failed :error <kw>}` (and `apply-proposed!` returns `{:decision :error …}`)
— a value, not a throw. So the success branch (`clear-app-state!`) runs, the "working…" state
clears with no error shown, and the row just silently re-parks on the next 5s poll. The user
clicks Apply, sees nothing happen, and has no reason why.

**Fix.** In `gate-resolve!`'s future, inspect the return value: if the decision is a failure
(`#{:notion-failed :error}`), set app-state `:failed` with a human reason instead of clearing.
The `:failed` app-state already renders (the `catch` uses it; `server_test` asserts
`{:state :failed :error-msg …}`), so no new rendering — just route the value-level failure to
the existing state.

```clojure
(future
  (try
    (let [{:keys [decision error]} (work/resolve-gate! project ws-id action-id input)]
      (if (contains? #{:notion-failed :error} decision)
        (dev/set-app-state! k :failed (str "Apply failed"
                                           (when error (str ": " (name error)))))
        (dev/clear-app-state! k)))
    (catch Exception e
      (dev/set-app-state! k :failed (or (:reason (ex-data e)) (ex-message e))))))
```

Non-goal: retry UI or auto-retry — the ticket stays parked and the button stays clickable, so
the human just clicks Apply again once Notion is healthy. Surfacing the reason is the whole fix.

---

## Fix B — scope rides along with navigation

**Problem.** The rail (`src/nido/ui/views.clj:159`) has the current surface (`active`) and scope,
and all three surfaces (`/`, `/workstreams`, `/system`) already honor `?scope=`. But the rail's
links don't carry state:
- **Scope links** are hardwired to `/` and `/?scope=<p>` — so picking a project on `/workstreams`
  or `/system` throws you back to the Needs-you home. A filter control that changes your *surface*
  is what reads as disjoint.
- **Surface links** (`Needs you` / `Workstreams` / `System`) are bare paths — so switching surface
  *drops* the selected scope.

**Fix.** Make scope a sticky dimension that rides along:
- **Scope links** stay on the **current** surface (`active`), changing only the scope — and
  preserve the Intake/Active tab when `active` is `:workstreams`.
- **Surface links** carry the **current** scope so it survives the jump between surfaces.

All in the `rail` fn, plus threading `:tab` into the rail context so scope links can preserve it:

```clojure
(defn- rail
  [{:keys [active needs-count daemon scope projects tab]}]
  (let [surface-path {:needs "/" :workstreams "/workstreams" :system "/system"}
        q (fn [scope-val on-workstreams?]
            (let [parts (cond-> []
                          (and scope-val (not= "all" scope-val)) (conj (str "scope=" scope-val))
                          (and on-workstreams? tab (not= :intake tab)) (conj (str "tab=" (name tab))))]
              (if (seq parts) (str "?" (str/join "&" parts)) "")))
        dest (fn [id href label]
               [:a {:class (str "rail-link" (when (= id active) " active"))
                    :href (str href (q scope (= id :workstreams)))}  ; carry current scope
                [:span label]
                (when (= id :needs) (rail-needs-badge needs-count))])
        scope-link (fn [scope-val label]
                     [:a {:class (when (= scope scope-val) "active")
                          :href (str (surface-path active)
                                     (q scope-val (= active :workstreams)))} label])]  ; stay on surface
    [:nav.rail
     [:a.rail-brand {:href "/" :title "nido"} …logo…]
     (dest :needs "/" "Needs you")
     (dest :workstreams "/workstreams" "Workstreams")
     (dest :system "/system" "System")
     [:div.rail-scope
      [:div.meta "Scope"]
      (scope-link "all" "All projects")
      (for [p projects] (scope-link p p))]
     (rail-health daemon)]))
```

- `rail-ctx` (server.clj:156, has the screen) also passes `:tab (:tab screen)`.
- `rail-context` (server.clj:166, the `:system` surface, no tab) passes `:tab nil` — harmless, the
  q-helper only uses tab when `active` / target is `:workstreams`.
- The `scope-link` for a surface link uses the CURRENT scope (preserve); the rail-scope entries use
  the NEW scope-val (change). That's the whole behavior: **selecting a project changes what you're
  looking at, never where you are; switching where you are keeps what you've filtered to.**

Remove the stale `;; Static for now; the scope task wires these to real project filters.` comment —
this is that task.

Non-goal (this round): deeper questions about whether the projects filter belongs in the rail at
all (the user said "for now, persist"); per-surface scope-facet richness. This is the persistence
fix only.

---

## Testing

- **Fix A** (`server_test`): a `resolve-gate!` stubbed to return `{:decision :notion-failed :error :server}`
  leaves the (project,ws-id) app-state `:failed` (not cleared); a success decision (`{:decision :applied}`)
  clears it. (gate-resolve! runs on a future — the existing tests stub `resolve-gate!` and assert the POST
  response; add an app-state assertion or make the future's body synchronously testable via the existing
  seam. Match how `run-action!`'s app-state tests are structured.)
- **Fix B** (`views_test`): `rail` rendered with `active :system, scope "brian"` → the scope link for a
  project points at `/system?scope=…` (NOT `/`), and the `Workstreams` surface link carries `?scope=brian`;
  with `active :workstreams, tab :active, scope "brian"` → a scope link preserves `tab=active`.

## Files touched
- `src/nido/ui/server.clj` — `gate-resolve!` failure-decision branch; `rail-ctx` passes `:tab`.
- `src/nido/ui/views.clj` — `rail` scope-link + surface-link state carrying.
- Tests: `test/nido/ui/server_test.clj`, `test/nido/ui/views_test.clj`.
