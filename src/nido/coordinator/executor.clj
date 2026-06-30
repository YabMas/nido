(ns nido.coordinator.executor
  "Slot-based scheduler with priority queue. Babashka-compatible:
   uses a Clojure sorted-set keyed by [uncapped-flag (- priority) received-at run-id]
   for the wait queue (java.util.concurrent.PriorityBlockingQueue isn't
   available in bb), and `future` per in-flight Run. All mutation goes
   through `locking` on `lock` so the daemon's reap+promote tick is
   atomic.

   In-flight tracking uses two separate maps:
     :in-flight-capped   — Runs subject to the global cap
     :in-flight-uncapped — Runs that bypass the cap (never count toward free slots)
   This lets tick! compute (cap - count-capped-in-flight) without
   iterating or annotating the combined map."
  (:require
   [nido.coordinator.clock :as clock]))

(defn- slot-cmp
  "Sort by uncapped-first (uncapped=0, capped=1), then descending priority,
   then by received-at ascending (FIFO ties), then by run-id (total ordering
   for set semantics). Uncapped items sort before capped at equal priority so
   they are always candidates for promotion first."
  [a b]
  (compare [(if (:uncapped? a) 0 1) (- (:priority a)) (:received-at a) (:run-id a)]
           [(if (:uncapped? b) 0 1) (- (:priority b)) (:received-at b) (:run-id b)]))

(defonce ^:private !state
  (atom {:queue              (sorted-set-by slot-cmp)
         :in-flight-capped   {}
         :in-flight-uncapped {}
         :cap                1}))

(defonce ^:private lock (Object.))

(defn configure!
  "Set the global concurrency cap. Pure config — does not start threads."
  [{:keys [global-cap]}]
  (swap! !state assoc :cap global-cap))

(defn clear!
  "Test-only: reset queue + in-flight. Does not cancel in-flight futures.
   Does not reset :cap — call configure! separately if needed."
  []
  (swap! !state assoc
         :queue              (sorted-set-by slot-cmp)
         :in-flight-capped   {}
         :in-flight-uncapped {}))

