;; src/nido/review/retreat.clj
(ns nido.review.retreat
  "What a superseding record GAVE UP relative to the one it supersedes.

   A record loop puts an agent in front of a judge with one instruction — make
   these findings go away — and the cheapest way to satisfy that is never to
   correct the record. It is to claim less. Drop the load-bearing property the
   code refuted, soften `:revisit` to `:within`, lower the effort, un-mark the
   observation whose `:invisibly-incomplete?` was the only thing stopping it from
   being spun out. Every one of those makes the next round quieter, and none of
   them is a repair.

   The diff review needs no equivalent, because its findings cite code that tests
   run against: a fixer that deletes the behaviour rather than fixing it breaks
   something. A record has no such floor. This namespace is the floor.

   Two deliberate limits:

   1. A retreat is NEVER treated as forbidden. Over-claiming is a real defect and
      the honest repair for it IS to claim less — a baseline that asserted a
      property the code does not have should lose it. So this reports, and the
      caller decides; what the caller must not do is let one pass silently.

   2. Pure, and reaching nothing but the shared model's readings, which say what a
      record claims whichever era wrote it. Whether a retreated baseline has
      fallen below the point where its own round would still run is a question
      for the predicate that owns it (`record/baseline-round-worth-running?`),
      asked by the stage that has both records in hand. Reaching for it here
      would invert that ownership to save a line. A design has no such floor:
      its decision round is never skipped."
  (:require
   [clojure.string :as str]
   [nido.coordinator.report :as report]
   [nido.coordinator.report.model :as model]))

(defn- retreat
  [what detail]
  {:what what :detail detail})

(defn- fewer
  "A count that fell further than `named` — how many of the dropped are already reported by id —
   as one retreat, or nil. A fall the named drops account for is those drops counted a second time."
  ([what label prev curr] (fewer what label prev curr 0))
  ([what label prev curr named]
   (let [p (count prev) c (count curr)]
     (when (> (- p c) named)
       (retreat what (str label " " p " → " c))))))

(defn- rank-drop
  "A move DOWN an ordinal, as one retreat, or nil. `order` is cheapest-first, so
   an unknown value on either side yields nil rather than a false alarm — a
   vocabulary this does not know about is not evidence of a retreat."
  [what order prev curr]
  (let [idx (zipmap order (range))
        p   (idx prev) c (idx curr)]
    (when (and p c (< c p))
      (retreat what (str prev " → " curr)))))

;; ── What the round asked for ────────────────────────────────────────────────

