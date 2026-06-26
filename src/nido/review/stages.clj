;; src/nido/review/stages.clj
(ns nido.review.stages
  "The review loop's three stages (review/judge/fix) as {:name :run} maps,
   plus the judge-decision parser. Stages only transform the iteration
   context; the engine owns flow."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [nido.review.codex :as codex]))

(def ^:private fenced-json-re #"(?s)```json\s*(\{.*?\})\s*```")

(defn parse-judge-decision
  "Last fenced ```json block in `text` -> decision map. Unparseable -> indeterminate."
  [text]
  (let [block (when (string? text) (last (re-seq fenced-json-re text)))]
    (if-let [body (second block)]
      (try
        (let [m (json/parse-string body true)
              d (keyword (:decision m))]
          (if (contains? #{:continue :stop :escalate} d)
            {:decision d :reason (:reason m) :fix-findings (:fix_findings m)}
            {:decision :indeterminate :reason (str "unknown decision: " (:decision m))}))
        (catch Exception e
          {:decision :indeterminate :reason (str "unparseable: " (ex-message e))}))
      {:decision :indeterminate :reason "no json decision block"})))

(def review-stage
  {:name :review
   :run  (fn [ctx]
           (let [{:keys [cwd base run-id]} (:config ctx)
                 res (codex/review! {:cwd cwd :base base :run-id run-id})]
             (if (= :clean (:status res))
               (assoc ctx :findings [] :control :stop :status :clean)
               (assoc ctx
                      :findings (:findings res)
                      :overall-correctness (:overall-correctness res)))))})
