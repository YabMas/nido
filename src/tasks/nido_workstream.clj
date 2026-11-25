(ns tasks.nido-workstream
  "bb-task entry points for the workstream ledger: append an entry, advance the
   stage, close a workstream. Resolves the target workstream by id or by Notion
   external ref (BR-####). Used by the triage skill's dual-write and by humans."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [nido.coordinator.lane.pipeline :as pipeline]
   [nido.coordinator.view.workstreams :as wsv]
   [nido.coordinator.report :as report]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.record.records :as records]
   [nido.coordinator.record.proposal :as proposal]
   [clojure.pprint :as pprint]
   [nido.core.client :as core-client]
   [nido.coordinator.record.fork :as fork]
   [nido.review.merge :as unit-merge]
   [nido.platform.task-args :as task-args]
   [nido.review.stages :as stages]
   [nido.coordinator.work :as work]
   [nido.coordinator.work.core :as wcore]
   [nido.coordinator.lane.landing :as landing]
   [babashka.fs :as fs]
   [tasks.nido-attach :as attach]))

(defn- resolve-ws-id
  "Workstream id from opts: explicit :ws-id, or :ref resolved via find-by-ref
   (:notion adapter). Throws when neither resolves."
  [{:keys [project ws-id ref]}]
  (let [p (keyword project)]
    (or ws-id
        (some-> (and ref (ws/find-by-ref p :notion ref)) :id)
        (throw (ex-info "Cannot resolve workstream — pass :ws-id or a known :ref"
                        {:project project :ref ref})))))

(defn- resolve-run
  "The core key of the run a verb acts for: explicit :run (a run id) of :project, else the run the
   turn calling it runs in (`NIDO_CORE_RUN`). Exits when neither names one — these verbs record
   what a run did, and a person's shell is no run."
  [{:keys [project run]}]
  (or (when run (core-client/run-key (keyword project) (str run)))
      (not-empty (System/getenv "NIDO_CORE_RUN"))
      (do (println "No run — pass :run <run-id>, or call this from the run's own turn (NIDO_CORE_RUN)")
          (System/exit 2))))

