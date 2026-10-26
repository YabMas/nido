(ns nido.session.engine
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [nido.platform.config :as config]
   [nido.platform.core :as core]
   [nido.platform.io :as io]
   [nido.platform.process :as proc]
   [nido.session.agent-guidance :as agent-guidance]
   [nido.session.checkout :as checkout]
   [nido.session.context :as ctx]
   [nido.session.launcher :as launcher]
   [nido.session.resume-shim :as resume-shim]
   [nido.session.service :as service]
   ;; Load service implementations (must be loaded before state for the
   ;; defmethods to register; state itself has no transitive deps on them).
   ;; `eval` is additionally aliased: the idempotent up path calls its app
   ;; toggle directly (see `reconcile-app!`).
   nido.session.services.config-file
   [nido.session.services.eval :as eval-svc]
   nido.session.services.postgresql
   nido.session.services.process
   [nido.session.test-pg :as test-pg]
   [nido.session.state :as state]))

;; ---------------------------------------------------------------------------
;; Setup step dispatch
;; ---------------------------------------------------------------------------

(defn- git-common-project-root [project-dir]
  (let [result (shell {:continue true :out :string :err :string}
                      "git" "-C" project-dir "rev-parse" "--git-common-dir")]
    (when (zero? (:exit result))
      ;; --git-common-dir returns a relative path when discovery walks up
      ;; the tree (e.g. when a jj workspace lives below a colocated repo);
      ;; canonicalize against project-dir so callers always get an absolute
      ;; source root.
      (some-> (:out result)
              str/trim
              (->> (fs/path project-dir))
              fs/canonicalize
              fs/parent
              str))))

(defn- jj-source-root
  "When `project-dir` is a jj workspace (no `.git`, just `.jj/`), its
   `.jj/repo` is a small text file containing a relative path to the
   source's `.jj/repo/` directory. Resolve that pointer and return the
   source dir (parent of the source's `.jj/`)."
  [project-dir]
  (let [pointer (fs/path project-dir ".jj" "repo")]
    (when (fs/regular-file? pointer)
      (let [rel (str/trim (slurp (str pointer)))
            target (fs/canonicalize (fs/path (fs/parent pointer) rel))]
        (some-> target fs/parent fs/parent str)))))

(defn- source-project-root
  "Resolve the source project root for a worktree at `project-dir`. jj
   workspaces (no `.git`, just a `.jj/repo` pointer file) take precedence
   so we don't accidentally walk up past the workspace via git's discovery
   behavior. Falls back to `git rev-parse --git-common-dir` for plain git
   worktrees."
  [project-dir]
  (or (jj-source-root project-dir)
      (git-common-project-root project-dir)))

(defn- local-root-paths [project-dir]
  (let [deps-path (str (fs/path project-dir "deps.edn"))]
    (if-not (fs/exists? deps-path)
      []
      (let [deps-edn (edn/read-string (slurp deps-path))]
        (->> (:deps deps-edn)
             vals
             (keep :local/root)
             distinct)))))

(defn- path-present?
  "True if the path exists (file or directory) OR if it's a symlink — even
   one whose target no longer resolves. fs/exists? alone follows links and
   reports false for dangling symlinks, which then trips `ln -s`."
  [path]
  (or (fs/exists? path)
      (fs/sym-link? path)))

(defn- link-path! [source-path target-path]
  (when-let [parent (fs/parent target-path)]
    (fs/create-dirs parent))
  (let [result (shell {:continue true :out :string :err :string}
                      "ln" "-s" source-path target-path)]
    (when-not (zero? (:exit result))
      (throw (ex-info "Failed to create symlink"
                      {:source source-path
                       :target target-path
                       :error (:err result)})))))

(defn- ensure-local-root-deps! [project-dir]
  (let [src-root (source-project-root project-dir)
        local-roots (local-root-paths project-dir)
        linked (atom [])
        missing (atom [])]
    (doseq [local-root local-roots
            :let [target-path (str (fs/normalize (fs/path project-dir local-root)))]
            :when (not (path-present? target-path))]
      (if src-root
        (let [source-path (str (fs/normalize (fs/path src-root local-root)))]
          (if (fs/exists? source-path)
            (do
              (link-path! source-path target-path)
              (swap! linked conj {:source source-path :target target-path}))
            (swap! missing conj {:local-root local-root
                                 :source source-path
                                 :target target-path})))
        (swap! missing conj {:local-root local-root :source nil :target target-path})))
    (when (seq @linked)
      (core/log-step "Linked local deps for this worktree:")
      (doseq [{:keys [source target]} @linked]
        (println "  " target "->" source)))
    (when (seq @missing)
      (throw (ex-info "Missing local/root dependencies for this worktree"
                      {:missing @missing
                       :hint "Ensure sibling repos exist near your git common project root."})))))

(defn- ensure-shared-link! [project-dir source-project-root rel-path]
  (when source-project-root
    (let [source-path (str (fs/path source-project-root rel-path))
          target-path (str (fs/path project-dir rel-path))]
      (when (and (fs/exists? source-path)
                 (not (path-present? target-path)))
        (link-path! source-path target-path)
        (core/log-step (str "Linked " rel-path " -> " source-path))))))

(defmulti ^{:malli/schema [:=> [:cat :map :Path] :any]}
  run-setup-step!
  "Run a one-shot setup step. Dispatches on (:type step)."
  (fn [step _project-dir] (:type step)))

(defmethod run-setup-step! :worktree-links
  [step project-dir]
  (let [{:keys [shared-paths local-deps]} step
        source-root (source-project-root project-dir)]
    (when (= local-deps :deps-edn)
      (core/log-step "Ensuring local/root dependencies...")
      (ensure-local-root-deps! project-dir))
    (doseq [p shared-paths]
      (ensure-shared-link! project-dir source-root p))))

(defmethod run-setup-step! :shell
  [step project-dir]
  (let [{:keys [command skip-if-exists]} step]
    (if (and skip-if-exists (fs/exists? (str (fs/path project-dir skip-if-exists))))
      (core/log-step (str "Skipping shell step: " skip-if-exists " already exists"))
      (do
        (core/log-step (str "Running: " command))
        (let [result (shell {:continue true :out :string :err :string :dir project-dir}
                            "bash" "-lc" command)]
          (when-not (zero? (:exit result))
            (core/log-step (str "WARNING: shell step failed: " (:err result)))))))))

(defmethod run-setup-step! :default
  [step _project-dir]
  (core/log-step (str "WARNING: Unknown setup step type: " (:type step))))

;; ---------------------------------------------------------------------------
;; Session lifecycle
;; ---------------------------------------------------------------------------

(defn ^{:malli/schema [:=> [:cat :ProjectName] :Path]}
  session-edn-path [project-name]
  (str (fs/path (core/nido-home) "projects" project-name "session.edn")))

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:maybe :map]]}
  read-session-edn
  "Soft read: the project's session.edn, or nil when it has no session.edn.
   For read-only callers that can still answer from defaults — a project can be
   registered in projects.edn but never configured, and enumerating it must not
   throw. Use `load-session-edn` wherever the config is genuinely required
   (anything that boots or mutates a session)."
  [project-name]
  (let [path (session-edn-path project-name)]
    (when (fs/exists? path)
      (io/read-edn path))))

