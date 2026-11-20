(ns nido.ui.components.ask
  "One Ask, offered for answer: the question card, its stepper, its options, and the action rows
   every gate's controls go through — registered with the UI library as two blocks, `card` and
   `bar`.

   HIDES how a decision is offered — one question at a time or all at once, which control is
   primary, where a consequence sits relative to the button that carries it — and what a gate's
   click sends: each block declares its actions as data, and the server reads a posted body back
   only through the decoder the same declaration derives.

   Reads nothing. Both blocks render over props, the context and a router of `:gate` and
   `{:project :ws-id :action}` the page supplies, so the card names no page's URL scheme — which
   is why the gate inbox could be dissolved into the workstream pane without the card changing,
   and why a scene can host it with a router that goes nowhere. The instance id is the
   workstream's, so two cards on one page share no id and no signal."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [nido.libraries.ui.block :as block]
            [nido.ui.markdown :as md]))

;; ---------------------------------------------------------------------------
;; What a gate's click sends

(def ^:private gate-click
  "A gate button that resolves nido-side: nothing but its action id and, for a button that
   answers a ledger question, the reading position it was rendered at, as `?entry=`."
  [:post :gate {}])

(def ^:private choices
  [:map-of :keyword :string])

(def ^:private card-actions
  {:click  gate-click
   ;; A card's submit sends every question's choice — its letter, or its words on a card whose
   ;; submit records them. A card with no stepper and no submit seeds neither, so both are
   ;; optional: an Accept on such a card resolves on the position alone.
   :submit [:post :gate {:answers [:signal :answers choices {:optional true}]
                         :words   [:signal :words choices {:optional true}]}]})

(def ^:private bar-actions
  {:click    gate-click
   :reply    [:post :gate {:text [:signal :reply :string]}]
   :evidence [:post :gate {:text [:signal :evidence :string]}]})

(defn- click
  "The expression giving action descriptor `d` through block action `k`."
  [action project ws-id k {:keys [id] entry-seq :seq}]
  (action k {:params {:project project :ws-id ws-id :action id}
             :query  (when entry-seq {:entry entry-seq})}))

;; ---------------------------------------------------------------------------
;; The bar

(defn- style-class [style]
  (case style :primary "btn btn-primary" :danger "btn btn-danger" "btn"))

(defn- action-button
  "One gate button. A descriptor carrying `:seq` (blocker-option buttons — see
   work/option-actions) posts it as ?entry=, the same reading position every other surface
   rides: the letter it sends means nothing except relative to the report that drew it, so the
   resolver needs to know which report that was."
  [action project ws-id {:keys [label style title] :as d}]
  [:button (cond-> {:class (style-class style)
                    "data-on:click" (click action project ws-id :click d)}
             ;; Only the lettered option buttons carry one — their label is a
             ;; single character, so what they answer lives in the tooltip.
             title (assoc :title title))
   label])

