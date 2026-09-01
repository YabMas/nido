;; src/nido/coordinator/source/improvement.clj
(ns nido.coordinator.source.improvement
  "The :improvement source. Emits one event when an approved proposal is waiting
   and nothing is already being implemented.

   nido's analyses propose improvements to nido's own machinery, and a human
   approves them on the operations surface. Until now the approval was the end
   of the road: a durable record that nothing acted on. This is what acts on it.

   A polled source rather than a fire from the approval itself, and that is a
   decision rather than an accident. The approval is one compare-and-append
   under the workstream lock; making it also decide whether an improvement may
   START would put a scheduling question inside a write whose whole contract is
   that it is atomic and fast. More concretely, a batch of approvals is
   ordinary — a reader works down the board — and every one of them would fire.

   THE ONE-AT-A-TIME RULE LIVES HERE, and it has to. Three things were verified
   about the alternatives before this was built:

   1. It cannot be the trigger's `:max-in-flight`. That gate counts sessions in
      `session/gating-phases`, which is #{:preprocessing :running :parked} — so
      a session that FAILS or is halted releases the slot, and the next poll
      starts a second improvement on top of a branch the first one abandoned
      mid-edit.

   2. It cannot key on a failure record. A hard crash — the machine sleeps, the
      daemon is killed — writes no tombstone, so \"no failure recorded\" reads
      identically to \"nothing ever ran\". The hold is the absence of a CLOSE,
      which a crash cannot fake.

   3. It must emit AT MOST ONE EVENT PER POLL. Provisioning mints the
      workstream, the run and a :queued session at drain time, before any
      scheduling gate runs — so two envelopes drained in one tick become two
      improvement sessions whatever any cap says. Emitting one is the only
      point at which the rule can be enforced.

   The consequence, accepted deliberately: a session that dies without closing
   its workstream WEDGES this source until a human closes it. That is the safe
   direction. A wedge costs a delay and is visible on the board as an open
   workstream; the alternative spends an agent budget every poll, forever, on a
   branch nobody is reading."
  (:require
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.proposal :as proposal]
   [nido.coordinator.source.registry :as sources]
   [nido.coordinator.source.state :as sst]))

(def ^:private title-cap
  "How much of a proposal's summary rides in the ref title. The title is what
   the board shows for the improvement workstream, so it is a label rather than
   the record — the payload carries the observation whole."
  90)

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  payload
  "Pure: the envelope payload for one proposal.

   `:adapter`/`:id` are the external ref the coordinator dedups workstreams on,
   and they are what makes the hold work: a re-emit for the same proposal lands
   back in the workstream that already holds the attempt instead of starting a
   second one. The adapter is named explicitly because `spawn/external-ref`
   defaults it to :notion, and a proposal is not a Notion page.

   The observation is carried WHOLE — summary, evidence and the proposal itself
   — rather than as a pointer to the ledger. The session that receives it can
   read the ledger perfectly well, but the payload is also what the run record
   keeps and what a human reads on a session card, and a card saying only
   `ws-20260831-fb41e7/1.2` says nothing."
  [{:keys [ws-id analysis-seq observation kind where summary evidence
           proposal run-id reviewed] :as p}]
  (let [addr (proposal/address p)]
    (cond-> {:adapter      :improvement
             :id           addr
             :title        (str "improve: " (if (> (count (str summary)) title-cap)
                                              (str (subs (str summary) 0 title-cap) "…")
                                              summary))
             :ws-id        ws-id
             :analysis-seq analysis-seq
             :observation  observation
             :address      addr
             :summary      (str summary)
             :fix          (str proposal)}
      kind     (assoc :kind (name kind))
      where    (assoc :where (str where))
      evidence (assoc :evidence (str evidence))
      run-id   (assoc :run-id (str run-id))
      reviewed (assoc :reviewed (str reviewed)))))

(defn ^{:malli/schema [:=> [:cat :map :any] :map]}
  poll-once!
  "One poll for a source-config. Returns the state to persist; emits at most one
   event through `emit-fn`.

   Carries no seen-set, unlike the Slack sources, and does not need one. The
   registry writes a broadcast to a CONTENT-ADDRESSED filename, so re-emitting
   the same proposal before the drain has minted its workstream lands on the
   same file and is a no-op; once the workstream exists the hold answers instead.
   A seen-set here would be a second, weaker copy of a fact the workstreams
   already hold — and one that a cleared state dir would silently drop."
  [source-config emit-fn]
  (let [project  (:project source-config)
        attempts (proposal/attempts project)
        pick     (proposal/next-to-implement (proposal/of-project project) attempts)
        open     (some #(when (:open? %) %) attempts)]
    (when pick (emit-fn (payload pick)))
    {:type            :improvement
     :source-config   source-config
     :last-polled-at  (clock/now-iso)
     :last-poll-result :ok
     ;; What this poll DID, in the state file, because the honest failure mode
     ;; here is silence: a source that emits nothing because it is held looks
     ;; exactly like one that emits nothing because there is no work, and the
     ;; first is the one somebody has to go and clear.
     :held-by         (when open (:ws-id open))
     :emitted         (when pick (proposal/address pick))}))

(defn ^{:malli/schema [:=> [:cat :map :any] :map]}
  start-instance!
  [source-config emit-fn]
  (let [hash (sources/config-hash source-config)
        emit (fn [p]
               (emit-fn {:type :improvement :source-config source-config :payload p}))]
    {:poll! (fn [] (sst/write-state! hash (poll-once! source-config emit)))
     :stop! (fn [] nil)}))

(defn ^{:malli/schema [:=> [:cat] :any]}
  register! []
  (sources/register-source!
   {:type   :improvement
    :schema [:map
             [:type    [:= :improvement]]
             [:project keyword?]
             [:poll    {:optional true} string?]]
    :events [:map [:adapter [:= :improvement]] [:id string?]]
    :start! start-instance!}))