(defn ^{:malli/schema [:=> [:cat :ProjectName] :map]}
  load-session-edn [project-name]
  (let [path (session-edn-path project-name)]
    (when-not (fs/exists? path)
      (throw (ex-info (str "No session.edn found for project '" project-name "'")
                      {:path path
                       :hint "Create a session.edn in ~/.nido/projects/<name>/session.edn"})))
    (io/read-edn path)))

(defn ^{:malli/schema [:=> [:cat :Path] :ProjectName]}
  resolve-project-name
  "Resolve a project-dir to its registered project name, falling back to the
   leaf path component."
  [project-dir]
  (let [projects (config/read-projects)]
    (or (some (fn [[name entry]]
                (when (= (str (fs/normalize (fs/path (:directory entry))))
                         (str (fs/normalize (fs/path project-dir))))
                  name))
              projects)
        (some (fn [[name entry]]
                (let [src-root (source-project-root project-dir)
                      reg-dir (str (fs/normalize (fs/path (:directory entry))))]
                  (when (and src-root
                             (= (str (fs/normalize (fs/path src-root)))
                                reg-dir))
                    name)))
              projects)
        (str (fs/file-name (fs/path project-dir))))))

(defn- main-checkout?
  "True when project-dir is the registered project's canonical directory
   (i.e. not a worktree)."
  [project-dir project-name]
  (let [projects (config/read-projects)
        reg-dir (some-> (get projects project-name) :directory)]
    (and reg-dir
         (= (str (fs/normalize (fs/path project-dir)))
            (str (fs/normalize (fs/path reg-dir)))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :string] :InstanceId]}
  instance-id-for-key
  "The instance id of `project-name`'s runtime keyed by `runtime-key`: `<project>--<key>`, spelled by
   `state/keyed-id`, which keeps distinct keys distinct. The key is the caller's, taken
   whole — nothing here derives one from a path."
  [project-name runtime-key]
  (state/keyed-id project-name runtime-key))

(defn- registry-entry
  "The registry's entry for the session whose worktree is `project-dir`, or nil."
  [project-dir]
  (let [registry (state/read-registry)]
    (or (get registry (str project-dir))
        (get registry (str (fs/normalize (fs/path project-dir)))))))

(defn ^{:malli/schema [:=> [:cat :Path] [:maybe :InstanceId]]}
  resolve-instance-id
  "The instance id of the runtime the session in `project-dir` is on: the one the registry
   recorded when it came up, else the project name for the project's own checkout, else nil — a
   worktree with no session up has no runtime, and none is guessed from its path."
  [project-dir]
  (or (:instance-id (registry-entry project-dir))
      (let [project-name (resolve-project-name project-dir)]
        (when (main-checkout? project-dir project-name)
          project-name))))

(defn ^{:malli/schema [:=> [:cat :Path] [:maybe :string]]}
  runtime-key-of
  "The runtime key the session in `project-dir` came up under, or nil when none is up there."
  [project-dir]
  (:runtime-key (registry-entry project-dir)))

(defn ^{:malli/schema [:=> [:cat :Path :ProjectName [:maybe :string]] :InstanceId]}
  runtime-instance-id
  "The instance id a session in `project-dir` comes up on for `runtime-key`: the project name for
   the project's own checkout, else the key's. Throws when a worktree is given no key."
  [project-dir project-name runtime-key]
  (cond
    (main-checkout? project-dir project-name) project-name
    runtime-key (instance-id-for-key project-name runtime-key)
    :else (throw (ex-info (str "No runtime key for " project-dir)
                          {:reason :no-runtime-key :project-dir project-dir}))))

(defn- print-session-summary [_session-data ctx]
  (println "nido session:")
  (println "  project:" (get-in ctx [:session :project-dir]))
  (println "  project-name:" (get-in ctx [:session :project-name]))
  (println "  instance-id:" (get-in ctx [:session :instance-id]))
  (when-let [url (get-in ctx [:app :url])]
    (println "  url:" url))
  (when-let [p (get-in ctx [:app :port])]
    (println "  app port:" p))
  (when-let [p (get-in ctx [:repl :port])]
    (println "  nrepl port:" p))
  (when-let [p (get-in ctx [:pg :port])]
    (println "  pg port:" p))
  (println "  state file:" (state/session-state-file
                            (get-in ctx [:session :instance-id]))))

