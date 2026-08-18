;; src/nido/review/verdict.clj
(ns nido.review.verdict
  "The design verdict: one pass after the review rounds terminate, asking whether
   the findings were the ironing-out of implementation details of a sound design,
   or evidence the design itself is wrong.

   Distinct from the in-loop judge in two ways. The judge decides whether to spend
   another fix round and is report-only (`:tools \"\"`), so it cannot read code —
   it reasons purely from what the record says. This pass is asked whether the
   design survived contact with the code, which cannot be answered without looking
   at it, so it runs with tools."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [nido.coordinator.agent :as agent]
   [nido.coordinator.state :as cstate]
   [nido.review.stages :as stages]))

(def ^:private fenced-json-re #"(?s)```json\s*(\{.*?\})\s*```")

(def ^:private verdicts #{:sound :strained :invalidated :standing-challenged})

(defn- bullets [xs] (str/join "\n" (map #(str "- " %) xs)))

(defn build-prompt
  "The verdict prompt. `design` is the workstream's :design record, `findings` the
   findings still open at the end, `history` the per-round digest."
  [{:keys [design stance findings history rounds]}]
  (str
   "You are judging whether a DESIGN survived a code review, not whether the code\n"
   "is correct. The review loop has finished; the fixes it wanted are already in.\n\n"
   "Read the code where you need to — you have tools, and the question cannot be\n"
   "answered from the diff summary alone.\n\n"
   "THE DESIGN THIS CHANGE COMMITTED TO:\n"
   "Shape: " (:shape design) "\n"
   "Invariants:\n" (bullets (:invariants design)) "\n"
   (when-let [r (seq (:rejected design))]
     (str "Already rejected (a finding re-proposing one of these is ANSWERED,\n"
          "not evidence against the design — unless the reason no longer holds):\n"
          (bullets (map #(str (:alternative %) " — because " (:why-not %)) r)) "\n"))
   (when-let [a (seq (:assumes design))]
     (str "What this change ASSUMED about the area's current design. If a finding\n"
          "shows an assumption was false, the design may have been sound and the\n"
          "premise wrong — say so, it is a different failure:\n"
          (bullets (map :about a)) "\n"))
   "\n"
   (when stance
     (str "PROJECT STANCE — framing only, never cite it against a specific\n"
          "finding:\n" stance "\n\n"))
   "Rounds run: " rounds "\n"
   "Round history (findings + what was fixed):\n" (pr-str history) "\n\n"
   "Findings still open at the end:\n"
   (if (seq findings)
     (->> findings
          (map-indexed (fn [i f] (str i ": [P" (:priority f) "] " (:title f)
                                      " — " (:body f))))
          (str/join "\n"))
     "(none)")
   "\n\n"
   "Return EXACTLY one fenced ```json block, nothing after it:\n"
   "{\"verdict\": \"sound|strained|invalidated|standing_challenged\",\n"
   " \"reason\": \"...\",\n"
   " \"invariants_held\": [\"...\"],\n"
   " \"invariants_broken\": [{\"invariant\": \"...\", \"finding\": \"...\"}],\n"
   " \"findings_classified\": [{\"finding\": \"...\", \"as\": \"implementation|design|stance\"}],\n"
   " \"needs\": \"...\"}\n\n"
   "- sound: the findings were implementation details. THIS IS THE EXPECTED\n"
   "  OUTCOME. Populate invariants_held with the ones this round actually\n"
   "  confirmed — that is the point of the verdict, not a formality.\n"
   "- strained: the design holds, but a boundary is visibly under pressure —\n"
   "  findings clustering on one seam, the same argument recurring. Ship it, but\n"
   "  say where the pressure is.\n"
   "- invalidated: the findings contradict a named invariant; the design itself\n"
   "  is wrong. REQUIRES invariants_broken and needs.\n"
   "- standing_challenged: the finding is right, the change is right, and the\n"
   "  PROJECT STANCE is what needs to move. Rare. REQUIRES needs.\n\n"
   "Do not reach for invalidated because the review was noisy. A design is only\n"
   "invalidated when you can name the invariant that cannot hold."))

(defn parse
  "Last fenced ```json block -> verdict map, or nil when absent/unparseable/unknown.
   nil is a non-answer, not a verdict: the caller records nothing rather than
   inventing one."
  [text round design-seq]
  (when-let [body (some-> (when (string? text) (last (re-seq fenced-json-re text)))
                          second)]
    (try
      (let [m (json/parse-string body true)
            v (keyword (str/replace (str (:verdict m)) "_" "-"))]
        (when (verdicts v)
          (cond-> {:format :design-verdict
                   :verdict v
                   :round round
                   :design-seq design-seq
                   :reason (str (:reason m))}
            (seq (:invariants_held m))
            (assoc :invariants-held (mapv str (:invariants_held m)))

            (seq (:invariants_broken m))
            (assoc :invariants-broken
                   (mapv #(-> {:invariant (str (:invariant %))
                               :finding   (str (:finding %))})
                         (:invariants_broken m)))

            (seq (:findings_classified m))
            (assoc :findings-classified
                   (into []
                         (keep #(let [as (keyword (str (:as %)))]
                                  (when (#{:implementation :design :stance} as)
                                    {:finding (str (:finding %)) :as as})))
                         (:findings_classified m)))

            (not (str/blank? (str (:needs m))))
            (assoc :needs (str (:needs m))))))
      (catch Exception _ nil))))

(defn decision?
  "True when the verdict is one a human has to answer rather than read."
  [v]
  (boolean (#{:invalidated :standing-challenged} (:verdict v))))

(defn run!
  "Run the verdict pass. Returns the verdict map, or nil when there is no design
   record to judge against, the agent no-ops, or the answer is unparseable — all
   three mean 'nothing to record', never a fabricated :sound."
  [{:keys [cwd run-id budget final report]}]
  (when-let [design (stages/discover-design-record cwd)]
    (let [prompt (build-prompt
                  {:design design
                   :stance (stages/read-stance (first (stages/project+ws-from-cwd cwd)))
                   :findings (:findings final)
                   :history (mapv #(dissoc % :findings) (:history final))
                   :rounds (or (get-in report [:summary :rounds]) 0)})
          {:keys [num-turns result-error? result-text]}
          (agent/launch! {:run-id run-id :cwd cwd
                          :first-message prompt :budget budget
                          :err-file (str (fs/path (cstate/run-dir run-id) "agent.err.log"))})]
      (when-not (or (zero? (or num-turns 0)) result-error?)
        (parse result-text
               (or (get-in report [:summary :rounds]) 0)
               (:seq design))))))
