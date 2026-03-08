(ns tasks.nido-vsdd
  "CLI entry points for VSDD orchestration."
  (:require [clojure.edn :as edn]
            [nido.io :as io]
            [nido.vsdd.loop :as vsdd]))

(defn- parse-args
  "Parse CLI args as EDN key/value pairs."
  [args]
  (let [parse-arg (fn [arg]
                    (try (edn/read-string arg)
                         (catch Exception _ arg)))
        values (map parse-arg args)]
    (when (odd? (count values))
      (throw (ex-info "Options must be key/value pairs" {:args args})))
    (apply hash-map values)))

(defn- load-vsdd-config
  "Load vsdd.edn from project dir and merge with CLI overrides."
  [project-dir overrides]
  (let [config-file (str project-dir "/.nido/vsdd.edn")
        base (or (io/read-edn config-file) {})]
    (merge base
           {:working-dir project-dir}
           overrides)))

(defn run
  "Run VSDD loop.
   Usage: bb nido:vsdd:run :project-dir <path> :module-path <path> [opts...]"
  [& args]
  (let [opts (parse-args args)
        project-dir (or (:project-dir opts)
                        (throw (ex-info "Missing :project-dir" {:args args})))
        module-path (or (:module-path opts)
                        (throw (ex-info "Missing :module-path" {:args args})))
        config (load-vsdd-config project-dir
                                 (cond-> {:module-path module-path}
                                   (:max-iterations opts)
                                   (assoc :max-iterations (:max-iterations opts))))]
    (vsdd/run config)))
