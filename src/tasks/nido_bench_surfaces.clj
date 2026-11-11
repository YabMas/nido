(ns tasks.nido-bench-surfaces
  "Bb task entry points for the surface bench: how long the dashboard's pages and the TUI's
   screens take to render, against a clone of the live store and against that clone enlarged
   with synthetic settled workstreams and finished analyses.

   Never reads or writes the live store in place. `surfaces` APFS-clones ~/.nido (less the
   per-session databases and checkouts, which no surface reads) into a temporary directory and
   runs every measurement in a child process whose NIDO_HOME is that clone, so a cache a render
   writes beside the records lands in the clone and dies with it.

   Examples:
     bb nido:bench:surfaces
     bb nido:bench:surfaces :project \"brian\" :synthetic 4000 :runs 7"
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [nido.coordinator.work :as work]
   [nido.platform.core :as core]
   [nido.ui.server :as server]
   [nido.ui.tui :as tui]))

(def ^:private uncloned
  "Top-level store directories no surface reads, left out of the clone: per-session
   PGDATA, shared and template clusters, test clusters, run checkouts and workspaces —
   tens of gigabytes that would make the clone the slowest part of the bench."
  #{"state" "shared" "templates" "test-pg" "run-checkouts" "workspaces"})

(defn- parse-opts [args]
  (let [vs (map #(try (edn/read-string %) (catch Exception _ %)) args)]
    (when (odd? (count vs))
      (throw (ex-info "Options must be key/value pairs" {:args args})))
    (apply hash-map vs)))

(defn- clone-store!
  "Clone the live store's surface-read directories into `dest` with `cp -c` (APFS
   clonefile: no data is copied, and nothing written to the clone reaches the original)."
  [dest]
  (let [home (core/nido-home)]
    (fs/create-dirs dest)
    (doseq [f (fs/list-dir home)
            :when (not (contains? uncloned (str (fs/file-name f))))]
      (p/shell "cp" "-cR" (str f) (str dest "/")))))

(defn- synthetic-settled!
  "Add `n` settled workstreams to `project`'s workstreams under `home`, each closed
   :dropped with a two-entry ledger and one archived session — the shape most of the
   live store's closed history has."
  [home project n]
  (let [root (fs/path home "projects" project "workstreams")
        at   "2026-01-01T00:00:00Z"]
    (dotimes [i n]
      (let [id  (format "ws-29990101-%06x" i)
            sn  (str "bench-synthetic-" i)
            dir (fs/path root id)
            e1  {:kind :closed :seq 1 :at at :file "entries/0001-closed.edn"}
            e2  {:kind :scratch :seq 2 :at at :file "entries/0002-scratch.edn"}]
        (fs/create-dirs (fs/path dir "entries"))
        (fs/create-dirs (fs/path dir "entries.meta"))
        (fs/create-dirs (fs/path dir "sessions" sn))
        (spit (str (fs/path dir "workstream.edn"))
              (pr-str {:id id :project (keyword project) :external-refs [] :stage :scratch
                       :stage-history [{:at at :stage :scratch}]
                       :closed {:at at :outcome :dropped} :created-at at :entries [e1 e2]}))
        (spit (str (fs/path dir "entries" "0001-closed.edn")) (pr-str {:format :closed :outcome :dropped :by :bench}))
        (spit (str (fs/path dir "entries" "0002-scratch.edn")) (pr-str {:format :scratch}))
        (spit (str (fs/path dir "entries.meta" "0001.edn")) (pr-str e1))
        (spit (str (fs/path dir "entries.meta" "0002.edn")) (pr-str e2))
        (spit (str (fs/path dir "sessions" sn "session.edn"))
              (pr-str {:name sn :workstream-id id :project (keyword project) :weight :heavy
                       :substrate :archived :substrate-history [{:at at :substrate :archived}]
                       :autonomy nil :created-at at}))))))

(defn- synthetic-analyses!
  "Add `n` finished review-loop analysis workstreams to `project` under `home`: open, a
   :review-run ref, a ledger holding only its filed analysis — which status reads as :analysed —
   and one archived session, the shape of the analyses the review loop files and nobody closes."
  [home project n]
  (let [root (fs/path home "projects" project "workstreams")
        at   "2026-01-01T00:00:00Z"]
    (dotimes [i n]
      (let [id  (format "ws-29990102-%06x" i)
            sn  (str "bench-analysis-" i)
            run (str "bench-run-" i)
            dir (fs/path root id)
            e1  {:kind :review-analysis :seq 1 :at at :file "entries/0001-review-analysis.edn"}]
        (fs/create-dirs (fs/path dir "entries"))
        (fs/create-dirs (fs/path dir "entries.meta"))
        (fs/create-dirs (fs/path dir "sessions" sn))
        (spit (str (fs/path dir "workstream.edn"))
              (pr-str {:id id :project (keyword project)
                       :external-refs [{:adapter :review-run :id run :title run}]
                       :stage :scratch :stage-history [{:at at :stage :scratch}]
                       :created-at at :entries [e1]}))
        (spit (str (fs/path dir "entries" "0001-review-analysis.edn"))
              (pr-str {:format :review-analysis :verdict :degraded :run-id run :status "clean"
                       :rounds 2 :summary "bench" :observations []}))
        (spit (str (fs/path dir "entries.meta" "0001.edn")) (pr-str e1))
        (spit (str (fs/path dir "sessions" sn "session.edn"))
              (pr-str {:name sn :workstream-id id :project (keyword project) :weight :heavy
                       :substrate :archived :substrate-history [{:at at :substrate :archived}]
                       :autonomy nil :created-at at}))))))

(defn- ms [f]
  (let [t0 (System/nanoTime)]
    (f)
    (/ (- (System/nanoTime) t0) 1e6)))

(defn- median [xs]
  (let [s (vec (sort xs))]
    (nth s (quot (count s) 2))))

(defn- get-page [uri]
  (let [[path q] (str/split uri #"\?" 2)
        resp (#'server/handle-get {:uri path :query-string q :request-method :get :headers {}})]
    (when-not (= 200 (:status resp))
      (throw (ex-info (str "GET " uri " answered " (:status resp)) {:uri uri})))
    resp))

(defn- surfaces-of
  "The surfaces timed, in order: [label thunk]. The TUI's navigation is its return from a
   workstream's detail to the board, which is the board read again."
  [project ws-id]
  [[:inbox        #(get-page "/")]
   [:overview     #(get-page "/workstreams")]
   [:detail       #(get-page (str "/workstreams/" project "/" ws-id))]
   [:tui-board    #(#'tui/board-rows project :all)]
   [:tui-detail   #(#'tui/environment-block project ws-id)]
   [:tui-navigate #(do (#'tui/environment-block project ws-id)
                       (#'tui/board-rows project :all))]])

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  measure
  "In THIS process, against whatever store NIDO_HOME names: hold rows as the dashboard and
   the TUI do (`work/hold!`), time the first render of a cold process — of `first`, the gate
   inbox unless it names another surface — then `runs` renders of each surface, and print the
   medians as EDN. The child half of `surfaces`; run it directly only against a store you can
   lose."
  [& args]
  (let [{:keys [project ws runs first] :or {runs 5 first :inbox}} (parse-opts args)
        project (name project)
        _       (work/hold!)
        timed   (surfaces-of project ws)
        cold    (ms (some (fn [[k f]] (when (= first k) f)) timed))
        warm    (into {} (for [[k f] timed :when (pos? runs)]
                           [k (median (repeatedly runs #(ms f)))]))]
    (prn {:cold-first-render cold :renders warm})))

(defn- child-measure!
  "Measure in a fresh child process against the store at `home`: its cold first render is the
   gate inbox's, or the TUI board's — the TUI's cold open — with `first` :tui-board."
  ([home opts] (child-measure! home opts :inbox))
  ([home opts first]
   (let [{:keys [out]} (p/shell {:out :string :extra-env {"NIDO_HOME" (str home)}
                                 :dir (core/nido-source-dir)}
                                "bb" "--config" (str (fs/path (core/nido-source-dir) "bb.edn"))
                                "nido:bench:surfaces:measure"
                                ":project" (pr-str (:project opts))
                                ":ws" (pr-str (:ws opts))
                                ":runs" (if (= :inbox first) (str (:runs opts)) "0")
                                ":first" (str first))]
     (edn/read-string (last (str/split-lines out))))))

(defn- measure-store!
  "Both measurements of the store at `home`: the renders, and the TUI's cold open in a process
   of its own, so neither cold start is warmed by the other."
  [home opts]
  (assoc (child-measure! home opts)
         :cold-tui-open (:cold-first-render (child-measure! home opts :tui-board))))

(defn- workstream-count [home project]
  (count (fs/list-dir (fs/path home "projects" project "workstreams"))))

(defn- print-table [results]
  (let [ks (keys (:renders (second (first results))))]
    (println (format "%-20s %s" "surface (ms)" (str/join "" (map #(format "%14s" (name (first %))) results))))
    (println (format "%-20s %s" "cold first render"
                     (str/join "" (map #(format "%14.0f" (:cold-first-render (second %))) results))))
    (println (format "%-20s %s" "cold TUI open"
                     (str/join "" (map #(format "%14.0f" (:cold-tui-open (second %))) results))))
    (doseq [k ks]
      (println (format "%-20s %s" (name k)
                       (str/join "" (map #(format "%14.0f" (get-in (second %) [:renders k])) results)))))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  surfaces
  "Time the dashboard's gate inbox, Workstreams overview and a workstream's detail, and the
   TUI's board, detail screen and navigation, on a clone of the live store, on that clone
   with `:synthetic` settled workstreams added to `:project`, and on that one with `:analyses`
   finished review-loop analyses added too (0 skips that store). Prints one column per store
   and the store sizes; `:out <path>` also writes the results as EDN.

   Options: :project (default \"nido\"), :ws the detail's workstream (default the newest
   workstream of :project), :synthetic (default 2000), :analyses (default 2000), :runs per
   surface (default 5)."
  [& args]
  (let [opts  (merge {:project "nido" :synthetic 2000 :analyses 2000 :runs 5} (parse-opts args))
        opts  (update opts :project name)
        root  (fs/create-temp-dir {:prefix "nido-bench-"})
        home  (fs/path root "home")]
    (try
      (println "cloning the store into" (str home) "…")
      (clone-store! home)
      (let [opts    (update opts :ws #(or % (->> (fs/list-dir (fs/path home "projects" (:project opts) "workstreams"))
                                                 (map (comp str fs/file-name)) sort last)))
            n0      (workstream-count home (:project opts))
            live    (measure-store! home opts)
            _       (synthetic-settled! home (:project opts) (:synthetic opts))
            n1      (workstream-count home (:project opts))
            big     (measure-store! home opts)
            _       (synthetic-analyses! home (:project opts) (:analyses opts))
            n2      (workstream-count home (:project opts))
            more    (when (pos? (:analyses opts)) (measure-store! home opts))
            results (cond-> [[(str "live " n0) live] [(str "enlarged " n1) big]]
                      more (conj [(str "+analyses " n2) more]))]
        (println (str "project " (:project opts) ", detail " (:ws opts) ", median of " (:runs opts) " runs"))
        (print-table results)
        (when-let [out (:out opts)]
          (spit (str out) (pr-str {:project (:project opts) :ws (:ws opts) :runs (:runs opts)
                                   :stores (into {} results)})))
        results)
      (finally
        (fs/delete-tree root)))))
