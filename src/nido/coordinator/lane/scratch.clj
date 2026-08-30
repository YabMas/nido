(ns nido.coordinator.lane.scratch
  "Loose ('scratch') workstreams: the home for one-off, manually-launched
   sessions that carry no external ref. A manual `bb nido:session:up` births a
   loose workstream + a human session here so that *every* session belongs to a
   workstream (the universal-workstream model). `destroy` reaps it when it never
   grew a ref or a ledger entry, keeping one-offs zero-ceremony.

   Lives coordinator-side (not in nido.session.*) to avoid a namespace cycle:
   the coordinator already depends on nido.session.lifecycle, so the wiring is
   done at the task layer (tasks.nido-session) — the only place that sees both."
  (:require
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.workstream :as workstream]))

(defn ^{:malli/schema [:=> [:cat :Workstream] :boolean]}
  scratch?
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

(defn- reconcile-weight!
  "Rewrite an owned session's `:weight` when it disagrees with what is actually
   provisioned. No-op when `weight` is nil (provisioning unknown — never
   overwrite a stored weight with a guess) or already correct."
  [project ws-id session-name weight]
  (when weight
    (when-let [s (session/read-session project ws-id session-name)]
      (when (not= weight (:weight s))
        (session/write! (assoc s :weight weight))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :SessionName :keyword] :any]}
  birth!
  "Ensure a loose workstream owns a human session named `session-name`, carrying
   `weight` — the weight of what was actually provisioned for it
   (`lifecycle/session-weight`), or nil when that is unknown.

   Idempotent: if any workstream already owns that session, returns its ws-id
   unchanged — but still reconciles a stale `:weight`, since this is the only
   point every path (manual up, TUI, the orphan sweep) re-runs against a live
   session. Otherwise mints a ref-less :scratch-stage workstream and a live human
   session (autonomy nil). An unknown weight births :light, the conservative
   read. Returns the ws-id."
  [project session-name weight]
  (if-let [ws-id (find-ws-for-session project session-name)]
    (do (reconcile-weight! project ws-id session-name weight)
        ws-id)
    (let [w (workstream/create! project {:stage :scratch :external-refs []})]
      (session/create! project (:id w)
                       {:name session-name :weight (or weight :light) :autonomy nil})
      (:id w))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :SessionName] :any]}
  reap!
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
