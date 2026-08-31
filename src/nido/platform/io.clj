(ns nido.platform.io
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn])
  (:import [java.nio.channels FileChannel]
           [java.nio.file Paths StandardOpenOption]))

;; ── Exclusion across processes AND threads ──────────────────────────────────

(defonce ^:private monitors
  (atom {}))

(defn- monitor-for
  "One canonical object per lock path, so `locking` on it means the same thing
   from every thread. Interned rather than created per call — two threads
   synchronising on two different objects synchronise on nothing."
  [path]
  (or (get @monitors path)
      (get (swap! monitors update path #(or % (Object.))) path)))

(defn ^{:malli/schema [:=> [:cat :string [:=> [:cat] :any]] :any]}
  with-file-lock
  "Run `f` holding an exclusive lock on `lock-path`, then release. Returns f's
   value; the lock is released whether f returns or throws.

   TWO locks, because one is not enough and each covers what the other cannot.
   The OS file lock excludes other PROCESSES — nido's writers are separate ones
   (bb tasks, the daemon, review loops), which is the case a JVM monitor cannot
   see at all. But a file lock is held per-JVM, so two threads in the daemon
   asking for the same file do not queue: the second gets an
   OverlappingFileLockException rather than waiting. The monitor is what makes
   them queue, and it has to be interned per path or it synchronises nothing.

   Released by closing the channel rather than by releasing the lock, because
   babashka does not admit methods on FileLockImpl — and closing is the stronger
   guarantee anyway: the OS drops the lock when the process dies, so a crash
   mid-append cannot wedge every later writer the way a stale lock DIRECTORY
   would."
  [lock-path f]
  (let [p (str lock-path)]
    (when-let [parent (fs/parent p)] (fs/create-dirs parent))
    ;; clj-kondo reads this as locking on something local, which is the right
    ;; thing to flag in general and wrong here: monitor-for INTERNS one object
    ;; per path, so every thread asking about the same path gets the same
    ;; object. Locking on a fresh one would synchronise nothing at all.
    #_{:clj-kondo/ignore [:locking-suspicious-lock]}
    (locking (monitor-for p)
      (let [ch (FileChannel/open
                (Paths/get p (into-array String []))
                (into-array StandardOpenOption
                            [StandardOpenOption/CREATE StandardOpenOption/WRITE]))]
        (try
          (.lock ch)
          (f)
          (finally (.close ch)))))))

(defn ^{:malli/schema [:=> [:cat :string] :any]}
  read-edn
  "Read an EDN file, returning nil if it doesn't exist."
  [path]
  (when (fs/exists? path)
    (edn/read-string (slurp path))))

(defn ^{:malli/schema [:=> [:cat :string :any] :any]}
  write-edn!
  "Atomically write EDN to path: writes to a unique temp file then atomically
  renames it into place. Creates parent dirs as needed. Protects readers from
  observing torn writes — the file is always either the previous good content or
  the new good content, never partial. Using a unique temp name per call makes
  concurrent writes to the same path safe from rename-races."
  [path data]
  (when-let [parent (fs/parent path)]
    (fs/create-dirs parent))
  (let [path-s (str path)
        tmp    (str path-s "." (java.util.UUID/randomUUID) ".tmp")]
    (spit tmp (str (pr-str data) "\n"))
    (fs/move tmp path-s {:replace-existing true})))

(defn ^{:malli/schema [:=> [:cat :string] :string]}
  lock-path-for
  "The lock that serialises updates to one file — a hidden sibling, so the lock
   travels with the file it guards and every process derives the same name from
   the same path.

   Public because an update that BRANCHES — writes on one outcome and refuses on
   another — cannot be expressed as `update-edn!`'s single function, and must
   still take this lock rather than one of its own. A second lock name for the
   same file excludes nobody."
  [path]
  (let [p (fs/path (str path))
        n (str "." (fs/file-name p) ".lock")]
    (str (if-let [parent (fs/parent p)]
           (fs/path parent n)
           (fs/path n)))))

(defn ^{:malli/schema [:=> [:cat :string [:=> [:cat :any] :any] [:* :any]] :any]}
  update-edn!
  "Read `path`, apply `f` to what it holds (nil when the file is absent), and
   write the result back — as ONE operation, serialised against every other
   updater of the same path. Returns the value written; `f` throwing writes
   nothing.

   The read being INSIDE the lock is the whole point rather than an
   implementation detail. `(write-edn! p (assoc (read-edn p) k v))` is two
   operations wearing one form: anything that landed between the read and the
   write is gone, the file is left perfectly well-formed, and nothing records
   that something went missing. write-edn!'s temp-and-rename does not help —
   that prevents a TORN write, and this is a LOST UPDATE.

   The window is not the microseconds the expression takes to evaluate, which is
   why `read` and `write` sitting on adjacent lines is not the reassurance it
   looks like. A `bb nido:*` process reads, then blocks on a slow path — or is
   abandoned and unwinds much later — and writes an hours-old snapshot over
   everything added since. Reading inside the lock makes every write derive from
   state read microseconds earlier, whatever the process did before it got here."
  [path f & args]
  (with-file-lock
    (lock-path-for path)
    (fn []
      (let [next (apply f (read-edn path) args)]
        (write-edn! path next)
        next))))

(defn ^{:malli/schema [:=> [:cat :string] :any]}
  read-json
  "Read a JSON file into keyword-keyed maps, returning nil if it doesn't exist.
  Throws on malformed JSON — callers that read files they don't own should
  guard."
  [path]
  (when (fs/exists? path)
    (json/parse-string (slurp (str path)) keyword)))

(defn ^{:malli/schema [:=> [:cat :string :any] :any]}
  write-json!
  "Write data as JSON to path, creating parent dirs."
  [path data]
  (when-let [parent (fs/parent path)]
    (fs/create-dirs parent))
  (spit path (json/generate-string data {:pretty true})))

(defn ^{:malli/schema [:=> [:cat :string] [:maybe :string]]}
  read-text
  "Read a text file, returning nil if it doesn't exist."
  [path]
  (when (fs/exists? path)
    (slurp path)))

(defn ^{:malli/schema [:=> [:cat :string :string] :any]}
  write-text!
  "Write text to path, creating parent dirs."
  [path text]
  (when-let [parent (fs/parent path)]
    (fs/create-dirs parent))
  (spit path text))
