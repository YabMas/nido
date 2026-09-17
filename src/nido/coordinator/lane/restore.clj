(ns nido.coordinator.lane.restore
  "Bring back what a failed session start stopped: the session, and whatever was
   waiting on it.

   The session is started HERE, in the process running the restore, and the
   daemon is handed — by envelope — only what continues once it is up: a Run to
   execute, or a reply's turn to replay. That split is the point. The daemon loads
   nido's code once; when the failure was a defect a recovery has just landed a fix
   for, a bring-up the daemon performed would run the code that failed. A
   continuation reaches a session that is already up, so it never runs the start
   path.

   A :failed Run stays failed. What it was doing continues as a new Run of its
   trigger and payload on its workstream; a Run that parked instead of failing is
   executed again as itself."
  (:require
   [clojure.string :as str]
   [nido.coordinator.lane.spawn :as spawn]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.triggers :as triggers]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.source.queue :as queue]
   [nido.coordinator.source.start-failures :as start-failures]
   [nido.session.failure :as failure]
   [nido.session.lifecycle :as lifecycle]))

(defn- closed-after?
  "Whether workstream `w` was closed after the moment `at`."
  [w at]
  (when-let [closed-at (-> w :closed :at)]
    (pos? (compare (java.time.Instant/parse closed-at) (java.time.Instant/parse at)))))

