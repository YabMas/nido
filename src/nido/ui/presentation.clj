(ns nido.ui.presentation
  "The Presentation slice: a project's component catalog as that project's own running app serves
   it, framed inside the dashboard.

   Where a project's catalog lives is its config: `:catalog-path` on its entry in projects.edn, the
   path its app serves the catalog at. nido's is the dashboard's own `/_catalog`. Any other
   project's is served by one of its sessions whose app is up — found in the session registry and
   confirmed by a connection, newest first — and nido never renders it: the catalog runs in the
   project's process, over the project's registry.

   The browser reaches that app only through `forward`, under `/presentation/app/<project>/`, so the
   framed page shares the dashboard's origin and no app port leaves the dashboard. The forward
   carries a request's method, query string, body and content type as they came, because a
   catalog's controls post; the library's catalog page writes its links and requests relative, so
   it runs unchanged under the prefix.

   Responses are read whole before they are answered: a request whose response stays open — a
   server-sent-event stream — is cut at the timeout. A catalog over literal values has none."
  (:require [babashka.http-client :as http]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [nido.platform.project :as project]
            [nido.session.state :as state]
            [nido.ui.registry :as registry]))

(def ^:private dashboard-project
  "The project whose catalog is the dashboard's own: nido's components are rendered by the process
   serving this page, so its catalog needs no session and no forward."
  "nido")

(def ^:private dashboard-catalog "/_catalog?embed=1")

(def ^:private forward-timeout-ms 30000)

(defn- answers?
  "Whether something accepts a connection on localhost `port` within a fifth of a second."
  [port]
  (try
    (with-open [s (java.net.Socket.)]
      (.connect s (java.net.InetSocketAddress. "localhost" (int port)) 200)
      true)
    (catch Exception _ false)))

(defn- live-app
  "The newest registry entry of `project` whose app port answers, or nil."
  [project]
  (->> (vals (state/read-registry))
       (filter #(and (= project (:project-name %)) (pos-int? (:app-port %))))
       (sort-by #(str (:created-at %)) #(compare %2 %1))
       (filter #(answers? (:app-port %)))
       first))

(defn- why-none
  "The sentence saying why `project` has no catalog to frame."
  [project status]
  (case status
    :no-catalog (str project " declares no catalog. Give its entry in projects.edn a "
                     ":catalog-path — the path its app serves the catalog at.")
    :no-app     (str "No session of " project " has its app up to serve the catalog. "
                     "Bring one up and reload.")
    (str project "'s catalog cannot be framed.")))

(defn- forwarded
  "The address of `path` on `project`'s catalog app, under the project's forward prefix."
  [project path]
  (str "/presentation/app/" (java.net.URLEncoder/encode project "UTF-8") "/"
       (str/replace path #"^/+" "")))

(defn- serving
  "Where `project`'s catalog is served, with the app port the forward needs."
  [project]
  (if (= dashboard-project project)
    {:status :dashboard :src dashboard-catalog}
    (if-let [path (some-> (project/get-project project) :catalog-path str not-empty)]
      (if-let [{:keys [session-name app-port]} (live-app project)]
        {:status :session :session session-name :src (forwarded project path) :port app-port}
        {:status :no-app :why (why-none project :no-app)})
      {:status :no-catalog :why (why-none project :no-catalog)})))

(defn ^{:malli/schema [:=> [:cat :string]
                       [:or [:map [:status [:enum :dashboard]] [:src :string]]
                            [:map [:status [:enum :session]] [:session :string] [:src :string]]
                            [:map [:status [:enum :no-catalog]] [:why :string]]
                            [:map [:status [:enum :no-app]] [:why :string]]]]}
  target
  "Where `project`'s catalog is served now, by `:status`: `:dashboard` for nido, `:session` for a
   live session's app (`:session` naming it) — each with `:src`, the same-origin address to frame —
   or why there is none, `:no-catalog` when the project declares no `:catalog-path` and `:no-app`
   when no session of it has an app answering, each with `:why`, the sentence saying so. Never
   throws; carries no port."
  [project]
  (dissoc (serving project) :port))

(defn- notice
  "A page standing in for the catalog inside the frame, in the dashboard's stylesheet."
  [status text]
  {:status  status
   :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-store"}
   :body    (str (h/html [:html
                          [:head [:meta {:charset "utf-8"}]
                           [:style (h/raw (registry/assembled-stylesheet))]]
                          [:body.notice-page
                           [:p.meta.notice-text text]]]))})

(defn ^{:malli/schema [:=> [:cat :string
                             [:map [:method [:enum :get :post]] [:path :string]
                              [:query-string [:maybe :string]] [:body [:maybe :string]]
                              [:content-type [:maybe :string]]]]
                       :map]}
  forward
  "Answer `request` — `:method`, `:path` under the project's prefix, `:query-string`, `:body` and
   `:content-type`, each possibly nil but the method — with what `project`'s catalog app answers
   to the same, or with a notice saying why there is no app to ask. Starts nothing."
  [project {:keys [method path query-string body content-type]}]
  (let [{:keys [status port] :as where} (serving project)]
    (if (= :session status)
      (try
        (let [resp (http/request
                    {:method  method
                     :uri     (str "http://localhost:" port "/" path
                                   (when (seq query-string) (str "?" query-string)))
                     :headers (cond-> {} content-type (assoc "content-type" content-type))
                     :body    body
                     :as      :bytes
                     :throw   false
                     :timeout forward-timeout-ms})]
          {:status  (:status resp)
           :headers (select-keys (:headers resp) ["content-type" "cache-control" "location"])
           :body    (:body resp)})
        (catch Exception e
          (notice 502 (str (:session where) "'s app stopped answering: " (ex-message e)))))
      (notice 404 (if (= :dashboard status)
                    (str project "'s catalog is the dashboard's own; it is not forwarded.")
                    (:why where))))))
