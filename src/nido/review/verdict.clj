;; src/nido/review/verdict.clj
(ns nido.review.verdict
  "The design verdict: one pass after the review rounds terminate, asking whether
   the findings were the ironing-out of implementation details of a sound design,
   or evidence the design itself is wrong.

   Distinct from the in-loop warden in two ways. The warden decides whether to spend
   another fix round and is report-only (`:tools \"\"`), so it cannot read code —
   it reasons purely from what the record says. This pass is asked whether the
   design survived contact with the code, which cannot be answered without looking
   at it, so it runs with tools."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [nido.coordinator.agent :as agent]
   [nido.coordinator.report :as report]
   [nido.coordinator.state :as cstate]
   [nido.review.stages :as stages]))

(def ^:private fenced-json-re #"(?s)```json\s*(\{.*?\})\s*```")

(def ^:private verdicts #{:sound :strained :invalidated :standing-challenged})

(defn- bullets [xs] (str/join "\n" (map #(str "- " %) xs)))

(defn- baseline-section
  "The second yardstick. `baseline` is the :baseline record the design cited (nil
   for a pre-baseline design); `relation` is what the design declared about it.

   Two different checks live here, and the prompt keeps them apart. Whether the
   change RESPECTED its declared relation is a claim about the change: it said
   :within, so every load-bearing property should still stand. Whether the
   baseline was ACCURATE is a claim about the premise, and a design can be sound
   while resting on a baseline that was wrong — a different failure with a different
   remedy, which is why it gets its own classification rather than being rounded
   to the design being wrong."
  [baseline relation]
  (when baseline
    (str "\nWHAT THE AREA ALREADY WAS — the baseline this change was judged\n"
         "against, baselined BEFORE the design was written. These are properties\n"
         "the code relied on beforehand, with where they were read:\n"
         (str/join "\n"
                   (map #(str "- " (:property %)
                              " [" (str/join ", " (:evidence %)) "]")
                        (:load-bearing baseline)))
         "\n\n"
         "The change declared itself " (str/upper-case (name (:relation relation)))
         " this design"
         (case (:relation relation)
           :within  ", meaning it needs NONE of those properties to change.\n"
           :extends ", meaning it adds without contradicting any of them.\n"
           :revisit (str ", and named these as the ones it has to break:\n"
                         (bullets (:breaks relation)) "\n"))
         "\n"
         "So there are two things to check that the invariants alone cannot tell\n"
         "you:\n"
         "1. Did it honour that declaration? A load-bearing property broken\n"
         "   WITHOUT being named in the declaration is the design failing to be\n"
         "   what it said it was — report it in load_bearing_broken. A property\n"
         "   the change named and broke on purpose is NOT a finding.\n"
         "2. Was the baseline right? If a finding shows a stated property is\n"
         "   simply not true of the code, the design may be sound and the BASELINE\n"
         "   wrong. Classify that finding as \"baseline\" — it means re-survey,\n"
         "   not redesign, and the two must not be confused.\n"
         "Populate load_bearing_held with the properties this round confirmed\n"
         "still stand, for the same reason invariants_held exists.\n")))

(defn- phase-section
  "What a phase plan changes about the question being asked, or nil when the
   design lands in one go.

   Without this the verdict pass judges the middle of a migration against the end
   of it. A phased design's intermediate states are correct BY DESIGN — during an
   expand/migrate/contract, \"there is exactly one writer\" is deliberately untrue
   for the whole middle phase — so an :on-completion invariant that does not hold
   yet is the plan working, not the design failing.

   The pass is not told WHICH phase is current, because nothing tracks that yet.
   That is a real limit and the prompt says so rather than inviting a guess: an
   :on-completion invariant is reported on only when the code shows the last
   phase has landed and it still does not hold."
  [phases]
  (when (seq phases)
    (str "\nTHIS DESIGN LANDS IN " (count phases) " PHASES, not one. Each phase is a\n"
         "separate deploy that the system has to be able to live in:\n"
         (str/join "\n"
                   (map-indexed
                    (fn [i {:keys [claim habitable exit]}]
                      (str (inc i) ". " claim
                           "\n   while live: " habitable
                           "\n   moves on when: " (:criterion exit)))
                    phases))
         "\n\n"
         "An invariant marked \"holds on completion\" is NOT expected to hold before\n"
         "the last phase lands. Finding that one does not hold yet is the plan\n"
         "working as written — do not report it as broken. Report it only if the\n"
         "code shows the final phase has landed and it still does not hold.\n"
         "Invariants marked \"holds always\" are the ones that must be true at\n"
         "EVERY phase boundary, including this one; judge those normally.\n")))

(defn build-prompt
  "The verdict prompt. `design` is the workstream's :design record, `baseline` the
   :baseline record it cited (nil when it predates them), `findings` the findings
   still open at the end, `history` the per-round digest."
  [{:keys [design baseline stance findings history rounds]}]
  (str
   "You are judging whether a DESIGN survived a code review, not whether the code\n"
   "is correct. The review loop has finished; the fixes it wanted are already in.\n\n"
   "Read the code where you need to — you have tools, and the question cannot be\n"
   "answered from the diff summary alone.\n\n"
   "THE DESIGN THIS CHANGE COMMITTED TO:\n"
   "Shape: " (:shape design) "\n"
   "Invariants:\n"
   (bullets (map (fn [i]
                   (let [{t :invariant h :holds} (report/invariant i)]
                     (str t " [holds " (name h) "]")))
                 (:invariants design)))
   "\n"
   (phase-section (:phases design))
   (when-let [r (seq (:rejected design))]
     (str "Already rejected (a finding re-proposing one of these is ANSWERED,\n"
          "not evidence against the design — unless the reason no longer holds):\n"
          (bullets (map #(str (:alternative %) " — because " (:why-not %)) r)) "\n"))
   (when-let [a (seq (:assumes design))]
     (str "What this change ASSUMED about the area's current design. If a finding\n"
          "shows an assumption was false, the design may have been sound and the\n"
          "premise wrong — say so, it is a different failure:\n"
          (bullets (map :about a)) "\n"))
   (baseline-section baseline (:baseline design))
   "\n"
   (when stance
     (str "PROJECT STANCE — framing only, never cite it against a specific\n"
          "finding:\n" stance "\n\n"))
   "Rounds run: " rounds "\n"
   "Round history (findings + what was fixed):\n" (pr-str history) "\n\n"
   "Findings still open at the end. Each carries the reach the reviewer assigned:\n"
   "local (a defect inside the current design), structural (about where a\n"
   "boundary sits — the reviewer saw shape without intent), or unclear. The\n"
   "structural ones are what you are really here to adjudicate: the reviewer\n"
   "could not see the design, and you can.\n"
   (if (seq findings)
     (->> findings
          (map-indexed (fn [i f] (str i ": [P" (:priority f) "/"
                                      (name (or (:reach f) :unclear)) "] "
                                      (:title f) " — " (:body f))))
          (str/join "\n"))
     "(none)")
   "\n\n"
   "Return EXACTLY one fenced ```json block, nothing after it:\n"
   "{\"verdict\": \"sound|strained|invalidated|standing_challenged\",\n"
   " \"reason\": \"...\",\n"
   " \"invariants_held\": [\"...\"],\n"
   " \"invariants_broken\": [{\"invariant\": \"...\", \"finding\": \"...\"}],\n"
   " \"load_bearing_held\": [\"...\"],\n"
   " \"load_bearing_broken\": [{\"invariant\": \"...\", \"finding\": \"...\"}],\n"
   " \"findings_classified\": [{\"finding\": \"...\", \"as\": \"implementation|design|stance|baseline\"}],\n"
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

            (seq (:load_bearing_held m))
            (assoc :load-bearing-held (mapv str (:load_bearing_held m)))

            (seq (:load_bearing_broken m))
            (assoc :load-bearing-broken
                   (mapv #(-> {:invariant (str (:invariant %))
                               :finding   (str (:finding %))})
                         (:load_bearing_broken m)))

            (seq (:findings_classified m))
            (assoc :findings-classified
                   (into []
                         (keep #(let [as (keyword (str (:as %)))]
                                  (when (#{:implementation :design :stance :baseline} as)
                                    {:finding (str (:finding %)) :as as})))
                         (:findings_classified m)))

            (not (str/blank? (str (:needs m))))
            (assoc :needs (str (:needs m))))))
      (catch Exception _ nil))))

(defn decision?
  "True when the verdict is one a human has to answer rather than read."
  [v]
  (boolean (#{:invalidated :standing-challenged} (:verdict v))))

(defn still-open
  "Findings that actually remain at the end — the warden closed the rest, by a
   named authority. Handing a closed finding to the verdict as \"still open\"
   would have it re-adjudicate something already decided, against a design it is
   supposed to be checking."
  [findings]
  (into [] (remove #(= :closed (:disposition %))) findings))

(defn run!
  "Run the verdict pass. Returns the verdict map, or nil when there is no design
   record to judge against, the agent no-ops, or the answer is unparseable — all
   three mean 'nothing to record', never a fabricated :sound."
  [{:keys [cwd run-id budget final report]}]
  (when-let [design (stages/discover-design-record cwd)]
    (let [prompt (build-prompt
                  {:design design
                   :baseline (stages/discover-baseline cwd design)
                   :stance (stages/read-stance (first (stages/project+ws-from-cwd cwd)))
                   :findings (still-open (:findings final))
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
