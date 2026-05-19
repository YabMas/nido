# Coordinator Preprocessor Dispatch (L4) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **jj commit hygiene:** This is a jujutsu repo. In every commit step, run `jj log -r '@-..@' --no-graph` BEFORE editing files (if `@` has a description, `jj new` first), and AGAIN AFTER editing (confirm parent is the previous task's commit).

**Goal:** Add a `:preprocessing` Run phase between `:queued` and `:running`. When the trigger declares `:preprocess [:notion-ticket]`, the coordinator runs each preprocessor as a shell-out before claude is spawned. Preprocessor failures stop the Run with `:reason :preprocess-failed`; success transitions to `:running` and claude launches as today.

**Architecture:** One new namespace `nido.coordinator.preprocess` holds the dispatch registry (currently a one-entry map: `{:notion-ticket invoke-notion-ticket!}`) and the budget-aware `run!` entry point. The Run state machine in `nido.coordinator.runs` gains the `:preprocessing` state plus three new transitions. The existing `run-blocking!` in `nido.coordinator.core` gains one new step: between `transition! :running` (which itself becomes `transition! :preprocessing` → `transition! :running`) and the session-spawn, call `preprocess/run!`. If it fails, transition to `:failed` and skip the session-spawn entirely.

**Tech Stack:** Babashka (bb), Malli, `clojure.test`, `babashka.process`. No new external dependencies. Depends on Plan A (`bb nido:transcribe-video`) and Plan B (`bb nido:notion:preprocess-ticket`) being shipped.

**Spec reference:** [2026-05-19-notion-ticket-preprocessing-design.md §Layer 4](../specs/2026-05-19-notion-ticket-preprocessing-design.md). This plan delivers **Stage 3** of the four-stage rollout.

---

## File Structure

**New:**
- `src/nido/coordinator/preprocess.clj` — dispatch registry + budget-aware runner.
- `test/nido/coordinator/preprocess_test.clj` — unit tests for the dispatch.

**Modified:**
- `src/nido/coordinator/runs.clj` — add `:preprocessing` to `states` + `allowed-transitions`.
- `src/nido/coordinator/triggers.clj` — `Trigger` schema gains `[:preprocess {:optional true} [:vector keyword?]]`.
- `src/nido/coordinator/core.clj` — `run-blocking!` calls `preprocess/run!` before `spawn-session-for-run!`.
- `test/nido/coordinator/triggers_test.clj` — add a test confirming `:preprocess` is accepted.

**Untouched:** Notion source, executor, TUI (the existing phase column picks up `:preprocessing` automatically), session lifecycle.

---

## Task 1 — `:preprocessing` state + transitions

**Files:**
- Modify: `src/nido/coordinator/runs.clj`
- Modify: `test/nido/coordinator/runs_test.clj` (if it exists; otherwise create)

The state set today:
```clojure
#{:queued :running :awaiting-review :done :failed :halted :dry-run-would-fire}
```

Plus today:
```clojure
{:queued          #{:running :failed :halted :dry-run-would-fire}
 :running         #{:awaiting-review :done :failed :halted}
 :awaiting-review #{:running :done :failed :halted}}
```

We insert `:preprocessing` between `:queued` and `:running`:
```clojure
#{:queued :preprocessing :running :awaiting-review :done :failed :halted :dry-run-would-fire}

{:queued          #{:preprocessing :running :failed :halted :dry-run-would-fire}
 :preprocessing   #{:running :failed :halted}
 :running         #{:awaiting-review :done :failed :halted}
 :awaiting-review #{:running :done :failed :halted}}
```

`:queued → :running` stays valid (for triggers without `:preprocess`) so this is purely additive.

- [ ] **Step 1: jj hygiene check.** `jj log -r '@-..@' --no-graph`; `jj new` if needed.

- [ ] **Step 2: Failing tests.** Check whether `test/nido/coordinator/runs_test.clj` exists. If not, create it with:

```clojure
(ns nido.coordinator.runs-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.runs :as runs]))

(deftest preprocessing-is-a-state
  (is (contains? runs/states :preprocessing)))

(deftest queued-can-transition-to-preprocessing
  (is (runs/valid-transition? :queued :preprocessing)))

(deftest preprocessing-can-transition-to-running
  (is (runs/valid-transition? :preprocessing :running)))

(deftest preprocessing-can-transition-to-failed
  (is (runs/valid-transition? :preprocessing :failed)))

(deftest queued-still-allows-direct-running
  ;; Triggers without :preprocess skip the preprocessing phase entirely.
  (is (runs/valid-transition? :queued :running)))
```

If the file already exists, append the new deftests.

- [ ] **Step 3: Run, verify fail**

```
bb nido:test :only nido.coordinator.runs-test
```

Expected: `preprocessing-is-a-state` and the new transition tests fail.

- [ ] **Step 4: Implement.** In `src/nido/coordinator/runs.clj`, modify `states`:

```clojure
(def states
  "All valid Run states."
  #{:queued :preprocessing :running :awaiting-review :done :failed :halted :dry-run-would-fire})
```

And `allowed-transitions`:

```clojure
(def allowed-transitions
  "Forward edges only; :halted/:failed/:done/:awaiting-review are terminal
   except where noted. :preprocessing is an optional phase between :queued
   and :running, used by triggers with a :preprocess config."
  {:queued          #{:preprocessing :running :failed :halted :dry-run-would-fire}
   :preprocessing   #{:running :failed :halted}
   :running         #{:awaiting-review :done :failed :halted}
   :awaiting-review #{:running :done :failed :halted}})
```

- [ ] **Step 5: Run, verify pass**

```
bb nido:test :only nido.coordinator.runs-test
```

Expected: all tests pass.

- [ ] **Step 6: Commit**

```
jj desc -m "feat(coordinator/runs): add :preprocessing phase between :queued and :running"
jj log -r '@-..@' --no-graph
```

---

## Task 2 — `:preprocess` in Trigger schema

**Files:**
- Modify: `src/nido/coordinator/triggers.clj`
- Modify: `test/nido/coordinator/triggers_test.clj`

Schema today:
```clojure
(def Trigger
  [:map {:closed true}
   [:name           keyword?]
   [:source         [:map [:type keyword?]]]
   [:skill          keyword?]
   [:payload        string?]
   [:filter      {:optional true} [:map-of keyword? any?]]
   [:payload-key {:optional true} keyword?]
   [:agent       {:optional true} keyword?]
   [:limits      {:optional true} [:map-of keyword? any?]]
   [:priority       {:optional true} int?]
   [:priority-from  {:optional true} [:map [:property string?]]]
   [:session-profile {:optional true} keyword?]
   [:dry-run?       {:optional true} boolean?]
   [:enabled?       {:optional true} boolean?]
   [:uncapped?      {:optional true} boolean?]])
```

Add `[:preprocess {:optional true} [:vector keyword?]]`. `:limits` is already `[:map-of keyword? any?]` so `:preprocess-budget` flows through without a schema change.

- [ ] **Step 1: jj hygiene check.** `jj log -r '@-..@' --no-graph`; `jj new` if needed.

- [ ] **Step 2: Failing tests.** Append to `test/nido/coordinator/triggers_test.clj`:

```clojure
(deftest trigger-schema-accepts-preprocess-vector
  (let [t {:name :triage-new
           :source {:type :notion-view}
           :skill :triage-bug
           :payload ""
           :preprocess [:notion-ticket]}]
    (is (malli.core/validate triggers/Trigger t))))

(deftest trigger-schema-rejects-non-keyword-preprocess
  (let [t {:name :triage-new
           :source {:type :notion-view}
           :skill :triage-bug
           :payload ""
           :preprocess ["notion-ticket"]}]
    (is (not (malli.core/validate triggers/Trigger t)))))

(deftest trigger-schema-allows-no-preprocess
  ;; Existing triggers don't break.
  (let [t {:name :smoke
           :source {:type :smoke}
           :skill :smoke
           :payload ""}]
    (is (malli.core/validate triggers/Trigger t))))
```

If `malli.core` and `triggers` aren't already required, add to the ns form.

- [ ] **Step 3: Run, verify fail**

```
bb nido:test :only nido.coordinator.triggers-test
```

Expected: `trigger-schema-accepts-preprocess-vector` fails (key not in closed schema).

