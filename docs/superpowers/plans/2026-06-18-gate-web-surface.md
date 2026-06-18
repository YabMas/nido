# Gate Web Surface Implementation Plan (Plan 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Re-envision the nido web dashboard as the second projection over `nido.work`: a cross-project **Gate Inbox** you act on, plus a reflect-only spine **Board** and a read-only **workstream detail** — all thin views over the gate facet shipped in Plan 1.

**Architecture:** Same stack as today — httpkit + hiccup2 + Datastar SSE, in-process in the coordinator at `:8800`. Data comes ONLY from `nido.work` (`all-gates`/`gate`/`resolve-gate!`/`grouped`/`workstream`/`open-target`). The home `/` becomes the Gate Inbox (master-detail); the old flat session board relocates to `/system`. Reports render via a tiny self-contained markdown→hiccup converter (no dependency — babashka bundles hiccup/httpkit/cheshire but NOT a markdown lib). VSDD views are untouched.

**Tech Stack:** Babashka/Clojure, `hiccup2.core`, `org.httpkit.server`, `cheshire.core`, Datastar v1 (CDN). `clojure.test`. jujutsu (jj). Tests: `bb nido:test :only nido.ui` (and focused `:only nido.ui.markdown`).

**This plan is the surface for** `docs/superpowers/specs/2026-06-18-gate-driven-web-companion-design.md`, building on the Plan 1 gate core (`nido.work` `gates`/`all-gates`/`gate`/`gate-actions`/`resolve-gate!`; `nido.coordinator.resume/resume!`).

---

## Facet shapes this plan consumes (from Plan 1, verified)

```clojure
;; (work/all-gates) => [gate ...]   (work/gate project ws-id) => gate | nil
gate   {:ws-id "ws-…" :project "brian" :origin :notion|:github|:slack|:scratch
        :stage :triage|:ready|:in-progress :label "BR-7 · t"
        :report {:kind :triage :at "…Z" :title "Verdict" :markdown "# …"} | nil
        :actions [{:id :promote|:skip|:drop|:done|:reply :label "Promote" :kind :mutation|:reply} …]
        :session "auto" | nil}     ; the parked session a :reply resumes
;; (work/resolve-gate! project ws-id action-id input?) -> result map (mutations return {:decision …}; :reply {:resumed name})
;; (work/grouped project) -> {:triage {:in-flight [row…] :queued [row…]} :ready [row…] :in-progress [row…]}   (rows carry :ws-id :origin :stage :label :needs-you)
;; (work/workstream project ws-id) -> {:ws-id :project :origin :stage :label :ledger {:key :status :report-count} :sessions [{:name :autonomy-level :parked? :status :brakes} …]} | nil
;; (work/open-target project ws-id) -> {:project :session} | nil
```

