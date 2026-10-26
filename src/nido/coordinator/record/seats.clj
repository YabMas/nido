(ns nido.coordinator.record.seats
  "A workstream as its seats, read off its records.

   A seat is one of the ids the current generation fixes — generation-defining elements of its
   design, as record-generation reads them — or, while the generation fixes none, the one whole
   seat holding its whole design, whose id is the generation's own number and so never an
   element's. A seat is never stored: no entry or file names one, so a re-cut that fixes other ids
   has other seats, and every generation has at least one. Which elements are seats is the
   design's mark; nothing here names a sort of element, and no caller supplies a selection.

   A tension is open until the participant it is addressed to settles it with an :accord, or the
   one who raised it withdraws it with a :withdrawal — derived from the span's entries, never a
   stored flag. It is addressed to a seat this reading answers or to the conductor by role. One
   addressed to the generation's whole seat after the generation fixed ids is the conductor's, the
   whole seat being gone; an addressee that is neither is nobody's, never the conductor's by
   default."
  (:require
   [nido.coordinator.record.generation :as generation]
   [nido.coordinator.record.standing :as standing]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.report.model :as model]))

(defn- span-entries
  "The current span's parsed entries of `kind`, oldest first."
  [project ws-id w kind]
  (let [span (into #{} (map :seq) (generation/current-span (:entries w)))]
    (filterv #(span (:seq %)) (ws/entries-of project ws-id kind))))

(defn- same-participant?
  "Whether two writers are the same participant: the conductor, or the same seat — whoever
   occupies it, since a replaced occupant speaks for the seat it took over."
  [a b]
  (and (= (:as a) (:as b)) (= (:seat a) (:seat b))))

(defn- addressee
  "The participant a tension is addressed to: the seat it names when the generation has that
   seat; the conductor when it names the conductor, or the generation's whole seat once the
   generation fixes ids; else nobody, whom no accord can come from."
  [seat-ids generation to]
  (cond (contains? seat-ids to)              {:as :seat :seat to}
        (or (= :conductor to) (= generation to)) {:as :conductor}
        :else                                {:as :nobody :seat to}))

(defn- read-tensions
  "Every tension of the current span with its addressee resolved and its state derived."
  [project ws-id w seat-ids]
  (let [generation  (generation/current (:entries w))
        accords     (span-entries project ws-id w :accord)
        withdrawals (span-entries project ws-id w :withdrawal)
        citing      (fn [entries t] (filter #(= (:seq t) (get-in % [:tension :seq])) entries))]
    (mapv (fn [t]
            (let [to      (addressee seat-ids generation (:to t))
                  accord  (first (filter #(same-participant? to (:by %)) (citing accords t)))
                  withdrawn (first (filter #(same-participant? (:by t) (:by %)) (citing withdrawals t)))
                  settled (first (sort-by :seq (remove nil? [accord withdrawn])))]
              (cond-> {:seq (:seq t) :by (:by t) :to (if (= :conductor (:as to)) :conductor (:seat to))
                       :about (:about t) :statement (:statement t)
                       :state (cond (nil? settled) :open
                                    (= settled accord) :accorded
                                    :else :withdrawn)}
                settled (assoc :settled-by (:seq settled)))))
          (span-entries project ws-id w :tension))))

(defn- seat-ids
  "The current generation's seat ids: those it fixes, or its own number for its whole seat."
  [w]
  (or (not-empty (generation/fixed-ids w)) #{(generation/current (:entries w))}))

(defn- whole?
  "Whether workstream `w`'s current generation is carried by a whole seat — it fixes no ids."
  [w]
  (empty? (generation/fixed-ids w)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:sequential :map]]}
  tensions
  "The current generation's tensions, oldest first, each as {:seq :by :to :about :statement
   :state}, :to a seat id or :conductor and :state :open, :accorded or :withdrawn — with
   :settled-by naming the entry that settled a settled one. Empty for a workstream that is not
   there."
  [project ws-id]
  (if-let [w (ws/read-ws project ws-id)]
    (read-tensions project ws-id w (seat-ids w))
    []))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:sequential :map]]}
  seats
  "The current generation's seats, sorted by id, each as {:id :interface :tensions}: :interface
   is what the governing design, laid over the baseline it cites, says that element offers — nil
   while no design of the generation states the element — and :tensions are the open ones
   addressed to the seat. While the generation fixes no ids, its whole seat alone, :whole true, its
   id the generation's number and its interface the governing design's shape — nil before a
   design. Empty only for a workstream that is not there."
  [project ws-id]
  (if-let [w (ws/read-ws project ws-id)]
    (let [ids    (seat-ids w)
          design (standing/governing-design project ws-id)
          open   (group-by :to (filter #(= :open (:state %)) (read-tensions project ws-id w ids)))]
      (if (whole? w)
        (let [id (first ids)]
          [{:id id :whole true :interface (:shape design) :tensions (get open id [])}])
        (let [baseline (some->> (get-in design [:baseline :seq]) (ws/entry-at-seq project ws-id))
              elements (when design
                         (into {} (map (juxt :id identity))
                               (:elements (model/overlay (:model baseline) (:model design)))))]
          (mapv (fn [id] {:id id :interface (get-in elements [id :interface]) :tensions (get open id [])})
                (sort ids)))))
    []))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:maybe :map]]}
  inherited
  "What the current generation inherited from the one before it — the :generation entry that
   opened it, as written: the re-cut it opens from, the person's words, the ids it fixes, the
   revision it builds from, the claims seen falsified and the tensions carried by value. nil in a
   first generation."
  [project ws-id]
  (when-let [w (ws/read-ws project ws-id)]
    (some->> (generation/opened-by (:entries w)) :seq (ws/entry-at-seq project ws-id))))
