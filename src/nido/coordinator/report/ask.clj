(ns nido.coordinator.report.ask
  "What asking a person a question is: one Ask shape every needs-you card is built in, and one
   registry entry per kind of question saying how its record yields that Ask.

   HIDES where each kind's question lives in its record. A blocker asks in `:needs`, a design
   decision in `:asks`, a design verdict in `:needs`, a phase gate in its exit criterion, and a
   triage verdict or a published PR in no field at all; the rest of nido reads an Ask and never
   which field a kind wrote. Adding a kind, or changing what one asks, is its entry here and its
   guidance addendum in `resources/asking/` — the generic flow, the work plane and the views name
   no kind.

   Pure: the caller hands `ask` the record the owed move's question lives in, the workstream's live
   intent, the actions the gate offers and any conductor note. It reads nothing but the guidance
   files, and `guidance` is the only function that does."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [malli.core :as m]
   [nido.coordinator.report :as report]
   [nido.platform.core :as core]))

;; ── the shape ───────────────────────────────────────────────────────────────

(def AskOption
  "One answer on offer. `:action` is the id of the gate action that gives it, so the card's
   branches and its buttons cannot disagree."
  [:map {:closed true}
   [:letter       :string]
   [:label        :string]
   [:summary      :string]
   [:consequence  {:optional true} :string]
   [:recommended? {:optional true} :boolean]
   [:action       :keyword]])

(def AnswerDoes
  "What giving one of the gate's actions does: what it records, and whom it resumes."
  [:map {:closed true}
   [:action :keyword]
   [:label  :string]
   [:does   :string]])

(def Ask
  "One question for a person. `:question` leads the card; `:context` is what the question
   presupposes, in reading order; `:options` are its branches, each tied to an action; `:answer-does`
   has one entry per action the gate offers; `:goal` is the workstream's live intent, done-whens in
   the intent's order; `:note` is a conductor's escalation riding on the owed move."
  [:map {:closed true}
   [:kind        :keyword]
   [:question    [:and :string [:fn {:error/message "a question is never blank"} (complement str/blank?)]]]
   [:context     [:vector :string]]
   [:options     [:vector AskOption]]
   [:answer-does [:vector AnswerDoes]]
   [:goal        {:optional true} [:map {:closed true}
                                   [:goal :string]
                                   [:done-when [:vector :string]]]]
   [:note        {:optional true} :string]])

;; ── what an action does ─────────────────────────────────────────────────────

