(ns tasks.nido-ticket
  "bb task entry points for the per-ticket triage record (the skill's interface)."
  (:require
   [clojure.pprint :as pprint]
   [nido.coordinator.tickets :as tickets]
   [nido.task-args :as task-args]))

(defn- project-kw [opts] (keyword (or (:project opts) "brian")))

(def ^:private raw-string-keys
  "Kwarg keys whose values must be passed through verbatim — URLs and titles
   may contain EDN-significant characters (e.g. `[Login] crash` parses as a
   vector without this guard)."
  #{:url :title})

(defn open-cmd
  "bb nido:ticket:open :project <p> :br BR-#### :page <id> :url <u> :title <t> :opened-by <kw>"
  [& args]
  (let [[_ o] (task-args/split-args args raw-string-keys)]
    (tickets/open! (project-kw o) (str (:br o))
                   {:notion-page-id (str (:page o))
                    :url (str (:url o))
                    :title (str (:title o))
                    :opened-by (some-> (:opened-by o) keyword)
                    :notion-last-edited-at (some-> (:edited o) str)})
    (println "opened" (:br o))))

(defn status-cmd
  "bb nido:ticket:status :project <p> :br BR-#### :status <kw>"
  [& args]
  (let [[_ o] (task-args/split-args args)]
    (tickets/set-status! (project-kw o) (str (:br o)) (keyword (:status o)))
    (println "status" (:br o) "->" (:status o))))

(defn complete-cmd
  "bb nido:ticket:complete :project <p> :br BR-#### :status <triaged|skipped> :disposition <kw>"
  [& args]
  (let [[_ o] (task-args/split-args args)]
    (tickets/complete! (project-kw o) (str (:br o))
                       (keyword (:status o)) (some-> (:disposition o) keyword))
    (println "completed" (:br o) (:status o))))

(defn append-cmd
  "bb nido:ticket:append :project <p> :br BR-#### :kind <kw> :session <s> :run-id <r> :file <path>
   Reads entry body from :file."
  [& args]
  (let [[_ o] (task-args/split-args args)
        path  (tickets/append-entry! (project-kw o) (str (:br o))
                                     {:kind (keyword (:kind o))
                                      :session (str (:session o))
                                      :run-id (str (:run-id o))}
                                     (slurp (str (:file o))))]
    (println "appended" path)))

(defn show-cmd
  "bb nido:ticket:show :project <p> :br BR-#### — pretty-print meta.edn."
  [& args]
  (let [[_ o] (task-args/split-args args)]
    (pprint/pprint (tickets/read-meta (project-kw o) (str (:br o))))))
