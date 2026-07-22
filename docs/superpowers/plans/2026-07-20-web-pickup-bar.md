# Web Pickup Bar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a paste box at the top of the `/workstreams` dashboard that takes a Notion URL / page-id / `BR-####`, resolves it, enqueues a `:plan-bug` envelope, and reports inline whether it is continuing an existing workstream ledger or starting a fresh one.

**Architecture:** Reuse the existing `nido.coordinator.pickup/pickup!` orchestration (resolve → enqueue `:plan-bug`; the daemon find-or-creates the workstream by Notion ref). Add three thin layers: (1) `pickup!` reports continuing-vs-new via `ws/find-by-ref-id`; (2) two view fns render the bar and its result fragment; (3) a `POST /workstreams/pickup/:project` route wires them together and patches `#pickup-result` over SSE.

**Tech Stack:** Babashka/Clojure, hiccup2 (`h/html`), Datastar SSE fragments (`sse-fragment` / `sse-response`), httpkit, `clojure.test`.

## Global Constraints

- Planning artifacts (this plan, the spec) stay **uncommitted** — never bundle them into a code commit (user CLAUDE.md).
- This repo is a **jj workspace** — use `jj` for VCS, never bare `git` (bare `git` binds to the parent source repo and lies). Commit steps below use `jj`.
- Run a load check (`bb -e "(require 'the.ns)"`) after any source edit before committing.
- Project resolution default: scope `"all"` → `"brian"` (the only Notion-owning project); a concrete scope → that project. This fallback lives in exactly one place (the view).
- Reuse existing seams: `parse-json-body`, `sse-fragment`, `sse-response`, `read-rail-daemon`, `client/keychain-token`. Do not add new ones.
- Error keywords returned by `resolve-ref` are the closed set: `:no-token`, `:not-found`, `:not-a-ticket`, `:unrecognized-input`, `:notion-error`, `:auth`. Any unmapped keyword renders the generic fallback.

---

### Task 1: `pickup!` reports continuing vs. new

**Files:**
- Modify: `src/nido/coordinator/pickup.clj` (the `pickup!` fn, ~lines 69-83; add `nido.coordinator.workstream` to the ns require)
- Test: `test/nido/coordinator/pickup_test.clj`

**Interfaces:**
- Consumes: `nido.coordinator.workstream/find-by-ref-id` — `(find-by-ref-id project external-id)` → workstream map (has `:id`) or `nil`.
- Produces: `pickup!` success return now also carries `:continuing? <bool>` and `:ws-id <existing-ws-id-or-nil>`. Unchanged on the error branch (`{:decision :unresolved :error <kw>}`).

- [ ] **Step 1: Write the failing tests**

Add to `test/nido/coordinator/pickup_test.clj`. Add `[nido.coordinator.workstream :as ws]` to the ns require, then:

```clojure
(deftest pickup-reports-continuing-when-workstream-exists
  (with-redefs [pickup/resolve-ref (fn [_ _ _] {:id "BR-1" :page-id "pg-1" :url "u" :title "t"})
                ws/find-by-ref-id  (fn [_project external-id]
                                     (is (= "BR-1" external-id))
                                     {:id "ws-42"})
                queue/enqueue!     (fn [_env] "/q/x.edn")]
    (let [r (pickup/pickup! :brian "https://notion.so/x" "tok")]
      (is (= :driving (:decision r)))
      (is (true? (:continuing? r)))
      (is (= "ws-42" (:ws-id r))))))

(deftest pickup-reports-starting-fresh-when-no-workstream
  (with-redefs [pickup/resolve-ref (fn [_ _ _] {:id "BR-1" :page-id "pg-1" :url "u" :title "t"})
                ws/find-by-ref-id  (fn [_ _] nil)
                queue/enqueue!     (fn [_env] "/q/x.edn")]
    (let [r (pickup/pickup! :brian "https://notion.so/x" "tok")]
      (is (= :driving (:decision r)))
      (is (false? (:continuing? r)))
      (is (nil? (:ws-id r))))))
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `bb nido:test :only nido.coordinator.pickup-test`
Expected: FAIL — the two new tests fail (no `:continuing?` key; `false?`/`nil?` assertions fail because the keys are absent → `(true? nil)` is false, etc.), existing tests still pass.

- [ ] **Step 3: Implement the reporting in `pickup!`**

In `src/nido/coordinator/pickup.clj`, add to the ns `:require`:

```clojure
   [nido.coordinator.workstream :as ws]
