(ns nido.boot.intake
  "The intake host: the supervised bb process that runs the sources and triggers and turns each
   arrival into core commands.

   HIDES which sources and triggers run, their limits and breakers, and how an arrival becomes
   commands. A fire whose trigger spawns opens a unit in the core, which provisions its session
   and starts the trigger's skill as the unit's first turn; a queue-mode fire pens a workstream for
   the person and opens nothing; a merge or a ticket's terminal status settles the workstream in
   the core. It writes no coordination state of its own: work records (the ledger) and the poll
   snapshots are all it keeps.

   One loop, one second apart: drain the envelope queue, poll the sources that are due, then the
   throttled passes. A pass that waits on the network runs on its own thread, one at a time."
  (:refer-clojure :exclude [run!])
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [nido.boot.attention :as attention]
   [nido.coordinator.daemon.breakers :as breakers]
   [nido.coordinator.daemon.halt :as halt]
   [nido.coordinator.daemon.heartbeat :as heartbeat]
   [nido.coordinator.lane.github-issue-intake :as github-issue-intake]
   [nido.coordinator.lane.github-merge :as github-merge]
   [nido.coordinator.lane.notion-sync :as notion-sync]
   [nido.coordinator.lane.titles :as titles]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.tickets :as tickets]
   [nido.coordinator.record.triggers :as triggers]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.source.drain :as drain-source]
   [nido.coordinator.source.events :as events]
   [nido.coordinator.source.improvement :as improvement-source]
   [nido.coordinator.source.notion :as nsource]
   [nido.coordinator.source.queue :as queue]
   [nido.coordinator.source.registry :as sources]
   [nido.coordinator.source.review-queue-nightly :as review-queue-nightly-source]
   [nido.coordinator.source.slack :as slack-source]
   [nido.coordinator.source.slack-events :as slack-events-source]
   [nido.coordinator.source.start-failures :as start-failures-source]
   [nido.coordinator.source.sweep :as sweep-source]
   [nido.coordinator.work :as work]
   [nido.coordinator.work.core :as wcore]
   [nido.github.config :as gh-config]
   [nido.notion.views :as views]
   [nido.platform.desk :as desk]
   [nido.platform.project :as project]
   [nido.review.reconcile :as review-reconcile]
   [nido.review.tree :as review-tree]
   [nido.session.reclaim :as reclaim]))

(def ^:private defaults
  {:poll-ms                 1000
   ;; Orphaned per-session state dirs older than the grace window are reclaimed hourly: a
   ;; session's state dir exists for its whole boot before its registry entry does.
   :reclaim-interval-ms     (* 60 60 1000)
   :reclaim-min-age-ms      (* 60 60 1000)
   :review-settle-interval-ms (* 60 60 1000)
   ;; Un-promoted inbox entries are dropped after three days.
   :inbox-expiry-ms         (* 3 24 60 60 1000)
   :inbox-sweep-interval-ms (* 30 60 1000)
   :titles-interval-ms      (* 5 60 1000)
   :attention-interval-ms   15000
   :drain-wakes-interval-ms 60000})

(defn- warn [& parts]
  (binding [*out* *err*] (println (apply str "nido intake: " parts))))

