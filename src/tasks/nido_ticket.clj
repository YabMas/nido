(ns tasks.nido-ticket
  "bb task entry points for the per-ticket triage record (the skill's interface)."
  (:require
   [clojure.pprint :as pprint]
   [nido.coordinator.lane.facets :as facets]
   [nido.coordinator.lane.promote :as promote]
   [nido.coordinator.report :as report]
   [nido.coordinator.record.tickets :as tickets]
   [nido.coordinator.view.tickets :as tickets-view]
   [nido.coordinator.record.workstream :as workstream]
   [nido.platform.task-args :as task-args]
   [nido.coordinator.work :as work]))

(defn- project-kw [opts] (keyword (or (:project opts) "brian")))

(defn exit!
  "Redefable wrapper around System/exit (matches the exit-test convention used
   elsewhere in tasks.* — see tasks.nido-transcribe / tasks.nido-notion-preprocess)
   so tests can capture the exit code without killing the test JVM."
  [code]
  (System/exit code))

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
  "bb nido:ticket:complete :project <p> :br BR-#### :status triaged :disposition <kw>
   (Off-radar tickets use nido:ticket:dismiss — :skipped is retired.)
   Refreshes the workstream's classification facets from Notion right after the
   verdict lands — triage is where a ticket gets its right Type / App Domain."
  [& args]
  (let [[_ o] (task-args/split-args args)
        project (project-kw o)
        br      (str (:br o))]
    (tickets/complete! project br
                       (keyword (:status o)) (some-> (:disposition o) keyword))
    (facets/refresh-for-ticket! project br)
    (println "completed" br (:status o))))

(defn dismiss-cmd
  "bb nido:ticket:dismiss :project <p> :br BR-#### (or positional BR-####)
   Take a ticket off the triage radar (status :dismissed). Skipped by
   auto-re-triage; creates the record if the ticket was never triaged."
  [& args]
  (let [[pos o] (task-args/split-args args)
        br      (str (or (:br o) (first pos)))]
    (tickets/dismiss! (project-kw o) br)
    (println "dismissed" br)))

(defn append-cmd
  "bb nido:ticket:append :project <p> :br BR-#### :kind <kw> :session <s> :run-id <r> :file <path>
   Reads entry body from :file. A :triage body must be valid TriageReport EDN —
   a malformed report is rejected (non-zero exit + explain) so the skill retries.
   Writes land on the workstream's ledger (the one canonical store), found-or-
   created by the BR-#### ref."
  [& args]
  (let [[_ o] (task-args/split-args args)]
    (try
      (let [path (workstream/append-to-ref! (project-kw o) (str (:br o))
                                            {:kind (keyword (:kind o))
                                             :session (str (:session o))
                                             :run-id (str (:run-id o))}
                                            (slurp (str (:file o))))]
        (println "appended" path))
      (catch Exception e
        (binding [*out* *err*]
          (println "append rejected:" (ex-message e))
          (when-let [ex (:explain (ex-data e))] (println (pr-str ex))))
        (System/exit 1)))))

(defn report-cmd
  "bb nido:ticket:report :project <p> :br <key> — print the latest triage report as
   markdown (the skill prints this into chat and reads :notion-writes from it)."
  [& args]
  (let [[_ o] (task-args/split-args args)]
    (println (report/report->markdown
              (tickets/latest-triage-report (project-kw o) (str (:br o)))))))

(defn show-cmd
  "bb nido:ticket:show :project <p> :br BR-#### — pretty-print meta.edn."
  [& args]
  (let [[_ o] (task-args/split-args args)]
    (pprint/pprint (tickets/read-meta (project-kw o) (str (:br o))))))

(defn list-cmd
  "bb nido:tickets:list [:status <kw>]
   List ticket records grouped by lifecycle stage. The 'Ready to implement'
   group (:triaged) is what you promote next. Optional :status narrows to one."
  [& args]
  (let [[_ o] (task-args/split-args args)
        all   (cond->> (tickets-view/read-all-tickets)
                (:status o) (filter #(= (keyword (str (:status o))) (:status %))))
        g     (tickets-view/grouped-tickets all)]
    (doseq [[label k] [["Ready to implement" :ready]
                       ["In progress"        :in-progress]
                       ["Dismissed"          :dismissed]]
            :let  [ts (get g k)]
            :when (seq ts)]
      (println (str "── " label " (" (count ts) ") ──"))
      (doseq [t ts] (println "  " (tickets-view/format-row t)))
      (println))))

(defn apply-cmd
  "bb nido:ticket:apply :project <p> :br BR-#### — execute a parked triage's verdict via
   nido (work/apply!): reads the typed report from the ledger and writes Notion (Ball
   Holder + App Domain, deep properties + callout), then completes the record. Prints the
   decision; exits non-zero on :notion-failed or an unknown BR so the caller can retry."
  [& args]
  (let [[_ o] (task-args/split-args args)
        project (project-kw o)
        br      (str (:br o))]
    (if-let [w (workstream/find-by-ref-id project br)]
      (let [{:keys [decision error callout]} (work/apply! project (:id w))]
        (println "apply" br "->" (name decision) (when callout (str "(callout " (name callout) ")")))
        (when (= :notion-failed decision)
          (binding [*out* *err*] (println "Notion write failed:" (some-> error name)))
          (exit! 1)))
      (do (binding [*out* *err*] (println "no workstream for" br))
          (exit! 1)))))

(defn promote-cmd
  "bb nido:ticket:promote :project <p> :br BR-#### (or positional BR-####)
   Gate a triaged ticket → enqueue a :plan-bug planning Run. Exits non-zero
   (and enqueues nothing) when the ticket isn't promotable."
  [& args]
  (let [[pos o] (task-args/split-args args)
        br      (str (or (:br o) (first pos)))
        res     (promote/promote! (project-kw o) br)]
    (case (:decision res)
      :promote (println "promoted" br "→ queued" (:queued res))
      (do (println "refused" br "—" (name (:decision res)))
          (System/exit 3)))))
