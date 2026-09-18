(ns nido.review.claude
  "Claude as a read-only reviewer, behind the contract `nido.review.codex/run-codex!` already
   keeps: a prompt and a JSON schema in, the structured answer written to :out-path, the model's
   whole stream in :log-path, `{:exit <int>}` back. A round reads the answer file and the log and
   cannot tell which reviewer produced them, which is what lets one stand in for the other.

   READ-ONLY IS ENFORCED BY THE CLI, not asked for in the prompt. codex gets it from its sandbox;
   here it is `--restricted`, which ignores the user's and the project's settings files — so an
   `allow` rule written for interactive work cannot widen a reviewer — together with an
   allowlist of tools and `dontAsk`, which denies what neither the list nor the CLI's own
   read-only classification (`find`, `wc`, …) admits, rather than waiting for a person who is not
   there. A denied write reaches the model as a refusal it has to work around; it never reaches
   the tree."
  (:require
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [nido.platform.process :as nprocess]))

(def default-model
  "The model a claude review runs on when the caller names none."
  "opus")

(def ^:private allowed-commands
  "The shell commands a claude reviewer is granted beyond those the CLI already runs as
   read-only: the two `jj` reads `review_prompt.md` gives a reviewer to see the change with, and
   `grep` to filter what they print. A reviewer pipes
   `jj file show` into grep as a matter of course, and refused, the pipe costs it a turn.

   Not `rg`, `sed` or `cat`, which the prompt also names: the Read, Grep and Glob tools answer the
   same questions with no way to write, while `rg --pre` runs a program and `sed -i` edits in
   place. `tool-note` tells the model so, because a reviewer that discovers the limit by being
   refused spends a turn per refusal."
  ["Bash(jj --ignore-working-copy diff:*)"
   "Bash(jj --ignore-working-copy file show:*)"
   "Bash(grep:*)"])

(def ^:private tool-note
  "What a claude reviewer is told about its tools, after the prompt it shares with codex. That
   prompt was written for codex's sandbox, where any read-only shell command runs."
  (str "\n\nTOOLS IN THIS REVIEW: your shell runs only `jj --ignore-working-copy diff …`,\n"
       "`jj --ignore-working-copy file show …` and `grep` (to filter their output). Use the Grep\n"
       "and Glob tools where the text above says `rg`, and the Read tool where it says `sed` or\n"
       "`cat`. Anything else is refused.\n"))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  claude-argv
  "The `claude --print` invocation as [opts & args] for p/process, the same shape as
   `codex/codex-argv`. Pure, so it is unit-testable.

   `schema` is the schema's JSON text, not its path: claude takes it inline. The prompt goes in on
   stdin, where its size is not bounded by the argument list. stdout is the stream-json transcript
   and goes to :log-path, with stderr merged in; `run-claude!` reads the answer back out of it."
  [{:keys [cwd schema log-path prompt model claude-bin]}]
  (into [{:dir cwd :continue true :in (str prompt tool-note)
          :out :write :out-file (io/file log-path) :err :out}
         (or claude-bin "claude") "--print"
         ;; stream-json in --print mode is refused without --verbose.
         "--verbose" "--output-format=stream-json"
         "--model" (or model default-model)
         "--restricted" "--strict-mcp-config" "--no-session-persistence"
         "--permission-mode" "dontAsk"
         "--tools" "Bash,Read,Grep,Glob"
         "--json-schema" schema
         ;; Last, because the flag is variadic and would take whatever option followed it as one
         ;; more rule.
         "--allowedTools"]
        allowed-commands))

(defn- result-event
  "The stream's final `result` event, or nil when the run ended without one."
  [log-path]
  (try
    (with-open [r (io/reader log-path)]
      (->> (line-seq r)
           (keep #(when (str/starts-with? % "{")
                    (try (json/parse-string % true) (catch Exception _ nil))))
           (filter #(= "result" (:type %)))
           last))
    (catch Exception _ nil)))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  run-claude!
  "Run claude as a reviewer over `opts` — the keys `codex/run-codex!` takes, plus an optional
   :model. Returns {:exit <int>}, and writes the structured answer to :out-path only when the run
   produced one. A run that did not leaves :out-path absent, which is how both callers already
   tell a failed review from an empty one.

   A failed run's own words are appended to the log as a plain last line. The stream buries them
   in a JSON event, and the log's last lines are what `codex/unavailability` reads to decide
   whether a reviewer was out of quota or broke.

   Registered as a child for the same reason as `run-codex!`: a stopped loop must not leave its
   reviewer running and billing."
  [{:keys [schema-path out-path log-path] :as opts}]
  (let [[opts' & cmd] (claude-argv (assoc opts :schema (slurp schema-path)))
        proc (apply p/process opts' cmd)
        exit (nprocess/with-child-registered (:proc proc) #(:exit @proc))
        {:keys [is_error structured_output result]} (result-event log-path)]
    (if (and (zero? exit) (not is_error) (some? structured_output))
      (spit out-path (json/generate-string structured_output))
      (when-not (str/blank? (str result))
        (spit log-path (str "\n" (str/trim (str result)) "\n") :append true)))
    {:exit exit}))
