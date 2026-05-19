# Notion Ticket Preprocessing (L3) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **jj commit hygiene:** This is a jujutsu repo. In every commit step, run `jj log -r '@-..@' --no-graph` BEFORE editing files (if `@` has a description, `jj new` first), and AGAIN AFTER editing (confirm parent is the previous task's commit).

**Goal:** Given a Notion `page-id`, walk the page recursively, classify every video block (`video` / `embed` / `bookmark`), call `nido.transcribe/video!` for each, and write `manifest.edn` + the agent-readable `transcripts.md` digest to an output directory. Shipped behind a new bb task: `bb nido:notion:preprocess-ticket :page <id> :out <dir> [:budget 10m]`.

**Architecture:** One new namespace `nido.notion.preprocess` is the composer. It depends on (a) a small extension to `nido.notion.client` — `retrieve-block-children` for paginated block fetching — and (b) `nido.transcribe/video!` from Plan A (shelled out as `bb nido:transcribe-video` for parity with how the coordinator will run it in Plan C, but the test path stubs the shell-out). Block discovery is bounded at depth 10 / 1000 total blocks. Per-video failures are notes in the manifest; page-level (auth/network) failures abort the preprocessor with a structured error.

**Tech Stack:** Babashka (bb), `clojure.test`, `babashka.fs`, `babashka.process`, `cheshire`. Depends on Plan A's `nido.transcribe.*` namespaces and `bb nido:transcribe-video` CLI being shipped.

**Spec reference:** [2026-05-19-notion-ticket-preprocessing-design.md §Layer 3](../specs/2026-05-19-notion-ticket-preprocessing-design.md). This plan delivers **Stage 2** of the four-stage rollout.

---

## File Structure

**New:**
- `src/nido/notion/preprocess.clj` — page walker, classifier, composer.
- `src/tasks/nido_notion_preprocess.clj` — `bb nido:notion:preprocess-ticket` entry point.
- `test/nido/notion/preprocess_test.clj` — unit tests for walker + classifier + composer.

**Modified:**
- `src/nido/notion/client.clj` — add `retrieve-block-children` (paginated + recursive helper exposed separately).
- `test/nido/notion/client_test.clj` — extend with `retrieve-block-children` tests.
- `bb.edn` — add `nido:notion:preprocess-ticket` task wiring.

**Untouched:** Coordinator, executor, session lifecycle — they hear about preprocessing in Plan C.

---

## Task 1 — `retrieve-block-children` in `nido.notion.client`

**Files:**
- Modify: `src/nido/notion/client.clj`
- Modify: `test/nido/notion/client_test.clj`

The Notion API: `GET /v1/blocks/<block-id>/children?start_cursor=<cursor>&page_size=100`. Returns `{:results [...] :has_more bool :next_cursor str}`. Pagination is cursor-based.

We expose two functions:

```clojure
(retrieve-block-children block-id token {:keys [start-cursor]})
;; → one page: {:status 200 :results [...] :has_more bool :next_cursor str}

(walk-blocks block-id token {:keys [max-depth max-total]})
;; → bounded recursive walk: lazy seq of {:block <map> :depth n}
```

Bounds default to `:max-depth 10`, `:max-total 1000` so a pathological page can't hang preprocessing.

- [ ] **Step 1: jj hygiene check.** `jj log -r '@-..@' --no-graph`; `jj new` if needed.

- [ ] **Step 2: Failing tests.** Extend `test/nido/notion/client_test.clj` with:

