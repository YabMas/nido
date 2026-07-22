# Dissolve the Workstream/System Split — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the system surface from web + TUI by making the workstream spine the only surface: every live session belongs to an open workstream (durable adoption), leftover resources render as a winding-down band, and global ops levers become ambient chrome.

**Architecture:** Three phases. (1) Model: `nido.work` gains the liveness oracle (`live-session-names`, `machine-rows`), the adoption invariant (`adopt-orphans!` on the daemon reconcile tick), a `winding-down` derivation threaded through `grouped`/`tab-bands`/`screen`, and `bring-down!`. (2) Web: the workstream pane's session rows absorb the machine facts + lifecycle controls, the rail health dot expands into an ops panel, the Active tab grows a winding-down band, `/system` becomes a redirect. (3) TUI: session plumbing moves to the detail screen, ops levers move to an `s`-key overlay modal, the `:system` screen is deleted.

**Tech Stack:** Babashka/Clojure, hiccup2 + Datastar v1.0.1 (web), charm.clj (TUI), clojure.test via `bb nido:test`.

**Spec:** `docs/superpowers/specs/2026-07-21-dissolve-system-surface-design.md`

## Global Constraints

- **VCS is jj, not git.** Every task: check `jj st`; if `@` is dirty with unrelated work run `jj new` first; set intent with `jj desc -m "…"`; after the task's steps pass, seal with `jj new`. Never run bare `git`.
- **Do NOT commit this plan file or the spec file** (`docs/superpowers/plans/*`, `docs/superpowers/specs/*`). They stay untracked/uncommitted.
- **Load-check after every source edit** (even comment-only): `bb -e "(require 'the.changed.ns)"` before committing.
- **Test runner:** `bb nido:test :only <ns-prefix>` for a namespace subset, bare `bb nido:test` for everything.
- **The daemon reads src/ once at startup** — coordinator changes (Task 5) do nothing to the live daemon until `bb nido:coordinator:restart` (final task).
- `nido.work` must NOT require `nido.session.dev` (dev already requires work — would cycle). UI-optimistic state is injected by surfaces, never read by work.
- Namespace aliases used below (as in the existing code): `cws` = nido.coordinator.workstream, `csession` = nido.coordinator.session, `wsv` = nido.coordinator.workstreams-view, `scratch` = nido.coordinator.scratch.

---

## Phase 1 — Model

### Task 1: The liveness oracle moves into `nido.work`

`live-session-names` currently lives in `nido.tui` (private); the machine scan lives in `nido.ui.server`. Both move to `nido.work` so the adopter, the winding-down derivation, the TUI, and the web read ONE oracle. The server's scan (`session-rows`/`all-session-rows`) is left in place for now — it carries UI-optimistic `:pending-state` that work must not know about, and Task 9 deletes it with the surface.

**Files:**
- Modify: `src/nido/work.clj` (new requires: `nido.session.state`, `nido.process`; new fns at the end of the file, before `screen`)
- Modify: `src/nido/tui.clj` (delete private `live-session-names` at :162–173, use `work/live-session-names`)
- Test: `test/nido/work_test.clj`

**Interfaces:**
- Produces: `(work/live-session-names project)` → set of session-name strings holding any registry port. Accepts keyword or string project.
- Produces: `(work/machine-rows project-name project-dir)` → seq of `{:name :wt-path :entry :live? :repl-rss :pg-rss :heap-max}` sorted by :name (nil when no worktrees dir).
- Produces: `(work/all-machine-rows)` / `(work/all-machine-rows rows-fn projects)` → cross-project rows each tagged `:project`, live-first sorted.
- Produces: `work/all-grouped` now threads live-names per project (was nil).

- [ ] **Step 1: `jj st` → `jj new` if dirty → `jj desc -m "refactor(work): move liveness oracle (live-session-names, machine scan) into nido.work"`**

- [ ] **Step 2: Write the failing tests** (append to `test/nido/work_test.clj`):

```clojure
(deftest live-session-names-are-the-ones-with-ports
  ;; Mirrors the TUI test of the same name — the oracle now lives in work.
  (with-redefs [nido.session.lifecycle/list-all-data
                (fn [_] {:sessions [{:name "up1" :pg-port 5501}
                                    {:name "up2" :app-port 3101}
                                    {:name "down" :pg-port nil :app-port nil :nrepl-port nil}]})]
    (is (= #{"up1" "up2"} (work/live-session-names "p")))))

(deftest all-machine-rows-aggregates-and-sorts-live-first
  (let [rows-fn  (fn [pname _dir]
                   (case pname
                     "brian" [{:name "b-down" :live? false :entry nil}
                              {:name "b-up"   :live? true  :entry {:url "u1"}}]
                     "foo"   [{:name "f-up"   :live? true  :entry {:url "u2"}}]))
        projects {"brian" {:directory "/x"} "foo" {:directory "/y"}}
        rows     (work/all-machine-rows rows-fn projects)]
    (is (= [["brian" "b-up" true] ["foo" "f-up" true] ["brian" "b-down" false]]
           (map (juxt :project :name :live?) rows)))))
```

- [ ] **Step 3: Run to verify they fail**

Run: `bb nido:test :only nido.work`
Expected: FAIL — `live-session-names` / `all-machine-rows` unresolved.

- [ ] **Step 4: Implement in `src/nido/work.clj`.** Add requires `[nido.process :as proc]` and `[nido.session.state :as sstate]` to the ns form. Add:

```clojure
(defn live-session-names
  "Set of session names for `project` that are actually up — i.e. hold a pg/app/
   nrepl port in the registry. THE liveness oracle: the TUI board, the web
   grouping, the adopter, and the winding-down band all read this one fn."
  [project]
  (->> (lifecycle/list-all-data {:project (name project)})
       :sessions
       (keep (fn [s] (when (or (:pg-port s) (:app-port s) (:nrepl-port s)) (:name s))))
       set))

(defn- instance-id-for [project-name session-name]
  (if (= project-name session-name)
    project-name
    (str project-name "--" session-name)))

(defn machine-rows
  "Machine facts for every worktree of one project: registry entry, TCP liveness,
   RSS for the repl JVM + PG, and the configured heap ceiling. No UI-optimistic
   state — that is a surface concern injected by callers that need it."
  [project-name project-dir]
  (let [base     (lifecycle/worktrees-dir project-name project-dir)
        registry (sstate/read-registry)]
    (when (fs/exists? base)
      (->> (fs/list-dir base)
           (filter fs/directory?)
           (map (fn [d]
                  (let [nm       (str (fs/file-name d))
                        wt-path  (str d)
                        entry    (get registry wt-path)
                        port     (:app-port entry)
                        live?    (and (pos-int? port) (proc/tcp-open? port))
                        iid      (instance-id-for project-name nm)
                        repl-rss (when (and live? (:repl-pid entry))
                                   (proc/rss-bytes (:repl-pid entry)))
                        session  (when live? (sstate/read-session iid))
                        pg-pid   (when session
                                   (get-in session [:service-states :pg :pg-pid]))
                        pg-rss   (when (and live? pg-pid) (proc/rss-bytes pg-pid))
                        heap-max (when session
                                   (get-in session [:context :session :jvm :heap-max]))]
                    {:name nm :wt-path wt-path :entry entry :live? live?
                     :repl-rss repl-rss :pg-rss pg-rss :heap-max heap-max})))
           (sort-by :name)))))

(defn all-machine-rows
  "Machine rows across all registered projects, live-first, each tagged :project.
   2-arity is pure (inject rows-fn + projects) for tests."
  ([] (all-machine-rows machine-rows (project/list-projects)))
  ([rows-fn projects]
   (->> (for [[pname entry] projects
              row           (or (try (rows-fn pname (:directory entry))
                                     (catch Throwable _ nil))
                                [])]
          (assoc row :project pname))
        (sort-by (juxt #(if (:live? %) 0 1) :project :name)))))
```

Then in `all-grouped`, thread liveness: replace `(grouped pname)` with `(grouped pname (live-session-names pname))`.

- [ ] **Step 5: Point the TUI at the oracle.** In `src/nido/tui.clj` delete the private `live-session-names` (lines ~164–173) and change its one caller in `board-rows` from `(live-session-names project)` to `(work/live-session-names project)`. Delete the now-redundant TUI test `live-session-names-are-the-ones-with-ports` from `test/nido/tui_test.clj` (the work test covers it).

- [ ] **Step 6: Load-check + run tests**

