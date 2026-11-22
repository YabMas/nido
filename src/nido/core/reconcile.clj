(ns nido.core.reconcile
  "What nido knows about things it does not own, and how it asks.

   An observation is a fact with when it was made and how long it holds. Read past that, or read
   from an observation whose observing failed, it is unknown: never absent, never empty, never
   the last value seen.

   Every call outside the daemon carries a deadline. A command that ends in time answers its exit
   and output; one past its deadline is ended and answers unknown, as does one that could not be
   started. Nothing here retries: whoever needs the fact decides whether to ask again.

   A reconciler converges on events and resyncs at most once a `:resync-ms` (default a minute),
   so a fact an event delivers is never polled for. Idle, its thread blocks."
  (:require [clojure.java.io :as io])
  (:import [java.lang ProcessHandle]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

;; ── observations ─────────────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :any :int :int] :map]} observed
  "An observation of `value`, made at `at` (epoch ms), holding for `holds-ms`."
  [value at holds-ms]
  {::value value ::at at ::holds-ms holds-ms})

(defn ^{:malli/schema [:=> [:cat :int :any] :map]} failed
  "An observation whose observing failed at `at`, saying `why`. It reads as unknown."
  [at why]
  {::failed why ::at at ::holds-ms 0})

(defn ^{:malli/schema [:=> [:cat [:maybe :map] :int] :any]} fact
  "What observation `o` says at `now`: its value while it holds, else `::unknown`. No observation
   at all is unknown too."
  [o now]
  (if (and o (not (::failed o)) (< now (+ (::at o) (::holds-ms o))))
    (::value o)
    ::unknown))

(defn ^{:malli/schema [:=> [:cat :any] :boolean]} unknown?
  "Whether `x`, a `fact`, is unknown."
  [x]
  (= ::unknown x))

;; ── bounded calls ────────────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat [:sequential :string] :map] :map]} call!
  "Run `argv` with a deadline. Options: :deadline-ms (required), :dir, :env.

   Answers {:exit n :out s :err s} when it ended in time, {:unknown :deadline} when it was ended
   at its deadline, {:unknown :not-started :message s} when it could not be started. Output is
   read as it is produced, so a full pipe cannot hold a command past its deadline."
  [argv {:keys [deadline-ms dir env]}]
  (assert (pos-int? deadline-ms) "every call outside the daemon carries a deadline")
  (let [pb (ProcessBuilder. ^java.util.List (vec argv))]
    (when dir (.directory pb (io/file dir)))
    (when env (.putAll (.environment pb) (update-keys env str)))
    (try
      (let [p (.start pb)
            out (future (slurp (.getInputStream p)))
            err (future (slurp (.getErrorStream p)))]
        (.close (.getOutputStream p))
        (if (.waitFor p deadline-ms TimeUnit/MILLISECONDS)
          {:exit (.exitValue p) :out @out :err @err}
          (do (doseq [c (iterator-seq (.iterator (.descendants (.toHandle p))))]
                (.destroyForcibly ^ProcessHandle c))
              (.destroyForcibly p)
              {:unknown :deadline})))
      (catch java.io.IOException e
        {:unknown :not-started :message (.getMessage e)}))))

;; ── reconcilers ──────────────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :map] :map]} start
  "Start a reconciler: `:converge` (fn [events]) is called on its thread with the events that
   arrived since the last call, or with [] on a resync when none did for `:resync-ms`."
  [{:keys [converge resync-ms name] :or {resync-ms 60000 name "nido-reconcile"}}]
  (let [q (LinkedBlockingQueue.)
        t (doto (Thread. ^Runnable
                         (fn []
                           (loop []
                             (let [first-e (.poll q (long (max resync-ms 60000)) TimeUnit/MILLISECONDS)]
                               (when-not (= ::stop first-e)
                                 (let [more (java.util.ArrayList.)]
                                   (.drainTo q more)
                                   (let [evs (cond-> [] first-e (conj first-e) true (into more))
                                         stop? (some #{::stop} evs)]
                                     (try (converge (vec (remove #{::stop} evs)))
                                          (catch Throwable e
                                            (binding [*out* *err*] (println name "-" (.getMessage e)))))
                                     (when-not stop? (recur))))))))
                         name)
            (.setDaemon true) (.start))]
    {:queue q :thread t}))

(defn ^{:malli/schema [:=> [:cat :map :any] :nil]} event!
  "Deliver an event to reconciler `r`."
  [r e]
  (.put ^LinkedBlockingQueue (:queue r) e)
  nil)

(defn ^{:malli/schema [:=> [:cat :map] :nil]} stop!
  "Stop reconciler `r` once it has converged on what it holds."
  [r]
  (.put ^LinkedBlockingQueue (:queue r) ::stop)
  (.join ^Thread (:thread r))
  nil)
