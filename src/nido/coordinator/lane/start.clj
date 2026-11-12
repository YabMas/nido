(ns nido.coordinator.lane.start
  "A person starting work: a session brought up on its workstream, and a ticket started at the
   click.

   A person's start of a ticket is an ACT, carried out when they click, not a request the daemon
   fulfils later. It is not a Run: a Run is work nido starts and finishes on its own, and nothing
   a person started waits behind the Run cap, a trigger's breaker or a budget. The workstream is
   activated by the start itself — that is what hands it to nido's recipe — and the conductor the
   activation grants takes it from there.

   HIDES what a start consists of: the order its ledger writes land in (the ticket's own words
   first, so nothing the start appends can make the transcription look already done), which
   source the brief is read from, the project's Notion properties a start writes, and how the
   impl session is named, brought up and carried."
  (:require
   [clojure.string :as str]
   [nido.coordinator.lane.brief :as brief]
   [nido.coordinator.lane.conductor :as conductor]
   [nido.coordinator.lane.scratch :as scratch]
   [nido.coordinator.record.session :as csession]
   [nido.coordinator.record.tickets :as tickets]
   [nido.coordinator.record.workstream :as ws]
   [nido.github.client :as gh]
   [nido.notion.client :as notion]
   [nido.platform.config :as config]
   [nido.session.lifecycle :as lifecycle]))

(defn- warn [msg]
  (binding [*out* *err*] (println (str "nido start: " msg))))

;; ── a session start ─────────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :ProjectName :SessionName :map] :WorkstreamId]}
  start-session!
  "Bring the session `session-name` up on the workstream holding it — or, given `:ws-id`, on that
   one; else on a minted one-off — and on that workstream's runtime, then carry it when this
   start first recorded it — or, given `:carry? true`, whether or not it did. Heavy and slow:
   surfaces wrap it in their own progress UI. Returns the ws-id.

   `opts` are lifecycle/up!'s, plus `:ws-id`, `:carry?`, and `:brief`, `:model` and `:given-by`
   for the carry (`conductor/carry!`; :given-by defaults to :start, a person's). The carry takes
   the whole seat only when it is free and no ids are fixed, so a session already recorded is
   carried only where nobody else works. The workstream is
   settled BEFORE up! so the session comes up on its runtime at once, and its weight read back
   AFTER, from what up! really provisioned. A failure to carry leaves the session up and
   undriven, and says so on stderr; it never fails the start. Callers check
   `scratch/joinable` first when a named workstream may refuse the session."
  [project session-name opts]
  (let [p        (keyword (name project))
        {:keys [ws-id carry? brief model given-by] :or {given-by :start}} opts
        up-opts  (-> (dissoc opts :ws-id :carry? :brief :model :given-by)
                     (assoc :project (name project)))
        first?   (nil? (csession/workstream-id-for p session-name))
        held-by  (scratch/birth! p session-name nil ws-id)]
    (lifecycle/up! session-name (assoc up-opts :runtime-key held-by))
    (let [held-by (scratch/birth! p session-name (lifecycle/session-weight session-name up-opts) held-by)]
      (when (or first? carry?)
        (try (conductor/carry! p held-by session-name brief model given-by)
             (catch Throwable t
               (warn (str session-name " is up but was not carried — " (ex-message t))))))
      held-by)))

;; ── the ticket a workstream carries ─────────────────────────────────────────

(defn- notion-ref [w] (some #(when (= :notion (:adapter %)) %) (:external-refs w)))
(defn- github-ref [w] (some #(when (= :github-issue (:adapter %)) %) (:external-refs w)))

(defn- slugify
  "Lower-case `s` into a `-`-delimited alphanumeric slug, capped near 40 characters on a word
   boundary."
  [s]
  (let [base (-> (str s) str/lower-case (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-+|-+$" ""))]
    (if (<= (count base) 40)
      base
      (let [cut (subs base 0 40) at (str/replace cut #"-[^-]*$" "")]
        (if (seq at) at cut)))))

(defn- impl-session-name
  "`impl-<ref-slug>[-<title-slug>]` — e.g. \"impl-br-4659-firefox-loading\". The name a ticket's
   impl session has always had, so a ticket started again finds the session it already has."
  [ref-id title]
  (let [t (some-> title slugify)]
    (str "impl-" (slugify ref-id) (when (seq t) (str "-" t)))))

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
                       [:or [:map [:decision [:enum :started]] [:ws-id :WorkstreamId] [:session :SessionName]]
                            [:map [:decision [:enum :no-such-workstream :closed :name-held :ticket-unreadable]]]]]}
  start-ticket!
  "A person starts a ticket's workstream, `start` being {:ws-id} or {:ref {:adapter :notion :id
   <BR-####>}} — already resolved by the surface lane that took their input, which is where the
   input is trusted. In order: transcribe the ticket onto the ledger when nothing tells it there
   yet; record the person's :in-progress choice unless the ticket is already planning or being
   worked (`tickets/status-of`); activate the recipe by
   :person; write the project's start properties to a Notion ticket; bring the impl session up
   and carry it into the whole seat when that seat is free.

   Answers {:decision :started :ws-id :session}, or, before writing anything on the workstream,
   one refusal: :no-such-workstream, :closed, :name-held (the impl session's name belongs to
   another workstream), or :ticket-unreadable (its words are owed to the ledger and could not be
   read). A failed Notion property write never stops a start."
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
            title   (or (:title ticket) (:title ref) (:title w))
            session (impl-session-name (:id ref) title)
            held    (scratch/joinable p session ws-id)]
        (cond
          (nil? ticket)                         {:decision :ticket-unreadable}
          (= :name-held (:reason held))         {:decision :name-held}
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
              (ws/activate! p ws-id session)
              (when (notion-ref w) (write-start-properties! p page-id))
              (start-session! p session {:ws-id ws-id :carry? true :session-profile :full :wait false})
              {:decision :started :ws-id ws-id :session session}))))))

