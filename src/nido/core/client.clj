(ns nido.core.client
  "How a process outside the daemon reaches the core: one request at a time over the command
   socket. A workstream is addressed by `ws-key` — \"<project>/<ws-id>\" — so the core needs no
   table of projects.

   A process may instead be given a core of its own (`use-local!`), which answers the same
   requests from memory; the bb test suite and the surface bench's measuring child are the two
   such processes, each over a nido home of its own. A process that has neither is answered
   `Unavailable`, naming the daemon, never a quiet second store.

   Whatever a process was given, the daemon answers only the person's own processes
   (`daemon-answers?`): never one pointed at a nido home the daemon does not serve, and never a
   request made while a test runs. Those are answered by the in-process core, so a test run any
   way at all — not only through `bb nido:test` — cannot write the person's workstreams."
  (:require [clojure.string :as str]
            [nido.core.api :as api]
            [nido.core.local :as local]
            [nido.core.release :as release]
            [nido.platform.core :as core]))

;; ── addressing ───────────────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :any :string] :string]} ws-key
  "The core's name for workstream `ws-id` of `project`."
  [project ws-id]
  (str (name project) "/" ws-id))

(def ^:private run-prefix "run:")

(defn ^{:malli/schema [:=> [:cat :any :string] :string]} run-key
  "The core's name for run `run-id` of `project`. What precedes its slash is `run:<project>`, which
   is no project: every read of a project's workstreams passes it by."
  [project run-id]
  (str run-prefix (name project) "/" run-id))

(defn ^{:malli/schema [:=> [:cat :any] :boolean]} run-key?
  "Whether `k` names a run rather than a workstream."
  [k]
  (str/starts-with? (str k) run-prefix))

(def ^:private journal-prefix "journal:")

(defn ^{:malli/schema [:=> [:cat :any :string] :string]} journal-key
  "The core's name for journal `jname` of `project`: `journal:<project>/<name>`, which no read of a
   project's workstreams or runs returns."
  [project jname]
  (str journal-prefix (name project) "/" jname))

(defn ^{:malli/schema [:=> [:cat :any] :boolean]} workstream-key?
  "Whether `k` names a workstream — neither a run nor a journal."
  [k]
  (not (or (run-key? k) (str/starts-with? (str k) journal-prefix))))

(defn ^{:malli/schema [:=> [:cat :string] [:maybe [:tuple :string :string]]]} split-key
  "[project ws-id] of a core workstream key, or nil when it is not one — a run's or a journal's
   key is not."
  [k]
  (let [i (str/index-of (str k) "/")]
    (when (and i (pos? i) (workstream-key? k)) [(subs k 0 i) (subs k (inc i))])))

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

(defn- in-a-test?
  "Whether a clojure.test test is running on this thread (or one it conveyed its bindings to)."
  []
  (boolean (some-> (find-ns 'clojure.test) (ns-resolve '*testing-vars*) deref seq)))

(defn- daemon-answers?
  "Whether the daemon may answer this request: the process was not given an in-process core, its
   nido home (`nido-root`, the seam tests redirect) is the one the daemon serves, and no test is
   running. A test that redirects the home, or one run straight from `bb -e` / a REPL rather than
   `bb nido:test`, would otherwise write its fixtures into the person's core — where every one
   showed up on the board as a workstream nobody started."
  []
  (and (not @local-core)
       (= (str (core/nido-root)) (:home (release/paths)))
       (not (in-a-test?))))

(defn ^{:malli/schema [:=> [:cat] [:tuple :keyword :string]]} core-identity
  "Which core this process's next request would reach: `[:daemon <socket>]`, or `[:local <nido
   home>]` for the in-process core of that home. Two requests with equal identities are answered
   by the same core, so a value read from one may stand for what the other would read."
  []
  (if (daemon-answers?)
    [:daemon (socket)]
    [:local (str (core/nido-root))]))

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
       [:map [:records [:vector :map]]]
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
  (if-not (daemon-answers?)
    (local/answer req)
    (let [s (socket)
          c (try (api/connect s) (catch Exception _ nil))]
      (if-not c
        (unavailable s)
        (try (or (api/request c req) (unavailable s))
             (catch Exception _ (unavailable s))
             (finally (api/disconnect c)))))))

;; ── reads and commands ───────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat] ReadReply]} workstreams
  "{:workstreams [key …]}: the key of every workstream, run and journal the core holds, whatever
   project it names and whether or not that project is still registered — or `Unavailable`."
  []
  (request! {:op :workstreams}))

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

(defn ^{:malli/schema [:=> [:cat :any] ReadReply]} runs
  "{:runs {run-id summary}} for every run of `project`, each its `nido.core.domain/summary` —
   identity under :run, :status, :detail — or `Unavailable`."
  [project]
  (let [prefix (run-key project "")
        r (request! {:op :summaries :project (str run-prefix (name project))})]
    (if (unavailable? r)
      r
      {:runs (into {} (map (fn [[k sm]] [(subs k (count prefix)) sm])) (:summaries r))})))

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

;; ── records: what a run did, and journals ────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :string] ReadReply]} records
  "{:records [record …]} of the run or journal `k`, in append order, each with its `:seq` — empty
   for a stream that holds none — or `Unavailable`."
  [k]
  (let [r (state k)]
    (if (unavailable? r) r {:records (vec (get-in r [:state :records]))})))

(defn ^{:malli/schema [:=> [:cat :string :string :map :int] CommandOutcome]} append-record!
  "Append `record` ({:kind k …}) to the run or journal `k` under `key`, having read `after` of its
   records. A journal not yet opened is opened first, named by its key. Answers the outcome:
   refused `:stale` with the seq it holds when another append got there first — read again and
   decide again, never resend — and `:run-is-over` on a run whose unit finished."
  [k key record after]
  (let [o (command! k key {:command :append-record :record record :after after})]
    (if (and (= :records-are-a-journals-or-a-runs (get-in o [:refused :reason]))
             (str/starts-with? k journal-prefix))
      (let [[project jname] (str/split (subs k (count journal-prefix)) #"/" 2)]
        (command! k (str "open/" k) {:command :open-journal :journal {:project project :name jname}})
        ;; Under a key of its own: `key` answers the refusal it committed, every time it is asked.
        (command! k (str key "/opened") {:command :append-record :record record :after after}))
      o)))
