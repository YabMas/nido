(ns nido.ui.server
  "HTTP server for the nido dashboard."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [nido.process :as proc]
            [nido.project :as project]
            [nido.session.lifecycle :as lifecycle]
            [nido.session.state :as state]
            [nido.ui.discovery :as discovery]
            [nido.ui.views :as views]
            [nido.ui.watchdog :as watchdog]
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

;; ---------------------------------------------------------------------------
;; Routing

(defn- parse-path
  "Parse a URI path into segments, ignoring empty strings."
  [uri]
  (vec (remove str/blank? (str/split uri #"/"))))

;; ---------------------------------------------------------------------------
;; Session helpers

(defn- read-log-tail
  "Read the last n lines (bytes-capped) of a log file. Returns \"\" if the
   file doesn't exist or is unreadable. Caps the read at 256 KB regardless
   of line count to stay cheap."
  [path n]
  (try
    (if-not (fs/exists? path)
      ""
      (let [size (fs/size path)
            cap (* 256 1024)
            offset (max 0 (- size cap))
            content (if (zero? offset)
                      (slurp path)
                      (with-open [is (java.io.FileInputStream. (str path))]
                        (.skip is offset)
                        (let [bs (byte-array (- size offset))]
                          (.read is bs)
                          (String. bs "UTF-8"))))
            lines (str/split-lines content)
            tail (if (> (count lines) n) (drop (- (count lines) n) lines) lines)]
        (str/join "\n" tail)))
    (catch Exception e
      (str "[nido ui] could not read log: " (ex-message e)))))

(defn- session-log-path
  "Resolve the on-disk log path for a session + service. service is
   \"repl\" or \"pg\"."
  [project-name session-name service]
  (let [instance-id (if (= project-name session-name)
                      project-name
                      (str project-name "--" session-name))
        fname (case service
                "repl" "repl.log"
                "pg"   "pg.log"
                (str service ".log"))]
    (str (fs/path (state/log-dir instance-id) fname))))

;; ---------------------------------------------------------------------------
;; In-flight action tracking
;;
;; The atom records transient/sticky app states (:starting, :stopping, :failed)
;; that the TCP probe alone can't express. Both POST responses and polling
;; fragment refreshes read from here, so a clicked button gets instant
;; feedback AND the feedback persists across the 3s polling cycle until the
;; future completes.
;; ---------------------------------------------------------------------------

(defonce ^:private app-states (atom {}))

(defn- set-app-state!
  "Store the current state for an instance. The atom value is a map
   `{:state :starting|:stopping|:restarting|:failed
     :error-msg <string?>}` so a :failed state can carry the actual
   error message to display in the UI."
  ([instance-id state] (set-app-state! instance-id state nil))
  ([instance-id state error-msg]
   (swap! app-states assoc instance-id
          (cond-> {:state state}
            error-msg (assoc :error-msg error-msg)))))

(defn- clear-app-state! [instance-id]
  (swap! app-states dissoc instance-id))

(defn- current-app-state [instance-id]
  (get @app-states instance-id))

(defn- instance-id-for [project-name session-name]
  (if (= project-name session-name)
    project-name
    (str project-name "--" session-name)))

(defn- session-rows
  "Build table rows for all sessions visible to a project. Combines the
   filesystem list (all worktrees), the nido registry, a live TCP check
   on the app port, and the in-flight app-states atom. Resident-set
   sizes are sampled via `ps` for the repl and (isolated) PG pids so
   the UI can show an at-a-glance memory footprint per session."
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
                        pending (current-app-state instance-id)
                        pg-mode (:pg-mode entry)
                        ;; RSS is only meaningful while the session is alive.
                        repl-rss (when (and live? (:repl-pid entry))
                                   (proc/rss-bytes (:repl-pid entry)))
                        session (when live? (state/read-session instance-id))
                        pg-pid (when session
                                 (get-in session [:service-states :pg :pg-pid]))
                        pg-rss (when (and live? pg-pid (= pg-mode :isolated))
                                 (proc/rss-bytes pg-pid))
                        heap-max (when session
                                   (get-in session [:context :session :jvm :heap-max]))]
                    {:name name
                     :wt-path wt-path
                     :entry entry
                     :live? live?
                     :pending-state pending
                     :pg-mode pg-mode
                     :repl-rss repl-rss
                     :pg-rss pg-rss
                     :heap-max heap-max})))
           (sort-by :name)))))

