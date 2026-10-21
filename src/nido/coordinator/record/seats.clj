(ns nido.coordinator.record.seats
  "A workstream as its seats, read off its records.

   A seat is one of the ids the current generation fixes — generation-defining elements of its
   design, as record-generation reads them — and is never stored: no entry or file names a seat,
   so a re-cut that fixes other ids has other seats, and a generation whose ids are not yet fixed
   has none. Which elements are seats is the design's mark; nothing here names a sort of element,
   and no caller supplies a selection.

   A tension is open until the participant it is addressed to settles it with an :accord, or the
   one who raised it withdraws it with a :withdrawal — derived from the span's entries, never a
   stored flag. A tension addressed to anything but a seat the generation has is addressed to the
   conductor, so a tension aimed at a seat a re-cut removed still reaches someone who can settle
   it."
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
   seat, else the conductor."
  [seat-ids to]
  (if (contains? seat-ids to) {:as :seat :seat to} {:as :conductor}))

(defn- read-tensions
  "Every tension of the current span with its addressee resolved and its state derived."
  [project ws-id w seat-ids]
  (let [accords     (span-entries project ws-id w :accord)
        withdrawals (span-entries project ws-id w :withdrawal)
        citing      (fn [entries t] (filter #(= (:seq t) (get-in % [:tension :seq])) entries))]
    (mapv (fn [t]
            (let [to      (addressee seat-ids (:to t))
                  accord  (first (filter #(same-participant? to (:by %)) (citing accords t)))
                  withdrawn (first (filter #(same-participant? (:by t) (:by %)) (citing withdrawals t)))
                  settled (first (sort-by :seq (remove nil? [accord withdrawn])))]
              (cond-> {:seq (:seq t) :by (:by t) :to (if (= :seat (:as to)) (:seat to) :conductor)
                       :about (:about t) :statement (:statement t)
                       :state (cond (nil? settled) :open
                                    (= settled accord) :accorded
                                    :else :withdrawn)}
                settled (assoc :settled-by (:seq settled)))))
          (span-entries project ws-id w :tension))))

(defn- seat-ids [w] (or (generation/fixed-ids w) #{}))

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
   addressed to the seat. Empty while the generation fixes no ids."
  [project ws-id]
  (if-let [w (ws/read-ws project ws-id)]
    (let [ids      (seat-ids w)
          design   (when (seq ids) (standing/governing-design project ws-id))
          baseline (some->> (get-in design [:baseline :seq]) (ws/entry-at-seq project ws-id))
          elements (when design
                     (into {} (map (juxt :id identity))
                           (:elements (model/overlay (:model baseline) (:model design)))))
          open     (group-by :to (filter #(= :open (:state %)) (read-tensions project ws-id w ids)))]
      (mapv (fn [id] {:id id :interface (get-in elements [id :interface]) :tensions (get open id [])})
            (sort ids)))
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
