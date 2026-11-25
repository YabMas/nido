(ns nido.coordinator.view.activity
  "Whether anything is happening in a workstream, and what: ONE pure function, `of`, over ONE
   value, the workstream's `Observation`.

   Every facet a surface says about it — what is underway (:doing), whether anything runs
   (:live), whether it has stalled, who owes the next move, whether it is paused, its engagement
   and whether it needs the person — is a field of `of`'s answer, so no two of them can be read
   off different sources.

   What runs is the core's to say. Nothing a process leaves on disk — an activity claim, a run's
   lock, a session's ports, an agent listing — is read as something running: everything nido
   runs, it runs as the core's turns. The one thing read beside the core is the claim a review
   takes inside a turn, and only to say what that turn is doing (`observe`).

   The observation is made once per row (`observe` reads the claim; the caller adds the record
   facts and the core's state, which this band may not reach) and nothing below `of` reads
   anything."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [nido.coordinator.record.activity :as claims]))

(def Observation
  "Everything `of` reads.

   Record facts — what the work records say, from `pipeline/status` and the ledger's closure:
     :closed?    the workstream is settled: closed (not between phases), its band :done, or the
                 core closed it.
     :owed-by    who the position owes the next move to — :person :agent :machine :nobody.
     :paused?    a person's standing pause, on the ledger or holding the core's units.

   The core's state of it:
     :core       its activity (`nido.core.domain/activity`: {:kind …}), or nil when the core does
                 nothing on it, is owed nothing, or does not hold it.
     :movable?   the core holds an open unit of it that no person holds.

   What a running turn is doing:
     :claim      the activity claim held while the core's turn is :working, with its round's
                 :progress, or nil."
  [:map
   [:closed?   :boolean]
   [:owed-by   {:optional true} [:maybe :keyword]]
   [:paused?   {:optional true} :boolean]
   [:core      {:optional true} [:maybe :map]]
   [:movable?  {:optional true} :boolean]
   [:claim     {:optional true} [:maybe :map]]])

;; ── The function ────────────────────────────────────────────────────────────

(defn- underway
  "What is underway, or nil: the core's activity short of an ask, which waits on a person —
   described by the claim a review holds inside a :working turn when there is one, since that
   says how far its round has got."
  [{:keys [claim core]}]
  (let [k (:kind core)]
    (cond
      (or (nil? k) (= :asking k)) nil

      (and claim (= :working k))
      (into {:source :claim} (select-keys claim [:kind :run-id :report-path :started-at :pid :progress]))

      :else
      (-> core (dissoc :kind) (assoc :source :core :activity k)))))

(defn ^{:malli/schema [:=> [:cat :map] :Activity]}
  of
  "The workstream's activity, every facet of it from `obs` alone — an `Observation`:

     {:doing      what is underway (`underway`), or nil — always nil once settled
      :live       something is underway
      :owed-by    :person while the core asks them, else the position's
      :paused     a person's pause stands
      :stalled    the core holds an open unit nobody holds, does nothing and is owed nothing, no
                  pause stands and the next move is not the person's: no turn's end, inbox item or
                  ask will wake it, so only a person moves it again — a fault, never a resting
                  state. A workstream whose next move the person owes is waiting on them, not
                  stalled, and one with no such unit is never stalled: nothing of it is the core's
                  to move.
      :needs-you  the core asks the person a question. Only a core ask: a ledger that says the
                  person owes a move asks nothing until the core raises it. Never a machine
                  failure the core raised as an ask — that is :failed.
      :failed     the core holds a machine-side failure for the person (its conductor stopped):
                  that move is a recovery, shown as an error. A workstream holding a question as
                  well is both needs-you and failed — each still needs the person
      :engagement :settled, :parked-at-gate (asked), :active (live), :stalled, :queued (the
                  machine owes it) or :idle}"
  [{:keys [closed? owed-by paused? core movable?] :as obs}]
  (let [doing   (when-not closed? (underway obs))
        asking? (and (not closed?) (= :asking (:kind core)))
        failed  (boolean (and asking? (:failed? core)))
        asked   (boolean (and asking? (not (:failure-only? core))))
        live    (some? doing)
        owed-by (if asking? :person (or owed-by :nobody))
        paused  (boolean paused?)
        stalled (boolean (and (not closed?) movable? (nil? core) (not paused) (not= :person owed-by)))]
    {:doing      doing
     :live       live
     :owed-by    owed-by
     :paused     paused
     :stalled    stalled
     :needs-you  asked
     :failed     failed
     :engagement (cond
                   closed?              :settled
                   asking?              :parked-at-gate
                   live                 :active
                   stalled              :stalled
                   (= :machine owed-by) :queued
                   :else                :idle)}))

;; ── What the records hold ───────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] [:maybe :map]]}
  round-progress
  "How far a running round has got, read off the report it keeps as it goes:
   {:round n :phase <keyword or nil> :findings n}, or nil for a report with no
   round yet.

   Both loops that take a claim persist this report on every event, and share
   its outline — rounds, each a sequence of phases — so one reading serves both.
   `:findings` counts what the CURRENT round's judging phase raised (`review`
   for a diff review, per layer; `judge` for a record round), which is what the
   rest of that round is working through. `:phase` is the one still running, nil
   between phases. While a diff review's judging is still running, `:judged` of
   `:of` layers have been read, so a count of nothing so far is not mistaken for
   a clean round."
  [report]
  (when-let [r (last (:rounds report))]
    (let [phases (:phases r)
          judged (filter #(#{"review" "judge"} (:phase %)) phases)
          open   (first (filter #(and (= "running" (:status %)) (seq (:layers %))) judged))]
      (cond->
       {:round    (:round r)
        :phase    (some #(when (= "running" (:status %)) (keyword (:phase %))) phases)
        :findings (+ (reduce + (for [p judged
                                     l (:layers p)
                                     :let [n (:findings l)]
                                     :when (number? n)]
                                 n))
                     (reduce + (for [p judged
                                     :let [f (:findings p)]
                                     :when (sequential? f)]
                                 (count f))))}
        open (assoc :judged (count (filter #(number? (:findings %)) (:layers open)))
                    :of     (count (:layers open)))))))

(defn- with-progress
  "`claim` with its running round's progress beside it, when its report can be read. A report
   that is missing or mid-write leaves the claim as it was: the progress decorates the label and
   is never why a row says something is running."
  [claim]
  (let [path (:report-path claim)]
    (if-let [p (when (and path (fs/exists? path))
                 (try (round-progress (json/parse-string (slurp path) true))
                      (catch Exception _ nil)))]
      (assoc claim :progress p)
      claim)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:maybe :map]]}
  observe
  "The activity claim held against workstream `ws-id` right now, with its round's :progress when
   its report can be read, or nil. Asked only while the core's turn on it is :working: a claim is
   never what says something runs, only what a running turn is doing."
  [project ws-id]
  (some-> (claims/read-live project ws-id) with-progress))
