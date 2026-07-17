(ns nido.coordinator.core-test
  "Integration smoke test: envelope → executor → run-blocking! → terminal state.
   Also covers the triage pre-spawn gate (Task 4)."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is use-fixtures]]
   [nido.coordinator.agent :as agent]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.core :as core]
   [nido.coordinator.executor :as executor]
   [nido.coordinator.github-merge :as github-merge]
   [nido.github.config :as gh-config]
   [nido.coordinator.anomaly :as anomaly]
   [nido.coordinator.breakers :as breakers]
   [nido.coordinator.intake :as intake]
   [nido.coordinator.notify :as notify]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.session :as session]
   [nido.coordinator.ship :as ship]
   [nido.coordinator.spawn :as spawn]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.tickets :as tickets]
   [nido.coordinator.status-file :as status-file]
   [nido.coordinator.workstream :as ws]
   [nido.project :as project]
   [nido.session.profiles :as profiles]
   [nido.work :as work]))

(defn- reset-executor! [f]
  (executor/configure! {:global-cap 1})
  (executor/clear!)
  (f))

(use-fixtures :each reset-executor!)

;; ---------------------------------------------------------------------------
;; Shared test helpers for Task 3.2
;; ---------------------------------------------------------------------------

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f))
      (finally (fs/delete-tree tmp)))))

(def ^:private autonomy-parked
  {:skill :triage-bug :first-message "x" :agent :claude :claude-session-id nil
   :trigger :triage-bug :limits {:budget "30m"} :priority 4 :uncapped? false
   :on-promote nil :phase :parked
   :phase-history [{:at "2026-06-18T00:00:00Z" :phase :parked}] :error nil})

(defn- run-fixture [id ws-id sname sid]
  {:id id :project :brian :trigger :triage-bug
   :source {:type :manual} :event-payload {} :skill :triage-bug
   :first-message "/triage-bug" :agent :claude :session-name sname
   :workstream-id ws-id :claude-session-id sid
   :limits {:budget "30m"} :priority 0 :session-profile :full
   :uncapped? false :state :awaiting-review
   :state-history [{:at "2026-06-18T00:00:00Z" :state :queued}]
   :artifacts [] :error nil})

(deftest envelope-drives-run-to-terminal-via-executor
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    agent/launch!    (fn [_]
                                       {:exit-code         0
                                        :claude-session-id "sess-x"
                                        :timed-out?        false})
                    runs/spawn-session-for-run! (fn [_] nil)
                    ;; stub launch-context so run-blocking! doesn't call real I/O
                    ;; (jj/git, config reads) — cwd unused since agent/launch! is stubbed
                    runs/launch-context (fn [_] {:cwd (str tmp) :briefing ""
                                                 :mcp-config nil :add-dirs []
                                                 :run-paths ""})
                    runs/teardown-session-for-run! (fn [_] nil)]
        (cstate/ensure-dirs!)
        (let [trigger  {:name    :t
                        :source  {:type :test}
                        :skill   :noop
                        :payload "x"}
              run      (runs/create-run!
                         {:project :p :trigger trigger :payload {} :priority 0}
                         {})]
          ;; submit directly to the executor (bypassing process-envelope!)
          (executor/submit! (:id run) 0)
          ;; first tick: promotes the Run into a future that calls run-blocking!
          (executor/tick! #'nido.coordinator.core/run-blocking! {})
          ;; wait for the future to finish (agent stub is instant)
          (Thread/sleep 200)
          ;; second tick: reaps the finished future
          (executor/tick! #'nido.coordinator.core/run-blocking! {})
          (is (contains? #{:done :failed :awaiting-review}
                         (:state (runs/read-run (:id run)))))))
      (finally (fs/delete-tree tmp)))))

(deftest defaults-include-executor-shutdown-grace
  (is (= 5000 (core/shutdown-grace-ms))))

