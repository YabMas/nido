;; src/nido/review/replay.clj
(ns nido.review.replay
  "Replay recorded record runs through the engine as it stands, to see where today's stopping
   rules would have ended them.

   Every change to when a loop stops was landed on the evidence of the run that prompted it, and
   none was tried against the runs it would also govern. Four of them — settle on two judges,
   alternate the judge, re-ask an omission, distrust unchecked-over-held — were each right about
   their own run and together kept design-loop-7d596499 going for 25 rounds. The rounds every run
   already went through are on disk; replaying them through `run-loop` is the cheapest experiment
   there is, and it runs the engine's own code rather than a model of it.

   What a replay can and cannot say. Each recorded round is handed back to the engine as its judge
   stage's result, so the engine's own stops — the cap, `no-progress?`, the give-up counter,
   `stalled?` — are exercised for real. A stop the record pipeline makes inside its judge stage
   (unruled-stop, :asked, :disputed) is replayed as recorded: the round that ended the run carries
   its recorded status, and nothing earlier can end there. So a replay answers whether the engine would
   now stop this run sooner, and on what — which is the question a terminator change asks."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [nido.coordinator.record.state :as cstate]
   [nido.review.loop :as rloop]
   [nido.review.record :as record]))

(defn- judge-of [rd]
  (some #(when (= "judge" (:phase %)) %) (:phases rd)))

(defn- amended? [rd]
  (boolean (some #(= "amend" (:phase %)) (:phases rd))))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :map]]}
  replay
  "Drive `run-loop` over a record run's recorded rounds. Returns `{:run-id :recorded {:status
   :rounds} :replayed {:status :rounds :stalled-on}}`, or nil for a report with no judged round."
  [report]
  (let [rounds (vec (:rounds report))
        n      (count rounds)]
    (when (and (pos? n) (every? judge-of rounds))
      (let [judge (fn [ctx]
                    (let [i  (dec (:iter ctx))
                          rd (nth rounds i)
                          j  (judge-of rd)
                          c  (assoc ctx
                                    :findings (vec (:findings j))
                                    :judged-seq (:judged-seq j)
                                    :record {:read-once        (:read-once j)
                                             :unruled          (:unruled j)
                                             :relation-unruled (:relation-unruled j)}
                                    :history (conj (vec (:history ctx))
                                                   {:iter (:iter ctx) :findings (vec (:findings j))
                                                    :amended? (amended? rd)}))]
                      (if (< (inc i) n)
                        (assoc c :control :next-round)
                        (assoc c :status (keyword (str (:status report)))))))
            out (rloop/run-loop
                 {:run-id      (:run-id report)
                  :pipeline    [{:name :judge :run judge}]
                  :judged-after :judge
                  :finding-key #(or (:handle %) (:title %) %)
                  :changed?    (fn [_ prior] (boolean (:amended? (last prior))))
                  :open        record/round-open})]
        {:run-id   (:run-id report)
         :recorded {:status (keyword (str (:status report))) :rounds n}
         :replayed (cond-> {:status (:status out) :rounds (:iter out)}
                     (:stalled-on out) (assoc :stalled-on (:stalled-on out)))}))))

(defn ^{:malli/schema [:=> [:cat :int] [:vector :map]]}
  replay-recent!
  "`replay` over every record run whose run dir moved in the last `days` days."
  [days]
  (let [cutoff (- (System/currentTimeMillis) (* days 86400000))]
    (into []
          (comp (filter #(re-find #"^(design|baseline)-loop-" (str (fs/file-name %))))
                (filter #(> (.toMillis (fs/last-modified-time %)) cutoff))
                (map #(fs/path % "report.json"))
                (filter fs/exists?)
                (keep #(try (replay (json/parse-string (slurp (str %)) true))
                            (catch Exception _ nil))))
          (when (fs/exists? (cstate/runs-dir)) (fs/list-dir (cstate/runs-dir))))))

(defn ^{:malli/schema [:=> [:cat :map] :boolean]}
  diverged?
  "Whether today's engine would have ended a run before its recorded end."
  [{:keys [recorded replayed]}]
  (< (:rounds replayed) (:rounds recorded)))

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  line
  "One divergence as a line."
  [{:keys [run-id recorded replayed]}]
  (str run-id ": recorded " (name (:status recorded)) " after " (:rounds recorded)
       " round(s); would end " (name (:status replayed)) " at round " (:rounds replayed)
       (when-let [o (:stalled-on replayed)]
         (str " — open: " (str/join ", " (sort (concat (:read-once o) (:unruled o))))))))