```

Replace the `pickup!` fn body's success branch so it looks up an existing workstream before returning:

```clojure
(defn pickup!
  "Resolve `input` and, on success, enqueue the :plan-bug envelope to drive the
   ticket (the daemon find-or-creates the workstream by its Notion ref → the shared
   Phase-B ledger). Reports whether an existing workstream ledger will be continued
   (`:continuing?` + `:ws-id`) so the caller can say which path the daemon will take.
   Returns {:decision :driving :ref … :continuing? … :ws-id … :queued …} or
   {:decision :unresolved :error …}."
  [project input token]
  (let [r (resolve-ref project input token)]
    (if (:error r)
      {:decision :unresolved :error (:error r)}
      (let [existing (ws/find-by-ref-id project (:id r))]
        {:decision    :driving
         :ref         r
         :continuing? (some? existing)
         :ws-id       (:id existing)
         :queued      (queue/enqueue!
                        {:target  {:project (keyword (name project)) :trigger :plan-bug}
                         :payload {:id (:id r) :notion-page-id (:page-id r)
                                   :url (:url r) :title (:title r)}})}))))
```

- [ ] **Step 4: Load-check + run the tests to verify they pass**

Run: `bb -e "(require 'nido.coordinator.pickup)"` — Expected: no output / no error.
Run: `bb nido:test :only nido.coordinator.pickup-test`
Expected: PASS — all pickup tests, including `pickup-enqueues-plan-bug-for-a-resolved-ref` (which redefs `resolve-ref` but not `find-by-ref-id`; `find-by-ref-id` scans an empty project state and returns nil, so `:continuing?` is false and enqueue still fires — assertions there are unaffected).

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(pickup): report continuing-vs-new so the caller can say which path drives"
```

---

### Task 2: Pickup bar + result fragment views

**Files:**
- Modify: `src/nido/ui/views.clj` (add two public fns near the other `/workstreams` view fns, e.g. just above `workstreams-page` ~line 691)
- Test: `test/nido/ui/views_pickup_test.clj` (create)

**Interfaces:**
- Consumes: `hiccup2.core` as `h` (already required in `views.clj`).
- Produces:
  - `(views/pickup-bar project)` — `project` is a string; returns a hiccup vector (the bar chrome + an empty `#pickup-result`). Rendered inside `workstreams-page` via `h/raw` or as a nested vector.
  - `(views/pickup-result-fragment result opts)` — `result` is the map returned by `pickup!` (either `:driving` or `:unresolved`); `opts` is `{:project <string> :daemon-ready? <bool>}`. Returns an HTML **string** whose root element is `<div id="pickup-result">`, suitable for `sse-fragment`.

- [ ] **Step 1: Write the failing tests**

Create `test/nido/ui/views_pickup_test.clj`:

