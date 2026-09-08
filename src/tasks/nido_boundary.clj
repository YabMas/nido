;; src/tasks/nido_boundary.clj
(ns tasks.nido-boundary
  "bb-task entrypoint: what nido asks for at a session's turn boundary.

   Run as a Claude Code `Stop` hook. It ASKS and never decides: the host
   resolves a turn across every hook that answered and grants a continuation any
   one of them requests, so exit 2 here is a vote and exit 0 is an abstention.
   Nido is therefore never the reason a session carries on past a person, and
   never the reason one stops either — a project's own Stop hook keeps its
   session going whatever this says, which is the arrangement invariant five
   deliberately preserves.

   IT RUNS NO STAGE. The smaller of the two claims that were open at design
   time: a boundary that ran what it found would be an actor, reviewable as one,
   and would take a working copy out from under whoever is typing in it. This
   says what is owed and lets the turn continue; running it stays `bb
   nido:attach`, which a person or a model asks for deliberately.

   FAILING OPEN IS THE CONTRACT. Every path that cannot decide — no workstream,
   a position the pipeline will not place, a ledger it cannot read, an exception
   of any kind — asks for nothing, and a turn nobody asked to continue ends as
   it does today. A hook that breaks a session it cannot even resolve is worse
   than no hook."
  (:require
   [cheshire.core :as json]
   [nido.platform.task-args :as task-args]
   [tasks.nido-owed :as owed]))

(def ^:private default-wait-ms
  "How long a boundary held for a person keeps asking before it gives up.

   THIRTY MINUTES, and the number follows from what the wait is FOR. A session
   whose ledger says a person owes the next move has nothing else to do, so
   going quiet is the wanted behaviour rather than a cost to be minimised — and
   the only thing a long wait takes away is the ability to talk to that session
   about something else, which one interrupt gives back. What a SHORT wait takes
   away is the whole feature: grant the approval after it lapsed and nothing
   picks it up, so you are back to typing into the session, which is what this
   exists to stop.

   Well inside the host's own default of 600 seconds? It is not — the settings
   entry raises the hook `timeout` above this deliberately, so the wait ends
   HERE, on its own terms, rather than by being killed. A killed hook has its
   output discarded, which reaches the same answer far less legibly. The host
   documents no ceiling on that field."
  1800000)

(def ^:private default-poll-ms
  "How often the wait re-folds the ledger. Nothing is armed, scheduled or
   remembered between polls: the fold is the whole state, so a session that dies
   mid-wait leaves nothing for a later reader to reconcile."
  3000)

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] :map]}
  asks-for
  "What nido asks of this boundary, given what the workstream owes.

   Pure over the answer, and the whole of the hook's judgement. `:wait` is not
   an answer the caller can act on — it is the loop's instruction to fold
   again — so it never reaches an exit code."
  [answer]
  (cond
    (nil? answer)             {:ask :nothing :because :no-workstream}
    ;; Before the position, because a workstream something else is advancing
    ;; owes this caller nothing: waiting would put a person's session to sleep
    ;; because a robot is busy, and the claim excludes claim-takers rather than
    ;; whoever is typing in the tree.
    (:held-by answer)         {:ask :nothing :because :claimed}
    (= :unplaceable (:at answer)) {:ask :nothing :because :unplaceable}
    (nil? (:stage answer))    {:ask :nothing :because :terminal}
    (:person answer)          {:ask :wait :because :a-person-owes-it}
    :else                     {:ask :continue :say (owed/owed-line answer)}))

(defn ^{:malli/schema [:=> [:cat :any [:? :map]] :map]}
  ask-at
  "Fold the ledger, and keep folding while a person owes the next move.

   The wait is the same question asked again in the same process until the
   answer changes — an approval is an append, and the next fold reads it. That
   is what carries a session across a stage a person owes with nothing typed
   into it, and it is why nothing is delivered to a waiting session: releasing
   on a re-read rather than on a message means a session that dies mid-wait
   leaves no state behind and the next turn asks in the same place."
  ([cwd] (ask-at cwd {}))
  ([cwd {:keys [wait-ms poll-ms sleep-fn now-fn]
         :or   {wait-ms  default-wait-ms
                poll-ms  default-poll-ms
                sleep-fn #(Thread/sleep %)
                now-fn   #(System/currentTimeMillis)}}]
   (let [deadline (+ (now-fn) wait-ms)]
     (loop []
       (let [d (asks-for (owed/owed-at cwd))]
         (cond
           (not= :wait (:ask d)) d
           (< (now-fn) deadline) (do (sleep-fn poll-ms) (recur))
           ;; The request is WITHDRAWN, not the turn ended. Whether it ends is
           ;; the host's, across every hook that answered.
           :else {:ask :nothing :because :waited-out}))))))

(defn- hook-cwd
  "The cwd to fold from: the Stop hook's own JSON on stdin, else what the caller
   named, else where this process is.

   Read from stdin because that is how the host says where a session is
   standing, and a hook that guessed would answer about a different workstream.

   ONLY WHEN THERE IS NO CONSOLE, and the guard is not defensive — without it
   this verb hangs a terminal. `slurp` reads to EOF, so an interactive shell
   leaves it waiting for a Ctrl-D that a person running `bb nido:boundary` to
   see what it says has no reason to send. A hook is spawned with no console and
   a pipe that closes after the JSON; a terminal has one. Checking the console
   rather than whether bytes are ready is what makes it a fact about the caller
   instead of a race with them."
  [opts]
  (or (when (nil? (System/console))
        (try (some-> (json/parse-string (slurp *in*) true) :cwd)
             (catch Throwable _ nil)))
      (:cwd opts)
      (System/getProperty "user.dir")))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  boundary-cmd*
  "Ask, then exit the way a Stop hook is heard: 2 with the reason on stderr to
   request a continuation, 0 to ask for nothing.

   Every throw is caught and answered with 0. A hook cannot be allowed to break
   a session by failing — see the namespace docstring."
  [opts]
  (let [d (try (ask-at (hook-cwd opts) opts)
               (catch Throwable t
                 {:ask :nothing :because :threw :detail (ex-message t)}))]
    (when (= :continue (:ask d))
      (binding [*out* *err*] (println (:say d))))
    d))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  boundary-cmd [& args]
  (let [[_ opts] (task-args/split-args args)
        d        (boundary-cmd* opts)]
    (System/exit (if (= :continue (:ask d)) 2 0))))
