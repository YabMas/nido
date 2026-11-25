(ns nido.executor.claude
  "The Claude Code executor: a turn's run is one `claude -p` process, and everything Claude-specific
   stays here.

   A turn's first run records a new session under an id this adapter chooses and keeps in the
   turn's directory; a later run resumes it with `--resume` and no prompt, which re-offers the
   deferred call. The agent asks through nido's ask tool (`nido.core.ask-tool`), and a PreToolUse
   hook on that tool decides it:
     - the call is the turn's resolved call: allow it, with the resolution as its answer
     - any other call: defer it, which ends the run with the call pending
   The hook is this namespace's -main, run under bb, and reads only the run's own directory.

   Claude does not always exit at a deferral: while a background task it started (a Monitor, a
   backgrounded shell) is alive it waits, and that task's notification wakes the agent again with
   the deferred call answered as an internal error. So every run is supervised (`supervise`), which
   ends Claude and everything it started at the first deferred result in its output. Should a run
   still go on past its deferred ask, the ask is its outcome all the same, and the resumption gives
   the agent the resolution as a prompt, since no call is left pending to re-offer.

   Every run is passed the same permission policy, because `--resume` restores none: permissions
   skipped, nothing nobody could answer (AskUserQuestion, ScheduleWakeup), and only the user's and
   project's own settings beneath the run's. A read-only turn (`:read-only?` on its spec) has a
   second PreToolUse hook, which refuses every file-editing tool pointed into its working copy.

   A turn's first run is prompted with the spec's `:instructions`, followed, when the spec names
   `:images` (digests), by one image block per image in order, read through the executor's `:image`
   option. A prompt with images goes in as one stream-json user message, written among the run's
   files and redirected to the run's stdin by its own command line — the runner gives every run
   /dev/null.

   Loads under bb as well as the JVM, because the hook runs under bb."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [nido.core.ask-tool :as ask-tool])
  (:import [java.lang ProcessHandle]
           [java.util UUID]))

(def ^:private ask-tool-name
  "The ask tool as Claude names it: server `nido`, tool `ask`."
  "mcp__nido__ask")

(def ^:private never-offered
  "Tools a run never gets: AskUserQuestion opens a dialog no one is at the terminal to answer,
   and ScheduleWakeup schedules a wakeup nothing delivers."
  ["AskUserQuestion" "ScheduleWakeup"])

(def ^:private file-editors
  "The tools that change files, which a read-only turn (`:read-only?` on its spec) may not point at
   its working copy: a hook guards them rather than their being withheld, because a skill writes
   its reports to files elsewhere."
  "Edit|MultiEdit|Write|NotebookEdit")

(defn- src-dir
  "The source root this namespace was loaded from, which the hook and the ask tool run from."
  []
  (some-> (io/resource "nido/executor/claude.clj") io/file .getParentFile .getParentFile .getParentFile str))

(defn- session-file [turn-dir] (io/file turn-dir "claude-session-id"))
(defn- resolution-file [run-dir] (io/file run-dir "resolution.edn"))
(defn- stdin-file [run-dir] (io/file run-dir "in.jsonl"))

(defn- prompt-message
  "The stream-json user message prompting with `text` and the images `images` names, each read by
   `image`; an image no longer kept is said in words in its place."
  [image text images]
  (json/write-str
   {:type "user"
    :message {:role "user"
              :content (into [{:type "text" :text text}]
                             (for [digest images
                                   :let [{:keys [mime data]} (when image (image digest))]]
                               (if data
                                 {:type "image" :source {:type "base64" :media_type mime :data data}}
                                 {:type "text" :text (str "(image " digest " is no longer kept)")})))}}))

