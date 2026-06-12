(ns nido.coordinator.github-merge
  "Coordinator housekeeping: poll a project's GitHub repo for merged PRs and
   react (close the matching workstream, nudge its Notion ticket). No agent
   session is spawned — this is a direct state mutation, parallel to reclaim.

   Correlation is by the workstream's :github external-ref, stamped at
   PR-creation time by the prepare-draft-pr skill. Best-effort throughout."
  (:require
   [clojure.string :as str]
   [nido.coordinator.sources.state :as sstate]
   [nido.coordinator.workstream :as ws]
   [nido.github.client :as gh]
   [nido.github.react :as react]
   [nido.notion.client :as notion]))

(defn- warn [msg]
  (binding [*err* *err*]
    (.println ^java.io.PrintWriter *err* (str "WARN: " msg))))

(defn- state-key [project] (str "github-" (name project)))

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

(defn- react-to-merge!
  "Correlate one merged PR to a workstream and react. Returns nil."
  [project on-merge repo {:keys [number]}]
  (let [id (pr-id repo number)
        w  (find-ws-by-github-id project id)]
    (cond
      (nil? w)      (warn (str "github-merge: merged PR " id " has no workstream; skipping"))
      (:closed w)   nil                                   ; idempotent no-op
      :else (do
              (ws/close! project (:id w) :done)
              (let [page-id (some #(when (= :notion (:adapter %)) (:page-id %))
                                  (:external-refs w))]
                (nudge-notion! page-id on-merge))))))

(defn poll-and-react!
  "One poll for a project. Lists merged PRs, dedups against the snapshot
   (first poll seeds + reacts to nothing), reacts to genuinely-new merges,
   persists the new snapshot. Breaker state is recorded on failure (auth, or
   ≥3 consecutive) for visibility only — v1 does NOT self-gate on it, so the
   daemon keeps re-polling (a 401 keeps warning every poll until the token is
   fixed)."
  [project {:keys [repo on-merge]}]
  (let [k     (state-key project)
        prior (sstate/read-state k)
        res   (gh/list-merged-prs repo)]
    (if (:error res)
      (let [auth? (= :auth (:error res))
            fails (inc (or (:consecutive-failures prior) 0))]
        (sstate/write-state! k (merge (or prior {:type :github-merge :project project :reacted #{}})
                                      {:consecutive-failures fails
                                       :breaker (if (or auth? (>= fails 3)) :open (:breaker prior))}))
        (warn (str "github-merge: gh poll failed for " project " — " (:error res))))
      (let [ids       (into #{} (map #(pr-id repo (:number %))) (:prs res))
            first?    (nil? prior)
            seen      (or (:reacted prior) #{})]
        (when-not first?
          (doseq [pr (:prs res)
                  :when (not (contains? seen (pr-id repo (:number pr))))]
            (react-to-merge! project on-merge repo pr)))
        (sstate/write-state! k {:type :github-merge :project project
                                :reacted ids :consecutive-failures 0 :breaker nil})))
    nil))
