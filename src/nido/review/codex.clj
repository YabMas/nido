(ns nido.review.codex
  "Git-free codex review driver: jj diff --git -> codex exec --output-schema
   -> normalized findings. nido worktrees are non-colocated jj workspaces, so
   git-coupled `codex review` cannot run there."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [nido.coordinator.state :as cstate]
   [nido.review.digest :as digest]
   [nido.review.prompts :as prompts]
   [nido.vsdd.jj :as jj]))

(defn finding-id
  "A finding's identity, derived from what it points at rather than from its
   position in a list. Indices into \"this round's findings\" cannot survive
   re-attribution across layers, and they make a report that says nothing about
   WHY a finding was dropped — it is simply absent."
  [{:keys [file line-start title]}]
  (digest/short-id (str file "|" line-start "|" title)))

(defn normalize-finding
  "Codex native finding (keyword keys) -> normalized finding.

   `kind` and `layers` are added only when the finding carries them, which is
   only ever the composition pass: a layer review is not asked for them, and
   stamping every finding with two nils would put the composition vocabulary on
   findings that have no claim to it."
  [raw]
  (let [loc (:code_location raw)
        lr  (:line_range loc)]
    (cond-> {:title      (:title raw)
             :body       (:body raw)
             :priority   (:priority raw)
             :reach      (some-> (:reach raw) keyword)
             :confidence (:confidence_score raw)
             :file       (:absolute_file_path loc)
             :line-start (:start lr)
             :line-end   (:end lr)}
      (:kind raw)         (assoc :kind (keyword (:kind raw)))
      (seq (:layers raw)) (assoc :layers (vec (:layers raw))))))

(defn- with-id [f] (assoc f :id (finding-id f)))

(defn parse-output
  "Parse codex --output-schema JSON string into
   {:findings [...] :overall-correctness <str>}."
  [json-str]
  (let [m (json/parse-string json-str true)]
    {:findings            (mapv (comp with-id normalize-finding) (:findings m))
     :overall-correctness (:overall_correctness m)}))

