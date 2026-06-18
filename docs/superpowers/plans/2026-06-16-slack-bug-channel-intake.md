# Slack Bug-Channel Intake Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Slack bug channel as a parallel autonomous source into nido's existing triage/workstream pipeline — every new top-level message auto-fires a triage session whose verdict stays in nido's ledger.

**Architecture:** A new `:slack-channel` source plugin mirrors the existing `:notion-view` plugin (poll the Slack Web API, watermark on message `ts`, emit one event per new top-level message). The single Notion-hardcoded seam in `spawn.clj` is generalized to be adapter-aware (default stays `:notion`), so a Slack event mints a `:slack-message` workstream + ledger + triage session through the unchanged downstream pipeline.

**Tech Stack:** Babashka, `babashka.http-client`, `cheshire`, macOS Keychain (`security`), clojure.test. Slack Web API: `conversations.history`, `chat.getPermalink`.

---

## Conventions for every task

- **This is a `jj` repo, not git.** Use the `jujutsu` skill's discipline. Each task's final step commits with `jj commit -m "<msg>"` (finalizes `@`, opens a fresh empty change). Before starting a task, confirm `@` is empty (`jj st`); if not, `jj new` first. Subagents: run `jj log -r @ --no-graph` before and after to confirm you created a NEW change and did not amend the previous one.
- **Run tests with:** `bb nido:test :only <ns-prefix>` (filters test namespaces by dotted-prefix). Run from `/Users/yabmas/Code/nido`.
- **Do NOT commit planning artifacts.** Neither this plan nor the spec (`docs/superpowers/specs/2026-06-16-slack-bug-channel-intake-design.md`) is committed (per user CLAUDE.md). They drive the work; they are not the work.
- **`triggers.edn` is runtime config under `~/.nido/`, not in the repo** — the trigger is added during rollout (Task 9), not committed.

---

## File structure

**Create:**
- `src/nido/slack/client.clj` — Slack Web API client + keychain helpers. One responsibility: talk to Slack and the keychain.
- `src/tasks/nido_slack.clj` — `bb nido:slack:auth:set` / `:check` glue.
- `src/nido/coordinator/sources/slack.clj` — the `:slack-channel` source plugin (poll/watermark/emit/breaker).
- `test/nido/slack/client_test.clj`
- `test/nido/coordinator/sources/slack_test.clj`

**Modify:**
- `bb.edn` — require the slack task ns + two task entries.
- `src/nido/coordinator/spawn.clj` — adapter-aware `external-ref` + the two `find-by-ref` calls.
- `test/nido/coordinator/spawn_test.clj` — add Slack cases, keep Notion regression.
- `src/nido/coordinator/workstreams_view.clj` — `ws-source` learns `:slack`.
- `test/nido/coordinator/workstreams_view_test.clj` — add the `:slack` case.
- `src/nido/coordinator/core.clj` — register the Slack plugin at startup.
- `CLAUDE.md` — document the new source + auth task.

**Cross-repo dependency (Task 8):** brian's `triage-bug` skill must tolerate a Notion-less run.

---

## Task 1: Slack client — keychain, HTTP, normalise, message-id

**Files:**
- Create: `src/nido/slack/client.clj`
- Test: `test/nido/slack/client_test.clj`

Slack differs from Notion in one critical way: **the Web API returns HTTP 200 even on logical errors**, signalling failure via a JSON `"ok": false` field. The client must inspect `ok`, not just the HTTP status. Rate-limiting is HTTP 429.

- [ ] **Step 1: Write the failing test**

Create `test/nido/slack/client_test.clj`:

```clojure
(ns nido.slack.client-test
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is]]
   [nido.slack.client :as slack]))

(defn- ok-resp [m] {:status 200 :body (json/generate-string (assoc m :ok true))})
(defn- err-resp [error] {:status 200 :body (json/generate-string {:ok false :error error})})

(deftest message-id-is-stable-and-fs-safe
  (is (= "slack-C123-1718000000.000123"
         (slack/message-id "C123" "1718000000.000123"))))

(deftest normalise-message-builds-the-event-payload
  (let [msg {:type "message" :ts "1718000000.000123" :user "U1"
             :text "login button is broken"}
        p   (slack/normalise-message "C123" msg "https://x.slack.com/archives/C123/p1718000000000123")]
    (is (= :slack-message (:adapter p)))
    (is (= "slack-C123-1718000000.000123" (:id p)))
    (is (= "1718000000.000123" (:ts p)))
    (is (= "C123" (:channel p)))
    (is (= "U1" (:user p)))
    (is (= "login button is broken" (:text p)))
    (is (= "login button is broken" (:title p)))
    (is (= "https://x.slack.com/archives/C123/p1718000000000123" (:url p)))))

(deftest normalise-message-truncates-the-title
  (let [long-text (apply str (repeat 200 "x"))
        p (slack/normalise-message "C123" {:ts "1.1" :text long-text} "u")]
    (is (<= (count (:title p)) 80))
    (is (= long-text (:text p)))))

(deftest conversations-history-success
  (with-redefs [slack/http-request (fn [_ _ _] (ok-resp {:messages [{:ts "2.0"} {:ts "1.0"}]
                                                         :has_more false}))]
    (let [r (slack/conversations-history "C123" "tok" {})]
      (is (= 2 (count (:messages r))))
      (is (false? (:has_more r)))
      (is (nil? (:error r))))))

(deftest conversations-history-maps-invalid-auth-to-auth-error
  (with-redefs [slack/http-request (fn [_ _ _] (err-resp "invalid_auth"))]
    (is (= :auth (:error (slack/conversations-history "C123" "tok" {}))))))

(deftest conversations-history-maps-429-to-rate-limit
  (with-redefs [slack/http-request (fn [_ _ _] {:status 429 :body ""})]
    (is (= :rate-limit (:error (slack/conversations-history "C123" "tok" {}))))))

(deftest conversations-history-maps-5xx-to-server
  (with-redefs [slack/http-request (fn [_ _ _] {:status 503 :body ""})]
    (is (= :server (:error (slack/conversations-history "C123" "tok" {}))))))

(deftest conversations-history-maps-network-to-network
  (with-redefs [slack/http-request (fn [_ _ _] {:status 0})]
    (is (= :network (:error (slack/conversations-history "C123" "tok" {}))))))

(deftest conversations-history-other-ok-false-is-api-error
  (with-redefs [slack/http-request (fn [_ _ _] (err-resp "not_in_channel"))]
    (let [r (slack/conversations-history "C123" "tok" {})]
      (is (= :api (:error r)))
      (is (= "not_in_channel" (:detail r))))))

(deftest chat-permalink-returns-url
  (with-redefs [slack/http-request (fn [_ _ _] (ok-resp {:permalink "https://x.slack.com/p"}))]
    (is (= "https://x.slack.com/p" (slack/chat-permalink "C123" "1.0" "tok")))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bb nido:test :only nido.slack.client`
