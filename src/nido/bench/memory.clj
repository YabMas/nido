(ns nido.bench.memory
  "Memory measurement harness for a project's dev JVM under nido.

   For each lever (a named variation of deps.edn + JVM opts + aliases),
   the harness:
     1. Mutates the session worktree's deps.edn if the lever requires it.
     2. Starts a session via the normal nido lifecycle (which boots PG +
        JVM + app in single-phase per B2).
     3. Snapshots the repl JVM's memory via `ps` (RSS) and `jcmd`
        (NativeMemoryTracking summary + heap info).
     4. Stops the session and restores deps.edn.

   Levers marked :cp-only? true skip boot and only report the classpath
   JAR size under the patched deps — a quick upper-bound for what
   removing those deps would save.

   Results land at ~/.nido/state/bench/<project>/<timestamp>/results.csv."
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [clojure.edn :as edn]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [nido.config :as config]
   [nido.core :as core]
   [nido.process :as proc]
   [nido.session.engine :as engine]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.state :as state]))

;; ---------------------------------------------------------------------------
;; Lever catalog
;; ---------------------------------------------------------------------------

(def default-jvm-extra-opts
  "Opts added to every bench variation so the JVM exposes native-memory
   tracking data. Cost: ~5-10% throughput + a per-class overhead. That's
   acceptable for a one-shot measurement.

   The `-J` prefix is required by the `clojure` CLI — without it,
   `-XX:…` would be parsed as an alias selector (`-X:…`), corrupting
   the launch line."
  ["-J-XX:NativeMemoryTracking=summary"])

