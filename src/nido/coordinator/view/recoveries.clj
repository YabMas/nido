(ns nido.coordinator.view.recoveries
  "Session recovery as something a person watches: where each cause stands, and
   what happened, in order.

   A pure read model over records it is handed — kept failures, each carrying its
   cause, and the recovery source's own reading of the recovery workstreams, with
   their ledger entries. It stores nothing and writes nothing: the feed is derived
   from the facts recovery already keeps, so it cannot disagree with them.

   The states are the recovery source's decisions, not a second reading of them.
   Owed, in flight and when a cause is next due are asked of
   `nido.coordinator.source.start-failures`, so the page cannot call a cause
   waiting that the source is about to fire."
  (:require
   [clojure.string :as str]
   [nido.coordinator.source.start-failures :as sf]))

(def window-days
  "How far back a settled cause is still shown as a row."
  7)

(def page-size
  "The most events one page of the feed carries. The trail only grows — kept
   failures are never pruned — so the feed is read a page at a time rather than
   re-sent whole on every poll."
  80)

(defn- instant [s]
  (try (java.time.Instant/parse (str s)) (catch Exception _ nil)))

(defn- since? [at since]
  (when-let [i (instant at)] (not (.isBefore ^java.time.Instant i since))))

(defn- innermost-message [f]
  (let [msgs (keep #(not-empty (:message %)) (:error f))]
    (or (last msgs) (-> f :error first :class) "")))

(defn- first-at [run] (-> run :state-history first :at))
(defn- last-at [run] (-> run :state-history last :at))

(defn- by-time-desc [xs k]
  (sort-by #(or (some-> (k %) instant .toEpochMilli) 0) > xs))

(defn- latest-ws
  "The recovery workstream a cause's row reads: its open one, else the one closed
   most recently."
  [mine]
  (or (first (remove :closed mine))
      (first (by-time-desc (filter :closed mine) #(-> % :closed :at)))))

(defn- open-runs [mine]
  (vec (sort-by first-at (mapcat :runs (remove :closed mine)))))

(defn- phases [mine]
  (for [r mine, s (:sessions r)]
    {:open? (not (:closed r)) :phase (get-in s [:autonomy :phase]) :ws-id (:ws-id r)}))

(defn ^{:malli/schema [:=> [:cat :string :map] :map]}
  cause-state
  "Where `cause` stands, given the owed failures and recoveries in `ctx`:

     :needs-you   a recovery session of it is parked on an open workstream — a
                  person is being asked something (:gate-ws names where)
     :recovering  a recovery session of it is queued or running
     :waiting     its open workstream's latest recoveries failed and it is not
                  yet due (:due-at is when it is)
     :owed        failures are owed or a workstream is open, and nothing above
     :restored    nothing owed or open; its latest recovery closed :done
     :dismissed   nothing owed or open; its latest recovery closed otherwise"
  [cause {:keys [owed recoveries now pacing]}]
  (let [mine   (filter #(= cause (:cause %)) recoveries)
        ph     (phases mine)
        parked (some #(when (and (:open? %) (= :parked (:phase %))) %) ph)
        runs   (open-runs mine)
        due-at (when (seq runs) (sf/due-at runs pacing))
        live?  (or (some #(= cause (:cause %)) owed) (some (complement :closed) mine))]
    (cond
      parked
      {:state :needs-you :gate-ws (:ws-id parked)}

      (sf/in-flight? (vec recoveries) cause)
      {:state :recovering}

      (and live? due-at (.isAfter ^java.time.Instant due-at now))
      {:state :waiting :due-at (str due-at) :failed-in-a-row (sf/failed-in-a-row runs)}

      live?
      {:state :owed}

      (= :done (-> (latest-ws mine) :closed :outcome))
      {:state :restored}

      :else
      {:state :dismissed})))

(def ^:private state-order
  {:needs-you 0 :recovering 1 :waiting 2 :owed 3 :restored 4 :dismissed 5})

(defn- entries [ws kind] (get-in ws [:entries kind]))

(def sample-size
  "How many of a row's failures, newest first, it reads to say which sessions
   failed and what the latest error was. A cause can fail hundreds of starts; its
   row counts all of them from their ids and reads only these."
  10)

(defn ^{:malli/schema [:=> [:cat [:sequential :string]] [:vector :string]]}
  row-sample
  "The ids a row reads records for: the newest `sample-size` of its failures."
  [ids]
  (vec (take-last sample-size (sort ids))))

(defn- ms->iso [ms] (when ms (str (java.time.Instant/ofEpochMilli ms))))

(defn- row [cause {:keys [failures-by-id ms-by-id owed recoveries] :as ctx}]
  (let [mine     (filter #(= cause (:cause %)) recoveries)
        ws       (latest-ws mine)
        ids      (sort (into (set (keep #(when (= cause (:cause %)) (:id %)) owed))
                             (when ws (:named ws))))
        sample   (keep failures-by-id (row-sample ids))
        at-of    (fn [id] (or (ms->iso (ms-by-id id)) (:at (failures-by-id id))))
        outcomes (mapcat :outcomes (entries ws :session-restored))
        diag     (last (entries ws :session-diagnosis))]
    (merge
     (cause-state cause ctx)
     {:cause     cause
      :ws-id     (:ws-id ws)
      :failures  (vec ids)
      :sessions  (vec (distinct (map #(str (:project %) "/" (:session %)) (reverse sample))))
      :message   (some-> (last sample) innermost-message)
      :first-at  (some-> (first ids) at-of)
      :last-at   (some-> (last ids) at-of)
      :runs      (count (:runs ws))
      :closed    (:closed ws)
      :diagnosis (when diag (select-keys diag [:verdict :cause :remedy :at]))
      :landed    (mapv #(select-keys % [:commit :url :title :at]) (entries ws :merged))
      :prs       (mapv #(select-keys % [:url :title]) (entries ws :pr-opened))
      :restores  (frequencies (map :outcome outcomes))})))

(defn- event-key
  "An event's place in the feed's order, unique and sortable as a string: its
   epoch millisecond, then its kind, then what names the record it came from."
  [ms kind ident]
  (format "%013d~%s~%s" (or ms 0) (name kind) ident))

(defn- failure-key [{:keys [id ms]}]
  (event-key ms :failure-kept id))

(defn- after? [k from]
  (or (nil? from) (neg? (compare k from))))

(defn ^{:malli/schema [:=> [:cat [:sequential :map] [:maybe :string]] [:vector :string]]}
  page-failure-ids
  "Which failure records the feed page after position `from` can show: the at
   most `page-size` newest failures past it, from the failure index — each
   failure's :id and the epoch millisecond its id carries — alone. Any failure
   on that page is one of these, so these are the only records it needs read."
  [failure-index from]
  (->> failure-index
       (map (fn [f] [(failure-key f) (:id f)]))
       (filter #(after? (first %) from))
       (sort-by first #(compare %2 %1))
       (take page-size)
       (mapv second)))

(defn- events
  "Recovery facts as feed events, each with its `:key`: every fact the recovery
   workstreams hold, and one per failure record handed in."
  [{:keys [failures recoveries ms-by-id]}]
  (let [evt (fn [ident m]
              (assoc m :key (event-key (or (when (= :failure-kept (:kind m)) (ms-by-id ident))
                                           (some-> (:at m) instant .toEpochMilli))
                                       (:kind m) ident)))]
    (concat
     (for [f failures]
       (evt (:id f)
            {:at (:at f) :kind :failure-kept :cause (:cause f) :failure (:id f)
             :session (str (:project f) "/" (:session f)) :verb (:verb f)
             :message (innermost-message f)}))
     (for [r recoveries
           :let [base {:cause (:cause r) :ws-id (:ws-id r)}
                 at-seq (fn [e] (str (:ws-id r) "#" (:seq e)))]
           e (concat
              (for [run (:runs r)]
                (evt (:id run)
                     (merge base {:at (first-at run) :kind :recovery-fired :run-id (:id run)
                                  :count (count (remove str/blank?
                                                        (str/split (str (-> run :event-payload :failures)) #",")))})))
              (for [run (:runs r) :when (#{:done :failed} (:state run))]
                (evt (:id run)
                     (merge base {:at (last-at run) :kind :recovery-ended :run-id (:id run)
                                  :state (:state run) :reason (-> run :error :reason)})))
              (for [d (entries r :session-diagnosis)]
                (evt (at-seq d) (merge base {:at (:at d) :kind :diagnosed :verdict (:verdict d) :text (:cause d)})))
              (for [x (entries r :session-restored)]
                (evt (at-seq x) (merge base {:at (:at x) :kind :restore
                                             :outcomes (frequencies (map :outcome (:outcomes x)))})))
              (for [b (entries r :blocker)]
                (evt (at-seq b) (merge base {:at (:at b) :kind :parked :text (:summary b)})))
              (for [m (entries r :merged)]
                (evt (at-seq m) (merge base {:at (:at m) :kind :landed :commit (:commit m)
                                             :url (:url m) :text (:title m)})))
              (for [p (entries r :pr-opened)]
                (evt (at-seq p) (merge base {:at (:at p) :kind :pr-opened :url (:url p) :text (:title p)})))
              (when-let [c (:closed r)]
                [(evt (:ws-id r) (merge base {:at (:at c) :kind :closed :outcome (:outcome c)}))]))]
       e))))

(defn ^{:malli/schema [:=> [:cat :map [:maybe :string]] :map]}
  feed-page
  "The page of the feed that follows position `from` — nil for the newest — in
   newest-first order, at most `page-size` events. `:next` is the position of the
   next older page, nil when this page reaches the oldest event. Following
   `:next` from the newest reaches every event exactly once.

   Failures are counted from `:failure-index` and shown from the records handed
   in, which must include every failure `page-failure-ids` names for `from`;
   older records handed in for other reasons do not change the page."
  [{:keys [failures failure-index] :as records} from]
  (let [index    (or failure-index
                     (map (fn [f] {:id (:id f) :ms (some-> (:at f) instant .toEpochMilli)}) failures))
        ms-by-id (into {} (map (juxt :id :ms)) index)
        after    (->> (events (assoc records :ms-by-id ms-by-id))
                      (filter #(after? (:key %) from))
                      (sort-by :key #(compare %2 %1)))
        page     (vec (take page-size after))
        total    (+ (count (remove #(= :failure-kept (:kind %)) after))
                    (count (filter #(after? (failure-key %) from) index)))]
    {:from   from
     :events page
     :next   (when (> total page-size) (:key (peek page)))}))

(defn ^{:malli/schema [:=> [:cat :map :any] :boolean]}
  shown?
  "Whether recovery workstream `r` is shown as of `now`: open, or closed within
   the window. A reader gathering what the overview needs reads the failures of
   these and no others."
  [r now]
  (or (not (:closed r))
      (boolean (since? (-> r :closed :at)
                       (.minus ^java.time.Instant now (java.time.Duration/ofDays window-days))))))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  overview
  "The session-recovery overview: counts, one row per cause worth showing, and the
   activity feed.

   `failures` are kept failure records, each carrying its :cause — at least every
   undischarged one, the `row-sample` of every shown recovery's named failures,
   and every one `page-failure-ids` names for the page; `failure-index` is every kept failure's
   :id and :ms, without its record. `recoveries` are the recovery source's
   readings of recovery workstreams, each carrying the ledger entries it holds
   under :entries by kind. `now` is an Instant. `feed-from` is the feed position
   to page from, nil for the newest.

   A cause is shown when it has an owed failure or an open recovery workstream,
   or when its latest recovery closed within the window. The feed is not
   windowed: every recovery fact is reachable a page at a time."
  [{:keys [failures failure-index recoveries now pacing feed-from]}]
  (let [owed    (sf/owed (vec failures) (vec recoveries))
        ctx     {:owed owed :recoveries (vec recoveries) :now now :pacing pacing
                 :failures-by-id (into {} (map (juxt :id identity)) failures)
                 :ms-by-id (into {} (map (juxt :id :ms))
                                 (or failure-index
                                     (map (fn [f] {:id (:id f) :ms (some-> (:at f) instant .toEpochMilli)})
                                          failures)))}
        causes  (distinct
                 (concat (map :cause owed)
                         (map :cause (remove :closed recoveries))
                         (map :cause (filter #(and (:closed %) (shown? % now)) recoveries))))
        rows    (->> causes
                     (map #(row % ctx))
                     (sort-by (juxt #(state-order (:state %))
                                    #(- (or (some-> (or (-> % :closed :at) (:last-at %)) instant .toEpochMilli) 0))))
                     vec)
        n       (fn [s] (count (filter #(= s (:state %)) rows)))]
    {:counts   {:owed       (count owed)
                :recovering (n :recovering)
                :needs-you  (n :needs-you)
                :waiting    (n :waiting)
                :restored   (n :restored)}
     :causes   rows
     :feed     (feed-page {:failures failures :failure-index failure-index :recoveries recoveries}
                          feed-from)}))
