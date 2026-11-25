(ns nido.core.client
  "How a process outside the daemon reaches the core: one request at a time over the command
   socket. A workstream is addressed by `ws-key` — \"<project>/<ws-id>\" — so the core needs no
   table of projects.

   A process may instead be given a core of its own (`use-local!`), which answers the same
   requests from memory; the bb test suite and the surface bench's measuring child are the two
   such processes, each over a nido home of its own. A process that has neither is answered
   `Unavailable`, naming the daemon, never a quiet second store."
  (:require [clojure.string :as str]
            [nido.core.api :as api]
            [nido.core.local :as local]
            [nido.core.release :as release]))

;; ── addressing ───────────────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :any :string] :string]} ws-key
  "The core's name for workstream `ws-id` of `project`."
  [project ws-id]
  (str (name project) "/" ws-id))

(defn ^{:malli/schema [:=> [:cat :string] [:maybe [:tuple :string :string]]]} split-key
  "[project ws-id] of a core workstream key, or nil when it is not one."
  [k]
  (let [i (str/index-of (str k) "/")]
    (when (and i (pos? i)) [(subs k 0 i) (subs k (inc i))])))

(defn ^{:malli/schema [:=> [:cat] :string]} socket
  "The core daemon's command socket."
  []
  (:socket (release/paths)))

;; ── the core this process speaks to ──────────────────────────────────────────

(defonce ^:private local-core
  (atom false))

(defn ^{:malli/schema [:=> [:cat] :nil]} use-local!
  "Answer every request of this process from an in-process core (`nido.core.local`) rather than
   the daemon. For the bb test suite and the surface bench's measuring child only, each over a
   nido home no person's process serves."
  []
  (reset! local-core true)
  nil)

(defn ^{:malli/schema [:=> [:cat] :boolean]} local?
  "Whether this process speaks to an in-process core."
  []
  @local-core)

;; ── what the core answers ──────────────────────────────────────────────────────
;; Every request is one of the variants core-api serves, and every answer is data — a command's
;; outcome, a read's reply, or `Unavailable` when no core answered. Nothing here throws; a caller
;; that needs the value turns `Unavailable` into its error with `value!`.

(def CoreRequest
  "A request the core answers: a keyed command, or a read."
  [:multi {:dispatch :op}
   [:command     [:map [:op [:= :command]] [:key :string] [:ws :string] [:command :map]
                  [:input? {:optional true} :boolean]]]
   [:state       [:map [:op [:= :state]] [:ws :string]]]
   [:states      [:map [:op [:= :states]] [:prefix :string]]]
   [:entries     [:map [:op [:= :entries]] [:ws :string] [:seqs [:vector :int]]]]
   [:summaries   [:map [:op [:= :summaries]] [:project :string]]]
   [:asking      [:map [:op [:= :asking]] [:project :string]]]
   [:project-states [:map [:op [:= :project-states]] [:project :string]
                     [:open? {:optional true} :boolean]
                     [:summaries? {:optional true} :boolean]
                     [:newest-of {:optional true} [:vector :keyword]]]]
   [:workstreams [:map [:op [:= :workstreams]]]]])

(def Unavailable
  "No core answered: the daemon at `:socket` is not running, or did not reply."
  [:map [:unavailable [:map [:socket :string] [:message :string]]]])

(def CommandOutcome
  "What a keyed command came to: committed, refused, undecided, or no core to ask."
  [:or [:map [:events [:vector :map]]]
       [:map [:refused :map]]
       [:map [:error :any]]
       Unavailable])

(def ReadReply
  "A read's reply, or no core to ask."
  [:or [:map [:state :map]]
       [:map [:states [:map-of :string :map]]]
       [:map [:entries [:map-of :int :string]]]
       [:map [:summaries [:map-of :string :map]]]
       [:map [:asking [:map-of :string :map]]]
       [:map [:states [:map-of :string :map]]
        [:summaries {:optional true} [:map-of :string :map]]
        [:contents {:optional true} [:map-of :string [:map-of :int :string]]]]
       [:map [:workstreams [:vector :string]]]
       [:map [:error :any]]
       Unavailable])

(defn ^{:malli/schema [:=> [:cat :any] :boolean]} unavailable?
  "Whether `answer` says no core answered."
  [answer]
  (contains? answer :unavailable))

