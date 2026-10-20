(ns tasks.nido-bench
  "Bb task entry points for bench runs.

   Examples:
     bb nido:bench:memory :project \"brian\"
     bb nido:bench:memory :project \"brian\" :levers [:baseline :drop-techascent]
     bb nido:bench:memory:levers"
  (:require
   [clojure.edn :as edn]
   [nido.bench.memory :as bench]
   [nido.coordinator.record.session :as csession]))

(defn- parse-opts [args]
  (if (empty? args)
    {}
    (let [parse-arg (fn [arg]
                      (try (edn/read-string arg) (catch Exception _ arg)))
          values (map parse-arg args)]
      (when (odd? (count values))
        (throw (ex-info "Options must be key/value pairs" {:args args})))
      (apply hash-map values))))

(defn- require-project [opts]
  (or (some-> (:project opts) name)
      (throw (ex-info "Missing :project <name>"
                      {:hint "Pass :project \"<project-name>\"."}))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  memory
  "Run the memory-bench matrix for a project. See nido.bench.memory/run-all!."
  [& args]
  (let [opts (parse-opts args)
        project-name (require-project opts)
        session-name (or (some-> (:session opts) name) "perf-bench")
        levers (:levers opts)
        settle-ms (:settle-ms opts)
        ;; The benchmark stops and starts the session; a workstream holding it keeps it on the
        ;; workstream's runtime, which only nido can say.
        ws-id (csession/workstream-id-for (keyword project-name) session-name)
        kwargs (cond-> {:session-name session-name}
                 ws-id (assoc :runtime-key ws-id)
                 levers (assoc :levers levers)
                 settle-ms (assoc :settle-ms settle-ms))]
    (apply bench/run-all! project-name (mapcat identity kwargs))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  levers
  "List known bench levers."
  [& _args]
  (doseq [{:keys [name doc cp-only?]} bench/default-levers]
    (println (format "  %-28s %s%s"
                     (clojure.core/name name)
                     (if cp-only? "[cp-only] " "")
                     doc))))
