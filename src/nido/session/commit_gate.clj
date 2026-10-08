(ns nido.session.commit-gate
  "Hold a session's agent to its project's own commit-message convention.

   A project that enforces a convention through a git `commit-msg` hook loses
   that enforcement in a session: the worktree is a jj workspace, and jj runs
   none of the source repository's git hooks. This borrows the hook rather than
   re-encoding its rule. Installed as a Claude Code `PreToolUse` hook on Bash, it
   reads the command the agent is about to run and, for a `jj git push`, runs the
   project's `commit-msg` hook over every description in the range that push
   would publish; for `gh pr create`/`gh pr edit` with a title, over the title.
   A rejection blocks the command and hands the agent the hook's own output.

   The title is checked whatever the project's merge strategy. Under a squash
   merge it becomes the commit on trunk; under any other it is the one line of
   the change a reviewer reads first, and a project that holds its commits to a
   convention has never been observed to want its PR titles exempt. Asking the
   forge which strategy applies would put a network call in front of every
   `gh pr` the agent runs, to relax a check whose cost is one reworded title.

   FAILING OPEN IS THE CONTRACT. A command it cannot parse, a directory that is
   not a repository, a project with no executable `commit-msg` hook, a hook that
   hangs, a throw of any kind — each lets the command through. The gate is
   installed in every session of every project, and one that blocked a push it
   could not reason about would be worse than none. What it promises is narrow:
   a description the project's hook rejects does not leave through a command it
   recognises."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as str]
   [nido.platform.core :as core]))

;; ── reading a shell command ─────────────────────────────────────────────────

