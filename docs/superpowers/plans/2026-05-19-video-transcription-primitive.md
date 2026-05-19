# Video Transcription Primitive (L1 + L2) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **jj commit hygiene:** This is a jujutsu repo. In every commit step, run `jj log -r '@-..@' --no-graph` BEFORE editing files (if `@` has a description, `jj new` first), and AGAIN AFTER editing (confirm parent is the previous task's commit). Subagents have repeatedly mashed two tasks into one commit by skipping this check.

**Goal:** Ship a self-contained URL → VTT primitive. `bb nido:transcribe-video <url>` takes any video URL, returns a VTT transcript. Loom share/embed URLs use Loom's public GraphQL transcript endpoint (no auth); everything else (and the Loom fallback when transcripts are disabled) downloads the MP4 and runs `whisper` locally.

**Architecture:** Three thin namespaces with one job each. `nido.transcribe.whisper` shells out to the `whisper` CLI. `nido.transcribe.loom` is a Loom GraphQL client (two operations: `FetchVideoTranscript` and `GetVideoSource`). `nido.transcribe` dispatches on URL host: Loom → try Loom first, fall back to whisper; otherwise → whisper directly. All HTTP and shell-out points have redef seams so unit tests stub them; no real network or whisper run in CI.

**Tech Stack:** Babashka (bb), `clojure.test`, `babashka.fs`, `babashka.http-client`, `cheshire`. No new external dependencies — `openai-whisper` is already on PATH at `/opt/homebrew/bin/whisper`.

**Spec reference:** [2026-05-19-notion-ticket-preprocessing-design.md](../specs/2026-05-19-notion-ticket-preprocessing-design.md). This plan delivers **Stage 1** of the four-stage rollout (L1 + L2 + bb task). Stages 2–4 (Notion page composer, coordinator dispatch, triage adoption) are separate plans.

---

## File Structure

**New:**
- `src/nido/transcribe/whisper.clj` — L1 shell-out to the `whisper` CLI.
- `src/nido/transcribe/loom.clj` — L2 Loom GraphQL client + URL parser.
- `src/nido/transcribe.clj` — L2 URL → VTT dispatch (`video!`).
- `src/tasks/nido_transcribe.clj` — bb task entry points.
- `test/nido/transcribe/whisper_test.clj` — unit tests for L1 (shell stubbed).
- `test/nido/transcribe/loom_test.clj` — unit tests for L2 Loom (HTTP stubbed).
- `test/nido/transcribe_test.clj` — unit tests for the dispatch.

**Modified:**
- `bb.edn` — add `nido:transcribe-video` task wiring and `tasks.nido-transcribe` to `:requires`.

**Untouched:** Coordinator, Notion source, session lifecycle, executor — none of them know about transcription yet. They start hearing about it in Plans B–D.

---

## Task 1 — `nido.transcribe.whisper` (L1 shell-out)

**Files:**
- Create: `src/nido/transcribe/whisper.clj`
- Test: `test/nido/transcribe/whisper_test.clj`

Thin wrapper around `whisper <input> --model <m> --output_format vtt --output_dir <out> --language en --fp16 False`. The `sh!` function is a redef seam so tests don't actually shell out. A separate timeout wrapper (`process-with-timeout!`) is a seam too — defaults to `babashka.process/process` with the `:timeout` option, but tests stub it.

Return shape:
```clojure
{:ok? true  :vtt-path "/.../sample.vtt"}
{:ok? false :error {:reason :whisper-crashed :detail {:exit 1 :stderr "..."}}}
{:ok? false :error {:reason :whisper-timeout :detail {:limit-s 300}}}
{:ok? false :error {:reason :input-missing   :detail {:input "/tmp/x.mp4"}}}
```

- [ ] **Step 1: jj hygiene check.** Run `jj log -r '@-..@' --no-graph`. If `@` already has a description, run `jj new` to start a fresh changeset.

- [ ] **Step 2: Failing tests**

Create `test/nido/transcribe/whisper_test.clj`:

