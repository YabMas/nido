(ns nido.coordinator.source.start-failures
  "The :session-failure source: kept session-start failures nothing has recovered
   yet, one arrival per cause.

   An arrival rather than a direct fire from where the start failed, because the
   session substrate cannot reach the queue, and because one cause failing five
   sessions is one thing to recover, not five.

   OWED IS DERIVED on every poll, never stored: a failure stops being owed when a
   recovery workstream for its cause closes having named it — in the payload of a
   recovery Run fired onto it, or in a :session-restored outcome. A recovery that
   dies without closing leaves its failures owed; one kept while a recovery was
   closing, and named by neither, stays owed too.

   ONE RECOVERY OF A CAUSE AT A TIME, across refs. The event's ref is keyed by the
   cause and its earliest owed failure, so the fire gate's pending-session dedup
   holds a ref; but a cause can change refs (its earliest failure settled), so this
   source also emits nothing for a cause while any recovery session of it is in
   flight.

   PACED PER CAUSE, not braked per trigger. A recovery Run charges no breaker —
   one cause whose recovery keeps failing would otherwise silence the recovery of
   every other. Instead, after k consecutive failed recovery Runs on a cause's
   open workstream, the cause waits one poll interval doubled k-1 times, capped
   at the source's :ceiling, from when the last of them ended."
  (:require
   [clojure.string :as str]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.triggers :as triggers]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.source.registry :as sources]
   [nido.coordinator.source.state :as sst]
   [nido.session.failure :as failure]))

(def adapter
  "The external-ref adapter a recovery workstream carries."
  :session-recovery)

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

(defn- recovery-triggers
  "The names of `project`'s triggers that this source fires."
  [project]
  (->> (triggers/load-for-project project)
       (filter #(= :session-failure (-> % :source :type)))
       (map :name)))

(defn- recovery-runs-by-ws
  "`project`'s recovery Runs grouped by workstream, oldest first. Run ids carry
   their project and trigger, which keeps this from reading every Run there is."
  [project]
  (let [marks (map #(str "-" (name project) "-" (name %) "-") (recovery-triggers project))]
    (->> (runs/list-run-ids)
         (filter (fn [id] (some #(str/includes? id %) marks)))
         (keep runs/read-run)
         (filter runs/recovery?)
         (sort-by #(-> % :state-history first :at))
         (group-by :workstream-id))))

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:vector :map]]}
  recoveries
  "Every recovery workstream of `project` as data the rest of this namespace
   decides on: its cause, whether and when it closed, the failures named on it,
   the outcomes restores recorded, its recovery Runs and its sessions."
  [project]
  (let [runs-by-ws (recovery-runs-by-ws project)]
    (vec
     (for [ws-id (ws/list-ids project)
           :let  [w   (ws/read-ws project ws-id)
                  ref (some #(when (= adapter (:adapter %)) %) (:external-refs w))]
           :when ref
           :let  [runs     (get runs-by-ws ws-id [])
                  outcomes (mapcat :outcomes (ws/entries-of project ws-id :session-restored))]]
       {:ws-id    ws-id
        :cause    (cause-of-ref (:id ref))
        :closed   (:closed w)
        :named    (into (set (mapcat #(split-ids (-> % :event-payload :failures)) runs))
                        (map :failure) outcomes)
        :settled  (set (keep #(when (#{:restored :withdrawn} (:outcome %)) (:failure %)) outcomes))
        :runs     (vec runs)
        :sessions (vec (session/list-sessions project ws-id))}))))

(defn- fired-by-this-source? [f]
  (= :session-failure (-> f :origin :source-type)))

(defn ^{:malli/schema [:=> [:cat [:vector :map]] [:set :string]]}
  discharged-ids
  "The ids of every failure a closed recovery workstream named — settled, and
   never owed again. Known from the recoveries alone, which is what lets a
   reader skip those records without opening them."
  [recoveries]
  (into #{} (comp (filter :closed) (mapcat :named)) recoveries))

(defn ^{:malli/schema [:=> [:cat [:vector :map] [:vector :map]] [:vector :map]]}
  owed
  "The failures still owed a recovery: not named by a closed recovery workstream,
   and not the start of a recovery Run — a recovery whose own session cannot
   start is nothing another recovery could fix."
  [failures recoveries]
  (let [discharged (discharged-ids recoveries)]
    (vec (remove #(or (discharged (:id %)) (fired-by-this-source? %)) failures))))

(defn ^{:malli/schema [:=> [:cat [:vector :map]] [:vector :map]]}
  undischarged-failures
  "Every kept failure no closed recovery workstream named, read from disk — the
   only records `owed` can keep. Settled failures, which are nearly all of them
   once recovery has run a while, are never opened."
  [recoveries]
  (let [discharged (discharged-ids recoveries)]
    (vec (keep failure/failure (remove discharged (failure/ids))))))

(defn ^{:malli/schema [:=> [:cat [:vector :map] :string] :boolean]}
  in-flight?
  "Whether a recovery of `cause` is in flight: a recovery session queued,
   preprocessing or running on any of the cause's workstreams, or parked on an
   open one. A session parked on a workstream a person closed is not — the close
   stopped it."
  [recoveries cause]
  (boolean
   (some (fn [{:keys [closed sessions] :as r}]
           (and (= cause (:cause r))
                (some #(let [phase (get-in % [:autonomy :phase])]
                         (or (#{:queued :preprocessing :running} phase)
                             (and (= :parked phase) (not closed))))
                      sessions)))
         recoveries)))

(defn- ended-at [run]
  (some-> run :state-history last :at java.time.Instant/parse))

(defn ^{:malli/schema [:=> [:cat [:vector :map]] :int]}
  failed-in-a-row
  "How many of a cause's recovery Runs, oldest first, failed at the end of the list."
  [runs]
  (count (take-while #(= :failed (:state %)) (reverse runs))))

(defn ^{:malli/schema [:=> [:cat [:vector :map] :map] :any]}
  due-at
  "When a cause may next be recovered, given the recovery Runs on its open
   workstream, oldest first, as an Instant — or nil when it may be recovered at any
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
   waiting out its delay, naming every owed failure of the cause, under a ref
   keyed by the cause and its earliest owed failure."
  [owed-failures recoveries now pacing]
  (vec
   (for [[cause fs] (group-by failure/cause owed-failures)
         :let  [fs    (sort-by :id fs)
                first-f (first fs)
                open-runs (->> recoveries
                               (filter #(and (= cause (:cause %)) (not (:closed %))))
                               (mapcat :runs)
                               (sort-by #(-> % :state-history first :at)))]
         :when (and (not (in-flight? recoveries cause))
                    (due? (vec open-runs) now pacing))]
     {:adapter  adapter
      :id       (str cause "@" (:id first-f))
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
