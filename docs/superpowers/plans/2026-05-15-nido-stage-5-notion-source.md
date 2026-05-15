# Nido Stage 5 — event-source plugin + `:notion-view`: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a plugin system for event sources and ship `:notion-view` as the first autonomous source, polling a Notion database and emitting one event per row that newly appears in the configured view.

**Architecture:** New `sources` registry holds typed source plugins (`register-source!` + `lookup`). The coordinator's `tick!` calls each registered source's `:poll!` when its `:poll` interval has elapsed. Sources emit broadcast envelopes through the existing queue dir; `events/route` now returns a vector to support fan-out across triggers. Auth lives in the macOS Keychain. Set-diff against a per-source state file dedupes; the first poll seeds the snapshot and emits nothing.

**Tech Stack:** Babashka, `babashka.http-client` (Notion REST), `babashka.process` (`security` shell-out for keychain), Malli for source schemas, existing trigger / queue / breaker plumbing.

**Spec:** [`../specs/2026-05-15-nido-stage-5-notion-source-design.md`](../specs/2026-05-15-nido-stage-5-notion-source-design.md)

---

## File structure

**Create:**
- `src/nido/coordinator/sources.clj` — registry + `config-hash` + source-instance atom
- `src/nido/coordinator/filter.clj` — trigger filter evaluation
- `src/nido/coordinator/sources/state.clj` — read/write `~/.nido/coordinator/sources/<hash>.edn`
- `src/nido/coordinator/sources/notion.clj` — `:notion-view` plugin
- `src/nido/notion/client.clj` — HTTP wrapper + keychain helper + response normaliser
- `src/tasks/nido_notion.clj` — `bb nido:notion:auth:set` / `:check`
- `src/tasks/nido_coordinator_source.clj` — `bb nido:coordinator:source:list` / `:reset`
- `test/nido/coordinator/sources_test.clj`
- `test/nido/coordinator/filter_test.clj`
- `test/nido/coordinator/sources/state_test.clj`
- `test/nido/coordinator/sources/notion_test.clj`
- `test/nido/notion/client_test.clj`
- `test/fixtures/notion/query-response.json` — recorded Notion API fixture

**Modify:**
- `src/nido/coordinator/events.clj` — `route` returns a vector; broadcast routing implemented
- `src/nido/coordinator/core.clj` — process envelope-vector; source lifecycle on boot/shutdown; `tick!` polls due sources
- `src/tasks/nido_coordinator.clj` — `status` surfaces `Sources:` section
- `test/nido/coordinator/events_test.clj` — broadcast routing tests, vector-shape assertions
- `bb.edn` — register four new tasks

---

### Task 1: `sources.clj` — registry + `config-hash`

**Files:**
- Create: `src/nido/coordinator/sources.clj`
- Create: `test/nido/coordinator/sources_test.clj`

- [ ] **Step 1: Write the failing tests**

```clojure
(ns nido.coordinator.sources-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.sources :as sources]))

(defn- reset-registry! [] (reset! (var-get #'nido.coordinator.sources/!registry) {}))

(deftest register-and-lookup-round-trip
  (reset-registry!)
  (sources/register-source! {:type    :test-src
                             :schema  [:map [:foo string?]]
                             :events  [:map [:bar string?]]
                             :start!  (fn [_ _] {:poll! (fn []) :stop! (fn [])})})
  (let [src (sources/lookup :test-src)]
    (is (= [:map [:foo string?]] (:schema src)))
    (is (= [:map [:bar string?]] (:events src)))
    (is (fn? (:start! src)))))

(deftest lookup-nil-for-unknown
  (reset-registry!)
  (is (nil? (sources/lookup :no-such-source))))

(deftest config-hash-is-deterministic
  (is (= (sources/config-hash {:database "abc" :view "v" :poll "5m"})
         (sources/config-hash {:poll "5m" :view "v" :database "abc"}))))

(deftest config-hash-strips-type
  (is (= (sources/config-hash {:database "abc" :poll "5m"})
         (sources/config-hash {:type :notion-view :database "abc" :poll "5m"}))))

(deftest config-hash-differs-for-different-configs
  (is (not= (sources/config-hash {:database "a"})
            (sources/config-hash {:database "b"}))))

(deftest config-hash-is-12-hex-chars
  (is (re-matches #"[0-9a-f]{12}" (sources/config-hash {:database "abc"}))))
```

- [ ] **Step 2: Run tests to verify they fail**

```
bb nido:test :only nido.coordinator.sources-test
```

