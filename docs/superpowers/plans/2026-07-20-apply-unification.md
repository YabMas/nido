# Apply Unification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make nido the single executor of triage `apply` — `work/apply!` reads the typed `:triage-report` from the ledger and writes the routing outcome (Ball Holder + App Domain, deep properties, deep callout) to Notion itself, so the board's one-click Apply stops silently dropping the routing.

**Architecture:** Extend `apply!`'s Notion branch to mirror the existing Slack `apply-proposed!` path — read the report, PATCH Notion properties in one call, best-effort-prepend the deep callout, then complete the ledger record. Property writes gate completion; a failed write returns `:notion-failed` and leaves the ticket parked. A new `bb nido:ticket:apply` routes chat-apply through the same code, and the triage-bug skill drops its raw Notion writes.

**Tech Stack:** Babashka/Clojure, `nido.notion.client` (REST over `babashka.http-client`, cheshire), Malli-typed reports, bb tasks.

## Global Constraints

- Owner → Notion Ball Holder user-id: Ataberk `3eb98667-d12e-4e9e-9342-48fec803b571`; Eric `955b4c25-7bce-4ca2-ab5e-d99acbcd423a`; Jaap `169d872b-594c-8160-b432-000250f98e86`.
- App Domain (multi_select) options: `Student`, `Teacher`, `Backend`, `Misc`. **App Domain write is additive** (union with the page's current values); **Ball Holder replaces**.
- Deep report (`:notion-writes` present) writes Type (select), Effort (select — skip when `:squirrel`), Status (status = the `to` of `:status-transition`), Task result (title). Shallow (`:notion-writes nil`) writes only Ball Holder + App Domain.
- **Failure gate:** the Notion property write is attempted first; only on success does `apply!` `complete!` the record. On failure or missing token, return `{:decision :notion-failed :error <kw>}` and do NOT complete — the ticket stays parked. A failed write must never leave a ticket `:triaged`-but-unwritten.
- **Callout is best-effort:** the deep enriched-description callout logs/flags a warning on failure or bottom-landing but never blocks completion; `apply!` returns `{:decision :applied :callout :warn}` in that case.
- Notion-Version pinned at `2025-09-03` (existing `notion-api-version`).
- Load-check any changed namespace before commit: `bb -e "(require 'the.ns :reload)"`. Test task is `bb nido:test` (NOT `bb test`); target one ns with `bb nido:test :only <ns>`.

---

### Task 1: Notion client — block-write primitives

**Files:**
- Modify: `src/nido/notion/client.clj` (`http-request` at 40–47; add two fns after `retrieve-block-children` ~318)
- Test: `test/nido/notion/client_test.clj`

**Interfaces:**
- Produces: `notion/delete-block!` → `{:ok true}` | `{:error :kw}`; `notion/prepend-block-children!` → `{:ok true}` | `{:error :kw}`. Task 2's `apply!` calls both.

- [ ] **Step 1: Write the failing tests**

Add to `test/nido/notion/client_test.clj`:

```clojure
(deftest delete-block-sends-delete-and-maps-status
  (let [captured (atom nil)]
    (with-redefs [notion/http-request
                  (fn [method url _opts]
                    (reset! captured {:method method :url url})
                    {:status 200 :body ""})]
      (is (= {:ok true} (notion/delete-block! "blk-1" "tok")))
      (is (= :delete (:method @captured)))
      (is (re-find #"/v1/blocks/blk-1$" (:url @captured)))))
  (with-redefs [notion/http-request (fn [_ _ _] {:status 401 :body ""})]
    (is (= :auth (:error (notion/delete-block! "b" "t"))))))

(deftest prepend-block-children-patches-with-position-start
  (let [captured (atom nil)]
    (with-redefs [notion/http-request
                  (fn [method url opts]
                    (reset! captured {:method method :url url
                                      :body (cheshire.core/parse-string (:body opts) true)})
                    {:status 200 :body "{}"})]
      (is (= {:ok true}
             (notion/prepend-block-children! "page-1" [{:object "block" :type "callout"}] "tok")))
      (is (= :patch (:method @captured)))
      (is (re-find #"/v1/blocks/page-1/children$" (:url @captured)))
      (is (= {:type "start"} (get-in @captured [:body :position])))
      (is (= 1 (count (get-in @captured [:body :children]))))))
  (with-redefs [notion/http-request (fn [_ _ _] {:status 500 :body ""})]
    (is (= :server (:error (notion/prepend-block-children! "p" [] "t"))))))
```

- [ ] **Step 2: Run to verify they fail**

Run: `bb nido:test :only nido.notion.client-test`
Expected: FAIL — `delete-block!` / `prepend-block-children!` unresolved, and `http-request` has no `:delete` case.

- [ ] **Step 3: Add `:delete` to `http-request`**

Replace the `case` in `http-request` (lines 44–47) with:

```clojure
  (case method
    :get    (http/get    url (assoc opts :throw false))
    :post   (http/post   url (assoc opts :throw false))
    :patch  (http/patch  url (assoc opts :throw false))
    :delete (http/delete url (assoc opts :throw false))))
```

- [ ] **Step 4: Add the two block-write fns**

Insert after `retrieve-block-children` (after line ~316, before `walk-blocks`):

```clojure
(defn- block-write-result
  "Map a Notion write response {:status …} to {:ok true} | {:error :kw}."
  [{:keys [status]}]
  (cond
    (= status 200)         {:ok true}
    (= status 401)         {:error :auth}
    (>= (or status 0) 500) {:error :server}
    (= status 0)           {:error :network}
    :else                  {:error :http :status status}))

(defn delete-block!
  "DELETE /v1/blocks/<block-id> (archives the block). Returns {:ok true} | {:error :kw}."
  [block-id token]
  (block-write-result
   (try
     (http-request :delete (str "https://api.notion.com/v1/blocks/" block-id)
                   {:headers {"Authorization"  (str "Bearer " token)
                              "Notion-Version" notion-api-version}
                    :timeout 10000})
     (catch Exception e {:status 0 :exception e}))))

(defn prepend-block-children!
  "PATCH /v1/blocks/<page-id>/children with `children` and position:start (prepend to
   the top). Notion may ignore `position` for the pinned Notion-Version and append at
   the bottom instead — the caller verifies placement. Returns {:ok true} | {:error :kw}."
  [page-id children token]
  (block-write-result
   (try
     (http-request :patch (str "https://api.notion.com/v1/blocks/" page-id "/children")
                   {:headers {"Authorization"  (str "Bearer " token)
                              "Notion-Version" notion-api-version
                              "Content-Type"   "application/json"}
                    :body    (json/generate-string {:children children :position {:type "start"}})
                    :timeout 10000})
     (catch Exception e {:status 0 :exception e}))))
```

- [ ] **Step 5: Run to verify pass**

Run: `bb -e "(require 'nido.notion.client :reload)"` then `bb nido:test :only nido.notion.client-test`
Expected: PASS.

- [ ] **Step 6: Commit** — controller runs jj; implementer stops and reports.

---

### Task 2: owner→id map + `apply!` Notion writeback

**Files:**
- Modify: `src/nido/coordinator/report.clj` (add `owner->user-id` after the `Owner` def)
- Modify: `src/nido/work.clj` (`apply!` at 483–511; add private helpers just above it)
- Test: `test/nido/coordinator/report_test.clj`, `test/nido/work_test.clj`

**Interfaces:**
- Consumes: `notion/delete-block!`, `notion/prepend-block-children!` (Task 1); `notion/retrieve-page`, `notion/update-page-properties!`, `notion/retrieve-block-children`, `notion/keychain-token` (existing); `wsv/notion-ref`, `wsv/ledger-ref`, `latest-report`, `tickets/complete!` (existing).
- Produces: `report/owner->user-id` (map). `apply!` unchanged signature `[project ws-id]`; new decisions `{:decision :notion-failed :error <kw>}` and `{:decision :applied :callout :warn}`.

- [ ] **Step 1: Write the failing tests (report_test)**

Add to `test/nido/coordinator/report_test.clj`:

```clojure
(deftest owner->user-id-covers-the-owner-enum
  (doseq [o [:ataberk :eric :jaap]]
    (is (string? (report/owner->user-id o)) (str o " maps to a user-id"))
    (is (not (clojure.string/blank? (report/owner->user-id o)))))
  (is (= "955b4c25-7bce-4ca2-ab5e-d99acbcd423a" (report/owner->user-id :eric))))
```

- [ ] **Step 2: Add the map**

In `src/nido/coordinator/report.clj`, immediately after the `Owner` def, add:

```clojure
(def owner->user-id
  "Semantic triage owner → Notion Ball Holder user-id. Single source; the skill emits
   the keyword and nido resolves it at apply time."
  {:ataberk "3eb98667-d12e-4e9e-9342-48fec803b571"
   :eric    "955b4c25-7bce-4ca2-ab5e-d99acbcd423a"
   :jaap    "169d872b-594c-8160-b432-000250f98e86"})
```

- [ ] **Step 3: Write the failing tests (work_test)**

Add to `test/nido/work_test.clj`. Fixtures + tests:

```clojure
(defn- routed-report-edn
  "TriageReport EDN string for a routed triage. `depth` :shallow ⇒ notion-writes nil."
  [{:keys [owner app-domain depth]}]
  (pr-str
   (cond-> {:format :triage-report :ticket-key "BR-77" :determination :bug
            :title "t" :summary "s" :confidence {:level :high :reason "r"}
            :routing {:owner owner :app-domain app-domain :depth depth}
            :directions [] :notion-writes nil :trail []}
     (= depth :deep)
     (assoc :notion-writes {:type "bug" :effort :M
                            :status-transition ["Needs verification" "Not started"]
                            :title "enriched title"
                            :description-prepend "the enriched body"}))))

(defn- with-routed-ws [depth owner app-domain f]
  ;; a parked Notion triage ws whose latest ledger entry is a routed :triage-report
  (let [w (workstream/create! :brian {:stage :triaging
                                      :external-refs [{:adapter :notion :id "BR-77"
                                                       :page-id "pg-77" :title "t"}]})]
    (tickets/open! :brian "BR-77" {:title "t"})
    (tickets/set-status! :brian "BR-77" :awaiting-input)
    (workstream/append-to-ref! :brian "BR-77" {:kind :triage}
                               (routed-report-edn {:owner owner :app-domain app-domain :depth depth}))
    (f w)))

(deftest apply-routed-shallow-writes-ball-holder-and-additive-app-domain
  (with-tmp
    (fn [_]
      (with-routed-ws :shallow :eric "Backend"
        (fn [w]
          (let [props (atom nil)]
            (with-redefs [notion-client/keychain-token (fn [] "tok")
                          notion-client/retrieve-page
                          (fn [_ _] {:properties {(keyword "App Domain")
                                                  {:multi_select [{:name "Teacher"}]}}})
                          notion-client/update-page-properties!
                          (fn [_pg p _tok] (reset! props p) {:ok true})
                          nido.coordinator.facets/refresh-for-ticket! (fn [& _] nil)]
              (let [r (work/apply! :brian (:id w))]
                (is (= :applied (:decision r)))
                (is (= {:people [{:id "955b4c25-7bce-4ca2-ab5e-d99acbcd423a"}]}
                       (get @props "Ball Holder")))
                (is (= #{"Teacher" "Backend"}
                       (set (map :name (:multi_select (get @props "App Domain")))))
                    "App Domain unions the routed value with the page's current tags")
                (is (nil? (get @props "Status")) "shallow writes no Status")
                (is (nil? (get @props "Type")) "shallow writes no Type")
                (is (= :triaged (tickets/status :brian "BR-77")))))))))))

(deftest apply-routed-deep-writes-properties-and-prepends-callout
  (with-tmp
    (fn [_]
      (with-routed-ws :deep :jaap "Teacher"
        (fn [w]
          (let [props (atom nil) prepended (atom nil)]
            (with-redefs [notion-client/keychain-token (fn [] "tok")
                          notion-client/retrieve-page (fn [_ _] {:properties {}})
                          notion-client/update-page-properties!
                          (fn [_pg p _tok] (reset! props p) {:ok true})
                          notion-client/retrieve-block-children (fn [_ _ _] {:results []})
                          notion-client/prepend-block-children!
                          (fn [_pg children _tok] (reset! prepended children) {:ok true})
                          nido.coordinator.facets/refresh-for-ticket! (fn [& _] nil)]
              ;; make the placement verify pass: first child after prepend is our callout
              (with-redefs [notion-client/retrieve-block-children
                            (fn [_ _ _] {:results [{:type "callout"
                                                    :callout {:rich_text [{:text {:content "🤖 Enriched (triage BR-77)\nx"}}]}}]})]
                (let [r (work/apply! :brian (:id w))]
                  (is (= :applied (:decision r)))
                  (is (= {:name "Not started"} (:status (get @props "Status"))) "deep sets Status to the transition target")
                  (is (= {:name "bug"} (:select (get @props "Type"))))
                  (is (= {:name "M"} (:select (get @props "Effort"))))
                  (is (= "enriched title" (get-in @props ["Task result" :title 0 :text :content])))
                  (is (some? @prepended) "deep prepends a callout")
                  (is (= :triaged (tickets/status :brian "BR-77"))))))))))))

(deftest apply-routed-notion-failure-does-not-complete
  (with-tmp
    (fn [_]
      (with-routed-ws :shallow :eric "Backend"
        (fn [w]
          (with-redefs [notion-client/keychain-token (fn [] "tok")
                        notion-client/retrieve-page (fn [_ _] {:properties {}})
                        notion-client/update-page-properties! (fn [_ _ _] {:error :server})
                        nido.coordinator.facets/refresh-for-ticket! (fn [& _] nil)]
            (let [r (work/apply! :brian (:id w))]
              (is (= :notion-failed (:decision r)))
              (is (= :server (:error r)))
              (is (= :awaiting-input (tickets/status :brian "BR-77"))
                  "a failed Notion write leaves the ticket parked, NOT triaged"))))))))

(deftest apply-routed-callout-bottom-landing-warns-but-completes
  (with-tmp
    (fn [_]
      (with-routed-ws :deep :jaap "Teacher"
        (fn [w]
          (with-redefs [notion-client/keychain-token (fn [] "tok")
                        notion-client/retrieve-page (fn [_ _] {:properties {}})
                        notion-client/update-page-properties! (fn [_ _ _] {:ok true})
                        ;; first child never becomes our callout ⇒ position stripped
                        notion-client/retrieve-block-children (fn [_ _ _] {:results [{:type "paragraph"}]})
                        notion-client/prepend-block-children! (fn [_ _ _] {:ok true})
                        nido.coordinator.facets/refresh-for-ticket! (fn [& _] nil)]
            (let [r (work/apply! :brian (:id w))]
              (is (= :applied (:decision r)) "properties landed ⇒ still applied")
              (is (= :warn (:callout r)) "callout didn't land at the top ⇒ flagged")
              (is (= :triaged (tickets/status :brian "BR-77"))))))))))
```

Add these requires to `test/nido/work_test.clj`'s ns form if absent: `[nido.notion.client :as notion-client]` and ensure `nido.coordinator.facets` is reachable (it's referenced fully-qualified above).