(defn- resolution-text
  "What the agent reads as the ask's answer. A withdrawn ask resolves as :refused, with the
   reason it was withdrawn for: the person's words, or a keyword such as :taken-over."
  [{:keys [answered returned refused]}]
  (cond
    answered (str "Answer: " answered)
    returned (str "Not answered yet. Explore further as follows, then ask again only if you still need to: "
                  returned)
    refused  (str "Withdrawn without an answer: "
                  (if (keyword? refused) (name refused) refused))
    :else    "No answer was given."))

(declare result-events deferred-ask?)

(defn- previous-run-dir
  "The directory of the run before `run` of the turn in `turn-dir`. The layout is
   `nido.core.turn/run-dir`'s, which this namespace does not load: the hook runs it under bb."
  [turn-dir run]
  (io/file turn-dir (str "run-" (dec run))))

(defn- call-pending?
  "Whether the run in `run-dir` ended at its deferral of call `call`, so that resuming re-offers it.
   A run whose output is not there to read is taken to have ended so."
  [run-dir call]
  (let [results (result-events run-dir)]
    (or (empty? results)
        (let [r (last results)] (and (deferred-ask? r) (= call (get-in r [:deferred_tool_use :id])))))))

(defn- resumption-prompt
  "What a resumed run is told when the agent went on past its ask, which so has no call to
   re-offer: that the ask reached the person all the same, and how it was resolved."
  [resolved-call resolution]
  (str "Your earlier ask (call " resolved-call ") did reach the person, whatever the ask tool "
       "returned to you at the time: nido held it, and it has now been resolved.\n\n"
       (resolution-text resolution)
       "\n\nCarry on from there."))

(defn- launch
  "What to start for `run`. See `nido.core.turn` for `run`."
  [{:keys [claude-bin bb-bin src add-dirs image] :or {claude-bin "claude" bb-bin "bb"}}
   {:keys [turn-dir run-dir run spec resolution resolved-call]}]
  (let [src (or src (src-dir))
        sid (if (= 1 run)
              (str (UUID/randomUUID))
              (str/trim (slurp (session-file turn-dir))))
        images (when (= 1 run) (seq (:images spec)))
        prompt (when (and (< 1 run) resolution
                          (not (call-pending? (previous-run-dir turn-dir run) resolved-call)))
                 (resumption-prompt resolved-call resolution))
        settings (str (io/file run-dir "settings.json"))
        mcp      (str (io/file run-dir "mcp.json"))]
    {:argv (cond-> [bb-bin "--classpath" src "-m" "nido.executor.claude" "supervise"
                    claude-bin "-p" "--verbose" "--output-format=stream-json"
                    "--setting-sources" "user,project"
                    "--dangerously-skip-permissions"
                    "--mcp-config" mcp
                    "--settings" settings]
             ;; Variadic too: the option after it ends its list.
             (seq add-dirs) (into (cons "--add-dir" add-dirs))
             (:model spec) (into ["--model" (:model spec)])
             ;; Variadic: the next option ends its list.
             true          (into (cons "--disallowedTools"
                                       (cond-> never-offered (false? (:ask? spec)) (conj ask-tool-name))))
             (= 1 run)     (into ["--session-id" sid])
             (< 1 run)     (into ["--resume" sid])
             images        (into ["--input-format" "stream-json"])
             ;; `--` keeps the prompt from being read as one more value of a variadic option.
             (and (= 1 run) (not images)) (into ["--" (:instructions spec)])
             prompt        (into ["--" prompt])
             ;; The prompt with images is read from a file of the run's own: the shell redirects it
             ;; onto stdin, then execs Claude in its place.
             images        (->> (into ["/bin/sh" "-c" "f=$1; shift; exec \"$@\" < \"$f\"" "sh"
                                       (str (stdin-file run-dir))])))
     :env {"CLAUDE_CODE_DISABLE_CRON" "1"}
     :files (cond-> {settings (json/write-str
                               {:hooks {:PreToolUse
                                        (cond-> [{:matcher ask-tool-name
                                                  :hooks [{:type "command"
                                                           :command (str bb-bin " --classpath " src
                                                                         " -m nido.executor.claude hook " run-dir)}]}]
                                          (and (:read-only? spec) (:cwd spec))
                                          (conj {:matcher file-editors
                                                 :hooks [{:type "command"
                                                          :command (str bb-bin " --classpath " src
                                                                        " -m nido.executor.claude guard " (:cwd spec))}]}))}})
                     ;; nido's ask tool, unless the spec says the turn has nobody to ask (`:ask?`
                     ;; false: a run's), and any MCP servers the turn's spec brings (a conductor's
                     ;; tools): MCP is no provider's, so a spec may name servers and stay neutral.
                     mcp (json/write-str
                          {:mcpServers (cond-> (or (:mcp-servers spec) {})
                                         (not (false? (:ask? spec)))
                                         (assoc "nido" {:command bb-bin
                                                        :args ["--classpath" src "-m" "nido.core.ask-tool"]}))})}
              (= 1 run)  (assoc (str (session-file turn-dir)) sid)
              images     (assoc (str (stdin-file run-dir)) (str (prompt-message image (:instructions spec) images) "\n"))
              resolution (assoc (str (resolution-file run-dir))
                                (pr-str {:call resolved-call :text (resolution-text resolution)})))}))

