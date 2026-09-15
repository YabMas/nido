;; src/nido/review/settled.clj
(ns nido.review.settled
  "Which subjects of a record a round need not ask its judge to check.

   The record-side counterpart of `nido.review.cache`, with the same bias: a subject re-checked for
   nothing costs one judge's attention, a subject skipped when it should not have been lets a false
   claim stand under a design. So every doubt resolves to checking — no identity, an unreadable
   ledger, a round that read no single tree, a retracted record, a finding at the key.

   Derived, never stored. What it reads are observations only a judge can make, and each is on the
   judgement already: the ids it says it checked, and what it read them against. Whether a subject
   is settled is a fold over those, asked afresh each round.

   A subject is keyed by its id, its content, and what it was checked against. The id alone is not
   a subject — an id confirmed and then amended names a claim nobody has checked. What it was
   checked against depends on what it rests on. A claim or element of a model, in a project that
   declares a design, rests on the declared elements it names: each one's declaration and the code
   its module pairs with, so a change anywhere else leaves it settled. Anything else — a health
   observation, a whole-record field, a subject of a record from before the shared model, every
   subject in a project that declares no design — rests on the whole tree, so any change anywhere
   unsettles it.

   A confirmation counts from a baseline review or a design decision, on the workstream's own
   ledger, on the parent ledger its :fork entry cites, and on the child ledger a merged design's
   :merges cites."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.coordinator.record.fork :as fork]
   [nido.coordinator.record.workstream :as ws]
   [nido.review.digest :as digest]
   [nido.vsdd.jj :as jj]))

