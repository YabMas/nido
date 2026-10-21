(ns nido.coordinator.record.generation
  "Which generation of a workstream's design an entry belongs to, read off the ledger.

   A :generation entry opens a generation: every entry from it to the next one belongs to it, and
   a ledger holding none is wholly its first. Intents, baselines, their reviews and the retractions
   of these belong to none and are read in every generation — a re-cut changes the design, never
   what the arc is for or the status quo it was surveyed against, so a new generation re-enters at
   the design round with the workstream's intent and baseline.

   Every reader that must not see an earlier generation's designs reads through here rather than
   cutting the ledger itself: the append checks, standing and position, and the seats above them.

   The mark a design puts on an element — :defines-generation — is all this level knows of why an
   element matters: that changing it makes a new generation. How a marked element is carried is
   decided above."
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.report :as report]
   [nido.platform.io :as io]))

(def ^:private spanless-kinds
  "Kinds that belong to no generation."
  #{:intent :baseline :baseline-review})

(defn- row-at [entries n]
  (some #(when (= n (:seq %)) %) entries))

(defn- spanless?
  "Whether the index row belongs to no generation: an intent, a baseline, a baseline's review, or
   a retraction of one of these — read off the target its row mirrors."
  [entries row]
  (or (contains? spanless-kinds (:kind row))
      (and (= :retraction (:kind row))
           (contains? spanless-kinds (:kind (row-at entries (:retracts row)))))))

(defn- openings
  "The seqs of the :generation rows, oldest first."
  [entries]
  (->> entries (filter #(= :generation (:kind %))) (map :seq) sort vec))

(defn- generation-at
  "The generation an entry at `n` would belong to if it belonged to one."
  [entries n]
  (inc (count (filter #(<= % n) (openings entries)))))

(defn ^{:malli/schema [:=> [:cat [:sequential :map] :int] [:maybe :int]]}
  generation-of
  "The generation the entry at `seq` belongs to — 1 on a ledger with no :generation entry, and for
   a :generation entry the one it opens — or nil for an intent, a baseline, a baseline review or a
   retraction of one of these, and for a seq the ledger does not hold."
  [entries seq]
  (when-let [row (row-at entries seq)]
    (when-not (spanless? entries row)
      (generation-at entries seq))))

(defn ^{:malli/schema [:=> [:cat [:sequential :map]] :int]}
  current
  "The number of the current generation: 1 until a :generation entry opens another."
  [entries]
  (inc (count (openings entries))))

(defn- span-of
  "The index rows of generation `g`, with every entry that belongs to no generation."
  [entries g]
  (filterv #(or (spanless? entries %) (= g (generation-at entries (:seq %)))) entries))

(defn ^{:malli/schema [:=> [:cat [:sequential :map]] [:sequential :map]]}
  current-span
  "The index rows of the current generation, with every entry that belongs to no generation."
  [entries]
  (span-of entries (current entries)))

(defn ^{:malli/schema [:=> [:cat [:sequential :map]] [:maybe :map]]}
  opened-by
  "The :generation row that opened the current generation, or nil for the first."
  [entries]
  (when-let [n (last (openings entries))]
    (row-at entries n)))

(defn- entry-at
  "The entry at `n` on `w`, through the read contract — nil when absent or unreadable."
  [w n]
  (when-let [row (row-at (:entries w) n)]
    (try (report/parse-event (:kind row)
                             (io/read-edn (str (fs/path (cstate/workstream-dir (:project w) (:id w))
                                                        (:file row)))))
         (catch Throwable _ nil))))

(defn- governing
  "The seq of the design whose plan governs over `rows`: the newest phase gate's design while no
   design follows the gate, else the newest design — or nil when the rows hold none."
  [rows]
  (let [gate   (->> rows (filter #(= :phase-gate (:kind %))) (sort-by :seq) last)
        newest (->> rows (filter #(= :design (:kind %))) (map :seq) (reduce max 0))]
    (cond (and gate (> (:seq gate) newest)) (:under gate)
          (pos? newest)                     newest)))

(defn- paused-on
  "The design a :between-phases close names, when the newest status row of `w` is that close."
  [w]
  (let [newest (last (filter #(#{:closed :reopened :phase-gate} (:kind %)) (:entries w)))]
    (when (= :closed (:kind newest))
      (let [close (entry-at w (:seq newest))]
        (when (= :between-phases (:outcome close))
          (get-in close [:design :seq]))))))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :int]]}
  current-design
  "The seq of the current span's design whose plan governs the workstream: the one its
   :between-phases close names, else the one the span's newest phase gate is under while no design
   follows that gate, else the span's newest design — or nil when the span holds none. The append
   checks take the current design from here and standing gives it as the governing design, so the
   two cannot disagree."
  [w]
  (or (paused-on w) (governing (current-span (:entries w)))))

(defn ^{:malli/schema [:=> [:cat :map :int] [:maybe :int]]}
  closed-design
  "The seq of the design that governed the generation the :generation entry at `seq` closes, read
   over that generation's span as current-design reads the current one — or nil when it held none.
   A generation is opened only while the workstream is not paused between phases, so no close is
   read."
  [w seq]
  (let [entries (:entries w)]
    (when (= :generation (:kind (row-at entries seq)))
      (governing (span-of entries (dec (generation-at entries seq)))))))

(defn ^{:malli/schema [:=> [:cat :map] [:set :string]]}
  defining-ids
  "The ids of a design's elements marked generation-defining; empty for a design that marks none.
   A design states every element it holds over its baseline, so its own model is the whole
   answer."
  [design]
  (into #{} (comp (filter :defines-generation) (map :id)) (get-in design [:model :elements])))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :int]]}
  fixing-design
  "The seq of the current span's design that fixes its generation-defining elements as they stand:
   the first design marking any element that is cleared or approved, in the order those clearances
   and approvals were written — nil while none is. What its marked elements hide is what the
   generation's later designs are held to keep."
  [w]
  (some (fn [row]
          (let [d (get-in (entry-at w (:seq row)) [:design :seq])]
            (when (some->> d (entry-at w) defining-ids not-empty) d)))
        (->> (current-span (:entries w))
             (filter #(#{:design-cleared :design-approved} (:kind %)))
             (sort-by :seq))))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe [:set :string]]]}
  fixed-ids
  "The generation-defining ids the current generation fixes: those its :generation entry carries,
   or in a first generation those of the design marking any element that is first cleared or
   approved — nil while none is fixed. The append checks hold a generation's designs to these and
   the seats are read from them, so the two start from one event."
  [w]
  (if-let [opened (opened-by (:entries w))]
    (some-> (entry-at w (:seq opened)) :ids set)
    (some->> (fixing-design w) (entry-at w) defining-ids)))
