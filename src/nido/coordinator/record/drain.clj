(ns nido.coordinator.record.drain
  "A drain's records: its charter, recipe versions, census readings, slice plans and roster,
   misfits, clearances, residue items and their dispositions, and its exit observation — all on the
   drain's ledger — and each slice's birth and rebase entries naming its drain and recipe version.

   READ, NEVER STORED. A workstream is a drain because its ledger holds a :drain birth entry, a
   slice because its ledger holds a :slice one. The recipe version is the seq of the :recipe entry.
   The roster is on the DRAIN's ledger, so a drain lists its slices from its own records.

   Every kind here is appended only through this namespace (`workstream/appending-drain!` refuses
   anything else, and the generic appends refuse these kinds). Each append checks what it cites
   under the drain's append lock, so what it decided from cannot move before it writes. WHO may ask
   for an append — the coordinator, a slice, the person — is the lane's to check, not this one's."
  (:require
   [clojure.string :as str]
   [nido.coordinator.report :as report]
   [nido.coordinator.record.workstream :as ws]))

;; ── reading ─────────────────────────────────────────────────────────────────

(defn- rows-of [w kind]
  (filter #(= kind (:kind %)) (:entries w)))

(defn- entries-in
  "Every entry of `kind` on `w`, oldest first, read and stamped with :seq/:at."
  [w kind]
  (into [] (keep #(ws/entry-at-seq (:project w) (:id w) (:seq %))) (rows-of w kind)))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  drain?
  "Whether `w` is a drain: its ledger holds a :drain birth entry."
  [w]
  (boolean (seq (rows-of w :drain))))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  slice?
  "Whether `w` is a slice: its ledger holds a :slice birth entry."
  [w]
  (boolean (seq (rows-of w :slice))))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  charter
  "The drain's :drain birth entry, or nil for a workstream that is no drain."
  [w]
  (first (entries-in w :drain)))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  slice-birth
  "The slice's :slice birth entry — its drain, starting recipe version and plan — or nil."
  [w]
  (first (entries-in w :slice)))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  drain-of
  "The ws-id of the drain a slice belongs to, or nil for a workstream that is no slice."
  [w]
  (:drain (slice-birth w)))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  slice-version
  "The recipe version a slice runs under: the newest of its birth's and its :slice-rebased
   entries' recipe seqs. nil for a workstream that is no slice."
  [w]
  (when-let [birth (slice-birth w)]
    (or (:recipe (last (entries-in w :slice-rebased))) (:recipe birth))))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  recipe
  "The drain's current recipe — its newest :recipe entry, stamped with :seq — or nil."
  [w]
  (last (entries-in w :recipe)))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  version
  "The drain's recipe version: the seq of its newest :recipe entry, or nil while it has none."
  [w]
  (:seq (recipe w)))

