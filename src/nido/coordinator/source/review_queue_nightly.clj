(ns nido.coordinator.source.review-queue-nightly
  "The :review-queue-nightly source. Grooms a project's Review queue with nobody
   deciding: the first poll at or after `:at` each local day queues one plan run,
   and once that plan is written a later poll approves every item nobody decided
   and queues the plan's apply.

   BOTH FIRES ARE KEYED, ADDRESSED ENVELOPES, not broadcasts through `emit-fn`. A
   broadcast is content-addressed only while it waits: re-emitted after the drain
   took it, it queues a second run. The key is taken once and forever, so once a
   day and once a plan are properties of the write, and nothing here has to
   remember what it fired — a restart, a crash between two writes or two polls
   racing all land on a key already taken. The apply's key is the page's
   (record.review-queue/apply-key), so the page's Apply and this source cannot
   both queue one plan.

   IT DECIDES ONLY WHAT IT FIRED. A plan run carries `:nightly \"true\"` in its
   payload exactly when this source queued it; a grooming a person started from
   the page has none and is never touched here. An item a person decided on a
   nightly plan before this source reached it keeps that decision.

   Every nightly plan that is written and whose apply is not recorded is carried
   out, not only the newest: a plan left unapplied because the daemon was down is
   applied when it comes back. The apply leg skips every ticket whose page was
   edited after the plan read it, so a late apply never writes over a person.
   The poll that queues a plan run applies nothing, so a plan is never decided in
   the same breath as the next one is asked for."
  (:require
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.review-queue :as rq]
   [nido.coordinator.source.queue :as queue]
   [nido.coordinator.source.registry :as sources]
   [nido.coordinator.source.state :as sst])
  (:import
   (java.time Instant LocalTime ZoneId ZonedDateTime)))

(defn- zone [{z :zone}]
  (if z (ZoneId/of z) (ZoneId/systemDefault)))

(defn ^{:malli/schema [:=> [:cat :map :string] [:maybe :string]]}
  due-day
  "The local calendar day (`yyyy-mm-dd`, in `:zone`, else the JVM's zone) whose
   grooming is due at instant `now`, or nil before `:at` (`HH:mm`, default
   00:00) on that day. `:at` is inclusive."
  [source-config now]
  (let [local (ZonedDateTime/ofInstant (Instant/parse now) (zone source-config))
        at    (LocalTime/parse (or (:at source-config) "00:00"))]
    (when-not (.isBefore (.toLocalTime local) at)
      (str (.toLocalDate local)))))

(defn- nightly? [run]
  (= "true" (str (get-in run [:event-payload :nightly]))))

(defn- plan-id [day] (str "nightly-" day))

(defn- queue-plan!
  "Queue `day`'s plan run, once: `{:queued path}` the first time, `{:already-queued
   key}` after."
  [project day]
  (queue/enqueue-keyed!
   (str "review-queue-nightly-" (name project) "-" day)
   {:target  {:project project :trigger rq/plan-trigger}
    :payload {:id (plan-id day) :nightly "true"}}))

(defn- decide-and-apply!
  "Approve every undecided item of nightly plan run `id` and queue its apply, as
   :nightly. Nil when there is nothing to do — no plan written yet, or its apply
   already recorded; otherwise what record.review-queue/apply! answered."
  [project id]
  (let [plan (rq/read-plan id)
        d    (rq/read-decisions id)]
    (when (and plan (not (get-in d [:applying :fired-at])))
      (when-not (:applying d)
        (doseq [{:keys [n]} (:items plan)
                :when (not (contains? (:decisions d) n))]
          (rq/decide! id n :approved)))
      (rq/apply! id
                 #(queue/enqueue-keyed! (rq/apply-key id)
                                        {:target  {:project project :trigger rq/apply-trigger}
                                         :payload (rq/apply-payload id)})
                 :nightly))))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  poll-once!
  "One poll. Queues today's plan run when it is due; a poll that queued nothing
   decides and queues the apply of every nightly plan run that is written and
   unapplied. Returns the state to persist."
  [{:keys [project] :as source-config}]
  (let [day     (due-day source-config (clock/now-iso))
        planned (when day (queue-plan! project day))
        applied (when-not (:queued planned)
                  (into {}
                        (keep (fn [{:keys [id]}]
                                (when-let [r (decide-and-apply! project id)] [id (:decision r)])))
                        (filter nightly? (rq/plan-runs project))))]
    {:type             :review-queue-nightly
     :source-config    source-config
     :last-polled-at   (clock/now-iso)
     :last-poll-result :ok
     :due-day          day
     :plan-queued      (when (:queued planned) (plan-id day))
     :applied          applied}))

(defn ^{:malli/schema [:=> [:cat :map :any] :map]}
  start-instance!
  "Start one configured instance. `emit-fn` is unused: both fires are keyed
   envelopes this source queues itself (see the namespace doc)."
  [source-config _emit-fn]
  (let [hash (sources/config-hash source-config)]
    {:poll! (fn [] (sst/write-state! hash (poll-once! source-config)))
     :stop! (fn [] nil)}))

(defn ^{:malli/schema [:=> [:cat] :any]}
  register! []
  (sources/register-source!
   {:type   :review-queue-nightly
    :schema [:map
             [:type    [:= :review-queue-nightly]]
             [:project keyword?]
             [:at      {:optional true} [:re #"^\d{2}:\d{2}$"]]
             [:zone    {:optional true} string?]
             [:poll    {:optional true} string?]]
    :events [:map [:id string?] [:nightly [:= "true"]]]
    :start! start-instance!}))
