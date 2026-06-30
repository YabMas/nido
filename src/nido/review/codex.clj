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

(defn review!
  "Git-free codex review of the branch change (merge-base(@,base)..@). See ns doc.

   Diffs from the MERGE BASE of @ and `base`, not the tip of `base` (see
   `merge-base`). The prompt carries a changed-file MANIFEST (`jj diff
   --name-only`), not the inlined diff: the full concatenated diff overflows
   codex's 1 MiB input limit. Codex pulls each file's diff itself (`jj
   --ignore-working-copy diff …`) and explores the worktree for context — which
   it must, since a flat patch can't answer \"is this deleted symbol still
   referenced?\"."
  [{:keys [cwd base run-id iter]}]
  (let [base-rev (merge-base cwd base)
        {:keys [exit out err]} (jj/jj! cwd "diff" "--name-only" "--from" base-rev "--to" "@")
        _        (when-not (zero? exit)
                   ;; A failed diff (cwd not a jj workspace, bad base, …) must not
                   ;; be mistaken for an empty diff — that would silently report a
                   ;; clean review of code nothing ever looked at.
                   (throw (ex-info "jj diff failed — cwd is not a reviewable workspace"
                                   {:reason :review-failed :exit exit
                                    :cwd cwd :base base :base-rev base-rev :err err})))
        manifest out]
    (if (str/blank? manifest)
      {:status :clean :findings []}
      (let [dir         (cstate/run-dir run-id)
            _           (fs/create-dirs dir)
            schema-path (str (fs/path dir "findings_schema.json"))
            out-path    (str (fs/path dir "review-out.json"))
            log-path    (str (fs/path dir (format "codex-round-%d.log" (or iter 1))))
            prompt      (str prompts/review-prompt
                             "\n\nBase revision (use this exact value as <base> in the"
                             " commands above): " base-rev "  (head: @)\n"
                             "Changed files:\n" (str/trim manifest))]
        (spit schema-path (slurp (io/resource "review/findings_schema.json")))
        (let [{:keys [exit]} (run-codex! {:cwd cwd :schema-path schema-path
                                          :out-path out-path :log-path log-path
                                          :prompt prompt})]
          (when (or (not (zero? exit)) (not (fs/exists? out-path)))
            (throw (ex-info "codex review failed"
                            {:reason :review-failed :exit exit :cwd cwd})))
          (assoc (parse-output (slurp out-path))
                 :status nil :manifest manifest :base-rev base-rev))))))
