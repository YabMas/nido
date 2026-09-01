(ns nido.coordinator.lane.github-merge
  "Coordinator housekeeping: poll a project's GitHub repo for merged PRs and
   react (close the matching workstream, append its terminal :merged ledger
   event, nudge its Notion ticket). No agent session is spawned — this is a
   direct state mutation, parallel to reclaim.

   Correlation is by the workstream's :github external-ref, stamped at
   PR-creation time by the prepare-draft-pr skill, AND by the branch the PR
   merged into — a ref match alone is not a landing (see `landed-on?`).
   Best-effort throughout."
  (:require
   [clojure.string :as str]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.source.state :as sstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.lane.pickup :as pickup]
   [nido.github.client :as gh]
   [nido.github.react :as react]
   [nido.notion.client :as notion]))

(defn- warn [msg]
  (binding [*err* *err*]
    (.println ^java.io.PrintWriter *err* (str "WARN: " msg))))

(defn- state-key [project] (str "github-" (name project)))

;; Half-open breaker: once tripped, suppress polling for this long, then allow
;; one probe poll — success clears it, failure re-arms the cooldown. Auth
;; failures need a human to re-auth `gh`, so this backs off (30m) instead of
;; hammering gh + warning every 5m poll, and self-heals once the token is fixed.
;; Mirrors the :notion-view source's breaker (nido.coordinator.source.notion).
(def ^:private breaker-cooldown-s (* 30 60))

(defn- cooldown-elapsed?
  "True if a tripped breaker is due for a half-open probe. Permissive when no
   :breaker-opened-at is recorded (legacy/hand-edited state) so the poller never
   stays dark forever."
  [state]
  (if-let [opened (:breaker-opened-at state)]
    (try
      (>= (.toSeconds (java.time.Duration/between
                        (java.time.Instant/parse opened)
                        (java.time.Instant/parse (clock/now-iso))))
          breaker-cooldown-s)
      (catch Exception _ true))
    true))

(defn- pr-id [repo number] (str repo "#" number))

(defn- nudge-notion!
  "Best-effort Notion reaction for a merged PR's ticket. `page-id` is resolved
   and checked by the CALLER — this guarded on it itself until 2026-09-01, which
   is how a workstream whose ref carried no page-id got a close, no ticket write,
   and no word about either."
  [page-id {:keys [notion-status remove-ball-holder]}]
  (try
    (if-let [token (notion/keychain-token)]
      (let [page  (when remove-ball-holder (notion/retrieve-page page-id token))
            props (cond-> {}
                    notion-status (assoc "Status" {:status {:name notion-status}})
                    (and remove-ball-holder page (not (:error page)))
                    (assoc "Ball Holder"
                           (react/people-without
                             (get-in page [:properties (keyword "Ball Holder")])
                             remove-ball-holder)))]
        (when (seq props)
          (let [res (notion/update-page-properties! page-id props token)]
            (when (:error res)
              (warn (str "github-merge: Notion write failed for " page-id " — " (pr-str res)))))))
      (warn (str "github-merge: no Notion token; skipping Notion nudge for " page-id)))
    (catch Throwable t
      (warn (str "github-merge: Notion nudge threw for " page-id " — " (.getMessage t))))))

(defn- find-ws-by-github-id
  "Workstream carrying a :github ref matching `id` case-insensitively. GitHub
   owner/repo slugs are case-insensitive, but find-by-ref compares exactly — so
   a slug cased differently in the stamped ref vs github.edn :repo would never
   correlate. Normalize both sides here."
  [project id]
  (let [target (str/lower-case id)]
    (->> (ws/list-ids project)
         (keep #(ws/read-ws project %))
         (some (fn [w]
                 (when (some #(and (= :github (:adapter %))
                                   (= target (some-> (:id %) str/lower-case)))
                             (:external-refs w))
                   w))))))

