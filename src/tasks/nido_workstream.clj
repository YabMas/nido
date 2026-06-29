(ns tasks.nido-workstream
  "bb-task entry points for the workstream ledger: append an entry, advance the
   stage, close a workstream. Resolves the target workstream by id or by Notion
   external ref (BR-####). Used by the triage skill's dual-write and by humans."
  (:require
   [nido.coordinator.workstream :as ws]
   [nido.task-args :as task-args]
   [nido.work :as work]))

(defn- resolve-ws-id
  "Workstream id from opts: explicit :ws-id, or :ref resolved via find-by-ref
   (:notion adapter). Throws when neither resolves."
  [{:keys [project ws-id ref]}]
  (let [p (keyword project)]
    (or ws-id
        (some-> (and ref (ws/find-by-ref p :notion ref)) :id)
        (throw (ex-info "Cannot resolve workstream — pass :ws-id or a known :ref"
                        {:project project :ref ref})))))

(defn entry-add* [{:keys [project kind content] :as opts}]
  (ws/append-entry! (keyword project) (resolve-ws-id opts)
                    {:kind (keyword (or kind "note"))} (or content "")))

(defn stage-advance* [{:keys [project stage] :as opts}]
  (work/set-stage! (keyword project) (resolve-ws-id opts) (keyword stage)))

(defn close* [{:keys [project outcome] :as opts}]
  (ws/close! (keyword project) (resolve-ws-id opts) (keyword (or outcome "done"))))

(defn ref-add* [{:keys [project adapter id url title] :as opts}]
  (ws/add-ref! (keyword project) (resolve-ws-id opts)
               (cond-> {:adapter (keyword adapter) :id id}
                 url   (assoc :url url)
                 title (assoc :title title))))

(defn- run* [f args]
  (let [[_ opts] (task-args/split-args args)]
    (f opts)
    (println "ok")))

(defn entry-add     [& args] (run* entry-add* args))
(defn stage-advance [& args] (run* stage-advance* args))
(defn close-cmd     [& args] (run* close* args))
(defn ref-add       [& args] (run* ref-add* args))
