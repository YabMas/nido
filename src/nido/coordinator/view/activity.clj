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
     :band       the board band it sits in; :shipping is the merge lane's.
     :owed-by    who the position owes the next move to — :person :agent :machine :nobody.
     :activated? nido's recipe runs on it (a person activated it), so its position owes moves.
     :landing?   the move the position owes is the :land stage — a release candidate's landing, or
                 a published draft's — so a person's need of it is said as a landing, not a
                 question.
     :failure    a failure in the core only the person clears, or nil: :conductor-stopped (the
                 conductor failed until the core stopped it and asked the person), or
                 :start-failed (an open unit nobody holds whose session failed to start).
     :paused?    a person's standing pause, on the ledger or holding the core's units.

   The core's state of it:
     :held?      the core holds it.
     :core       its activity (`nido.core.domain/activity`: {:kind …}), or nil when the core does
                 nothing on it, is owed nothing, or does not hold it.
     :movable?   the core holds an open unit of it that no person holds.
     :waits-on   the deliberate wait it rests on, a move something other than the idle core makes,
                 or nil: :person-act (a row on an intake band, which the person's Promote or Apply
                 moves) or :open-slice (a drain while a slice of it is open).

   What a running turn is doing:
     :claim      the activity claim held while the core's turn is :working, with its round's
                 :progress, or nil."
  [:map
   [:closed?    :boolean]
   [:band       {:optional true} [:maybe :keyword]]
   [:owed-by    {:optional true} [:maybe :keyword]]
   [:activated? {:optional true} :boolean]
   [:landing?   {:optional true} :boolean]
   [:failure    {:optional true} [:maybe [:enum :conductor-stopped :start-failed]]]
   [:paused?    {:optional true} :boolean]
   [:held?      {:optional true} :boolean]
   [:core       {:optional true} [:maybe :map]]
   [:movable?   {:optional true} :boolean]
   [:waits-on   {:optional true} [:maybe [:enum :person-act :open-slice]]]
   [:claim      {:optional true} [:maybe :map]]])

(def Running
  "What runs on a workstream: the core's activity, or the claim a review holds inside a :working turn."
  [:map [:source [:enum :core :claim]]])

(def QueuedMerge
  "The one thing underway that is not live: a merge waiting its turn while the core does nothing."
  [:map {:closed true} [:source [:= :merge]] [:phase [:= :queued]]])