(defn- resolve-jvm-config
  "Merge nido-controlled JVM knobs from session-edn :defaults :jvm with
   per-invocation opts. Flat CLI-friendly keys (:jvm-heap-max,
   :jvm-aliases, :jvm-extra-opts) are supported alongside a nested :jvm
   map in opts. Returns a map enriched with :aliases-joined and
   :extra-opts-joined so service-def templates can reference them
   directly."
  [defaults opts]
  (let [base (or (:jvm defaults) {})
        flat (cond-> {}
               (:jvm-heap-max opts)   (assoc :heap-max (:jvm-heap-max opts))
               (:jvm-aliases opts)    (assoc :aliases (:jvm-aliases opts))
               (:jvm-extra-opts opts) (assoc :extra-opts (:jvm-extra-opts opts)))]
    (ctx/prepare-jvm (merge base flat (:jvm opts)))))

(defn- pre-allocate-ports
  "Pre-allocate ports for services that need their port known by other
   services BEFORE they run (e.g. the :eval service's app port is
   referenced from the :config-file template, which runs first). Seeds
   `(keyword svc-name)` → {:port N} into the context."
  [services project-dir]
  (reduce (fn [acc svc-def]
            (if (and (= :eval (:type svc-def)) (:name svc-def))
              (let [[low high] (or (:port-range svc-def) [3100 5100])
                    pref (proc/deterministic-port project-dir low high)
                    port (proc/find-available-port pref (- high low))]
                (assoc acc (keyword (:name svc-def)) {:port port}))
              acc))
          {} services))

