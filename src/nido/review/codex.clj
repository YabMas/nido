(ns nido.review.codex
  "Launching a reviewer: which one judges, running it read-only over a prompt and an answer
   schema, and — when it could not be run — why, in the vendor's own words.

   codex judges unless a run or its project names claude, and a codex that has run out of quota
   hands the same prompt to claude rather than ending the run — see `run-reviewer!`. Every judge
   the review programs launch goes through it, and nothing past it knows which reviewer ran
   except by the `:judged-by` it answers with."
  (:require
   [nido.platform.process :as nprocess]
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [nido.coordinator.agent :as agent]
   [nido.review.claude :as claude]))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  codex-argv
  "The `codex exec` invocation as [opts & args] for p/process. Pure, so it's
   unit-testable. The prompt is fed via stdin with \"-\" as the positional
   prompt. Review runs read-only. codex's streaming output (stderr merged into
   stdout) is piped to `run-codex!`, which copies it to :log-path, so it never
   floods the review TUI; the structured findings still come back via -o :out-path."
  [{:keys [cwd schema-path out-path prompt]}]
  [{:dir cwd :continue true :in prompt :err :out}
   "codex" "exec" "--skip-git-repo-check"
   "-s" "read-only"
   "--output-schema" schema-path
   "-o" out-path
   "-"])

(def ^:private degenerate-whitespace-run
  "How many whitespace characters in a row end a reading as degenerate. A judge's answer is a few
   kilobytes of JSON and a command's output is source text; neither holds a blank run this long.
   A runaway answer does: 15.8 million spaces after 14 KB of valid JSON, generated for thirty
   minutes until codex's own next request was refused for its size."
  65536)

(def ^:private degenerate-line-chars
  "How long one line of the stream may grow before the reading is degenerate, whatever it holds —
   well under the 10 MiB a codex request may carry, which a final message past it can only fail."
  (* 4 1024 1024))