Run: `bb -e "(require 'nido.work 'nido.tui)"` then `bb nido:test :only nido.work` and `bb nido:test :only nido.tui`
Expected: PASS.

- [ ] **Step 7: Commit** — `jj new` (the description from Step 1 stands).

---

### Task 2: The adoption invariant — `orphan-live-sessions` + `adopt-orphans!`

**Files:**
- Modify: `src/nido/work.clj` (below the Task 1 fns; add `[clojure.set :as set]` to requires)
- Test: `test/nido/work_test.clj`

**Interfaces:**
- Consumes: `work/live-session-names` (Task 1), `scratch/birth!`, `scratch/scratch?`, `cws/list-ids`, `cws/read-ws`, `cws/delete!`, `csession/list-sessions`.
- Produces: `(work/orphan-live-sessions live owned)` → set (pure). `(work/adopt-orphans! project)` → `{:adopted [names] :yielded [ws-ids]}` — Task 5 calls this from the daemon tick.

- [ ] **Step 1: `jj st` clean → `jj desc -m "feat(work): adoption invariant — live orphan sessions get scratch workstreams"`**

- [ ] **Step 2: Write the failing tests:**

```clojure
(deftest orphan-live-sessions-is-the-set-difference
  (is (= #{"a"} (work/orphan-live-sessions #{"a" "b"} #{"b" "c"}))))

(deftest adopt-orphans!-births-scratch-for-live-orphans-idempotently
  (with-tmp
    (fn [_]
      (with-redefs [work/live-session-names (constantly #{"one-off"})]
        (is (= ["one-off"] (:adopted (work/adopt-orphans! :p))))
        ;; the born scratch ws owns the session now → second pass adopts nothing
        (is (= [] (:adopted (work/adopt-orphans! :p))))
        (let [ws (map #(workstream/read-ws :p %) (workstream/list-ids :p))]
          (is (= 1 (count ws)))
          (is (empty? (:external-refs (first ws)))))))))

(deftest adopt-orphans!-skips-closed-owned-sessions
  ;; A session under a CLOSED ws is a winding-down leftover, not an orphan.
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :p {:stage :in-progress :external-refs []})]
        (session/create! :p (:id w) {:name "left" :weight :light :autonomy nil})
        (workstream/close! :p (:id w) :done)
        (with-redefs [work/live-session-names (constantly #{"left"})]
          (is (= [] (:adopted (work/adopt-orphans! :p)))))))))

(deftest adopt-orphans!-yields-a-bare-scratch-double-owner
  ;; Adopted-then-claimed: when a REAL open ws also owns the session, the bare
  ;; scratch ws is deleted (newest real owner wins).
  (with-tmp
    (fn [_]
      (with-redefs [work/live-session-names (constantly #{"claimed"})]
        (work/adopt-orphans! :p)                              ; births scratch owner
        (let [real (workstream/create! :p {:stage :in-progress
                                           :external-refs [{:adapter :notion :id "BR-1"}]})]
          (session/create! :p (:id real) {:name "claimed" :weight :light :autonomy nil})
          (let [{:keys [yielded]} (work/adopt-orphans! :p)]
            (is (= 1 (count yielded)))
            (is (= [(:id real)]
                   (map :id (keep #(workstream/read-ws :p %) (workstream/list-ids :p)))))))))))
```

- [ ] **Step 3: Run to verify FAIL** — `bb nido:test :only nido.work` (unresolved fns).

- [ ] **Step 4: Implement in `src/nido/work.clj`:**

```clojure
(defn- owned-session-names
  "Session names owned by ANY workstream of `project` — open or closed. Closed
   owners keep their sessions out of adoption (they are winding-down leftovers)."
  [project]
  (->> (cws/list-ids project)
       (mapcat #(csession/list-sessions project %))
       (map :name)
       set))

(defn orphan-live-sessions
  "Pure: the live sessions no workstream owns."
  [live owned]
  (set/difference (set live) (set owned)))

(defn- yield-duplicate-scratch!
  "Adopted-then-claimed: delete any BARE scratch workstream (no refs, no ledger
   entries, exactly one session) whose session is also owned by another OPEN
   workstream — the newest real owner wins. Returns the deleted ws-ids."
  [project]
  (let [open (->> (cws/list-ids project)
                  (keep #(cws/read-ws project %))
                  (remove :closed))
        owners-of (fn [n]
                    (filter (fn [w] (some #(= n (:name %))
                                          (csession/list-sessions project (:id w))))
                            open))]
    (->> open
         (filter (fn [w] (and (scratch/scratch? w) (empty? (:entries w)))))
         (keep (fn [w]
                 (let [sess (csession/list-sessions project (:id w))]
                   (when (and (= 1 (count sess))
                              (some #(not= (:id w) (:id %))
                                    (owners-of (:name (first sess)))))
                     (cws/delete! project (:id w))
                     (:id w)))))
         vec)))

(defn adopt-orphans!
  "Enforce the invariant: every live session is reachable from a workstream.
   Births a scratch workstream for each live orphan (idempotent — birth! no-ops
   on an owned name), then yields bare scratch duplicates to real owners.
   Returns {:adopted [names] :yielded [ws-ids]}."
  [project]
  (let [orphans (sort (orphan-live-sessions (live-session-names project)
                                            (owned-session-names project)))]
    (doseq [n orphans]
      (scratch/birth! (keyword (name project)) n))
    {:adopted (vec orphans)
     :yielded (yield-duplicate-scratch! project)}))
```

- [ ] **Step 5: Load-check + tests** — `bb -e "(require 'nido.work)"`, `bb nido:test :only nido.work`. Expected: PASS.

- [ ] **Step 6: Commit** — `jj new`.

---

### Task 3: `winding-down` derivation, threaded through `grouped` → `tab-bands` → `screen`

**Files:**
- Modify: `src/nido/work.clj` (`winding-down` new; `grouped`, `tab-bands`, `grouped-rows`, `screen` extended)
- Test: `test/nido/work_test.clj` (new tests + extend the union oracle + tab-bands tests)

**Interfaces:**
- Produces: `(work/winding-down project live-names)` → vector of `{:ws-id :project :stage :winding-down :origin :label :outcome :sessions [live-names] :needs-you false}`.
- Produces: `work/grouped` 2-arity now assocs `:winding-down` (empty when `live-names` nil — purity for tests/legacy callers).
- Produces: `tab-bands :active` emits a trailing `[:winding-down rows]` band; `grouped-rows` includes them (union oracle).
- Produces: `work/screen` accepts `:winddown-pending` (set of "project/ws-id") in `data` and marks matching winding-down rows `:pending? true`.

- [ ] **Step 1: `jj desc -m "feat(work): winding-down band — closed workstreams still holding live sessions"`**

- [ ] **Step 2: Failing tests:**

```clojure
(deftest winding-down-lists-closed-ws-with-live-sessions
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :p {:stage :in-progress :external-refs []})]
        (session/create! :p (:id w) {:name "s1" :weight :light :autonomy nil})
        (workstream/close! :p (:id w) :done)
        (let [[row :as rows] (work/winding-down :p #{"s1"})]
          (is (= 1 (count rows)))
          (is (= (:id w) (:ws-id row)))
          (is (= :winding-down (:stage row)))
          (is (= :done (:outcome row)))
          (is (= ["s1"] (:sessions row)))
          (is (false? (:needs-you row))))
        (is (= [] (work/winding-down :p #{})) "downed sessions → gone")))))

(deftest winding-down-ignores-open-workstreams
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :p {:stage :in-progress :external-refs []})]
        (session/create! :p (:id w) {:name "s1" :weight :light :autonomy nil})
        (is (= [] (work/winding-down :p #{"s1"})))))))

(deftest tab-bands-active-appends-winding-down
  (let [grouped {:in-progress [{:ws-id "p"}] :shipping [{:ws-id "s"}]
                 :winding-down [{:ws-id "w"}]}]
    (is (= [[:shipping ["s"]] [:in-progress ["p"]] [:winding-down ["w"]]]
           (for [[stage rows] (work/tab-bands :active grouped)]
             [stage (mapv :ws-id rows)])))))

(deftest screen-marks-pending-winding-down-rows
  (let [groups [{:project "p" :grouped {:winding-down [{:ws-id "w1"} {:ws-id "w2"}]}}]
        screen (work/screen {:scope "all" :tab :active}
                            {:groups groups :winddown-pending #{"p/w1"}})]
    (is (= [true false]
           (->> screen :groups first :grouped :winding-down (map (comp boolean :pending?)))))))
```

