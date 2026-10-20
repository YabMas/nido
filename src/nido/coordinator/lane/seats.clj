(ns nido.coordinator.lane.seats
  "Who occupies each seat of a workstream, and what a new occupant is told.

   HIDES the occupancy policy, for any embodiment — an embodiment being how a seat's session is
   named and its occupant briefed ({:seat-name (fn [seat-id] slug) :practice text}); it selects
   nothing, since the seats are the design's mark, read by record.seats.

   Every seat of a workstream an embodiment carries is occupied by a driven background session
   of its own, on the workstream's one runtime; the conductor is a separate session that occupies
   no seat. An occupant is a session record naming its seat and the generation it occupies it in,
   and occupying, replacing and reading occupants read the current generation's records only, so
   a seat whose element survives a re-cut never takes up a retired occupant. When a generation
   opens, every occupant of the one before is retired — its Claude session stopped and its record
   archived — and the conductor's continues.

   A first brief is built from records — the seat, its interface, its open tensions, the accords
   that settled tensions it was party to, and what the generation inherited — never from another
   session's account. Seat messages reach a driven addressee only through conduct's inbox, and
   are logged per workstream beside the ledger, keyed by generation; no round reads the log.

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
   [nido.session.lifecycle :as lifecycle]
   [nido.session.state :as session-state]))

;; ── reading who is where ────────────────────────────────────────────────────

(defn- current-generation [project ws-id]
  (generation/current (:entries (ws/read-ws project ws-id))))

(defn- live-records [project ws-id]
  (filter session/live? (session/list-sessions project ws-id)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:sequential :map]]}
  occupants
  "Each seat's live occupant in the current generation, as {:seat :session}, sorted by seat.
   An earlier generation's occupant is never one, even of a seat whose element survived."
  [project ws-id]
  (let [g (current-generation project ws-id)]
    (->> (live-records project ws-id)
         (filter #(= g (get-in % [:seat :generation])))
         (map (fn [s] {:seat (get-in s [:seat :id]) :session (:name s)}))
         (sort-by :seat)
         vec)))

(defn- conductor-record [project ws-id]
  (first (filter :conductor (live-records project ws-id))))

(defn- session-of
  "The live record of the session that occupies `to` — a seat id or :conductor — or nil."
  [project ws-id to]
  (if (= :conductor to)
    (conductor-record project ws-id)
    (when-let [n (:session (first (filter #(= to (:seat %)) (occupants project ws-id))))]
      (session/read-session project ws-id n))))

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
   session. A seat's occupant of an earlier generation writes as the session it is, not the seat."
  [project ws-id cwd]
  (let [[p n] (session-at cwd)
        s (when (and n (= (keyword project) p)) (session/read-session project ws-id n))
        g (current-generation project ws-id)]
    (cond
      (and s (:conductor s))                        {:as :conductor :session n}
      (and s (= g (get-in s [:seat :generation])))  {:as :seat :seat (get-in s [:seat :id]) :session n}
      n                                             {:as :session :session n}
      :else                                         {:as :person})))

;; ── records and messages ────────────────────────────────────────────────────

(def ^:private stamped-kinds #{:tension :accord :withdrawal :recut})

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map] :map]}
  record!
  "Append `entry` — a :tension, :accord, :withdrawal or :recut, without :by — to the workstream's
   ledger, its writer stamped from where this runs. Refuses a stated :by, any other kind, and a
   :tension, :accord or :withdrawal from anyone but a seat's occupant or the conductor. Answers
   the entry as appended."
  [project ws-id entry]
  (let [kind (:format entry)
        by   (writer project ws-id (here))]
    (cond
      (not (stamped-kinds kind))
      (throw (ex-info (str "record! appends a :tension, :accord, :withdrawal or :recut, not " kind)
                      {:reason :kind :format kind}))
      (contains? entry :by)
      (throw (ex-info "record! stamps the writer from where it runs — drop :by"
                      {:reason :stated-writer}))
      (and (not= :recut kind) (not (#{:seat :conductor} (:as by))))
      (throw (ex-info (str "Only a seat's occupant or the conductor writes a " (name kind)
                           " — this runs " (if (:session by) (str "in " (:session by)) "outside every session"))
                      {:reason :not-in-ensemble :by by})))
    (let [stamped (assoc entry :by by)]
      (ws/append-stamped! project ws-id stamped)
      stamped)))

(defn- log-path [project ws-id]
  (str (fs/path (cstate/workstream-dir project ws-id) "seat-messages.edn")))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :int] [:sequential :map]]}
  traffic
  "Generation `generation`'s seat messages, oldest first, each {:generation :from :to :text :at}."
  [project ws-id generation]
  (filterv #(= generation (:generation %)) (or (io/read-edn (log-path project ws-id)) [])))

(defn- describe [{:keys [as seat]}]
  (if (= :seat as) (str "seat " seat) "the conductor"))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map] :map]}
  send!
  "Send {:to <seat id | :conductor> :text} from the seat's occupant or the conductor this runs
   in: log it under the current generation, then hand it to the addressee's session through
   conduct, attributed to its sender. Refused from anyone else, and to an addressee with no live
   session. Answers the logged message."
  [project ws-id {:keys [to text]}]
  (let [from (writer project ws-id (here))
        g    (current-generation project ws-id)
        to-s (session-of project ws-id to)]
    (when-not (#{:seat :conductor} (:as from))
      (throw (ex-info "Only a seat's occupant or the conductor sends a seat message"
                      {:reason :not-in-ensemble :from from})))
    (when-not to-s
      (throw (ex-info (str "No live session occupies " (if (= :conductor to) "the conductor" (str "seat " to)))
                      {:reason :no-addressee :to to})))
    (let [msg {:generation g :from from :to to :text text :at (clock/now-iso)}]
      (io/update-edn! (log-path project ws-id) (fnil conj []) msg)
      (conduct/message! project ws-id (:name to-s)
                        (str "[seat message from " (describe from) "]\n\n" text))
      msg)))

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
    (str "You occupy seat " seat-id " of workstream " ws-id
         ", generation " (current-generation project ws-id) ".\n\n"
         "Its interface: " (or (:interface seat) "not yet stated by a design of this generation") "\n\n"
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
         "Raise a tension, accord or withdraw with `bb nido:seats:record`, and message another seat "
         "or the conductor with `bb nido:seats:send`. Name your seat and your position among its "
         "layers in every commit (`Seat: " seat-id " <n>/<m>`) and PR title (`[" seat-id " <n>/<m>] …`).")))