(defn submit!
  "Add a Run to the wait queue. run-id is opaque to the executor; priority
   is an int (higher pops first). uncapped? (default false) marks the Run
   as exempt from the global cap — it always promotes regardless of how many
   capped Runs are in flight, and does not consume a cap slot.
   trigger is the trigger keyword that spawned this Run (used for per-trigger
   in-flight gating). max-in-flight is the per-trigger cap (nil = uncapped
   per-trigger; gated only by the global cap).
   Idempotent: re-submitting the same run-id is a no-op."
  ([run-id priority] (submit! run-id priority false nil nil))
  ([run-id priority uncapped?] (submit! run-id priority uncapped? nil nil))
  ([run-id priority uncapped? trigger max-in-flight]
   (locking lock
     (swap! !state update :queue
            (fn [q]
              (let [{:keys [in-flight-capped in-flight-uncapped]} @!state]
                (if (or (some #(= run-id (:run-id %)) q)
                        (contains? in-flight-capped run-id)
                        (contains? in-flight-uncapped run-id))
                  q
                  (conj q {:run-id        run-id
                           :priority      priority
                           :uncapped?     (boolean uncapped?)
                           :trigger       trigger
                           :max-in-flight max-in-flight
                           :received-at   (clock/now-iso)}))))))))

(defn- reap-done-map
  "Remove futures that have completed from a {run-id → future} map.
   Dereferences completed futures to surface any stored exceptions to stderr."
  [m]
  (reduce-kv
    (fn [acc rid f]
      (if (future-done? f)
        (do (try @f
              (catch Throwable t
                (.println ^java.io.PrintWriter *err*
                          (str "nido coordinator: executor reaper caught exception for run "
                               rid ": " (.getMessage t)))))
            acc)
        (assoc acc rid f)))
    {} m))

(defn tick!
  "Called by the daemon every poll. Reaps finished futures, then:
   - Promotes uncapped Runs bypassing the global cap but still subject to
     per-trigger :max-in-flight (a nil :max-in-flight means promote always).
   - Promotes capped Runs up to (cap - count-in-flight-capped) slots,
     further constrained by per-trigger :max-in-flight when set.
   Both capped and uncapped picks share the same per-trigger `used` tally,
   so a trigger mixing capped+uncapped runs cannot exceed its :max-in-flight.
   on-spawn is `(fn [run-id])` — typically a wrapper around run-blocking!.
   in-flight-by-trigger is {trigger -> n}, the current in-progress run count
   per trigger (from persisted run state). A run is promoted only while its
   trigger's in-flight (the map value plus picks already chosen this tick) is
   below its :max-in-flight. Runs with nil :max-in-flight obey only the global
   cap (capped) or are always promoted (uncapped)."
  ([on-spawn in-flight-by-trigger]
   (locking lock
     (swap! !state #(-> %
                        (update :in-flight-capped   reap-done-map)
                        (update :in-flight-uncapped reap-done-map)))
     (let [{:keys [queue in-flight-capped in-flight-uncapped cap]} @!state
           uncapped (filterv :uncapped? queue)
           capped   (filterv (complement :uncapped?) queue)
           free     (max 0 (- cap (count in-flight-capped)))
           ;; gate helper: walk candidates, picking each while its trigger's
           ;; running count (seeded from in-flight-by-trigger, grown by picks
           ;; chosen this tick) is below :max-in-flight. nil mif → always pick.
           ;; `slots` bounds capped picks by free global slots; uncapped passes
           ;; slots=∞ so it is bounded ONLY by per-trigger mif (own budget).
           gate     (fn [cands slots used]
                      (loop [cs cands, slots slots, used used, acc []]
                        (if (or (zero? slots) (empty? cs))
                          [acc used]
                          (let [c   (first cs)
                                t   (:trigger c)
                                mif (:max-in-flight c)
                                cur (get used t (get in-flight-by-trigger t 0))]
                            (if (or (nil? mif) (< cur mif))
                              (recur (rest cs) (dec slots)
                                     (assoc used t (inc cur)) (conj acc c))
                              (recur (rest cs) slots used acc))))))
           ;; uncapped first (own budget, slots effectively unbounded), then
           ;; capped within free global slots, sharing the same `used` tally so
           ;; a trigger that mixes capped + uncapped runs can't exceed its mif.
           [picks-uncapped used1] (gate uncapped Long/MAX_VALUE {})
           [picks-capped   _]     (gate capped free used1)
           picks (concat picks-uncapped picks-capped)]
       (when (seq picks)
         (let [new-q        (reduce disj queue picks)
               spawn-future (fn [rid] (future (try (on-spawn rid) (catch Throwable t t))))
               new-uncapped (into in-flight-uncapped
                                  (for [{:keys [run-id]} (filter :uncapped? picks)]
                                    [run-id (spawn-future run-id)]))
               new-capped   (into in-flight-capped
                                  (for [{:keys [run-id]} (remove :uncapped? picks)]
                                    [run-id (spawn-future run-id)]))]
           (swap! !state assoc
                  :queue new-q :in-flight-capped new-capped :in-flight-uncapped new-uncapped)))))))

(defn snapshot
  "Read-only view for the TUI. No locking — reads a consistent atom value.
   :in-flight is the aggregate count of capped + uncapped in-flight Runs.
   This may exceed :cap when uncapped Runs are active — that's intentional
   and correctly signals 'more in flight than the cap allows because some
   are uncapped'."
  []
  (let [{:keys [queue in-flight-capped in-flight-uncapped cap]} @!state]
    {:cap       cap
     :in-flight (+ (count in-flight-capped) (count in-flight-uncapped))
     :queued    (count queue)
     :queue     (mapv :run-id queue)}))