- [ ] **Step 4: Implement.** In `src/nido/coordinator/triggers.clj`, add to `Trigger`:

```clojure
   [:preprocess     {:optional true} [:vector keyword?]]
```

(Place it near `:session-profile` for grouping.)

- [ ] **Step 5: Run, verify pass**

```
bb nido:test :only nido.coordinator.triggers-test
```

Expected: all tests pass (including pre-existing).

- [ ] **Step 6: Commit**

```
jj desc -m "feat(coordinator/triggers): accept :preprocess [:notion-ticket] config"
jj log -r '@-..@' --no-graph
```

---

## Task 3 — `nido.coordinator.preprocess` dispatch

**Files:**
- Create: `src/nido/coordinator/preprocess.clj`
- Create: `test/nido/coordinator/preprocess_test.clj`

The dispatch reads `:preprocess` from a Run record, looks up each name in the registry, and shells out. Budget comes from `:limits.preprocess-budget` (default `"10m"`).

```clojure
(run! {:run-id :run :registry registry-or-default})
;; → {:ok? true}
;;   {:ok? false :error {:reason :preprocess-failed :preprocessor :kw :detail {...}}}
;;   {:ok? false :error {:reason :missing-page-id :detail {...}}}
;;   {:ok? false :error {:reason :preprocess-timeout :detail {...}}}
```

`shell-bb-task` is the redef seam.

The notion-ticket preprocessor reads `:page-id` from the envelope payload (which the Notion source sets) and shells out:

```
bb nido:notion:preprocess-ticket :page <id> :out <run-dir>/preprocess :budget <budget-s>s
```

with stdout/stderr captured into `<run-dir>/preprocess/notion-ticket.log`.

- [ ] **Step 1: jj hygiene check.** `jj log -r '@-..@' --no-graph`; `jj new` if needed.

- [ ] **Step 2: Failing tests.** Create `test/nido/coordinator/preprocess_test.clj`:

```clojure
(ns nido.coordinator.preprocess-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.preprocess :as pp]
   [nido.coordinator.state :as cstate]))

(defn- with-tmp-runs [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (fs/create-dirs (str (fs/path tmp "runs")))
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(deftest run-noop-when-no-preprocess-config
  (with-tmp-runs
    (fn [_]
      (let [run {:id "r1" :preprocess [] :payload "" :event {}}]
        (is (= {:ok? true} (pp/run! {:run run})))))))

(deftest run-noop-when-preprocess-key-missing
  (with-tmp-runs
    (fn [_]
      (let [run {:id "r1" :payload "" :event {}}]
        (is (= {:ok? true} (pp/run! {:run run})))))))

(deftest run-shells-out-to-notion-preprocessor
  (with-tmp-runs
    (fn [_]
      (let [calls (atom [])
            run   {:id "r1"
                   :preprocess [:notion-ticket]
                   :limits {:preprocess-budget "10m"}
                   :event {:page-id "page-1"}}]
        (with-redefs [pp/shell-bb-task (fn [args]
                                         (swap! calls conj args)
                                         {:exit 0 :out "" :err ""})]
          (is (:ok? (pp/run! {:run run}))))
        (let [[args] @calls]
          (is (= "bb" (first args)))
          (is (= "nido:notion:preprocess-ticket" (second args)))
          (is (some #{"page-1"} args))
          (is (some #(re-find #":budget" %) (map str args)))
          (is (some #(re-find #"600s" %) (map str args))))))))

(deftest run-returns-structured-error-on-preprocessor-failure
  (with-tmp-runs
    (fn [_]
      (let [run {:id "r1"
                 :preprocess [:notion-ticket]
                 :limits {:preprocess-budget "10m"}
                 :event {:page-id "page-1"}}]
        (with-redefs [pp/shell-bb-task (fn [_args]
                                         {:exit 1
                                          :err "{:reason :notion-auth}\n"})]
          (let [r (pp/run! {:run run})]
            (is (not (:ok? r)))
            (is (= :preprocess-failed (-> r :error :reason)))
            (is (= :notion-ticket (-> r :error :preprocessor)))))))))

(deftest run-fails-when-page-id-missing
  (with-tmp-runs
    (fn [_]
      (let [run {:id "r1"
                 :preprocess [:notion-ticket]
                 :event {}}]
        (let [r (pp/run! {:run run})]
          (is (not (:ok? r)))
          (is (= :missing-page-id (-> r :error :reason))))))))

(deftest run-stops-at-first-failure
  (with-tmp-runs
    (fn [_]
      (let [calls (atom [])
            run   {:id "r1"
                   :preprocess [:notion-ticket :nonexistent]
                   :event {:page-id "page-1"}}]
        (with-redefs [pp/shell-bb-task (fn [args]
                                         (swap! calls conj args)
                                         {:exit 1 :err "{:reason :x}\n"})]
          (pp/run! {:run run}))
        (is (= 1 (count @calls))
            "second preprocessor not invoked after first fails")))))

(deftest run-parses-budget-duration
  (with-tmp-runs
    (fn [_]
      (let [calls (atom [])
            run   {:id "r1"
                   :preprocess [:notion-ticket]
                   :limits {:preprocess-budget "15m"}
                   :event {:page-id "page-1"}}]
        (with-redefs [pp/shell-bb-task (fn [args]
                                         (swap! calls conj args)
                                         {:exit 0 :out "" :err ""})]
          (pp/run! {:run run}))
        (is (some #(re-find #"900s" %) (map str (first @calls))))))))
```