```clojure
(ns nido.ui.views-pickup-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [hiccup2.core :as h]
   [nido.ui.views :as views]))

(deftest pickup-bar-renders-input-and-post-for-project
  (let [html (str (h/html (views/pickup-bar "brian")))]
    (is (str/includes? html "data-bind=\"pickup\""))
    (is (str/includes? html "/workstreams/pickup/brian"))
    (is (str/includes? html "id=\"pickup-result\""))))

(deftest pickup-result-continuing-renders-link
  (let [html (views/pickup-result-fragment
              {:decision :driving :continuing? true :ws-id "ws-42"
               :ref {:id "BR-104" :title "Fix login redirect"}}
              {:project "brian" :daemon-ready? true})]
    (is (str/includes? html "id=\"pickup-result\""))
    (is (str/includes? html "Continuing"))
    (is (str/includes? html "BR-104"))
    (is (str/includes? html "Fix login redirect"))
    (is (str/includes? html "/workstreams/brian/ws-42"))
    (is (not (str/includes? html "daemon is down")))))

(deftest pickup-result-starting-fresh-has-no-link
  (let [html (views/pickup-result-fragment
              {:decision :driving :continuing? false :ws-id nil
               :ref {:id "BR-104" :title "Fix login redirect"}}
              {:project "brian" :daemon-ready? true})]
    (is (str/includes? html "Starting"))
    (is (str/includes? html "new workstream"))
    (is (not (str/includes? html "/workstreams/brian/")))))

(deftest pickup-result-daemon-down-warns
  (let [html (views/pickup-result-fragment
              {:decision :driving :continuing? false :ws-id nil
               :ref {:id "BR-104" :title "t"}}
              {:project "brian" :daemon-ready? false})]
    (is (str/includes? html "daemon is down"))))

(deftest pickup-result-errors-render-friendly-text
  (let [render (fn [err] (views/pickup-result-fragment
                          {:decision :unresolved :error err}
                          {:project "brian" :daemon-ready? true}))]
    (is (str/includes? (render :no-token) "keychain"))
    (is (str/includes? (render :not-found) "Couldn't find"))
    (is (str/includes? (render :not-a-ticket) "Couldn't find"))
    (is (str/includes? (render :unrecognized-input) "Paste a Notion URL"))
    (is (str/includes? (render :notion-error) "Notion lookup failed"))
    (is (str/includes? (render :auth) "Notion lookup failed"))))
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `bb nido:test :only nido.ui.views-pickup-test`
Expected: FAIL — `pickup-bar` / `pickup-result-fragment` are not defined.

- [ ] **Step 3: Implement the two view fns**

In `src/nido/ui/views.clj`, immediately above `workstreams-page` (~line 691), add:

```clojure
(defn pickup-bar
  "Paste-a-ticket bar at the top of /workstreams. Binds a `pickup` signal, POSTs
   it to /workstreams/pickup/<project> (Enter or the button), and reserves an
   empty #pickup-result the SSE response patches. Lives on the page chrome, NOT
   inside workstreams-fragment, so the 5s poll never clobbers the result."
  [project]
  (let [post (str "@post('/workstreams/pickup/" project "')")]
    [:div.card {:style "margin-bottom:12px;"}
     [:strong "Drive a ticket"]
     [:div {:style "display:flex; gap:8px; margin-top:6px;"}
      [:input {"data-bind" "pickup"
               "data-on:keydown" (str "evt.key === 'Enter' && (" post ")")
               :placeholder "paste Notion URL / page id / BR-#…"
               :style "flex:1; box-sizing:border-box;"}]
      [:button.btn.btn-primary {"data-on:click" post} "Drive →"]]
     [:div {:id "pickup-result"}]]))

(defn pickup-result-fragment
  "HTML string (root #pickup-result) reporting the outcome of a pickup POST.
   `result` is pickup!'s return; opts is {:project <str> :daemon-ready? <bool>}."
  [result {:keys [project daemon-ready?]}]
  (str
   (h/html
    [:div {:id "pickup-result" :style "margin-top:8px;"}
     (if (= :unresolved (:decision result))
       [:p.meta
        (case (:error result)
          :no-token           "No Notion token in keychain."
          (:not-found
           :not-a-ticket)     "Couldn't find that ticket."
          :unrecognized-input "Paste a Notion URL, page id, or BR-####."
          "Notion lookup failed — try again.")]
       (let [{:keys [continuing? ws-id ref]} result
             {:keys [id title]} ref]
         [:div
          (if continuing?
            [:p "✓ Continuing " [:strong id] " \"" title "\" → "
             [:a {:href (str "/workstreams/" project "/" ws-id)} "workstream ↗"]
             " (session spinning up…)"]
            [:p "✓ Starting " [:strong id] " \"" title "\" (new workstream) — "
             "it'll appear in the spine shortly."])
          (when-not daemon-ready?
            [:p.meta "⚠ daemon is down — queued, but it won't run until the daemon is back up."])]))])))
