(ns nido.vsdd.analyst
  "Analyzes completed VSDD runs for efficiency improvements.
   Collects run data (manifest + reports), passes to an LLM for pattern analysis."
  (:require [clojure.string :as str]
            [nido.core :as core]
            [nido.io :as io]
            [nido.vsdd.agent :as agent]
            [nido.vsdd.prompts :as prompts]))

;; ---------------------------------------------------------------------------
;; Data collection

(defn- module-slug [module-path]
  (-> module-path
      (str/replace #"/$" "")
      (str/split #"/")
      last))

(defn- load-report [run-dir module-path prefix iteration]
  (let [slug (module-slug module-path)
        path (str run-dir "/" slug "/" prefix "-" iteration ".edn")]
    (io/read-text path)))

(defn- collect-iteration-data
  "Collect all reports for a single iteration."
  [run-dir module-path iteration-record]
  (let [n (:iteration iteration-record)]
    (merge iteration-record
           {:critic-report-text (load-report run-dir module-path "critic-report" n)
            :impl-report-text   (load-report run-dir module-path "impl-report" n)})))

(defn collect-run-data
  "Collect all data for a VSDD run: manifest + all reports.
   Returns a map with :manifest and :iterations (enriched with report contents)."
  [project-dir run-id]
  (let [run-dir (str project-dir "/.vsdd/" run-id)
        manifest (io/read-edn (str run-dir "/manifest.edn"))]
    (when manifest
      {:manifest manifest
       :iterations (mapv #(collect-iteration-data run-dir (:module manifest) %)
                         (:iterations manifest))})))

;; ---------------------------------------------------------------------------
;; Prompt construction

(defn- format-iteration
  "Format a single iteration's data for the analysis prompt."
  [{:keys [iteration critic-report-text impl-report-text] :as iter}]
  (str "### Iteration " iteration "\n"
       "\n"
       "**Judge verdict:** " (some-> (get-in iter [:judge :verdict]) name) "\n"
       (when (:structural? (:judge iter))
         "**Structural:** yes\n")
       "\n"
       "**Critic report:**\n"
       "```edn\n"
       (or critic-report-text "(no report found)")
       "\n```\n"
       "\n"
       (when impl-report-text
         (str "**Implementer report:**\n"
              "```edn\n"
              impl-report-text
              "\n```\n"))
       "\n"))

(defn- build-analysis-prompt
  "Build the full analysis prompt with all run data."
  [{:keys [manifest iterations]}]
  (str "Analyze this VSDD run and identify opportunities to improve efficiency.\n"
       "\n"
       "## Run Metadata\n"
       "\n"
       "- **Run ID:** " (:run-id manifest) "\n"
       "- **Module:** " (:module manifest) "\n"
       "- **Status:** " (name (:status manifest)) "\n"
       "- **Total iterations:** " (count (:iterations manifest)) "\n"
       "- **Started:** " (:started-at manifest) "\n"
       "- **Finished:** " (:finished-at manifest) "\n"
       (when (:error manifest)
         (str "- **Error:** " (:error manifest) "\n"))
       "\n"
       "## Iteration Data\n"
       "\n"
       (str/join "\n---\n\n" (map format-iteration iterations))
       "\n"
       "## Agent System Prompts\n"
       "\n"
       "The following system prompts are currently used for each role.\n"
       "Include recommendations for improving them if applicable.\n"
       "\n"
       "### Critic System Prompt\n"
       "```markdown\n"
       (prompts/load-agent-prompt :critic)
       "\n```\n"
       "\n"
       "### Implementer System Prompt\n"
       "```markdown\n"
       (prompts/load-agent-prompt :implementer)
       "\n```\n"
       "\n"
       "### Architect System Prompt\n"
       "```markdown\n"
       (prompts/load-agent-prompt :architect)
       "\n```\n"))

;; ---------------------------------------------------------------------------
;; Public API

(defn analyze
  "Analyze a completed VSDD run and produce recommendations.

   Config:
     :project-dir — project root
     :run-id      — VSDD run to analyze
     :model       — model for analysis (default \"opus\")

   Returns {:exit int :result string :report-path string}."
  [{:keys [project-dir run-id model]}]
  (let [run-data (collect-run-data project-dir run-id)]
    (when-not run-data
      (throw (ex-info "Run not found" {:run-id run-id :project-dir project-dir})))

    (core/log-step "VSDD Analysis")
    (println (str "  Run ID:  " run-id))
    (println (str "  Module:  " (get-in run-data [:manifest :module])))
    (println (str "  Status:  " (name (get-in run-data [:manifest :status]))))
    (println (str "  Iterations: " (count (:iterations run-data))))
    (println)

    (let [sys-prompt (prompts/load-agent-prompt :analyst)
          task-prompt (build-analysis-prompt run-data)
          result (agent/invoke-agent
                  {:system-prompt sys-prompt
                   :allowed-tools []
                   :prompt        task-prompt
                   :working-dir   project-dir
                   :model         (or model "opus")
                   :display-name  "analyst"})
          report-dir (str project-dir "/.vsdd/" run-id)
          report-path (str report-dir "/analysis.md")]

      ;; Save the analysis report
      (io/write-text! report-path (:result result))

      (println)
      (core/log-step "Analysis Complete")
      (println (str "  Report: " report-path))

      (assoc result :report-path report-path))))
