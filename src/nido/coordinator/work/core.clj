(ns nido.coordinator.work.core
  "The work plane's view of the core: what a surface shows of coordination, as the core's feed
   delivered it, and what a person does to coordination, as a keyed command.

   A workstream is addressed in the core by `ws-key` — \"<project>/<ws-id>\" — so the core needs
   no table of projects. Which core answers is core-client's to decide: the key, the socket and
   every request come from there.

   READS. A long-lived surface `follow!`s the feed: one connection, subscribed from the last
   position it rendered, holding each workstream's state at its latest commit in `mirror`. A
   one-shot reader (a bb verb) asks for one workstream's state with `state`. Nothing here reads a
   ledger file to decide what awaits a person or what runs.

   ACTS. `act!` sends one command for a person, marked as input so the writer takes it ahead of
   background commands, under a key of its own unless the caller names one (a retried click
   naming the same key is answered with the first outcome)."
  (:require [nido.core.api :as api]
            [nido.core.client :as client]
            [nido.core.domain :as d]))

;; ── addressing ───────────────────────────────────────────────────────────────
;; Which core answers — the daemon, or an in-process one — is core-client's to decide; this
;; namespace takes its key, its socket and every request from there.

(defn ^{:malli/schema [:=> [:cat :any :string] :string]} ws-key
  "The core's name for workstream `ws-id` of `project`."
  [project ws-id]
  (client/ws-key project ws-id))

(defn ^{:malli/schema [:=> [:cat :string] [:maybe [:tuple :string :string]]]} split-key
  "[project ws-id] of a core workstream key, or nil when it is not one."
  [k]
  (client/split-key k))

(defn ^{:malli/schema [:=> [:cat] :string]} socket
  "The core daemon's command socket."
  []
  (client/socket))

;; ── one-shot requests ────────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :map] :any]} request!
  "Send `req` to the core through core-client and answer its reply. Throws ex-info
   {:core :unreachable}, naming the daemon, when core-client answers `Unavailable`."
  [req]
  (let [r (client/request! req)]
    (when (client/unavailable? r) (client/value! r :state))
    r))

(defn ^{:malli/schema [:=> [:cat :any :string] [:maybe :map]]} state
  "Workstream `ws-id`'s committed state in the core; a workstream the core never heard of reads
   as the state before its first event."
  [project ws-id]
  (:state (request! {:op :state :ws (ws-key project ws-id)})))

(defn ^{:malli/schema [:=> [:cat :any :string :map [:? :map]] :map]} act!
  "Send `command` on workstream `ws-id` for a person. Answers the outcome: {:events [...]} or
   {:refused {...}} (or {:error ...} from the socket). Options: :key, to name the act so a retry
   is answered rather than repeated."
  ([project ws-id command] (act! project ws-id command {}))
  ([project ws-id command {:keys [key]}]
   (let [k (ws-key project ws-id)]
     (request! {:op :command :key (or key (str "person/" k "/" (random-uuid))) :ws k
                :command command :input? true}))))

(defn ^{:malli/schema [:=> [:cat :map] :boolean]} admitted?
  "Whether an outcome `act!` answered was admitted."
  [outcome]
  (contains? outcome :events))

;; ── following the feed ───────────────────────────────────────────────────────

(defonce ^{:doc "What the feed has delivered: {:pos n :ws {ws-key {:state :awaiting :pos}}
  :following? bool :connected? bool}."}
  mirror
  (atom {:pos 0 :ws {} :following? false :connected? false}))

(defn- apply-message [m {:keys [ws pos state awaiting]}]
  (-> m
      (assoc-in [:ws ws] {:state state :awaiting (vec awaiting) :pos pos})
      (update :pos max pos)))

(defn ^{:malli/schema [:=> [:cat [:? :map]] :nil]} follow!
  "Follow the core's feed into `mirror` on a background thread, reconnecting from the last
   position held whenever the connection ends or is cut off. Idempotent. Options: :on-change,
   (fn [ws-key]) called after each message is applied."
  ([] (follow! {}))
  ([{:keys [on-change]}]
   (when-not (or (client/local?)
                 (:following? (first (swap-vals! mirror assoc :following? true))))
     (doto (Thread.
            (fn []
              (loop []
                (try
                  (let [c (api/connect (socket))]
                    (swap! mirror assoc :connected? true)
                    (try
                      (api/subscribe c (:pos @mirror))
                      (loop []
                        (when-let [m (api/next-message c)]
                          (when-not (:cut-off m)
                            (swap! mirror apply-message m)
                            (when on-change (try (on-change (:ws m)) (catch Exception _ nil)))
                            (recur))))
                      (finally (api/disconnect c))))
                  (catch Exception _ nil))
                (swap! mirror assoc :connected? false)
                (Thread/sleep 1000)
                (recur))))
       (.setDaemon true)
       (.setName "nido-core-feed")
       (.start)))
   nil))

