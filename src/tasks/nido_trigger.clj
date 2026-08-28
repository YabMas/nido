(ns tasks.nido-trigger
  "Bb task entry points for firing manual triggers and listing trigger
   configs.

   Conventions: kwargs are `:keyword value`. For trigger:fire, the trigger
   name is a positional; payload fields are kwargs minus the reserved
   :project key."
  (:require
   [nido.coordinator.breakers :as breakers]
   [nido.coordinator.queue :as queue]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.triggers :as triggers]
   [nido.platform.task-args :as task-args]))

(defn fire
  "bb nido:trigger:fire :project <p> <trigger-name> :url <v> :ticket-id <v> ..."
  [& args]
  (let [[positionals opts] (task-args/split-args args)
        project-kw         (some-> (:project opts) str keyword)
        t-name             (some-> (first positionals) str keyword)
        payload            (dissoc opts :project)]
    (when-not project-kw (println "Missing :project") (System/exit 2))
    (when-not t-name     (println "Missing trigger name") (System/exit 2))
    (let [ts (triggers/load-for-project project-kw)]
      (when-not (triggers/find-by-name ts t-name)
        (println "No trigger" t-name "for project" project-kw) (System/exit 3))
      (cstate/ensure-dirs!)
      (let [path (queue/enqueue!
                   {:target  {:project project-kw :trigger t-name}
                    :payload payload})]
        (println "queued" path)))))

(defn list-triggers
  "bb nido:trigger:list :project <p>"
  [& args]
  (let [[_ opts]   (task-args/split-args args)
        project-kw (some-> (:project opts) str keyword)]
    (when-not project-kw (println "Missing :project") (System/exit 2))
    (let [ts (triggers/load-for-project project-kw)]
      (if (empty? ts)
        (println "No triggers for project" project-kw)
        (doseq [t ts]
          (println (format "%-30s source=%s skill=%s%s"
                           (name (:name t))
                           (name (-> t :source :type))
                           (name (:skill t))
                           (if (:dry-run? t) " (dry-run)" ""))))))))

(defn enable
  "bb nido:trigger:enable :project <p> <trigger-name>"
  [& args]
  (let [[positionals opts] (task-args/split-args args)
        project-kw         (some-> (:project opts) str keyword)
        t-name             (some-> (first positionals) str keyword)]
    (when-not project-kw (println "Missing :project") (System/exit 2))
    (when-not t-name     (println "Missing trigger name") (System/exit 2))
    (breakers/enable! project-kw t-name)
    (println "Trigger enabled:" (name project-kw) "/" (name t-name))))

(defn disable
  "bb nido:trigger:disable :project <p> <trigger-name> [:note <str>]"
  [& args]
  (let [[positionals opts] (task-args/split-args args)
        project-kw         (some-> (:project opts) str keyword)
        t-name             (some-> (first positionals) str keyword)
        note               (some-> (:note opts) str)]
    (when-not project-kw (println "Missing :project") (System/exit 2))
    (when-not t-name     (println "Missing trigger name") (System/exit 2))
    (breakers/disable-by-user! project-kw t-name (or note ""))
    (println "Trigger disabled:" (name project-kw) "/" (name t-name)
             (if note (str "(note: " note ")") ""))))