- [ ] **Step 3: Run, verify fail**

```
bb nido:test :only nido.coordinator.preprocess-test
```

Expected: namespace not found.

- [ ] **Step 4: Implement** `src/nido/coordinator/preprocess.clj`:

```clojure
(ns nido.coordinator.preprocess
  "Pre-Run preprocessor dispatch. Runs configured preprocessors between
   envelope dequeue and session-spawn. Currently one entry:
   `:notion-ticket` → shells out to `bb nido:notion:preprocess-ticket`.

   Failures here abort the Run before claude launches."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [nido.coordinator.state :as cstate]))

(defn shell-bb-task
  "Shell-out to a bb task. Returns {:exit :out :err}. Redef seam."
  [args]
  (let [proc @(p/process args {:out :string :err :string})]
    {:exit (:exit proc)
     :out  (str (:out proc))
     :err  (str (:err proc))}))

(defn- parse-budget-s
  "Parse a duration string. Same accepted forms as :limits.budget."
  [s]
  (cond
    (nil? s)                 600  ;; default 10m
    (re-matches #"\d+s?" s)  (Integer/parseInt (str/replace s #"s$" ""))
    (re-matches #"\d+m" s)   (* 60 (Integer/parseInt (str/replace s #"m$" "")))
    (re-matches #"\d+h" s)   (* 3600 (Integer/parseInt (str/replace s #"h$" "")))
    :else                    600))

(defn invoke-notion-ticket!
  "Shell out to `bb nido:notion:preprocess-ticket`. Returns the same
   shape as the registry entry contract: {:ok? :error?}."
  [{:keys [run budget-s out-dir]}]
  (let [page-id (some-> run :event :page-id)]
    (cond
      (str/blank? page-id)
      {:ok? false :error {:reason :missing-page-id
                          :detail {:event-keys (vec (keys (:event run)))}}}

      :else
      (let [args ["bb" "nido:notion:preprocess-ticket"
                  ":page" page-id
                  ":out"  out-dir
                  ":budget" (str budget-s "s")]
            log  (str (fs/path out-dir "notion-ticket.log"))
            _    (fs/create-dirs out-dir)
            {:keys [exit out err]} (shell-bb-task args)]
        (spit log (str "STDOUT:\n" out "\nSTDERR:\n" err))
        (if (zero? exit)
          {:ok? true}
          (let [parsed (try (edn/read-string (str/trim err))
                            (catch Exception _
                              {:reason :unknown :detail {:stderr err}}))]
            {:ok? false :error parsed}))))))

(def default-registry
  "Single-entry registry for v1."
  {:notion-ticket invoke-notion-ticket!})

(defn run!
  "Run configured preprocessors for a Run before claude is spawned.
   Returns {:ok? true} or {:ok? false :error {...}}. Stops at the first
   failing preprocessor."
  [{:keys [run registry]
    :or   {registry default-registry}}]
  (let [names (or (:preprocess run) [])]
    (if (empty? names)
      {:ok? true}
      (let [out-dir  (str (fs/path (cstate/run-dir (:id run)) "preprocess"))
            budget-s (parse-budget-s (some-> run :limits :preprocess-budget))]
        (loop [[n & more] names]
          (cond
            (nil? n) {:ok? true}

            (not (contains? registry n))
            {:ok? false :error {:reason :preprocess-unknown
                                :preprocessor n}}

            :else
            (let [impl (get registry n)
                  r    (impl {:run run :budget-s budget-s :out-dir out-dir})]
              (if (:ok? r)
                (recur more)
                {:ok? false
                 :error (assoc {:reason :preprocess-failed
                                :preprocessor n}
                          :detail (:error r))}))))))))
```

