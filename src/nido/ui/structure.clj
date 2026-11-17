(ns nido.ui.structure
  "The Structure slice: a project's declared design as it stands on `main`, drawn by fukan's
   explorer inside the dashboard.

   One explorer per project, pinned to the commit `main` named when it started. Each visit to the
   slice asks jj where `main` is now, and a `main` that moved gets a new explorer over its own
   declaration; the old one is stopped and its directory deleted. Between visits nothing is polled.

   The page reaches the explorer only through `forward`, under `/structure/explorer/<project>/`, so
   the dashboard and the drawing share one origin and the explorer's port never reaches a browser.
   The explorer's own requests are relative, which is what lets it run under that prefix
   unchanged."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [hiccup2.core :as h]
            [clojure.string :as str]
            [nido.design.check :as design]
            [nido.design.explorer :as explorer]
            [nido.platform.project :as project]
            [nido.ui.stylesheet :as css]
            [nido.vsdd.jj :as jj]))

(def default-view
  "The view the slice opens on: the Bands and what each may depend on — the system at the altitude
   a reader starts from. Bands claim their namespaces by prefix rather than containing them, so
   under `child` they are unplaced, and the view asks for unplaced nodes to draw them at all. Any
   other view is a pick in the page away, and stays in its address."
  "embed=1&nest=child&link=may-depend&unplaced=1&select=%5B(Band%20%3Fn)%5D")

;; project name → {:commit :dir :handle}; :handle is nil when that commit declares no design
(defonce ^:private held (atom {}))

(defn- main-commit
  "The commit `main` names in `dir`, or nil when it names none. `--ignore-working-copy`, because
   the project's checkout is a person's, and reading a bookmark must not snapshot their edits."
  [dir]
  (let [{:keys [exit out]} (jj/jj! dir "log" "-r" "main" "-T" "commit_id" "--no-graph"
                                   "--ignore-working-copy")]
    (when (and (zero? (long exit)) (not (str/blank? out))) (str/trim out))))

(defn- release! [{:keys [handle dir]}]
  (explorer/stop! handle)
  (when dir (fs/delete-tree dir)))

(defn ^{:malli/schema [:=> [:cat :string] :map]}
  ensure!
  "The explorer over `project`'s declaration on `main`, started or restarted as `main` requires:
   one of `{:status :starting|:serving|:failed …}` as `nido.design.explorer/address` answers, or
   `{:status :unmodelled}` when `main` declares no design, or `{:status :no-main}` when the project
   has no `main` to read. An explorer that failed is started again, since the visit that asks is
   the only retry there is."
  [project]
  (locking held
    (let [dir    (:directory (project/get-project project))
          commit (some-> dir main-commit)
          cur    (get @held project)]
      (cond
        (nil? commit)
        {:status :no-main}

        (and (= commit (:commit cur))
             (or (nil? (:handle cur))
                 (not= :failed (:status (explorer/address (:handle cur))))))
        (if (:handle cur) (explorer/address (:handle cur)) {:status :unmodelled})

        :else
        (do (when cur (release! cur))
            (let [base   (jj/materialize-spec-dirs! dir commit (design/spec-dirs project))
                  handle (when base (explorer/start! project base))]
              (swap! held assoc project {:commit commit :dir base :handle handle})
              (if handle (explorer/address handle) {:status :unmodelled})))))))

(defn- notice
  "A page standing in for the explorer while it cannot answer, in the dashboard's own stylesheet so
   the frame reads as part of the page around it; `refresh?` asks the browser to try again shortly."
  [text refresh?]
  {:status  (if refresh? 503 502)
   :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-store"}
   :body    (str (h/html [:html
                          [:head [:meta {:charset "utf-8"}]
                           (when refresh? [:meta {:http-equiv "refresh" :content "1"}])
                           [:style (h/raw (css/stylesheet))]]
                          [:body {:style "display:grid;place-items:center;height:100vh;margin:0"}
                           [:p.meta {:style "max-width:40em;white-space:pre-wrap"} text]]]))})

(defn ^{:malli/schema [:=> [:cat :string :string [:maybe :string]] :map]}
  forward
  "Answer a request for `path` under `project`'s explorer with what the explorer answers, or with
   a notice while it is starting or after it failed. Reads the held explorer only: what starts
   one is a visit to the slice, so a page left open never restarts anything."
  [project path query-string]
  (let [{:keys [status port error]} (some-> (get-in @held [project :handle]) explorer/address)]
    (case status
      :serving
      (try
        (let [resp (http/get (str "http://localhost:" port "/" path
                                  (when (seq query-string) (str "?" query-string)))
                             {:throw false})]
          {:status  (:status resp)
           :headers (select-keys (:headers resp) ["content-type" "cache-control"])
           :body    (:body resp)})
        (catch Exception e
          (notice (str "The explorer stopped answering: " (ex-message e)
                       "\nReload the Structure page to start it again.")
                  false)))
      :starting (notice (str "Starting fukan's explorer over " project "'s declared design…") true)
      :failed   (notice (str "fukan's explorer did not start:\n\n" error) false)
      (notice "No explorer is running for this project. Open the Structure page." false))))
