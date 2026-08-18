#!/usr/bin/env bb
;; no-code-change.bb — proof that a fix stayed inside the comments.
;;
;; /local-ci's gate for the hygiene class: a comment-lint or ticket-ref fix must
;; not move behaviour, and "I only touched comments" is a claim worth checking
;; rather than believing.
;;
;; Usage:
;;   bb no-code-change.bb [--from REV] [--to REV] [--allow-prose] [--edn]
;;
;; Run from a jj worktree. Compares two revisions and classifies every changed
;; file into one of three outcomes:
;;
;;   PROVEN INERT — nothing a reader can see moved. A Clojure-family file whose
;;   sexpr-able top-level forms are identical (whitespace, `;` comments and `#_`
;;   uneval blocks are not sexpr-able, so an edit confined to them is form-
;;   identical by construction); any other file whose every changed line is a
;;   comment or blank. Silent: this is the outcome /clean-pr aims for.
;;
;;   NEEDS EYES — the program text changed, but only in a position that carries
;;   prose: a docstring, a string literal, or (in a non-Clojure file) a line
;;   whose change sits entirely inside quotes. A `testing` label and a spec's
;;   prose are prose; so is `(def timeout "30s")`, which is why this class is
;;   NEVER silent — every changed string prints before → after, and the gate
;;   still exits non-zero unless the caller passes --allow-prose to say a human
;;   read them.
;;
;;   CODE CHANGED — anything else, including a file added or removed and a file
;;   type with no comment grammar. Always exits non-zero. This is a bug in the
;;   fix, not a finding: revert the file and redo the edit inside the comment.
;;
;; Exit codes: 0 clean, 1 something needs a human, 2 mechanics failed (no jj,
;; unreadable revision).