- [ ] **Step 5: Run, verify pass**

```
bb nido:test :only nido.coordinator.preprocess-test
```

Expected: all 7 tests pass.

- [ ] **Step 6: Commit**

```
jj desc -m "feat(coordinator/preprocess): dispatch + notion-ticket shell-out"
jj log -r '@-..@' --no-graph
```

---

## Task 4 — Hook preprocess into `run-blocking!`

**Files:**
- Modify: `src/nido/coordinator/core.clj`
- Modify: `src/nido/coordinator/runs.clj` (snapshot `:preprocess` onto Run at create)

The Run record needs to carry `:preprocess` (snapshotted from the trigger at create time, same as `:priority` already is). Then `run-blocking!` reads it.

First, snapshot `:preprocess` and `:limits` onto the Run in `runs/create-run!`. Look for where `:priority` is snapshotted — it's around `runs/create-run!`. Add `:preprocess (:preprocess trigger)` alongside it. The Run schema is open enough (it's a map; check the Malli `Run` def) that this should slot in. If `Run` is `{:closed true}`, add `[:preprocess {:optional true} [:vector keyword?]]`.

Second, modify `run-blocking!` to call `preprocess/run!` before `spawn-session-for-run!`:

```clojure
(defn- run-blocking! [run-id]
  (runs/transition! run-id :preprocessing)
  (let [run    (runs/read-run run-id)
        pp-r   (preprocess/run! {:run run})]
    (if-not (:ok? pp-r)
      (do (runs/transition! run-id :failed)
          (let [r (runs/read-run run-id)]
            (runs/write-run! (assoc r :error (:error pp-r))))
          (swap! !detector anomaly/record-failure (clock/now-iso))
          (breakers/record-failure! (:project run) (:trigger run)
                                    (or (-> run :limits :max-failures) 3)))
      (do
        (runs/transition! run-id :running)
        ;; (rest of existing run-blocking! body unchanged)
        ...))))
```

For triggers without `:preprocess`, `preprocess/run!` returns `{:ok? true}` immediately — the new `:preprocessing` transition still happens, but it's instantaneous and harmless. (Behaviorally identical to going straight from `:queued` to `:running`.)

- [ ] **Step 1: jj hygiene check.** `jj log -r '@-..@' --no-graph`; `jj new` if needed.

- [ ] **Step 2: Failing test.** Add to `test/nido/coordinator/core_test.clj` (create if missing):

