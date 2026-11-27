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

(defn- config-of
  "The design configuration `project-name`'s registry entry lays over the defaults."
  [project-name]
  (merge default-design (:design (project/get-project (name project-name)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:vector :string]]}
  spec-dirs
  "The dirs, relative to a worktree root, where `project-name` keeps its design declaration —
   whether or not they hold one in any given tree. `design-of` answers whether they do."
  [project-name]
  (vec (:spec-dirs (config-of project-name))))

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
  (let [cfg   (config-of project-name)
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

(def ^:private load-trace-timeout-ms
  "How long to wait for a canvas namespace to be required on its own. One JVM and one namespace's
   load — a few seconds — so it has the renderer's budget, not the check's."
  60000)

(def ^:private unloaded-ns
  "The namespace fukan names when a canvas namespace fails to load. Fukan prints this phrase in the
   report it writes on stdout; it is read in this one place, and a caller reads the `:cause` it
   becomes, never the text."
  #"failed to load canvas namespace (\S+)")

(defn- canvas-ns
  "The namespace a canvas file under `worktree` declares, read off its path: `canvas/review/core.clj`
   is `canvas.review.core`. Nil for a path that is not a .clj file."
  [worktree file]
  (let [rel (str (fs/relativize (fs/absolutize worktree) (fs/absolutize (fs/path worktree file))))]
    (when (str/ends-with? rel ".clj")
      (-> rel (subs 0 (- (count rel) 4)) (str/replace "/" ".") (str/replace "_" "-")))))

(defn- load-cmd
  "The command that evaluates one form on the project's fukan classpath — `:load-cmd` when the
   project names one, else `:cmd` up to its `-m` (the main it runs), with `-e`. Nil when neither
   says how, in which case fukan's own error is all there is."
  [{:keys [cmd] :as design}]
  (or (:load-cmd design)
      (let [[head tail] (split-with #(not= "-m" %) cmd)]
        (when (seq tail) (vec (concat head ["-e"]))))))

(defn- load-error
  "What requiring `ns-name` on its own in `worktree` says, or nil when it loads. Clojure's own
   compiler error, with the classpath's warnings and the pointer to its report file dropped: that
   is the text that names the form which broke — for a duplicated (Operation …), the module that
   authors two instances of one name — where fukan's names whichever namespace required it first."
  [design worktree ns-name]
  (when-let [argv (load-cmd design)]
    (let [proc (process/process (concat argv [(str "(require '" ns-name ")")])
                                {:dir (str worktree) :out :string :err :string})
          done (deref proc load-trace-timeout-ms ::timeout)]
      (when (= ::timeout done) (process/destroy-tree proc))
      (when (and (not= ::timeout done) (not (zero? (long (:exit done)))))
        (let [lines (->> (str/split-lines (str (:err done)))
                         (remove #(str/starts-with? % "WARNING:"))
                         (remove #(str/starts-with? % "Full report at"))
                         (remove #(re-find #"^/\S+\.edn$" (str/trim %))))]
          (not-empty (str/trim (str/join "\n" lines))))))))

(defn- load-failure
  "The `:load-failure` answer for a listing fukan refused because `named` would not load: the
   first of the `changed` canvas namespaces, then `named` itself, that fails to load on its own,
   with its own load error — or fukan's error alone when none can be traced."
  [design worktree named changed fukan-error]
  (let [candidates (distinct (concat (keep #(canvas-ns worktree %) changed) [named]))
        traced     (some (fn [n] (when-let [e (load-error design worktree n)] [n e])) candidates)]
    {:status :undecidable
     :cause  :load-failure
     :error  (if-let [[n e] traced]
               (str "canvas namespace " n " does not load: " e)
               fukan-error)}))

(defn ^{:malli/schema [:=> [:cat :ProjectName :string [:? [:maybe [:vector :string]]]] :DeclaredElements]}
  elements
  "The declared design's elements as data, as fukan lists them for `worktree`:

     {:status :unmodelled}                                no design declared — nothing to list
     {:status :listed      :elements [...]}               one row per authored element
     {:status :undecidable :cause c :error \"…\"}         fukan did not answer

   A row's `:id` is the element's canvas identity (`canvas.review.record/review-record`) and its
   `:sort` the qualified sort it is declared under; `:ns` and `:file` are there only where its
   module pairs with code. Anything else fukan puts on a row is carried as it came.

   An empty listing is an answer only when fukan printed one. A caller resolving names against a
   listing reads every name missing from it as undeclared, so a run that timed out, failed, or
   printed something that is not a listing is `:undecidable`, never `[]`. The code is read to
   pair elements with it, which is why this has the check's budget and not the renderer's.

   `:cause` says, as data, why there is no listing, and a caller branches on it rather than on the
   error's text: `:timeout` when fukan did not finish, `:load-failure` when a canvas namespace does
   not load, `:unanswered` for any other run that printed no listing. A load failure's `:error` is
   that namespace's own load error, read by requiring it directly — first each of `changed` (paths
   relative to `worktree`, the canvas files a caller knows were edited), then the one fukan named —
   because fukan's error names the first namespace whose load failed, often a bystander that
   merely requires the one that broke."
  ([project-name worktree] (elements project-name worktree nil))
  ([project-name worktree changed]
   (if-let [{:keys [src spec-dirs] :as design} (design-of project-name worktree)]
     (let [done (run-fukan design worktree "elements"
                           ["--src" src "--spec-dirs" (str/join "," spec-dirs)] check-timeout-ms)]
       (if (= ::timeout done)
         {:status :undecidable
          :cause  :timeout
          :limit-ms check-timeout-ms
          :error  (str "the element listing did not finish within "
                       (quot check-timeout-ms 1000) "s")}
         (let [{:keys [exit out err]} done
               listing (parse-report out)]
           (cond
             (not (zero? (long exit)))
             (let [e (or (some-> (:error listing) str str/trim not-empty)
                         (not-empty (str/trim (str err)))
                         (str "fukan exited " exit))]
               (if-let [[_ named] (re-find unloaded-ns e)]
                 (load-failure design worktree named changed e)
                 {:status :undecidable :cause :unanswered :error e}))

             (vector? (:elements listing))
             {:status :listed :elements (:elements listing)}

             :else
             {:status :undecidable :cause :unanswered
              :error (str "unreadable listing: " (str/trim (str out)))}))))
     {:status :unmodelled})))

(defn ^{:malli/schema [:=> [:cat :any] :string]}
  claim-text
  "`s` with every run of whitespace one space — the form a claim's statement is compared in. A
   docstring wraps where a record's statement does not, and where a line breaks says nothing about
   what the claim is."
  [s]
  (str/trim (str/replace (str s) #"\s+" " ")))

(defn ^{:malli/schema [:=> [:cat :DeclaredElements] [:map-of :string :map]]}
  declared-claims
  "The Claim instances `listing` holds, by id — a Claim's instance name — each as its statement
   (`claim-text` of its docstring) and the set of subjects its `:about` names. Empty for a listing
   that is not `:listed`.

   Keying by name drops nothing only because the declaration's own law holds a Claim's name
   unique; a declaration breaking it is refused by the check."
  [listing]
  (into {}
        (keep (fn [{nm :name doc :doc refs :refs s :sort}]
                (when (= "Claim" (name s))
                  [nm {:statement (claim-text doc) :about (set (:about refs))}])))
        (when (= :listed (:status listing)) (:elements listing))))

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

   A DIRECTORY, not a revision: this seam reads a declaration and never learns how it was
   fetched. The caller holds the revision, materializes it with
   `nido.platform.jj/materialize-spec-dirs!` and hands the result down.

   Both ends are rendered by fukan and diffed as TEXT, which is honest about what it is: a
   reordering would read as a change. Two renderings of the same specs are byte-identical across
   separate runs, so that is the only false positive available to it — and the alternative, a
   structural model diff, belongs in fukan rather than here.

   Adoption and DELETION are both changes, and neither end declaring one is what `:unmodelled`
   means. A branch that adds a canvas changed the declaration from nothing to something; one
   that removes the last of it changed it the other way, which is the change a reviewer would
   least like to have reported as \"this project declares no design\"."
  ([project-name worktree base-dir] (diff project-name worktree base-dir nil))
  ([project-name worktree base-dir scope]
   (let [head (describe project-name worktree scope)]
     (if (= :undecidable (:status head))
       {:status :undecidable
        :error  (str "this branch's design did not render: " (:error head))}
       (let [base     (describe project-name base-dir scope)
             base-doc (case (:status base)
                        :described  (:document base)
                        :unmodelled ""
                        ::failed)
             ;; An unmodelled HEAD is not by itself an unmodelled diff. A branch that DELETES
             ;; the last declaration changed the declared design from something to nothing —
             ;; the mirror of adopting fukan, and the change a reviewer would least like to
             ;; miss. Only when BOTH ends declare nothing is there no design here to have
             ;; changed.
             head-doc (if (= :unmodelled (:status head)) "" (:document head))]
         (cond
           (= ::failed base-doc)
           {:status :undecidable
            :error  (str "the base's design did not render: " (:error base))}

           (= "" base-doc head-doc)
           {:status :unmodelled}

           (= base-doc head-doc)
           {:status :unchanged :digest (digest-of head-doc)}

           :else
           (if-let [d (unified base-doc head-doc)]
             {:status :changed :diff d :digest (digest-of head-doc)}
             {:status :undecidable :error "the two renderings could not be diffed"})))))))
