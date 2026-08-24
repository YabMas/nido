(ns nido.review.record
  "Judgment passes over a LEDGER RECORD rather than over a diff.

   The engine in nido.review.loop already runs a stage pipeline until terminal,
   and it names no stage — the pipeline is injected. So a round of a different
   kind is a different pipeline, not a different engine. What differs here is
   what is judged and against what:

     baseline round — VERIFICATION. Is this survey true, and complete enough to
       decide against? Near-mechanical: every property and health observation
       carries file:line evidence, so checking one is 'go read it'.

     design round — DECISION. Given that baseline, the goals of the task and the
       work-distribution guidelines, should we execute on this? The only point in
       the lifecycle where 'don't build this' is cheap, and the only round that
       ends at a human.

   Three properties hold of both, and each is enforced rather than requested:

   1. NO WRITES. Both run through `codex exec -s read-only`, the same sandbox the
      diff review uses. A pre-implementation round producing edits would make it
      a fix loop, which is the thing the design round must not be.

   2. EVERY FINDING CITES. The schema requires :cites non-empty. A round with no
      diff to be wrong about will otherwise produce fluent, unfalsifiable
      findings forever.

   3. NO EVIDENCE, NO ROUND. `worth-running?` refuses where the records say a
      round would not pay — the same rule verdict-worth-running? already applies,
      for the same reason: paying an agent to conclude nothing makes the answer
      noise rather than signal.

   The design round is deliberately SINGLE-PASS. It emits a decision, not
   findings to iterate on, so it never reaches the engine's no-progress check —
   which identifies a finding by [:file :line-start :title] and could not judge
   one that carries neither."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as jio]
   [clojure.string :as str]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]
   [nido.review.codex :as codex]
   [nido.review.stages :as stages]))

;; ── Whether a round is worth running ────────────────────────────────────────

(defn baseline-round-worth-running?
  "A survey is worth verifying when there is something checkable in it. That is
   any load-bearing property or health observation — every one carries evidence,
   so every one is a claim the code can refute.

   A baseline with neither is not a survey that passed; it is one that recorded
   nothing to check, and a round over it could only produce prose."
  [baseline]
  (boolean (and baseline
                (or (seq (:load-bearing baseline))
                    (seq (:health baseline))))))

