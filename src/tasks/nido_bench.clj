(ns tasks.nido-bench
  "Bb task entry points for bench runs.

   Examples:
     bb nido:bench:memory :project \"brian\"
     bb nido:bench:memory :project \"brian\" :levers [:baseline :drop-techascent]
     bb nido:bench:memory:levers
     bb nido:census [:port 8800] [:loads 3] [:project nido] [:deadline-s 90] [:out census.edn]"
  (:require
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [nido.bench.memory :as bench]
   [nido.coordinator.record.workstream :as cws]
   [nido.ui.server :as server]))

(defn- parse-opts [args]
  (if (empty? args)
    {}
    (let [parse-arg (fn [arg]
                      (try (edn/read-string arg) (catch Exception _ arg)))
          values (map parse-arg args)]
      (when (odd? (count values))
        (throw (ex-info "Options must be key/value pairs" {:args args})))
      (apply hash-map values))))

(defn- require-project [opts]
  (or (some-> (:project opts) name)
      (throw (ex-info "Missing :project <name>"
                      {:hint "Pass :project \"<project-name>\"."}))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  memory
  "Run the memory-bench matrix for a project. See nido.bench.memory/run-all!."
  [& args]
  (let [opts (parse-opts args)
        project-name (require-project opts)
        session-name (or (some-> (:session opts) name) "perf-bench")
        levers (:levers opts)
        settle-ms (:settle-ms opts)
        ;; The benchmark stops and starts the session; a workstream holding it keeps it on the
        ;; workstream's runtime, which only nido can say.
        ws-id (cws/of-session (keyword project-name) session-name)
        kwargs (cond-> {:session-name session-name}
                 ws-id (assoc :runtime-key ws-id)
                 levers (assoc :levers levers)
                 settle-ms (assoc :settle-ms settle-ms))]
    (apply bench/run-all! project-name (mapcat identity kwargs))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  levers
  "List known bench levers."
  [& _args]
  (doseq [{:keys [name doc cp-only?]} bench/default-levers]
    (println (format "  %-28s %s%s"
                     (clojure.core/name name)
                     (if cp-only? "[cp-only] " "")
                     doc))))

;; ---------------------------------------------------------------------------
;; The census: every page the dashboard lists, as a browser loads it

(def ^:private census-wall-ms
  "The whole census's wall clock, from its start to the browser closed: starting the browser,
   finding each parameterised page's instance, every load and closing it each wait only for what is
   left of it. A load is not started once the loads'
   share is spent; what was not taken is reported as not measured, never dropped. Under the ten
   minutes a census must end within."
  (* 9 60 1000))

(def ^:private close-reserve-ms
  "What of `census-wall-ms` the loads leave for closing the browser."
  30000)

(defn- now-ms [] (System/currentTimeMillis))

(defn- within
  "`(f)`, or ::timed-out when it has not answered within `ms`. It runs on a daemon thread, so a call
   that never answers — a core that accepted the connection and stopped replying — neither holds the
   census past its wall clock nor keeps the process alive after it."
  [ms f]
  (let [r (promise)
        t (doto (Thread. ^Runnable (fn [] (deliver r (try {:ok (f)} (catch Throwable e {:thrown e})))))
            (.setDaemon true)
            (.start))]
    (let [v (deref r ms ::timed-out)]
      (if (= ::timed-out v)
        (do (.interrupt t) ::timed-out)
        (if-let [e (:thrown v)] (throw e) (:ok v))))))

(def ^:private nav-timing-js
  "The browser's navigation timing of the document it is showing, as JSON."
  (str "JSON.stringify((e => e && {name: e.name, type: e.type, status: e.responseStatus,"
       " responseStart: e.responseStart,"
       " domContentLoaded: e.domContentLoadedEventEnd, load: e.loadEventEnd})"
       "(performance.getEntriesByType('navigation')[0]))"))

(defn- browser
  "Run agent-browser on `session` — `{:name :timeout-ms}` — with `args`, waiting at most `ms`: its
   trimmed stdout, or ::timed-out (the process is killed) or {::failed <stderr>}."
  [{session :name :keys [timeout-ms]} ms & args]
  (let [linux? (str/includes? (str/lower-case (System/getProperty "os.name")) "linux")
        cmd    (concat ["agent-browser" "--session" session]
                       ;; Chrome refuses to start without a usable sandbox where unprivileged
                       ;; user namespaces are off (Ubuntu 23.10+); the census loads loopback only.
                       (when linux? ["--args" "--no-sandbox"])
                       args)
        ;; agent-browser's own action timeout (25s by default) would cut a navigation before
        ;; the census's deadline does. One value for the session's every command: a command
        ;; with a different environment relaunches the browser, which restores the page it was
        ;; showing — a reload, not the load the census asked for.
        proc   (p/process cmd {:out :string :err :string
                               :extra-env {"AGENT_BROWSER_DEFAULT_TIMEOUT" (str timeout-ms)}})
        r      (deref proc ms ::timed-out)]
    (cond
      (= ::timed-out r) (do (p/destroy-tree proc) ::timed-out)
      (zero? (:exit r)) (str/trim (:out r))
      :else             {::failed (str/trim (str (:err r) (:out r)))})))

(defn- load-once
  "One load of `url` in `session`, within `ms`: `{:ms <loadEventEnd>}` read from the browser's
   navigation timing of that load, or `{:error …}` — among them a timing that is not of a
   navigation to `url` (a reload, or another document), and a load not answered 200."
  [session url ms]
  (let [t0 (System/currentTimeMillis)
        o  (browser session ms "open" url)]
    (cond
      (= ::timed-out o) {:error :timed-out}
      (map? o)          {:error (::failed o)}
      :else
      (let [left (- ms (- (System/currentTimeMillis) t0))
            raw  (if (pos? left) (browser session left "eval" nav-timing-js) ::timed-out)
            e    (when (string? raw)
                   (try (some-> (json/parse-string (json/parse-string raw)) not-empty)
                        (catch Exception _ nil)))]
        (cond
          (= ::timed-out raw)                          {:error :timed-out}
          (not (map? e))                               {:error (str "no navigation timing: " raw)}
          (not (and (= url (get e "name")) (= "navigate" (get e "type"))))
          {:error (str "timing not of this load: " (get e "type") " " (get e "name"))}
          (not= 200 (get e "status"))                  {:error (str "HTTP " (get e "status"))}
          (not (pos? (double (or (get e "load") 0)))) {:error :load-not-finished}
          :else {:ms (Math/round (double (get e "load")))
                 :response-ms (Math/round (double (get e "responseStart" 0)))})))))

(defn ^{:malli/schema [:=> [:cat [:sequential number?]] [:maybe number?]]}
  median
  "The median of `xs`, or nil when there are none."
  [xs]
  (when (seq xs)
    (let [v (vec (sort xs)) n (count v) h (quot n 2)]
      (if (odd? n) (v h) (/ (+ (v (dec h)) (v h)) 2.0)))))

(defn ^{:malli/schema [:=> [:cat :map :any] [:vector :map]]}
  census*
  "Load every page of `(server/page-routes)` `:loads` times through `load!` — `(load! url ms)` →
   `{:ms n}` or `{:error e}` — and answer one row per page: `:path`, `:url` (nil when a
   parameterised page has no instance), `:samples` (ms), `:median`, `:errors`, and `:status`
   :ok, :timed-out (a load, or finding a parameterised page's instance, missed its deadline),
   :failed, :no-instance or :not-measured (the census's budget was spent before it, or before its
   instance was sought). Every wait is bounded by what is left of `:budget-ms`. A page is never
   dropped. Pages are loaded round-robin, so whatever the budget cuts is spread across pages rather
   than taken from the last ones."
  [{:keys [base loads project deadline-ms budget-ms] :as _opts} load!]
  (let [t0    (now-ms)
        left  #(- budget-ms (- (now-ms) t0))
        pages (mapv (fn [{:keys [path instance] :as pg}]
                      (let [spent (and instance (<= (left) 0))
                            inst  (cond (not instance) path
                                        spent          nil
                                        :else          (try (within (left) #(instance {:project project}))
                                                            (catch Exception _ nil)))
                            lost  (= ::timed-out inst)]
                        (cond-> (assoc pg :url (when-not lost (some->> inst (str base))) :samples []
                                       :errors (if lost [:timed-out] []))
                          spent (assoc :unsought true))))
                    (:pages (server/page-routes)))
        rows  (reduce
               (fn [rows _round]
                 (mapv (fn [{:keys [url] :as r}]
                         (cond
                           (nil? url) r
                           (< (left) 1000) (update r :skipped (fnil inc 0))
                           :else (let [o (load! url (min deadline-ms (left)))]
                                   (if (:ms o)
                                     (update r :samples conj (:ms o))
                                     (update r :errors conj (:error o))))))
                       rows))
               pages (range loads))]
    (mapv (fn [{:keys [url samples errors skipped unsought] :as r}]
            (-> (select-keys r [:path :title :url :samples :errors :found-by])
                (assoc :median (median samples)
                       :status (cond
                                 (some #{:timed-out} errors) :timed-out
                                 unsought :not-measured
                                 (nil? url) :no-instance
                                 (seq errors) :failed
                                 (and skipped (< (count samples) loads)) :not-measured
                                 :else :ok))))
          rows)))

(defn- print-census [rows {:keys [base loads elapsed-ms]}]
  (println (format "census · %s · %d load(s) per page · %.1fs" base loads (/ elapsed-ms 1000.0)))
  (println (format "  %-34s %9s  %-10s %s" "page" "median" "status" "samples (ms)"))
  (doseq [{:keys [path url median status samples errors]}
          (sort-by (comp - #(or % -1) :median) rows)]
    (println (format "  %-34s %9s  %-10s %s%s"
                     (if (and url (not= path (str/replace url #"^https?://[^/]+" ""))) (str/replace url #"^https?://[^/]+" "") path)
                     (if median (format "%.0fms" (double median)) "—")
                     (name status)
                     (str/join " " samples)
                     (if (seq errors) (str "  errors: " (str/join "; " (map str errors))) ""))))
  (println "  left out, as the dashboard's page list names them:")
  (doseq [{:keys [route why]} (:left-out (server/page-routes))]
    (println (str "    " route " — " why))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  census
  "bb nido:census [:port n] [:loads n] [:project p] [:deadline-s n] [:out path] — every page the
   dashboard on that loopback port lists (`nido.ui.server/page-routes`, its parameterised ones by
   the instance it names for :project, default nido), each loaded :loads times (default and least
   3) in a browser driven through agent-browser, printed as one overview: each page's median of
   the loads' navigation timing (loadEventEnd), and its samples. Every load has a deadline
   (:deadline-s, default 90) and the whole census, browser start and close included, a nine-minute
   wall clock; a page whose load missed one is
   reported with the samples it has. :out also writes the rows as EDN. The census loads every page
   the list holds and leaves out only what the list names as left out."
  [& args]
  (let [opts    (parse-opts args)
        port    (or (:port opts) 8800)
        loads   (max 3 (or (:loads opts) 3))
        project (name (or (:project opts) "nido"))
        base    (str "http://localhost:" port)
        deadline (* 1000 (or (:deadline-s opts) 90))
        session {:name (str "nido-census-" port) :timeout-ms deadline}
        t0      (now-ms)
        left    #(- (+ t0 census-wall-ms) (now-ms))
        _       (browser session (min 60000 (- (left) close-reserve-ms)) "open" "about:blank")
        rows    (try
                  (census* {:base base :loads loads :project project
                            :deadline-ms deadline
                            :budget-ms (- (left) close-reserve-ms)}
                           (partial load-once session))
                  (finally (browser session (max 1 (left)) "close")))
        elapsed (- (now-ms) t0)]
    (print-census rows {:base base :loads loads :elapsed-ms elapsed})
    (when-let [out (:out opts)]
      (spit (str out) (pr-str {:base base :loads loads :elapsed-ms elapsed
                               :at (str (java.time.Instant/now)) :pages rows}))
      (println "  written to" (str out)))
    rows))