(deftest maybe-poll-github-merges-throttles-and-calls
  (let [calls (atom [])]
    ;; !last-github-poll-ms is a defonce atom that persists across test runs in
    ;; the same JVM — reset it so the "first call at t=0 polls" assumption holds.
    (reset! @#'core/!last-github-poll-ms {})
    (with-redefs [gh-config/load-config
                  (fn [p] (when (= :brian p) {:repo "brian-study/brian" :poll "5m"}))
                  core/registered-projects (constantly [:brian])
                  github-merge/poll-and-react!
                  (fn [p _] (swap! calls conj p))]
      ;; first call at t=0 polls (last-poll seeded to 0, interval elapsed)
      (#'core/maybe-poll-github-merges! 0)
      ;; immediate re-call is throttled (interval not elapsed)
      (#'core/maybe-poll-github-merges! 1000)
      (is (= [:brian] @calls) "polled once, second call throttled")
      ;; after the interval, polls again
      (#'core/maybe-poll-github-merges! (* 5 60 1000))
      (is (= [:brian :brian] @calls)))))

;; ---------------------------------------------------------------------------
;; Triage pre-spawn gate tests (Task 4)
;; ---------------------------------------------------------------------------
;;
;; The broadcast envelope shape (from events_test.clj and sources/notion.clj):
;;   {:broadcast {:type :notion-view
;;                :source-config {:database "x"}   ; must match trigger :source
;;                :payload {:page-id "pg" :id "BR-5236" :title "T"}}}
;;
;; The :project comes from the triggers-by-project map (NOT from the broadcast
;; itself). The task-description's test literal had :project in the broadcast,
;; which was incorrect — corrected here.
;;
;; The triage-trigger's :source must match the broadcast's :source-config via
;; events/route-broadcast's source-config-match? (compares sans :type).

(defn- gate-with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      ;; Stub session teardown by default: run-blocking! reclaims the session on
      ;; resolved-terminal, but session lifecycle uses real ~/.nido paths (it
      ;; doesn't honor the coordinator-root redef). Tests that assert teardown
      ;; behavior override this with their own spy.
      ;; Default the profile to a non-symlink (fail-open) shape so the pre-spawn
      ;; skill-resolution gate doesn't probe the real ~/Code/<project>/.claude.
      ;; The gate tests override resolve-profile with their own symlink target.
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    runs/teardown-session-for-run! (fn [_] nil)
                    profiles/resolve-profile (fn [_ _] {:worktree {:strategy :git-worktree}})]
        (cstate/ensure-dirs!)
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(def ^:private triage-trigger
  {:name    :triage-new
   :skill   :triage-bug
   :payload "Triage {{event/title}}"
   :source  {:type :notion-view :database "triage-db"}})

(def ^:private triage-envelope
  {:broadcast {:type          :notion-view
               :source-config {:database "triage-db"}
               :payload       {:source   :notion-view
                               :page-id  "pg"
                               :id       "BR-5236"
                               :title    "T"}}})

(deftest gate-skips-completed-triage-ticket
  (gate-with-tmp
    (fn [_]
      (tickets/open! :brian "BR-5236"
                     {:notion-page-id "pg" :url "u" :title "T"
                      :opened-by :triage-new :notion-last-edited-at "t0"})
      (tickets/complete! :brian "BR-5236" :triaged :applied)
      (let [created (atom 0)]
        (with-redefs [runs/create-run! (fn [& _] (swap! created inc) {:id "x"})]
          (#'core/process-envelope!
            triage-envelope
            {:brian [triage-trigger]})
          (is (zero? @created) "completed ticket must not create a Run"))))))

(deftest gate-skips-active-triage-ticket
  (gate-with-tmp
    (fn [_]
      (tickets/open! :brian "BR-5236"
                     {:notion-page-id "pg" :url "u" :title "T"
                      :opened-by :triage-new :notion-last-edited-at "t0"})
      ;; status is :investigating (the default after open!)
      (let [created (atom 0)]
        (with-redefs [runs/create-run! (fn [& _] (swap! created inc) {:id "x"})]
          (#'core/process-envelope!
            triage-envelope
            {:brian [triage-trigger]})
          (is (zero? @created) ":investigating ticket must not spawn a duplicate"))))))

(deftest gate-allows-untriaged-ticket
  (gate-with-tmp
    (fn [_]
      ;; No ticket record exists — gate-decision returns :spawn.
      ;; anomaly/record-spawn is no-op'd so the shared !detector atom is not
      ;; polluted across test namespaces (which would cause the e2e breaker
      ;; test to trip the spawn-burst anomaly threshold early).
      (let [created (atom 0)]
        (with-redefs [runs/create-run!        (fn [& _]
                                                (swap! created inc)
                                                {:id "run-x" :priority 0 :uncapped? false})
                      ;; Live arm now routes through spawn/spawn-records!; this
                      ;; gate test only asserts a Run was created, so no-op the
                      ;; session write (the stub run lacks Session fields).
                      spawn/create-session-for-run! (fn [& _] nil)
                      executor/submit!        (fn [& _] nil)
                      anomaly/record-spawn    (fn [det _] det)]
          (#'core/process-envelope!
            triage-envelope
            {:brian [triage-trigger]})
          (is (= 1 @created) "untriaged ticket creates a Run"))))))

(deftest gate-dedups-untriaged-ticket-with-inflight-session
  ;; The reconcile-dedup gate: even with NO ticket record (status gate returns
  ;; :spawn — cf. gate-allows-untriaged-ticket, which creates 1 Run), a re-emit
  ;; whose ref already has an in-flight :queued session for this trigger must NOT
  ;; mint a duplicate. This is the fix for the thousands-of-:queued-runs pileup.
  (gate-with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :triaging
                                  :external-refs [{:adapter :notion :id "BR-5236"}]})]
        (session/create! :brian (:id w)
                         {:name "run-triage-new-x" :weight :light
                          :autonomy {:skill :triage-bug :first-message "m" :agent :claude
                                     :claude-session-id nil :trigger :triage-new :limits {}
                                     :priority 0 :uncapped? false :on-promote nil
                                     :phase :queued
                                     :phase-history [{:at "t0" :phase :queued}] :error nil}})
        (let [created (atom 0)]
          (with-redefs [runs/create-run!        (fn [& _] (swap! created inc) {:id "run-x"})
                        spawn/create-session-for-run! (fn [& _] nil)
                        executor/submit!        (fn [& _] nil)
                        anomaly/record-spawn    (fn [det _] det)]
            (#'core/process-envelope!
              triage-envelope
              {:brian [triage-trigger]})
            (is (zero? @created)
                "a ref with an in-flight session must not spawn a duplicate run")))))))

