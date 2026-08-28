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
   discoverable and requirable. A project that consumes fukan differently overrides it."
  {:src       "src"
   :spec-dirs ["canvas"]
   :cmd       ["clojure" "-M:fukan" "-m" "fukan.check"]})

(def ^:private check-timeout-ms
  "How long to wait for the checker. It is a cold JVM plus a model build — seconds, not
   milliseconds — and the ceiling exists so a wedged one cannot hang a session coming up."
  180000)

(def ^:private declaration-char-cap
  "How much declared design to embed in a briefing before truncating. A model is prose plus
   declarations and is meant to be read; a model that crowded out everything else in a briefing
   would be read by nobody."
  16000)

(defn design-of
  "The design configuration for `project-name` in `worktree`, or nil when the project declares
   no design.

   Detection is by CONVENTION, not registration: a project is modelled iff its spec-dirs hold
   `.clj` files. Nothing to add to the registry, nothing to keep in sync with the tree — the
   canvas is checked into the repo, so its presence in a worktree is already the truth about
   whether that branch has a design. A project whose layout differs (a `src/main` root, a
   canvas somewhere else) overrides via `:design` in the project registry."
  [project-name worktree]
  (let [cfg   (merge default-design (:design (project/get-project project-name)))
        dirs  (for [d (:spec-dirs cfg)] (fs/path worktree d))
        files (mapcat #(when (fs/directory? %) (fs/glob % "**.clj")) dirs)]
    (when (seq files)
      (assoc cfg :files (mapv str (sort-by str files))))))

(defn declaration-text
  "The project's declared design, as the source that declares it.

   Not a generated summary. A model is authored as documented declarations — bands with
   docstrings, laws with the reason they exist — and rendering that down to a table would drop
   the half a reader most needs while adding a way for the rendering to drift from the model.
   Truncated at `declaration-char-cap` with a line saying so, never silently."
  [{:keys [files]}]
  (let [full (str/join "\n" (for [f files] (slurp f)))]
    (if (<= (count full) declaration-char-cap)
      full
      (str (subs full 0 declaration-char-cap)
           "\n\n;; … truncated. The whole declaration is in " (str/join ", " files) "\n"))))

(defn- parse-report
  "Read the checker's stdout. Unparseable output is UNDECIDABLE rather than clean: stdout is
   the report, and if it is not a report then nothing was decided."
  [out]
  (try (let [r (edn/read-string out)] (when (map? r) r))
       (catch Exception _ nil)))

(defn check
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
     (let [{:keys [cmd src spec-dirs]} design
           argv (concat cmd ["--src" src "--spec-dirs" (str/join "," spec-dirs)])
           proc (process/process argv {:dir (str worktree) :out :string :err :string})
           done (deref proc check-timeout-ms ::timeout)]
       (if (= ::timeout done)
         (do (process/destroy-tree proc)
             {:status :undecidable
              :error  (str "the design check did not finish within "
                           (quot check-timeout-ms 1000) "s")})
         (let [{:keys [exit out err]} done
               report (parse-report out)]
           (case (long exit)
             0 {:status :satisfied}
             1 (if report
                 {:status :violated :violations (:violations report)}
                 {:status :undecidable :error (str "unreadable report: " (str/trim (str out)))})
             {:status :undecidable
              :error  (or (:error report) (str/trim (str err)) (str "exit " exit))})))))))

(defn offender-line
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

(defn violation-text
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
