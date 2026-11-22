;; src/tasks/nido_commit_gate.clj
(ns tasks.nido-commit-gate
  "bb-task entrypoint: nido's commit gate, run as a Claude Code `PreToolUse`
   hook on Bash. Exit 2 with the reason on stderr blocks the command; 0 lets it
   run. Two judges: the project's own convention
   (`nido.session.commit-gate/verdict`), which fails open, and `refusal`: a
   person's act is not run in a turn the core runs unless the person granted it.

   A turn the core runs says so in its environment — NIDO_CORE_WS, its workstream's core key, and
   NIDO_CORE_UNIT for a unit's turn — and a grant is read from that workstream's state in the core,
   never from a ledger file."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [nido.coordinator.work.core :as wcore]
   [nido.platform.task-args :as task-args]
   [nido.session.commit-gate :as gate]))

(defn- hook-input
  "The host's JSON on stdin, or nil when there is none. Only read when there is
   no console, so running the verb by hand in a terminal does not wait on a
   Ctrl-D (see `tasks.nido-boundary/hook-input`)."
  []
  (when (nil? (System/console))
    (try (json/parse-string (slurp *in*) true)
         (catch Exception _ nil))))

;; ── the person's acts ───────────────────────────────────────────────────────

(def ^:private person-kinds
  "The ledger kinds only a person's act writes."
  #{:intent :triage-accepted :phase-gate :blocker-answered :grant :merged :closed
    ;; a drain's: approving a recipe, and observing its exit
    :recipe :exit-observed})

(def ^:private person-tasks
  "The nido tasks that do a person's act on any path they can take — whatever their arguments,
   and whether or not they then exit unsuccessfully. The review tasks are not here: what they
   write is a round's, never a person's act."
  #{"nido:workstream:blocker:answer" "nido:land:record" "nido:recovery:land"
    "nido:workstream:close" "nido:phase:advance" "nido:ticket:complete" "nido:ticket:apply"
    "nido:workstream:generation:open" "nido:activate" "nido:deactivate"
    "nido:workstream:stage:advance" "nido:ticket:dismiss" "nido:recovery:restore"
    "nido:findings:resolve" "nido:improvement:reserve" "nido:status:backfill"
    "nido:improvement:discharge" "nido:workstream:fork"
    "nido:drain:start" "nido:drain:approve" "nido:drain:land" "nido:drain:close"
    ;; a landing of the workstream — a unit's turn's only under a :merge grant (`granted?`)
    "nido:workstream:pr:land"
    ;; the other landing verbs: never granted under any grant
    "nido:land" "nido:ship"})

