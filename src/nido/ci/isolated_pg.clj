(ns nido.ci.isolated-pg
  "Per-step ephemeral PostgreSQL clusters: a CI step opts in with
   `:isolated-pg? true` and gets its own postmaster (cloned from the
   project's template) on a free port, with the step's work-dir local.edn
   rewritten to point at it.

   Why per-step rather than the session's shared workspace PG? The
   running session JVM holds a fat connection pool (brian: ~90 of
   max_connections=100). A test JVM that wants its own pool starves it
   out and either gets `Connection refused`, `sorry, too many clients
   already`, or a mid-fixture pg2 read-timeout. A step-local cluster
   means the test JVM owns 100% of its DB's connection budget; CI does
   not have to fight the live editor session.

   Cleanup is bound to step teardown: the cluster is stopped and its
   data dir is deleted regardless of how the step ended."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [nido.core :as core]
   [nido.process :as proc]
   [nido.session.services.postgresql :as pg-svc]
   [nido.session.state :as state]))

(defn- rewrite-local-edn-port!
  "Replace every occurrence of `from-port` with `to-port` in the
   work-dir's `local.edn`, if the file exists. brian's nido-written
   local.edn mentions the shared port in four places (httpkit, legacy
   postgres, pg2, RAD hikari); a literal port-number rewrite keeps them
   in sync without parsing the structure."
  [work-dir from-port to-port]
  (when (and from-port to-port (not= from-port to-port))
    (let [file (str (fs/path work-dir "local.edn"))]
      (when (fs/exists? file)
        (let [old (slurp file)
              new (str/replace old (str from-port) (str to-port))]
          (when-not (= old new)
            (spit file new)))))))

(defn rewrite-app-port!
  "Like `rewrite-local-edn-port!` but for the HTTP listener. brian's
   `:org.httpkit.server/config :port` carries the app port; literal
   replace works because the session-rendered local.edn writes it as a
   bare number."
  [work-dir from-port to-port]
  (rewrite-local-edn-port! work-dir from-port to-port))

(defn- deep-merge
  "Recursive map merge — later wins. Non-map values replace verbatim,
   so an override that supplies a vector replaces (rather than appends
   to) any existing vector at the same key."
  [a b]
  (cond
    (and (map? a) (map? b)) (merge-with deep-merge a b)
    :else                   (if (some? b) b a)))

(defn merge-local-edn-overrides!
  "Deep-merge `overrides` into the work-dir's local.edn (read as EDN,
   spit back). Used to layer ci.edn step-level `:local-edn-overrides`
   on top of the session-rendered file — e.g. an e2e step adding
   `:dev/auto-init? true` so the spawned brian app boots itself.

   No-op when overrides is nil/empty or the file is missing."
  [work-dir overrides]
  (when (seq overrides)
    (let [file (str (fs/path work-dir "local.edn"))]
      (when (fs/exists? file)
        (let [base (try (edn/read-string (slurp file))
                        (catch Exception _ nil))]
          (when (map? base)
            (let [merged (deep-merge base overrides)]
              (spit file (with-out-str (pprint/pprint merged))))))))))

(def ^:private socket-base-dir
  "Short, shared base for Unix-domain sockets. PGDATA paths under
   `~/.nido/state/<…>/runs/<…>/steps/<…>/work/pg-data` blow past macOS's
   `sun_path` 103-byte limit; pointing PG's `-k` at a short path keeps
   the socket file (`.s.PGSQL.<port>`) under that limit. Different
   ports → different socket file names, so multiple per-step clusters
   coexist here without collision."
  "/tmp/nido-pg-sock")

(defn- pick-template-data-dir
  "Prefer the project's `<project>--ci` template when present, falling
   back to the regular `<project>` template. The CI variant is meant to
   be schema-only — cloning a production-shaped dev template into a CI
   step turns ordinary cascade DELETEs in fixtures into multi-minute
   sequential scans. Users who haven't set up a CI variant get the dev
   template (correct, just slower) so opting into `:isolated-pg?`
   without prior setup still works."
  [project-name]
  (let [ci-dir (state/template-pg-data-dir (str project-name "--ci"))
        dev-dir (state/template-pg-data-dir project-name)]
    (cond
      (fs/exists? ci-dir)  {:template-data-dir ci-dir  :variant :ci}
      (fs/exists? dev-dir) {:template-data-dir dev-dir :variant :default}
      :else nil)))

(defn start!
  "Start an ephemeral PG cluster for one step. Allocates a free port,
   APFS-clones the project's template PGDATA into <work-dir>/pg-data,
   starts the postmaster, waits for TCP, and rewrites the work-dir's
   local.edn so the step's command will connect there.

   `from-port` is the shared workspace port to substitute out of
   local.edn — typically the session's `:pg-port`. Returns the started
   cluster's state map (used by `stop!`)."
  [{:keys [project-name work-dir from-port port-range]
    :or {port-range [5500 7500]}}]
  (when-not project-name
    (throw (ex-info ":project-name is required for isolated PG"
                    {:work-dir work-dir})))
  (when-not (and work-dir (fs/exists? work-dir))
    (throw (ex-info ":work-dir does not exist; cannot host PGDATA"
                    {:work-dir work-dir})))
  (let [bin-dir (pg-svc/find-pg-bin-dir)
        [low high] port-range
        preferred (proc/deterministic-port (str work-dir) low high)
        pg-port (proc/find-available-port preferred (- high low))
        data-dir (str (fs/path work-dir "pg-data"))
        log-path (str (fs/path work-dir "pg.log"))
        {:keys [template-data-dir variant]} (pick-template-data-dir project-name)]
    (when-not template-data-dir
      (throw (ex-info (str "No template PG cluster for project '" project-name
                           "'. Run `bb nido:template:pg:init :project " project-name
                           "` (and `:refresh`) before opting a CI step into "
                           ":isolated-pg?.")
                      {:project project-name})))
    (fs/create-dirs socket-base-dir)
    (core/log-step
     (str "Starting isolated PG for step "
          "(port " pg-port ", template=" (name variant) ")"))
    (pg-svc/clone-pgdata! template-data-dir data-dir)
    (pg-svc/pg-ctl-start! bin-dir data-dir pg-port log-path socket-base-dir)
    (pg-svc/wait-for-tcp! pg-port)
    (rewrite-local-edn-port! work-dir from-port pg-port)
    {:pg-port pg-port
     :data-dir data-dir
     :log-path log-path
     :template-variant variant}))

(defn stop!
  "Stop the per-step PG cluster and remove its data dir. Idempotent and
   never throws — teardown must run even if the step crashed mid-flight."
  [iso-pg]
  (when-let [data-dir (:data-dir iso-pg)]
    (when (fs/exists? data-dir)
      (try (pg-svc/pg-ctl-stop! data-dir) (catch Exception _ nil))
      (try (fs/delete-tree data-dir) (catch Exception _ nil)))))