;; ---------------------------------------------------------------------------
;; Routing

(defn- pending-state-for-action [action]
  (case action
    ["start"]   :starting
    ["stop"]    :stopping
    ["restart"] :restarting
    nil))

(defn- app-port-for-instance
  "Look up the app port stored in the session state file for this instance,
   so we can probe TCP after a lifecycle action completes."
  [instance-id]
  (some-> (state/read-session instance-id)
          (get-in [:service-states :app :app-port])))

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
        (do (lifecycle/init! session-name opts)
            ;; If init! didn't throw and the app port IS listening →
            ;; success. If it didn't throw but the port isn't up, init
            ;; timed out silently — surface :failed without a specific
            ;; message.
            (let [port (app-port-for-instance instance-id)]
              (if (and (pos-int? port) (proc/tcp-open? port))
                (clear-app-state! instance-id)
                (set-app-state! instance-id :failed
                                "App did not open its port within the timeout — see eval log"))))

        ["stop"]
        (do (lifecycle/stop! session-name opts)
            (clear-app-state! instance-id))

        ["restart"]
        (do (lifecycle/restart! session-name opts)
            (clear-app-state! instance-id))

        (do (println "[nido ui] unknown action:" action)
            (clear-app-state! instance-id)))
      (catch Exception e
        (let [err-msg (or (:error-msg (ex-data e))
                          (ex-message e))]
          (println "[nido ui] action failed:" err-msg)
          (set-app-state! instance-id :failed err-msg))))))

(defn- handle-post [{:keys [uri]}]
  (let [segs (parse-path uri)]
    (if (and (>= (count segs) 4)
             (= "sessions" (nth segs 1)))
      (let [project-name (first segs)
            session-name (nth segs 2)
            action (vec (drop 3 segs))
            instance-id (instance-id-for project-name session-name)
            pending (pending-state-for-action action)]
        ;; Mark the optimistic state NOW so the response *and* the next
        ;; polling cycle both show it.
        (when pending
          (set-app-state! instance-id pending))
        ;; Kick off the potentially slow lifecycle op on a background
        ;; thread. It'll clear or replace the app-state when it finishes.
        (future (run-action! project-name session-name action))
        ;; Respond with the current sessions fragment (which now includes
        ;; the just-set pending state) so the UI sees instant feedback.
        (if-let [ctx (discovery/project-context project-name)]
          (let [rows (session-rows project-name (:directory ctx))]
            (sse-response (sse-fragment
                           (views/sessions-table-fragment project-name rows))))
          {:status 204}))
      (html-response 404 (views/not-found-page)))))