```clojure
(ns nido.transcribe.whisper-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.transcribe.whisper :as whisper]))

(defn- stub-sh [{:keys [exit out err]}]
  (fn [_args _opts] {:exit exit :out (or out "") :err (or err "")}))

(deftest run-builds-correct-command
  (let [tmp   (fs/create-temp-dir)
        calls (atom [])
        out   (str (fs/path tmp "out"))]
    (fs/create-dirs out)
    (spit (str (fs/path tmp "in.mp4")) "fake")
    ;; whisper writes <basename>.vtt into --output_dir; pre-create it so the
    ;; success branch can verify the file is found.
    (spit (str (fs/path out "in.vtt")) "WEBVTT\n")
    (with-redefs [whisper/sh! (fn [args _opts]
                                (swap! calls conj args)
                                {:exit 0 :out "" :err ""})]
      (let [r (whisper/run! {:input    (str (fs/path tmp "in.mp4"))
                             :model    :small
                             :out-dir  out
                             :timeout-s 300})]
        (is (:ok? r))
        (is (= (str (fs/path out "in.vtt")) (:vtt-path r)))))
    (let [[cmd] @calls]
      (is (= "whisper" (first cmd)))
      (is (some #{"--model"} cmd))
      (is (some #{"small"} cmd))
      (is (some #{"--output_format"} cmd))
      (is (some #{"vtt"} cmd))
      (is (some #{"--language"} cmd))
      (is (some #{"--fp16"} cmd))
      (is (some #{"False"} cmd)))
    (fs/delete-tree tmp)))

(deftest run-returns-structured-error-on-non-zero-exit
  (let [tmp (fs/create-temp-dir)]
    (spit (str (fs/path tmp "in.mp4")) "fake")
    (with-redefs [whisper/sh! (stub-sh {:exit 1 :err "boom"})]
      (let [r (whisper/run! {:input    (str (fs/path tmp "in.mp4"))
                             :model    :small
                             :out-dir  (str tmp)
                             :timeout-s 300})]
        (is (not (:ok? r)))
        (is (= :whisper-crashed (-> r :error :reason)))
        (is (= 1 (-> r :error :detail :exit)))
        (is (= "boom" (-> r :error :detail :stderr)))))
    (fs/delete-tree tmp)))

(deftest run-returns-timeout-error-when-sh-times-out
  (let [tmp (fs/create-temp-dir)]
    (spit (str (fs/path tmp "in.mp4")) "fake")
    (with-redefs [whisper/sh! (fn [_args _opts]
                                (throw (java.util.concurrent.TimeoutException. "timeout")))]
      (let [r (whisper/run! {:input    (str (fs/path tmp "in.mp4"))
                             :model    :small
                             :out-dir  (str tmp)
                             :timeout-s 5})]
        (is (not (:ok? r)))
        (is (= :whisper-timeout (-> r :error :reason)))
        (is (= 5 (-> r :error :detail :limit-s)))))
    (fs/delete-tree tmp)))

(deftest run-rejects-missing-input
  (let [r (whisper/run! {:input    "/does/not/exist.mp4"
                         :model    :small
                         :out-dir  "/tmp"
                         :timeout-s 300})]
    (is (not (:ok? r)))
    (is (= :input-missing (-> r :error :reason)))))
```

- [ ] **Step 3: Run, verify fail**

```
bb nido:test :only nido.transcribe.whisper
```

Expected: namespace not found.

- [ ] **Step 4: Implement** `src/nido/transcribe/whisper.clj`:

```clojure
(ns nido.transcribe.whisper
  "Shell-out to the openai-whisper Python CLI. `sh!` is a redef seam so
   tests stub the subprocess entirely.

   Returns:
     {:ok? true  :vtt-path \"...\"}
     {:ok? false :error {:reason :whisper-crashed :detail {:exit n :stderr \"...\"}}}
     {:ok? false :error {:reason :whisper-timeout :detail {:limit-s n}}}
     {:ok? false :error {:reason :input-missing   :detail {:input p}}}"
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p])
  (:import (java.util.concurrent TimeoutException)))

(defn sh!
  "Wrapped shell-out. Tests stub this. Returns {:exit :out :err}.
   Honors {:timeout-s n} by throwing TimeoutException after the limit."
  [args {:keys [timeout-s]}]
  (let [proc (p/process args {:out :string :err :string})]
    (if timeout-s
      (try
        @(p/exec proc {:timeout (* 1000 timeout-s)})
        (catch TimeoutException e
          (p/destroy-tree proc)
          (throw e)))
      @proc)))

(defn- vtt-path-for [input out-dir]
  ;; whisper names the output <stem>.vtt in --output_dir
  (let [stem (-> input fs/file-name fs/strip-ext)]
    (str (fs/path out-dir (str stem ".vtt")))))

(defn run!
  "Transcribe a local audio/video file with whisper. See ns docstring."
  [{:keys [input model out-dir timeout-s]
    :or   {model :small timeout-s 300}}]
  (cond
    (not (fs/exists? input))
    {:ok? false :error {:reason :input-missing :detail {:input input}}}

    :else
    (let [args ["whisper" input
                "--model"         (name model)
                "--output_format" "vtt"
                "--output_dir"    out-dir
                "--language"      "en"
                "--fp16"          "False"]]
      (fs/create-dirs out-dir)
      (try
        (let [{:keys [exit err]} (sh! args {:timeout-s timeout-s})]
          (if (zero? exit)
            {:ok? true :vtt-path (vtt-path-for input out-dir)}
            {:ok? false :error {:reason :whisper-crashed
                                :detail {:exit exit :stderr err}}}))
        (catch TimeoutException _
          {:ok? false :error {:reason :whisper-timeout
                              :detail {:limit-s timeout-s}}})))))
```