(def default-levers
  "Per-brian lever definitions. Each lever mutates deps.edn and/or JVM
   opts along one axis so its memory delta is attributable.

   Fields:
     :name         - short kw identifier (row key in results.csv)
     :doc          - human description
     :cp-only?     - true → classpath-size only, don't boot
     :aliases      - :jvm-aliases override for session:init (defaults to
                     session.edn :defaults :jvm :aliases if absent)
     :extra-opts   - extra JVM opts appended to the default NMT flag
     :heap-max     - :jvm-heap-max override (string, e.g. \"2g\")
     :remove-deps  - set of symbols to remove from top-level :deps
     :remove-alias-deps - {alias-kw #{sym ...}} to remove from
                          :aliases <alias> :extra-deps"
  [{:name :baseline
    :doc "Current defaults (from session.edn + deps.edn)."}

   {:name :drop-techascent
    :doc "Remove techascent/* + scicloj/tablecloth (unused in src/main)."
    :remove-deps #{'techascent/tech.ml.dataset
                   'techascent/tech.ml.dataset.sql
                   'techascent/tech.viz
                   'scicloj/tablecloth}}

   {:name :drop-rad-dev
    :doc "Use Maven fulcro/fulcro-rad/pathom3 instead of local-repo sources."
    :aliases [:dev :cider/nrepl]}

   {:name :drop-dev-extras
    :doc "Strip Portal + clj-async-profiler + fulcro-inspect from :dev alias."
    :remove-alias-deps
    {:dev #{'djblue/portal
            'com.clojure-goes-fast/clj-async-profiler
            'com.fulcrologic/fulcro-inspect
            'criterium/criterium}}}

   {:name :slim-tika
    :doc "Drop tika-parsers-standard-package, keep tika-core."
    :remove-deps #{'org.apache.tika/tika-parsers-standard-package}}

   {:name :dedupe-aws-sdk
    :doc "Classpath-only: drop software.amazon.awssdk/*. Boot would fail (s3.clj uses both SDKs) — upper-bound only."
    :cp-only? true
    :remove-deps #{'software.amazon.awssdk/auth
                   'software.amazon.awssdk/s3}}

   {:name :string-dedup+meta-cap
    :doc "G1 string deduplication + MaxMetaspaceSize cap at 256m."
    :extra-opts ["-J-XX:+UseStringDeduplication"
                 "-J-XX:MaxMetaspaceSize=256m"]}

   {:name :zgc
    :doc "Switch from G1 to ZGC (better memory return to OS)."
    :extra-opts ["-J-XX:+UseZGC"]}

   {:name :drop-tika-entirely
    :doc "Classpath-only: drop all tika + bcjmail (hypothetical: move conversion to separate service)."
    :cp-only? true
    :remove-deps #{'org.apache.tika/tika-core
                   'org.apache.tika/tika-parsers-standard-package
                   'org.bouncycastle/bcjmail-jdk18on
                   'org.apache.pdfbox/pdfbox
                   'org.apache.poi/poi
                   'org.apache.poi/poi-ooxml}}])

;; ---------------------------------------------------------------------------
;; deps.edn mutation — reversible via backup file
;; ---------------------------------------------------------------------------

(defn- read-deps-edn [path]
  (edn/read-string (slurp path)))

(defn- write-deps-edn! [path data]
  ;; *print-meta* preserves metadata like ^:antq/exclude that deps.edn uses
  ;; for dep-bump tooling. Formatting changes vs. the hand-authored file,
  ;; but the content is equivalent and this file is restored from backup
  ;; after each lever runs.
  (binding [*print-meta* true]
    (spit path (with-out-str (pprint/pprint data)))))

(defn- apply-deps-patch! [deps-path {:keys [remove-deps remove-alias-deps]}]
  (when (or (seq remove-deps) (seq remove-alias-deps))
    (let [deps (read-deps-edn deps-path)
          deps' (cond-> deps
                  (seq remove-deps)
                  (update :deps #(apply dissoc % remove-deps))

                  (seq remove-alias-deps)
                  (update :aliases
                          (fn [aliases]
                            (reduce-kv
                             (fn [m alias-kw syms]
                               (update-in m [alias-kw :extra-deps]
                                          (fn [d] (apply dissoc d syms))))
                             aliases
                             remove-alias-deps))))]
      (write-deps-edn! deps-path deps'))))

(defn- backup-deps! [deps-path backup-path]
  (fs/copy deps-path backup-path {:replace-existing true}))

(defn- restore-deps! [deps-path backup-path]
  (when (fs/exists? backup-path)
    (fs/copy backup-path deps-path {:replace-existing true})))

;; ---------------------------------------------------------------------------
;; Measurement primitives
;; ---------------------------------------------------------------------------

(defn- jcmd
  "Run `jcmd <pid> <args...>`. Returns stdout on success, nil otherwise."
  [pid & args]
  (let [{:keys [exit out]} (apply shell {:continue true :out :string :err :string}
                                  "jcmd" (str pid) args)]
    (when (zero? exit) out)))

(defn- parse-nmt-summary
  "Parse `jcmd <pid> VM.native_memory summary` output into a map of
   category-name → {:reserved-bytes :committed-bytes}.

   Sample section we match:
       -                      Class (reserved=1234KB, committed=567KB)
                              (classes #…)"
  [text]
  (when text
    (let [unit->bytes {"KB" 1024 "MB" (* 1024 1024) "GB" (* 1024 1024 1024)}
          parse-size (fn [n-str u] (when (and n-str u)
                                     (* (parse-long n-str) (get unit->bytes u 1))))
          re #"-\s+(\w[\w\s]*?)\s+\(reserved=(\d+)(KB|MB|GB), committed=(\d+)(KB|MB|GB)\)"]
      (into {}
            (for [[_ cat r-n r-u c-n c-u] (re-seq re text)]
              [(str/trim cat)
               {:reserved-bytes (parse-size r-n r-u)
                :committed-bytes (parse-size c-n c-u)}])))))

(defn- nmt-total
  "Parse the Total line: `Total: reserved=…, committed=…`."
  [text]
  (when text
    (when-let [[_ r-n r-u c-n c-u]
               (re-find #"Total:\s+reserved=(\d+)(KB|MB|GB),\s+committed=(\d+)(KB|MB|GB)"
                        text)]
      (let [unit->bytes {"KB" 1024 "MB" (* 1024 1024) "GB" (* 1024 1024 1024)}]
        {:reserved-bytes (* (parse-long r-n) (unit->bytes r-u))
         :committed-bytes (* (parse-long c-n) (unit->bytes c-u))}))))

(defn snapshot
  "Return a memory snapshot for a pid: :rss, :nmt-total, and per-category
   committed bytes for the categories we care about. Unknown/unavailable
   fields are nil."
  [pid]
  (let [nmt-text (jcmd pid "VM.native_memory" "summary")
        nmt (parse-nmt-summary nmt-text)
        total (nmt-total nmt-text)
        get-committed (fn [cat] (get-in nmt [cat :committed-bytes]))]
    {:rss-bytes (proc/rss-bytes pid)
     :nmt-total-committed (:committed-bytes total)
     :nmt-total-reserved (:reserved-bytes total)
     :java-heap (get-committed "Java Heap")
     :class (get-committed "Class")
     :code (get-committed "Code")
     :gc (get-committed "GC")
     :thread (get-committed "Thread")
     :compiler (get-committed "Compiler")
     :internal (get-committed "Internal")
     :symbol (get-committed "Symbol")
     :other (get-committed "Other")
     :metaspace (get-committed "Metaspace")}))

;; ---------------------------------------------------------------------------
;; Classpath measurement (cp-only levers)
;; ---------------------------------------------------------------------------

(defn- classpath-size-bytes
  "Run `clj -Spath` in project-dir under the given aliases and sum every
   non-directory entry's size. Excludes source directories (which are
   tiny and would confuse the delta)."
  [project-dir aliases]
  (let [alias-str (when (seq aliases)
                    (str "-A:" (str/join ":" (map (fn [a] (if (keyword? a)
                                                             (subs (str a) 1)
                                                             (str a)))
                                                   aliases))))
        cmd (cond-> ["clojure" "-Spath"]
              alias-str (conj alias-str))
        {:keys [exit out]} (apply shell
                                  {:continue true :out :string :err :string
                                   :dir project-dir}
                                  cmd)]
    (if-not (zero? exit)
      nil
      (->> (str/split (str/trim out) #":")
           (map (fn [p]
                  (try
                    (when (and (fs/exists? p) (fs/regular-file? p))
                      (fs/size p))
                    (catch Exception _ nil))))
           (keep identity)
           (reduce + 0)))))

;; ---------------------------------------------------------------------------
;; Lever execution
;; ---------------------------------------------------------------------------

(defn- resolve-opts
  "Translate a lever into nido session:init opts. Merges NMT-summary into
   :jvm-extra-opts by default so every boot-measurable lever produces
   NMT data."
  [session-edn-defaults {:keys [aliases extra-opts heap-max]}]
  (cond-> {}
    aliases      (assoc :jvm-aliases aliases)
    heap-max     (assoc :jvm-heap-max heap-max)
    :always      (assoc :jvm-extra-opts
                        (vec (concat default-jvm-extra-opts
                                     (or extra-opts [])
                                     ;; Carry through any default extra-opts
                                     ;; declared in session.edn so our bench
                                     ;; doesn't silently drop project config.
                                     (or (get-in session-edn-defaults
                                                 [:jvm :extra-opts])
                                         []))))))

(defn- wait-for-repl-pid
  "After lifecycle/init! returns, the repl pid is in the registry. Pull it
   out once (init! already blocked on app start per B2)."
  [project-dir]
  (-> (state/read-registry) (get project-dir) :repl-pid))

(def ^:private default-settle-ms
  "Default time between successful init! return and the memory snapshot.
   5s is enough for the post-mount GC to settle in the common case; for
   slow-boot levers where background thread pools are still ramping up,
   pass a higher value via :settle-ms."
  5000)

(defn- settle-and-snapshot!
  "Sleep settle-ms to let JIT + background thread pools settle, then
   snapshot."
  [pid settle-ms]
  (Thread/sleep (long settle-ms))
  (snapshot pid))

(defn- run-boot-lever!
  [{:keys [project-name session-name worktree-dir session-edn-defaults
           settle-ms]
    :or {settle-ms default-settle-ms}} lever]
  (let [deps-path (str (fs/path worktree-dir "deps.edn"))
        backup-path (str (fs/path worktree-dir "deps.edn.bench-backup"))
        opts (-> (resolve-opts session-edn-defaults lever)
                 (assoc :project project-name))]
    (backup-deps! deps-path backup-path)
    (try
      (apply-deps-patch! deps-path (select-keys lever [:remove-deps :remove-alias-deps]))
      ;; Make sure no stale session is running.
      (try (lifecycle/stop! session-name opts) (catch Exception _))
      (let [t-start (System/currentTimeMillis)
            _ (lifecycle/init! session-name opts)
            t-booted (System/currentTimeMillis)
            pid (wait-for-repl-pid (str (fs/path worktree-dir)))
            _ (when-not pid
                (throw (ex-info "no repl pid after init"
                                {:lever (:name lever)})))
            snap (settle-and-snapshot! pid settle-ms)]
        (-> snap
            (assoc :lever (:name lever)
                   :phase :post-mount
                   :mode :boot
                   :boot-ms (- t-booted t-start)
                   :settle-ms settle-ms
                   :repl-pid pid)))
      (finally
        (try (lifecycle/stop! session-name opts) (catch Exception _))
        (restore-deps! deps-path backup-path)))))

(defn- run-cp-lever!
  [{:keys [worktree-dir session-edn-defaults]} lever]
  (let [deps-path (str (fs/path worktree-dir "deps.edn"))
        backup-path (str (fs/path worktree-dir "deps.edn.bench-backup"))
        aliases (or (:aliases lever)
                    (get-in session-edn-defaults [:jvm :aliases]))]
    (backup-deps! deps-path backup-path)
    (try
      (apply-deps-patch! deps-path (select-keys lever [:remove-deps :remove-alias-deps]))
      {:lever (:name lever)
       :phase :classpath
       :mode :cp-only
       :classpath-bytes (classpath-size-bytes worktree-dir aliases)}
      (finally
        (restore-deps! deps-path backup-path)))))

;; ---------------------------------------------------------------------------
;; Worktree provisioning — ensure the bench worktree exists before we
;; start mutating files under it.
;; ---------------------------------------------------------------------------

(defn- ensure-worktree!
  [project-name session-name]
  (let [[_ {:keys [directory]}] (or (find (config/read-projects) project-name)
                                    (throw (ex-info "unknown project"
                                                    {:project-name project-name})))
        wt-path (lifecycle/worktree-path project-name directory session-name)]
    (when-not (fs/exists? wt-path)
      (core/log-step (str "Creating bench worktree at " wt-path))
      ;; init! creates the worktree and starts the session. We immediately
      ;; stop so subsequent levers start from a clean slate.
      (lifecycle/init! session-name {:project project-name})
      (try (lifecycle/stop! session-name {:project project-name}) (catch Exception _)))
    wt-path))

;; ---------------------------------------------------------------------------
;; Orchestration + CSV output
;; ---------------------------------------------------------------------------

(defn- result-dir [project-name]
  (let [ts (str/replace (core/now-iso) #"[:.]" "-")]
    (str (fs/path (core/nido-home) "state" "bench" project-name ts))))

(def ^:private csv-columns
  [:lever :mode :rss-bytes :nmt-total-committed :java-heap :class :code :gc
   :thread :metaspace :classpath-bytes :boot-ms :settle-ms :repl-pid :error])

(defn- csv-header []
  (str (str/join "," (map name csv-columns)) "\n"))

(defn- csv-row [row]
  (str (str/join ","
                 (for [c csv-columns]
                   (let [v (get row c)]
                     (cond
                       (nil? v) ""
                       (keyword? v) (name v)
                       (string? v) (str "\"" (str/replace v "\"" "\"\"") "\"")
                       :else (str v)))))
       "\n"))

(defn- println-result [row]
  (let [fmt-mb #(if % (format "%.0f MB" (/ % 1024.0 1024.0)) "—")]
    (println
     (format "  %-28s %-9s rss=%-9s heap=%-9s class=%-9s code=%-9s cp=%-9s boot=%ss"
             (name (:lever row))
             (name (or (:mode row) "?"))
             (fmt-mb (:rss-bytes row))
             (fmt-mb (:java-heap row))
             (fmt-mb (:class row))
             (fmt-mb (:code row))
             (fmt-mb (:classpath-bytes row))
             (if (:boot-ms row) (format "%.1f" (/ (:boot-ms row) 1000.0)) "—")))))

(defn run-all!
  "Run all levers for a project and write a CSV with per-lever memory
   facts. Single positional arg: project-name. Options:
     :session-name  session name to use (default \"perf-bench\")
     :levers        vector of lever names to run (default: all)
     :settle-ms     ms to wait after init! before snapshot (default 5000).
                    Raise this when testing slow-boot levers — the snapshot
                    then reflects steady-state rather than still-ramping-up
                    background thread pools."
  [project-name & {:keys [session-name levers settle-ms]
                   :or {session-name "perf-bench"
                        settle-ms default-settle-ms}}]
  (let [session-edn (engine/load-session-edn project-name)
        defaults (:defaults session-edn)
        worktree-dir (ensure-worktree! project-name session-name)
        ctx {:project-name project-name
             :session-name session-name
             :worktree-dir worktree-dir
             :session-edn-defaults defaults
             :settle-ms settle-ms}
        chosen (cond->> default-levers
                 (seq levers) (filter #(contains? (set levers) (:name %))))
        out-dir (result-dir project-name)
        csv-path (str (fs/path out-dir "results.csv"))]
    (fs/create-dirs out-dir)
    (spit csv-path (csv-header))
    (println (str "Bench run: project=" project-name
                  " session=" session-name
                  " worktree=" worktree-dir))
    (println (str "Results: " csv-path))
    (println (str (count chosen) " lever(s) to run."))
    (println)
    (let [results
          (doall
           (for [lever chosen]
             (let [row (try
                         (if (:cp-only? lever)
                           (run-cp-lever! ctx lever)
                           (run-boot-lever! ctx lever))
                         (catch Exception e
                           {:lever (:name lever)
                            :mode :error
                            :error (ex-message e)}))]
               (spit csv-path (csv-row row) :append true)
               (println-result row)
               row)))]
      (println)
      (println (str "Wrote " (count results) " row(s) to " csv-path))
      {:csv csv-path :rows results})))
