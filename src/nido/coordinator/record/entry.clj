(ns nido.coordinator.record.entry
  "What a ledger entry says: its content as the core holds it, fetched by seq, and that content
   parsed through the read contract. An entry is never rewritten, so what was read once answers
   every later read of it in this process.

   Every reader of an entry's content goes through here — the ledger's own readings
   (`nido.coordinator.record.workstream`), the generations it is cut into, and the surfaces."
  (:require [clojure.edn :as edn]
            [nido.coordinator.report :as report]
            [nido.core.client :as client]
            [nido.platform.core :as core]
            [nido.platform.io :as io]))

(def ^:private texts
  "[nido-root ws-key seq] → that entry's content. Bounded, because a long-lived process reads
   many ledgers: as `io/cache-store` bounds it, at twice `cap`."
  (atom {}))

(def ^:private parsed-cache
  "[nido-root ws-key seq kind] → that entry parsed through the read contract, bounded as `texts`
   is. A failure is never kept."
  (atom {}))

(def ^:private cap 8192)

(defn- text-key [w n] [(str (core/nido-root)) (client/ws-key (:project w) (:id w)) n])

(defn ^{:malli/schema [:=> [:cat :any :int] [:maybe :string]]}
  text
  "The content of the entry at `seq-n` on the workstream record `w` (a record `read-ws` answers),
   as it was written — EDN for a typed kind, markdown for any other — or nil when its ledger holds
   no such entry. A miss asks the core for every entry of `w` not yet read, in one request, since
   a reader of one entry of a ledger is usually about to read its neighbours."
  [w seq-n]
  (when (some #(= seq-n (:seq %)) (:entries w))
    (or (io/cache-lookup texts (text-key w seq-n))
        (let [missing (vec (remove #(io/cache-lookup texts (text-key w %)) (map :seq (:entries w))))
              got     (client/value! (client/entry-contents (client/ws-key (:project w) (:id w)) missing)
                                     :entries)]
          (doseq [[n t] got] (io/cache-store texts cap (text-key w n) t))
          (get got seq-n)))))

(defn ^{:malli/schema [:=> [:cat :any [:map-of :int :string]] :nil]}
  prime!
  "Keep `contents` (seq → content), read for the workstream record `w` in some bulk read, as what
   `text` answers for those entries — so a reader folding many ledgers asks the core for them
   once, never ledger by ledger."
  [w contents]
  (doseq [[n t] contents] (io/cache-store texts cap (text-key w n) t))
  nil)

(defn ^{:malli/schema [:=> [:cat :any :int] [:maybe :map]]}
  parsed
  "The entry at `seq-n` on `w` parsed through the READ contract and stamped with its :seq and :at,
   or nil when it is absent or does not parse — a reader following a citation has to be able to
   find nothing without the caller crashing."
  [w seq-n]
  (when-let [row (some #(when (= seq-n (:seq %)) %) (:entries w))]
    (try (let [k (conj (text-key w seq-n) (:kind row))]
           (-> (or (io/cache-lookup parsed-cache k)
                   (when-let [t (text w seq-n)]
                     (io/cache-store parsed-cache cap k (report/parse-event (:kind row) (edn/read-string t)))))
               (assoc :seq (:seq row) :at (:at row))))
         (catch Throwable _ nil))))
