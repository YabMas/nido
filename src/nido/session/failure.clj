(ns nido.session.failure
  "Failed session starts, kept where a process other than the one that failed can
   find them.

   A start's exception is otherwise shown wherever it happened — a terminal, a TUI
   panel, a Run's one-line :error — and then lost, and a failed Run's teardown
   deletes the instance state directory whose logs explain it. A record snapshots
   that evidence at the moment of failure, under ~/.nido/failures/, which no
   session's destroy, teardown or reclaim touches.

   A record is immutable once kept. Whether it has been recovered is not a fact
   about the failure but about what was done since, and is derived from the
   ledger by whoever asks."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.platform.core :as core]
   [nido.platform.io :as io]
   [nido.session.state :as state]))

(def ^:private tail-bytes
  "How much of each log a record keeps. Enough for a stack trace and the lines
   that led to it; a shared cluster's pg.log runs to hundreds of thousands of
   lines, and the record is read whole by every poll."
  16000)

(def ^:private text-cap
  "The longest string a record keeps from an exception's data. `:output` of a
   failed psql or jj call can be the whole program output."
  4000)

(defn ^{:malli/schema [:=> [:cat] :Path]}
  failures-dir
  "Where kept failures live: ~/.nido/failures/. Outside state/, which is per
   instance and deleted with it."
  []
  (str (fs/path (core/nido-home) "failures")))

(defn- failure-path [id]
  (str (fs/path (failures-dir) (str id ".edn"))))

(defn- new-id
  "Sortable by when it failed, then unique: failures are listed oldest first by
   id alone, without reading them."
  []
  (str (-> (java.time.LocalDateTime/now java.time.ZoneOffset/UTC)
           (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmssSSS")))
       "-" (subs (str (random-uuid)) 0 8)))

(defn- capped [s]
  (if (> (count s) text-cap)
    (str (subs s 0 text-cap) "…[truncated]")
    s))

(defn- plain
  "`x` as data an EDN reader gets back unchanged: collections kept, scalars that
   print readably kept, anything else — a Path, a Process, a Throwable — as its
   string."
  [x]
  (cond
    (map? x)        (into {} (map (fn [[k v]] [(plain k) (plain v)])) x)
    (vector? x)     (mapv plain x)
    (sequential? x) (mapv plain x)
    (set? x)        (into #{} (map plain) x)
    (string? x)     (capped x)
    (or (nil? x) (boolean? x) (number? x) (keyword? x) (symbol? x)) x
    :else           (capped (str x))))

(defn- error-of
  "The exception chain, outermost first. The cause is where a wrapped failure
   says what actually went wrong — `Re-hydration failed` says only where."
  [^Throwable t]
  (->> (iterate #(.getCause ^Throwable %) t)
       (take-while some?)
       (take 8)
       (mapv (fn [^Throwable e]
               (cond-> {:class   (.getName (class e))
                        :message (or (ex-message e) "")}
                 (ex-data e) (assoc :data (plain (ex-data e))))))))

(defn- tail
  "The last `tail-bytes` of the file at `path` as text, or nil when it cannot be
   read. Seeks rather than slurping, because the logs worth reading are exactly
   the ones too big to slurp."
  [path]
  (try
    (when (and path (fs/regular-file? path))
      (with-open [f (java.io.RandomAccessFile. (str path) "r")]
        (let [len   (.length f)
              start (max 0 (- len tail-bytes))
              buf   (byte-array (- len start))]
          (.seek f start)
          (.readFully f buf)
          (String. buf "UTF-8"))))
    (catch Exception _ nil)))

(defn- log-paths
  "Every log that can explain the failure: the instance's own service logs, and
   any log a service named in the exception it threw — a shared cluster's
   pg.log lives outside the instance."
  [instance-id chain]
  (let [own   (when instance-id
                (let [dir (state/log-dir instance-id)]
                  (when (fs/directory? dir)
                    (map str (fs/glob dir "*.log")))))
        named (for [{:keys [data]} chain
                    k [:log-path :pg-log]
                    :let [p (get data k)]
                    :when (string? p)]
                p)]
    (distinct (concat own named))))

(defn ^{:malli/schema [:=> [:cat :map :any] [:maybe :map]]}
  record!
  "Keep one failed start and return the record, or nil when it could not be
   written.

   `attempt` is what the caller was doing: :verb, :session, :project,
   :instance-id, :opts (what the start was given, as far as it is plain data),
   :worktree-existed? and :origin — whose behalf it ran on, which is kept as the
   caller states it and never read here.

   Never throws. A failure to keep the record must not replace the failure it
   records; it is reported on stderr and the caller rethrows its own exception."
  [attempt t]
  (try
    (let [chain  (error-of t)
          id     (new-id)
          record (merge (plain (select-keys attempt [:verb :session :project :instance-id
                                                     :opts :worktree-existed? :origin]))
                        {:id    id
                         :at    (core/now-iso)
                         :error chain
                         :logs  (into {}
                                      (keep (fn [p] (when-let [s (tail p)] [p s])))
                                      (log-paths (:instance-id attempt) chain))})]
      (io/write-edn! (failure-path id) record)
      record)
    (catch Throwable e
      (binding [*out* *err*]
        (println (str "[nido] could not keep the failed start of "
                      (:session attempt) " — " (ex-message e))))
      nil)))

(defn ^{:malli/schema [:=> [:cat :string] [:maybe :map]]}
  failure
  "One kept failure by id, or nil."
  [id]
  (try (io/read-edn (failure-path id))
       (catch Exception _ nil)))

(defn ^{:malli/schema [:=> [:cat] [:vector :map]]}
  failures
  "Every kept failure, oldest first. A record that no longer reads is skipped
   rather than failing the listing — one torn file must not blind recovery to
   every other failure."
  []
  (let [dir (failures-dir)]
    (if-not (fs/directory? dir)
      []
      (->> (fs/glob dir "*.edn")
           (map #(str (fs/strip-ext (fs/file-name %))))
           sort
           (keep failure)
           vec))))

(defn- whole-word
  "`s` matched only where it stands alone, so a short session name does not
   rewrite every word it happens to be part of."
  [s]
  (re-pattern (str "(?<![\\w-])" (java.util.regex.Pattern/quote s) "(?![\\w-])")))

(defn- normalised
  "An error message with what names one particular session taken out: that
   session's name and instance, then paths, uuids, hashes and numbers."
  [s {:keys [session instance-id]}]
  (cond-> (str s)
    (seq instance-id) (str/replace (whole-word instance-id) "<instance>")
    (seq session)     (str/replace (whole-word session) "<session>")
    true (-> (str/replace #"(?:~|/)[^\s'\"`,;:()]*" "<path>")
             (str/replace #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" "<uuid>")
             (str/replace #"\b[0-9a-f]{7,40}\b" "<hex>")
             (str/replace #"\d+" "<n>")
             (str/replace #"\s+" " ")
             str/trim)))

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  cause
  "The key two failures share when their errors differ only in what names one
   session — its name, paths, ports, ids and counts. The project is part of it:
   the same message from two projects' session configs is two causes.

   A short hash rather than the text, because it travels in a workstream ref.
   The text a person reads is the failure's own message."
  [failure]
  (let [chain (:error failure)
        text  (->> chain
                   (map #(str (:class %) ": " (normalised (:message %) failure)))
                   (str/join " <- "))
        digest (.digest (java.security.MessageDigest/getInstance "SHA-1")
                        (.getBytes (str (:project failure) "|" text) "UTF-8"))]
    (str (:project failure) "-"
         (subs (apply str (map #(format "%02x" (bit-and % 0xff)) digest)) 0 12))))
