(ns nido.core.cutover
  "The one-shot cut-over from the old work plane to the core: the old daemon and every legacy agent
   session stopped, the legacy ledgers imported read-only, each unclosed workstream handed to its
   conductor.

   This is the store's half, run in the JVM while no core daemon runs: import every legacy ledger
   entry under the nido home, then commit a hand-over `plan` through a writer of its own. The plan
   is what the legacy world says, read by the bb verb that stopped it
   (`tasks.nido-core-cutover-cmd`): for each unclosed workstream, its commands — the unit opened
   over the session it had, an unknown where that session was stopped mid-turn, and the hand-over
   item. Each command is keyed by its place in the plan, so running a plan again is answered, not
   repeated. Last, it writes the marker the core daemon will not start without (`marker`)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [nido.core.release :as release]
            [nido.core.store :as store]
            [nido.core.writer :as writer]))

(defn ^{:malli/schema [:=> [:cat :map] :string]} marker
  "The file under the core's directory `ps` (`release/paths`) that says the cut-over ran: the old
   daemon was stopped and disabled before anything was imported."
  [ps]
  (str (:core ps) "/cut-over.edn"))

(defn ^{:malli/schema [:=> [:cat :map [:sequential :map]] :map]} apply-plan!
  "Import nido home `(:home ps)`'s legacy ledgers into its core store and commit `plan`, a sequence
   of {:ws <ws-key> :commands [command ...]}. Answers {:import {...} :handed n :refused [[ws
   command refusal] ...]}. A refusal stops nothing else: it is reported."
  [ps plan]
  (let [s (store/open (:db ps))]
    (try
      (let [imported (store/import-legacy! s (:home ps))
            w (writer/start s {})
            outcomes (doall
                      (for [{:keys [ws commands]} plan
                            [i c] (map-indexed vector commands)]
                        [ws c @(writer/submit! w {:key (str "cutover/" ws "/" i) :ws ws :command c})]))]
        (writer/stop! w)
        (spit (marker ps) (pr-str {:at (str (java.time.Instant/now)) :workstreams (count plan)}))
        {:import   imported
         :handed   (count plan)
         :refused  (vec (for [[ws c o] outcomes :when (not (contains? o :events))]
                          [ws (:command c) (or (:refused o) (:error o))]))})
      (finally (store/close s)))))

(defn ^{:malli/schema [:=> [:cat :string] :any]} -main
  "`<plan.edn>`: apply the plan to the nido home's core store, and print the outcome as EDN."
  [plan-path]
  (let [ps (release/paths)]
    (io/make-parents (io/file (:db ps)))
    (prn (apply-plan! ps (edn/read-string (slurp plan-path))))
    (shutdown-agents)))