(defn- conductor-brief [project ws-id]
  (str "You conduct workstream " ws-id ", generation " (current-generation project ws-id)
       ". You occupy no seat. Settle the tensions addressed to you with `bb nido:seats:record`, "
       "message seats with `bb nido:seats:send`, and propose a re-cut when the seats keep arguing "
       "across one boundary. Seats: "
       (str/join ", " (map :id (seats/seats project ws-id))) "."))

;; ── starting sessions ───────────────────────────────────────────────────────

(defn- launch!
  "Bring the session `session-name` up on the workstream's runtime and start a background session
   nido drives in its own working copy, on `prompt`. Answers the facet."
  [project ws-id session-name prompt]
  (let [pname (name project)
        opts  {:project pname :runtime-key ws-id}]
    (lifecycle/up! session-name opts)
    (let [instance-id (:instance-id (lifecycle/runtime-of session-name opts))
          mcp         (some-> instance-id session-state/session-mcp-path)]
      (conduct/start! {:project       project :ws-id ws-id :session-name session-name
                       :name          session-name
                       :cwd           (str (:wt-path (lifecycle/session-coords session-name opts)))
                       :source        (get-in (project/list-projects) [pname :directory])
                       :prompt        prompt
                       :system-prompt (launcher/session-briefing pname session-name instance-id)
                       :mcp-config    (when (and mcp (fs/exists? mcp)) mcp)
                       :add-dirs      (launcher/nido-add-dirs)}))))

(defn- occupancy-lock-path [project ws-id]
  (str (fs/path (cstate/workstream-dir project ws-id) ".occupancy.lock")))

(defn- serially
  "Run `f` holding the workstream's occupancy lock, so reading who occupies what, writing the
   record and starting its session are one step against every other occupancy verb."
  [project ws-id f]
  (io/with-file-lock (occupancy-lock-path project ws-id) f))

(defn- started?
  "Whether a session was ever started on record `s`, or a start of it is in flight — as against a
   record written for a launch that failed, which a retry may start on."
  [s]
  (boolean (or (:claude s) (:dispatching s))))