(defn- copy-log!
  "Copy codex's stream from Reader `in` to Writer `out`, flushing as it goes, until `in` ends —
   answering nil — or the stream turns degenerate, answering :whitespace-run or :line-cap
   (`degenerate-whitespace-run`, `degenerate-line-chars`) with nothing after the point it tripped
   copied.

   Each `exec` header line is written `exec <(stamp)>`: codex's log carries no clock of its own,
   and the command a judge sat in is otherwise found only by its place in the stream. The stream
   is read in chunks rather than lines, because the line to stop is the one that never ends."
  [^java.io.Reader in ^java.io.Writer out stamp]
  (let [buf  (char-array 8192)
        head (StringBuilder.)]
    (loop [i 0 n 0 line 0 ws 0]
      (if (= i n)
        (do (.flush out)
            (let [n' (.read in buf)]
              (if (neg? n')
                (do (.append out head) (.flush out) nil)
                (recur 0 n' line ws))))
        (let [c   (aget buf i)
              ws' (if (Character/isWhitespace c) (inc ws) 0)]
          (if (= c \newline)
            (do (.append out (if (= "exec" (str head)) (str "exec " (stamp)) head))
                (.setLength head 0)
                (.write out (int c))
                (if (> ws' degenerate-whitespace-run)
                  (do (.flush out) :whitespace-run)
                  (recur (inc i) n 0 ws')))
            (do (if (and (= line (.length head)) (< line 4) (= c (.charAt "exec" (int line))))
                  (.append head c)
                  (do (.append out head) (.setLength head 0) (.write out (int c))))
                (cond
                  (> ws' degenerate-whitespace-run) (do (.flush out) :whitespace-run)
                  (> (inc line) degenerate-line-chars) (do (.flush out) :line-cap)
                  :else (recur (inc i) n (inc line) ws')))))))))

(defn- stamp-now [] (str (.truncatedTo (java.time.Instant/now) java.time.temporal.ChronoUnit/SECONDS)))

(defn- stop!
  "SIGTERM `proc`, then a forcible kill ten seconds later if it is still alive."
  [^Process proc]
  (.destroy proc)
  (when-not (.waitFor proc 10 java.util.concurrent.TimeUnit/SECONDS)
    (.destroyForcibly proc)))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  run-codex!
  "Run `codex exec` with output-schema. Seam for tests. Returns {:exit <int> :timed-out? <bool>},
   plus :degenerate (`copy-log!`'s reason) when its stream turned degenerate and it was stopped
   for it. Writes the final JSON response to :out-path and the stream to :log-path. `:budget-ms`
   bounds the run: past it codex is killed, :timed-out? is true and :out-path is left unwritten.

   Spawned rather than shelled so the judge can be stopped when this process is.
   A killed loop used to leave its judge running — still billing, still writing
   an answer nothing would ever read — because nido disables the destroy-tree
   hook globally to cure a macOS shutdown hang."
  [{:keys [budget-ms log-path] :as opts}]
  (let [[opts' & cmd] (codex-argv opts)
        proc   (apply p/process opts' cmd)
        jproc  (:proc proc)
        copied (future
                 (with-open [in  (io/reader (:out proc))
                             out (io/writer (io/file log-path))]
                   (when-let [why (copy-log! in out stamp-now)]
                     (stop! jproc)
                     why)))]
    (nprocess/with-child-registered
      jproc
      #(let [ran        (nprocess/wait-within! jproc budget-ms)
             ;; Bounded: a command codex left running can hold the pipe open past codex itself.
             degenerate (deref copied 10000 nil)]
         (cond-> ran degenerate (assoc :degenerate degenerate))))))

(def ^:private unavailability-signatures
  "What codex prints when the REVIEWER could not be run, as against when it ran
   and the review failed.

   Each row is a phrase codex emits for a condition outside this process — a
   billing quota, a rate limit, a credential, the model being at capacity — or
   for an answer too large to send back, which is the model sampling a
   degenerate reply. What they share is that the run is no evidence at all about
   the branch. That is what makes the distinction worth drawing: it decides
   whether the next move is to open the diff or to wait. How long to wait
   differs — capacity sheds in seconds and a degenerate reply is re-sampled at
   once (`transient-retries-ms`); the rest need a clock or a person.

   Literal vendor phrasing, and deliberately narrow. A looser pattern that also
   matched an ordinary failure would report a working reviewer as an absent one,
   and a reader who believes the status stops looking at the code. A phrase codex
   re-words falls back to the unclassified reading, which costs a classification
   and nothing else — the cheap direction.

   Rows are matched against a line with its typographic punctuation made ASCII
   (`ascii-punctuation`), so a row is written with a plain apostrophe and survives
   codex switching between ' and ’ — which it has done once already.

   Order decides ties, and the ties are near-synonyms; no row is a superset of
   another."
  [{:signal :usage-limit  :re #"(?i)hit your usage limit"}
   {:signal :usage-limit  :re #"(?i)usage limit reached"}
   {:signal :rate-limited :re #"(?i)429 too many requests"}
   {:signal :capacity     :re #"(?i)selected model is at capacity"}
   {:signal :degenerate-output :re #"(?i)string too long.*maximum length"}
   {:signal :unauthorized :re #"(?i)401 unauthorized"}
   {:signal :unauthorized :re #"(?i)\bnot logged in\b"}])

(def ^:private unavailability-tail-chars
  "How much of the END of a review log to classify against.

   A review log is the model's whole streamed trace — hundreds of kilobytes on a
   real layer — and the line saying how the run stopped is the last thing in it.
   Reading the body would match a reviewer DISCUSSING a rate limit in the code
   it was reviewing, which is the one false positive that matters here."
  8192)

(defn- log-tail
  "The last `n` characters of `path`, or nil when it cannot be read. Nil is a
   legitimate answer: the log is codex's own stream, and a reviewer that died
   before opening it leaves nothing to classify."
  [path n]
  (try
    (let [s (slurp path)]
      (cond-> s (> (count s) n) (subs (- (count s) n))))
    (catch Throwable _ nil)))

(defn- ascii-punctuation
  "`line` with curly quotes and apostrophes replaced by their ASCII forms. For
   matching only — the line a caller is handed stays the vendor's verbatim."
  [line]
  (str/replace line #"[\x{2018}\x{2019}\x{201C}\x{201D}]"
               {"\u2018" "'" "\u2019" "'" "\u201C" "\"" "\u201D" "\""}))

(defn ^{:malli/schema [:=> [:cat [:maybe :string]] [:maybe :map]]}
  unavailability
  "Why no reviewer ran, read out of the tail of the log the reviewer streamed —
   or nil when nothing in it says the reviewer was unavailable. The phrases are
   codex's. A claude reviewer ends a failed log on its own words (see
   `claude/run-claude!`), which are classified only where they happen to share
   one; otherwise its failure reads as a failed review.

   {:signal :usage-limit|:rate-limited|:capacity|:degenerate-output|:unauthorized
    :message <the line codex printed>
    :retry-at <when it said to come back, when it said>}

   The line VERBATIM, never a sentence of ours: it is the only place the remedy
   and the reset hour exist. A quota exhaustion reported as `codex review failed`
   sent a reader to look for a broken review, while the sentence naming the
   credits page and the hour the window lifts sat unreferenced in a 500 KB log.

   The signal is nido's own reading of that line and is kept beside it rather
   than left to be inferred from it: whether to wait or to authenticate is the
   operator's next move, and the prose it would be inferred from is a vendor's
   to re-word.

   The LAST matching line, because codex retries internally and narrates each
   attempt; the one that ended the run is the last one it printed."
  [tail]
  (when tail
    (let [lines (str/split-lines tail)]
      (some (fn [{:keys [signal re]}]
              (when-let [line (some-> (last (filter #(re-find re (ascii-punctuation %)) lines)) str/trim)]
                (let [when-back (second (re-find #"(?i)try again at (.+?)\.?\s*$" line))]
                  (cond-> {:signal signal :message line}
                    when-back (assoc :retry-at when-back)))))
            unavailability-signatures))))

(defn- usage-footer?
  "Whether `line` is part of the token count codex prints after a run ends, even a failed one:
   a `tokens used` line and the bare number under it."
  [line]
  (boolean (re-matches #"(?i)tokens used|[\d,]+" line)))

(defn ^{:malli/schema [:=> [:cat [:maybe :string]] [:maybe :string]]}
  last-line
  "The line of the log at `log-path` most likely to say why the reviewer stopped, trimmed, or nil
   when the log has no non-blank line or cannot be read: the last line beginning `ERROR`, else the
   last non-blank line before codex's token-count footer. For a failure `unavailability` cannot
   classify, the reviewer's own last words are usually the error, and the only other copy of them
   is at the end of a log hundreds of kilobytes long — under a footer that, read as the last line,
   reports a token count as the cause."
  [log-path]
  (when log-path
    (when-let [lines (some->> (log-tail log-path unavailability-tail-chars)
                              str/split-lines
                              (map str/trim)
                              (remove str/blank?)
                              reverse
                              (drop-while usage-footer?)
                              seq)]
      (or (first (filter #(str/starts-with? % "ERROR") lines))
          (first lines)))))

;; ── Which reviewer judges ───────────────────────────────────────────────────

(def reviewers
  "Every reviewer a round can be judged by. Each is run through `run-one!`, and
   each keeps `run-codex!`'s contract, so nothing past `run-reviewer!` knows
   which one ran."
  #{:codex :claude})

(def default-reviewer
  "The reviewer when neither the run nor its project names one."
  :codex)

(def ^:private executing
  "The reviewers whose sandbox runs a command they choose: codex's read-only sandbox runs anything
   that writes nothing, while claude's shell admits only the reads `claude/allowed-commands` names.
   A clause about what a host DOES — a binary missing, a script exiting 127 — is observed by the
   first and only inferred by the second, so which kind of reader ruled is part of the ruling."
  #{:codex})

(defn ^{:malli/schema [:=> [:cat :any] :boolean]}
  executes?
  "Whether `reviewer` can run a command of its choosing. False for nil and for any name not a
   reviewer: a reading nobody can place is not one to credit with having observed anything."
  [reviewer]
  (contains? executing reviewer))

(def ^:private codex-failed-call
  "How codex's stream ends an `exec` block whose command did not succeed: ` exited <n> in <t>`. A
   command that succeeded ends ` succeeded in <t>`."
  #"(?m)^\s*exited -?\d+ in \d")

(defn ^{:malli/schema [:=> [:cat :any [:maybe :string]] :boolean]}
  tool-failed?
  "Whether the log `reviewer` streamed to `log-path` shows a tool call of its that was denied or
   errored — the only ground a judge's `tool-failure` cause can stand on. A judge that predicts a
   refusal and never makes the call has observed nothing about its tools. False when the log
   cannot be read, or `reviewer` is none `reviewers` names."
  [reviewer log-path]
  (boolean
   (when log-path
     (case reviewer
       :codex  (some->> (try (slurp log-path) (catch Throwable _ nil)) (re-find codex-failed-call))
       :claude (claude/failed-call? log-path)
       false))))

(defn ^{:malli/schema [:=> [:cat :any [:maybe :string]] [:vector :string]]}
  refused-paths
  "The paths the log `reviewer` streamed to `log-path` shows it was refused reading
   (`claude/refused-reads`). None for codex, whose read-only sandbox reads the whole disk."
  [reviewer log-path]
  (if (and log-path (= :claude reviewer)) (claude/refused-reads log-path) []))

(declare stand-in-log)

(defn ^{:malli/schema [:=> [:cat :string :map] :string]}
  log-of
  "Where the reading `judged-by` names streamed, for a round that asked for `log-path`: the log
   itself, or a stand-in's beside it (`run-reviewer!`)."
  [log-path judged-by]
  (if (:instead-of judged-by)
    (stand-in-log log-path (:reviewer judged-by))
    log-path))

(defn ^{:malli/schema [:=> [:cat :keyword] :keyword]}
  other-reviewer
  "The reviewer that is not `reviewer` — who reads a record a second time, so that two agreeing
   readings are two judges agreeing rather than one judge sampled twice."
  [reviewer]
  (first (disj reviewers reviewer)))

(defn ^{:malli/schema [:=> [:cat :any :any] :keyword]}
  reviewer-for
  "The reviewer a run is judged by: `override`, the run's own choice, else
   `configured`, its project's `:reviewer` in projects.edn, else
   `default-reviewer`. Either may be spelled as a keyword, a string or a symbol,
   which is how an EDN file and a command line spell it.

   A name that is no reviewer THROWS rather than falling back to the default. A
   misspelled `:reviewer` that quietly ran codex would look exactly like the
   setting working."
  [override configured]
  (if-let [chosen (or override configured)]
    (let [k (keyword (name chosen))]
      (or (reviewers k)
          (throw (ex-info (str "unknown reviewer " (pr-str chosen) " — expected one of "
                               (str/join ", " (map name (sort reviewers))))
                          {:reason :unknown-reviewer :reviewer chosen}))))
    default-reviewer))

(def ^:private fallbacks
  "Who judges instead when a reviewer could not be run, and on which of
   `unavailability`'s signals.

   Only codex's quota. A quota does not lift until a date, often days out, and
   the review it blocks is one another vendor's model can do. A credential is
   answered by logging in, and switching judges over one would keep a broken
   login hidden behind reviews that go on succeeding. A 429 is codex's own
   transient throttling and passes in minutes. Capacity is retried on the same
   reviewer instead (`transient-retries-ms`)."
  {:codex {:reviewer :claude :on #{:usage-limit}}})

(def ^:private transient-retries-ms
  "The `unavailability` signals worth running the SAME reviewer again for, each with how long to
   wait before each re-run — one entry per retry, so its length is the retry bound.

   Capacity: the vendor shedding load, which a request moments later usually gets past. Each
   unretried capacity refusal ended a whole record run, or aborted a diff round and the clean layer
   reviews already paid for beside it. Two minutes is the most a vendor blip costs before the run
   says the reviewer was unavailable.

   A degenerate reply, once and at once: it is one bad sample of a prompt the same judge answers
   in minutes, and its prefix is no answer to keep — it lacks the schema's closing fields.

   Not a 429, whose window is minutes — a short backoff only spends the wait without outlasting it."
  {:capacity          [20000 60000]
   :degenerate-output [0]})

(defn- sleep!
  "Seam for tests."
  [ms]
  (Thread/sleep (long ms)))

(defn- run-one!
  [reviewer opts]
  (case reviewer
    :codex  (run-codex! opts)
    :claude (claude/run-claude! opts)))

(defn- stand-in-log
  "Where a stand-in writes its stream: beside the log of the reviewer it replaced,
   never over it. That log holds the line saying why the stand-in ran."
  [log-path stand-in]
  (str (str/replace log-path #"\.log$" "") "-" (name stand-in) ".log"))

(defn- why-unavailable
  "Why the run that streamed to `log-path` could not be run at all — `unavailability`'s
   reading of its log's tail — or nil when it left an answer or its log does not say."
  [{:keys [exit log-path]} out-path]
  (when (or (not (zero? exit)) (not (fs/exists? out-path)))
    (unavailability (log-tail log-path unavailability-tail-chars))))

(defn- degenerate-reading
  "The `unavailability` map for a reading `run-codex!` stopped as degenerate. The sentence is
   nido's: codex printed none, since it was stopped before the vendor refused the answer."
  [why]
  {:signal  :degenerate-output
   :message (case why
              :whitespace-run (str "codex was stopped: its stream ran " degenerate-whitespace-run
                                   " whitespace characters without content")
              :line-cap       (str "codex was stopped: one line of its stream passed "
                                   degenerate-line-chars " characters"))})

(defn- run-within!
  "`run-reviewer!` over a launch budget already settled: the primary, its transient retries, and
   its stand-in."
  [reviewer opts out-path log-path judged-by]
  (let [[ran u]  (loop [retried {}]
                   (let [{:keys [exit timed-out? degenerate]} (run-one! reviewer opts)
                         ran   (cond-> {:exit exit :log-path log-path :judged-by judged-by}
                                 timed-out? (assoc :timed-out true))
                         u     (cond
                                 timed-out? nil
                                 degenerate (degenerate-reading degenerate)
                                 :else      (why-unavailable ran out-path))
                         sig   (:signal u)
                         waits (get transient-retries-ms sig)
                         n     (get retried sig 0)]
                     (if (< n (count waits))
                       (do (sleep! (nth waits n)) (recur (assoc retried sig (inc n))))
                       [ran u])))
        stand-in (get fallbacks reviewer)]
    (if (contains? (:on stand-in) (:signal u))
      (let [log' (stand-in-log log-path (:reviewer stand-in))
            {:keys [exit timed-out?]} (run-one! (:reviewer stand-in) (assoc opts :log-path log'))
            ran' {:exit      exit
                  :log-path  log'
                  :judged-by {:reviewer   (:reviewer stand-in)
                              :executes?  (executes? (:reviewer stand-in))
                              :instead-of reviewer
                              :because    (:message u)}}]
        (cond-> ran'
          timed-out? (assoc :timed-out true)
          (and (not timed-out?) (why-unavailable ran' out-path))
          (assoc :unavailable (why-unavailable ran' out-path))))
      (cond-> ran u (assoc :unavailable u)))))

(def judge-budget
  "The wall clock one reviewer launch runs under when its caller names none. A judge only reads
   and reports; one still reading after this long is not about to answer, and before this cap
   existed a judge ran unbounded — 66 minutes on one baseline round — while its loop's declared
   budget bounded only the amenders."
  "30m")

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  run-reviewer!
  "Run `:reviewer` (`default-reviewer` when absent) over the rest of `opts`, the
   keys `run-codex!` takes. When it could not be run for a reason `fallbacks`
   names, run its stand-in on the same prompt and schema, writing the same
   :out-path.

   Returns {:exit <int> :log-path <str> :judged-by <map>}, plus :unavailable when
   the last run left no answer and its log says the reviewer could not be run —
   `unavailability`'s map, so a caller tells an absent reviewer from a failed
   review without reading a log. :exit and :log-path are the LAST run's, since
   the stand-in's log is a different file. :judged-by says who that was:
   {:reviewer <kw> :executes? <bool>} (`executes?`), plus :instead-of and :because
   (the line that made it stand in) when it was a stand-in.

   The primary runs first every time, including on a round after one it could not
   run in. A quota can lift mid-run, and nothing in the log says when codex's
   will except a date in the vendor's own wording.

   A primary whose run left a signal `transient-retries-ms` names is run again after each of its
   waits, over the same log, and only the last attempt is answered for. A run `run-codex!` stopped
   as :degenerate reads as :degenerate-output. Still refused, it returns :unavailable like any
   other signal.

   Every launch runs under `:budget` (`judge-budget` when absent), cut to what the
   caller's `agent/*deadline-ms*` leaves. A launch killed on it answers :timed-out
   true and is neither retried nor handed to a stand-in: the clock, not the vendor,
   stopped it. With nothing left on the deadline nothing is launched, and the
   answer is :timed-out with :exit -1."
  [{:keys [reviewer out-path log-path budget] :as opts}]
  (let [reviewer  (or reviewer default-reviewer)
        budget-ms (agent/capped-budget-ms (agent/parse-budget-ms (or budget judge-budget)))
        opts      (assoc opts :budget-ms budget-ms)
        judged-by {:reviewer reviewer :executes? (executes? reviewer)}]
    (if (zero? budget-ms)
      {:exit -1 :log-path log-path :judged-by judged-by :timed-out true}
      (run-within! reviewer opts out-path log-path judged-by))))