(defn ^{:malli/schema [:=> [:cat :any :any] :any]}
  filter-services
  "Filter a session.edn :services list by an allowlist. Allowlist is
   either :all (return everything) or a vector of allowed :type values."
  [services allowlist]
  (cond
    (= :all allowlist) (vec services)
    (vector? allowlist) (filterv #(contains? (set allowlist) (:type %)) services)
    :else (throw (ex-info "Invalid services allowlist" {:allowlist allowlist}))))

;; ---------------------------------------------------------------------------
;; Profile persistence — written at session-up so cleanup can read the
;; resolved profile without re-resolving from config (robust against
;; registry edits between up and destroy).
;; ---------------------------------------------------------------------------

(defn ^{:malli/schema [:=> [:cat :ProjectName :string :any] :any]}
  write-profile-for-session!
  "Persist the resolved profile the session `session-name` was brought up with, under its own key
   rather than the runtime it is on, so cleanup paths can read it back without re-resolving —
   while it is down too."
  [project-name session-name profile]
  (io/write-edn! (state/session-profile-path project-name session-name) profile))

(defn ^{:malli/schema [:=> [:cat :ProjectName :string] [:maybe :any]]}
  read-profile-for-session
  "Return the resolved profile the session `session-name` was brought up with. nil if absent
   (e.g. legacy sessions predating this feature)."
  [project-name session-name]
  (state/read-session-profile project-name session-name))

(defn- record-profile!
  "Persist `profile` as the session `session-name`'s when it has none recorded. A profile describes
   how the session's working copy was made, so a start, a join or a briefing records it once and
   never replaces it: only the act that creates the working copy writes over one
   (`write-profile-for-session!`, from the worktree's creation)."
  [project-name session-name profile]
  (try (when (and session-name profile
                  (nil? (read-profile-for-session project-name session-name)))
         (write-profile-for-session! project-name session-name profile))
       (catch Exception e
         (core/log-step (str "warning: failed to write profile snapshot: " (ex-message e))))))

(defn- apply-pg-mode-override
  "If this session has a persisted PG mode override, apply it to the
   :postgresql service-def's :mode. Other services pass through untouched."
  [services instance-id]
  (if-let [ov (:mode (state/read-pg-mode-override instance-id))]
    (mapv (fn [svc] (if (= :postgresql (:type svc)) (assoc svc :mode ov) svc))
          services)
    services))

(defn- start-failure-report
  "The message logged when a service fails to start and the session rolls back.
   A :process service attaches the tail of its own log to the exception, and
   that is where the real cause lives — a classpath error, a port already
   bound. Append it instead of printing the bare ex-message and leaving the
   operator to go find ~/.nido/state/<instance>/logs/<service>.log by hand."
  [e started-count]
  (let [{:keys [log-tail log-path]} (ex-data e)
        headline (str "Session start failed: " (ex-message e)
                      " — rolling back " started-count " started service(s)")]
    (if (str/blank? log-tail)
      headline
      (str headline
           "\n  last output from the failed service:\n"
           (->> (str/split-lines (str/trim log-tail))
                (map (fn [line] (str "    " line)))
                (str/join "\n"))
           (when log-path (str "\n  full log: " log-path))))))

(defn- register!
  "Record in the registry that the session `session-name` in `project-dir` is on `runtime`, under
   `runtime-key`. One entry per worktree: sessions sharing a runtime each have their own, carrying
   the runtime's ports."
  [project-dir session-name runtime-key runtime]
  (let [ctx (:context runtime)]
    (state/upsert-registry!
     (str project-dir)
     (cond-> {:project-dir  (str project-dir)
              :project-name (:project-name runtime)
              :instance-id  (:instance-id runtime)
              :runtime-key  runtime-key
              :session-name session-name
              :url          (get-in ctx [:app :url])
              :app-port     (get-in ctx [:app :port])
              :nrepl-port   (get-in ctx [:repl :port])
              :repl-pid     (get-in ctx [:repl :pid])
              :created-at   (core/now-iso)}
       (get-in ctx [:pg :port]) (assoc :pg-port (get-in ctx [:pg :port]))))))

(declare rebrief!)

(defn- runtime-services
  "The services a runtime provides: every one any of its members' profiles asks for — the
   starting session's `profile` and the recorded profiles of `others` (worktree to session name).
   A member with no recorded profile asks for all, as a start with none does."
  [project-name profile others]
  (let [asks (map :services (cons profile (map #(read-profile-for-session project-name %)
                                               (vals others))))]
    (if (some #(or (nil? %) (= :all %)) asks)
      :all
      (vec (distinct (apply concat asks))))))

(defn- runtime-service-defs
  "The service definitions runtime `instance-id` runs: those any of its members asks for — the
   session starting or joining it, with `profile`, and `others` (worktree to session name). The
   one reading of what a runtime provides, for a start and a join alike."
  [project-name session-edn instance-id profile others]
  (apply-pg-mode-override
   (filter-services (:services session-edn) (runtime-services project-name profile others))
   instance-id))

(defn- start-each!
  "Bring `services` up in order over `ctx`, on a runtime running nothing. Returns
   {:ctx :service-defs :service-states}, the definitions and states written together so whatever
   stops the runtime later stops what this call started. When one throws, what it started is
   stopped, in reverse, and the throw goes on: nothing is persisted on the way, so nothing else
   would undo it."
  [ctx services opts]
  ;; `started` accumulates {:resolved-def ... :state ...} in start order so we can roll
  ;; back (stop in reverse) if any later service throws. Without this, a mid-init failure
  ;; would leave PG/JVM/etc. orphaned because session state isn't persisted until after.
  (let [started (atom [])]
    (try
      (reduce
       (fn [acc svc-def]
         (let [svc-name     (:name svc-def)
               resolved-def (ctx/substitute (:ctx acc) svc-def)
               {:keys [state context]} (service/start-service! resolved-def (:ctx acc) opts)]
           (swap! started conj {:resolved-def resolved-def :state state})
           (-> acc
               (update :ctx ctx/merge-context (keyword svc-name) context)
               (update :service-defs conj svc-def)
               (assoc-in [:service-states svc-name] state))))
       {:ctx ctx :service-defs [] :service-states {}}
       services)
      (catch Exception e
        (core/log-step (start-failure-report e (count @started)))
        (doseq [{:keys [resolved-def state]} (reverse @started)]
          (let [svc-name (:name resolved-def)]
            (try
              (core/log-step (str "Rolling back " (name svc-name) "..."))
              (service/stop-service! resolved-def state)
              (catch Exception e2
                (println (str "warning: rollback of " (name svc-name)
                              " threw: " (ex-message e2)))))))
        (throw e)))))

(defn- stop-services!
  "Stop every service runtime `session` (instance `instance-id`) runs, in reverse start order. A
   service it declares but saved no state for is stopped from its own record, since a later start
   that did not complete may have left its process there. A service that throws is reported and the
   rest are still stopped."
  [session instance-id]
  (doseq [svc-def (reverse (or (:service-defs session) []))]
    (let [svc-name    (:name svc-def)
          saved-state (get (:service-states session) svc-name)]
      (try
        (if saved-state
          (do (core/log-step (str "Stopping " (name svc-name) "..."))
              (service/stop-service! svc-def saved-state))
          (service/stop-recorded! svc-def instance-id))
        (catch Exception e
          (println (str "warning: error stopping " (name svc-name) ": "
                        (ex-message e))))))))

(defn- checkout?
  "Does a runtime carrying `dirs` (its members' worktrees) run out of a checkout of its own? Only
   when every member is a jj workspace: a git worktree's uncommitted changes are no commit a merge
   can read, so a runtime with one runs out of a member's worktree, as it always did."
  [dirs]
  (and (seq dirs) (every? checkout/jj-workspace? dirs)))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] :boolean]}
  owes-checkout?
  "Does live runtime record `runtime` run out of a member's worktree though every member is a jj
   workspace — one started before runtimes of jj workspaces ran out of a checkout of their own? It
   is moved onto one by restarting it, every member kept."
  [runtime]
  (boolean (and runtime (not (:checkout runtime))
                (checkout? (keys (state/members runtime))))))

(defn- runtime-dir
  "Where runtime `instance-id`, carrying `members` (worktree to session name), runs out of: its own
   checkout, brought up to date with them, when `checkout?` holds; else `fallback`, a member's
   worktree. Throws, changing nothing, when the members' working copies conflict."
  [instance-id members session-edn fallback]
  (if (checkout? (keys members))
    (checkout/refresh! instance-id members
                       (fn [dir] (doseq [step (:setup session-edn)] (run-setup-step! step dir))))
    fallback))

