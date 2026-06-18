# Daemon-bundled dashboard + live-sessions board — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **This is a Jujutsu (jj) repo, NOT git.** The `superpowers:jujutsu` skill is REQUIRED before any VCS operation. Commit steps use `jj commit <paths> -m "…"` with **explicit code/test paths only** so the uncommitted spec/plan docs under `docs/superpowers/` are NEVER bundled into a code commit (per the project's "never commit planning artifacts" rule). Before starting, run `jj st`; if `@` already holds unrelated changes, `jj new` first.

**Goal:** Make the nido web dashboard start/stop as part of the standard coordinator daemon, with a flat live-sessions board (across all projects) at the home page where each live session is a clickable friendly-host link.

**Architecture:** The dashboard runs in-process inside the coordinator JVM (httpkit owns its own thread pool, so it never blocks the tick loop). The home route becomes a cross-project aggregation of the existing per-session row logic, rendered live-first with clickable `:app-url` links. A latent field-name bug (`:url` vs `:app-url`) that has kept the URL column dark is fixed so the already-computed friendly-host URL surfaces.

**Tech Stack:** Babashka/Clojure, http-kit, Datastar (SSE), hiccup2, jj.

**Spec:** `docs/superpowers/specs/2026-06-15-daemon-dashboard-live-sessions-board-design.md`

---

## File Structure

- `src/nido/ui/views.clj` — add `board-row` (private), `live-board-fragment`, `live-board-page`; fix the `:url`→`:app-url` key in `session-row`.
- `src/nido/ui/server.clj` — add public `all-session-rows`; route `/` → board, `/projects` → old grid, `/_fragment/live` → board SSE tbody; make `handle-request` public.
- `src/nido/coordinator/core.clj` — `:dashboard` defaults; `dashboard-config` + `dashboard-status-line` (public, pure); start the server in `run!`; stop it in the shutdown hook; record `:dashboard-port` in the heartbeat.
- `src/tasks/nido_coordinator.clj` — thread `:dashboard-port`/`:no-dashboard` through `run` and `up`; add the `Dashboard:` line to `status`.
- `CLAUDE.md` — document the bundled dashboard.
- `test/nido/ui/views_test.clj`, `test/nido/ui/server_test.clj`, `test/nido/coordinator/core_dashboard_test.clj` — new tests.

Run tests with: `bb nido:test :only nido.ui` (UI) / `bb nido:test :only nido.coordinator` (core) / `bb nido:test :only nido` (all).

---

### Task 1: Surface the friendly-host URL in the per-project table (key fix)

**Files:**
- Test: `test/nido/ui/views_test.clj` (create)
- Modify: `src/nido/ui/views.clj:241`

- [ ] **Step 1: Write the failing test**

Create `test/nido/ui/views_test.clj`:

```clojure
(ns nido.ui.views-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [nido.ui.views :as views]))

(deftest sessions-table-renders-friendly-host-link
  ;; The registry persists the friendly-host URL under :app-url; the table
  ;; must render it as a clickable link (it long read the wrong key, :url).
  (let [html (views/sessions-table-fragment
              "brian"
              [{:name "fix-login" :live? true
                :entry {:app-url "http://fix-login.brian.localhost:3142" :app-port 3142}}])]
    (is (str/includes? html "href=\"http://fix-login.brian.localhost:3142\""))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bb nido:test :only nido.ui.views`
Expected: FAIL — the rendered row has `—` in the url cell because `session-row` reads `(:url entry)` (nil), so the `href` is absent.

- [ ] **Step 3: Fix the key**

In `src/nido/ui/views.clj`, in `session-row`, change line 241 from:

```clojure
        url (:url entry)
```

to:

```clojure
        url (:app-url entry)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bb nido:test :only nido.ui.views`
Expected: PASS.

- [ ] **Step 5: Commit (jj, code+test paths only)**

```bash
jj commit src/nido/ui/views.clj test/nido/ui/views_test.clj \
  -m "fix(ui): per-session table reads :app-url so the friendly-host link renders"
```

---

### Task 2: Cross-project `all-session-rows` aggregation

