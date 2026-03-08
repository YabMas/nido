(ns nido.ui.server
  "HTTP server for the nido dashboard."
  (:require [clojure.string :as str]
            [nido.project :as project]
            [nido.ui.discovery :as discovery]
            [nido.ui.views :as views]
            [org.httpkit.server :as http]))

;; ---------------------------------------------------------------------------
;; Routing

(defn- parse-path
  "Parse a URI path into segments, ignoring empty strings."
  [uri]
  (vec (remove str/blank? (str/split uri #"/"))))

(defn- html-response [status body]
  {:status status
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body body})

(defn- handle-request [{:keys [uri]}]
  (let [segments (parse-path uri)]
    (case (count segments)
      ;; GET / — project listing
      0
      (html-response 200 (views/home-page (project/list-projects)))

      ;; GET /:project/vsdd/ — runs list
      2
      (let [[project-name section] segments]
        (if (= section "vsdd")
          (if-let [ctx (discovery/project-context project-name)]
            (let [runs (discovery/list-vsdd-runs (:directory ctx))]
              (html-response 200 (views/vsdd-runs-page project-name runs)))
            (html-response 404 (views/not-found-page)))
          (html-response 404 (views/not-found-page))))

      ;; GET /:project/vsdd/:run-id — run detail
      3
      (let [[project-name section run-id] segments]
        (if (= section "vsdd")
          (if-let [ctx (discovery/project-context project-name)]
            (if-let [manifest (discovery/load-vsdd-run (:directory ctx) run-id)]
              (html-response 200 (views/vsdd-run-detail-page project-name run-id manifest))
              (html-response 404 (views/not-found-page)))
            (html-response 404 (views/not-found-page)))
          (html-response 404 (views/not-found-page))))

      ;; GET /:project/vsdd/:run-id/report/:module-slug/:iteration — critic report
      6
      (let [[project-name section run-id _ module-slug iteration-str] segments]
        (if (and (= section "vsdd") (= (nth segments 3) "report"))
          (if-let [ctx (discovery/project-context project-name)]
            (let [iteration (parse-long iteration-str)
                  report (when iteration
                           (discovery/load-critic-report (:directory ctx) run-id module-slug iteration))]
              (if report
                (html-response 200 (views/vsdd-report-page project-name run-id module-slug iteration report))
                (html-response 404 (views/not-found-page))))
            (html-response 404 (views/not-found-page)))
          (html-response 404 (views/not-found-page))))

      ;; Anything else
      (html-response 404 (views/not-found-page)))))

;; ---------------------------------------------------------------------------
;; Server lifecycle

(defonce ^:private server-atom (atom nil))

(defn start!
  "Start the dashboard server. Returns the stop function."
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
