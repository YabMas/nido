# Notion Source Upgrade + View Registry — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **jj commit hygiene:** This is a jujutsu repo. In every commit step, run `jj log -r '@-..@' --no-graph` BEFORE editing files (if `@` has a description, `jj new` first), and AGAIN AFTER editing (confirm parent is the previous task's commit). Subagents have repeatedly mashed two tasks into one commit by skipping this check.

**Goal:** Migrate nido's Notion source from the deprecated `/v1/databases/<id>/query` (no filter support) to the modern `/v1/data_sources/<ds-id>/query` (filter body), introduce a per-project `notion-views.edn` registry so triggers can name a Notion view instead of restating its filter inline, and add `:additional-filter` + `:priority-from` per-trigger knobs that the triage triggers need.

**Architecture:** A new `nido.notion.views` namespace owns the registry (per-project file `~/.nido/projects/<project>/notion-views.edn`) and exposes `resolve-view [project view-kw] → {:database "..." :filter <filter-map>}`. `nido.notion.client/database-query` is renamed `data-source-query` and accepts a filter body. `nido.coordinator.sources.notion`'s `poll-once!` accepts `:view` as a keyword, resolves via `views/resolve-view`, merges `:additional-filter` with `{:and [...]}`, applies the filter via the new client. Envelope `:priority` comes from either `:priority-from {:property "<name>"}` (read from normalised page properties) or the trigger-level `:priority` constant.

**Tech Stack:** Babashka (bb), `clojure.test`, Malli. Existing `nido.notion.*` and `nido.coordinator.sources.notion` namespaces.

**Spec reference:** [2026-05-18-notion-triage-agent-design.md §Notion data model](../specs/2026-05-18-notion-triage-agent-design.md). This plan delivers Stage 3 of the six-stage rollout.

---

## File Structure

**New:**
- `src/nido/notion/views.clj` — view registry loader, resolver, Malli schema.
- `src/nido/notion/views_check.clj` — validates a registry against the live database (used by the new `bb` task).
- `src/tasks/nido_notion_views.clj` — `bb nido:notion:views:check :project <p>` task entry point.
- `test/nido/notion/views_test.clj` — unit tests for the registry.

**Modified:**
- `src/nido/notion/client.clj` — `database-query` (deprecated) → `data-source-query`. Add `resolve-data-source-id` (one call to retrieve-a-database, extract the data-source id, cache it).
- `src/nido/coordinator/sources/notion.clj` — `:view` becomes a keyword resolved via the registry; `:additional-filter` merged with `{:and [...]}` before query; `:priority-from {:property "<name>"}` extracted per-event.
- `src/nido/coordinator/triggers.clj` — `Trigger` schema gains `[:priority-from {:optional true} [:map …]]` (the constant `:priority` from Plan A stays).
- `src/nido/coordinator/sources/notion.clj` Malli schema (`{:type :notion-view :schema ...}`) — `:view` becomes `keyword?`, new `:additional-filter` and `:priority-from` keys.
- `bb.edn` — add the `nido:notion:views:check` task wiring.

**Untouched:** `nido.coordinator.executor` (already consumes envelope `:priority` regardless of source).

---

## Task 1 — `nido.notion.views` registry

**Files:**
- Create: `src/nido/notion/views.clj`
- Test: `test/nido/notion/views_test.clj`

The registry lives at `~/.nido/projects/<project>/notion-views.edn`:

```clojure
{:database "124fca9f-403c-80d4-896f-fc857e105e35"
 :views
 {:new-reports
  {:filter {:property "Status" :status {:equals "Needs verification"}}}

  :bugs
  {:filter {:and [{:property "Type"   :select {:equals "bug"}}
                  {:property "Status" :status {:does_not_equal "Done"}}
                  ...]}}}}
```

Resolver returns `{:database <id> :filter <filter-map>}` for a `(project, view-kw)` pair. Throws if the file is missing (registries are explicit opt-in for any project that uses Notion sources), or if the view is unknown.

- [ ] **Step 1: jj hygiene check.** `jj log -r '@-..@' --no-graph`. If `@` has a description, `jj new`.

- [ ] **Step 2: Failing tests**

