(ns nido.coordinator.lane.findings
  "Staging findings round: a human files findings after reviewing the deployed
   staging build; nido records the immutable :findings event on the workstream's
   active ledger, reopens the workstream to :in-progress as the person's choice, and
   starts it again (`lane.start`) — its impl session brought up at once. Each item's resolution is a later :findings-resolved
   entry, and what a round still owes is `standing/open-findings` — nothing tracks it
   beside the ledger. Modeled on nido.coordinator.lane.promote. See spec
   docs/superpowers/specs/2026-07-13-staging-findings-round-design.md."
  (:require
   [clojure.pprint :as pprint]
   [nido.coordinator.lane.start :as start]
   [nido.coordinator.record.standing :as standing]
   [nido.coordinator.record.workstream :as ws]))

(defn- assign-ids
  "Fill any missing :id on items as f1, f2, … (1-based positional)."
  [items]
  (vec (map-indexed (fn [i it] (update it :id #(or % (str "f" (inc i))))) items)))

(defn- append-event!
  "Append the :findings event to the workstream's ledger (the one canonical store).
   Validation runs at the ledger boundary (report/entry-payload)."
  [project w round session]
  (let [content (with-out-str (pprint/pprint round))
        entry   (cond-> {:kind :findings} session (assoc :session session))]
    (ws/append-entry! project (:id w) entry content)))

(defn- startable?
  "Whether the workstream carries a ticket a start can bring a session up for: a Notion ticket
   or a GitHub issue. Any other workstream is reopened and left for a person to resume."
  [w]
  (some #(#{:notion :github-issue} (:adapter %)) (:external-refs w)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map] :map]}
  file!
  "File a staging findings round on a shipped (settled) workstream. `opts` =
   {:items [{:summary :severity (:id) (:area)} …] :staging-ref? :note? :session?}.
   Appends the immutable :findings event, reopens to :in-progress as the person's
   choice, and starts a ticket-backed workstream again (`start/start-ticket!`). Returns
   {:round n :start <the start's answer, or nil when nothing could be started>}. Throws if
   the workstream is absent or not settled.

   A round filed on a workstream closed :between-phases names the design that close
   named, as :between-phases: the round is about the phase that landed, and resolving
   its last item returns the workstream to its gate under that plan."
  [project ws-id {:keys [items staging-ref note session]}]
  (let [w      (or (ws/read-ws project ws-id)
                   (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))
        closed (ws/closure w)]
    (when-not closed
      (throw (ex-info "Can only file findings on a shipped (settled) workstream"
                      {:project project :ws-id ws-id})))
    (let [round-n (inc (:round (standing/newest-findings project ws-id w) 0))
          items*  (assign-ids items)
          round   (cond-> {:format :findings :round round-n :items items*}
                    staging-ref (assoc :staging-ref staging-ref)
                    note        (assoc :note note)
                    (= :between-phases (:outcome closed))
                    (assoc :between-phases (get-in closed [:design :seq])))]
      (append-event! project w round session)
      (ws/reopen! project ws-id :in-progress :person)
      {:round round-n
       :start (when (startable? w) (start/start-ticket! project {:ws-id ws-id}))})))

(defn- published-since-round?
  "Whether a :pr-opened was appended after the newest :findings — a fix for the
   round that will land, and close the workstream, through a landing path."
  [w]
  (let [round (->> (:entries w) (filter #(= :findings (:kind %))) (map :seq) (reduce max 0))]
    (some #(and (= :pr-opened (:kind %)) (> (:seq %) round)) (:entries w))))

(defn- settle-between-phases!
  "Close `ws-id` :between-phases again, under the design its round was filed against,
   when that round was filed between phases and no fix for it was published — a fix
   that was closes it the same way when it lands, through its landing path.

   The round belonged to the landed phase: filing it wrote no gate, so that phase is
   still current, and its end returns the workstream to its gate — never on to
   implementing the next phase, which only `lane.phase/advance!` opens."
  [project ws-id round]
  (let [w (ws/read-ws project ws-id)]
    (when (and (:between-phases round)
               (nil? (ws/closure w))
               (not (published-since-round? w)))
      (ws/close! project ws-id :between-phases (:between-phases round)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :any :any] :map]}
  resolve!
  "Mark items of the newest findings round resolved by PR/commit `by`: record the ones still
   open as a :findings-resolved entry; unknown and already-resolved ids are ignored, and a call
   resolving none records nothing. Throws when the workstream holds no findings round. Returns
   `{:round n :open #{ids still open}}`.

   Resolving the last open item of a round filed on a phased workstream between
   its phases settles it between phases again (`settle-between-phases!`)."
  [project ws-id item-ids by]
  (let [w     (ws/read-ws project ws-id)
        round (when w (standing/newest-findings project ws-id w))]
    (when-not round
      (throw (ex-info "No open findings round" {:project project :ws-id ws-id})))
    (let [open (standing/open-findings project ws-id)
          ids  (vec (distinct (filter open item-ids)))]
      (when (seq ids)
        (ws/append-entry! project ws-id {:kind :findings-resolved}
                          (pr-str {:format :findings-resolved :round (:round round)
                                   :items ids :by (str by)})))
      (let [left (reduce disj open ids)]
        (when (empty? left) (settle-between-phases! project ws-id round))
        {:round (:round round) :open left}))))
