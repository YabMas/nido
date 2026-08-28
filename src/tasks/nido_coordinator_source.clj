(ns tasks.nido-coordinator-source
  "Bb task entry points for source-instance inspection + reset (Stage 5)."
  (:require
   [nido.coordinator.sources :as sources]
   [nido.coordinator.sources.notion :as nsource]
   [nido.coordinator.sources.state :as sst]
   [nido.platform.task-args :as task-args]))

(defn list-cmd
  "bb nido:coordinator:source:list -- one row per source-instance state file."
  [& _args]
  (let [hashes (sst/list-state-hashes)]
    (if (empty? hashes)
      (println "No source instances on disk.")
      (doseq [h hashes
              :let [s (sst/read-state h)]]
        (println (format "%s  type=%s  poll=%s  failures=%d  breaker=%s  last=%s"
                         h
                         (name (or (:type s) :unknown))
                         (str (-> s :source-config :poll))
                         (or (:consecutive-failures s) 0)
                         (name (or (:breaker s) :closed))
                         (or (:last-polled-at s) "never")))))))

(defn reset-cmd
  "bb nido:coordinator:source:reset :type <source-type> :database <id> [:view <name>] [:poll <dur>]
   Clears breaker + consecutive-failures for the matching source-config."
  [& args]
  (nsource/register!)
  (let [[_ opts]    (task-args/split-args args)
        source-type (some-> (:type opts) keyword)
        config      (dissoc opts :type)]
    (cond
      (or (nil? source-type) (nil? (sources/lookup source-type)))
      (do (println "Unknown source type." source-type) (System/exit 1))

      :else
      (let [hash  (sources/config-hash (assoc config :type source-type))
            prior (sst/read-state hash)]
        (if (nil? prior)
          (println "No state for that source-config (hash" hash ").")
          (do (sst/write-state! hash (-> prior
                                         (assoc :consecutive-failures 0)
                                         (dissoc :breaker)))
              (println "Reset" (name source-type) "hash" hash "-- breaker cleared.")))))))