(defn design-round-worth-running?
  "The decision round costs a human's attention at the end of it, so it runs
   where the records themselves say it would pay.

   The trigger is read from what the design already declares, rather than from a
   new heuristic: a change declaring :within on the baseline AND :conforms on the
   stance is claiming it moves nothing structural — cheap to spot-check, not
   worth a decision round. :extends, :revisit, :challenges, a large effort, or
   any health observation routed anywhere other than :fix-here are each a reason
   the answer is not obvious.

   Deliberately readable off the record: a reader can see from the design itself
   why a round did or did not run."
  [design]
  (boolean
   (and design
        (or (not= :within   (get-in design [:baseline :relation]))
            (not= :conforms (get-in design [:standing :relation]))
            (contains? #{:L :XL} (:effort design))
            (some #(not= :fix-here (:to %)) (:routes design))))))

(defn discover-intent
  "The intent the design CITED, projected to what a goal may contain. nil when
   the design cites none — a pre-intent record — which the prompt states rather
   than papering over.

   A cited :triage entry projects its title and summary only. Its :directions
   carry a proposed shape and an effort, and feeding those into the goal
   yardstick would put the answer inside the question: goal-served exists to
   catch a design that over-serves or that a smaller design would satisfy, and it
   could never fail honestly against a goal that already names the solution.

   Never throws. The prompt is built as an argument to run-round!, so anything
   that throws here escapes the round's only catch and takes the task down
   instead of degrading."
  [cwd design]
  (try
    (when-let [n (get-in design [:intent :seq])]
      (when-let [[project ws-id] (stages/project+ws-from-cwd cwd)]
        (let [e (ws/entry-at-seq project ws-id n)]
          (case (:format e)
            :intent        {:goal (:goal e) :done-when (:done-when e)}
            :triage-report {:goal (:title e) :done-when [] :summary (:summary e)}
            nil))))
    (catch Throwable _ nil)))

;; ── Prompt construction ─────────────────────────────────────────────────────

(defn- bullets [xs] (str/join "\n" (map #(str "- " %) xs)))

(defn- evidenced
  "Render a list of {:property/:observation :evidence} as claim + where to look.
   The evidence refs ARE the check — a verifier's job here is to go read them."
  [items label-key]
  (bullets (map #(str (get % label-key)
                      " [" (str/join ", " (:evidence %)) "]")
                items)))

(defn- invariant-lines
  "Invariants, each with the moment it holds. A record written before phasing
   carries plain strings and means :always by them; a phased one carries the
   Invariant map.

   The distinction has to reach the round, because an :on-completion invariant is
   deliberately false for the whole middle of a plan. A judge shown it without the
   qualifier reads a designed intermediate state as a broken design and escalates
   a decision that was already made — which is the reason :holds exists at all."
  [invariants]
  (bullets (map (fn [i]
                  (if (string? i)
                    i
                    (str (:invariant i)
                         (case (:holds i)
                           :on-completion "  [holds ON COMPLETION — not yet true mid-plan, and NOT a finding]"
                           "  [holds always]"))))
                invariants)))

(defn baseline-prompt
  "The verification prompt. Everything it needs to refute a claim is a file
   reference the record itself supplied."
  [{:keys [baseline]}]
  (str
   "You are checking whether a SURVEY of an area is true, and whether it is\n"
   "complete enough to decide against. You are NOT designing anything, and you\n"
   "are not judging whether the area is good — only whether this description of\n"
   "it is accurate.\n\n"
   "Read the code. Every claim below carries the file references it was read\n"
   "from; going to read them is the whole job.\n\n"
   "AREA: " (:area baseline) "\n"
   "BOUNDED BY: " (:bounded-by baseline) "\n"
   "SHAPE: " (:shape baseline) "\n\n"
   "LOAD-BEARING — claimed to be what would break if violated:\n"
   (evidenced (:load-bearing baseline) :property) "\n"
   (when-let [h (seq (:health baseline))]
     (str "\nHEALTH — claimed about whether what holds is sound. :design means a\n"
          "weak design cleanly executed; :implementation means a strong design\n"
          "shakily executed. A mis-axed observation is a finding:\n"
          (bullets (map #(str "[" (name (:axis %)) "] " (:observation %)
                              " [" (str/join ", " (:evidence %)) "]")
                        h))
          "\n"))
   (when-let [u (seq (:unknowns baseline))]
     (str "\nDECLARED NOT DETERMINED — already honest, not findings:\n" (bullets u) "\n"))
   "\nTwo distinct failures, and they have different remedies:\n"
   "1. FALSIFIED — a stated claim is not true of the code. Cite the claim and\n"
   "   the file:line that contradicts it.\n"
   "2. UNDERSCOPED — the bound excludes something that GOVERNS this behaviour.\n"
   "   This is the one that hides design flaws, because the flaw is routinely\n"
   "   upstream of the code a change would touch. Scoping to what a diff would\n"
   "   touch is the failure; scoping to what governs the behaviour is correct.\n\n"
   "Every finding MUST cite what it falsifies — the exact property or\n"
   "observation text. A finding that cites nothing is not a finding; do not\n"
   "report it.\n\n"
   "ACCURATE IS THE EXPECTED OUTCOME. Populate confirmed with the claims you\n"
   "actually went and checked, and return accurate when they held. Do not\n"
   "manufacture findings to look thorough."))

(defn design-prompt
  "The decision prompt. Derives what can be derived; hands the rest over."
  [{:keys [design baseline stance intent]}]
  (str
   "You are deciding whether a change should be EXECUTED, before any code is\n"
   "written. This is the last cheap moment to say it should not be.\n\n"
   "You do not make the decision. A human does. Your job is to derive\n"
   "everything derivable so that what reaches them is only the judgement that\n"
   "cannot be derived — is this worth doing, now, at this cost.\n\n"
   "Read the code where you need to.\n\n"
   (if intent
     (str "WHAT THE TASK IS FOR — stated before this was designed:\n"
          (:goal intent) "\n"
          (when-let [sm (:summary intent)] (str sm "\n"))
          (when-let [d (seq (:done-when intent))]
            (str "Done when:\n" (bullets d) "\n"))
          "\n")
     (str "NO STATED INTENT. This design cites none — it predates the intent\n"
          "record. You cannot derive goal-served against a goal nobody wrote\n"
          "down: report that check as UNDERIVABLE, say the record is missing,\n"
          "and do NOT infer the goal from the design. An inferred goal is the\n"
          "one the design serves, so the check could never fail.\n\n"))
   "THE DESIGN:\n"
   "Summary: " (:summary design) "\n"
   "Shape: " (:shape design) "\n"
   "Effort: " (name (:effort design)) "\n"
   "Invariants:\n" (invariant-lines (:invariants design)) "\n"
   "Declared against the stance: " (name (get-in design [:standing :relation])) "\n"
   ;; A design written before the baseline event existed carries no :baseline at
   ;; all, and it still qualifies for a round — its absent relation is not
   ;; :within. Saying so is the degrade this area takes everywhere else; calling
   ;; `name` on the nil would throw HERE, while building the prompt, which is
   ;; outside run-round!'s catch and so would take the whole task down.
   (if-let [r (get-in design [:baseline :relation])]
     (str "Declared against the baseline: " (name r) "\n")
     (str "Declared against the baseline: NOTHING. This design predates the\n"
          "baseline event, so it was judged against no survey. Weigh its claims\n"
          "on their own merits; the absence is not itself a finding.\n"))
   (when-let [r (seq (:rejected design))]
     (str "\nALREADY REJECTED. A finding re-proposing one of these is ANSWERED,\n"
          "not evidence — unless you can show the stated reason no longer holds:\n"
          (bullets (map #(str (:alternative %) " — because " (:why-not %)) r)) "\n"))
   (when-let [l (seq (:layers design))]
     (str "\nCLAIMED DECOMPOSITION, VERTICAL — one claim per layer, ordered by\n"
          "dependency; all of it lands in one go:\n"
          (bullets (map #(str (:claim %) " (" (name (:mode %)) ")") l)) "\n"))
   (when-let [ph (seq (:phases design))]
     (str "\nCLAIMED DECOMPOSITION, TEMPORAL — each of these is a separate landing\n"
          "the running system has to live in, so judge them as states rather than\n"
          "as steps:\n"
          (bullets (map #(str (:claim %)
                              " — habitable: " (:habitable %)
                              " — exit: " (get-in % [:exit :criterion])) ph))
          "\n"))
   (when-let [rt (seq (:routes design))]
     (str "\nHEALTH OBSERVATIONS ROUTED:\n"
          (bullets (map #(str (:health-id %) " → " (name (:to %))
                              (when (:why %) (str " — " (:why %)))) rt)) "\n"))
   (when baseline
     (str "\nTHE AREA AS SURVEYED, before this was designed:\n"
          "Bounded by: " (:bounded-by baseline) "\n"
          "Load-bearing:\n" (evidenced (:load-bearing baseline) :property) "\n"))
   (when stance (str "\nPROJECT STANCE — framing only:\n" stance "\n"))
   "\nDERIVE THESE FOUR. Each is decidable; none is a matter of taste:\n\n"
   "  relation-honest   — does it stand where it says it stands? A design\n"
   "                      declaring :within whose own shape needs a load-bearing\n"
   "                      property to move is not what it says it is.\n"
   "  goal-served       — does it serve the goal, and ONLY the goal? Under-\n"
   "                      serving is easy to see. OVER-serving is the common\n"
   "                      one: the goal plus a good deal more, every piece\n"
   "                      individually defensible. Check whether a strictly\n"
   "                      smaller design would do, including one already\n"
   "                      rejected for a reason that no longer holds.\n"
   "  decomposable      — can the cut be stated? Vertically: layers ordered by\n"
   "                      dependency, one claim each with no \"and\", one review\n"
   "                      mode each. Temporally, IF the design is phased: each\n"
   "                      phase a state the system can be left in, with an exit\n"
   "                      criterion that is an observation rather than a to-do.\n"
   "                      A design whose cut cannot be stated is not decomposed\n"
   "                      yet, and there is nothing to approve. A design with no\n"
   "                      phase plan is the ordinary case and is NOT a finding.\n"
   "  routing-coherent  — do the routed health observations keep this ONE story?\n"
   "                      Observations routed to fix-here that belong to a\n"
   "                      different story make this two changes.\n\n"
   "Then recommend:\n"
   "  proceed  — nothing derivable blocks it.\n"
   "  amend    — a derivable defect in the record itself.\n"
   "  recut    — the decomposition does not hold.\n"
   "  resurvey — the PREMISE is wrong, not the commitment. A design can be sound\n"
   "             on a survey that was not. Redesign and re-survey are different\n"
   "             instructions and saying the wrong one is worse than saying\n"
   "             nothing.\n\n"
   "Every finding MUST cite what it falsifies. A finding that cites nothing is\n"
   "not a finding.\n\n"
   "asks is REQUIRED whatever you recommend: state the question the human still\n"
   "has to answer, in one or two sentences, with everything you derived already\n"
   "taken off the table. Never answer it yourself."))

;; ── Running a round ─────────────────────────────────────────────────────────

(def ^:private schema-resources
  {:baseline-review "review/baseline_review_schema.json"
   :design-decision "review/design_decision_schema.json"})

(defn- normalize-findings
  [raw]
  (into [] (keep (fn [f]
                   (let [cites (into [] (remove str/blank?) (map str (:cites f)))]
                     (when (seq cites)
                       (cond-> {:cites cites :claim (str (:claim f))}
                         (seq (:evidence f))
                         (assoc :evidence (mapv str (:evidence f))))))))
        raw))

(defn parse-baseline-review
  "Codex JSON -> a :baseline-review ledger record, or nil when the answer is
   unusable. nil is a non-answer, never a fabricated :accurate — the caller
   records nothing rather than inventing trust."
  [json-str baseline-seq]
  (try
    (let [m (json/parse-string json-str true)
          v (keyword (str (:verdict m)))]
      (when (#{:accurate :falsified :underscoped} v)
        (let [findings (normalize-findings (:findings m))]
          ;; A non-accurate verdict with nothing that cites anything is exactly
          ;; the theatre this round guards against — read it as no answer.
          (when (or (= :accurate v) (seq findings))
            (cond-> {:format :baseline-review
                     :verdict v
                     :baseline-seq baseline-seq
                     :reason (str (:reason m))}
              (seq (:confirmed m)) (assoc :confirmed (mapv str (:confirmed m)))
              (not= :accurate v)   (assoc :findings findings))))))
    (catch Exception _ nil)))

(defn parse-design-decision
  "Codex JSON -> a :design-decision ledger record, or nil when unusable."
  [json-str design-seq]
  (try
    (let [m (json/parse-string json-str true)
          r (keyword (str (:recommend m)))
          checks (into [] (keep (fn [c]
                                  (let [k (keyword (str/replace (str (:check c)) "_" "-"))]
                                    (when (#{:relation-honest :goal-served
                                             :decomposable :routing-coherent} k)
                                      {:check  k
                                       :status (let [st (keyword (str (:status c)))]
                                                 (if (#{:held :broken :underivable} st)
                                                   st
                                                   ;; a judge that answered in the
                                                   ;; old shape is still answering
                                                   (if (:held c) :held :broken)))
                                       :note   (str (:note c))}))))
                       (:checks m))
          findings (normalize-findings (:findings m))
          asks     (str (:asks m))]
      (when (and (#{:proceed :amend :recut :resurvey} r)
                 (seq checks)
                 (not (str/blank? asks))
                 (or (= :proceed r) (seq findings)))
        (cond-> {:format :design-decision
                 :recommend r
                 :design-seq design-seq
                 :reason (str (:reason m))
                 :checks checks
                 :asks asks}
          (not= :proceed r) (assoc :findings findings))))
    (catch Exception _ nil)))

(defn- run-round!
  "One read-only codex pass over a record. Returns {:ok <json-string>} or
   {:outcome <kw> :detail <str>} — never nil, and never throws.

   The outcome is tagged rather than collapsed because a judgment surface cannot
   afford one confusion above all others: a round that never ran must not read
   like a round that ran and found nothing to say. Silence from a judge is
   evidence; silence from a missing binary is not, and a reader who cannot tell
   them apart draws the wrong conclusion from the same blank line."
  [{:keys [cwd run-id kind prompt]}]
  (try
    (let [dir (cstate/run-dir run-id)
          _   (fs/create-dirs dir)
          n   (name kind)
          schema-path (str (fs/path dir (str n "-schema.json")))
          out-path    (str (fs/path dir (str n "-out.json")))
          log-path    (str (fs/path dir (str n ".log")))]
      (spit schema-path (slurp (jio/resource (schema-resources kind))))
      (let [{:keys [exit]} (codex/run-codex! {:cwd cwd :schema-path schema-path
                                              :out-path out-path :log-path log-path
                                              :prompt prompt})]
        (cond
          (not (zero? exit))          {:outcome :codex-failed
                                       :detail (str "codex exited " exit " — see " log-path)}
          (not (fs/exists? out-path)) {:outcome :no-output
                                       :detail (str "codex wrote no answer to " out-path)}
          :else                       {:ok (slurp out-path)})))
    (catch Throwable t
      {:outcome :round-crashed :detail (or (ex-message t) (str (class t)))})))

(defn- judged
  "Apply `parse` to a round result, keeping the outcome tagged the whole way.
   An answer that will not parse is its own outcome — the judge spoke and was
   unusable, which is a different fact from the judge never speaking."
  [result parse]
  (if-let [json (:ok result)]
    (or (parse json)
        {:outcome :unusable-answer
         :detail "the answer did not satisfy what a round must return"})
    result))

(defn baseline-review!
  "Verify this workstream's latest baseline against the code. Returns the ledger
   record, or {:outcome <kw> :detail <str>} saying why there is none — never a
   bare nil, so a caller can always tell a skipped round from a failed one."
  [{:keys [cwd run-id]}]
  (if-let [[project ws-id] (stages/project+ws-from-cwd cwd)]
    (if-let [baseline (ws/latest-entry project ws-id :baseline)]
      (if (baseline-round-worth-running? baseline)
        (judged (run-round! {:cwd cwd :run-id run-id :kind :baseline-review
                             :prompt (baseline-prompt {:baseline baseline})})
                #(parse-baseline-review % (:seq baseline)))
        {:outcome :nothing-to-check
         :detail "the baseline records no load-bearing property and no health observation"})
      {:outcome :no-record :detail "this workstream has no :baseline entry"})
    {:outcome :no-workstream :detail (str "cwd resolves to no nido session: " cwd)}))

(defn design-decision!
  "Run the decision round over this workstream's latest design record. Returns
   the ledger record, or {:outcome <kw> :detail <str>} saying why there is none.

   Single-pass on purpose: it emits a decision, not findings to iterate on."
  [{:keys [cwd run-id]}]
  (if-let [[project ws-id] (stages/project+ws-from-cwd cwd)]
    (if-let [design (ws/latest-entry project ws-id :design)]
      (if (design-round-worth-running? design)
        (judged (run-round!
                 {:cwd cwd :run-id run-id :kind :design-decision
                  :prompt (design-prompt
                           {:design   design
                            :baseline (stages/discover-baseline cwd design)
                            :stance   (stages/read-stance project)
                            :intent   (discover-intent cwd design)})})
                #(parse-design-decision % (:seq design)))
        {:outcome :not-worth-running
         :detail "the design declares :within on its baseline and :conforms on the stance at a modest effort, with nothing routed away from :fix-here"})
      {:outcome :no-record :detail "this workstream has no :design entry"})
    {:outcome :no-workstream :detail (str "cwd resolves to no nido session: " cwd)}))

(defn append!
  "Append a round's record to the workstream ledger. Best-effort, for the same
   reason the review path's appends are: a round that produced an answer must not
   turn into a failure because the side record could not be written."
  [cwd record]
  (try
    (when (:format record)
      (when-let [[project ws-id] (stages/project+ws-from-cwd cwd)]
        (ws/append-entry! project ws-id {:kind (:format record)} (pr-str record))))
    (catch Exception _ nil)))
