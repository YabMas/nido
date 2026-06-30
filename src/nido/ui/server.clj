(ns nido.ui.server
  "HTTP server for the nido dashboard."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [nido.process :as proc]
            [nido.project :as project]
            [nido.session.dev :as dev]
            [nido.session.lifecycle :as lifecycle]
            [nido.session.state :as state]
            [nido.ui.health :as health]
            [nido.ui.views :as views]
            [nido.work :as work]
            [org.httpkit.server :as http]))

;; ---------------------------------------------------------------------------
;; Response helpers

(defn- html-response [status body]
  {:status status
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body body})

(defn- sse-fragment
  "Format an SSE event that patches elements via datastar."
  [html-fragment]
  (let [lines (str/split-lines html-fragment)]
    (str "event: datastar-patch-elements\n"
         (str/join "\n" (map #(str "data: elements " %) lines))
         "\n\n")))

(defn- sse-response [body]
  {:status 200
   :headers {"Content-Type" "text/event-stream"
             "Cache-Control" "no-cache"
             "Connection" "keep-alive"}
   :body body})

(defn- png-resource-response
  "Serve a classpath PNG resource. Both circular crops of resources/nido-icon.png
   are cut by `clojure -M scripts/gen-favicon.clj`: `favicon.png` is zoomed hard
   to fill the disc at tab size; `nido-logo.png` is looser (keeps the gold ring)
   for the larger rail home-button mark."
  [resource-name]
  (if-let [res (io/resource resource-name)]
    {:status 200
     :headers {"Content-Type" "image/png"
               "Cache-Control" "public, max-age=86400"}
     :body (io/input-stream res)}
    {:status 404 :body ""}))

;; ---------------------------------------------------------------------------
;; Routing

(defn- parse-path
  "Parse a URI path into segments, ignoring empty strings."
  [uri]
  (vec (remove str/blank? (str/split uri #"/"))))

(defn- instance-id-for [project-name session-name]
  (if (= project-name session-name)
    project-name
    (str project-name "--" session-name)))

(defn- session-rows
  "Build table rows for all sessions visible to a project. Combines the
   filesystem list (all worktrees), the nido registry, a live TCP check
   on the app port, and the in-flight app-states atom. Resident-set
   sizes are sampled via `ps` for the repl and PG pids so the UI can
   show an at-a-glance memory footprint per session."
  [project-name project-dir]
  (let [base (lifecycle/worktrees-dir project-name project-dir)
        registry (state/read-registry)]
    (when (fs/exists? base)
      (->> (fs/list-dir base)
           (filter fs/directory?)
           (map (fn [d]
                  (let [name (str (fs/file-name d))
                        wt-path (str d)
                        entry (get registry wt-path)
                        port (:app-port entry)
                        live? (and (pos-int? port) (proc/tcp-open? port))
                        instance-id (instance-id-for project-name name)
                        pending (dev/current-app-state instance-id)
                        ;; RSS is only meaningful while the session is alive.
                        repl-rss (when (and live? (:repl-pid entry))
                                   (proc/rss-bytes (:repl-pid entry)))
                        session (when live? (state/read-session instance-id))
                        pg-pid (when session
                                 (get-in session [:service-states :pg :pg-pid]))
                        pg-rss (when (and live? pg-pid)
                                 (proc/rss-bytes pg-pid))
                        heap-max (when session
                                   (get-in session [:context :session :jvm :heap-max]))]
                    {:name name
                     :wt-path wt-path
                     :entry entry
                     :live? live?
                     :pending-state pending
                     :repl-rss repl-rss
                     :pg-rss pg-rss
                     :heap-max heap-max})))
           (sort-by :name)))))

(defn all-session-rows
  "Aggregate session rows across all registered projects into one flat,
   live-first list. Each row is tagged with its :project. The 2-arity is pure
   given the per-project row builder + projects map, so it is unit-testable;
   the 0-arity wires the real `session-rows` and registry."
  ([] (all-session-rows session-rows (project/list-projects)))
  ([rows-fn projects]
   (->> (for [[pname entry] projects
              row           (or (try (rows-fn pname (:directory entry))
                                     ;; A project that can't be read (e.g. no
                                     ;; session.edn) contributes no rows rather
                                     ;; than crashing the whole board.
                                     (catch Throwable _ nil))
                                 [])]
          (assoc row :project pname))
        (sort-by (juxt #(if (:live? %) 0 1) :project :name)))))

(defn all-grouped
  "[{:project :grouped} …] across registered projects (mirrors all-session-rows)."
  []
  (->> (project/list-projects)
       (keep (fn [[pname _]]
               (try {:project pname :grouped (work/grouped pname)}
                    (catch Throwable _ nil))))
       vec))

;; ---------------------------------------------------------------------------
;; Rail seam + context

(defn read-rail-daemon "Seam over health for stubbing in tests." [] (health/read-daemon-health))

(defn- parse-entry
  "Read the `entry` query param as a long seq, or nil (→ latest) when absent/garbage."
  [query-string]
  (when query-string
    (some-> (re-find #"(?:^|&)entry=(\d+)" query-string) second parse-long)))

(defn- parse-scope
  "Read scope from the query string; \"all\" when absent."
  [query-string]
  (or (when query-string
        (some->> (str/split query-string #"&")
                 (map #(str/split % #"=" 2))
                 (some (fn [[k v]] (when (= k "scope") v)))))
      "all"))

(defn- scope-filter
  "Keep only rows whose :project matches `scope` (no-op when \"all\")."
  [scope rows]
  (if (= "all" scope) rows (filterv #(= scope (:project %)) rows)))

(defn- parse-filters
  "Read source + facet params from the query string. :source defaults to :all;
   :facets is a map of kebab-keyword → value for every non-scope/source param."
  [query-string]
  (let [pairs (when query-string
                (->> (str/split query-string #"&")
                     (map #(str/split % #"=" 2))
                     (filter #(= 2 (count %)))))
        source (some (fn [[k v]] (when (= k "source") (keyword v))) pairs)
        facets (into {} (for [[k v] pairs
                              :when (not (#{"scope" "source"} k))]
                          (let [dv (java.net.URLDecoder/decode v "UTF-8")]
                            [(keyword k) (if (= dv "unclassified") :unclassified dv)])))]
    {:source (or source :all) :facets facets}))

(defn- valid-facet-keys
  "Kebab facet keys valid for `source` across the in-scope projects."
  [scope source]
  (->> (project/list-projects)
       (filter (fn [[pname _]] (or (= "all" scope) (= scope (name pname)))))
       (mapcat (fn [[pname _]] (work/facet-dimensions pname source)))
       set))

(defn- apply-filters
  "Narrow each {:project :grouped} entry's rows by source ∧ facets ∧ overview
   visibility (an :incoming row shows only under its own source lens)."
  [source facets groups]
  (mapv (fn [g]
          (update g :grouped
                  #(work/filter-grouped
                    % (fn [row] (and (work/source-match? source row)
                                     (work/facet-match? facets row)
                                     (work/overview-visible? source row))))))
        groups))

(defn- source-counts
  "Tally rows by :origin across all grouped entries."
  [groups]
  (->> groups
       (mapcat (fn [g] (work/grouped-rows (:grouped g))))
       (reduce (fn [m row] (update m (:origin row) (fnil inc 0))) {})))

(defn- rail-context
  "Render context for the shell rail on any page."
  [active scope]
  {:active      active
   :scope       scope
   :needs-count (count (scope-filter scope (work/all-gates)))
   :daemon      (read-rail-daemon)
   :projects    (mapv (comp name key) (project/list-projects))})

(defn- needs-fragment-response [gates sel-id]
  (sse-response
   (sse-fragment
    (str (views/needs-fragment gates sel-id)
         (views/rail-status-fragment {:needs-count (count gates)
                                      :daemon (read-rail-daemon)})))))

(defn- workstreams-fragment-response [groups scope]
  (sse-response
   (sse-fragment
    (str (views/workstreams-fragment groups nil)
         (views/rail-status-fragment {:needs-count (count (scope-filter scope (work/all-gates)))
                                       :daemon (read-rail-daemon)})))))

(defn- system-fragment-response [scope]
  (sse-response
   (sse-fragment
    (str (views/system-fragment (scope-filter scope (all-session-rows)) (read-rail-daemon))
         (views/rail-status-fragment {:needs-count (count (scope-filter scope (work/all-gates)))
                                       :daemon (read-rail-daemon)})))))

;; ---------------------------------------------------------------------------
;; Routing

(defn- ws-pane-fragment-response
  "SSE that patches #ws-pane with a freshly-rendered workstream pane (ws detail +
   per-session dev-env state). `entry` selects which ledger entry's report to show."
  ([project ws-id] (ws-pane-fragment-response project ws-id nil))
  ([project ws-id entry]
   (let [ws (work/workstream project ws-id entry)]
     (sse-response
      (sse-fragment
       (views/workstream-pane ws (dev/ws-session-dev-states project ws)))))))

(defn- run-action!
  "Run the lifecycle action matching `action` and update the app-states
   atom so both the POST response and subsequent polling fragments reflect
   the right transient/terminal state. When an action throws, extract the
   `:error-msg` the eval layer attached (if any) so the UI can show the
   actual failure reason under the red badge."
  [project-name session-name action]
  (let [instance-id (instance-id-for project-name session-name)
        opts {:project project-name}]
    (try
      (case action
        ["start"]
        (do (lifecycle/up! session-name opts)
            ;; If up! didn't throw and the app port IS listening →
            ;; success. If it didn't throw but the port isn't up, the
            ;; boot timed out silently — surface :failed without a
            ;; specific message.
            (let [port (dev/app-port-for-instance instance-id)]
              (if (and (pos-int? port) (proc/tcp-open? port))
                (dev/clear-app-state! instance-id)
                (dev/set-app-state! instance-id :failed
                                    "App did not open its port within the timeout — see eval log"))))

        ["stop"]
        (do (lifecycle/down! session-name opts)
            (dev/clear-app-state! instance-id))

        ["restart"]
        (do (lifecycle/restart! session-name opts)
            (dev/clear-app-state! instance-id))

        (do (println "[nido ui] unknown action:" action)
            (dev/clear-app-state! instance-id)))
      (catch Exception e
        (let [err-msg (or (:error-msg (ex-data e))
                          (ex-message e))]
          (println "[nido ui] action failed:" err-msg)
          (dev/set-app-state! instance-id :failed err-msg))))))

(defn- parse-json-body
  "Read a Datastar JSON signal body into a map, or {} when absent/unparseable."
  [body]
  (try
    (if body (json/parse-string (slurp body) true) {})
    (catch Exception _ {})))

(defn- gate-resolve!
  "Run work/resolve-gate! on a background thread, tracking optimistic state per
   (project,ws-id) so the inbox/pane reflect 'working…' until it settles. Mirrors
   run-action!'s app-states pattern."
  [project ws-id action-id input]
  (let [k (str project "/" ws-id)]
    (dev/set-app-state! k (if (#{:reply :apply} action-id) :resuming :resolving))
    (future
      (try
        (work/resolve-gate! project ws-id action-id input)
        (dev/clear-app-state! k)
        (catch Exception e
          (dev/set-app-state! k :failed (or (:reason (ex-data e)) (ex-message e))))))))

(defn- handle-post [{:keys [uri body]}]
  (let [segs (parse-path uri)]
    (cond
      ;; POST /gate/:project/:ws-id/:action — resolve a gate follow-action
      (and (= 4 (count segs)) (= "gate" (first segs)))
      (let [project   (nth segs 1)
            ws-id     (nth segs 2)
            action-id (keyword (nth segs 3))
            input     (when (= :reply action-id) (:reply (parse-json-body body)))]
        (gate-resolve! project ws-id action-id input)
        (sse-response (sse-fragment (views/gate-action-confirm-fragment action-id project ws-id))))

      ;; POST /workstreams/:project/:ws-id/sessions/:session/dev/:action
      (and (= 7 (count segs)) (= "workstreams" (first segs))
           (= "sessions" (nth segs 3)) (= "dev" (nth segs 5)))
      (let [project (nth segs 1) ws-id (nth segs 2)
            session (java.net.URLDecoder/decode (nth segs 4) "UTF-8")
            action  (nth segs 6)]
        (dev/dev-action! project ws-id session action)
        (ws-pane-fragment-response project ws-id))

      ;; POST /system/:project/:name/:action — session lifecycle action (renamed path)
      (and (>= (count segs) 4)
           (= "system" (first segs)))
      (let [project-name (nth segs 1)
            session-name (nth segs 2)
            action (vec (drop 3 segs))
            instance-id (instance-id-for project-name session-name)
            pending (dev/pending-state-for-action action)]
        ;; Mark the optimistic state NOW so the response *and* the next
        ;; polling cycle both show it.
        (when pending
          (dev/set-app-state! instance-id pending))
        ;; Kick off the potentially slow lifecycle op on a background
        ;; thread. It'll clear or replace the app-state when it finishes.
        (future (run-action! project-name session-name action))
        ;; Respond with the system fragment (patches #system + rail)
        ;; so the UI sees instant feedback. POSTs have no query-string scope;
        ;; default to "all" so the feedback shows the full system surface.
        (system-fragment-response "all"))

      :else
      (html-response 404 (views/not-found-page)))))

(defn- handle-get [{:keys [uri query-string]}]
  (let [segments (parse-path uri)
        scope    (parse-scope query-string)
        {:keys [source facets]} (parse-filters query-string)
        facets*  (select-keys facets (valid-facet-keys scope source))]
    (case segments
      ;; GET /favicon.{png,ico} — the nido icon (browsers auto-request .ico)
      ["favicon.png"] (png-resource-response "favicon.png")
      ["favicon.ico"] (png-resource-response "favicon.png")
      ;; GET /nido-logo.png — looser circular mark for the rail home button
      ["nido-logo.png"] (png-resource-response "nido-logo.png")

      ;; GET / — Needs you (home)
      []
      (let [gates (scope-filter scope (work/all-gates))]
        (html-response 200 (views/needs-page (rail-context :needs scope) gates nil)))

      ;; GET /system — cross-project session board with daemon health banner
      ["system"]
      (html-response 200 (views/system-page (rail-context :system scope)
                                            (scope-filter scope (all-session-rows))
                                            (read-rail-daemon)))

      ;; GET /workstreams — overview (no selection)
      ["workstreams"]
      (let [scoped (scope-filter scope (all-grouped))]
        (html-response 200 (views/workstreams-page
                            (assoc (rail-context :workstreams scope)
                                   :source source :facets facets*
                                   :facet-dims (vec (valid-facet-keys scope source))
                                   :source-counts (source-counts scoped))
                            (apply-filters source facets* scoped) nil nil)))

      ;; GET /_fragment/workstreams — SSE workstreams refresh
      ["_fragment" "workstreams"]
      (let [scoped (scope-filter scope (all-grouped))]
        (workstreams-fragment-response (apply-filters source facets* scoped) scope))

      ;; GET /_fragment/needs — queue + rail
      ["_fragment" "needs"]
      (let [gates (scope-filter scope (work/all-gates))]
        (needs-fragment-response gates nil))

      ;; GET /_fragment/system — SSE system surface refresh (patches #system + rail)
      ["_fragment" "system"]
      (system-fragment-response scope)

      ;; Otherwise, dispatch on structure
      (cond
        ;; GET /gate/:project/:ws-id — needs page, gate selected
        (and (= 3 (count segments)) (= "gate" (first segments)))
        (let [project (nth segments 1) ws-id (nth segments 2)
              gates (scope-filter scope (work/all-gates))]
          (html-response 200 (views/needs-page (rail-context :needs scope) gates (work/gate project ws-id))))

        ;; GET /workstreams/:project/:ws-id — overview + ledger pane
        (and (= 3 (count segments)) (= "workstreams" (first segments)))
        (let [project (nth segments 1) ws-id (nth segments 2)
              ws (work/workstream project ws-id (parse-entry query-string))]
          (html-response 200 (views/workstreams-page (rail-context :workstreams scope)
                                                     (scope-filter scope (all-grouped))
                                                     ws
                                                     (dev/ws-session-dev-states project ws))))

        ;; GET /_fragment/workstream/:project/:ws-id — SSE pane refresh (patches #ws-pane)
        (and (= 4 (count segments)) (= "_fragment" (first segments)) (= "workstream" (nth segments 1)))
        (let [project (nth segments 2) ws-id (nth segments 3)]
          (ws-pane-fragment-response project ws-id (parse-entry query-string)))

        :else
        (html-response 404 (views/not-found-page))))))

(defn handle-request [{:keys [request-method] :as req}]
  (case request-method
    :post (handle-post req)
    (handle-get req)))

;; ---------------------------------------------------------------------------
;; Server lifecycle

(defonce ^:private server-atom (atom nil))

(defn start!
  "Start the dashboard server."
  [{:keys [port] :or {port 8800}}]
  (when-let [old @server-atom]
    (old))
  (let [stop-fn (http/run-server handle-request {:port port})]
    (reset! server-atom stop-fn)
    (println (str "[nido] Dashboard running at http://localhost:" port))
    stop-fn))

(defn stop! []
  (when-let [stop-fn @server-atom]
    (stop-fn)
    (reset! server-atom nil)
    (println "[nido] Dashboard stopped")))
