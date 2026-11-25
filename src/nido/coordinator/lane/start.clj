(ns nido.coordinator.lane.start
  "A person starting a ticket at the click.

   A person's start of a ticket is an ACT, carried out when they click, not a request nido
   fulfils later. This is its work-record half: what the ticket says and where it stands. The
   coordination half — the workstream's unit opened in the core, which provisions its session —
   is the work plane's, which calls this first.

   HIDES what a start consists of: the order its ledger writes land in (the ticket's own words
   first, so nothing the start appends can make the transcription look already done), which
   source the brief is read from, and the project's Notion properties a start writes."
  (:require
   [nido.coordinator.lane.brief :as brief]
   [nido.coordinator.record.tickets :as tickets]
   [nido.coordinator.record.workstream :as ws]
   [nido.github.client :as gh]
   [nido.notion.client :as notion]
   [nido.platform.config :as config]))

(defn- warn [msg]
  (binding [*out* *err*] (println (str "nido start: " msg))))

;; ── the ticket a workstream carries ─────────────────────────────────────────

(defn- notion-ref [w] (some #(when (= :notion (:adapter %)) %) (:external-refs w)))
(defn- github-ref [w] (some #(when (= :github-issue (:adapter %)) %) (:external-refs w)))



(defn- issue-parts
  "\"owner/repo#42\" → [\"owner/repo\" 42]."
  [id]
  (let [i (.lastIndexOf ^String id "#")]
    [(subs id 0 i) (parse-long (subs id (inc i)))]))

(defn- read-ticket
  "What the start needs from the ticket outside nido: {:title :url}, plus :brief, the markdown of
   its own words when `owed?`. nil when the ticket cannot be read and its words are owed.
   A Notion ticket is read with the keychain token; a GitHub issue through gh."
  [w owed?]
  (if-let [g (github-ref w)]
    (let [[repo n] (issue-parts (:id g))
          {:keys [issue error]} (gh/view-issue repo n)]
      (cond
        issue (cond-> {:title (:title issue) :url (:url issue)}
                owed? (assoc :brief (str "# " (:title issue) "\n" (:url issue) "\n\n"
                                         "_Transcribed from GitHub by nido when a person started "
                                         "it; the issue is authoritative._\n\n" (:body issue))))
        (not owed?) {:title (:title g) :url (:url g)}
        :else (do (warn (str "issue " (:id g) " unreadable — " (pr-str error))) nil)))
    (let [n (notion-ref w)]
      (cond-> {:title (:title n) :url (:url n)}
        owed? (assoc ::notion-owed true)))))

;; ── the Notion properties a start writes ───────────────────────────────────

;; Fixed for brian's Task Database: who the people are is configuration, the property names that
;; own them are not.
(def ^:private ball-holder-property "Ball Holder")
(def ^:private participants-property "Participants")

(defn- project-entry [project]
  (let [ps (config/read-projects)]
    (or (get ps (name project)) (get ps (keyword (name project))) (get ps (symbol (name project))))))

(defn- write-start-properties!
  "Write the project's :on-start Notion properties — {:notion-status <s> :property <s?>
   :ball-holder <user-id?> :add-participants [<user-id> …]} in its projects.edn entry — to the
   page `page-id`. Status and Ball Holder replace; Participants is merged into whoever is already
   on the ticket, and skipped when the page cannot be read, so the list is never clobbered.
   Best-effort: every failure is logged and swallowed."
  [project page-id]
  (let [{:keys [notion-status property ball-holder add-participants]} (:on-start (project-entry project))]
    (when (and page-id (or notion-status ball-holder (seq add-participants)))
      (try
        (if-let [token (notion/keychain-token)]
          (let [parts (when (seq add-participants)
                        (let [page (notion/retrieve-page page-id token)]
                          (if (:error page)
                            (do (warn (str "could not read Participants of " page-id "; skipping them")) nil)
                            (let [have (keep :id (get-in page [:properties (keyword participants-property) :people]))]
                              {:people (mapv (fn [id] {:id id}) (distinct (concat have add-participants)))}))))
                props (cond-> {}
                        notion-status (assoc (or property "Status") {:status {:name notion-status}})
                        ball-holder   (assoc ball-holder-property {:people [{:id ball-holder}]})
                        parts         (assoc participants-property parts))
                res   (when (seq props) (notion/update-page-properties! page-id props token))]
            (when (:error res) (warn (str "Notion property write failed for " page-id " — " (pr-str res)))))
          (warn (str "no Notion token; skipping the start's property write for " page-id)))
        (catch Throwable t
          (warn (str "Notion property write threw for " page-id " — " (ex-message t))))))
    nil))

;; ── a ticket start ──────────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :ProjectName
                             [:or [:map [:ws-id :WorkstreamId]]
                                  [:map [:ref [:map [:adapter [:enum :notion :github-issue]] [:id :string]]]]]]
                       [:or [:map [:decision [:enum :started]] [:ws-id :WorkstreamId]]
                            [:map [:decision [:enum :no-such-workstream :closed :ticket-unreadable]]]]]}
  start-ticket!
  "A person starts a ticket's workstream, `start` being {:ws-id} or {:ref {:adapter :notion :id
   <BR-####>}} — already resolved by the surface lane that took their input, which is where the
   input is trusted. In order: transcribe the ticket onto the ledger when nothing tells it there
   yet; record the person's :in-progress choice unless the ticket is already planning or being
   worked (`tickets/status-of`); write the project's start properties to a Notion ticket. The
   caller then opens the workstream's unit in the core.

   Answers {:decision :started :ws-id}, or, before writing anything on the workstream, one
   refusal: :no-such-workstream, :closed, or :ticket-unreadable (its words are owed to the ledger
   and could not be read). A failed Notion property write never stops a start."
  [project start]
  (let [p (keyword (name project))
        w (if-let [id (:ws-id start)]
            (ws/read-ws p id)
            (ws/find-or-mint-by-ref! p (get-in start [:ref :id])))]
    (cond
      (nil? w)        {:decision :no-such-workstream}
      (ws/closure w)  {:decision :closed}
      :else
      (let [ws-id   (:id w)
            owed?   (brief/owed? w)
            ticket  (read-ticket w owed?)
            ;; The caller's ref fills what the workstream's lacks: one minted from a bare BR id
            ;; carries no page id, which the ticket record behind the caller does.
            ref     (merge (:ref start) (or (notion-ref w) (github-ref w)))
            page-id (or (:page-id (notion-ref w)) (get-in start [:ref :page-id]))
            title   (or (:title ticket) (:title ref) (:title w))]
        (cond
          (nil? ticket)                         {:decision :ticket-unreadable}
          ;; Owed and with no way to read it: no page to read, or no credential to read it with.
          ;; A page that reads empty is transcribed as empty, best-effort, and does not stop the
          ;; start — a person's start is not held hostage to a flaky API.
          (and owed? (::notion-owed ticket) (or (nil? page-id) (nil? (notion/keychain-token))))
          {:decision :ticket-unreadable}

          :else
          (do ;; The ticket's own words first, so the entries below never read as its telling.
              (when (and owed? (::notion-owed ticket))
                (brief/ensure-ticket-brief! p ws-id (assoc ref :page-id page-id :title title)
                                            (notion/keychain-token)))
              (when (and owed? (:brief ticket))
                (ws/append-entry! p ws-id {:kind :ticket} (:brief ticket)))
              ;; A ticket already planning or being worked keeps its stage: a second start must not
              ;; pull one with recorded work back to :planning.
              (when-not (#{:planning :implementing} (tickets/status-of p (ws/read-ws p ws-id)))
                (ws/advance-stage! p ws-id :in-progress :person))
              (when (notion-ref w) (write-start-properties! p page-id))
              {:decision :started :ws-id ws-id}))))))

