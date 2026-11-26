(ns nido.coordinator.source.start-failures
  "The :session-failure source: kept session-start failures nothing has recovered
   yet, one arrival per cause.

   An arrival rather than a direct fire from where the start failed, because the
   session substrate cannot reach the queue, and because one cause failing five
   sessions is one thing to recover, not five.

   A RECOVERY is one run of the recovery trigger, and what it records is what that run did: its
   diagnosis, each restore's outcomes, a blocker, a landing. A recovery from before runs is read
   as one (`records/holders`).

   OWED IS DERIVED on every poll, never stored: a failure stops being owed when a recovery records
   it :restored or :withdrawn, or when a recovery that named it closed — a `:closed` record, which
   only a recovery from before runs holds, or a person's dismissal of the run in the project's
   recovery journal (`journal-name`). A run that ends without settling what it was fired for leaves
   it owed, as a recovery that dies does, until a person dismisses it.

   ONE RECOVERY OF A CAUSE AT A TIME, across refs: this source emits nothing for a cause while a
   recovery run of it is starting or running. The event's id is keyed by the cause, its earliest
   owed failure and how many recovery runs the cause has had, so the core answers a fire
   re-emitted while one is being started, and a retry after a run ended is a new run.

   PACED PER CAUSE, not braked per trigger: one cause whose recovery keeps failing would otherwise
   silence the recovery of every other. After k consecutive recovery runs of a cause that ended
   without settling it, the cause waits one poll interval doubled k-1 times, capped at the
   source's :ceiling, from when the last of them last recorded anything."
  (:require
   [clojure.string :as str]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.records :as records]
   [nido.coordinator.source.registry :as sources]
   [nido.coordinator.source.state :as sst]
   [nido.session.failure :as failure]))

(def adapter
  "The `:adapter` a recovery fire's payload carries, which is what makes a run a recovery."
  :session-recovery)

(def ^{:doc "The name of a project's recovery journal: what people decided about recovery runs that are over."}
  journal-name "recovery")

(defn- dismissed-ids [project]
  (into #{} (keep #(when (= :recovery-dismissed (:kind %)) (:recovery %)))
        (records/of (records/journal project journal-name))))

(defn ^{:malli/schema [:=> [:cat :string] [:maybe :int]]}
  duration-ms
  "\"90s\" \"2m\" \"1h\" \"1d\" as milliseconds, or nil."
  [s]
  (when-let [[_ n u] (re-matches #"(\d+)\s*([smhd])" (str s))]
    (* (parse-long n) (case u "s" 1000 "m" 60000 "h" 3600000 "d" 86400000))))

(def ^:private default-pacing
  {:poll-ms    (* 2 60000)
   :ceiling-ms (* 24 3600000)})

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] :map]}
  pacing
  "The pacing a :session-failure source config declares — its poll interval and
   ceiling in milliseconds — with the source's defaults where it declares none."
  [source-config]
  {:poll-ms    (or (duration-ms (:poll source-config)) (:poll-ms default-pacing))
   :ceiling-ms (or (duration-ms (:ceiling source-config)) (:ceiling-ms default-pacing))})

