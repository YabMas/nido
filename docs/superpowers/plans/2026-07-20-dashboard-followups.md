# Dashboard Follow-ups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Two small web-dashboard fixes — surface a failed gate action on the board, and make the project scope persist across navigation instead of yanking you to the home surface.

**Architecture:** Fix A inspects `work/resolve-gate!`'s return value in `gate-resolve!` and routes a failure *decision* to the existing `:failed` app-state (today only a thrown exception does). Fix B makes the rail's scope links stay on the current surface and its surface links carry the current scope, threading `:tab` into the rail context.

**Tech Stack:** Babashka/Clojure, hiccup2 views, Datastar SSE, http-kit routes.

## Global Constraints

- Failure decisions from `work/resolve-gate!` are `#{:notion-failed :error}`; success decisions (`:applied`, `:dropped`, `:done`, `:promote`, `:dismissed`, `:triaging`, `{:resumed …}`, …) clear the working state as today.
- The `:failed` app-state renders as `{:state :failed :error-msg <string>}` (existing — `server_test` asserts this shape). Do not invent a new render.
- Scope sticky-dimension rule: **selecting a project changes what you're looking at, never where you are; switching surface keeps what you've filtered to.** Scope links → current surface + new scope (+ tab when on `:workstreams`). Surface links → target path + current scope.
- `scope` "all" and `tab` `:intake` are the defaults and are OMITTED from the query string (keep URLs clean).
- Load-check changed namespaces before commit: `bb -e "(require 'the.ns :reload)"`. Test task is `bb nido:test` (target one ns with `:only <ns>`). hiccup2 escapes `>` in inline `<style>` — irrelevant here (no CSS change), but never use child combinators in dashboard CSS.

---

### Task 1: Surface a failed gate action on the board

**Files:**
- Modify: `src/nido/ui/server.clj` (`gate-resolve!`, ~lines 273–285)
- Test: `test/nido/ui/server_test.clj`

**Interfaces:**
- Consumes: `work/resolve-gate! [project ws-id action-id input] → {:decision <kw> …}` (returns `:notion-failed`/`:error` on failure); `dev/set-app-state!` / `dev/clear-app-state!` (already used in `gate-resolve!`).
- Produces: no new public symbol; `gate-resolve!` now sets `:failed` on a failure decision.

- [ ] **Step 1: Write the failing test**

The current `gate-resolve!` runs its body on a `future`, which is awkward to assert. First read `gate-resolve!` and the existing app-state tests (`server_test` around the `run-action!` app-state assertions and line ~277 `{:state :failed :error-msg "boom"}`). Add a test that drives the failure path and asserts the app-state. If `gate-resolve!` is private and future-based, test it via the same mechanism the existing app-state tests use (redef `work/resolve-gate!` + `dev/set-app-state!`/`clear-app-state!` to capture calls, and await the future — or, if the existing tests already tolerate the future by capturing into an atom, mirror that). Concretely:

```clojure
(deftest gate-resolve-surfaces-a-notion-failed-decision
  (let [states (atom {})]
    (with-redefs [nido.work/resolve-gate! (fn [& _] {:decision :notion-failed :error :server})
                  nido.ui.dev/set-app-state! (fn [k st & [msg]] (swap! states assoc k [st msg]))
                  nido.ui.dev/clear-app-state! (fn [k] (swap! states dissoc k))]
      (#'nido.ui.server/gate-resolve! "brian" "w1" :apply nil)
      (Thread/sleep 50) ; let the future run
      (is (= :failed (first (get @states "brian/w1"))) "a :notion-failed decision surfaces as :failed")
      (is (re-find #"server" (str (second (get @states "brian/w1")))) "the reason is carried"))))

(deftest gate-resolve-clears-on-success-decision
  (let [states (atom {"brian/w1" [:resolving nil]})]
    (with-redefs [nido.work/resolve-gate! (fn [& _] {:decision :applied})
                  nido.ui.dev/set-app-state! (fn [k st & [msg]] (swap! states assoc k [st msg]))
                  nido.ui.dev/clear-app-state! (fn [k] (swap! states dissoc k))]
      (#'nido.ui.server/gate-resolve! "brian" "w1" :apply nil)
      (Thread/sleep 50)
      (is (nil? (get @states "brian/w1")) "a success decision clears the working state"))))
```

