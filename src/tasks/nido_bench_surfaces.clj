(ns tasks.nido-bench-surfaces
  "Bb task entry points for the surface bench: how long the dashboard's pages and the TUI's
   screens take to render, against a clone of the live store and against that clone enlarged
   with synthetic settled workstreams.

   Never reads or writes the live store in place. `surfaces` APFS-clones ~/.nido (less the
   per-session databases and checkouts, which no surface reads) into a temporary directory and
   runs every measurement in a child process whose NIDO_HOME is that clone, so a cache a render
   writes beside the records lands in the clone and dies with it. The core is not cloned: the
   bench snapshots every workstream the live core holds into the clone, and the child answers
   its core requests from that snapshot in memory — so a render's reads are timed, not the
   socket round trips the daemon would add.

   Examples:
     bb nido:bench:surfaces
     bb nido:bench:surfaces :project \"brian\" :synthetic 4000 :runs 7"
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [nido.coordinator.work :as work]
   [nido.core.client :as client]
   [nido.core.domain :as d]
   [nido.core.local :as local]
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

(defn- snapshot-path [home] (fs/path home "bench-core.edn"))

(defn- snapshot-core!
  "Write every workstream the live core holds — its state, and the content of every entry on its
   ledger — into the clone at `home`, for the child to load into a core of its own. The clone of
   the files carries no core: the daemon answers for the live home only."
  [home]
  (let [states (client/value! (client/states "") :states)]
    (spit (str (snapshot-path home))
          (pr-str {:states   states
                   :contents (into {} (for [[k st] states]
                                        [k (client/value! (client/entry-contents k (mapv :seq (:ledger st))) :entries)]))}))))

(defn- add-synthetic!
  "Add workstreams to the snapshot at `home`: `n` of them, the i-th with id `(id-of i)`, the
   identity `(identity-of i)` and the entries `(entries-of i)` — [row content] pairs, numbered
   from 1."
  [home project n id-of identity-of entries-of]
  (let [snap (edn/read-string (slurp (str (snapshot-path home))))
        at   "2026-01-01T00:00:00Z"
        snap (reduce (fn [snap i]
                       (let [k  (client/ws-key project (id-of i))
                             es (map-indexed (fn [n [row content]]
                                               [(assoc row :seq (inc n) :at at :ext "edn") content])
                                             (entries-of i))]
                         (-> snap
                             (assoc-in [:states k] (assoc d/empty-state
                                                          :identity (assoc (identity-of i) :project (keyword project)
                                                                           :created-at at)
                                                          :ledger (mapv first es)))
                             (assoc-in [:contents k] (into {} (map (fn [[r c]] [(:seq r) c])) es)))))
                     snap (range n))]
    (spit (str (snapshot-path home)) (pr-str snap))))

(defn- synthetic-sessions!
  "One archived session record per synthetic workstream, as its session directory holds it."
  [home project n id-of name-of]
  (let [at "2026-01-01T00:00:00Z"]
    (dotimes [i n]
      (let [id (id-of i) sn (name-of i)
            d  (fs/path home "projects" project "workstreams" id "sessions" sn)]
        (fs/create-dirs d)
        (spit (str (fs/path d "session.edn"))
              (pr-str {:name sn :workstream-id id :project (keyword project) :weight :heavy
                       :substrate :archived :substrate-history [{:at at :substrate :archived}]
                       :autonomy nil :created-at at}))))))

(defn- synthetic-settled!
  "Add `n` settled workstreams to `project` in the store at `home`, each closed :dropped with a
   two-entry ledger and one archived session — the shape most of the live store's closed history
   has."
  [home project n]
  (let [id-of #(format "ws-29990101-%06x" %)]
    (add-synthetic! home project n id-of (constantly {:external-refs []})
                    (fn [_] [[{:kind :closed} (pr-str {:format :closed :outcome :dropped :by :bench})]
                             [{:kind :scratch} (pr-str {:format :scratch})]]))
    (synthetic-sessions! home project n id-of #(str "bench-synthetic-" %))))

(defn- load-snapshot!
  "Answer this process's core requests from the snapshot in the store NIDO_HOME names."
  []
  (let [{:keys [states contents]} (edn/read-string (slurp (str (snapshot-path (core/nido-root)))))]
    (client/use-local!)
    (doseq [[k st] states]
      (local/put! k (constantly st) (get contents k)))))

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
        _       (load-snapshot!)
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
  (count (filter #(str/starts-with? % (str project "/"))
                 (keys (:states (edn/read-string (slurp (str (snapshot-path home)))))))))

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
   TUI's board, detail screen and navigation, on a clone of the live store and on that clone
   with `:synthetic` settled workstreams added to `:project`. Prints one column per store and
   the store sizes; `:out <path>` also writes the results as EDN.

   Options: :project (default \"nido\"), :ws the detail's workstream (default the newest
   workstream of :project), :synthetic (default 2000), :runs per surface (default 5)."
  [& args]
  (let [opts  (merge {:project "nido" :synthetic 2000 :runs 5} (parse-opts args))
        opts  (update opts :project name)
        root  (fs/create-temp-dir {:prefix "nido-bench-"})
        home  (fs/path root "home")]
    (try
      (println "cloning the store into" (str home) "…")
      (clone-store! home)
      (snapshot-core! home)
      (let [opts    (update opts :ws #(or % (->> (keys (client/value! (client/states (str (:project opts) "/")) :states))
                                                 (keep (comp second client/split-key)) sort last)))
            n0      (workstream-count home (:project opts))
            live    (measure-store! home opts)
            _       (synthetic-settled! home (:project opts) (:synthetic opts))
            n1      (workstream-count home (:project opts))
            big     (measure-store! home opts)
            results [[(str "live " n0) live] [(str "enlarged " n1) big]]]
        (println (str "project " (:project opts) ", detail " (:ws opts) ", median of " (:runs opts) " runs"))
        (print-table results)
        (when-let [out (:out opts)]
          (spit (str out) (pr-str {:project (:project opts) :ws (:ws opts) :runs (:runs opts)
                                   :stores (into {} results)})))
        results)
      (finally
        (fs/delete-tree root)))))