```clojure
(ns nido.notion.views-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.io :as io]
   [nido.notion.views :as views]
   [nido.coordinator.state :as cstate]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (fs/create-dirs (str (fs/path tmp "projects" "brian")))
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(def sample-registry
  {:database "124fca9f-403c-80d4-896f-fc857e105e35"
   :views {:new-reports {:filter {:property "Status" :status {:equals "Needs verification"}}}
           :bugs        {:filter {:and [{:property "Type" :select {:equals "bug"}}]}}}})

(deftest resolve-view-returns-database-and-filter
  (with-tmp
    (fn [tmp]
      (io/write-edn! (str (fs/path tmp "projects" "brian" "notion-views.edn"))
                     sample-registry)
      (let [r (views/resolve-view :brian :new-reports)]
        (is (= "124fca9f-403c-80d4-896f-fc857e105e35" (:database r)))
        (is (= {:property "Status" :status {:equals "Needs verification"}}
               (:filter r)))))))

(deftest resolve-view-throws-on-unknown-view
  (with-tmp
    (fn [tmp]
      (io/write-edn! (str (fs/path tmp "projects" "brian" "notion-views.edn"))
                     sample-registry)
      (is (thrown? clojure.lang.ExceptionInfo
                   (views/resolve-view :brian :nope))))))

(deftest resolve-view-throws-when-no-registry-file
  (with-tmp
    (fn [_]
      (is (thrown? clojure.lang.ExceptionInfo
                   (views/resolve-view :brian :new-reports))))))
```

- [ ] **Step 3: Run, verify fail** — `bb test test/nido/notion/views_test.clj`.

- [ ] **Step 4: Implement**

```clojure
(ns nido.notion.views
  "Per-project Notion view registry. The Notion REST API does not expose
   view filter definitions; this registry encodes them so triggers can
   refer to a view by keyword and the source applies the right filter.

   Registry path: ~/.nido/projects/<project>/notion-views.edn

   Shape:
     {:database \"<notion-db-id>\"
      :views {:<view-kw> {:filter <notion-filter-map>}
              ...}}

   Filters are the literal body Notion expects under the \"filter\" key in
   a data-source query. No translation layer."
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

(defn- registry-path [project]
  (str (fs/path (cstate/nido-root) "projects" (name project) "notion-views.edn")))

(defn load-registry [project]
  (let [path (registry-path project)]
    (when-not (fs/exists? path)
      (throw (ex-info (str "Notion views registry missing for project " project
                           ". Create " path " or remove the Notion source from triggers.")
                      {:project project :path path})))
    (io/read-edn path)))

(defn resolve-view
  "Returns {:database <id> :filter <map>} for the given (project, view-kw).
   Throws on missing registry or unknown view."
  [project view-kw]
  (let [{:keys [database views]} (load-registry project)]
    (or (when-let [v (get views view-kw)]
          {:database database :filter (:filter v)})
        (throw (ex-info (str "Unknown Notion view " view-kw " for project " project)
                        {:project project :view view-kw :known (keys views)})))))
```

- [ ] **Step 5: Run, verify pass.**

- [ ] **Step 6: Run full suite** — `bb test`.

- [ ] **Step 7: jj hygiene + commit**

```
jj describe @ -m "feat(notion/views): per-project view registry

New nido.notion.views ns. Loads ~/.nido/projects/<project>/notion-views.edn
and resolves a view keyword to {:database :filter}. Filters use Notion's
native filter-object DSL (no translation layer — the value is the literal
body of a data-source query). Throws on missing registry or unknown view.
Not wired into the source yet — see next tasks."
```

---

## Task 2 — Migrate `client/database-query` to `data-source-query`

**Files:**
- Modify: `src/nido/notion/client.clj` (lines 45-70 — the existing `database-query`)
- Test: append to whatever client test exists, or create `test/nido/notion/client_test.clj`

The existing `database-query` POSTs to `/v1/databases/<id>/query` with a hardcoded `{"page_size":100}` body. The new `data-source-query` POSTs to `/v1/data_sources/<ds-id>/query` and accepts a filter map. The Notion API version header bumps from `2022-06-28` to `2025-09-03` (per spec — verify against the latest Notion docs at plan-execution time, but `2025-09-03` is correct as of 2026-05-18).

Database-id → data-source-id resolution needs a separate call to `/v1/databases/<id>` (`retrieve-a-database`), which returns the database with its `data_sources` list. Cache the lookup per process.

- [ ] **Step 1: jj hygiene check.**

- [ ] **Step 2: Failing tests**

Create `test/nido/notion/client_test.clj` (or append to existing):