(defn codex-argv
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

(defn run-codex!
  "Run `codex exec` with output-schema. Seam for tests. Returns {:exit <int>}.
   Writes the final JSON response to :out-path."
  [opts]
  (let [res (apply p/shell (codex-argv opts))]
    {:exit (:exit res)}))

(defn composition-schema
  "The findings schema with the composition pass's two extra fields: the `kind`
   it must classify the defect as, and the `layers` it must show the defect
   spans.

   Derived from the base schema rather than kept beside it as a second resource.
   It IS the findings schema plus those two, and a copy would quietly stop being
   that the first time the base gains a field — leaving the pass that most needs
   a change to the review contract as the one place that never sees it.

   The `kind` enum comes from `prompts/composition-kinds`, the same list the
   primer teaches. A taxonomy the prompt names but the schema will not accept is
   not a soft mismatch: strict structured-output mode rejects the response, so
   every round 400s before the review turn starts.

   Every added property is also added to `required`, for the same reason —
   strict mode demands it of every object node."
  [base]
  (update-in base [:properties :findings :items]
             (fn [item]
               (-> item
                   (assoc-in [:properties :kind]
                             {:type "string"
                              :enum (mapv :kind prompts/composition-kinds)})
                   (assoc-in [:properties :layers]
                             {:type "array" :items {:type "string"}})
                   (update :required #(into (vec %) ["kind" "layers"]))))))

(defn schema-json
  "The output schema to hand codex for this review, as JSON."
  [composition?]
  (let [base (json/parse-string
              (slurp (io/resource "review/findings_schema.json")) true)]
    (json/generate-string (cond-> base composition? composition-schema))))

(defn merge-base
  "Resolve the merge base (fork point) of @ and `base` to a single commit id.
   This — not the tip of `base` — is the correct comparison point for a branch
   review: `jj diff --from <tip-of-base> --to @` is a 2-way tree diff, so any
   work `base` gained since the branch forked shows up as spurious deletions
   (e.g. 180 files instead of the PR's actual 29). Diffing from the fork point
   matches what the PR's \"Files changed\" shows. Throws :review-failed if jj
   can't resolve it (not a workspace, unrelated histories, …)."
  [cwd base]
  (let [{:keys [exit out err]}
        (jj/jj! cwd "log" "--no-graph"
                "-r" (str "heads(::@ & ::" base ")")
                "-T" "commit_id ++ \"\\n\"")
        rev (->> (str/split-lines (str out)) (remove str/blank?) first)]
    (when (or (not (zero? exit)) (str/blank? rev))
      (throw (ex-info "jj could not resolve a merge base — cwd is not a reviewable workspace"
                      {:reason :review-failed :exit exit :cwd cwd :base base :err err})))
    rev))

(defn- diff-name-only
  "Raw `jj diff --name-only` over a range. Returns jj!'s {:exit :out :err}; the
   caller decides what a failure means, because the two callers disagree. A
   review must treat a failed diff as fatal — an empty diff read as \"clean\"
   would report that nothing was wrong with code nothing looked at — while
   resolving the target up front must not take a run down over a display detail."
  [cwd from to]
  (jj/jj! cwd "diff" "--name-only" "--from" from "--to" to))

(defn changed-files
  "The files a range touches, or [] when the diff fails for any reason. Tolerant
   by design: this feeds the target block the display reads, and a header is
   never worth a run.

   Catches as well as checking the exit code — `shell` THROWS on an unusable
   :dir rather than returning non-zero, so an exit check alone would let a bad
   cwd escape as an exception from a display path."
  [cwd from to]
  (try
    (let [{:keys [exit out]} (diff-name-only cwd from to)]
      (if (zero? exit)
        (vec (remove str/blank? (str/split-lines (str out))))
        []))
    (catch Throwable _ [])))

(defn safe-label
  "A label made safe to put in a filename. Layer labels come from bookmarks, and
   an unstacked branch's bookmark is the session name — which contains a slash
   (`feat/thing`) and would silently write the artifact into a subdirectory that
   does not exist."
  [label]
  (if (str/blank? (str label))
    "stack"
    (str/replace (str label) #"[^A-Za-z0-9._-]" "-")))

(defn- artifact-name
  "Per-review file name. `label` segments the run dir so concurrent reviews of
   different ranges never write over each other's schema, output, or log — with
   one shared name the last writer wins and every layer reports the same
   findings."
  [label iter suffix]
  (format "%s-round-%d%s" (safe-label label) (or iter 1) suffix))

(defn review!
  "Git-free codex review of ONE revision range. See ns doc.

   `from`/`to` aim it: `merge-base(@,base)`→`@` for the whole stack, or a single
   layer's `<lower-tip>`→`<own-tip>`. `from` must be a FORK POINT rather than the
   tip of a branch (see `merge-base`) — diffing from a base that has moved on
   turns everything base gained into spurious deletions.

   `brief` is the layer's `/stack` §5 review brief, which bounds the review to
   what that layer claims. A whole-stack pass carries `composition` instead —
   the stack's layers with their revisions — which primes it to review the
   SEQUENCE rather than the branch flat. The two are exclusive by construction:
   a target is one layer or the composition of several, never both, and a
   composition has no single brief to be bounded by.

   The schema follows the primer and not the target: the composition variant
   demands a `kind` and the `layers` a defect spans, and asking that of a
   reviewer that was never taught the taxonomy is a contract nothing can meet.

   The prompt carries a changed-file MANIFEST (`jj diff --name-only`), not the
   inlined diff: the full concatenated diff overflows codex's 1 MiB input limit.
   Codex pulls each file's diff itself and reads file content AT `to` — never
   from the working copy, which for a layer review sits at a different revision
   than the one under review."
  [{:keys [cwd from to run-id iter label brief composition]}]
  (let [to       (or to "@")
        {:keys [exit out err]} (diff-name-only cwd from to)
        _        (when-not (zero? exit)
                   ;; A failed diff (cwd not a jj workspace, bad range, …) must not
                   ;; be mistaken for an empty diff — that would silently report a
                   ;; clean review of code nothing ever looked at.
                   (throw (ex-info "jj diff failed — cwd is not a reviewable workspace"
                                   {:reason :review-failed :exit exit
                                    :cwd cwd :from from :to to :err err})))
        manifest out]
    (if (str/blank? manifest)
      {:status :clean :findings [] :base-rev from :manifest ""}
      (let [dir         (cstate/run-dir run-id)
            _           (fs/create-dirs dir)
            schema-path (str (fs/path dir (artifact-name label iter "-schema.json")))
            out-path    (str (fs/path dir (artifact-name label iter "-out.json")))
            log-path    (str (fs/path dir (artifact-name label iter ".log")))
            composed    (prompts/composition-block composition)
            prompt      (str prompts/review-prompt
                             "\n\n" (or (prompts/layer-brief-block brief) composed)
                             "\nBase revision (use this exact value as <base> in the"
                             " commands above): " from "\n"
                             "Head revision (use this exact value as <head>): " to "\n"
                             "Changed files:\n" (str/trim manifest))]
        (spit schema-path (schema-json (some? composed)))
        (let [{:keys [exit]} (run-codex! {:cwd cwd :schema-path schema-path
                                          :out-path out-path :log-path log-path
                                          :prompt prompt})]
          (when (or (not (zero? exit)) (not (fs/exists? out-path)))
            (throw (ex-info "codex review failed"
                            {:reason :review-failed :exit exit :cwd cwd :label label})))
          (assoc (parse-output (slurp out-path))
                 :status nil :manifest manifest :base-rev from))))))