**Files:**
- Test: `test/nido/ui/server_test.clj` (create)
- Modify: `src/nido/ui/server.clj` (add `all-session-rows` after the existing private `session-rows`, ~line 156)

- [ ] **Step 1: Write the failing test**

Create `test/nido/ui/server_test.clj`:

```clojure
(ns nido.ui.server-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [nido.ui.server :as server]
            [nido.project :as project]))

(deftest all-session-rows-aggregates-and-sorts-live-first
  ;; Pure 2-arity: inject the per-project row builder + the projects map so the
  ;; aggregation/sort is testable without a real registry or worktrees on disk.
  (let [rows-fn  (fn [pname _dir]
                   (case pname
                     "brian" [{:name "b-down" :live? false :entry nil}
                              {:name "b-up"   :live? true  :entry {:app-url "u1"}}]
                     "foo"   [{:name "f-up"   :live? true  :entry {:app-url "u2"}}]))
        projects {"brian" {:directory "/x"} "foo" {:directory "/y"}}
        rows     (server/all-session-rows rows-fn projects)]
    ;; live-first, then project, then name; each row tagged with :project
    (is (= [["brian" "b-up" true] ["foo" "f-up" true] ["brian" "b-down" false]]
           (map (juxt :project :name :live?) rows)))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bb nido:test :only nido.ui.server`
Expected: FAIL — `Unable to resolve symbol: all-session-rows` (or "no such var").

- [ ] **Step 3: Implement `all-session-rows`**

In `src/nido/ui/server.clj`, add immediately after the `session-rows` defn (the one ending ~line 156). `session-rows` stays private; the 2-arity is the pure, testable seam:

```clojure
(defn all-session-rows
  "Aggregate session rows across all registered projects into one flat,
   live-first list. Each row is tagged with its :project. The 2-arity is pure
   given the per-project row builder + projects map, so it is unit-testable;
   the 0-arity wires the real `session-rows` and registry."
  ([] (all-session-rows session-rows (project/list-projects)))
  ([rows-fn projects]
   (->> (for [[pname entry] projects
              row           (rows-fn pname (:directory entry))]
          (assoc row :project pname))
        (sort-by (juxt #(if (:live? %) 0 1) :project :name)))))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bb nido:test :only nido.ui.server`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj commit src/nido/ui/server.clj test/nido/ui/server_test.clj \
  -m "feat(ui): all-session-rows aggregates sessions across projects, live-first"
```

---

### Task 3: Board views (`live-board-fragment` + `live-board-page`)

**Files:**
- Test: `test/nido/ui/views_test.clj` (extend)
- Modify: `src/nido/ui/views.clj` (add after `sessions-page`, ~line 352)

- [ ] **Step 1: Write the failing test**

Append to `test/nido/ui/views_test.clj`:

```clojure
(deftest live-board-fragment-links-live-and-routes-down
  (let [html (views/live-board-fragment
              [{:project "brian" :name "fix-login" :live? true
                :entry {:app-url "http://fix-login.brian.localhost:3142"}}
               {:project "brian" :name "doc-room" :live? false :entry nil}])]
    ;; live row: clickable friendly-host link opening a new tab
    (is (str/includes? html "href=\"http://fix-login.brian.localhost:3142\""))
    (is (str/includes? html "target=\"_blank\""))
    ;; both sessions listed
    (is (str/includes? html "fix-login"))
    (is (str/includes? html "doc-room"))
    ;; down row: routes to the per-project page (where start/stop live)
    (is (str/includes? html "/brian/sessions"))
    (is (str/includes? html "start"))))