(defn- result-events
  "Every `result` event in the run's stream-json output, in order: one per time the agent stopped,
   and Claude may wake it again after one."
  [run-dir]
  (let [f (io/file run-dir "out")]
    (when (.exists f)
      (with-open [r (io/reader f)]
        (->> (line-seq r)
             (keep #(try (let [o (json/read-str % :key-fn keyword)] (when (= "result" (:type o)) o))
                         (catch Exception _ nil)))
             vec)))))

(defn- deferred-ask? [{:keys [stop_reason deferred_tool_use]}]
  (and (= "tool_deferred" stop_reason) (= ask-tool-name (:name deferred_tool_use))))

(defn- outcome
  "The outcome a finished run's output carries, or nil when it carries none. A deferred ask is the
   outcome wherever the run stopped at it, also when the agent was woken past it: the ask stands
   until the person resolves it, and a run reading as anything else would leave it unseen."
  [run-dir]
  (let [results (result-events run-dir)
        ask (last (filter deferred-ask? results))]
    (when-let [{:keys [stop_reason deferred_tool_use is_error subtype result]} (peek results)]
      (cond
        ask
        {:outcome :asked
         :call (assoc (ask-tool/content (get-in ask [:deferred_tool_use :input]))
                      :id (get-in ask [:deferred_tool_use :id]))}

        (str/starts-with? (str stop_reason) "tool_deferred")
        {:outcome :failed :detail {:reason :deferred-another-tool :tool (:name deferred_tool_use)}}

        (or is_error (not= "success" subtype))
        {:outcome :failed :detail {:reason :error :message (str result)}}

        :else
        {:outcome :completed :detail {:summary (str result)}}))))

(defn- attach
  "What a person runs, in the turn's working directory, to enter its session."
  [{:keys [claude-bin] :or {claude-bin "claude"}} turn-dir]
  (let [f (session-file turn-dir)]
    (when (.exists f) [claude-bin "--resume" (str/trim (slurp f))])))

(defn ^{:malli/schema [:=> [:cat :map] :map]} executor
  "The Claude Code executor, for `nido.core.turn`. Options: :claude-bin, :bb-bin, :src (the source
   root the hook and ask tool run from; default, the one this namespace loaded from), :add-dirs
   (directories every run can reach, whose `.claude/skills` it loads: a turn runs in a bare
   worktree, so nido's own skills — the ones a trigger names — reach it only this way), :image
   ((fn [digest] -> {:mime :data} or nil), which reads the images a spec names)."
  [opts]
  {:launch  (partial launch opts)
   :outcome outcome
   :attach  (partial attach opts)})