Also EXTEND the existing `tab-bands-union-covers-every-row-exactly-once` and `tab-bands-splits-the-spine-into-two-jobs` tests: add `:winding-down [{:ws-id "w"}]` to their `grouped` fixture; the union test's expectation must now include `"w"` in the `:active` set, and the split test's `:active` expectation becomes `[[:shipping ["s"]] [:in-progress ["p"]] [:winding-down ["w"]]]`.

- [ ] **Step 3: Run to verify FAIL** — `bb nido:test :only nido.work`.

- [ ] **Step 4: Implement.** In `work.clj`:

```clojure
(defn winding-down
  "Closed (:done/:dropped) workstreams of `project` still holding ≥1 live
   session — resources you're paying for on finished work. Never gates
   (:needs-you false); rendered as the Active tab's trailing band with one
   action: bring-down!. Empty when live-names is empty/nil."
  [project live-names]
  (let [live (set live-names)]
    (if (empty? live)
      []
      (->> (cws/list-ids project)
           (keep #(cws/read-ws project %))
           (filter :closed)
           (keep (fn [w]
                   (let [sessions (csession/list-sessions project (:id w))
                         live-s   (filterv #(contains? live (:name %)) sessions)]
                     (when (seq live-s)
                       {:ws-id     (:id w)
                        :project   (name project)
                        :stage     :winding-down
                        :origin    (classify-origin w)
                        :label     (wsv/label w sessions)
                        :outcome   (get-in w [:closed :outcome])
                        :sessions  (mapv :name live-s)
                        :needs-you false}))))
           vec))))
```

In `grouped` (2-arity body), assoc the band:

```clojure
([project live-names]
 (assoc (wsv/grouped-by-stage (list-workstreams project live-names))
        :winding-down (winding-down project live-names)))
```

In `tab-bands`, the `:active` arm becomes:

```clojure
:active [[:shipping     (:shipping grouped)]
         [:in-progress  (:in-progress grouped)]
         [:winding-down (:winding-down grouped)]]
```

and update the `tab-bands` docstring's `:active` line to mention the trailing winding-down band (closed but still holding live resources). In `grouped-rows`, append `(:winding-down grouped)` to the concat (and its docstring note about the union oracle still applies).

In `screen`, destructure `winddown-pending` from `data` (default `#{}`) and, after computing `scoped`, mark the rows:

```clojure
scoped (mapv (fn [{:keys [project] :as g}]
               (update-in g [:grouped :winding-down]
                          (fn [rows]
                            (mapv #(assoc % :pending?
                                          (contains? winddown-pending
                                                     (str project "/" (:ws-id %))))
                                  rows))))
             (scope-keep scope groups))
```

- [ ] **Step 5: Load-check + tests** — `bb -e "(require 'nido.work)"`, `bb nido:test :only nido.work`. Expected: PASS. Also run `bb nido:test :only nido.ui` and `bb nido:test :only nido.tui` — surfaces consume `tab-bands`; fix any fixture expecting no `:winding-down` key.

- [ ] **Step 6: Commit** — `jj new`.

---

### Task 4: `work/bring-down!` + `dev/pending-winddown-keys`

**Files:**
- Modify: `src/nido/work.clj`
- Modify: `src/nido/session/dev.clj`
- Test: `test/nido/work_test.clj`, `test/nido/session/dev_test.clj`

**Interfaces:**
- Produces: `(work/bring-down! project ws-id)` → `{:downed [names]}` — downs every live session of the workstream via `lifecycle/down!`. Synchronous; surfaces wrap it in their own async machinery.
- Produces: `(dev/pending-winddown-keys)` → set of `"project/ws-id"` keys currently `:stopping` — the web injects this as `:winddown-pending` into `work/screen`.

- [ ] **Step 1: `jj desc -m "feat(work): bring-down! + winddown optimistic-state plumbing"`**

- [ ] **Step 2: Failing tests.** In `work_test.clj`:

```clojure
(deftest bring-down!-downs-only-live-sessions
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :p {:stage :in-progress :external-refs []})
            downed (atom [])]
        (session/create! :p (:id w) {:name "live1" :weight :light :autonomy nil})
        (session/create! :p (:id w) {:name "dead1" :weight :light :autonomy nil})
        (workstream/close! :p (:id w) :done)
        (with-redefs [work/live-session-names (constantly #{"live1"})
                      nido.session.lifecycle/down! (fn [n _] (swap! downed conj n))]
          (is (= {:downed ["live1"]} (work/bring-down! :p (:id w))))
          (is (= ["live1"] @downed)))))))
```

In `test/nido/session/dev_test.clj`:

```clojure
(deftest pending-winddown-keys-are-the-stopping-slash-keys
  (dev/set-app-state! "p/ws1" :stopping)
  (dev/set-app-state! "p/ws2" :resolving)
  (dev/set-app-state! "plain-instance" :stopping)
  (try
    (is (= #{"p/ws1"} (dev/pending-winddown-keys)))
    (finally
      (doseq [k ["p/ws1" "p/ws2" "plain-instance"]] (dev/clear-app-state! k)))))
```

- [ ] **Step 3: Run to verify FAIL** — `bb nido:test :only nido.work` / `bb nido:test :only nido.session.dev`.

- [ ] **Step 4: Implement.** `work.clj`:

```clojure
(defn bring-down!
  "Down every live session of a workstream — the winding-down band's one action.
   Synchronous and slow (lifecycle/down! per session); callers own async + UI
   optimism. Returns {:downed [names]}."
  [project ws-id]
  (let [live  (live-session-names project)
        names (->> (csession/list-sessions project ws-id)
                   (map :name)
                   (filterv live))]
    (doseq [n names]
      (lifecycle/down! n {:project (name project)}))
    {:downed names}))
```

`dev.clj` (next to `pending-resolve-keys`):

```clojure
(defn pending-winddown-keys
  "The \"<project>/<ws-id>\" keys with a :stopping state mid-flight — the web's
   winddown POST writes them; work/screen marks the matching winding-down rows
   :pending? so the 5s poll shows 'stopping…' instead of a re-clickable button."
  []
  (->> @app-states
       (filter (fn [[k v]] (and (string? k) (str/includes? k "/")
                                (= :stopping (:state v)))))
       (map key)
       set))
```

- [ ] **Step 5: Load-check + tests** — PASS expected.

- [ ] **Step 6: Commit** — `jj new`.

---

### Task 5: Daemon wiring — throttled `maybe-adopt!` on the tick

**Files:**
- Modify: `src/nido/coordinator/core.clj`
- Test: `test/nido/coordinator/core_test.clj`

**Interfaces:**
- Consumes: `work/adopt-orphans!` (Task 2), `registered-projects` (core-private).
- Produces: adoption runs on the first tick after (re)start, then at most every `:adopt-interval-ms` (5 min).

- [ ] **Step 1: `jj desc -m "feat(coordinator): adopt live orphan sessions on a throttled tick sweep"`**

- [ ] **Step 2: Failing test.** Follow the existing core_test style (check how `maybe-reclaim!`-adjacent behavior is tested there; if the tick helpers are tested via `with-redefs` on the swept fn, mirror it):

```clojure
(deftest maybe-adopt!-throttles-and-sweeps-all-projects
  (let [calls (atom [])]
    (with-redefs [nido.work/adopt-orphans! (fn [p] (swap! calls conj p) {:adopted [] :yielded []})
                  nido.project/list-projects (constantly {"brian" {:directory "/x"}})]
      ;; private fn + private atom: test through the var
      (#'nido.coordinator.core/reset-adopt-throttle!)
      (#'nido.coordinator.core/maybe-adopt! 1000000)
      (#'nido.coordinator.core/maybe-adopt! 1001000)   ; 1s later — throttled
      (is (= [:brian] @calls)))))
```

- [ ] **Step 3: Run to verify FAIL** — `bb nido:test :only nido.coordinator.core`.

- [ ] **Step 4: Implement in `core.clj`.** Add to `defaults` (next to `:reclaim-interval-ms`):

```clojure
;; Adoption sweep: enforce "every live session belongs to a workstream" by
;; adopting live orphans into scratch workstreams (work/adopt-orphans!). First
;; tick after (re)start sweeps immediately (throttle clock starts at 0).
:adopt-interval-ms (* 5 60 1000)   ; every 5 min
```

Add require `[nido.work :as work]` to the ns. Add next to `!last-reclaim-ms`:

```clojure
;; Last wall-clock ms an adoption sweep ran. Starts at 0 so the first tick after
;; a daemon (re)start sweeps immediately, then throttles to the interval.
(defonce ^:private !last-adopt-ms (atom 0))

(defn- reset-adopt-throttle! [] (reset! !last-adopt-ms 0))
```

