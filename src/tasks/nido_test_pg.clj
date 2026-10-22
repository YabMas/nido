(ns tasks.nido-test-pg
  (:require
   [clojure.edn :as edn]
   [nido.session.test-pg :as test-pg]))

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
                      {:hint "Pass :project \"<project-name>\" — the name used in `bb nido:project:add`."}))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  status
  "Show the project's test cluster and the databases in it.

   Usage:
     bb nido:test-pg:status :project \"brian\""
  [& args]
  (test-pg/status (require-project (parse-opts args))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  reset
  "Delete the project's test cluster, every session's test database with it.
   The next session start rebuilds the cluster and its template.

   Usage:
     bb nido:test-pg:reset :project \"brian\""
  [& args]
  (test-pg/reset! (require-project (parse-opts args))))
