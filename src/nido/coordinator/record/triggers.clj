(ns nido.coordinator.record.triggers
  "Per-project trigger config: schema, load, validate, find.

   See spec §Triggers."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [malli.core :as m]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.state :as cstate]
   [nido.platform.io :as io]))

(def Trigger
  [:map {:closed true}
   [:name           keyword?]
   [:source         [:map [:type keyword?]]]
   [:skill          keyword?]
   [:payload        string?]
   [:filter      {:optional true} [:map-of keyword? any?]]
   [:payload-key {:optional true} keyword?]
   [:agent       {:optional true} keyword?]
   ;; REQUIRED, and :budget inside it is required too. A trigger is the place a
   ;; unit of autonomous work declares its brakes, and this schema used to
   ;; constrain neither whether they were declared nor what they contained — so
   ;; omitting :budget was not an error anywhere, and agent/launch! read the
   ;; resulting nil as infinite. brian's :plan-bug omitted it for months while a
   ;; comment above it asserted it ran nothing headlessly.
   ;;
   ;; Refused HERE rather than only at launch, because here nothing has been
   ;; spawned yet: a bad trigger fails when the config is read, not after a
   ;; session, a worktree and a database have been provisioned for it. The
   ;; launch-time refusal stays as the backstop for every caller that reaches an
   ;; agent without a trigger behind it.
   [:limits      [:map
                  [:budget string?]
                  [:max-failures {:optional true} pos-int?]]]
   [:priority       {:optional true} int?]
   [:priority-from  {:optional true} [:map [:property string?]]]
   [:session-profile {:optional true} keyword?]
   ;; The stage a fire's workstream is BORN at. `spawn/initial-stage` has always read it
   ;; — `(or (-> routed :trigger :workstream-stage) :triaging)` — but this map is closed
   ;; and did not carry it, so every stanza that declared it failed validation before the
   ;; reader ever ran. A default nothing could override is not a default.
   ;;
   ;; The described-intent leg is the first caller that needs it: its workstream has no
   ;; ticket to triage, and being born :triaging would put it in the intake queue asking
   ;; a person to classify a description they just typed.
   ;;
   ;; Constrained to the SAME closed vocabulary `workstream/create!` accepts, not to
   ;; keyword?: a typo, or an arc stage like :design, otherwise validated here, routed
   ;; normally, and threw only once the envelope had been drained — after the queue file
   ;; was deleted, so the request was gone and the tick took the throw with it.
   [:workstream-stage {:optional true} (into [:enum] (sort session/storable-stages))]
   ;; ⚠ INERT. The dispatch that ran these was deleted 2026-08-31 as dead code — nothing
   ;; called it and no path wrote the :preprocessing phase it was meant to run in. The key
   ;; stays because configured triggers still carry it and a closed schema would reject them;
   ;; it names preprocessors nobody runs until something is wired to read it again.
   [:preprocess     {:optional true} [:vector keyword?]]
   [:intake         {:optional true} [:enum :spawn :queue]]
   [:dry-run?       {:optional true} boolean?]
   [:enabled?       {:optional true} boolean?]
   [:uncapped?      {:optional true} boolean?]
   [:max-in-flight  {:optional true} pos-int?]
   [:session-name-prefix {:optional true} string?]
   [:on-promote          {:optional true} [:map-of keyword? any?]]])

(def TriggersFile
  [:map {:closed true}
   [:triggers [:vector Trigger]]])

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:vector :Trigger]]}
  load-for-project
  "Read triggers.edn for a project. Returns a vector of trigger maps
   (possibly empty). Invalid entries are skipped with a stderr warning."
  [project]
  (let [path (cstate/triggers-path project)]
    (if (fs/exists? path)
      (let [raw (io/read-edn path)]
        (if (m/validate TriggersFile raw)
          (:triggers raw)
          (do
            (binding [*err* *err*]
              (.println ^java.io.PrintWriter *err*
                        (str "WARN: invalid triggers.edn for project " project
                             " — " (pr-str (m/explain TriggersFile raw)))))
            (->> (:triggers raw)
                 (filter #(m/validate Trigger %))
                 vec))))
      [])))

(defn ^{:malli/schema [:=> [:cat [:vector :Trigger] :keyword] [:maybe :Trigger]]}
  find-by-name
  "Find a trigger in a loaded vector by :name. Returns nil if absent."
  [triggers name]
  (some #(when (= name (:name %)) %) triggers))

(def ^:private placeholder-re
  #"\{\{event/([^}]+)\}\}")

(defn- lookup-path
  "Resolve a slash-delimited path like 'ticket/id' against an event map."
  [event path]
  (let [ks (mapv keyword (str/split path #"/"))]
    (get-in event ks)))

(def ^:private unfilled-placeholder
  "What a {{event/…}} the event carries no value for renders as.

   A blank cannot be told apart from a value that is genuinely empty or zero,
   and a payload template renders into the first message a session ever sees:
   `Coverage:  targets read this run` reads as a sentence missing a word, not
   as a number nobody supplied. `?` names the absence where the reader is."
  "?")

(defn ^{:malli/schema [:=> [:cat :string :map] :string]}
  render-payload
  "Replace {{event/path}} placeholders in template with values from event.
   A placeholder the event does not fill renders as `unfilled-placeholder`;
   `payload-problems` is what says the same thing to an operator."
  [template event]
  (str/replace template placeholder-re
               (fn [[_ path]]
                 (if-some [v (lookup-path event path)]
                   (str v)
                   unfilled-placeholder))))

(defn ^{:malli/schema [:=> [:cat :Trigger :map] [:vector :string]]}
  payload-problems
  "Pure. One sentence for each way this trigger's :payload template and the
   framework's contract for it disagree, given the event about to be rendered
   into it. Empty when they agree.

   The template is the one part of a trigger nothing else can hold to anything.
   It is read from ~/.nido/projects/<project>/triggers.edn, outside any repo,
   and names payload keys that ship with the code — so an adapter and the
   template that reads it can never land together, and a branch forked before a
   key was renamed asks for keys its own payload does not build. Both faults are
   otherwise silent: one loses a number, the other hands the skill its own name
   as an argument.

   Judged at FIRE, which is the only moment both halves exist: `load-for-project`
   runs on every daemon tick and has no event to check against."
  [{:keys [skill payload]} event]
  (let [prefix   (str "/" (name skill))
        unfilled (->> (re-seq placeholder-re payload)
                      (map second)
                      distinct
                      (remove #(some? (lookup-path event %))))]
    (cond-> []
      ;; The framework prepends "/<skill> " itself — see `runs/create-run!`, where
      ;; the contract is stated. A template that opens with the same token sends the
      ;; skill a second copy of its own name as its first argument.
      (= prefix (first (str/split payload #"\s" 2)))
      (conj (str "payload opens with " prefix ", which the framework prepends anyway"
                 " — the skill gets a second copy as its first argument"))

      (seq unfilled)
      (conj (str "payload asks for "
                 (str/join ", " (map #(str "{{event/" % "}}") unfilled))
                 ", which this event does not carry")))))

(defn ^{:malli/schema [:=> [:cat :string] [:vector :keyword]]}
  placeholder-keys
  "Return ordered vector of placeholder names from a trigger's :payload
   template. `{{event/url}}` → `:url`. Top-level keys only — slash-paths
   are not addressable from the form."
  [payload-template]
  (->> (re-seq #"\{\{event/([^}/]+)\}\}" payload-template)
       (map second)
       distinct
       (mapv keyword)))