(defn- retire-earlier!
  "Retire every live seat occupant of a generation before the current one — its Claude session
   stopped, its record archived, its working copy kept. Answers their names, in name order. Run
   under the occupancy lock, by reseat! as a generation opens and again before any seat is
   occupied or its occupant replaced, so a retirement that failed at the opening completes before a new occupant starts."
  [project ws-id]
  (let [g     (current-generation project ws-id)
        stale (->> (live-records project ws-id)
                   (filter #(and (:seat %) (< (get-in % [:seat :generation]) g)))
                   (sort-by :name))]
    (doseq [s stale]
      (conduct/retire! project ws-id (:name s) {})
      (session/archive! project ws-id (:name s)))
    (mapv :name stale)))

(defn- seat-session-name [ws-id embodiment seat-id g]
  (str ws-id "--" ((:seat-name embodiment) seat-id) "-g" g))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map :string] :map]}
  occupy!
  "Start a driven session for `seat-id`, a seat of the current generation with no occupant in it,
   briefed from the records, once every occupant of an earlier generation is retired — a
   retirement that fails throws, and nothing is started. Refused for an id that is not a seat
   and for a seat already occupied — a session started on its record, or a start in flight; a record left by a launch
   that failed is started on again. Answers {:seat :session}."
  [project ws-id embodiment seat-id]
  (serially
   project ws-id
   (fn []
     (retire-earlier! project ws-id)
     (let [g (current-generation project ws-id)]
       (when-not (some #(= seat-id (:id %)) (seats/seats project ws-id))
         (throw (ex-info (str seat-id " is not a seat of generation " g) {:reason :not-a-seat :seat seat-id})))
       (let [o (first (filter #(= seat-id (:seat %)) (occupants project ws-id)))
             n (or (:session o) (seat-session-name ws-id embodiment seat-id g))]
         (when (and o (started? (session/read-session project ws-id n)))
           (throw (ex-info (str "Seat " seat-id " is occupied by " n) {:reason :occupied :occupant o})))
         (when-not o
           (when (session/read-session project ws-id n)
             (throw (ex-info (str "The record " n " of seat " seat-id " is archived") {:reason :archived :session n})))
           (session/create! project ws-id {:name n :weight :heavy :autonomy nil
                                           :seat {:id seat-id :generation g}}))
         (launch! project ws-id n (brief project ws-id embodiment seat-id))
         {:seat seat-id :session n})))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map :string] :map]}
  replace!
  "Retire the Claude session occupying `seat-id` in the current generation and start a fresh one
   on the same record and working copy, briefed afresh from the records — the old session is
   stopped before the new one starts, so the seat never has two. Answers {:seat :session}."
  [project ws-id embodiment seat-id]
  (serially
   project ws-id
   (fn []
     (retire-earlier! project ws-id)
     (let [o (or (first (filter #(= seat-id (:seat %)) (occupants project ws-id)))
                 (throw (ex-info (str "Seat " seat-id " has no occupant to replace") {:reason :unoccupied :seat seat-id})))]
       (conduct/retire! project ws-id (:session o) {})
       (session/update-substrate! project ws-id (:session o) #(assoc % :claude nil))
       (launch! project ws-id (:session o) (brief project ws-id embodiment seat-id))
       o))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :map]}
  reseat!
  "Retire every seat occupant of an earlier generation — its Claude session stopped, its record
   archived, its working copy kept — and leave the conductor's running. Answers {:retired
   [session …]}, in name order. The new generation's seats are occupied by whatever carries it, once it can be
   carried. A retirement that throws here is completed by the next occupy!, which retires what
   is left before it starts anyone, so a failed opening never needs another generation."
  [project ws-id]
  (serially project ws-id #(hash-map :retired (retire-earlier! project ws-id))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :map]}
  conductor!
  "Start the workstream's conductor: a driven session on a record naming it the conductor,
   occupying no seat. Refused while a session was started on the workstream's live conductor
   record; a record left by a launch that failed is started on again. A conductor whose record
   was archived is not started again here. Answers {:session}."
  [project ws-id]
  (serially
   project ws-id
   (fn []
     (let [c        (conductor-record project ws-id)
           n        (or (:name c) (str ws-id "--conductor"))
           existing (or c (session/read-session project ws-id n))]
       (cond
         (and c (started? c))
         (throw (ex-info (str "Workstream " ws-id " already has its conductor, " n)
                         {:reason :conducted :session n}))
         (nil? existing)
         (session/create! project ws-id {:name n :weight :heavy :autonomy nil :conductor true})
         (not (session/live? existing))
         (throw (ex-info (str "The conductor's record " n " is archived") {:reason :archived :session n})))
       (launch! project ws-id n (conductor-brief project ws-id))
       {:session n}))))

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
   session that occupies no seat, and on anything it cannot read — it fails open, as the commit
   gate does."
  [{:keys [tool_name tool_input cwd]}]
  (try
    (when (and (= "Bash" tool_name) (string? (:command tool_input)) (string? cwd))
      (when-let [[p n] (session-at cwd)]
        (when-let [ws-id (session/workstream-id-for p n)]
          (when-let [seat-id (get-in (session/read-session p ws-id n) [:seat :id])]
            (some (fn [{:keys [kind dir args title]}]
                    (case kind
                      :push  (let [ids (into #{seat-id} (map :id) (seats/seats p ws-id))]
                               (some (fn [{:keys [change description]}]
                                       (when-not (marked? :push ids description)
                                         (str "Blocked: commit " change " does not name its seat. Add a `Seat: <seat> <n>/<m>` "
                                              "trailer — the seat whose layer it is, " seat-id " for your own, and its position "
                                              "among that seat's layers — with `jj describe -r " change "`, then push again.")))
                                     (commit-gate/push-range dir args)))
                      :title (when-not (marked? :title [seat-id] title)
                               (str "Blocked: a PR title of seat " seat-id " starts `[" seat-id
                                    " <n>/<m>] ` — its position among the seat's layers."))))
                  (commit-gate/checks (:command tool_input) cwd))))))
    (catch Throwable _ nil)))