Add next to `maybe-reclaim!`:

```clojure
(defn- maybe-adopt!
  "Throttled invariant sweep: at most once per :adopt-interval-ms, adopt live
   orphan sessions into scratch workstreams and yield claimed duplicates
   (work/adopt-orphans!). Never throws into the tick loop."
  [now-ms]
  (when (>= (- now-ms @!last-adopt-ms) (:adopt-interval-ms defaults))
    (reset! !last-adopt-ms now-ms)
    (doseq [project (registered-projects)]
      (try
        (let [{:keys [adopted yielded]} (work/adopt-orphans! project)]
          (when (seq adopted)
            (println (str "nido coordinator: adopted " (count adopted)
                          " orphan session(s) in " (name project) ": "
                          (str/join ", " adopted))))
          (when (seq yielded)
            (println (str "nido coordinator: yielded " (count yielded)
                          " scratch workstream(s) in " (name project)))))
        (catch Throwable t
          (binding [*err* *err*]
            (.println ^java.io.PrintWriter *err*
                      (str "WARN: adoption sweep threw for " (name project)
                           " — " (ex-message t)))))))))
```

In `tick!` (~line 703), call `(maybe-adopt! now-ms)` right after `(maybe-reclaim! now-ms)`.

**Cycle check:** `core.clj` requiring `nido.work` is new — verify no cycle: `bb -e "(require 'nido.coordinator.core)"`. `work` requires coordinator record namespaces (workstream/session/etc.) but NOT `coordinator.core`, so this is safe; if the load-check surfaces a cycle, stop and report rather than working around it.

- [ ] **Step 5: Load-check + tests** — `bb -e "(require 'nido.coordinator.core)"`, `bb nido:test :only nido.coordinator.core`. Expected: PASS.

- [ ] **Step 6: Commit** — `jj new`.

---

## Phase 2 — Web

### Task 6: Workstream-pane session rows absorb the machine facts

**Files:**
- Modify: `src/nido/work.clj` (`machine-facts`)
- Modify: `src/nido/ui/server.clj` (`derive-screen` attaches `:machine`; `ws-pane-fragment-response` threads it)
- Modify: `src/nido/ui/views.clj` (`workstream-pane` 3-arity; sessions table gains ports/mem columns; `session-dev-cell` gains restart)
- Test: `test/nido/work_test.clj`, `test/nido/ui/views_test.clj`

**Interfaces:**
- Produces: `(work/machine-facts project names)` → `{session-name {:live? :url :pg-port :nrepl-port :app-port :repl-rss :pg-rss :heap-max}}`.
- Produces: `views/workstream-pane` new arity `[ws session-dev-states machine-facts]`; the old 2-arity delegates with `{}` so no caller breaks mid-task.
- Consumes: the EXISTING POST `/workstreams/:project/:ws-id/sessions/:session/dev/:action` route (start/stop/restart via `dev/dev-action!`) — this already is the re-homed lifecycle route the spec asked for; no new route is added.

- [ ] **Step 1: `jj desc -m "feat(ui): workstream pane session rows carry machine facts (ports, RSS, heap, restart)"`**

- [ ] **Step 2: Failing tests.** `work_test.clj`:

```clojure
(deftest machine-facts-keyed-by-session-name
  (with-redefs [work/machine-rows
                (fn [_ _] [{:name "a" :live? true :entry {:url "u" :pg-port 5501 :app-port 3101}
                            :repl-rss 1024 :pg-rss 2048 :heap-max "2g"}
                           {:name "b" :live? false :entry nil}])
                nido.project/list-projects (constantly {"p" {:directory "/x"}})]
    (let [facts (work/machine-facts "p" ["a"])]
      (is (= ["a"] (keys facts)))
      (is (= {:live? true :url "u" :pg-port 5501 :nrepl-port nil :app-port 3101
              :repl-rss 1024 :pg-rss 2048 :heap-max "2g"}
             (get facts "a"))))))
```

