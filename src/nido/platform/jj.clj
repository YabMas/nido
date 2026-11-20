(ns nido.platform.jj
  "Running jj from nido: a command in a directory, answered rather than thrown, and a project's
   spec dirs read back as files at a revision.

   Not every jj call in nido comes through here — several session, lane and daemon namespaces
   still shell to jj themselves. New jj calls belong here."
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

(defn ^{:malli/schema [:=> [:cat :string :string [:vector :string]] [:maybe :string]]}
  materialize-spec-dirs!
  "Write the spec dirs as they stood at `rev` into a fresh directory, beside a link to everything
   else the worktree holds, and answer it. The caller deletes it, which removes the links and never
   what they point at.

   The design seam (`nido.design.check`) is handed the directory and never the revision: whoever
   holds the revision resolves it here to a tree — the design diff, the landing gate, the
   dashboard's explorer — so the seam reads a declaration without knowing how it was fetched.

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
