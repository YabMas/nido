(ns nido.ui.server
  "HTTP server for the nido dashboard."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [nido.coordinator.findings :as findings]
            [nido.coordinator.pickup :as pickup]
            [nido.notion.client :as client]
            [nido.process :as proc]
            [nido.project :as project]
            [nido.session.dev :as dev]
            [nido.session.lifecycle :as lifecycle]
            [nido.session.state :as state]
            [nido.ui.health :as health]
            [nido.ui.views :as views]
            [nido.ui.view-state :as view-state]
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

;; ---------------------------------------------------------------------------
;; Rail seam + context

(defn read-rail-daemon "Seam over health for stubbing in tests." [] (health/read-daemon-health))

(defn- scope-keep-rows
  "Keep only rows whose :project matches `scope` (no-op when \"all\"). Used for
   the system surface's session rows + gates, which are tagged with :project."
  [scope rows]
  (if (= "all" scope) rows (filterv #(= scope (:project %)) rows)))

(defn derive-screen
  "Impure wiring: gather what only IO can produce (grouped rows, gates, in-flight
   resolve keys), hand off to the pure work/screen, then attach the selection
   detail. Selection detail is attached HERE (not in work) because it needs the
   dev layer, which work must not depend on. Every /workstreams + / render route
   runs through this one function, so no two render sites disagree."
  [view-state]
  (let [screen (work/screen view-state
                            {:groups           (work/all-grouped)
                             :gates            (work/all-gates)
                             :pending          (dev/pending-resolve-keys)
                             :winddown-pending (dev/pending-winddown-keys)})
        sel (:selection view-state)]
    (-> screen
        (assoc :selection
               (when sel
                 (let [ws (when (= :workstreams (:surface view-state))
                            (work/workstream (:project sel) (:ws-id sel) (:entry view-state)))]
                   (cond-> {:project (:project sel) :ws-id (:ws-id sel)}
                     ws (assoc :ws ws
                               :dev-states (dev/ws-session-dev-states (:project sel) ws)
                               :machine (work/machine-facts (:project sel) (map :name (:sessions ws))))))))
        (update :groups
                (fn [groups]
                  (mapv (fn [{:keys [project] :as g}]
                          (update-in g [:grouped :winding-down]
                                     (fn [rows]
                                       (mapv (fn [row]
                                               (let [facts (work/machine-facts project (:sessions row))
                                                     total (->> (vals facts)
                                                                (mapcat (juxt :repl-rss :pg-rss))
                                                                (remove nil?)
                                                                (reduce + 0))]
                                                 (cond-> row
                                                   (pos? total) (assoc :rss-str (proc/human-bytes total)))))
                                             rows))))
                        groups))))))

(defn- rail-ctx
  "Rail context for the screen-based surfaces (needs, workstreams). The badge
   count is the screen's own needs-count, so rail + inbox always agree."
  [active screen]
  {:active      active
   :scope       (:scope screen)
   :tab         (:tab screen)
   :needs-count (:needs-count screen)
   :daemon      (read-rail-daemon)
   :projects    (mapv (comp name key) (project/list-projects))})

(defn- rail-context
  "Rail context for the system surface (not screen-based). Scope-filters gates
   for the badge count."
  [active scope]
  {:active      active
   :scope       scope
   :tab         nil
   :needs-count (count (scope-keep-rows scope (work/all-gates)))
   :daemon      (read-rail-daemon)
   :projects    (mapv (comp name key) (project/list-projects))})

(defn- needs-fragment-response [screen]
  (sse-response
   (sse-fragment
    (str (views/needs-fragment screen)
         (views/rail-status-fragment {:needs-count (:needs-count screen)
                                      :daemon (read-rail-daemon)})))))

(defn- workstreams-fragment-response [screen]
  (sse-response
   (sse-fragment
    (str (views/workstreams-fragment screen)
         (views/rail-status-fragment {:needs-count (:needs-count screen)
                                      :daemon (read-rail-daemon)})))))

(defn- system-fragment-response [scope]
  (sse-response
   (sse-fragment
    (str (views/system-fragment (scope-keep-rows scope (all-session-rows)) (read-rail-daemon))
         (views/rail-status-fragment {:needs-count (count (scope-keep-rows scope (work/all-gates)))
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
       (views/workstream-pane ws (dev/ws-session-dev-states project ws)
                              (work/machine-facts project (map :name (:sessions ws)))))))))

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

(def ^:private valid-severities #{:blocker :tweak :nice-to-have})

(defn parse-findings-lines
  "Parse a textarea of findings, one per line `severity | area | summary`. Blank
   lines are skipped; a blank/unknown severity defaults to :tweak; a blank area is
   omitted. Returns a vector of {:summary :severity (:area)} maps."
  [s]
  (->> (str/split-lines (or s ""))
       (map str/trim)
       (remove str/blank?)
       (mapv (fn [line]
               (let [[sev area summary] (map str/trim (str/split line #"\|" 3))
                     sev-kw (keyword (str/lower-case (or sev "")))]
                 (cond-> {:summary  (or (not-empty summary) line)
                          :severity (if (valid-severities sev-kw) sev-kw :tweak)}
                   (not-empty area) (assoc :area area)))))))

(defn- gate-resolve!
  "Run work/resolve-gate! on a background thread, tracking optimistic state per
   (project,ws-id) so the inbox/pane reflect 'working…' until it settles. Mirrors
   run-action!'s app-states pattern."
  [project ws-id action-id input]
  (let [k (str project "/" ws-id)]
    (dev/set-app-state! k (if (= :reply action-id) :resuming :resolving))
    (future
      (try
        (let [{:keys [decision error]} (work/resolve-gate! project ws-id action-id input)]
          (if (contains? #{:notion-failed :error} decision)
            (dev/set-app-state! k :failed (str "Apply failed" (when error (str ": " (name error)))))
            (dev/clear-app-state! k)))
        (catch Exception e
          (dev/set-app-state! k :failed (or (:reason (ex-data e)) (ex-message e))))))))

(defn- handle-post [{:keys [uri body] :as req}]
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

      ;; POST /workstreams/:project/:ws-id/gate/:action — resolve a gate action from
      ;; the overview/detail pane (the stage-appropriate action bar below the reader).
      ;; Reuses gate-resolve!; :reply carries the textarea input like the home route.
      ;; The confirmation patches #ws-pane, the list's 5s poll drops the resolved row.
      (and (= 5 (count segs)) (= "workstreams" (first segs)) (= "gate" (nth segs 3)))
      (let [project   (nth segs 1)
            ws-id     (nth segs 2)
            action-id (keyword (nth segs 4))
            input     (when (= :reply action-id) (:reply (parse-json-body body)))]
        (gate-resolve! project ws-id action-id input)
        (sse-response (sse-fragment (views/gate-action-confirm-fragment action-id project ws-id "ws-pane"))))

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

      ;; POST /workstreams/pickup/:project — resolve a pasted Notion ref, enqueue
      ;; the :plan-bug leg, and patch #pickup-result with the continuing/new report.
      (and (= 3 (count segs)) (= "workstreams" (first segs)) (= "pickup" (nth segs 1)))
      (let [project (nth segs 2)
            input   (str/trim (str (:pickup (parse-json-body body))))
            ready?  (= :up (:state (read-rail-daemon)))]
        (if (str/blank? input)
          (sse-response
           (sse-fragment
            (views/pickup-result-fragment {:decision :unresolved :error :unrecognized-input}
                                          {:project project :daemon-ready? ready?})))
          (let [result (pickup/pickup! (keyword project) input (client/keychain-token))]
            (sse-response
             (sse-fragment
              (views/pickup-result-fragment result {:project project :daemon-ready? ready?}))))))

      ;; POST /workstreams/:project/:ws-id/winddown — bring a closed workstream's
      ;; leftover sessions down. Optimistic :stopping keyed "project/ws-id" (same
      ;; key-space as gate-resolve!) marks the row pending until down! settles.
      (and (= 4 (count segs)) (= "workstreams" (first segs)) (= "winddown" (nth segs 3)))
      (let [project (nth segs 1) ws-id (nth segs 2) k (str project "/" ws-id)]
        (dev/set-app-state! k :stopping)
        (future
          (try (work/bring-down! project ws-id)
               (dev/clear-app-state! k)
               (catch Exception e
                 (dev/set-app-state! k :failed (ex-message e)))))
        (workstreams-fragment-response (derive-screen (view-state/parse req))))

      ;; POST /workstreams/:project/:ws-id/findings — file a staging findings round
      (and (= 4 (count segs)) (= "workstreams" (first segs)) (= "findings" (nth segs 3)))
      (let [project (nth segs 1)
            ws-id   (nth segs 2)
            b       (parse-json-body body)
            items   (parse-findings-lines (:findings b))]
        (when (seq items)
          (try
            (findings/file! (keyword project) ws-id
                            {:items items :staging-ref (not-empty (:staging b))})
            (catch Exception e
              (println "[nido ui] findings/file! failed:" (ex-message e)))))
        (ws-pane-fragment-response project ws-id))

      :else
      (html-response 404 (views/not-found-page)))))

(defn- handle-get [{:keys [uri] :as req}]
  (let [segments (parse-path uri)]
    (case segments
      ;; GET /favicon.{png,ico} — the nido icon (browsers auto-request .ico)
      ["favicon.png"] (png-resource-response "favicon.png")
      ["favicon.ico"] (png-resource-response "favicon.png")
      ;; GET /nido-logo.png — looser circular mark for the rail home button
      ["nido-logo.png"] (png-resource-response "nido-logo.png")

      ;; GET / — Needs you (home)
      []
      (let [screen (derive-screen (view-state/parse req))]
        (html-response 200 (views/needs-page (rail-ctx :needs screen) screen)))

      ;; GET /system — cross-project session board with daemon health banner
      ["system"]
      (let [scope (:scope (view-state/parse req))]
        (html-response 200 (views/system-page (rail-context :system scope)
                                              (scope-keep-rows scope (all-session-rows))
                                              (read-rail-daemon))))

      ;; GET /workstreams — overview (selection, if any, comes from ?sel=)
      ["workstreams"]
      (let [screen (derive-screen (view-state/parse req))]
        (html-response 200 (views/workstreams-page (rail-ctx :workstreams screen) screen)))

      ;; GET /_fragment/workstreams — SSE workstreams refresh
      ["_fragment" "workstreams"]
      (workstreams-fragment-response (derive-screen (view-state/parse req)))

      ;; GET /_fragment/needs — queue + rail
      ["_fragment" "needs"]
      (needs-fragment-response (derive-screen (view-state/parse req)))

      ;; GET /_fragment/system — SSE system surface refresh (patches #system + rail)
      ["_fragment" "system"]
      (system-fragment-response (:scope (view-state/parse req)))

      ;; Otherwise, dispatch on structure
      (cond
        ;; GET /_fragment/workstream/:project/:ws-id — SSE pane refresh (patches #ws-pane)
        (and (= 4 (count segments)) (= "_fragment" (first segments)) (= "workstream" (nth segments 1)))
        (ws-pane-fragment-response (nth segments 2) (nth segments 3) (:entry (view-state/parse req)))

        ;; GET /workstreams/:project/:ws-id — legacy deep link → canonical selection
        (and (= 3 (count segments)) (= "workstreams" (first segments)))
        (let [vs     (assoc (view-state/parse req) :surface :workstreams
                            :selection {:project (nth segments 1) :ws-id (nth segments 2)})
              screen (derive-screen vs)]
          (html-response 200 (views/workstreams-page (rail-ctx :workstreams screen) screen)))

        ;; GET /gate/:project/:ws-id — legacy deep link → needs surface, gate selected
        (and (= 3 (count segments)) (= "gate" (first segments)))
        (let [vs     (assoc (view-state/parse req) :surface :needs
                            :selection {:project (nth segments 1) :ws-id (nth segments 2)})
              screen (derive-screen vs)]
          (html-response 200 (views/needs-page (rail-ctx :needs screen) screen)))

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
