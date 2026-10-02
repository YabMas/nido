(ns nido.review.settled
  "Which subjects of a record a round need not ask its judge to check.

   The record-side counterpart of `nido.review.cache`, with the same bias: a subject re-checked for
   nothing costs one judge's attention, a subject skipped when it should not have been lets a false
   claim stand under a design. So every doubt resolves to checking — no identity, an unreadable
   ledger, a round that read no single tree, a retracted record, a finding at the key, a
   confirmation that cites nothing it read.

   Derived, never stored. What it reads are observations only a judge can make, and each is on the
   judgement already: the ids it says it checked, where it read each, and what it read them against.
   Whether a subject is settled is a fold over those, asked afresh each round.

   A subject is keyed by its id, its content, what the record it sits in says around it, and what it
   was checked against. The id alone is not a subject — an id confirmed and then amended names a
   claim nobody has checked. Nor is the content alone: a claim is judged in the context of its
   record, so the record's own entries for the elements it is about, the other claims about those
   elements, and the intent and baseline the record cites are part of the key — amending any of
   them leaves a claim whose words did not move unchecked under what it now sits beside. What it was
   checked against depends on what it rests on. A claim or element of a model, in a project that
   declares a design, rests on the declared elements it names: each one's declaration and the code
   its module pairs with, so a change anywhere else leaves it settled. Anything else — a health
   observation, a whole-record field, a subject of a record from before the shared model, every
   subject in a project that declares no design — rests on the whole tree, so any change anywhere
   unsettles it.

   One confirmation is a reading, not a settlement: a round skips a subject only on the second
   consecutive one at the same content and key (`single-readings`), because a judge is not
   deterministic at an unchanged record.

   A confirmation counts from a baseline review or a design decision, on the workstream's own
   ledger, on the parent ledger its :fork entry cites, and on the child ledger a merged design's
   :merges cites — and only where the judgement says what it read to confirm it (`:checked-at`). A
   bare id is the judge's word, and a word settles nothing."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [nido.coordinator.record.fork :as fork]
   [nido.coordinator.record.workstream :as ws]
   [nido.review.digest :as digest]
   [nido.vsdd.jj :as jj]))