Expected: FAIL — `No namespace: nido.slack.client` / unresolved symbols.

- [ ] **Step 3: Write the implementation**

Create `src/nido/slack/client.clj`:

```clojure
(ns nido.slack.client
  "Slack Web API client + macOS Keychain helpers for the bot token.
   Used by the :slack-channel source.

   Keychain entries are scoped per-user with service name 'nido-slack'.
   `sh!` is a redef seam so tests can stub `security` invocations.
   `http-request` is a redef seam so tests can stub HTTP calls.

   NOTE: Slack returns HTTP 200 even on logical errors, signalling failure via
   a JSON \"ok\": false field — so we inspect `ok`, not just the status."
  (:require
   [babashka.http-client :as http]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as str]))

(defn sh!
  "Wrapped shell-out so tests can stub `security` calls."
  [args]
  (p/sh args))

(defn- whoami []
  (str/trim (:out (p/sh ["whoami"]))))

(defn keychain-token
  "Read the Slack bot token from the user's macOS Keychain. Returns the trimmed
   token string, or nil if the entry isn't present."
  []
  (let [{:keys [exit out]} (sh! ["security" "find-generic-password"
                                 "-s" "nido-slack" "-a" (whoami) "-w"])]
    (when (zero? exit) (str/trim out))))

(defn keychain-set!
  "Upsert the Slack bot token into the user's macOS Keychain. `-U` upserts."
  [token]
  (sh! ["security" "add-generic-password"
        "-s" "nido-slack" "-a" (whoami) "-U" "-w" token]))

(defn http-request
  "Wrapped HTTP call so tests can stub. Returns {:status :body}."
  [method url opts]
  (case method
    :get  (http/get  url (assoc opts :throw false))
    :post (http/post url (assoc opts :throw false))))

(defn- api-error
  "Map an HTTP response to a normalized error keyword, or nil if it is a Slack
   logical success (HTTP 200 + ok:true). Returns {:error :kw :detail str}."
  [{:keys [status body]}]
  (cond
    (= status 429) {:error :rate-limit}
    (>= (or status 0) 500) {:error :server}
    (zero? (or status 0)) {:error :network}
    (= status 200)
    (let [parsed (json/parse-string body true)]
      (if (:ok parsed)
        nil
        (let [e (:error parsed)]
          (if (#{"invalid_auth" "not_authed" "token_revoked" "account_inactive"} e)
            {:error :auth :detail e}
            {:error :api :detail e}))))
    :else {:error :api :detail (str "http-" status)}))

(defn conversations-history
  "GET conversations.history for `channel`. Options:
     :oldest    — only messages after this ts (exclusive via :inclusive false)
     :limit     — page size (default 200)
     :cursor    — pagination cursor
   Returns {:messages [...] :has_more bool :next_cursor str} on success,
   or {:error :auth|:server|:network|:rate-limit|:api :detail str}.
   Messages come back newest-first (Slack's order)."
  [channel token {:keys [oldest limit cursor]
                  :or   {limit 200}}]
  (let [params (cond-> {"channel" channel "limit" (str limit) "inclusive" "false"}
                 oldest (assoc "oldest" oldest)
                 cursor (assoc "cursor" cursor))
        resp   (try
                 (http-request
                   :get "https://slack.com/api/conversations.history"
                   {:headers      {"Authorization" (str "Bearer " token)}
                    :query-params params
                    :timeout      10000})
                 (catch Exception e {:status 0 :exception e}))]
    (if-let [err (api-error resp)]
      err
      (let [parsed (json/parse-string (:body resp) true)]
        {:messages    (:messages parsed)
         :has_more    (boolean (:has_more parsed))
         :next_cursor (get-in parsed [:response_metadata :next_cursor])}))))

(defn chat-permalink
  "GET chat.getPermalink for one message. Returns the permalink URL, or nil on
   any error (a missing permalink must not block emission)."
  [channel ts token]
  (let [resp (try
               (http-request
                 :get "https://slack.com/api/chat.getPermalink"
                 {:headers      {"Authorization" (str "Bearer " token)}
                  :query-params {"channel" channel "message_ts" ts}
                  :timeout      10000})
               (catch Exception e {:status 0 :exception e}))]
    (when-not (api-error resp)
      (:permalink (json/parse-string (:body resp) true)))))

(defn message-id
  "Stable, unique, filesystem-safe id for a channel message."
  [channel ts]
  (str "slack-" channel "-" ts))

(defn- truncate [s n]
  (if (> (count s) n) (subs s 0 n) s))

(defn normalise-message
  "Turn a raw Slack message + its permalink into the event-payload shape the
   spawn pipeline consumes. The full text is the triage brief; :title is a
   truncated display line."
  [channel {:keys [ts text user]} permalink]
  (let [text (or text "")]
    {:adapter :slack-message
     :id      (message-id channel ts)
     :ts      ts
     :channel channel
     :url     permalink
     :title   (truncate text 80)
     :text    text
     :user    user}))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bb nido:test :only nido.slack.client`
