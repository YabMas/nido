(ns nido.coordinator.report.model
  "The shared model of an area — its elements and the claims made about them — read out of a
   baseline or a design of any era.

   A record written in the model era carries its model. Every earlier record describes the same
   things in the shapes of its day: a baseline's modules, load-bearing properties and composition;
   a design's invariants. This reads them AS elements and claims, so a consumer asks for claims
   and never for which era wrote the record.

   What an older record cannot supply stays missing rather than invented. A load-bearing property
   is about no named element, so its claim's :about is empty; an invariant has no id, so its
   claim's :id is nil. A reading of an older record is therefore not a valid Claim, and nothing
   here pretends it is — only a record written in the model era is.

   Beside the readings, the derivations over current-era models that forking and merging need:
   `overlay`, the model a design leaves when laid over the baseline it cites, and `combine`, three
   such models put together by id. Both read models only, never a record's own fields, and
   `predates` says which records hold a model they can be given."
  (:require
   [nido.coordinator.report :as report]))

(defn- survey-elements
  "An older baseline's modules as elements. A module's id is its :id where it has one and its
   prose name before ids existed."
  [modules]
  (mapv (fn [{:keys [id module hides interface readings]}]
          (cond-> {:id (or id module) :sort :module}
            hides     (assoc :hides hides)
            interface (assoc :interface interface)
            (seq readings) (assoc :readings readings)))
        modules))

(defn- survey-claims
  "An older baseline's load-bearing properties, and its composition, as claims. The composition
   is a claim about every module the baseline names, under the id rounds already use for it."
  [load-bearing composition elements]
  (cond-> (mapv (fn [{:keys [id property falsified-by readings evidence drift]}]
                  (cond-> {:id id :about [] :statement property :evidence {:by :round}}
                    falsified-by   (assoc :falsified-by falsified-by)
                    (seq readings) (assoc :readings readings)
                    (seq evidence) (assoc :read-at evidence)
                    drift          (assoc :drift drift)))
                load-bearing)
    composition (conj {:id "composition" :about (mapv :id elements)
                       :statement composition :evidence {:by :round}})))

(defn- invariant-claims
  "An older design's invariants as claims: no id, no subjects, judged by a round — which is all
   an invariant ever was."
  [invariants]
  (mapv (fn [i] {:id nil :about [] :statement (:invariant (report/invariant i))
                 :evidence {:by :round}})
        invariants))

(defn ^{:malli/schema [:=> [:cat [:maybe :LedgerEvent]] [:maybe :Model]]}
  model
  "The model a baseline or design states: `{:elements :claims}`. The record's own model when it
   carries one; otherwise the same things read out of the shapes its era used. nil for anything
   that is neither.

   Decided by what the record CARRIES, not by its :format tag: the fields are what there is to
   read, and a record held without its tag — a design passed along as the map it parsed to — says
   the same things it said with one."
  [record]
  (cond
    (contains? record :model) (:model record)

    (or (contains? record :load-bearing) (contains? record :modules))
    (let [elements (survey-elements (:modules record))]
      {:elements elements
       :claims   (survey-claims (:load-bearing record) (:composition record) elements)})

    (contains? record :invariants)
    {:elements [] :claims (invariant-claims (:invariants record))}

    :else nil))

(defn ^{:malli/schema [:=> [:cat [:maybe :LedgerEvent]] [:vector :Claim]]}
  claims
  "A record's claims, whichever era wrote it. Empty for a record that states none."
  [record]
  (vec (:claims (model record))))

(defn ^{:malli/schema [:=> [:cat [:maybe :LedgerEvent]] [:vector :Element]]}
  elements
  "A record's elements, whichever era wrote it. Empty for a record that names none."
  [record]
  (vec (:elements (model record))))