```

- [ ] **Step 4: Load-check + run the tests to verify they pass**

Run: `bb -e "(require 'nido.ui.views)"` — Expected: no error.
Run: `bb nido:test :only nido.ui.views-pickup-test`
Expected: PASS — all six tests.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(ui): pickup-bar + result fragment views for /workstreams"
```

---

### Task 3: POST route + wire the bar into the page

**Files:**
- Modify: `src/nido/ui/server.clj` (the `handle-post` cond ~lines 291-359; add `nido.coordinator.pickup` + `nido.notion.client` to the ns require)
- Modify: `src/nido/ui/views.clj` (`workstreams-page`, ~lines 691-709 — render the bar in the `.queue-col`)
- Test: `test/nido/ui/server_pickup_test.clj` (create)

**Interfaces:**
- Consumes:
  - `nido.coordinator.pickup/pickup!` — `(pickup! project-kw input token)` (Task 1 shape).
  - `nido.notion.client/keychain-token` — `() → <token-string-or-nil>`.
  - `views/pickup-result-fragment` and `views/pickup-bar` (Task 2).
  - `read-rail-daemon` (already in `server.clj`) — `() → {:state :up|:down|…}`. Daemon-ready ⇔ `(= :up (:state …))`.
- Produces: route `POST /workstreams/pickup/:project` → `sse-response` patching `#pickup-result`.

- [ ] **Step 1: Write the failing test**

Create `test/nido/ui/server_pickup_test.clj`:

```clojure
(ns nido.ui.server-pickup-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.pickup :as pickup]
   [nido.notion.client :as client]
   [nido.ui.server :as server]))

(defn- post [uri body]
  (server/handle-request {:request-method :post :uri uri
                          :body (java.io.ByteArrayInputStream.
                                 (.getBytes ^String body "UTF-8"))}))

(deftest pickup-post-resolves-and-patches-result
  (with-redefs [client/keychain-token (fn [] "tok")
                server/read-rail-daemon (fn [] {:state :up})
                pickup/pickup! (fn [project input token]
                                 (is (= :brian project))
                                 (is (= "BR-104" input))
                                 (is (= "tok" token))
                                 {:decision :driving :continuing? true :ws-id "ws-9"
                                  :ref {:id "BR-104" :title "t"} :queued "/q/x.edn"})]
    (let [resp (post "/workstreams/pickup/brian" "{\"pickup\":\"BR-104\"}")]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "datastar-patch-elements"))
      (is (str/includes? (:body resp) "Continuing"))
      (is (str/includes? (:body resp) "/workstreams/brian/ws-9")))))

(deftest pickup-post-blank-input-short-circuits
  (with-redefs [client/keychain-token (fn [] "tok")
                server/read-rail-daemon (fn [] {:state :up})
                pickup/pickup! (fn [& _] (throw (ex-info "should not be called" {})))]
    (let [resp (post "/workstreams/pickup/brian" "{\"pickup\":\"   \"}")]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "Paste a Notion URL")))))

(deftest pickup-post-daemon-down-warns
  (with-redefs [client/keychain-token (fn [] "tok")
                server/read-rail-daemon (fn [] {:state :down})
                pickup/pickup! (fn [& _]
                                 {:decision :driving :continuing? false :ws-id nil
                                  :ref {:id "BR-104" :title "t"} :queued "/q/x.edn"})]
    (let [resp (post "/workstreams/pickup/brian" "{\"pickup\":\"BR-104\"}")]
      (is (str/includes? (:body resp) "daemon is down")))))
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `bb nido:test :only nido.ui.server-pickup-test`
Expected: FAIL — the route falls through to the 404 branch (`:status 404`), so the status/`Continuing` assertions fail.

- [ ] **Step 3: Implement the route**

In `src/nido/ui/server.clj`, add to the ns `:require`:

```clojure
            [nido.coordinator.pickup :as pickup]
            [nido.notion.client :as client]
