(ns nido.session.change
  "A session's unlanded change: what it is, landing it, and throwing it away.

   HIDES the revsets that say what a session has not landed — everything between origin's main and
   its working copy — how a revert is made, and that publishing is the landing verb run as a
   process in the session's worktree.

   A change is a value, `{:description :files}`: the description of its newest described commit
   (what it would land as; blank when the agent has not described it) and every path it touches,
   each `{:path :status}` with status one of :added :modified :deleted :renamed. No change is nil.

   Origin's main is read as the last fetch left it, so a change based on an older main still reads
   as only its own commits; publishing fetches for itself."
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [nido.session.lifecycle :as lifecycle]))

(def ^:private unlanded "main@origin..@")

(defn- worktree [project session]
  (:wt-path (lifecycle/session-coords session {:project (name project)})))

(defn- jj! [dir & args]
  (let [{:keys [exit out err]} (apply p/shell {:dir dir :continue true :out :string :err :string}
                                      "jj" args)]
    (when-not (zero? exit)
      (throw (ex-info (str "jj " (str/join " " args) " failed: " (str/trim (str err)))
                      {:exit exit :err err})))
    (str/trim (str out))))

(def ^:private statuses {"A" :added "M" :modified "D" :deleted "R" :renamed "C" :added})

(defn- parse-summary
  "`jj diff --summary` lines, `M path` or `R {old => new}`, as `{:path :status}`s."
  [out]
  (vec (for [line (str/split-lines out)
             :let [[_ code path] (re-matches #"([A-Z])\s+(.+)" (str/trim line))]
             :when code]
         {:path path :status (statuses code :modified)})))

(defn ^{:malli/schema [:=> [:cat :any :string] [:maybe :map]]}
  pending
  "The change `session` holds that origin's main does not, or nil when it holds none. Throws when
   the worktree cannot be read."
  [project session]
  (let [dir   (worktree project session)
        files (parse-summary (jj! dir "diff" "--summary" "-r" unlanded))]
    (when (seq files)
      {:description (jj! dir "log" "--no-graph" "-r"
                         (str "latest((" unlanded ") & description(regex:'.'))")
                         "-T" "description")
       :files files})))

(defn ^{:malli/schema [:=> [:cat :any :string] :any]}
  revert!
  "Throw away every change `session` holds that origin's main does not: its working copy becomes an
   empty change on origin's main, and the commits it had are abandoned. jj's operation log keeps
   them, so `jj op restore` brings them back. Throws when jj refuses, leaving what it had not done."
  [project session]
  (let [dir (worktree project session)
        old (jj! dir "log" "--no-graph" "-r" "@" "-T" "change_id")]
    (jj! dir "new" "main@origin")
    (jj! dir "abandon" (str "main@origin.." old))
    nil))

(defn ^{:malli/schema [:=> [:cat :any :string :any] :int]}
  publish!
  "Land `session`'s change on origin's main by running `bb nido:land` in its worktree, calling
   `on-line` with each line it prints as it prints it; returns its exit code once it has finished,
   zero when the change landed. The verb is the worktree's own, so the gate that runs is the one
   the change carries, rebased onto main's; its refusals are lines like any other."
  [project session on-line]
  (let [proc (p/process ["bb" "nido:land"] {:dir (worktree project session) :err :out :shutdown p/destroy-tree})]
    (with-open [r (io/reader (:out proc))]
      (doseq [line (line-seq r)] (on-line line)))
    (:exit @proc)))
