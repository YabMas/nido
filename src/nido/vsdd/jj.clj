(ns nido.vsdd.jj
  "jj VCS integration for VSDD sweep.
   All jj shell interactions in one place."
  (:require [babashka.process :refer [shell]]
            [clojure.string :as str]))

(defn jj!
  "Run a jj command in the given directory. Returns {:exit :out :err}."
  [dir & args]
  (let [result (apply shell {:dir dir :continue true :out :string :err :string}
                      "jj" args)]
    {:exit (:exit result)
     :out (str/trim (:out result))
     :err (str/trim (:err result))}))

(defn current-change-id
  "Get the change_id of @ in the given directory."
  [dir]
  (let [{:keys [exit out]} (jj! dir "log" "-r" "@" "-T" "change_id" "--no-graph")]
    (when (zero? exit) out)))

(defn workspace-change-id
  "Get the change_id of a workspace's @ from the main repo."
  [dir ws-name]
  (let [{:keys [exit out]} (jj! dir "log" "-r" (str ws-name "@") "-T" "change_id" "--no-graph")]
    (when (zero? exit) out)))

(defn create-workspace
  "Create a jj workspace. Returns {:exit :out :err}."
  [dir ws-path ws-name]
  (jj! dir "workspace" "add" ws-path "--name" ws-name))

(defn forget-workspace
  "Forget a jj workspace."
  [dir ws-name]
  (jj! dir "workspace" "forget" ws-name))

(defn last-converged-commit
  "Find the most recent commit with a vsdd-converged trailer for the given module.
   Returns change_id or nil."
  [dir module]
  (let [pattern (str "*vsdd-converged: " module "*")
        {:keys [exit out]} (jj! dir "log" "-r" (str "description(glob:" (pr-str pattern) ")")
                                "-T" "change_id" "--no-graph" "--limit" "1")]
    (when (and (zero? exit) (not (str/blank? out)))
      (first (str/split-lines out)))))

(defn files-changed-since?
  "Check if any files under module-path changed since the given change_id."
  [dir change-id module-path]
  (let [{:keys [exit out]} (jj! dir "diff" "--stat" "--from" change-id
                                "--" module-path)]
    (and (zero? exit) (not (str/blank? out)))))

(defn rebase-revision
  "Rebase a single revision onto a destination."
  [dir source-change dest-change]
  (jj! dir "rebase" "-r" source-change "--destination" dest-change))

(defn describe
  "Set the description of a revision."
  [dir change-id message]
  (jj! dir "desc" "-r" change-id "-m" message))

(defn get-description
  "Get the description of a revision."
  [dir change-id]
  (let [{:keys [exit out]} (jj! dir "log" "-r" change-id "-T" "description" "--no-graph")]
    (when (zero? exit) out)))

(defn new-on-top
  "Create a new empty working copy on top of the given revision."
  [dir change-id]
  (jj! dir "new" change-id))

(defn abandon
  "Abandon a revision."
  [dir change-id]
  (jj! dir "abandon" change-id))