```clojure
(ns nido.notion.client-test
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is]]
   [nido.notion.client :as client]))

(deftest data-source-query-posts-filter-body
  (let [captured (atom nil)]
    (with-redefs [client/http-request
                  (fn [_method url opts]
                    (reset! captured {:url url :body (:body opts)})
                    {:status 200 :body "{\"results\":[],\"has_more\":false}"})]
      (client/data-source-query "ds-1" "token-x"
                                {:filter {:property "Status"
                                          :status {:equals "Needs verification"}}})
      (let [{:keys [url body]} @captured
            decoded (json/parse-string body true)]
        (is (re-find #"/v1/data_sources/ds-1/query" url))
        (is (= {:property "Status" :status {:equals "Needs verification"}}
               (:filter decoded)))))))

(deftest resolve-data-source-id-extracts-from-database-fetch
  (with-redefs [client/http-request
                (fn [_method url _opts]
                  (cond
                    (re-find #"/v1/databases/db-1" url)
                    {:status 200
                     :body (json/generate-string
                             {:id "db-1"
                              :data_sources [{:id "ds-from-db-1" :name "main"}]})}
                    :else {:status 404 :body ""}))]
    (is (= "ds-from-db-1" (client/resolve-data-source-id "db-1" "token-x")))))

(deftest resolve-data-source-id-caches
  (let [calls (atom 0)]
    (with-redefs [client/http-request
                  (fn [_method _url _opts]
                    (swap! calls inc)
                    {:status 200
                     :body (json/generate-string
                             {:id "db-cached" :data_sources [{:id "ds-cached"}]})})]
      (client/clear-data-source-cache!)
      (client/resolve-data-source-id "db-cached" "token-x")
      (client/resolve-data-source-id "db-cached" "token-x")
      (is (= 1 @calls) "second call should hit the cache, not the API"))))
```

- [ ] **Step 3: Run, verify fail.**

- [ ] **Step 4: Implement**

In `src/nido/notion/client.clj`, replace `database-query` with the new functions. Keep the docstring style + the `http-request` redef seam.

```clojure
(def ^:private notion-api-version "2025-09-03")

(defonce ^:private !data-source-cache (atom {}))

(defn clear-data-source-cache!
  "Test-only / config-reload helper."
  []
  (reset! !data-source-cache {}))

(defn- retrieve-database
  "GET /v1/databases/<id>. Returns parsed JSON or {:error :kw}."
  [database-id token]
  (let [resp (try
               (http-request
                 :get
                 (str "https://api.notion.com/v1/databases/" database-id)
                 {:headers {"Authorization"  (str "Bearer " token)
                            "Notion-Version" notion-api-version}
                  :timeout 10000})
               (catch Exception e
                 {:status 0 :exception e}))
        {:keys [status body]} resp]
    (cond
      (= status 200) (json/parse-string body true)
      (= status 401) {:error :auth}
      (>= status 500) {:error :server}
      (= status 0) {:error :network}
      :else {:error :http :status status})))

(defn resolve-data-source-id
  "Look up the first data-source id for a database (the only one for most
   databases). Cached per-process — call clear-data-source-cache! after
   schema changes. Returns the id or throws if the database has no data
   sources / the API call failed."
  [database-id token]
  (if-let [cached (get @!data-source-cache database-id)]
    cached
    (let [db (retrieve-database database-id token)]
      (if (:error db)
        (throw (ex-info "Failed to resolve data-source id"
                        {:database database-id :error db}))
        (let [ds-id (-> db :data_sources first :id)]
          (when-not ds-id
            (throw (ex-info "Database has no data sources"
                            {:database database-id})))
          (swap! !data-source-cache assoc database-id ds-id)
          ds-id)))))

(defn data-source-query
  "POST /v1/data_sources/<ds-id>/query with a body containing optional
   :filter (Notion filter map), :sorts, and :page_size. Returns
   {:status 200 :results :has_more} on success or {:status :error :kw}.
   Hard 10s timeout."
  [data-source-id token {:keys [filter sorts page-size]
                         :or   {page-size 100}}]
  (let [body (cond-> {:page_size page-size}
               filter (assoc :filter filter)
               sorts  (assoc :sorts sorts))
        resp (try
               (http-request
                 :post
                 (str "https://api.notion.com/v1/data_sources/" data-source-id "/query")
                 {:headers {"Authorization"  (str "Bearer " token)
                            "Notion-Version" notion-api-version
                            "Content-Type"   "application/json"}
                  :body    (json/generate-string body)
                  :timeout 10000})
               (catch Exception e
                 {:status 0 :exception e}))
        {:keys [status body]} resp]
    (cond
      (= status 200)  (let [parsed (json/parse-string body true)]
                        {:status 200 :results (:results parsed) :has_more (:has_more parsed)})
      (= status 401)  {:status status :error :auth}
      (>= status 500) {:status status :error :server}
      (= status 0)    {:status 0 :error :network}
      :else           {:status status :error :http}))))
```

