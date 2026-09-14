;; src/nido/review/settled.clj
(ns nido.review.settled
  "Which subjects of a baseline a round need not ask its judge to check.

   The record-side counterpart of `nido.review.cache`, with the same bias: a
   subject re-checked for nothing costs one judge's attention, a subject skipped
   when it should not have been lets a false claim stand under a design. So every
   doubt resolves to checking — no code identity, an unreadable ledger, a review
   that read no single tree, a retracted baseline, a finding at the key.

   Derived, never stored. The two facts it reads are observations only a judge
   can make and both are on the review already: the ids it says it checked, and
   the tree it read. Whether a subject is settled is a fold over those, asked
   afresh each round.

   The key is (subject id, subject content, code identity). The id alone is not a
   subject — an id confirmed and then amended names a claim nobody has checked —
   and the code identity is the WHOLE tree, so any change anywhere unsettles
   everything. The finer key is the set of modelled elements a claim is about,
   which claims do not name yet (FU-162)."
  (:require
   [clojure.string :as str]
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

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  subjects
  "Every subject of a baseline, by id, each as the vector of what carries that id.

   A vector because ids are unique per kind and not across kinds: a claim and a
   module may share one, and a judge confirming that id cannot say which it meant.
   Both are then one subject, settled only while neither has changed. :shape and
   :composition take their field names, as the prompt brackets them."
  [record]
  (let [add (fn [m id v] (update m id (fnil conj []) v))]
    (cond-> (reduce (fn [m s] (if-let [id (:id s)] (add m id s) m))
                    {}
                    (concat (:load-bearing record) (:modules record) (:health record)))
      (some? (:shape record))       (add "shape" (:shape record))
      (some? (:composition record)) (add "composition" (:composition record)))))

(def ^:private ledger-kinds [:baseline-review :baseline :retraction])

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:maybe :map]]}
  ledger
  "{:reviews :baselines :retractions}, each oldest first and stamped with :seq — or
   nil when the workstream is unreadable or any entry of those kinds fails to parse.

   `ws/entries-of` drops what it cannot parse, and here a dropped entry is not
   harmless: the review it held may be the one that found against a subject, and
   the retraction may be the one that unseats a confirmation. So the parsed count is
   held against the index, and a shortfall settles nothing."
  [project ws-id]
  (try
    (when-let [w (ws/read-ws project ws-id)]
      (let [indexed (frequencies (map :kind (:entries w)))
            read    (into {} (map (fn [k] [k (vec (ws/entries-of project ws-id k))]))
                          ledger-kinds)]
        (when (every? #(= (get indexed % 0) (count (read %))) ledger-kinds)
          {:reviews     (read :baseline-review)
           :baselines   (read :baseline)
           :retractions (read :retraction)})))
    (catch Throwable _ nil)))

(defn ^{:malli/schema [:=> [:cat [:maybe :map] :map [:maybe :string]] :map]}
  settled
  "The subjects of `record` settled at `code-identity`, as {id review-seq}.

   A subject is settled when a review that read this code identity, judging a
   baseline nobody retracted whose subject with that id is identical to this one,
   named the id in :confirmed — and no review at that same content and identity has
   raised a finding naming it, before or since. A finding at the key is permanent:
   the subject is checked again only by changing, which is what an amendment does.

   The seq named is the latest confirming review. A holding verdict settles nothing
   by itself; only an id its judge says it checked does."
  [ledger record code-identity]
  (if (or (nil? ledger) (nil? code-identity))
    {}
    (let [retracted (into #{} (map #(get-in % [:retracts :seq])) (:retractions ledger))
          surveyed  (into {} (map (fn [b] [(:seq b) (subjects b)])) (:baselines ledger))
          at-tree   (for [r (sort-by :seq (:reviews ledger))
                          :let [subj (surveyed (:baseline-seq r))]
                          :when (and subj (= code-identity (:code-identity r)))]
                      [r subj])]
      (into {}
            (keep (fn [[id content]]
                    (let [at-key   (filter (fn [[_ subj]] (= content (get subj id))) at-tree)
                          against? (some (fn [[r _]] (some #(= id (:claim-id %)) (:findings r)))
                                         at-key)
                          by       (->> at-key
                                        (filter (fn [[r _]] (and (not (retracted (:baseline-seq r)))
                                                                 (some #{id} (:confirmed r)))))
                                        last)]
                      (when (and by (not against?))
                        [id (:seq (first by))]))))
            (subjects record)))))
