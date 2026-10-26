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
   recognises. `publications` names what the gate could not read, for a caller —
   a seat's gate — that must refuse it rather than let it through; `checks`
   drops it."
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

(def ^:private gh-pr-options
  "The options of `gh pr create` and `gh pr edit` the gate knows: those taking a value, and the
   flags taking none. Any other option is one the gate cannot bind."
  {:valued  #{"-t" "--title" "-b" "--body" "-F" "--body-file" "-B" "--base" "-H" "--head"
              "-R" "--repo" "-l" "--label" "-a" "--assignee" "-r" "--reviewer" "-m" "--milestone"
              "-p" "--project" "-T" "--template" "--recover"
              "--add-label" "--remove-label" "--add-reviewer" "--remove-reviewer"
              "--add-assignee" "--remove-assignee" "--add-project" "--remove-project"}
   :flags   #{"-d" "--draft" "-f" "--fill" "--fill-first" "--fill-verbose" "-w" "--web"
              "--dry-run" "--no-maintainer-edit" "--remove-milestone"}
   :dry-run {"--dry-run" true}
   :dashes  :stop})

(def ^:private jj-push-options
  "The options of `jj` around `git push` the gate knows — global and the push's own. `:unsafe` are
   those it binds but will not run a probe with, since they reach past what the push selects (a
   config can name the git a push runs). A `--` leaves the push unbound: nothing a probe appends
   may become an operand."
  {:valued  #{"-R" "--repository" "--at-op" "--at-operation" "--color" "--config" "--config-toml"
              "--config-file" "-b" "--bookmark" "-c" "--change" "-r" "--revisions" "--remote"
              "-N" "--named"}
   :flags   #{"--all" "--tracked" "--deleted" "--allow-new" "--allow-empty-description"
              "--allow-private" "--dry-run" "--ignore-working-copy" "--ignore-immutable"
              "--no-pager" "--quiet" "--debug"}
   :unsafe  #{"--config" "--config-toml" "--config-file"}
   :dry-run {"--dry-run" true}
   :dashes  :unbound})

(def ^:private git-push-options
  "The options of `git` around `push` the gate knows — global and the push's own — as
   jj-push-options are: `:unsafe` ones it binds and runs no probe with, since they run a command or
   a configuration past what the push selects."
  {:valued  #{"-C" "-c" "--git-dir" "--work-tree" "--namespace" "--repo" "--receive-pack" "--exec"
              "-o" "--push-option" "--recurse-submodules"}
   :flags   #{"-f" "--force" "-u" "--set-upstream" "--tags" "--follow-tags" "--no-follow-tags"
              "--all" "--branches" "--mirror" "-d" "--delete" "--prune" "-v" "--verbose" "-q"
              "--quiet" "--progress" "--no-progress" "--porcelain" "--atomic" "--force-if-includes"
              "--thin" "--no-thin" "--dry-run" "-n" "--no-dry-run" "--verify" "--no-verify"
              "--force-with-lease" "--no-force-with-lease" "--signed" "--no-signed" "-4" "-6"
              "--ipv4" "--ipv6" "-P" "--no-pager"}
   :unsafe  #{"-c" "--receive-pack" "--exec" "-o" "--push-option" "--recurse-submodules" "--verify"
              "--signed"}
   :dry-run {"--dry-run" true "-n" true "--no-dry-run" false}
   :dashes  :unbound})

