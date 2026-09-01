(ns nido.platform.lock
  "A machine-wide advisory mutex, keyed by name.

   nido runs many sessions on one laptop, and some of the things a session does
   size themselves as if they owned it — a full Dockerized CI suite is eight
   containers before anything else starts. Two at once do not take twice as
   long; they take longer than that AND come back unreadable, because every job
   trips its own wall-clock timeout at the same moment. A named lock lets a
   project declare that one of its commands is machine-exclusive, without
   either caller having to know the other exists.

   The lock is a FILE created with CREATE_NEW, and the owner's pid is written
   into that same open. Creation is atomic and fails if the path exists, so
   there is no instant at which the lock exists without saying who holds it —
   which a mkdir followed by a separate write cannot promise: a second caller
   arriving in that gap sees a lock with no owner, reads it as debris, and
   deletes the winner's claim.

   A holder that is killed leaves its file behind, so a lock whose recorded pid
   is no longer alive reads as free. That is the ONLY staleness rule — no
   lease, no timeout, nothing to tune, and nothing that can break a lock a live
   process still holds. Recovering a dead holder's lock races benignly: two
   callers may both clear it, and one of them wins the CREATE_NEW that follows."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str])
  (:import
   [java.nio.file Files OpenOption StandardOpenOption]))

(defn ^{:malli/schema [:=> [:cat] :Path]}
  locks-dir
  "Where locks live. Under the nido home so a lock outlives any one session and
   is visible to every session on the machine."
  []
  (fs/path (or (System/getenv "NIDO_HOME")
               (str (System/getProperty "user.home") "/.nido"))
           "locks"))

(defn- lock-path [lock-name]
  (fs/path (locks-dir)
           (str (str/replace (str lock-name) #"[^A-Za-z0-9._-]" "_") ".lock")))

(defn- pid-alive?
  "Whether `pid` names a live process. Guards pid <= 0 explicitly: macOS reports
   pid 0 (the kernel task) as alive, so a corrupted or hand-written owner file
   claiming it would pin the lock permanently with nothing able to clear it."
  [pid]
  (boolean (and pid
                (pos? (long pid))
                (some-> (java.lang.ProcessHandle/of (long pid))
                        (.orElse nil)
                        (.isAlive)))))

(defn- read-owner
  "The record written into the lock file, or nil if it is absent or unreadable.
   An unreadable file is treated as absent rather than as a held lock: a
   half-written or corrupted lock nobody can parse must not wedge the machine."
  [lock-name]
  (let [p (lock-path lock-name)]
    (when (fs/exists? p)
      (try (edn/read-string (slurp (fs/file p)))
           (catch Exception _ nil)))))

(defn ^{:malli/schema [:=> [:cat :string] [:maybe :map]]}
  holder
  "Who holds `lock-name`, or nil if nobody does. A lock whose owner is gone
   reads as free — callers never have to reason about staleness themselves."
  [lock-name]
  (let [owner (read-owner lock-name)]
    (when (pid-alive? (:pid owner))
      owner)))

(defn ^{:malli/schema [:=> [:cat :string] :boolean]}
  release!
  "Drop `lock-name`. Safe to call when not held."
  [lock-name]
  (boolean (fs/delete-if-exists (lock-path lock-name))))

(defn- write-claim!
  "Create the lock file and record the owner in the same operation. True if
   this process created it; false if someone else already had."
  [lock-name label]
  (let [p (fs/path (lock-path lock-name))]
    (try
      (with-open [out (Files/newOutputStream
                       p (into-array OpenOption [StandardOpenOption/CREATE_NEW
                                                 StandardOpenOption/WRITE]))]
        (.write out (.getBytes (pr-str {:pid (.pid (java.lang.ProcessHandle/current))
                                        :label label
                                        :since (str (java.time.Instant/now))})
                               "UTF-8")))
      true
      (catch java.nio.file.FileAlreadyExistsException _ false))))

(defn- claim!
  "One attempt. True if this process now owns the lock."
  [lock-name label]
  (fs/create-dirs (locks-dir))
  (cond
    ;; Ours already — re-entrant within a process, which matters because a
    ;; command may be composed by another command.
    (= (.pid (java.lang.ProcessHandle/current)) (:pid (read-owner lock-name)))
    true

    (write-claim! lock-name label)
    true

    ;; The file exists. If its owner is gone it is debris from a killed run,
    ;; and clearing it is the whole recovery story.
    (nil? (holder lock-name))
    (do (release! lock-name)
        (write-claim! lock-name label))

    :else false))

(defn ^{:malli/schema [:=> [:cat :string :string :map] :boolean]}
  acquire!
  "Take `lock-name`, waiting up to `:wait-ms` for the current holder to finish.
   Returns true if acquired. `:on-wait` is called once with the holder map when
   the caller starts waiting, so a CLI can say who it is waiting for rather
   than appearing to hang."
  [lock-name label {:keys [wait-ms poll-ms on-wait] :or {wait-ms 0 poll-ms 2000}}]
  (or (claim! lock-name label)
      (let [deadline (+ (System/currentTimeMillis) (long wait-ms))]
        (when (and on-wait (pos? wait-ms))
          (on-wait (holder lock-name)))
        (loop []
          (cond
            (claim! lock-name label) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep (long poll-ms)) (recur)))))))

(defn ^{:malli/schema [:=> [:cat :string :string :map [:=> [:cat] :any]] :any]}
  with-lock*
  "Run `f` holding `lock-name`, releasing it however `f` leaves. Throws if the
   lock cannot be taken within `:wait-ms`, naming the holder — a caller that
   silently ran anyway would be the bug this namespace exists to prevent."
  [lock-name label opts f]
  (if (acquire! lock-name label opts)
    (try (f) (finally (release! lock-name)))
    (throw (ex-info (str "Could not acquire lock: " lock-name)
                    {:lock lock-name
                     :holder (holder lock-name)
                     :waited-ms (:wait-ms opts 0)}))))
