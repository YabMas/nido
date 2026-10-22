(ns nido.coordinator.agent.substrate
  "Claude Code background sessions, as nido uses them: start one, see them all, message one, wake
   one, stop one, restart one, and make a directory one may run in.

   What this HIDES is everything Claude Code calls a research preview — the `--bg` and
   `agents --json` command lines and the listing's shape, where a live session's inbox socket is
   and what a message on it looks like, which resume continues a session in place rather than
   copying it, the workspace-trust dialog and where its answer is kept, and the minimum version
   all of that was observed on. When Claude Code moves, this namespace is the edit.

   It decides NOTHING about a session: whether to message it, whom it belongs to, what its turn
   meant. That is the driver's. No other nido namespace starts, lists, messages, wakes, stops or
   restarts a Claude Code session itself."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def min-version
  "The oldest Claude Code every behaviour here was observed on — dispatch, the listing, inbox
   delivery, in-place wake, supervisor restart. Below it nido dispatches nothing."
  [2 1 296])

(defn- claude!
  "Run a claude command line to completion with no stdin, in `opts`'s :dir when given, as
   {:exit :out :err}. The one place this namespace runs the CLI, so a test substitutes it here."
  ([cmd] (claude! cmd {}))
  ([cmd {:keys [dir]}]
   (let [r (apply p/shell (cond-> {:in "" :out :string :err :string :continue true}
                            dir (assoc :dir dir))
                  cmd)]
     {:exit (:exit r) :out (:out r) :err (:err r)})))