`origin` → badge: `:notion "N"`, `:github "G"`, `:slack "S"`, `:scratch "·"` (mirror the TUI's `origin-badge` in `src/nido/tui.clj`).

## Commit hygiene (read before Task 1)

This repo uses **jujutsu (jj)**. The working copy `@` holds **uncommitted planning docs** (`docs/superpowers/**`) + an untracked `resources/nido-icon.png` (1.5 MB, jj refuses to snapshot it — that's expected, leave it). Before starting:

```bash
jj log -r '@' --no-graph -T 'change_id.shortest(8) ++ "\n"'   # note the docs-bearing change
jj new                                                         # clean code changeset on top
```

Each task ends with its own `jj commit -m "…"`. After each, verify no planning artifacts or the icon got swept in:

```bash
jj show -s @-   # must list ONLY the task's src/test files — never docs/** or resources/nido-icon.png
```

If a commit ever shows `docs/**` or `resources/nido-icon.png`, `jj squash`/`jj restore` it back out before continuing.

## File Structure

- **`src/nido/ui/markdown.clj`** (create) — `render` : a tiny markdown string → hiccup vector (headings, paragraphs, `-`/`*` bullet lists, inline `` `code` `` + `**bold**`). One responsibility; ~60 lines.
- **`src/nido/ui/views.clj`** (modify) — add gate styles to `layout`; add `origin-badge`, `gate-card`, `gate-inbox-fragment`, `gate-pane`, `gate-inbox-page`, `board-fragment`/`board-page`, `ws-detail-page`. Keep all existing session/vsdd views.
- **`src/nido/ui/server.clj`** (modify) — add `gate`/board/detail routes + `POST /gate/:project/:ws-id/:action`; relocate the old `/` board to `/system`; add `gate-resolve!` (optimistic-state-tracked, like `run-action!`) + `session-url`/`workstream-live-url` helpers.
- **Tests:** `test/nido/ui/markdown_test.clj` (create); extend `test/nido/ui/views_test.clj` + `test/nido/ui/server_test.clj`.

---

# Phase 1 — Gate Inbox (read-only) + markdown

### Task 1: Minimal markdown renderer (`nido.ui.markdown`)

**Files:**
- Create: `src/nido/ui/markdown.clj`
- Test: `test/nido/ui/markdown_test.clj`

- [ ] **Step 1: Write the failing test**

`test/nido/ui/markdown_test.clj`:

```clojure
(ns nido.ui.markdown-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [nido.ui.markdown :as md]))

(defn- html [s] (str (h/html (md/render s))))

(deftest renders-headings-paragraphs-lists
  (let [out (html "# Verdict\n\nbug — reproduced.\n\n- one\n- two")]
    (is (str/includes? out "<h3") "a # heading renders as an h-element")
    (is (str/includes? out "Verdict"))
    (is (str/includes? out "bug — reproduced."))
    (is (str/includes? out "<ul"))
    (is (str/includes? out "<li>one</li>"))))

(deftest renders-inline-code-and-bold
  (let [out (html "fix `cart/line-total` and **only** that")]
    (is (str/includes? out "<code>cart/line-total</code>"))
    (is (str/includes? out "<strong>only</strong>"))))

(deftest escapes-html-in-text
  ;; hiccup2 escapes strings by default; markup we emit as raw must stay safe.
  (let [out (html "a <script>x</script> & b")]
    (is (not (str/includes? out "<script>")) "raw HTML in the report is neutralized")
    (is (str/includes? out "&lt;script&gt;"))))

(deftest blank-input-is-empty
  (is (= "" (html nil)))
  (is (= "" (html ""))))
```

- [ ] **Step 2: Run to verify it fails**

Run: `bb nido:test :only nido.ui.markdown`
Expected: FAIL — namespace `nido.ui.markdown` does not exist.

- [ ] **Step 3: Implement the renderer**

`src/nido/ui/markdown.clj`:

```clojure
(ns nido.ui.markdown
  "A deliberately tiny markdown -> hiccup renderer for agent-written gate reports.
   Handles only what the reports use: # headings, blank-line paragraphs, -/* bullet
   lists, inline `code` and **bold**. Everything else passes through as plain text.
   Text is returned as plain strings so hiccup2 escapes it (raw HTML in a report is
   neutralized). NOT a general markdown engine — by design (see spec §Rendering)."
  (:require [clojure.string :as str]))

(defn- inline-nodes
  "Split one line into hiccup nodes, rendering `code` and **bold**; plain runs stay
   strings (hiccup2 escapes them)."
  [line]
  (let [pat #"`[^`]*`|\*\*[^*]*\*\*"
        toks (re-seq pat line)
        plains (str/split line pat -1)]
    ;; interleave plains and toks: plains[0] tok[0] plains[1] tok[1] ...
    (loop [ps plains, ts toks, acc []]
      (let [acc (if (seq (first ps)) (conj acc (first ps)) acc)]
        (if (seq ts)
          (let [t (first ts)
                node (cond
                       (str/starts-with? t "`")  [:code (subs t 1 (dec (count t)))]
                       (str/starts-with? t "**") [:strong (subs t 2 (- (count t) 2))]
                       :else t)]
            (recur (rest ps) (rest ts) (conj acc node)))
          acc)))))

