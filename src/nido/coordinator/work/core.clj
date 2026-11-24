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
   naming the same key is answered with the first outcome).

   IMAGES. A chat message names its images by digest. The side chat keeps what a person attached
   in a store of its own (`keep-chat-image!`); a relay copies the images it names into the core's
   (`relay-image!`) before the message naming them is sent."
  (:require [nido.core.api :as api]
            [nido.core.client :as client]
            [nido.core.domain :as d]
            [nido.core.images :as images]
            [nido.core.release :as release]
            [nido.platform.core :as platform]))

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
  :following? bool :connected? bool :caught-up promise}. :caught-up is delivered once the first
  catch-up has been applied whole."}
  mirror
  (atom {:pos 0 :ws {} :following? false :connected? false :caught-up (promise)}))

(defn- apply-message [m {:keys [ws pos state awaiting]}]
  (-> m
      (assoc-in [:ws ws] {:state state :awaiting (vec awaiting) :pos pos})
      (update :pos max pos)))

(def ^:private caught-up-wait-ms
  "How long after following starts a read may wait for the first catch-up before answering what
   it has — a deadline, not a wait per read, so a core that is down costs a surface ten seconds
   once rather than on every render. The catch-up of a few thousand workstreams applies in about
   a second."
  10000)

(defn ^{:malli/schema [:=> [:cat :map :map :boolean] :boolean]} receive!
  "Apply feed message `m` to `mirror`, given whether this connection's catch-up is over, and call
   what it owes: during a catch-up nothing per workstream, and once it ends `:on-caught-up`,
   once; after it `:on-change` with each workstream a live message changes. Answers whether the
   connection is caught up after `m`.

   A catch-up is applied whole before anything refreshes: refreshing each of a few thousand
   workstreams as it arrived took the follower minutes behind a board rebuilding, and every row
   built meanwhile read the core as holding nothing of a workstream it had not reached — the
   newest last, which are the ones a person is looking at."
  [m {:keys [on-change on-caught-up]} caught-up?]
  (cond
    (:caught-up m)
    (do (deliver (:caught-up @mirror) true)
        (when on-caught-up (try (on-caught-up) (catch Exception _ nil)))
        true)

    (not caught-up?)
    (do (swap! mirror apply-message m) false)

    :else
    (do (swap! mirror apply-message m)
        (when on-change (try (on-change (:ws m)) (catch Exception _ nil)))
        true)))

