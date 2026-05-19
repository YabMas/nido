# Notion ticket preprocessing (video transcription)

**Status:** spec, awaiting implementation plans
**Date:** 2026-05-19
**Companion specs:** [Notion triage agent](2026-05-18-notion-triage-agent-design.md), [Stage 5 Notion source](2026-05-15-nido-stage-5-notion-source-design.md), [nido coordination layer](2026-05-13-nido-coordination-layer-design.md)

## Goal

Let nido's autonomous agents work with the parts of Notion tickets they currently can't read — videos. Most bug reports include either a Loom share URL or a screen-capture uploaded directly to Notion. Today the triage agent can't process either; the human ends up watching the video themselves to know what's reported. This design adds a preprocessing pass, owned by the coordinator, that stages a transcript of every video on the page **before** claude is spawned. The agent reads the transcripts as just another investigation artifact.

The design is decomposed into independently useful layers so future preprocessors (image OCR, attached PDFs, etc.) can land without re-architecting.

## Non-goals

- Cross-Run / cross-ticket transcript caching. Each Run preprocesses from scratch. Cheap enough at v1 volumes; add later only if measurable.
- A general-purpose preprocessor framework. `:preprocess [:notion-ticket]` is a small dispatch table with one entry; we don't ship a protocol-and-registry abstraction until a second preprocessor lands.
- Whisper.cpp / GPU acceleration. The user already has `openai-whisper` (Python, Homebrew) installed; v1 uses it as-is. Swap later if `small`-on-CPU is the bottleneck.
- Loom URL detection inside `rich_text` paragraph blocks. Only `video` / `embed` / `bookmark` blocks are classified. Notion's auto-embed turns pasted Loom URLs into embed blocks in practice, so the gap is theoretical.
- Coordinator-side preprocessor concurrency / pool. Preprocessors run inline on the worker thread that owns the Run slot. If that becomes a bottleneck, a separate pool is the right next step — not v1.
- Live transcription progress in the TUI. The Run shows `:preprocessing` phase; that's it.

## Constraints carried in from prior conversation