```clojure
(deftest retrieve-block-children-returns-results-and-cursor
  (let [captured (atom nil)]
    (with-redefs [notion/http-request
                  (fn [method url opts]
                    (reset! captured {:method method :url url :opts opts})
                    {:status 200
                     :body (cheshire.core/generate-string
                             {:results [{:id "b1" :type "paragraph"}]
                              :has_more true
                              :next_cursor "cur-2"})})]
      (let [r (notion/retrieve-block-children "page-1" "tok" {})]
        (is (= 200 (:status r)))
        (is (= [{:id "b1" :type "paragraph"}] (:results r)))
        (is (true? (:has_more r)))
        (is (= "cur-2" (:next_cursor r)))
        (is (re-find #"/v1/blocks/page-1/children" (:url @captured)))))))

(deftest retrieve-block-children-passes-cursor
  (let [captured (atom nil)]
    (with-redefs [notion/http-request
                  (fn [_method url _opts]
                    (reset! captured url)
                    {:status 200
                     :body (cheshire.core/generate-string
                             {:results [] :has_more false})})]
      (notion/retrieve-block-children "page-1" "tok" {:start-cursor "cur-X"})
      (is (re-find #"start_cursor=cur-X" @captured)))))

(deftest retrieve-block-children-handles-auth
  (with-redefs [notion/http-request (fn [_ _ _] {:status 401 :body ""})]
    (is (= :auth (-> (notion/retrieve-block-children "p" "t" {}) :error)))))

(deftest walk-blocks-paginates-and-recurses-children
  ;; Page tree:
  ;;   page-1 has [b1 (paragraph, no children), b2 (toggle, has_children=true)]
  ;;     b2 has [b3 (paragraph)]
  ;; Two pages of /blocks/page-1/children (has_more), then b2's children once.
  (let [responses (atom {"page-1?"        [{:status 200
                                            :body (cheshire.core/generate-string
                                                    {:results [{:id "b1" :type "paragraph" :has_children false}]
                                                     :has_more true
                                                     :next_cursor "p1-cur"})}
                                           {:status 200
                                            :body (cheshire.core/generate-string
                                                    {:results [{:id "b2" :type "toggle" :has_children true}]
                                                     :has_more false})}]
                         "b2?"             [{:status 200
                                            :body (cheshire.core/generate-string
                                                    {:results [{:id "b3" :type "paragraph" :has_children false}]
                                                     :has_more false})}]})
        next!     (fn [k]
                    (let [[h & t] (get @responses k)]
                      (swap! responses assoc k (vec t))
                      h))]
    (with-redefs [notion/http-request
                  (fn [_method url _opts]
                    (cond
                      (re-find #"/v1/blocks/page-1/children" url) (next! "page-1?")
                      (re-find #"/v1/blocks/b2/children"     url) (next! "b2?")))]
      (let [ids (->> (notion/walk-blocks "page-1" "tok" {})
                     (map (comp :id :block)))]
        (is (= ["b1" "b2" "b3"] ids))))))

(deftest walk-blocks-respects-max-total
  (let [responses (atom
                    [(repeat 1 {:status 200
                                :body (cheshire.core/generate-string
                                        {:results (vec (for [i (range 50)]
                                                         {:id (str "b" i)
                                                          :type "paragraph"
                                                          :has_children false}))
                                         :has_more true
                                         :next_cursor "more"})})
                     (repeat 1 {:status 200
                                :body (cheshire.core/generate-string
                                        {:results (vec (for [i (range 100 200)]
                                                         {:id (str "b" i)
                                                          :type "paragraph"
                                                          :has_children false}))
                                         :has_more false})})])
        ix        (atom 0)]
    (with-redefs [notion/http-request
                  (fn [_ _ _]
                    (let [r (first (nth @responses @ix))]
                      (swap! ix inc)
                      r))]
      (let [walked (notion/walk-blocks "p" "t" {:max-total 30})]
        (is (= 30 (count walked)))))))
```

- [ ] **Step 3: Run, verify fail**

```
bb nido:test :only nido.notion.client-test
```

Expected: undefined `retrieve-block-children` / `walk-blocks`.

- [ ] **Step 4: Implement.** Append to `src/nido/notion/client.clj`:

```clojure
(defn retrieve-block-children
  "GET /v1/blocks/<block-id>/children. Single page; pass `:start-cursor`
   to get the next page. Returns:
     {:status 200 :results [...] :has_more bool :next_cursor str}
     {:status N   :error :auth|:server|:network|:http}"
  [block-id token {:keys [start-cursor]}]
  (let [base-url (str "https://api.notion.com/v1/blocks/" block-id "/children?page_size=100")
        url      (if start-cursor (str base-url "&start_cursor=" start-cursor) base-url)
        resp     (try
                   (http-request
                     :get url
                     {:headers {"Authorization"  (str "Bearer " token)
                                "Notion-Version" notion-api-version}
                      :timeout 10000})
                   (catch Exception e {:status 0 :exception e}))
        {:keys [status body]} resp]
    (cond
      (= status 200) (let [parsed (cheshire.core/parse-string body true)]
                       {:status 200
                        :results     (:results parsed)
                        :has_more    (:has_more parsed)
                        :next_cursor (:next_cursor parsed)})
      (= status 401) {:status status :error :auth}
      (>= status 500) {:status status :error :server}
      (= status 0)    {:status 0     :error :network}
      :else           {:status status :error :http})))

(defn walk-blocks
  "Recursively walk a page's block tree. Returns a lazy seq of
   {:block <notion-block-map> :depth n}. Bounded by :max-depth (default 10)
   and :max-total (default 1000). Pagination is followed automatically.

   Throws ex-info on auth/network/server failure — the caller is expected
   to surface this as a page-level failure (no partial manifest written)."
  [root-id token {:keys [max-depth max-total]
                  :or   {max-depth 10 max-total 1000}}]
  (let [seen (volatile! 0)]
    (letfn [(visit [block-id depth]
              (when (and (<= depth max-depth) (< @seen max-total))
                (loop [cursor nil
                       acc    []]
                  (let [{:keys [status results has_more next_cursor]
                         :as page} (retrieve-block-children
                                     block-id token
                                     (when cursor {:start-cursor cursor}))]
                    (when-not (= 200 status)
                      (throw (ex-info "Notion block fetch failed"
                                      {:block block-id :error page})))
                    (let [acc' (reduce
                                 (fn [a b]
                                   (if (>= @seen max-total)
                                     (reduced a)
                                     (do (vswap! seen inc)
                                         (let [entry {:block b :depth depth}
                                               kids  (when (:has_children b)
                                                       (visit (:id b) (inc depth)))]
                                           (-> (conj a entry)
                                               (cond-> (seq kids) (into kids)))))))
                                 acc results)]
                      (if (and has_more next_cursor (< @seen max-total))
                        (recur next_cursor acc')
                        acc'))))))]
      (visit root-id 0))))
```