`database-query` (the old function) — delete it entirely. There's only one caller (the notion source) and it'll be updated in Task 3. Use grep to confirm no other reference exists before deleting.

- [ ] **Step 5: Run client tests, verify pass.**

- [ ] **Step 6: Run full suite** — there will likely be FAILURES because `sources/notion.clj` still calls `database-query`. That's expected; Task 3 fixes that. For now, run with `bb test test/nido/notion/client_test.clj` only to confirm the client tests are green; the broken source tests will be addressed in Task 3's commit.

If you'd rather not leave the tree in a non-green state between commits, do a temporary stub in `sources/notion.clj` that uses `data-source-query` directly (no filter, no view resolution yet). But this is one commit's worth of red — usually acceptable in CDD if the very next commit fixes it.

- [ ] **Step 7: jj hygiene + commit**

```
jj describe @ -m "feat(notion/client): data-source-query replaces deprecated database-query

POST /v1/data_sources/<ds-id>/query with filter body, Notion API
version 2025-09-03. resolve-data-source-id caches the per-database
lookup. Source-side migration in the next commit (suite may be red
between the two; that's the cost of an honest two-commit migration)."
```

---

## Task 3 — Notion source uses the new client + view registry

**Files:**
- Modify: `src/nido/coordinator/sources/notion.clj` (the source plugin)
- Test: append to `test/nido/coordinator/sources/notion_test.clj` (find or create)

The source plugin today (from earlier survey) emits an envelope per row, polling via `database-query`. After this task:

1. `:view` is a keyword, resolved via `nido.notion.views/resolve-view` to `{:database :filter}`.
2. Optional `:additional-filter` on the source config is merged with the view's filter using `{:and [view-filter additional-filter]}`.
3. The database id from the resolved view drives `resolve-data-source-id` → `data-source-query`.

Envelope `:priority` extraction lives in Task 4.

- [ ] **Step 1: jj hygiene check.**

- [ ] **Step 2: Failing test**

Find or create `test/nido/coordinator/sources/notion_test.clj`. Add:

```clojure
(ns nido.coordinator.sources.notion-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.sources.notion :as nv]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]
   [nido.notion.client :as client]
   [nido.notion.views :as views]))

(defn- with-tmp [project f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (fs/create-dirs (str (fs/path tmp "projects" (name project))))
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(deftest poll-once-uses-view-filter
  (with-tmp :brian
    (fn [tmp]
      (io/write-edn! (str (fs/path tmp "projects" "brian" "notion-views.edn"))
                     {:database "db-1"
                      :views {:new-reports
                              {:filter {:property "Status" :status {:equals "Needs verification"}}}}})
      (let [captured-filter (atom nil)]
        (with-redefs [client/resolve-data-source-id (fn [_ _] "ds-1")
                      client/data-source-query
                      (fn [_ds _token opts]
                        (reset! captured-filter (:filter opts))
                        {:status 200 :results [] :has_more false})]
          (nv/poll-once! {:project :brian :view :new-reports} "token" (fn [_]))
          (is (= {:property "Status" :status {:equals "Needs verification"}}
                 @captured-filter)))))))

(deftest poll-once-merges-additional-filter
  (with-tmp :brian
    (fn [tmp]
      (io/write-edn! (str (fs/path tmp "projects" "brian" "notion-views.edn"))
                     {:database "db-1"
                      :views {:bugs {:filter {:property "Type" :select {:equals "bug"}}}}})
      (let [captured-filter (atom nil)]
        (with-redefs [client/resolve-data-source-id (fn [_ _] "ds-1")
                      client/data-source-query
                      (fn [_ds _token opts]
                        (reset! captured-filter (:filter opts))
                        {:status 200 :results [] :has_more false})]
          (nv/poll-once! {:project :brian
                          :view    :bugs
                          :additional-filter
                          {:property "Effort" :select {:is_empty true}}}
                         "token" (fn [_]))
          (is (= {:and [{:property "Type"   :select {:equals "bug"}}
                        {:property "Effort" :select {:is_empty true}}]}
                 @captured-filter)))))))
```

Adapt to whatever `poll-once!`'s actual signature is — read the function first.

