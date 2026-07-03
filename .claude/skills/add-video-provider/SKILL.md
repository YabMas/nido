---
name: add-video-provider
description: Autonomously verify a brian "Unsupported video provider attempted" Slack alert and, if brian can extract captions for the provider, open a ready-for-review PR adding it to brian's ingestion allow-list. Fired by the :video-provider-alerts trigger on #product-alerts. Runs in a :full brian session.
---

# /add-video-provider

Harness-side, owned by nido. Injected into every session. Fired by the
`:video-provider-alerts` trigger on brian's `#product-alerts` channel
(`C0B7NAC47QT`). Runs **fully autonomously** — the safety comes from fail-closed
verification, not a human gate.

**This skill writes to the nido ledger and to Slack. It NEVER writes to Notion.**

## Inputs

The run record is at `./run-link/run.edn`. Read `:event-payload` — the normalised
Slack message: `:adapter :slack-message`, `:id` (e.g. `slack-C0B7NAC47QT-1718…`),
`:channel`, `:ts`, `:url` (permalink), `:text` (the full alert body). The alert body
looks like:

```
[DEV] :warning: *Unsupported video provider attempted*
Domain: `example.com`
URL: https://example.com/somevideo
Source: ingest
User: 12345
```

The **ledger key** for this run is `vp-<domain>` (e.g. `vp-vimeo.com`). `bb nido:ticket:*`
accepts any string as `:br`.

## Step 0 — Acknowledge on Slack

Read `:channel` and `:ts` from `./run-link/run.edn` `:event-payload`. React :eyes::

```bash
bb nido:slack:react :channel <channel> :ts <ts> :name eyes
```

(An `already_reacted` error is benign — ignore it and continue.)

## Step 1 — Parse the alert

From `:text`, read the `Domain:`, `URL:`, and `Source:` lines. **Slack auto-linkifies URLs
and bare domains in message text**, so the values arrive wrapped in Slack link markup — the
URL as `<https://…>` or `<https://…|label>`, and the backticked domain as
`` `<http://dom|dom>` ``. Strip the markup before using anything: a `<X>` token is just `X`,
and a `<X|Y>` token has the real link in `X` (the part before the `|`). Also drop any
`[DEV]`/`[PROD]` prefix and `:warning:` — the filter already matched the title.

- **Ingest URL** = the `URL:` line with Slack markup stripped (take the part before any `|`,
  drop the surrounding `<>`). Keep query params. This is the exact URL you verify and cite.
  Example: `<https://www.ardmediathek.de/video/…?isChildContent>` → `https://www.ardmediathek.de/video/…?isChildContent`.
- **Domain** = derive from the ingest URL's host (strip a leading `www.`), reduced to its
  **registrable domain** — typically the last two labels (`www.ardmediathek.de` →
  `ardmediathek.de`, `player.vimeo.com` → `vimeo.com`); use judgment for multi-part TLDs.
  Cross-check against the `Domain:` field (also markup-stripped). Derive a platform keyword
  from the main label (`ardmediathek.de` → `:ardmediathek`, `vimeo.com` → `:vimeo`).

If `Source` is not `ingest` (i.e. `embed`), this is out of scope (the Quill embed path
needs per-site conversion logic). Reply `skipped-embed` and stop:

```bash
bb nido:slack:reply :channel <channel> :thread-ts <ts> :text "add-video-provider: skipped-embed — <domain> came from the editor embed path, not ingestion."
```

## Step 2 — Dedup / applicability

Open (or find) the ledger record — idempotent; if it already exists this run is a re-alert:

```bash
bb nido:ticket:show :project brian :br vp-<domain>
```

If a record already exists, branch on its prior disposition (reply with the ACCURATE prior
outcome — do not blanket-label everything `already-supported`):
- `:disposition applied` (a PR was opened) or `:disposition already-supported` → reply
  `already-supported` (link the PR if present) and stop.
