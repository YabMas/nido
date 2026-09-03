(ns nido.design.check
  "Does this worktree's code still stand up the design the project declared?

   nido drives projects; a project may declare its high-level structure as a fukan model in
   `canvas/`, and fukan will say whether the code obeys it. This namespace is the seam between
   the two: it locates a project's model, runs the checker over a worktree, and returns findings
   as data for whoever asked — a briefing being composed, a review stage, a landing gate.

   Two things it deliberately is not:

   - It is not a checker. Fukan owns what a violation is; this shells to `fukan.check` and reads
     what comes back. A second opinion about the design living here would be a second design.
   - It is not an opinion about what to DO with a violation. A session briefing wants to warn, a
     landing gate wants to refuse, a review loop wants to hand it to a fixer. Those are the
     callers' calls, which is why every reading here returns a status rather than exiting.

   A project with no `canvas/` answers `:unmodelled`, and that is not a failure. Most projects
   nido drives declare no design at all, and a seam that reported them as broken would be
   switched off within a week — after which it would not be there for the projects that do."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [nido.platform.project :as project])
  (:import [java.security MessageDigest]))

(def default-design
  "What a modelled project looks like when it says nothing: fukan's own conventions.

   `:cmd` is the documented way to consume fukan from another project — a `:fukan` alias whose
   `:extra-paths [\".\"]` puts the consumer's root on the classpath, making `canvas/` both
   discoverable and requirable. The VERB is appended to it, so one entry covers both questions a
   project can ask. A project that consumes fukan differently overrides it."
  {:src       "src"
   :spec-dirs ["canvas"]
   :cmd       ["clojure" "-M:fukan" "-m" "fukan.cli"]
   ;; No selection by default: most projects declare a design small enough to read whole, and a
   ;; default that scoped it would silently hide the rest. A project whose design has outgrown a
   ;; briefing sets `:select` in its registry entry — datalog `:where` clauses binding `?n`,
   ;; e.g. `[(Band ?n)]` for the architecture without the element detail underneath it.
   :select    nil})

(def ^:private check-timeout-ms
  "How long to wait for the checker. It is a cold JVM plus a model build over the whole code
   tree — seconds, not milliseconds — and the ceiling exists so a wedged one cannot hang a
   session coming up."
  180000)

(def ^:private describe-timeout-ms
  "How long to wait for a design DOCUMENT. Shorter than the check's budget on purpose: the
   model build behind it reads no code at all (~0.5s; the rest is JVM start), and this one is
   in the path of a session briefing, which must not sit for minutes waiting to say something
   it can equally well not say."
  60000)