- [ ] **Step 4: Run to verify they fail**

Run: `bb nido:test :only nido.coordinator.report-test` and `bb nido:test :only nido.work-test`
Expected: FAIL — `owner->user-id` unresolved; `apply!` doesn't yet branch on `:routing` (routed reports fall through to the legacy path, so Ball Holder assertions fail).

- [ ] **Step 5: Add the apply helpers + rewrite `apply!`**

In `src/nido/work.clj`, add these private helpers immediately above `apply!` (line 483). Confirm the ns already requires `[nido.notion.client :as notion]`, `[nido.coordinator.report :as report]`, and `[clojure.string :as str]` (it does — `report/report->markdown` and `notion/*` are already used).

```clojure
(defn- triage-notion-props
  "Notion :properties map for a routed :triage-report. Ball Holder replaces; App Domain
   unions `current-domains` (the page's existing multi_select names) with the routed one.
   Deep (`:notion-writes` present) adds Type/Effort/Status/Title; Effort is skipped when
   :squirrel (not a real select option)."
  [{:keys [routing notion-writes]} current-domains]
  (let [domains (->> (conj (vec current-domains) (:app-domain routing))
                     (remove nil?) distinct (mapv (fn [n] {:name n})))]
    (cond-> {"Ball Holder" {:people [{:id (report/owner->user-id (:owner routing))}]}
             "App Domain"  {:multi_select domains}}
      notion-writes
      (into (cond-> {}
              (:type notion-writes)
              (assoc "Type" {:select {:name (:type notion-writes)}})
              (and (:effort notion-writes) (not= :squirrel (:effort notion-writes)))
              (assoc "Effort" {:select {:name (name (:effort notion-writes))}})
              (:status-transition notion-writes)
              (assoc "Status" {:status {:name (second (:status-transition notion-writes))}})
              (:title notion-writes)
              (assoc "Task result" {:title [{:text {:content (:title notion-writes)}}]}))))))

(defn- our-callout?
  "True when `block` is our enriched callout carrying `marker`."
  [block marker]
  (and (= "callout" (:type block))
       (some #(str/includes? (or (get-in % [:text :content]) "") marker)
             (get-in block [:callout :rich_text]))))

(defn- prepend-enriched-callout!
  "Best-effort deep enrichment: delete our prior callout (idempotency), prepend a fresh
   one, verify it landed at the top. Returns :ok | :warn. Never throws."
  [page-id br desc token]
  (try
    (let [marker (str "🤖 Enriched (triage " br ")")
          block  {:object "block" :type "callout"
                  :callout {:icon {:type "emoji" :emoji "🤖"}
                            :rich_text [{:type "text" :text {:content (str marker "\n" desc)}}]}}
          first0 (-> (notion/retrieve-block-children page-id token {}) :results first)]
      (when (and first0 (our-callout? first0 marker))
        (notion/delete-block! (:id first0) token))
      (if (:error (notion/prepend-block-children! page-id [block] token))
        :warn
        (if (our-callout? (-> (notion/retrieve-block-children page-id token {}) :results first) marker)
          :ok :warn)))
    (catch Throwable _ :warn)))

(defn- apply-routed!
  "Execute a routed :triage-report's Notion writes, then complete the record. Property
   writes gate completion; the deep callout is best-effort. Returns {:decision :applied
   [:callout :warn]} on success, {:decision :notion-failed :error <kw>} otherwise."
  [project ws-id report w]
  (let [page-id (:page-id (wsv/notion-ref w))
        br      (:id (wsv/ledger-ref w))
        token   (notion/keychain-token)]
    (cond
      (nil? token)          {:decision :notion-failed :error :no-token}
      (str/blank? page-id)  {:decision :notion-failed :error :no-page-id}
      :else
      (let [current (keep :name (get-in (notion/retrieve-page page-id token)
                                        [:properties (keyword "App Domain") :multi_select]))
            res     (notion/update-page-properties! page-id (triage-notion-props report current) token)]
        (if (:error res)
          {:decision :notion-failed :error (:error res)}
          (let [callout (when-let [desc (get-in report [:notion-writes :description-prepend])]
                          (prepend-enriched-callout! page-id br desc token))]
            (when br
              (tickets/complete! project br :triaged :applied)
              (try (facets/refresh-for-ticket! project br) (catch Throwable _ nil)))
            (cond-> {:decision :applied}
              (= :warn callout) (assoc :callout :warn))))))))
```

