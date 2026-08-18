#!/usr/bin/env bb
;; hygiene-scan.bb — brian's diff-scoped hygiene lints, reproduced locally.
;;
;; /local-ci's pre-flight: the `Checks` CI job carries `Comment Lint` and
;; `Ticket Refs`, and a 5-minute Docker cycle is an expensive way to hear that a
;; comment narrates its own migration. This answers in ~2s.
;;
;; Usage:
;;   bb hygiene-scan.bb [--from REV] [--to REV] [--edn]
;;
;; Run from a jj worktree. Defaults to trunk()..@ — the whole branch, which is
;; the same span the PR shows and the same span the project's diff-scoped lints
;; gate on.
;;
;; WHY THIS EXISTS. brian's own `bb lint:comments` / `bb lint:refs` shell out to
;; `git diff`. A nido session worktree is a non-colocated jj workspace nested
;; inside the colocated source repo, so git there walks up and binds to the
;; PARENT repo: the lints answer about the wrong tree, or (refs) fail to resolve
;; their engine at all, because `git rev-parse --show-toplevel` points at the
;; source checkout. This script computes the diff with jj — correct in a
;; workspace — and drives the project's OWN rule functions over it. The rules
;; stay the project's; only the diff comes from here.
;;
;; Classes, blocking first:
;;   :archaeology   comment narrates the migration that produced the code
;;   :deleted-ref   comment names a symbol this same diff deletes
;;   :ticket-ref    non-canonical ticket-ref shape on an added line
;;   :narration     the SOFT archaeology vocabulary (advisory)
;;   :artifact      debug output, commented-out code, stray files (advisory)
;;
;; The first three are the project's own rules, borrowed — except that
;; `:archaeology` falls back to a small marker list here when the project ships
;; no comment-lint at all. `:narration` is the class
;; brian's comment-lint deliberately refuses to blocker-gate ("handled by review
;; lanes, NOT this commit blocker") — this IS that review lane, so it reports
;; and never gates. `:artifact` is likewise advisory: fixing it means editing
;; code, which puts it outside the comment-only fix the gate can bless.
;;
;; Exit codes: 0 no blocking violations, 1 blocking violations found,
;; 2 mechanics failed (no jj, unreadable revision).

(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.string :as str])

;; ── jj plumbing ─────────────────────────────────────────────────────────────

(defn- jj [& args]
  (let [{:keys [exit out err]} (apply p/shell {:out :string :err :string :continue true} "jj" args)]
    (when-not (zero? exit)
      (binding [*out* *err*]
        (println (str "jj " (str/join " " args) " failed:"))
        (println (str/trim (or err ""))))
      (System/exit 2))
    out))

(def ^:private source-fileset
  "Files worth scanning. A union of the pathspecs brian's two lints use; a
   project without those lints still gets the advisory classes over the same
   set. Markdown is deliberately out — docs quote the banned tokens as
   examples."
  (str/join " | " (map #(str "glob:'**/*." % "'")
                       ["clj" "cljc" "cljs" "bb" "edn" "ts" "tsx" "js" "jsx"
                        "sql" "css" "scss" "allium" "sh" "yaml" "yml"])))

(defn- source-diff [from to]
  (jj "diff" "--git" "--context" "0" "--from" from "--to" to source-fileset))

(defn- summary [from to]
  (->> (str/split-lines (jj "diff" "--summary" "--from" from "--to" to))
       (remove str/blank?)
       (mapv (fn [l] {:op (subs l 0 1) :path (subs l 2)}))))

;; ── Added lines, self-parsed (the advisory classes need them too) ───────────

