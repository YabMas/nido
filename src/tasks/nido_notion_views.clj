(ns tasks.nido-notion-views
  "Bb task entry points for Notion view registry validation."
  (:require
   [nido.notion.client :as client]
   [nido.notion.views-check :as check]
   [nido.task-args :as task-args]))

(defn check-cmd
  "bb nido:notion:views:check :project <p> — validate the registry against the live DB."
  [& args]
  (let [[_ opts]   (task-args/split-args args)
        project    (:project opts)]
    (when-not project
      (println "ERROR: :project required.")
      (System/exit 2))
    (let [token (client/keychain-token)]
      (when-not token
        (println "ERROR: no Notion token in keychain. Run bb nido:notion:auth:set first.")
        (System/exit 2))
      (let [project-kw (if (string? project) (keyword project) project)
            {:keys [status errors]} (check/check-registry project-kw token)]
        (case status
          :ok    (println "Registry check passed.")
          :error (do (println "Registry check FAILED:")
                     (doseq [e errors] (println "  -" (:message e)))
                     (System/exit 1)))))))
