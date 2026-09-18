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
   [nido.review.claude :as claude]))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  codex-argv
  "The `codex exec` invocation as [opts & args] for p/shell. Pure, so it's
   unit-testable. The prompt is fed via stdin with \"-\" as the positional
   prompt. Review runs read-only. codex's streaming output is captured to
   :log-path (stderr merged into stdout) so it never floods the review TUI;
   the structured findings still come back via -o :out-path."
  [{:keys [cwd schema-path out-path prompt log-path]}]
  [{:dir cwd :continue true :in prompt
    :out :write :out-file (io/file log-path) :err :out}
   "codex" "exec" "--skip-git-repo-check"
   "-s" "read-only"
   "--output-schema" schema-path
   "-o" out-path
   "-"])

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  run-codex!
  "Run `codex exec` with output-schema. Seam for tests. Returns {:exit <int>}.
   Writes the final JSON response to :out-path.

   Spawned rather than shelled so the judge can be stopped when this process is.
   A killed loop used to leave its judge running — still billing, still writing
   an answer nothing would ever read — because nido disables the destroy-tree
   hook globally to cure a macOS shutdown hang."
  [opts]
  (let [[opts' & cmd] (codex-argv opts)
        proc (apply p/process opts' cmd)]
    (nprocess/with-child-registered
      (:proc proc)
      #(let [res @proc] {:exit (:exit res)}))))

(def ^:private unavailability-signatures
  "What codex prints when the REVIEWER could not be run, as against when it ran
   and the review failed.

   Each row is a phrase codex emits for a condition outside this process — a
   billing quota, a rate limit, a credential. What they share is that the run is
   no evidence at all about the branch, and that running the same command again
   changes nothing until a clock or a person intervenes. That is what makes the
   distinction worth drawing: it decides whether the next move is to open the
   diff or to wait.

   Literal vendor phrasing, and deliberately narrow. A looser pattern that also
   matched an ordinary failure would report a working reviewer as an absent one,
   and a reader who believes the status stops looking at the code. A phrase codex
   re-words falls back to the unclassified reading, which costs a classification
   and nothing else — the cheap direction.

   Order decides ties, and the ties are near-synonyms; no row is a superset of
   another."
  [{:signal :usage-limit  :re #"(?i)you'?ve hit your usage limit"}
   {:signal :usage-limit  :re #"(?i)usage limit reached"}
   {:signal :rate-limited :re #"(?i)429 too many requests"}
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

(defn ^{:malli/schema [:=> [:cat [:maybe :string]] [:maybe :map]]}
  unavailability
  "Why no reviewer ran, read out of the tail of the log the reviewer streamed —
   or nil when nothing in it says the reviewer was unavailable. The phrases are
   codex's. A claude reviewer ends a failed log on its own words (see
   `claude/run-claude!`), which are classified only where they happen to share
   one; otherwise its failure reads as a failed review.

   {:signal :usage-limit|:rate-limited|:unauthorized
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
              (when-let [line (some-> (last (filter #(re-find re %) lines)) str/trim)]
                (let [when-back (second (re-find #"(?i)try again at (.+?)\.?\s*$" line))]
                  (cond-> {:signal signal :message line}
                    when-back (assoc :retry-at when-back)))))
            unavailability-signatures))))

;; ── Which reviewer judges ───────────────────────────────────────────────────

(def reviewers
  "Every reviewer a round can be judged by. Each is run through `run-one!`, and
   each keeps `run-codex!`'s contract, so nothing past `run-reviewer!` knows
   which one ran."
  #{:codex :claude})

(def default-reviewer
  "The reviewer when neither the run nor its project names one."
  :codex)

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
   transient throttling and passes in minutes."
  {:codex {:reviewer :claude :on #{:usage-limit}}})

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
   {:reviewer <kw>}, plus :instead-of and :because (the line that made it stand
   in) when it was a stand-in.

   The primary runs first every time, including on a round after one it could not
   run in. A quota can lift mid-run, and nothing in the log says when codex's
   will except a date in the vendor's own wording."
  [{:keys [reviewer out-path log-path] :as opts}]
  (let [reviewer (or reviewer default-reviewer)
        {:keys [exit]} (run-one! reviewer opts)
        ran      {:exit exit :log-path log-path :judged-by {:reviewer reviewer}}
        u        (why-unavailable ran out-path)
        stand-in (get fallbacks reviewer)]
    (if (contains? (:on stand-in) (:signal u))
      (let [log' (stand-in-log log-path (:reviewer stand-in))
            {:keys [exit]} (run-one! (:reviewer stand-in) (assoc opts :log-path log'))
            ran' {:exit      exit
                  :log-path  log'
                  :judged-by {:reviewer   (:reviewer stand-in)
                              :instead-of reviewer
                              :because    (:message u)}}]
        (cond-> ran'
          (why-unavailable ran' out-path) (assoc :unavailable (why-unavailable ran' out-path))))
      (cond-> ran u (assoc :unavailable u)))))
