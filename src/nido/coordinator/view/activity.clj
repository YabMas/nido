(ns nido.coordinator.view.activity
  "Whether anything is happening in a workstream, and what: ONE pure function, `of`, over ONE
   value, the workstream's `Observation`.

   Every facet a surface says about it — what is underway (:doing), whether anything runs
   (:live), whether it has stalled, who owes the next move, whether it is paused, its engagement
   and whether it needs the person — is a field of `of`'s answer, so no two of them can be read
   off different sources. They were: the core answered engagement and the ledger answered
   liveness, and a row said idle beside its own live mark.

   The observation is made once per row (`observe` reads what the records hold; the caller adds
   what this band may not reach — which sessions are up, and the core's activity) and nothing
   below `of` reads anything."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [nido.coordinator.record.activity :as claims]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.session :as session]))

(def Observation
  "Everything `of` reads.

   Record facts — what the work records say, from `pipeline/status` and the ledger's closure:
     :closed?    the workstream is settled: closed (not between phases), its band :done, or the
                 core closed it.
     :band       the board band the row sits in; :shipping is what puts a merge underway.
     :owed-by    who the position owes the next move to — :person :agent :machine :nobody.
     :paused?    a person's standing pause, on the ledger or holding the core's units.
     :resting?   the conversation is a person's own, resting after their prompt — not a stall.
     :stall-why  why it stopped, used only if it has stalled.

   Live evidence — observed, never stored:
     :claim       the activity claim held right now, with its round's :progress, or nil.
     :claim-held? the claim is held, whether or not its holder has published it yet — so a
                 claim being taken is live before there is anything to say it is underway.
     :runs       the workstream's runs, each with :live? — its agent holds its lock.
     :sessions   its sessions, each with :up? (its ports answer) and :mid-turn? (the agent
                 listing sees it mid-turn).
     :core       the core's activity on it (`nido.core.domain/activity`: {:kind …}), or nil
                 when the core holds it doing nothing or does not hold it."
  [:map
   [:closed?   :boolean]
   [:band      {:optional true} [:maybe :keyword]]
   [:owed-by   {:optional true} [:maybe :keyword]]
   [:paused?   {:optional true} :boolean]
   [:resting?  {:optional true} :boolean]
   [:stall-why {:optional true} [:maybe :map]]
   [:claim     {:optional true} [:maybe :map]]
   [:claim-held? {:optional true} :boolean]
   [:runs      {:optional true} [:sequential :map]]
   [:sessions  {:optional true} [:sequential :map]]
   [:core      {:optional true} [:maybe :map]]])

;; ── The function ────────────────────────────────────────────────────────────

(defn- merge-phase
  "How far the merge lane has got, from the newest merge run's state; :queued when no merge run
   says — the lane takes one branch at a time, so waiting for it is a state the board keeps
   saying."
  [runs]
  (or (when-let [r (last (filter #(or (= :merge (:mode %)) (= :merge (:trigger %))) runs))]
        (case (:state r)
          :queued                   :queued
          (:running :preprocessing) :driving
          :awaiting-review          :blocked
          :done                     :awaiting-merge
          nil))
      :queued))

(defn- work-session?
  "A session of the work: neither the conductor's, which runs for as long as the workstream is
   open, nor a chat's, which is up without any of the work moving."
  [s]
  (not (or (:conductor s) (:chat s))))

(defn- persons-session?
  "A person's own session: no autonomy and no :claude facet, so nido drives nothing in it and
   its being up is the only evidence of work."
  [s]
  (and (work-session? s) (nil? (:autonomy s)) (nil? (:claude s))))

(defn- underway
  "What is underway, from exactly one source, or nil. In precedence order:
     :claim    a named process holds the claim — evidence of a process alive at the read, and
               the one source that says how far its round has got;
     :core     a turn running, a session starting, the conductor deciding;
     :merge    the band is :shipping;
     :session  an agent holds its run's lock; else a session that is up works through an
               instruction nido gave it or holds queued (`:answering` — a reply leaves its run
               parked and lockless); else a session of the work the listing sees mid-turn.
   A run parked for review is a gate, not activity, and so is an ask: both wait on a person."
  [{:keys [claim core band runs sessions]}]
  (let [core-kind (:kind core)]
    (cond
      claim
      (into {:source :claim} (select-keys claim [:kind :run-id :report-path :started-at :pid :progress]))

      (and core-kind (not= :asking core-kind))
      (-> core (dissoc :kind) (assoc :source :core :activity core-kind))

      (= :shipping band)
      {:source :merge :phase (merge-phase runs)}

      :else
      (if-let [r (last (filter :live? runs))]
        {:source :session :phase (:state r) :session (:session-name r)}
        (if-let [s (first (filter #(and (:up? %)
                                        (or (get-in % [:claude :pending]) (get-in % [:claude :queued])))
                                  sessions))]
          {:source :session :phase :answering :session (:name s)}
          (when-let [s (first (filter #(and (:mid-turn? %) (work-session? %)) sessions))]
            {:source :session :phase :running :session (:name s)}))))))

(defn ^{:malli/schema [:=> [:cat :map] :Activity]}
  of
  "The workstream's activity, every facet of it from `obs` alone — an `Observation`:

     {:doing      what is underway (`underway`), or nil — always nil once settled
      :live       something runs: anything underway but a merge waiting its turn, a claim held,
                  a run whose agent holds its lock, or a person's own session that is up. Observed even when
                  settled, so a process still running under a closed workstream is not hidden.
      :owed-by    :person while the core asks them, else the position's
      :paused     a person's pause stands
      :stalled    an agent owes the move and nothing runs, nothing asks, nobody paused it and the
                  conversation is not a person's own at rest — a fault, never a resting state,
                  with :why beside it
      :needs-you  the core asks the person something. Only a core ask: a ledger that says the
                  person owes a move asks nothing until the core raises it.
      :engagement :settled, :parked-at-gate (asked), :active (live), :stalled, :queued (the
                  machine owes it) or :idle}"
  [{:keys [closed? owed-by paused? resting? stall-why claim-held? runs sessions core] :as obs}]
  (let [doing    (when-not closed? (underway obs))
        asking?  (and (not closed?) (= :asking (:kind core)))
        live     (boolean (or (and doing (not= :merge (:source doing)))
                              claim-held?
                              (some :live? runs)
                              (some #(and (:up? %) (persons-session? %)) sessions)))
        owed-by  (if asking? :person (or owed-by :nobody))
        paused   (boolean paused?)
        stalled  (and (not closed?) (= :agent owed-by) (not live) (not paused) (not resting?))]
    (cond-> {:doing      doing
             :live       live
             :owed-by    owed-by
             :paused     paused
             :stalled    stalled
             :needs-you  asking?
             :engagement (cond
                           closed?             :settled
                           asking?             :parked-at-gate
                           live                :active
                           stalled             :stalled
                           (= :machine owed-by) :queued
                           :else               :idle)}
      stalled (assoc :why stall-why))))

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

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId [:maybe [:set :string]] [:maybe [:set :string]]]
                         :map]}
  observe
  "The live evidence of `Observation` for workstream `ws-id`, as its records hold it now: the
   claim, each run with :live?, each session with :up? and :mid-turn?. `up` is the set of session
   names whose ports answer and `mid-turn` the set of claude session ids the agent listing sees
   mid-turn (nil: none) — both are the caller's to observe, since this band reaches neither the
   substrate nor the listing."
  [project ws-id up mid-turn]
  (let [up       (or up #{})
        mid-turn (or mid-turn #{})
        ;; The lock files are what say a run's agent is alive; a live run with no record of its
        ;; own is still a run holding its lock.
        live     (set (runs/live-runs project ws-id))
        recorded (mapv #(assoc % :live? (contains? live (:id %))) (runs/runs-of project ws-id))]
    {:claim       (some-> (claims/read-live project ws-id) with-progress)
     :claim-held? (claims/held? project ws-id)
     :runs        (into recorded
                        (for [id (sort live) :when (not-any? #(= id (:id %)) recorded)]
                          {:id id :live? true}))
     :sessions (mapv #(assoc %
                             :up?       (contains? up (:name %))
                             :mid-turn? (contains? mid-turn (get-in % [:claude :session-id])))
                     (session/list-sessions project ws-id))}))