```clojure
(ns nido.coordinator.core-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.agent :as agent]
   [nido.coordinator.core :as core]
   [nido.coordinator.preprocess :as preprocess]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.state :as cstate]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (fs/create-dirs (str (fs/path tmp "runs")))
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(deftest run-blocking-runs-preprocess-then-launches
  (with-tmp
    (fn [_]
      (let [run-id "r-1"
            calls  (atom [])]
        ;; Manually create a :queued Run on disk with :preprocess
        (runs/write-run! {:id run-id :state :queued
                          :preprocess [:notion-ticket]
                          :event {:page-id "p1"}
                          :first-message "go"
                          :state-history [{:at "x" :state :queued}]})
        (with-redefs [preprocess/run! (fn [_]
                                        (swap! calls conj :preprocess)
                                        {:ok? true})
                      runs/spawn-session-for-run! (fn [_]
                                                    (swap! calls conj :spawn))
                      agent/launch! (fn [_]
                                      (swap! calls conj :launch)
                                      {:exit-code 0 :claude-session-id nil})]
          (#'core/run-blocking! run-id))
        (is (= [:preprocess :spawn :launch] @calls))
        (let [r (runs/read-run run-id)]
          (is (some #(= :preprocessing (:state %)) (:state-history r)))
          (is (some #(= :running       (:state %)) (:state-history r))))))))

(deftest run-blocking-on-preprocess-failure-skips-launch
  (with-tmp
    (fn [_]
      (let [run-id "r-2"
            calls  (atom [])]
        (runs/write-run! {:id run-id :state :queued
                          :preprocess [:notion-ticket]
                          :event {:page-id "p1"}
                          :limits {:max-failures 3}
                          :project :brian
                          :trigger :triage-new
                          :first-message "go"
                          :state-history [{:at "x" :state :queued}]})
        (with-redefs [preprocess/run! (fn [_]
                                        (swap! calls conj :preprocess)
                                        {:ok? false
                                         :error {:reason :preprocess-failed
                                                 :preprocessor :notion-ticket
                                                 :detail {:reason :notion-auth}}})
                      runs/spawn-session-for-run! (fn [_]
                                                    (swap! calls conj :spawn))
                      agent/launch! (fn [_]
                                      (swap! calls conj :launch)
                                      {:exit-code 0 :claude-session-id nil})]
          (#'core/run-blocking! run-id))
        (is (= [:preprocess] @calls)
            "neither spawn nor launch ran after preprocess failure")
        (let [r (runs/read-run run-id)]
          (is (= :failed (:state r)))
          (is (= :preprocess-failed (-> r :error :reason))))))))

(deftest run-blocking-no-preprocess-still-launches
  ;; Triggers without :preprocess transition :queued → :preprocessing → :running.
  (with-tmp
    (fn [_]
      (let [run-id "r-3"
            calls  (atom [])]
        (runs/write-run! {:id run-id :state :queued
                          :event {}
                          :first-message "go"
                          :state-history [{:at "x" :state :queued}]})
        (with-redefs [runs/spawn-session-for-run! (fn [_]
                                                    (swap! calls conj :spawn))
                      agent/launch! (fn [_]
                                      (swap! calls conj :launch)
                                      {:exit-code 0 :claude-session-id nil})]
          (#'core/run-blocking! run-id))
        (is (= [:spawn :launch] @calls))))))
```

- [ ] **Step 3: Run, verify fail**

```
bb nido:test :only nido.coordinator.core-test
```

