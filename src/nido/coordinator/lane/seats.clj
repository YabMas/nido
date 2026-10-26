(ns nido.coordinator.lane.seats
  "Who occupies each seat of a workstream, and what a new occupant is told.

   HIDES the occupancy policy, for any embodiment — an embodiment being how a seat's session is
   named and its occupant briefed ({:seat-name (fn [seat-id] slug) :practice text :refuse (fn
   [project ws-id])}), and what it refuses to carry; it selects nothing, since the seats are read
   by record.seats. Its refusal is run inside every occupancy transaction, against the generation
   that transaction seats. The whole seat a generation fixing no ids has is carried by this
   namespace's own `default-embodiment`; one occupancy policy serves it and every other.

   Every seat of a workstream an embodiment carries is occupied by a driven background session
   of its own, on the workstream's one runtime; the conductor is a separate session that occupies
   no seat. An occupant is a session record naming its seat and the generation it occupies it in,
   and occupying, replacing and reading occupants read the current generation's records only, so
   a seat whose element survives a re-cut never takes up a retired occupant. Re-seating retires
   every occupant whose seat the seats reading no longer answers — its Claude session stopped and
   its record archived, save one carried from a person's or a Run's start, which is released to
   them instead — and the conductor's continues. The conductor is retired only with its
   workstream, its record kept, and started again on the same record if the workstream reopens.

   A first brief is built from records — the seat, its interface, its open tensions, the accords
   that settled tensions it was party to, and what the generation inherited — never from another
   session's account. Seat messages are logged per workstream beside the ledger, keyed by
   generation, and the log is all there is of them: what a seat or the conductor is still owed is
   derived from what the log addresses to it and the deliveries it records, given by conduct to
   whichever session holds the seat or conducts at the time (`owed`, `delivered!`). nido's own
   messages — a hand-back to the conductor, a decided instruction to a seat — are logged once per
   key (`post!`). No round reads the log.

   Who wrote a :tension, :accord, :withdrawal or :recut is stamped by record! from the session
   it runs in, never stated by the caller: a seat's occupant as its seat, the conductor as the
   conductor, and — for a :recut alone — any other session by its name, or a person outside every
   session. A seat's commits and PR titles name its seat and its position among the seat's
   layers — a `Seat: <seat> <n>/<m>` trailer, a `[<seat> <n>/<m>]` title — and gate-verdict
   refuses a publication that does not."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.coordinator.lane.conduct :as conduct]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.generation :as generation]
   [nido.coordinator.record.seats :as seats]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.platform.io :as io]
   [nido.platform.project :as project]
   [nido.session.commit-gate :as commit-gate]
   [nido.session.launcher :as launcher]
   [nido.session.lifecycle :as lifecycle]))

;; ── reading who is where ────────────────────────────────────────────────────

(defn- current-generation [project ws-id]
  (generation/current (:entries (ws/read-ws project ws-id))))

(defn- live-records [project ws-id]
  (filter session/live? (session/list-sessions project ws-id)))

(defn- occupants-in [project ws-id g]
  (->> (live-records project ws-id)
       (filter #(= g (get-in % [:seat :generation])))
       (map (fn [s] {:seat (get-in s [:seat :id]) :session (:name s)}))
       (sort-by :seat)
       vec))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:sequential :map]]}
  occupants
  "Each seat's live occupant in the current generation, as {:seat :session}, sorted by seat.
   An earlier generation's occupant is never one, even of a seat whose element survived."
  [project ws-id]
  (occupants-in project ws-id (current-generation project ws-id)))

(defn- started?
  "Whether record `s` holds a session — one driven on it and not retired since
   (`conduct/retire!` makes its facet's driver :retired, and whatever drives it again writes its
   own), or a start of it in flight — as against a record written for a launch that failed, or
   whose session was retired before a launch that failed, which a retry may start on."
  [s]
  (boolean (or (and (:claude s) (not= :retired (:driver (:claude s)))) (:dispatching s))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:set :SeatId]]}
  held
  "The current generation's seats whose occupant holds a session (`started?`) — not those whose
   occupant's record is all a failed launch left, which occupying again starts."
  [project ws-id]
  (into #{} (keep #(when (started? (session/read-session project ws-id (:session %))) (:seat %)))
        (occupants project ws-id)))

(defn- conductor-name [ws-id] (str ws-id "--conductor"))

(defn- conductor-record
  "The workstream's one conductor record, whatever state its session is in — or nil."
  [project ws-id]
  (some-> (session/read-session project ws-id (conductor-name ws-id))
          (as-> s (when (:conductor s) s))))

