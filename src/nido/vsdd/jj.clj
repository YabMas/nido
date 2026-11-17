(ns nido.vsdd.jj
  "jj VCS integration for VSDD sweep, and for reading a project's declaration at a revision.
   All jj shell interactions in one place."
  (:require [babashka.fs :as fs]
            [babashka.process :refer [shell]]
            [clojure.string :as str]))

(defn ^{:malli/schema [:=> [:cat :Path [:* :any]] :ShellResult]}
  jj!
  "Run a jj command in the given directory. Returns {:exit :out :err}."
  [dir & args]
  (let [result (apply shell {:dir dir :continue true :out :string :err :string}
                      "jj" args)]
    {:exit (:exit result)
     :out (str/trim (:out result))
     :err (str/trim (:err result))}))

(defn ^{:malli/schema [:=> [:cat :Path] [:maybe :string]]}
  current-change-id
  "Get the change_id of @ in the given directory."
  [dir]
  (let [{:keys [exit out]} (jj! dir "log" "-r" "@" "-T" "change_id" "--no-graph")]
    (when (zero? exit) out)))

(defn ^{:malli/schema [:=> [:cat :Path :string] [:maybe :string]]}
  workspace-change-id
  "Get the change_id of a workspace's @ from the main repo."
  [dir ws-name]
  (let [{:keys [exit out]} (jj! dir "log" "-r" (str ws-name "@") "-T" "change_id" "--no-graph")]
    (when (zero? exit) out)))

(defn ^{:malli/schema [:=> [:cat :Path :Path :string] :ShellResult]}
  create-workspace
  "Create a jj workspace. Returns {:exit :out :err}."
  [dir ws-path ws-name]
  (jj! dir "workspace" "add" ws-path "--name" ws-name))

(defn ^{:malli/schema [:=> [:cat :Path :string] :ShellResult]}
  forget-workspace
  "Forget a jj workspace."
  [dir ws-name]
  (jj! dir "workspace" "forget" ws-name))

(defn ^{:malli/schema [:=> [:cat :Path :string] [:maybe :string]]}
  last-converged-commit
  "Find the most recent commit with a vsdd-converged trailer for the given module.
   Returns change_id or nil."
  [dir module]
  (let [pattern (str "*vsdd-converged: " module "*")
        {:keys [exit out]} (jj! dir "log" "-r" (str "description(glob:" (pr-str pattern) ")")
                                "-T" "change_id" "--no-graph" "--limit" "1")]
    (when (and (zero? exit) (not (str/blank? out)))
      (first (str/split-lines out)))))

(defn ^{:malli/schema [:=> [:cat :Path :string :string] :boolean]}
  files-changed-since?
  "Check if any files under module-path changed since the given change_id."
  [dir change-id module-path]
  (let [{:keys [exit out]} (jj! dir "diff" "--stat" "--from" change-id
                                "--" module-path)]
    (and (zero? exit) (not (str/blank? out)))))

(defn ^{:malli/schema [:=> [:cat :Path :string :string] :ShellResult]}
  rebase-revision
  "Rebase a single revision onto a destination."
  [dir source-change dest-change]
  (jj! dir "rebase" "-r" source-change "--destination" dest-change))

(defn ^{:malli/schema [:=> [:cat :Path :string :string] :ShellResult]}
  describe
  "Set the description of a revision."
  [dir change-id message]
  (jj! dir "desc" "-r" change-id "-m" message))

(defn ^{:malli/schema [:=> [:cat :Path :string] :string]}
  get-description
  "Get the description of a revision."
  [dir change-id]
  (let [{:keys [exit out]} (jj! dir "log" "-r" change-id "-T" "description" "--no-graph")]
    (when (zero? exit) out)))

(defn ^{:malli/schema [:=> [:cat :Path :string] :ShellResult]}
  new-on-top
  "Create a new empty working copy on top of the given revision."
  [dir change-id]
  (jj! dir "new" change-id))

(defn ^{:malli/schema [:=> [:cat :Path :string] :ShellResult]}
  abandon
  "Abandon a revision."
  [dir change-id]
  (jj! dir "abandon" change-id))

(defn ^{:malli/schema [:=> [:cat :string :string [:vector :string]] [:maybe :string]]}
  materialize-spec-dirs!
  "Write the spec dirs as they stood at `rev` into a fresh directory, beside a link to everything
   else the worktree holds, and answer it. The caller deletes it, which removes the links and never
   what they point at.

   This is the half `nido.design.check` may not do: its band depends on Platform alone, and a
   revision is a VCS concept. Whoever may reach jj resolves the revision to a tree and hands the
   design seam a directory — the design diff, the landing gate, the dashboard's explorer.

   The links are what let fukan run there at all. Its documented command resolves the `:fukan`
   alias from the project's `deps.edn` where it runs, and puts that directory on the classpath as
   `.` — so a directory holding the declaration alone has no fukan to read it with, and one linking
   the worktree's spec dirs reads the wrong declaration. Only the spec dirs are the base's; what
   reads them is the worktree's, as it is at the other end of a diff.

   File by file rather than a second workspace: a workspace costs a checkout of the whole repo
   and has to be forgotten afterwards, and what is wanted is a few dozen small files. nil when
   the revision names nothing, which is the caller's cue to say so rather than to diff against
   an empty design and report the whole declaration as new."
  [worktree rev spec-dirs]
  (let [{:keys [exit out]} (jj! worktree "file" "list" "-r" rev)]
    (when (zero? (long exit))
      (let [wanted (->> (str/split-lines (str out))
                        (map str/trim)
                        (filter (fn [f] (and (str/ends-with? f ".clj")
                                             (some #(str/starts-with? f (str % "/")) spec-dirs)))))
            dir    (fs/create-temp-dir {:prefix "nido-design-base-"})
            ;; a spec dir's root is the base's, and a classpath cache or a VCS store is the
            ;; worktree's own — neither is to be read, or written, through a link
            unlinked (into #{".cpcache" ".git" ".jj"} (map #(first (str/split % #"/"))) spec-dirs)]
        (doseq [entry (fs/list-dir worktree)
                :let [nm (str (fs/file-name entry))]
                :when (not (unlinked nm))]
          (fs/create-sym-link (fs/path dir nm) (fs/absolutize entry)))
        (doseq [f wanted]
          (let [target (fs/path dir f)]
            (fs/create-dirs (fs/parent target))
            (let [{:keys [exit out]} (jj! worktree "file" "show" "-r" rev f)]
              (when (zero? (long exit)) (spit (str target) out)))))
        (str dir)))))