Expected: PASS (all deftests).

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(slack): Slack Web API client + keychain helpers"
```

---

## Task 2: Slack auth bb task + wiring

**Files:**
- Create: `src/tasks/nido_slack.clj`
- Modify: `bb.edn` (require block ~line 25; task block ~line 273)

- [ ] **Step 1: Write the task ns**

Create `src/tasks/nido_slack.clj`:

```clojure
(ns tasks.nido-slack
  "Bb task entry points for Slack auth. The token is an xoxb- bot token with
   channels:history (public) or groups:history (private) scope, and the bot
   must be a member of the bug channel."
  (:require
   [clojure.string :as str]
   [nido.slack.client :as slack]))

(defn auth-set
  "bb nido:slack:auth:set — read a bot token from stdin, store in macOS Keychain.
   Stdin is echoed (Babashka can't trivially disable terminal echo); the user
   should clear their terminal scrollback after running this."
  [& _args]
  (println "Paste your Slack bot token (xoxb-...) (input is echoed; clear terminal afterwards):")
  (let [token (read-line)]
    (cond
      (or (nil? token) (str/blank? token))
      (do (println "Empty token; aborted.") (System/exit 1))

      :else
      (let [{:keys [exit err]} (slack/keychain-set! token)]
        (if (zero? exit)
          (println "Token stored. Run `bb nido:slack:auth:check` to verify, then `bb nido:coordinator:restart`.")
          (do (println "security failed (exit" exit ").")
              (println err)
              (System/exit exit)))))))

(defn auth-check
  "bb nido:slack:auth:check — print whether the keychain has a token."
  [& _args]
  (let [token (slack/keychain-token)]
    (cond
      (nil? token)
      (do (println "No Slack token in keychain. Run `bb nido:slack:auth:set`.")
          (System/exit 1))

      (str/blank? token)
      (do (println "Keychain entry is empty.") (System/exit 1))

      :else
      (println "Slack token present in keychain (length" (count token) ")."))))
```

- [ ] **Step 2: Wire the require into `bb.edn`**

In the `:requires` vector (next to `[tasks.nido-notion :as nido-notion]`, ~line 25), add:

```clojure
             [tasks.nido-slack :as nido-slack]
```

- [ ] **Step 3: Wire the two task entries into `bb.edn`**

After the `nido:notion:auth:check` task block (~line 271), add:

```clojure
  nido:slack:auth:set
  {:doc "Store a Slack bot token in the macOS Keychain (interactive)."
   :task (apply nido-slack/auth-set *command-line-args*)}

  nido:slack:auth:check
  {:doc "Print whether a Slack token is present in the macOS Keychain."
   :task (apply nido-slack/auth-check *command-line-args*)}
```

- [ ] **Step 4: Verify the task loads and reports cleanly**

Run: `bb nido:slack:auth:check`
Expected: prints `No Slack token in keychain. Run \`bb nido:slack:auth:set\`.` and exits 1 (no token stored yet — that is correct; the real token is stored at rollout, Task 9). The point of this step is to confirm `bb.edn` parses and the task resolves.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(slack): bb nido:slack:auth:set/check tasks"
```

---

## Task 3: Slack source plugin — poll, watermark, emit, breaker

**Files:**
- Create: `src/nido/coordinator/sources/slack.clj`
- Test: `test/nido/coordinator/sources/slack_test.clj`

This mirrors `sources/notion.clj` but uses a single `:last-seen-ts` watermark instead of a row-set, and pages `conversations.history` (newest-first) until `has_more` is false before advancing the watermark.

- [ ] **Step 1: Write the failing test**

Create `test/nido/coordinator/sources/slack_test.clj`:

```clojure
(ns nido.coordinator.sources.slack-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.sources :as sources]
   [nido.coordinator.sources.slack :as slack-src]
   [nido.coordinator.sources.state :as sst]
   [nido.coordinator.state :as cstate]
   [nido.slack.client :as client]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(def ^:private sc {:type :slack-channel :project :brian :channel "C123" :poll "2m"})

(defn- msg [ts text & {:keys [subtype]}]
  (cond-> {:type "message" :ts ts :user "U1" :text text}
    subtype (assoc :subtype subtype)))

(defn- stub-history
  "Returns a one-page conversations-history stub (newest-first)."
  [messages]
  (fn [_chan _tok _opts] {:messages messages :has_more false :next_cursor nil}))

(deftest cold-start-seeds-watermark-and-emits-nothing
  (with-tmp
    (fn [_]
      (let [emitted (atom [])]
        (with-redefs [client/conversations-history (stub-history [(msg "2.0" "newest") (msg "1.0" "older")])
                      client/chat-permalink         (fn [_ _ _] "u")]
          (let [handle ((:start! (sources/lookup :slack-channel)) sc
                        (fn [p] (swap! emitted conj p)))]
            ((:poll! handle))
            (is (empty? @emitted) "cold start must not replay history")
            (is (= "2.0" (:last-seen-ts (sst/read-state (sources/config-hash sc)))))))))))

(deftest second-poll-emits-new-messages-oldest-first
  (with-tmp
    (fn [_]
      (let [emitted (atom [])]
        (with-redefs [client/chat-permalink (fn [_ _ _] "u")]
          (with-redefs [client/conversations-history (stub-history [(msg "2.0" "seed")])]
            (let [handle ((:start! (sources/lookup :slack-channel)) sc
                          (fn [p] (swap! emitted conj p)))]
              ((:poll! handle))  ; seed at 2.0
              (is (empty? @emitted))
              (with-redefs [client/conversations-history (stub-history [(msg "4.0" "b") (msg "3.0" "a")])]
                ((:poll! handle))
                (is (= 2 (count @emitted)))
                (is (= ["a" "b"] (map #(get-in % [:payload :text]) @emitted))
                    "emitted oldest-first")
                (is (= "4.0" (:last-seen-ts (sst/read-state (sources/config-hash sc)))))))))))))

(deftest does-not-re-emit-already-seen-messages
  (with-tmp
    (fn [_]
      (let [emitted (atom [])]
        (with-redefs [client/chat-permalink (fn [_ _ _] "u")
                      client/conversations-history (stub-history [(msg "1.0" "seed")])]
          (let [handle ((:start! (sources/lookup :slack-channel)) sc
                        (fn [p] (swap! emitted conj p)))]
            ((:poll! handle))            ; seed at 1.0
            ((:poll! handle))            ; same single message → oldest=1.0 exclusive → none
            (is (empty? @emitted))))))))

(deftest skips-configured-subtypes
  (with-tmp
    (fn [_]
      (let [emitted (atom [])
            sc'     (assoc sc :subtypes-skip #{"channel_join"})]
        (with-redefs [client/chat-permalink (fn [_ _ _] "u")]
          (with-redefs [client/conversations-history (stub-history [(msg "1.0" "seed")])]
            (let [handle ((:start! (sources/lookup :slack-channel)) sc'
                          (fn [p] (swap! emitted conj p)))]
              ((:poll! handle))
              (with-redefs [client/conversations-history
                            (stub-history [(msg "3.0" "real")
                                           (msg "2.5" "joined" :subtype "channel_join")])]
                ((:poll! handle))
                (is (= 1 (count @emitted)))
                (is (= "real" (get-in (first @emitted) [:payload :text])))))))))))

(deftest breaker-opens-immediately-on-auth-error
  (with-tmp
    (fn [_]
      (with-redefs [client/conversations-history (fn [_ _ _] {:error :auth})]
        (let [handle ((:start! (sources/lookup :slack-channel)) sc (fn [_]))]
          ((:poll! handle))
          (is (= :open (:breaker (sst/read-state (sources/config-hash sc))))))))))

(deftest breaker-opens-after-3-server-failures
  (with-tmp
    (fn [_]
      (with-redefs [client/conversations-history (fn [_ _ _] {:error :server})]
        (let [handle ((:start! (sources/lookup :slack-channel)) sc (fn [_]))]
          (dotimes [_ 3] ((:poll! handle)))
          (is (= :open (:breaker (sst/read-state (sources/config-hash sc))))))))))

(deftest rate-limit-and-network-never-trip-breaker
  (with-tmp
    (fn [_]
      (doseq [err [:rate-limit :network]]
        (sst/delete-state! (sources/config-hash sc))
        (with-redefs [client/conversations-history (fn [_ _ _] {:error err})]
          (let [handle ((:start! (sources/lookup :slack-channel)) sc (fn [_]))]
            (dotimes [_ 5] ((:poll! handle)))
            (is (nil? (:breaker (sst/read-state (sources/config-hash sc))))
                (str err " must not open the breaker"))))))))

(deftest plugin-is-registered
  (is (some? (sources/lookup :slack-channel))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bb nido:test :only nido.coordinator.sources.slack`
Expected: FAIL — `No namespace: nido.coordinator.sources.slack`.

- [ ] **Step 3: Write the implementation**

Create `src/nido/coordinator/sources/slack.clj`:

```clojure
(ns nido.coordinator.sources.slack
  "The :slack-channel source plugin. Polls a Slack channel via
   conversations.history, watermarks on message ts, and emits one event per
   new top-level message.

   Cold start: the first poll seeds the watermark to the channel's newest ts
   and emits nothing, so turning the trigger on does not replay the backlog.
   Subsequent polls page (newest-first) until has_more is false, then emit
   qualifying messages oldest-first and advance the watermark to the max ts.

   See spec §Polling & identity mechanics."
  (:require
   [nido.coordinator.clock :as clock]
   [nido.coordinator.sources :as sources]
   [nido.coordinator.sources.state :as sst]
   [nido.slack.client :as client]))

(def ^:private failure-threshold 3)
(def ^:private breaker-cooldown-s (* 5 60))
(def ^:private default-subtypes-skip #{"channel_join" "channel_leave" "channel_topic"
                                       "channel_purpose" "channel_name" "channel_archive"})
(def ^:private max-pages 10)

(defn- seconds-since [iso]
  (when iso
    (try (.toSeconds (java.time.Duration/between
                       (java.time.Instant/parse iso)
                       (java.time.Instant/parse (clock/now-iso))))
         (catch Exception _ nil))))

(defn- cooldown-elapsed? [state]
  (if-let [since (seconds-since (:breaker-opened-at state))]
    (>= since breaker-cooldown-s)
    true))

(defn- ts> [a b]
  (and a (or (nil? b) (pos? (compare (bigdec a) (bigdec b))))))

(defn- qualifies?
  "A top-level user/bot message that is not a skipped system subtype."
  [skip {:keys [subtype ts]}]
  (and ts (not (contains? skip subtype))))

(defn- fetch-since
  "Page conversations.history (newest-first) collecting all messages after
   `oldest`. Returns {:messages [...]} (newest-first) or {:error :kw}."
  [channel token oldest]
  (loop [cursor nil, acc [], pages 0]
    (let [resp (client/conversations-history channel token
                                             (cond-> {} oldest (assoc :oldest oldest)
                                                        cursor (assoc :cursor cursor)))]
      (if (:error resp)
        resp
        (let [acc' (into acc (:messages resp))]
          (if (and (:has_more resp) (:next_cursor resp) (< (inc pages) max-pages))
            (recur (:next_cursor resp) acc' (inc pages))
            {:messages acc'}))))))

(defn poll-once!
  "One iteration for a source-config. Reads prior state, returns updated state
   (caller persists). source-config must have :project, :channel."
  [source-config token emit-fn]
  (let [hash        (sources/config-hash source-config)
        prior-state (sst/read-state hash)
        channel     (:channel source-config)
        skip        (or (:subtypes-skip source-config) default-subtypes-skip)
        base        {:type :slack-channel :source-config source-config
                     :last-polled-at (clock/now-iso)}]
    (if (and (= :open (:breaker prior-state)) (not (cooldown-elapsed? prior-state)))
      prior-state
      (let [resp (fetch-since channel token (:last-seen-ts prior-state))]
        (cond
          ;; success
          (not (:error resp))
          (let [msgs (->> (:messages resp)
                          (filter #(qualifies? skip %))
                          (sort-by :ts #(compare (bigdec %1) (bigdec %2))))  ; oldest-first
                newest (reduce (fn [m {:keys [ts]}] (if (ts> ts m) ts m))
                               (:last-seen-ts prior-state)
                               (:messages resp))]
            ;; Cold start (no prior watermark): seed, emit nothing.
            (when (some? (:last-seen-ts prior-state))
              (doseq [m msgs]
                (let [permalink (client/chat-permalink channel (:ts m) token)]
                  (emit-fn (client/normalise-message channel m permalink)))))
            (-> base
                (assoc :last-seen-ts        (or newest (:last-seen-ts prior-state) "0")
                       :last-poll-result    :ok
                       :consecutive-failures 0
                       :network-failures    0)
                (dissoc :breaker :breaker-opened-at)))

          ;; transient: rate-limit / network — never trip
          (#{:rate-limit :network} (:error resp))
          (cond-> (-> (merge prior-state base)
                      (assoc :last-poll-result {:error (:error resp)})
                      (update :network-failures (fnil inc 0)))
            (= :open (:breaker prior-state)) (assoc :breaker-opened-at (clock/now-iso)))

          ;; auth: open immediately
          (= :auth (:error resp))
          (-> (merge prior-state base)
              (assoc :last-poll-result {:error :auth}
                     :breaker :open :breaker-opened-at (clock/now-iso))
              (update :consecutive-failures (fnil inc 0)))

          ;; other (server / api): trip after threshold
          :else
          (let [next-failures ((fnil inc 0) (:consecutive-failures prior-state))
                tripped?      (or (= :open (:breaker prior-state))
                                  (>= next-failures failure-threshold))]
            (cond-> (-> (merge prior-state base)
                        (assoc :last-poll-result {:error (:error resp) :detail (:detail resp)}
                               :consecutive-failures next-failures))
              tripped? (assoc :breaker :open :breaker-opened-at (clock/now-iso)))))))))

(defn start-instance!
  [source-config emit-fn {:keys [token] :as _opts}]
  (let [hash  (sources/config-hash source-config)
        token (or token (client/keychain-token))
        emit  (fn [payload]
                (emit-fn {:type :slack-channel :source-config source-config :payload payload}))]
    {:poll! (fn [] (sst/write-state! hash (poll-once! source-config token emit)))
     :stop! (fn [] nil)}))

(defn register! []
  (sources/register-source!
   {:type   :slack-channel
    :schema [:map
             [:type    [:= :slack-channel]]
             [:project keyword?]
             [:channel string?]
             [:poll    {:optional true} string?]
             [:subtypes-skip {:optional true} [:set string?]]]
    :events [:map [:source [:= :slack-channel]] [:id string?]]
    :start! (fn [source-config emit-fn]
              (start-instance! source-config emit-fn {}))}))
```

**Note on the cold-start emit guard:** emission is gated on `(some? (:last-seen-ts prior-state))`. On the very first poll there is no prior watermark, so nothing is emitted and the watermark is seeded to the channel's newest ts (or `"0"` for an empty channel). Because the source is registered at namespace load (Task 6 calls `register!` at startup), the test's `(sources/lookup :slack-channel)` requires the test ns to load this ns — it does, via the `:require`.

- [ ] **Step 4: Ensure the plugin registers when the ns loads (test scope)**

Add to the bottom of `src/nido/coordinator/sources/slack.clj`:

```clojure
;; Self-register on load so tests (which :require this ns) can look it up.
;; Production also calls register! explicitly at daemon startup (core.clj).
(register!)
```

- [ ] **Step 5: Run test to verify it passes**

Run: `bb nido:test :only nido.coordinator.sources.slack`
Expected: PASS (all deftests, including `plugin-is-registered`).

- [ ] **Step 6: Commit**

```bash
jj commit -m "feat(slack): :slack-channel source plugin with watermark + breaker"
```

---

## Task 4: Generalize spawn — adapter-aware external-ref

**Files:**
- Modify: `src/nido/coordinator/spawn.clj:14-37` and `:82`
- Modify: `test/nido/coordinator/spawn_test.clj`

- [ ] **Step 1: Write the failing test**

Add to `test/nido/coordinator/spawn_test.clj` (after `external-ref-from-notion-payload`):

```clojure
(deftest external-ref-defaults-to-notion-when-no-adapter
  ;; Regression pin: existing Notion payloads (no :adapter) stay :notion.
  (is (= :notion (:adapter (spawn/external-ref {:id "BR-1" :title "T"})))))

(deftest external-ref-honors-slack-adapter
  (let [p {:adapter :slack-message :id "slack-C1-1.0" :title "broken" :url "u"}]
    (is (= {:adapter :slack-message :id "slack-C1-1.0" :title "broken" :url "u"}
           (spawn/external-ref p)))))

(deftest ensure-workstream-dedups-slack-on-its-own-adapter
  (with-tmp
    (fn [_]
      (let [p {:adapter :slack-message :id "slack-C1-1.0" :title "broken"}
            a (spawn/ensure-workstream! :brian p :triaging)
            b (spawn/ensure-workstream! :brian p :triaging)]
        (is (= (:id a) (:id b)) "same Slack message must not mint two workstreams")
        (is (= 1 (count (ws/list-ids :brian))))
        (is (= :slack-message (-> (ws/read-ws :brian (:id a)) :external-refs first :adapter)))))))

(deftest spawn-records-creates-slack-workstream-ledger-and-session
  (with-tmp
    (fn [_]
      (let [routed {:project :brian
                    :trigger {:name :triage-slack-bugs :skill :triage-bug :agent :claude
                              :payload "Triage {{event/title}}" :limits {:budget "15m"}
                              :source {:type :slack-channel}}
                    :payload {:adapter :slack-message :id "slack-C1-9.0" :title "Nine" :text "boom" :url "u"}
                    :priority 0 :session-profile :lite :uncapped? false}
            run    (spawn/spawn-records! routed {:fired-at "2026-06-16T00:00:00Z" :fired-by "t"})
            ws-id  (:workstream-id run)]
        (is (some? ws-id))
        (is (= "slack-C1-9.0"
               (-> (ws/find-by-ref :brian :slack-message "slack-C1-9.0") :external-refs first :id)))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bb nido:test :only nido.coordinator.spawn`
Expected: FAIL — `external-ref-honors-slack-adapter` returns `{:adapter :notion ...}`; the dedup/spawn-records Slack tests mint duplicates or fail `find-by-ref`.

- [ ] **Step 3: Edit `external-ref`**

In `src/nido/coordinator/spawn.clj`, replace the `external-ref` body (lines 17-23):

```clojure
(defn external-ref
  "External ref derived from an event payload, or nil when the payload carries
   no usable id. The adapter comes from the payload (:adapter), defaulting to
   :notion so existing Notion payloads are unchanged. Optional fields are
   included when present (Slack payloads carry no :page-id, so it is omitted)."
  [payload]
  (let [adapter (or (:adapter payload) :notion)
        id      (:id payload)]
    (when (and id (not (str/blank? id)))
      (cond-> {:adapter adapter :id id}
        (:title payload)   (assoc :title (:title payload))
        (:url payload)     (assoc :url (:url payload))
        (:page-id payload) (assoc :page-id (:page-id payload))))))
```

- [ ] **Step 4: Thread the adapter through the two `find-by-ref` calls**

In `ensure-workstream!` (line ~35), change:

```clojure
    (or (ws/find-by-ref project (:adapter ref) (:id ref))
        (ws/create! project {:stage stage :external-refs [ref]}))
```

In `spawn-records!` (line ~82), change:

```clojure
        pre     (when ref (ws/find-by-ref project (:adapter ref) (:id ref)))
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `bb nido:test :only nido.coordinator.spawn`
Expected: PASS — both the new Slack tests and the unchanged Notion regression tests (`external-ref-from-notion-payload`, `external-ref-defaults-to-notion-when-no-adapter`, `ensure-workstream-dedups-on-ref`, `spawn-records-creates-workstream-session-and-linked-run`).

- [ ] **Step 6: Commit**

```bash
jj commit -m "feat(spawn): adapter-aware external-ref (default :notion) for Slack intake"
```

---

## Task 5: ws-source learns :slack

**Files:**
- Modify: `src/nido/coordinator/workstreams_view.clj:18-28`
- Modify: `test/nido/coordinator/workstreams_view_test.clj`

- [ ] **Step 1: Write the failing test**

In `test/nido/coordinator/workstreams_view_test.clj`, extend `ws-source-classifies-from-the-raw-record`:

```clojure
(deftest ws-source-classifies-from-the-raw-record
  (is (= :scratch (wsv/ws-source {:stage :scratch :external-refs []})))
  (is (= :notion  (wsv/ws-source {:stage :triaging
                                  :external-refs [{:adapter :notion :id "BR-1"}]})))
  (is (= :notion  (wsv/ws-source {:stage :triaging :external-refs []})))
  (is (= :github  (wsv/ws-source {:stage :ready
                                  :external-refs [{:adapter :github-issue :id "42"}]})))
  (is (= :slack   (wsv/ws-source {:stage :triaging
                                  :external-refs [{:adapter :slack-message :id "slack-C1-1.0"}]}))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bb nido:test :only nido.coordinator.workstreams-view`
Expected: FAIL — a `:slack-message` ref currently falls through to `:else → :notion`.

- [ ] **Step 3: Add the `:slack` branch**

In `src/nido/coordinator/workstreams_view.clj`, in `ws-source`'s `cond` (after the `:github` line, before `:else`):

```clojure
    (some #(= :slack-message (:adapter %)) (:external-refs ws)) :slack
```

Also update the docstring to mention `:slack` (a `:slack-message` ref).

- [ ] **Step 4: Run test to verify it passes**

Run: `bb nido:test :only nido.coordinator.workstreams-view`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(workstreams-view): classify :slack-message refs as :slack source"
```

---

## Task 6: Register the Slack plugin at daemon startup

**Files:**
- Modify: `src/nido/coordinator/core.clj:27` (require) and `:596` (register call)

The source self-registers on ns load (Task 3 Step 4), but production must load that ns and register explicitly at startup, next to the Notion registration.

- [ ] **Step 1: Add the require alias**

In `src/nido/coordinator/core.clj`'s `:require` (next to `[nido.coordinator.sources.notion :as nsource]`, line 27):

```clojure
   [nido.coordinator.sources.slack :as slack-source]
```

- [ ] **Step 2: Add the register call at startup**

After line 596 (`(nsource/register!)`):

```clojure
  (slack-source/register!)                            ; register Slack source plugin
```

- [ ] **Step 3: Verify the daemon namespace compiles**

Run: `bb -e "(require 'nido.coordinator.core) (require 'nido.coordinator.sources :as-alias s) (println (some? ((requiring-resolve 'nido.coordinator.sources/lookup) :slack-channel)))"`
Expected: prints `true` (the plugin is registered after loading core).

- [ ] **Step 4: Run the full coordinator test suite as a regression check**

Run: `bb nido:test :only nido.coordinator`
Expected: PASS (no regressions across sources, spawn, workstreams-view, etc.).

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(coordinator): register :slack-channel source at daemon startup"
```

---

## Task 7: Document the new source

**Files:**
- Modify: `CLAUDE.md` ("Autonomous sources (Stage 5)" section)

- [ ] **Step 1: Add the Slack auth tasks and source description**

In `CLAUDE.md`, in the **Autonomous sources (Stage 5)** block, after the Notion auth commands, add:

```
bb nido:slack:auth:set                 # store Slack bot token (xoxb-) in macOS Keychain
bb nido:slack:auth:check               # confirm token presence
```

And after the `:notion-view` source paragraph, add:

> A trigger with `:source {:type :slack-channel :channel "C..." :poll "2m"}` polls a
> Slack channel's top-level messages and emits one event per new message
> (watermarked on message `ts`). The first poll seeds the watermark and emits
> nothing — it does not replay channel history. Each new message auto-fires a
> triage session; the verdict stays in nido's ledger (no Slack/Notion writeback).
> The bot token needs `channels:history` (public) or `groups:history` (private)
> scope and the bot must be a channel member. Breaker opens on `invalid_auth`
> (or ≥3 consecutive server errors); rate-limit (429) and connectivity blips are
> transient and never trip it.

- [ ] **Step 2: Commit**

```bash
jj commit -m "docs: document the :slack-channel autonomous source"
```

---

## Task 8: brian-side — `triage-bug` tolerates a Notion-less run (cross-repo dependency)

**Repo:** brian (`~/Code/brian`), NOT nido. This is the only out-of-nido change. Sequence it before the end-to-end rollout smoke test (Task 9) so a real Slack-fired triage run does not error on a missing Notion target.

**Acceptance criterion:** a triage run whose payload has `:adapter :slack-message` and **no** Notion `:page-id`:
1. triages from the brief (`:text` + `:url` in the first-message) — no `:notion-ticket` preprocess is configured for the Slack trigger, so the skill must not assume a fetched ticket body exists;
2. writes its verdict to the nido per-ticket ledger, keyed by the run payload `:id` (the `slack-C...-ts` id);
3. **skips the Notion writeback** — the in-chat `apply` commits only the ledger verdict (and prints that there is no Notion ticket to update); `promote` still works off the ledger/workstream.

- [ ] **Step 1: Read brian's `triage-bug` skill and locate the Notion-writeback step**

Run: `ls ~/Code/brian/.claude/skills/ | grep -i triage` and read the skill file. Identify where it (a) requires a `BR-####`/`page-id`, (b) runs the `:notion-ticket` preprocess output, and (c) performs the final `API-patch-page` / status writeback.

- [ ] **Step 2: Determine whether it already degrades gracefully**

If the skill already guards the Notion writeback on a present `page-id`/`notion-page-id` and triages from the first-message brief when no preprocess output exists, **no edit is needed** — record that finding and skip to Step 4.

- [ ] **Step 3: If needed, make the skill Notion-optional**

Edit the skill so the Notion writeback is conditional on a Notion target being present (the run payload has no `:page-id` for Slack). Triage logic, the ledger write (keyed by payload `:id`), and the HITL halt are unchanged. Follow brian's own commit conventions in the brian repo (it is a separate VCS root — use its workflow). Do NOT bundle nido's plan/spec into a brian commit.

- [ ] **Step 4: Verify against the contract**

Confirm (by reading, and if brian has a harness, by exercising) that a `:adapter :slack-message` run with no `:page-id` produces a ledger verdict and attempts no Notion API call. Record the result.

---

## Task 9: Rollout — token, trigger, restart, smoke test

**This task touches runtime state under `~/.nido/`, not the repo. No commits.** Do it only after Tasks 1-8 are merged and Task 8's acceptance criterion holds.

- [ ] **Step 1: Store a freshly rotated bot token**

The token shared during brainstorming is burned. Rotate it in Slack, then:

Run: `bb nido:slack:auth:set` (paste the new `xoxb-` token at the prompt), then `bb nido:slack:auth:check`.
Expected: `Slack token present in keychain (length N).`

- [ ] **Step 2: Confirm the bot can read the channel**

Ensure the bot is invited to the bug channel (`/invite @<bot>` in Slack) and note the channel id (e.g. `C0123ABC`).

- [ ] **Step 3: Add the trigger to `~/.nido/projects/brian/triggers.edn`**

Add this entry to the `:triggers` vector (replace `C0123ABC` with the real channel id):

```clojure
  {:name            :triage-slack-bugs
   :source          {:type :slack-channel :channel "C0123ABC" :poll "2m"}
   :skill           :triage-bug
   :session-profile :lite
   :max-in-flight   3
   :payload         "Triage Slack bug: {{event/title}}\n\n{{event/text}}\n\n{{event/url}}"
   :payload-key     :id
   :limits          {:budget "15m" :max-failures 3}}
```

- [ ] **Step 4: Restart the daemon to load new code + the new trigger**

Run: `bb nido:coordinator:restart` (under launchd) or `bb nido:coordinator:down` then `bb nido:coordinator:up`.
(The daemon reads `src/` once at startup — the new source plugin and trigger only take effect after restart.)

- [ ] **Step 5: Confirm the source is live and healthy**

Run: `bb nido:coordinator:status`
Expected: a `Sources:` line includes the `:slack-channel` instance with a recent poll and no open breaker. The first poll seeds the watermark and emits nothing.

- [ ] **Step 6: Smoke test end-to-end**

Post a fresh test message in the bug channel. Within one poll interval (~2m), confirm a new triaging workstream/session appears for it:

Run: `bb nido:coordinator:status` and check the dashboard (`http://localhost:8800`) for a `:slack` workstream that auto-fired a triage session and halted for review in its session chat. `nido enter` that session and confirm the verdict writes to the ledger (no Notion call).

---

## Self-review

**Spec coverage** (each spec section → task):
- New files (client / source / auth task) → Tasks 1, 2, 3. ✓
- Touched files (spawn / core / ws-source / triage-bug / triggers.edn) → Tasks 4, 6, 5, 8, 9. ✓
- Polling watermark + cold-start + top-level/subtype filter + crash-safety ordering → Task 3 (poll-once! seeds on first poll; `fetch-since` pages; envelope-first is inherited from `sources/emit-broadcast!`, unchanged). ✓
- Identity (`:adapter :slack-message`, `slack-<channel>-<ts>` used 3 ways) → Task 1 (`message-id`/`normalise-message`), Task 4 (workstream dedup + spawn-records ledger key). ✓
- Breaker (401 immediate, 5xx after 3, network/429 transient, half-open) → Task 3 tests + impl. ✓
- Trigger config + safety (`:max-in-flight`, budget, no `:dry-run?`) → Task 9. ✓
- Testing matrix (client, source, spawn regression+slack, ws-source, end-to-end path) → Tasks 1, 3, 4, 5; the end-to-end path is the spawn-records Slack test in Task 4 plus the live smoke test in Task 9. ✓
- Auth + rotation → Tasks 2, 9. ✓

**Placeholder scan:** No TBD/TODO/"handle edge cases" — every code step shows complete code; `C0123ABC` is an explicitly-marked example channel id resolved at rollout. ✓

**Type consistency:** `message-id` / `normalise-message` signatures match between Task 1 (impl) and Task 3 (usage in poll-once!). `external-ref` shape (`:adapter`/`:id`/`:title`/`:url`/`:page-id`) is consistent across Tasks 1, 4. `conversations-history` return shape (`:messages`/`:has_more`/`:next_cursor`/`:error`/`:detail`) is consistent between Task 1 and Task 3's `fetch-since`. The source-config keys (`:type`/`:project`/`:channel`/`:poll`/`:subtypes-skip`) match between the Task 3 schema, the tests, and the Task 9 trigger. ✓