(defn- tokenize
  "Split a shell command into words and control operators.

   Enough of sh to find a `jj git push` or `gh pr` and read its arguments:
   single and double quotes, backslash escapes, and `&&`, `||`, `;`, `|`, `&`
   and newlines as separators. A word holding a command substitution, a
   backtick or a heredoc is returned as `:opaque`, because its value is decided
   when the shell runs it and the gate cannot know it."
  [s]
  (let [n (count s)]
    (loop [i 0, cur nil, opaque? false, out []]
      (let [flush (fn [out] (if cur (conj out (if opaque? :opaque cur)) out))]
        (if (>= i n)
          (flush out)
          (let [c (.charAt ^String s i)
                nxt (when (< (inc i) n) (.charAt ^String s (inc i)))]
            (cond
              (Character/isWhitespace c)
              (if (= c \newline)
                (recur (inc i) nil false (conj (flush out) :sep))
                (recur (inc i) nil false (flush out)))

              (or (and (= c \&) (= nxt \&)) (and (= c \|) (= nxt \|)))
              (recur (+ i 2) nil false (conj (flush out) :sep))

              (#{\; \| \& \( \)} c)
              (recur (inc i) nil false (conj (flush out) :sep))

              (and (= c \<) (= nxt \<))
              (recur (+ i 2) (str cur) true out)

              (= c \')
              (let [end (str/index-of s "'" (inc i))]
                (if end
                  (recur (inc end) (str cur (subs s (inc i) end)) opaque? out)
                  (recur n (str cur (subs s (inc i))) true out)))

              (= c \")
              (let [[j acc op?] (loop [j (inc i), acc (StringBuilder.), op? false]
                                  (if (>= j n)
                                    [j acc true]
                                    (let [d (.charAt ^String s j)]
                                      (cond
                                        (= d \") [(inc j) acc op?]
                                        (and (= d \\) (< (inc j) n))
                                        (recur (+ j 2) (.append acc (.charAt ^String s (inc j))) op?)
                                        (#{\$ \`} d) (recur (inc j) (.append acc d) true)
                                        :else (recur (inc j) (.append acc d) op?)))))]
                (recur j (str cur acc) (or opaque? op?) out))

              (and (= c \\) nxt)
              (recur (+ i 2) (str cur nxt) opaque? out)

              (#{\$ \`} c)
              (recur (inc i) (str cur c) true out)

              :else
              (recur (inc i) (str cur c) opaque? out))))))))

(defn- segments
  "The simple commands of a tokenized line, in order, each a vector of words."
  [tokens]
  (->> (partition-by #(= :sep %) tokens)
       (remove #(= [:sep] (distinct %)))
       (map vec)))

(defn- strip-prefix
  "A simple command without its leading `VAR=value` assignments and `command`."
  [words]
  (let [ws (drop-while #(and (string? %) (re-matches #"[A-Za-z_][A-Za-z0-9_]*=.*" %)) words)]
    (vec (if (= "command" (first ws)) (rest ws) ws))))

(def ^:private jj-global-valued
  "jj's global options that take a value, so the word after them is not a
   subcommand."
  #{"-R" "--repository" "--at-op" "--at-operation" "--config" "--config-file" "--color"})

(defn- option-values
  "Values of the options in `names` among `args`, both `--opt v` and `--opt=v`."
  [names args]
  (loop [[a & more] args, out []]
    (cond
      (nil? a) out
      (and (string? a) (names a) (seq more)) (recur (rest more) (conj out (first more)))
      (and (string? a) (some #(str/starts-with? a (str % "=")) names))
      (recur more (conj out (subs a (inc (str/index-of a "=")))))
      :else (recur more out))))

(defn- jj-push
  "`{:repo r :args [word …]}` when `words` is a `jj git push`, else nil: the
   push's own arguments after `jj`, so the same push can be asked what it would
   publish. `:repo` is the `-R` value when given. A push already marked
   `--dry-run` publishes nothing and is nil."
  [words]
  (when (= "jj" (first words))
    (let [args (rest words)
          tail (loop [[a & more :as xs] args]
                 (cond
                   (nil? a) nil
                   (jj-global-valued a) (recur (rest more))
                   (and (string? a) (str/starts-with? a "-")) (recur more)
                   :else xs))]
      (when (and (= "git" (first tail)) (= "push" (second tail))
                 (not (some #{"--dry-run"} tail)))
        {:repo (last (option-values #{"-R" "--repository"} args))
         :args (vec args)}))))

(defn- pr-title
  "`{:title t}` when `words` is a `gh pr create`/`gh pr edit` naming a title,
   else nil."
  [words]
  (when (and (= "gh" (first words)) (= "pr" (second words))
             (#{"create" "edit"} (nth words 2 nil)))
    (when-let [t (last (option-values #{"-t" "--title"} (drop 3 words)))]
      {:title t})))

(defn ^{:malli/schema [:=> [:cat :string :string] [:vector :map]]}
  checks
  "What a Bash command would publish that the project's hook should judge, in
   order: `{:kind :push :dir d :args [word …]}` for a `jj git push`, and
   `{:kind :title :dir d :title t}` for a titled `gh pr create`/`edit`. `:dir`
   is where the command runs — `cwd`, moved by any `cd` earlier on the line.

   A command whose relevant part the gate cannot read — a title or a push
   argument decided by a substitution, a `cd` into one — contributes no check
   for that part. That is the failing-open contract, applied before anything runs."
  [command cwd]
  (loop [[ws & more] (map strip-prefix (segments (tokenize command)))
         dir cwd
         out []]
    (if (nil? ws)
      out
      (cond
        (= "cd" (first ws))
        (let [to (second ws)]
          (if (or (nil? to) (= :opaque to) (= "-" to))
            out
            (recur more (str (fs/normalize (fs/path dir (str/replace to #"^~(?=/|$)" (System/getProperty "user.home"))))) out)))

        (some #{:opaque} (take 3 ws))
        (recur more dir out)

        :else
        (let [push  (jj-push ws)
              title (pr-title ws)]
          (recur more dir
                 (cond-> out
                   ;; a push is re-run by the gate, so every word must be known
                   (and push (every? string? (:args push)))
                   (conj {:kind :push
                          :dir  (if-let [r (:repo push)] (str (fs/normalize (fs/path dir r))) dir)
                          :args (:args push)})
                   (and title (string? (:title title)))
                   (conj {:kind :title :dir dir :title (:title title)}))))))))

;; ── the project's hook ──────────────────────────────────────────────────────

(def ^:private timeout-ms
  "How long one subprocess — the project's hook, or jj asked what a push would
   publish — may take before the gate gives up and lets the command through. A
   message hook that needs longer is doing something the gate cannot wait on
   inside a tool call."
  10000)

(defn- run
  "Run `cmd` in `dir`, stderr folded into stdout. `{:exit n :out s}`, or nil
   when it could not be run or did not finish within `timeout-ms`."
  [dir cmd]
  (try
    (let [proc (p/process cmd {:dir dir :err :out :out :string})
          r    (deref proc timeout-ms ::timeout)]
      (if (= ::timeout r)
        (do (p/destroy-tree proc) nil)
        {:exit (:exit r) :out (str (:out r))}))
    (catch Exception _ nil)))

(defn- sh-out
  "stdout of `cmd` run in `dir`, trimmed, or nil when it fails."
  [dir & cmd]
  (let [r (run dir (vec cmd))]
    (when (= 0 (:exit r)) (str/trim (:out r)))))

(defn ^{:malli/schema [:=> [:cat :string] [:maybe :string]]}
  commit-msg-hook
  "The executable `commit-msg` hook of the git repository behind `dir`, or nil.

   Behind a jj workspace that is the repository holding its store (`jj git
   root`); otherwise the git repository `dir` is in. Resolved through
   `--git-path`, so a `core.hooksPath` the repository sets is honoured. A hook
   that is not executable is one git would skip, and so does this."
  [dir]
  (when (fs/directory? dir)
    (let [git-dir (or (sh-out dir "jj" "git" "root")
                      (sh-out dir "git" "rev-parse" "--absolute-git-dir"))
          hooks   (when git-dir
                    (sh-out dir "git" (str "--git-dir=" git-dir) "rev-parse" "--git-path" "hooks"))
          hook    (when hooks (fs/path (fs/path git-dir) hooks "commit-msg"))]
      (when (and hook (fs/regular-file? hook) (fs/executable? hook))
        (str (fs/canonicalize hook))))))

(defn- run-hook
  "Run `hook` on `message` as git would — the message in a file, its path the
   one argument — from `dir`. `{:rejected? b :output s}`, or nil when the hook
   could not be run to an answer."
  [hook dir message]
  (let [f (fs/create-temp-file {:prefix "nido-commit-msg-"})]
    (try
      (spit (str f) message)
      (when-let [r (run dir [hook (str f)])]
        {:rejected? (not (zero? (:exit r))) :output (str/trim (:out r))})
      (finally (fs/delete-if-exists f)))))

(def ^:private record-sep "\u001e")
(def ^:private field-sep "\u001f")

(defn- pushed-heads
  "The commit ids a push would move or add a remote bookmark to, as jj reports
   them for the same arguments with `--dry-run` — so every way a push selects
   bookmarks (named, `-c`, `--all`, `--tracked`, jj's default) is jj's to
   decide, not the gate's. Nil when jj cannot answer; [] when nothing moves."
  [dir args]
  (when-let [r (run dir (into ["jj"] (conj args "--dry-run")))]
    (when (zero? (:exit r))
      (vec (distinct (map second (re-seq #"\bto ([0-9a-f]{6,})\]" (:out r))))))))

(defn ^{:malli/schema [:=> [:cat :string [:vector :string]] [:maybe [:vector :map]]]}
  push-range
  "`[{:change c :description d}]` for every commit the push would publish that
   carries a description: reachable from a head it would move, and from no
   remote bookmark. Nil when jj cannot answer."
  [dir args]
  (when-let [heads (seq (pushed-heads dir args))]
    (let [revset (str "::(" (str/join " | " heads) ") ~ ::remote_bookmarks()")
          out    (sh-out dir "jj" "log" "--no-graph" "--ignore-working-copy" "-r" revset
                         "-T" (str "change_id.short() ++ \"" field-sep "\" ++ description ++ \"" record-sep "\""))]
      (when out
        (vec (for [rec (str/split out (re-pattern record-sep))
                   :let [[change desc] (str/split (str/triml rec) (re-pattern field-sep) 2)]
                   :when (and change (not (str/blank? desc)))]
               {:change change :description desc}))))))

(defn- check-rejection
  "The first rejection `check` meets, as `{:subject s :output o :hook h}`, or nil."
  [{:keys [kind dir args title]}]
  (when-let [hook (commit-msg-hook dir)]
    (case kind
      :push  (some (fn [{:keys [change description]}]
                     (let [r (run-hook hook dir description)]
                       (when (:rejected? r)
                         {:hook hook :output (:output r)
                          :subject (str "commit " change " (\"" (first (str/split-lines description)) "\")")})))
                   (push-range dir args))
      :title (let [r (run-hook hook dir title)]
               (when (:rejected? r)
                 {:hook hook :output (:output r) :subject (str "PR title \"" title "\"")})))))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :string]]}
  verdict
  "Why the command in a Claude Code `PreToolUse` input must not run, or nil to
   let it. The reason names what was rejected, the hook that rejected it, and
   the hook's own output — it is what the agent reads instead of a push."
  [{:keys [tool_name tool_input cwd]}]
  (try
    (when (and (= "Bash" tool_name) (string? (:command tool_input)) (string? cwd))
      (some (fn [c]
              (when-let [{:keys [subject hook output]} (check-rejection c)]
                (str "Blocked by nido's commit gate: the project's commit-msg hook (" hook
                     ") rejects the " subject ".\n\n" output "\n\n"
                     "Reword it to the project's convention"
                     (when (= :push (:kind c)) " (`jj describe -r <change> -m …`)")
                     " and run the command again.")))
            (checks (:command tool_input) cwd)))
    (catch Throwable _ nil)))

;; ── installing it ───────────────────────────────────────────────────────────

(def ^:private hook-timeout-s
  "The host's bound on the whole gate, in seconds: a few hook runs at
   `timeout-ms` each, plus bb's start."
  60)

(defn- gate-command
  "The shell command the host runs on each Bash call. It reads the host's JSON
   once and starts bb only when the command could be a push or a PR — every
   other Bash call costs a shell `case` and no bb start."
  []
  (str "i=$(cat); case \"$i\" in *'git push'*|*'gh pr'*) "
       "printf '%s' \"$i\" | bb --config '" (fs/path (core/nido-source-dir) "bb.edn") "' nido:commit:gate ;; esac"))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  with-gate
  "`settings` — a Claude Code settings map — with the gate added as a
   `PreToolUse` hook on Bash. Every hook `settings` already declares stays."
  [settings]
  (update-in settings [:hooks :PreToolUse] (fnil conj [])
             {:matcher "Bash"
              :hooks   [{:type "command" :command (gate-command) :timeout hook-timeout-s}]}))

(defn ^{:malli/schema [:=> [:cat] :string]}
  settings-json
  "The gate alone as a settings document, for claude's `--settings` on a launch
   that does not start in a session home."
  []
  (json/generate-string (with-gate {})))
