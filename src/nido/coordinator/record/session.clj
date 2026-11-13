(ns nido.coordinator.record.session
  "A work-episode against a workstream. Carries substrate state (live/archived),
   weight (light/heavy), and an OPTIONAL autonomy facet — the autonomous-session
   record. The session is the authoritative work + HITL record: it is
   self-sufficient for resume identity (:autonomy carries :claude-session-id +
   :limits). run.edn remains the execution-driver/substrate record that drives the
   FSM; reconcile! is the authoritative repair that re-syncs phase + id from it on
   startup. nil autonomy ⇒ a human session.
   See spec docs/superpowers/specs/2026-06-05-workstream-session-model-design.md."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [malli.core :as m]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.state :as cstate]
   [nido.platform.io :as io]
   [nido.platform.project :as project]
   [nido.session.state :as sstate]))

(def Autonomy
  [:map {:closed true}
   [:skill             keyword?]
   [:first-message     string?]
   [:agent             keyword?]
   [:claude-session-id [:maybe string?]]
   [:trigger           keyword?]
   [:limits            [:map-of keyword? any?]]
   [:priority          int?]
   [:uncapped?         boolean?]
   [:on-promote        [:maybe [:map-of keyword? any?]]]
   [:error             [:maybe [:map-of keyword? any?]]]])

(def Instruction
  "An instruction nido gave a session: its token is written BEFORE the instruction is delivered,
   so a prompt carrying it is known for nido's."
  [:map {:closed true}
   [:token string?] [:at string?] [:via [:enum :dispatch :send :wake]]
   ;; The session's own prompt event carried the token: delivered, not yet ended.
   [:seen? boolean?]
   ;; The Run it was given for — absent for an instruction no Run gave, whose turn end settles no
   ;; Run.
   [:for {:optional true} string?]
   ;; :start when the start that brought the session gave it — a person's start or a spawn's
   ;; brief, not the conductor's instruction — so the commit gate lets its turn take a person's act
   ;; the start asked for (an intent from a brief).
   [:given-by {:optional true} [:enum :start]]
   ;; The seat message it was, when it was one.
   [:message {:optional true} string?]])

