(ns nido.review.pass
  "The diff review pass, git-free: one revision range's manifest and prompt -> a reviewer
   launched through `codex/run-reviewer!` -> normalized, identified findings. nido worktrees are
   non-colocated jj workspaces, so git-coupled `codex review` cannot run there."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [nido.coordinator.record.state :as cstate]
   [nido.review.codex :as codex]
   [nido.review.digest :as digest]
   [nido.review.prompts :as prompts]
   [nido.vsdd.jj :as jj]))

(defn ^{:malli/schema [:=> [:cat :Finding] :string]}
  finding-id
  "A finding's identity, derived from what it points at rather than from its
   position in a list. Indices into \"this round's findings\" cannot survive
   re-attribution across layers, and they make a report that says nothing about
   WHY a finding was dropped — it is simply absent."
  [{:keys [file line-start title]}]
  (digest/short-id (str file "|" line-start "|" title)))

(def ^:private leading-priority-re
  "The `[P2] ` a reviewer is asked to open its title with."
  #"^\s*\[P\d\]\s*")

(defn- untagged-title
  "The title without the priority it opens with.

   `review_prompt.md` asks for the priority TWICE — as a `[Pn]` on the front of
   the title, which is what makes a reviewer commit to a number in prose, and in
   the `priority` field, which is the one everything downstream renders from. A
   title that keeps its tag therefore reaches every reader doubled; the fixer was
   handed `- [P2] [P2] Restore the pool's configured statement timeout`.

   Dropped at ingest rather than at each place that prepends the field, so a
   later reader of a finding cannot reintroduce it. It also leaves the title as
   the finding's own sentence, which is what `finding-id` here and the
   cross-reviewer dedup in `nido.review.stages` hash: with the tag in, two
   reviewers reporting one defect at different priorities were two findings, and
   a reviewer that re-raised a finding at a new priority raised a new one."
  [title]
  (str/replace (str title) leading-priority-re ""))

(defn ^{:malli/schema [:=> [:cat :map] :Finding]}
  normalize-finding
  "Codex native finding (keyword keys) -> normalized finding.

   `kind`, `remedy` and `layers` are added only when the finding carries them,
   which is only ever the composition pass: a layer review is not asked for
   them, and stamping every finding with three nils would put the composition
   vocabulary on findings that have no claim to it."
  [raw]
  (let [loc (:code_location raw)
        lr  (:line_range loc)]
    (cond-> {:title      (untagged-title (:title raw))
             :body       (:body raw)
             :priority   (:priority raw)
             :reach      (some-> (:reach raw) keyword)
             :confidence (:confidence_score raw)
             :file       (:absolute_file_path loc)
             :line-start (:start lr)
             :line-end   (:end lr)}
      ;; Only when there is one, like :kind and :remedy beside it — a key
      ;; present and nil is a citation the reviewer did not make, and every
      ;; reader downstream tests the key. Blank counts as absent for the same
      ;; reason: a reviewer holding no invariant answers "" as readily as null.
      (not (str/blank? (str (:contradicts raw))))
      (assoc :contradicts (str/trim (str (:contradicts raw))))
      (:kind raw)         (assoc :kind (keyword (:kind raw)))
      (:remedy raw)       (assoc :remedy (keyword (:remedy raw)))
      (seq (:layers raw)) (assoc :layers (vec (:layers raw))))))

(defn- with-id [f] (assoc f :id (finding-id f)))

(defn ^{:malli/schema [:=> [:cat :string] :any]}
  parse-output
  "Parse codex --output-schema JSON string into
   {:findings [...] :overall-correctness <str>}."
  [json-str]
  (let [m (json/parse-string json-str true)]
    {:findings            (mapv (comp with-id normalize-finding) (:findings m))
     :overall-correctness (:overall_correctness m)}))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  composition-schema
  "The findings schema with the composition pass's three extra fields: the
   `kind` it must classify the defect as, the `remedy` it says the defect needs,
   and the `layers` it must show the defect spans.

   Derived from the base schema rather than kept beside it as a second resource.
   It IS the findings schema plus those three, and a copy would quietly stop
   being that the first time the base gains a field — leaving the pass that most
   needs a change to the review contract as the one place that never sees it.

   Both enums come from the lists the primer teaches, `prompts/composition-kinds`
   and `prompts/remedy-vocabulary`. A value the prompt names but the schema will
   not accept is not a soft mismatch: strict structured-output mode rejects the
   response, so every round 400s before the review turn starts.

   Every added property is also added to `required`, for the same reason —
   strict mode demands it of every object node."
  [base]
  (update-in base [:properties :findings :items]
             (fn [item]
               (-> item
                   (assoc-in [:properties :kind]
                             {:type "string"
                              :enum (mapv :kind prompts/composition-kinds)})
                   (assoc-in [:properties :remedy]
                             {:type "string"
                              :enum (mapv :remedy prompts/remedy-vocabulary)})
                   (assoc-in [:properties :layers]
                             {:type "array" :items {:type "string"}})
                   (update :required #(into (vec %) ["kind" "remedy" "layers"]))))))

(defn ^{:malli/schema [:=> [:cat :boolean] :string]}
  schema-json
  "The output schema to hand codex for this review, as JSON."
  [composition?]
  (let [base (json/parse-string
              (slurp (io/resource "review/findings_schema.json")) true)]
    (json/generate-string (cond-> base composition? composition-schema))))

(defn ^{:malli/schema [:=> [:cat :Path :any] :string]}
  merge-base
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

(defn ^{:malli/schema [:=> [:cat :Path :any :any] :any]}
  changed-files
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

(defn ^{:malli/schema [:=> [:cat :any] :string]}
  safe-label
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

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  review!
  "Git-free review of ONE revision range. See ns doc.

   `reviewer` is who judges it, `codex/default-reviewer` when nil;
   `codex/run-reviewer!` says what happens when it cannot be run. The answer
   carries `:judged-by`, because a stand-in's findings are not codex's and a
   reader of the report is owed the difference.

   `from`/`to` aim it: `merge-base(@,base)`→`@` for the whole stack, or a single
   layer's `<lower-tip>`→`<own-tip>`. `from` must be a FORK POINT rather than the
   tip of a branch (see `merge-base`) — diffing from a base that has moved on
   turns everything base gained into spurious deletions.

   `brief` is the layer's `/stack` §5 review brief, which bounds the review to
   what that layer claims. A whole-stack pass carries `composition` instead —
   the stack's layers with their revisions — which primes it to ask whether the
   change was cut into the right pieces and whether they hold together, rather
   than to review the branch flat. The two are exclusive by construction: a
   target is one layer or the composition of several, never both, and a
   composition has no single brief to be bounded by.

   `prior-fixes` is what a fixer already did to this exact target earlier in the
   run — a repair landed or put back, or an argument for writing none — plus any
   repair a higher layer took for something this target reported; see
   `stages/with-fix-memory`. It is orthogonal to both: a layer and the
   composition alike can have been worked on, and neither reviewer is told so by
   anything else.

   `standing` is what an earlier RUN's design verdict left outstanding, and it
   reaches a layer or the flat branch but never the composition pass — see
   `stages/with-standing-needs`.

   `prior-open` is what an earlier RUN left owed against this exact layer,
   matched by label because a repair moves the patch a hash is taken over; it
   reaches the same readers as `standing` and for the same reasons — see
   `stages/with-prior-open`.

   The schema follows the primer and not the target: the composition variant
   demands a `kind` and the `layers` a defect spans, and asking that of a
   reviewer that was never taught the taxonomy is a contract nothing can meet.

   The prompt carries a changed-file MANIFEST (`jj diff --name-only`), not the
   inlined diff: the full concatenated diff overflows codex's 1 MiB input limit.
   Codex pulls each file's diff itself and reads file content AT `to` — never
   from the working copy, which for a layer review sits at a different revision
   than the one under review.

   The composition pass is the exception and gets NO manifest — see
   `prompts/composition-manifest-note`. Its files are already on the layer rows,
   one layer at a time, and the flat union is the range in which the cut it is
   here to judge cannot be seen."
  [{:keys [cwd from to run-id iter label brief composition prior-fixes standing
           prior-open design reviewer]}]
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
      ;; Not :clean. Nothing was read, so "found nothing" is a claim no reviewer
      ;; made — and this is the only place that can still tell the two apart:
      ;; downstream both arrive as a target with an empty finding list.
      {:status :nothing-to-review :findings [] :base-rev from :manifest ""}
      (let [dir         (cstate/run-dir run-id)
            _           (fs/create-dirs dir)
            schema-path (str (fs/path dir (artifact-name label iter "-schema.json")))
            out-path    (str (fs/path dir (artifact-name label iter "-out.json")))
            log-path    (str (fs/path dir (artifact-name label iter ".log")))
            composed    (prompts/composition-block composition)
            prompt      (str prompts/review-prompt
                             ;; BEFORE the layer brief, and above everything the
                             ;; round itself carries. A layer's claims are what
                             ;; this slice of the change asserts about itself;
                             ;; the design is what the whole change committed to,
                             ;; and a reviewer reading the narrower one first
                             ;; reads the wider one as a qualification of it.
                             "\n\n" (prompts/design-yardstick-block design)
                             "\n\n" (or (prompts/layer-brief-block brief) composed)
                             ;; Appended rather than folded into the brief block,
                             ;; because a layer with no brief still gets fixed and
                             ;; that block is nil for one. What a fixer landed here
                             ;; is a fact about the range under review, not about
                             ;; how the range was bounded.
                             (prompts/prior-fixes-block prior-fixes)
                             (prompts/standing-needs-block standing)
                             (prompts/prior-open-block prior-open)
                             "\nBase revision (use this exact value as <base> in the"
                             " commands above): " from "\n"
                             "Head revision (use this exact value as <head>): " to "\n"
                             ;; The composition pass gets no flat manifest, and
                             ;; not as an economy: the union of the layers' files
                             ;; is the one view in which no cut is visible. See
                             ;; `prompts/composition-manifest-note`.
                             (if composed
                               prompts/composition-manifest-note
                               (prompts/manifest-block manifest)))]
        (spit schema-path (schema-json (some? composed)))
        (let [{:keys [exit judged-by unavailable]}
              (codex/run-reviewer! {:reviewer reviewer :cwd cwd :schema-path schema-path
                                    :out-path out-path :log-path log-path
                                    :prompt prompt})]
          (when (or (not (zero? exit)) (not (fs/exists? out-path)))
            ;; Two reasons, because they ask opposite things of a reader. A
            ;; classified failure is a condition outside the branch that must be
            ;; waited out or authenticated past; an unclassified one is this run
            ;; failing, and the diff is where to look. See `codex/unavailability`.
            ;; Read from whoever ran LAST: a stand-in that broke is this run
            ;; failing, whatever the reviewer it replaced ran out of.
            (if-let [u unavailable]
              (throw (ex-info (:message u)
                              {:reason :reviewer-unavailable :unavailable u
                               :exit exit :cwd cwd :label label :judged-by judged-by}))
              (throw (ex-info (str (name (:reviewer judged-by)) " review failed"
                                   (when-let [why (:because judged-by)]
                                     (str " — standing in for "
                                          (name (:instead-of judged-by)) ": " why)))
                              {:reason :review-failed :exit exit :cwd cwd :label label
                               :judged-by judged-by}))))
          (assoc (parse-output (slurp out-path))
                 :status nil :manifest manifest :base-rev from
                 :judged-by judged-by))))))
