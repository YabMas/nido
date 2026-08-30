(ns nido.coordinator.source.queue
  "The :manual event source — a filesystem queue of envelopes.

   - `enqueue!` writes an envelope file under ~/.nido/coordinator/queue/<uuid>.edn
   - `drain!` reads, deletes, and returns all pending envelopes (skipping
     malformed files, which are renamed `<file>.malformed` for inspection)."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
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

(defn drain!
  "Read and remove all envelope files. Returns a vector of envelopes.
   Malformed files are renamed `<file>.malformed` and skipped."
  []
  (let [files (->> (fs/list-dir (cstate/queue-dir))
                   (filter #(re-matches #".*\.edn$" (str (fs/file-name %))))
                   (sort-by str))]
    (reduce
      (fn [acc f]
        (let [[envelope err] (read-envelope-file f)]
          (if err
            (do
              (fs/move f (str f ".malformed"))
              (binding [*err* *err*]
                (.println ^java.io.PrintWriter *err*
                          (str "WARN: malformed queue file " f " — " (ex-message err))))
              acc)
            (do
              (fs/delete f)
              (conj acc envelope)))))
      []
      files)))

(defn enqueue!
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