- [ ] **Step 5: Run, verify pass**

```
bb nido:test :only nido.transcribe.whisper
```

Expected: all 4 tests pass.

- [ ] **Step 6: Commit**

```
jj desc -m "feat(transcribe/whisper): shell-out wrapper for openai-whisper CLI"
jj log -r '@-..@' --no-graph
```

Verify parent is the previous task's commit (or the spec commit on the first task).

---

## Task 2 — `nido.transcribe.loom` (L2 Loom GraphQL client)

**Files:**
- Create: `src/nido/transcribe/loom.clj`
- Test: `test/nido/transcribe/loom_test.clj`

The endpoint is `POST https://www.loom.com/graphql`. Two operations:

- `FetchVideoTranscript($videoId, $password)` — returns `{:captions_source_url ...}` (a VTT URL) or a `PublicApiError`. We GET the captions URL and return the VTT text.
- `GetVideoSource($videoId)` — returns a CDN MP4 URL used as the whisper-fallback input.

URL extraction handles `https://www.loom.com/share/<32-hex>`, `https://www.loom.com/embed/<32-hex>`, and either with trailing query/path. Other domains → `{:ok? false :error {:reason :not-a-loom-url}}`.

`http-request` is the redef seam (same shape as `nido.notion.client/http-request`).

- [ ] **Step 1: jj hygiene check.** `jj log -r '@-..@' --no-graph`; `jj new` if needed.

- [ ] **Step 2: Failing tests**

Create `test/nido/transcribe/loom_test.clj`:

```clojure
(ns nido.transcribe.loom-test
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing]]
   [nido.transcribe.loom :as loom]))

(deftest extract-video-id-from-share-url
  (is (= "abc123def456abc123def456abc123de"
         (loom/extract-video-id "https://www.loom.com/share/abc123def456abc123def456abc123de"))))

(deftest extract-video-id-from-embed-url
  (is (= "abc123def456abc123def456abc123de"
         (loom/extract-video-id "https://www.loom.com/embed/abc123def456abc123def456abc123de"))))

(deftest extract-video-id-with-trailing-query
  (is (= "abc123def456abc123def456abc123de"
         (loom/extract-video-id "https://www.loom.com/share/abc123def456abc123def456abc123de?sid=foo"))))

(deftest extract-video-id-non-loom-returns-nil
  (is (nil? (loom/extract-video-id "https://example.com/video"))))

(defn- stub-http [responses]
  (let [calls (atom [])
        ix    (atom 0)]
    [calls
     (fn [method url opts]
       (swap! calls conj {:method method :url url :opts opts})
       (let [r (nth responses @ix)]
         (swap! ix inc)
         r))]))

(deftest fetch-vtt-happy-path
  (let [[calls stub] (stub-http
                      [{:status 200
                        :body   (json/generate-string
                                  {:data {:fetchVideoTranscript
                                          {:captions_source_url "https://cdn.loom.com/x.vtt"}}})}
                       {:status 200 :body "WEBVTT\n\n00:00.000 --> 00:01.000\nhi\n"}])]
    (with-redefs [loom/http-request stub]
      (let [r (loom/fetch-vtt "abc123def456abc123def456abc123de")]
        (is (:ok? r))
        (is (= "WEBVTT\n\n00:00.000 --> 00:01.000\nhi\n" (:vtt-text r)))))
    (is (= 2 (count @calls)))
    (is (= :post (-> @calls first :method)))
    (is (= "https://www.loom.com/graphql" (-> @calls first :url)))
    (is (= "FetchVideoTranscript" (-> @calls first :opts :headers (get "graphql-operation-name"))))))

(deftest fetch-vtt-handles-transcript-disabled
  (let [[_ stub] (stub-http
                   [{:status 200
                     :body   (json/generate-string
                               {:data {:fetchVideoTranscript {:captions_source_url nil}}})}])]
    (with-redefs [loom/http-request stub]
      (let [r (loom/fetch-vtt "abc123def456abc123def456abc123de")]
        (is (not (:ok? r)))
        (is (= :loom-transcript-unavailable (-> r :error :reason)))))))

(deftest fetch-vtt-handles-public-api-error
  (let [[_ stub] (stub-http
                   [{:status 200
                     :body   (json/generate-string
                               {:data {:fetchVideoTranscript
                                       {:__typename "PublicApiError"
                                        :message    "Video is password protected"}}})}])]
    (with-redefs [loom/http-request stub]
      (let [r (loom/fetch-vtt "abc123def456abc123def456abc123de")]
        (is (not (:ok? r)))
        (is (= :loom-api-error (-> r :error :reason)))
        (is (= "Video is password protected" (-> r :error :detail :message)))))))

(deftest fetch-vtt-handles-5xx
  (let [[_ stub] (stub-http [{:status 502 :body "bad gateway"}])]
    (with-redefs [loom/http-request stub]
      (let [r (loom/fetch-vtt "abc123def456abc123def456abc123de")]
        (is (not (:ok? r)))
        (is (= :loom-server-error (-> r :error :reason)))))))

(deftest video-source-url-returns-mp4
  (let [[_ stub] (stub-http
                   [{:status 200
                     :body   (json/generate-string
                               {:data {:getVideoSource {:url "https://cdn.loom.com/x.mp4"}}})}])]
    (with-redefs [loom/http-request stub]
      (let [r (loom/video-source-url "abc123def456abc123def456abc123de")]
        (is (:ok? r))
        (is (= "https://cdn.loom.com/x.mp4" (:url r)))))))
```