(def Claude
  "The Claude Code background session a record drives — which conversation that is — and who may
   give it its next instruction. Origin is kept for display and decides nothing; the driver is
   what nido reads before it sends, and no prompt changes it. A record without this facet is a
   conversation nido does not drive.

   A facet written before a person's prompt stopped holding a session may still carry :hold,
   :idle-seen and :cursor, and a :driver of :person: they read, and nothing reads them — :person is
   read as :nobody, and the cursor only seeds the conversation's read position (`:reading`)."
  [:map {:closed true}
   [:session-id string?]
   [:bg-id [:maybe string?]]
   [:origin [:enum :nido :person]]
   ;; :retired once retire! stopped its process: the conversation is over, and nothing drives the
   ;; facet. It lasts until a conversation is driven on the record again — a start or an
   ;; adoption — and a release, which only changes who drives the facet it finds, leaves it
   ;; retired. :person is a legacy value, read as :nobody.
   [:driver [:enum :nido :person :nobody :retired]]
   [:hold {:optional true} [:maybe [:map {:closed true} [:at string?] [:because keyword?]]]]
   ;; The instruction given and not yet ended by a Stop of the conversation.
   [:pending [:maybe Instruction]]
   ;; Instructions that lapsed — no prompt carrying the token was recorded, and the conversation
   ;; stopped after it was given — and that a later instruction replaced as :pending. A Stop
   ;; whose turn carried one's token still ends it. The newest few are kept.
   [:lapsed {:optional true} [:vector Instruction]]
   ;; One instruction waiting for the session to be ready for it, and the Run it is for.
   [:queued [:maybe string?]]
   [:queued-for {:optional true} [:maybe string?]]
   ;; The session's last Stop left work of its own running (background tasks, crons), so nothing
   ;; is given it until that work is done.
   [:own-work? boolean?]
   [:policy [:map {:closed true} [:auto-respawn? boolean?]]]
   [:cursor {:optional true} int?]
   ;; Since when nido has been driving it, by dispatch or adoption.
   [:driving-since [:maybe string?]]
   ;; Its last Stop ended no instruction recorded for a Run: the turn end went to whoever answers
   ;; for the workstream — its conductor where it has one.
   [:ended-unrun? {:optional true} boolean?]
   ;; The current entry into blocked has been announced; cleared when the session leaves it.
   [:announced-blocked? boolean?]
   ;; The driven conversation's latest Stop: when, what the turn last said, and whether the turn
   ;; ended an instruction nido gave (false when only a person's prompts were in it). Absent until
   ;; conduct folds a Stop. What status's :why and a stall's escalation are read from.
   [:last-stop {:optional true} [:maybe [:map {:closed true}
                                         [:at string?]
                                         [:message {:optional true} [:maybe string?]]
                                         [:nido? boolean?]]]]
   ;; Since when the listing has shown the session stopped on a dialog (blocked, status waiting),
   ;; and what it said it waits for; nil once it is not. The first pass to see it sets the instant.
   [:waiting {:optional true} [:maybe [:map {:closed true}
                                       [:since string?]
                                       [:for {:optional true} [:maybe string?]]]]]
   [:idle-seen {:optional true} [:maybe [:enum :stopped :listed]]]
   ;; The host boot recover! last restarted this session in, so it restarts it at most once.
   [:recovered-boot [:maybe string?]]])

(def Reading
  "How far conduct has read the conversations of a session: the instant it began reading the
   session, and the read position (session-events/events-since's cursor) of each conversation of
   the session by its id — the one a facet drives among them. A conversation with no position is
   read from its first event recorded at or after :since."
  [:map {:closed true}
   [:since string?]
   [:positions [:map-of string? int?]]])

(def Session
  [:map {:closed true}
   [:name              string?]
   [:workstream-id     string?]
   [:project           keyword?]
   [:weight            [:enum :light :heavy]]
   [:substrate         [:enum :live :archived]]
   [:substrate-history [:vector [:map [:at string?] [:substrate [:enum :live :archived]]]]]
   [:autonomy          [:maybe Autonomy]]
   [:claude            {:optional true} [:maybe Claude]]
   [:reading           {:optional true} Reading]
   ;; A background session being started for this record and not yet named by Claude Code: the
   ;; first prompt's token, recorded before the start, and — once a start has launched a process
   ;; the listing did not name in time — its background id. Never beside a facet for long: the
   ;; facet is written, and this cleared, once the session's full id is known.
   [:dispatching       {:optional true} [:maybe [:map {:closed true}
                                                 [:token string?] [:at string?]
                                                 [:for {:optional true} string?]
                                                 [:given-by {:optional true} [:enum :start]]
                                                 [:policy {:optional true} [:maybe [:map-of keyword? any?]]]
                                                 [:bg-id {:optional true} [:maybe string?]]]]]
   ;; The seat of its workstream this session occupies, and the generation it occupies it in —
   ;; its id an element's, or the generation's number for its whole seat. A seat is never occupied
   ;; across generations: a new generation's seats get new sessions. :carried marks a session a
   ;; person's or a Run's start brought, which re-seating releases rather than archives.
   [:seat              {:optional true} [:map {:closed true}
                                         [:id [:or [:string {:min 1}] pos-int?]]
                                         [:generation pos-int?]
                                         [:carried {:optional true} [:= true]]]]
   ;; A carried session whose seat re-seating took away: it occupies none, and keeps saying its
   ;; workstream was carried.
   [:released          {:optional true} [:= true]]
   ;; The workstream's conductor, whatever the generation. Never also a seat's occupant.
   [:conductor         {:optional true} [:= true]]
   ;; The instruction and model nido last started a conversation on this record with, and who
   ;; gave it, so a start that failed is retried on what it was given rather than on a recipe
   ;; rebuilt without it.
   [:launched          {:optional true} [:map {:closed true}
                                         [:prompt string?]
                                         [:model {:optional true} [:maybe string?]]
                                         [:given-by {:optional true} [:enum :start]]]]
   [:created-at        string?]])

(defn- shed
  "`s` without the burst phase records written before a run's state was the only answer to where
   its agent is — so any rewrite of such a record leaves it holding no copy of what the run says."
  [s]
  (cond-> s (:autonomy s) (update :autonomy dissoc :phase :phase-history)))

(defn ^{:malli/schema [:=> [:cat :Session] :Session]}
  validate [s]
  (cond
    (not (m/validate Session s))
    (throw (ex-info "Invalid Session record"
                    {:errors (m/explain Session s) :session s}))
    (and (:seat s) (:conductor s))
    (throw (ex-info "A session record names a seat or the conductor, never both"
                    {:session (:name s) :seat (:seat s)}))
    :else s))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :SessionName] [:maybe :Session]]}
  read-session
  "Read a session.edn by project + ws-id + name. Returns nil if absent."
  [project ws-id session-name]
  (io/read-edn (cstate/session-edn-path project ws-id session-name)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:sequential :Session]]}
  list-sessions
  "Seq of session records under one workstream's sessions/ dir. Reads each
   session.edn directly (rather than re-deriving the path from the directory
   name) so a session whose name was percent-encoded into the dir key — e.g.
   'feat/foo' → 'feat%2Ffoo' — still round-trips via the record's :name."
  [project ws-id]
  (let [d (cstate/ws-sessions-dir project ws-id)]
    (if (fs/exists? d)
      (->> (fs/list-dir d)
           (filter fs/directory?)
           (map #(str (fs/path % "session.edn")))
           (keep io/read-edn-cached))
      [])))