(defn ^{:malli/schema [:=> [:cat :string] Unavailable]} unavailable
  "The `Unavailable` answer for the daemon at `socket`."
  [socket]
  {:unavailable {:socket socket
                 :message (str "the core daemon is not running at " socket " (bb nido:core:status)")}})

(defn ^{:malli/schema [:=> [:cat :map :keyword] :any]} value!
  "The value under `k` of a read's `reply`, for a caller that cannot go on without it: an
   `Unavailable` reply is thrown as an error naming the daemon, {:core :unreachable}."
  [reply k]
  (if (unavailable? reply)
    (throw (ex-info (get-in reply [:unavailable :message])
                    {:core :unreachable :socket (get-in reply [:unavailable :socket])}))
    (get reply k)))

(defn ^{:malli/schema [:=> [:cat CoreRequest] [:or CommandOutcome ReadReply]]} request!
  "Send `req` to the core and answer its reply, or `Unavailable` when the daemon does not answer."
  [req]
  (if @local-core
    (local/answer req)
    (let [s (socket)
          c (try (api/connect s) (catch Exception _ nil))]
      (if-not c
        (unavailable s)
        (try (or (api/request c req) (unavailable s))
             (catch Exception _ (unavailable s))
             (finally (api/disconnect c)))))))

;; ── reads and commands ───────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :string] ReadReply]} state
  "Workstream `k`'s committed state as {:state st} — the state before any event when the core
   never heard of it — or `Unavailable`."
  [k]
  (request! {:op :state :ws k}))

(defn ^{:malli/schema [:=> [:cat :string] ReadReply]} states
  "{:states {key state}} for every workstream whose key starts with `prefix`, or `Unavailable`."
  [prefix]
  (let [r (request! {:op :states :prefix prefix})]
    (if (unavailable? r) r (update r :states #(or % {})))))

(defn ^{:malli/schema [:=> [:cat :any] ReadReply]} summaries
  "{:summaries {key summary}} for every workstream of `project` (`nido.core.domain/summary`), or
   `Unavailable`."
  [project]
  (let [r (request! {:op :summaries :project (name project)})]
    (if (unavailable? r) r (update r :summaries #(or % {})))))

(defn ^{:malli/schema [:=> [:cat :any] ReadReply]} asking
  "{:asking {key state}} for every workstream of `project` a person owes a move now — an open ask
   on a workstream that is not closed — or `Unavailable`."
  [project]
  (let [r (request! {:op :asking :project (name project)})]
    (if (unavailable? r) r (update r :asking #(or % {})))))

(defn ^{:malli/schema [:=> [:cat :any :map] ReadReply]} project-states
  "Every workstream of `project` in one request, as `nido.core.store/project-states` reads it —
   {:states {key state}}, with `opts` `:summaries? true` {:summaries {key summary}}, with
   `:open? true` only those neither closed nor discarded, with `:newest-of [kind …]`
   {:contents {key {seq content}}} — or `Unavailable`."
  [project opts]
  (let [r (request! (merge {:op :project-states :project (name project)}
                           (select-keys opts [:open? :summaries? :newest-of])
                           (when (:newest-of opts) {:newest-of (vec (:newest-of opts))})))]
    (if (unavailable? r) r (update r :states #(or % {})))))

(defn ^{:malli/schema [:=> [:cat :string [:sequential :int]] ReadReply]} entry-contents
  "{:entries {seq content}} for each entry of workstream `k` named in `seqs`, or `Unavailable`."
  [k seqs]
  (if (empty? seqs)
    {:entries {}}
    (let [r (request! {:op :entries :ws k :seqs (vec seqs)})]
      (if (unavailable? r) r (update r :entries #(or % {}))))))

(defn ^{:malli/schema [:=> [:cat :string :string :map] CommandOutcome]} command!
  "Send `command` on workstream `k` under `key` and answer its outcome: {:events [...]},
   {:refused {...}}, {:error ...} or `Unavailable`. A reply lost on the way is asked for again
   under the same key, which answers the outcome committed under it rather than deciding the
   command twice."
  [k key command]
  (loop [tries 3]
    (let [o (request! {:op :command :key key :ws k :command command})]
      (if (and (> tries 1) (or (unavailable? o) (= :timeout (:error o))))
        (recur (dec tries))
        o))))