(defn ^{:malli/schema [:=> [:cat :map :map] :map]}
  continuation
  "What continues failure `f` once its session is up, given `facts` read for it:
   :run (the Run its origin names) and :ws (the workstream its origin names).

     {:kind :none}                       a person's start; bringing it up is all
     {:kind :execute :run-id id}         a Run that parked on its failed start
     {:kind :successor :run run}         a Run that failed on it
     {:kind :resume-turn …}              a reply whose turn could not start
     {:kind :withdrawn}                  the origin's workstream closed after the
                                         failure; there is nothing left to continue

   Withdrawn only for a closed workstream — never because the session is gone,
   which the teardown of every failed Run makes true."
  [f {:keys [run ws]}]
  (let [{:keys [kind] :as origin} (:origin f)]
    (cond
      (and (#{:run :resume} kind) ws (closed-after? ws (:at f)))
      {:kind :withdrawn}

      (= :resume kind)
      {:kind :resume-turn :project (:project origin) :ws-id (:ws-id origin) :input (:input origin)}

      (and (= :run kind) run (= :awaiting-review (:state run)))
      {:kind :execute :run-id (:id run)}

      (and (= :run kind) run (= :failed (:state run)))
      {:kind :successor :run run}

      :else
      {:kind :none})))

(defn- facts-for [f]
  (let [{:keys [kind run-id project ws-id session]} (:origin f)
        run (case kind
              :run    (when run-id (runs/read-run run-id))
              :resume (when (and project ws-id session)
                        (runs/find-for-session (keyword (name project)) ws-id session))
              nil)
        ws-id (or ws-id (:workstream-id run))
        p     (or (some-> project name keyword) (:project run))]
    {:run run
     :ws  (when (and p ws-id) (ws/read-ws p ws-id))}))

(defn- successor!
  "A new Run of `run`'s trigger and payload on `run`'s own workstream, its session
   brought up here. The trigger is read as it is declared now."
  [run origin]
  (let [trigger (triggers/find-by-name (triggers/load-for-project (:project run)) (:trigger run))
        _       (when-not trigger
                  (throw (ex-info (str "trigger " (name (:trigger run)) " is no longer declared for "
                                       (name (:project run)))
                                  {:reason :trigger-gone})))
        routed  {:project         (:project run)
                 :trigger         trigger
                 :payload         (:event-payload run)
                 :priority        (or (:priority run) 0)
                 :session-profile (:session-profile run)
                 :uncapped?       (boolean (:uncapped? run))
                 :workstream-id   (:workstream-id run)}
        next    (runs/create-run! routed {:fired-at (clock/now-iso) :fired-by "restore"})]
    (spawn/create-session-for-run! next (:workstream-id run))
    (try
      (runs/spawn-session-for-run! next origin)
      next
      (catch Throwable t
        (runs/transition! (:id next) :failed)
        (runs/write-run! (assoc (runs/read-run (:id next)) :error
                                (cond-> {:reason :spawn-failed :detail (ex-message t)}
                                  (:session-failure/id (ex-data t))
                                  (assoc :failure-id (:session-failure/id (ex-data t))))))
        (runs/teardown-session-for-run! next)
        (throw t)))))

(defn- restore-one!
  "Bring failure `f` back and hand on its continuation. Returns its outcome."
  [f]
  (let [facts  (facts-for f)
        cont   (continuation f facts)
        origin (assoc (:origin f) :restoring (:id f))]
    (if (= :withdrawn (:kind cont))
      {:failure (:id f) :outcome :withdrawn}
      (try
        (let [handed
              (case (:kind cont)
                :none
                (do (lifecycle/up! (:session f) (assoc (:opts f)
                                                       :project (:project f)
                                                       :origin origin))
                    {:kind :none})

                :execute
                (do (runs/spawn-session-for-run! (:run facts) origin)
                    (queue/enqueue! {:type :execute-run :run-id (:run-id cont)})
                    {:kind :execute :run-id (:run-id cont)})

                :successor
                (let [next (successor! (:run cont) origin)]
                  (queue/enqueue! {:type :execute-run :run-id (:id next)})
                  {:kind :successor :run-id (:id next) :of (:id (:run cont))})

                :resume-turn
                (do (when-let [run (:run facts)]
                      (runs/spawn-session-for-run! run origin))
                    (queue/enqueue! {:type    :resume-turn
                                     :project (:project cont)
                                     :ws-id   (:ws-id cont)
                                     :input   (:input cont)})
                    {:kind :resume-turn :ws-id (:ws-id cont)}))]
          {:failure (:id f) :outcome :restored :continuation handed})
        (catch Throwable t
          (let [left (some #(:session-failure/id (ex-data %))
                           (take-while some? (iterate #(.getCause ^Throwable %) t)))]
            (cond-> {:failure (:id f) :outcome :not-restored
                     :note    (str/trim (str (ex-message t)))}
              left (assoc :failure-left left))))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :map]}
  restore!
  "Restore every failure of recovery workstream `ws-id`'s cause still owed that no
   earlier :session-restored entry on it names restored or withdrawn; append one
   :session-restored entry with each failure's outcome; close the workstream once
   every failure named on it has been named restored or withdrawn.

   Refuses — throws with :reason — on a workstream that is not a recovery
   (:not-a-recovery) or holds no :session-diagnosis (:undiagnosed): restoring an
   undiagnosed failure is repair by guesswork.

   Returns {:outcomes [...] :closed? bool}; :outcomes is empty when nothing was
   owed, and nothing is appended then."
  [project ws-id]
  (let [w   (or (ws/read-ws project ws-id)
                (throw (ex-info "no such workstream" {:reason :not-a-recovery :ws-id ws-id})))
        ref (some #(when (= start-failures/adapter (:adapter %)) %) (:external-refs w))
        _   (when-not ref
              (throw (ex-info (str ws-id " is not a session recovery") {:reason :not-a-recovery})))
        _   (when-not (ws/latest-entry project ws-id :session-diagnosis)
              (throw (ex-info (str ws-id " holds no :session-diagnosis — diagnose before restoring")
                              {:reason :undiagnosed})))
        cause    (first (str/split (:id ref) #"@" 2))
        mine     (some #(when (= ws-id (:ws-id %)) %) (start-failures/recoveries project))
        covered  (->> (start-failures/owed-failures project)
                      (filter #(= cause (failure/cause %)))
                      (remove #((:settled mine #{}) (:id %)))
                      (sort-by :id))
        outcomes (mapv restore-one! covered)]
    (when (seq outcomes)
      (ws/append-entry! project ws-id {:kind :session-restored}
                        (pr-str {:format :session-restored :outcomes outcomes})))
    (let [after    (some #(when (= ws-id (:ws-id %)) %) (start-failures/recoveries project))
          settled? (and after (seq (:named after)) (every? (:settled after) (:named after)))]
      (when (and settled? (not (:closed after)))
        (ws/close! project ws-id :done))
      {:outcomes outcomes :closed? (boolean settled?)})))