(defn- here
  "The directory the calling process runs in — where a writer is stamped from."
  []
  (System/getProperty "user.dir"))

(defn- session-at
  "[project session-name] of the nido session `cwd` belongs to — its worktree or its home — or
   nil."
  [cwd]
  (or (when-let [{:keys [project session]} (lifecycle/session-from-cwd cwd)]
        [(keyword project) session])
      (when-let [[p n] (lifecycle/session-home-coords-from-cwd cwd)]
        [(keyword p) n])))

(defn- writer
  "Who a record or message written from `cwd` is by, on `ws-id`: a seat's occupant as its seat,
   the conductor as the conductor, any other nido session by its name, and a person outside every
   session. A seat's occupant of an earlier generation writes as the session it is, not the seat.
   The generation is read from `w`, the workstream as the caller holds it under its append lock,
   so no generation opens between the stamp and what is done under it."
  [project ws-id cwd w]
  (let [[p n] (session-at cwd)
        s (when (and n (= (keyword project) p)) (session/read-session project ws-id n))
        g (generation/current (:entries w))]
    (cond
      (and s (:conductor s))                        {:as :conductor :session n}
      (and s (= g (get-in s [:seat :generation])))  {:as :seat :seat (get-in s [:seat :id]) :session n}
      n                                             {:as :session :session n}
      :else                                         {:as :person})))

;; ── records and messages ────────────────────────────────────────────────────