- [ ] **Step 3: Run, verify fail**

```
bb nido:test :only nido.transcribe.loom
```

Expected: namespace not found.

- [ ] **Step 4: Implement** `src/nido/transcribe/loom.clj`:

```clojure
(ns nido.transcribe.loom
  "Loom public GraphQL client. No auth required for public share/embed
   videos. `http-request` is the redef seam (matches the shape used in
   nido.notion.client).

   Operations:
     FetchVideoTranscript — returns captions_source_url (a VTT URL)
                            or a PublicApiError.
     GetVideoSource       — returns a CDN MP4 URL for whisper fallback."
  (:require
   [babashka.http-client :as http]
   [cheshire.core :as json]
   [clojure.string :as str]))

(def ^:private graphql-url "https://www.loom.com/graphql")

(def ^:private base-headers
  {"Content-Type"             "application/json"
   "Accept"                   "application/json"
   "Origin"                   "https://www.loom.com"
   "apollographql-client-name" "web"})

(defn http-request
  "Wrapped HTTP call so tests can stub. Dispatches on method.
   Returns {:status :body}."
  [method url opts]
  (case method
    :get  (http/get  url (assoc opts :throw false))
    :post (http/post url (assoc opts :throw false))))

(def ^:private video-id-re
  ;; 32-char hex Loom video ID; matches share/embed segments
  #"https?://(?:www\.)?loom\.com/(?:share|embed)/([0-9a-f]{32})")

(defn extract-video-id
  "Extract a Loom video id from a share/embed URL. Returns nil if the URL
   is not a Loom share/embed."
  [url]
  (when (string? url)
    (when-let [m (re-find video-id-re url)]
      (second m))))

(def ^:private fetch-transcript-query
  "query FetchVideoTranscript($videoId: ID!, $password: String) {
     fetchVideoTranscript(videoId: $videoId, password: $password) {
       __typename
       ... on VideoTranscriptDetails { captions_source_url }
       ... on PublicApiError { message }
     }
   }")

(def ^:private get-video-source-query
  "query GetVideoSource($videoId: ID!) {
     getVideoSource(videoId: $videoId) { url }
   }")

(defn- graphql-post [operation query variables]
  (http-request
    :post graphql-url
    {:headers (assoc base-headers
                "graphql-operation-name" operation)
     :body    (json/generate-string
                {:operationName operation
                 :variables     variables
                 :query         query})
     :timeout 10000}))

(defn fetch-vtt
  "Fetch a public Loom video's VTT transcript via GraphQL + captions URL.
   Returns:
     {:ok? true  :vtt-text \"...\"}
     {:ok? false :error {:reason :loom-transcript-unavailable :detail {:video-id ..}}}
     {:ok? false :error {:reason :loom-api-error              :detail {:message ..}}}
     {:ok? false :error {:reason :loom-server-error           :detail {:status n}}}
     {:ok? false :error {:reason :loom-network-error          :detail {:exception ..}}}"
  [video-id]
  (let [{:keys [status body]} (try
                                (graphql-post "FetchVideoTranscript"
                                  fetch-transcript-query
                                  {:videoId video-id})
                                (catch Exception e
                                  {:status 0 :exception e}))]
    (cond
      (= status 0)
      {:ok? false :error {:reason :loom-network-error
                          :detail {:exception (str (:exception {:status 0}))}}}

      (>= status 500)
      {:ok? false :error {:reason :loom-server-error :detail {:status status}}}

      (not= status 200)
      {:ok? false :error {:reason :loom-server-error :detail {:status status}}}

      :else
      (let [resp (json/parse-string body true)
            t    (-> resp :data :fetchVideoTranscript)]
        (cond
          (= "PublicApiError" (:__typename t))
          {:ok? false :error {:reason :loom-api-error :detail {:message (:message t)}}}

          (str/blank? (:captions_source_url t))
          {:ok? false :error {:reason :loom-transcript-unavailable
                              :detail {:video-id video-id}}}

          :else
          (let [vtt-resp (try (http-request :get (:captions_source_url t)
                                            {:timeout 10000})
                              (catch Exception e {:status 0 :exception e}))]
            (if (= 200 (:status vtt-resp))
              {:ok? true :vtt-text (:body vtt-resp)}
              {:ok? false :error {:reason :loom-server-error
                                  :detail {:status (:status vtt-resp)
                                           :phase  :captions-fetch}}})))))))

(defn video-source-url
  "Resolve a Loom video id to a CDN MP4 URL for whisper fallback.
   Returns {:ok? true :url \"...\"} or {:ok? false :error {...}}."
  [video-id]
  (let [{:keys [status body]} (try
                                (graphql-post "GetVideoSource"
                                  get-video-source-query
                                  {:videoId video-id})
                                (catch Exception e
                                  {:status 0 :exception e}))]
    (cond
      (= status 0)
      {:ok? false :error {:reason :loom-network-error}}

      (= status 200)
      (let [u (some-> (json/parse-string body true) :data :getVideoSource :url)]
        (if (str/blank? u)
          {:ok? false :error {:reason :loom-source-unavailable
                              :detail {:video-id video-id}}}
          {:ok? true :url u}))

      :else
      {:ok? false :error {:reason :loom-server-error :detail {:status status}}})))
```

