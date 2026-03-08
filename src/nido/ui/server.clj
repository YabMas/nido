(ns nido.ui.server
  "HTTP server for the nido dashboard."
  (:require [clojure.string :as str]
            [nido.project :as project]
            [nido.ui.discovery :as discovery]
            [nido.ui.views :as views]
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

(defn- handle-request [{:keys [uri]}]
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
            (case rest-segs
              ;; GET /:project/vsdd/ — runs list
              ["vsdd"]
              (let [runs (discovery/list-vsdd-runs dir)
                    has-in-progress? (some #(= :in-progress (get-in % [:manifest :status])) runs)]
                (html-response 200 (views/vsdd-runs-page project-name runs has-in-progress?)))

              ;; GET /:project/vsdd/_fragment/runs — SSE fragment for runs table
              ["vsdd" "_fragment" "runs"]
              (let [runs (discovery/list-vsdd-runs dir)]
                (sse-response (sse-fragment (views/vsdd-runs-table-fragment project-name runs))))

              ;; Match /:project/vsdd/:run-id and /:project/vsdd/:run-id/_fragment/detail
              (cond
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

                :else
                (html-response 404 (views/not-found-page)))))
          (html-response 404 (views/not-found-page)))))))

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