(defn- handle-get [{:keys [uri]}]
  (let [segments (parse-path uri)]
    (case segments
      ;; GET / — project listing
      []
      (html-response 200 (views/home-page (project/list-projects)))

      ;; Otherwise, dispatch on structure
      (let [project-name (first segments)]
        (if-let [ctx (discovery/project-context project-name)]
          (let [dir (:directory ctx)
                rest-segs (vec (rest segments))]
            (cond
              ;; GET /:project/sessions — session list
              (= rest-segs ["sessions"])
              (let [rows (session-rows project-name dir)
                    wts-dir (lifecycle/worktrees-dir project-name dir)]
                (html-response 200 (views/sessions-page project-name wts-dir rows)))

              ;; GET /:project/sessions/_fragment/list — SSE table body
              (= rest-segs ["sessions" "_fragment" "list"])
              (let [rows (session-rows project-name dir)]
                (sse-response (sse-fragment (views/sessions-table-fragment project-name rows))))

              ;; GET /:project/sessions/:name/logs/:service — full page
              (and (= 4 (count rest-segs))
                   (= "sessions" (first rest-segs))
                   (= "logs" (nth rest-segs 2)))
              (let [session-name (second rest-segs)
                    service (nth rest-segs 3)
                    log-path (session-log-path project-name session-name service)
                    content (read-log-tail log-path 200)]
                (html-response 200 (views/session-log-page
                                    project-name session-name service content)))

              ;; GET /:project/sessions/:name/logs/:service/_fragment — SSE tail
              (and (= 5 (count rest-segs))
                   (= "sessions" (first rest-segs))
                   (= "logs" (nth rest-segs 2))
                   (= "_fragment" (nth rest-segs 4)))
              (let [session-name (second rest-segs)
                    service (nth rest-segs 3)
                    log-path (session-log-path project-name session-name service)
                    content (read-log-tail log-path 200)]
                (sse-response (sse-fragment (views/log-tail-fragment content))))

              ;; GET /:project/vsdd/ — runs list
              (= rest-segs ["vsdd"])
              (let [runs (discovery/list-vsdd-runs dir)
                    has-in-progress? (some #(= :in-progress (get-in % [:manifest :status])) runs)]
                (html-response 200 (views/vsdd-runs-page project-name runs has-in-progress?)))

              ;; GET /:project/vsdd/_fragment/runs — SSE fragment for runs table
              (= rest-segs ["vsdd" "_fragment" "runs"])
              (let [runs (discovery/list-vsdd-runs dir)]
                (sse-response (sse-fragment (views/vsdd-runs-table-fragment project-name runs))))

              ;; GET /:project/vsdd/:run-id/_fragment/detail — SSE fragment
              (and (= 4 (count rest-segs))
                   (= "vsdd" (first rest-segs))
                   (= "_fragment" (nth rest-segs 2))
                   (= "detail" (nth rest-segs 3)))
              (let [run-id (second rest-segs)]
                (if-let [manifest (discovery/load-vsdd-run dir run-id)]
                  (sse-response (sse-fragment (views/vsdd-run-detail-fragment project-name run-id manifest)))
                  (html-response 404 (views/not-found-page))))

              ;; GET /:project/vsdd/:run-id — run detail page
              (and (= 2 (count rest-segs))
                   (= "vsdd" (first rest-segs)))
              (let [run-id (second rest-segs)]
                (if-let [manifest (discovery/load-vsdd-run dir run-id)]
                  (html-response 200 (views/vsdd-run-detail-page project-name run-id manifest))
                  (html-response 404 (views/not-found-page))))

              ;; GET /:project/vsdd/:run-id/report/:module-slug/:iteration — critic report
              (and (= 5 (count rest-segs))
                   (= "vsdd" (first rest-segs))
                   (= "report" (nth rest-segs 2)))
              (let [run-id (second rest-segs)
                    module-slug (nth rest-segs 3)
                    iteration (parse-long (nth rest-segs 4))
                    report (when iteration
                             (discovery/load-critic-report dir run-id module-slug iteration))]
                (if report
                  (html-response 200 (views/vsdd-report-page project-name run-id module-slug iteration report))
                  (html-response 404 (views/not-found-page))))

              ;; GET /:project/vsdd/:run-id/impl-report/:module-slug/:iteration — implementer report
              (and (= 5 (count rest-segs))
                   (= "vsdd" (first rest-segs))
                   (= "impl-report" (nth rest-segs 2)))
              (let [run-id (second rest-segs)
                    module-slug (nth rest-segs 3)
                    iteration (parse-long (nth rest-segs 4))
                    report (when iteration
                             (discovery/load-impl-report dir run-id module-slug iteration))]
                (if report
                  (html-response 200 (views/vsdd-impl-report-page project-name run-id module-slug iteration report))
                  (html-response 404 (views/not-found-page))))

              :else
              (html-response 404 (views/not-found-page))))
          (html-response 404 (views/not-found-page)))))))

(defn- handle-request [{:keys [request-method] :as req}]
  (case request-method
    :post (handle-post req)
    (handle-get req)))

;; ---------------------------------------------------------------------------
;; Server lifecycle

(defonce ^:private server-atom (atom nil))

(defn start!
  "Start the dashboard server and the idle-app watchdog. Returns the stop fn."
  [{:keys [port watchdog?] :or {port 8800 watchdog? true}}]
  (when-let [old @server-atom]
    (old))
  (let [stop-fn (http/run-server handle-request {:port port})]
    (reset! server-atom stop-fn)
    (println (str "[nido] Dashboard running at http://localhost:" port))
    (when watchdog?
      (watchdog/start! {}))
    stop-fn))

(defn stop! []
  (when-let [stop-fn @server-atom]
    (stop-fn)
    (reset! server-atom nil)
    (watchdog/stop!)
    (println "[nido] Dashboard stopped")))
