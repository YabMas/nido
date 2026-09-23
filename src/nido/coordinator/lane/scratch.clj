(ns nido.coordinator.lane.scratch
  "Which workstream a session a person started by hand belongs to: the one the start names, or
   else the one already holding the name, or else a loose ('scratch') one-off minted for it, so
   that *every* session belongs to a workstream (the universal-workstream model). `destroy` reaps
   a one-off when it never grew a ref or a ledger entry, keeping one-offs zero-ceremony.

   Naming the workstream is how a unit with no ticket to spawn from — a forked child above all —
   gets a session at all. The choice is made once: a session never moves afterwards.

   Lives coordinator-side (not in nido.session.*) to avoid a namespace cycle:
   the coordinator already depends on nido.session.lifecycle, so the wiring is
   done at the task layer (tasks.nido-session) — the only place that sees both."
  (:require
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.workstream :as workstream]))

(defn ^{:malli/schema [:=> [:cat :Workstream] :boolean]}
  scratch?
  "A one-off workstream: one carrying the `:scratch` stage `birth!` writes.

   MARKED, not inferred. This used to read ref-lessness — Notion and GitHub
   workstreams both carry refs, so \"no refs\" stood in for \"disposable\" — and
   that held exactly as long as nothing else minted a ref-less workstream. The
   described-intent leg does, and its workstream sits with no entries until the
   agent writes its `:intent`, which is precisely the window both reapers test
   for. Inferred disposability would have deleted a description whose session
   halted on a blocker before it could record what it was for.

   The marker was always the authored fact; the inference was the shortcut."
  [w]
  (= :scratch (:stage w)))

(defn- reconcile-weight!
  "Rewrite an owned session's `:weight` when it disagrees with what is actually
   provisioned. No-op when `weight` is nil (provisioning unknown — never
   overwrite a stored weight with a guess) or already correct."
  [project ws-id session-name weight]
  (when weight
    (when-let [s (session/read-session project ws-id session-name)]
      (when (not= weight (:weight s))
        (session/write! (assoc s :weight weight))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :SessionName :WorkstreamId] [:maybe :map]]}
  joinable
  "Why a start naming workstream `ws-id` may not put the session `session-name` on it, as
   `{:reason :no-such-workstream|:closed|:name-held …}`, or nil when it may. A name `ws-id`
   already holds is joinable — that is the same start run again."
  [project session-name ws-id]
  (let [w (workstream/read-ws project ws-id)]
    (cond
      (nil? w)     {:reason :no-such-workstream :ws-id ws-id}
      (:closed w)  {:reason :closed :ws-id ws-id}
      :else        (when-let [holder (session/workstream-id-for project session-name)]
                     (when (not= holder ws-id)
                       {:reason :name-held :ws-id ws-id :holder holder})))))

(defn- mint-one-off!
  "A fresh :scratch workstream holding a new human session named `session-name`. When another
   start takes the name first, create! refuses; the workstream minted for it is deleted and the
   holder answered instead, so a lost race leaves no empty one-off behind."
  [project session-name weight]
  (let [w (workstream/create! project {:stage :scratch :external-refs []})]
    (try
      (session/create! project (:id w) {:name session-name :weight (or weight :light) :autonomy nil})
      (:id w)
      (catch clojure.lang.ExceptionInfo e
        (if (= :name-held (:reason (ex-data e)))
          (do (workstream/delete! project (:id w))
              (:holder (ex-data e)))
          (throw e))))))

(defn ^{:malli/schema [:function
                       [:=> [:cat :ProjectName :SessionName [:maybe :keyword]] :any]
                       [:=> [:cat :ProjectName :SessionName [:maybe :keyword] [:maybe :WorkstreamId]] :any]]}
  birth!
  "Ensure a workstream owns a human session named `session-name`, carrying `weight` — the weight
   of what was actually provisioned for it (`lifecycle/session-weight`), or nil when that is
   unknown. Returns the ws-id.

   Given `ws-id`, the session goes on that workstream, and a start that cannot join it is refused
   with `:refused :join` and the reason `joinable` gives; nothing is written. Called that way
   BEFORE the session is provisioned, the record is the claim on the name: no other start can
   take it, and the orphan sweep finds the session owned.

   Without one: whichever workstream already holds the name keeps it, else a ref-less
   :scratch-stage one-off is minted for it. An unknown weight births :light, the conservative read.

   Idempotent either way, and it still reconciles a stale `:weight`, since this is the only point
   every path (manual up, TUI, the orphan sweep) re-runs against a live session."
  ([project session-name weight]
   (birth! project session-name weight nil))
  ([project session-name weight ws-id]
   (if ws-id
     (if-let [why (joinable project session-name ws-id)]
       (throw (ex-info (str "Cannot start " session-name " on " ws-id " — "
                            (case (:reason why)
                              :no-such-workstream "no such workstream"
                              :closed             "that workstream is closed"
                              :name-held          (str "the name already belongs to " (:holder why))))
                       (assoc why :refused :join :session session-name :project project)))
       (do (if (session/read-session project ws-id session-name)
             (reconcile-weight! project ws-id session-name weight)
             (session/create! project ws-id {:name session-name :weight (or weight :light) :autonomy nil}))
           ws-id))
     (if-let [held (session/workstream-id-for project session-name)]
       (do (reconcile-weight! project held session-name weight)
           held)
       (mint-one-off! project session-name weight)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :SessionName] :any]}
  reap!
  "Delete the one-off workstream owning `session-name` when it is safe to discard:
   it is MARKED `:scratch`, has acquired no external ref, carries no ledger entries,
   and owns no session other than this one. No-op otherwise. Idempotent. Returns nil.

   The marker and the ref are two facts, not one. The marker says it was born
   disposable; the absent ref says nothing outside has claimed it since. `scratch?`
   used to answer both at once by reading ref-lessness, which worked only while
   nothing but `birth!` minted a ref-less workstream — and hid that a one-off which
   later grows a ticket ref stops being discardable for a reason of its own."
  [project session-name]
  (when-let [ws-id (session/workstream-id-for project session-name)]
    (let [w      (workstream/read-ws project ws-id)
          others (->> (session/list-sessions project ws-id)
                      (remove #(= session-name (:name %))))]
      (when (and (scratch? w)
                 (empty? (:external-refs w))
                 (empty? (:entries w))
                 (empty? others))
        (workstream/delete! project ws-id))))
  nil)