`views_test.clj` (follow the file's existing render-and-grep style):

```clojure
(deftest workstream-pane-session-row-shows-machine-facts
  (let [html (views/workstream-pane
              {:project "p" :ws-id "w" :origin :scratch :stage :in-progress :label "L"
               :sessions [{:name "s1" :autonomy-level :interactive :parked? false :status :up}]}
              {"s1" {:state :running :url "http://localhost:3101"}}
              {"s1" {:pg-port 5501 :nrepl-port 7001 :app-port 3101
                     :repl-rss (* 512 1024 1024) :pg-rss (* 100 1024 1024) :heap-max "2g"}})]
    (is (str/includes? html "5501"))
    (is (str/includes? html "7001"))
    (is (str/includes? html "3101"))
    (is (str/includes? html "max 2g"))
    (is (str/includes? html "restart"))))
```

- [ ] **Step 3: Run to verify FAIL.**

- [ ] **Step 4: Implement.** `work.clj`:

```clojure
(defn machine-facts
  "Machine facts for `names` (sessions of `project`), keyed by session name.
   The workstream pane's per-session ports/RSS/heap column feed."
  [project names]
  (let [dir  (:directory (get (project/list-projects) (name project)))
        keep (set names)]
    (into {}
          (for [{:keys [name entry live? repl-rss pg-rss heap-max]}
                (machine-rows (clojure.core/name project) dir)
                :when (contains? keep name)]
            [name {:live? live? :url (:url entry)
                   :pg-port (:pg-port entry) :nrepl-port (:nrepl-port entry)
                   :app-port (:app-port entry)
                   :repl-rss repl-rss :pg-rss pg-rss :heap-max heap-max}]))))
```

`server.clj` — in `derive-screen`'s selection block, alongside `:dev-states`, add `:machine (work/machine-facts (:project sel) (map :name (:sessions ws)))`; in `ws-pane-fragment-response` compute `(work/machine-facts project (map :name (:sessions ws)))` and pass as third arg.

`views.clj` — `workstream-pane` gains a 3-arity (2-arity delegates with `{}`). The Sessions table header becomes `[:th "session"] [:th "axis"] [:th "status"] [:th "dev env"] [:th "ports"] [:th "mem"] [:th "brakes"]`; each row adds:

```clojure
[:td.mono (let [{:keys [pg-port nrepl-port app-port]} (get machine-facts name)]
            (str/join " · " (keep (fn [[l p]] (when p (str l " " p)))
                                  [["pg" pg-port] ["repl" nrepl-port] ["app" app-port]])))]
[:td.meta (let [{:keys [repl-rss pg-rss heap-max]} (get machine-facts name)]
            (list (when repl-rss (str "jvm " (process/human-bytes repl-rss) " "))
                  (when pg-rss (str "pg " (process/human-bytes pg-rss) " "))
                  (when heap-max (str "max " heap-max))))]
```

`session-dev-cell` `:running` arm adds a restart button after stop: `[:button.btn {"data-on:click" (act "restart")} "restart"]`. Update `workstreams-page` to pass `(:machine selection)` as the pane's third arg (and the dev-POST + findings-POST responses go through `ws-pane-fragment-response`, already updated).

- [ ] **Step 5: Load-check + tests** — `bb -e "(require 'nido.ui.views 'nido.ui.server 'nido.work)"`, `bb nido:test :only nido.ui` and `:only nido.work`. Expected: PASS.

- [ ] **Step 6: Commit** — `jj new`.

---

### Task 7: Web winding-down band + winddown route

**Files:**
- Modify: `src/nido/ui/views.clj` (`ws-fold-stages` gains `[:winding-down "WindingDown"]`; `workstreams-fragment` renders winding-down rows via a dedicated row fn)
- Modify: `src/nido/ui/server.clj` (screen data gains `:winddown-pending`; new POST route; RSS decoration)
- Test: `test/nido/ui/views_test.clj`, `test/nido/ui/server_test.clj`

**Interfaces:**
- Consumes: `work/bring-down!`, `dev/pending-winddown-keys`, `work/machine-facts` (RSS decoration).
- Produces: `POST /workstreams/:project/:ws-id/winddown` → sets `"project/ws-id"` `:stopping`, backgrounds `bring-down!`, responds with the workstreams fragment.

- [ ] **Step 1: `jj desc -m "feat(ui): winding-down band on the Active tab with one-click bring-down"`**

- [ ] **Step 2: Failing tests.** `views_test.clj`:

```clojure
(deftest workstreams-fragment-renders-winding-down-rows
  (let [screen {:scope "all" :tab :active :selection nil
                :groups [{:project "p"
                          :grouped {:winding-down
                                    [{:ws-id "w1" :origin :scratch :label "old-one"
                                      :outcome :done :sessions ["s1"] :rss-str "612 MB"}
                                     {:ws-id "w2" :origin :scratch :label "stopping-one"
                                      :outcome :dropped :sessions ["s2"] :pending? true}]}}]}
        html (views/workstreams-fragment screen)]
    (is (str/includes? html "old-one"))
    (is (str/includes? html "612 MB"))
    (is (str/includes? html "/workstreams/p/w1/winddown"))
    (is (str/includes? html "stopping…") "pending row shows progress, no button")
    (is (not (str/includes? html "/workstreams/p/w2/winddown")))))
```

`server_test.clj`:

```clojure
(deftest post-winddown-sets-stopping-and-responds-with-fragment
  (let [called (atom nil)]
    (with-redefs [nido.work/bring-down! (fn [p w] (reset! called [p w]) {:downed []})
                  nido.work/all-grouped (fn [] [])
                  nido.work/all-gates (fn [] [])
                  server/read-rail-daemon (fn [] {:state :up})]
      (let [resp (server/handle-request {:request-method :post
                                         :uri "/workstreams/p/w1/winddown"})]
        (is (= 200 (:status resp)))
        (is (str/includes? (get-in resp [:headers "Content-Type"]) "text/event-stream"))
        ;; the future runs async — poll until the pending key CLEARS (asserting
        ;; on @called alone races the clear-app-state! that follows it)
        (loop [n 40]
          (when (and (seq (dev/pending-winddown-keys)) (pos? n))
            (Thread/sleep 50) (recur (dec n))))
        (is (= ["p" "w1"] @called))
        (is (empty? (dev/pending-winddown-keys)) "cleared after bring-down returns")))))
```

- [ ] **Step 3: Run to verify FAIL.**

- [ ] **Step 4: Implement.**

`views.clj` — add `[:winding-down "WindingDown"]` to `ws-fold-stages` (fold persistence keeps working). Add:

```clojure
(defn- winddown-row
  "One winding-down row: closed workstream still holding live sessions. Muted;
   one action. A :pending? row shows 'stopping…' (no re-clickable button); the
   5s poll drops the row once its sessions are down."
  [{:keys [project ws-id origin label outcome sessions rss-str pending?]}]
  [:div.gate-card.winddown
   [:div.gate-top (origin-badge origin) [:span.lbl label]]
   [:div.gate-sub
    [:span project]
    [:span.meta (str "closed:" (name (or outcome :done)) " · "
                     (count sessions) " live session(s)"
                     (when rss-str (str " · " rss-str)))]
    (if pending?
      [:span.meta "stopping…"]
      [:button.btn.btn-danger
       {"data-on:click" (str "@post('/workstreams/" project "/" ws-id "/winddown')")}
       "Bring down"])]])
```

In `workstreams-fragment`'s section loop, render `winddown-row` instead of `ws-list-row` when `(:stage r)` / the section's `stage` is `:winding-down` (the section stage is available — branch on it: `(if (= :winding-down stage) (winddown-row r) (ws-list-row screen sel-id project r))`). Add a `.winddown { opacity: 0.65; }` rule to the shell CSS (descendant selectors only — hiccup2 escapes `>`).

`server.clj` — in `derive-screen`, pass `:winddown-pending (dev/pending-winddown-keys)` in the data map, and after `work/screen` returns, decorate RSS: for each group's `:winding-down` rows, `(assoc row :rss-str ...)` summing `:repl-rss`+`:pg-rss` from `(work/machine-facts (:project group) (:sessions row))` formatted via `process/human-bytes` (skip when zero/nil). Add the POST route to `handle-post`:

```clojure
;; POST /workstreams/:project/:ws-id/winddown — bring a closed workstream's
;; leftover sessions down. Optimistic :stopping keyed "project/ws-id" (same
;; key-space as gate-resolve!) marks the row pending until down! settles.
(and (= 4 (count segs)) (= "workstreams" (first segs)) (= "winddown" (nth segs 3)))
(let [project (nth segs 1) ws-id (nth segs 2) k (str project "/" ws-id)]
  (dev/set-app-state! k :stopping)
  (future
    (try (work/bring-down! project ws-id)
         (dev/clear-app-state! k)
         (catch Exception e
           (dev/set-app-state! k :failed (ex-message e)))))
  (workstreams-fragment-response (derive-screen (view-state/parse req))))
```

Note `handle-post` currently destructures only `{:keys [uri body]}` — widen to `[{:keys [uri body] :as req}]` so `view-state/parse` gets the full request.

- [ ] **Step 5: Load-check + tests** — `bb nido:test :only nido.ui`. Expected: PASS.

- [ ] **Step 6: Commit** — `jj new`.

---

### Task 8: Ops panel on the rail health dot

**Files:**
- Modify: `src/nido/coordinator/triggers.clj` (public `placeholder-keys`, moved from tui)
- Modify: `src/nido/tui.clj` (delegate to `triggers/placeholder-keys`)
- Modify: `src/nido/ui/views.clj` (`rail-health` becomes a toggle; `ops-panel-fragment`; shell declares `$opsOpen`)
- Modify: `src/nido/ui/server.clj` (`ops-context`, `GET /_fragment/ops`, `POST /ops/...` routes)
- Test: `test/nido/ui/views_test.clj`, `test/nido/ui/server_test.clj`, `test/nido/coordinator/triggers_test.clj`

**Interfaces:**
- Produces: `(triggers/placeholder-keys payload-template)` → ordered vector of keywords (verbatim move of the tui private fn).
- Produces: `(server/ops-context)` → `{:daemon :halt :breakers :triggers}` where `:breakers` is `breakers/tripped-triggers` and `:triggers` is `{project-kw [manual-trigger ...]}`.
- Produces: routes — `GET /_fragment/ops` (SSE patch `#ops-panel`), `POST /ops/halt`, `POST /ops/resume`, `POST /ops/breakers/:project/:trigger/clear`, `POST /ops/fire/:project/:trigger` (JSON body signals = placeholder values). All POSTs respond with the refreshed ops fragment + rail status.
- Produces: `(views/ops-panel-fragment ops-ctx)` → `#ops-panel` HTML string.

- [ ] **Step 1: `jj desc -m "feat(ui): ops panel — halt/breakers/fire as ambient chrome behind the rail health dot"`**

- [ ] **Step 2: Failing tests.**

`triggers_test.clj`:

```clojure
(deftest placeholder-keys-extracts-ordered-distinct-event-keys
  (is (= [:url :note]
         (triggers/placeholder-keys "{\"u\":\"{{event/url}}\",\"n\":\"{{event/note}}\",\"u2\":\"{{event/url}}\"}")))
  (is (= [] (triggers/placeholder-keys "{}"))))
```

`views_test.clj`:

```clojure
(deftest ops-panel-renders-daemon-halt-breakers-and-fire
  (let [html (views/ops-panel-fragment
              {:daemon   {:state :breaker :heartbeat-at "2026-07-21T10:00:00Z"}
               :halt     nil
               :breakers [{:project :brian :trigger :triage-new
                           :info {:consecutive-failures 3}}]
               :triggers {:brian [{:name :one-off :payload "{}"}
                                  {:name :with-args :payload "{\"u\":\"{{event/url}}\"}"}]}})]
    (is (str/includes? html "id=\"ops-panel\""))
    (is (str/includes? html "/ops/halt"))
    (is (str/includes? html "/ops/breakers/brian/triage-new/clear"))
    (is (str/includes? html "/ops/fire/brian/one-off"))
    (is (str/includes? html "/ops/fire/brian/with-args"))))

(deftest rail-health-toggles-the-ops-panel
  (let [html (str (hiccup2.core/html (#'views/rail-health {:state :up})))]
    (is (str/includes? html "$opsOpen"))))
```

(If `rail-health` is private and the test style elsewhere greps whole pages instead, assert via `views/needs-page` output containing `$opsOpen` and `id="ops-panel"` — follow the file's existing convention.)

`server_test.clj`:

```clojure
(deftest post-ops-halt-writes-halt-and-responds-with-ops-fragment
  (let [halted (atom false)]
    (with-redefs [nido.coordinator.halt/halt! (fn [_] (reset! halted true))
                  nido.coordinator.halt/read-halt-info (fn [] nil)
                  nido.coordinator.breakers/tripped-triggers (fn [] [])
                  nido.coordinator.triggers/load-for-project (fn [_] [])
                  server/read-rail-daemon (fn [] {:state :up})
                  nido.work/all-gates (fn [] [])]
      (let [resp (server/handle-request {:request-method :post :uri "/ops/halt"})]
        (is @halted)
        (is (str/includes? (:body resp) "ops-panel"))))))
```

- [ ] **Step 3: Run to verify FAIL.**

- [ ] **Step 4: Implement.**

`triggers.clj` — add the fn verbatim from tui (with its docstring); `tui.clj` deletes its private copy and calls `triggers/placeholder-keys` (tui already requires triggers).

`views.clj`:

- `rail-health` becomes a toggle + panel mount:

```clojure
(defn- rail-health [{:keys [state]}]
  (let [s (name (or state :down))]
    [:div {:id "rail-health" :class "rail-health"}
     [:button.rail-health-btn {"data-on:click" "$opsOpen = !$opsOpen"
                               :title "ops panel"}
      [:span {:class (str "dot dot-" s)}] s]]))
```

- `shell`'s `[:body ...]` gains `{:data-signals__ifmissing "{opsOpen: false}"}` and, after the rail, an ops mount that lazy-loads + polls only while open:

```clojure
[:div.ops-wrap {:data-show "$opsOpen"
                :data-on-interval__duration.5s "$opsOpen && @get('/_fragment/ops')"}
 [:div {:id "ops-panel"} [:p.meta "…"]]]
```

- `ops-panel-fragment`:

```clojure
(defn ops-panel-fragment
  "The ambient ops chrome: daemon state, halt/resume, open breakers with
   per-trigger clear, and a fire form per manual trigger (placeholder-less →
   one click; placeholder-carrying → one input per {{event/*}} key, signals
   fire_<trigger>_<key>). No route of its own — lives behind the rail dot."
  [{:keys [daemon halt breakers triggers]}]
  (str
   (h/html
    [:div {:id "ops-panel" :class "ops-panel"}
     [:div.card
      [:span {:class (str "dot dot-" (name (or (:state daemon) :down)))}]
      " daemon " (name (or (:state daemon) :down))
      (when-let [hb (:heartbeat-at daemon)] [:span.meta " · heartbeat " hb])]
     [:div.card
      (if halt
        (list [:span "⏸ halted by " (name (:source halt))
               (when (:note halt) (str " — " (:note halt)))]
              [:button.btn.btn-primary {"data-on:click" "@post('/ops/resume')"} "Resume"])
        (list [:span "running"]
              [:button.btn.btn-danger {"data-on:click" "@post('/ops/halt')"} "Halt"]))]
     [:div.card
      [:strong "Breakers"]
      (if (seq breakers)
        (for [{:keys [project trigger]} breakers]
          [:div.actions
           [:span.mono (str (name project) "/" (name trigger))]
           [:button.btn {"data-on:click"
                         (str "@post('/ops/breakers/" (name project) "/" (name trigger) "/clear')")}
            "clear"]])
        [:span.meta "none tripped"])]
     [:div.card
      [:strong "Fire trigger"]
      (for [[project ts] triggers
            {:keys [name payload] :as _t} ts]
        (let [ks (triggers/placeholder-keys (or payload "{}"))]
          [:div.fire-row
           [:span.mono (str (clojure.core/name project) "/" (clojure.core/name name))]
           (for [k ks]
             [:input {"data-bind" (str "fire_" (clojure.core/name name) "_" (clojure.core/name k))
                      :placeholder (clojure.core/name k)}])
           [:button.btn {"data-on:click"
                         (str "@post('/ops/fire/" (clojure.core/name project)
                              "/" (clojure.core/name name) "')")}
            "fire"]]))]])))
```

(`views.clj` adds `[nido.coordinator.triggers :as triggers]` to its requires.)

`server.clj`:

```clojure
(defn- ops-context []
  {:daemon   (read-rail-daemon)
   :halt     (halt/read-halt-info)
   :breakers (breakers/tripped-triggers)
   :triggers (into {}
                   (for [[pname _] (project/list-projects)]
                     [(keyword pname)
                      (->> (triggers/load-for-project (keyword pname))
                           (filter #(= :manual (-> % :source :type)))
                           vec)]))})

(defn- ops-fragment-response []
  (sse-response
   (sse-fragment
    (str (views/ops-panel-fragment (ops-context))
         (views/rail-status-fragment {:needs-count (count (work/all-gates))
                                      :daemon (read-rail-daemon)})))))
```

New requires: `[nido.coordinator.halt :as halt] [nido.coordinator.breakers :as breakers] [nido.coordinator.triggers :as triggers] [nido.coordinator.queue :as queue]`. Routes — `GET` case gains `["_fragment" "ops"] (ops-fragment-response)`; `handle-post` gains one arm:

```clojure
;; POST /ops/... — ambient ops levers (halt/resume, breaker clear, fire).
;; Every lever responds with the refreshed ops fragment + rail status.
(= "ops" (first segs))
(do
  (cond
    (= ["ops" "halt"] segs)   (halt/halt! {:source :user :note "from dashboard"})
    (= ["ops" "resume"] segs) (halt/resume!)
    ;; /ops/breakers/:project/:trigger/clear
    (and (= 5 (count segs)) (= "breakers" (second segs)) (= "clear" (nth segs 4)))
    (breakers/enable! (keyword (nth segs 2)) (keyword (nth segs 3)))
    ;; /ops/fire/:project/:trigger — placeholder values ride the JSON signal body
    (and (= 4 (count segs)) (= "fire" (second segs)))
    (let [project (keyword (nth segs 2))
          tname   (keyword (nth segs 3))
          trig    (->> (triggers/load-for-project project)
                       (filter #(= tname (:name %))) first)
          ks      (triggers/placeholder-keys (or (:payload trig) "{}"))
          body*   (parse-json-body body)
          payload (into {} (for [k ks]
                             [k (str (get body* (keyword (fire-signal tname k)) ""))]))]
      (queue/enqueue! {:target {:project project :trigger tname} :payload payload}))
    :else nil)
  (ops-fragment-response))
```

**Signal-name hygiene:** Datastar signal names must be JS identifiers, and trigger/placeholder names carry hyphens (`:triage-new`). Define the sanitizer ONCE in `views.clj` and re-use it from the server (public fn):

```clojure
(defn fire-signal
  "Signal name for one fire-form input: JS-identifier-safe (hyphens → underscores).
   The /ops/fire route reads the SAME name back out of the signal body — keep the
   two sides on this one fn."
  [trigger-name k]
  (str "fire_" (str/replace (name trigger-name) "-" "_")
       "_" (str/replace (name k) "-" "_")))
```

In `ops-panel-fragment`, each input's `"data-bind"` becomes `(fire-signal name k)` (with `name` here being the trigger's `:name` from the destructuring); `server.clj` calls `views/fire-signal` in the payload rebuild above. Extend the `triggers_test.clj`-adjacent views test with a hyphenated trigger (`:with-args` → also add `{:name :two-part-name :payload "{\"u\":\"{{event/page-url}}\"}"}` and assert the rendered HTML contains `fire_two_part_name_page_url`).

Add a `.ops-wrap` CSS block to the shell css: fixed position bottom-left above the rail, max-width ~360px, background var matching `.card`, scrollable (`overflow-y: auto; max-height: 70vh`). Descendant selectors only (hiccup2 escapes `>`).

- [ ] **Step 5: Load-check + tests** — `bb -e "(require 'nido.coordinator.triggers 'nido.ui.views 'nido.ui.server 'nido.tui)"`, then `bb nido:test :only nido.ui`, `:only nido.coordinator.triggers`, `:only nido.tui`. Expected: PASS.

- [ ] **Step 6: Commit** — `jj new`.

---

### Task 9: Delete the web `/system` surface

**Files:**
- Modify: `src/nido/ui/server.clj` (delete `session-rows`, `all-session-rows`, `instance-id-for`, `run-action!`, `system-fragment-response`, `rail-context`, `scope-keep-rows`, the `GET /system` + `GET /_fragment/system` + `POST /system/...` arms; `GET /system` becomes a 302)
- Modify: `src/nido/ui/views.clj` (delete `system-row`, `system-fragment`, `system-page`; rail loses the System link and `:system` from `surface-path`)
- Test: `test/nido/ui/server_test.clj`, `test/nido/ui/views_test.clj`

**Interfaces:**
- Consumes: nothing new. `work/machine-rows`/`all-machine-rows` (Task 1) are the surviving scan.
- Produces: `GET /system` → `{:status 302 :headers {"Location" "/workstreams"}}`.

- [ ] **Step 1: `jj desc -m "feat(ui)!: dissolve the /system surface — redirect to /workstreams"`**

- [ ] **Step 2: Rewrite the affected tests FIRST:**
- Delete: `all-session-rows-aggregates-and-sorts-live-first`, `all-session-rows-skips-unreadable-projects` (superseded by Task 1's work tests), `system-route-renders-on-shell`, `system-fragment-route-is-sse-and-patches-rail`, `post-system-lifecycle-renamed-path`, and any `views` system-fragment tests.
- In the two loop-over-surfaces tests (`server_test.clj:181` and `:248`), drop `"/system"` from the `doseq` uri vectors.
- Add:

```clojure
(deftest system-redirects-to-workstreams
  (let [resp (server/handle-request {:request-method :get :uri "/system"})]
    (is (= 302 (:status resp)))
    (is (= "/workstreams" (get-in resp [:headers "Location"])))))
```

- [ ] **Step 3: Run to verify FAIL** — the redirect test fails (old page renders 200).

- [ ] **Step 4: Implement the deletions.** In `server.clj`: `["system"]` arm → the 302 map; remove the `["_fragment" "system"]` arm, the `POST /system` cond arm, and the now-unreferenced fns listed above. `rail-ctx` stays (needs/workstreams). In `views.clj`: delete the three system fns; in `rail`, remove `(dest :system "/system" "System")` and the `:system` entry from `surface-path`. Grep to confirm nothing dangles: `grep -rn "system-fragment\|system-page\|system-row\|rail-context\|all-session-rows" src/ test/` → only work-side `all-machine-rows` remains.

- [ ] **Step 5: Load-check + full UI tests** — `bb -e "(require 'nido.ui.server 'nido.ui.views)"`, `bb nido:test :only nido.ui`. Expected: PASS.

- [ ] **Step 6: Manual smoke (optional but recommended):** `bb nido:ui :port 8899` → check `/` and `/workstreams` render, `/system` redirects, health dot opens the ops panel, a workstream pane shows ports/mem. Ctrl-C the server.

- [ ] **Step 7: Commit** — `jj new`.

---

## Phase 3 — TUI

### Task 10: Detail screen absorbs session plumbing (u/d/x/w)

**Files:**
- Modify: `src/nido/tui.clj` (`update-workstream` gains keys; footer text)
- Test: `test/nido/tui_test.clj`

**Interfaces:**
- Consumes: `with-selected-session` (screen-agnostic — reads `selected-data :name` + `(:project state)`), `start-session-up/down`, `open-confirm-destroy`, `enter-session`.
- Produces: on the `:workstream` screen — `u` up, `d` down, `x` destroy-with-confirm, `w` enter worktree, for the highlighted session row. Non-session rows (ledger/report/header) no-op with the existing `"(no session selected)"` status.

- [ ] **Step 1: `jj desc -m "feat(tui): session plumbing (u/d/x/w) on the workstream detail screen"`**

- [ ] **Step 2: Failing test** (follow `tui_test.clj`'s update-fn style — build a state on the `:workstream` screen whose list has a session row selected, feed a key msg through the update fn var, assert the effect):

```clojure
(deftest detail-u-starts-the-selected-session
  (let [started (atom nil)]
    (with-redefs [nido.session.lifecycle/up! (fn [n opts] (reset! started [n (:project opts)]))
                  nido.coordinator.scratch/birth! (fn [_ _] nil)]
      (let [state (#'tui/rebuild-list {:screen :workstream :project "p" :ws-id "w"}
                                      [{:title "s" :description ""
                                        :data {:name "sess1" :autonomy-level :interactive}}])
            [state' _] (#'tui/update-fn state {:type :key :key "u"})]
        ;; the in-app action machinery runs async — assert the busy state armed
        (is (= :up (get-in state' [:busy :verb])))
        (is (= "sess1" (get-in state' [:busy :subject])))))))

(deftest detail-footer-lists-the-plumbing-verbs
  (let [f (#'tui/footer {:screen :workstream})]
    (doseq [verb ["[u]p" "[d]own" "[x] destroy" "[w]orktree"]]
      (is (str/includes? f verb)))))
```

(Adapt the key-msg construction to whatever `msg/key-match?` expects in the existing tests — copy the msg shape from `board-footer-has-work-verbs` / `board-open-routes-through-open-target`.)

- [ ] **Step 3: Run to verify FAIL** — `bb nido:test :only nido.tui`.

- [ ] **Step 4: Implement.** In `update-workstream`, add before the `:else`:

```clojure
(msg/key-match? msg "w")
(if-let [sname (some-> (selected-data state) :name)]
  (enter-session state (:project state) sname :worktree)
  [state nil])
(msg/key-match? msg "u") (with-selected-session state start-session-up)
(msg/key-match? msg "d") (with-selected-session state start-session-down)
(msg/key-match? msg "x") (with-selected-session state (fn [s p sn] (open-confirm-destroy s p sn)))
```

Footer `:workstream` line becomes:
`"[↵] open in chat  [w]orktree  [a] apply  [r] reply  [u]p  [d]own  [x] destroy  [S] dev-start  [X] dev-stop  [R] dev-restart  [esc] back  [q]uit"`

Key-collision check (documented, not code): detail already binds `a r S X R ↵ o esc q`; `u d x w` are free. `d`/`x` here act on the SESSION (down/destroy), unlike the board where they act on the workstream (done/dismiss) — the footer strings make that explicit per screen.

- [ ] **Step 5: Load-check + tests** — `bb -e "(require 'nido.tui)"`, `bb nido:test :only nido.tui`. Expected: PASS.

- [ ] **Step 6: Commit** — `jj new`.

---

### Task 11: TUI winding-down band + contextual bring-down

**Files:**
- Modify: `src/nido/tui.clj` (`band-rows` takes an optional row-fn; `board-rows` appends the band; `d` on a winding-down row = bring down)
- Test: `test/nido/tui_test.clj`

**Interfaces:**
- Consumes: `work/grouped`'s `:winding-down` key (Task 3), `work/bring-down!` (Task 4), the in-app action machinery (`with-spinner`, `captured-cmd`).
- Produces: board band "Winding down" (collapsed by default — add `:winding-down` to `default-collapsed-bands`? NO — keep it expanded: it exists to be seen. Leave `default-collapsed-bands` unchanged); `d` on one of its rows arms a `:bring-down` busy action.

- [ ] **Step 1: `jj desc -m "feat(tui): winding-down band with d = bring down"`**

- [ ] **Step 2: Failing tests:**

```clojure
(deftest board-rows-include-winding-down-band
  (with-redefs [work/grouped (fn [_ _] {:incoming [] :in-progress [] :shipping []
                                        :triage {:in-flight [] :queued []}
                                        :winding-down [{:ws-id "w" :origin :scratch
                                                        :label "leftover" :outcome :done
                                                        :sessions ["s1"] :stage :winding-down}]})
                work/live-session-names (constantly #{"s1"})]
    (let [titles (map :title (#'tui/board-rows "p" :all #{} {}))]
      (is (some #(str/includes? % "Winding down") titles))
      (is (some #(str/includes? % "leftover") titles)))))

(deftest board-d-on-winding-down-row-arms-bring-down
  (let [state (#'tui/rebuild-list {:screen :board :project "p" :origin :all}
                                  [{:title "x" :description ""
                                    :data {:ws-id "w" :stage :winding-down :label "leftover"
                                           :sessions ["s1"]}}])
        [state' _] (#'tui/update-fn state {:type :key :key "d"})]
    (is (= :bring-down (get-in state' [:busy :verb])))))
```

(Again: copy the exact key-msg shape from existing tui tests.)

- [ ] **Step 3: Run to verify FAIL.**

- [ ] **Step 4: Implement.**

- `band-rows` gains an optional row-fn arg: `([band-key label rows collapsed?] (band-rows band-key label rows collapsed? badged-item-row))` with the 5-arity mapping `row-fn`.
- Add:

```clojure
(defn- winddown-item-row
  "One winding-down row: closed workstream still holding live sessions."
  [r]
  {:title (str (origin-badge (:origin r)) "  " (:label r)
               "  [closed:" (name (or (:outcome r) :done))
               " · " (count (:sessions r)) " live]")
   :description "d brings its sessions down"
   :data r})
```

- In `board-rows`, append after the `:incoming` band: `(band-rows :winding-down "Winding down" (keep (:winding-down g)) (contains? collapsed :winding-down) winddown-item-row)` — note `keep` here is the local origin/facet filter fn; winding-down rows carry `:origin` so origin filtering composes; they carry no `:facets`, and `facet-match?` treats an ACTIVE facet selection as non-matching for facet-less rows — acceptable (the band hides only while you're actively slicing by facet).
- In `update-board`, replace the `d` arm:

```clojure
(msg/key-match? msg "d")
(let [sel (selected-workstream state)]
  (if (= :winding-down (:stage sel))
    (with-spinner state :bring-down (:label sel)
      (captured-cmd
       (fn [] (work/bring-down! (:project state) (:ws-id sel)))
       (fn [{:keys [ok? error output]}]
         (if ok?
           {:type ::action-done :verb :bring-down :subject (:label sel)}
           {:type ::action-failed :verb :bring-down :subject (:label sel)
            :error error :output output}))))
    (done-selected state)))
```

- Add `:bring-down` to `action-defs` (no `:fn` — invoked directly, like `:rehydrate`): `{:gerund "Bringing down" :past "Brought down" :failed "bring down"}`.
- Board footer: change `[d]one` to `[d]one/bring-down`.

- [ ] **Step 5: Load-check + tests** — `bb nido:test :only nido.tui`. Expected: PASS.

- [ ] **Step 6: Commit** — `jj new`.

---

### Task 12: TUI ops overlay; delete the `:system` screen

**Files:**
- Modify: `src/nido/tui.clj`
- Test: `test/nido/tui_test.clj`

**Interfaces:**
- Produces: `s` on the board opens modal `:ops` (status-bar + halt info + tripped breakers, read-only) whose keys route to the EXISTING modal openers: `h` → `open-halt-confirm`, `c` → `open-clear-breaker-picker`, `f` → `open-fire-trigger`, `p` → `open-pickup-input`, `esc` closes.
- Deletes: `enter-system`, `update-system`, TUI `session-rows`, `open-session-info`, `update-session-info`, `session-info-body`, `session-link-entries`, `render-link-rows`, `link-indent`, `info-row` (if unused elsewhere), the `:system` arms in `current-rows`/`update-fn`/`header`/`footer`/`main-list-height`/`view`, and the `with-selected-session`-based `e`-key path (detail `↵` covers entering). Session links remain reachable via `bb nido:session:link:*`; per-session port/RSS detail lives in the web pane now.

- [ ] **Step 1: `jj desc -m "feat(tui)!: dissolve the system screen — s opens the ops overlay"`**

- [ ] **Step 2: Failing tests:**

```clojure
(deftest s-opens-the-ops-overlay
  (let [state (#'tui/rebuild-list {:screen :board :project "p" :origin :all} [])
        [state' _] (#'tui/update-fn state {:type :key :key "s"})]
    (is (= :ops (:modal state')))))

(deftest ops-overlay-routes-to-the-levers
  (with-redefs [nido.coordinator.halt/halted? (fn [] false)]
    (let [state {:screen :board :project "p" :origin :all :modal :ops}
          [s-h _] (#'tui/update-fn state {:type :key :key "h"})
          [s-esc _] (#'tui/update-fn state {:type :key :key "escape"})]
      (is (= :halt-confirm (:modal s-h)))
      (is (nil? (:modal s-esc))))))

(deftest system-screen-is-gone
  (is (nil? (resolve 'nido.tui/enter-system)))
  (is (nil? (resolve 'nido.tui/update-system))))
```

Also update any existing tests that reference the `:system` screen or its footer line (`board-footer-has-work-verbs` asserts `[s]ystem`? — check and update the expected strings to `[s] ops`).

- [ ] **Step 3: Run to verify FAIL.**

- [ ] **Step 4: Implement.**

- `update-board`'s `s` arm becomes `[(assoc state :modal :ops :status nil) nil]`.
- New modal handler + wiring in `update-fn` (next to the other modal cases):

```clojure
(defn- update-ops [state msg]
  (cond
    (msg/key-match? msg "escape") [(close-modal state) nil]
    (msg/key-match? msg "h") (open-halt-confirm (close-modal state))
    (msg/key-match? msg "c") (open-clear-breaker-picker (close-modal state))
    (msg/key-match? msg "f") (open-fire-trigger (close-modal state))
    (msg/key-match? msg "p") (open-pickup-input (close-modal state))
    :else [state nil]))
```

- `modal-body` `:ops` arm:

```clojure
:ops
(str (status-bar) "\n\n"
     (when-let [h (halt/read-halt-info)]
       (str "halted by " (name (:source h))
            (when (:note h) (str " — " (:note h))) "\n\n"))
     (let [tripped (breakers/tripped-triggers)]
       (if (seq tripped)
         (str "breakers:\n"
              (str/join "\n" (for [{:keys [project trigger info]} tripped]
                               (str "  " (name project) "/" (name trigger)
                                    "  —  " (runs-view/breaker-reason info)))))
         "no breakers tripped")))
```

- `header` gains `:ops` → `(str "nido — " (:project state) " · ops")`; `footer` gains `:ops` → `"[h]alt  [c]lear breaker  [f]ire  [p]ickup  [esc] back"`.
- Board footer: `[s]ystem` → `[s] ops`; the empty-board hint row (`board-rows`) `"[n] new · [s] system · [f via system] fire"` → `"[n] new · [s] ops · [f via ops] fire"`.
- Delete the `:system` screen: the fns and case-arms listed under Interfaces. `with-selected-session` STAYS (Task 10 uses it). After deleting, grep: `grep -n ":system\|session-rows\|session-info\|enter-system\|update-system" src/nido/tui.clj` — expect only comments you've rewritten and no live references; also delete the ns-doc's `:system` screen line and the exit-action comment block if it references it.
- `main-list-height`: remove the `(if (= :system (:screen state)) 1 0)` chrome line.

- [ ] **Step 5: Load-check + full TUI tests** — `bb -e "(require 'nido.tui)"`, `bb nido:test :only nido.tui`. Expected: PASS.

- [ ] **Step 6: Manual smoke:** run `bb nido:tui`, drill into a project board: `s` opens the ops overlay, `h`/`c`/`f`/`p` route, `esc` backs out; drill `i` into a workstream, check the footer verbs; `q` out.

- [ ] **Step 7: Commit** — `jj new`.

---

### Task 13: Docs, restart, end-to-end verification

**Files:**
- Modify: `CLAUDE.md` (project root)

- [ ] **Step 1: `jj desc -m "docs: dissolve the workstream/system split in CLAUDE.md"`**

- [ ] **Step 2: Update `CLAUDE.md`:**
- In **Web dashboard** paragraph: remove "The old flat live-sessions board … moved to `/system`." Replace with: "The old flat live-sessions board is gone: every live session belongs to a workstream (the daemon adopts orphans into scratch workstreams every 5 minutes), machine facts (ports, RSS, lifecycle) live on each workstream's session rows, closed-but-still-running workstreams surface in the Active tab's winding-down band, and global ops levers (halt, breakers, fire-trigger) sit behind the rail's health dot. `/system` redirects to `/workstreams`."
- In the **TUI** bullets (Coordination-layer section: "`s` opens the system surface"): replace with "`s` opens the ops overlay (halt / clear breaker / fire / pickup)". Update the two "system surface" references under **Safety brakes** and **session:enter** accordingly (the TUI board's detail screen now carries session plumbing: `u`/`d`/`x`/`w`).
- Add one line to the session-lifecycle section: "Debug escape hatch: `bb nido:session:status` / `bb nido:session:list` stay scan-based and model-independent — they work even when the workstream model is wedged."

- [ ] **Step 3: Full test suite** — `bb nido:test`. Expected: PASS (fix anything that snuck through per-namespace runs).

- [ ] **Step 4: Restart the daemon** so the adopter + any coordinator changes load: `bb nido:coordinator:restart`, then `bb nido:coordinator:logs` — expect a possible `adopted N orphan session(s)` line on the first tick and no WARNs from the sweep.

- [ ] **Step 5: End-to-end manual pass (spec §7):** with the daemon up, dashboard at `localhost:8800`: (a) a manually-started one-off session appears on the board within ~5 min (or immediately after restart); (b) close a test workstream that has a live session → it appears in the winding-down band; (c) Bring down → row shows stopping… → disappears; (d) health dot → ops panel → halt → dot goes red → resume; (e) `/system` redirects.

- [ ] **Step 6: Commit** — `jj new`. Confirm `jj log` shows one commit per task and `jj st` shows only the uncommitted plan/spec docs.