(defn- lettered
  "The letter an `<prefix>-<letter>` action id stands for, or nil."
  [prefix id]
  (some #(when (= id (keyword (str prefix "-" (str/lower-case %)))) %) report/option-letters))

(defn- does
  "What giving action `a` does, in the person's terms. Read off the action's id, kind and `:answers`
   — the resolver the gate says it reaches where one id has several — never off the kind of
   question: the same button does the same thing on every card."
  [{:keys [id kind answers]}]
  (or
   (when-let [l (lettered "option" id)]
     (str "Records " l " as your answer. A session parked on the question resumes with it; "
          "otherwise it waits on the ledger for whoever takes the workstream up next."))
   (when-let [l (lettered "direction" id)]
     (str "Accepts the triage verdict with direction " l " as the fix to build."))
   (case id
     :approve      "Approves the design; the workstream goes on to implement it."
     :redesign     "Sends the design back to be redesigned against the review's finding."
     :hold-design  "Accepts the design as it stands despite the finding; the work goes on."
     :assert-gate  "Records what you observed as the gate's evidence and opens the next phase."
     :apply        (if (= :proposal answers)
                     "Accepts the proposal and files it as a ticket."
                     "Accepts the triage verdict and writes it to the ticket, leaving the fix's direction open.")
     :dismiss      "Dismisses the workstream from the board; Restore brings it back."
     :done         "Marks the workstream done and stops its sessions."
     :drop         "Drops the workstream off the queue."
     :promote      "Starts implementing the workstream in a session of its own."
     :restore      "Puts the workstream back on the triage queue."
     :start-triage "Starts the triage that never ran."
     ;; `:answers` names the resolver the words reach when it is not a parked agent's resume.
     :reply        (case answers
                     :ledger    (str "Records your words on the ledger as the blocker's answer. Nobody "
                                     "is resumed: whoever takes the workstream up next reads it there.")
                     :conductor (str "Records your words as the answer to the conductor's escalation; "
                                     "the conductor reads it on its next pass.")
                     (if (= :resume kind)
                       "Sends your words to the parked session, which resumes with them."
                       "Records your words as the answer."))
     nil)
   "Acts on the workstream as its label says."))

(defn- answer-does
  [actions]
  (mapv (fn [{:keys [id label] :as a}]
          {:action id :label (str label) :does (does a)})
        actions))

(defn- branches
  "Lettered branches of a record, as options tied to the gate's `<prefix>-<letter>` actions. A
   branch the gate offers no action for is left out: an option nobody can give is not on offer."
  [prefix actions items ->option]
  (let [offered (into #{} (map :id) actions)]
    (vec (keep-indexed
          (fn [i item]
            (let [l (report/option-letter i)
                  id (when l (keyword (str prefix "-" (str/lower-case l))))]
              (when (and id (offered id))
                (merge {:letter l :action id} (->option item)))))
          items))))

;; ── the kinds ───────────────────────────────────────────────────────────────

(def ^:private escalation-summary
  "The fixed headline escalation blockers carried before an escalation said what it was about. It
   says how a question arrived, not what it is, so it is never context."
  "the conductor escalated a hand-back")

(defn- non-blank [& xs] (vec (remove #(or (nil? %) (str/blank? (str %))) xs)))

(defn- blocker-option [{:keys [label summary consequence recommended?]}]
  (cond-> {:label label :summary (str summary)}
    consequence  (assoc :consequence consequence)
    recommended? (assoc :recommended? true)))

(def ^:private registry
  "One entry per move a person owes. `:record` picks the record the move's question lives in, from
   the gate's report, the record of each format the work plane handed (`newest` of a format) and
   the gate's actions; `:question`, `:context` and `:options` read it (with the actions, for
   options); `:guidance` names the addendum in resources/asking/ its authors are briefed with.
   `:generic` answers for anything unregistered."
  {:answer-blocker
   {:guidance "answer-blocker"
    ;; The gate's report IS the owed blocker: the work plane hands the buried one when later
    ;; records covered it, because which blocker is owed is the position's to say.
    :record   (fn [report _ _] report)
    :question (fn [{:keys [needs]}] needs)
    :context  (fn [{:keys [summary]}]
                (non-blank (when-not (= escalation-summary summary) summary)))
    :options  (fn [{:keys [options]} actions] (branches "option" actions options blocker-option))}

   :approve-design
   {:guidance "approve-design"
    :record   (fn [_ newest _] (newest :design-decision))
    :question (fn [{:keys [asks]}] asks)
    :context  (fn [{:keys [reason recommend]}]
                (non-blank (when recommend (str "The design round recommends: " (name recommend) "."))
                           reason))}

   :acknowledge-invalidation
   {:guidance "acknowledge-invalidation"
    :record   (fn [_ newest _] (newest :design-verdict))
    :question (fn [{:keys [needs]}]
                (or (not-empty needs)
                    "The review found the design does not hold. Redesign it, or accept it as it stands?"))
    :context  (fn [{:keys [reason]}] (non-blank reason))}

   :assert-gate
   {:record   (fn [_ _ actions] {:criterion (some :criterion actions)})
    :question (fn [{:keys [criterion]}]
                (str "Have you observed this on the running system: " (or criterion "the phase's exit criterion") "?"))
    :context  (fn [{:keys [claim]}]
                (non-blank (when claim (str "This phase claims: " claim))))}

   :accept-triage
   ;; Owed over a Notion triage or a Slack proposal alike (`standing/verdict-kinds`): the record is
   ;; whichever of the two the work plane handed, the newer when it handed both.
   {:record   (fn [_ newest _]
                (last (sort-by #(or (:seq %) 0)
                               (keep newest [:triage-report :proposed-ticket]))))
    :question (fn [{:keys [format title directions]}]
                (if (= :proposed-ticket format)
                  (str "File “" title "” as a ticket?")
                  (str "Accept the triage of “" title "”"
                       (if (seq directions) ", and which fix should be built?" "?"))))
    :context  (fn [{:keys [format summary determination ticket-type priority doubt] :as r}]
                (if (= :proposed-ticket format)
                  (into (non-blank (str "Proposed " (or ticket-type "ticket")
                                        (when priority (str ", priority " priority)) ".")
                                   doubt)
                        (keep (fn [{:keys [heading text]}] (when-not (str/blank? text) (str heading ": " text))))
                        (concat (report/product-sections r) (report/analysis-sections r)))
                  (non-blank (when determination (str "Determination: " (name determination) "."))
                             summary)))
    :options  (fn [{:keys [directions]} actions]
                (branches "direction" actions directions
                          (fn [{:keys [label shape effort]}]
                            (cond-> {:label (or label "") :summary (str shape)}
                              effort (assoc :consequence (str "Effort: " (report/effort-label effort)))))))}

   :land
   {:record   (fn [_ newest _] (newest :pr-opened))
    :question (fn [{:keys [title]}] (str "Land “" (or title "this pull request") "”?"))
    :context  (fn [{:keys [url summary]}] (non-blank url summary))}

   :generic
   {:record   (fn [report _ _] report)
    :question (fn [{:keys [title summary needs]}]
                (or (not-empty needs) (not-empty title) (not-empty summary)
                    "This workstream is waiting on you."))
    :context  (fn [_] [])}})

(defn ^{:malli/schema [:=> [:cat] [:vector :keyword]]}
  kinds
  "The kinds of question registered, :generic among them."
  []
  (vec (sort (keys registry))))

;; ── asking ──────────────────────────────────────────────────────────────────

(defn- goal-of [{:keys [goal done-when]}]
  (when (and (string? goal) (not (str/blank? goal)))
    {:goal goal :done-when (vec (filter string? done-when))}))

(def ^:private Inputs
  [:map
   [:report  [:maybe :map]]
   [:entries [:vector :map]]
   [:actions [:vector [:map [:id :keyword]]]]
   [:intent  {:optional true} :map]
   [:note    {:optional true} :string]])

(defn- build
  "The Ask `entry` yields for kind `k`, or nil when what it yields is not one."
  [k entry {:keys [report entries actions intent note]}]
  (let [newest (fn [fmt] (last (filter #(= fmt (:format %)) entries)))
        record (or ((:record entry) report newest actions) {})
        q      (str/trim (str ((:question entry) record)))
        out    (cond-> {:kind        k
                        :question    q
                        :context     ((:context entry) record)
                        :options     (if-let [f (:options entry)] (f record actions) [])
                        :answer-does (answer-does actions)}
                 (goal-of intent)              (assoc :goal (goal-of intent))
                 (not (str/blank? (str note))) (assoc :note note))]
    (when (m/validate Ask out) out)))

(defn ^{:malli/schema [:=> [:cat :keyword [:map
                                           [:report [:maybe :map]]
                                           [:entries [:vector :map]]
                                           [:actions [:vector [:map [:id :keyword]]]]
                                           [:intent {:optional true} :map]
                                           [:note {:optional true} :string]]]
                       :Ask]}
  ask
  "The Ask for one gate. `kind` is the move a person owes; `inputs` is {:report <the gate's report —
   the owed blocker when one is owed> :entries <one record of each format a question can live in —
   for the format the owed move was placed on, that record, else the newest> :actions <the gate's
   action descriptors> :intent <the live intent> :note <a conductor's escalation on the owed move>}.

   The one place an Ask is made, and the one place it is checked: what a kind's entry yields that
   is not an Ask, like an unregistered kind, answers through the generic entry instead, so a kind
   whose record is malformed still asks something rather than costing the inbox its card."
  [kind inputs]
  (let [inputs (update inputs :entries #(vec (or % [])))]
    (when-not (m/validate Inputs inputs)
      (throw (ex-info "Not ask's inputs" {:kind kind :explain (m/explain Inputs inputs)})))
    (or (when-let [entry (get registry kind)] (build kind entry inputs))
        (build :generic (:generic registry) inputs)
        (throw (ex-info "No Ask could be built" {:kind kind})))))

;; ── guidance ────────────────────────────────────────────────────────────────

(defn- asking-file [n]
  (str (fs/path (core/nido-source-dir) "resources" "asking" (str n ".md"))))

(defn ^{:malli/schema [:=> [:cat :keyword] :string]}
  guidance
  "The guidance an author of a `kind` question is briefed with: the generic asking guidance, then
   the kind's addendum when it has one. `:escalation` is a kind here though no move is owed for it —
   it is the conductor's own way of asking."
  [kind]
  (let [addendum (or (get-in registry [kind :guidance]) (when (= :escalation kind) "escalation"))]
    (str/join "\n\n"
              (cond-> [(str/trim (slurp (asking-file "ask")))]
                (and addendum (fs/exists? (asking-file addendum)))
                (conj (str/trim (slurp (asking-file addendum))))))))