(def ^:private raw-string-keys
  "Kwarg keys whose values must reach us verbatim. An entry body for a typed
   kind IS EDN, and parse-token would read it into a map — leaving entry-payload
   holding a map where it expects the string it is about to parse. The symbol
   carve-out rescues prose; nothing rescues a leading brace."
  #{:content})

(defn- owed-lines
  "What the amendment of the goal at `goal-seq` still owes its unit, as attach words it."
  [project ws-id goal-seq]
  (attach/amendment-lines (pipeline/amendment-owed project ws-id goal-seq)))

(defn- refusal-lines
  "What a refusal of a record over a replaced goal leaves that record's unit owing, or []. Asked
   of the refused record's own unit, through the replaced goal the refusal carries, and never of
   the position — which may be held by another unit's survey, or by a blocker."
  [opts e]
  (if (:amendment (ex-data e))
    (owed-lines (keyword (:project opts)) (resolve-ws-id opts) (:seq (ex-data e)))
    []))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  entry-add*
  "Append one entry. The body comes from :file when given, else :content — the
   same split nido:ticket:append makes, and for the same reason: a typed report
   is EDN, which does not survive a shell argument intact at any useful size.

   An :intent that amends a goal something was already built on is followed by
   what the amendment still owes, so its author reads the whole chain at the
   append that started it rather than one refusal at a time."
  [{:keys [project kind content file] :as opts}]
  (let [p     (keyword project)
        ws-id (resolve-ws-id opts)
        n     (ws/append-entry! p ws-id {:kind (keyword (or kind "note"))}
                                (if file (slurp (str file)) (or content "")))]
    (when (= :intent (keyword kind))
      (run! println (owed-lines p ws-id n)))
    n))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  stage-advance* [{:keys [project stage] :as opts}]
  (work/set-stage! (keyword project) (resolve-ws-id opts) (keyword stage)))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  close*
  "Close the workstream for the person, by their command to the core: its open units' goals
   retracted, then the workstream closed. :outcome done (the default) or dropped."
  [{:keys [project outcome] :as opts}]
  (let [r (work/core-resolve! (keyword project) (resolve-ws-id opts)
                              (if (= "done" (name (or outcome "done"))) :done :drop) nil)]
    (when (= :core-refused (:decision r))
      (throw (ex-info (str "The core refused the close — " (:because r)) r)))
    r))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  ref-add*
  "Stamp an external ref. For :adapter github this also files the :pr-opened
   ledger event (nido.coordinator.record.workstream/add-ref!), so :summary — the design-
   terms line nido cannot write for itself — belongs on this call, and so does
   :design, the seq of the design the published work was done under."
  [{:keys [project adapter id url title summary design] :as opts}]
  (ws/add-ref! (keyword project) (resolve-ws-id opts)
               (cond-> {:adapter (keyword adapter) :id id}
                 url   (assoc :url url)
                 title (assoc :title title))
               {:summary summary
                :design  (some-> design str parse-long)}))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  landed*
  "Record that an approved proposal is now in the tree.

   The proposal is addressed the way every reader addresses it — the analysis
   entry's seq and the observation's index inside it — which is what the
   operations surface prints on every card as `proposal <seq>.<i>`."
  [{:keys [project analysis-seq observation rev note] :as opts}]
  (work/record-landing! (keyword project) (resolve-ws-id opts)
                        {:analysis-seq (parse-long (str analysis-seq))
                         :observation  (parse-long (str observation))
                         :rev          (str rev)
                         :note         note}))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  backfill-landings*
  "One-shot: discharge every approval whose note already records the outcome.

   For the era when an approval was the whole of the ledger's vocabulary for
   'this is done'. Idempotent, so re-running it is safe on a ledger that has no
   delete."
  [{:keys [project]}]
  (let [{:keys [landed skipped]} (work/backfill-landings! (keyword project))]
    (println (format "landed %d · left alone %d" landed skipped))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  show* [{:keys [project] :as opts}]
  (let [p     (keyword project)
        ws-id (resolve-ws-id opts)
        w     (work/workstream p ws-id)]
    (if-not w
      (println "no such workstream:" ws-id)
      (do
        (println "ws-id:" ws-id)
        (println "stage:" (name (:stage w)) "·" (:label w))
        ;; What is happening in it right now, which the stage cannot say: a
        ;; workstream sits at :in-progress whether an agent is mid-round or
        ;; nothing has run against it for a week.
        (when-let [d (wsv/doing-label (:doing w))]
          (println "doing:" d))
        (when-let [idx (:entries w)]
          (println "ledger:")
          (doseq [{:keys [seq kind title]} idx]
            (println (format "  %2d  %-24s %s" seq (name kind) (or title "")))))
        (println)
        (println "── latest entry ──")
        ;; Asked for by name rather than taken off `w`: the pane opens no entry
        ;; unless its reader picks one (work/workstream), but this command's whole
        ;; contract is "the ledger, then the newest entry" — there is no reader here
        ;; to pick, so it names what it wants.
        (println (report/report->markdown (work/latest-report p ws-id)))))))