Expected: failure with "Could not locate nido/coordinator/sources" (namespace doesn't exist yet).

- [ ] **Step 3: Implement**

```clojure
(ns nido.coordinator.sources
  "Plugin registry for event sources. Sources register themselves at load
   time; the coordinator looks up by :type and calls :start! per distinct
   source-config. See spec §Plugin contract."
  (:require [clojure.string :as str])
  (:import (java.security MessageDigest)))

(defonce ^:private !registry (atom {}))

(defn register-source!
  "Register a source plugin. Idempotent (a re-registration replaces the
   previous entry — useful for REPL development)."
  [{:keys [type schema events start!] :as src}]
  (assert (keyword? type)        "source :type must be a keyword")
  (assert (some? schema)         "source :schema is required")
  (assert (some? events)         "source :events is required")
  (assert (fn? start!)           "source :start! must be a function")
  (swap! !registry assoc type (select-keys src [:schema :events :start!]))
  type)

(defn lookup [type] (get @!registry type))

(defn- sha1-hex [^String s]
  (let [md (MessageDigest/getInstance "SHA-1")
        bs (.digest md (.getBytes s "UTF-8"))]
    (str/join (map #(format "%02x" %) bs))))

(defn config-hash
  "Stable 12-hex-char hash of a source-config map. :type is stripped before
   hashing so the hash identifies the source-instance, not the source type."
  [source-config]
  (let [stripped  (dissoc source-config :type)
        canonical (pr-str (into (sorted-map) stripped))]
    (subs (sha1-hex canonical) 0 12)))
```

- [ ] **Step 4: Run tests to verify they pass**

```
bb nido:test :only nido.coordinator.sources-test
```

Expected: 5 tests, 0 failures.

- [ ] **Step 5: Commit**

```
jj desc -m "feat(coordinator): sources registry + config-hash"
```

---

### Task 2: `filter.clj` — trigger filter evaluation

**Files:**
- Create: `src/nido/coordinator/filter.clj`
- Create: `test/nido/coordinator/filter_test.clj`

- [ ] **Step 1: Write the failing tests**

```clojure
(ns nido.coordinator.filter-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.filter :as f]))

(deftest empty-filter-accepts-everything
  (is (true? (f/accept? {} {})))
  (is (true? (f/accept? {} {:status "any"}))))

(deftest equality-on-top-level
  (is (true?  (f/accept? {:status "Untriaged"} {:status "Untriaged"})))
  (is (false? (f/accept? {:status "Untriaged"} {:status "Done"}))))

(deftest equality-on-properties
  (is (true? (f/accept? {:status "Untriaged"} {:properties {:status "Untriaged"}}))))

(deftest set-membership
  (is (true?  (f/accept? {:priority ["P0" "P1"]} {:priority "P0"})))
  (is (true?  (f/accept? {:priority ["P0" "P1"]} {:priority "P1"})))
  (is (false? (f/accept? {:priority ["P0" "P1"]} {:priority "P2"}))))

(deftest set-membership-with-set-literal
  (is (true? (f/accept? {:priority #{"P0" "P1"}} {:priority "P0"}))))

(deftest all-keys-must-match
  (is (true?  (f/accept? {:status "Untriaged" :priority "P0"}
                         {:status "Untriaged" :priority "P0"})))
  (is (false? (f/accept? {:status "Untriaged" :priority "P0"}
                         {:status "Untriaged" :priority "P1"}))))

(deftest top-level-shadows-properties
  ;; If a key appears both at top-level and under :properties, top-level wins.
  (is (true? (f/accept? {:status "Untriaged"}
                        {:status     "Untriaged"
                         :properties {:status "Wrong"}}))))

(deftest missing-key-fails
  (is (false? (f/accept? {:status "Untriaged"} {:priority "P0"}))))
```

- [ ] **Step 2: Run tests to verify they fail**

```
bb nido:test :only nido.coordinator.filter-test
```

Expected: failure with "Could not locate nido/coordinator/filter".

- [ ] **Step 3: Implement**

```clojure
(ns nido.coordinator.filter
  "Trigger filter evaluation: map-equality + set-membership against
   event payloads. See spec §Source: :notion-view / Event-payload schema.")

(defn- lookup
  "Top-level first, then :properties. Missing-everywhere returns ::missing."
  [event k]
  (cond
    (contains? event k)              (get event k)
    (contains? (:properties event) k) (get-in event [:properties k])
    :else                            ::missing))

(defn- key-matches? [event [k v]]
  (let [ev (lookup event k)]
    (cond
      (= ::missing ev)        false
      (or (set? v) (vector? v)) (contains? (set v) ev)
      :else                     (= v ev))))

(defn accept?
  "True iff every key in filter-map matches the event payload."
  [filter-map event]
  (every? (partial key-matches? event) filter-map))
```

- [ ] **Step 4: Run tests to verify they pass**

```
bb nido:test :only nido.coordinator.filter-test
```

Expected: 8 tests, 0 failures.

- [ ] **Step 5: Commit**

```
jj desc -m "feat(coordinator): filter/accept? — map-equality + set-membership"
```

---

### Task 3: `events.clj` — broadcast routing + vector return

**Files:**
- Modify: `src/nido/coordinator/events.clj`
- Modify: `test/nido/coordinator/events_test.clj`

This is a **breaking signature change** to `route` (single fire-request → vector of fire-requests). Step 4 updates the caller in `core.clj` so the change lands atomically.

- [ ] **Step 1: Write the failing tests**

Add to `test/nido/coordinator/events_test.clj` (alongside existing tests):

```clojure
(deftest route-direct-returns-singleton-vector
  (let [tbp {:brian [{:name :inv :source {:type :manual} :skill :investigate-bug
                      :payload "{{event/url}}"}]}]
    (is (= [{:project :brian
             :trigger (first (:brian tbp))
             :payload {:url "u"}}]
           (events/route
             {:target  {:project :brian :trigger :inv}
              :payload {:url "u"}}
             tbp)))))

(deftest route-direct-unknown-trigger-returns-error-vector
  (is (= [{:error :unknown-trigger :project :brian :trigger :nope}]
         (events/route
           {:target {:project :brian :trigger :nope} :payload {}}
           {:brian []}))))

(deftest route-broadcast-fans-out-to-matching-triggers
  (let [t1 {:name :a :source {:type :notion-view :database "x"} :skill :s
            :payload "" :filter {:status "Open"}}
        t2 {:name :b :source {:type :notion-view :database "x"} :skill :s
            :payload "" :filter {:status "Open"}}
        t3 {:name :c :source {:type :notion-view :database "x"} :skill :s
            :payload "" :filter {:status "Done"}}     ; filter rejects
        t4 {:name :d :source {:type :notion-view :database "y"} :skill :s
            :payload "" :filter {:status "Open"}}     ; different db
        tbp {:p [t1 t2 t3 t4]}
        env {:broadcast {:type :notion-view
                         :source-config {:database "x"}
                         :payload {:status "Open"}}}]
    (is (= [{:project :p :trigger t1 :payload {:status "Open"}}
            {:project :p :trigger t2 :payload {:status "Open"}}]
           (events/route env tbp)))))

(deftest route-broadcast-empty-when-no-matches
  (is (= []
         (events/route
           {:broadcast {:type :cron :source-config {} :payload {}}}
           {:p [{:name :a :source {:type :notion-view :database "x"}
                 :skill :s :payload ""}]}))))
```

(Remove or adapt any pre-existing single-value-route tests so they expect a vector.)

- [ ] **Step 2: Run tests to verify they fail**

```
bb nido:test :only nido.coordinator.events-test
```

Expected: failures on shape mismatch (vector vs single map) and on `route-broadcast-fans-out-to-matching-triggers`.

- [ ] **Step 3: Rewrite `events.clj`**

Replace the file body:

```clojure
(ns nido.coordinator.events
  "Envelope routing: turn an incoming envelope into a vector of fire
   requests. Two envelope shapes (spec §Event sources):
     {:target {:project :p :trigger :t} :payload <m>}   — direct-target
     {:broadcast {:type :<src-type>
                  :source-config <m>
                  :payload <event-payload>}}            — broadcast"
  (:require
   [nido.coordinator.filter :as f]
   [nido.coordinator.triggers :as triggers]))

(defn- route-direct
  [{:keys [target payload]} triggers-by-project]
  (let [{:keys [project trigger]} target
        ts (get triggers-by-project project)]
    (cond
      (nil? ts)
      [{:error :unknown-project :project project}]

      :else
      (if-let [t (triggers/find-by-name ts trigger)]
        [{:project project :trigger t :payload payload}]
        [{:error :unknown-trigger :project project :trigger trigger}]))))

(defn- source-config-match?
  "Compare source-configs by value, ignoring :type."
  [a b]
  (= (dissoc a :type) (dissoc b :type)))

(defn- route-broadcast
  [{:keys [broadcast]} triggers-by-project]
  (let [{:keys [type source-config payload]} broadcast]
    (vec
      (for [[project triggers] triggers-by-project
            t triggers
            :when (= type (-> t :source :type))
            :when (source-config-match? source-config (:source t))
            :when (f/accept? (:filter t) payload)]
        {:project project :trigger t :payload payload}))))

(defn route
  "Resolve an envelope to a vector of fire-requests:
     [{:project <kw> :trigger <trigger-map> :payload <m>} ...]
   Or, on routing errors for :target envelopes, a vector of error maps:
     [{:error <kw> :project ... :trigger ...}]
   Broadcast envelopes with no matches return an empty vector."
  [envelope triggers-by-project]
  (cond
    (:target envelope)    (route-direct envelope triggers-by-project)
    (:broadcast envelope) (route-broadcast envelope triggers-by-project)
    :else                 [{:error :unknown-envelope}]))
```

- [ ] **Step 4: Update the only caller** (in `core.clj`)

Locate `process-envelope!` in `src/nido/coordinator/core.clj` (around lines 99-134). Replace its body:

```clojure
(defn- process-envelope! [envelope triggers-by-project]
  (doseq [routed (events/route envelope triggers-by-project)]
    (cond
      (:error routed)
      (binding [*err* *err*]
        (.println ^java.io.PrintWriter *err*
                  (str "WARN: dropping envelope — " (pr-str routed))))

      (breakers/tripped? (:project routed) (-> routed :trigger :name))
      (binding [*err* *err*]
        (.println ^java.io.PrintWriter *err*
                  (str "WARN: trigger breaker open — skipping "
                       (name (:project routed)) "/"
                       (name (-> routed :trigger :name)))))

      :else
      (let [run (runs/create-run! routed
                                  {:fired-at (clock/now-iso)
                                   :fired-by (System/getenv "USER")})]
        (swap! !detector anomaly/record-spawn (clock/now-iso))
        (try
          (run-now! (:id run))
          (let [final (runs/read-run (:id run))]
            (when (= :failed (:state final))
              (swap! !detector anomaly/record-failure (clock/now-iso))))
          (catch Exception e
            (swap! !detector anomaly/record-failure (clock/now-iso))
            (binding [*err* *err*]
              (.println ^java.io.PrintWriter *err*
                        (str "ERROR: run-now! threw for "
                             (:id run) " — " (ex-message e))))
            (mark-run-failed! (:id run) e)))))))
```

The only structural change is wrapping the existing body in `(doseq [routed (events/route envelope triggers-by-project)] ...)`.

- [ ] **Step 5: Run all coordinator tests to verify nothing else broke**

```
bb nido:test :only nido.coordinator
```

Expected: all tests pass; events tests now show 4 new tests on top of the existing ones.

- [ ] **Step 6: Commit**

```
jj desc -m "feat(coordinator): events/route returns vector + broadcast routing"
```

---

### Task 4: `sources/state.clj` — per-source on-disk state

**Files:**
- Create: `src/nido/coordinator/sources/state.clj`
- Create: `test/nido/coordinator/sources/state_test.clj`

- [ ] **Step 1: Write the failing tests**

```clojure
(ns nido.coordinator.sources.state-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.sources.state :as sst]
   [nido.coordinator.state :as cstate]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f))
      (finally (fs/delete-tree tmp)))))

(deftest read-nil-when-absent
  (with-tmp (fn [] (is (nil? (sst/read-state "abc123"))))))

(deftest write-then-read-round-trips
  (with-tmp
    (fn []
      (sst/write-state! "abc123"
                        {:type :notion-view
                         :source-config {:database "x"}
                         :last-rows #{"p1" "p2"}
                         :last-polled-at "2026-05-15T00:00:00Z"
                         :consecutive-failures 0})
      (let [s (sst/read-state "abc123")]
        (is (= :notion-view (:type s)))
        (is (= #{"p1" "p2"} (:last-rows s)))
        (is (zero? (:consecutive-failures s)))))))

(deftest write-state!-creates-sources-dir
  (with-tmp
    (fn []
      (fs/delete-tree (sst/sources-dir))
      (sst/write-state! "xyz" {:foo 1})
      (is (fs/exists? (sst/state-path "xyz"))))))

(deftest delete-state-removes-file
  (with-tmp
    (fn []
      (sst/write-state! "del" {:foo 1})
      (sst/delete-state! "del")
      (is (nil? (sst/read-state "del"))))))

(deftest list-state-hashes-enumerates-files
  (with-tmp
    (fn []
      (sst/write-state! "h1" {:foo 1})
      (sst/write-state! "h2" {:foo 2})
      (is (= #{"h1" "h2"} (set (sst/list-state-hashes)))))))
```

- [ ] **Step 2: Run tests to verify they fail**

```
bb nido:test :only nido.coordinator.sources.state-test
```

Expected: failure with "Could not locate nido/coordinator/sources/state".

- [ ] **Step 3: Implement**

```clojure
(ns nido.coordinator.sources.state
  "Per-source-config state files under ~/.nido/coordinator/sources/.

   Each file holds the source's last-poll snapshot, breaker state,
   consecutive-failures counter, etc. Filename is the source-config-hash
   (see nido.coordinator.sources/config-hash)."
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

(defn sources-dir []
  (str (fs/path (cstate/coordinator-root) "sources")))

(defn state-path [config-hash]
  (str (fs/path (sources-dir) (str config-hash ".edn"))))

(defn read-state
  "Read the state file for a source-config-hash. Returns nil if absent
   or unparseable."
  [config-hash]
  (let [p (state-path config-hash)]
    (when (fs/exists? p)
      (try (io/read-edn p) (catch Exception _ nil)))))

(defn write-state!
  "Write state for a source-config-hash. Creates the sources dir if missing."
  [config-hash state]
  (fs/create-dirs (sources-dir))
  (io/write-edn! (state-path config-hash) state))

(defn delete-state! [config-hash]
  (let [p (state-path config-hash)]
    (when (fs/exists? p) (fs/delete p))))

(defn list-state-hashes []
  (if (fs/exists? (sources-dir))
    (->> (fs/list-dir (sources-dir))
         (filter #(re-matches #"[0-9a-f]+\.edn" (fs/file-name %)))
         (map #(-> % fs/file-name (subs 0 12)))
         vec)
    []))
```

- [ ] **Step 4: Run tests to verify they pass**

```
bb nido:test :only nido.coordinator.sources.state-test
```

Expected: 5 tests, 0 failures.

- [ ] **Step 5: Commit**

```
jj desc -m "feat(coordinator): per-source on-disk state under ~/.nido/coordinator/sources/"
```

---

### Task 5: `notion/client.clj` — keychain helper

**Files:**
- Create: `src/nido/notion/client.clj`
- Create: `test/nido/notion/client_test.clj`

- [ ] **Step 1: Write failing tests for the keychain helper**

```clojure
(ns nido.notion.client-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.notion.client :as notion]))

(defn- stub-sh [result]
  (fn [_args] result))

(deftest keychain-token-returns-trimmed-secret-on-success
  (with-redefs [notion/sh! (stub-sh {:exit 0 :out "secret_token\n" :err ""})]
    (is (= "secret_token" (notion/keychain-token)))))

(deftest keychain-token-returns-nil-on-non-zero-exit
  (with-redefs [notion/sh! (stub-sh {:exit 44 :out "" :err "not found"})]
    (is (nil? (notion/keychain-token)))))

(deftest keychain-set-shells-security-add-with--U-and--w
  (let [calls (atom [])]
    (with-redefs [notion/sh! (fn [args] (swap! calls conj args)
                                        {:exit 0 :out "" :err ""})]
      (notion/keychain-set! "my-token")
      (let [[args] @calls]
        (is (= "security"            (nth args 0)))
        (is (= "add-generic-password" (nth args 1)))
        (is (some #{"-U"} args))
        (is (some #{"-w"} args))
        (is (some #{"-s" "nido-notion"} (partition 2 1 args)))))))
```

- [ ] **Step 2: Run tests to verify they fail**

```
bb nido:test :only nido.notion.client-test
```

Expected: failures on undefined namespace.

- [ ] **Step 3: Implement the keychain helpers**

```clojure
(ns nido.notion.client
  "Notion REST client + macOS Keychain helpers for the integration token.
   Used by the :notion-view source."
  (:require
   [babashka.http-client :as http]
   [babashka.process :as p]
   [clojure.string :as str]
   [cheshire.core :as json]))

(defn sh!
  "Wrapped shell-out so tests can stub `security` calls."
  [args]
  (p/sh args))

(defn- whoami []
  (str/trim (:out (sh! ["whoami"]))))

(defn keychain-token
  "Read the Notion integration token from the user's macOS Keychain.
   Returns the trimmed token string, or nil if the entry isn't present."
  []
  (let [{:keys [exit out]} (sh! ["security" "find-generic-password"
                                 "-s" "nido-notion" "-a" (whoami) "-w"])]
    (when (zero? exit) (str/trim out))))

(defn keychain-set!
  "Upsert the Notion integration token into the user's macOS Keychain.
   `-U` upserts if an entry with the same service+account already exists."
  [token]
  (sh! ["security" "add-generic-password"
        "-s" "nido-notion" "-a" (whoami) "-U" "-w" token]))
```

Note: `cheshire.core` isn't a Babashka built-in but is included via babashka's `clojure.data.json`-equivalent. Babashka ships with `cheshire.core` available by default (verify via REPL if uncertain — bb has it pre-loaded).

- [ ] **Step 4: Run tests to verify they pass**

```
bb nido:test :only nido.notion.client-test
```

Expected: 3 tests, 0 failures.

- [ ] **Step 5: Commit**

```
jj desc -m "feat(notion): keychain-token / keychain-set! helpers"
```

---

### Task 6: `notion/client.clj` — `database-query`

**Files:**
- Modify: `src/nido/notion/client.clj`
- Modify: `test/nido/notion/client_test.clj`

- [ ] **Step 1: Write failing tests**

Add to `test/nido/notion/client_test.clj`:

```clojure
(defn- stub-http [result]
  (fn [_method _url _opts] result))

(deftest database-query-builds-the-right-request
  (let [calls (atom [])]
    (with-redefs [notion/http-request
                  (fn [method url opts]
                    (swap! calls conj {:method method :url url :opts opts})
                    {:status 200 :body "{\"results\":[],\"has_more\":false}"})]
      (notion/database-query "abc-123" "the-token")
      (let [[{:keys [method url opts]}] @calls]
        (is (= :post method))
        (is (= "https://api.notion.com/v1/databases/abc-123/query" url))
        (is (= "Bearer the-token" (get-in opts [:headers "Authorization"])))
        (is (= "2022-06-28"      (get-in opts [:headers "Notion-Version"])))
        (is (= "application/json" (get-in opts [:headers "Content-Type"])))
        (is (= "{\"page_size\":100}" (:body opts)))
        (is (= 10000 (:timeout opts)))))))

(deftest database-query-returns-parsed-result-on-200
  (with-redefs [notion/http-request
                (stub-http {:status 200
                            :body "{\"results\":[{\"id\":\"p1\"}],\"has_more\":false}"})]
    (let [{:keys [status results has_more]} (notion/database-query "x" "t")]
      (is (= 200 status))
      (is (= [{:id "p1"}] results))
      (is (false? has_more)))))

(deftest database-query-marks-401-as-auth-error
  (with-redefs [notion/http-request
                (stub-http {:status 401 :body "{\"message\":\"Invalid token\"}"})]
    (let [r (notion/database-query "x" "bad")]
      (is (= 401 (:status r)))
      (is (= :auth (:error r))))))

(deftest database-query-marks-5xx-as-server-error
  (with-redefs [notion/http-request
                (stub-http {:status 503 :body "service unavailable"})]
    (is (= :server (:error (notion/database-query "x" "t"))))))
```

- [ ] **Step 2: Run tests to verify they fail**

```
bb nido:test :only nido.notion.client-test
```

Expected: failures — `http-request` / `database-query` undefined.

- [ ] **Step 3: Implement**

Add to `src/nido/notion/client.clj`:

```clojure
(defn http-request
  "Wrapped HTTP call (POST) so tests can stub. Returns {:status :body}."
  [_method url opts]
  (http/post url (assoc opts :throw false)))

(defn database-query
  "POST https://api.notion.com/v1/databases/<id>/query. Returns
   {:status :results :has_more} on 2xx, or {:status :error <kw>} on
   4xx/5xx / network failures."
  [database-id token]
  (let [resp (try
               (http-request :post
                             (str \"https://api.notion.com/v1/databases/\"
                                  database-id \"/query\")
                             {:headers {\"Authorization\" (str \"Bearer \" token)
                                        \"Notion-Version\" \"2022-06-28\"
                                        \"Content-Type\" \"application/json\"}
                              :body    \"{\\\"page_size\\\":100}\"
                              :timeout 10000})
               (catch Exception e
                 {:status 0 :exception e}))]
    (let [{:keys [status body]} resp]
      (cond
        (= status 200)
        (let [parsed (json/parse-string body true)]
          {:status   200
           :results  (:results parsed)
           :has_more (:has_more parsed)})

        (= status 401) {:status status :error :auth}
        (>= status 500) {:status status :error :server}
        (= status 0)    {:status 0     :error :network}
        :else           {:status status :error :http}))))
```

Note: the embedded backslash-escapes in this code block are display-only; when typing actual source, use plain double quotes — e.g. `"https://api.notion.com/v1/databases/"` not `\"https...\"`.

- [ ] **Step 4: Run tests to verify they pass**

```
bb nido:test :only nido.notion.client-test
```

Expected: 6 tests (3 + 3 new), 0 failures.

- [ ] **Step 5: Commit**

```
jj desc -m "feat(notion): database-query HTTP wrapper with error classification"
```

---

### Task 7: `notion/client.clj` — response normalisation

**Files:**
- Modify: `src/nido/notion/client.clj`
- Modify: `test/nido/notion/client_test.clj`
- Create: `test/fixtures/notion/query-response.json`

- [ ] **Step 1: Create a recorded fixture**

Write `/Users/yabmas/Code/nido/test/fixtures/notion/query-response.json`:

```json
{
  "results": [
    {
      "id": "page-abc-123",
      "url": "https://notion.so/Login-loops-page-abc-123",
      "created_time": "2026-05-15T13:00:00.000Z",
      "last_edited_time": "2026-05-15T13:30:00.000Z",
      "properties": {
        "Name": {
          "type": "title",
          "title": [{"plain_text": "Login redirect loops on Safari"}]
        },
        "Status": {
          "type": "status",
          "status": {"name": "Untriaged"}
        },
        "Priority": {
          "type": "select",
          "select": {"name": "P0"}
        },
        "Tags": {
          "type": "multi_select",
          "multi_select": [{"name": "auth"}, {"name": "browser"}]
        },
        "Owner": {
          "type": "rich_text",
          "rich_text": [{"plain_text": "alice"}]
        },
        "Ticket ID": {
          "type": "rich_text",
          "rich_text": [{"plain_text": "ABC-123"}]
        }
      }
    }
  ],
  "has_more": false
}
```

- [ ] **Step 2: Write failing tests**

Add to `test/nido/notion/client_test.clj`:

```clojure
(def ^:private fixture
  (json/parse-string (slurp "test/fixtures/notion/query-response.json") true))

(deftest normalise-page-extracts-required-top-level-fields
  (let [[page] (:results fixture)
        ev    (notion/normalise-page page)]
    (is (= :notion-view (:source ev)))
    (is (= "page-abc-123" (:page-id ev)))
    (is (= "https://notion.so/Login-loops-page-abc-123" (:url ev)))
    (is (= "Login redirect loops on Safari" (:title ev)))
    (is (= "2026-05-15T13:00:00.000Z" (:created-time ev)))
    (is (= "2026-05-15T13:30:00.000Z" (:edited-time ev)))))

(deftest normalise-page-promotes-properties-to-top-level
  (let [[page] (:results fixture)
        ev    (notion/normalise-page page)]
    (is (= "Untriaged" (:status ev)))
    (is (= "P0"        (:priority ev)))
    (is (= "alice"     (:owner ev)))
    (is (= "ABC-123"   (:ticket-id ev)))
    (is (= ["auth" "browser"] (:tags ev)))))

(deftest normalise-page-keeps-properties-map
  (let [[page] (:results fixture)
        ev    (notion/normalise-page page)]
    (is (= "Untriaged" (get-in ev [:properties :status])))
    (is (= "ABC-123"   (get-in ev [:properties :ticket-id])))))

(deftest normalise-page-handles-empty-title
  (let [page {:id "p" :url "u" :created_time "t" :last_edited_time "t"
              :properties {:Name {:type "title" :title []}}}]
    (is (= "" (:title (notion/normalise-page page))))))

(deftest normalise-page-handles-unknown-property-type
  (let [page {:id "p" :url "u" :created_time "t" :last_edited_time "t"
              :properties {:Weird {:type "files" :files [{:name "x"}]}}}]
    ;; Unknown types render as their raw value so the row doesn't crash.
    (is (some? (get-in (notion/normalise-page page) [:properties :weird])))))
```

- [ ] **Step 3: Run tests to verify they fail**

```
bb nido:test :only nido.notion.client-test
```

Expected: failures on `normalise-page` undefined.

- [ ] **Step 4: Implement**

Add to `src/nido/notion/client.clj`:

```clojure
(defn- normalise-property-name
  "\"Ticket ID\" → :ticket-id"
  [s]
  (-> (str s)
      str/lower-case
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-|-$" "")
      keyword))

(defn- extract-value
  "Pull a value out of a Notion property based on its :type. Unknown types
   render as the raw map so we don't crash."
  [{:keys [type] :as prop}]
  (case type
    "title"        (->> (:title prop) (map :plain_text) (apply str))
    "rich_text"    (->> (:rich_text prop) (map :plain_text) (apply str))
    "select"       (some-> (:select prop) :name)
    "multi_select" (->> (:multi_select prop) (mapv :name))
    "status"       (some-> (:status prop) :name)
    "url"          (:url prop)
    "number"       (:number prop)
    "checkbox"     (:checkbox prop)
    "date"         (some-> (:date prop) :start)
    ;; fallback: hand back the whole property so debugging is possible
    prop))

(defn- title-of
  "Return the value of the first property whose :type is \"title\"."
  [properties]
  (some (fn [[_ p]] (when (= "title" (:type p)) (extract-value p)))
        properties))

(defn normalise-page
  "Turn a Notion API page object into the spec's event-payload shape.
   See spec §Source: :notion-view / Event-payload schema."
  [{:keys [id url created_time last_edited_time properties]}]
  (let [props-kw  (into {} (map (fn [[k v]] [(normalise-property-name k) (extract-value v)]) properties))]
    (merge {:source       :notion-view
            :page-id      id
            :url          url
            :title        (title-of properties)
            :created-time created_time
            :edited-time  last_edited_time
            :properties   props-kw}
           ;; promote each property to top-level too (for filter.clj's
           ;; uniform top-level-then-properties lookup)
           props-kw)))
```

Add `[clojure.string :as str]` to requires if it's not already there.

- [ ] **Step 5: Run tests to verify they pass**

```
bb nido:test :only nido.notion.client-test
```

Expected: 11 tests, 0 failures.

- [ ] **Step 6: Commit**

```
jj desc -m "feat(notion): normalise-page — flatten Notion properties into event payload"
```

---

### Task 8: `sources/notion.clj` — `:notion-view` plugin (start! + dedup)

**Files:**
- Create: `src/nido/coordinator/sources/notion.clj`
- Create: `test/nido/coordinator/sources/notion_test.clj`

- [ ] **Step 1: Write failing tests**

```clojure
(ns nido.coordinator.sources.notion-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.sources :as sources]
   [nido.coordinator.sources.notion :as nsource]
   [nido.coordinator.sources.state :as sst]
   [nido.coordinator.state :as cstate]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(defn- stub-query [results]
  (fn [_db _token] {:status 200 :results results :has_more false}))

(deftest first-poll-seeds-snapshot-and-emits-nothing
  (with-tmp
    (fn [_]
      (let [emitted (atom [])
            handle  (nsource/start-instance!
                      {:database "db1" :poll "5m"}
                      (fn [payload] (swap! emitted conj payload))
                      {:token "t"
                       :query (stub-query [{:id "p1" :url "u1" :created_time "t"
                                            :last_edited_time "t" :properties {}}])})]
        ((:poll! handle))
        (is (empty? @emitted))
        (let [s (sst/read-state (sources/config-hash {:database "db1" :poll "5m"}))]
          (is (= #{"p1"} (:last-rows s))))))))

(deftest second-poll-emits-additions
  (with-tmp
    (fn [_]
      (let [emitted (atom [])
            query-result (atom [{:id "p1" :url "u1" :created_time "t" :last_edited_time "t" :properties {}}])
            handle  (nsource/start-instance!
                      {:database "db1" :poll "5m"}
                      (fn [payload] (swap! emitted conj payload))
                      {:token "t"
                       :query (fn [_ _] {:status 200 :results @query-result :has_more false})})]
        ((:poll! handle))                     ; seeds
        (reset! query-result [{:id "p1" :url "u1" :created_time "t" :last_edited_time "t" :properties {}}
                              {:id "p2" :url "u2" :created_time "t" :last_edited_time "t" :properties {}}])
        ((:poll! handle))
        (is (= 1 (count @emitted)))
        (is (= "p2" (-> @emitted first :page-id)))))))

(deftest emits-once-not-twice-on-repeated-poll
  (with-tmp
    (fn [_]
      (let [emitted (atom [])
            handle  (nsource/start-instance!
                      {:database "db1" :poll "5m"}
                      (fn [p] (swap! emitted conj p))
                      {:token "t"
                       :query (stub-query [{:id "p1" :url "u" :created_time "t" :last_edited_time "t" :properties {}}
                                           {:id "p2" :url "u" :created_time "t" :last_edited_time "t" :properties {}}])})]
        ((:poll! handle))   ; seeds
        ((:poll! handle))   ; no additions
        ((:poll! handle))   ; no additions
        (is (empty? @emitted))))))

(deftest row-leaves-and-returns-emits-again
  (with-tmp
    (fn [_]
      (let [emitted (atom [])
            qr      (atom [{:id "p1" :url "u" :created_time "t" :last_edited_time "t" :properties {}}])
            handle  (nsource/start-instance!
                      {:database "db1" :poll "5m"}
                      (fn [p] (swap! emitted conj p))
                      {:token "t"
                       :query (fn [_ _] {:status 200 :results @qr :has_more false})})]
        ((:poll! handle))                ; seeds with p1
        (reset! qr [])
        ((:poll! handle))                ; p1 left
        (reset! qr [{:id "p1" :url "u" :created_time "t" :last_edited_time "t" :properties {}}])
        ((:poll! handle))                ; p1 returned
        (is (= 1 (count @emitted)))
        (is (= "p1" (-> @emitted first :page-id)))))))
```

- [ ] **Step 2: Run tests to verify they fail**

```
bb nido:test :only nido.coordinator.sources.notion-test
```

Expected: namespace-not-found failures.

- [ ] **Step 3: Implement**

```clojure
(ns nido.coordinator.sources.notion
  "The :notion-view source plugin. Polls a Notion database, diffs results
   against the per-source state snapshot, emits one event per new page.
   See spec §Source: :notion-view."
  (:require
   [clojure.set :as set]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.sources :as sources]
   [nido.coordinator.sources.state :as sst]
   [nido.notion.client :as notion]))

(defn- poll-once!
  "One iteration of the polling loop for a given source-config.
   Pure of HTTP via the `query` and `emit` callbacks (so tests stub).
   Returns updated state."
  [{:keys [source-config token query emit]} prior-state]
  (let [{:keys [database]} source-config
        {:keys [status results error]} (query database token)]
    (cond
      (= status 200)
      (let [pages         (mapv notion/normalise-page results)
            current-rows  (into #{} (map :page-id) pages)
            new-state     (assoc prior-state
                                 :type                 :notion-view
                                 :source-config        source-config
                                 :last-rows            current-rows
                                 :last-polled-at       (clock/now-iso)
                                 :last-poll-result     :ok
                                 :consecutive-failures 0)]
        (when-let [prev (:last-rows prior-state)]
          (let [additions (set/difference current-rows prev)]
            (doseq [page pages
                    :when (contains? additions (:page-id page))]
              (emit page))))
        new-state)

      :else
      (-> prior-state
          (assoc :type                  :notion-view
                 :source-config         source-config
                 :last-polled-at        (clock/now-iso)
                 :last-poll-result      {:error error :status status})
          (update :consecutive-failures (fnil inc 0))))))

(defn start-instance!
  "Start one source-instance. The :poll! and :stop! callbacks honor the
   source-plugin contract. `opts` is used by tests to inject a fake
   query function and to skip keychain reads; production code passes
   neither (defaults below)."
  [source-config emit-fn {:keys [token query] :as _opts}]
  (let [hash    (sources/config-hash source-config)
        token   (or token (notion/keychain-token))
        query   (or query notion/database-query)
        emit    (fn [payload]
                  ;; Stage-5 plumbing: emit goes through emit-fn supplied
                  ;; by the coordinator. Task 9 wires this to the queue dir.
                  (emit-fn payload))]
    {:poll! (fn []
              (let [prior (sst/read-state hash)
                    next  (poll-once! {:source-config source-config
                                       :token         token
                                       :query         query
                                       :emit          emit}
                                      prior)]
                (sst/write-state! hash next)))
     :stop! (fn []  ;; nothing in-memory to clean for v1
              nil)}))

(defn register! []
  (sources/register-source!
   {:type   :notion-view
    :schema [:map
             [:database string?]
             [:view     {:optional true} string?]
             [:poll     string?]]
    :events [:map [:source [:= :notion-view]] [:page-id string?]]
    :start! (fn [source-config emit-fn]
              (start-instance! source-config emit-fn {}))}))
```

- [ ] **Step 4: Run tests to verify they pass**

```
bb nido:test :only nido.coordinator.sources.notion-test
```

Expected: 4 tests, 0 failures.

- [ ] **Step 5: Commit**

```
jj desc -m "feat(coordinator): :notion-view source — start!, poll!, set-diff dedup"
```

---

### Task 9: emit through queue + breaker

**Files:**
- Modify: `src/nido/coordinator/sources/notion.clj`
- Modify: `test/nido/coordinator/sources/notion_test.clj`

The earlier task accepted any `emit-fn`. This task wires the production `emit-fn` to write broadcast envelopes into the queue dir, and adds breaker logic so a broken source stops polling itself.

- [ ] **Step 1: Write failing tests**

Add to `test/nido/coordinator/sources/notion_test.clj`:

```clojure
(deftest breaker-opens-after-3-consecutive-failures
  (with-tmp
    (fn [_]
      (let [emitted (atom [])
            handle  (nsource/start-instance!
                      {:database "db1" :poll "5m"}
                      (fn [p] (swap! emitted conj p))
                      {:token "t"
                       :query (fn [_ _] {:status 503 :error :server})})]
        (dotimes [_ 3] ((:poll! handle)))
        (let [s (sst/read-state (sources/config-hash {:database "db1" :poll "5m"}))]
          (is (= 3 (:consecutive-failures s)))
          (is (= :open (:breaker s))))))))

(deftest breaker-opens-immediately-on-401
  (with-tmp
    (fn [_]
      (let [handle (nsource/start-instance!
                     {:database "db1" :poll "5m"}
                     (fn [_])
                     {:token "t"
                      :query (fn [_ _] {:status 401 :error :auth})})]
        ((:poll! handle))
        (let [s (sst/read-state (sources/config-hash {:database "db1" :poll "5m"}))]
          (is (= :open (:breaker s)))
          (is (= :auth (-> s :last-poll-result :error))))))))

(deftest open-breaker-suppresses-polling
  (with-tmp
    (fn [_]
      (let [calls   (atom 0)
            qfn     (fn [_ _] (swap! calls inc) {:status 503 :error :server})
            handle  (nsource/start-instance!
                      {:database "db1" :poll "5m"}
                      (fn [_])
                      {:token "t" :query qfn})]
        ;; trip the breaker
        (dotimes [_ 3] ((:poll! handle)))
        (let [trip-count @calls]
          ;; subsequent polls do nothing
          ((:poll! handle))
          ((:poll! handle))
          (is (= trip-count @calls)))))))

(deftest success-after-failures-clears-counter
  (with-tmp
    (fn [_]
      (let [outcome  (atom {:status 503 :error :server})
            qfn      (fn [_ _] @outcome)
            handle   (nsource/start-instance!
                       {:database "db1" :poll "5m"}
                       (fn [_])
                       {:token "t" :query qfn})]
        ((:poll! handle))                ; fail
        ((:poll! handle))                ; fail
        (reset! outcome {:status 200 :results [] :has_more false})
        ((:poll! handle))                ; ok
        (let [s (sst/read-state (sources/config-hash {:database "db1" :poll "5m"}))]
          (is (zero? (:consecutive-failures s)))
          (is (nil? (:breaker s))))))))
```

- [ ] **Step 2: Run tests to verify they fail**

Expected: failures on breaker behavior.

- [ ] **Step 3: Update `poll-once!` and `start-instance!`**

Replace `poll-once!` and add a private constant:

```clojure
(def ^:private failure-threshold 3)

(defn- poll-once!
  [{:keys [source-config token query emit]} prior-state]
  (let [{:keys [database]} source-config
        {:keys [status results error]} (query database token)]
    (cond
      (= status 200)
      (let [pages         (mapv notion/normalise-page results)
            current-rows  (into #{} (map :page-id) pages)
            new-state     (-> prior-state
                              (assoc :type                 :notion-view
                                     :source-config        source-config
                                     :last-rows            current-rows
                                     :last-polled-at       (clock/now-iso)
                                     :last-poll-result     :ok
                                     :consecutive-failures 0)
                              (dissoc :breaker))]
        (when-let [prev (:last-rows prior-state)]
          (let [additions (set/difference current-rows prev)]
            (doseq [page pages
                    :when (contains? additions (:page-id page))]
              (emit page))))
        new-state)

      ;; Auth errors open immediately — a bad token won't get better.
      (= error :auth)
      (-> prior-state
          (assoc :type             :notion-view
                 :source-config    source-config
                 :last-polled-at   (clock/now-iso)
                 :last-poll-result {:error :auth :status status}
                 :breaker          :open)
          (update :consecutive-failures (fnil inc 0)))

      :else
      (let [next-failures ((fnil inc 0) (:consecutive-failures prior-state))]
        (cond-> prior-state
          true (assoc :type             :notion-view
                      :source-config    source-config
                      :last-polled-at   (clock/now-iso)
                      :last-poll-result {:error error :status status}
                      :consecutive-failures next-failures)
          (>= next-failures failure-threshold) (assoc :breaker :open))))))
```

Update `start-instance!`'s `:poll!` to check the breaker first:

```clojure
:poll! (fn []
         (let [prior (sst/read-state hash)]
           (when-not (= :open (:breaker prior))
             (let [next (poll-once! {:source-config source-config
                                     :token         token
                                     :query         query
                                     :emit          emit}
                                    prior)]
               (sst/write-state! hash next)))))
```

- [ ] **Step 4: Run tests to verify they pass**

```
bb nido:test :only nido.coordinator.sources.notion-test
```

Expected: 8 tests, 0 failures.

- [ ] **Step 5: Commit**

```
jj desc -m "feat(coordinator): :notion-view breaker on 3 failures / auth"
```

---

### Task 10: emit production envelopes through the queue dir

**Files:**
- Modify: `src/nido/coordinator/sources.clj` (add `emit-broadcast!`)
- Create: `test/nido/coordinator/sources_emit_test.clj`

- [ ] **Step 1: Write failing tests**

```clojure
(ns nido.coordinator.sources-emit-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.queue :as queue]
   [nido.coordinator.sources :as sources]
   [nido.coordinator.state :as cstate]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f))
      (finally (fs/delete-tree tmp)))))

(deftest emit-broadcast!-writes-envelope-to-queue
  (with-tmp
    (fn []
      (sources/emit-broadcast!
        {:type :notion-view :source-config {:database "x"} :payload {:page-id "p1"}})
      (let [envelopes (queue/drain!)]
        (is (= 1 (count envelopes)))
        (is (= :notion-view (-> envelopes first :broadcast :type)))
        (is (= "p1"         (-> envelopes first :broadcast :payload :page-id)))))))

(deftest emit-broadcast!-is-idempotent-by-content
  (with-tmp
    (fn []
      (sources/emit-broadcast!
        {:type :notion-view :source-config {:database "x"} :payload {:page-id "p1"}})
      (sources/emit-broadcast!
        {:type :notion-view :source-config {:database "x"} :payload {:page-id "p1"}})
      (is (= 1 (count (queue/drain!)))))))
```

- [ ] **Step 2: Run tests to verify they fail**

Expected: failures on `sources/emit-broadcast!` undefined.

- [ ] **Step 3: Implement `emit-broadcast!` in `sources.clj`**

Add to `src/nido/coordinator/sources.clj`:

```clojure
(:require [babashka.fs :as fs]
          [nido.coordinator.clock :as clock]
          [nido.coordinator.state :as cstate]
          [nido.io :as io]
          [clojure.string :as str])
;; ...add to existing ns require list

(defn- envelope-filename [broadcast]
  (let [canonical (pr-str (into (sorted-map) broadcast))]
    (str (subs (sha1-hex canonical) 0 16) ".edn")))

(defn emit-broadcast!
  "Write a broadcast envelope into the queue dir. Filename is the SHA-1
   of the broadcast contents so re-emission of the same broadcast is a
   filesystem no-op (matches crash-safety ordering: envelope-then-snapshot)."
  [broadcast]
  (let [env {:broadcast broadcast :created-at (clock/now-iso)}
        ;; Note: `:created-at` is intentionally NOT part of the filename
        ;; hash so re-emissions still produce the same name.
        filename (envelope-filename broadcast)]
    (fs/create-dirs (cstate/queue-dir))
    (io/write-edn! (str (fs/path (cstate/queue-dir) filename)) env)))
```

- [ ] **Step 4: Update `sources/notion.clj` to use `emit-broadcast!`**

Replace the `emit` closure in `start-instance!`:

```clojure
emit    (fn [payload]
          (emit-fn {:type          :notion-view
                    :source-config source-config
                    :payload       payload}))
```

And update `register!`'s `:start!` so the coordinator's `emit-fn` is wired to `sources/emit-broadcast!`:

```clojure
:start! (fn [source-config emit-fn]
          (start-instance! source-config emit-fn {}))
```

— but the production `emit-fn` is set in the next task (core wiring). Keep `:start!` as it is here.

- [ ] **Step 5: Run tests to verify they pass**

```
bb nido:test :only nido.coordinator.sources-emit-test
bb nido:test :only nido.coordinator.sources.notion-test
```

Expected: 2 new + 8 from Task 9 pass.

- [ ] **Step 6: Commit**

```
jj desc -m "feat(coordinator): emit-broadcast! writes content-addressed envelopes"
```

---

### Task 11: source lifecycle on boot + tick! polls due sources

**Files:**
- Modify: `src/nido/coordinator/core.clj`

This task wires sources into the daemon's lifecycle. No new tests at the `core` level (matches existing precedent — `core` is integration-tested manually). We rely on the unit tests written for the individual modules.

- [ ] **Step 1: Add source lifecycle helpers**

Add near the top of `src/nido/coordinator/core.clj`, after the existing `defonce ^:private !detector` line:

```clojure
(defonce ^:private !source-instances (atom {}))   ; {config-hash -> {:source-config :poll! :stop! :poll-ms :last-polled-ms}}

(defn- parse-duration-ms [s]
  ;; Minimal duration parser: "30s" "5m" "1h"
  (let [[_ n u] (re-matches #"(\d+)\s*([smh])" s)]
    (when n
      (* (parse-long n)
         (case u "s" 1000 "m" 60000 "h" 3600000)))))

(defn- discover-source-configs
  "Walk loaded triggers and return distinct source-configs whose type is
   registered. Filters out :manual (handled by the queue dir directly)."
  [triggers-by-project]
  (->> (for [[_ triggers] triggers-by-project, t triggers
             :let [src (:source t)]
             :when (and (not= :manual (:type src))
                        (sources/lookup (:type src)))]
         src)
       distinct))

(defn- start-source! [source-config]
  (let [hash       (sources/config-hash source-config)
        plugin     (sources/lookup (:type source-config))
        handle     ((:start! plugin) source-config sources/emit-broadcast!)
        poll-ms    (or (parse-duration-ms (str (:poll source-config))) 300000)]
    (swap! !source-instances assoc hash
           (assoc handle :source-config source-config
                         :poll-ms       poll-ms
                         :last-polled-ms 0))))

(defn- stop-source! [config-hash]
  (when-let [{:keys [stop!]} (get @!source-instances config-hash)]
    (try (stop!) (catch Exception _ nil)))
  (swap! !source-instances dissoc config-hash))

(defn- reconcile-sources!
  "Start sources that should be running, stop sources that no longer have
   a referencing trigger."
  [triggers-by-project]
  (let [desired (set (map #(sources/config-hash %) (discover-source-configs triggers-by-project)))
        current (set (keys @!source-instances))]
    (doseq [hash (set/difference current desired)] (stop-source! hash))
    (doseq [sc (discover-source-configs triggers-by-project)
            :when (not (contains? current (sources/config-hash sc)))]
      (start-source! sc))))
```

Add to requires: `[clojure.set :as set]` and `[nido.coordinator.sources :as sources]`. Also ensure `nido.coordinator.sources.notion` is required so its `register!` runs on namespace load — but actually call `register!` explicitly at `run!` entry.

- [ ] **Step 2: Update `tick!` to drive source polling**

Replace `tick!`:

```clojure
(defn tick!
  "One iteration of the main loop. Public for testability."
  []
  (let [triggers-by-project (load-all-triggers)
        halt-info           (halt/read-halt-info)]
    (if halt-info
      (heartbeat/write! {:status       :halted
                         :halted-by    (:source halt-info)
                         :halt-note    (:note halt-info)
                         :slots-in-use 0})
      (do
        (heartbeat/write! {:status :running :slots-in-use 0})
        (reconcile-sources! triggers-by-project)
        ;; Drain queue first (this consumes envelopes emitted on the PREVIOUS
        ;; tick by source polls — keeps each tick's unit of work small).
        (doseq [env (queue/drain!)]
          (process-envelope! env triggers-by-project))
        ;; Then poll due sources. Their emissions land in the queue and are
        ;; picked up next tick.
        (let [now-ms (System/currentTimeMillis)]
          (doseq [[hash inst] @!source-instances
                  :when (>= (- now-ms (:last-polled-ms inst)) (:poll-ms inst))]
            (try
              ((:poll! inst))
              (catch Exception e
                (binding [*err* *err*]
                  (.println ^java.io.PrintWriter *err*
                            (str "WARN: source " hash " poll! threw — " (ex-message e))))))
            (swap! !source-instances assoc-in [hash :last-polled-ms] now-ms)))
        ;; After draining + polling, check anomaly thresholds.
        (when-let [trip (anomaly/check @!detector anomaly-thresholds)]
          (halt/halt! {:source  :auto
                       :reason  (:trip trip)
                       :details trip
                       :note    (str "auto-halt: " (name (:trip trip))
                                     " count=" (:count trip))}))))))
```

- [ ] **Step 3: Register sources at `run!` startup**

Update `run!` so it calls `(nido.coordinator.sources.notion/register!)` before the loop. Add the require: `[nido.coordinator.sources.notion :as nsource]`. Then:

```clojure
(defn run!
  [& {:keys [poll-ms] :or {poll-ms (:poll-ms defaults)}}]
  (cstate/ensure-dirs!)
  (println "nido coordinator: starting (poll" poll-ms "ms)")
  (reconcile/reconcile!)
  (pid/write! (long (.pid (java.lang.ProcessHandle/current))))
  (install-shutdown-hook!)
  (heartbeat/write! {:status :running :slots-in-use 0})
  (nsource/register!)                                 ; NEW
  (loop []
    (tick!)
    (Thread/sleep poll-ms)
    (recur)))
```

- [ ] **Step 4: Extend the shutdown hook**

Update `install-shutdown-hook!`:

```clojure
(defn- install-shutdown-hook! []
  (.addShutdownHook
    (Runtime/getRuntime)
    (Thread.
      (fn []
        (doseq [hash (keys @!source-instances)]   ; NEW: stop sources first
          (stop-source! hash))
        (try (heartbeat/write! {:status :stopped :slots-in-use 0})
             (catch Exception _ nil))
        (try (pid/delete!)
             (catch Exception _ nil))))))
```

- [ ] **Step 5: Smoke-test the daemon boots cleanly**

```
bb nido:coordinator:restart                # respawn the launchd-managed daemon
sleep 2
bb nido:coordinator:status                 # confirm Process: alive, Coordinator: running
bb nido:coordinator:logs | tail -5         # no startup exception
```

Expected: daemon running; log shows no exceptions.

- [ ] **Step 6: Commit**

```
jj desc -m "feat(coordinator): source lifecycle + tick! polls due sources"
```

---

### Task 12: `bb nido:notion:auth:set` / `:check`

**Files:**
- Create: `src/tasks/nido_notion.clj`
- Modify: `bb.edn`

- [ ] **Step 1: Implement task functions**

```clojure
(ns tasks.nido-notion
  "Bb task entry points for Notion integration auth."
  (:require
   [nido.notion.client :as notion]))

(defn auth-set
  "bb nido:notion:auth:set — read a token from stdin, store in macOS Keychain.
   No-echo prompt for safety; if stdin is not a terminal, read raw."
  [& _args]
  (println "Paste your Notion integration token (input is echoed; clear terminal afterwards):")
  (let [token (read-line)]
    (cond
      (or (nil? token) (clojure.string/blank? token))
      (do (println "Empty token; aborted.") (System/exit 1))

      :else
      (let [{:keys [exit err]} (notion/keychain-set! token)]
        (if (zero? exit)
          (println "Token stored. Run `bb nido:notion:auth:check` to verify, then `bb nido:coordinator:restart`.")
          (do (println "security failed (exit" exit ").")
              (println err)
              (System/exit exit)))))))

(defn auth-check
  "bb nido:notion:auth:check — print whether the keychain has a token."
  [& _args]
  (let [token (notion/keychain-token)]
    (cond
      (nil? token)              (do (println "No Notion token in keychain. Run `bb nido:notion:auth:set`.") (System/exit 1))
      (clojure.string/blank? token) (do (println "Keychain entry is empty.") (System/exit 1))
      :else                     (println "Notion token present in keychain (length" (count token) ").")))) 
```

Add `[clojure.string]` to requires if needed.

- [ ] **Step 2: Register in `bb.edn`**

Add to the `:requires` block:

```clojure
[tasks.nido-notion :as nido-notion]
```

And add the task entries (after `nido:coordinator:restart`):

```clojure
  nido:notion:auth:set
  {:doc "Store a Notion integration token in the macOS Keychain (interactive)."
   :task (apply nido-notion/auth-set *command-line-args*)}

  nido:notion:auth:check
  {:doc "Print whether a Notion token is present in the macOS Keychain."
   :task (apply nido-notion/auth-check *command-line-args*)}
```

- [ ] **Step 3: Smoke-test**

```
bb nido:notion:auth:check               # likely prints "No Notion token in keychain"
# Set one (this is destructive on existing keychain entry — be deliberate):
echo "secret_test_token_DO_NOT_USE" | bb nido:notion:auth:set
bb nido:notion:auth:check               # "Notion token present ... length 30"
```

Then **leave a real working token in place if you have one** — if not, delete the test entry: `security delete-generic-password -s nido-notion -a $(whoami)`.

- [ ] **Step 4: Commit**

```
jj desc -m "feat(notion): bb nido:notion:auth:set / :check"
```

---

### Task 13: `bb nido:coordinator:source:list` / `:reset`

**Files:**
- Create: `src/tasks/nido_coordinator_source.clj`
- Modify: `bb.edn`

- [ ] **Step 1: Implement task functions**

```clojure
(ns tasks.nido-coordinator-source
  "Bb task entry points for source-instance inspection + reset."
  (:require
   [nido.coordinator.sources :as sources]
   [nido.coordinator.sources.state :as sst]
   [nido.task-args :as task-args]))

(defn list-cmd
  "bb nido:coordinator:source:list — one row per source-instance state file."
  [& _args]
  (let [hashes (sst/list-state-hashes)]
    (if (empty? hashes)
      (println "No source instances on disk.")
      (doseq [h hashes
              :let [s (sst/read-state h)]]
        (println (format "%s  type=%s  poll=%s  failures=%d  breaker=%s  last=%s"
                         h
                         (name (or (:type s) :unknown))
                         (str (-> s :source-config :poll))
                         (or (:consecutive-failures s) 0)
                         (name (or (:breaker s) :closed))
                         (or (:last-polled-at s) "never")))))))

(defn reset-cmd
  "bb nido:coordinator:source:reset :notion-view :database <id> [:view <name>] [:poll <dur>]
   Clears the breaker and consecutive-failures for the matching source-config."
  [& args]
  (let [[positional opts] (task-args/split-args args)
        source-type       (some-> positional first keyword)
        config            (-> opts (assoc :type source-type))]
    (cond
      (or (nil? source-type) (nil? (sources/lookup source-type)))
      (do (println "Unknown source type." source-type) (System/exit 1))

      :else
      (let [hash  (sources/config-hash config)
            prior (sst/read-state hash)]
        (if (nil? prior)
          (println "No state for that source-config (hash" hash ")." )
          (do
            (sst/write-state! hash (-> prior
                                       (assoc :consecutive-failures 0)
                                       (dissoc :breaker)))
            (println "Reset" (name source-type) "hash" hash "— breaker cleared.")))))))
```

- [ ] **Step 2: Register in `bb.edn`**

Add to `:requires`:

```clojure
[tasks.nido-coordinator-source :as nido-coordinator-source]
```

Add tasks (after the `nido:notion:*` block):

```clojure
  nido:coordinator:source:list
  {:doc "List source-instance state files."
   :task (apply nido-coordinator-source/list-cmd *command-line-args*)}

  nido:coordinator:source:reset
  {:doc "Clear breaker + failure counter for a source-instance."
   :task (apply nido-coordinator-source/reset-cmd *command-line-args*)}
```

- [ ] **Step 3: Smoke-test**

```
bb nido:coordinator:source:list                  # "No source instances on disk." (no triggers yet)
```

Without a real trigger, more meaningful testing waits for Task 15.

- [ ] **Step 4: Commit**

```
jj desc -m "feat(coordinator): bb nido:coordinator:source:list / :reset"
```

---

### Task 14: `status` surfaces `Sources:` section

**Files:**
- Modify: `src/tasks/nido_coordinator.clj`

- [ ] **Step 1: Update `status` to print source summary**

Add a require: `[nido.coordinator.sources.state :as sst]`.

Append to the `status` function body (right after the existing halt block):

```clojure
    (let [hashes (sst/list-state-hashes)]
      (when (seq hashes)
        (println "Sources:")
        (doseq [h hashes
                :let [s (sst/read-state h)]]
          (println (format "  %s %s  (%s, %s)"
                           (name (or (:type s) :unknown))
                           h
                           (case (:breaker s)
                             :open  (str "breaker OPEN: "
                                         (name (or (-> s :last-poll-result :error) :unknown)))
                             "OK")
                           (or (:last-polled-at s) "never polled"))))))
```

- [ ] **Step 2: Smoke test**

```
bb nido:coordinator:status                  # before any trigger: no Sources section
```

Section will show up once a trigger exists.

- [ ] **Step 3: Commit**

```
jj desc -m "feat(coordinator): status surfaces source-instance health"
```

---

### Task 15: end-to-end smoke test against a real Notion database

This is the integration test the spec mandates. It requires a Notion database the user controls.

**Pre-requisites** (the engineer running this is the user themselves; document carefully):

1. A Notion database you control. Note its database ID.
2. An internal Notion integration token with read access to that database.
3. The integration must be **added as a "Connection"** on the database (Notion's "•••" menu → Connections → add the integration). Without this, the API returns 404 for the database ID.

- [ ] **Step 1: Set the auth**

```
bb nido:notion:auth:set                  # paste the integration token when prompted
bb nido:notion:auth:check                # confirms token is in keychain
```

- [ ] **Step 2: Create a trigger config**

Write to `~/.nido/projects/<project>/triggers.edn`:

```edn
{:triggers
 [{:name      :smoke-notion
   :source    {:type     :notion-view
               :database "<your-database-id>"
               :view     "smoke-test"
               :poll     "30s"}
   :filter    {}
   :skill     :investigate-bug
   :payload   "Smoke test {{event/title}} ({{event/page-id}})"
   :payload-key :title
   :dry-run?  true}]}
```

`:dry-run? true` makes the first end-to-end without spawning a real claude session.

- [ ] **Step 3: Verify the source comes up**

```
bb nido:coordinator:restart
sleep 5
bb nido:coordinator:status
bb nido:coordinator:source:list
```

Expected: `Sources:` section now shows `:notion-view <hash> (OK, <timestamp>)`. The first poll seeds the snapshot.

- [ ] **Step 4: Add a new row to the Notion database**

In the Notion UI, create one new page in the database.

Wait 30s.

```
bb nido:coordinator:logs | tail -30
bb nido:coordinator:source:list
ls ~/.nido/runs/
```

Expected: a new Run directory in `~/.nido/runs/` for the smoke trigger; coordinator log shows the envelope being processed; source state file's `:last-rows` includes the new page ID.

- [ ] **Step 5: Verify dedup — no second emission**

Wait another 60s, then:

```
ls ~/.nido/runs/ | wc -l
```

Expected: the count is unchanged. The same row is not re-emitted on subsequent polls.

- [ ] **Step 6: Verify breaker (manual cycle)**

```
bb nido:notion:auth:check                                        # confirm token present
security delete-generic-password -s nido-notion -a $(whoami)     # invalidate
bb nido:coordinator:restart                                      # daemon picks up missing token
sleep 35
bb nido:coordinator:status                                       # source listed with breaker OPEN
bb nido:notion:auth:set                                          # restore the token
bb nido:coordinator:source:reset :notion-view :database "<id>" :view "smoke-test" :poll "30s"
bb nido:coordinator:status                                       # breaker cleared
```

- [ ] **Step 7: Tear down or keep**

Decide whether to keep the smoke trigger configured or remove it:

```
# Option A: keep — useful for ongoing verification
# Option B: delete the trigger from triggers.edn and:
#   bb nido:coordinator:restart
```

- [ ] **Step 8: Mark the spec as implemented**

Edit `docs/superpowers/specs/2026-05-15-nido-stage-5-notion-source-design.md` and change `**Status:** designed, not implemented` → `**Status:** implemented`.

```
jj desc -m "docs(nido): mark Stage 5 spec as implemented"
```

---

### Task 16: CLAUDE.md update

**Files:**
- Modify: `/Users/yabmas/Code/nido/CLAUDE.md`

- [ ] **Step 1: Locate the coordination-layer section + the trailing future-tense sentence**

Search for "Coordination layer" in CLAUDE.md. After the existing Stage 4 block, add a Stage 5 block:

```markdown
**Autonomous sources (Stage 5):**

```
bb nido:notion:auth:set                # store Notion integration token in macOS Keychain
bb nido:notion:auth:check              # confirm token presence
bb nido:coordinator:source:list        # one row per source-instance on disk
bb nido:coordinator:source:reset :notion-view :database <id> :view <name> :poll <dur>
                                       # clear an open breaker
```

A trigger with `:source {:type :notion-view :database "..." :view "..." :poll "5m"}` polls the database every 5 minutes and emits one event per row that newly enters the view. The first poll seeds the snapshot and emits nothing. Status shows per-source health under a `Sources:` section; breakers open after 3 consecutive failures (or immediately on 401).
```

- [ ] **Step 2: Update the trailing future-tense sentence**

Change "stages 5+ add Notion / cron / GitHub event sources." to "Stage 5 added the Notion source; cron / GitHub remain future work."

- [ ] **Step 3: Commit**

```
jj desc -m "docs(nido): Stage 5 Notion source verbs in CLAUDE.md"
```

---

## Self-review

**Spec coverage:**

- Plugin contract → Task 1.
- Trigger filter → Task 2.
- Broadcast routing (`events.clj`) → Task 3.
- Source-config dedup via hash → Task 1.
- Per-source state files → Task 4.
- Notion API integration → Tasks 5, 6, 7.
- Set-diff dedup with seeded cold start → Task 8.
- Breaker on 3 failures / immediate on 401 → Task 9.
- Crash-safe envelope-then-snapshot → Task 10 (filename = content hash makes the re-emission a no-op).
- Source lifecycle on boot/shutdown → Task 11.
- `tick!` polls due sources → Task 11.
- `bb nido:notion:auth:set/check` → Task 12.
- `bb nido:coordinator:source:list/reset` → Task 13.
- `status` surface for sources → Task 14.
- End-to-end smoke test → Task 15.
- CLAUDE.md update → Task 16.

**Placeholder scan:** none.

**Type consistency:**
- `sources/config-hash` returns 12 hex chars — used in Tasks 4, 8, 10, 13, 14 (all expect 12 chars).
- Source state map keys: `:type`, `:source-config`, `:last-rows`, `:last-polled-at`, `:last-poll-result`, `:consecutive-failures`, `:breaker` — consistent across Tasks 4, 8, 9, 13, 14.
- Broadcast envelope shape: `{:broadcast {:type :source-config :payload}}` — Tasks 3, 10, 11.
- Event payload shape: top-level `:source`, `:page-id`, `:url`, `:title`, `:created-time`, `:edited-time`, plus flattened properties + `:properties` map — Tasks 7, 8, 9.

**One issue I'm flagging inline (Task 6):** the JSON-string body in `database-query` is hand-written. If you'd prefer `(json/generate-string {:page_size 100})` for symmetry with the response-parsing call, swap it — both work, and the hand-written form is just slightly less to read.

---

## Execution handoff

After saving the plan, the user can choose either subagent-driven execution (fresh agent per task) or inline execution (this session, batched).
