(ns nido.coordinator.lane.github-merge
  "Coordinator housekeeping: poll a project's GitHub repo for merged PRs and
   react (close the matching workstream, append its terminal :merged ledger
   event, nudge its Notion ticket). No agent session is spawned — this is a
   direct state mutation, parallel to reclaim.

   Correlation is by the workstream's :github external-ref, stamped at
   PR-creation time by the prepare-draft-pr skill. Best-effort throughout."
  (:require
   [clojure.string :as str]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.source.state :as sstate]
   [nido.coordinator.record.workstream :as ws]
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
  "Best-effort Notion reaction for a merged PR's ticket. page-id from the
   workstream's :notion ref; on-merge from config."
  [page-id {:keys [notion-status remove-ball-holder]}]
  (when page-id
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
        (warn (str "github-merge: Notion nudge threw for " page-id " — " (.getMessage t)))))))

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

(defn- react-to-merge!
  "Correlate one merged PR to a workstream and react. Returns nil."
  [project on-merge repo {:keys [number] :as pr}]
  (let [id (pr-id repo number)
        w  (find-ws-by-github-id project id)]
    (cond
      (nil? w)      (warn (str "github-merge: merged PR " id " has no workstream; skipping"))
      (:closed w)   nil                                   ; idempotent no-op
      :else (do
              (ws/close! project (:id w) :done)
              (record-merge! project (:id w) id pr)
              (let [page-id (some #(when (= :notion (:adapter %)) (:page-id %))
                                  (:external-refs w))]
                (nudge-notion! page-id on-merge))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :map] :any]}
  poll-and-react!
  "One poll for a project. Lists merged PRs, dedups against the snapshot
   (first poll seeds + reacts to nothing), reacts to genuinely-new merges,
   persists the new snapshot.

   Half-open breaker: an :auth failure (or ≥3 consecutive failures) opens it.
   While open the poll is SKIPPED — no `gh` call, no warning — until
   breaker-cooldown-s has elapsed since :breaker-opened-at, then exactly one
   probe runs: success clears the breaker, failure re-arms the cooldown. So a
   broken `gh` auth backs off to one retry per cooldown instead of warning every
   poll, and self-heals once the token is fixed."
  [project {:keys [repo on-merge]}]
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
                (react-to-merge! project on-merge repo pr)))
            ;; success clears the breaker (fresh map drops :breaker-opened-at).
            (sstate/write-state! k {:type :github-merge :project project
                                    :reacted ids :consecutive-failures 0 :breaker nil})))))
    nil))