(defn ^{:malli/schema [:=> [:cat] :map]} held
  "Every workstream's state the feed has delivered: {ws-key {:state :awaiting :pos}}. Read once
   from the daemon when nothing follows the feed in this process, so a one-shot surface renders the
   same committed state a following one does. A process whose core-client answers in-process has
   no feed; it reads what that core holds from core-client."
  []
  (cond
    (client/local?)
    (into {} (for [[k st] (client/value! (client/states "") :states)]
               [k {:state st :awaiting (vec (d/awaiting-person st)) :pos 0}]))

    (:following? @mirror)
    (:ws @mirror)

    :else
    (try
      (let [c (api/connect (socket))]
        (try
          (api/subscribe c 0)
          ;; The catch-up arrives first and is everything committed; read until the stream goes
          ;; quiet, which a one-shot render accepts.
          (loop [acc {}]
            (let [m (deref (future (api/next-message c)) 300 ::quiet)]
              (if (or (= ::quiet m) (nil? m) (:cut-off m))
                acc
                (recur (assoc acc (:ws m) {:state (:state m) :awaiting (vec (:awaiting m)) :pos (:pos m)})))))
          (finally (api/disconnect c))))
      (catch Exception _ {}))))

(defn ^{:malli/schema [:=> [:cat :map :any :string] [:maybe :map]]} held-ws
  "Workstream `ws-id`'s entry in `held` map `h`, or nil."
  [h project ws-id]
  (get h (ws-key project ws-id)))

;; ── what a surface shows ─────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] :keyword]} engagement
  "The engagement a held workstream `w` reads as: :settled once closed, :parked-at-gate while an
   ask awaits the person, :active while a turn runs, :idle otherwise."
  [{:keys [state awaiting]}]
  (cond
    (:closed? state)                                           :settled
    (seq awaiting)                                             :parked-at-gate
    (some #(#{:running :resolved} (:status %)) (vals (:turns state))) :active
    :else                                                      :idle))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] :boolean]} held-units?
  "Whether a person holds every open unit of held workstream `w`, and it has one."
  [{:keys [state]}]
  (let [open (filter #(= :open (:status %)) (vals (:units state)))]
    (boolean (and (seq open) (every? :held? open)))))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] [:maybe :map]]} doing
  "What is underway in held workstream `w`, as the board's :doing reads it, or nil."
  [{:keys [state]}]
  (when-let [t (first (filter #(= :running (:status %)) (vals (:turns state))))]
    {:source :core :stage (if (:unit t) :unit-turn :conductor-turn) :turn (:id t) :unit (:unit t)}))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] [:maybe :map]]} position
  "Where held workstream `w` stands by its coordination, for a workstream whose ledger records none
   of the work: {:at :core-asking} — the next move the person's — while an ask awaits them,
   {:at :core-under-way} once a unit's turn has run, else nil. A unit turn records its outcome in
   the core and writes no record of the arc, so the ledger alone reads such a workstream as never
   started."
  [{:keys [state awaiting]}]
  (cond
    (:closed? state) nil
    (seq awaiting)   {:at :core-asking :next {:stage :answer-ask :mode :human}}
    (some :unit (vals (:turns state))) {:at :core-under-way}))

(defn ^{:malli/schema [:=> [:cat :map] [:vector :map]]} open-asks
  "Every ask awaiting the person across held map `h`: [{:project :ws-id :ask}], oldest workstream
   key first."
  [h]
  (vec (for [[k {:keys [awaiting]}] (sort-by key h)
             :let [[project ws-id] (split-key k)]
             :when project
             a (sort-by :id awaiting)]
         {:project project :ws-id ws-id :ask a})))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] [:vector :map]]} chat
  "The chat of held workstream `w`, in the order posted: [{:id :from :text}]."
  [{:keys [state]}]
  (vec (:chat state)))