(def ^:private stamped-kinds #{:tension :accord :withdrawal :recut})

(defn- addressable?
  "Whether `to` names a seat the seats reading answers, or the conductor by role."
  [project ws-id to]
  (or (= :conductor to) (boolean (some #(= to (:id %)) (seats/seats project ws-id)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map] :map]}
  record!
  "Append `entry` — a :tension, :accord, :withdrawal or :recut, without :by — to the workstream's
   ledger, its writer stamped from where this runs. Refuses a stated :by, any other kind, and a
   :tension, :accord or :withdrawal from anyone but a seat's occupant or the conductor. Answers
   the entry as appended."
  [project ws-id entry]
  (let [kind    (:format entry)
        cwd     (here)
        stamped (volatile! nil)]
    (cond
      (not (stamped-kinds kind))
      (throw (ex-info (str "record! appends a :tension, :accord, :withdrawal or :recut, not " kind)
                      {:reason :kind :format kind}))
      (contains? entry :by)
      (throw (ex-info "record! stamps the writer from where it runs — drop :by"
                      {:reason :stated-writer})))
    ;; The writer is resolved from the workstream the append reads under its lock: resolved
    ;; before it, a generation opened in between would take an old occupant's entry as its seat's.
    (ws/append-stamped!
     project ws-id
     (fn [w]
       (let [by (writer project ws-id cwd w)]
         (when (and (not= :recut kind) (not (#{:seat :conductor} (:as by))))
           (throw (ex-info (str "Only a seat's occupant or the conductor writes a " (name kind)
                                " — this runs " (if (:session by) (str "in " (:session by)) "outside every session"))
                           {:reason :not-in-ensemble :by by})))
         (when (and (= :tension kind) (not (addressable? project ws-id (:to entry))))
           (throw (ex-info (str "A tension is addressed to a seat of the current generation or to the "
                                "conductor, not " (pr-str (:to entry)))
                           {:reason :no-addressee :to (:to entry)})))
         (vreset! stamped (assoc entry :by by)))))
    @stamped))

(defn- log-path [project ws-id]
  (str (fs/path (cstate/workstream-dir project ws-id) "seat-messages.edn")))

(defn- log-rows
  "The log as written: a message is {:id :generation :from :to :text :at}, a delivery
   {:delivered <message id> :at}."
  [project ws-id]
  (or (io/read-edn (log-path project ws-id)) []))

(defn- with-deliveries
  "The log's messages, oldest first, each with :deliveries — its recorded deliveries, oldest
   first, each {:token :conversation :at} — and :delivered? whether it has any."
  [rows]
  (let [by-id (group-by :delivered (filter :delivered rows))]
    (into [] (comp (filter :text)
                   (map (fn [m] (let [ds (mapv #(select-keys % [:token :conversation :at]) (by-id (:id m)))]
                                  (assoc m :deliveries ds :delivered? (boolean (seq ds)))))))
          rows)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :int] [:sequential :map]]}
  traffic
  "Generation `generation`'s seat messages, oldest first, each {:id :generation :from :to :text
   :at :delivered? :deliveries} — a delivered one with the token, and the conversation, it was
   given under."
  [project ws-id generation]
  (filterv #(= generation (:generation %)) (with-deliveries (log-rows project ws-id))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :string] :boolean]}
  handed-back-since?
  "Whether a hand-back to the conductor was logged on the workstream at or after `since`, an ISO
   instant — read from the message log alone, with no ledger read."
  [project ws-id since]
  (boolean (some #(and (= :hand-back (:kind %)) (not (neg? (compare (str (:at %)) since))))
                 (log-rows project ws-id))))

(defn- describe [{:keys [as seat]}]
  (case as
    :seat (if (int? seat) "the whole seat" (str "seat " seat))
    :nido "nido"
    "the conductor"))

(defn- addressee
  "What the session record `s` is addressed as in generation `g`: :conductor, its seat's id when
   it occupies that seat in `g`, else nil."
  [s g]
  (cond
    (not (session/live? s))               nil
    (:conductor s)                        :conductor
    (= g (get-in s [:seat :generation]))  (get-in s [:seat :id])))

(defn- answered-hand-backs
  "The ids of the hand-backs a :conduct-decision on the workstream answers."
  [project ws-id]
  (into #{} (map :hand-back) (ws/entries-of project ws-id :conduct-decision)))

(defn- owed-to-conductor?
  "Whether the conductor, whose running conversation is `conversation`, is owed message `m` of
   workstream `w`: one addressed to it in any generation — so a generation's opening drops none —
   or to the current generation's whole seat once that generation fixes ids. Owed while no
   delivery is recorded; a hand-back no decision answers is owed until its running conversation
   was given it, so one a stopped conversation took is owed again."
  [m w answered conversation]
  (let [g (generation/current (:entries w))]
    (and (or (= :conductor (:to m))
             (and (= g (:to m)) (seq (generation/fixed-ids w))))
         (if (and (= :hand-back (:kind m)) (not (answered (:id m))))
           (not-any? #(and conversation (= conversation (:conversation %))) (:deliveries m))
           (not (:delivered? m))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :SessionName] [:sequential :map]]}
  owed
  "The seat messages the session `session-name` is owed, oldest first, each {:id :text} with its
   sender named in the text: for a seat's occupant, those the log addresses to the seat it
   occupies in the current generation with no delivery recorded; for the conductor, those
   `owed-to-conductor?` says. Derived each time, so a replaced occupant's successor is owed what
   it was, and a session that is no seat's or conductor's is owed nothing. Empty for a workstream
   or record not there."
  [project ws-id session-name]
  (let [s  (session/read-session project ws-id session-name)
        w  (ws/read-ws project ws-id)
        g  (some-> w :entries generation/current)
        to (when (and s g) (addressee s g))
        as-owed (map (fn [{:keys [id from text]}]
                       {:id id :text (str "[seat message from " (describe from) "]\n\n" text)}))]
    (cond
      (nil? to) []
      (= :conductor to)
      (let [answered     (answered-hand-backs project ws-id)
            conversation (get-in s [:claude :session-id])]
        (into [] (comp (filter #(owed-to-conductor? % w answered conversation)) as-owed)
              (with-deliveries (log-rows project ws-id))))
      :else
      (into [] (comp (filter #(and (= to (:to %)) (not (:delivered? %)))) as-owed)
            (traffic project ws-id g)))))

(defn- conversation-given
  "The conversation of the workstream's session whose outstanding instruction carries `token` —
   the one a delivery under it went to — or nil."
  [project ws-id token]
  (some #(when (= token (get-in % [:claude :pending :token])) (get-in % [:claude :session-id]))
        (session/list-sessions project ws-id)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :string :string] :any]}
  delivered!
  "Record that the seat message `id` was given to its addressee's session, under `token` and to
   the conversation whose outstanding instruction carries it; it is owed no more — save a
   hand-back no decision answers, owed again to a later conductor conversation."
  [project ws-id id token]
  (io/update-edn! (log-path project ws-id) (fnil conj [])
                  {:delivered id :token token :conversation (conversation-given project ws-id token)
                   :at (clock/now-iso)}))

(defn- log!
  "Log message `msg` — {:to :text}, plus whatever the caller adds — under the current generation of
   `w`, addressed as `addressable?` allows. Answers the logged message."
  [project ws-id w msg]
  (let [g (generation/current (:entries w))]
    (when-not (addressable? project ws-id (:to msg))
      (throw (ex-info (str "Generation " g " has no seat " (pr-str (:to msg)))
                      {:reason :no-addressee :to (:to msg)})))
    (let [logged (merge {:id (str (random-uuid)) :generation g :at (clock/now-iso)} msg)]
      (io/update-edn! (log-path project ws-id) (fnil conj []) logged)
      logged)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map] :map]}
  send!
  "Send {:to <seat id | :conductor> :text} from the seat's occupant this runs in: log it under the
   current generation, addressed to a seat the seats reading answers or to the conductor by role,
   and attributed to its sender. Conduct gives it to whichever session holds the seat, or
   conducts, once one is ready (`owed`); none need be live when it is sent. Refused from anyone
   else — the conductor included, whose word reaches a seat only as a decided instruction
   (`post!`) — and to any other addressee. Answers the logged message."
  [project ws-id {:keys [to text]}]
  (let [cwd (here)]
    ;; Sender, generation and addressee from one read, held under the lock a generation's opening
    ;; takes until the message is logged: none of them can change in between.
    (ws/holding-ledger
     project ws-id
     (fn [w]
       (let [from (writer project ws-id cwd w)]
         (when (= :conductor (:as from))
           (throw (ex-info "The conductor instructs a seat by deciding a hand-back (bb nido:seats:decide), not by a seat message"
                           {:reason :conductor-sends :from from})))
         (when-not (= :seat (:as from))
           (throw (ex-info "Only a seat's occupant sends a seat message"
                           {:reason :not-in-ensemble :from from})))
         (log! project ws-id w {:from from :to to :text text}))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :Workstream :map] :map]}
  post-held!
  "post! for a caller already holding the workstream's append lock, `w` read under it — so a
   ledger append the caller makes under the same lock and the posts it carries out are one step,
   which no generation's opening or design approval can fall between."
  [project ws-id w {:keys [key] :as msg}]
  (or (first (filter #(= key (:key %)) (log-rows project ws-id)))
      (log! project ws-id w (assoc msg :from {:as :nido}))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map] :map]}
  post!
  "Log a message nido writes — {:key :to :text :kind}, a :hand-back to the conductor or an
   :instruction to a seat, plus what its kind carries — addressed as send! addresses, at most
   once per `:key`: a second post under a key already logged logs nothing and answers the first.
   Answers the logged message."
  [project ws-id msg]
  (ws/holding-ledger project ws-id (fn [w] (post-held! project ws-id w msg))))

;; ── briefs ──────────────────────────────────────────────────────────────────

(defn- party?
  "Whether `seat-id` raised the tension or was addressed by it."
  [seat-id t]
  (or (= seat-id (:to t)) (= seat-id (get-in t [:by :seat]))))

(defn- brief
  "An occupant's first prompt, built from the records: its seat, the seat's interface, the open
   tensions addressed to it, the settled tensions it was party to, what the generation inherited,
   and the embodiment's practice."
  [project ws-id embodiment seat-id]
  (let [seat      (first (filter #(= seat-id (:id %)) (seats/seats project ws-id)))
        settled   (filter #(and (not= :open (:state %)) (party? seat-id %)) (seats/tensions project ws-id))
        inherited (seats/inherited project ws-id)
        settling  (fn [t] (when-let [s (:settled-by t)]
                            (let [e (ws/entry-at-seq project ws-id s)]
                              (str " (" (if (= :accorded (:state t)) "accorded" "withdrawn") " by #" s
                                   (when-let [said (or (:terms e) (:note e))] (str ": " said)) ")"))))
        lines     (fn [ts] (str/join "\n" (map #(str "- #" (:seq %) " " (pr-str (:about %)) ": " (:statement %)
                                                     (settling %))
                                           ts)))]
    (str (if (:whole seat)
           (str "You occupy the whole seat of workstream " ws-id ", generation " seat-id
                ": no design of it marks elements, so you carry its whole design.\n\n"
                "Its design's shape: " (or (:interface seat) "no design is written yet") "\n\n")
           (str "You occupy seat " seat-id " of workstream " ws-id
                ", generation " (current-generation project ws-id) ".\n\n"
                "Its interface: " (or (:interface seat) "not yet stated by a design of this generation") "\n\n"))
         (when (seq (:tensions seat)) (str "Tensions open against it:\n" (lines (:tensions seat)) "\n\n"))
         (when (seq settled) (str "Tensions it was party to, settled:\n" (lines settled) "\n\n"))
         (when inherited
           (str "This generation was opened from re-cut #" (get-in inherited [:recut :seq])
                ": " (:words inherited) "\nIt builds from revision " (:base-rev inherited) ".\n"
                (when-let [fs (seq (:falsified inherited))]
                  (str "Claims the generation before saw falsified:\n"
                       (str/join "\n" (map #(str "- " (:claim %) ", falsified by " (:by %)) fs)) "\n"))
                (when-let [ts (seq (:tensions inherited))]
                  (str "Tensions carried in as its cause:\n"
                       (str/join "\n" (map #(str "- " (describe (:by %)) " to "
                                                  (if (= :conductor (:to %)) "the conductor" (str "seat " (:to %)))
                                                  " " (pr-str (:about %)) ": " (:statement %))
                                            ts))
                       "\n"))
                "\n"))
         (:practice embodiment) "\n\n"
         (if (:whole seat)
           "Raise a tension or message the conductor with `bb nido:seats:record` and `bb nido:seats:send`."
           (str "Raise a tension, accord or withdraw with `bb nido:seats:record`, and message another seat "
                "or the conductor with `bb nido:seats:send`. Name your seat and your position among its "
                "layers in every commit (`Seat: " seat-id " <n>/<m>`) and PR title (`[" seat-id " <n>/<m>] …`).")))))

;; ── starting sessions ───────────────────────────────────────────────────────

(defn- refuse-closed!
  "Throw (:closed) when the workstream is closed: nido starts no driven session on a settled
   workstream, nor writes a record for one."
  [project ws-id]
  (when (some-> (ws/read-ws project ws-id) ws/closure)
    (throw (ex-info (str "Workstream " ws-id " is closed: nido starts no session on it")
                    {:reason :closed :ws-id ws-id}))))

(defn- launch!
  "Bring the session `session-name` up on the workstream's runtime — unless `up?` is false, for a
   session its own start already brought up — and start a background session nido drives in its
   own working copy, on `prompt`, given for no Run, on `model` (nil: claude's default), recording
   both on its record (:launched) first. Refused (:closed) on a closed workstream: every start of
   a driven session comes through here. Answers the facet."
  ([project ws-id session-name prompt] (launch! project ws-id session-name prompt {:up? true}))
  ([project ws-id session-name prompt {:keys [up? model]}]
  (refuse-closed! project ws-id)
  (session/write! (assoc (session/read-session project ws-id session-name)
                         :launched (cond-> {:prompt prompt} model (assoc :model model))))
  (let [pname (name project)
        opts  {:project pname :runtime-key ws-id}]
    (when up? (lifecycle/up! session-name opts))
    (let [instance-id (:instance-id (lifecycle/runtime-of session-name opts))
          mcp         (launcher/session-mcp-path pname session-name)]
      (conduct/start! {:project       project :ws-id ws-id :session-name session-name
                       :name          session-name
                       :cwd           (str (:wt-path (lifecycle/session-coords session-name opts)))
                       :source        (get-in (project/list-projects) [pname :directory])
                       :prompt        prompt
                       :model         model
                       :system-prompt (launcher/session-briefing pname session-name instance-id)
                       :mcp-config    (when (and mcp (fs/exists? mcp)) mcp)
                       :add-dirs      (launcher/nido-add-dirs)})))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :SessionName :string [:? [:maybe :string]]] :any]}
  drive!
  "Start a background session nido drives on the live record `session-name`, already up, on
   `prompt` given for no Run and on `model` when given, occupying no seat — so its turn ends are
   the conductor's. Refused as conduct refuses a record whose session is running or starting. For
   a start that brought a session up and could not carry it."
  [project ws-id session-name prompt & [model]]
  (launch! project ws-id session-name prompt {:up? false :model model}))

(defn- occupancy-lock-path [project ws-id]
  (str (fs/path (cstate/workstream-dir project ws-id) ".occupancy.lock")))

(defn- serially
  "Run `f` holding the workstream's occupancy lock and, inside it, its ledger's append lock — the
   one a generation's opening takes — so reading who occupies what in which generation, writing
   the record and starting its session are one step against every other occupancy verb and
   against an opening: no occupant is launched in a generation that has closed. Always in that
   order, occupancy then ledger; nothing takes the occupancy lock while holding the ledger's."
  [project ws-id f]
  (io/with-file-lock (occupancy-lock-path project ws-id)
                     #(ws/holding-ledger project ws-id (fn [_] (f)))))

(defn- retire-unseated!
  "Retire every live seat occupant whose seat the current seats reading does not answer — one of
   an earlier generation, or the whole seat's once its generation fixes ids. An occupant a
   person's or a Run's start brought (:carried) is released: its record stops naming the seat and
   is marked :released, and it stays its person's or its Run's — nido stops driving a person's,
   and leaves a Run's driver and instructions as they are. Any other has its Claude session
   stopped and its record archived, its working copy kept. Answers their names, in name order.
   Run under the occupancy lock, by reseat! and again before any seat is occupied or its occupant
   replaced, so a retirement that failed completes before a new occupant starts."
  [project ws-id]
  (let [g     (current-generation project ws-id)
        ids   (set (map :id (seats/seats project ws-id)))
        stale (->> (live-records project ws-id)
                   (filter #(and (:seat %)
                                 (not (and (= g (get-in % [:seat :generation]))
                                           (ids (get-in % [:seat :id]))))))
                   (sort-by :name))]
    (doseq [s stale]
      (if (get-in s [:seat :carried])
        (do (when (and (:claude s) (not (session/autonomous? s))) (conduct/release! project ws-id (:name s)))
            (session/write! (-> (session/read-session project ws-id (:name s)) (dissoc :seat) (assoc :released true))))
        (do (conduct/retire! project ws-id (:name s) {})
            (session/archive! project ws-id (:name s)))))
    (mapv :name stale)))

(defn- seat-session-name [ws-id embodiment seat-id g]
  (str ws-id "--" ((:seat-name embodiment) seat-id) "-g" g))

(defn- driven?
  "Whether nido drives a conversation on record `s` now, or a start of one is in flight."
  [s]
  (boolean (or (:dispatching s) (= :nido (get-in s [:claude :driver])))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map :SeatId [:? :SessionName]] :map]}
  occupy!
  "Start a driven session for `seat-id`, a seat of the current generation with no occupant in it,
   briefed from the records, once every occupant whose seat is gone is retired — a retirement
   that fails throws, and nothing is started. The session is the embodiment's, on a record it
   names; or, when the caller names `session-name` — a live session record of the workstream that
   occupies no seat and is not its conductor — that record, which then occupies the seat as one
   carried from its start, started on the brief unless nido already drives it. Refused as the
   embodiment refuses the workstream (`:refuse`, read against the generation it seats), for an id
   that is not a seat and for a seat already occupied — a session started on its record, or a
   start in flight; a record left by a launch that failed is started on again. Answers {:seat
   :session}."
  [project ws-id embodiment seat-id & [session-name]]
  (serially
   project ws-id
   (fn []
     (retire-unseated! project ws-id)
     (refuse-closed! project ws-id)
     ((:refuse embodiment) project ws-id)
     (let [g (current-generation project ws-id)]
       (when-not (some #(= seat-id (:id %)) (seats/seats project ws-id))
         (throw (ex-info (str seat-id " is not a seat of generation " g) {:reason :not-a-seat :seat seat-id})))
       (let [o (first (filter #(= seat-id (:seat %)) (occupants project ws-id)))]
         (if session-name
           (let [s (session/read-session project ws-id session-name)]
             (when o
               (throw (ex-info (str "Seat " seat-id " is occupied by " (:session o)) {:reason :occupied :occupant o})))
             (when-not (and s (session/live? s) (not (:seat s)) (not (:conductor s)))
               (throw (ex-info (str session-name " is no live session of " ws-id " free to occupy a seat")
                               {:reason :not-free :session session-name})))
             (session/write! (assoc s :seat {:id seat-id :generation g :carried true}))
             ;; Its own start brought it up — perhaps still booting its services — so only the
             ;; conversation is started here.
             (when-not (driven? s)
               (launch! project ws-id session-name (brief project ws-id embodiment seat-id)
                        {:up? false :model (:model embodiment)}))
             {:seat seat-id :session session-name})
           (let [n (or (:session o) (seat-session-name ws-id embodiment seat-id g))]
             (when (and o (started? (session/read-session project ws-id n)))
               (throw (ex-info (str "Seat " seat-id " is occupied by " n) {:reason :occupied :occupant o})))
             (when-not o
               (when (session/read-session project ws-id n)
                 (throw (ex-info (str "The record " n " of seat " seat-id " is archived") {:reason :archived :session n})))
               (session/create! project ws-id {:name n :weight :heavy :autonomy nil
                                               :seat {:id seat-id :generation g}}))
             ;; A record a failed launch left is started again on what that launch was given — a
             ;; carried session's first instruction among it — not on a recipe rebuilt without it.
             (let [{:keys [prompt model]} (when o (:launched (session/read-session project ws-id n)))]
               (if prompt
                 (launch! project ws-id n prompt {:up? true :model model})
                 (launch! project ws-id n (brief project ws-id embodiment seat-id)
                          {:up? true :model (:model embodiment)})))
             {:seat seat-id :session n})))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map :SeatId] :map]}
  replace!
  "Retire the Claude session occupying `seat-id` in the current generation and start a fresh one
   on the same record and working copy, briefed afresh from the records — the old session is
   stopped before the new one starts, so the seat never has two. Refused as the embodiment refuses
   the workstream, against the generation it seats. Answers {:seat :session}."
  [project ws-id embodiment seat-id]
  (serially
   project ws-id
   (fn []
     (retire-unseated! project ws-id)
     ((:refuse embodiment) project ws-id)
     (let [o (or (first (filter #(= seat-id (:seat %)) (occupants project ws-id)))
                 (throw (ex-info (str "Seat " seat-id " has no occupant to replace") {:reason :unoccupied :seat seat-id})))]
       (conduct/retire! project ws-id (:session o) {})
       ;; The retired facet stays on the record until the new session installs its own; `start!`
       ;; refuses while its session still lives.
       (launch! project ws-id (:session o) (brief project ws-id embodiment seat-id))
       o))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :map]}
  reseat!
  "Retire every seat occupant whose seat the current seats reading does not answer — its Claude
   session stopped and its record archived, or released when its start carried it
   (`retire-unseated!`) — and leave the conductor's running. Answers {:retired [session …]}, in
   name order. The current seats are occupied by whatever carries the workstream, once it can be
   carried. A retirement that throws here is completed by the next occupy!, which retires what is
   left before it starts anyone, so a failed opening never needs another generation."
  [project ws-id]
  (serially project ws-id #(hash-map :retired (retire-unseated! project ws-id))))

(defn- running?
  "Whether the conversation on record `s` is running — its process in the listing — or a start of
   one is in flight."
  [s]
  (boolean (or (:dispatching s)
               (when-let [sid (get-in s [:claude :session-id])]
                 (and (not= :retired (get-in s [:claude :driver]))
                      (:pid (get (conduct/live-states {}) sid)))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :string] :map]}
  conductor!
  "Start the workstream's conductor on `brief`: a driven session on the one record naming it the
   conductor, occupying no seat, in its own working copy brought up first. Refused while the
   conductor's session is running; on a record whose session is not — gone from the listing, or
   retired with its workstream's close — it is started again on the same record. Answers
   {:session}."
  [project ws-id brief]
  (serially
   project ws-id
   (fn []
     (let [n (conductor-name ws-id)
           c (conductor-record project ws-id)]
       (cond
         (and c (running? c))
         (throw (ex-info (str "Workstream " ws-id " already has its conductor running, " n)
                         {:reason :conducted :session n}))
         (nil? c)
         (session/create! project ws-id {:name n :weight :heavy :autonomy nil :conductor true})
         (not (session/live? c))
         (session/write! (assoc c :substrate :live)))
       (when (and c (:claude c) (not= :retired (get-in c [:claude :driver])))
         ;; Stopped without retirement: its facet still names nido as driving a conversation that is
         ;; over. Retiring it says so before a new one starts on the record.
         (conduct/retire! project ws-id n {}))
       (launch! project ws-id n brief)
       {:session n}))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :map]}
  retire-conductor!
  "Retire the workstream's conductor by reconciling its two resources, each whatever the other's
   state: any Claude process of its record stopped, and its session brought down when its
   worktree exists — its record kept for a start if the workstream reopens. Idempotent: no
   conductor record means nothing to retire. Answers {:session} naming the record, or {}."
  [project ws-id]
  (if-let [c (conductor-record project ws-id)]
    (let [n (:name c)]
      (conduct/retire! project ws-id n {})
      (when (fs/exists? (:wt-path (lifecycle/session-coords n {:project (name project) :runtime-key ws-id})))
        (lifecycle/down! n {:project (name project)}))
      {:session n})
    {}))

(defn ^{:malli/schema [:=> [:cat] :map]}
  default-embodiment
  "The whole seat's embodiment, as occupancy takes it: its occupant's session named for the
   workstream, briefed to work the recipe's next stage — leaving a person's step to the person,
   and handing back naming it — and to hand back when the stage ends or it needs a decision.
   Refuses a generation that fixes ids, which its seats' own embodiment carries."
  []
  {:seat-name (fn [_] "work")
   :practice  (str "Work the next stage of nido's recipe for this workstream — intent, baseline, design, "
                   "implementation, and the review loop over each — as `bb nido:owed` places it. When that "
                   "stage is a person's to take (an intent, a design approval, a merge), do not take it: end "
                   "your turn saying what the person owes. End your turn when the stage is done or when you "
                   "need a decision; the workstream's conductor reads where you stopped and instructs you next.")
   :refuse    (fn [project ws-id]
                (when-not (:whole (first (seats/seats project ws-id)))
                  (throw (ex-info (str ws-id "'s generation fixes ids; its seats are not the whole seat's")
                                  {:reason :fixes-ids}))))})

;; ── the commit and PR hook ──────────────────────────────────────────────────

(defn- marked?
  "Whether `text` names one of `seat-ids` and a position among its layers in the shape `kind`
   takes."
  [kind seat-ids text]
  (let [s (str "(?:" (str/join "|" (map #(java.util.regex.Pattern/quote %) seat-ids)) ")")]
    (boolean (re-find (re-pattern (case kind
                                    :push  (str "(?m)^Seat: " s " [1-9][0-9]*/[1-9][0-9]*\\s*$")
                                    :title (str "^\\[" s " [1-9][0-9]*/[1-9][0-9]*\\] ")))
                      text))))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :string]]}
  gate-verdict
  "Why a Bash command run by a seat's occupant must not run, or nil to let it: a `jj git push`
   that would publish a description naming no seat of the workstream in a `Seat: <seat> <n>/<m>`
   trailer — each layer is held to its own seat, so an unpublished layer of another seat under
   the pusher's goes out with it — or a `gh pr` title not starting `[<seat> <n>/<m>]` with the
   pusher's own seat. Takes a Claude Code `PreToolUse` input; nil for any
   session that occupies no seat, for another conversation than the occupant's driven one in its
   folder, and for the whole seat's occupant, whose layers name no seat. Whether the session occupies one is the only thing it fails
   open on: once it does, a publication it recognises but cannot read is refused, since letting
   it through would publish a seat's layer that may name no seat. Best-effort over the commands
   it recognises: a script, a wrapper or a command assembled at run time publishes unseen, and
   what holds every seat's layer to its seat is the :layer-completed entry and the review loop."
  [{:keys [tool_name tool_input cwd session_id]}]
  (when-let [[p ws-id seat-id]
             (try
               (when (and (= "Bash" tool_name) (string? (:command tool_input)) (string? cwd))
                 (when-let [[p n] (session-at cwd)]
                   (when-let [ws-id (session/workstream-id-for p n)]
                     (let [s       (session/read-session p ws-id n)
                           seat-id (get-in s [:seat :id])
                           driven  (get-in s [:claude :session-id])]
                       ;; The whole seat's layers name no seat: nothing to hold them to. And the seat
                       ;; is its occupant's driven conversation, not the folder: a person's other
                       ;; conversation there occupies none — unless either conversation is unknown.
                       (when (and (string? seat-id)
                                  (or (nil? driven) (nil? session_id) (= driven session_id)))
                         [p ws-id seat-id])))))
               (catch Throwable _ nil))]
    (try
      (some (fn [{:keys [kind dir args title what]}]
              (case kind
                :push  (let [ids   (into #{seat-id} (comp (map :id) (filter string?)) (seats/seats p ws-id))
                             range (or (commit-gate/push-range dir args)
                                       (throw (ex-info "jj could not say what the push publishes" {})))]
                         (some (fn [{:keys [change description]}]
                                 (when-not (marked? :push ids description)
                                   (str "Blocked: commit " change " does not name its seat. Add a `Seat: <seat> <n>/<m>` "
                                        "trailer — the seat whose layer it is, " seat-id " for your own, and its position "
                                        "among that seat's layers — with `jj describe -r " change "`, then push again.")))
                               range))
                :title (when-not (marked? :title [seat-id] title)
                         (str "Blocked: a PR title of seat " seat-id " starts `[" seat-id
                              " <n>/<m>] ` — its position among the seat's layers."))
                :unread (str "Blocked: seat " seat-id "'s publication cannot be read for its seat and "
                             "position — " what ".")))
            (commit-gate/publications (:command tool_input) cwd))
      (catch Throwable e
        (str "Blocked: seat " seat-id "'s publication could not be checked for its seat and position ("
             (ex-message e) "). A seat's publication goes out only once it is; retry when the cause is fixed.")))))