- [ ] **Step 5: Run, verify pass**

```
bb nido:test :only nido.transcribe.loom
```

Expected: all tests pass.

- [ ] **Step 6: Commit**

```
jj desc -m "feat(transcribe/loom): public GraphQL client for transcript + video source"
jj log -r '@-..@' --no-graph
```

---

## Task 3 — `nido.transcribe` (L2 dispatch)

**Files:**
- Create: `src/nido/transcribe.clj`
- Test: `test/nido/transcribe_test.clj`

URL → VTT dispatch:

1. Loom URL → `loom/fetch-vtt`. Success: write VTT to `:out`, return `:transcript-source :loom-graphql`.
2. Loom URL fallback (transcript unavailable / api error) → `loom/video-source-url` → fall through to step 3.
3. Any other URL (including the resolved Loom MP4) → `download-to-temp` → `whisper/run!` → return `:transcript-source :whisper`.

`download-to-temp` is its own seam — tests stub it to avoid network I/O.

Return shape:
```clojure
{:ok? true  :vtt-path "..." :transcript-source :loom-graphql}
{:ok? true  :vtt-path "..." :transcript-source :whisper}
{:ok? false :error {:reason :... :detail {...}}}
```

- [ ] **Step 1: jj hygiene check.** `jj log -r '@-..@' --no-graph`; `jj new` if needed.

- [ ] **Step 2: Failing tests**

Create `test/nido/transcribe_test.clj`:

