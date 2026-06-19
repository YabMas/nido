(ns nido.coordinator.migrate-dispositions
  "One-shot, idempotent migration: rewrite legacy :skipped ticket dispositions to
   :dismissed. The :skipped status was retired — a ticket is either on the radar
   (→ :triaged) or off it (→ :dismissed), nothing between. A leftover :skipped
   would otherwise fall through derive-stage to :triage and reappear in the queue.

   br-id is taken from the ticket directory name (robust even when a legacy
   meta.edn lacks the :br-id field)."
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.tickets :as tickets]))

(defn- ticket-coords
  "[[project-kw br-id] …] for every ticket directory under ~/.nido/projects/*/tickets/*."
  []
  (let [pdir (fs/path (cstate/nido-root) "projects")]
    (when (fs/exists? pdir)
      (vec
       (for [proj  (fs/list-dir pdir)
             :when (fs/directory? proj)
             :let  [tdir (fs/path proj "tickets")]
             :when (fs/exists? tdir)
             tk    (fs/list-dir tdir)
             :when (fs/directory? tk)]
         [(keyword (str (fs/file-name proj))) (str (fs/file-name tk))])))))

(defn- migrate-coords!
  "Rewrite :skipped → :dismissed for the given [project br-id] pairs. Count migrated."
  [coords]
  (reduce (fn [n [project br-id]]
            (if (= :skipped (tickets/status project br-id))
              (do (tickets/set-status! project br-id :dismissed) (inc n))
              n))
          0 coords))

(defn run-project!
  "Migrate one project's ticket dispositions :skipped → :dismissed. Count migrated.
   Idempotent."
  [project]
  (migrate-coords! (filter #(= project (first %)) (ticket-coords))))

(defn run-all!
  "Migrate ticket dispositions :skipped → :dismissed across every project.
   Count migrated. Idempotent."
  []
  (migrate-coords! (ticket-coords)))