(defn- proposals
  "Every recipe proposal, oldest first, each marked `:approved?` when a :recipe cites it."
  [w]
  (let [approved (into #{} (map :proposal) (entries-in w :recipe))]
    (mapv #(assoc % :approved? (contains? approved (:seq %))) (entries-in w :recipe-proposed))))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  pending-proposals
  "The proposals no approval has taken — newer than the current recipe, none approved since."
  [w]
  (let [v (or (version w) 0)]
    (filterv #(and (not (:approved? %)) (> (:seq %) v)) (proposals w))))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  censuses
  "Every census reading, oldest first."
  [w]
  (entries-in w :census))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  newest-census
  "The newest census reading citing the current recipe, or nil."
  [w]
  (when-let [v (version w)]
    (last (filter #(= v (:recipe %)) (censuses w)))))

(defn- starts
  "Every :slice-started on the drain, oldest first."
  [w]
  (entries-in w :slice-started))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  plans
  "Every slice plan on the drain, oldest first, each marked with how it stands: `:started` (a
   start cites it, `:slice` naming the workstream), `:withdrawn`, `:replaced` (a newer plan
   replaces it), or `:open`."
  [w]
  (let [started   (into {} (map (juxt :plan :ws-id)) (starts w))
        withdrawn (into #{} (map :plan) (entries-in w :slice-withdrawn))
        all       (entries-in w :slice-planned)
        replaced  (into #{} (keep :replaces) all)]
    (mapv (fn [p]
            (let [n (:seq p)]
              (cond
                (contains? started n) (assoc p :state :started :slice (started n))
                (contains? withdrawn n) (assoc p :state :withdrawn)
                (contains? replaced n) (assoc p :state :replaced)
                :else (assoc p :state :open))))
          all)))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  open-plans
  "The plans no start has consumed and none has withdrawn or replaced."
  [w]
  (filterv #(= :open (:state %)) (plans w)))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  slices
  "The drain's slices, from its own :slice-started entries: `{:ws-id :plan :started-at}`."
  [w]
  (mapv (fn [s] {:ws-id (:ws-id s) :plan (:plan s) :started-at (:at s)}) (starts w)))

(defn- misfits
  "Every misfit on the drain, oldest first, each with the `:answer` that answered it, or none."
  [w]
  (let [answers (into {} (map (juxt :misfit :answer)) (entries-in w :misfit-answered))]
    (mapv #(cond-> % (answers (:seq %)) (assoc :answer (answers (:seq %)))) (entries-in w :misfit))))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  open-misfits
  "The misfits no :misfit-answered names."
  [w]
  (filterv (complement :answer) (misfits w)))

(defn- clearances
  "Every :slice-cleared on the drain, oldest first."
  [w]
  (entries-in w :slice-cleared))

(defn ^{:malli/schema [:=> [:cat :any :any] :any]}
  cleared?
  "Whether the coordinator has cleared the slice `slice-ws-id`."
  [w slice-ws-id]
  (boolean (some #(= slice-ws-id (:slice %)) (clearances w))))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  residue
  "The drain's residue items, oldest first, each with its newest `:disposition`."
  [w]
  (let [dispositions (reduce (fn [m d] (assoc m (:residue d) (:disposition d)))
                             {} (entries-in w :residue-disposed))]
    (mapv #(assoc % :disposition (dispositions (:seq %))) (entries-in w :residue))))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  exit-observed
  "The :exit-observed entry, or nil."
  [w]
  (first (entries-in w :exit-observed)))

;; ── appending ───────────────────────────────────────────────────────────────

(defn- refuse! [message data]
  (throw (ex-info message (assoc data :refused :drain))))

(defn- must-drain! [w]
  (when-not (drain? w)
    (refuse! (str (:id w) " is not a drain") {:ws-id (:id w)})))

(defn- in-drain!
  "Answer `f` of `{:w :append!}` under the drain's append lock, refusing a workstream that is no
   drain."
  [project ws-id f]
  (ws/appending-drain! project ws-id
                       (fn [{:keys [w] :as ctx}] (must-drain! w) (f ctx))))

(defn- entry-kind-at [w n]
  (:kind (first (filter #(= n (:seq %)) (:entries w)))))

(defn- must-cite! [w n kind what]
  (when-not (= kind (entry-kind-at w n))
    (refuse! (str what " " n " is not a " kind " on " (:id w)) {:ws-id (:id w) :seq n :kind kind})))

(defn- valid!
  "Throw what the append would throw for `payload`'s shape, writing nothing. A verb that writes
   more than one entry asks it of every entry before its first append, so a refusal leaves none
   of them behind."
  [payload]
  (report/validate-event (:format payload) payload)
  nil)

(defn ^{:malli/schema [:=> [:cat :ProjectName :any] :any]}
  start-drain!
  "Mint a drain workstream: its :drain birth, naming what established the pattern
   (`{:ws-id …}` or `{:commit …}`) and the exit criterion. Whether the named workstream or commit
   exists is the caller's to check. Answers the new workstream's id."
  [project {:keys [established-by exit external-refs]}]
  (let [birth {:format :drain :established-by established-by :exit exit :by :person}
        _     (valid! birth)
        w     (ws/create! project {:external-refs external-refs})]
    (ws/appending-drain! project (:id w) (fn [{:keys [append!]}] (append! birth)))
    (:id w)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :any] :any]}
  propose-recipe!
  "The coordinator's :recipe-proposed. Answers its seq."
  [project ws-id {:keys [reason recipe]}]
  (in-drain! project ws-id
             (fn [{:keys [append!]}]
               (append! {:format :recipe-proposed :reason reason :recipe recipe :by :coordinator}))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :any] :any]}
  approve-recipe!
  "The person's approval of a pending proposal: a :recipe carrying its recipe, whose seq is the new
   version. Refuses a proposal already approved or older than the current recipe. Answers the seq."
  [project ws-id proposal-seq]
  (in-drain! project ws-id
             (fn [{:keys [w append!]}]
               (must-cite! w proposal-seq :recipe-proposed "Proposal")
               (let [p (first (filter #(= proposal-seq (:seq %)) (pending-proposals w)))]
                 (when-not p
                   (refuse! (str "Proposal " proposal-seq " is approved already or older than the current recipe")
                            {:ws-id ws-id :proposal proposal-seq}))
                 (append! {:format :recipe :proposal proposal-seq :recipe (:recipe p) :by :person})))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :any] :any]}
  record-census!
  "nido's census reading under the recipe it cites, which must be the current one. Answers its seq."
  [project ws-id {:keys [recipe remaining groups]}]
  (in-drain! project ws-id
             (fn [{:keys [w append!]}]
               (when-not (and recipe (= recipe (version w)))
                 (refuse! (str "A census cites the current recipe (" (version w) "), not " recipe)
                          {:ws-id ws-id :recipe recipe}))
               (append! {:format :census :recipe recipe :remaining remaining
                         :groups (vec groups) :by :nido}))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :any] :any]}
  plan-slice!
  "The coordinator's :slice-planned. Refuses one planned from any census but the newest citing the
   current recipe, resting on anything but a plan of this drain, or replacing a plan that is not
   open. Answers its seq."
  [project ws-id {:keys [sites rests-on start census replaces note]}]
  (in-drain! project ws-id
             (fn [{:keys [w append!]}]
               (let [newest (newest-census w)]
                 (when-not (and newest (= census (:seq newest)))
                   (refuse! (str "A plan is made from the newest census of the current recipe ("
                                 (:seq newest) "), not " census)
                            {:ws-id ws-id :census census}))
                 (doseq [r rests-on] (must-cite! w r :slice-planned "Rested-on plan"))
                 (when replaces
                   (when-not (some #(= replaces (:seq %)) (open-plans w))
                     (refuse! (str "Plan " replaces " is not open, so it cannot be replaced")
                              {:ws-id ws-id :replaces replaces})))
                 (append! (cond-> {:format :slice-planned :sites (vec sites) :rests-on (vec rests-on)
                                   :start (or start :after-landing) :census census :by :coordinator}
                            replaces (assoc :replaces replaces)
                            note     (assoc :note note)))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :any :any] :any]}
  withdraw-plan!
  "The coordinator withdrawing an open plan. Answers the entry's seq."
  [project ws-id plan-seq why]
  (in-drain! project ws-id
             (fn [{:keys [w append!]}]
               (when-not (some #(= plan-seq (:seq %)) (open-plans w))
                 (refuse! (str "Plan " plan-seq " is not open") {:ws-id ws-id :plan plan-seq}))
               (append! {:format :slice-withdrawn :plan plan-seq :why why :by :coordinator}))))

(defn ^{:malli/schema [:=> [:cat :any :any] :any]}
  startable
  "Why a slice may not start from `plan-seq` on drain `w` now — a string — or nil when it may:
   the drain must hold a recipe and a census citing it, and the plan must be open."
  [w plan-seq]
  (cond
    (nil? (version w))        "the drain has no recipe"
    (nil? (newest-census w))  "the drain has no census under its current recipe"
    (not (some #(= plan-seq (:seq %)) (open-plans w)))
    (str "plan " plan-seq " is not open")))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :any :any] :any]}
  start-slice!
  "Start a slice from an open plan: refuse, naming what is missing, before anything is made when
   the drain has no recipe, no census under it, or the plan is not open; otherwise mint the slice
   workstream with its :slice birth and append the drain's :slice-started citing the plan.
   Answers the slice's ws-id."
  [project ws-id plan-seq by]
  (in-drain! project ws-id
             (fn [{:keys [w append!]}]
               (when-let [why (startable w plan-seq)]
                 (refuse! (str "No slice starts: " why) {:ws-id ws-id :plan plan-seq}))
               (let [birth   {:format :slice :drain ws-id :recipe (version w) :plan plan-seq}
                     started #(hash-map :format :slice-started :plan plan-seq :ws-id % :by by)]
                 ;; The slice's id is not minted yet; the drain's own id stands in for it in the
                 ;; shape check, which asks only that it be one.
                 (valid! birth)
                 (valid! (started ws-id))
                 (let [slice (ws/create! project {})]
                   (ws/appending-drain! project (:id slice) (fn [{sa! :append!}] (sa! birth)))
                   (append! (started (:id slice)))
                   (:id slice))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :any] :any]}
  rebase-slice!
  "Move a slice to its drain's current recipe: a :slice-rebased citing it, when the slice runs an
   older one. Answers the version the slice now runs under."
  [project slice-ws-id]
  (ws/appending-drain! project slice-ws-id
                       (fn [{:keys [w append!]}]
                         (let [drain (some->> (drain-of w) (ws/read-ws project))
                               v     (some-> drain version)]
                           (when-not v
                             (refuse! (str slice-ws-id " is no slice of a drain with a recipe") {:ws-id slice-ws-id}))
                           (when (< (slice-version w) v)
                             (append! {:format :slice-rebased :recipe v :by :nido}))
                           v))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :any] :any]}
  record-misfit!
  "A slice's :misfit on its drain. Answers its seq."
  [project ws-id {:keys [slice site note]}]
  (in-drain! project ws-id
             (fn [{:keys [w append!]}]
               (when-not (some #(= slice (:ws-id %)) (slices w))
                 (refuse! (str slice " is no slice of " ws-id) {:ws-id ws-id :slice slice}))
               (append! {:format :misfit :slice slice :site site :note note :by :slice}))))

(defn- residue-source
  "The slice whose clearance or misfit left the residue item cited from `from`, or nil."
  [w from]
  (let [e (first (filter #(= from (:seq %)) (entries-in w :slice-cleared)))
        a (when-not e (first (filter #(= from (:seq %)) (entries-in w :misfit-answered))))]
    (or (:slice e)
        (when a (:slice (first (filter #(= (:misfit a) (:seq %)) (misfits w))))))))

(defn- covers?
  "Whether `plan` covers residue at `site` left by the slice `source`: it names the site, and it is
   not the plan of the slice that left it. The one reading of coverage — a follow-up is accepted,
   and a residue item read covered, by it alone."
  [plan site source]
  (boolean (and (some #{site} (:sites plan))
                (or (nil? source) (not= source (:slice plan))))))

(defn- must-follow-up! [w plan-seq site source]
  (must-cite! w plan-seq :slice-planned "Follow-up plan")
  (when-not (covers? (first (filter #(= plan-seq (:seq %)) (plans w))) site source)
    (refuse! (str "Plan " plan-seq " does not cover residue at " site
                  " — a follow-up names the site, and is not the plan of the slice that left it")
             {:ws-id (:id w) :plan plan-seq :site site :slice source})))

(defn- check-residue!
  "Refuse a residue item and its first disposition before anything is appended: the follow-up plan
   it cites, which must cover the site against `source`, the slice leaving the item, and the shape
   of both entries. The seqs they will cite are not taken yet, so the first stands in for them in the shape
   check."
  [w site disposition source]
  (when (= :follow-up (:to disposition))
    (must-follow-up! w (:plan disposition) site source))
  (valid! {:format :residue :site site :from 1 :by :coordinator})
  (valid! {:format :residue-disposed :residue 1 :disposition disposition :by :coordinator}))

(defn- append-residue!
  "Append a residue item and its first disposition, answering the item's seq. The caller has
   asked `check-residue!` of both first."
  [append! site from disposition]
  (let [n (append! {:format :residue :site site :from from :by :coordinator})]
    (append! {:format :residue-disposed :residue n :disposition disposition :by :coordinator})
    n))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :any :any] :any]}
  answer-misfit!
  "The coordinator's answer to an open misfit. `answer` is `{:as :proposed :proposal n}`,
   `{:as :carved :site s :disposition d}` — whose residue item is appended with its disposition in
   the same step — or `{:as :escalated :asks s}`. Answers the :misfit-answered seq."
  [project ws-id misfit-seq answer]
  (in-drain! project ws-id
             (fn [{:keys [w append!]}]
               (when-not (some #(= misfit-seq (:seq %)) (open-misfits w))
                 (refuse! (str "Misfit " misfit-seq " is not open") {:ws-id ws-id :misfit misfit-seq}))
               (when (= :proposed (:as answer))
                 (must-cite! w (:proposal answer) :recipe-proposed "Proposal"))
               (when (and (= :carved (:as answer)) (nil? (:disposition answer)))
                 (refuse! "A carved site is disposed of as it is carved" {:ws-id ws-id}))
               (let [answered {:format :misfit-answered :misfit misfit-seq
                               :answer (dissoc answer :disposition) :by :coordinator}]
                 (valid! answered)
                 (when (= :carved (:as answer))
                   (check-residue! w (:site answer) (:disposition answer)
                                   (:slice (first (filter #(= misfit-seq (:seq %)) (misfits w))))))
                 (let [n (append! answered)]
                   (when (= :carved (:as answer))
                     (append-residue! append! (:site answer) n (:disposition answer)))
                   n)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :any :any] :any]}
  clear-slice!
  "The coordinator clearing a slice of this drain for landing, naming the residue it leaves —
   `[{:site s :disposition d} …]` — each appended as a residue item with its disposition in the
   same step. Answers the :slice-cleared seq."
  [project ws-id slice-ws-id residue-items]
  (in-drain! project ws-id
             (fn [{:keys [w append!]}]
               (when-not (some #(= slice-ws-id (:ws-id %)) (slices w))
                 (refuse! (str slice-ws-id " is no slice of " ws-id) {:ws-id ws-id :slice slice-ws-id}))
               (let [cleared {:format :slice-cleared :slice slice-ws-id
                              :residue (mapv :site residue-items) :by :coordinator}]
                 (valid! cleared)
                 (doseq [{:keys [site disposition]} residue-items]
                   (check-residue! w site disposition slice-ws-id))
                 (let [n (append! cleared)]
                   (doseq [{:keys [site disposition]} residue-items]
                     (append-residue! append! site n disposition))
                   n)))))

(defn- plan-covering
  "The started plan covering a residue item through its disposition, or nil."
  [w item]
  (let [p (get-in item [:disposition :plan])]
    (when p (first (filter #(and (= p (:seq %)) (= :started (:state %))
                                 (covers? % (:site item) (residue-source w (:from item))))
                           (plans w))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :any :any] :any]}
  dispose-residue!
  "The coordinator disposing again of a residue item no started plan covers yet. Answers the seq."
  [project ws-id residue-seq disposition]
  (in-drain! project ws-id
             (fn [{:keys [w append!]}]
               (let [item (first (filter #(= residue-seq (:seq %)) (residue w)))]
                 (when-not item
                   (refuse! (str residue-seq " is no residue item of " ws-id) {:ws-id ws-id}))
                 (when (plan-covering w item)
                   (refuse! (str "Residue " residue-seq " is covered by a started plan already") {:ws-id ws-id}))
                 (when (= :follow-up (:to disposition))
                   (must-follow-up! w (:plan disposition) (:site item) (residue-source w (:from item))))
                 (append! {:format :residue-disposed :residue residue-seq :disposition disposition
                           :by :coordinator})))))

(defn ^{:malli/schema [:=> [:cat :any :any] :any]}
  uncovered-residue
  "The residue items no landed slice covers. `landed?` answers whether a slice workstream has
   landed — the lane's reading, not this namespace's. A landed slice covers an item only when its
   plan covers the item's site (`covers?`: names it, and is not the plan of the slice that left
   it) — the follow-up plan its disposition cites, or, disposed :closing, any plan made after the
   item was left, so none planned without knowing of it covers it."
  [w landed?]
  (let [ps (plans w)]
    (filterv (fn [item]
               (let [source   (residue-source w (:from item))
                     covering (if-let [p (get-in item [:disposition :plan])]
                                (filter #(= p (:seq %)) ps)
                                (filter #(> (:seq %) (:from item)) ps))]
                 (not (some #(and (:slice %) (covers? % (:site item) source) (landed? (:slice %)))
                            covering))))
             (residue w))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :any] :any]}
  observe-exit!
  "The person observing the exit criterion against the newest census, which must read zero, with
   every residue item covered by a landed slice. Answers the :exit-observed seq."
  [project ws-id landed?]
  (in-drain! project ws-id
             (fn [{:keys [w append!]}]
               (let [c (last (censuses w))]
                 (when-not (and c (zero? (:remaining c)))
                   (refuse! (str "The drain's newest census reads " (:remaining c) " remaining, not zero")
                            {:ws-id ws-id}))
                 (when-let [left (seq (uncovered-residue w landed?))]
                   (refuse! (str "Residue not yet covered by a landed slice: "
                                 (str/join ", " (map :site left)))
                            {:ws-id ws-id}))
                 (append! {:format :exit-observed :exit (:exit (charter w)) :census (:seq c) :by :person})))))
