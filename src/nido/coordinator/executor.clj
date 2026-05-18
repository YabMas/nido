(ns nido.coordinator.executor
  "Slot-based scheduler with priority queue. Babashka-compatible:
   uses a Clojure sorted-set keyed by [(- priority) received-at run-id]
   for the wait queue (java.util.concurrent.PriorityBlockingQueue isn't
   available in bb), and `future` per in-flight Run. All mutation goes
   through `locking` on `lock` so the daemon's reap+promote tick is
   atomic."
  (:require
   [nido.coordinator.clock :as clock]))

(defn- slot-cmp
  "Sort by descending priority, then by received-at ascending (FIFO ties),
   then by run-id (total ordering for set semantics)."
  [a b]
  (compare [(- (:priority a)) (:received-at a) (:run-id a)]
           [(- (:priority b)) (:received-at b) (:run-id b)]))

(defonce ^:private !state
  (atom {:queue     (sorted-set-by slot-cmp)
         :in-flight {}
         :cap       1}))

(defonce ^:private lock (Object.))

(defn configure!
  "Set the global concurrency cap. Pure config — does not start threads."
  [{:keys [global-cap]}]
  (swap! !state assoc :cap global-cap))

(defn clear!
  "Test-only: reset queue + in-flight. Does not cancel in-flight futures.
   Does not reset :cap — call configure! separately if needed."
  []
  (swap! !state assoc :queue (sorted-set-by slot-cmp) :in-flight {}))

(defn submit!
  "Add a Run to the wait queue. run-id is opaque to the executor; priority
   is an int (higher pops first). Idempotent: re-submitting the same
   run-id is a no-op (prevents duplicate work if the daemon re-processes
   a queue file)."
  [run-id priority]
  (locking lock
    (swap! !state update :queue
           (fn [q]
             (if (or (some #(= run-id (:run-id %)) q)
                     (contains? (:in-flight @!state) run-id))
               q
               (conj q {:run-id      run-id
                        :priority    priority
                        :received-at (clock/now-iso)}))))))

(defn- reap-done [in-flight]
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
    {} in-flight))

(defn tick!
  "Called by the daemon every poll. Reaps finished futures, then promotes
   up to (cap - in-flight) queued Runs into new futures. on-spawn is
   `(fn [run-id])` — typically a wrapper around the legacy run-now! body."
  [on-spawn]
  (locking lock
    (swap! !state update :in-flight reap-done)
    ;; Re-read after reap to get the updated in-flight count.
    (let [{:keys [queue in-flight cap]} @!state
          free  (max 0 (- cap (count in-flight)))
          picks (->> queue (take free) vec)]
      (when (seq picks)
        (let [new-q (reduce disj queue picks)
              new-f (into in-flight
                          (for [{:keys [run-id]} picks]
                            [run-id (future (try (on-spawn run-id)
                                                  (catch Throwable t t)))]))]
          (swap! !state assoc :queue new-q :in-flight new-f))))))

(defn snapshot
  "Read-only view for the TUI. No locking — reads a consistent atom value."
  []
  (let [{:keys [queue in-flight cap]} @!state]
    {:cap       cap
     :in-flight (count in-flight)
     :queued    (count queue)
     :queue     (mapv :run-id queue)}))