(deftest gate-does-not-affect-non-triage-triggers
  (gate-with-tmp
    (fn [_]
      ;; A non-triage trigger (different :skill) fires unconditionally.
      ;; anomaly/record-spawn is no-op'd for the same reason as above.
      (let [non-triage-trigger {:name    :investigate-new
                                :skill   :investigate-bug
                                :payload "Investigate {{event/title}}"
                                :source  {:type :notion-view :database "triage-db"}}
            created            (atom 0)]
        (with-redefs [runs/create-run!      (fn [& _]
                                              (swap! created inc)
                                              {:id "run-y" :priority 0 :uncapped? false})
                      ;; Live arm now routes through spawn/spawn-records!; this
                      ;; gate test only asserts a Run was created, so no-op the
                      ;; session write (the stub run lacks Session fields).
                      spawn/create-session-for-run! (fn [& _] nil)
                      executor/submit!      (fn [& _] nil)
                      anomaly/record-spawn  (fn [det _] det)]
          (#'core/process-envelope!
            triage-envelope
            {:brian [non-triage-trigger]})
          (is (= 1 @created) "non-triage trigger must not be blocked by the gate"))))))

(deftest live-arm-spawns-workstream-session-and-linked-run
  ;; The live (:else) arm routes a fired, non-dry-run, non-gated trigger through
  ;; spawn/spawn-records!, which creates a workstream + authoritative session and
  ;; links the run back via :workstream-id. A non-triage trigger avoids the
  ;; triage pre-spawn gate entirely. executor/submit! is no-op'd (the test only
  ;; needs the records written, not an agent spawn); anomaly/record-spawn is
  ;; no-op'd to keep the shared !detector atom clean across namespaces.
  (gate-with-tmp
    (fn [_]
      (let [non-triage-trigger {:name    :investigate-new
                                :skill   :investigate-bug
                                :agent   :claude
                                :payload "Investigate {{event/title}}"
                                :source  {:type :notion-view :database "triage-db"}}
            envelope           {:broadcast {:type          :notion-view
                                            :source-config {:database "triage-db"}
                                            :payload       {:source  :notion-view
                                                            :page-id "pg"
                                                            :id      "BR-5"
                                                            :title   "Five"}}}]
        (with-redefs [executor/submit!     (fn [& _] nil)
                      anomaly/record-spawn (fn [det _] det)]
          (#'core/process-envelope! envelope {:brian [non-triage-trigger]})
          (let [run-id (first (runs/list-run-ids))
                run    (runs/read-run run-id)]
            (is (some? (:workstream-id run)) "run is linked to a workstream")
            (is (some? (session/read-session (:project run) (:workstream-id run) (:session-name run)))
                "an authoritative session was created for the run")
            (is (= "BR-5" (-> (ws/find-by-ref (:project run) :notion "BR-5") :external-refs first :id))
                "workstream is deduped on the BR external ref")))))))

;; ---------------------------------------------------------------------------
;; Run-termination hook tests (Task 5)
;; ---------------------------------------------------------------------------

