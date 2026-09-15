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
   here pretends it is — only a record written in the model era is."
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