(Adjust the `dev` alias to the ns `gate-resolve!` actually uses — confirm from the `require` block.)

- [ ] **Step 2: Run to verify it fails**

Run: `bb nido:test :only nido.ui.server-test`
Expected: FAIL — `gate-resolve-surfaces-a-notion-failed-decision` sees the state cleared (current code calls `clear-app-state!` unconditionally on a non-throwing return).

- [ ] **Step 3: Route failure decisions to `:failed`**

Replace the `future` body in `gate-resolve!` with:

```clojure
    (future
      (try
        (let [{:keys [decision error]} (work/resolve-gate! project ws-id action-id input)]
          (if (contains? #{:notion-failed :error} decision)
            (dev/set-app-state! k :failed (str "Apply failed" (when error (str ": " (name error)))))
            (dev/clear-app-state! k)))
        (catch Exception e
          (dev/set-app-state! k :failed (or (:reason (ex-data e)) (ex-message e))))))
```

- [ ] **Step 4: Run to verify pass**

Run: `bb -e "(require 'nido.ui.server :reload)"` then `bb nido:test :only nido.ui.server-test`
Expected: PASS — both new tests green; the existing gate POST tests (which stub `resolve-gate!` to return success/`:resumed` maps) stay green.

- [ ] **Step 5: Commit** — controller runs jj; implementer stops and reports.

---

### Task 2: Scope rides along with navigation

**Files:**
- Modify: `src/nido/ui/views.clj` (`rail`, ~lines 159–179)
- Modify: `src/nido/ui/server.clj` (`rail-ctx`, ~line 156 — pass `:tab`)
- Test: `test/nido/ui/views_test.clj`

**Interfaces:**
- Consumes: the rail context map `{:active :needs-count :daemon :scope :projects :tab}`. `active` ∈ `{:needs :workstreams :system}`; `scope` a project string or `"all"`; `tab` ∈ `{:intake :active}` or nil.
- Produces: `rail` renders state-carrying links.

- [ ] **Step 1: Write the failing test**