(def Activity
  "What `of` answers: one closed variant per :engagement, each fixing what its facets may be, and
   within it what runs fixed by whether it is live and paused — live is `Running` and unpaused,
   paused is never live, anything else underway is a `QueuedMerge` — so contradictory facets are
   not an Activity. The tests validate every answer over the enumerated observations against it."
  [:multi {:dispatch :engagement}
   [:settled
    [:map {:closed true}
      [:engagement [:= :settled]]
      [:doing :nil]
      [:live [:= false]]
      [:owed-by [:enum :agent :machine :nobody]]
      [:paused :boolean]
      [:stalled [:= false]]
      [:stuck :boolean]
      [:needs-you [:= false]]
      [:needs :nil]]]
   [:parked-at-gate
    [:or
     [:map {:closed true}
          [:engagement [:= :parked-at-gate]]
          [:doing Running]
          [:live [:= true]]
          [:owed-by [:= :person]]
          [:paused [:= false]]
          [:stalled [:= false]]
          [:stuck :boolean]
          [:needs-you [:= true]]
          [:needs [:enum :ask :landing :system-error]]]
     [:map {:closed true}
          [:engagement [:= :parked-at-gate]]
          [:doing [:maybe [:or Running QueuedMerge]]]
          [:live [:= false]]
          [:owed-by [:= :person]]
          [:paused [:= true]]
          [:stalled [:= false]]
          [:stuck :boolean]
          [:needs-you [:= true]]
          [:needs [:enum :ask :landing :system-error]]]
     [:map {:closed true}
          [:engagement [:= :parked-at-gate]]
          [:doing [:maybe QueuedMerge]]
          [:live [:= false]]
          [:owed-by [:= :person]]
          [:paused [:= false]]
          [:stalled [:= false]]
          [:stuck :boolean]
          [:needs-you [:= true]]
          [:needs [:enum :ask :landing :system-error]]]
     [:map {:closed true}
          [:engagement [:= :parked-at-gate]]
          [:doing :nil]
          [:live [:= false]]
          [:owed-by [:= :person]]
          [:paused [:= false]]
          [:stalled [:= true]]
          [:stuck :boolean]
          [:needs-you [:= true]]
          [:needs [:= :system-error]]]]]
   [:active
    [:map {:closed true}
          [:engagement [:= :active]]
          [:doing Running]
          [:live [:= true]]
          [:owed-by [:enum :agent :machine :nobody]]
          [:paused [:= false]]
          [:stalled [:= false]]
          [:stuck :boolean]
          [:needs-you [:= false]]
          [:needs :nil]]]
   [:queued
    [:or
     [:map {:closed true}
          [:engagement [:= :queued]]
          [:doing [:maybe [:or Running QueuedMerge]]]
          [:live [:= false]]
          [:owed-by [:= :machine]]
          [:paused [:= true]]
          [:stalled [:= false]]
          [:stuck :boolean]
          [:needs-you [:= false]]
          [:needs :nil]]
     [:map {:closed true}
          [:engagement [:= :queued]]
          [:doing [:maybe QueuedMerge]]
          [:live [:= false]]
          [:owed-by [:= :machine]]
          [:paused [:= false]]
          [:stalled [:= false]]
          [:stuck :boolean]
          [:needs-you [:= false]]
          [:needs :nil]]]]
   [:idle
    [:or
     [:map {:closed true}
          [:engagement [:= :idle]]
          [:doing [:maybe [:or Running QueuedMerge]]]
          [:live [:= false]]
          [:owed-by [:enum :agent :nobody]]
          [:paused [:= true]]
          [:stalled [:= false]]
          [:stuck :boolean]
          [:needs-you [:= false]]
          [:needs :nil]]
     [:map {:closed true}
          [:engagement [:= :idle]]
          [:doing [:maybe QueuedMerge]]
          [:live [:= false]]
          [:owed-by [:enum :agent :nobody]]
          [:paused [:= false]]
          [:stalled [:= false]]
          [:stuck :boolean]
          [:needs-you [:= false]]
          [:needs :nil]]]]])

;; ── The function ────────────────────────────────────────────────────────────

(def ^:private merge-queued
  "The one thing underway that is not live: a branch in the merge lane, waiting its turn while
   the core does nothing on it. It is underway — the workstream is merging, and every surface says
   `merging · queued` — but nothing of nido's runs until the lane takes it, so no surface lights
   the working dot for it."
  {:source :merge :phase :queued})