```clojure
(ns nido.transcribe-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.transcribe :as t]
   [nido.transcribe.loom :as loom]
   [nido.transcribe.whisper :as whisper]))

(deftest loom-fast-path-writes-vtt-from-graphql
  (let [tmp (fs/create-temp-dir)
        out (str (fs/path tmp "01.vtt"))]
    (with-redefs [loom/fetch-vtt (fn [_id]
                                   {:ok? true :vtt-text "WEBVTT\nhi\n"})]
      (let [r (t/video! {:url "https://www.loom.com/share/abc123def456abc123def456abc123de"
                         :out out :model :small :timeout-s 60})]
        (is (:ok? r))
        (is (= :loom-graphql (:transcript-source r)))
        (is (= out (:vtt-path r)))
        (is (= "WEBVTT\nhi\n" (slurp out)))))
    (fs/delete-tree tmp)))

(deftest loom-fallback-resolves-mp4-then-whisper
  (let [tmp (fs/create-temp-dir)
        out (str (fs/path tmp "01.vtt"))
        downloaded (atom nil)]
    (with-redefs [loom/fetch-vtt        (fn [_id]
                                          {:ok? false
                                           :error {:reason :loom-transcript-unavailable}})
                  loom/video-source-url (fn [_id]
                                          {:ok? true :url "https://cdn.loom.com/x.mp4"})
                  t/download-to-temp!   (fn [url]
                                          (reset! downloaded url)
                                          {:ok? true :path (str (fs/path tmp "x.mp4"))})
                  whisper/run!          (fn [_opts]
                                          (spit out "WEBVTT\nfallback\n")
                                          {:ok? true :vtt-path out})]
      (let [r (t/video! {:url "https://www.loom.com/share/abc123def456abc123def456abc123de"
                         :out out :model :small :timeout-s 60})]
        (is (:ok? r))
        (is (= :whisper (:transcript-source r)))
        (is (= "https://cdn.loom.com/x.mp4" @downloaded))))
    (fs/delete-tree tmp)))

(deftest non-loom-url-goes-straight-to-whisper
  (let [tmp (fs/create-temp-dir)
        out (str (fs/path tmp "01.vtt"))
        downloaded (atom nil)]
    (with-redefs [t/download-to-temp! (fn [url]
                                        (reset! downloaded url)
                                        {:ok? true :path (str (fs/path tmp "x.mp4"))})
                  whisper/run!        (fn [_opts]
                                        (spit out "WEBVTT\nok\n")
                                        {:ok? true :vtt-path out})]
      (let [r (t/video! {:url "https://prod-files-secure.s3/x.mp4"
                         :out out :model :small :timeout-s 60})]
        (is (:ok? r))
        (is (= :whisper (:transcript-source r)))
        (is (= "https://prod-files-secure.s3/x.mp4" @downloaded))))
    (fs/delete-tree tmp)))

(deftest whisper-failure-propagates
  (let [tmp (fs/create-temp-dir)
        out (str (fs/path tmp "01.vtt"))]
    (with-redefs [t/download-to-temp! (fn [_url]
                                        {:ok? true :path (str (fs/path tmp "x.mp4"))})
                  whisper/run!        (fn [_opts]
                                        {:ok? false
                                         :error {:reason :whisper-crashed
                                                 :detail {:exit 1}}})]
      (let [r (t/video! {:url "https://example.com/x.mp4"
                         :out out :model :small :timeout-s 60})]
        (is (not (:ok? r)))
        (is (= :whisper-crashed (-> r :error :reason)))))
    (fs/delete-tree tmp)))

(deftest download-failure-propagates
  (let [tmp (fs/create-temp-dir)
        out (str (fs/path tmp "01.vtt"))]
    (with-redefs [t/download-to-temp! (fn [_url]
                                        {:ok? false
                                         :error {:reason :download-failed
                                                 :detail {:status 404}}})]
      (let [r (t/video! {:url "https://example.com/x.mp4"
                         :out out :model :small :timeout-s 60})]
        (is (not (:ok? r)))
        (is (= :download-failed (-> r :error :reason)))))
    (fs/delete-tree tmp)))
```

- [ ] **Step 3: Run, verify fail**

```
bb nido:test :only nido.transcribe-test
```

Expected: namespace not found.

- [ ] **Step 4: Implement** `src/nido/transcribe.clj`:

```clojure
(ns nido.transcribe
  "URL → VTT dispatch. Loom share/embed URLs use the public GraphQL
   transcript endpoint with a whisper fallback for transcript-disabled
   videos. Anything else downloads + whispers.

   `download-to-temp!` is a redef seam so tests stub network I/O."
  (:require
   [babashka.fs :as fs]
   [babashka.http-client :as http]
   [nido.transcribe.loom :as loom]
   [nido.transcribe.whisper :as whisper]))

(defn download-to-temp!
  "GET `url` to a temp file. Returns {:ok? true :path \"...\"}
   or {:ok? false :error {:reason :download-failed :detail {:status n}}}.

   The spec calls for a 1 GB cap; v1 enforces only the 5-minute HTTP
   timeout. If oversized videos become a real concern, wrap the
   transferTo loop with a byte counter and bail at the limit."
  [url]
  (let [tmp  (fs/create-temp-file {:prefix "nido-transcribe-" :suffix ".bin"})
        path (str tmp)]
    (try
      (let [resp (http/get url {:as :stream :throw false :timeout 300000})]
        (if (= 200 (:status resp))
          (do (with-open [in  (:body resp)
                          out (java.io.FileOutputStream. path)]
                (.transferTo in out))
              {:ok? true :path path})
          (do (fs/delete-if-exists path)
              {:ok? false :error {:reason :download-failed
                                  :detail {:status (:status resp) :url url}}})))
      (catch Exception e
        (fs/delete-if-exists path)
        {:ok? false :error {:reason :download-failed
                            :detail {:exception (str e) :url url}}}))))

(defn- write-vtt! [out vtt-text]
  (fs/create-dirs (fs/parent out))
  (spit out vtt-text))

(defn- whisper-from-url
  "Download URL, run whisper into a sibling out-dir, then move the
   produced VTT to `out`. Cleans up the temp MP4 on success or failure."
  [{:keys [url out model timeout-s]}]
  (let [dl (download-to-temp! url)]
    (if-not (:ok? dl)
      dl
      (let [in-path (:path dl)
            out-dir (str (fs/parent out))]
        (try
          (let [r (whisper/run! {:input    in-path
                                 :model    model
                                 :out-dir  out-dir
                                 :timeout-s timeout-s})]
            (if-not (:ok? r)
              r
              (do (fs/move (:vtt-path r) out {:replace-existing true})
                  {:ok? true :vtt-path out :transcript-source :whisper})))
          (finally
            (fs/delete-if-exists in-path)))))))

(defn video!
  "Transcribe a video URL into a VTT at :out. See ns docstring.
   Required opts: :url :out. Optional: :model (default :small),
   :timeout-s (default 300)."
  [{:keys [url out model timeout-s]
    :or   {model :small timeout-s 300}
    :as   opts}]
  (let [video-id (loom/extract-video-id url)]
    (if-not video-id
      (whisper-from-url (assoc opts :model model :timeout-s timeout-s))
      (let [r (loom/fetch-vtt video-id)]
        (if (:ok? r)
          (do (write-vtt! out (:vtt-text r))
              {:ok? true :vtt-path out :transcript-source :loom-graphql})
          ;; Loom transcript path failed — fall back via MP4 source.
          (let [src (loom/video-source-url video-id)]
            (if-not (:ok? src)
              src
              (whisper-from-url
                (assoc opts
                  :url (:url src)
                  :model model
                  :timeout-s timeout-s)))))))))
```

