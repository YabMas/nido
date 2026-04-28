(ns nido.ci.session
  "Adapter that turns a live nido session into the Session value the
   local-ci spec treats as an external input.

   The spec models Session with worktree-path, instance-state-dir, pg-port
   and an app port range. This ns reads the session's state file and
   surfaces those fields; it does not start or stop anything."
  (:require
   [nido.session.state :as state]))

(def ^:private default-ci-port-range
  "Default range local-ci services may allocate from. Matches the app-port
   band used elsewhere in nido; callers can override per step."
  [3100 5100])

(defn- session-name-from-instance-id
  "instance-id is `<project>--<session-name>`; the session-name part is
   the worktree leaf and a stable identity nido step-run env can rely on."
  [instance-id project-name]
  (let [prefix (str project-name "--")]
    (if (and instance-id (.startsWith ^String instance-id prefix))
      (subs instance-id (count prefix))
      instance-id)))

(defn lookup
  "Return a Session value for a running nido session, or throw if the
   session has no state on disk."
  [instance-id]
  (let [session (state/read-session instance-id)]
    (when-not session
      (throw (ex-info (str "No running session for instance-id '" instance-id "'")
                      {:instance-id instance-id
                       :hint "Run `bb nido:session:init <name>` first."})))
    (let [ctx (:context session)
          project-name (:project-name session)
          [low high] default-ci-port-range]
      {:instance-id instance-id
       :project-name project-name
       :session-name (session-name-from-instance-id instance-id project-name)
       :worktree-path (:project-dir session)
       :instance-state-dir (state/instance-state-dir instance-id)
       :pg-port (get-in ctx [:pg :port])
       :app-port (get-in session [:service-states :app :app-port])
       :app-port-range-start low
       :app-port-range-end high})))