(defn- notion-ref [w]
  (some #(when (= :notion (:adapter %)) %) (:external-refs w)))

(defn- ref-page-id
  "The page-id to write a merge reaction to: the ref's stored :page-id, else the
   id carried in its :url.

   The URL fallback is load-bearing rather than belt-and-braces. A ref is stamped
   with a :page-id only when the spawning event's payload carried one, and the
   older spawn paths emitted just the BR-####; seven of brian's workstreams hold
   a :notion ref with a :url and no :page-id. Reading (:page-id ref) as the whole
   answer classified those as having no Notion ticket at all."
  [ref]
  (or (:page-id ref) (pickup/extract-page-id (:url ref))))

(defn- record-merge!
  "Append the terminal :merged event to the workstream's ledger. Best-effort and
   deliberately AFTER close! — a ledger write that fails must not cost the close
   or the Notion nudge. Everything it needs came back from `gh pr list`, which is
   why this is the one lifecycle event that cannot go missing."
  [project ws-id id {:keys [url title merged-at]}]
  (try
    (ws/append-entry! project ws-id {:kind :merged}
                      (pr-str {:format    :merged
                               :pr        id
                               :url       url
                               :title     title
                               :merged-at merged-at}))
    (catch Throwable t
      (warn (str "github-merge: ledger append failed for " ws-id " (" id ") — " (.getMessage t))))))

(def ^:private default-landing-base
  "The branch a merge must land on before it counts as shipped, when github.edn
   names none."
  "main")

(defn- landed-on?
  "True when `pr` merged into `base` — the ONLY merge that ships anything.

   Reads a positive signal, so it must block on absence: a `:base` we did not
   get back (an older `gh` without --json baseRefName, a stubbed poll) answers
   false and withholds the close. The alternative — treating a missing base as
   \"probably the default branch\" — disarms the guard on exactly the input it
   exists to catch, and the cost of the two directions is not symmetric. A
   withheld close leaves a shipped workstream sitting on the board, visible and
   one poll from correcting itself. A wrongful close is silent, terminal, and
   takes the ticket off every surface a human looks at."
  [pr base]
  (= base (:base pr)))

(defn- react-to-merge!
  "Correlate one merged PR to a workstream and react. Returns nil."
  [project on-merge base repo {:keys [number] :as pr}]
  (let [id (pr-id repo number)
        w  (find-ws-by-github-id project id)]
    (cond
      (nil? w)      (warn (str "github-merge: merged PR " id " has no workstream; skipping"))
      (:closed w)   nil                                   ; idempotent no-op
      ;; A stack merges its layers into each other while it is being restacked,
      ;; and every one of those is a genuine `merged` PR carrying a ref this
      ;; workstream stamped. Only the merge into `base` shipped anything. Warn
      ;; rather than pass over it quietly: the workstream stays open, which is
      ;; correct but indistinguishable from nothing having happened, and it was
      ;; a silent close on exactly this input that stranded BR-5559 for a week.
      (not (landed-on? pr base))
      (warn (str "github-merge: PR " id " merged into "
                 (or (:base pr) "<no base reported>") ", not " base
                 " — leaving workstream " (:id w) " open. A stack-internal merge"
                 " lands nothing on " base "."))
      :else (let [ref (notion-ref w)]
              (ws/close! project (:id w) :done)
              (record-merge! project (:id w) id pr)
              (if-let [page-id (ref-page-id ref)]
                (nudge-notion! page-id on-merge)
                ;; No :notion ref at all (a GitHub-issue or Slack workstream)
                ;; means there is no ticket to nudge, and silence is right. A ref
                ;; we could not resolve is a gap: the workstream is now closed
                ;; while its ticket sits at its pre-merge status, and nobody is
                ;; told which of the two happened.
                (when ref
                  (warn (str "github-merge: workstream " (:id w) " carries a :notion ref ("
                             (:id ref) ") with no resolvable page-id — closed on " id
                             ", but the ticket was NOT nudged."))))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :map] :any]}
  poll-and-react!
  "One poll for a project. Lists merged PRs, dedups against the snapshot
   (first poll seeds + reacts to nothing), reacts to genuinely-new merges that
   landed on the config's :base (default \"main\"), persists the new snapshot.

   The snapshot holds every merged PR the poll saw, INCLUDING the ones that
   landed somewhere other than :base. They are seen rather than reacted to, so
   a stack-internal merge is warned about exactly once instead of once per poll
   for as long as it stays in the repo's last fifty merges.

   Half-open breaker: an :auth failure (or ≥3 consecutive failures) opens it.
   While open the poll is SKIPPED — no `gh` call, no warning — until
   breaker-cooldown-s has elapsed since :breaker-opened-at, then exactly one
   probe runs: success clears the breaker, failure re-arms the cooldown. So a
   broken `gh` auth backs off to one retry per cooldown instead of warning every
   poll, and self-heals once the token is fixed."
  [project {:keys [repo on-merge base]}]
  (let [k     (state-key project)
        prior (sstate/read-state k)]
    (when-not (and (= :open (:breaker prior)) (not (cooldown-elapsed? prior)))
      (let [res (gh/list-merged-prs repo)]
        (if (:error res)
          (let [auth? (= :auth (:error res))
                fails (inc (or (:consecutive-failures prior) 0))
                ;; open on auth, on crossing the threshold, or re-arm a failed
                ;; half-open probe (breaker was already open).
                open? (or auth? (>= fails 3) (= :open (:breaker prior)))]
            (sstate/write-state! k (merge (or prior {:type :github-merge :project project :reacted #{}})
                                          (cond-> {:consecutive-failures fails
                                                   :breaker (if open? :open (:breaker prior))}
                                            open? (assoc :breaker-opened-at (clock/now-iso)))))
            (warn (str "github-merge: gh poll failed for " project " — " (:error res))))
          (let [ids    (into #{} (map #(pr-id repo (:number %))) (:prs res))
                first? (nil? prior)
                seen   (or (:reacted prior) #{})]
            (when-not first?
              (doseq [pr (:prs res)
                      :when (not (contains? seen (pr-id repo (:number pr))))]
                (react-to-merge! project on-merge (or base default-landing-base) repo pr)))
            ;; success clears the breaker (fresh map drops :breaker-opened-at).
            (sstate/write-state! k {:type :github-merge :project project
                                    :reacted ids :consecutive-failures 0 :breaker nil})))))
    nil))
