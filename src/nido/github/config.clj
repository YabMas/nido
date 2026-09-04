(ns nido.github.config
  "Per-project GitHub config at ~/.nido/projects/<project>/github.edn.
   Absent file ⇒ nil (the merge poller is off for that project).

   Two consumers, and they read it differently. The merge poller reads the
   validated map through `load-config`. The publication skills
   (/prepare-draft-pr, /squash, /land) read the FILE, because an agent following
   a markdown skill has no Clojure runtime in hand — which is why keys those
   skills need still belong in this schema: the map is closed and `load-config`
   throws, so a key nothing here declares takes the poller down for the project
   the moment someone adds it."
  (:require
   [babashka.fs :as fs]
   [malli.core :as m]
   [nido.platform.core :as core]
   [nido.platform.io :as io]))

(def Config
  [:map {:closed true}
   [:repo string?]                                 ; "owner/repo"
   [:poll {:optional true} string?]                ; "5m" (default applied by caller)
   [:base {:optional true} string?]                ; landing branch; "main" by default
   [:on-merge {:optional true}
    [:map
     [:notion-status      {:optional true} string?]
     [:remove-ball-holder {:optional true} string?]]]
   [:issues {:optional true}
    [:map {:closed true}
     [:assignee {:optional true} string?]
     [:enabled  {:optional true} boolean?]]]
   ;; The delivery-claim shape this project's OWN automation honours in a PR
   ;; body — read by the publication skills, never by anything here. Absent
   ;; (every project but brian) means those skills write no claim line at all,
   ;; which is why it carries no defaults: a guessed prefix produces a line that
   ;; reads as a promise and that nothing keeps.
   ;;
   ;; :prefix also decides what is EXCLUDED. nido's cross-project follow-up DB
   ;; issues FU-# ids through the same :notion adapter as a project ticket, and
   ;; brian's parser matches only BR-; requiring the ref to carry this prefix is
   ;; what keeps a follow-up from being claimed on a brian PR.
   [:delivery-claim {:optional true}
    [:map {:closed true}
     [:prefix string?]                             ; "BR-" — ticket ids that may be claimed
     [:verb   string?]]]])                         ; "Closes" — the closing verb that project reads

(defn- config-path [project]
  (core/project-file project "github.edn"))

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:maybe :map]]}
  load-config
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