(defn- cause-of-ref [ref-id]
  (first (str/split (str ref-id) #"@" 2)))

(defn- split-ids [s]
  (->> (str/split (str s) #",") (map str/trim) (remove str/blank?)))

(defn- outcomes-of [records]
  (mapcat :outcomes (filter #(= :session-restored (:kind %)) records)))

(defn- settled-by [records]
  (set (keep #(when (#{:restored :withdrawn} (:outcome %)) (:failure %)) (outcomes-of records))))

(defn- run-state
  "A recovery run's state as pacing and the page decide on it. A run that ended having settled
   everything it named is :done; one that ended leaving any of it owed is :awaiting-review when it
   recorded a blocker — it stopped for a person — and :failed otherwise, as one that died is."
  [{:keys [status records]} settled-all?]
  (case status
    :starting :queued
    :running  :running
    :done     (cond settled-all?                              :done
                    (some #(= :blocker (:kind %)) records)    :awaiting-review
                    :else                                     :failed)
    :failed))

(defn- first-named
  "The earliest failure a recovery's fire id `cause@failure[#n]` names, or nil."
  [ref-id]
  (some-> (second (str/split (str ref-id) #"@" 2)) (str/split #"#" 2) first))

(defn- closed?
  "Whether `records` end closed: their latest close or reopen is a close."
  [records]
  (= :closed (:kind (last (filter #(#{:closed :reopened} (:kind %)) records)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:vector :map]]}
  recoveries
  "Every recovery of `project` as data the rest of this namespace decides on, oldest first: its
   cause, whether and when it ended, the failures named on it, those it settled — every one it
   named, once it closed or a person dismissed it — its runs, and its records under `:entries` by
   kind. Throws when the core is unreachable."
  [project]
  (let [rs      (->> (records/holders project)
                     (filter #(= adapter (keyword (-> % :run :payload :adapter))))
                     (sort-by #(str (-> % :run :fired-at))))
        settled (into #{} (mapcat (comp settled-by :records)) rs)
        gone    (dismissed-ids project)]
    (vec
     (for [{:keys [id records] :as r} rs
          :let [payload (-> r :run :payload)
                named   (-> (set (split-ids (:failures payload)))
                            (into (some-> (first-named (:id payload)) vector))
                            (into (map :failure) (outcomes-of records)))
                dropped (contains? gone id)
                closed  (or dropped (closed? records))
                mine    (if closed named (settled-by records))
                state   (run-state r (every? (into settled mine) named))
                fired   (-> r :run :fired-at)
                last-at (or (some :at (reverse records)) fired)]]
      {:ws-id    id
       :cause    (str (or (:cause payload) (cause-of-ref (:id payload))))
       :closed   (when-not (#{:queued :running} state)
                   {:at last-at
                    :outcome (or (when dropped :dismissed)
                                 (when closed (some #(when (= :closed (:kind %)) (:outcome %)) (rseq records)))
                                 (if (= :done state) :done :dropped))})
       :named    named
       :settled  mine
       :runs     [{:id id :state state :event-payload payload :error (:detail r)
                   :state-history [{:at fired :state :queued} {:at last-at :state state}]}]
       :entries  (group-by :kind records)}))))

(defn- fired-by-this-source? [f]
  (= :session-failure (-> f :origin :source-type)))

(defn ^{:malli/schema [:=> [:cat [:vector :map]] [:set :string]]}
  discharged-ids
  "The ids of every failure settled, and never owed again: recorded :restored or :withdrawn by a
   recovery, or named by one that closed. Known from the recoveries alone, which is what lets a
   reader skip those records without opening them."
  [recoveries]
  (into #{} (mapcat :settled) recoveries))

(defn ^{:malli/schema [:=> [:cat [:vector :map] [:vector :map]] [:vector :map]]}
  owed
  "The failures still owed a recovery: not settled (`discharged-ids`), and not the start of a
   recovery Run from before runs — a recovery whose own session cannot start is nothing another
   recovery could fix."
  [failures recoveries]
  (let [discharged (discharged-ids recoveries)]
    (vec (remove #(or (discharged (:id %)) (fired-by-this-source? %)) failures))))

(defn ^{:malli/schema [:=> [:cat [:vector :map]] [:vector :map]]}
  undischarged-failures
  "Every kept failure not settled, read from disk — the only records `owed` can keep. Settled
   failures, which are nearly all of them once recovery has run a while, are never opened."
  [recoveries]
  (let [discharged (discharged-ids recoveries)]
    (vec (keep failure/failure (remove discharged (failure/ids))))))

(defn ^{:malli/schema [:=> [:cat [:vector :map] :string] :boolean]}
  in-flight?
  "Whether a recovery of `cause` is in flight: a recovery run of it starting or running."
  [recoveries cause]
  (boolean
   (some (fn [{:keys [runs] :as r}]
           (and (= cause (:cause r)) (some #(#{:queued :running} (:state %)) runs)))
         recoveries)))

(defn- ended-at [run]
  (some-> run :state-history last :at java.time.Instant/parse))

(defn ^{:malli/schema [:=> [:cat [:vector :map]] :int]}
  failed-in-a-row
  "How many of a cause's recovery runs, oldest first, ended leaving it owed — failed, or stopped
   for a person — at the end of the list."
  [runs]
  (count (take-while #(#{:failed :awaiting-review} (:state %)) (reverse runs))))

(defn ^{:malli/schema [:=> [:cat [:vector :map] :string] [:vector :map]]}
  pacing-runs
  "The runs that pace `cause`, oldest first: every recovery run of it. A run that settled the cause
   ends the streak before it, so a cause recurring long after is not held back by what failed then."
  [recoveries cause]
  (->> recoveries
       (filter #(= cause (:cause %)))
       (mapcat :runs)
       (sort-by #(str (-> % :state-history first :at)))
       vec))

(defn ^{:malli/schema [:=> [:cat [:vector :map] :map] :any]}
  due-at
  "When a cause may next be recovered, given its recovery runs (`pacing-runs`), oldest first, as an
   Instant — or nil when it may be recovered at any
   time, because the latest did not fail or its end time is unreadable. After k
   consecutive failures it is the last one's end plus one poll interval doubled
   k-1 times, capped at the ceiling."
  [runs {:keys [poll-ms ceiling-ms]}]
  (let [k (failed-in-a-row runs)]
    (when (pos? k)
      (when-let [since (ended-at (last runs))]
        (.plusMillis ^java.time.Instant since
                     (min ceiling-ms (* poll-ms (bit-shift-left 1 (min 40 (dec k))))))))))

(defn ^{:malli/schema [:=> [:cat [:vector :map] :any :map] :boolean]}
  due?
  "Whether a cause may be recovered at `now`: when due-at is nil or not after it."
  [runs now pacing]
  (let [at (due-at runs pacing)]
    (or (nil? at) (not (.isAfter ^java.time.Instant at ^java.time.Instant now)))))

(defn- innermost-message [f]
  (let [msgs (keep #(not-empty (:message %)) (:error f))]
    (or (last msgs) (-> f :error first :class) "")))

(defn- clamp [s n]
  (let [s (str s)] (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn ^{:malli/schema [:=> [:cat [:vector :map] [:vector :map] :any :map] [:vector :map]]}
  recovery-events
  "One event per cause among the owed failures that is neither in flight nor
   waiting out its delay, naming every owed failure of the cause, under an id
   keyed by the cause, its earliest owed failure and — once it has had any — how
   many recovery runs it has had, `<cause>@<failure>#<n>`."
  [owed-failures recoveries now pacing]
  (vec
   (for [[cause fs] (group-by failure/cause owed-failures)
         :let  [fs      (sort-by :id fs)
                first-f (first fs)
                tried   (count (filter #(= cause (:cause %)) recoveries))]
         :when (and (not (in-flight? recoveries cause))
                    (due? (pacing-runs recoveries cause) now pacing))]
     {:adapter  adapter
      :id       (cond-> (str cause "@" (:id first-f)) (pos? tried) (str "#" tried))
      :title    (clamp (str "recover " (:project first-f) "/" (:session first-f) ": "
                            (innermost-message first-f))
                       100)
      :cause    cause
      :project  (str (:project first-f))
      :session  (str (:session first-f))
      :message  (clamp (innermost-message first-f) 400)
      :failures (str/join "," (map :id fs))
      :count    (str (count fs))})))

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:vector :map]]}
  owed-failures
  "The failures `project`'s recoveries still owe, read from disk now."
  [project]
  (let [recs (recoveries project)]
    (owed (undischarged-failures recs) recs)))

(defn ^{:malli/schema [:=> [:cat :map :any] :map]}
  poll-once!
  "One poll: derive the owed failures, emit the events of the causes that may be
   recovered now, and return the state to persist."
  [source-config emit-fn]
  (let [project    (:project source-config)
        recs       (recoveries project)
        owed-fs    (owed (undischarged-failures recs) recs)
        now        (java.time.Instant/now)
        events     (recovery-events owed-fs recs now (pacing source-config))]
    (doseq [e events] (emit-fn e))
    {:type             :session-failure
     :source-config    source-config
     :last-polled-at   (clock/now-iso)
     :last-poll-result :ok
     :owed             (count owed-fs)
     :emitted          (mapv :id events)}))

(defn ^{:malli/schema [:=> [:cat :map :any] :map]}
  start-instance!
  [source-config emit-fn]
  (let [hash (sources/config-hash source-config)
        emit (fn [p] (emit-fn {:type :session-failure :source-config source-config :payload p}))]
    {:poll! (fn [] (sst/write-state! hash (poll-once! source-config emit)))
     :stop! (fn [] nil)}))

(defn ^{:malli/schema [:=> [:cat] :any]}
  register! []
  (sources/register-source!
   {:type   :session-failure
    :schema [:map
             [:type    [:= :session-failure]]
             [:project keyword?]
             [:poll    {:optional true} string?]
             [:ceiling {:optional true} string?]]
    :events [:map [:adapter [:= adapter]] [:id string?]]
    :start! start-instance!}))
