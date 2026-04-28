(ns nido.vsdd.sweep
  "VSDD parallel sweep — run all dirty modules concurrently in jj workspaces,
   then rebase converged results onto the main branch."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [nido.core :as core]
            [nido.io :as io]
            [nido.vsdd.jj :as jj]
            [nido.vsdd.loop :as vsdd-loop]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- module-slug
  "Turn a module path into a short slug for workspace/directory naming."
  [module-path]
  (-> module-path
      (str/replace #"/$" "")
      (str/split #"/")
      last))

(defn- timestamp-id []
  (.format (java.time.LocalDateTime/now)
           (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")))

;; ---------------------------------------------------------------------------
;; Dirty detection

(defn module-needs-run?
  "Check if a module needs a VSDD run.
   Returns {:needs-run? bool :reason :changed|:no-prior-run|nil}."
  [project-dir module-path]
  (let [last-change (jj/last-converged-commit project-dir module-path)]
    (if-not last-change
      {:needs-run? true :reason :no-prior-run}
      (if (jj/files-changed-since? project-dir last-change module-path)
        {:needs-run? true :reason :changed}
        {:needs-run? false :reason nil}))))

(defn detect-dirty-modules
  "Returns dirty modules as [{:module-path ... :reason :changed|:no-prior-run}]."
  [project-dir modules]
  (->> modules
       (map (fn [m]
              (let [{:keys [needs-run? reason]} (module-needs-run? project-dir m)]
                (when needs-run?
                  {:module-path m :reason reason}))))
       (remove nil?)
       vec))

;; ---------------------------------------------------------------------------
;; Workspace management

(defn- workspaces-root [project-dir]
  (str project-dir "-workspaces"))

(defn create-workspace-for-module
  "Create a jj workspace for a module. Returns workspace info map."
  [project-dir module-path]
  (let [slug (module-slug module-path)
        ws-name (str "vsdd-" slug)
        ws-path (str (workspaces-root project-dir) "/" ws-name)
        result (jj/create-workspace project-dir ws-path ws-name)]
    (if (zero? (:exit result))
      {:workspace-name ws-name
       :workspace-path ws-path
       :module-path module-path}
      (throw (ex-info "Failed to create workspace"
                      {:module module-path :ws-name ws-name :error (:err result)})))))

(defn cleanup-workspaces!
  "Forget workspaces and remove their directories."
  [project-dir workspaces]
  (doseq [{:keys [workspace-name workspace-path]} workspaces]
    (let [forget-result (jj/forget-workspace project-dir workspace-name)]
      (when-not (zero? (:exit forget-result))
        (println (str "  Warning: failed to forget workspace " workspace-name
                      ": " (:err forget-result)))))
    (when (fs/exists? workspace-path)
      (fs/delete-tree workspace-path)))
  ;; Remove workspaces root if empty
  (let [root (workspaces-root project-dir)]
    (when (and (fs/exists? root)
               (empty? (fs/list-dir root)))
      (fs/delete-tree root))))

;; ---------------------------------------------------------------------------
;; Parallel execution

(defn run-module-in-workspace
  "Run VSDD for a single module in its workspace.
   Returns the manifest or {:status :error :error msg}."
  [base-config ws-info]
  (let [config (assoc base-config :working-dir (:workspace-path ws-info)
                                  :module-path (:module-path ws-info))]
    (try
      (vsdd-loop/run config)
      (catch Exception e
        {:status :error :error (ex-message e) :module-path (:module-path ws-info)}))))

(defn launch-parallel-runs
  "Launch VSDD runs in parallel. Returns {module-path future}."
  [base-config workspaces]
  (into {}
        (map (fn [ws]
               [(:module-path ws)
                (future (run-module-in-workspace base-config ws))]))
        workspaces))

(defn await-all-runs
  "Deref all futures. Returns {module-path result}."
  [futures-map]
  (into {}
        (map (fn [[module-path fut]]
               [module-path @fut]))
        futures-map))

;; ---------------------------------------------------------------------------
;; Rebase converged results

(defn rebase-converged!
  "Rebase converged workspace changes onto the main branch in module order.
   Returns [{:module-path :change-id}] for converged modules."
  [project-dir results workspaces module-order]
  (let [converged-modules (->> module-order
                                (filter (fn [m]
                                          (= :converged (:status (get results m)))))
                                vec)
        ws-by-module (into {} (map (fn [ws] [(:module-path ws) ws])) workspaces)]
    (when (seq converged-modules)
      ;; Abandon the empty default @ to get it out of the way
      (jj/abandon project-dir "@")
      ;; Rebase each converged module in order
      (let [rebased (loop [remaining converged-modules
                           acc []]
                      (if (empty? remaining)
                        acc
                        (let [module (first remaining)
                              ws-name (:workspace-name (get ws-by-module module))
                              ws-change (jj/workspace-change-id project-dir ws-name)
                              dest (if (empty? acc)
                                     (jj/current-change-id project-dir)
                                     (:change-id (last acc)))
                              _ (jj/rebase-revision project-dir ws-change dest)
                              new-change (jj/workspace-change-id project-dir ws-name)
                              desc (or (jj/get-description project-dir new-change) "")
                              trailer (str "\nvsdd-converged: " module)
                              new-desc (if (str/includes? desc trailer)
                                         desc
                                         (str (str/trimr desc) "\n" trailer))]
                          (jj/describe project-dir new-change new-desc)
                          (recur (rest remaining)
                                 (conj acc {:module-path module
                                            :change-id new-change})))))]
        ;; Create new working copy on top of last rebased change
        (when (seq rebased)
          (jj/new-on-top project-dir (:change-id (last rebased))))
        rebased))))

;; ---------------------------------------------------------------------------
;; Artifact preservation

(defn preserve-artifacts!
  "Copy .vsdd/<run-id>/ from each workspace into the sweep artifacts dir."
  [project-dir sweep-id workspaces results]
  (let [sweep-dir (str project-dir "/.vsdd/sweeps/" sweep-id)]
    (doseq [{:keys [module-path workspace-path]} workspaces]
      (let [result (get results module-path)
            run-id (:run-id result)
            slug (module-slug module-path)
            source (str workspace-path "/.vsdd/" run-id)
            dest (str sweep-dir "/" slug)]
        (when (and run-id (fs/exists? source))
          (fs/create-dirs dest)
          (fs/copy-tree source dest))))))

;; ---------------------------------------------------------------------------
;; Sweep manifest

(defn- make-sweep-manifest [sweep-id started-at results module-order]
  (let [module-entries (mapv (fn [m]
                               (let [r (get results m)]
                                 {:module-path m
                                  :outcome (case (:status r)
                                             :converged :converged
                                             :escalated :escalated
                                             :error :error
                                             :skipped)
                                  :run-id (:run-id r)
                                  :change-id nil})) ;; filled in after rebase
                             module-order)
        all-converged? (every? #(= :converged (:outcome %)) module-entries)]
    {:sweep-id sweep-id
     :started-at started-at
     :finished-at (core/now-iso)
     :status (if all-converged? :completed :partial)
     :modules module-entries}))

(defn- update-manifest-change-ids [manifest rebased-info]
  (let [change-map (into {} (map (juxt :module-path :change-id)) rebased-info)]
    (update manifest :modules
            (fn [mods]
              (mapv (fn [m]
                      (if-let [cid (get change-map (:module-path m))]
                        (assoc m :change-id cid)
                        m))
                    mods)))))

;; ---------------------------------------------------------------------------
;; Top-level entry point

(defn sweep
  "Run VSDD across all dirty modules in parallel.

   Config keys:
     :working-dir — project root (required)
     :modules     — list of module paths to sweep (required, from vsdd.edn)
     Plus all standard VSDD config keys (roles, judge, max-iterations, etc.)"
  [config]
  (let [project-dir (:working-dir config)
        modules (:modules config)
        sweep-id (timestamp-id)
        started-at (core/now-iso)]

    (when (empty? modules)
      (throw (ex-info "No :modules defined in vsdd.edn config"
                      {:config-keys (keys config)})))

    (core/log-step "VSDD Sweep")
    (println (str "  Sweep ID: " sweep-id))
    (println (str "  Modules:  " (count modules)))

    ;; 1. Detect dirty modules
    (let [dirty (detect-dirty-modules project-dir modules)
          clean-count (- (count modules) (count dirty))]

      (println)
      (when (pos? clean-count)
        (println (str "  " clean-count " module(s) up to date — skipping")))

      (doseq [{:keys [module-path reason]} dirty]
        (println (str "  " module-path " — "
                      (case reason
                        :no-prior-run "no prior converged run"
                        :changed "files changed since last convergence"))))

      (if (empty? dirty)
        (do (println "  All modules converged — nothing to do.")
            {:sweep-id sweep-id :status :completed :modules []})

        ;; 2. Create workspaces
        (let [workspaces (reduce (fn [acc {:keys [module-path]}]
                                   (try
                                     (conj acc (create-workspace-for-module project-dir module-path))
                                     (catch Exception e
                                       (println (str "  Warning: skipping " module-path
                                                     " — " (ex-message e)))
                                       acc)))
                                 []
                                 dirty)
              dirty-module-paths (mapv :module-path workspaces)
              ;; Base config without working-dir/module-path (those come per-workspace)
              base-config (dissoc config :working-dir :module-path :modules)]

          (println)
          (println (str "  Created " (count workspaces) " workspace(s)"))

          (try
            ;; 3. Launch parallel runs
            (let [futures (launch-parallel-runs base-config workspaces)
                  ;; 4. Await all
                  results (await-all-runs futures)
                  ;; Merge skipped modules into results
                  all-results (merge
                                (into {} (map (fn [m] [m {:status :skipped}]))
                                      (remove (set dirty-module-paths) modules))
                                results)

                  ;; 5. Print intermediate summary
                  _ (do (println)
                        (core/log-step "Module Results")
                        (doseq [m modules]
                          (let [r (get all-results m)]
                            (println (str "  " m " — "
                                          (case (:status r)
                                            :converged "CONVERGED"
                                            :escalated "ESCALATED"
                                            :error (str "ERROR: " (:error r))
                                            :skipped "skipped (clean)"
                                            (str (:status r))))))))

                  ;; 6. Rebase converged results
                  rebased (rebase-converged! project-dir all-results workspaces modules)

                  ;; 7. Preserve artifacts
                  _ (preserve-artifacts! project-dir sweep-id workspaces all-results)

                  ;; 8. Build and save sweep manifest
                  manifest (-> (make-sweep-manifest sweep-id started-at all-results modules)
                               (update-manifest-change-ids rebased))
                  manifest-path (str project-dir "/.vsdd/sweeps/" sweep-id "/sweep-manifest.edn")]

              (io/write-edn! manifest-path manifest)

              ;; 9. Cleanup
              (cleanup-workspaces! project-dir workspaces)

              ;; 10. Final summary
              (println)
              (core/log-step "Sweep Complete")
              (println (str "  Status:    " (name (:status manifest))))
              (println (str "  Artifacts: .vsdd/sweeps/" sweep-id "/"))
              (when (seq rebased)
                (println (str "  Rebased:   " (count rebased) " module(s) onto main branch")))

              manifest)

            (catch Exception e
              ;; On error, still try to clean up workspaces
              (println (str "\n  Sweep error: " (ex-message e)))
              (println "  Cleaning up workspaces...")
              (try
                (cleanup-workspaces! project-dir workspaces)
                (catch Exception cleanup-err
                  (println (str "  Warning: cleanup failed: " (ex-message cleanup-err)))))
              (throw e))))))))