(defn- parse-duration-ms [s]
  (when-let [[_ n u] (re-matches #"(\d+)\s*([smh])" (str s))]
    (* (parse-long n) (case u "s" 1000 "m" 60000 "h" 3600000))))

(defn- registered-projects []
  (mapv keyword (keys (project/list-projects))))

(defn- load-all-triggers []
  (into {} (map (fn [p] [p (triggers/load-for-project p)])) (registered-projects)))

;; ── a fire, as core commands ─────────────────────────────────────────────────

(defn- route-fire!
  "One routed fire: dropped with a word when its breaker is open or it is a dry run, penned when
   its trigger queues, skipped when its ticket is settled, else opened in the core."
  [routed]
  (let [tname (-> routed :trigger :name)
        label (str (name (:project routed)) "/" (name tname))]
    (cond
      (:error routed) (warn "dropping envelope — " (pr-str routed))
      (breakers/tripped? (:project routed) tname) (warn "trigger breaker open — skipping " label)
      (-> routed :trigger :dry-run?) (println "nido intake: dry run —" label "would fire")
      (= :queue (-> routed :trigger :intake)) (work/enqueue-inbox! routed)
      (and (= :triage-bug (-> routed :trigger :skill))
           (some->> (-> routed :payload :id) (tickets/gate-decision (:project routed)) (not= :spawn)))
      (println "nido intake: triage gate skip —" label (-> routed :payload :id))
      :else (let [r (work/open-fire! routed)]
              (when (#{:opened :running} r)
                (println "nido intake:" label (name r) (or (-> routed :payload :id) "")))))))

(defn ^{:malli/schema [:=> [:cat :map :map] :any]}
  dispatch!
  "Route one drained envelope: a drain source's fire to lane-drain, which no trigger names; an
   envelope of the retired run machinery — a ship, a run to submit, a restore's continuation — is
   dropped with a word, since the core runs every turn; everything else is a source event for
   trigger matching."
  [env triggers-by-project]
  (cond
    (#{:ship :submit-run :execute-run :resume-turn} (:type env))
    (warn "dropping a " (name (:type env)) " envelope: the core runs turns now")

    (= :drain (get-in env [:broadcast :type]))
    (work/drain-fire! (get-in env [:broadcast :payload]))

    :else (doseq [routed (events/route env triggers-by-project)] (route-fire! routed))))

(def ^:private core-settlement
  "How a merge poll and a ticket sync read and settle a workstream: in the core."
  {:open?   (fn [project ws-id] (work/core-open? project ws-id))
   :merged! (fn [project ws-id] (work/core-merged! project ws-id "github"))
   :close!  (fn [project ws-id outcome]
              (work/core-resolve! project ws-id (if (= :done outcome) :done :drop) nil))})

;; ── sources ──────────────────────────────────────────────────────────────────

(defonce ^:private !sources (atom {}))

(defn- instance-key [sc] (str (name (:type sc)) ":" (sources/config-hash sc)))

(defn- source-configs
  "The sources to run: every enabled trigger's registered source, each project's board views no
   trigger polls (their page snapshot is what the board reads), and one drain census per project
   — a trigger's own before the default."
  [triggers-by-project]
  (let [polled (into #{} (for [[p ts] triggers-by-project, t ts
                               :when (= :notion-view (:type (:source t)))]
                           [p (:view (:source t))]))
        configs (distinct
                 (concat
                  (for [[p ts] triggers-by-project, t ts
                        :let [src (assoc (:source t) :project p)]
                        :when (and (not= :manual (:type src)) (not (false? (:enabled? t)))
                                   (sources/lookup (:type src)))]
                    src)
                  (for [p (keys triggers-by-project), v (sort (views/board-views p))
                        :when (not (polled [p v]))]
                    {:type :notion-view :view v :project p :poll (views/board-poll p)})
                  (when (sources/lookup :drain)
                    (for [p (keys triggers-by-project)] {:type :drain :project p :poll "2m"}))))
        first-drain (into {} (comp (filter #(= :drain (:type %))) (map (juxt :project identity)))
                          (reverse configs))]
    (remove #(and (= :drain (:type %)) (not (identical? % (first-drain (:project %))))) configs)))

(defn- stop-source! [k]
  (when-let [{:keys [stop!]} (get @!sources k)] (try (stop!) (catch Exception _ nil)))
  (swap! !sources dissoc k))

(defn- reconcile-sources! [triggers-by-project]
  (let [want (into {} (map (juxt instance-key identity)) (source-configs triggers-by-project))]
    (doseq [k (set/difference (set (keys @!sources)) (set (keys want)))] (stop-source! k))
    (doseq [[k sc] want :when (not (contains? @!sources k))]
      (let [handle ((:start! (sources/lookup (:type sc))) sc sources/emit-broadcast!)]
        (swap! !sources assoc k (assoc handle :source-config sc :last-polled-ms 0
                                       :poll-ms (or (parse-duration-ms (:poll sc)) 300000)))))))

;; ── throttled passes ─────────────────────────────────────────────────────────

(defonce ^:private !last (atom {}))
(defonce ^:private !background (atom {}))
(defonce ^:private !in-background? (atom false))

(defn- due? [k now-ms interval-ms]
  (let [last-ms (get @!last k)]
    (when (or (nil? last-ms) (>= (- now-ms last-ms) interval-ms))
      (swap! !last assoc k now-ms)
      true)))

(defn- off-the-loop
  "Run `f` on its own thread under `k` unless its last run is still going; in line when the loop is
   driven by a test. What it throws is said, never thrown into the loop."
  [k f]
  (let [run #(try (f) (catch Throwable t (warn (pr-str k) " threw — " (ex-message t))))]
    (if-not @!in-background?
      (run)
      (let [prior (get @!background k)]
        (when (or (nil? prior) (realized? prior))
          (swap! !background assoc k (future (run))))))))

(defn- per-project-poll!
  "Poll `poll!` for each project whose config `load` answers, at most once per its :poll
   (`default-poll` when unset)."
  [k now-ms load default-poll poll!]
  (doseq [p (registered-projects)
          :let [cfg (try (load p) (catch Throwable t (warn (name k) " config for " p " — " (ex-message t))))]
          :when (and cfg (due? [k p] now-ms (or (parse-duration-ms (or (:poll cfg) default-poll)) 300000)))]
    (try (poll! p cfg) (catch Throwable t (warn (name k) " poll threw for " p " — " (ex-message t))))))

(defn- hygiene! [now-ms]
  (when (due? :reclaim now-ms (:reclaim-interval-ms defaults))
    (let [{:keys [deleted]} (reclaim/reclaim-orphans! {:min-age-ms (:reclaim-min-age-ms defaults)
                                                       :now-ms now-ms})]
      (when (seq deleted)
        (println "nido intake: reclaimed" (count deleted) "orphan state dir(s):" (str/join ", " (map first deleted)))))
    (review-tree/reap-ended! (cstate/runs-dir))
    (reclaim/forget-dangling-everywhere!))
  (when (due? :settle-reviews now-ms (:review-settle-interval-ms defaults))
    (review-reconcile/settle-abandoned! {}))
  (when (due? :expire-inbox now-ms (:inbox-sweep-interval-ms defaults))
    (doseq [p (registered-projects)]
      (work/expire-stale! p (:inbox-expiry-ms defaults) now-ms)))
  (when (due? :drain-wakes now-ms (:drain-wakes-interval-ms defaults))
    (doseq [p (registered-projects)] (work/drain-wakes! p))))

(defonce ^:private !attention-seen (atom nil))

(defn- notify-attention!
  "Post a desktop notification for each ask, halt or tripped breaker that appeared since the last
   look."
  [now-ms]
  (when (due? :attention now-ms (:attention-interval-ms defaults))
    (let [current (attention/items {:gates (work/all-gates) :halt (halt/read-halt-info)
                                    :breakers (breakers/auto-tripped-triggers)
                                    :casualties (:casualties (work/owed-revivals))})]
      (doseq [n (attention/coalesce (attention/arrivals @!attention-seen current))]
        (desk/request! (assoc n :kind :notify)))
      (reset! !attention-seen (set (keys current))))))

(defn- heartbeat!
  "Write the host's heartbeat — its pid, whether it runs or is halted, and when — where the
   surfaces read the coordinator's liveness from."
  [halted?]
  (try (heartbeat/write! {:pid (.pid (java.lang.ProcessHandle/current))
                          :status (if halted? :halted :running)})
       (catch Throwable t (warn "heartbeat not written — " (ex-message t)))))

(defn ^{:malli/schema [:=> [:cat] :any]}
  tick!
  "One pass of the loop. A halt stops intake — nothing is drained or polled — and nothing else."
  []
  (let [now-ms (System/currentTimeMillis)
        tbp    (load-all-triggers)]
    (heartbeat! (boolean (halt/read-halt-info)))
    (off-the-loop :attention #(notify-attention! now-ms))
    (when-not (halt/read-halt-info)
      (reconcile-sources! tbp)
      (off-the-loop :dispatch #(doseq [env (queue/drain!)]
                                 (try (dispatch! env tbp)
                                      (catch Throwable t (warn "dispatch failed — " (ex-message t))))))
      (doseq [[k inst] @!sources :when (>= (- now-ms (:last-polled-ms inst)) (:poll-ms inst))]
        (off-the-loop [:source k] (:poll! inst))
        (swap! !sources assoc-in [k :last-polled-ms] now-ms))
      (off-the-loop :github-merges
                    #(per-project-poll! :github-merge now-ms gh-config/load-config "5m"
                                        (fn [p cfg] (github-merge/poll-and-react! p cfg core-settlement))))
      (off-the-loop :github-issues
                    #(per-project-poll! :github-issues now-ms
                                        (fn [p] (let [c (gh-config/load-config p)]
                                                  (when (and (:issues c) (not (false? (:enabled (:issues c))))) c)))
                                        "5m" github-issue-intake/poll-and-reconcile!))
      (off-the-loop :notion-sync
                    #(per-project-poll! :notion-sync now-ms notion-sync/load-config "10m"
                                        (fn [p cfg] (notion-sync/poll-and-react! p cfg core-settlement))))
      (off-the-loop :titles #(when (due? :titles now-ms (:titles-interval-ms defaults))
                               (let [ps (registered-projects)]
                                 (doseq [p ps] (titles/refresh-project! p))
                                 (titles/write-tab-titles! ps))))
      (off-the-loop :hygiene #(hygiene! now-ms)))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  run!
  "Run the intake host until the process ends: register the source plugins, then tick every
   `:poll-ms`."
  [& {:keys [poll-ms] :or {poll-ms (:poll-ms defaults)}}]
  (cstate/ensure-dirs!)
  (println "nido intake: starting (poll" poll-ms "ms)")
  (doseq [register! [nsource/register! slack-source/register! slack-events-source/register!
                     improvement-source/register! sweep-source/register!
                     review-queue-nightly-source/register! start-failures-source/register!
                     drain-source/register!]]
    (register!))
  (.addShutdownHook (Runtime/getRuntime) (Thread. #(doseq [k (keys @!sources)] (stop-source! k))))
  (reset! !in-background? true)
  (loop []
    (try (tick!) (catch Throwable t (warn "tick failed — " (ex-message t))))
    (Thread/sleep (long poll-ms))
    (recur)))