(deftest live-board-page-renders-header-and-poll
  (let [html (views/live-board-page [])]
    (is (str/includes? html "live sessions"))
    ;; auto-refresh against the board SSE fragment
    (is (str/includes? html "/_fragment/live"))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bb nido:test :only nido.ui.views`
Expected: FAIL — `live-board-fragment` / `live-board-page` unresolved.

- [ ] **Step 3: Implement the board views**

In `src/nido/ui/views.clj`, add after `sessions-page` (~line 352):

```clojure
;; ---------------------------------------------------------------------------
;; Live-sessions board (dashboard home)

(defn- board-row
  "One board row: project · session · status · dev URL. Live sessions get the
   clickable friendly-host link (new tab → own cookie jar); down sessions route
   to the per-project page where start/stop controls live."
  [{:keys [project name live? entry]}]
  (let [url (:app-url entry)]
    [:tr
     [:td.mono project]
     [:td [:a {:href (str "/" project "/sessions/" name "/logs/repl")} [:strong name]]]
     [:td (if live?
            [:span {:style "color:#4ade80"} "● up"]
            [:span.meta "○ down"])]
     [:td (cond
            (and live? url) [:a {:href url :target "_blank"} url]
            live?           [:span.meta "—"]
            :else           [:a {:href (str "/" project "/sessions")} "start →"])]]))

(defn live-board-fragment
  "Just the board tbody — initial render + SSE refresh."
  [rows]
  (str
   (h/html
    (if (seq rows)
      [:tbody {:id "board-body"}
       (for [row rows] (board-row row))]
      [:tbody {:id "board-body"}
       [:tr [:td {:colspan "4"} [:span.empty "No sessions yet — run `bb nido:session:up <name>`."]]]]))))

(defn live-board-page
  "Dashboard home: a flat, live-first board of every session across projects."
  [rows]
  (layout
   "live sessions"
   [:h1 "nido — live sessions"]
   [:p.meta [:a {:href "/projects"} "all projects →"]]
   [:div {:data-on-interval__duration.3s "@get('/_fragment/live')"}
    [:table
     [:thead
      [:tr [:th "project"] [:th "session"] [:th "status"] [:th "dev url"]]]
     (h/raw (live-board-fragment rows))]]))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bb nido:test :only nido.ui.views`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj commit src/nido/ui/views.clj test/nido/ui/views_test.clj \
  -m "feat(ui): live-sessions board views (fragment + page)"
```

---

### Task 4: Route the board (home → board, /projects → grid, /_fragment/live)

**Files:**
- Test: `test/nido/ui/server_test.clj` (extend)
- Modify: `src/nido/ui/server.clj` — `handle-get` `case` (~line 241) and make `handle-request` public (~line 344)

- [ ] **Step 1: Write the failing test**

Append to `test/nido/ui/server_test.clj`:

```clojure
(deftest home-route-renders-board
  (with-redefs [server/all-session-rows (fn [] [])]
    (let [resp (server/handle-request {:request-method :get :uri "/"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "live sessions")))))

(deftest projects-route-renders-grid
  (with-redefs [project/list-projects (fn [] {"brian" {:directory "/x"}})]
    (let [resp (server/handle-request {:request-method :get :uri "/projects"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "brian")))))

(deftest live-fragment-route-is-sse
  (with-redefs [server/all-session-rows (fn [] [])]
    (let [resp (server/handle-request {:request-method :get :uri "/_fragment/live"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (get-in resp [:headers "Content-Type"]) "text/event-stream")))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bb nido:test :only nido.ui.server`
Expected: FAIL — `handle-request` is private (cannot resolve from another ns) and `/` still returns the project grid (no "live sessions").

- [ ] **Step 3: Make `handle-request` public and add the routes**

In `src/nido/ui/server.clj`:

(a) Change `handle-request` from private to public (~line 344):

```clojure
(defn handle-request [{:keys [request-method] :as req}]
  (case request-method
    :post (handle-post req)
    (handle-get req)))
```

(b) In `handle-get`, replace the `[]` clause and add two new clauses at the top of the `case segments` form (~line 241):

```clojure
  (let [segments (parse-path uri)]
    (case segments
      ;; GET / — live-sessions board across all projects (dashboard home)
      []
      (html-response 200 (views/live-board-page (all-session-rows)))

      ;; GET /projects — the original project grid
      ["projects"]
      (html-response 200 (views/home-page (project/list-projects)))

      ;; GET /_fragment/live — SSE board tbody
      ["_fragment" "live"]
      (sse-response (sse-fragment (views/live-board-fragment (all-session-rows))))

      ;; Otherwise, dispatch on structure
      (let [project-name (first segments)]
```

(Leave the rest of `handle-get` — the per-project dispatch — unchanged.)

- [ ] **Step 4: Run test to verify it passes**

Run: `bb nido:test :only nido.ui.server`
Expected: PASS (all three new tests + Task 2's).

- [ ] **Step 5: Commit**

```bash
jj commit src/nido/ui/server.clj test/nido/ui/server_test.clj \
  -m "feat(ui): home route serves the live board; project grid moves to /projects"
```

---

### Task 5: Bundle the dashboard into the daemon lifecycle

**Files:**
- Test: `test/nido/coordinator/core_dashboard_test.clj` (create)
- Modify: `src/nido/coordinator/core.clj` (ns require, `defaults`, `run!`, `install-shutdown-hook!`, `tick!`, new `dashboard-config`)
- Modify: `src/tasks/nido_coordinator.clj` (`run` + `up` arg threading)

- [ ] **Step 1: Write the failing test**

Create `test/nido/coordinator/core_dashboard_test.clj`:

```clojure
(ns nido.coordinator.core-dashboard-test
  (:require [clojure.test :refer [deftest is]]
            [nido.coordinator.core :as core]))

(deftest dashboard-config-defaults-on-8800
  (is (= {:enabled? true :port 8800} (core/dashboard-config {}))))

(deftest dashboard-config-respects-overrides
  (is (= {:enabled? true :port 9001} (core/dashboard-config {:dashboard-port 9001})))
  (is (false? (:enabled? (core/dashboard-config {:no-dashboard true})))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bb nido:test :only nido.coordinator.core-dashboard`
Expected: FAIL — `dashboard-config` unresolved.

- [ ] **Step 3: Add `:dashboard` defaults and `dashboard-config`**

In `src/nido/coordinator/core.clj`:

(a) Add to the ns `:require` (after the existing `nido.session.profiles` line):

```clojure
   [nido.ui.server :as ui-server]
```

(b) Add a `:dashboard` key to the `defaults` map (alongside `:poll-ms` etc.):

```clojure
   :dashboard           {:enabled? true :port 8800}
```

(c) Add a `!dashboard-port` atom near the other `defonce`s (~line 73):

```clojure
;; Resolved dashboard port for the running daemon (nil when disabled). Recorded
;; in the heartbeat so `status` can report + probe the right port.
(defonce ^:private !dashboard-port (atom nil))
```

(d) Add the pure resolver (place above `run!`):

```clojure
(defn dashboard-config
  "Resolve {:enabled? :port} for the in-process dashboard from run! opts over
   `defaults`. `:no-dashboard true` disables it; `:dashboard-port` overrides the
   port."
  [{:keys [dashboard-port no-dashboard]}]
  (let [d (:dashboard defaults)]
    {:enabled? (boolean (and (:enabled? d) (not no-dashboard)))
     :port     (or dashboard-port (:port d))}))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bb nido:test :only nido.coordinator.core-dashboard`
Expected: PASS.

- [ ] **Step 5: Wire start/stop/heartbeat (no new test — integration wiring)**

In `src/nido/coordinator/core.clj`:

(a) `run!` — change the arglist to capture all opts and start the server after `resubmit-queued!`:

```clojure
(defn run!
  [& {:keys [poll-ms] :as opts :or {poll-ms (:poll-ms defaults)}}]
  (cstate/ensure-dirs!)
  (println "nido coordinator: starting (poll" poll-ms "ms)")
  (reconcile/reconcile!)
  (resubmit-queued! (load-all-triggers))
  (let [{:keys [enabled? port]} (dashboard-config opts)]
    (reset! !dashboard-port (when enabled? port))
    (when enabled?
      (try (ui-server/start! {:port port})
           (catch Throwable t
             (reset! !dashboard-port nil)
             (println "WARN: dashboard failed to start —" (ex-message t))))))
  (pid/write! (long (.pid (java.lang.ProcessHandle/current))))
  (install-shutdown-hook!)
  (heartbeat/write! {:status :running :slots-in-use 0 :dashboard-port @!dashboard-port})
  (executor/configure! {:global-cap (:global-parallel-cap defaults)})
  (nsource/register!)
  (loop []
    (tick!)
    (Thread/sleep poll-ms)
    (recur)))
```

(b) `install-shutdown-hook!` — stop the server first in the hook body:

```clojure
(defn- install-shutdown-hook! []
  (.addShutdownHook
    (Runtime/getRuntime)
    (Thread.
      (fn []
        (try (ui-server/stop!) (catch Exception _ nil))
        (doseq [hash (keys @!source-instances)]
          (stop-source! hash))
        (try (heartbeat/write! {:status :stopped :slots-in-use 0})
             (catch Exception _ nil))
        (try (pid/delete!)
             (catch Exception _ nil))))))
```

(c) `tick!` — include the dashboard port in the running heartbeat. Change the `:running` write inside `tick!` (~line 484) to:

```clojure
        (heartbeat/write! {:status :running :slots-in-use 0 :dashboard-port @!dashboard-port})
```

- [ ] **Step 6: Thread the CLI args through `run` and `up`**

In `src/tasks/nido_coordinator.clj`:

(a) Replace `run` (~line 21):

```clojure
(defn run [& args]
  (let [[_ opts]  (task-args/split-args args)
        ms        (some-> (:poll-ms opts) str parse-long)
        dport     (some-> (:dashboard-port opts) str parse-long)
        no-dash?  (= true (:no-dashboard opts))]
    (apply core/run! (cond-> []
                       ms       (into [:poll-ms ms])
                       dport    (into [:dashboard-port dport])
                       no-dash? (into [:no-dashboard true])))))
```

(b) In `up`, extend the bare-spawn `cmd` builder (~line 115) to forward the new flags:

```clojure
              cmd       (cond-> ["bb" "nido:coordinator:run"]
                          (:poll-ms opts)        (into [":poll-ms" (str (:poll-ms opts))])
                          (:dashboard-port opts) (into [":dashboard-port" (str (:dashboard-port opts))])
                          (= true (:no-dashboard opts)) (into [":no-dashboard" "true"]))
```

- [ ] **Step 7: Verify the suite still loads + passes**

Run: `bb nido:test :only nido.coordinator`
Expected: PASS (core loads with the new `ui-server` require; dashboard-config tests green).

- [ ] **Step 8: Commit**

```bash
jj commit src/nido/coordinator/core.clj src/tasks/nido_coordinator.clj test/nido/coordinator/core_dashboard_test.clj \
  -m "feat(coordinator): run the dashboard in-process with the daemon"
```

---

### Task 6: `status` Dashboard line + docs

**Files:**
- Test: `test/nido/coordinator/core_dashboard_test.clj` (extend)
- Modify: `src/nido/coordinator/core.clj` (add `dashboard-status-line`)
- Modify: `src/tasks/nido_coordinator.clj` (`status` + require `nido.process`)
- Modify: `CLAUDE.md`

- [ ] **Step 1: Write the failing test**

Append to `test/nido/coordinator/core_dashboard_test.clj`:

```clojure
(deftest dashboard-status-line-formats
  (is (clojure.string/includes? (core/dashboard-status-line 8800 true) "http://localhost:8800"))
  (is (clojure.string/includes? (core/dashboard-status-line 8800 true) "reachable"))
  (is (clojure.string/includes? (core/dashboard-status-line 8800 false) "not reachable")))
```

(Add `[clojure.string]` to the test ns require, or use the fully-qualified `clojure.string/includes?` as written.)

- [ ] **Step 2: Run test to verify it fails**

Run: `bb nido:test :only nido.coordinator.core-dashboard`
Expected: FAIL — `dashboard-status-line` unresolved.

- [ ] **Step 3: Implement `dashboard-status-line`**

In `src/nido/coordinator/core.clj`, next to `dashboard-config`:

```clojure
(defn dashboard-status-line
  "Format the `status` Dashboard line for a resolved port + reachability."
  [port reachable?]
  (format "Dashboard:   http://localhost:%s (%s)"
          port (if reachable? "reachable" "not reachable")))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bb nido:test :only nido.coordinator.core-dashboard`
Expected: PASS.

- [ ] **Step 5: Call it from `status`**

In `src/tasks/nido_coordinator.clj`:

(a) Add to the ns require:

```clojure
   [nido.process :as proc]
```

(b) Inside `status`, in the `(if (fs/exists? p) (let [s (io/read-edn p)] …))` branch, after the `Slots:` println, add:

```clojure
        (when-let [dport (:dashboard-port s)]
          (println (core/dashboard-status-line dport (proc/tcp-open? dport))))
```

- [ ] **Step 6: Document in CLAUDE.md**

In `CLAUDE.md`, under the coordinator section (after the "Running the daemon (Stage 3)" block), add:

```markdown
**Web dashboard (bundled with the daemon):** the coordinator runs the dashboard
in-process. With the daemon up it's always at `http://localhost:8800` — the home
page is a flat, live-first board of every session across projects, each live
session a clickable friendly-host link (`<session>.<project>.localhost`). Override
with `bb nido:coordinator:up :dashboard-port <n>`; disable with `:no-dashboard true`.
`bb nido:coordinator:status` shows a `Dashboard:` line (port + reachability).

The standalone `bb nido:ui [:port 8800]` task still exists for UI iteration or when
the daemon is down — but it and the daemon both bind 8800, so don't run both.
```

- [ ] **Step 7: Commit**

```bash
jj commit src/nido/coordinator/core.clj src/tasks/nido_coordinator.clj test/nido/coordinator/core_dashboard_test.clj CLAUDE.md \
  -m "feat(coordinator): status reports the bundled dashboard; document it"
```

---

### Task 7: Full-suite + manual verification (no commit)

- [ ] **Step 1: Run the full unit suite**

Run: `bb nido:test :only nido`
Expected: PASS (no regressions across the suite).

- [ ] **Step 2: Manual smoke — daemon-bundled board**

```bash
bb nido:coordinator:down   # if running
bb nido:coordinator:up
bb nido:coordinator:status # expect a "Dashboard:   http://localhost:8800 (reachable)" line
```

Open `http://localhost:8800`:
- The home page is the live-sessions board (header "nido — live sessions"), one row per session across all projects, live-first.
- Bring a session up/down (`bb nido:session:up :project brian <s>`) and confirm the board reflects it within ~3s without reloading.

- [ ] **Step 3: Manual smoke — friendly-host link**

On the board, click a live session's dev URL. Expected: it opens `http://<session>.<project>.localhost:<port>` in a new tab and lands in that session's app. **Verify the brian app serves correctly under the friendly host** (no broken absolute redirect to a hard-coded host, login cookie sticks) — this is the §Risks caveat from the spec. If the app misbehaves under the friendly host, fall back to `http://localhost:<port>` for the link (change `:app-url` usage in `board-row`/`session-row` to build `localhost:<app-port>`) and note it for follow-up.

- [ ] **Step 4: Manual smoke — overrides**

```bash
bb nido:coordinator:down
bb nido:coordinator:up :dashboard-port 8899
```
Confirm `http://localhost:8899` serves the board and `status` reports port 8899. Then:
```bash
bb nido:coordinator:down
bb nido:coordinator:up :no-dashboard true
```
Confirm nothing is listening on 8800 and `status` shows no Dashboard line. Restore default: `bb nido:coordinator:down && bb nido:coordinator:up`.

---

## Self-Review notes

- **Spec coverage:** §1 daemon-bundled → Tasks 5, 6 (+ docs). §2 flat board home → Tasks 2, 3, 4. §3 friendly-host clickable → Tasks 1, 3 (read `:app-url`, `target=_blank`). Testing section → Tasks 2/3/5/6 unit + Task 7 manual. Risks (friendly-host reachability) → Task 7 Step 3.
- **Type/name consistency:** `all-session-rows` (server) used in Task 2 def + Task 4 routes; `live-board-fragment`/`live-board-page` defined Task 3, routed Task 4; `dashboard-config`/`dashboard-status-line`/`!dashboard-port` consistent across Tasks 5–6; heartbeat `:dashboard-port` written (run!/tick!) and read (status).
- **jj:** every commit step lists explicit code/test/doc paths so `docs/superpowers/` spec+plan stay uncommitted.
