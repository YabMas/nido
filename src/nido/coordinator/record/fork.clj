(ns nido.coordinator.record.fork
  "A unit forked into a child workstream: its lineage, the parent records a lineage names, and the
   fork itself.

   A fork is a CHILD WORKSTREAM, never a second unit on the parent's. Every reader of a workstream
   takes its newest design, so a child unit on the parent's ledger would become the parent's
   design; on its own ledger it is nobody else's. Forking therefore writes only to the child — its
   goal, a `:fork` entry naming the parent's baseline and design, and a baseline derived from those
   two — and the parent's ledger and record are untouched.

   Lineage is read off the child's `:fork` entry whenever it is asked, and stored nowhere else."
  (:require
   [nido.coordinator.record.standing :as standing]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.report :as report]
   [nido.coordinator.report.model :as model]))

(defn ^{:malli/schema [:=> [:cat :Workstream] [:maybe :Lineage]]}
  lineage
  "Where `w` came from — `{:parent <ws-id> :baseline-seq <n> :design-seq <n>}` — read from its
   `:fork` entry, or nil for a workstream that was not forked.

   Throws when `w` holds a `:fork` entry that cannot be read, rather than answering nil: a fork
   read as no fork would have its parent's claims re-checked as though they were new."
  [w]
  (when-let [row (->> (:entries w) (filter #(= :fork (:kind %))) first)]
    (let [e (or (ws/entry-at-seq (:project w) (:id w) (:seq row))
                (throw (ex-info (str "The :fork entry at " (:seq row) " on " (:id w)
                                     " could not be read, so where this unit came from cannot be said")
                                {:ws-id (:id w) :seq (:seq row)})))]
      {:parent       (get-in e [:parent :ws-id])
       :baseline-seq (get-in e [:parent :baseline :seq])
       :design-seq   (get-in e [:parent :design :seq])})))

(defn ^{:malli/schema [:=> [:cat :ProjectName :Lineage] :map]}
  parent-records
  "The baseline and design a lineage names, read from the parent's ledger by seq, as
   `{:baseline :design}` — either nil where its entry cannot be read."
  [project {:keys [parent baseline-seq design-seq]}]
  {:baseline (ws/entry-at-seq project parent baseline-seq)
   :design   (ws/entry-at-seq project parent design-seq)})

(defn- refuse!
  [message data]
  (throw (ex-info message (assoc data :refused :fork))))

(defn- derived-baseline
  "A child's first baseline: the parent baseline as it reads, with the parent design laid over its
   model, less the health observations that design routed :fix-here. The design answers those, and
   a child does not inherit what its parent committed to fixing."
  [baseline design]
  (let [fixed  (into #{} (keep #(when (= :fix-here (:to %)) (:health-id %))) (:routes design))
        health (into [] (remove #(contains? fixed (:id %))) (:health baseline))]
    (cond-> (-> (ws/unstamp baseline)
                (dissoc :supersedes :health :intent :fork)
                (assoc :model (model/overlay (:model baseline) (:model design))))
      (seq health) (assoc :health health))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map] :Workstream]}
  fork!
  "Mint a child workstream from `parent`, whose newest design stands, and return the child.

   `opts` carries the child's goal — `:goal`, `:done-when`, optionally `:context` — because a child
   is a unit of its own, and a unit is rooted in a goal. Appends, on the child only: that :intent,
   a :fork naming the parent's newest design and the baseline it cites, and a baseline derived from
   the two. Nothing is written on the parent.

   Refuses — throwing with `:refused :fork` and writing nothing anywhere — a parent with no design;
   a design that does not stand, because nothing cleared or granted it or something blocks it; a
   design or baseline written before the shared model, or before a role named its players, named by
   entry; and child records the ledger would not accept, with why. All of it is decided before the
   child is minted, so a refused fork leaves no workstream behind."
  [project parent {:keys [goal done-when context]}]
  (let [design   (or (ws/latest-entry project parent :design)
                     (refuse! (str "Workstream " parent " holds no design to fork") {:parent parent}))
        st       (standing/of-design project parent design)
        _        (when-not (:cleared? st)
                   (refuse! (str "The design at entry " (:seq design) " on " parent " does not stand"
                                 (if-let [b (:blocked st)]
                                   (str " — " (:detail b))
                                   " — nothing has cleared or granted it"))
                            {:parent parent :design-seq (:seq design)}))
        bseq     (get-in design [:baseline :seq])
        baseline (ws/entry-at-seq project parent bseq)
        _        (doseq [[what r n] [["design" design (:seq design)] ["baseline" baseline bseq]]]
                   (when-let [era (model/predates r)]
                     (refuse! (str "The " what " at entry " n " on " parent " was written before "
                                   (case era
                                     :shared-model "the shared model, so it has no claim ids or subjects"
                                     :role-players "a role named its players, so a claim about that role binds nothing")
                                   " to fork by — re-survey the area in the current shape first")
                              {:parent parent :seq n})))
        intent   (cond-> {:format :intent :goal goal :done-when (vec done-when)}
                   context (assoc :context context))
        derived  (derived-baseline baseline design)
        ;; Validated whole before the child exists, with the two citations the ledger will number
        ;; standing in — so the only refusals left once it is minted are the ledger's own.
        _        (try (report/validate-event :intent intent)
                      (report/validate-event :baseline (assoc derived :intent {:seq 1} :fork {:seq 2}))
                      (catch clojure.lang.ExceptionInfo e
                        (refuse! (str "The child's first records would not be accepted: " (ex-message e))
                                 {:parent parent :explain (:explain (ex-data e))})))
        child    (:id (ws/create! project {:stage :in-progress :external-refs []}))
        add!     (fn [kind record]
                   (ws/append-entry! project child {:kind kind} (pr-str record))
                   (:seq (ws/latest-entry project child kind)))
        i        (add! :intent intent)
        f        (add! :fork {:format :fork
                              :parent {:ws-id    parent
                                       :baseline {:seq bseq}
                                       :design   {:seq (:seq design)}}})]
    (add! :baseline (assoc derived :intent {:seq i} :fork {:seq f}))
    (ws/read-ws project child)))