(defn- underway
  "What is underway, or nil: the core's activity short of an ask, which waits on a person —
   described by the claim a review holds inside a :working turn when there is one, since that
   says how far its round has got — else a merge waiting its turn (`merge-queued`) in the
   :shipping band."
  [{:keys [claim core band]}]
  (let [k (:kind core)]
    (cond
      (#{:asking :stuck} k) nil

      (and claim (= :working k))
      (into {:source :claim} (select-keys claim [:kind :run-id :report-path :started-at :pid :progress]))

      k
      (-> core (dissoc :kind) (assoc :source :core :activity k))

      (= :shipping band)
      merge-queued)))

(def needs-categories
  "What needing the person can be — one concept, :needs-you, said in one of these:
     :ask           the person is asked: a core ask, or a blocker, question, verdict or gate the
                    position owes them;
     :landing       a release candidate waits on the person's Land;
     :system-error  a failure only the person clears: the conductor stopped after failing, a
                    unit's session failed to start and nothing in the core will retry it, or the
                    workstream stalled (`of`'s :stalled) — the person's Wake is what moves it."
  [:ask :landing :system-error])

(defn ^{:malli/schema [:=> [:cat :Observation] :Activity]}
  of
  "The workstream's activity, every facet of it from `obs` alone — an `Observation`:

     {:doing      what is underway (`underway`), or nil — always nil once settled, and nil while
                  the core's turn is stuck: its run was ended and lives on, so nothing it does
                  is underway
      :live       something runs: anything underway, except a merge waiting its turn
                  (`merge-queued`) and anything while a person's pause stands
      :needs-you  the person owes the next move: the core asks them; a failure only they clear
                  (`:failure`) stands — a failed start only while the core does nothing and no
                  pause stands; the position of an activated, unpaused workstream owes them a
                  move; or it is stalled. One answer from every source, so a blocker the ledger
                  holds, a landing waiting on Land, a stopped conductor and a stall all put the
                  workstream on Needs you.
      :needs      which of `needs-categories` it is, exactly while :needs-you, else nil: a
                  failure outranks an ask, which outranks a landing; a stall is a :system-error
      :owed-by    :person exactly when it needs you; else the position's, a position's :person
                  that nothing makes theirs (not activated, or paused) reading :nobody
      :paused     a person's pause stands
      :stalled    the core is its mover and nothing will move it: the core holds it with the
                  recipe activated on it, or holds an open unit of it nobody holds; a move is owed
                  to an agent or the machine; the core does nothing and is owed nothing; nothing
                  is underway (a merge waiting its turn included); no pause stands, nothing
                  else needs the person and it rests on no deliberate wait (:waits-on). No turn's end, inbox item or ask will wake it, so only a
                  person moves it again — a fault, never a resting state, so it needs the person
                  as a :system-error. A workstream the core is not the mover of is never stalled,
                  nor is one whose move is the person's or nobody's.
      :stuck      the core's turn is stuck (`nido.core.domain/activity` :stuck, or an ask carrying
                  the stuck run): its run was ended and its process lives on. A fact about a
                  process, not about who owes the move, so it is read apart from every other
                  facet — settled or not, asked or not, paused or not: closing a workstream ends
                  no process, and an answer to an ask ends none either
      :engagement :settled, :parked-at-gate (needs you, a stall included), :active (live),
                  :queued (the machine owes it) or :idle}

   Invariants, over every observation (the tests check them exhaustively):
     underway ⇒ live, except a merge waiting its turn or a pause;
     live ⇒ underway, and never paused;
     :owed-by :person ⇔ :needs-you ⇔ :needs names a category — no exception;
     a stall needs you, as a :system-error;
     stuck ⇒ nothing underway, and so nothing live;
     settled ⇒ nothing underway, needed or stalled."
  [{:keys [closed? owed-by activated? landing? failure paused? held? core movable? waits-on] :as obs}]
  (let [doing     (when-not closed? (underway obs))
        paused    (boolean paused?)
        asking?   (and (not closed?) (= :asking (:kind core)))
        live      (boolean (and doing (not= :merge (:source doing)) (not paused)))
        failed?   (and (not closed?)
                       (case failure
                         :conductor-stopped asking?
                         :start-failed      (and (nil? core) (not paused))
                         false))
        persons?  (and (not closed?) activated? (not paused) (= :person owed-by))
        stalled   (boolean (and (not closed?) (or (and held? activated?) movable?)
                                (nil? core) (nil? doing) (not paused) (not failed?)
                                (not= :conductor-stopped failure) (nil? waits-on)
                                (#{:agent :machine} owed-by)))
        needs     (cond failed?                   :system-error
                        asking?                   :ask
                        (and persons? landing?)   :landing
                        persons?                  :ask
                        stalled                   :system-error)
        needs-you (some? needs)
        owed-by   (cond needs-you            :person
                        (= :person owed-by)  :nobody
                        :else                (or owed-by :nobody))
        ;; The core's reading alone: it reads a stuck run on a closed workstream too, and beside
        ;; an ask, which carries it.
        stuck     (boolean (or (= :stuck (:kind core)) (:stuck core)))]
    {:doing      doing
     :live       live
     :owed-by    owed-by
     :paused     paused
     :stalled    stalled
     :stuck      stuck
     :needs-you  needs-you
     :needs      needs
     :engagement (cond
                   closed?              :settled
                   needs-you            :parked-at-gate
                   live                 :active
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