(defn ^{:malli/schema [:=> [:cat :string] [:maybe [:vector :int]]]}
  parse-version
  "The [major minor patch] a `claude --version` line names, or nil when it names none."
  [s]
  (when-let [[_ a b c] (re-find #"(\d+)\.(\d+)\.(\d+)" (str s))]
    [(parse-long a) (parse-long b) (parse-long c)]))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  version-check
  "Whether the installed Claude Code is at least `min-version`, as {:ok? :installed :required}."
  [{:keys [claude-bin] :or {claude-bin "claude"}}]
  (let [v (parse-version (:out (claude! [claude-bin "--version"])))]
    {:ok?       (boolean (and v (>= (compare v min-version) 0)))
     :installed v
     :required  min-version}))

(defn ^{:malli/schema [:=> [:cat :map] [:sequential :map]]}
  listing
  "Every session the supervisor knows — interactive and background, finished ones included — as
   Claude Code reports it: :id (short) and :sessionId, :cwd, :kind, :name, :state for a background
   session (working blocked done failed stopped), and while its process lives :pid, :status (busy
   waiting idle) and :waitingFor. Starts the supervisor when it is not running. Throws when the
   listing cannot be read, rather than answering that there are no sessions."
  [{:keys [claude-bin] :or {claude-bin "claude"}}]
  (let [{:keys [exit out err]} (claude! [claude-bin "agents" "--json" "--all"])]
    (when-not (zero? exit)
      (throw (ex-info "claude agents --json failed" {:reason :listing-failed :exit exit :err err})))
    (json/parse-string out true)))

(def ^:private listing-ttl-ms
  "How long a listing read for display stays good, counted from when the read finished. A board
   renders every workstream at once; one read serves the render rather than one each."
  5000)

(defonce ^:private !last-listing
  ;; A delay of {:at :rows}: unrealized while its read is in flight.
  (atom nil))

(defn ^{:malli/schema [:=> [:cat :map] [:sequential :map]]}
  listing-cached
  "`listing`, served from the last read when it is under five seconds old — for display, never
   for a decision to message or wake a session. An unreadable listing answers empty here.

   A caller arriving while a read is in flight waits for that read rather than starting its own.
   Each read is a `claude` process, and a read slows as load rises: without the wait, every
   render landing during a slow read started another, which slowed the next — 2026-10-22's
   dashboard ran ~34 at once at a load of 176."
  [opts]
  (let [stale? (fn [d] (or (nil? d)
                           (and (realized? d)
                                (>= (- (System/currentTimeMillis) (:at @d)) listing-ttl-ms))))
        read   (delay (let [rows (try (listing opts) (catch Exception _ []))]
                        {:at (System/currentTimeMillis) :rows rows}))]
    (:rows @(swap! !last-listing #(if (stale? %) read %)))))

(defn- background-id
  "The short id a `--bg` launch printed — `backgrounded · <id> · <name>` — or nil."
  [out]
  (second (re-find #"backgrounded · ([0-9a-f]{8})" (str out))))

(defn- copy-note
  "The id of the copy a resume started instead of continuing the session, from the `note:` line
   Claude Code prints when it does, or nil."
  [out]
  (second (re-find #"started a copy as ([0-9a-f]{8})" (str out))))

(def ^:private listing-attempts
  "How many listings `dispatch!` reads for a session it just started before giving up on it."
  5)

(defn- pause! [ms] (Thread/sleep (long ms)))

(defn- listed-session-id
  "The full session id the listing reports for the background session `bg-id`, reading the
   listing again while it does not show one yet, or nil when it never does."
  [bg-id opts]
  (loop [n 1]
    (or (:sessionId (first (filter #(= bg-id (:id %)) (listing opts))))
        (when (< n listing-attempts)
          (pause! 500)
          (recur (inc n))))))

(defn- running-through
  "Run `f`, which follows the start of process `id` and precedes any confirmation that it stopped:
   whatever escapes `f` names `id` as :left-running. Where a process is left running is derived
   from this scope, never from a failure's reason."
  [id f]
  (try (f)
       (catch Exception e
         (throw (ex-info (or (ex-message e) "failed with a process left running")
                         (assoc (ex-data e) :left-running id) e)))))

(defn- trust-refusal? [s] (str/includes? (str s) "Workspace not trusted"))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  dispatch!
  "Start a background session in `:cwd` on `:prompt`, as {:bg-id :session-id}. `:name`, `:model`,
   `:system-prompt` (appended to Claude Code's own), `:mcp-config` and `:add-dirs` are the run's
   context and are passed through; nothing of the launch baseline is — the directory's settings
   carry it. The system prompt is recorded with the conversation on its first request, so a wake
   keeps it.

   Throws, naming the cause, below `min-version` (:reason :claude-too-old) and in a directory
   Claude Code does not trust (:reason :workspace-untrusted) — nothing is started then. Throws
   :reason :session-unlisted, with the :bg-id, when the session started but the listing never
   named its full id: the session runs, and no stand-in id is answered for it.

   Every failure here, and in wake!, that leaves a process running names it as :left-running in
   its ex-data — any failure between a process starting and its stop being confirmed, a listing
   that cannot be read included; one without that key started nothing that still runs."
  [{:keys [cwd prompt name model system-prompt mcp-config add-dirs claude-bin]
    :or {claude-bin "claude"} :as opts}]
  (let [v (version-check opts)]
    (when-not (:ok? v)
      (throw (ex-info "Claude Code is older than the substrate needs"
                      (assoc v :reason :claude-too-old)))))
  (let [cmd (cond-> [claude-bin "--bg"]
              name  (into ["--name" (str name)])
              model (into ["--model" (str model)])
              system-prompt (into ["--append-system-prompt" system-prompt])
              mcp-config (into ["--mcp-config" mcp-config])
              (seq add-dirs) (into (mapcat (fn [d] ["--add-dir" d]) add-dirs))
              :always (into ["--" prompt]))
        {:keys [exit out err]} (claude! cmd {:dir cwd})]
    (cond
      (trust-refusal? (str out err))
      (throw (ex-info (str "Claude Code does not trust " cwd)
                      {:reason :workspace-untrusted :dir cwd}))
      (or (not (zero? exit)) (nil? (background-id out)))
      (throw (ex-info "claude --bg did not start a session"
                      {:reason :dispatch-failed :exit exit :out out :err err}))
      :else
      (let [id (background-id out)]
        (running-through
         id
         #(if-let [sid (listed-session-id id opts)]
            {:bg-id id :session-id sid}
            (throw (ex-info (str "claude --bg started " id " but the listing never named its session")
                            {:reason :session-unlisted :bg-id id}))))))))

(defn- socket-path
  "Where a live session's inbox is: Claude Code binds `<pid>.sock` in its per-user socket
   directory, `/tmp/cc-socks` or `/tmp/cc-socks-<uid>` when the shared one is refused."
  [pid]
  (first (filter fs/exists?
                 [(str "/tmp/cc-socks/" pid ".sock")
                  (str "/tmp/cc-socks-" (str/trim (:out (p/shell {:out :string} "id" "-u"))) "/" pid ".sock")])))

(defn- post!
  "Write one user message line to a Unix socket. Answers whether it was written."
  [path text]
  (try
    (let [addr (java.net.UnixDomainSocketAddress/of ^String path)]
      (with-open [ch (java.nio.channels.SocketChannel/open java.net.StandardProtocolFamily/UNIX)]
        (.connect ch addr)
        (let [line (str (json/generate-string {:type    "user"
                                               :message {:role "user" :content text}})
                        "\n")
              buf  (java.nio.ByteBuffer/wrap (.getBytes line "UTF-8"))]
          (while (.hasRemaining buf) (.write ch buf))
          true)))
    (catch Exception _ false)))

(defn ^{:malli/schema [:=> [:cat :map :string] :map]}
  send!
  "Deliver `text` to a session's live process as a message on its inbox, given the session as the
   listing reports it. Never stops, restarts or copies the session. Answers {:delivered? bool}
   and, when it was not, why: :no-process when the listing shows none, :no-inbox when the
   process has none, :write-failed otherwise. Delivery is the inbox's acceptance, not the
   session reading it — the session's own prompt event says that."
  [{:keys [pid]} text]
  (cond
    (nil? pid) {:delivered? false :because :no-process}
    :else (if-let [path (socket-path pid)]
            (if (post! path text)
              {:delivered? true}
              {:delivered? false :because :write-failed})
            {:delivered? false :because :no-inbox})))

(defn ^{:malli/schema [:=> [:cat :string :string :map] :map]}
  wake!
  "Continue the session `session-id`, which has no live process, in the background under its own
   id with `text` as its next prompt, run from `opts`'s :cwd — the directory the listing shows it
   in, where Claude Code finds its conversation and the session loads that directory's settings.
   Answers {:woke? true} or, when Claude Code started a copy
   instead — it does when the session turned out to be running — {:woke? false :copy <id>}, the
   copy stopped and removed again so nothing runs beside the session. Throws :reason
   :copy-cleanup-failed, naming the copy and the command Claude Code refused, when it was not —
   with the copy as :left-running only while its stop is unconfirmed."
  [session-id text {:keys [claude-bin cwd] :or {claude-bin "claude"}}]
  (let [{:keys [exit out err]} (claude! [claude-bin "--resume" session-id "--bg" "--" text] {:dir cwd})]
    (if-let [copy (copy-note (str out err))]
      (let [cleanup! (fn [verb]
                       (let [r (claude! [claude-bin verb copy])]
                         (when-not (zero? (:exit r))
                           (throw (ex-info (str "claude --resume started a copy " copy " that could not be removed")
                                           {:reason :copy-cleanup-failed :copy copy :failed {(keyword verb) r}})))))]
        (running-through copy #(cleanup! "stop"))
        (cleanup! "rm")
        {:woke? false :copy copy})
      (if (zero? exit)
        {:woke? true}
        (throw (ex-info "claude --resume --bg failed"
                        {:reason :wake-failed :exit exit :out out :err err}))))))

(defn- confirmed!
  "Answer true when Claude Code accepted command `verb` on session `id`, and throw naming the
   refusal when it did not — so no caller can take a refused stop or restart for a done one."
  [verb id {:keys [exit out err]}]
  (if (zero? exit)
    true
    (throw (ex-info (str "claude " verb " " id " was refused")
                    {:reason (keyword (str verb "-refused")) :id id :exit exit :out out :err err}))))

(defn ^{:malli/schema [:=> [:cat :string :map] :boolean]}
  stop!
  "Stop a session's process; its conversation is kept. Answers true once Claude Code said so;
   throws :reason :stop-refused when it did not."
  [id {:keys [claude-bin] :or {claude-bin "claude"}}]
  (confirmed! "stop" id (claude! [claude-bin "stop" id])))

(defn ^{:malli/schema [:=> [:cat :string :map] :boolean]}
  respawn!
  "Restart a session from its saved conversation, run from `opts`'s :cwd when given — the
   directory the listing shows it in. Answers true once Claude Code said so; throws :reason
   :respawn-refused when it did not."
  [id {:keys [claude-bin cwd] :or {claude-bin "claude"}}]
  (confirmed! "respawn" id (claude! [claude-bin "respawn" id] {:dir cwd})))

(defn- claude-config-path []
  (str (fs/path (or (System/getenv "CLAUDE_CONFIG_DIR") (System/getProperty "user.home"))
                ".claude.json")))

(defn ^{:malli/schema [:=> [:cat :string] :boolean]}
  trusted?
  "Whether Claude Code has recorded trust for exactly `dir`. Trust is per directory and is not
   inherited from a trusted parent."
  [dir]
  (try
    (boolean (get-in (json/parse-string (slurp (claude-config-path)))
                     ["projects" (str (fs/canonicalize dir)) "hasTrustDialogAccepted"]))
    (catch Exception _ false)))

(def ^:private accept-trust-script
  "Answer Claude Code's trust dialog for the directory it starts in: wait for the dialog, move to
   the accepting choice (it is not the default), confirm, then leave. Exits 0 when the dialog was
   seen."
  (str "import os,pty,select,sys,time,re\n"
       "pid,fd=pty.fork()\n"
       "if pid==0:\n os.execvp(sys.argv[1],[sys.argv[1]])\n"
       "buf=b''\n"
       "def pump(t):\n global buf\n end=time.time()+t\n while time.time()<end:\n  r,_,_=select.select([fd],[],[],0.2)\n  if r:\n   try: buf+=os.read(fd,65536)\n   except OSError: return\n"
       "pump(8)\n"
       "seen=b'trust' in re.sub(rb'\\x1b\\[[0-9;?]*[a-zA-Z]',b'',buf).lower()\n"
       "try:\n if seen:\n  os.write(fd,b'\\x1b[B'); pump(1); os.write(fd,b'\\r'); pump(5)\n os.write(fd,b'/exit\\r'); pump(3)\nexcept OSError: pass\n"
       "try: os.kill(pid,15)\nexcept Exception: pass\n"
       "sys.exit(0 if seen else 3)\n"))

(defn ^{:malli/schema [:=> [:cat :string :map] :boolean]}
  ensure-trusted!
  "Make `dir` a directory Claude Code will run a background session in, by accepting its trust
   dialog there — the same answer a person gives. The CALLER vouches for the directory; this only
   gives the answer. Answers whether `dir` is trusted afterwards."
  [dir {:keys [claude-bin] :or {claude-bin "claude"}}]
  (or (trusted? dir)
      (do (p/shell {:dir dir :in "" :out :string :err :string :continue true}
                   "python3" "-I" "-c" accept-trust-script claude-bin)
          (trusted? dir))))

(defn ^{:malli/schema [:=> [:cat :string :map] :any]}
  attach!
  "Put the person in front of the background session `session-id`, in this terminal: this
   process becomes `claude attach`, and the session keeps running when they detach. Throws,
   starting nothing, when the listing shows no background session by that id."
  [session-id {:keys [claude-bin] :or {claude-bin "claude"} :as opts}]
  (let [row (first (filter #(and (= session-id (:sessionId %)) (= "background" (:kind %)))
                           (listing opts)))]
    (when-not row
      (throw (ex-info (str "Claude Code lists no background session " session-id " — see `claude agents`")
                      {:reason :not-listed :session-id session-id})))
    (p/exec [claude-bin "attach" (:id row)])))