(defn- bind
  "`args` with every option bound to its value as `spec` names them — `{:opts [[name value] …]
   :operands [w …] :kept [w …] :unbound? b :dry-run? b}`, a flag's value true unless `--flag=v`
   gives one — so no option's value is read as an option. `:unbound?` when a word stands where an
   option might and the gate cannot bind it — an option it does not know, an `:unsafe` one, or a
   word it cannot read, an option's value included, since the shell may split it into options of
   its own — and, per `:dashes`, at a `--` (`:stop` reads the words after it as operands it
   ignores). `:dry-run?` is the effect of the last of the spec's dry-run options, the one the
   program acts on. `:kept` is `args` without any dry-run option: what a probe runs, so no
   argument can undo the dry run the probe adds."
  [{:keys [valued flags unsafe dry-run dashes]} args]
  (let [opt (fn [out n v words]
              (cond-> (update out :opts conj [n v])
                (not (contains? dry-run n)) (update :kept into words)
                (contains? dry-run n)       (assoc :dry-run? (and (get dry-run n) (#{true "true"} v) true))
                (contains? unsafe n)        (assoc :unbound? true)))]
    (loop [[a & more] args, out {:opts [] :operands [] :kept [] :unbound? false :dry-run? false}]
      (cond
        (nil? a)                 out
        (= "--" a)               (cond-> out (= :unbound dashes) (assoc :unbound? true))
        (not (string? a))        (recur more (assoc out :unbound? true))
        (valued a)               (recur (rest more) (cond-> (opt out a (first more) [a (first more)])
                                                      (not (string? (first more))) (assoc :unbound? true)))
        (and (str/starts-with? a "--") (str/includes? a "="))
        (let [[n v] (str/split a #"=" 2)]
          (recur more (cond-> (opt out n v [a])
                        (not (or (valued n) (flags n))) (assoc :unbound? true))))
        (flags a)                (recur more (opt out a true [a]))
        (str/starts-with? a "-") (recur more (-> out (assoc :unbound? true) (update :kept conj a)))
        :else                    (recur more (-> out (update :operands conj a) (update :kept conj a)))))))

(defn- gh-pr-bound
  "A `gh pr <verb>`'s `args` bound as gh-pr-options name them (`bind`)."
  [args]
  (bind gh-pr-options args))

(defn- jj-push
  "`{:repo r :args [word …] :bound b}` when `words` is a `jj git push`, else nil: the push's own
   arguments after `jj`, so the same push can be asked what it would publish, and them bound
   (`bind`). `:repo` is the `-R` value when given. A push whose effective options make it a dry
   run publishes nothing and is nil."
  [words]
  (when (= "jj" (first words))
    (let [args (vec (rest words))
          b    (bind jj-push-options args)]
      (when (and (= ["git" "push"] (take 2 (:operands b))) (not (:dry-run? b)))
        {:repo  (last (keep (fn [[n v]] (when (#{"-R" "--repository"} n) v)) (:opts b)))
         :args  args
         :bound b}))))

(def ^:private valued-globals
  "Options before or between subcommand words that take a value, so the word after them is not a
   subcommand: jj's and git's globals, and gh's -R/--repo, which every `gh pr` verb inherits."
  (into jj-global-valued #{"-C" "-c" "--git-dir" "--work-tree" "--repo"}))

(defn- command-path
  "The subcommand words of a program's `args` — the words after the program — as its own parser
   finds them: options skipped wherever they stand, a valued global (`valued-globals`) with its
   value. :opaque stands where a word the gate cannot read does, since it may be either. Every
   reader of which subcommand a command runs reads it here."
  [args]
  (loop [[a & more] args, out []]
    (cond
      (nil? a)                 out
      (= :opaque a)            (recur more (conj out :opaque))
      (valued-globals a)       (recur (rest more) out)
      (str/starts-with? a "-") (recur more out)
      :else                    (recur more (conj out a)))))

(defn- without-path
  "`args` with the words of `path` removed, each its first occurrence after the one before: the
   subcommand's own options and operands, wherever its path words stood among them."
  [args path]
  (loop [[a & more :as xs] (seq args), [p & ps :as want] (seq path), out []]
    (cond
      (nil? xs)  out
      (and want (= a p)) (recur more ps out)
      :else      (recur more want (conj out a)))))

(defn- dry-run?
  "Whether `program`'s words after it, `args`, are bound (`bind`) by `options` to a dry run."
  [options args]
  (boolean (and options (:dry-run? (bind options args)))))

(defn- pr-title
  "`{:title t}` when `words` is a `gh pr create`/`gh pr edit` naming a title,
   else nil."
  [words]
  (let [path (take 2 (command-path (rest words)))]
    (when (and (= "gh" (first words)) (= "pr" (first path)) (#{"create" "edit"} (second path)))
      (when-let [t (last (keep (fn [[n v]] (when (#{"-t" "--title"} n) v))
                               (:opts (gh-pr-bound (without-path (rest words) path)))))]
        {:title t}))))

(def ^:private publication-verbs
  "Every way a command publishes that the gate knows of, as the program and the subcommand words
   naming it — with, for a call on the API, a word its target must contain. The one list: what
   `publications` recognises and what the installed hook starts the gate for are both read off
   it, so a verb added here is recognised and reached at once, and none is one and not the other."
  [{:verb :push   :program "jj"  :sub ["git" "push"]   :options jj-push-options}
   {:verb :push   :program "git" :sub ["push"]          :options git-push-options}
   {:verb :create :program "gh"  :sub ["pr" "create"]   :options gh-pr-options}
   {:verb :edit   :program "gh"  :sub ["pr" "edit"]     :options gh-pr-options}
   {:verb :api    :program "gh"  :sub ["api"] :target "pulls"}])

(def ^:private shell-runners
  "Programs that run a command line handed to them as one word. What a word is run as is not
   in its syntax, so this is the one thing the gate is told rather than reads: a string handed to
   one of these is read as a command line of its own; any other string is a value."
  #{"sh" "bash" "zsh" "eval"})

(defn- writes?
  "Whether a `verb` invocation whose words after its subcommand are `args` publishes anything: a
   push and a create always do; an edit only through a title or a word the gate cannot read
   standing as no other option's value; an API call only when it writes — a method other than
   GET, a field, an input, or a word the gate cannot read."
  [verb args]
  (let [free-opaque? (some (fn [[prev w]] (and (= :opaque w)
                                               (not (and (string? prev) (str/starts-with? prev "-")))))
                           (partition 2 1 (cons nil args)))]
    (case verb
      (:push :create) true
      :edit (boolean (or (seq (option-values #{"-t" "--title"} args)) free-opaque?))
      :api  (boolean (or (some #{:opaque} args)
                         (some #(not= "GET" (str/upper-case %)) (option-values #{"-X" "--method"} args))
                         ;; a short option's value may stand joined to it: -XPOST, -Fquery=…
                         (some #(when-let [[_ m] (and (string? %) (re-find #"^-X(.+)$" %))]
                                  (not= "GET" (str/upper-case m)))
                               args)
                         (some #(and (string? %)
                                     (or (#{"-f" "-F" "--field" "--raw-field" "--input"} %)
                                         (re-find #"^--(field|raw-field|input)=|^-[fF]." %)))
                               args))))))

(defn- publication
  "The publication a simple command's words `ws` name, or nil: `{:verb v :direct? b}`, `:direct?`
   when its program is the command's first word — anything else runs it through a wrapper the
   gate does not read, or as a command line handed to a shell. The program is found wherever it
   stands, so no wrapper needs naming; a word the gate cannot read may stand for an option. One
   its verb's options bind to a dry run (`dry-run?`) publishes nothing."
  [ws]
  (or (some (fn [i]
                (let [program (nth ws i)
                      words   (filterv string? (command-path (drop (inc i) ws)))]
                  (some (fn [{:keys [verb sub target] :as row}]
                          (when (and (= program (:program row))
                                     (= sub (take (count sub) words))
                                     (or (nil? target)
                                         ;; after the subcommand, the order `verb-pattern` matches
                                         (some #(and (string? %) (str/includes? % target))
                                               (rest (drop-while #(not= (last sub) %)
                                                                 (drop (inc i) ws)))))
                                     (writes? verb (drop (inc i) ws))
                                     (not (dry-run? (:options row) (drop (inc i) ws))))
                            {:verb verb :direct? (zero? i)}))
                        publication-verbs)))
              (keep-indexed (fn [i w] (when (string? w) i)) ws))
        ;; A command line handed to a shell, wherever the shell stands.
        (some (fn [w]
                (when (string? w)
                  (some #(some-> (publication (strip-prefix %)) (assoc :direct? false))
                        (segments (tokenize w)))))
              (rest (drop-while #(not (shell-runners %)) ws)))))

(defn- unread
  [what]
  {:kind :unread :what what})

(defn ^{:malli/schema [:=> [:cat :string :string] [:vector :map]]}
  publications
  "What a Bash command would publish, in order: `{:kind :push :dir d :args [word …]}` for a
   `jj git push`, `{:kind :title :dir d :title t}` for a titled `gh pr create`/`edit`, and
   `{:kind :unread :what <why>}` for every other command naming a publication (`publication`)
   — one the gate cannot attribute: a push or title decided by a substitution, one run in a
   directory a `cd` left unknown or through a wrapper or a shell, a `gh pr create` naming no
   title, a `git push` or a `gh api` write, which publish around jj. `:dir` is where the command
   runs — `cwd`, moved by any `cd` earlier on the line."
  [command cwd]
  (loop [[ws & more] (map strip-prefix (segments (tokenize command)))
         dir cwd
         out []]
    (if (nil? ws)
      out
      (if (= "cd" (first ws))
        (let [to (second ws)]
          (if (or (nil? to) (= :opaque to) (= "-" to))
            (recur more nil out)
            (recur more (when dir (str (fs/normalize (fs/path dir (str/replace to #"^~(?=/|$)" (System/getProperty "user.home")))))) out)))
        (let [{:keys [verb direct?] :as pub} (publication ws)
              push  (when (= :push verb) (jj-push ws))
              title (when (#{:create :edit} verb) (pr-title ws))]
          (recur more dir
                 (cond
                   (nil? pub) out

                   (nil? dir)
                   (conj out (unread "it runs in a directory a `cd` left unknown"))

                   (not direct?)
                   (conj out (unread "it runs through a wrapper or a shell the gate does not read — run `jj git push` or `gh pr` as the command itself"))

                   (and push (not-any? #{:opaque} (:args push)))
                   (conj out {:kind :push
                              :dir  (if-let [r (:repo push)] (str (fs/normalize (fs/path dir r))) dir)
                              :args (:args push)})

                   push
                   (conj out (unread "a push argument is decided when the shell runs it"))

                   (= :push verb)
                   (conj out (unread "it publishes around jj — push with `jj git push`"))

                   (string? (:title title))
                   (conj out {:kind :title :dir dir :title (:title title)})

                   title
                   (conj out (unread "the PR title is decided when the shell runs it"))

                   (= :create verb)
                   (conj out (unread "`gh pr create` names no title for the gate to read — pass `--title`"))

                   :else
                   (conj out (unread "it publishes in a way the gate does not read — publish with `jj git push` and `gh pr` with a literal `--title`")))))))))

(defn ^{:malli/schema [:=> [:cat :string :string] [:vector :map]]}
  checks
  "What a Bash command would publish that the project's hook should judge, in
   order: `{:kind :push :dir d :args [word …]}` for a `jj git push`, and
   `{:kind :title :dir d :title t}` for a titled `gh pr create`/`edit`. `:dir`
   is where the command runs — `cwd`, moved by any `cd` earlier on the line.

   `publications` without what the gate cannot read: a title or a push argument
   decided by a substitution, a `cd` into one, contributes no check for that
   part. That is the failing-open contract, applied before anything runs."
  [command cwd]
  (filterv #(not= :unread (:kind %)) (publications command cwd)))

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

(defn- probe
  "The output of `program` run in `dir` on `args` as a dry run, or nil when it cannot be run as one
   or does not succeed. The one way the gate runs a command's own arguments: they are bound by
   `options` (`bind`), any dry-run option among them is dropped and `dry-run` — the flags making
   the run a dry one — appended after them, so no argument can undo it; and it is not run at all
   when they cannot all be bound, or one is an option it will not run (`:unsafe`)."
  [dir program options args dry-run]
  (let [{:keys [kept unbound?]} (bind options args)]
    (when-not unbound?
      (when-let [r (run dir (-> [program] (into kept) (into dry-run)))]
        (when (zero? (:exit r)) (:out r))))))

(defn- pushed-heads
  "The commit ids a push would move or add a remote bookmark to, as jj reports
   them for the same arguments as a dry run (`probe`) — so every way a push selects
   bookmarks (named, `-c`, `--all`, `--tracked`, jj's default) is jj's to
   decide, not the gate's. Nil when jj cannot answer; [] when nothing moves."
  [dir args]
  (when-let [out (probe dir "jj" jj-push-options args ["--dry-run"])]
    (vec (distinct (map second (re-seq #"\bto ([0-9a-f]{6,})\]" out))))))

(defn ^{:malli/schema [:=> [:cat :string [:vector :string]] [:maybe [:vector :map]]]}
  push-range
  "`[{:change c :description d}]` for every commit the push would publish that
   carries a description: reachable from a head it would move, and from no
   remote bookmark; [] when the push moves nothing. Nil when jj cannot answer."
  [dir args]
  (when-let [heads (pushed-heads dir args)]
    (if (empty? heads)
      []
      (let [revset (str "::(" (str/join " | " heads) ") ~ ::remote_bookmarks()")
          out    (sh-out dir "jj" "log" "--no-graph" "--ignore-working-copy" "-r" revset
                         "-T" (str "change_id.short() ++ \"" field-sep "\" ++ description ++ \"" record-sep "\""))]
      (when out
        (vec (for [rec (str/split out (re-pattern record-sep))
                   :let [[change desc] (str/split (str/triml rec) (re-pattern field-sep) 2)]
                   :when (and change (not (str/blank? desc)))]
               {:change change :description desc})))))))

;; ── acts another gate judges ──────────────────────────────────────────────

(defn- gh-api-pr-merge
  "`{:verb \"api\"}` when `words` are a `gh api` call that writes (`writes?`) and may merge a pull
   request — a `pulls/<n>/merge` path, a GraphQL query naming a merge or whose body the gate cannot
   read (one read from a file or stdin, by `--input` or a field's `@`), or any call with a word the
   gate cannot read, which may stand for either; else nil. Any other write to a pull request opens
   or edits it, which is not a merge."
  [words]
  (when (and (= "gh" (first words)) (= "api" (first (command-path (rest words)))))
    (let [args (without-path (rest words) ["api"])]
      (when (and (writes? :api args)
                 (or (some #{:opaque} args)
                     (some #(re-find #"pulls/[^/\s]+/merge" %) args)
                     (and (some #(re-find #"(?i)(^|/)graphql/?$" %) args)
                          (some #(re-find #"(?i)merge|=@|^@|^--input" %) args))))
        {:verb "api"}))))

(defn- nido-task
  "`{:task t :args [word …]}` when `words` run a nido task — `bb … nido:<verb>` or the `nido`
   shell wrapper's `nido <verb>` — else nil."
  [words]
  (let [strs (filterv string? words)]
    (or (when (= "bb" (first strs))
          (when-let [i (first (keep-indexed (fn [i w] (when (str/starts-with? w "nido:") i)) strs))]
            {:task (nth strs i) :args (subvec (vec words) (inc (.indexOf ^java.util.List (vec words) (nth strs i))))}))
        (when (and (= "nido" (first words)) (string? (second words)) (str/includes? (second words) ":"))
          {:task (str "nido:" (second words)) :args (vec (drop 2 words))}))))

(defn- gh-pr
  "`{:verb v}` when `words` are a `gh pr <verb>`, its path read as gh binds it (`command-path`),
   else nil. A `gh` whose subcommand or `pr` verb the gate cannot read may be any, a merge
   included, and is `{:verb :unread}`."
  [words]
  (when (= "gh" (first words))
    (let [[sub verb] (command-path (rest words))]
      (cond
        (= :opaque sub)                         {:verb :unread}
        (and (= "pr" sub) (= :opaque verb))     {:verb :unread}
        (and (= "pr" sub) (string? verb))       {:verb verb}))))

(def ^:private act-readers
  "Every act `acts` reads, as the words naming it and the reader of a simple command's words — run
   in a directory, nil when a `cd` left it unknown — answering the act or nil. The one list: what
   `acts` reads, what it reads a later word or a shell's command line for, and what the installed
   hook starts the gate for are all read off it, so an act added here is read and reached at once."
  [{:words ["gh" "pr"]
    :read  (fn [words _] (some-> (gh-pr words) (assoc :kind :gh-pr)))}
   {:words ["gh" "api"]
    :read  (fn [words _] (some-> (gh-api-pr-merge words) (assoc :kind :gh-pr)))}
   {:words ["nido" ":"]
    :read  (fn [words _] (some-> (nido-task words) (assoc :kind :nido-task)))}])

(def ^:private act-named
  "Whether a word names, as text, an act `act-readers` reads: its words in order."
  (let [res (for [{:keys [words]} act-readers]
              (re-pattern (str/join "[\\s\\S]*"
                                    (map #(if (re-matches #"\w+" %) (str "\\b" % "\\b") (java.util.regex.Pattern/quote %))
                                         words))))]
    (fn [w] (and (string? w) (boolean (some #(re-find % w) res))))))

(defn- acts*
  "`acts`, in a directory nil when a `cd` left it unknown — as a command line handed to a shell is
   read, in whatever directory its own line left."
  [command cwd]
  (loop [[ws & more] (map strip-prefix (segments (tokenize command)))
         dir cwd
         out []]
    (if (nil? ws)
      out
      (if (= "cd" (first ws))
        (let [to (second ws)]
          (recur more (when (and dir (string? to) (not= "-" to))
                        (str (fs/normalize (fs/path dir (str/replace to #"^~(?=/|$)" (System/getProperty "user.home"))))))
                 out))
        (let [direct (fn [words] (some #((:read %) words dir) act-readers))
              ;; The program wherever it stands: a wrapper (`nice`, `env`, `xargs` …) runs it as
              ;; surely as the shell does.
              act    (some #(when (string? (nth ws %)) (direct (subvec ws %))) (range (count ws)))
              ;; A command line handed to a shell is read as one, since it acts as surely as the
              ;; shell does — always, whatever another of the words reads as.
              inner  (into [] (comp (filter string?) (mapcat #(acts* % dir)))
                           (rest (drop-while #(not (shell-runners %)) ws)))
              ;; Anything else naming one of these past the first word runs it through something
              ;; the gate does not read.
              hidden (when-not (or act (seq inner)) (some act-named (rest ws)))]
          (recur more dir
                 (cond-> (into (cond-> out act (conj act)) inner)
                   hidden (conj (unread "it runs through a wrapper or a shell the gate does not read")))))))))

(defn ^{:malli/schema [:=> [:cat :string :string] [:vector :map]]}
  acts
  "What a Bash command run in `cwd` would do that a caller judges on its own terms, in order — one
   act per reader of `act-readers`: `{:kind :gh-pr :verb v}` for each `gh pr <verb>`, and
   `{:kind :gh-pr :verb \"api\"}` for each `gh api` write that may merge a pull request
   (`gh-api-pr-merge`); `{:kind :nido-task :task t :args [word …]}` for each nido task it runs; and
   `{:kind :unread :what <why>}` for each of these it cannot read: a later word naming one, unless
   it is a command line handed to a shell that yields one. Each is found wherever its program
   stands, so a wrapper (`nice`, `env` …) hides none."
  [command cwd]
  (acts* command cwd))

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

(defn- verb-pattern
  "A shell `case` pattern matching any text holding a verb's words in order — so any command
   `publication` could recognise as it, whatever stands between them."
  [{:keys [program sub target]}]
  (str "*" (str/join "*" (map #(str "'" % "'") (concat [program] sub (when target [target])))) "*"))

(def ^:private opaque-marks
  "What `tokenize` reads a word as :opaque for, as it stands in the text once quotes and
   backslashes are removed: a substitution's `$`, a backtick, a heredoc's `<<`."
  ["$" "`" "<<"])

(def ^:private act-patterns
  "The `case` patterns for what `acts` reads, read off `act-readers`: any text holding a reader's
   words in order — or its program followed by its first words and then a word the parse cannot
   read (`opaque-marks`), which may stand for the next of them, as `command-path` reads one. A
   nido task is matched by what `nido-task` needs of the text — `nido` with a `:` somewhere after
   it, as both `nido:<verb>` and the wrapper's `nido <verb>` have whatever separates the words —
   never by its spelling."
  (let [pattern (fn [words] (str "*" (str/join "*" (map #(str "'" % "'") words)) "*"))]
    (distinct
     (for [{:keys [words]} act-readers
           ws (cons words (for [i (range 1 (count words)), m opaque-marks]
                            (conj (subvec words 0 i) m)))]
       (pattern ws)))))

(defn- gate-command
  "The shell command the host runs on each Bash call. It reads the host's JSON
   once and starts bb only when the command could name a publication — read off
   `publication-verbs`, as `publications` is — or an act `acts` reads, so nothing
   either recognises goes unchecked. The patterns are matched against the text with every quote
   and backslash removed, because `tokenize` removes them before either reads a word: `ni''do`
   and `g\\h` are the words `nido` and `gh` to the parse, and must be to the prefilter. Every
   other Bash call costs a `tr` and a shell `case`, and no bb start."
  []
  (str "i=$(cat); case \"$(printf '%s' \"$i\" | tr -d \"\\\\\\\\'\\\"\")\" in "
       (str/join "|" (concat (map verb-pattern publication-verbs) act-patterns)) ") "
       "printf '%s' \"$i\" | bb --config '" (fs/path (core/nido-source-dir) "bb.edn") "' nido:commit:gate ;; esac"))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  with-gate
  "`settings` — a Claude Code settings map — with the gate added as a
   `PreToolUse` hook on Bash, started on each call that could name a publication or an act `acts`
   reads. Every hook `settings` already declares stays."
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