(defn- added-lines
  "[{:file :line :text}] for every added line in a unified diff."
  [diff-text]
  (second
   (reduce (fn [[{:keys [file n]} acc] line]
             (cond
               (str/starts-with? line "diff --git ")
               [{:file (some-> (re-find #" b/(.*)$" line) second) :n 0} acc]

               (str/starts-with? line "@@")
               [{:file file :n (or (some-> (re-find #"^@@ -\d+(?:,\d+)? \+(\d+)" line) second parse-long) 0)} acc]

               (and file (str/starts-with? line "+") (not (str/starts-with? line "+++")))
               [{:file file :n (inc n)}
                (conj acc {:file file :line n :text (subs line 1)})]

               :else [{:file file :n n} acc]))
           [{:file nil :n 0} []]
           (str/split-lines diff-text))))

;; ── Borrowed engine 1: the project's comment-lint rules ────────────────────

(defn- comment-lint-fns
  "The project's own {:archaeology f :deleted-ref f} over diff text, or nil when
   this project has no comment-lint. Requires from the cwd bb.edn classpath, so
   it only resolves when run from the project's own tree."
  []
  (try
    (require 'tasks.comment-lint)
    {:archaeology (requiring-resolve 'tasks.comment-lint/find-violations)
     :deleted-ref (requiring-resolve 'tasks.comment-lint/find-deleted-ref-violations)}
    (catch Throwable _ nil)))

;; ── Borrowed engine 2: the ticket-ref engine ───────────────────────────────

(defn- refs-engine-script
  "The lab:refs engine: installed plugin first, then the repo's vendored pin.
   Unlike the project's own wrapper this resolves the pin from the WORKTREE
   (cwd), not from `git rev-parse --show-toplevel` — which in a jj workspace
   answers with the source repo, where the pin does not live."
  []
  (let [cache (fs/file (System/getProperty "user.home") ".claude" "plugins" "cache" "parenstech" "lab")
        newest (when (fs/exists? cache)
                 (some->> (fs/list-dir cache)
                          (filter fs/directory?)
                          (sort-by #(mapv (fn [s] (or (parse-long s) 0))
                                          (str/split (fs/file-name %) #"\.")))
                          last
                          (#(fs/file % "skills" "refs" "lib" "ticket-refs.bb"))))
        vendored (fs/file "bin" "vendor" "refs-engine" "ticket-refs.bb")]
    (cond
      (and newest (fs/exists? newest)) (str newest)
      (fs/exists? vendored) (str vendored)
      :else nil)))

(defn- ticket-ref-violations
  "Non-canonical refs on added lines, or ::unavailable when no engine resolves."
  [diff-text]
  (if-let [script (refs-engine-script)]
    (try
      (load-file script)
      (let [ns' 'ticket-refs
            v (fn [sym] @(ns-resolve ns' sym))
            env ((v 'build-env) ((v 'load-registry))
                                ((v 'load-config) ".")
                                ((v 'origin-repo) "."))]
        (vec ((v 'lint-added-lines) (:added ((v 'parse-diff) diff-text)) env)))
      (catch Throwable e
        (binding [*out* *err*] (println "refs engine failed to load:" (ex-message e)))
        ::unavailable))
    ::unavailable))

;; ── Native class 1: soft archaeology narration (advisory) ──────────────────

(def ^:private comment-prefixes
  {"clj" ";" "cljc" ";" "cljs" ";" "bb" ";" "edn" ";"
   "allium" "--" "sql" "--"
   "ts" "//" "tsx" "//" "js" "//" "jsx" "//" "scss" "//"
   "css" "*" "sh" "#" "yaml" "#" "yml" "#"})

(defn- ext [path]
  (let [i (str/last-index-of path ".")] (when i (str/lower-case (subs path (inc i))))))

(defn- comment-text
  "The prose of a comment line, or nil when the line is code."
  [file text]
  (when-let [prefix (comment-prefixes (ext file))]
    (let [t (str/trim text)]
      (when (str/starts-with? t prefix)
        (str/replace t (re-pattern (str "^[" (java.util.regex.Pattern/quote prefix) "*]+\\s*")) "")))))

(def ^:private fallback-archaeology-markers
  "Hard archaeology tokens, scanned ONLY when the project ships no comment-lint
   of its own (babel, fukan, nido itself). A project that owns the rule always
   wins — this list never runs alongside one, so the two cannot disagree. Kept
   to the unambiguous transition and work-item tags; the softer vocabulary is
   `narration-markers` below and stays advisory everywhere."
  [#"(?i)\bWP-\d+"
   #"(?i)\bRED[- ]phase\b"
   #"(?i)\bGREEN[- ]phase\b"
   #"(?i)\bthe planned fix\b"
   #"(?i)\bdeprecated\b"
   #"(?i)\bbackwards? compat(?:ibility)?\b"])

(def ^:private ratchet-re
  "A dated ratchet is transition-relative BY DESIGN — it names a removal date,
   not past work — so a marker on such a line is allowlisted."
  #"(?i)\b(?:DELETE-AT|REMOVE-AFTER)\b")

(defn- fallback-archaeology [added]
  (vec (for [{:keys [file line text]} added
             :let [c (comment-text file text)]
             :when (and c (not (re-find ratchet-re text)))
             :let [hit (some #(re-find % c) fallback-archaeology-markers)]
             :when hit]
         {:file file :line line :marker hit :text (str/trim text)})))

(def ^:private narration-markers
  "The 'narration to avoid' vocabulary from brian's code-conventions
   § Comments Describe Current Code. Advisory BY DESIGN: English collides with
   several of these ('the list is no longer than five'), and the fix is a
   judgement about what the comment should say instead — which is exactly the
   work /clean-pr does by hand, not a token swap."
  [#"(?i)\bno longer\b"
   #"(?i)\bpreviously\b"
   #"(?i)\bformerly\b"
   #"(?i)\bused to\b"
   #"(?i)\bretired\b"
   #"(?i)\bthe old \w"
   #"(?i)\breplace[sd]? (?:the|by)\b"
   #"(?i)\boff the graph\b"
   #"(?i)\bwe (?:now|used to)\b"
   #"(?i)\bthis (?:used to|replaces)\b"])

(defn- narration-violations [added]
  (vec (for [{:keys [file line text]} added
             :let [c (comment-text file text)]
             :when c
             :let [hit (some #(re-find % c) narration-markers)]
             :when hit]
         {:file file :line line :marker hit :text (str/trim text)})))

;; ── Native class 2: stray artifacts (advisory) ─────────────────────────────

(def ^:private debug-output
  #"(?<![\w-])(?:println|prn|pprint|clojure\.pprint/pprint|tap>|console\.log|debugger)(?![\w-])")

(def ^:private stray-file #"(?i)(\.orig|\.rej|\.bak|~|\.DS_Store|\.swp)$")

(defn- artifact-violations [added files]
  (vec
   (concat
    (for [{:keys [op path]} files
          :when (and (not= op "D") (re-find stray-file path))]
      {:kind :stray-file :file path :detail "backup/merge artifact — never commit these"})

    (for [{:keys [file line text]} added
          :when (and (nil? (comment-text file text))
                     (re-find debug-output text)
                     (not (str/includes? file "/test/"))
                     (not (str/includes? file "/dev/"))
                     (not (str/starts-with? file "bin/")))]
      {:kind :debug-output :file file :line line :detail (str/trim text)})

    (for [{:keys [file line text]} added
          :let [c (comment-text file text)]
          :when (and c (contains? #{"clj" "cljc" "cljs" "bb"} (ext file))
                     (re-find #"^\(\s*(?:defn?|let|if|when|do|require|->|->>)\b" c))]
      {:kind :commented-out-code :file file :line line :detail c}))))

;; ── Report ─────────────────────────────────────────────────────────────────

(defn- clip
  "One readable line. A spec's prose paragraph can run thousands of characters
   on a single source line; the location is what the reader needs to act."
  [s]
  (let [t (str/trim (str s))]
    (if (> (count t) 140) (str (subs t 0 137) "…") t)))

(defn- section! [title rows fmt]
  (when (seq rows)
    (println (str "── " title " (" (count rows) ") ──"))
    (doseq [r rows] (println (str "  " (fmt r))))
    (println)))

(let [args (vec *command-line-args*)
      opt (fn [flag default]
            (if-let [i (->> args (keep-indexed #(when (= %2 flag) %1)) first)]
              (get args (inc i) default) default))
      from (opt "--from" "trunk()")
      to (opt "--to" "@")
      edn? (boolean (some #{"--edn"} args))
      diff (source-diff from to)
      files (summary from to)
      added (added-lines diff)
      cl (comment-lint-fns)
      archaeology (if cl (vec ((:archaeology cl) diff)) (fallback-archaeology added))
      archaeology-source (if cl :project :nido-fallback)
      deleted-ref (if cl (vec ((:deleted-ref cl) diff)) ::unavailable)
      ticket-ref (ticket-ref-violations diff)
      narration (narration-violations added)
      artifacts (artifact-violations added files)
      blocking (remove #(= ::unavailable %) [archaeology deleted-ref ticket-ref])
      blocking-n (reduce + 0 (map count blocking))
      loc (fn [{:keys [file line]}] (str file (when line (str ":" line))))]
  (if edn?
    (prn {:gate :hygiene-scan :from from :to to
          :added-lines (count added) :files (count files)
          :blocking blocking-n :archaeology-source archaeology-source
          :archaeology archaeology :deleted-ref deleted-ref :ticket-ref ticket-ref
          :narration narration :artifact artifacts})
    (do
      (println (str "hygiene-scan: " from " → " to
                    "  (" (count files) " files, " (count added) " added lines)"))
      (println)
      (when (= :nido-fallback archaeology-source)
        (println "  note: this project ships no comment-lint of its own. Archaeology is")
        (println "        scanned with nido's fallback marker list; the deleted-symbol")
        (println "        class needs the project's engine and is NOT scanned.")
        (println))
      (when (= ::unavailable ticket-ref)
        (println "  note: no ticket-ref engine resolved — ref canonicalization not scanned.")
        (println))
      (section! (str "BLOCKING · comment archaeology"
                     (when (= :nido-fallback archaeology-source) " (nido fallback rules)"))
                (when (coll? archaeology) archaeology)
                #(str (loc %) "\n      " (clip (:text %))))
      (section! "BLOCKING · comment names a symbol this diff deletes" (when (coll? deleted-ref) deleted-ref)
                #(str (loc %) "  [" (:name %) "]\n      " (clip (:text %))))
      (section! "BLOCKING · non-canonical ticket refs" (when (coll? ticket-ref) ticket-ref)
                #(str (loc %) "  " (:token %) " -> " (:fix %) "\n      " (clip (:text %))))
      (section! "advisory · migration narration in comments" narration
                #(str (loc %) "\n      " (clip (:text %))))
      (section! "advisory · stray artifacts (needs a CODE edit — report, do not fix)" artifacts
                #(str (name (:kind %)) "  " (loc %) "\n      " (clip (:detail %))))
      (if (zero? blocking-n)
        (println (str "No blocking violations."
                      (when (or (seq narration) (seq artifacts))
                        (str " " (+ (count narration) (count artifacts)) " advisory item(s) above."))))
        (println (str blocking-n " blocking violation(s) — all fixable inside comments.")))))
  (System/exit (if (zero? blocking-n) 0 1)))
