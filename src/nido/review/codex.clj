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
   unit-testable. The prompt (review instructions + a changed-file manifest) is
   fed via stdin with \"-\" as the positional prompt. Review runs under a
   read-only sandbox: codex explores the worktree (jj diff / file show / grep /
   read) but must never mutate it — the separate fix stage does that. Read-only
   also lets codex run `jj --ignore-working-copy` reads without the working-copy
   lock write the sandbox would otherwise deny."
  [{:keys [cwd schema-path out-path prompt]}]
  [{:dir cwd :continue true :in prompt :out :inherit :err :inherit}
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

(defn review!
  "Git-free codex review of the branch change (base..@). See ns doc.

   The prompt carries a changed-file MANIFEST (`jj diff --name-only`), not the
   inlined diff: the full concatenated diff overflows codex's 1 MiB input limit.
   Codex pulls each file's diff itself (`jj --ignore-working-copy diff …`) and
   explores the worktree for context — which it must, since a flat patch can't
   answer \"is this deleted symbol still referenced?\"."
  [{:keys [cwd base run-id]}]
  (let [{:keys [exit out err]} (jj/jj! cwd "diff" "--name-only" "--from" base "--to" "@")
        _        (when-not (zero? exit)
                   ;; A failed diff (cwd not a jj workspace, bad base, …) must not
                   ;; be mistaken for an empty diff — that would silently report a
                   ;; clean review of code nothing ever looked at.
                   (throw (ex-info "jj diff failed — cwd is not a reviewable workspace"
                                   {:reason :review-failed :exit exit
                                    :cwd cwd :base base :err err})))
        manifest out]
    (if (str/blank? manifest)
      {:status :clean :findings []}
      (let [dir         (cstate/run-dir run-id)
            _           (fs/create-dirs dir)
            schema-path (str (fs/path dir "findings_schema.json"))
            out-path    (str (fs/path dir "review-out.json"))
            prompt      (str prompts/review-prompt
                             "\n\nBase branch: " base "  (head: @)\n"
                             "Changed files:\n" (str/trim manifest))]
        (spit schema-path (slurp (io/resource "review/findings_schema.json")))
        (let [{:keys [exit]} (run-codex! {:cwd cwd :schema-path schema-path
                                          :out-path out-path
                                          :prompt prompt})]
          (when (or (not (zero? exit)) (not (fs/exists? out-path)))
            (throw (ex-info "codex review failed"
                            {:reason :review-failed :exit exit :cwd cwd})))
          (assoc (parse-output (slurp out-path))
                 :status nil :manifest manifest))))))