- **Triage agents can't see videos today.** The most common form of a bug report in brian's Notion intake is "here's a Loom of the bug" with minimal text. Triage quality depends on getting at the video content.
- **Two video sources cover the realistic intake.** Loom (public share URLs, transcripts publicly fetchable for most videos) and Notion-uploaded screen captures (signed S3 URLs from the Notion API). External video hosts other than Loom are rare enough to fold into the whisper-fallback path.
- **Loom's public GraphQL endpoint exposes transcripts without auth.** `POST https://www.loom.com/graphql` with the `FetchVideoTranscript` operation returns a `captions_source_url` (a VTT) for any public video whose owner has transcripts enabled. The yt-dlp Loom extractor confirms the operation shape and headers. When the owner disabled transcripts or the video is password-protected, we fall back to fetching the MP4 via `GetVideoSource` and running whisper.
- **The triage skill should stay focused on triage.** Preprocessing is plumbing; threading it through the skill markdown would muddy responsibilities. Putting it in the coordinator keeps the skill's instruction count short and consistent across future skills that consume Notion pages.
- **Per-Run budget already exists.** Preprocessing must not eat the agent's `:limits.budget` — that's reserved for investigation + human review time. Preprocessing gets its own `:preprocess-budget` dimension.
- **Autonomous nido features need first-class safety.** Default off (covered upstream by the triage trigger's `:dry-run?`), hard caps, structured failure detail, visible state.

## Glossary

- **Preprocessor** — a named pre-Run step declared in a trigger's `:preprocess` vector. v1 ships one: `:notion-ticket`.
- **Preprocess budget** — wall-clock cap on the preprocessing phase, distinct from the Run's investigation budget.
- **`:preprocessing` phase** — new Run phase between `:queued` and `:investigating`, written to `_run-status.edn`.
- **L1 / L2 / L3 / L4** — the four layers of the implementation; see the table below.
- **Transcript primitive** — `bb nido:transcribe-video <url>`. Pure URL → VTT; knows nothing about Notion. Reusable for one-off triage and for future non-Notion consumers.

## Architecture

```
Coordinator (executor) — Run lifecycle gains a :preprocessing phase
   envelope dequeued
        │
        ▼
   nido.coordinator.preprocess/run!         ← L4 dispatch
       reads trigger :preprocess [:notion-ticket]
       for each name → shell-out to bb task with :run-dir
        │
        ▼
   bb nido:notion:preprocess-ticket          ← L3 composer task
       :page <id-from-envelope> :out <run-dir>/preprocess
        │
        ▼
   nido.notion.preprocess/preprocess-ticket!
       1. retrieve-block-children (recursive page walk)
       2. classify video blocks → {:url :kind :loom|:notion-upload|:other}
       3. for each video URL: nido.transcribe/video!
       4. write manifest.edn + transcripts.md
        │
        ▼
   nido.transcribe/video!                    ← L2 primitive
       :loom → nido.transcribe.loom/fetch-vtt or fall back
       else  → download-to-temp → nido.transcribe.whisper/run!
        │
        ▼
   (success or failed-with-note) → manifest entry
        │
        ▼
   Coordinator transitions Run :preprocessing → :investigating
       session:up + spawn claude as today
        │
        ▼
   Briefing (CLAUDE.md) gains a pointer to ./run-link/preprocess/manifest.edn
```

### Four layers, each independently testable

| Layer | Namespace / surface | Single responsibility |
|---|---|---|
| L1 | `nido.transcribe.whisper` — `(run! {:input :model :out-dir}) → {:ok? :vtt-path :error}` | Shell-out to the `whisper` CLI |
| L2 | `nido.transcribe.loom` — `(fetch-vtt video-id) → {:ok? :vtt-text :error}`, `(video-source-url video-id) → url` | Loom GraphQL client |
| L2 | `nido.transcribe/video!` + `bb nido:transcribe-video <url>` | URL → VTT dispatch (Loom fast path, whisper fallback) |
| L3 | `nido.notion.preprocess` + `bb nido:notion:preprocess-ticket` | Walk a page, call `video!` per video, write manifest |
| L4 | `nido.coordinator.preprocess` | Registry + dispatch from trigger `:preprocess` config; budget; phase transition |

### What does not change

- The triage-bug skill instructions stay focused on triage. One line near the top points the agent at `./run-link/preprocess/manifest.edn`. No `bb` invocation lives in skill markdown.
- `nido.notion.client` keeps its data-source query code; we extend it with `retrieve-block-children`.
- Session profile, executor slot scheduler, queue, watchdog — untouched.
- Existing triggers with no `:preprocess` key see no behavior change. The smoke trigger keeps running as today.

## Layer 1 — `nido.transcribe.whisper`

Thin wrapper around the OpenAI whisper Python CLI:

```bash
whisper <input.mp4> --model small --output_format vtt \
                    --output_dir <out-dir> --language en --fp16 False
```

v1 hardcodes the model at `:small` end-to-end — the `bb` tasks accept `:model` and the L1 API takes `:model`, but no trigger-config plumbing surfaces it. A per-trigger `:whisper {:model ...}` knob is named as a follow-up; until then, escape-hatching means calling `bb nido:transcribe-video` directly with `:model :medium`.

API:

```clojure
(run! {:input "/tmp/x.mp4"
       :model :small          ;; :tiny|:base|:small|:medium|:large-v3
       :out-dir "/.../preprocess"
       :timeout-s 300})
;; → {:ok? true  :vtt-path "..."}
;;   {:ok? false :error {:reason :whisper-crashed :detail {...}}}
;;   {:ok? false :error {:reason :whisper-timeout :detail {:limit-s 300}}}
```

Stdout/stderr go to `<out-dir>/whisper-<idx>.log` so failures are diagnosable. Apple Silicon CPU; we are not optimizing for GPU here.

`sh!` and the timeout mechanism are redef seams for testing — the same pattern `nido.notion.client` already uses.

## Layer 2 — `nido.transcribe.loom`

Loom GraphQL client. Single endpoint, three operations matter:

```
POST https://www.loom.com/graphql
Headers:
  Content-Type: application/json
  Accept: application/json
  Origin: https://www.loom.com
  apollographql-client-name: web
  graphql-operation-name: <op>
```

**`FetchVideoTranscript`** — primary path:

```graphql
query FetchVideoTranscript($videoId: ID!, $password: String) {
  fetchVideoTranscript(videoId: $videoId, password: $password) {
    ... on VideoTranscriptDetails { captions_source_url }
    ... on PublicApiError { message }
  }
}
```

If `captions_source_url` is non-null, GET it directly — it's a VTT file. Return `{:ok? true :vtt-text "..."}`.

**`GetVideoSource`** — fallback when transcript is disabled. Returns a CDN MP4 URL; hand it to `nido.transcribe/video!` for the whisper fallback path.

**Video-id extraction** — accepts:
- `https://www.loom.com/share/<32-hex>`
- `https://www.loom.com/embed/<32-hex>`
- Same with trailing query params or path segments

Wrong domain or unparseable id → `{:ok? false :error {:reason :not-a-loom-url}}`. Caller is expected to dispatch on this rather than `video!` itself.

`http-request` is a redef seam, same shape as `nido.notion.client`. Tests stub GraphQL responses for: transcript available, transcript disabled (`captions_source_url` null), password-protected, 5xx, network error.

## Layer 2 — `nido.transcribe/video!` (dispatch) + `bb nido:transcribe-video`

URL-level primitive. Knows nothing about Notion. Reusable:

```clojure
(video! {:url "https://www.loom.com/share/abc..."
         :out "/.../01-loom-abc.vtt"
         :model :small
         :timeout-s 300})
;; → {:ok? true  :vtt-path "..." :transcript-source :loom-graphql}
;;   {:ok? true  :vtt-path "..." :transcript-source :whisper}
;;   {:ok? false :error {:reason :... :detail {...}}}
```

Dispatch:

1. If URL matches a Loom share/embed pattern → try `loom/fetch-vtt`. On success, write VTT to `:out` and return `:transcript-source :loom-graphql`.
2. On Loom transcript unavailable / password / 5xx → try `loom/video-source-url` → fall through to step 3 with the resolved MP4 URL.
3. Any other video URL (including the Loom fallback) → `download-to-temp` → `whisper/run!`. Return `:transcript-source :whisper`.

CLI shape:

```
bb nido:transcribe-video <url>
   :out <path>          ;; default: stdout
   :model :small        ;; default
   :timeout 5m          ;; default; total wall-clock
```

On success: prints an EDN map `{:vtt-path "..." :transcript-source :loom-graphql|:whisper}` to stdout, exit 0. On failure: non-zero with a single-line EDN error map on stderr. Self-contained, manually exercisable against any URL.

## Layer 3 — `nido.notion.preprocess` + `bb nido:notion:preprocess-ticket`

Walks a Notion page and composes `transcribe/video!` over every video found.

### Block discovery

Extends `nido.notion.client` with a single new function:

```clojure
(retrieve-block-children block-id token {:keys [start-cursor]})
;; → {:status 200 :results [...] :has_more bool :next_cursor str}
;;   {:status N   :error :kw}
```

L3 wraps this with a recursive walker bounded at **depth 10** and **1000 total blocks** so a pathological page can't hang preprocessing.

### Classification

For each block:

- `video.external.url` matching `loom.com/(share|embed)/<id>` → `:loom`
- `video.file.url` (Notion-hosted, signed S3 URL) → `:notion-upload`
- `embed.url` matching Loom pattern → `:loom`
- `bookmark.url` matching Loom pattern → `:loom`
- `video.external.url` or `video.file.url` ending in `.mp4|.mov|.webm|.mkv` and not Loom → `:other`
- Anything else → ignored

Rich-text paragraph blocks are not scanned for video URLs. In practice Notion auto-converts pasted Loom URLs to embed blocks, so the gap is theoretical and v1 does not pay for it.

### Composition

```clojure
(preprocess-ticket! {:page-id "..."
                     :out-dir "<run-dir>/preprocess"
                     :budget-s 600          ;; from :preprocess-budget
                     :token "..."})
;; → {:ok? true  :manifest {...}}
;;   {:ok? false :error {:reason :... :detail {...}}}
```

For each classified video, allocate `max(60, budget / n-videos)` seconds, then shell out to `bb nido:transcribe-video`. Aggregate results into `manifest.edn`. Always write `transcripts.md` (the agent-readable digest), even on partial failure.

Page-level Notion failures (401, 5xx during `retrieve-block-children`) abort the preprocessor with a structured error and no manifest written. The coordinator surfaces this as `:preprocess-failed`.

### CLI shape

```
bb nido:notion:preprocess-ticket
   :page <page-id>         ;; required
   :out <dir>              ;; required (coordinator passes <run-dir>/preprocess)
   :budget 10m             ;; default
```

Exit 0 on success or partial-failure (per-video failures are not preprocessor failures). Non-zero on page-level failure with a structured EDN error on stderr.

### Manifest schema (`manifest.edn`)

```clojure
{:page-id "..."
 :page-last-edited-time "2026-05-19T..."   ;; snapshot for the triage apply step
 :generated-at "2026-05-19T..."
 :videos
 [{:idx 1
   :url "https://www.loom.com/share/<id>"
   :kind :loom
   :transcript-source :loom-graphql        ;; or :whisper or :none
   :status :ok                             ;; :ok | :failed
   :vtt-path "01-loom-<id>.vtt"
   :error nil}
  {:idx 2
   :url "https://prod-files-secure.s3..."
   :kind :notion-upload
   :transcript-source :whisper
   :status :failed
   :vtt-path nil
   :error {:reason :whisper-crashed :detail {...}}}]}
```

`:page-last-edited-time` is captured during the page walk so the triage apply step's optimistic-concurrency check has a defined snapshot point — preprocessing does not introduce a new race window.

### Agent-readable digest (`transcripts.md`)

```markdown
# Pre-staged transcripts (2 videos)

## 1. Loom — 3m24s — https://www.loom.com/share/abc...
Transcript source: Loom (official). Full VTT: `01-loom-abc.vtt`.

> First few lines for context:
> When I click the create button, the modal flashes briefly
> and then closes without saving. Steps to reproduce: ...

## 2. ⚠️ Notion upload — failed
Source: `https://prod-files-secure.s3...` (kind: notion-upload).
Reason: whisper crashed (see `whisper-2.log`). Watch the video
manually if needed; nothing has been pre-staged.
```

A ~300-character preview per video plus the path to the full `.vtt`. The agent reads `.vtt` files on demand; the digest gives enough flavor to decide whether a given video is central to the bug report.

## Layer 4 — `nido.coordinator.preprocess`

New namespace. Tiny dispatch layer:

```clojure
(defn run!
  "Run preprocessors for an envelope before claude is spawned.
   Returns {:ok? bool :error? <map>}."
  [{:keys [envelope run-dir trigger-config]}]
  ...)
```

Internals:

- Read `:preprocess` from `trigger-config`. Empty / missing → return `{:ok? true}` immediately (no behavior change for existing triggers).
- Read `:limits.preprocess-budget` from `trigger-config` (default `10m`). Parsed as a duration string the same way `:limits.budget` already is.
- For each name in the vector, look up its implementation (currently a single-entry map: `{:notion-ticket invoke-notion-ticket!}`).
- Each implementation receives `{:envelope :run-dir :budget-s}` and shells out to the matching `bb` task. Stdout/stderr captured into `<run-dir>/preprocess/<name>.log`.
- If any preprocessor exits non-zero, return `{:ok? false :error {:reason :preprocess-failed :preprocessor <kw> :detail <edn>}}`. **Claude is not spawned.** Run terminates `:failed` and counts toward the breaker.
- On success, write `:preprocessing-completed-at` to the Run record and transition phase.

Run lifecycle gains the `:preprocessing` phase. Phase set in `_run-status.edn` on enter / exit. TUI runs surface picks it up via the existing phase column — no new TUI code.

### Trigger config

```clojure
{:name :triage-new
 :source {:type :notion-view :view :new-reports :poll "2m"}
 :preprocess [:notion-ticket]               ;; NEW
 :skill :triage-bug
 :session-profile :lite
 :priority 10
 :limits {:budget "15m"
          :preprocess-budget "10m"          ;; NEW; default 10m
          :max-failures 3}}
```

`:preprocess` is a vector to keep the future shape obvious — even though v1 only ever sees `[:notion-ticket]`.

`:preprocess-budget` defaults to `10m`. It is a wall-clock cap on the whole preprocessing phase, regardless of how many preprocessors are configured or how many videos are on the page. If hit, the Run fails with `:reason :preprocess-timeout`.

### Envelope payload contract

The `:notion-ticket` preprocessor needs `:page-id` on the envelope. The Notion source already includes it; no change needed there. If `:page-id` is missing (e.g. a manually-fired envelope), the preprocessor fails fast with `:reason :missing-page-id`.

## Error handling

Three failure boundaries, three policies:

| Boundary | Failure | Policy |
|---|---|---|
| Per-video, inside L3 | Loom 404 / password / transcript disabled / whisper crash / oversized / per-video timeout | Mark `:status :failed` in manifest with structured `:reason` + `:detail`. `preprocess-ticket` exits 0; agent sees the failure in `transcripts.md` and proceeds. |
| Per-preprocessor, in L4 | `bb` exits non-zero, hits `:preprocess-budget`, or process dies | Mark Run `:preprocess-failed` with `:reason`. Claude is **not** spawned; Run terminates `:failed` and counts toward the breaker. Saves the human review window from being wasted on a half-baked session. |
| Whole-page walk | Notion API 401 / 5xx during `retrieve-block-children` | Same as preprocessor failure above. Surfaces auth/network problems loudly rather than silently shipping an empty manifest. |

### Structured failure detail

Always keys + a short detail map. No stringly-typed reasons.

```clojure
{:reason :loom-transcript-unavailable :detail {:video-id "..." :api-error "NoTranscript"}}
{:reason :whisper-timeout            :detail {:input "01.mp4" :elapsed-s 280 :limit-s 240}}
{:reason :video-too-large            :detail {:bytes 1.4e9 :limit-bytes 1e9}}
{:reason :missing-page-id            :detail {:envelope-keys [:source :url :title]}}
{:reason :notion-auth                :detail {:http-status 401}}
{:reason :preprocess-timeout         :detail {:limit-s 600 :elapsed-s 612}}
```

Failures are logged to `agent.log` (so they appear in the existing Run log stream) and recorded in `manifest.edn` so the triage agent can quote a specific reason in its report.

### Per-video hard caps (defaults; configurable later)

- Download: 5 min / 1 GB.
- Whisper: 5 min wall-clock per video (covers ~15 min of audio at `small`).
- Total per-page: capped by `:preprocess-budget` (10 min default).

### Idempotency on Run re-spawn

Preprocessing is not persisted as a separate phase artifact outside `<run-dir>/preprocess/`. If a Run is reconciled and re-spawns (e.g. coordinator restart between dequeue and spawn), preprocessing runs again from scratch into a fresh `<run-dir>/preprocess/`. No cross-Run cache, so no stale data to handle.

### Optimistic concurrency with the triage apply step

The triage spec already captures `last_edited_time` for the apply step. Preprocessing snapshots `last_edited_time` during the page walk and writes it into `manifest.edn` as `:page-last-edited-time`. If a human edits the page during the agent's investigation, the apply step's existing concurrency check covers the same semantics — preprocessing does not add a new race window.

## Testing

**L1 (whisper) — `nido.transcribe.whisper`**
- `sh!` redef seam. Unit tests assert command construction (`--model small --output_format vtt --output_dir ...`) and parse output paths.
- One integration test against a small bundled WAV (~5s in `test/resources/`) gated by `WHISPER_INTEGRATION=1` so CI doesn't pay the cost.

**L2 (Loom) — `nido.transcribe.loom`**
- `http-request` redef seam. Tests stub GraphQL responses for: happy path (`captions_source_url`), transcript disabled (null), password-protected (`PublicApiError`), 5xx, network error.
- Video-id extraction tests for share / embed / trailing-query / shortened URLs.

**L2 (dispatch) — `nido.transcribe/video!`**
- L2-Loom and L1-whisper stubbed. Assert: Loom URL → tries Loom first; on Loom failure → falls back to whisper; non-Loom → whisper directly; bad URL → structured error.

**L3 — `nido.notion.preprocess`**
- `retrieve-block-children` stub returns canned Notion responses with mixed block types (`video`/`embed`/`bookmark`/`paragraph`-with-Loom-URL). Assert classifier picks the right kinds and ignores non-video blocks.
- `transcribe-video` stubbed; assert `manifest.edn` and `transcripts.md` shape, exit code, partial-failure handling, depth/total-blocks bounds.

**L4 — `nido.coordinator.preprocess`**
- bb-process shell-out stubbed. Tests assert: dispatch reads `:preprocess` from trigger config, applies `:preprocess-budget`, transitions phase, does not spawn claude on failure.
- One end-to-end test fires an envelope through executor → preprocess → spawn (both stubbed) and verifies phase transitions in `_run-status.edn`.

**No real Loom or Notion API calls in unit tests.** A separate `bb nido:transcribe-video:smoke <url>` task can be run manually against a real public Loom share URL for occasional sanity checks; not in CI.

## Rollout

Four sequenced stages. Each ships independently; only the ordering matters.

1. **L1 + L2 — whisper module + Loom client + dispatch + `bb nido:transcribe-video`.** Standalone URL-level primitive, manually testable against any URL. No Notion or coordinator involvement.
2. **L3 — `nido.notion.preprocess` + `bb nido:notion:preprocess-ticket` + `retrieve-block-children` extension.** Manually run against a real bug-report page id to validate end-to-end walk → transcribe → manifest. Still no coordinator changes.
3. **L4 — coordinator preprocessor dispatch + `:preprocess` / `:preprocess-budget` trigger keys + Run phase `:preprocessing`.** Wire to a smoke trigger first (`bb nido:trigger:fire` with a known page id). Verify the Run transitions cleanly and `manifest.edn` is on disk before claude launches.
4. **Triage triggers adopt `:preprocess [:notion-ticket]`.** Add the key to `triage-new` and `triage-backlog`. Update the triage-bug skill briefing to point at `./run-link/preprocess/`. Ships behind the existing `:dry-run? true`. Quality assessment against the real backlog. **Depends on the triage-bug skill landing** — see Plan D in the companion triage spec. If preprocessing is ready first, leave the trigger keys off until the skill ships; preprocessing without a consumer is a no-op cost, not a correctness problem.

Each stage gets its own implementation plan after this spec is signed off.

## Open follow-ups (out of scope for v1)

- **Cross-Run / project-level transcript cache.** Adds a key-derivation step (Loom video-id is natural; Notion uploads need a hash of bytes since signed-URL params rotate). Add when measurable cost emerges.
- **`whisper.cpp` integration.** ~5× speedup on Apple Silicon. Add when `small`-on-CPU is the budget bottleneck.
- **Image OCR / PDF preprocessors.** Same dispatch layer; new `:preprocess [...]` entries. Add when an agent has a clear use.
- **Per-preprocessor concurrency pool.** Separate worker pool, decouples preprocess time from Run-slot occupancy. Add only if the existing global cap becomes a measurable bottleneck for triage throughput.
- **Loom URL detection in rich_text paragraphs.** Cheap to add; deferred until a real ticket gets missed by classification.
- **Live transcription progress in TUI.** Today's `:preprocessing` phase is binary. A "video 2/3, whisper running" surface would be friendly but isn't load-bearing.

## What this design explicitly does not introduce

- **No generic preprocessor protocol.** `:preprocess [:notion-ticket]` is a one-entry dispatch map. Adding `:images-ocr` later is a small addition; we don't ship a registry-with-protocols when we have one consumer.
- **No coordinator-side parallelism for preprocessors.** Inline on the worker thread that owns the Run slot.
- **No new TUI surface.** `:preprocessing` reuses the existing phase column.
- **No backfill CLI for already-spawned Runs.** New `:preprocess` config only affects newly-dequeued envelopes. Existing Runs in flight keep behavior unchanged.
- **No cross-Run idempotency cache.** A Run that re-spawns rewrites its `preprocess/` directory from scratch.
