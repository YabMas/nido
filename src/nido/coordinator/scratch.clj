(ns nido.coordinator.scratch
  "Loose ('scratch') workstreams: the home for one-off, manually-launched
   sessions that carry no external ref. A manual `bb nido:session:up` births a
   loose workstream + a human session here so that *every* session belongs to a
   workstream (the universal-workstream model). `destroy` reaps it when it never
   grew a ref or a ledger entry, keeping one-offs zero-ceremony.

   Lives coordinator-side (not in nido.session.*) to avoid a namespace cycle:
   the coordinator already depends on nido.session.lifecycle, so the wiring is
   done at the task layer (tasks.nido-session) — the only place that sees both."
  (:require
   [nido.coordinator.session :as session]
   [nido.coordinator.workstream :as workstream]))

(defn scratch?
  "A loose workstream: one carrying no external refs. Source-agnostic — Notion
   and GitHub workstreams both carry refs, so 'no refs' uniquely marks a one-off."
  [w]
  (empty? (:external-refs w)))

(defn- find-ws-for-session
  "ws-id of the workstream whose sessions include `session-name`, or nil. Scans
   the project's workstreams (same shape as workstream/find-by-ref)."
  [project session-name]
  (->> (workstream/list-ids project)
       (some (fn [ws-id]
               (when (some #(= session-name (:name %))
                           (session/list-sessions project ws-id))
                 ws-id)))))

(defn birth!
  "Ensure a loose workstream owns a human session named `session-name`.
   Idempotent: if any workstream already owns that session, returns its ws-id
   unchanged. Otherwise mints a ref-less :scratch-stage workstream and a live
   human session (autonomy nil). Returns the ws-id."
  [project session-name]
  (or (find-ws-for-session project session-name)
      (let [w (workstream/create! project {:stage :scratch :external-refs []})]
        (session/create! project (:id w)
                         {:name session-name :weight :light :autonomy nil})
        (:id w))))

(defn reap!
  "Delete the loose workstream owning `session-name` when it is safe to discard:
   it is scratch (no refs), carries no ledger entries, and owns no session other
   than this one. No-op when absent or not reapable (it grew a ref/entry, or
   another session shares it). Idempotent. Returns nil."
  [project session-name]
  (when-let [ws-id (find-ws-for-session project session-name)]
    (let [w      (workstream/read-ws project ws-id)
          others (->> (session/list-sessions project ws-id)
                      (remove #(= session-name (:name %))))]
      (when (and (scratch? w)
                 (empty? (:entries w))
                 (empty? others))
        (workstream/delete! project ws-id))))
  nil)