(require '[babashka.process :as p]
         '[clojure.string :as str]
         '[clojure.walk :as walk]
         '[rewrite-clj.parser :as rp]
         '[rewrite-clj.node :as rn])

;; ── jj plumbing ─────────────────────────────────────────────────────────────
;; jj, never git. A nido session worktree is a non-colocated jj workspace nested
;; inside the colocated source repo, so a bare `git` there walks up and binds to
;; the PARENT repo — it answers about the wrong tree without erroring. babashka
;; execs directly, so the ~/.zshrc guard that catches this interactively never
;; fires here; the only defence is not reaching for git at all.

(defn- jj [& args]
  (let [{:keys [exit out err]} (apply p/shell {:out :string :err :string :continue true} "jj" args)]
    (when-not (zero? exit)
      (binding [*out* *err*]
        (println (str "jj " (str/join " " args) " failed:"))
        (println (str/trim (or err ""))))
      (System/exit 2))
    out))

(defn- changed-files [from to]
  (->> (str/split-lines (jj "diff" "--summary" "--from" from "--to" to))
       (remove str/blank?)
       (mapv (fn [line]
               {:op (case (subs line 0 1) "M" :modified "A" :added "D" :removed :other)
                :path (subs line 2)}))))

(defn- file-at [rev path]
  (let [{:keys [exit out]} (p/shell {:out :string :err :string :continue true}
                                    "jj" "file" "show" "-r" rev path)]
    (when (zero? exit) out)))

;; ── Clojure family: compare at the reader ──────────────────────────────────

(def ^:private clj-exts #{"clj" "cljc" "cljs" "bb" "edn"})

(def ^:private def-heads
  "Def-family head names, matched on the NAME half of the symbol so the schema
   and malli variants (`mx/defn`, `s/defn`) fall under the same rule."
  '#{def defn defn- defmacro defmacro- defprotocol definterface defmulti})

(defn- doc-index
  "Index of the docstring in a def-family form, or nil when it has none. Walks
   from the name onward, stepping over a `:- <schema>` annotation, and takes the
   first string that still has a body after it — which is what makes
   `(def x \"val\")` a VALUE and `(def x \"doc\" val)` a docstring."
  [x]
  (let [head (first x)
        nm (when (symbol? head) (name head))]
    (cond
      (= "ns" nm)
      (when (and (> (count x) 2) (string? (nth x 2))) 2)

      (and nm (contains? def-heads (symbol nm)))
      (loop [i 2]
        (cond
          (>= i (dec (count x))) nil
          (= :- (nth x i)) (recur (+ i 2))
          (string? (nth x i)) i
          :else nil))

      :else nil)))

(def ^:private gensym-suffix
  "The counter half of a reader- or syntax-quote-minted symbol. `#(…)` and
   `foo#` mint a FRESH counter on every read, so the same bytes parsed twice
   yield different symbols — normalize the counter away or every file holding an
   anonymous-fn literal reads as changed."
  #"__\d+(?:#|__auto__)?$")

(defn- erase-gensyms [form]
  (walk/postwalk
   (fn [x] (if (and (symbol? x) (re-find gensym-suffix (name x)))
             (symbol (namespace x) (str/replace (name x) gensym-suffix "__GENSYM"))
             x))
   form))

(defn- blank-docstrings [form]
  (walk/postwalk
   (fn [x]
     (cond
       (and (seq? x) (doc-index x))
       (let [i (doc-index x)] (concat (take i x) [::docstring] (drop (inc i) x)))
       (and (map? x) (string? (:doc x))) (assoc x :doc ::docstring)
       :else x))
   form))

(defn- blank-strings [form]
  (walk/postwalk (fn [x] (if (string? x) ::string x)) form))

(defn- strings-in
  "Every string in the form, in a deterministic order. Two structurally equal
   forms produce equal-length sequences, so zipping them pairs each changed
   string with what it was."
  [form]
  (let [acc (volatile! [])]
    (walk/postwalk (fn [x] (when (string? x) (vswap! acc conj x)) x) form)
    @acc))

(defn- top-level-forms [content]
  (try
    (->> (rn/children (rp/parse-string-all content))
         (filter rn/sexpr-able?)
         (mapv rn/sexpr)
         erase-gensyms)
    (catch Exception _ ::unparsable)))

(defn- clip [s]
  (let [t (str/trim (str s))]
    (if (> (count t) 110) (str (subs t 0 107) "…") t)))

(defn- delta
  "The two strings reduced to the region that actually differs, with a little
   context on each side. A docstring runs for paragraphs and a spec's prose for
   pages; clipping from the front would show the reader the identical opening of
   both and hide the one word that moved."
  [before after]
  (let [flat #(str/replace (str/trim (str %)) #"\s+" " ")
        b (flat before) a (flat after)
        lim (min (count b) (count a))
        pre (count (take-while true? (map = b a)))
        suf (min (- lim pre)
                 (count (take-while true? (map = (reverse b) (reverse a)))))
        ctx 34
        window (fn [s] (str (when (pos? (max 0 (- pre ctx))) "…")
                            (subs s (max 0 (- pre ctx)) pre)
                            "《" (subs s pre (- (count s) suf)) "》"
                            (subs s (- (count s) suf) (min (count s) (+ (- (count s) suf) ctx)))
                            (when (> (- (count s) suf ctx) 0) "…")))]
    (if (and (zero? pre) (zero? suf))
      {:before (clip b) :after (clip a)}
      {:before (window b) :after (window a)})))

(defn- changed-strings [b a]
  (vec (for [[x y] (map vector (strings-in b) (strings-in a))
             :when (not= x y)]
         (delta x y))))

(defn- check-forms [before after]
  (let [b (top-level-forms before)
        a (top-level-forms after)]
    (cond
      (or (= ::unparsable b) (= ::unparsable a))
      {:class :unparsable :ok? false
       :detail "the reader could not parse one side — verify by hand"}

      (= b a)
      {:class :forms-identical :ok? true}

      (= (mapv blank-docstrings b) (mapv blank-docstrings a))
      {:class :docstring-only :prose? true :strings (changed-strings b a)}

      (= (mapv blank-strings b) (mapv blank-strings a))
      {:class :string-only :prose? true :strings (changed-strings b a)}

      :else
      {:class :code-changed :ok? false
       :detail "top-level forms differ outside strings"})))

;; ── Everything else: compare line by line, hunk by hunk ────────────────────

(def ^:private comment-prefixes
  {"allium" ["--"] "sql" ["--"]
   "ts" ["//" "/*" "*" "*/"] "tsx" ["//" "/*" "*" "*/"]
   "js" ["//" "/*" "*" "*/"] "jsx" ["//" "/*" "*" "*/"]
   "java" ["//" "/*" "*" "*/"] "css" ["/*" "*" "*/"] "scss" ["//" "/*" "*" "*/"]
   "sh" ["#"] "bash" ["#"] "zsh" ["#"] "yaml" ["#"] "yml" ["#"]
   "toml" ["#"] "py" ["#"] "rb" ["#"]
   "clj" [";"] "cljc" [";"] "cljs" [";"] "bb" [";"] "edn" [";"]})

(def ^:private prose-exts #{"md" "markdown" "txt" "adoc" "rst"})

(defn- ext [path]
  (let [i (str/last-index-of path ".")] (when i (str/lower-case (subs path (inc i))))))

(defn- comment-line? [prefixes line]
  (let [t (str/trim line)]
    (or (str/blank? t) (some #(str/starts-with? t %) prefixes))))

(defn- mask-quoted
  "The line with every double-quoted span replaced by a placeholder. Two lines
   with equal masks differ only INSIDE quotes."
  [line]
  (str/replace line #"\"(?:[^\"\\]|\\.)*\"" "\"…\""))

(defn- hunks
  "{path -> [{:removed [line…] :added [line…]} …]} from a `jj diff --git`."
  [diff-text]
  (let [step (fn [{:keys [path acc cur]} line]
               (let [flush (fn [acc cur] (if cur (update acc path (fnil conj []) cur) acc))]
                 (cond
                   (str/starts-with? line "diff --git ")
                   {:path (some-> (re-find #" b/(.*)$" line) second) :acc (flush acc cur) :cur nil}

                   (str/starts-with? line "@@")
                   {:path path :acc (flush acc cur) :cur {:removed [] :added []}}

                   (or (str/starts-with? line "+++") (str/starts-with? line "---")
                       (str/starts-with? line "index ") (nil? cur))
                   {:path path :acc acc :cur cur}

                   (str/starts-with? line "+")
                   {:path path :acc acc :cur (update cur :added conj (subs line 1))}

                   (str/starts-with? line "-")
                   {:path path :acc acc :cur (update cur :removed conj (subs line 1))}

                   :else {:path path :acc acc :cur cur})))
        {:keys [path acc cur]} (reduce step {:path nil :acc {} :cur nil} (str/split-lines diff-text))]
    (if cur (update acc path (fnil conj []) cur) acc)))

(defn- check-lines
  "A file with no reader: every hunk must be comments, or a quoted-span edit."
  [prefixes file-hunks]
  (let [verdicts
        (for [{:keys [removed added]} file-hunks]
          (cond
            (every? (partial comment-line? prefixes) (concat removed added))
            {:class :comment-lines-only}

            (and (= (count removed) (count added))
                 (every? true? (map #(= (mask-quoted %1) (mask-quoted %2)) removed added)))
            {:class :string-only
             :strings (vec (for [[b a] (map vector removed added) :when (not= b a)]
                             (delta b a)))}

            :else
            {:class :code-changed
             :detail (vec (take 4 (map (comp clip str/trim)
                                       (remove (partial comment-line? prefixes)
                                               (concat removed added)))))}))]
    (cond
      (some #(= :code-changed (:class %)) verdicts)
      {:class :code-changed :ok? false
       :detail (vec (mapcat :detail (filter #(= :code-changed (:class %)) verdicts)))}

      (some #(= :string-only (:class %)) verdicts)
      {:class :string-only :prose? true :strings (vec (mapcat :strings verdicts))}

      :else {:class :comment-lines-only :ok? true})))

;; ── Verdict ────────────────────────────────────────────────────────────────

(defn- check-file [from to file-hunks {:keys [op path]}]
  (let [e (ext path)
        result
        (cond
          (not= op :modified)
          {:class :file-set-changed :ok? false
           :detail (str "file " (name op) " — a hygiene pass neither adds nor removes files")}

          (contains? prose-exts e) {:class :prose :ok? true}
          (contains? clj-exts e) (check-forms (file-at from path) (file-at to path))
          (contains? comment-prefixes e) (check-lines (comment-prefixes e) (get file-hunks path))

          :else
          {:class :unknown-file-type :ok? false
           :detail (str "no comment grammar for ." (or e "(no extension)") " — verify by hand")})]
    (assoc result :path path)))

(defn- report! [results from to allow-prose?]
  (let [broken (filter #(false? (:ok? %)) results)
        prose (filter :prose? results)]
    (println (str "no-code-change: " from " → " to))
    (println)
    (doseq [{:keys [path class ok? prose? detail]} results]
      (println (format "  %-11s %-20s %s"
                       (cond ok? "proven" prose? "needs-eyes" :else "CODE")
                       (name class) path))
      (when (and detail (not ok?))
        (doseq [d (if (coll? detail) detail [detail])] (println (str "                  " d)))))
    (println)
    (when (seq prose)
      (println "Prose changed — every one of these is a real string in the program.")
      (println "Read them; nothing below was proven inert:")
      (doseq [{:keys [path strings]} prose
              {:keys [before after]} strings]
        (println (str "  " path))
        (println (str "    - " before))
        (println (str "    + " after)))
      (println))
    (cond
      (seq broken)
      (println (str "FAIL — " (count broken) " file(s) carry a change outside every prose position."))

      (and (seq prose) (not allow-prose?))
      (println (str "HOLD — " (count prose) " file(s) changed prose. Re-run with --allow-prose"
                    " once a human has read the strings above."))

      :else
      (println (str "PASS — " (count results) " file(s) changed, nothing a reader can see moved.")))))

(let [args (vec *command-line-args*)
      opt (fn [flag default]
            (if-let [i (->> args (keep-indexed #(when (= %2 flag) %1)) first)]
              (get args (inc i) default) default))
      from (opt "--from" "@-")
      to (opt "--to" "@")
      allow-prose? (boolean (some #{"--allow-prose"} args))
      edn? (boolean (some #{"--edn"} args))
      files (changed-files from to)]
  (if (empty? files)
    (do (if edn?
          (prn {:gate :no-code-change :status :clean :files []})
          (println "no-code-change: nothing changed between" from "and" to))
        (System/exit 0))
    (let [file-hunks (hunks (jj "diff" "--git" "--context" "0" "--from" from "--to" to))
          results (mapv (partial check-file from to file-hunks) files)
          broken? (some #(false? (:ok? %)) results)
          prose? (some :prose? results)
          status (cond broken? :code-changed prose? :prose-changed :else :clean)]
      (if edn?
        (prn {:gate :no-code-change :status status :from from :to to :files results})
        (report! results from to allow-prose?))
      (System/exit (if (or broken? (and prose? (not allow-prose?))) 1 0)))))