Then rewrite `apply!` (lines 483–511 body) to a three-way `cond`, preserving the existing proposed-ticket and legacy branches verbatim:

```clojure
(defn apply!
  "Accept a parked triage verdict WITHOUT resuming the review conversation. Three paths:

   • Slack proposal (`:proposed-ticket`, no :notion ref yet) → create the Notion page
     (apply-proposed!). Returns {:decision :created …} | {:decision :error …}.
   • Routed Notion triage (`:triage-report` with :routing, on a :notion-backed ws) →
     execute the routing outcome to Notion (apply-routed!): Ball Holder + App Domain,
     deep properties, deep callout. Returns {:decision :applied [:callout :warn]} or
     {:decision :notion-failed :error <kw>} (ticket left parked to retry).
   • Legacy / Slack-triage (any other report, or a ref-less ws) → finalize the ticket
     :triaged/:applied nido-side only. Returns {:decision :applied}.

   The daemon's sweep settles the now-resolved parked session."
  [project ws-id]
  (if-let [w (cws/read-ws project ws-id)]
    (let [report (latest-report project ws-id)]
      (cond
        (and (= :proposed-ticket (:format report)) (nil? (wsv/notion-ref w)))
        (apply-proposed! project ws-id report w)

        (and (= :triage-report (:format report)) (:routing report) (wsv/notion-ref w))
        (apply-routed! project ws-id report w)

        :else
        (do
          (when-let [br (:id (wsv/ledger-ref w))]
            (tickets/complete! project br :triaged :applied)
            (try (facets/refresh-for-ticket! project br)
                 (catch Throwable _ nil)))
          {:decision :applied})))
    {:decision :applied}))
```