(def ^:private generic-appends
  "The nido tasks that append an entry of whatever :kind their arguments name."
  #{"nido:workstream:entry:add" "nido:ticket:append"})

(defn- appends-person-kind?
  "Whether the generic append's `args` may name a kind only a person's act writes. A word the gate
   cannot read standing as the :kind, or where it could be the :kind key, may name one."
  [args]
  (let [[pos opts] (try (task-args/split-args args) (catch Exception _ nil))
        kind       (:kind opts)]
    (boolean (or (= :opaque kind)
                 (some #{:opaque} pos)
                 (person-kinds (try (keyword kind) (catch Exception _ nil)))))))

(defn- persons-act
  "What a person's act `act` — one of commit-gate/acts — is, as a phrase, or nil. Pushing and
   opening or readying a PR are not a person's act; merging one is, and so is passing word on
   with a grant."
  [{:keys [kind verb task args]}]
  (case kind
    :gh-pr     (cond (= "merge" verb) "gh pr merge"
                     (= :unread verb) "a gh command whose verb the gate cannot read, which may merge a pull request"
                     (= "api" verb)   "a gh api write that may merge a pull request")
    :nido-task (cond (person-tasks task) task
                     (and (generic-appends task) (appends-person-kind? args)) (str task " of a person's kind")
                     (and (= "nido:workstream:tell" task)
                          (some #(or (not (string? %)) (re-matches #"(?i):?grants" %)) args))
                     "nido:workstream:tell with a grant")
    nil))

(defn- landing-act?
  "Whether `act` lands the workstream by a route other than `nido:workstream:pr:land`."
  [{:keys [kind task]}]
  (or (= :gh-pr kind) (and (= :nido-task kind) (contains? #{"nido:land" "nido:ship"} task))))

(defn- under? [dir root]
  (and dir root (let [d (str dir) r (str/replace (str root) #"/$" "")]
                  (or (= d r) (str/starts-with? d (str r "/"))))))

(defn- granted?
  "Whether `act` is one the person granted to unit turn `turn` ({:ws :unit :state}) by a grant
   unspent in its workstream's core state: under :intent, a generic append of an :intent naming no
   other workstream; under :merge, `nido:workstream:pr:land` naming no PR or one PR, run in the
   unit's working copy, which it lands. Arguments the gate cannot read are granted nothing, a raw
   merge never is, and the conductor's turn is granted nothing: a grant is the person's to a unit."
  [{:keys [ws-id unit state]} {:keys [kind task args dir]}]
  (boolean
   (when (and (= :nido-task kind) unit)
     (let [grants (set (keys (:grants state)))
           [pos opts] (try (task-args/split-args args #{:pr}) (catch Exception _ nil))
           readable? (and (some? opts) (not-any? #{:opaque} pos) (not-any? #{:opaque} (vals opts)))
           own? (or (nil? (:ws-id opts)) (= ws-id (str (:ws-id opts))))]
       (cond
         (generic-appends task)
         (and readable? own? (= :intent (try (keyword (:kind opts)) (catch Exception _ nil)))
              (contains? grants :intent))

         (= "nido:workstream:pr:land" task)
         (and readable? own? (contains? grants :merge)
              (or (nil? (:pr opts)) (string? (:pr opts)))
              (under? dir (get-in state [:units unit :session :cwd])))

         :else false)))))

(defn ^{:malli/schema [:=> [:cat :map [:? [:maybe :map]]] [:maybe :string]]}
  refusal
  "Why a Bash command must not run, or nil: a person's act — an intent, an answer, a landing, a PR
   merge or a close — run in a turn the core runs, judged over what the commit gate reads the
   command to do, unless an unspent grant in that workstream's core state admits it (`granted?`).
   A turn the core does not run is the person's, and its acts are allowed. `env` is the hook's
   environment (default the process's). Fails open when the core cannot be read."
  ([input] (refusal input nil))
  ([{:keys [tool_name tool_input cwd]} env]
   (let [env (or env (into {} (System/getenv)))
         k   (get env "NIDO_CORE_WS")]
     (when (and k (= "Bash" tool_name) (string? (:command tool_input)))
       (when-let [[p ws-id] (wcore/split-key k)]
         (let [acts  (gate/acts (:command tool_input) cwd)
               state (delay (try (wcore/state p ws-id) (catch Exception _ nil)))
               turn  (delay {:ws-id ws-id :unit (get env "NIDO_CORE_UNIT") :state @state})]
           (when-let [[a act] (some #(when-let [phrase (persons-act %)]
                                       (when-not (and @state (granted? @turn %)) [% phrase]))
                                    acts)]
             (cond
               (and (landing-act? a) (contains? (:grants @state) :merge))
               (str "Blocked: " act " is the person's to do, even under their grant. The grant they "
                    "gave lands this workstream by its project's declared procedure through `bb "
                    "--config <nido>/bb.edn nido:workstream:pr:land`, run in the unit's working copy.")
               :else
               (str "Blocked: " act " is the person's to do, and this is a turn nido runs. Ask the "
                    "person instead, with nido's ask tool: say what they need to do and why.")))))))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  gate-cmd [& _args]
  (if-let [reason (when-let [in (hook-input)] (or (gate/verdict in) (refusal in)))]
    (do (binding [*out* *err*] (println reason))
        (System/exit 2))
    (System/exit 0)))
