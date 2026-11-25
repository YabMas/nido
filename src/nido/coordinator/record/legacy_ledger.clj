(ns nido.coordinator.record.legacy-ledger
  "The ledgers as they were kept before the core held them, read once into the core and never
   written.

   A legacy ledger is a directory: workstream.edn holds the identity and an index, entries/ one
   immutable file per entry named by its four-digit seq, and entries.meta/ the index row written
   beside each entry. The directory is the ledger and the index a convenience over it, so the
   rows are read the way the ledger always read them where the two disagreed: each file's row as
   kept beside it, else the row the index held for that seq and file, else the row the file's
   name implies, with what an index row mirrors out of its payload read back out of it.

   `import!` sends each workstream to the core as one keyed :import-ledger command, refused once
   the core holds that workstream; nothing here writes under the nido home."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [nido.coordinator.record.state :as cstate]
            [nido.core.client :as client]
            [nido.platform.core :as core]
            [nido.platform.io :as io]))

(def ^:private stored-status-keys
  "Fields a workstream.edn carried before its ledger was the only place its status lived. The
   ledger records each as entries, so they are not part of the identity."
  #{:stage :stage-history :closed :findings})

(def ^:private mirrored-kinds
  "The kinds whose index row mirrors a citation out of the payload: a trail record's design, a
   gate's phase, a retraction's target."
  #{:implementation-completed :review :pr-opened :merged :phase-gate :retraction})

(defn- read-text [path] (when (fs/exists? path) (slurp (str path))))

(defn- mirrored
  "What an index row mirrors out of an entry of `kind` with `text`: its :under, a gate's :opens
   and :phase, a retraction's :retracts. Nothing when the payload does not parse."
  [kind text]
  (let [body (when (and text (mirrored-kinds kind))
               (try (edn/read-string text) (catch Exception _ nil)))]
    (if-not (map? body)
      {}
      (cond-> {}
        (get-in body [:design :seq])                (assoc :under (get-in body [:design :seq]))
        (= :phase-gate kind)                        (assoc :opens (:opens body))
        (and (= :phase-gate kind) (:phase body))    (assoc :phase (:phase body))
        (= :retraction kind)                        (assoc :retracts (get-in body [:retracts :seq]))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:vector :string]]}
  legacy-ids
  "The ids of every workstream of `project` with a legacy record on disk, in id order."
  [project]
  (let [d (cstate/workstreams-dir project)]
    (if (fs/exists? d)
      (->> (fs/list-dir d)
           (filter #(fs/exists? (fs/path % "workstream.edn")))
           (map #(str (fs/file-name %)))
           sort
           vec)
      [])))

(defn ^{:malli/schema [:=> [:cat] [:vector :keyword]]}
  legacy-projects
  "Every project with a workstreams directory under the nido home."
  []
  (let [d (fs/path (core/nido-root) "projects")]
    (if (fs/exists? d)
      (->> (fs/list-dir d)
           (filter #(fs/exists? (fs/path % "workstreams")))
           (map #(keyword (str (fs/file-name %))))
           sort
           vec)
      [])))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:maybe :map]]}
  read-legacy
  "Workstream `ws-id` of `project` as its legacy files hold it — {:identity {...} :entries [{:row
   :content}] :shared [file ...]}, the rows in seq order, each carrying the :at it was written at
   (the file's time where nothing kept one) and the :ext its content is in — or nil when it has no
   workstream.edn.

   One entry per seq. Where a lost update left several files under one seq they are all :shared,
   and the entry is the file a kept row names — which the cut-over's plan may read, but which
   `import!` never sends: a ledger whose files are not one to a seq is not imported at all."
  [project ws-id]
  (when-let [w (io/read-edn (cstate/workstream-edn-path project ws-id))]
    (let [dir    (cstate/workstream-dir project ws-id)
          edir   (fs/path dir "entries")
          frozen (into {} (map (juxt :seq identity)) (:entries w))
          files  (if (fs/exists? edir)
                   (->> (fs/list-dir edir) (map #(str (fs/file-name %)))
                        (filter #(re-find #"^\d{4}-.+\.[a-z]+$" %)) sort)
                   [])
          side   (memoize (fn [n] (io/read-edn (str (fs/path dir (format "entries.meta/%04d.edn" n))))))
          seq-of (fn [fname] (parse-long (subs fname 0 4)))
          kept   (fn [fname] (some #(when (= (str "entries/" fname) (:file %)) %)
                                   [(side (seq-of fname)) (frozen (seq-of fname))]))
          chosen (for [[_ fs] (group-by seq-of files)]
                   (if (next fs)
                     (let [named (filter kept fs)]
                       {:take (if (= 1 (count named)) (first named) nil) :all fs})
                     {:take (first fs) :all fs}))]
      {:identity (apply dissoc w :id :entries stored-status-keys)
       :shared   (vec (sort (for [{fs :all} chosen :when (next fs), f fs] f)))
       :entries
       (vec (for [fname (sort (keep :take chosen))
                  :let [[_ n kind ext] (re-find #"^(\d{4})-(.+)\.([a-z]+)$" fname)
                        seq-n (parse-long n)
                        kind  (keyword kind)
                        path  (fs/path edir fname)
                        text  (read-text path)
                        row   (merge (mirrored kind text) (kept fname))]]
              {:row (-> row
                        (dissoc :file)
                        (assoc :seq seq-n :kind kind :ext ext)
                        (update :at #(or % (str (fs/last-modified-time path)))))
               :content (or text "")}))})))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :map]}
  import!
  "Send workstream `ws-id`'s legacy ledger to the core whole, as one keyed :import-ledger command,
   and answer the outcome. A ledger whose numbered files are not one to a seq is sent as no
   command: it is refused, naming the files that share a seq. A workstream the core already holds
   is refused, and the key answers a repeat with the outcome committed under it, so importing
   twice records nothing twice."
  [project ws-id]
  (if-let [{:keys [identity entries shared]} (read-legacy project ws-id)]
    (if (seq shared)
      {:refused {:reason :files-share-a-seq :files shared}}
      (let [k (client/ws-key project ws-id)]
      (client/command! k (str "import/" k)
                       {:command :import-ledger :identity (assoc identity :project project)
                        :entries entries})))
    {:refused {:reason :no-legacy-record}}))

(defn ^{:malli/schema [:=> [:cat [:? [:maybe [:vector :keyword]]]] :map]}
  import-all!
  "Import every legacy ledger of `projects` (default: every project with a workstreams directory),
   and answer {:imported n :held n :refused [[ws-key refusal] ...]}: :held counts the workstreams
   the core already held, whether from an earlier run or written since."
  ([] (import-all! nil))
  ([projects]
   (reduce (fn [acc [p id]]
             (let [k    (client/ws-key p id)
                   held (some? (:identity (client/value! (client/state k) :state)))
                   o    (when-not held
                          (try (import! p id) (catch Exception e {:error (ex-message e)})))]
               (cond
                 held                                                 (update acc :held inc)
                 (contains? o :events)                                (update acc :imported inc)
                 (= :workstream-exists (get-in o [:refused :reason])) (update acc :held inc)
                 :else (update acc :refused conj [k (or (:refused o) (:error o) (:unavailable o))]))))
           {:imported 0 :held 0 :refused []}
           (for [p (or projects (legacy-projects)) id (legacy-ids p)] [p id]))))
