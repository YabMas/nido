(ns nido.core.provision
  "Bringing a unit's session up, stopping it and destroying it, as the core records it.

   The rules record a provisioning attempt; this carries out each one it is handed and reports the
   outcome as a keyed command. The session itself is the session verb's: a bounded call of
   `bb nido:session:provision`, which works through session-lifecycle alone and carries the
   session into no conductor, seat or agent. The verb is idempotent, so an attempt a kill left
   unresolved is simply made again by the daemon that starts next.

   What a unit's session is called: the last segment of the working directory it was recorded
   ready at, else the unit's id — so a session the cut-over adopted keeps its own name.

   Asleep, nothing here runs: it acts only on the commits it is handed, one attempt at a time per
   unit, on a virtual thread each."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [nido.core.domain :as d]))

(def ^:private default-timeout-ms
  "How long one call of the session verb may take before the attempt is recorded failed. A cold
   bring-up clones a database and boots a JVM."
  (* 20 60 1000))

(defn ^{:malli/schema [:=> [:cat :map] :string]} session-name
  "The session attempt `a` ({:unit :cwd}) works on."
  [{:keys [unit cwd]}]
  (or (some-> cwd io/file .getName not-empty) unit))

(defn ^{:malli/schema [:=> [:cat :map :map] [:sequential :string]]} verb-argv
  "The argv of the session verb for attempt `a` ({:unit :want :project :cwd :profile})."
  [{:keys [bb-bin nido-dir]} {:keys [want project cwd profile] :as a}]
  (cond-> [(or bb-bin "bb") "--config" (str nido-dir "/bb.edn") "nido:session:provision"
           ":project" (str project) ":want" (name want) (session-name a)]
    cwd     (into [":path" cwd])
    profile (into [":profile" (name profile)])))

(defn ^{:malli/schema [:=> [:cat :string] [:maybe :map]]} parse-result
  "The verb's result: the last line of its output that reads as an EDN map, or nil."
  [out]
  (some (fn [line] (try (let [x (edn/read-string line)] (when (map? x) x)) (catch Exception _ nil)))
        (reverse (str/split-lines (or out "")))))

(defn- run-verb!
  "Run `argv`, bounded by `timeout-ms`: {:exit :out}, exit nil when it timed out."
  [argv timeout-ms]
  (let [pb (doto (ProcessBuilder. ^java.util.List argv) (.redirectErrorStream true))
        p (.start pb)
        out (future (slurp (.getInputStream p)))
        done? (.waitFor p timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)]
    (when-not done? (.destroyForcibly (.toHandle p)) (.destroyForcibly p))
    {:exit (when done? (.exitValue p)) :out (deref out 5000 "")}))

(defn ^{:malli/schema [:=> [:cat :map :map] :map]} outcome-command
  "The keyed command recording attempt `a`'s outcome, from the verb's run `r` ({:exit :out})."
  [{:keys [ws unit attempt want]} {:keys [exit out]}]
  (let [res (parse-result out)
        ok? (and (= 0 exit) (not (:error res)))]
    {:key (str "provision/" ws "/" unit "/" attempt)
     :ws ws
     :command (cond-> {:command :record-provision-outcome :unit unit :attempt attempt
                       :outcome (if ok? :done :failed)}
                (and ok? (= :up want)) (assoc :cwd (:cwd res))
                (not ok?) (assoc :detail (str/trim (str (or (:error res)
                                                            (if exit (str "exit " exit) "timed out"))
                                                        (when-not (:error res)
                                                          (str ": " (last (str/split-lines (str out)))))))))}))

(defn ^{:malli/schema [:=> [:cat :map] :map]} start
  "Start the provisioner. Options:
     :submit      (fn [envelope]) a command to the writer
     :nido-dir    the nido checkout whose bb.edn holds the session verb
     :bb-bin      bb's path (default `bb`)
     :timeout-ms  one call's bound (default 20 minutes)
     :run-verb    (fn [argv timeout-ms]) -> {:exit :out}; a test's stand-in for the verb
   Feed it committed changes [{:ws :state} ...] with `changed!`; a start hands it every
   workstream not closed the same way, which is how an attempt a kill left is made again."
  [{:keys [submit timeout-ms run-verb] :or {timeout-ms default-timeout-ms run-verb run-verb!} :as opts}]
  (let [in-flight (atom #{})]
    {:changed (fn [changes]
                (doseq [{:keys [ws state]} changes
                        a (d/attempts-pending state)
                        :let [k [ws (:unit a) (:attempt a)]]
                        :when (not (contains? @in-flight k))]
                  (swap! in-flight conj k)
                  (Thread/startVirtualThread
                   (fn []
                     (try
                       (let [r (try (run-verb (verb-argv opts a) timeout-ms)
                                    (catch Throwable t {:exit nil :out (str (.getMessage t))}))]
                         ;; Waited for, so a commit landing meanwhile still finds it in flight.
                         (deref (submit (outcome-command (assoc a :ws ws) r)) 60000 nil))
                       (finally (swap! in-flight disj k)))))))}))

(defn ^{:malli/schema [:=> [:cat :map [:sequential :map]] :nil]} changed!
  "Hand the provisioner a commit's changes. Never blocks."
  [provisioner changes]
  ((:changed provisioner) changes)
  nil)