- [ ] **Step 5: Run, verify pass**

```
bb nido:test :only nido.transcribe-test
```

Expected: all 5 tests pass.

- [ ] **Step 6: Commit**

```
jj desc -m "feat(transcribe): URL dispatch — Loom fast path + whisper fallback"
jj log -r '@-..@' --no-graph
```

---

## Task 4 — `bb nido:transcribe-video` task

**Files:**
- Create: `src/tasks/nido_transcribe.clj`
- Modify: `bb.edn` (add to `:requires` and add task entry)
- Test: `test/nido/tasks/nido_transcribe_test.clj`

CLI shape (per the spec):

```
bb nido:transcribe-video <url> :out <path> [:model :small] [:timeout 5m]
```

`<url>` is positional. `:out` is required. `:model` defaults to `:small`. `:timeout` accepts a duration string (`5m`, `120s`) or seconds as int; defaults to `5m`. On success: print the VTT path on stdout, exit 0. On failure: print an EDN error map on stderr, exit non-zero.

We reuse `nido.task-args` for parsing positionals + opts. Look at `tasks.nido-coordinator-source` for a sibling example of duration parsing.

- [ ] **Step 1: jj hygiene check.** `jj log -r '@-..@' --no-graph`; `jj new` if needed.

- [ ] **Step 2: Failing tests**

Create `test/nido/tasks/nido_transcribe_test.clj`:

```clojure
(ns nido.tasks.nido-transcribe-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.transcribe :as transcribe]
   [tasks.nido-transcribe :as task]))

(deftest parse-duration-accepts-m-and-s-and-ints
  (is (= 300 (task/parse-duration "5m")))
  (is (= 120 (task/parse-duration "120s")))
  (is (= 90  (task/parse-duration "90"))))

(deftest run-prints-vtt-path-on-success
  (let [tmp (fs/create-temp-dir)
        out (str (fs/path tmp "out.vtt"))
        captured-opts (atom nil)]
    (with-redefs [transcribe/video! (fn [opts]
                                       (reset! captured-opts opts)
                                       {:ok? true :vtt-path out
                                        :transcript-source :loom-graphql})]
      (let [stdout (with-out-str
                     (binding [*err* (java.io.StringWriter.)]
                       (try
                         (task/run "https://www.loom.com/share/abc"
                                   ":out" out
                                   ":model" ":small"
                                   ":timeout" "5m")
                         (catch Exception _ nil))))]
        (is (re-find (re-pattern (java.util.regex.Pattern/quote out)) stdout))
        (is (= "https://www.loom.com/share/abc" (:url @captured-opts)))
        (is (= out (:out @captured-opts)))
        (is (= :small (:model @captured-opts)))
        (is (= 300 (:timeout-s @captured-opts)))))
    (fs/delete-tree tmp)))

(deftest run-prints-edn-on-failure-and-exits-nonzero
  (let [exit-code (atom nil)]
    (with-redefs [transcribe/video! (fn [_opts]
                                      {:ok? false
                                       :error {:reason :loom-server-error
                                               :detail {:status 500}}})
                  task/exit!       (fn [code] (reset! exit-code code))]
      (let [stderr-w (java.io.StringWriter.)]
        (binding [*err* stderr-w]
          (task/run "https://www.loom.com/share/abc" ":out" "/tmp/x.vtt"))
        (is (= 1 @exit-code))
        (is (re-find #":loom-server-error" (str stderr-w)))))))
```

- [ ] **Step 3: Run, verify fail**

```
bb nido:test :only nido.tasks.nido-transcribe-test
```

Expected: namespace not found.

- [ ] **Step 4: Implement** `src/tasks/nido_transcribe.clj`:

```clojure
(ns tasks.nido-transcribe
  "bb task entry point for video transcription.

   Usage:
     bb nido:transcribe-video <url> :out <path> [:model :small] [:timeout 5m]"
  (:require
   [clojure.string :as str]
   [nido.task-args :as task-args]
   [nido.transcribe :as transcribe]))

(defn exit! [code] (System/exit code))

(defn parse-duration
  "Parse a duration string into integer seconds. Accepts NNNNs, NNNNm, or
   a bare integer (treated as seconds)."
  [s]
  (cond
    (nil? s)                 nil
    (re-matches #"\d+s?" s)  (Integer/parseInt (str/replace s #"s$" ""))
    (re-matches #"\d+m" s)   (* 60 (Integer/parseInt (str/replace s #"m$" "")))
    :else
    (throw (ex-info (str "Bad duration: " s) {:input s}))))

(defn run
  "bb nido:transcribe-video <url> :out <path> [:model :small] [:timeout 5m]"
  [& args]
  (let [[positionals opts] (task-args/split-args args)
        url   (first positionals)
        out   (:out opts)
        model (or (some-> (:model opts) (str/replace #"^:" "") keyword) :small)
        secs  (or (parse-duration (:timeout opts)) 300)]
    (when (or (str/blank? url) (str/blank? out))
      (binding [*err* *err*]
        (println "Usage: bb nido:transcribe-video <url> :out <path> [:model :small] [:timeout 5m]"))
      (exit! 2))
    (let [r (transcribe/video! {:url url :out out :model model :timeout-s secs})]
      (if (:ok? r)
        (println (:vtt-path r))
        (do (binding [*err* *err*]
              (println (pr-str (:error r))))
            (exit! 1))))))
```

- [ ] **Step 5: Modify `bb.edn`** — add to `:requires` and add the task entry.

In the `:requires` vector inside `:tasks`, append:
```clojure
             [tasks.nido-transcribe :as nido-transcribe]
```

Then add a new task entry near the other `nido:notion:*` entries:
```clojure
  nido:transcribe-video
  {:doc "Transcribe a video URL → VTT: <url> :out <path> [:model :small] [:timeout 5m]"
   :task (apply nido-transcribe/run *command-line-args*)}
```

- [ ] **Step 6: Run, verify pass**

```
bb nido:test :only nido.tasks.nido-transcribe-test
bb tasks | grep nido:transcribe-video
```

Both should succeed.

- [ ] **Step 7: Commit**

```
jj desc -m "feat(tasks/transcribe): bb nido:transcribe-video CLI"
jj log -r '@-..@' --no-graph
```

---

## Task 5 — Manual smoke verification

**Files:** none (manual sanity check; no commit).

This step is not a unit test — it exercises the real Loom GraphQL endpoint and (optionally) real whisper. Run it once locally; do NOT commit any captured artifacts.

- [ ] **Step 1: Pick a public Loom video.** Any short (< 1 min) public share URL — Loom's own product demos work well. Verify the owner has transcripts enabled (most public videos do).

- [ ] **Step 2: Run the task against the real URL.**

```
bb nido:transcribe-video https://www.loom.com/share/<some-real-id> :out /tmp/loom.vtt
cat /tmp/loom.vtt | head -20
```

Expected: a VTT file with `WEBVTT` header and reasonable timestamps. Operation should complete in under 5 seconds (it's the Loom fast path).

- [ ] **Step 3: Force the whisper fallback (optional).** Pick a non-Loom MP4 URL (or a Loom video whose owner has transcripts disabled).

```
bb nido:transcribe-video https://example.com/short-clip.mp4 :out /tmp/clip.vtt
cat /tmp/clip.vtt | head -20
```

Expected: whisper runs, prints progress, produces a VTT. Takes ~real-time for short clips on Apple Silicon CPU at `small`.

- [ ] **Step 4: Verify error formatting.** Run against a clearly-bad URL:

```
bb nido:transcribe-video https://example.com/does-not-exist.mp4 :out /tmp/bad.vtt
echo "exit: $?"
```

Expected: an EDN error map on stderr, exit code 1, no `/tmp/bad.vtt` file.

No commit — manual verification only.

---

## Self-Review

Before declaring Stage 1 done, check:

- **`bb nido:test :only nido.transcribe`** runs all three test namespaces and is green.
- **`bb tasks | grep transcribe`** shows the new task.
- **The Loom GraphQL fast path returns in under 5 seconds** for a short public video.
- **The whisper fallback writes a non-empty VTT** for at least one short MP4.
- **Error EDN on stderr is structured** (not a stack trace) for the bad-URL case.

Once green, Stage 2 (`2026-05-19-notion-preprocess-ticket.md`) can begin — it depends on `nido.transcribe/video!` and the bb task being callable.