Add to `test/nido/ui/views_test.clj` (the `rail` fn is private — call via `#'views/rail`; the ns likely already refers hiccup rendering — render to string with `h/html` or the file's existing rail-test helper if one exists):

```clojure
(deftest rail-scope-link-stays-on-current-surface
  (let [html (str (h/html (#'views/rail {:active :system :scope "all" :needs-count 0
                                         :daemon {:state :up} :projects ["brian"] :tab nil})))]
    (is (re-find #"/system\?scope=brian" html) "a scope link stays on the current (system) surface")
    (is (not (re-find #"href=\"/\?scope=brian\"" html)) "scope link does NOT jump to the home surface")))

(deftest rail-surface-link-carries-current-scope
  (let [html (str (h/html (#'views/rail {:active :system :scope "brian" :needs-count 0
                                         :daemon {:state :up} :projects ["brian"] :tab nil})))]
    (is (re-find #"/workstreams\?scope=brian" html) "the Workstreams surface link carries the current scope")
    (is (re-find #"href=\"/\?scope=brian\"" html) "the Needs-you surface link carries the current scope")))

(deftest rail-scope-link-preserves-workstreams-tab
  (let [html (str (h/html (#'views/rail {:active :workstreams :scope "brian" :tab :active
                                         :needs-count 0 :daemon {:state :up} :projects ["brian"]})))]
    (is (re-find #"/workstreams\?scope=brian&tab=active" html)
        "on workstreams, a scope link preserves the active tab")))
```

(If a `rail` render helper already exists in the file, use it; confirm the `h`/hiccup alias from the ns form.)

- [ ] **Step 2: Run to verify it fails**

Run: `bb nido:test :only nido.ui.views-test`
Expected: FAIL — current `rail` hardwires scope links to `/` and `/?scope=…` and surface links to bare paths, so `/system?scope=brian`, the scope-carrying surface links, and the tab-preserving scope link are all absent.

- [ ] **Step 3: Rewrite `rail`**

Replace the `rail` fn (views.clj:159–179) with:

```clojure
(defn- rail
  "The persistent navigation rail. Scope is a sticky dimension: a scope link stays on the
   current surface (changing only scope, preserving the workstreams tab); a surface link
   carries the current scope. Selecting a project changes what you see, never where you are."
  [{:keys [active needs-count daemon scope projects tab]}]
  (let [surface-path {:needs "/" :workstreams "/workstreams" :system "/system"}
        q (fn [scope-val workstreams?]
            (let [parts (cond-> []
                          (and scope-val (not= "all" scope-val)) (conj (str "scope=" scope-val))
                          (and workstreams? tab (not= :intake tab))    (conj (str "tab=" (name tab))))]
              (if (seq parts) (str "?" (str/join "&" parts)) "")))
        dest (fn [id href label]
               [:a {:class (str "rail-link" (when (= id active) " active"))
                    :href (str href (q scope (= id :workstreams)))}
                [:span label]
                (when (= id :needs) (rail-needs-badge needs-count))])
        scope-link (fn [scope-val label]
                     [:a {:class (when (= scope scope-val) "active")
                          :href (str (surface-path active) (q scope-val (= active :workstreams)))}
                      label])]
    [:nav.rail
     [:a.rail-brand {:href "/" :title "nido"}
      [:img.rail-logo {:src "/nido-logo.png" :alt "nido" :width 84 :height 84}]]
     (dest :needs "/" "Needs you")
     (dest :workstreams "/workstreams" "Workstreams")
     (dest :system "/system" "System")
     [:div.rail-scope
      [:div.meta "Scope"]
      (scope-link "all" "All projects")
      (for [p projects] (scope-link p p))]
     (rail-health daemon)]))
```

Confirm `[clojure.string :as str]` is required in `views.clj` (it is used elsewhere in the file — verify). No CSS change.

- [ ] **Step 4: Thread `:tab` into the rail context**

In `src/nido/ui/server.clj` `rail-ctx` (~line 156), add `:tab (:tab screen)` to the returned map. Leave `rail-context` (the `:system` builder, ~line 166) as-is or add `:tab nil` — `active :system` never triggers the tab branch, so either is fine; prefer explicit `:tab nil`.

- [ ] **Step 5: Run to verify pass**

Run: `bb -e "(require 'nido.ui.views :reload) (require 'nido.ui.server :reload)"` then `bb nido:test :only nido.ui.views-test` and `bb nido:test :only nido.ui.server-test`
Expected: PASS. Existing rail/shell render tests stay green (the rail still renders the same three surfaces + scope entries; only the hrefs changed).

- [ ] **Step 6: Commit** — controller runs jj; implementer stops and reports.

---

## Self-Review

**1. Spec coverage:** Fix A → Task 1 (`gate-resolve!` failure-decision branch). Fix B → Task 2 (`rail` scope/surface link state + `rail-ctx` `:tab`). Stale "static for now" comment removed in Task 2's rewrite. ✅

**2. Placeholder scan:** No TBD/TODO; complete code in every code step. Task 1 Step 1 flags the future-timing test concern with a concrete approach rather than hand-waving. ✅

**3. Type consistency:** Failure-decision set `#{:notion-failed :error}` matches `apply!`/`apply-proposed!` returns. Rail context keys (`:active :scope :tab :projects :needs-count :daemon`) match `rail-ctx`/`rail-context`. `tab` values `:intake`/`:active` and the `scope="all"`/`tab=:intake` omission match the routing arc's `screen-query` conventions. ✅

## Notes for the controller (VCS)
- Stacks on top of the routing + apply combined stack (base = the apply arc's HEAD). Planning docs stay in an undescribed `@` and MUST NOT land — split each task's source+test paths with `jj split`.
- Land the whole combined stack (routing + apply + these two) together when the user says go; restart the daemon after.