Add the import `cheshire.core` if it isn't already used (check the existing requires — `cheshire.core` is already required as `json`; re-use it: replace `cheshire.core/parse-string` and `cheshire.core/generate-string` calls inline with `json/parse-string` / `json/generate-string` to match the existing namespace style).

- [ ] **Step 5: Run, verify pass**

```
bb nido:test :only nido.notion.client-test
```

Expected: all tests (including pre-existing) pass.

- [ ] **Step 6: Commit**

```
jj desc -m "feat(notion/client): retrieve-block-children + bounded walk-blocks"
jj log -r '@-..@' --no-graph
```

---

## Task 2 — `nido.notion.preprocess` classifier

**Files:**
- Create: `src/nido/notion/preprocess.clj` (classifier portion)
- Test: `test/nido/notion/preprocess_test.clj` (classifier portion)

Pure classification — given a block map, return `nil` or a `{:url :kind :block-id}` map. No I/O. The composer in Task 3 walks blocks and calls this per block.

Classification rules (per spec §Classification):
- `video.external.url` matching `loom.com/(share|embed)/<id>` → `:loom`
- `video.file.url` (Notion-hosted S3 URL) → `:notion-upload`
- `embed.url` matching Loom pattern → `:loom`
- `bookmark.url` matching Loom pattern → `:loom`
- `video.external.url` / `video.file.url` ending in `.mp4|.mov|.webm|.mkv` and not Loom → `:other`
- Anything else → `nil`

- [ ] **Step 1: jj hygiene check.** `jj log -r '@-..@' --no-graph`; `jj new` if needed.

- [ ] **Step 2: Failing tests.** Create `test/nido/notion/preprocess_test.clj`:

```clojure
(ns nido.notion.preprocess-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [nido.notion.preprocess :as pp]))

(deftest classify-loom-video-external
  (let [b {:id "b1" :type "video"
           :video {:type "external"
                   :external {:url "https://www.loom.com/share/abc123def456abc123def456abc123de"}}}]
    (is (= {:url "https://www.loom.com/share/abc123def456abc123def456abc123de"
            :kind :loom
            :block-id "b1"}
           (pp/classify b)))))

(deftest classify-notion-uploaded-video
  (let [b {:id "b2" :type "video"
           :video {:type "file"
                   :file {:url "https://prod-files-secure.s3/x.mp4?sig=z"}}}]
    (is (= {:url "https://prod-files-secure.s3/x.mp4?sig=z"
            :kind :notion-upload
            :block-id "b2"}
           (pp/classify b)))))

(deftest classify-loom-via-embed-block
  (let [b {:id "b3" :type "embed"
           :embed {:url "https://www.loom.com/embed/abc123def456abc123def456abc123de"}}]
    (is (= :loom (:kind (pp/classify b))))))

(deftest classify-loom-via-bookmark
  (let [b {:id "b4" :type "bookmark"
           :bookmark {:url "https://www.loom.com/share/abc123def456abc123def456abc123de"}}]
    (is (= :loom (:kind (pp/classify b))))))

(deftest classify-mp4-external-as-other
  (let [b {:id "b5" :type "video"
           :video {:type "external"
                   :external {:url "https://cdn.example.com/clip.mp4"}}}]
    (is (= :other (:kind (pp/classify b))))))

(deftest classify-ignores-paragraph
  (is (nil? (pp/classify {:id "b6" :type "paragraph"
                          :paragraph {:rich_text [{:plain_text "hi"}]}}))))

(deftest classify-ignores-embed-with-non-video
  (is (nil? (pp/classify {:id "b7" :type "embed"
                          :embed {:url "https://www.figma.com/file/X"}}))))
```