(defn- sync-checkout!
  "Bring runtime `runtime`'s checkout up to date with `members`, when it runs out of one. A
   conflict, or a working copy that cannot be snapshotted, is reported and the checkout kept as it
   was: a member coming up or going down is not refused for what another member's working copy
   holds."
  [runtime members]
  (when (and (:checkout runtime) (checkout? (keys members)))
    (try (checkout/refresh! (:instance-id runtime) members)
         (catch clojure.lang.ExceptionInfo e
           (if (#{:checkout-conflict :checkout-unsnapshottable} (:reason (ex-data e)))
             (core/log-step (str "warning: " (ex-message e)))
             (throw e))))))

(defn- start-services!
  "Start runtime `instance-id` cold for the session in `project-dir`, under the union of what it
   and `others` ask for. `others` are the sessions staying on the runtime — those still on it when
   it died, or every member when a join restarts it — worktree to session name; they stay its
   members and are re-registered and re-briefed with the ports it comes up on. `fallback-dir` is
   the worktree the runtime runs out of when it has no checkout of its own (`runtime-dir`)."
  [project-dir project-name instance-id session-edn opts others fallback-dir]
  (core/log-step (str "Starting session " instance-id " (" project-dir ")"))
  (let [session-name (:session-name opts)
        all      (assoc others (str project-dir) session-name)
        run-dir  (runtime-dir instance-id all session-edn fallback-dir)
        services (runtime-service-defs project-name session-edn instance-id (:profile opts) others)
        pre-allocated (pre-allocate-ports services run-dir)
        jvm-cfg (resolve-jvm-config (:defaults session-edn) opts)
        init-ctx (merge pre-allocated
                        {:session (cond-> {:project-dir run-dir
                                           :project-name project-name
                                           :instance-id instance-id
                                           :runtime-key (:runtime-key opts)
                                           :jvm jvm-cfg}
                                    session-name (assoc :name session-name)
                                    (checkout? (keys all)) (assoc :git-dir (checkout/git-dir run-dir)))})
        ;; No session state is persisted unless every service starts.
        result (start-each! init-ctx services opts)
        final-ctx (:ctx result)
        session-data (cond-> {:project-dir run-dir
                              :project-name project-name
                              :instance-id instance-id
                              :runtime-key (:runtime-key opts)
                              :members all
                              :service-defs (:service-defs result)
                              :service-states (:service-states result)
                              :context final-ctx
                              :created-at (core/now-iso)}
                       session-name              (assoc :name session-name)
                       (checkout? (keys all))    (assoc :checkout run-dir))]
    (state/write-session! instance-id session-data)
    ;; The profile is recorded before the briefing, which reads it to choose the full or lite one.
    (rebrief! project-dir session-name (:runtime-key opts) session-data session-edn
              (select-keys opts [:run-dir :profile]))
    (doseq [[dir member] others]
      (rebrief! dir member (:runtime-key opts) session-data session-edn))
    (print-session-summary session-data final-ctx)
    session-data))

(defn- session-alive?
  "Is any of this session's PROCESSES still running? Deliberately narrow: it
   decides only whether re-provisioning would duplicate a live process. The
   :eval service owns no process at all (it is mount state inside the repl
   JVM), so it is invisible here by construction — `reconcile-app!` covers it."
  [session]
  (let [svc-states (:service-states session)]
    (some (fn [[_ s]]
            (when-let [pid (:pid s)]
              (proc/process-alive? pid)))
          svc-states)))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  reconcile-app!
  "Re-start any :eval service of an already-running session whose app is no
   longer listening.

   Without this, a session whose app died inside a living JVM — a failed
   auto-reload, a stray `development/stop` — was unrecoverable: `session-alive?`
   saw the repl pid and short-circuited, so the start-form was never evaluated
   by `up` nor by the dashboard's retry, which then blamed a timeout that had
   not happened.

   `start-app!` is itself idempotent (an open port returns immediately), so the
   healthy case is a no-op. Exceptions propagate, matching `start-service! :eval`
   on a fresh boot, so the caller reports the real cause. A service with no saved
   state was never started for this session (a profile that excludes the app), so
   it is skipped rather than provisioned here."
  [existing]
  (doseq [svc-def (:service-defs existing)
          :when   (= :eval (:type svc-def))
          :let    [saved-state (get (:service-states existing) (:name svc-def))]
          :when   saved-state]
    (eval-svc/start-app! svc-def saved-state (:context existing))))

