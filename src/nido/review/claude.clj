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
   the tree.

   `--restricted` also confines the file tools to the working directory, a copy of ONE project's
   tree, where codex's read-only sandbox reads the whole disk. A record that cites a second repo —
   brian's PDF path runs through document-service — then gets its claims on that repo refused on
   every claude round and never confirmed, and the loop can never pass it. So a claude reviewer
   is given, with `--add-dir`, the directories `read-roots` names. Settings files cannot do this:
   `--restricted` ignores them."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [nido.platform.core :as core]
   [nido.platform.process :as nprocess]
   [nido.platform.project :as project]
   [nido.session.lifecycle :as lifecycle]))

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
       "`cat`. Anything else is refused. The Read, Grep and Glob tools also reach, at their absolute\n"
       "paths, every checkout and worktree of every project nido drives (e.g. a service this repo\n"
       "calls), every repository this prompt names a path in, and nido's ledgers and runs under\n"
       "~/.nido/projects and ~/.nido/runs.\n"))

(defn- git-worktrees
  "The worktrees git knows of for the checkout at `dir`, the main one included. A worktree a
   person or an agent added by hand lives wherever they put it — BR-7085's document-service
   worktree sits beside the checkout, not under it — and git's own list is the one place every
   one of them is named. None when `dir` is no git checkout, or git cannot be run."
  [dir]
  (try
    (let [{:keys [exit out]} (p/shell {:out :string :err :string :continue true}
                                      "git" "-C" (str dir) "worktree" "list" "--porcelain")]
      (when (zero? exit)
        (keep #(second (re-find #"^worktree (.+)$" %)) (str/split-lines out))))
    (catch Exception _ nil)))

(defn- project-roots
  "For one registered project: its checkout, its session worktrees directory, and every git
   worktree of the checkout."
  [[project-name {:keys [directory]}]]
  (when directory
    (concat [directory]
            (some-> (try (lifecycle/worktrees-dir (name project-name) directory)
                         (catch Exception _ nil))
                    vector)
            (git-worktrees directory))))

(def ^:private cited-path
  "An absolute or home-relative path as a record writes one: `~/Code/x/src/a.clj:41` gives
   `~/Code/x/src/a.clj`. The line suffix and any closing punctuation are not part of it."
  #"(?:^|[\s\"'`(\[{<])(~(?:/[\w.@+-]+)+|(?:/[\w.@+-]+){2,})")

(defn ^{:malli/schema [:=> [:cat] :string]}
  home-dir
  "The home directory, which no cited repository may be at or above (`repo-root`)."
  []
  (str (fs/home)))

(defn- outermost
  "`dirs` without any that lies inside another of them: `--add-dir` grants a directory's whole
   subtree, and a project's checkout already holds the worktrees under it."
  [dirs]
  (reduce (fn [kept d]
            (if (some #(or (= % d) (str/starts-with? d (str % "/"))) kept) kept (conj kept d)))
          [] (sort-by count dirs)))

(defn- repo-root
  "The nearest directory at or above `path` holding a `.git` or `.jj` — the repository a cited
   file lives in — or nil when there is none short of the home directory. Walks up from the
   nearest part of `path` that exists, so a citation of a file since deleted still names its repo.
   Never the home directory or anything above it: a stray repository there would hand a reviewer
   every checkout on the machine on the strength of one citation."
  [path]
  (let [home (home-dir)]
    (loop [f (fs/absolutize (fs/expand-home path))]
      (when (and f (str/starts-with? (str f) (str home "/")))
        (if (and (fs/directory? f)
                 (or (fs/exists? (fs/path f ".git")) (fs/exists? (fs/path f ".jj"))))
          (str f)
          (recur (fs/parent f)))))))

(defn ^{:malli/schema [:=> [:cat [:maybe :string]] [:vector :string]]}
  cited-repos
  "The repository of every path `text` names that has one (`repo-root`). A design that changes two
   repos names files in both, and its reviewer is owed both however the second was checked out."
  [text]
  (->> (re-seq cited-path (str text))
       (keep (comp repo-root second))
       distinct
       vec))

(defn ^{:malli/schema [:=> [:cat [:maybe :string] [:? [:maybe :string]]] [:vector :string]]}
  read-roots
  "The directories a claude reviewer may read beyond its cwd:

   - every project nido drives: its checkout, its session worktrees directory, and every git
     worktree of the checkout wherever it was added;
   - the repository of every path `prompt` names (`cited-repos`), which is how a record reaches a
     repo nido does not drive, or a checkout made by hand;
   - nido's own records it is asked to cite: the ledgers under the nido root's `projects/` and the
     runs under its `runs/`, the directory `log-path` is in among them (the run's earlier rounds).

   Only those that exist; a project whose registry entry cannot be read adds nothing rather than
   failing the review.

   Each is a path a judge was refused before it was here: the project checkout alone left
   ~/Code/document-service-br-7085 — a git worktree beside the checkout, named on every clause of
   BR-7085's design — and the workstream's own ledger entries unread."
  ([log-path] (read-roots log-path nil))
  ([log-path prompt]
   (let [projects (try (project/list-projects) (catch Exception _ nil))
         root     (try (core/nido-root) (catch Exception _ nil))]
     (->> (concat (mapcat project-roots projects)
                  (cited-repos prompt)
                  (when root [(str (fs/path root "projects")) (str (fs/path root "runs"))])
                  (some-> log-path io/file .getAbsoluteFile .getParent vector))
          (map str)
          (filter #(.isDirectory (io/file %)))
          outermost))))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  claude-argv
  "The `claude --print` invocation as [opts & args] for p/process, the same shape as
   `codex/codex-argv`. Pure, so it is unit-testable.

   `schema` is the schema's JSON text, not its path: claude takes it inline. The prompt goes in on
   stdin, where its size is not bounded by the argument list. stdout is the stream-json transcript
   and goes to :log-path, with stderr merged in; `run-claude!` reads the answer back out of it.
   `add-dirs` are the directories it may read beyond `cwd` (`read-roots`)."
  [{:keys [cwd schema log-path prompt model claude-bin add-dirs]}]
  (into [{:dir cwd :continue true :in (str prompt tool-note)
          :out :write :out-file (io/file log-path) :err :out}
         (or claude-bin "claude") "--print"
         ;; stream-json in --print mode is refused without --verbose.
         "--verbose" "--output-format=stream-json"
         "--model" (or model default-model)
         "--restricted" "--strict-mcp-config" "--no-session-persistence"
         ;; A review run's cwd is often a nido worktree: its settings.local.json carries the session
         ;; baseline, whose hooks and briefing are for sessions nido drives, not for a one-shot call.
         "--setting-sources" "user,project"
         "--permission-mode" "dontAsk"
         "--tools" "Bash,Read,Grep,Glob"
         "--json-schema" schema]
        (concat (mapcat (fn [d] ["--add-dir" d]) add-dirs)
                ;; --allowedTools last, because the flag is variadic and would take whatever option
                ;; followed it as one more rule.
                ["--allowedTools"]
                allowed-commands)))

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

(defn ^{:malli/schema [:=> [:cat [:maybe :string]] :boolean]}
  failed-call?
  "Whether the stream at `log-path` shows a tool call that failed: a `tool_result` the CLI marked
   an error — a refused command, one that exited non-zero — or a final `result` listing permission
   denials. False when the log cannot be read: a failure the log does not show is not one this
   reading can be held to."
  [log-path]
  (try
    (with-open [r (io/reader log-path)]
      (boolean
       (some (fn [line]
               (when (str/starts-with? line "{")
                 (let [e (try (json/parse-string line true) (catch Exception _ nil))]
                   (or (and (= "result" (:type e)) (seq (:permission_denials e)))
                       (and (= "user" (:type e))
                            (some #(and (map? %) (= "tool_result" (:type %)) (true? (:is_error %)))
                                  (let [c (get-in e [:message :content])] (when (sequential? c) c))))))))
             (line-seq r))))
    (catch Exception _ false)))

(def ^:private file-tool-path
  "Where each file tool names what it reads, in the input a permission denial echoes back."
  {"Read" :file_path "Glob" :path "Grep" :path})

(defn ^{:malli/schema [:=> [:cat [:maybe :string]] [:vector :string]]}
  refused-reads
  "The paths the stream at `log-path` shows a file tool — Read, Glob, Grep — was denied, in the
   order first denied. Under `--restricted` a file tool is denied only for reaching outside the
   directories it was given, so each is a read nido did not grant: whatever the judge needed there
   went unread, however it then ruled. Empty when the log cannot be read, or nothing was denied.
   A refused shell command is not one: the shell is narrowed on purpose (`allowed-commands`)."
  [log-path]
  (or (some-> (result-event log-path) :permission_denials
              (->> (keep (fn [{:keys [tool_name tool_input]}]
                           (when-let [k (file-tool-path tool_name)]
                             (let [v (get tool_input k)]
                               (when-not (str/blank? (str v)) (str v))))))
                   distinct
                   vec))
      []))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  run-claude!
  "Run claude as a reviewer over `opts` — the keys `codex/run-codex!` takes, :budget-ms among
   them, plus an optional :model. Returns {:exit <int> :timed-out? <bool>}, and writes the
   structured answer to :out-path only when the run
   produced one. A run that did not leaves :out-path absent, which is how both callers already
   tell a failed review from an empty one.

   A failed run's own words are appended to the log as a plain last line. The stream buries them
   in a JSON event, and the log's last lines are what `codex/unavailability` reads to decide
   whether a reviewer was out of quota or broke.

   Registered as a child for the same reason as `run-codex!`: a stopped loop must not leave its
   reviewer running and billing."
  [{:keys [schema-path out-path log-path] :as opts}]
  (let [[opts' & cmd] (claude-argv (assoc opts :schema (slurp schema-path)
                                          :add-dirs (or (:add-dirs opts) (read-roots log-path (:prompt opts)))))
        proc (apply p/process opts' cmd)
        {:keys [exit timed-out?]} (nprocess/with-child-registered
                                   (:proc proc) #(nprocess/wait-within! (:proc proc) (:budget-ms opts)))
        {:keys [is_error structured_output result]} (when-not timed-out? (result-event log-path))]
    (if (and (zero? exit) (not timed-out?) (not is_error) (some? structured_output))
      (spit out-path (json/generate-string structured_output))
      (when-not (str/blank? (str result))
        (spit log-path (str "\n" (str/trim (str result)) "\n") :append true)))
    {:exit exit :timed-out? (boolean timed-out?)}))
