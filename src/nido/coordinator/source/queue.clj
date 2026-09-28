(ns nido.coordinator.source.queue
  "The :manual event source — a filesystem queue of envelopes.

   - `enqueue!` writes an envelope file under ~/.nido/coordinator/queue/<uuid>.edn
   - `drain!` reads, deletes, and returns all pending envelopes (skipping
     malformed files, which are renamed `<file>.malformed` for inspection)."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.state :as cstate]
   [nido.platform.io :as io]))

(defn- read-envelope-file [path]
  (try
    (let [v (edn/read-string (slurp (str path)))]
      (if (map? v)
        [v nil]
        [nil (ex-info "envelope is not a map" {:value v})]))
    (catch Exception e [nil e])))

(def ^:private keyed-prefix
  "A keyed envelope's file is `keyed-<key>.edn` in the queue, so the name alone says the key is
   taken while it waits to be drained."
  "keyed-")

(defn- keys-lock []
  (io/lock-path-for (str (fs/path (cstate/fired-keys-dir) "keys"))))

(defn- keyed? [f] (str/starts-with? (str (fs/file-name f)) keyed-prefix))

(defn- retire-keyed!
  "Move a drained keyed envelope to fired-keys under its key, and return where it now is. The
   rename is what keeps the key taken once the queue no longer holds it."
  [f]
  (let [k    (subs (str (fs/file-name f)) (count keyed-prefix))
        dest (fs/path (cstate/fired-keys-dir) k)]
    (fs/create-dirs (cstate/fired-keys-dir))
    (io/with-file-lock (keys-lock)
      (fn [] (fs/move f dest {:atomic-move true})))
    dest))

(defn ^{:malli/schema [:=> [:cat] [:vector :Envelope]]}
  drain!
  "Read and remove all envelope files. Returns a vector of envelopes.
   Malformed files are renamed `<file>.malformed` and skipped. A keyed envelope is moved to
   fired-keys rather than deleted, so its key stays taken after it is drained."
  []
  (let [files (->> (fs/list-dir (cstate/queue-dir))
                   (filter #(re-matches #".*\.edn$" (str (fs/file-name %))))
                   (sort-by str))]
    (reduce
      (fn [acc f]
        (let [f              (if (keyed? f) (retire-keyed! f) f)
              [envelope err] (read-envelope-file f)]
          (if err
            (do
              (fs/move f (str f ".malformed"))
              (binding [*err* *err*]
                (.println ^java.io.PrintWriter *err*
                          (str "WARN: malformed queue file " f " — " (ex-message err))))
              acc)
            (do
              (when-not (str/starts-with? (str f) (cstate/fired-keys-dir))
                (fs/delete f))
              (conj acc envelope)))))
      []
      files)))

(defn- stamp [envelope]
  (let [now (clock/now-iso)]
    (-> envelope
        (assoc :received-at now)
        (cond->
          (not (contains? envelope :created-at)) (assoc :created-at now)
          (not (contains? envelope :priority))   (assoc :priority 0)))))

(defn ^{:malli/schema [:=> [:cat :string :Envelope] :map]}
  enqueue-keyed!
  "Queue `envelope` under `key` unless the key is taken — by an envelope still waiting, or by
   one drained since. Returns `{:queued path}` or `{:already-queued key}`.

   Queueing and taking the key are ONE write: the envelope is written whole to a temp file and
   then hard-linked to its keyed name, which fails when the name exists. So no stop leaves a key
   taken with no envelope, or an envelope with its key free. `key` must be a plain file name —
   letters, digits, `.`, `_` and `-`."
  [key envelope]
  (when-not (re-matches #"[A-Za-z0-9._-]+" (str key))
    (throw (ex-info "an envelope key must be a plain file name" {:key key})))
  (fs/create-dirs (cstate/queue-dir))
  (fs/create-dirs (cstate/fired-keys-dir))
  (let [path (fs/path (cstate/queue-dir) (str keyed-prefix key ".edn"))
        tmp  (fs/path (cstate/queue-dir) (str "." key "." (java.util.UUID/randomUUID) ".tmp"))]
    (io/with-file-lock (keys-lock)
      (fn []
        (if (fs/exists? (fs/path (cstate/fired-keys-dir) (str key ".edn")))
          {:already-queued key}
          (try
            (spit (str tmp) (str (pr-str (stamp envelope)) "\n"))
            (java.nio.file.Files/createLink (fs/path path) (fs/path tmp))
            {:queued (str path)}
            (catch java.nio.file.FileAlreadyExistsException _
              {:already-queued key})
            (finally (fs/delete-if-exists tmp))))))))

(defn ^{:malli/schema [:=> [:cat :Envelope] :any]}
  enqueue!
  "Write an envelope to the queue with a fresh UUID filename.
   Stamps :created-at (when the caller produced it), :received-at (when
   we observed it), and defaults :priority to 0 if absent."
  [envelope]
  (let [uuid (str (java.util.UUID/randomUUID))
        path (str (fs/path (cstate/queue-dir) (str uuid ".edn")))
        now  (clock/now-iso)
        env  (-> envelope
                 (assoc :received-at now)
                 (cond->
                   (not (contains? envelope :created-at)) (assoc :created-at now)
                   (not (contains? envelope :priority))   (assoc :priority 0)))]
    (io/write-edn! path env)
    path))