(defn ^{:malli/schema [:=> [:cat :Path [:maybe :InstanceId]] [:sequential :string]]}
  sessions-backed-by
  "The sessions, other than the one in `project-dir`, on a runtime other than `except` that runs
   out of `project-dir`: the worktree of the session that started it, which its JVM, classpath and
   generated config live in whether or not that session is still on it. Removing that worktree
   would pull the runtime out from under them, and starting another runtime there would overwrite
   its files."
  [project-dir except]
  (let [dir (str (fs/normalize (fs/absolutize project-dir)))]
    (into []
          (comp (keep #(state/read-session (str (fs/file-name %))))
                (remove #(= except (:instance-id %)))
                (filter #(= dir (str (fs/normalize (fs/absolutize (str (:project-dir %)))))))
                (mapcat #(vals (dissoc (state/members %) (str project-dir) dir))))
          (when (fs/exists? (state/state-dir)) (fs/list-dir (state/state-dir))))))

(defn ^{:malli/schema [:=> [:cat :keyword [:maybe :string] :Path [:maybe :InstanceId]] :nil]}
  refuse-backing-worktree!
  "Refuse `verb` on the worktree `project-dir` of the session `name` — removing it, or provisioning
   a runtime other than `except` in it — while another session is on a runtime that runs out of
   it: that runtime's JVM, classpath and generated config live there, and it stops only when none
   of its sessions is up. The one guard every path that removes a worktree or places a runtime's
   files in one goes through."
  [verb name project-dir except]
  (let [others (sessions-backed-by project-dir except)]
    (when (seq others)
      (throw (ex-info (str "Refusing " (clojure.core/name verb) " of " name " — its worktree "
                           project-dir " backs the runtime " (str/join ", " others) " are on"
                           "; bring those down first.")
                      {:reason :runtime-backed :verb verb :session name :with (vec others)})))))

(defn ^{:malli/schema [:=> [:cat [:maybe :string] :Path :InstanceId] :nil]}
  refuse-start-in-backing-worktree!
  "Refuse bringing the session `name` in `project-dir` up on runtime `instance-id` when that
   would start the runtime there — it is not up with another session on it, so this session
   cannot just join it — while the worktree backs another runtime other sessions are on. Every
   verb that stops or detaches a session before starting it asks this first, under its locks,
   so a refused start leaves the session where it was."
  [name project-dir instance-id]
  (let [existing (state/read-session instance-id)]
    (when-not (and existing (session-alive? existing)
                   (seq (dissoc (state/members existing) (str project-dir))))
      (refuse-backing-worktree! :start name project-dir instance-id))))

(defn ^{:malli/schema [:=> [:cat :InstanceId] [:sequential :string]]}
  sessions-on
  "The names of the sessions on runtime `instance-id`; empty when it is not up."
  [instance-id]
  (vec (vals (some-> (state/read-session instance-id) state/members))))

(declare stop-session!)

(defn- home-run-dir
  "The Run directory the session `session-name`'s home is linked to, or nil."
  [project-name session-name]
  (when session-name
    (resume-shim/run-dir (state/session-home-dir project-name session-name))))

(defn- render-member!
  "Render runtime `runtime`'s config files into the worktree `project-dir` of its member
   `session-name`, when the runtime runs out of somewhere else, and record what they replaced under
   the runtime's :member-states so `unrender-member!` restores it. Each test database the runtime
   runs is the member's own in what is rendered there (`test-pg/member-context`): sessions sharing
   a runtime share its app and dev database, never a test database. Nothing for the worktree the
   runtime runs out of, whose files its own services rendered."
  [project-dir session-name runtime]
  (when (and session-name (not= (str project-dir) (str (:project-dir runtime))))
    (let [defs  (:service-defs runtime)
          own   (state/session-key (:project-name runtime) session-name)
          ctx   (reduce (fn [c d]
                          (if (= :test-pg (:type d))
                            (assoc c (keyword (:name d)) (test-pg/member-context (ctx/substitute c d) c own))
                            c))
                        (-> (:context runtime)
                            (assoc-in [:session :name] session-name)
                            (assoc-in [:session :project-dir] (str project-dir)))
                        defs)
          files (into {}
                      (for [d defs :when (= :config-file (:type d))]
                        [(:name d) (:state (service/start-service! (ctx/substitute ctx d) ctx {}))]))]
      (when (seq files)
        (let [instance-id (:instance-id runtime)]
          (state/write-session! instance-id (assoc-in (or (state/read-session instance-id) runtime)
                                                      [:member-states (str project-dir)] files)))))))

(defn- unrender-member!
  "Restore what `render-member!` replaced in the worktree `project-dir`. A file that cannot be
   restored is reported and the rest still are. Returns `runtime` without the record of them."
  [project-dir runtime]
  (doseq [[svc-name saved] (get-in runtime [:member-states (str project-dir)])
          :let [d (some #(when (= svc-name (:name %)) %) (:service-defs runtime))]
          :when d]
    (try (service/stop-service! d saved)
         (catch Exception e
           (println (str "warning: error restoring " (name svc-name) " in " project-dir ": "
                         (ex-message e))))))
  (update runtime :member-states dissoc (str project-dir)))

(defn- rebrief!
  "Register the session `session-name` in `project-dir` as on `runtime` and brief it with the
   runtime's REPL, app and database, keeping the Run its home is linked to, and render the
   runtime's config files into its worktree when the runtime runs out of another
   (`render-member!`). A `profile` given is recorded as the session's own before the briefing,
   which reads it, when it has none."
  ([project-dir session-name runtime-key runtime session-edn]
   (rebrief! project-dir session-name runtime-key runtime session-edn
             {:run-dir (home-run-dir (:project-name runtime) session-name)}))
  ([project-dir session-name runtime-key runtime session-edn {:keys [run-dir profile]}]
   (let [ctx (-> (:context runtime)
                 (assoc-in [:session :name] session-name)
                 (assoc-in [:session :project-dir] (str project-dir)))]
     (register! project-dir session-name runtime-key runtime)
     (record-profile! (:project-name runtime) session-name profile)
     (try (render-member! project-dir session-name runtime)
          (catch Exception e
            (core/log-step (str "warning: rendering " session-name "'s config files: " (ex-message e)))))
     (try (launcher/write-artifacts! ctx session-edn run-dir)
          (catch Exception e
            (core/log-step (str "warning: refresh launcher artifacts: " (ex-message e))))))))

(defn- still-on
  "The sessions other than the one in `project-dir` that runtime `existing` records as members
   and the registry still places on it — worktree to session name."
  [project-dir existing]
  (into {}
        (filter (fn [[dir _]]
                  (= (:instance-id existing) (:instance-id (registry-entry dir)))))
        (some-> existing state/members (dissoc (str project-dir)))))

(defn- join!
  "Put the session `session-name` in `project-dir` on the live runtime `existing`. When the
   runtime already runs everything the joiner asks for, nothing is provisioned: it is recorded as a
   member, registered with the runtime's ports and briefed — its own session home, told the
   runtime's REPL, app and database — and the runtime's checkout, if it has one, takes in its
   working copy. When it asks for a service the runtime lacks, the whole runtime is restarted cold
   under the union of every member's profile and its own, out of the same place, and every member
   is re-briefed with what it came up on; Claude sessions and working copies are not touched. A
   runtime is never grown, nor rolled back, service by service. A runtime that `owes-checkout?` is
   restarted the same way, onto a checkout of its own."
  [project-dir session-name existing session-edn opts]
  (let [others  (dissoc (state/members existing) (str project-dir))
        all     (assoc others (str project-dir) session-name)
        defs    (runtime-service-defs (:project-name existing) session-edn
                                      (:instance-id existing) (:profile opts) others)
        missing (remove #(contains? (:service-states existing) (:name %)) defs)
        moving? (owes-checkout? (assoc existing :members all))]
    (if (or (seq missing) moving?)
      (do
        ;; A conflict between the working copies refuses the join before anything is stopped.
        (when (checkout? (keys all)) (runtime-dir (:instance-id existing) all session-edn nil))
        (core/log-step (str "Restarting " (:instance-id existing) " for " session-name
                            (if (seq missing)
                              (str ", which asks for " (str/join ", " (map (comp name :name) missing)))
                              ", onto a checkout of its own")))
        (stop-services! existing (:instance-id existing))
        (start-services! project-dir (:project-name existing) (:instance-id existing) session-edn
                         opts others (:project-dir existing))
        nil)
      (let [runtime (assoc existing :members (assoc (state/members existing) (str project-dir) session-name))]
        (println "Joining running session" (:instance-id runtime))
        (state/write-session! (:instance-id runtime) runtime)
        (sync-checkout! runtime (state/members runtime))
        (rebrief! project-dir session-name (:runtime-key opts) runtime session-edn
                  (select-keys opts [:run-dir :profile]))
        (reconcile-app! runtime)
        nil))))

(defn ^{:malli/schema [:=> [:cat :Path :map] :any]}
  start-session!
  "Bring the session in `project-dir` up on the runtime `(:runtime-key opts)` names, using the
   project's session.edn. A runtime is keyed by that key whole — nido passes a workstream's id, so
   every session of a workstream shares one JVM, app and database. When no key is passed the
   session stays on the runtime it is on; a worktree with neither is refused. The project's own
   checkout is always its own runtime.

   Joins the runtime when it is live, starts its services when it is not. A session on another
   runtime leaves that one first, so it is never on two.
   opts: {:runtime-key string, :session-name string, :profile <resolved-profile-map>,
          :jvm-heap-max string, :jvm-aliases [kw], :jvm-extra-opts [str], :run-dir path}"
  [project-dir {:keys [profile] :as opts}]
  (let [opts         (assoc opts
                            :profile (or profile {:services :all
                                                  :worktree {:strategy :git-worktree}})
                            :runtime-key (or (:runtime-key opts) (runtime-key-of project-dir)))
        project-name (resolve-project-name project-dir)
        instance-id  (runtime-instance-id project-dir project-name (:runtime-key opts))
        session-edn  (load-session-edn project-name)
        on           (resolve-instance-id project-dir)
        ;; Read before leaving another runtime, which removes the session home and its run link.
        opts         (assoc opts :run-dir (or (:run-dir opts)
                                              (home-run-dir project-name (:session-name opts))))
        existing     (state/read-session instance-id)
        join?        (and existing (session-alive? existing))]
    ;; Refuse before leaving anything.
    (refuse-start-in-backing-worktree! (:session-name opts) project-dir instance-id)
    (when (and on (not= on instance-id))
      (core/log-step (str "Leaving runtime " on " for " instance-id))
      (stop-session! project-dir))
    ;; Setup prepares this session's own working copy, whether its runtime is started or joined.
    (doseq [step (:setup session-edn)]
      (run-setup-step! step project-dir))
    ;; The session briefing is now injected at launch (--append-system-prompt),
    ;; so the worktree stays pure project code: remove any prior nido-managed
    ;; CLAUDE.md instead of writing one. (Session-home dissolution, step 2.)
    (try (agent-guidance/remove! project-dir)
         (catch Exception e
           (core/log-step (str "warning: failed to remove agent CLAUDE.md: "
                               (ex-message e)))))
    (if join?
      (join! project-dir (:session-name opts) existing session-edn opts)
      (start-services! project-dir project-name instance-id session-edn opts
                       (if existing (still-on project-dir existing) {}) project-dir))))

;; ---------------------------------------------------------------------------
;; Legacy session detection
;; ---------------------------------------------------------------------------

(defn- read-legacy-session [project-dir]
  (let [legacy-path (str (fs/path project-dir ".codex" "session.edn"))]
    (io/read-edn legacy-path)))

(defn- leave!
  "Take the session in `project-dir` off runtime `session`, which other sessions are still on:
   drop its membership, its registry entry and its session home, restore the config files
   rendered into its worktree, take its working copy out of the runtime's checkout, and stop
   nothing."
  [project-dir session]
  (let [instance-id (:instance-id session)
        session-name (get (state/members session) (str project-dir))]
    (state/write-session! instance-id (-> (unrender-member! project-dir session)
                                          (assoc :members (dissoc (state/members session) (str project-dir)))))
    (state/remove-from-registry! (str project-dir))
    (sync-checkout! session (dissoc (state/members session) (str project-dir)))
    (when session-name
      (try (launcher/remove-artifacts! (:project-name session) session-name)
           (catch Exception e
             (core/log-step (str "warning: failed to remove launcher artifacts: "
                                 (ex-message e))))))
    (println "Left session" instance-id "— still running for"
             (str/join ", " (vals (dissoc (state/members session) (str project-dir)))))))

(defn ^{:malli/schema [:=> [:cat :Path] :any]}
  stop-session!
  "Take the session in `project-dir` off its runtime, stopping the runtime's services when no
   other session is on it."
  [project-dir]
  (let [instance-id (resolve-instance-id project-dir)
        session (or (some-> instance-id state/read-session)
                    ;; Legacy fallback
                    (when-let [legacy (read-legacy-session project-dir)]
                      (core/log-step "Found legacy session format, converting...")
                      legacy))]
    (cond
      (not session)
      ;; A start that never completed wrote no session.edn, yet a service it
      ;; reached may have left a detached process on its own record.
      (do (doseq [svc-def (:services (read-session-edn (resolve-project-name project-dir)))]
            (try (service/stop-recorded! svc-def instance-id)
                 (catch Exception e
                   (println (str "warning: error stopping " (name (:name svc-def)) ": "
                                 (ex-message e))))))
          (println "No session to stop for" project-dir))

      (seq (dissoc (state/members session) (str project-dir)))
      (leave! project-dir session)

      :else
      (do
        (core/log-step (str "Stopping session " instance-id))
        (doseq [dir (keys (:member-states session))]
          (unrender-member! dir session))
        (stop-services! session instance-id)
        (when (:checkout session)
          (checkout/remove! instance-id (str project-dir)))
        ;; Clean up state
        (when instance-id (state/delete-session! instance-id))
        (state/remove-from-registry! (str project-dir))
        ;; Clean up legacy session file if it exists
        (let [legacy-file (str (fs/path project-dir ".codex" "session.edn"))]
          (fs/delete-if-exists legacy-file))
        ;; Clean up .nrepl-port — in the worktree the runtime ran out of too, which is not this
        ;; one when the last session off a shared runtime is not the one that started it.
        (doseq [dir (distinct [(str project-dir) (str (or (:project-dir session) project-dir))])]
          (fs/delete-if-exists (str (fs/path dir ".nrepl-port"))))
        ;; Remove nido-managed CLAUDE.md
        (try (agent-guidance/remove! project-dir)
             (catch Exception e
               (core/log-step (str "warning: failed to remove agent CLAUDE.md: "
                                   (ex-message e)))))
        (try (launcher/remove-artifacts! (:project-name session)
                                         (get (state/members session) (str project-dir) (:name session)))
             (catch Exception e
               (core/log-step (str "warning: failed to remove launcher artifacts: "
                                   (ex-message e)))))
        (println "Stopped session" instance-id)))))

(defn ^{:malli/schema [:function [:=> [:cat :Path] [:maybe :Path]]
                       [:=> [:cat :Path [:maybe :InstanceId]] [:maybe :Path]]]}
  sync-runtime!
  "Bring the checkout of the runtime the session in `project-dir` is on up to date with every
   member's working copy, snapshotting each, so what they changed — code or not — is what the
   runtime's JVM and app read. Returns the checkout's directory; nil when the runtime runs out of a
   member's worktree, where there is nothing to bring up to date. Throws when no session is up in
   `project-dir`, and, changing nothing, when the members' working copies conflict.

   Membership is read here, so a caller runs this holding the lock of runtime `instance-id` — the
   one `resolve-instance-id` gave it before taking that lock — as every join and departure does:
   a membership read before a departure would put the departed member's files back. Throws, as
   when no session is up, when `project-dir` is no longer on that runtime."
  ([project-dir] (sync-runtime! project-dir (resolve-instance-id project-dir)))
  ([project-dir instance-id]
   (let [runtime (some-> instance-id state/read-session)]
     (when-not (and runtime (= instance-id (resolve-instance-id project-dir)))
       (throw (ex-info (str "No session is up in " project-dir) {:reason :no-session})))
     (when (:checkout runtime)
       (checkout/refresh! (:instance-id runtime) (state/members runtime))))))

(defn ^{:malli/schema [:=> [:cat :Path] :any]}
  session-status
  "Show status for a project session."
  [project-dir]
  (let [project-name (resolve-project-name project-dir)
        instance-id (resolve-instance-id project-dir)
        session (or (some-> instance-id state/read-session)
                    (read-legacy-session project-dir))]
    (if-not session
      (println "No session found for" project-dir)
      (let [service-defs (or (:service-defs session) [])
            service-states (or (:service-states session) {})]
        (println "nido session:")
        (println "  project:" project-dir)
        (println "  project-name:" project-name)
        (println "  instance-id:" instance-id)
        (doseq [svc-def service-defs]
          (let [svc-name (:name svc-def)
                saved-state (get service-states svc-name)]
            (when saved-state
              (let [status (try
                             (service/service-status svc-def saved-state)
                             (catch Exception e
                               {:alive? false :error (ex-message e)}))]
                (println (str "  " (name svc-name) ":"))
                (doseq [[k v] (sort-by key status)]
                  (println (str "    " (name k) ": " v)))))))
        (println "  state file:" (state/session-state-file instance-id))))))

(defn ^{:malli/schema [:=> [:cat] :any]}
  list-sessions
  "List all sessions tracked by the nido registry."
  []
  (let [registry (state/read-registry)]
    (if (seq registry)
      (doseq [[project-dir entry] (sort-by key registry)]
        (let [repl-pid (:repl-pid entry)
              app-port (:app-port entry)
              pg-port (:pg-port entry)]
          (println "-")
          (println "  project:" project-dir)
          (when-let [name (:project-name entry)]
            (println "  name:" name))
          (println "  url:" (:url entry))
          (println "  app port:" app-port)
          (println "  nrepl port:" (:nrepl-port entry))
          (println "  repl pid:" repl-pid)
          (println "  repl alive:" (and (pos-int? repl-pid) (proc/process-alive? repl-pid)))
          (println "  app listening:" (and (pos-int? app-port) (proc/tcp-open? app-port)))
          (when pg-port
            (println "  pg port:" pg-port)
            (println "  pg listening:" (proc/tcp-open? pg-port)))))
      (println "No sessions tracked."))))