(defn ^{:malli/schema [:=> [:cat [:? :map]] :nil]} follow!
  "Follow the core's feed into `mirror` on a background thread, reconnecting from the last
   position held whenever the connection ends or is cut off. Idempotent. Options: :on-change,
   (fn [ws-key]) called after each live message is applied; :on-caught-up, (fn []) called once
   each connection's catch-up has been applied whole (`receive!`)."
  ([] (follow! {}))
  ([opts]
   (when-not (or (client/local?)
                 (:following? (first (swap-vals! mirror assoc :following? true
                                                  :wait-until (+ (System/currentTimeMillis)
                                                                 caught-up-wait-ms)))))
     (doto (Thread.
            (fn []
              (loop []
                (try
                  (let [c (api/connect (socket))]
                    (swap! mirror assoc :connected? true)
                    (try
                      (api/subscribe c (:pos @mirror))
                      (loop [caught-up? false]
                        (when-let [m (api/next-message c)]
                          (when-not (:cut-off m)
                            (recur (receive! m opts caught-up?)))))
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
    (let [{:keys [caught-up wait-until]} @mirror]
      (deref caught-up (max 0 (- (or wait-until 0) (System/currentTimeMillis))) nil)
      (:ws @mirror))

    :else
    (try
      (let [c (api/connect (socket))]
        (try
          (api/subscribe c 0)
          ;; The catch-up arrives first and is everything committed, and says where it ends; a
          ;; core that says nothing for a while is taken as having said all it has.
          (loop [acc {}]
            (let [m (deref (future (api/next-message c)) 2000 ::quiet)]
              (if (or (= ::quiet m) (nil? m) (:cut-off m) (:caught-up m))
                acc
                (recur (assoc acc (:ws m) {:state (:state m) :awaiting (vec (:awaiting m)) :pos (:pos m)})))))
          (finally (api/disconnect c))))
      (catch Exception _ {}))))

(defn ^{:malli/schema [:=> [:cat :map :any :string] [:maybe :map]]} held-ws
  "Workstream `ws-id`'s entry in `held` map `h`, or nil."
  [h project ws-id]
  (get h (ws-key project ws-id)))

;; ── what a surface shows ─────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] [:maybe :map]]} activity
  "What the core is doing on held workstream `w` (`nido.core.domain/activity`), or nil."
  [{:keys [state]}]
  (some-> state d/activity))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] :boolean]} background?
  "Whether held workstream `w` is a background run (`nido.core.domain/background-run?`)."
  [{:keys [state]}]
  (boolean (some-> state d/background-run?)))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] :keyword]} engagement
  "The engagement a held workstream `w` reads as: :settled once closed, :parked-at-gate while an
   ask awaits the person, :active while the core is doing anything else (`activity`), :idle
   otherwise."
  [{:keys [state] :as w}]
  (let [a (activity w)]
    (cond
      (:closed? state)          :settled
      (= :asking (:kind a))     :parked-at-gate
      a                         :active
      :else                     :idle)))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] :boolean]} held-units?
  "Whether a person holds every open unit of held workstream `w`, and it has one."
  [{:keys [state]}]
  (let [open (filter #(= :open (:status %)) (vals (:units state)))]
    (boolean (and (seq open) (every? :held? open)))))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] [:maybe :map]]} doing
  "What is underway in held workstream `w`, as the board's :doing reads it, or nil:
   {:source :core :activity k} with the :turn and :unit it concerns, k any `activity` but
   :asking, which waits on the person rather than being underway."
  [w]
  (when-let [{:keys [kind] :as a} (activity w)]
    (when-not (= :asking kind)
      (-> a (dissoc :kind) (assoc :source :core :activity kind)))))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] [:maybe :map]]} position
  "Where held workstream `w` stands by its coordination, for a workstream whose ledger records none
   of the work: {:at :core-asking} — the next move the person's — while an ask awaits them,
   {:at :core-under-way} once a unit's turn has run, else nil. A unit turn records its outcome in
   the core and writes no record of the arc, so the ledger alone reads such a workstream as never
   started."
  [{:keys [state] :as w}]
  (let [a (activity w)]
    (cond
      (:closed? state)          nil
      (= :asking (:kind a))     {:at :core-asking :next {:stage :answer-ask :mode :human}}
      (or a (some :unit (vals (:turns state)))) {:at :core-under-way})))

(defn ^{:malli/schema [:=> [:cat :map] [:vector :map]]} open-asks
  "Every ask awaiting the person across held map `h`: [{:project :ws-id :ask}], oldest workstream
   key first."
  [h]
  (vec (for [[k {:keys [awaiting]}] (sort-by key h)
             :let [[project ws-id] (split-key k)]
             :when project
             a (sort-by :id awaiting)]
         {:project project :ws-id ws-id :ask a})))

(defn- chat-images-root
  "Where the side chat keeps the images a person attached: outside the core, which holds only the
   images a relay passed on."
  []
  (str (platform/nido-home) "/chats/images"))

(defn ^{:malli/schema [:=> [:cat :map] [:or :string :map]]} keep-chat-image!
  "Keep `image` — {:mime :data :digest}, as the chat host's `parse-image` made it — in the side
   chat's own image store, outside the core, so a relay can pass it on later; answer its digest, or
   the store's refusal ({:refused :digest-mismatch :digest})."
  [image]
  (images/put! (chat-images-root) image))

(defn ^{:malli/schema [:=> [:cat :string] [:maybe :string]]} relay-image!
  "Copy the image the side chat keeps by `digest` into the core's image store, for a message to the
   conductor to name; answer the digest, or nil when the side chat keeps no image by it."
  [digest]
  (when-let [img (images/fetch (chat-images-root) digest)]
    (let [kept (images/put! (:images (release/paths)) img)]
      (when (string? kept) kept))))