(defn- list-ws-ids [project]
  (let [d (cstate/workstreams-dir project)]
    (if (fs/exists? d)
      (->> (fs/list-dir d) (filter fs/directory?) (mapv #(str (fs/file-name %))))
      [])))

(defn- holders
  "Every workstream of `project` holding a session record named `session-name`."
  [project session-name]
  (filterv (fn [ws-id] (some #(= session-name (:name %)) (list-sessions project ws-id)))
           (list-ws-ids project)))

(defn- names-lock-path
  "The lock every write that brings a session record into existence takes. One per project,
   because what it guards — which workstream holds a name — spans all of that project's
   workstreams. A hidden file, so the workstream listings, which take directories, never see it."
  [project]
  (str (fs/path (cstate/workstreams-dir project) ".session-names.lock")))

(defn ^{:malli/schema [:=> [:cat :Session] :Session]}
  write!
  "Persist a session, validated first.

   A write that brings a record into existence is refused — throwing `:reason :name-held`,
   naming the holder — while another workstream of the project holds a session of that name:
   a name has one workstream, so workstream-id-for has one answer. The check and the write are
   one step under the project's names lock, so two creations of a name cannot both find it free.
   A write over a record that already exists — a weight, a substrate, an error changing — is not
   a creation and takes no lock."
  [s]
  (let [s (validate (shed s))
        {:keys [project workstream-id]} s
        n    (:name s)
        path (cstate/session-edn-path project workstream-id n)]
    (if (fs/exists? path)
      (io/write-edn! path s)
      (io/with-file-lock (names-lock-path project)
        (fn []
          (when-let [held-by (first (remove #{workstream-id} (holders project n)))]
            (throw (ex-info (str "Session " n " already belongs to workstream " held-by
                                 " — a session name has one workstream in its project")
                            {:reason :name-held :session n :holder held-by
                             :ws-id workstream-id :project project})))
          (io/write-edn! path s))))
    s))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map] :Session]}
  create!
  "Persist a fresh :live session. `opts` carries :name, :weight, and :autonomy
   (nil for human sessions, or a full Autonomy map), and optionally :seat or :conductor.
   Reads via (:name opts) rather than destructuring to avoid shadowing clojure.core/name."
  [project ws-id opts]
  (let [now (clock/now-iso)]
    (write! (merge {:name              (:name opts)
                    :workstream-id     ws-id
                    :project           project
                    :weight            (:weight opts)
                    :substrate         :live
                    :substrate-history [{:at now :substrate :live}]
                    :autonomy          (:autonomy opts)
                    :created-at        now}
                   (select-keys opts [:seat :conductor])))))

(defn ^{:malli/schema [:=> [:cat :Session] :boolean]}
  live?       [s] (= :live (:substrate s)))
(defn ^{:malli/schema [:=> [:cat :Session] :boolean]}
  autonomous? [s] (some? (:autonomy s)))
(defn ^{:malli/schema [:=> [:cat :Session] :boolean]}
  person?
  "Whether `s` is a session a person works in: neither its workstream's conductor, nor a Run's,
   nor an occupant nido created to fill a seat. A seat not marked :carried is how such an occupant
   is told: a start that brings a session marks its seat :carried, and nothing else seats a record
   but occupy! on one it created."
  [s]
  (not (or (:conductor s) (autonomous? s) (and (:seat s) (not (get-in s [:seat :carried]))))))
(defn ^{:malli/schema [:=> [:cat :Session] [:maybe :map]]}
  claude-facet [s] (:claude s))

;; ── whether a person is at work ─────────────────────────────────────────────

(def worked-window-ms
  "How long a person's prompt keeps the session it was submitted in worked: a day."
  (* 24 60 60 1000))

(def ^:private delivered
  "What marks a prompt no person typed: nido's tag on everything it sends, and the wrappers the
   harness puts round what another agent delivers."
  #"\[nido-send |<cross-session-message|<agent-message|<task-notification|\[Cross-session delivery notice\]")

(defn ^{:malli/schema [:=> [:cat :map] :boolean]}
  person-prompt?
  "Whether recorded prompt `p` ({:prompt …}) was typed by a person: it carries neither nido's tag
   nor a harness wrapper for another agent's message."
  [p]
  (not (re-find delivered (str (:prompt p)))))

(defn- real [p] (try (str (fs/real-path p)) (catch Exception _ nil)))

(defn- session-dirs
  "Every directory a session's conversations run in — its home, and the worktree the home links
   to — as {dir #{[project session-name] …}}. Read off the homes under sessions-root; a name may
   hold one slash (explore/x). A worktree that is a project's own checkout — what a :lite session
   links to — is no session's: every conversation in that checkout would otherwise be counted for
   whichever session linked it."
  []
  (let [root      (sstate/sessions-root)
        checkouts (into #{} (keep (comp real :directory val)) (project/list-projects))]
    (if-not (fs/directory? root)
      {}
      (apply merge-with into {}
             (for [marker (mapcat #(fs/glob root %) ["*/*/worktree" "*/*/*/worktree" "*/*/CLAUDE.md" "*/*/*/CLAUDE.md"])
                   :let   [home  (fs/parent marker)
                           [project & parts] (map str (fs/relativize root home))
                           owner [project (str/join "/" parts)]
                           wt    (real (fs/path home "worktree"))]
                   dir    (cond-> [(str home)] (and wt (not (checkouts wt))) (conj wt))]
               {dir #{owner}})))))

(defn- owner-of
  "A function from a directory to the session that runs in it, as [project session-name] — the
   session whose home, or whose worktree, holds it, the deepest such directory — or nil. A
   directory more than one session runs in (a :lite worktree is the project's own checkout) is
   no one's."
  []
  (let [dirs (session-dirs)]
    (fn [cwd]
      (let [[_ owners] (->> dirs
                            (filter (fn [[d _]] (or (= cwd d) (str/starts-with? (str cwd) (str d "/")))))
                            (sort-by (comp - count key))
                            first)]
        (when (= 1 (count owners)) (first owners))))))

(defn ^{:malli/schema [:=> [:cat [:sequential :map]] [:set [:tuple :string :string]]]}
  prompted-sessions
  "The sessions a person typed one of `prompts` ({:cwd :prompt …}) in, as #{[project
   session-name]}: a prompt counts for the session whose home, or whose worktree, holds the
   directory it was submitted in — the deepest such directory. A directory more than one session
   runs in (a :lite worktree is the project's own checkout) names no one, so a prompt there
   counts for none."
  [prompts]
  (let [typed (filter person-prompt? prompts)]
    (if (empty? typed)
      #{}
      (let [owner (owner-of)] (into #{} (keep (comp owner :cwd)) typed)))))

(defn ^{:malli/schema [:=> [:cat [:sequential :map]] [:map-of [:tuple :string :string] [:set :string]]]}
  conversation-sessions
  "The conversations of each session among `conversations` ({:id :cwd …}, as
   session-events/conversations answers them), as {[project session-name] #{id …}}: a
   conversation belongs to the session whose home, or whose worktree, holds its recorded cwd —
   the deepest such directory, as `prompted-sessions` counts a prompt. One in a directory no
   session, or more than one, runs in belongs to none."
  [conversations]
  (if (empty? conversations)
    {}
    (let [owner (owner-of)]
      (reduce (fn [m {:keys [id cwd]}]
                (if-let [o (when cwd (owner cwd))] (update m o (fnil conj #{}) id) m))
              {} conversations))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :SessionName [:=> [:cat :map] :map]] :Session]}
  update-substrate!
  "Replace a session's :claude facet, :dispatching note and :reading with what `(f {:claude
   :dispatching :reading})` answers, read and written under the record's lock, so a facet written,
   a note cleared and a read position moved are one step, and two updaters (a conduct pass and a
   start) cannot lose each other's write. A :claude or :dispatching the answer leaves nil or out
   is removed; a :reading it leaves out is kept, so no updater that is not reading the session's
   conversations can drop where they were read to. Throws when the session does not exist or the
   result is not valid, and writes nothing then."
  [project ws-id session-name f]
  (let [path (cstate/session-edn-path project ws-id session-name)]
    (io/update-edn! path
                    (fn [s]
                      (when-not s
                        (throw (ex-info "Session not found"
                                        {:project project :ws-id ws-id :session session-name})))
                      (let [r       (f (select-keys s [:claude :dispatching :reading]))
                            reading (if (contains? r :reading) (:reading r) (:reading s))]
                        (validate (shed (cond-> (-> s
                                                    (assoc :claude (:claude r) :dispatching (:dispatching r))
                                                    (dissoc :reading))
                                          reading (assoc :reading reading)))))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :SessionName [:=> [:cat [:maybe :map]] [:maybe :map]]] :Session]}
  update-claude!
  "Replace a session's :claude facet with `(f facet)` — nil to remove it — under the record's
   lock, as update-substrate! does; the :dispatching note is left as it is."
  [project ws-id session-name f]
  (update-substrate! project ws-id session-name
                     (fn [m] (update m :claude f))))

(defn- load! [project ws-id session-name]
  (or (read-session project ws-id session-name)
      (throw (ex-info "Session not found"
                      {:project project :ws-id ws-id :session session-name}))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :SessionName] :Session]}
  archive!
  "Flip a session to :archived, appending substrate-history. Idempotent (no
   duplicate history entry when already archived). Returns updated record."
  [project ws-id session-name]
  (let [s (load! project ws-id session-name)]
    (if (= :archived (:substrate s))
      s
      (write! (-> s
                  (assoc :substrate :archived)
                  (update :substrate-history conj
                          {:at (clock/now-iso) :substrate :archived}))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :SessionName [:maybe :map]] :Session]}
  set-error!
  "Set (nil clears) the last-resume error on an autonomous session's autonomy
   facet. Throws if the session has no autonomy facet (a human session has none).
   Returns the updated record."
  [project ws-id session-name err]
  (let [s (load! project ws-id session-name)]
    (when-not (autonomous? s)
      (throw (ex-info "Cannot set error on a human session"
                      {:project project :ws-id ws-id :session session-name})))
    (write! (assoc-in s [:autonomy :error] err))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :SessionName [:maybe :string]] :Session]}
  set-claude-session-id!
  "Persist the resumable claude conversation id onto an autonomous session's
   autonomy facet (the run captures it mid-burst; mirroring it here makes the
   session self-sufficient for resume). Throws on a human session. Idempotent."
  [project ws-id session-name id]
  (let [s (load! project ws-id session-name)]
    (when-not (autonomous? s)
      (throw (ex-info "Cannot set claude-session-id on a human session"
                      {:project project :ws-id ws-id :session session-name})))
    (write! (assoc-in s [:autonomy :claude-session-id] id))))

(def lifecycle-stages
  "The stage keywords the board groups by. A stage choice — a :stage-set or :reopened entry —
   places a workstream ONLY when it names one of these (`standing/chosen-stage`); a choice of
   any other stage hands the band back to where status places the work. :incoming is the
   pre-triage queue stage (session-less, awaiting a human promote/dismiss). :shipping is the
   merge-pipeline stage (chosen by the ship handler, never derived)."
  #{:incoming :triage :ready :in-progress :shipping :done})

(def marker-stages
  "Stages a workstream may be set to that deliberately place nothing: :triaging (create!'s
   default — the workstream has been minted and its band is wherever status places it) and
   :scratch (a one-off's, though what marks one is its :scratch birth entry). Kept out of
   lifecycle-stages on purpose; kept legal to write, because both are written by nido itself."
  #{:triaging :scratch})

(def storable-stages
  "The closed vocabulary a workstream's :stage may be SET to — the union of the
   stages the board honors as an override and the markers that ride alongside
   them. Enforced by workstream/create! and workstream/advance-stage!.

   Wider than lifecycle-stages, and the difference is the whole point: a marker
   stage is legal but places nothing, so the two sets must not be conflated.
   Anything outside this set would be silently accepted and then silently ignored —
   a workstream set to, say, :implementing (a TICKET status, not a stage) would land
   back in the triage queue with no indication anything had gone wrong. :inbox — the
   pen's name before it was :incoming — is absent by design: nothing may choose it."
  (into lifecycle-stages marker-stages))

(def notion-terminal-statuses
  "Notion statuses that are done from nido's side → off the board. 'Review' is a
   human-review handoff: nido's active involvement is over (self-heals if bounced
   back to In progress, since this projection is stateless)."
  #{"Done" "Not Done" "Review"})

(def notion-in-progress-statuses
  "Notion statuses that map to the :in-progress band."
  #{"In progress" "Code Review"})

(defn ^{:malli/schema [:=> [:cat :any :boolean] :keyword]}
  notion-stage
  "Board stage from a Notion ticket's live status + the local triage disposition.
   Pre-implementation statuses (e.g. Needs verification / Not started / On Hold /
   nil / other) split on `triaged?` — ticket status :triaged, the ONLY disposition
   promote accepts — so :ready on the board means promotable. A ticket with an old
   triage report but a non-:triaged status (re-opened → :investigating, or dismissed)
   is :triage, not :ready: it isn't promotable yet."
  [notion-status triaged?]
  (cond
    (contains? notion-terminal-statuses notion-status)    :done
    (contains? notion-in-progress-statuses notion-status) :in-progress
    triaged?                                              :ready
    :else                                                 :triage))

(defn ^{:malli/schema [:=> [:cat :ProjectName :SessionName] [:maybe :WorkstreamId]]}
  workstream-id-for
  "The workstream holding the session named `session-name` in `project`, or nil when none does.
   Matches on the record's :name, which round-trips a percent-encoded directory key.

   Throws `:reason :name-held-twice`, naming the holders, for a name two workstreams hold — which
   write! leaves no way to reach, so it can only be a record written before the rule. It never
   answers one of several: a verb acting on whichever holder happened to list first is acting on
   a workstream nobody chose."
  [project session-name]
  (let [hs (holders project session-name)]
    (case (count hs)
      0 nil
      1 (first hs)
      (throw (ex-info (str "Session " session-name " is held by " (count hs) " workstreams: "
                           (str/join ", " hs))
                      {:reason :name-held-twice :session session-name :holders hs :project project})))))

