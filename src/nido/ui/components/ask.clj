(ns nido.ui.components.ask
  "One Ask, offered for answer: the question card, its stepper, its options, and the action rows
   every gate's controls go through.

   HIDES how a decision is offered — one question at a time or all at once, which control is
   primary, where a consequence sits relative to the button that carries it, and the key the
   card's step and choices are held under in page state.

   Reads nothing. The pane supplies how a click is routed, as a function of project, workstream
   and action id, so the card names no page's URL scheme — which is why the gate inbox could be
   dissolved into the workstream pane without the card changing, and why a scene can host it
   with a router that goes nowhere."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]))

;; ---------------------------------------------------------------------------
;; An action, as the button that gives it

(defn- style-class [style]
  (case style :primary "btn btn-primary" :danger "btn btn-danger" "btn"))

(defn- action-button
  "One gate button. A descriptor carrying `:seq` (blocker-option buttons — see
   work/option-actions) posts it as ?entry=, the same reading position every other
   surface rides: the letter it sends means nothing except relative to the report
   that drew it, so the resolver needs to know which report that was."
  [project ws-id {:keys [id label style title] entry-seq :seq} route]
  [:button (cond-> {:class (style-class style)
                    "data-on:click" (str "@post('"
                                         (cond-> (route project ws-id id)
                                           entry-seq (str "?entry=" entry-seq))
                                         "')")}
             ;; Only the lettered option buttons carry one — their label is a
             ;; single character, so what they answer lives in the tooltip.
             title (assoc :title title))
   label])

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :any :any :any] :any]}
  action-bar
  "Render an action set: one-click buttons (mutations + preset-input resumes) in a row,
   plus a free-text reply textarea when a resume action without :input is present.
   `session` labels the resume target, or is nil. `route` builds each button's POST url from
   (project ws-id action-id). The single renderer behind every gate."
  ([project ws-id actions session route]
   (let [buttons   (filter #(or (#{:mutation :link} (:kind %)) (:input %)) actions)
         free-text (some #(when (and (= :resume (:kind %)) (not (:input %))) %) actions)
         ;; "Answer" is the free text a gate offers when nobody is parked: it is recorded on the
         ;; ledger as the blocker's answer and resumes nobody, so the copy must not promise a resume.
         answer?   (= "Answer" (:label free-text))
         evidence  (some #(when (= :evidence (:kind %)) %) actions)]
     (list
      (when evidence
        [:div.reply
         [:div.meta {:style "text-transform:uppercase;font-size:var(--fs-xs)"} "Assert the gate"]
         (when-let [c (:criterion evidence)] [:p [:span.meta "Criterion: "] c])
         [:textarea {"data-bind" "evidence"
                     :placeholder "What you observed on the running system, and where…"}]
         [:div {:style "margin-top:9px"}
          [:button.btn.btn-primary
           {"data-on:click" (str "@post('" (route project ws-id (:id evidence)) "')")}
           (str (:label evidence) " ▸")]]])
      (when (seq buttons)
        (into [:div.actions {:style "margin-top:16px"}]
              (for [a buttons]
                (if (= :link (:kind a))
                  [:a {:class (style-class (:style a)) :href (:href a) :title (:title a)} (:label a)]
                  (action-button project ws-id a route)))))
      (when free-text
        [:div.reply
         [:div.meta {:style "text-transform:uppercase;font-size:var(--fs-xs)"}
          (if answer? "Answer the blocker" "Reply & resume")]
         [:textarea {"data-bind" "reply"
                     :placeholder (if answer?
                                    "Your answer, in your words. It is recorded on the ledger for whoever picks this up next…"
                                    "Tell the agent what to do next…")}]
         [:div {:style "margin-top:9px"}
          [:button.btn.btn-primary
           {"data-on:click" (str "@post('" (route project ws-id :reply) "')")}
           (if answer? "Record answer ▸" "Send & resume ▸")]
          (when (and session (not answer?))
            [:span.meta {:style "margin-left:10px"} "resumes " session])]])))))

;; ---------------------------------------------------------------------------
;; The card's page state
;;
;; A card's step and choices live in page state under one key per workstream, so two cards on
;; one page never share them and a pane poll re-renders the card without resetting the reader's
;; step. The server reads a submit's choices back from the same key — by the `answers`/`q<i>`
;; layout written here, which it repeats as literals of its own: the design's
;; posted-names-shared-by-literal, which the split lengthens rather than closes.

(defn ^{:malli/schema [:=> [:cat :WorkstreamId] :string]}
  card-signal
  "The key under `qcard` that holds workstream `ws-id`'s card state ({step, answers}): the id with
   every character a signal name cannot carry replaced by `_`. Keyed per workstream so the state is
   seeded only when missing — a pane poll re-renders the card without resetting the reader's step
   or choices — and a card opened for another workstream starts fresh instead of inheriting them."
  [ws-id]
  (str/replace (str ws-id) #"[^A-Za-z0-9]" "_"))

(defn- answer-signal
  "The page signal holding question `i`'s choice on `ws-id`'s card: a letter, or '' while open."
  [ws-id i]
  (str "$qcard." (card-signal ws-id) ".answers.q" i))

(defn- step-signal [ws-id] (str "$qcard." (card-signal ws-id) ".step"))

;; ---------------------------------------------------------------------------
;; The card

(defn- does-of
  "What giving action `id` does, as the Ask words it."
  [ask id]
  (some #(when (= id (:action %)) (:does %)) (:answer-does ask)))

(defn- post-expr
  "The click expression posting action descriptor `d` through `route`, with the reading position
   it was rendered at when it carries one."
  [route project ws-id {:keys [id] entry-seq :seq}]
  (str "@post('" (cond-> (route project ws-id id) entry-seq (str "?entry=" entry-seq)) "')"))

(defn- option-choice
  "One option of question `i`, as the button that answers it: its label in bold, then its summary,
   then its consequence, and never its letter. An option tied to an offered action posts that
   action; one that is not is a choice — clicking marks it chosen in the page, clicking it again
   leaves the question open — that the Ask's submit sends. A pending card renders it with no
   button at all."
  [{:keys [project ws-id route by-id pending? primary-option?]} i
   {:keys [letter label summary consequence recommended? action] :as opt}]
  (let [chosen (str (answer-signal ws-id i) " === '" letter "'")
        body   (list [:span.qc-opt-label [:strong label]
                      (when recommended? [:span.chip.c-opt-rec "recommended"])
                      (when-not action [:span.qc-chosen {"data-show" chosen} "✓ chosen"])]
                     (when-not (str/blank? summary) [:span.qc-opt-sum summary])
                     (when consequence [:span.qc-opt-cons consequence]))
        d      (when action (by-id action))]
    (cond
      (or pending? (and action (nil? d))) [:div.qc-opt.static body]
      d   [:button {:type "button"
                    :class (str "qc-opt" (when (primary-option? opt) " primary"))
                    "data-on:click" (post-expr route project ws-id d)}
           body]
      :else [:button {:type "button" :class "qc-opt"
                      "data-class:chosen" chosen
                      "data-attr:aria-pressed" chosen
                      "data-on:click" (str (answer-signal ws-id i) " = (" chosen ") ? '' : '" letter "'")}
             body])))

(defn- question-section
  "Question `i` of `n`: its line, then its context, recommendation and what follows the answer as
   body text, then its options. Only the shown one is visible when there are several."
  [ctx i n {:keys [question context recommendation after options]}]
  [:section (cond-> {:class "qc-question"} (> n 1) (assoc "data-show" (str (step-signal (:ws-id ctx)) " === " i)))
   [:p.qc-q question]
   (when (seq context) (into [:div.qc-ctx] (map (fn [c] [:p c])) context))
   (when recommendation [:p.qc-rec [:span.qc-k "Recommended"] recommendation])
   (when after [:p.qc-after [:span.qc-k "Once answered"] after])
   (when (seq options)
     (into [:div.qc-opts] (map #(option-choice ctx i %)) options))])

(defn- stepper-nav
  "Where the reader is among `n` questions: which is shown, one marker per question saying whether
   it is answered (● answered, ○ open — the glyph says it, not only the colour), and a jump to each."
  [ws-id n]
  (into [:div.qc-nav
         [:span "Question " [:span {"data-text" (str (step-signal ws-id) " + 1")} "1"] " of " n]]
        (for [i (range n)]
          [:button {:type "button" :class "qc-mark"
                    :aria-label (str "Question " (inc i))
                    "data-class:answered" (str (answer-signal ws-id i) " !== ''")
                    "data-class:current" (str (step-signal ws-id) " === " i)
                    "data-text" (str (answer-signal ws-id i) " !== '' ? '●' : '○'")
                    "data-on:click" (str (step-signal ws-id) " = " i)}
           "○"])))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map :any :map] :any]}
  question-card
  "One Ask, answered: its questions one at a time, their choices, and every action the gate offers
   beside what it does. Reads nothing but its arguments, so any pane can drop it in: `ask` is a
   report.ask Ask, `actions` the gate's action descriptors, and `opts` {:route :pending?
   :error-msg :session} — `:route` (project ws-id action-id) -> the URL every POST it renders goes
   to, `:pending?` a gate whose agent is running, which renders
   its questions with no control for any action, `:error-msg` a failed action's message, set
   above the first question, and `:session` the session a Reply resumes.

   Several questions share one card with a stepper; choices made in it are page state
   (`qcard.<card-signal>.answers`) until the Ask's `:submit` sends them all, so a question may be left open. The
   submit, or with none the recommended option, is the one primary button."
  [project ws-id {:keys [question questions submit note] :as ask} actions
   {:keys [route pending? error-msg session]}]
  (let [by-id     (into {} (map (juxt :id identity)) actions)
        n         (count questions)
        options   (mapcat :options questions)
        opt-ids   (set (keep :action options))
        ;; With a submit, it is the one primary; without, the recommended option that answers by
        ;; itself is — and no other row may also be.
        primary-option? (fn [o] (and (nil? submit) (:recommended? o) (:action o)))
        primary-taken?  (or submit (some primary-option? options))
        opt-does  (distinct (keep #(does-of ask %) opt-ids))
        submit-d  (when submit (by-id submit))
        others    (remove #(or (opt-ids (:id %)) (= submit (:id %))
                               (not (or (= :mutation (:kind %)) (:input %))))
                          actions)
        boxes     (filter #(or (= :evidence (:kind %))
                               (and (= :resume (:kind %)) (not (:input %))))
                          actions)
        ctx       {:project project :ws-id ws-id :route route :by-id by-id
                   :pending? pending? :primary-option? primary-option?}
        row       (fn [d style]
                    [:div.ans-row
                     (action-button project ws-id (assoc d :style style) route)
                     [:div.ans-body [:p.ans-cons (does-of ask (:id d))]]])]
    [:div (cond-> {:class "qcard"}
            (or submit (> n 1))
            (assoc "data-signals__ifmissing"
                   (str "{qcard: {" (card-signal ws-id) ": {step: 0, answers: {"
                        (str/join ", " (map #(str "q" % ": ''") (range n)))
                        "}}}}")))
     (when (and error-msg (not pending?)) [:div.action-err "⚠ " error-msg])
     (when (and (> n 1) (not= question (:question (first questions))))
       [:p.qc-head question])
     (when (> n 1) (stepper-nav ws-id n))
     (map-indexed #(question-section ctx %1 n %2) questions)
     (when (> n 1)
       [:div.qc-step
        [:button {:type "button" :class "btn"
                  "data-attr:disabled" (str (step-signal ws-id) " === 0")
                  "data-on:click" (str (step-signal ws-id) " = Math.max(0, " (step-signal ws-id) " - 1)")} "◂ Previous"]
        [:button {:type "button" :class "btn"
                  "data-attr:disabled" (str (step-signal ws-id) " === " (dec n))
                  "data-on:click" (str (step-signal ws-id) " = Math.min(" (dec n) ", " (step-signal ws-id) " + 1)")} "Next ▸"]])
     (when (seq opt-does) (into [:div] (map (fn [d] [:p.ans-does d])) opt-does))
     (when note [:p.ask-note note])
     (if pending?
       [:div.controls [:div.ans-row [:span.meta "working… the agent is running."]]]
       [:div.controls
        (when submit-d (row submit-d :primary))
        (for [a others]
          (row a (if (and primary-taken? (= :primary (:style a))) :default (:style a))))
        (when (seq boxes)
          (list (into [:div] (keep (fn [a] (when-let [d (does-of ask (:id a))] [:p.ans-does d]))) boxes)
                ;; The reply's send button is drawn primary for a gate that has nothing else to
                ;; press; beside a card's own primary it would be a second one on one decision.
                (cond->> (action-bar project ws-id boxes session route)
                  primary-taken? (walk/postwalk #(if (= :button.btn.btn-primary %) :button.btn %)))))])]))