(def ^:private ref-raw-string-keys
  "Kwarg keys on `ref:add` that must reach us verbatim.

   A stack PR's title starts with `[n/N]` — /prepare-draft-pr mandates that
   shape — and parse-token reads `[2/2]` as a vector holding the ratio 2/2,
   i.e. `[1]`. The Workstream schema then rejects the whole record, so the
   stamp fails on exactly the PRs the stack path exists to stamp.

   The other three are prose-by-luck rather than by design: a URL and a
   sentence both start with something read-string returns as a symbol, and
   the symbol carve-out hands back the original string. Nothing rescues a
   leading bracket, brace, digit or quote — so the keys carrying free text
   say so instead of relying on their first character."
  #{:id :url :title :summary})

(defn- run*
  ([f args] (run* f args #{}))
  ([f args raw-keys]
   (let [[_ opts] (task-args/split-args args raw-keys)]
     (f opts)
     (println "ok"))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  entry-add
  "bb nido:workstream:entry:add :project <p> (:ws-id <id> | :ref BR-####)
     :kind <kw> (:file <path> | :content <str>)
   A typed kind's body is validated at the ledger boundary; a malformed one is
   rejected with its explain dump and a non-zero exit, so the emitting skill can
   fix and retry rather than reading a stack trace."
  [& args]
  (let [[_ opts] (task-args/split-args args raw-string-keys)]
    (try
      (println (entry-add* opts))
      (catch Exception e
        (binding [*out* *err*]
          (println "append rejected:" (ex-message e))
          (when-let [ex (:explain (ex-data e))] (println (pr-str ex)))
          (run! println (refusal-lines opts e)))
        (System/exit 1)))))
(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  stage-advance [& args] (run* stage-advance* args))
(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  plan*
  "Append one day's improvement plan, reading it from a file.

   The plan goes through `record-plan!` and never through `entry:add`, because
   only the verb derives the owed set and can refuse a grouping that does not
   cover it. A refusal prints what is wrong — uncovered and unowed are different
   mistakes, and a writer told only `no` cannot fix either."
  [{:keys [project file] :as opts}]
  (when-not file
    (println "Missing :file <path> — the plan is EDN and does not survive a shell argument")
    (System/exit 2))
  (let [plan (edn/read-string (slurp (str file)))
        res  (work/record-plan! (keyword project) (resolve-run opts) plan)]
    (if (= :refused (:plan res))
      (do (println "plan REFUSED — it does not partition what is owed")
          (when-let [u (get-in res [:defect :uncovered])]
            (println "  owed but claimed by nothing:") (doseq [a u] (println "   " a)))
          (when-let [u (get-in res [:defect :unowed])]
            (println "  claimed but not owed:") (doseq [a u] (println "   " a)))
          (System/exit 1))
      (println "recorded at seq" (:seq res)))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  reserve*
  "Fix a claim's veto deadline, or report that a decline reached it first.

   Exits non-zero when vetoed, so a skill that runs this before pushing stops
   there without having to read the output."
  [{:keys [project plan-seq claim addresses] :as opts}]
  (let [res (work/reserve-claim! (keyword project) (resolve-run opts)
                                 {:plan-seq  (parse-long (str plan-seq))
                                  :claim     (parse-long (str claim))
                                  :addresses (str/split (str addresses) #",")})]
    (if (= :vetoed (:reserved res))
      (do (println "claim VETOED — declined since it was planned:")
          (doseq [a (:declined res)] (println "  " a))
          (println "the claim is ended, vetoed; its other addresses are owed again")
          (System/exit 1))
      (println "reserved"))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  discharge*
  "Push the claim and record what it carried. Refuses without a reservation."
  [{:keys [project worktree bookmark rev addresses] :as opts}]
  (let [res (work/discharge-claim! (keyword project) (resolve-run opts)
                                   {:worktree  (str worktree)
                                    :bookmark  (or (some-> bookmark str) "main")
                                    :rev       (str rev)
                                    :addresses (str/split (str addresses) #",")})]
    (case (:discharged res)
      :recorded   (println "landed" (count (:addresses res)) "proposal(s) at" rev)
      :unreserved (do (println "REFUSED — no reservation stands for this claim; nothing was pushed")
                      (System/exit 1))
      :not-pushed (do (println "NOT PUSHED —" (name (:outcome res)))
                      (when-let [d (:detail res)] (println " " d))
                      (System/exit 1)))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  run-record*
  "Record what this run did: one record of :kind, its fields the EDN map in :file — a ledger
   entry's content of that kind, `:format` and all or without it. A `:closed` record needs no file:
   :outcome done, dropped or vetoed says how the run's work ended. Checked against the kind's
   schema before the core sees it; a refusal prints why."
  [{:keys [kind file outcome note] :as opts}]
  (let [k   (keyword (or kind (when outcome "closed")))
        rec (if file
              (-> (records/->record (assoc (edn/read-string (slurp (str file))) :format k)) (assoc :kind k))
              (cond-> {:kind k :outcome (keyword (or outcome "done")) :by :nido}
                note (assoc :note (str note))))
        n   (records/append-to-run! (resolve-run opts) rec)]
    (println "recorded" (name k) "at seq" n)))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  owed*
  "Print what a plan must partition, as EDN: every proposal of :project still owed, by address,
   with its evidence and what was decided about it, and the frontier it was derived at — the
   `:frontier` a plan records, ready to copy."
  [{:keys [project]}]
  (let [pk    (keyword project)
        props (proposal/of-project pk)
        atts  (proposal/claim-attempts pk)
        ow    (proposal/owed props (proposal/plans-of pk) atts)]
    (pprint/pprint
     {:owed     (mapv (fn [p] (-> (select-keys p [:kind :where :summary :evidence :proposal :run-id :at])
                                  (assoc :address (proposal/address p)
                                         :decided (get-in p [:decision :verdict]))))
                      ow)
      :frontier {:proposals (vec (distinct (map #(select-keys % [:ws-id :at-seq]) props)))
                 :attempts  (mapv (fn [a] {:ws-id (:ws-id a) :closed? (not (:open? a))}) atts)}})))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  owed-cmd [& args] (run* owed* args))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  run-record-cmd [& args] (run* run-record* args #{:file :note}))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  close-cmd     [& args] (run* close* args))
(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  ref-add       [& args] (run* ref-add* args ref-raw-string-keys))
;; :rev and :note are prose-by-luck under parse-token and must not be: a change
;; id is a bare word that happens to read as a symbol, and a note is a sentence
;; that may begin with anything at all.
(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  landed-cmd    [& args] (run* landed* args #{:rev :note}))
(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  plan-cmd      [& args] (run* plan* args #{:file}))
(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  reserve-cmd   [& args] (run* reserve* args #{:addresses}))
(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  discharge-cmd [& args] (run* discharge* args #{:rev :addresses :worktree :bookmark}))
(def ^:private settled-bands
  "The bands of work that is over or is not work, which the list leaves out unless asked."
  #{:done :dismissed :analysed})

(defn- list-line
  "One row as a line: id, band, who owes the next move — the person with the category they are
   needed in (`person:landing`) — what is underway, title."
  [row]
  (str/join "  "
            [(:ws-id row)
             (format "%-13s" (name (:stage row)))
             (format "%-19s" (let [{:keys [owed-by needs]} (:status row)]
                               (cond needs   (str "person:" (name needs))
                                     owed-by (name owed-by)
                                     :else   "-")))
             (format "%-28s" (or (wsv/doing-label (:doing row)) "-"))
             (:label row)]))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  list*
  "Print where each workstream of :project stands — its id, band, who owes the next move
   (`person` first: those wait on a person), what is underway and its title — one line each,
   and the count. Shows work that is not over; `:all true` adds the settled bands. A ticket no
   workstream covers has no id to act on and is never listed."
  [{:keys [project all]}]
  (let [rows (->> (work/list-workstreams (keyword project))
                  (remove :bare?)
                  (remove #(and (not all) (settled-bands (:stage %))))
                  (sort-by (juxt #(if (:needs-you %) 0 1) #(or (:last-activity %) "")))
                  (partition-by :needs-you)
                  (mapcat #(sort-by :last-activity (fn [a b] (compare b a)) %)))]
    (doseq [r rows] (println (list-line r)))
    (println (str (count rows) " workstream(s), " (count (filter :needs-you rows)) " waiting on a person"))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  list-cmd [& args]
  (let [[_ opts] (task-args/split-args args)]
    (when-not (:project opts)
      (throw (ex-info "Missing :project <name>" {})))
    (list* opts)))

(defn- here
  "[project ws-id] from explicit opts — :project with :ws-id or :ref — else from the session the
   cwd is in. The agent recording its own progress is standing in its worktree, and naming the
   workstream it already sits in is a step it can get wrong."
  [{:keys [project ws-id ref] :as opts}]
  (if (and project (or ws-id ref))
    [(keyword project) (resolve-ws-id opts)]
    (or (stages/project+ws-from-cwd (System/getProperty "user.dir"))
        (throw (ex-info "No workstream here — run from a session's worktree or session home, or pass :project and :ws-id"
                        {:cwd (System/getProperty "user.dir")})))))

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  layer-complete*
  "Record that layer :layer of :of is finished under the design it was built under — :design
   <seq>, else the workstream's only design. NEVER the newest of several: a design appended while
   the layer was being built is one it was never built under, so with more than one the caller
   names it. A seat's layer names its :seat, and :layer and :of are then its position and count
   within that seat. Recording it again under the same design and seat appends nothing and
   answers the entry already there."
  [{:keys [layer of bookmark commit seat] :as opts}]
  (let [[p id]  (here opts)
        designs (filterv #(= :design (:kind %)) (:entries (ws/read-ws p id)))
        n       (some-> (:design opts) str parse-long)
        design  (cond
                  (empty? designs)
                  (throw (ex-info "No design on this workstream — a layer is built under one" {:ws-id id}))
                  (:design opts)
                  (or (some #(when (= n (:seq %)) %) designs)
                      (throw (ex-info "No design at that seq" {:ws-id id :design (:design opts)
                                                               :designs (mapv :seq designs)})))
                  (= 1 (count designs)) (first designs)
                  :else
                  (throw (ex-info (str "This workstream holds " (count designs) " designs — name the one"
                                       " the layer was built under with :design <seq>")
                                  {:ws-id id :designs (mapv :seq designs)})))]
    (some val (ws/append-entry-once!
               p id {:kind :layer-completed}
               (pr-str (cond-> {:format :layer-completed :design {:seq (:seq design)}
                                :layer layer :of of}
                         bookmark (assoc :bookmark (str bookmark))
                         commit   (assoc :commit (str commit))
                         seat     (assoc :seat (str seat))))
               #(and (= (:seq design) (-> % :design :seq)) (= layer (:layer %))
                     (= (some-> seat str) (:seat %)))))))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  generation-open*
  "Open the next generation of the workstream's design from the re-cut at :recut, in the person's
   :words, building from :base-rev. The generation-defining ids are :ids (comma-separated), else
   the ones the re-cut proposes. The claims the closing generation saw falsified, and the tensions
   the re-cut names, the ledger carries itself from the closing span — nobody supplies them. Every
   Answers {:generation <entry>}."
  [{:keys [recut words base-rev ids] :as opts}]
  (let [[p id] (here opts)
        n      (some-> recut str parse-long)
        w      (ws/read-ws p id)
        _      (when-not (some #(and (= n (:seq %)) (= :recut (:kind %))) (:entries w))
                 (throw (ex-info "Pass :recut <seq> — the re-cut the generation opens from"
                                 {:ws-id id :recut recut})))
        ids    (if ids
                 (mapv str/trim (str/split (str ids) #","))
                 (:ids (ws/entry-at-seq p id n)))]
    (when (str/blank? (str words))
      (throw (ex-info "A generation is opened in the person's words — pass :words \"…\"" {:ws-id id})))
    (when (str/blank? (str base-rev))
      (throw (ex-info "Pass :base-rev — the revision the new generation builds from" {:ws-id id})))
    (let [opened (ws/open-generation! p id {:format :generation :recut {:seq n} :words (str words)
                                            :ids ids :base-rev (str base-rev)})]
      {:generation opened})))

(defn- unanswered-blockers
  "The seqs of this workstream's blockers no :blocker-answered names, oldest first — off the
   index, so a blocker whose payload no longer reads is still one to answer."
  [p id]
  (let [answered (into #{} (keep :blocker-seq) (ws/entries-of p id :blocker-answered))]
    (into [] (comp (filter #(= :blocker (:kind %))) (map :seq) (remove answered))
          (:entries (ws/read-ws p id)))))

(defn- grants-opt
  "The acts a `:grants` option names — one word, or a vector of them, colon or none — as keywords,
   or nil when none is given. One no grant may hand (report/grantable-kinds) is refused: it stays
   the person's own."
  [v]
  (when (some? v)
    (let [ks (->> (if (sequential? v) v [v])
                  (map #(keyword (str/replace (str (if (keyword? %) (name %) %)) #"^:" "")))
                  distinct vec)]
      (when-let [bad (seq (remove report/grantable-kinds ks))]
        (throw (ex-info (str "Only " (str/join ", " (map name (sort report/grantable-kinds)))
                             " may be granted; " (str/join ", " (map name bad)) " stays the person's own")
                        {:grants ks})))
      (not-empty ks))))

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  blocker-answer*
  "Record a person's answer, in their words: to a review's blocker — :blocker-seq, else the newest
   one nothing has answered — as their :blocker-answered work record, and to the oldest ask
   awaiting them in the core as their command. A :blocker-seq that names no unanswered blocker is
   refused, and so is an answer with nothing awaiting it. :grants names the acts the person's
   answer hands the conductor (`grants-opt`), passed because they said so — the words are not
   read for one."
  [{:keys [answer blocker-seq grants] :as opts}]
  (let [[p id] (here opts)
        owed   (unanswered-blockers p id)
        asked? (some #(= :open (:status %)) (vals (:operator-asks (wcore/state p id))))
        n      (or blocker-seq (peek owed))]
    (when (str/blank? (str answer))
      (throw (ex-info "An answer is the person's words — pass :answer \"…\"" {:ws-id id})))
    (when (and (nil? n) (not asked?))
      (throw (ex-info "Nothing on this workstream awaits an answer" {:ws-id id})))
    (when (and n (not (some #{n} owed)))
      (throw (ex-info "No unanswered blocker at that seq" {:ws-id id :blocker-seq n :unanswered owed})))
    (let [gs (grants-opt grants)
          ;; A review's blocker is a work record: the person's answer to it is theirs, appended in
          ;; their own process, before the core hears it.
          path (when n
                 (ws/append-entry! p id {:kind :blocker-answered}
                                   (pr-str {:format :blocker-answered :blocker-seq n :summary (str answer)})))
          said (work/person-says! p id (when asked? (str answer)) (vec gs))]
      (when (seq (:refused said))
        (binding [*out* *err*] (println "the core refused:" (str/join ", " (:refused said)))))
      (str (or path (str "answered " (:answered said)))))))

(defn- images-opt
  "The images an `:images` option names — digests, comma-separated — copied from the side chat's
   image store into the core's, answered as the digests a chat message names them by; nil when
   none is given. A digest the side chat does not keep is refused, naming it, before anything is
   sent: a relay passes on only images the person attached."
  [v]
  (when-let [ds (seq (remove str/blank? (map str/trim (str/split (str v) #","))))]
    (let [kept (map (juxt identity wcore/relay-image!) (distinct ds))]
      (when-let [gone (seq (keep (fn [[d i]] (when-not i d)) kept))]
        (throw (ex-info (str "No image is kept by digest " (str/join ", " gone)
                             " — name only the digests the person's message carried")
                        {:images (vec gone)})))
      (mapv second kept))))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  tell*
  "Pass :text on to the conductor of the workstream this runs in, as the person's chat message —
   or their answer, when an ask awaits them — and print where it went. :grants names the acts the
   person's word hands the conductor (`grants-opt`), each given as the person's grant; :images
   the person's images that go with it, by digest (`images-opt`)."
  [{:keys [text grants images] :as opts}]
  (when (and images (str/blank? (str text)))
    (throw (ex-info "Images go with the person's words — pass :text \"…\" with :images" {:images images})))
  (let [[p id] (here opts)
        m      (work/person-says! p id (str text) (vec (grants-opt grants)) (images-opt images))]
    (println (cond (:posted m)   (str "posted to " id "'s conductor")
                   (:answered m) (str "answered " (:answered m) " on " id)
                   :else         (str "nothing reached " id "'s conductor: " (str/join ", " (:refused m)))))
    m))

(defn- worktree-of
  "The worktree a session's directory `cwd` stands for: its `worktree/` when `cwd` is the
   session's home, else `cwd` itself."
  [cwd]
  (let [wt (fs/path cwd "worktree")]
    (if (fs/exists? wt) (str (fs/canonicalize wt)) cwd)))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  pr-land*
  "Land the change of the workstream of the session this runs in, by its project's declared
   procedure, through landing/land!; answer its result. Where that landing names a PR
   (landing/names-pr?), :pr — `owner/repo#n`, or `n` when one ref has that number — must be the
   workstream's own PR (`ws/own-pr`); one it does not record is refused before anything runs. A
   :push-main landing takes no :pr and runs in the session's worktree. A directory in no session's
   workstream is refused. The workstream is read from the directory and never named, so this
   lands no other workstream."
  [{:keys [pr]}]
  (let [cwd    (System/getProperty "user.dir")
        [p id] (or (stages/project+ws-from-cwd cwd)
                   (throw (ex-info "No workstream here — run from a session's worktree or session home"
                                   {:reason :no-workstream :cwd cwd})))
        needs  (landing/names-pr? p)]
    (when (map? needs)
      (throw (ex-info (str "Project " (name p) " declares :landing " (pr-str (:value needs))
                           ", which is none of :pr-auto, :pr-squash or :push-main")
                      {:reason :undeclared-landing :value (:value needs)})))
    (if needs
      (let [w   (ws/read-ws p id)
            ref (when (some? pr) (ws/own-pr w (str pr)))]
        (when-not ref
          (throw (ex-info (str "PR " (pr-str pr) " is not one workstream " id " records as its own; its PRs: "
                               (or (some->> (filter #(= :github (:adapter %)) (:external-refs w))
                                            (map :id) seq (str/join ", "))
                                   "none"))
                          {:reason :not-own :pr pr :ws-id id})))
        (landing/land! p id {:pr (:id ref)}))
      (landing/land! p id {:dir (worktree-of cwd)}))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  pr-land-cmd
  "bb nido:workstream:pr:land [:pr <owner/repo#n | n>] — exits non-zero, printing why, when the
   landing is refused: a PR that is not the workstream's own, a project landing it cannot read, or
   the procedure's own refusal."
  [& args]
  (let [[_ opts] (task-args/split-args args #{:pr})
        r        (try (pr-land* opts)
                      (catch clojure.lang.ExceptionInfo e {:error :refused :detail (ex-message e)}))]
    (if (= :ok (:status r))
      (println (str "pr:land · " (or (:pr r) "main") " "
                    (case (:via r)
                      :auto   "set to merge once its checks allow"
                      :queue  "enqueued on its merge queue"
                      :squash "squash-merged"
                      :push   "landed on main by nido:land")))
      (binding [*out* *err*]
        (println (str "pr:land · refused: " (:detail r)))
        (System/exit 1)))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  tell-cmd [& args] (run* tell* args #{:text :images}))
(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  layer-complete-cmd [& args] (run* layer-complete* args #{:bookmark :commit :seat}))
(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  blocker-answer-cmd [& args] (run* blocker-answer* args #{:answer}))
(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  generation-open-cmd [& args] (run* generation-open* args #{:words :base-rev :ids}))
(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  backfill-landings-cmd [& args]
  (let [[_ opts] (task-args/split-args args)] (backfill-landings* opts)))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  show-cmd [& args]
  (let [[_ opts] (task-args/split-args args)]
    (show* opts)))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  fork*
  "Fork a unit into a child workstream, from a parent whose newest design stands. Prints the
   child's id on stdout, and on stderr the activation that hands it to the core; the parent's
   ledger is not written. A refusal says why and exits 1."
  [{:keys [project goal done-when context] :as opts}]
  (when (or (str/blank? (str goal)) (empty? done-when))
    (println "Missing :goal <str> or :done-when [\"...\"] — a child is a unit, and a unit is rooted in a goal")
    (System/exit 2))
  (try
    (let [child (fork/fork! (keyword project) (resolve-ws-id opts)
                            (cond-> {:goal      (str goal)
                                     :done-when (if (sequential? done-when)
                                                  (mapv str done-when)
                                                  [(str done-when)])}
                              context (assoc :context (str context))))]
      (println (:id child))
      (binding [*out* *err*]
        (println (str "Hand it to the core, which provisions its session: bb nido:activate :project "
                      (name (keyword project)) " :ws " (:id child)))))
    (catch clojure.lang.ExceptionInfo e
      (if (= :fork (:refused (ex-data e)))
        (do (println "fork REFUSED ·" (ex-message e))
            (System/exit 1))
        (throw e)))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  fork-cmd [& args]
  (let [[_ opts] (task-args/split-args args #{:goal :context})]
    (fork* opts)))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  merge*
  "Propose merging a child unit's design into its parent, checking the declaration in `:worktree`
   (default: here). Prints every conflict, by id and by law, and exits 1 while any stands. With none
   standing and no :file it prints what the merged design will carry; with :file — the merged
   design's own fields, as EDN — it appends that design to the parent and prints where."
  [{:keys [project file worktree] :as opts}]
  (let [p        (keyword project)
        child    (resolve-ws-id opts)
        worktree (str (or worktree (System/getProperty "user.dir")))]
    (try
      (let [{:keys [proposal appended]}
            (if file
              (unit-merge/merge! p child worktree (edn/read-string (slurp (str file))))
              {:proposal (unit-merge/proposal p child worktree)})]
        (cond
          (seq (:conflicts proposal))
          (do (println "merge REFUSED ·" (count (:conflicts proposal)) "conflict(s) stand")
              (println (unit-merge/conflicts-text proposal))
              (System/exit 1))

          appended
          (println appended)

          :else
          (do (println "no conflict stands. Beside the fields you author, the merged design will carry:")
              (prn (select-keys proposal [:model :parent :child]))
              (println "Re-run with :file <the merged design's own fields, as EDN> to append it."))))
      (catch clojure.lang.ExceptionInfo e
        (println (if (= :merge (:refused (ex-data e))) "merge REFUSED ·" "append rejected:")
                 (ex-message e))
        (System/exit 1)))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  merge-cmd [& args]
  (let [[_ opts] (task-args/split-args args #{:file :worktree})]
    (merge* opts)))