(defn- render-bar
  "An action set: one-click buttons (mutations + preset-input resumes) in a row, plus a
   free-text reply when a resume action without :input is present and an evidence box when the
   gate is asserted. `:session` labels the resume target, or is nil."
  [{:keys [part action signal] {:keys [project ws-id actions session demote-primary?]} :props}]
  (let [buttons   (filter #(or (#{:mutation :link} (:kind %)) (:input %)) actions)
        free-text (some #(when (and (= :resume (:kind %)) (not (:input %))) %) actions)
        ;; "Answer" is the free text a gate offers when nobody is parked: it is recorded on the
        ;; ledger as the blocker's answer and resumes nobody, so the copy must not promise a resume.
        answer?   (= "Answer" (:label free-text))
        evidence  (some #(when (= :evidence (:kind %)) %) actions)
        bar       [:div (part :root)
                   (when evidence
                     [:div.reply
                      [:div.meta {:style "text-transform:uppercase;font-size:var(--fs-xs)"} "Assert the gate"]
                      (when-let [c (:criterion evidence)] [:p [:span.meta "Criterion: "] c])
                      [:textarea {"data-bind" (signal :evidence)
                                  :placeholder "What you observed on the running system, and where…"}]
                      [:div {:style "margin-top:var(--space-9)"}
                       [:button.btn.btn-primary
                        {"data-on:click" (click action project ws-id :evidence evidence)}
                        (str (:label evidence) " ▸")]]])
                   (when (seq buttons)
                     (into [:div.actions {:style "margin-top:var(--space-16)"}]
                           (for [a buttons]
                             (if (= :link (:kind a))
                               [:a {:class (style-class (:style a)) :href (:href a) :title (:title a)} (:label a)]
                               (action-button action project ws-id a)))))
                   (when free-text
                     [:div.reply
                      [:div.meta {:style "text-transform:uppercase;font-size:var(--fs-xs)"}
                       (if answer? "Answer the blocker" "Reply & resume")]
                      [:textarea {"data-bind" (signal :reply)
                                  :placeholder (if answer?
                                                 "Your answer, in your words. It is recorded on the ledger for whoever picks this up next…"
                                                 "Tell the agent what to do next…")}]
                      [:div {:style "margin-top:var(--space-9)"}
                       [:button.btn.btn-primary
                        {"data-on:click" (click action project ws-id :reply {:id :reply})}
                        (if answer? "Record answer ▸" "Send & resume ▸")]
                       (when (and session (not answer?))
                         [:span.meta {:style "margin-left:var(--space-10)"} "resumes " session])]])]]
    ;; The reply's send button is drawn primary for a gate that has nothing else to press;
    ;; beside a card's own primary it would be a second one on one decision.
    (cond->> bar
      demote-primary? (walk/postwalk #(if (= :button.btn.btn-primary %) :button.btn %)))))

;; ---------------------------------------------------------------------------
;; The card

(defn- does-of
  "What giving action `id` does, as the Ask words it."
  [ask id]
  (some #(when (= id (:action %)) (:does %)) (:answer-does ask)))

(defn- q-key [i] (keyword (str "q" i)))

(defn- answer-signal
  "The expression reading question `i`'s choice: a letter, or '' while open."
  [signal i]
  (str "$" (signal [:answers (q-key i)])))

(defn- step-signal [signal] (str "$" (signal :step)))

(defn- option-choice
  "One option of question `i`, as the button that answers it: its label in bold, then its summary,
   then its consequence, and never its letter. An option tied to an offered action posts that
   action; one that is not is a choice — clicking marks it chosen in the page, clicking it again
   leaves the question open — that the Ask's submit sends. A pending card renders it with no
   button at all."
  [{:keys [project ws-id by-id pending? primary-option? action signal]} i
   {:keys [letter label summary consequence recommended?] opt-action :action :as opt}]
  (let [chosen (str (answer-signal signal i) " === '" letter "'")
        body   (list [:span.qc-opt-label [:strong label]
                      (when recommended? [:span.chip.c-opt-rec "recommended"])
                      (when-not opt-action [:span.qc-chosen {"data-show" chosen} "✓ chosen"])]
                     ;; Rendered inside the option's <button>, where an <a> is invalid HTML.
                     (when-not (str/blank? summary) [:div.qc-opt-sum (md/render summary {:links? false})])
                     (when-not (str/blank? consequence) [:div.qc-opt-cons (md/render consequence {:links? false})]))
        d      (when opt-action (by-id opt-action))]
    (cond
      (or pending? (and opt-action (nil? d))) [:div.qc-opt.static body]
      d   [:button {:type "button"
                    :class (str "qc-opt" (when (primary-option? opt) " primary"))
                    "data-on:click" (click action project ws-id :click d)}
           body]
      :else [:button {:type "button" :class "qc-opt"
                      "data-class:chosen" chosen
                      "data-attr:aria-pressed" chosen
                      "data-on:click" (str (answer-signal signal i) " = (" chosen ") ? '' : '" letter "'")}
             body])))

(defn- question-section
  "Question `i` of `n`: its line, then its context, recommendation and what follows the answer as
   body text, then its options — or, for a question with none on a card whose submit records
   words, a field for the answer in words. Every piece of it is agent prose and renders as
   Markdown. Only the shown one is visible when there are several."
  [{:keys [signal] :as ctx} i n {:keys [question context recommendation after options]}]
  [:section (cond-> {:class "qc-question"} (> n 1) (assoc "data-show" (str (step-signal signal) " === " i)))
   [:div.qc-q (md/render question)]
   (when (seq context) (into [:div.qc-ctx] (map md/render) context))
   (when-not (str/blank? recommendation) [:div.qc-rec [:div.qc-k "Recommended"] (md/render recommendation)])
   (when-not (str/blank? after) [:div.qc-after [:div.qc-k "Once answered"] (md/render after)])
   (when (seq options)
     (into [:div.qc-opts] (map #(option-choice ctx i %)) options))
   (when (and (:words? ctx) (empty? options) (not (:pending? ctx)))
     [:textarea.qc-words {"data-bind" (signal [:words (q-key i)])
                          :placeholder "Your answer to this question, in your words…"}])])

(defn- answered-expr
  "Whether question `i` has an answer: a letter chosen, or — on a card that takes words — words
   written."
  [signal i words?]
  (str (answer-signal signal i) " !== ''"
       (when words? (str " || ($" (signal [:words (q-key i)]) " || '') !== ''"))))

(defn- stepper-nav
  "Where the reader is among `n` questions: which is shown, one marker per question saying whether
   it is answered (● answered, ○ open — the glyph says it, not only the colour), and a jump to each."
  [signal n words?]
  (into [:div.qc-nav
         [:span "Question " [:span {"data-text" (str (step-signal signal) " + 1")} "1"] " of " n]]
        (for [i (range n)]
          [:button {:type "button" :class "qc-mark"
                    :aria-label (str "Question " (inc i))
                    "data-class:answered" (answered-expr signal i words?)
                    "data-class:current" (str (step-signal signal) " === " i)
                    "data-text" (str "(" (answered-expr signal i words?) ") ? '●' : '○'")
                    "data-on:click" (str (step-signal signal) " = " i)}
           "○"])))

(defn- submit-of [{:keys [submit]} actions]
  (when submit (some #(when (= submit (:id %)) %) actions)))

(defn- card-signals
  "The card's page state, seeded only when missing so a pane poll re-renders it without
   resetting the reader's step or choices: the step, one blank answer per question, and one
   blank word field per question when the submit records words. Nothing for a card with one
   question and no submit — it has no state to keep."
  [{:keys [ask actions]}]
  (let [n      (count (:questions ask))
        words? (boolean (:words? (submit-of ask actions)))
        blanks (into {} (map (fn [i] [(q-key i) ""])) (range n))]
    (when (or (:submit ask) (> n 1))
      (cond-> {:step 0 :answers blanks}
        words? (assoc :words blanks)))))

(declare bar)

(defn- render-card
  [{:keys [part action signal ctx router]
    {:keys [project ws-id ask actions pending? error-msg session]} :props}]
  (let [{:keys [question questions submit note]} ask
        by-id     (into {} (map (juxt :id identity)) actions)
        n         (count questions)
        options   (mapcat :options questions)
        opt-ids   (set (keep :action options))
        ;; With a submit, it is the one primary; without, the recommended option that answers by
        ;; itself is — and no other row may also be.
        primary-option? (fn [o] (and (nil? submit) (:recommended? o) (:action o)))
        primary-taken?  (boolean (or submit (some primary-option? options)))
        opt-does  (distinct (keep #(does-of ask %) opt-ids))
        submit-d  (submit-of ask actions)
        others    (remove #(or (opt-ids (:id %)) (= submit (:id %))
                               (not (or (= :mutation (:kind %)) (:input %))))
                          actions)
        boxes     (filter #(or (= :evidence (:kind %))
                               (and (= :resume (:kind %)) (not (:input %))))
                          actions)
        ;; Only a submit whose resolver records words (`:words?` on its descriptor) gets a words
        ;; field: words typed on a card whose submit ignores them would be dropped unseen.
        words?    (boolean (:words? submit-d))
        qctx      {:project project :ws-id ws-id :by-id by-id :words? words? :action action
                   :signal signal :pending? pending? :primary-option? primary-option?}
        row       (fn [k d style]
                    [:div.ans-row
                     (action-button (fn [_ opts] (action k opts)) project ws-id (assoc d :style style))
                     [:div.ans-body [:p.ans-cons (does-of ask (:id d))]]])]
    [:div (merge (part :root) {:class "qcard"})
     (when (and error-msg (not pending?)) [:div.action-err "⚠ " error-msg])
     (when (and (> n 1) (not= question (:question (first questions))))
       [:div.qc-head (md/render question)])
     (when (> n 1) (stepper-nav signal n words?))
     (map-indexed #(question-section qctx %1 n %2) questions)
     (when (> n 1)
       [:div.qc-step
        [:button {:type "button" :class "btn"
                  "data-attr:disabled" (str (step-signal signal) " === 0")
                  "data-on:click" (str (step-signal signal) " = Math.max(0, " (step-signal signal) " - 1)")} "◂ Previous"]
        [:button {:type "button" :class "btn"
                  "data-attr:disabled" (str (step-signal signal) " === " (dec n))
                  "data-on:click" (str (step-signal signal) " = Math.min(" (dec n) ", " (step-signal signal) " + 1)")} "Next ▸"]])
     (when (seq opt-does) (into [:div] (map (fn [d] [:p.ans-does d])) opt-does))
     (when note [:p.ask-note note])
     (if pending?
       [:div.controls [:div.ans-row [:span.meta "working… the agent is running."]]]
       [:div.controls
        (when submit-d (row :submit submit-d :primary))
        (for [a others]
          (row :click a (if (and primary-taken? (= :primary (:style a))) :default (:style a))))
        (when (seq boxes)
          (list (into [:div] (keep (fn [a] (when-let [d (does-of ask (:id a))] [:p.ans-does d]))) boxes)
                (block/render bar ws-id {:project project :ws-id ws-id :actions (vec boxes)
                                         :session session :demote-primary? primary-taken?}
                              ctx router)))])]))

;; ---------------------------------------------------------------------------
;; Scenes: literal asks, each on a workstream id of its own

(def ^:private one-question-ask
  {:kind :answer-blocker
   :question "Should the component catalog replace the pages as the interface?"
   :questions [{:question "Should the component catalog replace the pages as the interface?"
                :context ["The split is behaviour-preserving: no rendered page changes."
                          "It breaks one baseline claim — pages-are-the-interface — and states blocks-are-addressable in its place."]
                :recommendation "Replace them. The decision round found nothing it could not derive."
                :after "The next decision round reads your answer."
                :options [{:letter "A" :label "Replace them" :summary "Blocks become the interface."
                           :consequence "The design stands; the next round judges it again."
                           :recommended? true :action :option-a}
                          {:letter "B" :label "Keep the pages"
                           :summary "Something in the shape has to change first."
                           :consequence "The design is amended before the next round."
                           :action :option-b}]}]
   :answer-does [{:action :option-a :label "Choose" :does "Records your answer on the ledger."}
                 {:action :reply :label "Reply" :does "Records what you said and resumes the session."}]})

(def ^:private one-question-actions
  [{:id :option-a :label "A" :kind :resume :style :primary :seq 7}
   {:id :option-b :label "B" :kind :resume :style :default :seq 7}
   {:id :reply :label "Reply" :kind :resume :style :default}])

(def ^:private two-question-ask
  {:kind :accept-triage
   :question "Accept the triage of “Checkout off by a cent”?"
   :questions [{:question "Where should the total be rounded?"
                :context ["Each line rounds independently today, so the error accumulates."]
                :recommendation "Round once — the smallest fix."
                :after "The fix is built and a PR opened."
                :options [{:letter "A" :label "Round once" :summary "On the total."
                           :consequence "Effort: S" :recommended? true}
                          {:letter "B" :label "A money type"
                           :summary "Make the wrong thing unrepresentable."
                           :consequence "Effort: L"}]}
               {:question "Should the receipt show the rounding?"
                :context ["Finance asked; the customer has never asked."]
                :options [{:letter "A" :label "Show it" :summary "One line under the total."
                           :consequence "Effort: XS"}
                          {:letter "B" :label "Leave it" :summary "Nothing on the receipt."
                           :consequence "Effort: none"}]}]
   :submit :apply
   :answer-does [{:action :apply :label "Accept" :does "Records every answer and files the ticket."}
                 {:action :defer :label "Defer" :does "Leaves the questions for the design work."}]})

(def ^:private two-question-actions
  [{:id :apply :label "Accept" :kind :mutation :style :primary :seq 4}
   {:id :defer :label "Defer" :kind :mutation :style :default}
   {:id :reply :label "Reply" :kind :resume :style :default}])

(def ^:private unrecommended-ask
  (update-in one-question-ask [:questions 0]
             (fn [q] (-> q (dissoc :recommendation)
                         (update :options (fn [os] (mapv #(dissoc % :recommended?) os)))))))

(def ^:private long-question
  (str "Should the component catalog cover the twenty per-kind record cards as "
       "well, given that each one reads a different ledger record's shape and "
       "would need a sample of that record to have a scene at all, or is that a "
       "different reading job from the blocks a person actually iterates on?"))

(defn- card-props [ws-id ask actions & {:as more}]
  (merge {:project "nido" :ws-id ws-id :ask ask :actions actions} more))

;; ---------------------------------------------------------------------------
;; Their own rules, assembled into nido's stylesheet by the library

(def ^:private card-css
  ".qcard { border:1px solid var(--border); border-radius:var(--radius-6); background:var(--surface-raised);
            padding:var(--space-14) var(--space-16); margin:0 0 var(--space-20); }
   .qc-head { margin:0 0 var(--space-10); color:var(--text-muted); font-size:var(--fs-sm); max-width:var(--measure); }
   .qc-nav { display:flex; align-items:center; gap:var(--space-6); margin:0 0 var(--space-10); font-size:var(--fs-xs);
             color:var(--text-faint); text-transform:uppercase; letter-spacing:0.04em; }
   .qc-mark { background:none; border:0; padding:0 var(--space-2); cursor:pointer; color:var(--text-faint);
              font-size:var(--fs-sm); line-height:1; }
   .qc-mark.answered { color:var(--ok); }
   .qc-mark.current { color:var(--text-strong); text-decoration:underline; }
   .qc-q { font-size:var(--fs-lg); font-weight:500; color:var(--text-strong); margin:0 0 var(--space-10);
           line-height:1.4; max-width:var(--measure); }
   .qc-rec, .qc-after { font-size:var(--fs-md); color:var(--text); margin:0 0 var(--space-8);
                       max-width:var(--measure); }
   .qc-k { font-weight:600; color:var(--text-muted); margin:0 0 var(--space-2); }
   .qc-opts { display:grid; gap:var(--space-8); margin:var(--space-12) 0 var(--space-6); }
   .qc-opt { display:block; width:100%; max-width:var(--measure); text-align:left; font:inherit;
             color:var(--text); background:var(--surface); border:1px solid var(--border);
             border-radius:var(--radius-6); padding:var(--space-9) var(--space-12); cursor:pointer; }
   .qc-opt:hover { background:var(--surface-hover); }
   .qc-opt.primary { background:var(--accent-bg); border-color:var(--accent-border); }
   .qc-opt.chosen { background:var(--recommended-bg); border-color:var(--recommended); }
   .qc-opt.static { cursor:default; }
   .qc-opt-label { display:block; color:var(--text-strong); }
   .qc-opt-sum { display:block; }
   .qc-opt-cons { display:block; color:var(--text-muted); font-size:var(--fs-sm); }
   .qc-chosen { color:var(--recommended); font-size:var(--fs-xs); margin-left:var(--space-6); }
   .qc-step { display:flex; gap:var(--space-8); margin:var(--space-10) 0 0; }
   .ask-note { color:var(--text-muted); font-style:italic; max-width:var(--measure); }
   .controls { display:grid; gap:var(--space-8); margin:var(--space-6) 0 var(--space-20); }
   .ans-row { display:grid; grid-template-columns:minmax(150px, max-content) 1fr; gap:var(--space-12);
              align-items:start; padding:var(--space-10) var(--space-12); border:1px solid var(--border-subtle);
              border-radius:var(--radius-6); background:var(--surface-raised); }
   .ans-row.rec { border-color:var(--recommended-bg); }
   .ans-body p { margin:0 0 var(--space-4); max-width:var(--measure); }
   .ans-cons { color:var(--text-muted); font-size:var(--fs-sm); }
   .ans-letter { font-weight:600; color:var(--text-muted); }
   .ans-does { margin:0; color:var(--text-faint); font-size:var(--fs-sm); max-width:var(--measure); }
   .controls .reply { margin-top:0; }")

(def ^:private bar-css
  ".reply { margin-top:var(--space-16); border:1px solid var(--border); border-radius:var(--radius-6); background:var(--surface-raised); padding:var(--space-12) var(--space-14); }
   .reply textarea { width:100%; min-height:62px; background:var(--surface-sunken); border:1px solid var(--border);
                     border-radius:var(--radius-4); color:var(--text); font:inherit; font-size:var(--fs-sm); padding:var(--space-9) var(--space-11); }")

;; ---------------------------------------------------------------------------
;; The registrations

(def ^:private gate-props
  [:map
   [:project :string]
   [:ws-id :string]
   [:actions [:sequential :map]]])

(def ^{:malli/schema :map}
  bar
  "One action set: the single renderer behind every gate's controls."
  (block/block
   {:id      :ask-bar
    :doc     "One action set: the single renderer behind every gate's controls."
    :render  render-bar
    :props   (into gate-props [[:session {:optional true} [:maybe :string]]
                               [:demote-primary? {:optional true} :boolean]])
    :css     bar-css
    :actions bar-actions
    :morph   {:signals {:reply "" :evidence ""}}
    :scenes  [{:title "mutations and a reply"
               :props {:project "nido" :ws-id "ws-bar-1" :session "component-catalog"
                       :actions [{:id :promote :label "Promote" :kind :mutation :style :primary}
                                 {:id :drop :label "Drop" :kind :mutation :style :danger}
                                 {:id :reply :label "Reply" :kind :resume}]}}
              {:title "answering a blocker"
               :note  "The free text is recorded on the ledger and resumes nobody, so the copy must
                       not promise a resume. That is the whole difference from the row above."
               :props {:project "nido" :ws-id "ws-bar-2"
                       :actions [{:id :reply :label "Answer" :kind :resume}]}}
              {:title "asserting a gate"
               :note  "The evidence box comes first, above the buttons, because what you observed
                       is what the answer is."
               :props {:project "nido" :ws-id "ws-bar-3"
                       :actions [{:id :assert :label "Assert the gate" :kind :evidence
                                  :criterion "the dashboard serves /_catalog"}
                                 {:id :defer :label "Not yet" :kind :mutation}]}}]}))

(def ^{:malli/schema :map}
  card
  "One Ask, answered: its questions one at a time, their choices, and every action the gate
   offers beside what it does. Props: `:ask` a report.ask Ask, `:actions` the gate's action
   descriptors, `:pending?` a gate whose agent is running — its questions render with no control
   for any action — `:error-msg` a failed action's message, set above the first question, and
   `:session` the session a Reply resumes.

   Several questions share one card with a stepper; choices made in it are page state until the
   Ask's `:submit` sends them all — with the words written for a question that has no options
   when the submit's descriptor says it records them, `:words?` — so a question may be left
   open. The submit, or with none the recommended option, is the one primary button."
  (block/block
   {:id      :ask
    :doc     "One Ask, answered."
    :render  render-card
    :props   (into gate-props [[:ask [:maybe :map]]
                               [:pending? {:optional true} [:maybe :boolean]]
                               [:error-msg {:optional true} [:maybe :string]]
                               [:session {:optional true} [:maybe :string]]])
    :css     card-css
    :actions card-actions
    :morph   {:signals card-signals}
    :scenes
    [{:title "one question"
      :note  "The ordinary gate. The question leads, its support follows, and the one recommended
              answer is the one primary button."
      :props (card-props "ws-card-1" one-question-ask one-question-actions :session "component-catalog")}
     {:title "several questions"
      :note  "A verdict with one question per defect cannot let each answer give itself, because no
              one answer is the whole of it. One card, one question at a time, a marker per
              question, and Accept as the one submitting action. Click Previous/Next and the
              markers — the stepper is page state and works here."
      :props (card-props "ws-card-2" two-question-ask two-question-actions :session "auto")}
     {:title "pending"
      :note  "Its agent is running. Every choice renders with no control at all, so a poll cannot
              flip it back to a fresh button."
      :props (card-props "ws-card-3" two-question-ask two-question-actions :pending? true :session "auto")}
     {:title "a failed action"
      :note  "Set above the first question, and the controls stay clickable so the person can
              retry."
      :props (card-props "ws-card-4" one-question-ask one-question-actions
                         :error-msg "That option is no longer on the table — the report moved on since this page rendered.")}
     {:title "no recommendation"
      :note  "The writer had none. With no recommendation and no submit there is no primary button
              either — which is the honest rendering of a question nobody has an answer to."
      :props (card-props "ws-card-5" unrecommended-ask (mapv #(dissoc % :style) one-question-actions))}
     {:title "a dialog a driven session stands on"
      :note  "A session nido drives stopped mid-turn on a permission prompt nobody was looking at.
              After two minutes nido states it here as a blocker it wrote: what the prompt would
              run, and where to answer it — a button here could not answer the prompt. nido marks
              it answered when the prompt closes."
      :props (card-props
              "ws-card-6"
              {:kind :answer-blocker
               :question "Allow catalog-chat to use Bash: npm install --save-dev playwright?"
               :questions [{:question "Allow catalog-chat to use Bash: npm install --save-dev playwright?"
                            :context ["catalog-chat stopped mid-turn on a dialog (permission prompt) at 2026-11-13T14:43:19Z, and nothing proceeds until it is answered. It waits to run Bash: npm install --save-dev playwright."]
                            :after "Answer it in the session itself — open catalog-chat from the board. nido marks this answered when the dialog closes. A reply here is recorded, but it cannot reach the dialog."
                            :options []}]
               :answer-does [{:action :reply :label "Answer"
                              :does "Records your words on the ledger as the blocker's answer. Nobody is resumed: whoever takes the workstream up next reads it there."}]}
              [{:id :reply :label "Answer" :kind :resume :style :default :answers :ledger}]
              :session "catalog-chat")}
     {:title "an evidence box"
      :note  "A phase gate is asserted with what you observed on the running system. The box is a
              row beside what it does, like every other control."
      :props (card-props
              "ws-card-7"
              {:kind :assert-gate
               :question "Has phase 2 landed on the running system?"
               :questions [{:question "Has phase 2 landed on the running system?"
                            :context ["The exit criterion is the dashboard serving /_catalog on a daemon that has been up for an hour."]
                            :options []}]
               :answer-does [{:action :assert :label "Assert the gate"
                              :does "Records what you observed and opens phase 3."}]}
              [{:id :assert :label "Assert the gate" :kind :evidence
                :criterion "the dashboard serves /_catalog on a daemon that has been up for an hour"}])}
     {:title "a long question"
      :note  "A question nobody managed to write in one line. Prose is bounded to the reading
              measure and is never clipped — if this one is cut off or runs the width of the pane,
              the card is wrong."
      :props (card-props "ws-card-8"
                         (-> one-question-ask (assoc :question long-question)
                             (assoc-in [:questions 0 :question] long-question))
                         one-question-actions)}]}))
