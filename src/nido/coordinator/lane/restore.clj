(ns nido.coordinator.lane.restore
  "Bring back what a failed session start stopped.

   What a person was working in comes back up here, in the process running the restore — the
   recovery's own session, never the daemon: when the failure was a defect a recovery has just
   landed a fix for, a bring-up the daemon performed would run the code that failed.

   A start kept on behalf of a Run or a reply's turn is withdrawn: the core runs every turn now,
   and a turn whose session did not come up ended failed there, so there is nothing left here to
   continue."
  (:require
   [clojure.string :as str]
   [nido.coordinator.record.records :as records]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.session-events :as session-events]
   [nido.coordinator.source.start-failures :as start-failures]
   [nido.session.failure :as failure]
   [nido.session.lifecycle :as lifecycle]))

(defn ^{:malli/schema [:=> [:cat :map :map] :map]}
  continuation
  "What continues failure `f` once its session is up, given `facts` read for it: :typed? (whether
   a person typed a prompt in the failed session within `session/worked-window-ms` before it
   failed, or :unread).

     {:kind :none}                       a person's start; bringing it up is all
     {:kind :withdrawn}                  a start made for a Run or a reply's turn, or one naming a
                                         session `up!` now refuses; there is nothing left to
                                         continue
     {:kind :unread}                     a person's start whose prompt log could not be read:
                                         nothing is decided, and the failure stays owed
     {:kind :withdrawn :why :no-person}  a start kept as a person's in a session nobody typed into
                                         in the day before it failed — restoring it would only
                                         bring up what nobody is working in

   An unnameable session predates the refusal: restoring it could only be refused again, and the
   refusal is not a failure, so it would stay owed forever."
  [f {:keys [typed?]}]
  (let [{:keys [kind]} (:origin f)]
    (cond
      (or (some-> (:session f) lifecycle/session-name-problem)
          (some-> (get-in f [:opts :branch]) lifecycle/session-name-problem)
          (#{:run :resume} kind))
      {:kind :withdrawn}

      (= :unread typed?)
      {:kind :unread}

      (not typed?)
      {:kind :withdrawn :why :no-person}

      :else
      {:kind :none})))

(defn- typed-before?
  "Whether a person typed a prompt in failure `f`'s session within `session/worked-window-ms`
   before it failed, or :unread when the prompt log could not be read — which says nothing about
   who typed. A failure whose time does not parse was typed into by no one."
  [f]
  (try
    (boolean
     (when-let [at-ms (try (.toEpochMilli (java.time.Instant/parse (str (:at f)))) (catch Exception _ nil))]
       (let [typed (->> (session-events/prompts-since (- at-ms session/worked-window-ms))
                         (filterv #(not (pos? (compare (str (:at %)) (str (:at f))))))
                         session/prompted-sessions)]
         (contains? typed [(name (:project f)) (str (:session f))]))))
    (catch Exception _ :unread)))

(defn- restore-one!
  "Bring failure `f` back. Returns its outcome."
  [f]
  (let [cont   (continuation f {:typed? (typed-before? f)})
        origin (assoc (:origin f) :restoring (:id f))]
    (case (:kind cont)
      :unread
      {:failure (:id f) :outcome :not-restored
       :note    "the prompt log could not be read, so whether a person was working in it is unknown; left owed"}

      :withdrawn
      (cond-> {:failure (:id f) :outcome :withdrawn}
        (:why cont) (assoc :why (:why cont)))

      (try
        ;; On the runtime of the workstream holding it now, else one of its own: a kept failure
        ;; carries no key, since who holds the session may have changed since it failed.
        (lifecycle/up! (:session f)
                       (let [held-by (session/workstream-id-for (keyword (name (:project f))) (:session f))]
                         (cond-> (assoc (:opts f) :project (:project f) :origin origin)
                           held-by (assoc :runtime-key held-by))))
        {:failure (:id f) :outcome :restored :continuation {:kind :none}}
        (catch Throwable t
          (let [left (some #(:session-failure/id (ex-data %))
                           (take-while some? (iterate #(.getCause ^Throwable %) t)))]
            (cond-> {:failure (:id f) :outcome :not-restored
                     :note    (str/trim (str (ex-message t)))}
              left (assoc :failure-left left))))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :string] :map]}
  restore!
  "Restore every failure of recovery run `run-key`'s cause still owed, and record one
   :session-restored on the run with each failure's outcome.

   Refuses — throws with :reason — on a run that is not a recovery (:not-a-recovery) or has
   recorded no :session-diagnosis (:undiagnosed): restoring an undiagnosed failure is repair by
   guesswork.

   Returns {:outcomes [...] :settled? bool}, :settled? when nothing of the cause is owed any
   more; :outcomes is empty when nothing was owed, and nothing is recorded then."
  [project run-key]
  (let [run      (some #(when (= run-key (:key %)) %) (records/runs project))
        payload  (-> run :run :payload)
        _        (when-not (= start-failures/adapter (:adapter payload))
                   (throw (ex-info (str run-key " is not a session recovery") {:reason :not-a-recovery})))
        _        (when-not (some #(= :session-diagnosis (:kind %)) (:records run))
                   (throw (ex-info (str run-key " has recorded no :session-diagnosis — diagnose before restoring")
                                   {:reason :undiagnosed})))
        cause    (str (:cause payload))
        owed-now #(->> (start-failures/owed-failures project)
                       (filter (fn [f] (= cause (failure/cause f))))
                       (sort-by :id))
        outcomes (mapv restore-one! (owed-now))]
    (when (seq outcomes)
      (records/append-to-run! run-key {:kind :session-restored :outcomes outcomes}))
    {:outcomes outcomes :settled? (empty? (owed-now))}))
