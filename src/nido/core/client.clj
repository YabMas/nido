(ns nido.core.client
  "How a process outside the daemon reaches the core: one request at a time over the command
   socket. A workstream is addressed by `ws-key` — \"<project>/<ws-id>\" — so the core needs no
   table of projects.

   A process may instead be given a core of its own (`use-local!`), which answers the same
   requests from memory; the bb test suite and the surface bench's measuring child are the two
   such processes, each over a nido home of its own. A process that has neither
   gets an error naming the daemon, never a quiet second store."
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

(defn ^{:malli/schema [:=> [:cat :map] :any]} request!
  "Send `req` to the core and answer its reply. Throws ex-info {:core :unreachable} when the daemon
   does not answer."
  [req]
  (if @local-core
    (local/answer req)
    (let [c (try (api/connect (socket))
                 (catch Exception e
                   (throw (ex-info "the core daemon is not running (bb nido:core:status)"
                                   {:core :unreachable} e))))]
      (try (api/request c req) (finally (api/disconnect c))))))

;; ── reads and commands ───────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :string] :map]} state
  "Workstream `k`'s committed state; the state before any event when the core never heard of it."
  [k]
  (:state (request! {:op :state :ws k})))

(defn ^{:malli/schema [:=> [:cat :string] [:map-of :string :map]]} states
  "The committed state of every workstream whose key starts with `prefix`, by key."
  [prefix]
  (or (:states (request! {:op :states :prefix prefix})) {}))

(defn ^{:malli/schema [:=> [:cat :string [:sequential :int]] [:map-of :int :string]]} entry-contents
  "The content of each entry of workstream `k` named in `seqs`, by seq."
  [k seqs]
  (if (empty? seqs) {} (or (:entries (request! {:op :entries :ws k :seqs (vec seqs)})) {})))

(defn ^{:malli/schema [:=> [:cat :string :string :map] :map]} command!
  "Send `command` on workstream `k` under `key` and answer its committed outcome: {:events [...]},
   {:refused {...}}, or {:error ...}. A reply lost on the way is asked for again under the same
   key, which answers the outcome committed under it rather than deciding the command twice."
  [k key command]
  (loop [tries 3]
    (let [o (request! {:op :command :key key :ws k :command command})]
      (if (and (contains? o :error) (> tries 1) (#{:timeout} (:error o)))
        (recur (dec tries))
        o))))