(defn- heading-level [line]
  (count (re-find #"^#+" line)))

(defn render
  "Markdown string -> a hiccup [:div …] of block nodes. nil/blank -> \"\"."
  [s]
  (if (str/blank? s)
    ""
    (let [lines (str/split-lines s)]
      (loop [ls lines, blocks [], bullets nil]
        (let [flush (fn [bs] (if (seq bs) (conj blocks (into [:ul] bs)) blocks))]
          (if (empty? ls)
            (into [:div.md] (flush bullets))
            (let [line (first ls)]
              (cond
                (str/blank? line)
                (recur (rest ls) (flush bullets) nil)

                (re-find #"^#{1,6}\s+" line)
                (let [lvl (min 6 (max 3 (+ 2 (heading-level line)))) ; #→h3, ##→h4 … (dashboard scale)
                      txt (str/replace line #"^#{1,6}\s+" "")]
                  (recur (rest ls) (conj (flush bullets) (into [(keyword (str "h" lvl))] (inline-nodes txt))) nil))

                (re-find #"^\s*[-*]\s+" line)
                (let [txt (str/replace line #"^\s*[-*]\s+" "")]
                  (recur (rest ls) blocks (conj (or bullets []) (into [:li] (inline-nodes txt)))))

                :else
                (recur (rest ls) (conj (flush bullets) (into [:p] (inline-nodes line))) nil)))))))))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bb nido:test :only nido.ui.markdown`
Expected: PASS (4 tests). If the heading element assertion needs tuning, the mapping is `#`→`h3` (dashboard headings are small); the test asserts `<h3`.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(ui): tiny markdown->hiccup renderer for gate reports"
jj show -s @-
```

---

### Task 2: Gate views — card, inbox fragment, pane (read-only)

**Files:**
- Modify: `src/nido/ui/views.clj` (add gate CSS to `layout`; add `origin-badge`, `gate-card`, `gate-inbox-fragment`, `gate-pane`)
- Test: `test/nido/ui/views_test.clj` (append)

- [ ] **Step 1: Write the failing test**

Append to `test/nido/ui/views_test.clj`:

```clojure
(def ^:private sample-gate
  {:ws-id "ws-1" :project "brian" :origin :notion :stage :triage
   :label "BR-7 · checkout off by a cent"
   :report {:kind :triage :at "2026-06-18T00:00:00Z" :title "Verdict"
            :markdown "# Verdict\n\nbug — reproduced."}
   :actions [{:id :skip :label "Skip" :kind :mutation}
             {:id :reply :label "Reply" :kind :reply}]
   :session "auto"})

(deftest gate-inbox-fragment-lists-cards
  (let [html (views/gate-inbox-fragment [sample-gate] nil)]
    (is (str/includes? html "id=\"gate-inbox\""))
    (is (str/includes? html "BR-7"))
    (is (str/includes? html ">N<") "origin badge")
    (is (str/includes? html "brian"))
    (is (str/includes? html "/gate/brian/ws-1") "card links to the gate pane")))

(deftest gate-inbox-fragment-empty-state
  (is (str/includes? (views/gate-inbox-fragment [] nil) "No gates")))

(deftest gate-pane-renders-report-and-actions
  (let [html (views/gate-pane sample-gate)]
    ;; report rendered (markdown -> heading)
    (is (str/includes? html "Verdict"))
    (is (str/includes? html "bug — reproduced."))
    ;; a :mutation action is a button POSTing the resolve route
    (is (str/includes? html "/gate/brian/ws-1/skip"))
    ;; a :reply action has a textarea + resume post
    (is (str/includes? html "/gate/brian/ws-1/reply"))
    (is (str/includes? html "<textarea"))))

(deftest gate-pane-empty-when-nil
  (is (str/includes? (views/gate-pane nil) "Select a gate")))
```

- [ ] **Step 2: Run to verify it fails**

Run: `bb nido:test :only nido.ui.views`
Expected: FAIL — `gate-inbox-fragment`/`gate-pane` undefined.

- [ ] **Step 3: Implement the views**

In `src/nido/ui/views.clj`:

(a) Add to the `<style>` string inside `layout` (append before the closing `"`):

```
        .badge { display:inline-flex; align-items:center; justify-content:center;
                 width:18px; height:18px; border-radius:4px; font-size:11px; font-weight:bold; }
        .b-notion{ background:#2a3a4a; color:#aee0ff; } .b-github{ background:#2a2a1a; color:#facc15; }
        .b-slack{ background:#3a2a3a; color:#e0a0e0; } .b-scratch{ background:#252540; color:#9a9ac0; }
        .gate-wrap { display:grid; grid-template-columns: 38% 62%; min-height:80vh; }
        .inbox { border-right:1px solid #2a2a4a; overflow:auto; }
        .gate-card { display:block; padding:10px 16px; border-bottom:1px solid #20203a; color:inherit; }
        .gate-card:hover { background:#181830; text-decoration:none; }
        .gate-card.sel { background:#16213e; border-left:3px solid #7eb8da; padding-left:13px; }
        .gate-top { display:flex; align-items:center; gap:8px; }
        .gate-top .lbl { flex:1; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; color:#e8e8e8; }
        .needs { width:7px; height:7px; border-radius:50%; background:#facc15; box-shadow:0 0 6px #facc15; }
        .gate-sub { display:flex; gap:8px; color:#666; font-size:11px; margin:3px 0 0 26px; }
        .gate-prev { color:#7a7a98; font-size:11.5px; margin:5px 0 0 26px;
                     white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
        .chip { padding:0 7px; border-radius:3px; font-size:10.5px; text-transform:uppercase; }
        .c-triage{ background:#2a2a1a; color:#facc15; } .c-ready{ background:#1a3a2a; color:#4ade80; }
        .c-in-progress{ background:#1a2a3a; color:#7eb8da; }
        .pane { padding:18px 24px; overflow:auto; }
        .md { font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',system-ui,sans-serif;
              font-size:13.5px; line-height:1.7; color:#cdcde0; background:#0f0f1e;
              border:1px solid #2a2a4a; border-radius:6px; padding:16px 18px; }
        .md code { background:#1c1c33; padding:1px 5px; border-radius:3px; color:#aee0ff; }
        .reply { margin-top:16px; border:1px solid #2a2a4a; border-radius:6px; background:#13132a; padding:12px 14px; }
        .reply textarea { width:100%; min-height:62px; background:#0f0f1e; border:1px solid #2a2a4a;
                          border-radius:4px; color:#e0e0e0; font:inherit; font-size:13px; padding:9px 11px; }
```

(b) Add these functions (after the existing components, before the Pages section):

```clojure
(defn origin-badge [origin]
  (let [[ch cls] (case origin
                   :notion ["N" "b-notion"] :github ["G" "b-github"]
                   :slack ["S" "b-slack"]   ["·" "b-scratch"])]
    [:span {:class (str "badge " cls)} ch]))

(defn- chip [stage]
  [:span {:class (str "chip c-" (name stage))} (name stage)])

(defn- gate-card
  "One inbox row; links to the gate pane. `sel?` highlights the open gate."
  [{:keys [ws-id project origin stage label report session]} sel?]
  [:a {:class (str "gate-card" (when sel? " sel"))
       :href  (str "/gate/" project "/" ws-id)}
   [:div.gate-top (origin-badge origin) [:span.lbl label] [:span.needs {:title "needs you"}]]
   [:div.gate-sub [:span project] (chip stage)
    [:span (if session (str "parked · " session) "decide")]]
   [:div.gate-prev (or (some-> report :markdown
                               (clojure.string/replace #"^#.*\n+" "")
                               clojure.string/split-lines first)
                       "—")]])

(defn gate-inbox-fragment
  "The inbox column body — initial render + SSE refresh. `sel` is the open ws-id."
  [gates sel]
  (str
   (h/html
    (if (seq gates)
      [:div {:id "gate-inbox"}
       (for [g gates] (gate-card g (= sel (:ws-id g))))]
      [:div {:id "gate-inbox"} [:p.empty "No gates — nothing needs you right now."]]))))

(defn gate-pane
  "The detail pane: rendered report + follow-actions. nil -> placeholder."
  [{:keys [ws-id project origin stage label report actions session] :as gate}]
  (str
   (h/html
    (if-not gate
      [:div {:id "gate-pane"} [:p.empty "Select a gate."]]
      [:div {:id "gate-pane"}
       [:div.breadcrumb project " / " (name stage)]
       [:h1 (origin-badge origin) " " label]
       (when report
         [:div.meta (some-> report :kind name) " · " (:at report)])
       ;; md/render already returns a [:div.md …]; embed it directly (no double wrap)
       (md/render (:markdown report))
       [:div.actions {:style "margin-top:16px"}
        (for [{:keys [id label kind]} actions
              :when (= kind :mutation)]
          [:button.btn {:class (if (#{:skip :drop} id) "btn-danger" "btn-primary")
                        "data-on:click" (str "@post('/gate/" project "/" ws-id "/" (name id) "')")}
           label])]
       (when (some #(= :reply (:kind %)) actions)
         [:div.reply
          [:div.meta {:style "text-transform:uppercase;font-size:11px"} "Reply & resume"]
          [:textarea {"data-bind-reply" true
                      :placeholder "Tell the agent what to do next…"}]
          [:div {:style "margin-top:9px"}
           [:button.btn.btn-primary
            {"data-on:click" (str "@post('/gate/" project "/" ws-id "/reply')")}
            "Send & resume ▸"]
           (when session [:span.meta {:style "margin-left:10px"} "resumes " session])]])]))))
```

> Add `[nido.ui.markdown :as md]` to the `views` ns `:require`. `clojure.string` is already required (alias `str`); use the alias instead of the fully-qualified calls shown — i.e. `(str/replace …)` / `(str/split-lines …)`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `bb nido:test :only nido.ui.views`
Expected: PASS (existing + 4 new). Adjust the empty-state strings if the assertions disagree (keep them: "No gates", "Select a gate").

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(ui): gate inbox card + pane views (read-only)"
jj show -s @-
```

---

### Task 3: Inbox routes + relocate the old session board

**Files:**
- Modify: `src/nido/ui/server.clj` (new `gate-inbox-page` route at `/`; `/_fragment/gates`; `/gate/:project/:ws-id` pane; relocate the old `live-board-page` to `/system`)
- Modify: `src/nido/ui/views.clj` (add `gate-inbox-page` full-page wrapper)
- Test: `test/nido/ui/server_test.clj` (append), `test/nido/ui/views_test.clj` (append)

- [ ] **Step 1: Write the failing tests**

Append to `test/nido/ui/views_test.clj`:

```clojure
(deftest gate-inbox-page-has-master-detail-and-poll
  (let [html (views/gate-inbox-page [sample-gate] sample-gate)]
    (is (str/includes? html "gate-wrap"))
    (is (str/includes? html "/_fragment/gates") "polls the inbox fragment")
    (is (str/includes? html "BR-7"))))
```

Append to `test/nido/ui/server_test.clj`:

```clojure
(deftest home-route-renders-gate-inbox
  (with-redefs [nido.work/all-gates (fn [] [])]
    (let [resp (server/handle-request {:request-method :get :uri "/"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "gate-wrap")))))

(deftest gates-fragment-route-is-sse
  (with-redefs [nido.work/all-gates (fn [] [])]
    (let [resp (server/handle-request {:request-method :get :uri "/_fragment/gates"})]
      (is (str/includes? (get-in resp [:headers "Content-Type"]) "text/event-stream")))))

(deftest gate-pane-route-renders
  (with-redefs [nido.work/all-gates (fn [] [{:ws-id "ws-1" :project "brian" :origin :notion
                                             :stage :triage :label "BR-7" :report nil
                                             :actions [] :session nil}])
                nido.work/gate (fn [_ _] {:ws-id "ws-1" :project "brian" :origin :notion
                                          :stage :triage :label "BR-7" :report nil
                                          :actions [] :session nil})]
    (let [resp (server/handle-request {:request-method :get :uri "/gate/brian/ws-1"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "BR-7")))))

(deftest system-route-still-serves-the-session-board
  (with-redefs [server/all-session-rows (fn [] [])]
    (let [resp (server/handle-request {:request-method :get :uri "/system"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "live sessions")))))
```

- [ ] **Step 2: Run to verify it fails**

Run: `bb nido:test :only nido.ui`
Expected: FAIL — `gate-inbox-page` undefined; `/` still renders the session board; `/system` 404s.

- [ ] **Step 3: Implement**

(a) In `src/nido/ui/views.clj`, add the page wrapper:

```clojure
(defn gate-inbox-page
  "Dashboard home: cross-project Gate Inbox (master-detail). `sel` is the open
   gate map (or nil). The inbox column polls; the pane is server-rendered for the
   selected gate (deep-linked via /gate/:project/:ws-id)."
  [gates sel]
  (layout
   "gates"
   [:h1 "nido — gates"]
   [:p.meta [:a {:href "/board"} "board →"] " · " [:a {:href "/system"} "system →"]]
   [:div.gate-wrap
    [:div.inbox {:data-on-interval__duration.3s "@get('/_fragment/gates')"}
     (h/raw (gate-inbox-fragment gates (:ws-id sel)))]
    [:div.pane (h/raw (gate-pane sel))]]))
```

(b) In `src/nido/ui/server.clj`, require `[nido.work :as work]`, then in `handle-get`'s `case segments` (the top-level routes), change the home and add the gate routes. Replace the `[]` and add cases:

```clojure
      ;; GET / — cross-project Gate Inbox (dashboard home)
      []
      (html-response 200 (views/gate-inbox-page (work/all-gates) nil))

      ;; GET /system — the old flat live-sessions board (relocated)
      ["system"]
      (html-response 200 (views/live-board-page (all-session-rows)))

      ;; GET /_fragment/gates — SSE inbox refresh
      ["_fragment" "gates"]
      (sse-response (sse-fragment (views/gate-inbox-fragment (work/all-gates) nil)))

      ;; GET /board — spine board (Task 5 fills this in; stub for now)
      ;; (leave to Task 5)
```

And add a `/gate/:project/:ws-id` branch in the structural dispatch (the `(let [project-name (first segments)] …)` fallback). The cleanest is a dedicated check BEFORE the project-context block:

```clojure
      ;; GET /gate/:project/:ws-id — inbox with that gate selected in the pane
      (let [segs segments]
        (if (and (= 3 (count segs)) (= "gate" (first segs)))
          (let [project (nth segs 1), ws-id (nth segs 2)
                sel     (work/gate project ws-id)]
            (html-response 200 (views/gate-inbox-page (work/all-gates) sel)))
          ;; … existing project-context dispatch unchanged …
          ))
```

> Keep the existing `["projects"]` route and the whole project-context block (sessions/logs/vsdd) intact — you are ADDING routes and moving the `[]` board to `["system"]`, not deleting the session/vsdd surface. The old `live-board-fragment` `/_fragment/live` route stays (it now backs `/system`).

- [ ] **Step 4: Run tests to verify they pass**

Run: `bb nido:test :only nido.ui`
Expected: PASS. Confirm `home-route-renders-gate-inbox`, `gate-pane-route-renders`, `system-route-still-serves-the-session-board` all green and no existing server/views test regressed.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(ui): gate inbox home + /gate pane route; relocate session board to /system"
jj show -s @-
```

---

# Phase 2 — Gate resolution (act on gates)

### Task 4: `POST /gate/:project/:ws-id/:action` → resolve, with optimistic state

**Files:**
- Modify: `src/nido/ui/server.clj` (add `gate-resolve!` + the POST branch + `parse-json-body`)
- Test: `test/nido/ui/server_test.clj` (append)

- [ ] **Step 1: Write the failing test**

Append to `test/nido/ui/server_test.clj`:

```clojure
(deftest post-gate-mutation-calls-resolve-and-returns-sse
  (let [calls (atom [])]
    (with-redefs [nido.work/resolve-gate! (fn [p w a & [in]] (swap! calls conj [p w a in]) {:decision :dropped})
                  nido.work/all-gates    (fn [] [])]
      (let [resp (server/handle-request {:request-method :post :uri "/gate/brian/ws-1/skip"})]
        ;; resolve dispatched on a background future — wait briefly for it
        (Thread/sleep 50)
        (is (= [["brian" "ws-1" :skip nil]] @calls))
        (is (str/includes? (get-in resp [:headers "Content-Type"]) "text/event-stream"))))))

(deftest post-gate-reply-passes-input-from-body
  (let [calls (atom [])]
    (with-redefs [nido.work/resolve-gate! (fn [p w a & [in]] (swap! calls conj [p w a in]) {:resumed "auto"})
                  nido.work/all-gates    (fn [] [])]
      (let [body (java.io.ByteArrayInputStream. (.getBytes "{\"reply\":\"do the fix\"}"))
            resp (server/handle-request {:request-method :post :uri "/gate/brian/ws-1/reply" :body body})]
        (Thread/sleep 50)
        (is (= [["brian" "ws-1" :reply "do the fix"]] @calls))))))
```

- [ ] **Step 2: Run to verify it fails**

Run: `bb nido:test :only nido.ui.server`
Expected: FAIL — the POST `/gate/...` route 404s (no handler).

- [ ] **Step 3: Implement**

In `src/nido/ui/server.clj` add (near `run-action!`):

```clojure
(defn- parse-json-body
  "Read a Datastar JSON signal body into a map, or {} when absent/unparseable."
  [body]
  (try
    (if body (json/parse-string (slurp body) true) {})
    (catch Exception _ {})))

(defn- gate-resolve!
  "Run work/resolve-gate! on a background thread, tracking optimistic state per
   (project,ws-id) so the inbox/pane reflect 'working…' until it settles. Mirrors
   run-action!'s app-states pattern."
  [project ws-id action-id input]
  (let [k (str project "/" ws-id)]
    (set-app-state! k (if (= :reply action-id) :resuming :resolving))
    (future
      (try
        (work/resolve-gate! project ws-id action-id input)
        (clear-app-state! k)
        (catch Exception e
          (set-app-state! k :failed (or (:reason (ex-data e)) (ex-message e))))))))
```

Add `(require '[cheshire.core :as json])` to the ns `:require` if not present (server.clj already uses SSE; confirm `json` alias). Then in `handle-post`, add a branch BEFORE the existing sessions branch:

```clojure
(defn- handle-post [{:keys [uri body] :as _req}]
  (let [segs (parse-path uri)]
    (cond
      ;; POST /gate/:project/:ws-id/:action — resolve a gate follow-action
      (and (= 4 (count segs)) (= "gate" (first segs)))
      (let [project   (nth segs 1)
            ws-id     (nth segs 2)
            action-id (keyword (nth segs 3))
            input     (when (= :reply action-id) (:reply (parse-json-body body)))]
        (gate-resolve! project ws-id action-id input)
        (sse-response (sse-fragment (views/gate-inbox-fragment (work/all-gates) ws-id))))

      ;; … the existing (sessions …) branch, unchanged …
      :else
      (html-response 404 (views/not-found-page)))))
```

> Convert the existing `if`-based `handle-post` to the `cond` shown, preserving the current sessions handling as a later clause. Keep behavior identical for the sessions path.

- [ ] **Step 4: Run tests to verify they pass**

Run: `bb nido:test :only nido.ui.server`
Expected: PASS (mutation + reply tests + existing). The `Thread/sleep 50` lets the background future run; resolve is mocked so it's instant.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(ui): POST /gate resolve route (mutations + reply) with optimistic state"
jj show -s @-
```

---

# Phase 3 — Spine Board (reflect) + route-in

### Task 5: Board view + route + route-in helper

**Files:**
- Modify: `src/nido/ui/views.clj` (add `board-fragment`, `board-page`)
- Modify: `src/nido/ui/server.clj` (add `/board`, `/_fragment/board` routes; `workstream-live-url` helper)
- Test: `test/nido/ui/views_test.clj` + `test/nido/ui/server_test.clj` (append)

- [ ] **Step 1: Write the failing tests**

Append to `test/nido/ui/views_test.clj`:

```clojure
(def ^:private sample-grouped
  {:triage {:in-flight [{:ws-id "w1" :origin :notion :stage :triage :label "BR-1 · a" :needs-you true}]
            :queued []}
   :ready [{:ws-id "w2" :origin :github :stage :ready :label "#2 · b" :needs-you true}]
   :in-progress [{:ws-id "w3" :origin :scratch :stage :in-progress :label "spike" :needs-you false}]})

(deftest board-fragment-groups-by-stage-with-badges
  ;; board-fragment takes a seq of {:project :grouped} so it can thread the project
  ;; into each row's /ws/<project>/<ws-id> link (grouped rows carry no :project).
  (let [html (views/board-fragment [{:project "brian" :grouped sample-grouped}])]
    (is (str/includes? html "triage"))
    (is (str/includes? html "ready"))
    (is (str/includes? html "in-progress"))
    (is (str/includes? html "BR-1 · a"))
    (is (str/includes? html "/ws/brian/w1") "rows link to workstream detail")
    (is (str/includes? html ">N<"))))
```

Append to `test/nido/ui/server_test.clj`:

```clojure
(deftest board-route-renders
  (with-redefs [nido.work/grouped (fn [_] {:triage {:in-flight [] :queued []} :ready [] :in-progress []})
                project/list-projects (fn [] {"brian" {:directory "/x"}})]
    (let [resp (server/handle-request {:request-method :get :uri "/board"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "board")))))
```

- [ ] **Step 2: Run to verify it fails**

Run: `bb nido:test :only nido.ui`
Expected: FAIL — `board-fragment`/`board-page` undefined; `/board` 404s.

- [ ] **Step 3: Implement**

(a) In `src/nido/ui/views.clj` add (mirror the TUI's `board-rows`/stage grouping in `src/nido/tui.clj` for which stages show and their order — triage(in-flight+queued) → ready → in-progress):

```clojure
(defn- board-row [project {:keys [ws-id origin stage label needs-you]}]
  [:tr
   [:td (origin-badge origin)]
   [:td [:a {:href (str "/ws/" project "/" ws-id)} label]]
   [:td (when needs-you [:span.needs {:title "needs you"}])]])

(defn board-fragment
  "Stage-grouped reflect board across all projects. `groups` is a seq of
   {:project :grouped} (grouped = work/grouped output)."
  [groups]
  (str
   (h/html
    [:div {:id "board"}
     (for [{:keys [project grouped]} groups
           [stage rows] [[:triage (concat (-> grouped :triage :in-flight) (-> grouped :triage :queued))]
                         [:ready (:ready grouped)]
                         [:in-progress (:in-progress grouped)]]
           :when (seq rows)]
       [:div [:h3 (name stage) " — " project]
        [:table [:tbody (for [r rows] (board-row project r))]]])])))

(defn board-page [groups]
  (layout "board"
   [:h1 "nido — board"]
   [:p.meta [:a {:href "/"} "← gates"] " · " [:a {:href "/system"} "system →"]]
   [:div {:data-on-interval__duration.5s "@get('/_fragment/board')"}
    (h/raw (board-fragment groups))]))
```

(b) In `src/nido/ui/server.clj`, add a helper that builds per-project grouped data, and the routes:

```clojure
(defn all-grouped
  "[{:project :grouped} …] across registered projects (mirrors all-session-rows)."
  []
  (->> (project/list-projects)
       (keep (fn [[pname _]]
               (try {:project pname :grouped (work/grouped pname)}
                    (catch Throwable _ nil))))
       vec))
```

Add routes in `handle-get`'s top `case`:

```clojure
      ["board"]              (html-response 200 (views/board-page (all-grouped)))
      ["_fragment" "board"]  (sse-response (sse-fragment (views/board-fragment (all-grouped))))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bb nido:test :only nido.ui`
Expected: PASS. Fix the test's `/ws/_/w1` to match the project threaded by `all-grouped` (the test redefs a single project — assert `/ws/brian/w1` if you set project "brian").

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(ui): spine board view + /board route (reflect, origin badges)"
jj show -s @-
```

---

# Phase 4 — Workstream detail + final wiring

### Task 6: Workstream detail (read-only + route-in)

**Files:**
- Modify: `src/nido/ui/views.clj` (add `ws-detail-page`)
- Modify: `src/nido/ui/server.clj` (add `/ws/:project/:ws-id` route; `workstream-live-url` helper)
- Test: `test/nido/ui/views_test.clj` + `test/nido/ui/server_test.clj` (append)

> **Spec reconciliation:** the spec listed "start/stop lifecycle + logs" on the workstream detail. We keep those on the relocated **`/system`** board (which already has the start/stop/restart + log-tail UI, untouched) and make `/ws/...` detail **reflect + route-in only** (open the live session, see its sessions on the autonomy axis). This avoids duplicating the lifecycle UI in two places; the operational controls remain one click away via the "open session ↗" link and `/system`. If per-detail lifecycle is wanted later, it's an additive follow-up.

- [ ] **Step 1: Write the failing tests**

Append to `test/nido/ui/views_test.clj`:

```clojure
(def ^:private sample-ws
  {:ws-id "ws-1" :project "brian" :origin :notion :stage :triage :label "BR-7 · t"
   :ledger {:key "BR-7" :status :investigating :report-count 1}
   :sessions [{:name "auto" :autonomy-level :autonomous :parked? true :status :parked :brakes {:budget "30m"}}
              {:name "me"   :autonomy-level :interactive :parked? false :status :up :brakes nil}]})

(deftest ws-detail-renders-ledger-and-sessions
  (let [html (views/ws-detail-page sample-ws "http://auto.brian.localhost:3142")]
    (is (str/includes? html "BR-7"))
    (is (str/includes? html "investigating"))
    (is (str/includes? html "auto"))      ; autonomous session
    (is (str/includes? html "me"))        ; interactive session
    (is (str/includes? html "autonomous"))
    (is (str/includes? html "parked"))
    (is (str/includes? html "http://auto.brian.localhost:3142") "route-in link when a live url is known")))
```

Append to `test/nido/ui/server_test.clj`:

```clojure
(deftest ws-detail-route-renders
  (with-redefs [nido.work/workstream (fn [_ _] {:ws-id "ws-1" :project "brian" :origin :notion
                                                :stage :triage :label "BR-7" :ledger nil :sessions []})
                server/workstream-live-url (fn [_ _] nil)]
    (let [resp (server/handle-request {:request-method :get :uri "/ws/brian/ws-1"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "BR-7")))))
```

- [ ] **Step 2: Run to verify it fails**

Run: `bb nido:test :only nido.ui`
Expected: FAIL — `ws-detail-page` / `workstream-live-url` / route undefined.

- [ ] **Step 3: Implement**

(a) In `src/nido/ui/views.clj`:

```clojure
(defn ws-detail-page
  "Read-only workstream detail: origin · stage · ledger · sessions on the autonomy
   axis, plus a route-in link when a live session url is known. Mutations live on
   the gate inbox; this surface reflects + routes in (see spec)."
  [{:keys [ws-id project origin stage label ledger sessions]} live-url]
  (layout
   (str label " — workstream")
   (breadcrumb [:a {:href "/"} "gates"] [:a {:href "/board"} "board"] label)
   [:h1 (origin-badge origin) " " label]
   [:p.meta (name stage)
    (when live-url [:span " · " [:a {:href live-url :target "_blank"} "open session ↗"]])]
   (when ledger
     [:div.card [:strong "ledger "] (:key ledger) " · " (some-> ledger :status name)
      " · " (:report-count ledger) " report(s)"])
   [:h2 "Sessions"]
   (if (seq sessions)
     [:table
      [:thead [:tr [:th "session"] [:th "axis"] [:th "status"] [:th "brakes"]]]
      [:tbody
       (for [{:keys [name autonomy-level parked? status brakes]} sessions]
         [:tr [:td name]
          [:td (clojure.core/name autonomy-level) (when parked? " · gate")]
          [:td (clojure.core/name (or status :down))]
          [:td.meta (when brakes (pr-str brakes))]])]]
     [:p.empty "No sessions."])))
```

(b) In `src/nido/ui/server.clj` add the route-in helper + route. `workstream-live-url` finds the LIVE session for a workstream and returns its registry `:url`:

```clojure
(defn workstream-live-url
  "The friendly-host :url of the workstream's live entry session, or nil. Reuses
   work/open-target (prefers the live session) + the registry the session board reads."
  [project ws-id]
  (when-let [{:keys [session]} (work/open-target project ws-id)]
    (let [registry (state/read-registry)]
      (some (fn [[_wt entry]] (when (= session (:session-name entry)) (:url entry)))
            registry))))
```

> Verify the registry entry's session-name key by reading `nido.session.state`/the registry shape (it may be `:session` or `:name` rather than `:session-name`); match on whatever the registry actually stores, consistent with how `session-rows` reads it. If the registry isn't keyed to recover the name cheaply, fall back to `nil` (route-in simply hides) — do NOT block on this.

Add the route in the structural dispatch (alongside `/gate/...`):

```clojure
      (and (= 3 (count segs)) (= "ws" (first segs)))
      (let [project (nth segs 1), ws-id (nth segs 2)]
        (if-let [w (work/workstream project ws-id)]
          (html-response 200 (views/ws-detail-page w (workstream-live-url project ws-id)))
          (html-response 404 (views/not-found-page))))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bb nido:test :only nido.ui`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(ui): read-only workstream detail + /ws route with route-in"
jj show -s @-
```

---

### Task 7: Nav reconcile, end-to-end smoke, full suite

**Files:**
- Modify: `src/nido/ui/views.clj` (home/projects nav links point at gates/board/system consistently)
- Modify: `CLAUDE.md` (dashboard section: home is the Gate Inbox; `/system` is the session board; `/board` the spine board)
- Test: `test/nido/ui/server_test.clj` (a smoke test)

- [ ] **Step 1: Write a smoke test**

Append to `test/nido/ui/server_test.clj`:

```clojure
(deftest dashboard-routes-smoke
  (with-redefs [nido.work/all-gates (fn [] [])
                server/all-grouped  (fn [] [])
                server/all-session-rows (fn [] [])]
    (doseq [uri ["/" "/board" "/system"]]
      (is (= 200 (:status (server/handle-request {:request-method :get :uri uri})))
          (str uri " serves 200")))))
```

- [ ] **Step 2: Run to verify it fails (or passes if routes already complete)**

Run: `bb nido:test :only nido.ui.server`
Expected: PASS if Tasks 3/5 wired all three; if a nav link or route is missing, FAIL — fix it.

- [ ] **Step 3: Reconcile nav + docs**

- In `views.clj`, ensure `home-page` (`/projects` grid) and `live-board-page` (`/system`) link back to `/` (gates) and `/board` so the surfaces are navigable. Remove the old "all projects →" link wording that implied `/projects` was home; point the gate inbox's nav at board/system (done in Task 3).
- In `CLAUDE.md`, update the "Web dashboard" paragraph: the home page is now the cross-project **Gate Inbox** (act on parked workstreams); the flat live-sessions board moved to `/system`; the spine **Board** is at `/board`. Keep the port/override notes.

- [ ] **Step 4: Full suite**

Run: `bb nido:test`
Expected: all green (≈ existing + new ui tests; no regressions).

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(ui): reconcile dashboard nav (gates home, board, system); docs"
jj show -s @-
```

---

## Self-check after all tasks

- [ ] `bb nido:test` — full suite green.
- [ ] `jj log` — 7 contiguous code-only commits on the `jj new` changeset; NO `docs/**` and NO `resources/nido-icon.png` in any (`jj diff -r <first>::@- --name-only` lists only `src/nido/ui/**` + `test/nido/ui/**` + `CLAUDE.md`).
- [ ] Manual: with the daemon up (`bb nido:coordinator:up`) or `bb nido:ui`, `/` shows the Gate Inbox, clicking a gate deep-links the pane, a mutation button + reply box POST and the inbox reflects; `/board` and `/system` render.
- [ ] VSDD routes (`/:project/vsdd/…`) and session/logs routes still work (untouched).

## Non-goals (unchanged from the spec)

No channels / persistent sessions / mid-work steering. VSDD untouched. New-workstream creation stays in the TUI. The triage gate's one-click "accept verdict + promote" (Option 2) is explicitly out — deferred as a later, eyes-open safety/UX decision.
