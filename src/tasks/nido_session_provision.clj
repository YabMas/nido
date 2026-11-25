(ns tasks.nido-session-provision
  "`bb nido:session:provision` — the session verb core-provision calls for a unit's session.

   Brings the named session's working copy and services up, stops them, or destroys the session,
   through session-lifecycle alone: no workstream is recorded, no conductor or seat is carried, no
   agent is started. Idempotent, so an attempt made twice does the work once.

     bb nido:session:provision :project <p> :want up|stopped|destroyed <session> [:path <worktree>]
                               [:profile <session-profile>]
     bb nido:session:provision :check true

   `:profile` names the session profile to bring the session up with (default :full).

   Its last line of output is an EDN map: {:cwd <worktree>} for up, {:stopped true},
   {:destroyed true}, {:ok true} for :check — or {:error <message>} with exit 1."
  (:require [nido.platform.task-args :as task-args]
            [nido.session.lifecycle :as lifecycle]))

(defn- result! [m code]
  (prn m)
  (flush)
  (System/exit code))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  provision
  "The verb. See the namespace doc."
  [& args]
  (let [[positionals opts] (task-args/split-args args)
        session (first positionals)
        lopts (cond-> {:project (some-> (:project opts) str)}
                (:path opts)    (assoc :path (str (:path opts)))
                (:profile opts) (assoc :session-profile (keyword (name (:profile opts)))))]
    (try
      (cond
        (:check opts) (result! {:ok true} 0)
        (nil? session) (result! {:error "a session name is required"} 1)
        :else
        (case (some-> (:want opts) name)
          "up"        (do (lifecycle/up! session lopts)
                          (result! {:cwd (str (:wt-path (lifecycle/session-coords session lopts)))} 0))
          "stopped"   (do (lifecycle/down! session lopts) (result! {:stopped true} 0))
          "destroyed" (do (lifecycle/destroy! session lopts) (result! {:destroyed true} 0))
          (result! {:error (str "unknown :want " (:want opts))} 1)))
      (catch Throwable t
        (result! {:error (or (ex-message t) (str t))} 1)))))