- [ ] **Step 6: Run to verify pass**

Run: `bb -e "(require 'nido.coordinator.report :reload) (require 'nido.work :reload)"` then `bb nido:test :only nido.coordinator.report-test` and `bb nido:test :only nido.work-test`
Expected: PASS. The existing `resolve-gate-apply-finalizes-the-ticket-nido-side` test stays green (its ws has no appended `:triage-report`, so `latest-report`'s fallback returns a non-`:triage-report` and `apply!` takes the legacy branch).

- [ ] **Step 7: Commit** — controller runs jj; implementer stops and reports.

---

### Task 3: `bb nido:ticket:apply` task

**Files:**
- Modify: `src/tasks/nido_ticket.clj` (add `apply-cmd`)
- Modify: `bb.edn` (declare `nido:ticket:apply` after `nido:ticket:complete` ~line 359)
- Test: `test/nido/tasks/nido_ticket_test.clj` (create if absent, else add there)

**Interfaces:**
- Consumes: `work/apply!` (Task 2); `workstream/find-by-ref-id` (existing, returns the ws record or nil).
- Produces: `nido-ticket/apply-cmd`; the `bb nido:ticket:apply :project <p> :br <BR>` task the skill calls.

- [ ] **Step 1: Write the failing test**

Locate the ticket-task test (`grep -rl nido-ticket test/`). Add (adjust the require alias to the file's convention):

```clojure
(deftest apply-cmd-resolves-ws-and-calls-work-apply
  (let [calls (atom [])]
    (with-redefs [nido.coordinator.workstream/find-by-ref-id (fn [_ br] {:id (str "ws-" br)})
                  nido.work/apply! (fn [p id] (swap! calls conj [p id]) {:decision :applied})]
      (nido-ticket/apply-cmd ":project" "brian" ":br" "BR-5")
      (is (= [[:brian "ws-BR-5"]] @calls) "resolves the ws by BR and calls work/apply!"))))

(deftest apply-cmd-no-workstream-exits-nonzero
  (with-redefs [nido.coordinator.workstream/find-by-ref-id (fn [_ _] nil)
                nido.work/apply! (fn [& _] (throw (ex-info "should not be called" {})))]
    (is (thrown? Throwable (nido-ticket/apply-cmd ":project" "brian" ":br" "BR-none")))))
```

(If `apply-cmd` calls `System/exit` on the no-ws path, the second test's `thrown?` covers the exit-throw under the test runner; if the runner traps exit differently, assert on captured stderr instead — match the file's existing exit-test convention.)

- [ ] **Step 2: Run to verify it fails**

Run: `bb nido:test :only <the-ticket-task-test-ns>`
Expected: FAIL — `apply-cmd` unresolved.

- [ ] **Step 3: Add `apply-cmd`**

In `src/tasks/nido_ticket.clj`, add the require `[nido.work :as work]` and `[nido.coordinator.workstream :as workstream]` to the ns form (workstream is likely already required), then add:

```clojure
(defn apply-cmd
  "bb nido:ticket:apply :project <p> :br BR-#### — execute a parked triage's verdict via
   nido (work/apply!): reads the typed report from the ledger and writes Notion (Ball
   Holder + App Domain, deep properties + callout), then completes the record. Prints the
   decision; exits non-zero on :notion-failed or an unknown BR so the caller can retry."
  [& args]
  (let [[_ o] (task-args/split-args args)
        project (project-kw o)
        br      (str (:br o))]
    (if-let [w (workstream/find-by-ref-id project br)]
      (let [{:keys [decision error callout] :as r} (work/apply! project (:id w))]
        (println "apply" br "->" (name decision) (when callout (str "(callout " (name callout) ")")))
        (when (= :notion-failed decision)
          (binding [*out* *err*] (println "Notion write failed:" (some-> error name)))
          (System/exit 1)))
      (do (binding [*out* *err*] (println "no workstream for" br))
          (System/exit 1)))))
```

- [ ] **Step 4: Declare the task in `bb.edn`**

After the `nido:ticket:complete` block (~line 362), add:

```clojure
  nido:ticket:apply
  {:requires ([tasks.nido-ticket :as nido-ticket])
   :doc "Execute a parked triage verdict via nido (writes Notion from the typed report)."
   :task (apply nido-ticket/apply-cmd *command-line-args*)}
```

- [ ] **Step 5: Run to verify pass**

Run: `bb -e "(require 'tasks.nido-ticket :reload)"` then `bb nido:test :only <the-ticket-task-test-ns>`
Expected: PASS. Also sanity-check the task resolves: `bb tasks | grep ticket:apply`.

- [ ] **Step 6: Commit** — controller runs jj; implementer stops and reports.

---

### Task 4: Simplify the triage-bug skill's apply step

**Files:**
- Modify: `.claude/skills/triage-bug/SKILL.md`

**Interfaces:**
- Consumes: `bb nido:ticket:apply :project brian :br <BR>` (Task 3).
- Produces: no code. Documentation. Verify by re-reading for consistency.

Documentation task — no TDD. Read the whole file first.

- [ ] **Step 1: Replace Step 4's Notion writes with the nido task**

In `## Step 4 — Apply`, remove the raw Notion-write instructions — the "Routing write (both depths)" `notion api PATCH` block, the deep "Property update" `notion page set` block, and the "Description prepend" callout block (idempotency `notion block delete` + `notion api PATCH .../children`). Replace all of it with:

```markdown
On `apply`, nido executes the verdict from your typed report — you do not write Notion
directly:

    bb nido:ticket:apply :project brian :br <BR-####>

nido reads the latest `:triage-report` from the ledger and writes Notion itself: Ball
Holder (from `:routing :owner`) + App Domain (additive), and for a deep report the
Type/Effort/Status ("Needs verification" → "Not started")/Task-result title plus the
enriched-description callout (prepended, idempotently). It prints `apply <BR> -> applied`
on success; on `apply <BR> -> notion-failed` (or a non-zero exit) the ticket is left
parked — surface the error and retry. This is the ONLY apply path; there is no separate
`notion page set` / `notion api PATCH` / `notion block delete` step anymore.
```

Keep the Slack-run branch of Step 4 (ledger-only `bb nido:ticket:complete`) unchanged — a Slack run has `:routing nil` and no Notion writes.

- [ ] **Step 2: Remove the owner→id table from the Routing section**

In `## Routing (Notion runs)`, delete the user-id column / the "user-ids above are only for the Notion write" note — nido owns the ids now. Keep the area→owner→App-Domain routing table and the semantic `:owner` keyword (`:ataberk`/`:eric`/`:jaap`); the report still carries the keyword, and nido maps it. Update any "Ball Holder (user-id)" wording to just the owner name.

- [ ] **Step 3: Reconcile the hard-contract + property-reference notes**

Find the "hard contract" sentence (near the end of Step 4) enumerating permitted Notion-write commands, and any leading `> **Notion access — `notion` CLI**` note that lists `notion page set …` / callout PATCH as apply writes. Update them to state that on `apply` the only action is `bb nido:ticket:apply` (nido performs all Notion writes); the skill's own `notion` CLI use is now read-only (page/props/blocks fetch for investigation) plus the Slack-run `bb nido:ticket:complete`.

- [ ] **Step 4: Verify consistency**

Re-read the file. Confirm: (a) no instruction tells the agent to `notion page set` / `notion api PATCH` / `notion block delete` on apply; (b) `apply` for a Notion run is `bb nido:ticket:apply`; (c) the owner→id table is gone but the semantic `:owner` vocabulary (`:ataberk`/`:eric`/`:jaap`) and the routing/depth logic are intact; (d) the Slack ledger-only path is unchanged. Fix any drift inline.

- [ ] **Step 5: Commit** — controller runs jj; implementer stops and reports.

---

## Self-Review

**1. Spec coverage:**
- §1 nido writes routing outcome → Task 2 (`apply-routed!` + `triage-notion-props`), owner→id map in report.clj. ✅
- §2 callout ported (kept) → Task 1 (client fns) + Task 2 (`prepend-enriched-callout!` idempotency/verify/warn). ✅
- §3 `bb nido:ticket:apply` + skill Step 4 → Tasks 3 & 4. ✅
- Failure gate (property fail ⇒ `:notion-failed`, no complete) → Task 2 tests `apply-routed-notion-failure-does-not-complete`. ✅
- Callout best-effort ⇒ `:applied :callout :warn` → Task 2 test `apply-routed-callout-bottom-landing-warns-but-completes`. ✅
- Non-goal (concurrency re-check) → not built. ✅

**2. Placeholder scan:** No TBD/TODO; every code step carries complete code; skill steps carry exact replacement prose. Task 3's test notes an exit-convention fallback rather than leaving it vague. ✅

**3. Type consistency:** `owner->user-id` keys `:ataberk/:eric/:jaap` match the `Owner` enum and the routing arc's schema. `triage-notion-props` reads `:routing`/`:notion-writes` exactly as the landed `TriageReport` defines them (`:app-domain` string, `:effort` `TriageEffort` incl. `:squirrel`, `:status-transition` `[from to]`, `:title`, `:description-prepend`). `apply!` decisions (`:applied`/`:notion-failed`/`:created`/`:callout :warn`) are consumed by Task 3's `apply-cmd` on the exact keys it prints/branches on. Property names ("Ball Holder", "App Domain", "Type", "Effort", "Status", "Task result") match the live Task DB schema and `notify.clj`. ✅

## Notes for the controller (VCS)
- Root repo is jj colocated; base is the current unlanded triage-routing stack (main..@-, `0b825a8d` HEAD). These 4 tasks stack ON TOP of it. Planning docs stay in an undescribed `@` and MUST NOT land — split each task's source paths with `jj split -m "…" <paths>`.
- Test task is `bb nido:test`; implementers never run jj/git.
- Land the whole combined stack (routing arc + apply unification) together, only when the user asks: `jj bookmark set main -r @-`, then push, then restart the daemon.
