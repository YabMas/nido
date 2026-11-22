(ns nido.core.writer
  "The single writer. One platform thread takes commands from one queue, decides each against
   committed state, commits a bounded batch as one store transaction, and only then answers the
   batch's callers and hands what changed to `:publish`. Nothing else writes the store.

   Order. A command marked `:input?` (a person's input on a surface) is taken ahead of every
   background command still queued; within a class, commands are taken in arrival order. A
   batch holds at most `:max-batch` commands, so input waits on at most the batch in progress
   and earlier input.

   Keys. Every command carries the key its sender chose. A key already committed is answered
   with the outcome committed under it and is not decided again; a key repeated within one batch
   is decided once. A refusal is an outcome too, committed under its key with no events.

   Failure. If a batch's commit throws, nothing in it was written and each of its commands is
   retried as a batch of its own, so one bad command cannot sink the others. A command whose own
   commit throws is answered {:error ...} and nothing is recorded under its key, so its sender
   may resend it.

   Idle, the thread blocks on the queue and executes nothing."
  (:require [nido.core.domain :as d]
            [nido.core.store :as store])
  (:import [java.util.concurrent PriorityBlockingQueue]
           [java.util.concurrent.atomic AtomicLong]))

(def ^:private stop-class
  "Orders the stop request ahead of everything still queued."
  -1)

(defn- queue-order [a b]
  (let [c (compare (:class a) (:class b))]
    (if (zero? c) (compare (:seq a) (:seq b)) c)))

(defn- malformed
  "Why `envelope` cannot be decided at all, or nil."
  [{:keys [key ws command]}]
  (cond
    (not (and (string? key) (seq key)))     :key-missing
    (not (and (string? ws) (seq ws)))       :workstream-missing
    (not (keyword? (:command command)))     :command-missing))

;; ── deciding a batch ─────────────────────────────────────────────────────────

(defn- decide-batch
  "Decide `pendings` in order against committed state, carrying each workstream's state forward
   through the batch. Answers {:entries [store batch entries] :answers [[pending outcome] ...]}."
  [store decide pendings]
  (dissoc
   (reduce
    (fn [{:keys [states seen] :as acc} {:keys [envelope] :as p}]
      (let [{:keys [key ws command]} envelope]
        (if-let [o (or (get seen key) (store/command-outcome store key))]
          (update acc :answers conj [p o])
          (let [st (or (get states ws) (store/state store ws))
                [st' evs refused] (decide st command)
                o (if refused {:refused refused} {:events (vec evs)})]
            (-> acc
                (assoc-in [:states ws] st')
                (assoc-in [:seen key] o)
                (update :entries conj {:key key :ws ws :events (vec evs) :outcome o :state st'})
                (update :answers conj [p o]))))))
    {:states {} :seen {} :entries [] :answers []}
    pendings)
   :states :seen))

(defn- changes
  "What a committed batch changed, one message per workstream at its last commit in the batch,
   in commit order: {:ws :pos :state}."
  [entries positions]
  (->> (map (fn [e pos] (when pos {:ws (:ws e) :pos pos :state (:state e)})) entries positions)
       (remove nil?)
       (reduce (fn [m c] (assoc m (:ws c) c)) {})
       vals
       (sort-by :pos)
       vec))

(defn- report! [what ^Throwable t]
  (binding [*out* *err*]
    (println "nido.core.writer:" what "-" (.getMessage t))))

(defn- run-batch!
  "Decide, commit, then answer and publish: nothing leaves before the commit is durable."
  [{:keys [store decide publish]} pendings]
  (let [{:keys [entries answers]} (decide-batch store decide pendings)
        positions (if (seq entries) (store/commit! store entries) [])]
    (doseq [[p o] answers] (deliver (:answer p) o))
    (when-let [cs (seq (changes entries positions))]
      (try (publish (vec cs))
           (catch Throwable t (report! "publish failed" t))))))

(defn- run-batch-or-each!
  [w pendings]
  (try
    (run-batch! w pendings)
    (catch Throwable t
      (if (next pendings)
        (doseq [p pendings] (run-batch-or-each! w [p]))
        (do (report! (str "command " (pr-str (get-in (first pendings) [:envelope :key])) " not committed") t)
            (deliver (:answer (first pendings))
                     {:error {:message (.getMessage t) :data (ex-data t)}}))))))

;; ── the thread ───────────────────────────────────────────────────────────────

(defn- take-batch
  "Block for one pending, then take what else is queued up to `max-batch`. Answers nil when the
   first thing taken is the stop request; a stop request met later is put back for the next take."
  [^PriorityBlockingQueue q max-batch]
  (let [first-p (.take q)]
    (when-not (= stop-class (:class first-p))
      (loop [batch [first-p]]
        (if (>= (count batch) max-batch)
          batch
          (if-let [p (.poll q)]
            (if (= stop-class (:class p))
              (do (.put q p) batch)
              (recur (conj batch p)))
            batch))))))

(defn- run-loop [{:keys [queue max-batch stopped] :as w}]
  (loop []
    (if-let [batch (take-batch queue max-batch)]
      (do (run-batch-or-each! w batch) (recur))
      (do (reset! stopped true)
          (loop []
            (when-let [p (.poll ^PriorityBlockingQueue queue)]
              (when-let [a (:answer p)] (deliver a {:error :writer-stopped}))
              (recur)))))))

;; ── interface ────────────────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :map :map] :map]} start
  "Start the writer over `store` (one `nido.core.store/open` handle, which it alone then uses).

   Options:
     :max-batch  commands per batch, at least 1 (default 64)
     :publish    called on the writer thread after each durable commit with the batch's
                 changes, [{:ws :pos :state} ...] in commit order; it must not block (default no-op)
     :decide     (fn [state command]) -> [state events refusal] (default `nido.core.domain/step`)"
  [store {:keys [max-batch publish decide] :or {max-batch 64 publish (fn [_]) decide d/step}}]
  (let [w {:store     store
           :decide    decide
           :publish   publish
           :max-batch (max 1 max-batch)
           :queue     (PriorityBlockingQueue. 64 queue-order)
           :seq       (AtomicLong.)
           :stopped   (atom false)}
        t (doto (Thread. ^Runnable #(run-loop w) "nido-core-writer") (.setDaemon true) (.start))]
    (assoc w :thread t)))

(defn ^{:malli/schema [:=> [:cat :map :map] :any]} submit!
  "Queue `envelope` {:key :ws :command :input?} and answer a promise of its outcome: {:events [...]}
   when admitted, {:refused {...}} when not, or {:error ...} when it could not be decided or
   committed. The promise is delivered only after the outcome is durable, or never recorded."
  [writer envelope]
  (let [p (promise)]
    (cond
      (malformed envelope) (deliver p {:error (malformed envelope)})
      @(:stopped writer)   (deliver p {:error :writer-stopped})
      :else (.put ^PriorityBlockingQueue (:queue writer)
                  {:class    (if (:input? envelope) 0 1)
                   :seq      (.incrementAndGet ^AtomicLong (:seq writer))
                   :envelope envelope
                   :answer   p}))
    p))

(defn ^{:malli/schema [:=> [:cat :map] :nil]} stop!
  "Finish the batch in progress, answer every command still queued {:error :writer-stopped}, and
   wait for the thread to end. The store stays open; its owner closes it."
  [writer]
  (.put ^PriorityBlockingQueue (:queue writer) {:class stop-class :seq 0})
  (.join ^Thread (:thread writer))
  nil)