- [ ] **Step 3: Run, verify fail.**

- [ ] **Step 4: Implement view + filter merging**

In `src/nido/coordinator/sources/notion.clj`:

```clojure
(require '[nido.notion.views :as views])

(defn- merge-filters
  "Combine the view's filter with an optional :additional-filter into a
   single Notion filter object."
  [view-filter additional-filter]
  (cond
    (nil? additional-filter) view-filter
    (nil? view-filter)       additional-filter
    :else                    {:and [view-filter additional-filter]}))

(defn poll-once!
  "...existing docstring..."
  [source-config token emit-fn]
  (let [{:keys [project view additional-filter]} source-config
        {:keys [database filter]} (views/resolve-view project view)
        ds-id   (client/resolve-data-source-id database token)
        combined (merge-filters filter additional-filter)
        resp    (client/data-source-query ds-id token {:filter combined})]
    ;; existing :status handling + normalise-page + emit-fn loop
    ...))
```

The `:project` field is new on `source-config` — needs to flow from the trigger. The coordinator already knows the project (it's on the trigger), so when the source is registered, the project should be threaded through. Look at how `sources.clj` registers and instantiates the source — the trigger's project should be on the source-config map by the time `poll-once!` sees it.

If not, that's the part to wire: in `nido.coordinator.sources/start!` or wherever a source is instantiated for a project, add `:project (-> trigger :name namespace-or-similar)` … or pass it explicitly. The exact wiring depends on the existing source-instance shape (look at how the snapshot file under `~/.nido/coordinator/sources/<hash>.edn` is keyed).

- [ ] **Step 5: Update the source plugin's Malli schema**

`:view` is now `keyword?`, not `string?`. Add `:additional-filter` (optional map). Find the schema in `sources/notion.clj` (around line 94-99 per the earlier survey):

```clojure
(def schema
  [:map
   [:type    [:= :notion-view]]
   [:project keyword?]
   [:view    keyword?]
   [:poll    {:optional true} string?]
   [:additional-filter {:optional true} [:map-of keyword? any?]]
   ;; :priority-from added in Task 4
   ])
```

- [ ] **Step 6: Run sources/notion tests, verify pass.**

- [ ] **Step 7: Update the existing smoke-notion trigger** to use the new view-keyword form. The existing trigger config at `~/.nido/projects/brian/triggers.edn` uses `:view "open-bugs"` (string). Either:

(a) Add a temporary `:open-bugs` view to the brian registry (a single trigger we currently use) so the smoke trigger keeps working.
(b) Bump the smoke trigger to use `:new-reports` or `:bugs` from the planned-for-triage registry.

Choose (a) for minimum churn. The full content of `~/.nido/projects/brian/notion-views.edn` after this task:

```clojure
{:database "124fca9f-403c-80d4-896f-fc857e105e35"
 :views
 {:open-bugs
  {:filter {:and [{:property "Type"   :select {:equals "bug"}}
                  {:property "Status" :status {:does_not_equal "Done"}}
                  {:property "Status" :status {:does_not_equal "Not Done"}}]}}}}
```

(The :new-reports and :bugs entries will be added in Task 6 when triage triggers ship.)

Update `~/.nido/projects/brian/triggers.edn` to use `:view :open-bugs`.

- [ ] **Step 8: Run full suite**

```
bb test
```

Expected: green. The sources/notion test count went up, the source itself works against the new client.

- [ ] **Step 9: jj hygiene + commit**

```
jj describe @ -m "feat(coordinator/sources/notion): use view registry + data-source query

poll-once! resolves :view via nido.notion.views, merges with optional
:additional-filter ({:and [...]}), and queries through the migrated
data-source-query client. :view is now a keyword. :project on
source-config drives registry lookup. Brian's smoke-notion trigger
migrated to :view :open-bugs in the registry. No behaviour change for
that trigger — same database, same filter shape."
```

---

## Task 4 — Per-event `:priority-from`

**Files:**
- Modify: `src/nido/coordinator/triggers.clj` (Trigger schema gains `:priority-from`)
- Modify: `src/nido/coordinator/sources/notion.clj` (extract priority from page per event)
- Modify: `src/nido/coordinator/events.clj` (route uses event's `:priority` if present, falls back to trigger's `:priority`)
- Test: append to triggers_test, sources/notion_test, events_test

`:priority-from {:property "severity-calc"}` says "look up this property on the Notion page and use its numeric value as the envelope priority." If the property is missing or non-numeric, fall back to the trigger-level `:priority` or 0.

- [ ] **Step 1: jj hygiene check.**

- [ ] **Step 2: Failing tests**

Append to `test/nido/coordinator/triggers_test.clj`:

```clojure
(deftest schema-accepts-priority-from-map
  (is (m/validate triggers/Trigger
                  (assoc minimal-trigger :priority-from {:property "severity-calc"}))))
```

Append to `test/nido/coordinator/sources/notion_test.clj`:

```clojure
(deftest poll-once-stamps-priority-from-property
  (with-tmp :brian
    (fn [tmp]
      (io/write-edn! (str (fs/path tmp "projects" "brian" "notion-views.edn"))
                     {:database "db-1" :views {:v {:filter {}}}})
      (let [emitted (atom [])]
        (with-redefs [client/resolve-data-source-id (fn [_ _] "ds-1")
                      client/data-source-query
                      (fn [_ds _token _opts]
                        {:status 200 :has_more false
                         :results [{:id "page-1" :url "u"
                                    :created_time "t" :last_edited_time "t"
                                    :properties {"severity-calc" {:type "formula"
                                                                   :formula {:type "number" :number 5}}}}]})]
          (nv/poll-once! {:project :brian :view :v
                          :priority-from {:property "severity-calc"}}
                         "token"
                         (fn [event] (swap! emitted conj event)))
          (is (= 1 (count @emitted)))
          (is (= 5 (-> @emitted first :priority))
              "envelope :priority should come from the Notion property"))))))
```

Append to `test/nido/coordinator/events_test.clj`:

```clojure
(deftest route-event-priority-overrides-trigger-priority
  (let [trigger (assoc minimal-trigger :priority 5)
        envelope {:broadcast {:type :notion-view :source-config {}
                              :payload {:page-id "p" :priority 100}}
                  :received-at "t" :priority 100}
        requests (events/route envelope [trigger])]
    (is (= 100 (-> requests first :priority))
        "event-level priority should win over trigger-level priority")))
```

- [ ] **Step 3: Run, verify fail.**

- [ ] **Step 4: Add `:priority-from` to the Trigger schema**

```clojure
[:priority-from {:optional true}
 [:map [:property string?]]]
```

- [ ] **Step 5: Extract priority in `sources/notion.clj`**

`normalise-page` (in `nido/notion/client.clj` line 104-120) already flattens properties under top-level keys. Augment the source's per-page emission:

```clojure
(defn- priority-from-page
  "Given a Notion page (post-normalise) and :priority-from config, extract
   the numeric value or return nil."
  [page priority-from]
  (when-let [prop-name (:property priority-from)]
    (let [v (get-in page [:properties (-> prop-name str clojure.string/lower-case
                                          (clojure.string/replace #"[^a-z0-9]+" "-")
                                          (clojure.string/replace #"^-|-$" "")
                                          keyword)])]
      (cond
        (number? v) (long v)
        (map? v)    (some-> v :number long)  ;; formula values may be {:type :number :number N}
        :else       nil))))
```

The normalise function already turns the value (formula → number) into a top-level scalar; the `(map? v)` branch is defensive in case the extract path changes.

Then in the per-page emit loop:

```clojure
(let [page-norm   (client/normalise-page page)
      ev-priority (priority-from-page page-norm priority-from)
      payload     (cond-> page-norm
                    ev-priority (assoc :priority ev-priority))]
  (emit-fn payload))
```

- [ ] **Step 6: Update `events/route`** to prefer event-level `:priority` from the payload over trigger `:priority`:

```clojure
:priority (or (-> envelope :broadcast :payload :priority)
              (:priority t)
              0)
```

(Direct-target envelopes don't have this layering — the route-direct branch keeps `(or (:priority t) 0)`.)

- [ ] **Step 7: Run tests, verify pass.**

- [ ] **Step 8: jj hygiene + commit**

```
jj describe @ -m "feat(coordinator): per-event :priority-from for Notion sources

Trigger gains :priority-from {:property \"<name>\"}; source extracts
the value from each page and stamps the envelope's :priority. route()
prefers event-level priority over trigger-level constant. Falls back
to trigger :priority or 0 when the property is absent / non-numeric.
Unlocks the triage-backlog trigger using severity-calc (1..5)."
```

---

## Task 5 — `bb nido:notion:views:check` validates a registry

**Files:**
- Create: `src/nido/notion/views_check.clj`
- Create: `src/tasks/nido_notion_views.clj`
- Modify: `bb.edn` (add the task wiring)
- Test: append to `test/nido/notion/views_test.clj`

The task walks the registry, hits the Notion API for each database/view, and verifies (a) the database exists, (b) every property referenced in any filter exists on the database, (c) every select value referenced exists on its property. Reports drift; exits non-zero on any error.

This is a small but high-value tool — it catches the "I renamed a Notion property and now triage silently breaks" class of bugs.

- [ ] **Step 1: jj hygiene check.**

- [ ] **Step 2: Failing test**

In `test/nido/notion/views_test.clj`:

```clojure
(deftest check-registry-passes-when-properties-exist
  (with-tmp
    (fn [tmp]
      (io/write-edn! (str (fs/path tmp "projects" "brian" "notion-views.edn"))
                     {:database "db-1"
                      :views {:v {:filter {:property "Status"
                                           :status {:equals "Needs verification"}}}}})
      (with-redefs [client/retrieve-database
                    (fn [_ _]
                      {:properties {"Status" {:type "status"
                                              :status {:options [{:name "Needs verification"}]}}}})]
        (let [result (check/check-registry :brian "fake-token")]
          (is (= :ok (:status result))))))))

(deftest check-registry-fails-on-missing-property
  (with-tmp
    (fn [tmp]
      (io/write-edn! (str (fs/path tmp "projects" "brian" "notion-views.edn"))
                     {:database "db-1"
                      :views {:v {:filter {:property "Bogus"
                                           :status {:equals "x"}}}}})
      (with-redefs [client/retrieve-database
                    (fn [_ _] {:properties {"Status" {:type "status" :status {:options []}}}})]
        (let [result (check/check-registry :brian "fake-token")]
          (is (= :error (:status result)))
          (is (some #(re-find #"Bogus" (:message %)) (:errors result))))))))
```

- [ ] **Step 3: Run, verify fail.**

- [ ] **Step 4: Implement**

```clojure
(ns nido.notion.views-check
  "Validate a notion-views.edn registry against the live Notion database."
  (:require
   [clojure.set :as set]
   [nido.notion.client :as client]
   [nido.notion.views :as views]))

(defn- properties-in-filter
  "Walk a Notion filter map, return the set of all referenced property names."
  [f]
  (cond
    (nil? f)         #{}
    (sequential? f)  (apply set/union (map properties-in-filter f))
    (map? f)         (let [child (apply set/union (map properties-in-filter (vals f)))]
                       (if-let [p (:property f)]
                         (conj child p)
                         child))
    :else            #{}))

(defn- select-values-in-filter
  "Walk a Notion filter map, return [(property-name, select-value) …] for
   every :equals / :does_not_equal under a typed sub-key."
  [f]
  (cond
    (nil? f) []
    (sequential? f) (mapcat select-values-in-filter f)
    (map? f) (let [direct (when-let [p (:property f)]
                            (for [[sub-k sub-v] (dissoc f :property)
                                  :when (and (map? sub-v) (or (:equals sub-v) (:does_not_equal sub-v)))
                                  v [(:equals sub-v) (:does_not_equal sub-v)]
                                  :when (string? v)]
                              [p v]))]
               (concat direct (mapcat select-values-in-filter (vals f))))
    :else []))

(defn check-registry
  "Returns {:status :ok} or {:status :error :errors [{:message ...} ...]}."
  [project token]
  (let [{:keys [database views]} (views/load-registry project)
        db (client/retrieve-database database token)
        db-props (:properties db)
        all-filter-props (apply set/union (for [[_ v] views] (properties-in-filter (:filter v))))
        all-select-pairs (apply concat (for [[_ v] views] (select-values-in-filter (:filter v))))
        missing-props (remove (set (keys db-props)) all-filter-props)
        invalid-options
        (for [[prop val] all-select-pairs
              :let [opts (or (get-in db-props [prop :select :options])
                             (get-in db-props [prop :status :options])
                             (get-in db-props [prop :multi_select :options]))]
              :when (and opts (not (some #(= val (:name %)) opts)))]
          [prop val])
        errors (concat
                 (for [p missing-props]
                   {:message (str "Property '" p "' not found on database " database)})
                 (for [[p v] invalid-options]
                   {:message (str "Property '" p "' has no option '" v "' on database " database)}))]
    (if (seq errors)
      {:status :error :errors (vec errors)}
      {:status :ok})))
```

- [ ] **Step 5: Implement the task**

```clojure
(ns tasks.nido-notion-views
  (:require
   [nido.notion.client :as client]
   [nido.notion.views-check :as check]
   [nido.task-args :as task-args]))

(defn check-cmd
  "bb nido:notion:views:check :project <p> — validate the registry."
  [& args]
  (let [{:keys [project]} (task-args/split-args args)
        token (client/keychain-token)
        _ (when-not token
            (println "ERROR: no notion token in keychain. Run bb nido:notion:auth:set first.")
            (System/exit 2))
        {:keys [status errors]} (check/check-registry (keyword project) token)]
    (case status
      :ok    (do (println "Registry check passed.") nil)
      :error (do (println "Registry check FAILED:")
                 (doseq [e errors] (println "  -" (:message e)))
                 (System/exit 1)))))
```

- [ ] **Step 6: Wire in `bb.edn`**

Find the `:requires` block and the existing notion-related tasks. Add:

```edn
nido:notion:views:check
{:doc "Validate ~/.nido/projects/<p>/notion-views.edn against the live database. :project <p>."
 :task (apply tasks.nido-notion-views/check-cmd *command-line-args*)}
```

Add `[tasks.nido-notion-views]` to the task requires.

- [ ] **Step 7: Run tests + smoke**

```
bb test
bb nido:notion:views:check :project brian
```

The smoke needs the brian notion token set in the keychain (already so since Stage 5). Expected: "Registry check passed." (assuming the registry from Task 3's update is correct).

- [ ] **Step 8: jj hygiene + commit**

```
jj describe @ -m "feat(notion/views-check): bb task validates registry against live DB

bb nido:notion:views:check :project <p> walks the registry, hits the
Notion API, verifies referenced properties + select values exist.
Exits non-zero on drift. Catches the 'I renamed a Notion property and
triage silently breaks' class of bugs at config-edit time, not at
poll-time."
```

---

## Task 6 — End-to-end verification

**Files:** none (verification only)

Confirm the upgraded source still drives the smoke trigger end-to-end, and that view resolution + filter application work against live Notion.

- [ ] **Step 1: Confirm registry exists**

```
cat ~/.nido/projects/brian/notion-views.edn
```

Should show the `:open-bugs` view added in Task 3.

- [ ] **Step 2: Confirm trigger config uses keyword view**

```
cat ~/.nido/projects/brian/triggers.edn
```

`:view` should be `:open-bugs` (keyword), not `"open-bugs"` (string).

- [ ] **Step 3: Run the validator**

```
bb nido:notion:views:check :project brian
```

Expect: "Registry check passed."

- [ ] **Step 4: Bring up the daemon and verify a poll**

```
bb nido:coordinator:down 2>/dev/null || true
sleep 1
bb nido:coordinator:up
sleep 5
bb nido:coordinator:logs | tail -20
```

The source's polling should now go through `data-source-query`. Look for any 4xx errors in the log (auth fine; bad filter or missing data-source would surface here).

- [ ] **Step 5: Fire a trigger to confirm end-to-end**

```
bb nido:trigger:fire :project brian smoke-notion :title "post-upgrade-smoke"
sleep 5
bb nido:runs:list | head -5
```

Run should reach terminal state.

- [ ] **Step 6: Tear down**

```
bb nido:coordinator:down
```

If anything fails, report BLOCKED with the failing step + observed output.

---

## Self-review — spec coverage check

| Spec requirement | Task |
|---|---|
| `notion-views.edn` registry per project | Task 1 |
| Resolver throws on missing registry / unknown view | Task 1 |
| `database-query` → `data-source-query` | Task 2 |
| Resolve database-id → data-source-id, cached | Task 2 |
| Notion API version bumped to 2025-09-03 | Task 2 |
| Source uses view keyword + registry lookup | Task 3 |
| `:additional-filter` merged with `{:and [...]}` | Task 3 |
| Existing smoke trigger keeps working (migrated to keyword view) | Task 3 |
| `:priority-from {:property "<name>"}` extracts per-event priority | Task 4 |
| Event priority overrides trigger priority | Task 4 |
| `bb nido:notion:views:check` validates against live DB | Task 5 |
| End-to-end smoke against post-upgrade source | Task 6 |
| New properties on Notion (Effort, etc.) | **Plan D** (config-time concern, not source-time) |
| Triage triggers using these | **Plan D** |

No placeholders. No TBDs. Field names (`:priority-from`, `:additional-filter`, `:view` as keyword) are introduced in the task that adds them and consumed only in later tasks.
