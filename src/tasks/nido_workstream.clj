(ns tasks.nido-workstream
  "bb-task entry points for the workstream ledger: append an entry, advance the
   stage, close a workstream. Resolves the target workstream by id or by Notion
   external ref (BR-####). Used by the triage skill's dual-write and by humans."
  (:require
   [nido.coordinator.view.workstreams :as wsv]
   [nido.coordinator.report :as report]
   [nido.coordinator.record.workstream :as ws]
   [nido.platform.task-args :as task-args]
   [nido.coordinator.work :as work]))

(defn- resolve-ws-id
  "Workstream id from opts: explicit :ws-id, or :ref resolved via find-by-ref
   (:notion adapter). Throws when neither resolves."
  [{:keys [project ws-id ref]}]
  (let [p (keyword project)]
    (or ws-id
        (some-> (and ref (ws/find-by-ref p :notion ref)) :id)
        (throw (ex-info "Cannot resolve workstream — pass :ws-id or a known :ref"
                        {:project project :ref ref})))))

(def ^:private raw-string-keys
  "Kwarg keys whose values must reach us verbatim. An entry body for a typed
   kind IS EDN, and parse-token would read it into a map — leaving entry-payload
   holding a map where it expects the string it is about to parse. The symbol
   carve-out rescues prose; nothing rescues a leading brace."
  #{:content})

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  entry-add*
  "Append one entry. The body comes from :file when given, else :content — the
   same split nido:ticket:append makes, and for the same reason: a typed report
   is EDN, which does not survive a shell argument intact at any useful size."
  [{:keys [project kind content file] :as opts}]
  (ws/append-entry! (keyword project) (resolve-ws-id opts)
                    {:kind (keyword (or kind "note"))}
                    (if file (slurp (str file)) (or content ""))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  stage-advance* [{:keys [project stage] :as opts}]
  (work/set-stage! (keyword project) (resolve-ws-id opts) (keyword stage)))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  close* [{:keys [project outcome] :as opts}]
  (ws/close! (keyword project) (resolve-ws-id opts) (keyword (or outcome "done"))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  ref-add*
  "Stamp an external ref. For :adapter github this also files the :pr-opened
   ledger event (nido.coordinator.record.workstream/add-ref!), so :summary — the design-
   terms line nido cannot write for itself — belongs on this call."
  [{:keys [project adapter id url title summary] :as opts}]
  (ws/add-ref! (keyword project) (resolve-ws-id opts)
               (cond-> {:adapter (keyword adapter) :id id}
                 url   (assoc :url url)
                 title (assoc :title title))
               {:summary summary}))

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
        ;; Loud, and above the ledger listing, because everything printed below
        ;; is read OFF the index — so when it has fallen behind the directory,
        ;; the listing is exactly the thing that cannot be trusted to say so.
        (when-let [{:keys [on-disk indexed missing]} (ws/index-drift p ws-id)]
          (println)
          (println (format "⚠ LEDGER INDEX IS BEHIND THE DISK — %d %s not listed below"
                           missing (if (= 1 missing) "entry" "entries")))
          (println (format "  entries/ holds up to %04d; the index has %d rows."
                           on-disk indexed))
          (println "  Every reader here — the panes, the review warden — sees the index."))
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
          (when-let [ex (:explain (ex-data e))] (println (pr-str ex))))
        (System/exit 1)))))
(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  stage-advance [& args] (run* stage-advance* args))
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
  backfill-landings-cmd [& args]
  (let [[_ opts] (task-args/split-args args)] (backfill-landings* opts)))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  show-cmd [& args]
  (let [[_ opts] (task-args/split-args args)]
    (show* opts)))