(def ^:private content-id
  "What every line of `jj debug tree` carries for the entry it lists: `FileId(\"…\")`,
   `SymlinkId(\"…\")`, one per side of a conflict."
  #"Id\(\"[0-9a-f]+\"\)")

(defn- git!
  "Run git in `dir`, answering {:exit :out}. `index` names the index file it stages into."
  [dir index & args]
  (let [{:keys [exit out]} (apply p/shell {:dir dir :out :string :err :string :continue true
                                           :extra-env (cond-> {} index (assoc "GIT_INDEX_FILE" index))}
                                  "git" args)]
    {:exit exit :out (str/trim (str out))}))

(defn- git-identity
  "A hash of the tree git would commit from `cwd` — every tracked and untracked file that is not
   ignored — or nil when `cwd` is in no git repository.

   Staged into a copy of the repository's index, so the real one is never touched and git's stat
   cache still spares it re-hashing every file."
  [cwd]
  (let [top (git! cwd nil "rev-parse" "--show-toplevel")]
    (when (zero? (long (:exit top)))
      (let [tmp (str (fs/create-temp-file {:prefix "nido-identity-" :suffix ".index"}))]
        (try
          (let [idx (:out (git! cwd nil "rev-parse" "--path-format=absolute" "--git-path" "index"))]
            (if (and (not (str/blank? idx)) (fs/exists? idx))
              (fs/copy idx tmp {:replace-existing true})
              (fs/delete-if-exists tmp))
            (when (zero? (long (:exit (git! (:out top) tmp "add" "-A"))))
              (let [{:keys [exit out]} (git! (:out top) tmp "write-tree")]
                (when (and (zero? (long exit)) (re-matches #"[0-9a-f]{40,64}" out))
                  (digest/sha256-hex (str "git-tree " out))))))
          (finally (fs/delete-if-exists tmp)))))))

(defn ^{:malli/schema [:=> [:cat :Path] [:maybe :string]]}
  code-identity
  "A hash of the tree at `cwd`, or nil.

   In a jj repository it is the tree jj lists. Listing it snapshots the working copy first, so an
   uncommitted edit moves the hash; a description edit, or a rebase that leaves the tree as it was,
   does not. The executable bit and symlinks are part of the listing and move it too. In a plain git
   repository — which jj is never asked about again once it has said it is not one — it is the tree
   git would commit from the working copy: tracked and untracked files, ignored ones left out.

   jj is asked first and git only when jj says `cwd` is in no jj repository. A jj workspace nested
   inside a git checkout is a directory git would silently read as the OUTER repository, so a jj
   failure inside one is nil, never git's answer about somewhere else.

   `jj debug tree` is not an interface jj promises to keep. A listing line with no
   content id yields nil rather than a hash, because a format that dropped the ids
   would otherwise hash two different trees to one value — the single failure this
   must not have. Anything else unexpected (an empty tree, a throw) is nil too."
  [cwd]
  (try
    (let [{:keys [exit out]} (jj/jj! cwd "debug" "tree" "-r" "@")]
      (if (zero? (long exit))
        (when (and (not (str/blank? out))
                   (every? #(re-find content-id %) (str/split-lines out)))
          (digest/sha256-hex out))
        (when-not (zero? (long (:exit (jj/jj! cwd "root"))))
          (git-identity cwd))))
    (catch Throwable _ nil)))

(defn- stratum? [row] (= "stratum" (some-> (:sort row) name str/lower-case)))

(defn ^{:malli/schema [:=> [:cat :DeclaredElements :Path] [:maybe [:map-of :string :string]]]}
  subject-identities
  "Each declared element's identity at `worktree`, by id, as `listing` reads it — or nil when the
   listing is not `:listed`, which it never is in a project that declares no design.

   An identity hashes what was declared about the element — every row listed under its id, with its
   sort and declaration digest — and the content of the file its module pairs with. So it moves
   when the element's declaration or its code moves, and not when anything else does. A file that
   cannot be read hashes as unreadable rather than as absent, so a later reading that can read it
   differs.

   A stratum's reading is about the code of the modules providing it, so their files are part of its
   identity: a `sound` read at one tree is not settled at a tree where its modules moved.

   A module or operation listed with no file has no identity. It is code-bearing, so a listing that
   pairs it with nothing either reads a module not yet realized or dropped code it could not read
   — and the two look alike from here, so it is a doubt, and a doubt settles nothing. A stratum one
   of whose providers is such a module has none either. A role, or a kind no module holds, has no
   code of its own and is identified by its declaration alone."
  [listing worktree]
  (when (= :listed (:status listing))
    (let [content      (memoize
                        (fn [file]
                          (try (digest/sha256-hex
                                (slurp (str (if (fs/absolute? file) file (fs/path worktree file)))))
                               (catch Throwable _ "unreadable"))))
          code-bearing #(contains? #{"module" "operation"} (str/lower-case (name (:sort %))))
          by-id        (group-by :id (:elements listing))
          providers    (fn [rows]
                         (for [row rows :when (stratum? row)
                               id  (get-in row [:refs :provided-by])]
                           [id (some :file (get by-id id))]))]
      (into {}
            (keep (fn [[id rows]]
                    (let [provided (providers rows)]
                      (when-not (or (some #(and (code-bearing %) (nil? (:file %))) rows)
                                    (some (comp nil? second) provided))
                        [id (digest/sha256-hex
                             (pr-str [(sort (map (fn [{:keys [sort declaration file]}]
                                                   [(str sort) (str declaration) (when file (content file))])
                                                 rows))
                                      (sort (map (fn [[pid file]] [pid (content file)]) provided))]))]))))
            by-id))))

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

(defn ^{:malli/schema [:=> [:cat [:vector :any]] :boolean]}
  nothing-to-check?
  "Whether a subject carries nothing a judge could check: every part of it an element of a model
   stating no more than its id and sort — a bare kind or operation, which says what exists and
   nothing about it. Such a subject is shown for what the claims are about and is never owed a
   ruling; a judge has nothing to confirm it by and nothing to refute."
  [content]
  (every? #(and (map? %) (contains? % :sort) (not (contains? % :about))
                (every? (fn [k] (let [v (get % k)] (or (nil? v) (and (coll? v) (empty? v)))))
                        [:hides :interface :readings :plays]))
          content))

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

(defn ^{:malli/schema [:=> [:cat :map :map] [:set :string]]}
  rested-on
  "Every declared element some subject of `record` rests on, its roles played as `effective` plays
   them — the only identities a judgement over `record` needs to keep. Empty for a record with no
   model."
  [record effective]
  (into #{} (mapcat (fn [[_ content]] (rests-on record effective content))) (subjects record)))

(defn- about
  "The ids of the elements `content` is about in `record`'s own model: a claim's :about, an
   element's own id, and the players the record gives any of them."
  [record content]
  (let [ids   (set (mapcat #(cond (contains? % :about) (:about %)
                                  (contains? % :sort)  [(:id %)])
                           (filter map? content)))
        plays (mapcat :plays (filter (comp ids :id) (get-in record [:model :elements])))]
    (into ids plays)))

(defn- context
  "What `record` says around its subject `id` that a judgement of it was made beside: the record's
   own entries for the elements in `ids`, and every other claim of its own about one of them. Both
   are the record's words and not the tree's, so no identity of the code moves when an amendment
   rewrites them."
  [record id ids]
  {:elements (into (sorted-map) (keep #(when (ids (:id %)) [(:id %) %]))
                   (get-in record [:model :elements]))
   :siblings (into #{} (filter #(and (not= id (:id %)) (some ids (:about %))))
                   (get-in record [:model :claims]))})

(defn- cites-as?
  "Whether `judged` cites the intent and the baseline `record` cites, wherever `record` cites one. A
   design resting on a re-surveyed baseline, or on a goal that moved, is judged against a different
   yardstick; a record citing neither asks nothing of the judged one."
  [record judged]
  (every? (fn [k] (or (nil? (get-in record [k :seq]))
                      (= (get-in record [k :seq]) (get-in judged [k :seq]))))
          [:intent :baseline]))

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

(defn- checked?
  "Whether `judgement` confirmed `id` and said what it read to do so."
  [judgement id]
  (and (some #{id} (:confirmed judgement))
       (seq (get-in judgement [:checked-at id]))))

(defn- judged
  "Every judgement on `ledgers` with the record it judged — nil-record judgements dropped — as
   `{:ws-id :judgement :kind :record :subjects :retracted?}`, :kind :baseline for a review and
   :design for a decision."
  [ledgers]
  (for [{:keys [ws-id reviews decisions baselines designs retractions]} ledgers
        :let [retracted (into #{} (map #(get-in % [:retracts :seq])) retractions)
              records   (into {} (map (juxt :seq identity)) (concat baselines designs))]
        [j kind cited] (concat (map (juxt identity (constantly :baseline) :baseline-seq) reviews)
                               (map (juxt identity (constantly :design) :design-seq) decisions))
        :let [r (get records cited)]
        :when r]
    {:ws-id ws-id :judgement j :kind kind :record r :subjects (subjects r)
     :retracted? (contains? retracted cited)}))

(defn- names?
  "Whether `finding` is about the subject `id`: filed under it, or quoting it in a :cites line the
   way the judge prompt renders it — `[id] …`. A finding filed under one id whose counterexample
   quotes a sibling has shown the sibling's text in doubt too, and a subject is settled only while
   nothing at its key has."
  [finding id]
  (or (= id (:claim-id finding))
      (let [bracketed (str "[" id "]")]
        (some #(str/includes? (str %) bracketed) (:cites finding)))))

(defn- chronological [ms] (sort-by (fn [{j :judgement}] [(str (:at j)) (or (:seq j) 0)]) ms))

(defn- bearings
  "Per subject id of `record`, oldest first, every judgement at its content, context, cited
   yardstick and key (`reading`) that bears on it — a finding naming it (`names?`) over any record
   (`:found? true`), or a checked confirmation over one nobody retracted. Empty when nothing can be
   keyed."
  [ledgers record reading effective]
  (if (or (nil? ledgers) (and (nil? (:code-identity reading)) (nil? (:subject-identities reading))))
    {}
    (let [js (judged ledgers)]
      (into {}
            (map (fn [[id content]]
                   (let [needed (rests-on record effective content)
                         ids    (about record content)
                         around (context record id ids)]
                     [id (->> js
                              (filter #(and (= content (get (:subjects %) id))
                                            (= around (context (:record %) id ids))
                                            (cites-as? record (:record %))
                                            (at-key? reading needed (:judgement %))))
                              ;; A judgement doing both found.
                              (keep (fn [{j :judgement :as m}]
                                      (cond
                                        (some #(names? % id) (:findings j)) (assoc m :found? true)
                                        (and (not (:retracted? m)) (checked? j id)) m)))
                              chronological
                              vec)])))
            (subjects record)))))

(defn ^{:malli/schema [:function
                       [:=> [:cat [:maybe [:vector :map]] :map :map] :map]
                       [:=> [:cat [:maybe [:vector :map]] :map :map :map] :map]]}
  settled
  "The subjects of `record` whose latest judgement at `reading` — `{:code-identity
   :subject-identities}`, what the judge is about to read — confirmed them, as `{id {:ws-id :seq}}`,
   naming that judgement.

   A subject is confirmed here when a baseline review or a design decision, on any of `ledgers`,
   named its id in :confirmed and said where it read it (:checked-at), while judging a record nobody
   retracted that carries that subject identically, around the same context, under the same cited
   intent and baseline, at this subject's key — and no judgement at that same content and key has
   found against it since, whether under its id or by quoting it in a finding filed under another.
   The newest judgement at the key that bears on the subject decides,
   ordered by :at whichever ledger it is on: a finding stands until a later confirmation answers
   it, so a record amended elsewhere in answer to a finding leaves the subject to be confirmed
   again rather than checked for ever. A holding verdict settles nothing by itself; only an id its
   judge says it checked, and where, does.

   ONE such confirmation is a reading, not yet a settlement: a round skips only what is here and not
   in `single-readings`. It is kept as the fold because a confirmation standing at the key is also
   what a new confirmation pairs with.

   Only `record`'s own subjects are candidates, but a role's players are read from `effective` — for
   a design, its model laid over its baseline's, since a role it keeps is not restated and a claim
   about it still rests on its players. `record` itself when omitted."
  ([ledgers record reading] (settled ledgers record reading record))
  ([ledgers record reading effective]
   (into {}
         (keep (fn [[id bearing]]
                 (let [latest (peek bearing)]
                   (when (and latest (not (:found? latest)))
                     [id {:ws-id (:ws-id latest) :seq (get-in latest [:judgement :seq])}]))))
         (bearings ledgers record reading effective))))

(defn ^{:malli/schema [:function
                       [:=> [:cat [:maybe [:vector :map]] :map :map] [:set :string]]
                       [:=> [:cat [:maybe [:vector :map]] :map :map :map] [:set :string]]]}
  single-readings
  "The ids `settled` names on ONE reading: the confirmation standing at the key is not itself
   preceded, at that key, by another. A subject settles on the second consecutive confirmation of
   the same content at the same key, so these are still put to the judge.

   A judge is not deterministic at a byte-identical record — the same one has held and broken one
   claim a round apart on text neither round touched — so a single confirmation is a sample. The
   two readings need not be in one run; a finding between them ends the pair."
  ([ledgers record reading] (single-readings ledgers record reading record))
  ([ledgers record reading effective]
   (into #{}
         (keep (fn [[id bearing]]
                 (let [n      (count bearing)
                       latest (peek bearing)
                       before (when (> n 1) (nth bearing (- n 2)))]
                   (when (and latest (not (:found? latest))
                              (or (nil? before) (:found? before)))
                     id))))
         (bearings ledgers record reading effective))))

(defn ^{:malli/schema [:=> [:cat :map] [:set :string]]}
  checked-confirmations
  "The ids `judgement` confirmed and said where it read — the only confirmations that count."
  [judgement]
  (into #{} (filter #(checked? judgement %)) (:confirmed judgement)))

(defn- relation-of
  "What a relation-honest finding about `id` read of `record` besides the subject itself: the
   relation the record declares to its baseline, and whether its :breaks lists `id` — bracketed or
   not, as a judge or an amender may have written it."
  [record id]
  [(get-in record [:baseline :relation])
   (boolean (some #(= id (-> (str %) str/trim (str/replace #"^\[|\]$" "")))
                  (get-in record [:baseline :breaks])))])

(defn- relation? [finding] (= :relation-honest (:check finding)))

(defn- answers?
  "Whether `judgement`, over `record`, answers `finding` against `id`. A relation-honest finding is
   about where :breaks puts the id, which no confirmation of the subject speaks to; it is answered
   by a ruling on the id that the judged record's :breaks agrees with. Any other finding is answered
   by a confirmation of the id that says where it read it."
  [judgement record finding id]
  (if (relation? finding)
    (let [listed? (second (relation-of record id))]
      (boolean (some #(and (= id (str (:id %)))
                           (#{:breaks :stands} (keyword (name (:ruling %))))
                           (= listed? (= :breaks (keyword (name (:ruling %))))))
                     (:relation-rulings judgement))))
    (checked? judgement id)))

(defn ^{:malli/schema [:=> [:cat [:maybe [:vector :map]] [:enum :baseline :design] :map [:maybe :string]] :map]}
  prior-findings
  "For each subject of `record`, a `kind` (:baseline or :design) of record, the newest finding
   against its id by a judgement of another run than `run-id` over a record of the same kind, on
   any of `ledgers`, at any content or key, that no later judgement of that kind has answered — as
   `{id {:ws-id :seq :finding :restated?}}`, :restated? true when the record that judgement read
   differed in what the finding is about.

   What a confirmation now would overturn. At any key, because a finding at another tree or text is
   still the last word said against the id, and whether the text moved since is exactly what a
   reversal has to answer; from other runs only, because a run's own findings already reached its
   amender and came back as its disputes. Its own confirmations still answer, from any run.

   Keyed on the kind as well as the id because a baseline and its design share ids — a design's
   `shape` is not its baseline's, and a refutation of one confirmed the other into a false
   :overturns. Answered (`answers?`) because a finding a later judge confirmed past is not the last
   word any more; shown, it is a reversal the judge is asked to justify a second time. And a
   relation-honest finding is about the id's place under the record's :breaks and its relation as
   much as about the subject, so a record that moved either has restated what it found against."
  [ledgers kind record run-id]
  (let [now (subjects record)]
    (reduce (fn [prior {j :judgement :keys [ws-id retracted?] :as m}]
              (if (not= kind (:kind m))
                prior
                (let [answered (when-not retracted?
                                 (keep (fn [[id {f :finding}]]
                                         (when (answers? j (:record m) f id) id))
                                       prior))
                      prior    (apply dissoc prior answered)]
                  (if (and run-id (= (str run-id) (:run-id j)))
                    prior
                    (reduce (fn [p f]
                              (let [id (some-> (:claim-id f) str not-empty)]
                                (if (contains? now id)
                                  (assoc p id {:ws-id ws-id :seq (:seq j) :finding f
                                               :restated? (or (not= (get now id) (get (:subjects m) id))
                                                              (and (relation? f)
                                                                   (not= (relation-of record id)
                                                                         (relation-of (:record m) id))))})
                                  p)))
                            prior
                            (:findings j))))))
            {}
            (chronological (judged ledgers)))))