Expected: tests fail (run-blocking! doesn't yet call preprocess).

- [ ] **Step 4: Implement.**

First, in `src/nido/coordinator/runs.clj` `create-run!`, add `:preprocess` to the persisted Run map (around the same place `:priority` is set):

```clojure
   :preprocess (:preprocess trigger)
   :limits     (merge {:max-failures 3} (:limits trigger))
```

(Confirm by inspection that `:limits` is already snapshotted; if so, only `:preprocess` is new.)

If `Run` schema is closed, add `[:preprocess {:optional true} [:vector keyword?]]`.

Second, in `src/nido/coordinator/core.clj`, modify the `run-blocking!` function:

```clojure
(defn- run-blocking!
  "Drive a single :queued Run to terminal/awaiting-review state.
   Phase :preprocessing runs configured preprocessors before claude launches;
   failures here skip the session spawn entirely."
  [run-id]
  (runs/transition! run-id :preprocessing)
  (let [run0     (runs/read-run run-id)
        pp-r     (preprocess/run! {:run run0})]
    (if-not (:ok? pp-r)
      (do
        (runs/transition! run-id :failed)
        (let [r (runs/read-run run-id)]
          (runs/write-run! (assoc r :error (:error pp-r))))
        (swap! !detector anomaly/record-failure (clock/now-iso))
        (breakers/record-failure! (:project run0) (:trigger run0)
                                  (or (-> run0 :limits :max-failures) 3)))
      (do
        (runs/transition! run-id :running)
        (let [run          (runs/read-run run-id)
              _            (runs/spawn-session-for-run! run)
              session-home (cstate/run-session-home-link run-id)
              result       (agent/launch! {:run-id        run-id
                                           :cwd           session-home
                                           :first-message (:first-message run)
                                           :system-prompt (:system-prompt defaults)
                                           :budget        (-> run :limits :budget)})
              next-state (cond
                           (:timed-out? result) :failed
                           (zero? (:exit-code result))
                           (status-file/derive-state-after-exit
                             (status-file/read-status run-id))
                           :else :failed)]
          (let [r (runs/read-run run-id)]
            (runs/write-run! (assoc r :claude-session-id (:claude-session-id result))))
          (runs/transition! run-id next-state)
          (when (= :failed next-state)
            (let [r (runs/read-run run-id)]
              (runs/write-run! (assoc r :error (cond-> {:exit-code (:exit-code result)}
                                                 (:timed-out? result)
                                                 (assoc :reason :timeout
                                                        :budget (-> r :limits :budget)))))))
          (let [project      (:project run)
                trigger-name (:trigger run)
                max-failures (or (-> run :limits :max-failures) 3)]
            (case next-state
              :failed          (do (swap! !detector anomaly/record-failure (clock/now-iso))
                                   (breakers/record-failure! project trigger-name max-failures))
              :done            (breakers/record-success! project trigger-name)
              :awaiting-review (breakers/record-success! project trigger-name)
              nil)))))))
```

Add `[nido.coordinator.preprocess :as preprocess]` to the ns requires.

- [ ] **Step 5: Run, verify pass**

```
bb nido:test :only nido.coordinator.core-test
bb nido:test :only nido.coordinator
```

Expected: all coordinator tests pass.

- [ ] **Step 6: Commit**

```
jj desc -m "feat(coordinator/core): run preprocess phase before claude launch"
jj log -r '@-..@' --no-graph
```

---

## Task 5 — Smoke trigger end-to-end

**Files:** none (manual sanity check; no commit).

Exercise the full L4 flow without the triage skill (Stage 4) so this stage is independently verifiable.

- [ ] **Step 1: Add a temporary preprocess to a smoke trigger.** Edit `~/.nido/projects/brian/triggers.edn` and add `:preprocess [:notion-ticket]` to an existing smoke trigger (or a fresh one configured to fire on a single test Notion page id). Make sure the trigger's source emits a payload containing `:page-id`.

- [ ] **Step 2: Bring the coordinator up** (or restart it):

```
bb nido:coordinator:restart   # if launchd-installed
# OR
bb nido:coordinator:down ; bb nido:coordinator:up
```

- [ ] **Step 3: Fire the smoke trigger manually:**

```
bb nido:trigger:fire :project brian smoke :page-id "<real-page-id>"
```

- [ ] **Step 4: Watch the Run.** In a second terminal:

```
bb nido:runs:list
bb nido:runs:show <run-id>
```

Expected: state-history includes `:queued → :preprocessing → :running → …`. The Run's `<run-dir>/preprocess/` contains `manifest.edn`, `transcripts.md`, per-video `.vtt`s, and `notion-ticket.log`.

- [ ] **Step 5: Verify failure path.** Temporarily un-set the keychain token, fire the trigger again. The Run should land `:failed` with `:error {:reason :preprocess-failed :preprocessor :notion-ticket :detail {:reason :missing-token? | ...}}`. Restore the token afterwards.

- [ ] **Step 6: Remove the temporary trigger config** before moving on.

No commit — manual verification only.

---

## Self-Review

Before declaring Stage 3 done, check:

- **`bb test`** across `nido.coordinator.{runs,triggers,preprocess,core}-test` all green.
- **A smoke trigger with `:preprocess [:notion-ticket]`** completes preprocessing before claude launches, and `<run-dir>/preprocess/manifest.edn` is present at spawn time.
- **A smoke trigger WITHOUT `:preprocess`** still works (no behavior change).
- **A preprocess failure** lands the Run in `:failed` with structured error and does **not** call `spawn-session-for-run!` or `agent/launch!`.
- **The Run's `state-history`** records the `:preprocessing` phase even when it's instantaneous (no-op case).

Once green, Stage 4 (`2026-05-19-preprocessing-triage-adoption.md`) can begin — it adds `:preprocess [:notion-ticket]` to the triage triggers and updates the triage-bug skill briefing.
