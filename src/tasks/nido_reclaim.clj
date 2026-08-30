(ns tasks.nido-reclaim
  "Bb task entry point for reclaiming orphaned per-instance state dirs."
  (:require
   [clojure.edn :as edn]
   [nido.session.reclaim :as reclaim]))

(defn- parse-opts [args]
  (if (empty? args)
    {}
    (let [parse-arg (fn [arg]
                      (try (edn/read-string arg) (catch Exception _ arg)))
          values (map parse-arg args)]
      (when (odd? (count values))
        (throw (ex-info "Options must be key/value pairs" {:args args})))
      (apply hash-map values))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  run
  "Delete per-instance state dirs not referenced by any registry entry.
   Default is list-only; pass :force? true to actually delete.
   Also accepts :force (zsh users: quote it as ':force?')."
  [& args]
  (let [opts (parse-opts args)]
    (reclaim/reclaim! :force? (boolean (or (:force? opts) (:force opts))))))
