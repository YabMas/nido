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
   [nido.review.prompts :as prompts]
   [nido.vsdd.jj :as jj]))

(defn normalize-finding
  "Codex native finding (keyword keys) -> normalized finding."
  [raw]
  (let [loc (:code_location raw)
        lr  (:line_range loc)]
    {:title      (:title raw)
     :body       (:body raw)
     :priority   (:priority raw)
     :reach      (some-> (:reach raw) keyword)
     :confidence (:confidence_score raw)
     :file       (:absolute_file_path loc)
     :line-start (:start lr)
     :line-end   (:end lr)}))

(defn parse-output
  "Parse codex --output-schema JSON string into
   {:findings [...] :overall-correctness <str>}."
  [json-str]
  (let [m (json/parse-string json-str true)]
    {:findings            (mapv normalize-finding (:findings m))
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

(defn- artifact-name
  "Per-review file name. `label` segments the run dir so concurrent reviews of
   different ranges never write over each other's schema, output, or log — with
   one shared name the last writer wins and every layer reports the same
   findings."
  [label iter suffix]
  (format "%s-round-%d%s" (or label "stack") (or iter 1) suffix))

(defn review!
  "Git-free codex review of ONE revision range. See ns doc.

   `from`/`to` aim it: `merge-base(@,base)`→`@` for the whole stack, or a single
   layer's `<lower-tip>`→`<own-tip>`. `from` must be a FORK POINT rather than the
   tip of a branch (see `merge-base`) — diffing from a base that has moved on
   turns everything base gained into spurious deletions.

   `brief` is the layer's `/stack` §5 review brief, which bounds the review to
   what that layer claims. Omit it for a whole-stack review: there is no single
   brief for a composition, and inventing one would bound the pass that exists
   precisely to be unbounded.

   The prompt carries a changed-file MANIFEST (`jj diff --name-only`), not the
   inlined diff: the full concatenated diff overflows codex's 1 MiB input limit.
   Codex pulls each file's diff itself and reads file content AT `to` — never
   from the working copy, which for a layer review sits at a different revision
   than the one under review."
  [{:keys [cwd from to run-id iter label brief]}]
  (let [to       (or to "@")
        {:keys [exit out err]} (jj/jj! cwd "diff" "--name-only" "--from" from "--to" to)
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
            prompt      (str prompts/review-prompt
                             "\n\n" (prompts/layer-brief-block brief)
                             "\nBase revision (use this exact value as <base> in the"
                             " commands above): " from "\n"
                             "Head revision (use this exact value as <head>): " to "\n"
                             "Changed files:\n" (str/trim manifest))]
        (spit schema-path (slurp (io/resource "review/findings_schema.json")))
        (let [{:keys [exit]} (run-codex! {:cwd cwd :schema-path schema-path
                                          :out-path out-path :log-path log-path
                                          :prompt prompt})]
          (when (or (not (zero? exit)) (not (fs/exists? out-path)))
            (throw (ex-info "codex review failed"
                            {:reason :review-failed :exit exit :cwd cwd :label label})))
          (assoc (parse-output (slurp out-path))
                 :status nil :manifest manifest :base-rev from))))))
