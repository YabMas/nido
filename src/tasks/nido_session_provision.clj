(ns tasks.nido-session-provision
  "`bb nido:session:provision` — the session verb core-provision calls for a unit's session.

   Brings the named session's working copy and services up, stops them, or destroys the session,
   through session-lifecycle alone: no workstream is recorded, no conductor or seat is carried, no
   agent is started. Idempotent, so an attempt made twice does the work once.

     bb nido:session:provision :project <p> :want up|stopped|destroyed <session> [:path <worktree>]
                               [:profile <session-profile>]
     bb nido:session:provision :check true

   `:profile` names the session profile to bring the session up with (default :full).

   Its last line of output is an EDN map: {:cwd <worktree>} for up — with `:writable`, the
   absolute dirs of the project's design declaration in that worktree, when it declares one: what a
   turn held read-only until its design clears may still edit — {:stopped true},
   {:destroyed true}, {:ok true} for :check — or {:error <message>} with exit 1, carrying
   `:refused :not-owned` and the `:path` kept when session-lifecycle refused to remove a directory
   that is not the session's own."
  (:require [babashka.fs :as fs]
            [nido.design.check :as design-check]
            [nido.platform.task-args :as task-args]
            [nido.session.lifecycle :as lifecycle]))

(defn ^{:malli/schema [:=> [:cat [:maybe :string] :string] [:maybe [:vector :string]]]}
  writable-dirs
  "The absolute dirs `project`'s design declaration lives in under `worktree`, or nil when the
   project declares none there. Read here, outside the core, so the core carries them as opaque
   paths and never learns what a declaration is."
  [project worktree]
  (when-let [{:keys [spec-dirs]} (and project (design-check/design-of project worktree))]
    (not-empty (vec (for [d spec-dirs] (str (fs/canonicalize (fs/path worktree d))))))))

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
                          (let [cwd      (str (:wt-path (lifecycle/session-coords session lopts)))
                                writable (try (writable-dirs (:project lopts) cwd)
                                              (catch Throwable _ nil))]
                            (result! (cond-> {:cwd cwd} writable (assoc :writable writable)) 0)))
          "stopped"   (do (lifecycle/down! session lopts) (result! {:stopped true} 0))
          "destroyed" (do (lifecycle/destroy! session lopts) (result! {:destroyed true} 0))
          (result! {:error (str "unknown :want " (:want opts))} 1)))
      (catch Throwable t
        (result! (cond-> {:error (or (ex-message t) (str t))}
                   (= :not-owned (:reason (ex-data t))) (assoc :refused :not-owned :path (:path (ex-data t))))
                 1)))))
