(ns nido.session.profiles
  "Per-project session profile registry. Loads
   ~/.nido/projects/<project>/session-profiles.edn (if present) and
   resolves a profile keyword to a concrete shape:

     {:services <:all | [<service-type-kw>...]>
      :worktree {:strategy <:git-worktree | :symlink>
                 :target   <abs-path>     ; symlink only
                 }}

   The default registry (used when no file exists) defines only :full
   with :services :all and :strategy :git-worktree — preserving the
   pre-profiles behavior."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.io :as io]
   [nido.coordinator.state :as cstate]))

(def builtin-registry
  {:profiles
   {:full {:services :all
           :worktree {:strategy :git-worktree}}}})

(defn- registry-path [project]
  (str (fs/path (cstate/nido-root) "projects" (name project) "session-profiles.edn")))

(defn- expand-tilde [s]
  (if (and (string? s) (str/starts-with? s "~/"))
    (str (fs/path (System/getProperty "user.home") (subs s 2)))
    s))

(defn- normalise-profile [p]
  (cond-> p
    (-> p :worktree :target) (update-in [:worktree :target] expand-tilde)))

(defn load-registry [project]
  (let [path (registry-path project)]
    (if (fs/exists? path)
      (io/read-edn path)
      builtin-registry)))

(defn resolve-profile
  "Resolve a profile keyword (e.g. :full, :lite) for a project."
  [project profile-kw]
  (let [{:keys [profiles]} (load-registry project)]
    (or (some-> (get profiles profile-kw) normalise-profile)
        (throw (ex-info (str "Unknown session profile " profile-kw " for project " project)
                        {:project project :profile profile-kw
                         :known (keys profiles)})))))