(def ^:private content-id
  "What every line of `jj debug tree` carries for the entry it lists: `FileId(\"…\")`,
   `SymlinkId(\"…\")`, one per side of a conflict."
  #"Id\(\"[0-9a-f]+\"\)")

(defn ^{:malli/schema [:=> [:cat :Path] [:maybe :string]]}
  code-identity
  "A hash of the tree jj lists at `cwd`, or nil.

   Listing the tree snapshots the working copy first, so an uncommitted edit moves
   the hash; a description edit, or a rebase that leaves the tree as it was, does
   not. The executable bit and symlinks are part of the listing and move it too.

   `jj debug tree` is not an interface jj promises to keep. A listing line with no
   content id yields nil rather than a hash, because a format that dropped the ids
   would otherwise hash two different trees to one value — the single failure this
   must not have. Anything else unexpected (not a jj repo, an empty tree, a throw)
   is nil too."
  [cwd]
  (try
    (let [{:keys [exit out]} (jj/jj! cwd "debug" "tree" "-r" "@")]
      (when (and (zero? exit)
                 (not (str/blank? out))
                 (every? #(re-find content-id %) (str/split-lines out)))
        (digest/sha256-hex out)))
    (catch Throwable _ nil)))

(defn ^{:malli/schema [:=> [:cat :DeclaredElements :Path] [:maybe [:map-of :string :string]]]}
  subject-identities
  "Each declared element's identity at `worktree`, by id, as `listing` reads it — or nil when the
   listing is not `:listed`, which it never is in a project that declares no design.

   An identity hashes what was declared about the element — every row listed under its id, with its
   sort and declaration digest — and the content of the file its module pairs with. So it moves
   when the element's declaration or its code moves, and not when anything else does. A file that
   cannot be read hashes as unreadable rather than as absent, so a later reading that can read it
   differs.

   A module or operation listed with no file has no identity. It is code-bearing, so a listing that
   pairs it with nothing either reads a module not yet realized or dropped code it could not read
   — and the two look alike from here, so it is a doubt, and a doubt settles nothing. A role, or a
   kind no module holds, has no code of its own and is identified by its declaration alone."
  [listing worktree]
  (when (= :listed (:status listing))
    (let [content      (memoize
                        (fn [file]
                          (try (digest/sha256-hex
                                (slurp (str (if (fs/absolute? file) file (fs/path worktree file)))))
                               (catch Throwable _ "unreadable"))))
          code-bearing #(contains? #{"module" "operation"} (str/lower-case (name (:sort %))))]
      (into {}
            (keep (fn [[id rows]]
                    (when-not (some #(and (code-bearing %) (nil? (:file %))) rows)
                      [id (digest/sha256-hex
                           (pr-str (sort (map (fn [{:keys [sort declaration file]}]
                                                [(str sort) (str declaration) (when file (content file))])
                                              rows))))])))
            (group-by :id (:elements listing))))))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  subjects
  "Every subject of a baseline or design, by id, each as the vector of what carries that id — its
   claims, modules and health observations, or in the shared model its claims and elements.

   A vector because ids are unique per kind and not across kinds: a claim and an element may share
   one, and a judge confirming that id cannot say which it meant. Both are then one subject,
   settled only while neither has changed. :shape and :composition take their field names, as the
   prompt brackets them."
  [record]
  (let [add (fn [m id v] (update m id (fnil conj []) v))]
    (cond-> (reduce (fn [m s] (if-let [id (:id s)] (add m id s) m))
                    {}
                    (concat (:load-bearing record) (:modules record) (:health record)
                            (get-in record [:model :claims]) (get-in record [:model :elements])))
      (some? (:shape record))       (add "shape" (:shape record))
      (some? (:composition record)) (add "composition" (:composition record)))))

(def ^:private ledger-kinds [:baseline-review :design-decision :baseline :design :retraction])

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:maybe :map]]}
  ledger
  "One workstream's judgements and what they judged — `{:ws-id :reviews :decisions :baselines
   :designs :retractions}`, each oldest first and stamped with :seq — or nil when the workstream is
   unreadable or any entry of those kinds fails to parse.

   `ws/entries-of` drops what it cannot parse, and here a dropped entry is not harmless: the
   judgement it held may be the one that found against a subject, and the retraction may be the
   one that unseats a confirmation. So the parsed count is held against the index, and a shortfall
   settles nothing."
  [project ws-id]
  (try
    (when-let [w (ws/read-ws project ws-id)]
      (let [indexed (frequencies (map :kind (:entries w)))
            read    (into {} (map (fn [k] [k (vec (ws/entries-of project ws-id k))]))
                          ledger-kinds)]
        (when (every? #(= (get indexed % 0) (count (read %))) ledger-kinds)
          {:ws-id       ws-id
           :reviews     (read :baseline-review)
           :decisions   (read :design-decision)
           :baselines   (read :baseline)
           :designs     (read :design)
           :retractions (read :retraction)})))
    (catch Throwable _ nil)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map] [:maybe [:vector :map]]]}
  ledgers
  "The ledgers a round over `record`, on `ws-id`, may find its subjects settled on: this
   workstream's first, then its parent's when this unit was forked, then the child's when `record`
   is a design carrying :merges. nil when any of them cannot be read, or this unit's lineage cannot
   be said — the confirmation or the finding that decides might be on the ledger nobody could read."
  [project ws-id record]
  (try
    (when-let [w (ws/read-ws project ws-id)]
      (let [ls (mapv #(ledger project %)
                     (distinct (remove nil? [ws-id
                                             (:parent (fork/lineage w))
                                             (get-in record [:merges :ws-id])])))]
        (when (every? some? ls) ls)))
    (catch Throwable _ nil)))

(defn- rests-on
  "The declared elements `content` — everything `record` carries under one id — rests on, when all
   of it is borne by the record's model: a claim's subjects, an element itself, and the players of
   any role either names, as `effective`'s model plays it. nil for anything else, which only the
   whole tree can key."
  [record effective content]
  (let [elements (into {} (map (juxt :id identity)) (get-in effective [:model :elements]))
        played   (fn [id] (cons id (:plays (get elements id))))]
    (when (and (contains? record :model)
               (every? #(and (map? %) (or (contains? % :about) (contains? % :sort))) content))
      (set (mapcat #(if (contains? % :about) (mapcat played (:about %)) (played (:id %)))
                   content)))))

(defn- at-key?
  "`judgement` read the subject as `reading` would now: the whole tree was this tree, or every
   element `needed` names had the identity it has now."
  [{:keys [code-identity subject-identities]} needed judgement]
  (boolean
   (or (and code-identity (= code-identity (:code-identity judgement)))
       (and (seq needed) subject-identities
            (every? #(let [now (get subject-identities %)]
                       (and now (= now (get-in judgement [:subject-identities %]))))
                    needed)))))

(defn ^{:malli/schema [:function
                       [:=> [:cat [:maybe [:vector :map]] :map :map] :map]
                       [:=> [:cat [:maybe [:vector :map]] :map :map :map] :map]]}
  settled
  "The subjects of `record` settled at `reading` — `{:code-identity :subject-identities}`, what the
   judge is about to read — as `{id {:ws-id :seq}}`, naming the latest judgement that settled each.

   A subject is settled when a baseline review or a design decision, on any of `ledgers`, named its
   id in :confirmed while judging a record nobody retracted whose subject with that id is identical
   to this one, at this subject's key — and no judgement at that same content and key has found
   against it since. The newest judgement at the key that bears on the subject decides, ordered by
   :at whichever ledger it is on: a finding stands until a later confirmation answers it, so a record
   amended elsewhere in answer to a finding leaves the subject to be confirmed again rather than
   checked for ever. A holding verdict settles nothing by itself; only an id its judge says it
   checked does.

   Only `record`'s own subjects are candidates, but a role's players are read from `effective` — for
   a design, its model laid over its baseline's, since a role it keeps is not restated and a claim
   about it still rests on its players. `record` itself when omitted."
  ([ledgers record reading] (settled ledgers record reading record))
  ([ledgers record reading effective]
   (if (or (nil? ledgers) (and (nil? (:code-identity reading)) (nil? (:subject-identities reading))))
     {}
     (let [judged (for [{:keys [ws-id reviews decisions baselines designs retractions]} ledgers
                        :let [retracted (into #{} (map #(get-in % [:retracts :seq])) retractions)
                              records   (into {} (map (juxt :seq identity)) (concat baselines designs))]
                        [j cited] (concat (map (juxt identity :baseline-seq) reviews)
                                          (map (juxt identity :design-seq) decisions))
                        :let [r (get records cited)]
                        :when r]
                    {:ws-id ws-id :judgement j :subjects (subjects r)
                     :retracted? (contains? retracted cited)})]
       (into {}
             (keep (fn [[id content]]
                     (let [needed  (rests-on record effective content)
                           at-key  (filter #(and (= content (get (:subjects %) id))
                                                 (at-key? reading needed (:judgement %)))
                                           judged)
                           ;; What bears on the subject at this key: a finding over any record, and a
                           ;; confirmation over one nobody retracted. A judgement doing both found.
                           bearing (keep (fn [{j :judgement :as m}]
                                           (cond
                                             (some #(= id (:claim-id %)) (:findings j)) (assoc m :found? true)
                                             (and (not (:retracted? m)) (some #{id} (:confirmed j))) m))
                                         at-key)
                           latest  (last (sort-by (fn [{j :judgement}] [(str (:at j)) (or (:seq j) 0)])
                                                  bearing))]
                       (when (and latest (not (:found? latest)))
                         [id {:ws-id (:ws-id latest) :seq (get-in latest [:judgement :seq])}]))))
             (subjects record))))))
