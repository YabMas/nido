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
   [nido.platform.io :as io]))

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

(def Claude
  "The Claude Code background session a record is about, and who may give it its next
   instruction. Origin is kept for display and decides nothing; driver and hold are what nido
   reads before it sends. A record without this facet is a conversation from before the
   substrate, continued — if at all — the old way."
  [:map {:closed true}
   [:session-id string?]
   [:bg-id [:maybe string?]]
   [:origin [:enum :nido :person]]
   [:driver [:enum :nido :person :nobody]]
   ;; Set when a turn nido did not give is seen; only a hand-back clears it.
   [:hold [:maybe [:map {:closed true} [:at string?] [:because keyword?]]]]
   ;; The instruction given and not yet followed by the session's Stop. Its token is written
   ;; here BEFORE the instruction is delivered, so a prompt carrying it is known for nido's.
   [:pending [:maybe [:map {:closed true}
                      [:token string?] [:at string?] [:via [:enum :dispatch :send :wake]]
                      ;; The session's own prompt event carried the token: delivered, not yet ended.
                      [:seen? boolean?]]]]
   ;; One instruction waiting for the session to be ready for it.
   [:queued [:maybe string?]]
   ;; Seat messages waiting, oldest first — each given in turn once nothing is queued; none is
   ;; refused because another waits.
   [:inbox {:optional true} [:vector string?]]
   ;; The session's last Stop left work of its own running (background tasks, crons), so the
   ;; turn that follows is the session's own and not a person's.
   [:own-work? boolean?]
   [:policy [:map {:closed true} [:auto-respawn? boolean?]]]
   ;; How far conduct has read the session's event log (session-events/events-since).
   [:cursor int?]
   ;; Since when nido has been driving it: turns recorded before this never hold it.
   [:driving-since [:maybe string?]]
   ;; The current entry into blocked has been announced; cleared when the session leaves it.
   [:announced-blocked? boolean?]
   ;; What nido has seen of the session idle since it last began driving it or gave it anything:
   ;; nil, :stopped by a recorded Stop, or :listed done by the listing since that Stop. Only a
   ;; turn after such an idle can be one nido did not give.
   [:idle-seen {:optional true} [:maybe [:enum :stopped :listed]]]
   ;; The host boot recover! last restarted this session in, so it restarts it at most once.
   [:recovered-boot [:maybe string?]]])

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
   ;; A background session being started for this record and not yet named by Claude Code: the
   ;; first prompt's token, recorded before the start, and — once a start has launched a process
   ;; the listing did not name in time — its background id. Never beside a facet for long: the
   ;; facet is written, and this cleared, once the session's full id is known.
   [:dispatching       {:optional true} [:maybe [:map {:closed true}
                                                 [:token string?] [:at string?]
                                                 [:policy {:optional true} [:maybe [:map-of keyword? any?]]]
                                                 [:bg-id {:optional true} [:maybe string?]]]]]
   ;; The seat of its workstream this session occupies, and the generation it occupies it in.
   ;; A seat is never occupied across generations: a new generation's seats get new sessions.
   [:seat              {:optional true} [:map {:closed true} [:id string?] [:generation pos-int?]]]
   ;; The workstream's conductor, whatever the generation. Never also a seat's occupant.
   [:conductor         {:optional true} [:= true]]
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
(defn ^{:malli/schema [:=> [:cat :Session] [:maybe :map]]}
  claude-facet [s] (:claude s))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :SessionName [:=> [:cat :map] :map]] :Session]}
  update-substrate!
  "Replace a session's :claude facet and :dispatching note together with what `(f {:claude
   :dispatching})` answers — either nil to remove it — read and written under the record's lock,
   so a facet written and a note cleared are one step, and two updaters (a conduct pass and a
   hand-back) cannot lose each other's write. Throws when the session does not exist or the
   result is not valid, and writes nothing then."
  [project ws-id session-name f]
  (let [path (cstate/session-edn-path project ws-id session-name)]
    (io/update-edn! path
                    (fn [s]
                      (when-not s
                        (throw (ex-info "Session not found"
                                        {:project project :ws-id ws-id :session session-name})))
                      (let [{:keys [claude dispatching]} (f (select-keys s [:claude :dispatching]))]
                        (validate (shed (assoc s :claude claude :dispatching dispatching))))))))

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