(deftest run-blocking-clears-ticket-on-abnormal-exit
  (gate-with-tmp
    (fn [_]
      (tickets/open! :brian "BR-7" {:notion-page-id "pg" :url "u" :title "T"
                                    :opened-by :triage-new :notion-last-edited-at "t0"})
      ;; Stand up a minimal :queued triage Run on disk.
      ;; run-blocking! first transitions :queued → :running, so the run
      ;; must start in :queued state (not :running).
      (let [run {:id "r7" :project :brian :trigger :triage-new
                 :source {:type :notion-view} :event-payload {:id "BR-7"}
                 :skill :triage-bug :first-message "/triage-bug x" :agent :claude
                 :session-name "run-x" :claude-session-id nil
                 :limits {:budget "15m" :max-failures 3} :priority 10
                 :session-profile :lite :uncapped? true :state :queued
                 :state-history [{:at "t" :state :queued}] :artifacts [] :error nil}]
        (runs/write-run! run)
        (with-redefs [;; force an abnormal (:failed) outcome without launching claude:
                      ;; exit-code 1 (non-zero) → :else :failed branch in run-blocking!
                      ;; status-file/read-status is NOT called on the :else path
                      runs/spawn-session-for-run! (fn [_] nil)
                      agent/launch!               (fn [_] {:exit-code 1 :timed-out? false
                                                           :claude-session-id nil})
                      cstate/run-session-home-link (constantly "/tmp/nonexistent")
                      ;; no-op anomaly/breaker side-effects: the !detector atom and
                      ;; breakers file are global across the test suite; recording a
                      ;; failure here would inflate counts and cause the e2e breaker
                      ;; test to trip the anomaly auto-halt during tick! — same
                      ;; isolation pattern used in gate-allows-untriaged-ticket.
                      anomaly/record-failure      (fn [det _] det)
                      breakers/record-failure!    (fn [& _] nil)]
          (#'core/run-blocking! "r7")
          (is (nil? (tickets/status :brian "BR-7"))
              "abnormal triage exit clears the stale :investigating status"))))))

(deftest run-blocking-generates-and-persists-session-id-before-launch
  ;; The session-id is generated up front and written to run.edn BEFORE claude
  ;; launches, so it survives interruption (a daemon restart mid-session) — the
  ;; resume shim can always find it. launch! is handed that id.
  (gate-with-tmp
    (fn [_]
      (tickets/open! :brian "BR-S" {:notion-page-id "p" :url "u" :title "T"
                                    :opened-by :triage-teacher-bugs :notion-last-edited-at "t"})
      (tickets/set-status! :brian "BR-S" :awaiting-input)
      (runs/write-run! {:id "rs" :project :brian :trigger :triage-teacher-bugs
                        :source {:type :notion-view} :event-payload {:id "BR-S"}
                        :skill :triage-bug :first-message "x" :agent :claude
                        :session-name "run-rs" :claude-session-id nil :limits {}
                        :priority 0 :session-profile :lite :uncapped? false
                        :state :queued :state-history [{:at "t" :state :queued}]
                        :artifacts [] :error nil})
      (let [sid-passed    (atom :unset)
            sid-at-launch (atom :unset)]
        (with-redefs [runs/spawn-session-for-run! (fn [_] nil)
                      nido.coordinator.agent/launch!
                      (fn [opts]
                        (reset! sid-passed (:claude-session-id opts))
                        (reset! sid-at-launch (:claude-session-id (runs/read-run "rs")))
                        {:exit-code 0 :timed-out? false :claude-session-id (:claude-session-id opts)})
                      cstate/run-session-home-link (constantly "/tmp/nope")
                      status-file/read-status      (fn [_] nil)
                      anomaly/record-failure       (fn [det _] det)
                      breakers/record-failure!     (fn [& _] nil)]
          (#'core/run-blocking! "rs")
          (is (string? @sid-passed) "launch! is handed a session-id")
          (is (= @sid-passed @sid-at-launch)
              "session-id is persisted to run.edn BEFORE launch (survives interruption)")
          (is (= @sid-passed (:claude-session-id (runs/read-run "rs")))
              "and remains in run.edn after"))))))

(deftest run-blocking-fails-cleanly-when-session-spawn-throws
  ;; If spawn-session-for-run! (or launch) throws, run-blocking! must mark the
  ;; run :failed — NOT leave it stuck :running (a zombie that leaks an in-flight
  ;; slot). The ticket is then cleared → re-triable.
  (gate-with-tmp
    (fn [_]
      (tickets/open! :brian "BR-Z" {:notion-page-id "p" :url "u" :title "T"
                                    :opened-by :triage-teacher-bugs :notion-last-edited-at "t"})
      (runs/write-run! {:id "rz" :project :brian :trigger :triage-teacher-bugs
                        :source {:type :notion-view} :event-payload {:id "BR-Z"}
                        :skill :triage-bug :first-message "x" :agent :claude
                        :session-name "run-rz" :claude-session-id nil :limits {}
                        :priority 0 :session-profile :lite :uncapped? false
                        :state :queued :state-history [{:at "t" :state :queued}]
                        :artifacts [] :error nil})
      (with-redefs [runs/spawn-session-for-run! (fn [_] (throw (ex-info "boom: worktree create failed" {})))
                    nido.coordinator.agent/launch! (fn [_] {:exit-code 0 :timed-out? false}) ; unreached
                    cstate/run-session-home-link (constantly "/tmp/nope")
                    anomaly/record-failure      (fn [det _] det)
                    breakers/record-failure!    (fn [& _] nil)]
        (#'core/run-blocking! "rz")
        (is (= :failed (:state (runs/read-run "rz")))
            "spawn failure ⇒ run :failed, not stuck :running (no zombie)")
        (is (= :spawn-failed (-> (runs/read-run "rz") :error :reason)))
        (is (nil? (tickets/status :brian "BR-Z"))
            "ticket cleared on spawn-failure terminal ⇒ re-triable")))))

(deftest run-blocking-fails-no-op-agent-exit
  ;; Regression (the "36 sessions" incident): claude rejected the launch with
  ;; "Unknown command: /triage-bug" — exit 0, is_error false, num_turns 0. The
  ;; agent did literally nothing. This MUST be :failed (so the breaker sees it
  ;; and trips after max-failures), NOT :done. A :done run silently frees its
  ;; trigger's in-flight slot, letting the next queued run spawn → the whole
  ;; backlog drains and max-in-flight becomes a no-op.
  (gate-with-tmp
    (fn [_]
      (tickets/open! :brian "BR-NOOP" {:notion-page-id "p" :url "u" :title "T"
                                       :opened-by :triage-teacher-bugs :notion-last-edited-at "t"})
      (runs/write-run! {:id "rno" :project :brian :trigger :triage-teacher-bugs
                        :source {:type :notion-view} :event-payload {:id "BR-NOOP"}
                        :skill :triage-bug :first-message "/triage-bug x" :agent :claude
                        :session-name "run-rno" :claude-session-id nil :limits {}
                        :priority 0 :session-profile :lite :uncapped? false
                        :state :queued :state-history [{:at "t" :state :queued}]
                        :artifacts [] :error nil})
      (with-redefs [runs/spawn-session-for-run! (fn [_] nil)
                    nido.coordinator.agent/launch!
                    (fn [_] {:exit-code 0 :timed-out? false :num-turns 0
                             :result-text "Unknown command: /triage-bug"})
                    cstate/run-session-home-link (constantly "/tmp/nope")
                    status-file/read-status (fn [_] nil)
                    anomaly/record-failure      (fn [det _] det)
                    breakers/record-failure!    (fn [& _] nil)]
        (#'core/run-blocking! "rno")
        (is (= :failed (:state (runs/read-run "rno")))
            "exit-0 but zero turns ⇒ :failed, not :done (cap not silently freed)")
        (is (= :agent-no-op (-> (runs/read-run "rno") :error :reason))
            "failure reason records the no-op so the dashboard is honest")))))

(deftest skill-resolvable?-gates-symlink-profiles-only
  ;; A :lite (symlink) session's skill resolves from nido-native harness skills,
  ;; ~/.claude, or the target checkout's .claude; a non-symlink profile fails
  ;; open (can't be cheaply checked).
  (gate-with-tmp
    (fn [tmp]
      (let [target (str (fs/path tmp "co"))]
        (fs/create-dirs (str (fs/path target ".claude" "skills" "some-project-skill")))
        (with-redefs [profiles/resolve-profile
                      (fn [_ _] {:worktree {:strategy :symlink :target target}})]
          (is (true? (boolean (#'core/skill-resolvable?
                                {:project :brian :session-profile :lite :skill :some-project-skill})))
              "skill present in the target checkout resolves")
          (is (true? (boolean (#'core/skill-resolvable?
                                {:project :brian :session-profile :lite :skill :triage-bug})))
              "nido-native skill (triage-bug) resolves regardless of the target checkout")
          (is (false? (boolean (#'core/skill-resolvable?
                                 {:project :brian :session-profile :lite :skill :ghost-skill-xyz})))
              "skill nowhere (not nido-native, not in target) does not resolve ⇒ gated"))
        (with-redefs [profiles/resolve-profile
                      (fn [_ _] {:worktree {:strategy :git-worktree}})]
          (is (true? (#'core/skill-resolvable?
                       {:project :brian :session-profile :full :skill :ghost-skill-xyz}))
              ":full / git-worktree fails open (not gated)"))))))

(deftest run-blocking-fails-fast-when-skill-unavailable
  ;; The :lite checkout-coupling fix: a triage run whose /skill no longer exists
  ;; in the symlinked checkout is failed BEFORE a session is built — no doomed
  ;; spawn, breaker still fed.
  (gate-with-tmp
    (fn [tmp]
      (let [spawned (atom false)
            target  (str (fs/path tmp "co"))]
        (fs/create-dirs (str (fs/path target ".claude" "skills")))   ; .claude exists, skill absent
        ;; Use a skill that is NOT a nido-native harness skill, so the only place
        ;; it could resolve is the (empty) target checkout — i.e. it can't.
        (tickets/open! :brian "BR-U" {:notion-page-id "p" :url "u" :title "T"
                                      :opened-by :triage-teacher-bugs :notion-last-edited-at "t"})
        (runs/write-run! {:id "ru" :project :brian :trigger :triage-teacher-bugs
                          :source {:type :notion-view} :event-payload {:id "BR-U"}
                          :skill :ghost-skill-xyz :first-message "/ghost-skill-xyz x" :agent :claude
                          :session-name "run-ru" :claude-session-id nil :limits {}
                          :priority 0 :session-profile :lite :uncapped? false
                          :state :queued :state-history [{:at "t" :state :queued}]
                          :artifacts [] :error nil})
        (with-redefs [profiles/resolve-profile
                      (fn [_ _] {:worktree {:strategy :symlink :target target}})
                      runs/spawn-session-for-run! (fn [_] (reset! spawned true) nil)
                      cstate/run-session-home-link (constantly "/tmp/nope")
                      anomaly/record-failure       (fn [det _] det)
                      breakers/record-failure!     (fn [& _] nil)]
          (#'core/run-blocking! "ru")
          (is (= :failed (:state (runs/read-run "ru"))))
          (is (= :skill-unavailable (-> (runs/read-run "ru") :error :reason)))
          (is (false? @spawned) "no session is spawned for an unresolvable skill"))))))

(deftest run-blocking-tears-down-session-on-terminal-not-on-park
  ;; Resolved-terminal runs (:done/:failed) must reclaim their session so it
  ;; leaves the CLI list and frees PG/JVM/ports; a parked :awaiting-review run
  ;; must KEEP its session up (the human's review surface).
  (gate-with-tmp
    (fn [_]
      (let [torn (atom [])]
        (with-redefs [runs/spawn-session-for-run!     (fn [_] nil)
                      runs/teardown-session-for-run!   (fn [r] (swap! torn conj (:id r)))
                      cstate/run-session-home-link     (constantly "/tmp/nope")
                      status-file/read-status          (fn [_] nil)
                      anomaly/record-failure           (fn [det _] det)
                      breakers/record-failure!         (fn [& _] nil)]
          ;; (1) clean exit, no ticket ⇒ :done ⇒ torn down
          (tickets/open! :brian "BR-D" {:notion-page-id "p" :url "u" :title "T"
                                        :opened-by :triage-teacher-bugs :notion-last-edited-at "t"})
          (tickets/complete! :brian "BR-D" :triaged :applied)  ; ⇒ :done
          (runs/write-run! {:id "rdone" :project :brian :trigger :triage-teacher-bugs
                            :source {:type :notion-view} :event-payload {:id "BR-D"}
                            :skill :triage-bug :first-message "x" :agent :claude
                            :session-name "run-rdone" :claude-session-id nil :limits {}
                            :priority 0 :session-profile :lite :uncapped? false
                            :state :queued :state-history [{:at "t" :state :queued}]
                            :artifacts [] :error nil})
          (with-redefs [nido.coordinator.agent/launch!
                        (fn [_] {:exit-code 0 :timed-out? false :num-turns 5})]
            (#'core/run-blocking! "rdone"))
          (is (= :done (:state (runs/read-run "rdone"))))
          (is (some #{"rdone"} @torn) "a :done run is torn down")
          ;; (2) parked at :awaiting-review ⇒ NOT torn down
          (reset! torn [])
          (tickets/open! :brian "BR-P" {:notion-page-id "p" :url "u" :title "T"
                                        :opened-by :triage-teacher-bugs :notion-last-edited-at "t"})
          (tickets/set-status! :brian "BR-P" :awaiting-input)
          (runs/write-run! {:id "rpark" :project :brian :trigger :triage-teacher-bugs
                            :source {:type :notion-view} :event-payload {:id "BR-P"}
                            :skill :triage-bug :first-message "x" :agent :claude
                            :session-name "run-rpark" :claude-session-id nil :limits {}
                            :priority 0 :session-profile :lite :uncapped? false
                            :state :queued :state-history [{:at "t" :state :queued}]
                            :artifacts [] :error nil})
          (with-redefs [nido.coordinator.agent/launch!
                        (fn [_] {:exit-code 0 :timed-out? false :num-turns 5})]
            (#'core/run-blocking! "rpark"))
          (is (= :awaiting-review (:state (runs/read-run "rpark"))))
          (is (empty? @torn) "a parked :awaiting-review run keeps its session up"))))))

(deftest run-blocking-parks-triage-run-from-ticket-status
  (gate-with-tmp
    (fn [_]
      (tickets/open! :brian "BR-11" {:notion-page-id "p" :url "u" :title "T"
                                     :opened-by :triage-teacher-bugs :notion-last-edited-at "t"})
      (tickets/set-status! :brian "BR-11" :awaiting-input)   ; skill parked
      (runs/write-run! {:id "rp" :project :brian :trigger :triage-teacher-bugs
                        :source {:type :notion-view} :event-payload {:id "BR-11"}
                        :skill :triage-bug :first-message "x" :agent :claude
                        :session-name "run-rp" :claude-session-id nil :limits {}
                        :priority 0 :session-profile :lite :uncapped? false
                        :state :queued :state-history [{:at "t" :state :queued}]
                        :artifacts [] :error nil})
      (with-redefs [runs/spawn-session-for-run! (fn [_] nil)
                    nido.coordinator.agent/launch! (fn [_] {:exit-code 0 :timed-out? false})
                    cstate/run-session-home-link (constantly "/tmp/nope")
                    status-file/read-status (fn [_] nil)
                    anomaly/record-failure      (fn [det _] det)
                    breakers/record-failure!    (fn [& _] nil)]
        (#'core/run-blocking! "rp")
        (is (= :awaiting-review (:state (runs/read-run "rp")))
            "clean exit + ticket :awaiting-input ⇒ run parks at :awaiting-review, not :done")))))

(deftest run-blocking-plan-bug-launches-continue-ticket-headlessly
  ;; Promote leg: brings the :full session up, flips Notion, then launches
  ;; /continue-ticket headlessly under the pre-generated session-id so the
  ;; session is oriented before the human arrives. It bypasses the
  ;; skill-resolvable? gate (/continue-ticket is harness-injected). Here the
  ;; redefed launch! doesn't run the real skill, so the ticket stays :planning
  ;; ⇒ the run parks at :awaiting-review (the human resumes via the shim).
  (gate-with-tmp
    (fn [_]
      (let [notified  (atom nil)
            launch-of (atom nil)]
        (tickets/open! :brian "BR-12" {:notion-page-id "PG12" :url "u" :title "T"
                                       :opened-by :triage-new :notion-last-edited-at "t"})
        (tickets/complete! :brian "BR-12" :triaged :applied)
        (tickets/set-status! :brian "BR-12" :planning)   ; just promoted
        (runs/write-run! {:id "rplan" :project :brian :trigger :plan-bug
                          :source {:type :manual} :event-payload {:id "BR-12" :notion-page-id "PG12"}
                          :skill :plan-bug :first-message "/plan-bug x" :agent :claude
                          :session-name "impl-br-12" :claude-session-id nil :limits {}
                          :priority 0 :session-profile :full :uncapped? false
                          :on-promote {:notion-status "In progress"}
                          :state :queued :state-history [{:at "t" :state :queued}]
                          :artifacts [] :error nil})
        (with-redefs [runs/spawn-session-for-run! (fn [_] nil)
                      nido.coordinator.agent/launch! (fn [opts] (reset! launch-of opts) {:exit-code 0})
                      cstate/run-session-home-link (constantly "/tmp/nope")
                      notify/on-plan-spawn! (fn [run] (reset! notified (:id run)))
                      breakers/record-success! (fn [& _] nil)]
          (#'core/run-blocking! "rplan")
          (is (= "rplan" @notified) "Notion flip still happens at provision")
          (is (some? @launch-of) "/continue-ticket launches headlessly at provision")
          (is (= "/continue-ticket" (:first-message @launch-of))
              "the launched command is /continue-ticket, not the run's /plan-bug first-message")
          (is (= (:claude-session-id (runs/read-run "rplan")) (:claude-session-id @launch-of))
              "launched under the pre-generated session-id so the shim can --resume it")
          (is (= :awaiting-review (:state (runs/read-run "rplan")))
              "ticket still :planning (skill not really run) ⇒ parked ready-for-human"))))))

(deftest run-blocking-plan-bug-spawn-failure-parks-blocked-not-reverts
  ;; Promote regression (the shared-PG Flyway bounce): when a provision-only
  ;; :plan-bug session fails to boot (canonically a shared-PG migration checksum
  ;; mismatch), run-blocking! must PARK the workstream as a blocked gate — ticket
  ;; stays :planning (board stays :in-progress), the blocker rides the session —
  ;; NOT mark the run :failed. A :failed here runs on-run-terminal!, which reverts
  ;; the ticket :planning→:triaged, bouncing the board back to :ready and hiding
  ;; the failure. (Triage runs are unaffected: they still :failed — see
  ;; run-blocking-fails-cleanly-when-session-spawn-throws.)
  (gate-with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :triaging
                                  :external-refs [{:adapter :notion :id "BR-13"}]})]
        (session/create! :brian (:id w)
                         {:name "impl-br-13" :weight :heavy
                          :autonomy (assoc autonomy-parked :skill :plan-bug :phase :running)})
        (tickets/open! :brian "BR-13" {:notion-page-id "PG13" :url "u" :title "T"
                                       :opened-by :triage-new :notion-last-edited-at "t"})
        (tickets/complete! :brian "BR-13" :triaged :applied)
        (tickets/set-status! :brian "BR-13" :planning)   ; just promoted
        (runs/write-run! {:id "rblock" :project :brian :trigger :plan-bug
                          :source {:type :manual} :event-payload {:id "BR-13" :notion-page-id "PG13"}
                          :skill :plan-bug :first-message "/plan-bug x" :agent :claude
                          :session-name "impl-br-13" :workstream-id (:id w)
                          :claude-session-id nil :limits {:budget "30m"}
                          :priority 0 :session-profile :full :uncapped? false
                          :on-promote {:notion-status "In progress"}
                          :state :queued :state-history [{:at "t" :state :queued}]
                          :artifacts [] :error nil})
        (with-redefs [runs/spawn-session-for-run!
                      (fn [_] (throw (ex-info "Database migration failed — Flyway checksum mismatch on V265" {})))
                      notify/on-plan-spawn! (fn [_] nil)              ; unreached (spawn throws first)
                      cstate/run-session-home-link (constantly "/tmp/nope")
                      breakers/record-success! (fn [& _] nil)]
          (#'core/run-blocking! "rblock")
          (is (= :awaiting-review (:state (runs/read-run "rblock")))
              "spawn failure on a promote ⇒ parked (blocked gate), NOT :failed")
          (is (= :planning (tickets/status :brian "BR-13"))
              "ticket stays :planning ⇒ board stays :in-progress (no silent revert to :ready)")
          (let [err (get-in (first (session/list-sessions :brian (:id w))) [:autonomy :error])]
            (is (= :promote-blocked (:reason err))
                "blocker recorded on the session so the gate inbox shows WHY")
            (is (re-find #"Flyway" (:message err))
                "the blocker detail is surfaced to the human")))))))

(deftest queue-mode-trigger-parks-inbox-no-spawn
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (let [trigger {:name :triage-slack-bugs :intake :queue :skill :triage-bug
                       :source {:type :slack-channel :channel "C"}}
              env     {:broadcast {:type :slack-channel
                                   :source-config {:type :slack-channel :channel "C"}
                                   :payload {:adapter :slack-message :id "slack-C-1.0"
                                             :title "boom" :text "it broke"}}}
              tbp     {:brian [trigger]}]
          (#'nido.coordinator.core/process-envelope! env tbp)
          (let [ids (ws/list-ids :brian)]
            (is (= 1 (count ids)))
            (let [w (ws/read-ws :brian (first ids))]
              (is (= :incoming (:stage w)))
              (is (= "it broke" (-> w :intake :payload :text)))
              (is (empty? (session/list-sessions :brian (:id w))))))
          ;; nothing was spawned as a run
          (is (or (not (fs/exists? (cstate/runs-dir)))
                  (empty? (filter fs/directory? (fs/list-dir (cstate/runs-dir))))))))
      (finally (fs/delete-tree tmp)))))

(deftest maybe-expire-inbox-closes-old-entries
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (with-redefs [clock/now-iso (constantly "2026-06-01T00:00:00Z")]
          (intake/enqueue-inbox!
            {:project :brian
             :trigger {:name :triage-slack-bugs}
             :payload {:adapter :slack-message :id "slack-C-1.0" :text "x"}}))
        ;; reset the throttle clock so the sweep fires, then sweep 10 days later
        (reset! @#'nido.coordinator.core/!last-inbox-sweep-ms 0)
        (with-redefs-fn {#'nido.coordinator.core/registered-projects (constantly [:brian])}
          (fn []
            (#'nido.coordinator.core/maybe-expire-inbox!
              (+ (.toEpochMilli (java.time.Instant/parse "2026-06-01T00:00:00Z"))
                 (* 10 24 60 60 1000)))))
        (is (= :dropped (-> (ws/find-by-ref :brian :slack-message "slack-C-1.0")
                            :closed :outcome))))
      (finally (fs/delete-tree tmp)))))

;; ---------------------------------------------------------------------------
;; Task 3.2: persist-claude-session-id! mirrors id onto run AND session
;; ---------------------------------------------------------------------------

(deftest mirror-claude-session-id-onto-session
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:stage :triaging :external-refs []})]
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy
                          :autonomy (assoc autonomy-parked :phase :running)})
        (runs/write-run! (run-fixture "r1" (:id w) "auto" nil))   ; id nil at first
        (#'core/persist-claude-session-id! (runs/read-run "r1") "sid-77")
        (is (= "sid-77" (:claude-session-id (runs/read-run "r1"))))
        (is (= "sid-77" (get-in (first (session/list-sessions :brian (:id w)))
                                [:autonomy :claude-session-id])))))))

;; ---------------------------------------------------------------------------
;; Task 7: dispatch-envelope! routes :ship to handle-ship!, rest to process-envelope!
;; ---------------------------------------------------------------------------

(deftest ship-envelope-routes-to-handle-ship
  (let [seen (atom nil)]
    (with-redefs [nido.coordinator.ship/handle-ship! (fn [env] (reset! seen env) nil)]
      (#'nido.coordinator.core/dispatch-envelope! {:type :ship :project :brian :session "s" :ws-id "w"} {})
      (is (= :ship (:type @seen))))))

(deftest non-ship-envelope-still-routes-to-process-envelope
  ;; Verify that a non-:ship envelope does NOT reach handle-ship!.
  ;; Redefining a private var via #' can crash Babashka ("PersistentList cannot
  ;; be cast to Named"), so we use a tripwire on handle-ship! instead: assert
  ;; it was NOT called when dispatch-envelope! receives a :manual envelope.
  ;; (The :manual envelope will fall through to the real process-envelope!, which
  ;; may throw internally — that's fine; we only assert on the tripwire.)
  (let [handle-ship-called (atom false)]
    (with-redefs [nido.coordinator.ship/handle-ship! (fn [_] (reset! handle-ship-called :ship!))]
      (try
        (#'nido.coordinator.core/dispatch-envelope! {:type :manual :payload {}} {})
        (catch Throwable _
          ;; process-envelope! may throw in the bare test context — that's OK;
          ;; the assertion below is all we need.
          nil))
      (is (false? @handle-ship-called)
          "non-:ship envelope must NOT route to handle-ship!"))))

;; ---------------------------------------------------------------------------
;; Task 5: throttled maybe-adopt! sweep on the tick
;; ---------------------------------------------------------------------------

(deftest maybe-adopt!-throttles-and-sweeps-all-projects
  (let [calls (atom [])]
    (with-redefs [nido.work/adopt-orphans! (fn [p] (swap! calls conj p) {:adopted [] :yielded []})
                  nido.project/list-projects (constantly {"brian" {:directory "/x"}})]
      ;; private fn + private atom: test through the var
      (#'nido.coordinator.core/reset-adopt-throttle!)
      (#'nido.coordinator.core/maybe-adopt! 1000000)
      (#'nido.coordinator.core/maybe-adopt! 1001000)   ; 1s later — throttled
      (is (= [:brian] @calls)))))