(defn ^{:malli/schema [:=> [:cat :ProjectName :string] [:maybe :DesignConfig]]}
  design-of
  "The design configuration for `project-name` in `worktree`, or nil when the project declares
   no design.

   Detection is by CONVENTION, not registration: a project is modelled iff its spec-dirs hold
   `.clj` files. Nothing to add to the registry, nothing to keep in sync with the tree — the
   canvas is checked into the repo, so its presence in a worktree is already the truth about
   whether that branch has a design. A project whose layout differs (a `src/main` root, a
   canvas somewhere else) overrides via `:design` in the project registry.

   `project-name` may be a string or a keyword — callers hold it both ways, and a lookup that
   silently missed on the wrong one would fall back to the defaults and check the wrong tree."
  [project-name worktree]
  (let [cfg   (merge default-design (:design (project/get-project (name project-name))))
        dirs  (for [d (:spec-dirs cfg)] (fs/path worktree d))
        files (mapcat #(when (fs/directory? %) (fs/glob % "**.clj")) dirs)]
    (when (seq files)
      (assoc cfg :files (mapv str (sort-by str files))))))

(defn- run-fukan
  "Run one fukan verb over `worktree`, or `::timeout`. The verb is appended to the configured
   `:cmd`, so a project that consumes fukan differently overrides one thing rather than two."
  [{:keys [cmd]} worktree verb flags timeout-ms]
  (let [argv (concat cmd [verb] flags)
        proc (process/process argv {:dir (str worktree) :out :string :err :string})
        done (deref proc timeout-ms ::timeout)]
    (when (= ::timeout done) (process/destroy-tree proc))
    (or (and (= ::timeout done) ::timeout) done)))

(defn- unrendered-message
  "Why a render that ran out of time did, and what to do about it.

   The two cases are not the same problem and must not get the same sentence. Asked WITHOUT a
   selection, the budget is not the fault — a whole model is simply more than a session can
   wait for, and the fix is to ask for less; nido's own canvas renders in seconds scoped to its
   bands and does not finish at all unscoped. Asked WITH one and still too slow, the selection
   is not the missing piece and saying so would send the reader after a knob they already
   turned."
  [{:keys [spec-dirs]} select]
  (str "the design did not render within " (quot describe-timeout-ms 1000) "s"
       (if select
         (str ", even scoped to " (pr-str select) "."
              " Reproduce it with `clojure -M:fukan -m fukan.cli describe --spec-dirs "
              (str/join "," spec-dirs) " --select '" (pr-str select) "'`.")
         (str ", and nothing narrowed it. A whole model is more than a session start can wait"
              " for once a project has more than a few dozen declarations. Ask fukan for the"
              " part that governs the area — `--select '[(Band ?n)]'` for the architecture —"
              " and record it as `:select` on the project's registry entry, or as `:scope` on"
              " the workstream's baseline."))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :string [:? [:maybe :any]]] :DesignDocument]}
  describe
  "The project's declared design as a document:

     {:status :unmodelled}                      no design declared — nothing to render
     {:status :described   :document \"…\"}      the declaration, as fukan renders it
     {:status :undecidable :error \"…\"}         fukan did not answer

   Asked of FUKAN rather than read off disk. Fukan is what knows what a design is: which
   vocabularies the project actually instantiated, which nodes are its own rather than the
   meta-grammar's, and how an instance was authored. Reading `canvas/**.clj` instead worked
   only for the case where a design happens to be one hand-written file — it pasted requires
   and helper fns along with the declarations, said nothing about a model that is partly
   extracted, and offered no way to select. `bands.clj` is a structure expressed through fukan,
   not a privileged shape this seam should know the outline of.

   `:undecidable` is reported rather than folded into `:unmodelled`, for the reason `check`
   keeps them apart and one sharper. A render is asked for at `session:up`, where the only
   available failure was to omit the section — so a project whose design is too large to render
   inside the budget briefed exactly like a project with no design at all, and the agent was
   told nothing either way. The larger the design, the more certainly it vanished.

   The document is COMPLETE, and the reader that has a budget is the one that applies it. This
   used to truncate at a briefing's cap, which is a fact about a briefing rather than about a
   design — and a second reader arrived that compares two renderings, for which a shared cap is
   not a smaller answer but a wrong one: every change past the cut would read as no change."
  ([project-name worktree] (describe project-name worktree nil))
  ([project-name worktree scope]
   (if-let [design (design-of project-name worktree)]
     (let [{:keys [spec-dirs]} design
           ;; an explicit scope WINS over the project's configured default: the caller that has
           ;; one got it from a baseline that read the code, and the default was set by someone
           ;; who had not
           select (or scope (:select design))
           done   (run-fukan design worktree "describe"
                             (cond-> ["--spec-dirs" (str/join "," spec-dirs)]
                               select (conj "--select" (pr-str select)))
                             describe-timeout-ms)]
       (cond
         (= ::timeout done)
         {:status :undecidable :error (unrendered-message design select)}

         (not (zero? (long (:exit done))))
         {:status :undecidable
          :error  (let [e (str/trim (str (:err done)))]
                    (if (str/blank? e) (str "fukan exited " (:exit done)) e))}

         (str/blank? (str/trim (str (:out done))))
         {:status :undecidable :error "fukan rendered an empty document"}

         :else
         {:status :described :document (str/trim (:out done))}))
     {:status :unmodelled})))

(defn- parse-report
  "Read the checker's stdout. Unparseable output is UNDECIDABLE rather than clean: stdout is
   the report, and if it is not a report then nothing was decided."
  [out]
  (try (let [r (edn/read-string out)] (when (map? r) r))
       (catch Exception _ nil)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :string [:? [:maybe :DesignConfig]]] :CheckResult]}
  check
  "Run the checker over `worktree` and return what it found:

     {:status :unmodelled}                         no design declared — nothing to say
     {:status :satisfied}                          every law holds
     {:status :violated    :violations [...]}      laws fired; each carries its named offenders
     {:status :undecidable :error \"…\"}             the check itself did not complete

   Every answer but `:unmodelled` also carries `:files` — where the declaration is written.
   A reading that prints a violation has to name them, and asking for them separately meant
   resolving the same config twice.

   `:undecidable` is reported, never swallowed into `:satisfied`. A caller that cannot tell
   \"the design holds\" from \"nobody could tell\" will eventually wave through the branch that
   broke the checker."
  ([project-name worktree] (check project-name worktree (design-of project-name worktree)))
  ([_project-name worktree design]
   (if-not design
     {:status :unmodelled}
     (let [{:keys [src spec-dirs files]} design
           done (run-fukan design worktree "check"
                           ["--src" src "--spec-dirs" (str/join "," spec-dirs)] check-timeout-ms)
           ;; carried on every modelled answer, not just the ones that print it. A caller that
           ;; had to ask for the files separately was re-resolving a config this call already
           ;; holds, and two resolutions of "where is the declaration" can disagree once a
           ;; branch moves the canvas.
           with-files (fn [m] (cond-> m (seq files) (assoc :files (vec files))))]
       (with-files
         (if (= ::timeout done)
           {:status :undecidable
            :error  (str "the design check did not finish within "
                         (quot check-timeout-ms 1000) "s")}
           (let [{:keys [exit out err]} done
                 report (parse-report out)]
             (case (long exit)
               0 {:status :satisfied}
               1 (if report
                   {:status :violated :violations (:violations report)}
                   {:status :undecidable :error (str "unreadable report: " (str/trim (str out)))})
               {:status :undecidable
                :error  (or (:error report) (str/trim (str err)) (str "exit " exit))}))))))))

(defn ^{:malli/schema [:=> [:cat [:maybe [:vector :string]] [:vector :string]] :string]}
  offender-line
  "One offender row as a line, its columns LABELLED by the law's own offender var names.

   A row is a tuple — a law binding an edge carries both ends and the bands they sit in — and
   four names in a line read as a four-hop chain, which is not what they are. The labels come
   from the law rather than from a per-law renderer here, so nido never holds a second, drifting
   copy of what each law's columns mean. A law that named its vars poorly renders poorly; a law
   that named them well is legible without nido knowing anything about it."
  [vars row]
  (if (= (count vars) (count row))
    (str/join "  " (map (fn [v x] (str (str/replace (str v) #"^\?" "") "=" x)) vars row))
    (str/join " · " row)))

(defn ^{:malli/schema [:=> [:cat :CheckResult] :string]}
  violation-text
  "The findings as text for a human or an agent to read. Empty string when there is nothing to
   say, so a caller can splice it into a document without testing first."
  [{:keys [status violations error]}]
  (case status
    (:unmodelled :satisfied) ""
    :undecidable (str "The design check did not complete: " error "\n")
    :violated
    (str/join "\n"
              (for [{:keys [law vars offenders]} violations]
                (str "✗ " law "\n"
                     (str/join "\n" (for [row offenders]
                                       (str "    " (offender-line vars row)))))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName] :string]}
  unmodelled-line
  "The one sentence a terminal reading says when `project-name` declares no design.

   The seam's words, not each reading's. Three readings said this separately and each said it
   differently — one called the model a `design` and one called it a `structure` — and `diff`
   arrived saying it twice, inside the very change whose record had promised not to add a
   fourth. Only the seam knows what declaring no design means; the prefix and what to do next
   still belong to the reading, because a gate and a question are addressing different people."
  [project-name]
  (str (name project-name) " declares no design"))

(defn ^{:malli/schema [:=> [:cat :CheckResult] :Refusal]}
  refusal
  "A violated result as both terminal readings state it: `{:count <rows> :body \"…\"}`.

   The body is the part `design:check` and `land:check` say identically — the offenders, that
   one of the two sides is wrong, and where the declaration is. Their headlines differ because
   they address different people (one answers a question, one stops a landing), and so does what
   each says next.

   No exit code, and that is the line rather than an omission. `status-not-exit` is what lets a
   briefing warn where a gate refuses; a seam that returned a code would have decided for both.

   The count is ROWS, not laws. One law broken in forty places is one finding for a review and
   forty violations for a human deciding whether to look — and two readings that counted
   differently would describe the same branch two ways."
  [{:keys [violations files] :as result}]
  {:count (reduce + (map (comp count :offenders) violations))
   :body  (str (violation-text result)
               "\n\nEither the code moves or the declaration does — one of them is wrong."
               (when (seq files)
                 (str "\nThe declaration is in " (str/join ", " files) ".")))})

(defn- digest-of
  "A short stable name for a rendering.

   Its own four lines rather than `nido.review.digest`'s: the Design band may depend on Platform
   alone, and Review sits above it. The alternative is not sharing the function, it is inverting
   a dependency the band declaration exists to hold."
  [s]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (->> (.digest md (.getBytes (str s) "UTF-8"))
         (map #(format "%02x" %))
         (apply str)
         (take 12)
         (apply str))))

(defn- unified
  "`before` against `after` as a unified diff, or nil when a diff could not be taken.

   Shelled to `diff`, which is on every machine this runs on and has had its edge cases found.
   Exit 1 means they differ and is the expected answer here; only 2 and above is a failure."
  [before after]
  (let [a (fs/create-temp-file {:prefix "design-base-" :suffix ".txt"})
        b (fs/create-temp-file {:prefix "design-head-" :suffix ".txt"})]
    (try
      (spit (str a) before)
      (spit (str b) after)
      (let [{:keys [exit out]} (process/shell {:out :string :err :string :continue true}
                                              "diff" "-u"
                                              "--label" "the design at the base"
                                              "--label" "the design on this branch"
                                              (str a) (str b))]
        (when (< (long exit) 2) out))
      (finally (fs/delete-if-exists a) (fs/delete-if-exists b)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :string :string [:? [:maybe :any]]] :DesignDiff]}
  diff
  "What this worktree changes about the project's declared design, against `base-dir` — a
   directory holding the spec dirs as they were at some base revision:

     {:status :unmodelled}                          no design declared here — nothing to compare
     {:status :unchanged  :digest \"…\"}             the declaration is what it was
     {:status :changed    :diff \"…\" :digest \"…\"}   what it became
     {:status :undecidable :error \"…\"}             one of the two ends would not render

   The reading that was missing. `check` answers whether the code obeys the declaration as it
   now stands, which is the same question for a branch that rewrote `canvas/` and one that
   touched only code — so a design change was the one change nothing here could show.

   A DIRECTORY, not a revision. Materializing a revision is a VCS question and the Design band
   may reach nothing but Platform; the caller materializes and hands the result down.

   Both ends are rendered by fukan and diffed as TEXT, which is honest about what it is: a
   reordering would read as a change. Two renderings of the same specs are byte-identical across
   separate runs, so that is the only false positive available to it — and the alternative, a
   structural model diff, belongs in fukan rather than here.

   A base that declares no design is not an error. A branch that ADOPTS fukan changes the
   declared design from nothing to something, and that is exactly what a reviewer wants to see."
  ([project-name worktree base-dir] (diff project-name worktree base-dir nil))
  ([project-name worktree base-dir scope]
   (let [head (describe project-name worktree scope)]
     (case (:status head)
       :unmodelled  {:status :unmodelled}
       :undecidable {:status :undecidable
                     :error  (str "this branch's design did not render: " (:error head))}
       (let [base      (describe project-name base-dir scope)
             base-doc  (case (:status base)
                         :described  (:document base)
                         :unmodelled ""
                         ::failed)
             head-doc  (:document head)]
         (if (= ::failed base-doc)
           {:status :undecidable
            :error  (str "the base's design did not render: " (:error base))}
           (if (= base-doc head-doc)
             {:status :unchanged :digest (digest-of head-doc)}
             (if-let [d (unified base-doc head-doc)]
               {:status :changed :diff d :digest (digest-of head-doc)}
               {:status :undecidable :error "the two renderings could not be diffed"}))))))))