;; ── the hook ─────────────────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :string :map] :map]} hook-decision
  "The PreToolUse hook's answer for `event` (Claude's hook input) in run directory `run-dir`."
  [run-dir event]
  (let [{:keys [tool_use_id tool_input]} event
        f (resolution-file run-dir)
        r (when (.exists f) (edn/read-string (slurp f)))]
    {:hookSpecificOutput
     (merge {:hookEventName "PreToolUse"}
            (if (and r (= tool_use_id (:call r)))
              {:permissionDecision "allow"
               :updatedInput (assoc tool_input :answer (:text r))}
              {:permissionDecision "defer"
               :permissionDecisionReason "nido holds this ask"}))}))

(defn- canonical [base p]
  (let [f (io/file p)]
    (.getCanonicalPath (if (.isAbsolute f) f (io/file (or base ".") p)))))

(defn- guard-decision
  "The read-only hook's answer for `event`: a denial when the file-editing tool it names points
   into working copy `cwd`, else nil."
  [cwd {:keys [tool_input] :as event}]
  (when-let [p (or (:file_path tool_input) (:notebook_path tool_input))]
    (let [root (canonical nil cwd)
          path (canonical (:cwd event) p)]
      (when (or (= path root) (str/starts-with? path (str root "/")))
        {:hookSpecificOutput
         {:hookEventName "PreToolUse"
          :permissionDecision "deny"
          :permissionDecisionReason
          (str "nido's recipe holds this workstream's code until its design is cleared: this turn "
               "may not change files in its working copy. Write what the recipe owes through nido's "
               "verbs instead.")}}))))

;; ── the supervisor ───────────────────────────────────────────────────────────

(defn- deferred-line?
  "Whether `line` of Claude's output is a result that stopped at a deferred call."
  [line]
  (and (str/includes? line "tool_deferred")
       (try (let [o (json/read-str line :key-fn keyword)]
              (and (= "result" (:type o)) (str/starts-with? (str (:stop_reason o)) "tool_deferred")))
            (catch Exception _ false))))

(defn- end-tree!
  "End process `ph` and everything it started. The descendants are read first, so none is orphaned
   out of reach, and `ph` ends first, so none's ending can wake it."
  [^ProcessHandle ph]
  (let [ds (vec (iterator-seq (.iterator (.descendants ph))))]
    (.destroyForcibly ph)
    (doseq [^ProcessHandle d ds] (.destroyForcibly d))))

(defn ^{:malli/schema [:=> [:cat [:sequential :string]] :int]} supervise
  "Run `argv` with its output passed through to this process's, line by line, and end it with
   everything it started at its first result that stopped at a deferred call: Claude stays alive
   past a deferral while a background task of its runs, and that task's end wakes the agent with
   the deferred call answered as an error. Answers the exit code."
  [argv]
  (let [p (-> (ProcessBuilder. ^java.util.List (vec argv))
              (.redirectInput java.lang.ProcessBuilder$Redirect/INHERIT)
              (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT)
              .start)]
    (with-open [r (io/reader (.getInputStream p))]
      (loop []
        (when-let [line (.readLine ^java.io.BufferedReader r)]
          (println line)
          (flush)
          (if (deferred-line? line)
            (end-tree! (.toHandle p))
            (recur)))))
    (.waitFor p)))

(defn ^:export -main
  "`hook <run-dir>`: answer one PreToolUse event on stdin. `guard <cwd>`: refuse a file edit into
   working copy `cwd`, for a read-only turn. `supervise <argv…>`: run Claude, ending it at its
   first deferral."
  [& [cmd arg & more]]
  (case cmd
    "hook"      (let [event (json/read-str (slurp *in*) :key-fn keyword)]
                  (println (json/write-str (hook-decision arg event))))
    "guard"     (when-let [d (guard-decision arg (json/read-str (slurp *in*) :key-fn keyword))]
                  (println (json/write-str d)))
    "supervise" (System/exit (supervise (cons arg more)))
    nil))