(defn ^{:malli/schema [:=> [:cat :Model] [:map-of :string :Claim]]}
  claims-by-id
  "A model's claims keyed by id. Claims with no id — an older design's invariants — are not in
   it, because nothing can name them."
  [model]
  (into {} (keep #(when (:id %) [(:id %) %])) (:claims model)))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] [:maybe [:enum :shared-model :role-players]]]}
  predates
  "What `record` was written before, of the two things laying one model over another needs:
   `:shared-model` for a record carrying no model, nil included, and `:role-players` for a model
   holding a role that names no players. nil for a record written after both.

   The read contracts accept both, so an older record stays readable, and anything laying models
   over one another refuses them: over a role with no players, the other model could supply or drop
   players neither states, and a side removing one could not be read as a conflict."
  [record]
  (cond
    (not (contains? record :model))
    :shared-model

    (some #(and (= :role (:sort %)) (empty? (:plays %))) (get-in record [:model :elements]))
    :role-players))

(defn ^{:malli/schema [:=> [:cat :Model :Model] [:vector :string]]}
  undescribed-modules
  "The ids of the modules `design` adds to `baseline` — a module the baseline does not hold as a
   module — that do not say both what they hide and what the rest may assume of them, in the
   design's order.

   A design names a module its baseline already describes by id alone, and laid over the baseline
   it keeps that description. A module it adds has none to keep, so a baseline derived from the two
   would list a module that hides nothing, which no baseline may."
  [baseline design]
  (let [modules (into #{} (keep #(when (= :module (:sort %)) (:id %))) (:elements baseline))]
    (into []
          (comp (filter #(= :module (:sort %)))
                (remove #(contains? modules (:id %)))
                (remove #(and (:hides %) (:interface %)))
                (map :id))
          (:elements design))))

(defn ^{:malli/schema [:=> [:cat :Model :Model] [:vector :string]]}
  undescribed-strata
  "The ids of the strata `design` adds to `baseline` that do not say what they provide or carry a
   reading of their level, in the design's order.

   The same obligation `undescribed-modules` holds a module to, for the same reason: a baseline
   derived from the two would list a stratum that says nothing about itself, which no baseline may."
  [baseline design]
  (let [strata (into #{} (keep #(when (= :stratum (:sort %)) (:id %))) (:elements baseline))]
    (into []
          (comp (filter #(= :stratum (:sort %)))
                (remove #(contains? strata (:id %)))
                (remove #(and (:interface %) (some (fn [r] (= :stratified/level (:lens r))) (:readings %))))
                (map :id))
          (:elements design))))

(defn ^{:malli/schema [:=> [:cat [:maybe :Model]] [:vector :string]]}
  strata-of
  "The ids of the stratum elements `model` lists, in its order — the :strata a record carrying this
   model states. A record whose model is derived (a fork's first baseline, a merged design) states
   these rather than authoring its own list, so the two cannot disagree."
  [model]
  (into [] (comp (filter #(= :stratum (:sort %))) (map :id)) (:elements model)))

(defn ^{:malli/schema [:=> [:cat :Model :Model] :Model]}
  overlay
  "The model `design` leaves when laid over `baseline`, by id — a design's EFFECTIVE model.

   A design restates nothing it keeps. So an id the design neither states nor removes is carried
   unchanged, an id it states as removed is dropped, and an id it states is laid over the
   baseline's at that id: for an element of the same sort, what the design says wins and what it
   leaves unsaid is carried, so a design that only names a module keeps what that module hides; for
   an element whose sort the design changes, and for a claim, the design's is the one. Ids new to
   the design follow the baseline's, in the design's order.

   An id missing from a design is never read as a removal, and the result states no removals of
   its own, because it is laid over nothing."
  [baseline design]
  (let [lay (fn [base over gone restate]
              (let [over-by  (into {} (map (juxt :id identity)) over)
                    base-ids (into #{} (map :id) base)]
                (-> []
                    (into (comp (remove #(contains? gone (:id %)))
                                (map #(if-let [o (get over-by (:id %))] (restate % o) %)))
                          base)
                    (into (remove #(contains? base-ids (:id %))) over))))]
    {:elements (lay (:elements baseline) (:elements design)
                    (set (get-in design [:removed :elements]))
                    ;; what a role's players, or a module's secret, were is not carried onto an
                    ;; element that is no longer that sort
                    (fn [was restated] (if (= (:sort was) (:sort restated)) (merge was restated) restated)))
     :claims   (lay (:claims baseline) (:claims design)
                    (set (get-in design [:removed :claims])) (fn [_ restated] restated))}))

(defn- by-id [xs] (into {} (map (juxt :id identity)) xs))

(defn- combine-kind
  "One kind's entries combined three ways, in first-seen order: `{:kept [<entry>] :diverged [<id>]}`."
  [base parent child]
  (let [b (by-id base) p (by-id parent) c (by-id child)]
    (reduce (fn [acc id]
              (let [bv (get b id) pv (get p id) cv (get c id)
                    p? (not= pv bv)
                    c? (not= cv bv)]
                (cond
                  (and p? c? (not= pv cv)) (update acc :diverged conj id)
                  p?                       (cond-> acc pv (update :kept conj pv))
                  c?                       (cond-> acc cv (update :kept conj cv))
                  :else                    (cond-> acc bv (update :kept conj bv)))))
            {:kept [] :diverged []}
            (distinct (map :id (concat base parent child))))))

(defn ^{:malli/schema [:=> [:cat :Model :Model :Model] [:map [:model :Model] [:conflicts [:vector :Conflict]]]]}
  combine
  "Three effective models — the fork's `base`, the `parent`'s and the `child`'s — combined by id, as
   `{:model <combined> :conflicts [<conflict>]}`.

   Each side is read against the base, never against the other side's wording. An id whose content
   on a side equals the base's is unchanged there, whether or not that side's design restated it,
   and an id the base holds and a side does not is removed on that side. A side that alone changed
   an id wins; both making the same change is one change.

   What stands a conflict, each named by id:
     :element-diverged, :claim-diverged — both sides changed one id, differently;
     :subject-removed  — a combined claim about an element the combination no longer holds, or a
                         role naming such a player, by the claim or role and that element; and a
                         claim one side added or changed about a role one of whose players, as
                         any of the three models names them, the other side alone removed;
     :subject-restated — a claim one side added or changed about an element, or a role one of whose
                         players as any of the three models names them, the other side alone
                         restated, by the claim and that element. The
                         claim was stated against its subjects as they stood at the fork; a claim
                         both sides carry unchanged relies on nothing the other side restated.
   Laws are not its business. While a conflict stands the combined model leaves every diverged id
   out, and is not one to append."
  [base parent child]
  (let [elements (combine-kind (:elements base) (:elements parent) (:elements child))
        claims   (combine-kind (:claims base) (:claims parent) (:claims child))
        be       (by-id (:elements base))
        pe       (by-id (:elements parent))
        ce       (by-id (:elements child))
        bc       (by-id (:claims base))
        present  (into #{} (map :id) (:kept elements))
        diverged (set (:diverged elements))
        gone?    #(not (or (present %) (diverged %)))
        ;; `changer` alone moved element `id` off the base — restated or removed it — and `keeper`
        ;; left it where it was
        alone?   (fn [changer keeper id]
                   (and (not= (get changer id) (get be id))
                        (= (get keeper id) (get be id))))]
    {:model {:elements (:kept elements) :claims (:kept claims)}
     :conflicts
     (vec (distinct
           (concat
            (for [id (:diverged elements)] {:kind :element-diverged :names [id]})
            (for [id (:diverged claims)]   {:kind :claim-diverged :names [id]})
            (for [{:keys [id about]} (:kept claims) s about :when (gone? s)]
              {:kind :subject-removed :names [id s]})
            (for [{:keys [id plays]} (:kept elements) s plays :when (gone? s)]
              {:kind :subject-removed :names [id s]})
            (for [[stated-e stated-c other-e] [[pe (by-id (:claims parent)) ce]
                                               [ce (by-id (:claims child)) pe]]
                  [id claim] stated-c
                  :when (not= claim (get bc id))
                  subject (:about claim)
                  moved (distinct (cons subject
                                        (mapcat #(let [role (get % subject)]
                                                   (when (= :role (:sort role)) (:plays role)))
                                                [be pe ce])))
                  :when (alone? other-e stated-e moved)]
              {:kind  (if (get other-e moved) :subject-restated :subject-removed)
               :names [id moved]}))))}))