- [ ] **Step 3: Run, verify fail**

```
bb nido:test :only nido.notion.preprocess-test
```

Expected: namespace not found.

- [ ] **Step 4: Implement (classifier portion).** Create `src/nido/notion/preprocess.clj`:

```clojure
(ns nido.notion.preprocess
  "Walk a Notion page, find video blocks (Loom + Notion-uploaded + other
   MP4-like URLs), call `nido.transcribe/video!` for each, write a
   manifest + agent-readable digest to <out-dir>."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [nido.io :as io]
   [nido.notion.client :as notion]
   [nido.transcribe.loom :as loom]))

(def ^:private mp4-ext-re #"(?i)\\.(?:mp4|mov|webm|mkv)(?:\\?|$)")

(defn- loom-url? [url] (some? (loom/extract-video-id url)))
(defn- mp4-url?  [url] (and url (re-find mp4-ext-re url)))

(defn classify
  "Return {:url :kind :block-id} or nil. Pure function."
  [{:keys [id type] :as block}]
  (case type
    "video"
    (let [v   (:video block)
          ext (some-> v :external :url)
          fl  (some-> v :file :url)]
      (cond
        (and ext (loom-url? ext)) {:url ext :kind :loom            :block-id id}
        (and fl  (loom-url? fl))  {:url fl  :kind :loom            :block-id id}
        fl                        {:url fl  :kind :notion-upload   :block-id id}
        (and ext (mp4-url? ext))  {:url ext :kind :other           :block-id id}
        :else                     nil))

    "embed"
    (let [u (some-> block :embed :url)]
      (when (loom-url? u) {:url u :kind :loom :block-id id}))

    "bookmark"
    (let [u (some-> block :bookmark :url)]
      (when (loom-url? u) {:url u :kind :loom :block-id id}))

    nil))
```

- [ ] **Step 5: Run, verify pass**

```
bb nido:test :only nido.notion.preprocess-test
```

Expected: all 7 classifier tests pass.

- [ ] **Step 6: Commit**

```
jj desc -m "feat(notion/preprocess): classify video blocks (loom/upload/other)"
jj log -r '@-..@' --no-graph
```

---

## Task 3 — `preprocess-ticket!` composer

**Files:**
- Modify: `src/nido/notion/preprocess.clj` (add composer)
- Modify: `test/nido/notion/preprocess_test.clj` (add composer tests)

The composer wires it together:

1. Read `page-last-edited-time` via `notion/retrieve-block-children` (single page is fine — Notion returns the page's own metadata via a separate endpoint, but for v1 we just snapshot the current time at walk start; the spec calls out `last_edited_time` from the page object — see below for the actual fetch).

   Actually — to capture `last_edited_time` for the apply-step concurrency check, we need `retrieve-a-page` (which is not in `notion/client` yet). For v1 simplicity, call `GET /v1/pages/<page-id>` via the existing `http-request` seam and extract `last_edited_time`. Treat absence as nil (logged but not fatal).

2. `walk-blocks page-id token` → seq of blocks.
3. Run `classify` on each; keep non-nil. Cap at `:max-videos 50` defensively.
4. For each classified video, allocate `min(300, max(60, budget-s / n-videos))` seconds — the spec's per-video hard cap is 5 min wall-clock regardless of how much page budget is left. Shell out to `bb nido:transcribe-video <url> :out <out-dir>/NN-<kind>-<short>.vtt :model :small :timeout <s>s`. Capture exit + stderr.
5. Build a manifest entry per video: `:idx :url :kind :transcript-source :status :vtt-path :error`.
6. Write `manifest.edn`, `transcripts.md`, and (per video) the `.vtt`s the `bb` task produced.

Page-level failures (auth / 5xx during walk) throw → composer returns `{:ok? false :error {...}}` and writes nothing.

`shell-bb-task` is the redef seam for the per-video shell-out.

- [ ] **Step 1: jj hygiene check.** `jj log -r '@-..@' --no-graph`; `jj new` if needed.

- [ ] **Step 2: Failing tests.** Append to `test/nido/notion/preprocess_test.clj`:

```clojure
(deftest preprocess-ticket-happy-path-loom
  (let [tmp (fs/create-temp-dir)
        out (str (fs/path tmp "out"))]
    (fs/create-dirs out)
    (with-redefs [pp/fetch-page-meta!  (fn [_pid _tok]
                                         {:last_edited_time "2026-05-19T10:00:00Z"})
                  notion/walk-blocks    (fn [_pid _tok _opts]
                                          [{:block {:id "b1" :type "video"
                                                    :video {:type "external"
                                                            :external {:url
                                                              "https://www.loom.com/share/abc123def456abc123def456abc123de"}}}
                                            :depth 0}])
                  pp/shell-bb-task     (fn [_args]
                                         {:exit 0 :out "" :err ""})]
      ;; Pre-create the .vtt the (stubbed) shell-out would have produced
      (spit (str (fs/path out "01-loom-abc123de.vtt")) "WEBVTT\\nok\\n")
      (let [r (pp/preprocess-ticket!
                {:page-id "p1" :token "tok"
                 :out-dir out :budget-s 600 :max-videos 50})]
        (is (:ok? r))
        (let [m (edn/read-string (slurp (str (fs/path out "manifest.edn"))))]
          (is (= "p1" (:page-id m)))
          (is (= "2026-05-19T10:00:00Z" (:page-last-edited-time m)))
          (is (= 1 (count (:videos m))))
          (is (= :ok (-> m :videos first :status))))
        (is (fs/exists? (fs/path out "transcripts.md")))))
    (fs/delete-tree tmp)))

(deftest preprocess-ticket-partial-failure-still-writes-manifest
  (let [tmp (fs/create-temp-dir)
        out (str (fs/path tmp "out"))]
    (fs/create-dirs out)
    (with-redefs [pp/fetch-page-meta!  (fn [_pid _tok] {:last_edited_time "x"})
                  notion/walk-blocks    (fn [_pid _tok _opts]
                                          [{:block {:id "b1" :type "video"
                                                    :video {:type "file"
                                                            :file {:url "https://s3/x.mp4"}}}
                                            :depth 0}])
                  pp/shell-bb-task     (fn [_args]
                                         {:exit 1
                                          :err "{:reason :whisper-crashed :detail {:exit 1}}\\n"})]
      (let [r (pp/preprocess-ticket!
                {:page-id "p1" :token "tok"
                 :out-dir out :budget-s 600 :max-videos 50})]
        (is (:ok? r) "per-video failure is not a preprocessor failure")
        (let [m (edn/read-string (slurp (str (fs/path out "manifest.edn"))))]
          (is (= :failed (-> m :videos first :status)))
          (is (= :whisper-crashed (-> m :videos first :error :reason))))))
    (fs/delete-tree tmp)))

(deftest preprocess-ticket-page-walk-failure-aborts
  (let [tmp (fs/create-temp-dir)
        out (str (fs/path tmp "out"))]
    (fs/create-dirs out)
    (with-redefs [pp/fetch-page-meta! (fn [_pid _tok] {:last_edited_time "x"})
                  notion/walk-blocks   (fn [_pid _tok _opts]
                                          (throw (ex-info "auth"
                                                          {:error {:error :auth}})))]
      (let [r (pp/preprocess-ticket!
                {:page-id "p1" :token "tok"
                 :out-dir out :budget-s 600 :max-videos 50})]
        (is (not (:ok? r)))
        (is (= :notion-auth (-> r :error :reason)))
        (is (not (fs/exists? (fs/path out "manifest.edn")))
            "no partial manifest on page-walk failure")))
    (fs/delete-tree tmp)))

(deftest preprocess-ticket-zero-videos-writes-empty-manifest
  (let [tmp (fs/create-temp-dir)
        out (str (fs/path tmp "out"))]
    (fs/create-dirs out)
    (with-redefs [pp/fetch-page-meta!  (fn [_ _] {:last_edited_time "x"})
                  notion/walk-blocks    (fn [_ _ _]
                                          [{:block {:id "b1" :type "paragraph"}
                                            :depth 0}])
                  pp/shell-bb-task     (fn [_]
                                         (throw (ex-info "should not be called" {})))]
      (let [r (pp/preprocess-ticket!
                {:page-id "p1" :token "tok"
                 :out-dir out :budget-s 600 :max-videos 50})]
        (is (:ok? r))
        (let [m (edn/read-string (slurp (str (fs/path out "manifest.edn"))))]
          (is (= [] (:videos m))))
        (is (fs/exists? (fs/path out "transcripts.md")))))
    (fs/delete-tree tmp)))
```

- [ ] **Step 3: Run, verify fail**

```
bb nido:test :only nido.notion.preprocess-test
```

Expected: 4 new tests fail (undefined `preprocess-ticket!`, `fetch-page-meta!`, `shell-bb-task`).

- [ ] **Step 4: Implement.** Append to `src/nido/notion/preprocess.clj`:

```clojure
(defn fetch-page-meta!
  "GET /v1/pages/<id> via the notion http-request seam. Returns parsed map
   or nil on failure (last_edited_time is best-effort)."
  [page-id token]
  (let [resp (try
               (notion/http-request
                 :get (str "https://api.notion.com/v1/pages/" page-id)
                 {:headers {"Authorization"  (str "Bearer " token)
                            "Notion-Version" "2025-09-03"}
                  :timeout 10000})
               (catch Exception _ nil))]
    (when (= 200 (:status resp))
      (json/parse-string (:body resp) true))))

(defn shell-bb-task
  "Shell-out to a bb task. Returns {:exit :out :err}. Redef seam."
  [args]
  (let [proc @(p/process args {:out :string :err :string})]
    {:exit (:exit proc)
     :out  (str (:out proc))
     :err  (str (:err proc))}))

(defn- short-id [url]
  (let [id (or (loom/extract-video-id url)
               (last (str/split (-> url (str/split #"\\?") first) #"/")))]
    (-> id
        (or "unknown")
        (str/replace #"[^A-Za-z0-9]" "")
        (subs 0 (min 8 (count (str id)))))))

(defn- vtt-filename [idx kind url]
  (format "%02d-%s-%s.vtt" idx (name kind) (short-id url)))

(defn- transcribe-one!
  [{:keys [idx url kind out-dir per-video-s model]}]
  (let [vtt-name (vtt-filename idx kind url)
        vtt-path (str (fs/path out-dir vtt-name))
        args     ["bb" "nido:transcribe-video" url
                  ":out" vtt-path
                  ":model" (str ":" (name model))
                  ":timeout" (str per-video-s "s")]
        {:keys [exit out err]} (shell-bb-task args)]
    (if (zero? exit)
      {:idx idx :url url :kind kind
       :transcript-source (if (= kind :loom) :loom-graphql :whisper)
       :status :ok
       :vtt-path vtt-name
       :error nil}
      ;; bb's `:init` block writes a banner to stderr before tasks run
      ;; (look for `[nido :init] shell altered: ...`). The transcribe task's
      ;; EDN error map is the LAST non-blank line that starts with `{`.
      (let [last-edn-line (->> (str/split-lines err)
                               (filter (fn [l] (str/starts-with? (str/triml l) "{")))
                               last)
            parsed (try (edn/read-string (str/trim (or last-edn-line err)))
                        (catch Exception _ {:reason :unknown :detail {:stderr err}}))]
        {:idx idx :url url :kind kind
         :transcript-source :whisper
         :status :failed
         :vtt-path nil
         :error parsed}))))

(defn- ->preview [vtt-path]
  ;; First 5 caption lines, ~300 chars.
  (when (fs/exists? vtt-path)
    (->> (str/split-lines (slurp vtt-path))
         (remove #(or (str/blank? %) (re-find #"-->" %) (= % "WEBVTT")))
         (take 6)
         (str/join " ")
         (#(if (> (count %) 300) (str (subs % 0 300) "…") %)))))

(defn- digest-md [out-dir videos]
  (let [lines (cons (format "# Pre-staged transcripts (%d videos)\\n"
                            (count videos))
                    (for [{:keys [idx url kind status transcript-source vtt-path error]} videos]
                      (if (= status :ok)
                        (let [full (str (fs/path out-dir vtt-path))]
                          (str (format "## %d. %s — %s\\n"
                                       idx (name kind) url)
                               (format "Transcript source: %s. Full VTT: `%s`.\\n\\n"
                                       (name (or transcript-source :whisper)) vtt-path)
                               (when-let [p (->preview full)]
                                 (str "> First few lines for context:\\n> " p "\\n"))))
                        (str (format "## %d. ⚠️ %s — failed\\n" idx (name kind))
                             (format "Source: `%s`.\\nReason: %s.\\n"
                                     url (-> error :reason name))))))]
    (str/join "\\n" lines)))

(defn preprocess-ticket!
  "Walk a Notion page, transcribe every video, write manifest + digest
   to out-dir. See ns docstring. Returns {:ok? bool [:manifest m] [:error e]}."
  [{:keys [page-id token out-dir budget-s max-videos model]
    :or   {budget-s 600 max-videos 50 model :small}}]
  (fs/create-dirs out-dir)
  (let [meta (fetch-page-meta! page-id token)
        last-edit (some-> meta :last_edited_time)]
    (try
      (let [blocks (notion/walk-blocks page-id token {})
            videos (->> blocks
                        (keep (comp classify :block))
                        (take max-videos)
                        (vec))
            n      (count videos)
            ;; Per-video timeout: spec caps at 5 min wall-clock, with a 1-min floor.
            per-s  (if (zero? n) 0 (min 300 (max 60 (quot budget-s (max 1 n)))))
            results (vec (map-indexed
                           (fn [i v]
                             (transcribe-one!
                               (assoc v :idx (inc i) :out-dir out-dir
                                        :per-video-s per-s :model model)))
                           videos))
            manifest {:page-id page-id
                      :page-last-edited-time last-edit
                      :generated-at (str (java.time.Instant/now))
                      :videos results}]
        (io/write-edn! (str (fs/path out-dir "manifest.edn")) manifest)
        (spit (str (fs/path out-dir "transcripts.md"))
              (digest-md out-dir results))
        {:ok? true :manifest manifest})
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)
              reason (case (-> data :error :error)
                       :auth    :notion-auth
                       :server  :notion-server-error
                       :network :notion-network-error
                       :notion-walk-failed)]
          {:ok? false :error {:reason reason :detail data}})))))
```

- [ ] **Step 5: Run, verify pass**

```
bb nido:test :only nido.notion.preprocess-test
```

Expected: 4 composer tests + 7 classifier tests pass.

- [ ] **Step 6: Commit**

```
jj desc -m "feat(notion/preprocess): preprocess-ticket! composer (walk + transcribe + manifest)"
jj log -r '@-..@' --no-graph
```

---

## Task 4 — `bb nido:notion:preprocess-ticket` task

**Files:**
- Create: `src/tasks/nido_notion_preprocess.clj`
- Modify: `bb.edn`

CLI shape:

```
bb nido:notion:preprocess-ticket :page <id> :out <dir> [:budget 10m]
```

Reads the Notion token from the keychain (existing `nido.notion.client/keychain-token`). On success: prints `manifest.edn` path + a one-line summary. On failure: prints EDN error map on stderr, exits non-zero.

- [ ] **Step 1: jj hygiene check.** `jj log -r '@-..@' --no-graph`; `jj new` if needed.

- [ ] **Step 2: Failing tests.** Create `test/nido/tasks/nido_notion_preprocess_test.clj`:

```clojure
(ns nido.tasks.nido-notion-preprocess-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.notion.client :as notion]
   [nido.notion.preprocess :as pp]
   [tasks.nido-notion-preprocess :as task]))

(deftest run-requires-page-and-out
  (let [exit (atom nil)]
    (with-redefs [task/exit! (fn [c] (reset! exit c))]
      (task/run)
      (is (= 2 @exit)))))

(deftest run-calls-preprocess-with-keychain-token
  (let [tmp (fs/create-temp-dir)
        out (str (fs/path tmp "out"))
        captured (atom nil)]
    (fs/create-dirs out)
    (with-redefs [notion/keychain-token (constantly "secret-tok")
                  pp/preprocess-ticket! (fn [opts]
                                          (reset! captured opts)
                                          (spit (str (fs/path out "manifest.edn"))
                                                "{:videos []}")
                                          {:ok? true :manifest {:videos []}})]
      (let [stdout (with-out-str (task/run ":page" "page-id-1" ":out" out))]
        (is (= "secret-tok" (:token @captured)))
        (is (= "page-id-1" (:page-id @captured)))
        (is (= 600 (:budget-s @captured)))
        (is (re-find #"manifest.edn" stdout))))
    (fs/delete-tree tmp)))

(deftest run-exits-nonzero-on-preprocessor-failure
  (let [exit (atom nil)]
    (with-redefs [notion/keychain-token (constantly "tok")
                  pp/preprocess-ticket! (fn [_]
                                          {:ok? false
                                           :error {:reason :notion-auth}})
                  task/exit!            (fn [c] (reset! exit c))]
      (binding [*err* (java.io.StringWriter.)]
        (task/run ":page" "p1" ":out" "/tmp/x"))
      (is (= 1 @exit)))))

(deftest run-parses-budget-duration
  (let [captured (atom nil)]
    (with-redefs [notion/keychain-token (constantly "tok")
                  pp/preprocess-ticket! (fn [opts]
                                          (reset! captured opts)
                                          {:ok? true :manifest {:videos []}})]
      (with-out-str (task/run ":page" "p1" ":out" "/tmp/x" ":budget" "15m"))
      (is (= 900 (:budget-s @captured))))))
```

- [ ] **Step 3: Run, verify fail**

```
bb nido:test :only nido.tasks.nido-notion-preprocess-test
```

Expected: namespace not found.

- [ ] **Step 4: Implement** `src/tasks/nido_notion_preprocess.clj`:

```clojure
(ns tasks.nido-notion-preprocess
  "bb task entry point for Notion ticket preprocessing.

   Usage:
     bb nido:notion:preprocess-ticket :page <id> :out <dir> [:budget 10m]"
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.notion.client :as notion]
   [nido.notion.preprocess :as pp]
   [nido.task-args :as task-args]))

(defn exit! [code] (System/exit code))

(defn- parse-duration [s]
  (cond
    (nil? s)                 nil
    (re-matches #"\\d+s?" s)  (Integer/parseInt (str/replace s #"s$" ""))
    (re-matches #"\\d+m" s)   (* 60 (Integer/parseInt (str/replace s #"m$" "")))
    :else (throw (ex-info (str "Bad duration: " s) {:input s}))))

(defn run
  "bb nido:notion:preprocess-ticket :page <id> :out <dir> [:budget 10m]"
  [& args]
  (let [[_ opts] (task-args/split-args args)
        page-id  (:page opts)
        out-dir  (:out opts)
        budget-s (or (parse-duration (:budget opts)) 600)]
    (when (or (str/blank? page-id) (str/blank? out-dir))
      (binding [*err* *err*]
        (println "Usage: bb nido:notion:preprocess-ticket :page <id> :out <dir> [:budget 10m]"))
      (exit! 2))
    (let [token (notion/keychain-token)]
      (when (str/blank? token)
        (binding [*err* *err*]
          (println "No Notion token in keychain. Run `bb nido:notion:auth:set` first."))
        (exit! 2))
      (let [r (pp/preprocess-ticket!
                {:page-id  page-id
                 :token    token
                 :out-dir  out-dir
                 :budget-s budget-s})]
        (if (:ok? r)
          (do (println (str (fs/path out-dir "manifest.edn")))
              (println (format "%d videos processed."
                               (count (-> r :manifest :videos)))))
          (do (binding [*err* *err*]
                (println (pr-str (:error r))))
              (exit! 1)))))))
```

- [ ] **Step 5: Modify `bb.edn`.** Add to `:requires` (inside `:tasks`):
```clojure
             [tasks.nido-notion-preprocess :as nido-notion-preprocess]
```

Add a task entry near the other `nido:notion:*` entries:
```clojure
  nido:notion:preprocess-ticket
  {:doc "Preprocess a Notion ticket (transcribe videos): :page <id> :out <dir> [:budget 10m]"
   :task (apply nido-notion-preprocess/run *command-line-args*)}
```

- [ ] **Step 6: Run, verify pass**

```
bb nido:test :only nido.tasks.nido-notion-preprocess-test
bb tasks | grep preprocess-ticket
```

Both should succeed.

- [ ] **Step 7: Commit**

```
jj desc -m "feat(tasks/notion): bb nido:notion:preprocess-ticket CLI"
jj log -r '@-..@' --no-graph
```

---

## Task 5 — Manual smoke verification

**Files:** none (manual sanity check; no commit).

- [ ] **Step 1: Pick a real bug-report Notion page** with at least one Loom video. Note the page id (the 32-hex segment after the last `-` in the page URL).

- [ ] **Step 2: Confirm the token is in the keychain.**

```
bb nido:notion:auth:check
```

Expected: "Token present."

- [ ] **Step 3: Run the preprocessor against a real page.**

```
mkdir -p /tmp/preprocess-smoke
bb nido:notion:preprocess-ticket :page <page-id> :out /tmp/preprocess-smoke
ls /tmp/preprocess-smoke
cat /tmp/preprocess-smoke/manifest.edn
cat /tmp/preprocess-smoke/transcripts.md
```

Expected: `manifest.edn` lists every video on the page; `transcripts.md` has a section per video with a preview; per-video `.vtt` files present.

- [ ] **Step 4: Run against a page with no videos.**

```
bb nido:notion:preprocess-ticket :page <other-page-id> :out /tmp/preprocess-empty
cat /tmp/preprocess-empty/manifest.edn
```

Expected: `:videos []`, `transcripts.md` shows "Pre-staged transcripts (0 videos)".

No commit — manual verification only.

---

## Self-Review

Before declaring Stage 2 done, check:

- **`bb nido:test :only nido.notion.preprocess`** and **`bb nido:test :only nido.notion.client`** both green.
- **A real Notion bug page** with videos produces a complete manifest + per-video VTTs.
- **A page with no videos** produces an empty-but-valid manifest.
- **Error EDN on stderr is structured** when the token is missing (`exit 2`) or when Notion auth fails (`exit 1`).

Once green, Stage 3 (`2026-05-19-coordinator-preprocessor-dispatch.md`) can begin — it shells out to `bb nido:notion:preprocess-ticket` between envelope dequeue and session-up.