- `:disposition not-supported` → reply `not-supported` ("previously assessed — no usable
  captions") and stop.
- `:disposition failed` → a prior *transient* failure (eval timeout, REPL down). **Re-attempt**:
  fall through and continue with verification below — the failure may have cleared.
- no record, or only a non-terminal `:investigating` (a crashed prior run) → open/continue it:

```bash
bb nido:ticket:open :project brian :br vp-<domain> :url "<permalink>" :title "Add video provider <domain>" :opened-by video-provider-alerts
```

Discover the session nREPL port (session-local): read `./worktree/.nrepl-port`; if
absent, `clj-nrepl-eval --discover-ports`. **Ping-verify** the port and confirm brian is
loaded:

```bash
clj-nrepl-eval -p <port> "(+ 1 1)"
clj-nrepl-eval -p <port> "(some? (find-ns 'brian.model.video))"
```

If no working brian REPL is reachable, treat it as `failed-to-process` (Step 7) — do NOT
start services yourself.

Check whether brian already supports the domain (reuses brian's own boundary logic):

```bash
clj-nrepl-eval -p <port> "(brian.model.video/detect-platform-by-url \"https://<domain>/probe\")"
```

Non-nil ⇒ already in the allow-list. Reply `already-supported`, complete the ledger
(`:status triaged :disposition already-supported`), and stop.

## Step 3 — Strict verification (fail-closed, via brian's own code)

Prove brian can actually produce a transcript for the alert URL, using brian's real
extraction+cleaning path (`fetch-metadata-with-transcript` = `fetch-metadata` →
`get-captions-url` → `slurp` → `clean-vtt-transcript`):

```bash
clj-nrepl-eval -p <port> --timeout 60000 "(do (require '[brian.server-components.youtube-dlp :as ydlp] :reload) (let [m (try (ydlp/fetch-metadata-with-transcript \"<ingest-url>\") (catch Exception e {:error (ex-message e)})) t (:transcript m)] {:error (:error m) :has-transcript (boolean (and t (not (clojure.string/blank? t)))) :length (count (or t \"\")) :title (:title m)}))"
```

Interpret the returned map:
- The eval did not return a map at all — it timed out (`--timeout 60000`), errored in the
  transport, or returned a non-map value ⇒ treat as `failed-to-process` — reply, complete
  ledger `:disposition failed`, stop. **No PR.** (Fail closed: only a well-formed map with
  `:has-transcript true` may proceed.)
- `:error` present ⇒ extraction blew up (yt-dlp error, unfetchable). This is
  `failed-to-process` — reply, complete ledger `:disposition failed`, stop. **No PR.**
- `:has-transcript false` ⇒ yt-dlp has no usable captions for this provider; brian can't
  ingest it. This is `not-supported` — reply, complete ledger `:disposition not-supported`,
  stop. **No PR.**
- `:has-transcript true` ⇒ proceed. Note `:length` for the PR body.

## Step 4 — Edit the allow-list + test

In `./worktree`, edit `src/main/brian/model/video.clj` — add the provider to `platforms`:

```clojure
(def platforms
  {:youtube {:domains #{"youtube.com" "youtu.be"}}
   :<provider> {:domains #{"<domain>"}}})
```

Edit `src/test/brian/model/video_test.clj`:
- Add detection cases inside `detect-platform-by-url-test` — a positive match **and** a
  host-only false-positive assertion. Detection is **host-based**: `detect-platform-by-url`
  matches the URL's parsed host (exact or dotted-subdomain), NOT a substring of the whole
  URL — so a domain appearing in the path/query must never match. Lock that in for the new
  provider (this is the invariant a past reviewer flagged; keep proving it per provider):
  ```clojure
  (testing "Identifies <Provider> URLs"
    (is (= :<provider> (sut/detect-platform-by-url "<a real URL on the domain>")))
    ;; host-only matching — the domain in a path/query must NOT false-match
    (is (nil? (sut/detect-platform-by-url "https://example.com/<domain>/x"))))
  ```
  If that false-positive assertion ever fails, the fix is in `detect-platform-by-url`
  itself (it must parse the host and compare only that) — do not work around it per-provider.
- **Fix any negative test that references this exact domain.** The existing
  `"Returns nil for unsupported platforms"` test uses a specific domain as its
  "unsupported" example — if that domain is the one you're adding, the test will flip red.
  Replace it with a different, still-unsupported domain.

## Step 5 — Regression proof

Reload and run the focused test against the running REPL (`:reload`, not `:reload-all`):

```bash
clj-nrepl-eval -p <port> "(do (require '[brian.model.video] :reload) (require '[brian.model.video-test] :reload) (clojure.test/run-tests 'brian.model.video-test))"
```

Any `:fail`/`:error` > 0 ⇒ `failed-to-process`: reply, complete ledger `:disposition failed`,
stop. **No PR.** (Fix the edit and re-run if the failure is your own negative-test oversight
from Step 4; otherwise halt to a human.) If the eval times out or does not return a
`run-tests` summary map at all ⇒ also `failed-to-process`, **no PR** — never open a PR on
unproven tests.

## Step 6 — Open the ready PR

Commit the change (jj workspace — use jj, see the jujutsu skill; one focused commit):

```bash
cd worktree
jj commit -m "feat(video): support <domain> for video ingestion"
cd ..
```

Then open the PR by following the **prepare-draft-pr** skill's steps (it reads
`./run-link/run.edn`, pushes, creates the PR, wires the session `:pr` link, and the
workstream `:github` ref that the merge poller correlates on). **One override:** this run's
natural ledger key (`:event-payload :id`) is the per-message slack id, but you keyed your
record on `vp-<domain>` in Step 2 — so when you do prepare-draft-pr's `:pr-opened` ledger
append, pass `:br vp-<domain>` explicitly (do NOT let it default to the slack `:id`), so the
PR event lands on the same `vp-<domain>` record as your open/complete. Use this PR title/body:

- Title: `feat(video): support <domain> for video ingestion`
- Body: what it does; the evidence — sample URL, captions confirmed, transcript length
  (`:length` from Step 3), `brian.model.video-test` green; a note that this was opened
  autonomously by nido's `/add-video-provider` from a `#product-alerts` alert.

After prepare-draft-pr completes, flip the PR from draft to ready (idempotent, handles the
reuse case):

```bash
cd worktree && gh pr ready
```

Complete the ledger:

```bash
bb nido:ticket:complete :project brian :br vp-<domain> :status triaged :disposition applied
```

## Step 7 — Report on Slack

Post the terminal status as a threaded reply (`thread-ts` = the alert `:ts`). Exactly one of:

- `pr-ready` — include the PR URL.
- `not-supported` — yt-dlp has no usable captions for `<domain>`; no PR.
- `failed-to-process` — verification errored or tests went red; needs a human look.
- `already-supported` — `<domain>` already in the allow-list.
- `skipped-embed` — embed-path alert, out of scope.

```bash
bb nido:slack:reply :channel <channel> :thread-ts <ts> :text "add-video-provider: pr-ready — <domain> verified (transcript N chars), tests green. PR: <url>"
```

## Idempotency

Re-invocation for the same domain is safe: Step 2 finds the existing `vp-<domain>` ledger
record and short-circuits; `bb nido:slack:react` tolerates `already_reacted`;
prepare-draft-pr reuses an existing PR.