```

Add this clause to the `handle-post` `cond`, immediately **before** the existing `/workstreams/:project/:ws-id/findings` clause (a 3-segment `workstreams` shape — order it before the 4-seg findings clause is fine; place it as the first `workstreams` clause for clarity):

```clojure
      ;; POST /workstreams/pickup/:project — resolve a pasted Notion ref, enqueue
      ;; the :plan-bug leg, and patch #pickup-result with the continuing/new report.
      (and (= 3 (count segs)) (= "workstreams" (first segs)) (= "pickup" (nth segs 1)))
      (let [project (nth segs 2)
            input   (str/trim (str (:pickup (parse-json-body body))))
            ready?  (= :up (:state (read-rail-daemon)))]
        (if (str/blank? input)
          (sse-response
           (sse-fragment
            (views/pickup-result-fragment {:decision :unresolved :error :unrecognized-input}
                                          {:project project :daemon-ready? ready?})))
          (let [result (pickup/pickup! (keyword project) input (client/keychain-token))]
            (sse-response
             (sse-fragment
              (views/pickup-result-fragment result {:project project :daemon-ready? ready?}))))))
```

- [ ] **Step 4: Load-check + run the route test**

Run: `bb -e "(require 'nido.ui.server)"` — Expected: no error.
Run: `bb nido:test :only nido.ui.server-pickup-test`
Expected: PASS — all three route tests.

- [ ] **Step 5: Wire the bar into `workstreams-page`**

In `src/nido/ui/views.clj`, in `workstreams-page`, compute the project from scope and render the bar at the top of `.queue-col`, above `tab-row`. Change the `let` binding and the `.queue-col` div:

```clojure
  (let [sel-id  (:ws-id selection)
        q       (screen-query screen (when sel-id {:sel (str (:project selection) ":" sel-id)}))
        project (if (= "all" (:scope screen)) "brian" (:scope screen))]
```

and

```clojure
      [:div.queue-col
       (pickup-bar project)
       (tab-row screen)
       [:div.inbox {:data-on-interval__duration.5s (str "@get('/_fragment/workstreams" q "')")}
        (h/raw (workstreams-fragment screen))]]
```

- [ ] **Step 6: Load-check + run the views + server suites**

Run: `bb -e "(require 'nido.ui.views 'nido.ui.server)"` — Expected: no error.
Run: `bb nido:test :only nido.ui.views-pickup-test` then `bb nido:test :only nido.ui.server-pickup-test`
Expected: PASS.

- [ ] **Step 7: Manual smoke (optional but recommended)**

With the daemon up (dashboard at `http://localhost:8800`), open `/workstreams`, paste a real `BR-####` into the bar, press Enter, and confirm the result line shows Continuing/Starting and (when continuing) the workstream link resolves. If the daemon is down, confirm the ⚠ warning shows.

- [ ] **Step 8: Commit**

```bash
jj commit -m "feat(ui): POST /workstreams/pickup route + pickup bar on the overview"
```

---

## Self-Review

**Spec coverage:**
- `pickup!` reports continuing vs new → Task 1. ✓
- POST route with project-from-scope + daemon-down guard + `parse-json-body` → Task 3. ✓
- `pickup-bar` on page chrome above `tab-row`, `data-bind`, Enter-to-submit, `#pickup-result` → Tasks 2 + 3 (step 5). ✓
- `pickup-result-fragment` continuing/starting/error/daemon-down variants → Task 2. ✓
- Testing: `pickup!` branch + fragment rendering → Tasks 1 & 2; route wiring → Task 3. ✓
- Out-of-scope items (no new orchestration, `/workstreams` only, inline confirmation) are honored — no redirect, no other surfaces touched. ✓

**Placeholder scan:** none — every code/test step shows the actual code and commands.

**Type consistency:** `pickup!` returns `:continuing?`/`:ws-id`/`:ref` (Task 1) — consumed verbatim by `pickup-result-fragment` (Task 2) and asserted in the route test (Task 3). `pickup-result-fragment` opts `{:project :daemon-ready?}` consistent across Tasks 2 & 3. Route path `/workstreams/pickup/:project` consistent between `pickup-bar`'s `@post` (Task 2) and the `handle-post` clause (Task 3). Daemon-ready derived identically (`(= :up (:state …))`) in the route and asserted in tests.
