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
            [nido.platform.project :as project]))

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

(def ^:private declaration-char-cap
  "How much declared design to embed in a briefing before truncating. A model is prose plus
   declarations and is meant to be read; a model that crowded out everything else in a briefing
   would be read by nobody. Fukan's own self-model renders to ~40k, nido's to ~3k."
  16000)

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

(defn ^{:malli/schema [:=> [:cat :ProjectName :string [:? [:maybe :any]]] [:maybe :string]]}
  describe
  "The project's declared design as a document, or nil when it declares none — or when fukan
   could not render one.

   Asked of FUKAN rather than read off disk. Fukan is what knows what a design is: which
   vocabularies the project actually instantiated, which nodes are its own rather than the
   meta-grammar's, and how an instance was authored. Reading `canvas/**.clj` instead worked
   only for the case where a design happens to be one hand-written file — it pasted requires
   and helper fns along with the declarations, said nothing about a model that is partly
   extracted, and offered no way to select. `bands.clj` is a structure expressed through fukan,
   not a privileged shape this seam should know the outline of.

   Truncated at `declaration-char-cap` with a line saying so, never silently."
  ([project-name worktree] (describe project-name worktree nil))
  ([project-name worktree scope]
  (when-let [design (design-of project-name worktree)]
    (let [{:keys [spec-dirs]} design
          ;; an explicit scope WINS over the project's configured default: the caller that has
          ;; one got it from a baseline that read the code, and the default was set by someone
          ;; who had not
          select (or scope (:select design))
          done (run-fukan design worktree "describe"
                          (cond-> ["--spec-dirs" (str/join "," spec-dirs)]
                            select (conj "--select" (pr-str select)))
                          describe-timeout-ms)]
      (when (and (map? done) (zero? (long (:exit done))) (seq (str/trim (str (:out done)))))
        (let [full (str/trim (:out done))]
          (if (<= (count full) declaration-char-cap)
            full
            (str (subs full 0 declaration-char-cap)
                 "\n\n;; … truncated at " declaration-char-cap " of " (count full)
                 " characters. The whole declaration is in "
                 (str/join ", " (:files design))
                 ".\n;; A design this size should be SCOPED rather than cut: the workstream's"
                 "\n;; baseline records a `:scope`, and a project may set `:select` on its registry"
                 "\n;; entry — either carries a whole answer to a narrower question instead of"
                 "\n;; most of a wide one.\n"))))))))

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

   `:undecidable` is reported, never swallowed into `:satisfied`. A caller that cannot tell
   \"the design holds\" from \"nobody could tell\" will eventually wave through the branch that
   broke the checker."
  ([project-name worktree] (check project-name worktree (design-of project-name worktree)))
  ([_project-name worktree design]
   (if-not design
     {:status :unmodelled}
     (let [{:keys [src spec-dirs]} design
           done (run-fukan design worktree "check"
                           ["--src" src "--spec-dirs" (str/join "," spec-dirs)] check-timeout-ms)]
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
              :error  (or (:error report) (str/trim (str err)) (str "exit " exit))})))))))

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
