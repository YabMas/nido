(ns nido.review.merge
  "Bringing a forked unit's design back into its parent.

   It COMPUTES only the part that is data — the elements and claims, keyed by id — and asks fukan
   whether the declaration in the child's working copy still obeys its laws. It never composes
   prose: a merged design's summary, shape, standing, routes, layers and seams are authored beside
   the proposal, and the merged design is judged by the ordinary design round, whose record-level
   derivations run whatever the proposal found.

   Three effective models are combined, each built by the one overlay: the BASE, recomputed from
   the child's :fork entry as the design it forked laid over that design's baseline, and never read
   off a child record a later round may have superseded; the PARENT, its current design over the
   baseline that design cites; and the CHILD, its current design over the baseline it cites."
  (:require
   [clojure.string :as str]
   [nido.coordinator.record.fork :as fork]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.report.model :as model]
   [nido.design.check :as design]))

(defn- refuse!
  [message data]
  (throw (ex-info message (assoc data :refused :merge))))

(defn- model-era!
  "Refuse, naming it, a record that cannot be read, or was written before the shared model or before
   a role named its players — it holds no model to combine by."
  [ws-id [record n what]]
  (when-let [era (model/predates record)]
    (refuse! (str "The " what " at entry " n " on " ws-id
                  (cond (nil? record)         " could not be read"
                        (= :shared-model era) " was written before the shared model"
                        :else                 " was written before a role named its players")
                  " — there is nothing to combine it by")
             {:ws-id ws-id :seq n})))

(defn- laid-over-its-baseline
  "`design`, on `ws-id`, laid over the baseline it cites: `{:baseline <record> :model <effective>}`."
  [project ws-id design whose]
  (let [bseq     (get-in design [:baseline :seq])
        baseline (ws/entry-at-seq project ws-id bseq)]
    (model-era! ws-id [design (:seq design) (str whose " design")])
    (model-era! ws-id [baseline bseq (str whose " baseline")])
    {:baseline baseline
     :model    (model/overlay (:model baseline) (:model design))}))

(defn- law-conflicts
  "Every law the declaration in `worktree` breaks, as conflicts. A declaration fukan could not check
   refuses the merge: nobody can say which laws the combination breaks, and that is not none."
  [project worktree]
  (let [result (design/check project worktree)]
    (case (:status result)
      (:unmodelled :satisfied) []
      :violated (mapv (fn [{:keys [law offenders]}]
                        {:kind :law-violated :names (into [(str law)] (map pr-str) offenders)})
                      (:violations result))
      (refuse! (str "The declaration in " worktree " could not be checked: " (:error result))
               {:worktree worktree}))))

(defn- removed-from
  "The ids `baseline`'s model holds that `combined` does not — what a merged design has to state as
   removed, since a design laid over that baseline carries every id it leaves unsaid."
  [baseline combined]
  (let [gone (fn [k] (let [kept (into #{} (map :id) (k combined))]
                       (into [] (comp (map :id) (remove kept)) (get-in baseline [:model k]))))]
    (cond-> {}
      (seq (gone :elements)) (assoc :elements (gone :elements))
      (seq (gone :claims))   (assoc :claims (gone :claims)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :string :string] :MergeProposal]}
  proposal
  "What merging `child-ws` back into its parent would append, and everything standing in its way:
   `{:model :conflicts :parent :child}`.

   :model is the combination, stating as removed each id the parent's current baseline holds and the
   combination dropped. :conflicts is every conflict — by id from the models, by law from the
   declaration in `worktree`. :parent and :child are the citations a merged design carries: the
   parent's current design with the baseline and intent it cites, and the child design combined.

   Refuses, throwing with `:refused :merge`: a workstream that was not forked, a parent or child
   holding no design, a record that cannot be read or predates the shared model, and a declaration
   fukan could not check."
  [project child-ws worktree]
  (let [child   (or (ws/read-ws project child-ws)
                    (refuse! (str "No workstream " child-ws) {:ws-id child-ws}))
        lineage (or (fork/lineage child)
                    (refuse! (str "Workstream " child-ws " was not forked, so it has no parent to merge into")
                             {:ws-id child-ws}))
        parent  (:parent lineage)
        {fb :baseline fd :design} (fork/parent-records project lineage)
        _       (model-era! parent [fd (:design-seq lineage) "forked design"])
        _       (model-era! parent [fb (:baseline-seq lineage) "forked baseline"])
        pd      (or (ws/latest-entry project parent :design)
                    (refuse! (str "The parent " parent " holds no design") {:ws-id parent}))
        cd      (or (ws/latest-entry project child-ws :design)
                    (refuse! (str "The child " child-ws " holds no design to merge") {:ws-id child-ws}))
        {pm :model pb :baseline} (laid-over-its-baseline project parent pd "parent's current")
        {cm :model}              (laid-over-its-baseline project child-ws cd "child's")
        {:keys [model conflicts]} (model/combine (model/overlay (:model fb) (:model fd)) pm cm)
        removed (removed-from pb model)]
    {:model     (cond-> model (seq removed) (assoc :removed removed))
     :conflicts (into (vec conflicts) (law-conflicts project worktree))
     :parent    {:ws-id    parent
                 :design   {:seq (:seq pd)}
                 :baseline {:seq (:seq pb)}
                 :intent   (:intent pd)}
     :child     {:ws-id child-ws :design {:seq (:seq cd)}}}))

(def ^:private conflict-heads
  {:element-diverged "an element each side changed differently"
   :claim-diverged   "a claim each side changed differently"
   :subject-removed  "a claim kept about what the other side removed"
   :subject-restated "a claim stated against a subject the other side alone restated"
   :law-violated     "a law the declaration breaks"})

(defn ^{:malli/schema [:=> [:cat :MergeProposal] :string]}
  conflicts-text
  "The conflicts for a person, one line each, named by id or by law. Empty when none stands."
  [{:keys [conflicts]}]
  (str/join "\n" (for [{:keys [kind names]} conflicts]
                   (str "✗ " (get conflict-heads kind (name kind)) ": " (str/join " · " names)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :string :string :map] :map]}
  merge!
  "Append to the parent the merged design whose own fields `authored` carries, when no conflict
   stands. Returns `{:proposal p :appended <path>}`, or `{:proposal p}` having appended nothing.

   The proposal supplies what a merged design is not free to author: its model, the baseline and
   intent the parent's current design cites, that it supersedes that design, and the child design it
   combined, under :merges. `authored` supplies the rest — summary, shape, standing, the relation to
   the baseline, routes, effort — and a :supersedes :why when it has one. The append boundary holds
   the model to the combination again, whoever appends."
  [project child-ws worktree authored]
  (let [p (proposal project child-ws worktree)]
    (if (seq (:conflicts p))
      {:proposal p}
      (let [{:keys [ws-id design baseline intent]} (:parent p)
            record (assoc authored
                          :format     :design
                          :model      (:model p)
                          :intent     intent
                          :merges     (:child p)
                          :baseline   (assoc (:baseline authored) :seq (:seq baseline))
                          :supersedes {:seq (:seq design)
                                       :why (or (get-in authored [:supersedes :why])
                                                (str "merges the child unit " child-ws
                                                     " and its design at entry "
                                                     (get-in p [:child :design :seq])))})]
        {:proposal p
         :appended (ws/append-entry! project ws-id {:kind :design} (pr-str record))}))))