(defn- asks
  "What `findings` — the round's judge findings the amendment answers — asked of the record:
   `:at`, each subject id a finding named mapped to the finding's `:claim` texts; `:needs`, each
   id to the repairs its findings asked for; `:cites`, each id to the places its findings pointed
   at (`:evidence` and `:cites`); and `:checks`, every check a finding was filed under, which a
   finding is only when that check broke."
  [findings]
  (let [texts (fn [v] (keep #(when-not (str/blank? (str %)) (str %)) (if (sequential? v) v [v])))
        add   (fn [m ids vs] (reduce #(update %1 %2 (fnil (comp vec distinct into) []) vs) m ids))]
    (reduce (fn [a f]
              (let [ids (into [] (comp (keep #(not-empty (str %))) (distinct))
                              (cons (:claim-id f) (:claim-ids f)))]
                (cond-> (-> a
                            (update :at add ids (texts (:claim f)))
                            (update :needs add ids (texts (:needs f)))
                            (update :cites add ids (concat (texts (:evidence f)) (texts (:cites f)))))
                  (:check f) (update :checks conj (:check f)))))
            {:at {} :needs {} :cites {} :checks #{}}
            findings)))

(def ^:private check-orders
  "Which retreats a broken check asks for, by the field each check judges. relation-honest rules on
   the declared relation and the shape that needs it; goal-served on whether a strictly smaller
   design would do; routing-coherent on where the health goes; decomposable and stratified on the
   plan the change lands by."
  {:relation-honest  #{:baseline-relation-softened :shape}
   :goal-served      #{:effort-lowered :rejected-fewer}
   :routing-coherent #{:route-deferred}
   :decomposable     #{:phases-dropped}
   :stratified       #{:phases-dropped}})

(defn- answered
  "`r` marked `:ordered` when the round asked for it — a finding at `id`, or a broken check that
   judges `field` — with the finding's claim as `:finding` and its stated repair as `:answering`.

   An ordered retreat is still reported, because a narrowing is a narrowing however sanctioned; it
   is left out of the unasked count (`unasked`), which is the one a reader is meant to act on.
   Linking it is what lets a reader tell the honest repair from the silent one without re-deriving
   the run. Asked again under another id, the first finding found stays the one it names."
  [r {:keys [at needs checks]} id field]
  (let [found  (some-> id at)
        broken (filter #(contains? (check-orders %) field) checks)]
    (cond-> r
      (or found (seq broken)) (assoc :ordered true)
      (seq found)             (update :finding #(or % found))
      (seq broken)            (assoc :checks (vec (sort broken)))
      (seq (some-> id needs)) (update :answering #(or % (needs id))))))

;; ── Narrowing in place ──────────────────────────────────────────────────────

(def ^:private exception-re
  #"(?i)\b(?:other than|except|excepting|unless|excluding|apart from|aside from|save for|but not|outside|only (?:when|if|where)|does not cover|not covered|not hidden|does not hide|accepted as)\b")

(def ^:private hedge-re
  #"(?i)\b(?:can|could|may|might|typically|usually|generally|where possible)\b(?!['’]t|\s*not)")

(def ^:private disjunct-re
  #"(?i)\bor\b")

(def ^:private scope-re
  "A condition a commitment is restricted by — `a read whose scope holds no summaries`. Not after
   `only`, which `exception-re` already counts."
  #"(?i)(?<!only )\b(?:whose|when|whenever|wherever|while|if|once|provided|as long as|so long as|in which|for which|under which)\b")

(def ^:private absolute-re
  #"(?i)\b(?:every|all|always|exactly|each|never|none|nothing|any|whatever|whichever)\b")

(defn- clauses
  "`s` cut at sentence and semicolon boundaries — the commitments a statement makes one by one."
  [s]
  (into [] (comp (map str/trim) (remove str/blank?)) (str/split (str s) #"(?<=[.!?;])\s+")))

(defn- items
  "`s` cut at every list boundary — punctuation and `or` — lowercased: the counterexamples a
   falsifier lists, one by one, whichever way the list is joined."
  [s]
  (into [] (comp (map (comp str/lower-case str/trim)) (remove str/blank?))
        (str/split (str s) #"(?i)[,;.!?]|\bor\b")))

(defn- opens-with?
  [re s]
  (.lookingAt (re-matcher re s)))

(defn- qualifying
  "The part of falsifier `after` a qualifier in it can narrow what `before` listed.

   A falsifier's polarity is the statement's inverted: what it lists are counterexamples, so one it
   gains makes the claim stronger. When every item of `before` is still listed, each new item is a
   new counterexample, and an exception or a scope inside it qualifies that counterexample alone —
   `…, or a stored node field other than blob` refutes MORE than before. Such an item is left out.
   An item that OPENS with a qualifier (`…, unless the lease lapsed`) is a condition on the list,
   and stays. Where an item of `before` was reworded or dropped, all of `after`."
  [before after]
  (let [was (set (items before))
        now (items after)]
    (if (every? (set now) was)
      (str/join ", " (filter #(or (was %) (some (fn [re] (opens-with? re %)) [exception-re scope-re hedge-re]))
                             now))
      after)))

(defn- words
  [s]
  (set (re-seq #"[a-z][a-z0-9_-]{3,}" (str/lower-case (str s)))))

(defn- relocated?
  "Whether every clause of `before` that `after` no longer states is in `parked` — the record's
   :unknowns or :open, or the claims `after` newly cites — by most of its words. A commitment moved
   into what the record declares it does not know is declared, not dropped, and one moved into a
   claim cited by id is still made; the amender usually rewords it on the way."
  [before after parked]
  (let [now  (str/lower-case (str after))
        gone (remove #(str/includes? now (str/lower-case %)) (clauses before))
        have (words parked)]
    (boolean
     (and (seq gone)
          (every? (fn [c] (let [w (words c)]
                            (and (seq w) (>= (count (filter have w)) (* 0.6 (count w))))))
                  gone)))))

(defn- narrowing
  "How `after` commits to less than `before` as `field`, as the signals that say so, or nil.

   Read off the text because a claim's identity is its id and nothing else about it survives an
   amender: the wording changes whether the claim was corrected or quietly given up. Six signals,
   each one a way the counterexample set shrinks without the claim going away — a disjunct lost
   (`a read or a write` → `a write`), an exception added (`… other than after a lease lapsed`), a
   clause lost, a hedge added (`applies` → `can apply`), a scope condition put on an absolute
   commitment (`gets exactly today's answers` → `a read whose scope holds none answers exactly as
   today`), an absolute quantifier lost (`every` → `a`). On :falsified-by a qualifier counts only
   where it can narrow the list (`qualifying`). A rewording that moves none of them is not
   reported, and a narrowing worded so it moves none of them is missed: this measures, it does
   not read."
  [field before after]
  (let [before (str before) after (str after)
        n      (fn [re s] (count (re-seq re s)))
        q      (if (= :falsified-by field) (qualifying before after) after)]
    (when (and (not (str/blank? before)) (not (str/blank? after)) (not= before after))
      (not-empty
       (cond-> []
         (< (n disjunct-re after) (n disjunct-re before))    (conj :disjunct-lost)
         (> (n exception-re q) (n exception-re before))      (conj :exception-added)
         (< (count (clauses after)) (count (clauses before))) (conj :clause-lost)
         (> (n hedge-re q) (n hedge-re before))              (conj :hedged)
         (and (pos? (n absolute-re before))
              (> (n scope-re q) (n scope-re before)))        (conj :scope-restricted)
         (< (n absolute-re after) (n absolute-re before))    (conj :quantifier-lost))))))

(def ^:private signal-words
  {:disjunct-lost "lost a disjunct" :exception-added "gained an exception"
   :clause-lost "lost a clause" :hedged "was hedged"
   :scope-restricted "was restricted in scope" :quantifier-lost "lost an absolute quantifier"})

(defn- clip
  [s]
  (let [s (str/replace (str s) #"\s+" " ")]
    (if (> (count s) 140) (str (subs s 0 140) "…") s)))

(defn- cites?
  [s id]
  (boolean (re-find (re-pattern (str "(?i)(?<![\\w-])" (java.util.regex.Pattern/quote id) "(?![\\w-])")) (str s))))

(defn- narrowings
  "One `:narrowed` per field of a subject both records hold, by id, that commits to less (`narrowing`)
   — `subjects` reads `[[label id field text] ...]` off a record, `parked` the text it declares
   unknown, `asked` what the round asked for (`asks`). A narrowing whose only loss is clauses that
   reappear in `parked` is `:relocated`; one the round asked for is `:ordered` (`answered`).

   A subject with no id of its own — the record's shape — is reported and asked for under its
   field's name, `\"shape\"`, the claim-id a judge files a finding against it under. A shape is also
   asked for by a finding at any claim its new text names as `[id]`: a shape is a composition of
   its claims, and a repair to one is often carried by restating how the shape composes them.

   One whose only losses are clauses and disjuncts that reappear in subjects of `curr` it newly
   cites by id (`… (see one-covering-rule)`) is not reported at all: the record still makes the
   commitment, under the id it now points at, and is judged there."
  [subjects prev curr parked asked]
  (let [now   (into {} (map (fn [[label id field text]] [[label id field] text])) (subjects curr))
        texts (reduce (fn [m [_ id _ text]] (cond-> m id (update id str " " text))) {} (subjects curr))
        moved (fn [id before after]
                (str/join " " (for [[cid t] texts
                                    :when (and (not= cid id) (cites? after cid) (not (cites? before cid)))]
                                t)))]
    (for [[label id field before] (subjects prev)
          :let [after   (get now [label id field])
                signals (narrowing field before after)
                by      (or id (name field))
                named   (when (= :shape field) (map second (re-seq #"\[([^\[\]\s]+)\]" (str after))))]
          :when (and signals
                     (not (and (every? #{:disjunct-lost :clause-lost} signals)
                               (relocated? before after (moved id before after)))))]
      (cond-> (reduce #(answered %1 asked %2 field)
                      {:what    :narrowed
                       :detail  (str label (when id (str " " id "'s")) " " (name field) " "
                                     (str/join ", " (map signal-words signals))
                                     ": \"" (clip before) "\" → \"" (clip after) "\"")
                       :id      by
                       :field   field
                       :signals signals
                       :from    before
                       :to      after}
                      (cons by named))
        (and (= [:clause-lost] signals) (relocated? before after parked))
        (assoc :relocated true)))))

;; ── Baseline ────────────────────────────────────────────────────────────────

(def ^:private cite-re
  "A file and its lines (groups 1–3); a bare `:N`, the lines of the nearest file before it (4–5); and
   `, N` straight after a citation, more lines of that file (6–7). The file starts at a word
   character, so the `(` of `(foo.clj:68)` is not part of its name."
  #"([A-Za-z0-9_][\w./-]*\.[A-Za-z]+):(\d+)(?:-(\d+))?|(?<![\w.]):(\d+)(?:-(\d+))?|,[ \t]*(\d+)(?:-(\d+))?(?![ \t]*[A-Za-z])")

(defn- locations
  "Every PLACE an evidence string points at — [file first-line last-line].

   More than one, because an annotated citation names its file once and then
   refers to further lines in it bare: `foo.clj:732 (ladder inlined) joined to
   :746 at :760-765` points at three places in foo.clj, and reading only the
   first would call the other two lost the moment they stopped being written out
   in full. A bare line belongs to the NEAREST file named before it, and every
   `file:line` names a file afresh: `a.clj:1, b.clj:2,9` is a.clj:1, b.clj:2 and
   b.clj:9. Bound to the first file instead, a widened multi-file citation read as
   places nobody cited, and the places it really dropped went unreported. A `, N`
   counts only straight after a citation and before no word, so `a.clj:5, 3 callers` is
   one place.

   Compared by place rather than by text because the text is not stable and is
   not meant to be: an amender that keeps a citation and explains it turns
   `foo.clj:669` into `foo.clj:669 (period_dialogue_time groups on ...)`, and one
   that sharpens a line into the range around it turns `:202` into `:198-205`.
   Both still point where they pointed. Comparing the strings called eight such
   improvements a loss in one round, which is this detector inverted — a human
   reading eight non-events is a human who will miss the ninth."
  [s]
  (let [m (re-matcher cite-re (str s))
        place (fn [file a b] [file (parse-long a) (parse-long (or b a))])]
    (loop [file nil end -1 out []]
      (if-not (.find m)
        (distinct out)
        (let [g #(.group m (int %))]
          (cond
            (g 1)                              (recur (g 1) (.end m) (conj out (place (g 1) (g 2) (g 3))))
            (and file (g 4))                   (recur file (.end m) (conj out (place file (g 4) (g 5))))
            (and file (g 6) (= end (.start m))) (recur file (.end m) (conj out (place file (g 6) (g 7))))
            :else                              (recur file end out)))))))

(defn- same-file?
  "Whether two cited paths name one file: equal, or one a path suffix of the other. A judge cites
   `src/main/brian/content/ingest.clj` where the record says `content/ingest.clj`."
  [a b]
  (or (= a b) (str/ends-with? a (str "/" b)) (str/ends-with? b (str "/" a))))

(defn- covers?
  [[file start end] [f s _]]
  (and (same-file? file f) (<= start s end)))

(defn- baseline-evidence
  [b]
  (mapcat locations (mapcat :evidence (:load-bearing b))))

(defn- overlaps?
  [[file a b] [f c d]]
  (and (same-file? file f) (<= a d) (<= c b)))

(defn- claim-places
  [claim]
  (distinct (mapcat locations (:evidence claim))))

(defn- given-up
  "Of `lost` — places claim `p` cited that nothing cites any more — the ones its successor `c` gave
   up rather than re-pointed. `judged` is the judge's evidence strings against this claim this round.

   An amender answering a finding moves the citation onto the site the finding named, and a moved
   citation shares nothing with the place it left: `:166` → `:159` reads exactly like a loss. Two
   things tell them apart. A claim that still cites every file it cited, at least as many times,
   has lost nothing — the count and the files are what survive a re-pointing. Where either fell,
   each newly cited place the judge named answers for one lost place in its file, the nearest: that
   is the citation the finding moved."
  [p c lost judged]
  (let [was   (claim-places p)
        now   (claim-places c)
        files #(set (map first %))]
    (if (and (>= (count now) (count was))
             (every? (files now) (files was)))
      []
      (let [judge (mapcat locations judged)
            moved (filter (fn [loc] (and (not-any? #(overlaps? % loc) was)
                                         (some #(overlaps? % loc) judge)))
                          now)]
        (reduce (fn [lost [f s _]]
                  (if-let [near (->> lost
                                     (filter #(same-file? f (first %)))
                                     (sort-by #(abs (- s (second %))))
                                     first)]
                    (vec (remove #{near} lost))
                    lost))
                (vec lost) moved)))))

(defn- evidence-lost
  "Citations the old record made that lost a place: one `{:claim-id :citation :places}` per
   evidence string of a claim in `prev`, `:places` being the ones nothing in `curr`'s load-bearing
   properties points at any more and the claim's successor did not re-point (`given-up`).

   Per string, not per place, because the string is what the amender kept or deleted: one grep
   line naming three sites in a file is one decision, and reporting it as three weakenings put a
   single narrowing at the top of the headline three times. A place is still cited when ANY
   remaining reference in that file covers its line, so a widened range keeps everything inside
   it. A claim with no id, or one the new record no longer makes, has no successor to re-point to:
   everything only it cited is lost."
  [prev curr judged]
  (let [now   (vec (baseline-evidence curr))
        lost? (fn [loc] (not-any? #(covers? % loc) now))
        kept  (into {} (keep #(when (:id %) [(:id %) %])) (:load-bearing curr))]
    (->> (:load-bearing prev)
         (mapcat (fn [p]
                   (let [lost (filter lost? (claim-places p))
                         gone (set (if-let [c (some-> (:id p) kept)]
                                     (given-up p c lost (get judged (:id p)))
                                     lost))]
                     ;; A place two of the claim's strings name is reported under the first only.
                     (first
                      (reduce (fn [[out seen] s]
                                (let [places (remove seen (filter gone (distinct (locations s))))]
                                  (if (seq places)
                                    [(conj out {:claim-id (:id p) :citation s :places (vec places)})
                                     (into seen places)]
                                    [out seen])))
                              [[] #{}] (distinct (map str (:evidence p))))))))
         (sort-by (comp (juxt first second) first :places)))))

(def ^:private relocation-asked
  "A finding's `:needs` asking for evidence to go somewhere else rather than away. Read off the
   judge's prose, so it can miss; a miss leaves the retreat reading as an ordinary requested
   narrowing, never as no request at all."
  #"(?i)relocat|re-?file|\bmov(?:e|ing)\b|\bas (?:bounded|health)|\binto (?:the )?(?:health|bounded)")

(def ^:private narrowing-asked
  "A finding's `:needs` asking for the claim to say or cite less. Read off the judge's prose, so it
   can miss; a miss leaves the drop reading as one made while answering the finding, which a reader
   then checks against the need it quotes — never as a request nobody made."
  #"(?i)\b(?:narrow|qualif|restrict|weaken|withdraw|drop|remov|delet|exclud|no longer)")

(defn- place-str
  [[file start end]]
  (str file ":" start (when (not= start end) (str "-" end))))

(defn- dropped-evidence
  "One `:evidence-dropped` per lost citation, worded by why it might have gone — the only thing
   that tells a narrowing the judge asked for from one nobody asked for, which otherwise read
   alike in the headline.

   `asked` is what the round asked for (`asks`). A retreat at a claim with stated repairs carries
   them as `:answering`. Those repairs make the drop `:ordered` only when one asks the claim to say
   less (`narrowing-asked`) or a repair or a place the finding cited names a dropped place: a need
   asking for an ADDITION requested no removal, and the drop made while answering it is one nobody
   asked for. A drop at a claim a finding objected to without stating a repair answers that
   objection and is `:ordered`; one at a claim nobody found against is unasked.
   `elsewhere` is every place the new record cites anywhere at all, health and prose included: a
   place still in it was moved out of the claim, not removed, and is `:relocated`. A place in none
   of it, at a claim whose finding asked for evidence to be relocated, is `:relocation-dropped?` —
   the amender deleted what it was told to move, which no finding asked for."
  [{:keys [claim-id citation places]} {:keys [at needs cites]} elsewhere]
  (let [found?    (contains? at claim-id)
        asked     (get needs claim-id)
        named?    (some (fn [loc] (some #(overlaps? % loc)
                                        (mapcat locations (concat asked (get cites claim-id)))))
                        places)
        requested? (or named? (some #(re-find narrowing-asked %) asked))
        removed?  (not-every? (fn [loc] (some #(covers? % loc) elsewhere)) places)
        relocate? (and removed? (some #(re-find relocation-asked %) asked))
        where     (str/join ", " (map place-str places))
        detail    (cond
                    (nil? claim-id)
                    (str where " is cited by no load-bearing property any more")

                    (not removed?)
                    (str "claim " claim-id " no longer cites " where
                         "; the record still cites it outside the load-bearing claims")

                    relocate?
                    (str "claim " claim-id " no longer cites " where
                         ", which its finding asked to relocate and the record no longer cites anywhere — "
                         (str/join " / " asked))

                    (and (seq asked) requested?)
                    (str "claim " claim-id " no longer cites " where
                         " — narrowed at its finding's request: " (str/join " / " asked))

                    (seq asked)
                    (str "claim " claim-id " no longer cites " where
                         ", dropped while answering a finding that asked for: " (str/join " / " asked))

                    found?
                    (str "claim " claim-id " no longer cites " where
                         ", and a finding at it this round objected to the claim")

                    :else
                    (str "claim " claim-id " no longer cites " where
                         ", and no finding at it this round asked it to"))]
    (cond-> (assoc (retreat :evidence-dropped detail)
                   :citation citation :places (mapv place-str places))
      claim-id               (assoc :claim-id claim-id)
      (seq asked)            (assoc :answering (vec asked))
      (seq (get at claim-id)) (assoc :finding (get at claim-id))
      relocate?              (assoc :relocation-dropped? true)
      (not removed?)         (assoc :relocated true)
      (and removed? (not relocate?) (if (seq asked) requested? found?)) (assoc :ordered true))))

(defn- blanked
  "The fields of `p` that said something and say nothing in `c`.

   Present-and-blank, not absent. A field that is simply gone is either a schema
   violation the ledger already refuses or a record from an era before that
   field existed, and calling either one a weakening reports a loss that never
   happened. Blank is the case an amender can actually reach and the ledger
   will actually accept."
  [subject p c fields]
  (for [f fields
        :when (and (not (str/blank? (str (get p f))))
                   (contains? c f)
                   (str/blank? (str (get c f))))]
    (retreat :emptied (str subject " " (name f) " now says nothing"))))

(defn- emptied
  "Fields that said something and now say nothing.

   Every other detector here measures something with a natural cardinality —
   how many modules, which ids, which file:line references. A baseline's prose
   fields have none, so blanking one is claiming less in the one way nothing
   counted: the schema types them `string?`, so \"\" validates, the ledger
   accepts it, and the round that follows has nothing left to refute.

   Only blank-from-non-blank. Length is not measured and must not be — an
   amender tightening a claim is doing the job, and a length threshold would
   report the good version of the edit alongside the bad one. What a shorter
   field gave up is read off its wording, by `narrowings`; blank is the one case
   that needs no reading."
  [prev curr]
  (let [gone blanked
        by-id (fn [xs] (into {} (keep #(when (:id %) [(:id %) %])) xs))
        within (fn [k subject fields]
                 (let [cm (by-id (get curr k))]
                   (mapcat (fn [[id p]]
                             (when-let [c (cm id)]
                               (gone (str subject " " id "'s") p c fields)))
                           (sort-by key (by-id (get prev k))))))]
    (concat
     (gone "the baseline's" prev curr [:shape :composition :bounded-by :area])
     (within :modules      "module" [:module :hides :interface])
     (within :load-bearing "claim"  [:property :falsified-by]))))

(defn- as-survey
  "A baseline in the shape the measures below read — modules and load-bearing properties. One in
   the shared model is read out through its elements and claims: an element is a module and a claim
   a property, under the same ids. So a retreat is measured alike whichever era either record was
   written in, including a survey amended into a model."
  [b]
  (if (contains? b :model)
    (-> b
        (assoc :modules (mapv (fn [{:keys [id hides interface readings]}]
                                (cond-> {:id id :module id}
                                  hides          (assoc :hides hides)
                                  interface      (assoc :interface interface)
                                  (seq readings) (assoc :readings readings)))
                              (model/elements b))
               :load-bearing (mapv (fn [{:keys [id statement falsified-by readings read-at drift]}]
                                     (cond-> {:id id :property statement}
                                       falsified-by   (assoc :falsified-by falsified-by)
                                       (seq readings) (assoc :readings readings)
                                       (seq read-at)  (assoc :evidence read-at)
                                       drift          (assoc :drift drift)))
                                   (model/claims b)))
        (dissoc :model))
    b))

(defn- survey-subjects
  "What a baseline in survey shape commits to, as `narrowings` reads it: its shape, each module's
   :hides and each claim's statement and :falsified-by, under their ids."
  [b]
  (concat
   [["the baseline's" nil :shape (:shape b)]]
   (for [m (:modules b) :when (:id m)]
     ["module" (:id m) :hides (:hides m)])
   (for [c (:load-bearing b) :when (:id c)
         [field k] [[:statement :property] [:falsified-by :falsified-by]]]
     ["claim" (:id c) field (get c k)])))

(defn ^{:malli/schema [:=> [:cat :map] [:set :string]]}
  claim-ids
  "The ids of baseline `b`'s claims as `baseline-retreats` reads them — the only ids a withdrawal
   can name."
  [b]
  (into #{} (keep :id) (:load-bearing (as-survey b))))

(defn- withdrawals
  "The claims of `prev` that `curr` no longer makes and `withdrawn` — `{id reason}` — gives a reason
   for, as `{id reason}`. A reason for a claim `curr` still makes withdraws nothing."
  [prev curr withdrawn]
  (let [gone (set (remove (set (keep :id (:load-bearing curr)))
                          (keep :id (:load-bearing prev))))]
    (into (sorted-map) (filter (comp gone key)) withdrawn)))

(defn ^{:malli/schema [:=> [:cat :map :map
                            [:? [:maybe [:map-of :string :string]]]
                            [:? [:maybe [:sequential :map]]]]
                      :any]}
  baseline-retreats
  "Everything the superseding baseline claims less of than the one before it.

   Health observations are compared by :id, which is stable by schema — that is
   what :id is for, and what lets a dropped observation be named rather than
   counted. Load-bearing properties are compared by id where they carry one, and
   by what survives rewording either way: how many there are, and which file:line
   references nothing cites any more and no claim re-pointed. A property genuinely
   corrected keeps pointing at the code that corrected it; one quietly dropped
   takes its evidence with it.

   `withdrawn`, `{claim-id reason}`, names claims removed on purpose: ones the
   caller let the amender take out, with the reason it gave. Each one the new record
   no longer makes is one `:claim-withdrawn` carrying that reason, and is measured
   nowhere else — the count, evidence and readings that left with it are that
   withdrawal, and reporting them again would call a stated removal an unexplained
   weakening four times over.

   A health observation dropped with a reason in `withdrawn` carries it. A claim, module or the shape both records hold, by id, is read for narrowing in place
   (`narrowings`): the retreat that keeps every id and claims less.

   `findings` are the round's judge findings the amendment answers, each read for its `:claim-id`,
   `:claim`, `:evidence` and `:needs`. The evidence is where the judge pointed — the sites an
   amender re-points a citation onto, which `evidence-lost` does not count as dropped — and the
   rest is what each retreat is read against (`answered`), so one that answers a finding says so
   and leaves the unasked count."
  ([prev curr] (baseline-retreats prev curr nil nil))
  ([prev curr withdrawn] (baseline-retreats prev curr withdrawn nil))
  ([prev curr withdrawn findings]
   (let [by-claim (fn [k]
                    (reduce (fn [m f]
                              (let [id (str (:claim-id f))
                                    vs (keep #(when-not (str/blank? (str %)) (str %))
                                             (let [v (get f k)] (if (sequential? v) v [v])))]
                                (cond-> m
                                  (and (not (str/blank? id)) (seq vs))
                                  (update id (fnil (comp vec distinct into) []) vs))))
                            {} findings))
         judged    (by-claim :evidence)
         asked     (asks findings)
         elsewhere (into [] (comp (filter string?) (mapcat locations))
                         (tree-seq coll? seq curr))
         prev (as-survey prev)
         curr (as-survey curr)
         taken (withdrawals prev curr withdrawn)
         prev (update prev :load-bearing #(vec (remove (comp taken :id) %)))
         ;; By id now, not by name. A module renamed while the decomposition grew
         ;; read as a module lost, because its own description was its identity and
         ;; the amender rewrites descriptions.
         id-or-name (fn [m] (or (:id m) (:module m)))
         pmods (into {} (map (juxt id-or-name identity)) (:modules prev))
         cmods (set (map id-or-name (:modules curr)))
         mods-gone (remove cmods (keys pmods))
         ;; Readings are where the analysis lives, so losing one loses analysis
         ;; whatever the prose still says. There is no id on a claim to track a
         ;; reading through a rewrite, so what is counted is how many readings
         ;; the baseline carries and which perspectives it still applies at all —
         ;; both of which survive an amender rewriting every word.
         readings (fn [b] (concat (mapcat :readings (:load-bearing b))
                                  (mapcat :readings (:modules b))))
         plenses (set (map :lens (readings prev)))
         clenses (set (map :lens (readings curr)))
         pids  (into {} (map (juxt :id identity)) (:health prev))
         cids  (into {} (map (juxt :id identity)) (:health curr))
         gone  (remove (set (keys cids)) (keys pids))
         unveiled (for [[id p] pids
                        :let [c (cids id)]
                        :when (and c (:invisibly-incomplete? p)
                                   (not (:invisibly-incomplete? c)))]
                    id)
         ev-gone (evidence-lost prev curr judged)
         claims-gone (let [cids (set (keep :id (:load-bearing curr)))]
                       (sort (remove cids (keep :id (:load-bearing prev)))))]
     (vec
      (concat
       (for [[id why] taken]
         (retreat :claim-withdrawn (str "claim " id " was removed: " why)))
       (keep identity
             [(fewer :load-bearing-fewer "load-bearing" (:load-bearing prev) (:load-bearing curr)
                     (count claims-gone))
              (fewer :modules-fewer "modules" (:modules prev) (:modules curr) (count mods-gone))
              (fewer :read-narrowed "read" (:read prev) (:read curr))])
       ;; Named only when the count also fell. A module's identity is its own
       ;; descriptive name, which the amender rewrites like everything else —
       ;; "codex — the judge launch" became "codex — the read-only judge launch"
       ;; while the decomposition GREW by two, and comparing the strings called
       ;; that a module lost. The count is the signal that survives a rewording;
       ;; the names are the best detail available once it fires, and offering
       ;; them when it has not is asserting a loss the data does not support.
       ;; With ids the count gate is no longer needed to suppress renames — a
       ;; dropped id is a dropped module whatever else changed, and the count
       ;; reports only a fall the ids do not account for.
       (for [m (sort mods-gone)]
         (answered (retreat :module-dropped
                            (str "module " m " is no longer part of the decomposition"))
                   asked m :module-dropped))
       (for [c claims-gone]
         (answered (retreat :claim-dropped (str "claim " c " is no longer made"))
                   asked c :claim-dropped))
       (keep identity
             [(fewer :readings-fewer "readings" (readings prev) (readings curr))])
       (for [l (sort (remove clenses plenses))]
         (retreat :lens-abandoned
                  (str "nothing is read through " (namespace l) "/" (name l)
                       " any more; a perspective dropped is analysis dropped")))
       (for [id (sort gone)]
         (answered (retreat :health-dropped
                            (str "observation " id " is no longer recorded"
                                 (when-let [why (get withdrawn id)] (str ": " why))))
                   asked id :health-dropped))
       ;; The veto is the whole reason :invisibly-incomplete? exists — an
       ;; observation carrying it can never be spun out. Clearing the flag is
       ;; therefore not a baseline correction with a side effect; it is the one
       ;; edit that converts a defect into a deferrable.
       (for [id (sort unveiled)]
         (retreat :veto-lifted
                  (str "observation " id " was invisibly-incomplete? and no longer is")))
       (map #(dropped-evidence % asked elsewhere) ev-gone)
       (narrowings survey-subjects prev curr (str/join " " (:unknowns curr)) asked)
       (emptied prev curr))))))

;; ── Design ──────────────────────────────────────────────────────────────────

(def effort-order   [:XS :S :M :L :XL])
(def baseline-order [:within :extends :revisit])
(def standing-order [:conforms :extends :challenges])

(defn- design-subjects
  "What a design commits to, as `narrowings` reads it: its shape, each element's :hides and each
   claim's statement and :falsified-by, under their ids."
  [d]
  (concat
   [["the design's" nil :shape (:shape d)]]
   (for [e (model/elements d) :when (:id e)]
     ["element" (:id e) :hides (:hides e)])
   (for [c (model/claims d) :when (:id c)
         field [:statement :falsified-by]]
     ["claim" (:id c) field (get c field)])))

(defn ^{:malli/schema [:=> [:cat :map :map [:? [:maybe [:sequential :map]]]] :any]}
  design-retreats
  "Everything the superseding design commits to less strongly than the one
   before it.

   The three relations are ordinals, cheapest first, and every one of them is a
   claim about how much of the existing system this change is willing to move.
   Sliding down one is exactly how a design stops being refutable while still
   reading like a design.

   Routes are read in one direction only. :fix-here is the conservative
   destination — it says you are doing the work — so moving TO it is not a
   retreat, and moving AWAY from it to any form of not-doing-it is. Promising
   MORE work is not something this can call a retreat without lying about which
   way the doctrine points. A deferral carries the route's own :why and :ref.

   A claim, element or the shape both designs hold, by id, is read for narrowing
   in place (`narrowings`): a claim reworded keeps its id and is no loss, and one
   that kept its id and lost a disjunct or gained an exception claims less.

   `findings` are the decision round's findings the amendment answers. A retreat
   at an id a finding named, or on a field a broken check judges
   (`check-orders`), is the one the round asked for (`answered`)."
  ([prev curr] (design-retreats prev curr nil))
  ([prev curr findings]
   (let [asked   (asks findings)
         proutes (into {} (map (juxt :health-id :to)) (:routes prev))
         croutes (into {} (map (juxt :health-id identity)) (:routes curr))
         ids     #(into #{} (keep :id) (model/claims %))
         gone    (sort (remove (ids curr) (ids prev)))
         order   (fn [r] (when r (answered r asked nil (:what r))))]
     (vec
      (concat
       (keep order
             [(rank-drop :effort-lowered effort-order (:effort prev) (:effort curr))
              (rank-drop :baseline-relation-softened baseline-order
                         (get-in prev [:baseline :relation]) (get-in curr [:baseline :relation]))
              (rank-drop :standing-softened standing-order
                         (get-in prev [:standing :relation]) (get-in curr [:standing :relation]))
              (fewer :invariants-fewer "invariants" (model/claims prev) (model/claims curr) (count gone))
              (fewer :rejected-fewer "rejected alternatives" (:rejected prev) (:rejected curr))
              (when (and (seq (:phases prev)) (empty? (:phases curr)))
                (retreat :phases-dropped
                         (str (count (:phases prev)) " phases → none; the change now claims one landing")))])
       ;; By id, where claims have one; an invariant with no id can only be counted, above.
       (for [c gone]
         (answered (retreat :claim-dropped (str "claim " c " is no longer made")) asked c :claim-dropped))
       (for [[hid {:keys [to why ref]}] (sort-by key croutes)
             :let [was (proutes hid)]
             :when (and was (= :fix-here was) (not= :fix-here to))]
         (order (cond-> (retreat :route-deferred
                                 (str hid ": :fix-here → " to
                                      (when ref (str " (" ref ")"))
                                      (when-not (str/blank? (str why)) (str " — " why))))
                  ref (assoc :ref ref)
                  (not (str/blank? (str why))) (assoc :why why))))
       ;; :breaks lists what the design stops being true. An id leaving it is the design newly
       ;; claiming that property holds, which quiets relation-honest exactly as softening the
       ;; relation does — and the judge re-rules it only because its membership moved. Under a
       ;; softened relation the softening is that retreat, and each id it emptied would repeat it.
       (let [listed #(map report/break-id (get-in % [:baseline :breaks]))
             soft?  (rank-drop :softened baseline-order
                               (get-in prev [:baseline :relation]) (get-in curr [:baseline :relation]))]
         (for [id (sort (remove (set (listed curr)) (distinct (listed prev))))
               :when (not soft?)]
           (assoc (answered (retreat :breaks-dropped
                                     (str id " is no longer under :baseline :breaks; the design now claims it holds"))
                            asked id :breaks-dropped)
                  :id id)))
       (narrowings design-subjects prev curr (str/join " " (:open curr)) asked)
       ;; Same hole the baseline had: these are typed `string?`, so a record whose
       ;; shape and summary say nothing at all is a record the ledger accepts and
       ;; nothing here counted. It does not quiet the round — the gate reads the
       ;; relations — so it is pure loss, which is the clearest case there is.
       (blanked "the design's" prev curr [:summary :shape])
       (blanked "the design's standing" (:standing prev) (:standing curr) [:note])
       (blanked "the design's baseline relation"
                (:baseline prev) (:baseline curr) [:note]))))))

(def ^:private grew-by
  "How much longer a field has to get before it is worth reporting.

   A correction changes length; a record being talked out of checkability
   multiplies it. Doubling is the line because a claim re-stated so it is simply
   true lands about where it started, and the observed failure was nowhere near
   the boundary: 405 characters to 4091 over six rounds, and 271 to 2671 beside
   it. A floor as well, so a two-word field going to five words is not news."
  {:factor 2.0 :floor 400})

(defn ^{:malli/schema [:=> [:cat :map :map] :any]}
  growth
  "Prose fields that have grown past checking since the run began.

   NOT a retreat, and reported apart from them for that reason: a field that
   tripled claims MORE, not less, and calling it a weakening would be false.
   It is the other way an amendment can cost something, and the one nothing
   measured — a run whose composition went from four sentences to a page
   reported that the record still claims everything it claimed at the start,
   which was true and told the reader the opposite of what had happened.

   Compared against the record AS AUTHORED rather than against last round.
   Growth is a property of the run: 400 characters to 800 is a correction, and
   six of those in a row is a claim nobody can check. Per-round comparison sees
   only the corrections.

   The record's prose fields, and each claim's statement and :falsified-by and
   each element's :hides and :interface under its id: a claim that tripled is
   the same failure as a composition that did, and the one a record-wide reading
   cannot see. A subject the as-authored record did not have has no start to
   measure from. A row for a subject carries its `:id`."
  [as-authored curr]
  (let [fields (fn [r]
                 (concat
                  (for [f [:shape :composition :bounded-by]] [[nil f] (get r f)])
                  ;; An older baseline's composition reads back as a claim too; it is measured once.
                  (for [c (model/claims r) :when (and (:id c) (not= "composition" (:id c)))
                        f [:statement :falsified-by]]
                    [[(:id c) f] (get c f)])
                  (for [e (model/elements r) :when (:id e) f [:hides :interface]]
                    [[(:id e) f] (get e f)])))
        now    (into {} (fields curr))]
    (vec
     (for [[[id f :as k] text] (fields as-authored)
           :let [a (count (str text))
                 c (count (str (get now k)))]
           :when (and (pos? a)
                      (>= (- c a) (:floor grew-by))
                      (>= (/ (double c) a) (:factor grew-by)))]
       (cond-> {:field f :from a :to c} id (assoc :id id))))))

(defn ^{:malli/schema [:=> [:cat :any] :string]}
  growth-summary
  [growth]
  (when (seq growth)
    (str/join "\n"
              (map (fn [{:keys [id field from to]}]
                     (format "  ↑ %s%s — %d characters at the start, %d now (×%.1f)"
                             (if id (str id "'s ") "") (name field) from to (/ (double to) from)))
                   growth))))

(def ^:private widenings
  "Each `narrowing` signal read backwards, as what it says of the later text, and its words."
  {:disjunct-lost    [:disjunct-gained "gained a disjunct"]
   :exception-added  [:exception-lost "lost an exception"]
   :clause-lost      [:clause-gained "gained a clause"]
   :hedged           [:hedge-lost "lost a hedge"]
   :scope-restricted [:scope-lost "lost a scope condition"]
   :quantifier-lost  [:quantifier-gained "gained an absolute quantifier"]})

(defn ^{:malli/schema [:=> [:cat [:enum :baseline :design] :map :map [:maybe [:sequential :string]]]
                      [:vector :map]]}
  widened-spent
  "The claims of `ids` that `curr` states more broadly than `prev` does, one `{:id :field :signals
   :detail}` per field: `narrowing` read backwards, with `:signals` naming what `curr` gained
   (`widenings`).

   `ids` are the spent claims, the ones its amender was offered weakening, splitting or withdrawal
   for because a rewording at the same strength had already been refuted twice. A broader claim is
   none of those three, and nothing else reports it: it is no retreat, and the amender may well
   call it a weakening. `kind` is :baseline or :design."
  [kind prev curr ids]
  (let [subjects (case kind :baseline (comp survey-subjects as-survey) :design design-subjects)
        claims   (fn [r] (into {} (keep (fn [[label id field text]]
                                          (when (= "claim" label) [[id field] text])))
                               (subjects r)))
        now      (claims curr)
        ids      (set ids)]
    (vec
     (for [[[id field :as k] before] (sort-by key (claims prev))
           :when (ids id)
           :let [after   (get now k)
                 signals (map widenings (narrowing field after before))]
           :when (seq signals)]
       {:id      id
        :field   field
        :signals (mapv first signals)
        :detail  (str "spent claim " id "'s " (name field) " "
                      (str/join ", " (map second signals))
                      ": \"" (clip before) "\" → \"" (clip after) "\"")}))))

;; ── Reporting ───────────────────────────────────────────────────────────────

(def ^:private narrowing-declared
  "An amender's reason for a :stale subject that says it made the subject claim less."
  #"(?i)\b(?:narrow|weaken|restrict|qualif|no longer (?:claim|state|cover|say)|scoped? (?:down|to))")

(defn ^{:malli/schema [:=> [:cat [:maybe [:map-of :string :string]] [:maybe [:sequential :map]]]
                      [:vector :map]]}
  declared
  "One `:narrowing-declared` per subject of `stale-because` — an amender's `{id reason}` for the
   subjects it put in question — whose reason says the amendment narrowed it, and that none of
   `retreats` already reports by id. `narrowings` reads the text and misses a narrowing worded so
   no signal moves; the amender's own account is then the only record of it, and left under :stale
   the headline says the record gave nothing up. `:declared`: its reason is said, so it is not
   unasked, and a finding did not order it, so it is not requested either."
  [stale-because retreats]
  (let [reported (into #{} (keep #(or (:id %) (:claim-id %))) retreats)]
    (vec (for [[id why] (sort-by key stale-because)
               :when (and (re-find narrowing-declared (str why)) (not (reported id)))]
           (assoc (retreat :narrowing-declared (str id " — narrowed by the amender's account: " (clip why)))
                  :id id :declared true :because why)))))

(defn ^{:malli/schema [:=> [:cat [:maybe [:sequential :map]] [:maybe [:sequential :map]]
                            [:maybe [:sequential :map]] [:maybe [:map-of :string :string]]]
                      [:vector :map]]}
  attributed
  "`retreats` with each one at an id the amender's sweep put under a finding marked `:ordered` by
   that finding (`answered`): an id the sweep lists under the finding's :amended, or under its
   :stale with a reason in `stale-because`. `swept` is `[{:finding n :amended :stale}]` with `n`
   counting `findings` from 1, as the amend answer gives it.

   A judge names the subject it refutes, and the amender then repairs whatever that refutation
   reaches — a shape restating the claim it found against, a claim resting on the fact it refuted.
   The sweep is the only record of that reach, and without it each knock-on repair reads as a
   narrowing nobody asked for. The finding is linked, so a reader can check the attribution."
  [retreats findings swept stale-because]
  (let [by-id (reduce (fn [m {:keys [finding amended stale]}]
                        (if-let [f (when (int? finding) (nth (vec findings) (dec finding) nil))]
                          (reduce #(update %1 %2 (fnil conj []) f) m
                                  (concat amended (filter #(contains? stale-because %) stale)))
                          m))
                      {} swept)]
    (mapv (fn [r]
            (if-let [fs (some-> (:id r) by-id)]
              ;; Re-filed under the retreat's id, so `asks` reads each as a finding at it.
              (answered r (asks (map #(assoc % :claim-id (:id r) :claim-ids []) fs)) (:id r) nil)
              r))
          retreats)))

(defn ^{:malli/schema [:=> [:cat :map] :keyword]}
  retreat-class
  "Which of four a retreat is: `:relocated`, what it gave up still stands elsewhere in the record;
   `:requested`, a finding asked for it (`:ordered`); `:declared`, the amender said it narrowed and
   why (`declared`); or `:unasked`, none of these — a weakening, the one a reader acts on."
  [r]
  (cond (:relocated r) :relocated
        (:declared r)  :declared
        (:ordered r)   :requested
        :else          :unasked))

(defn ^{:malli/schema [:=> [:cat [:maybe [:sequential :map]]] [:map-of :keyword :int]]}
  classes
  "How many of `retreats` are of each `retreat-class`, every class present, zero or not."
  [retreats]
  (merge {:unasked 0 :requested 0 :relocated 0 :declared 0}
         (frequencies (map retreat-class retreats))))

(defn ^{:malli/schema [:=> [:cat [:maybe [:sequential :map]]] [:vector :map]]}
  unasked
  "The retreats nothing accounts for (`retreat-class` `:unasked`). The count a headline leads with —
   the others are reported, but a reader looking for an amender that gave ground unasked should
   not have to read past them to learn there was none."
  [retreats]
  (filterv #(= :unasked (retreat-class %)) retreats))

(defn ^{:malli/schema [:=> [:cat [:map-of :keyword :int]] :string]}
  tally
  "`classes` as a headline says it: the unasked as `weakenings`, then each other class with any.
   `weakenings` names the unasked alone because it is the word a reader acts on — read off the
   total, a round whose every retreat was relocated or asked for sent people looking for an
   amender that gave ground."
  [{:keys [unasked requested relocated declared]}]
  (let [n      (or unasked 0)
        others (for [[k label] [[requested "requested"] [relocated "relocated"]
                                [declared (str "narrowing" (when (not= 1 declared) "s") " declared")]]
                     :when (pos? (or k 0))]
                 (str k " " label))]
    (str n " weakening" (when (not= 1 n) "s")
         (when (seq others) (str " (" (str/join ", " others) ")")))))

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  line
  "One retreat as a line: what, the detail, and its class when it is not unasked."
  [r]
  (str (name (:what r)) " — " (when (:by-hand r) "between runs: ") (:detail r)
       (case (retreat-class r)
         :requested " [asked for]"
         :relocated " [relocated]"
         :declared  " [declared]"
         nil)))

(defn ^{:malli/schema [:=> [:cat :any] :string]}
  summary
  "One line per retreat, for the terminal and for the escalation. Empty vector
   renders as nil rather than as an empty heading — 'nothing was weakened' is
   worth saying once, by the caller, not implied by a blank section."
  [retreats]
  (when (seq retreats)
    (str/join "\n" (map #(str "  ! " (line %)) retreats))))
