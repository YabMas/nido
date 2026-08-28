(ns nido.github.config
  "Per-project GitHub config at ~/.nido/projects/<project>/github.edn.
   Absent file ⇒ nil (the merge poller is off for that project)."
  (:require
   [babashka.fs :as fs]
   [malli.core :as m]
   [nido.platform.core :as core]
   [nido.platform.io :as io]))

(def Config
  [:map {:closed true}
   [:repo string?]                                 ; "owner/repo"
   [:poll {:optional true} string?]                ; "5m" (default applied by caller)
   [:on-merge {:optional true}
    [:map
     [:notion-status      {:optional true} string?]
     [:remove-ball-holder {:optional true} string?]]]
   [:issues {:optional true}
    [:map {:closed true}
     [:assignee {:optional true} string?]
     [:enabled  {:optional true} boolean?]]]])

(defn- config-path [project]
  (core/project-file project "github.edn"))

(defn load-config
  "Read + validate github.edn for a project. Returns the config map, or nil
   when the file is absent (feature off). Throws on a malformed file."
  [project]
  (let [path (config-path project)]
    (when (fs/exists? path)
      (let [c (io/read-edn path)]
        (when-not (m/validate Config c)
          (throw (ex-info (str "Malformed github.edn for project " project)
                          {:path path :errors (m/explain Config c)})))
        c))))
