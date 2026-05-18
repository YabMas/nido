(ns nido.notion.client
  "Notion REST client + macOS Keychain helpers for the integration token.
   Used by the :notion-view source.

   Keychain entries are scoped per-user with service name 'nido-notion'.
   `sh!` is a redef seam so tests can stub `security` invocations.
   `http-request` is a redef seam so tests can stub HTTP calls."
  (:require
   [babashka.http-client :as http]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as str]))

(defn sh!
  "Wrapped shell-out so tests can stub `security` calls."
  [args]
  (p/sh args))

(defn- whoami
  "Resolve the current username via `whoami`. Called at invocation time.
   Uses p/sh directly (not sh!) — not a security call, not a test seam."
  []
  (str/trim (:out (p/sh ["whoami"]))))

(defn keychain-token
  "Read the Notion integration token from the user's macOS Keychain.
   Returns the trimmed token string, or nil if the entry isn't present."
  []
  (let [{:keys [exit out]} (sh! ["security" "find-generic-password"
                                 "-s" "nido-notion" "-a" (whoami) "-w"])]
    (when (zero? exit) (str/trim out))))

(defn keychain-set!
  "Upsert the Notion integration token into the user's macOS Keychain.
   `-U` upserts if an entry with the same service+account already exists."
  [token]
  (sh! ["security" "add-generic-password"
        "-s" "nido-notion" "-a" (whoami) "-U" "-w" token]))

(defn http-request
  "Wrapped HTTP call so tests can stub. Dispatches on method (:get/:post).
   Returns {:status :body}."
  [method url opts]
  (case method
    :get  (http/get  url (assoc opts :throw false))
    :post (http/post url (assoc opts :throw false))))

(def ^:private notion-api-version "2025-09-03")

(defonce ^:private !data-source-cache (atom {}))

(defn clear-data-source-cache!
  "Test-only / config-reload helper. Clears the cached database-id→data-source-id map."
  []
  (reset! !data-source-cache {}))

(defn retrieve-database
  "GET /v1/databases/<id>. Returns parsed JSON or {:error :kw}.
   Public so the registry validator (nido.notion.views-check) can call it directly."
  [database-id token]
  (let [resp (try
               (http-request
                 :get
                 (str "https://api.notion.com/v1/databases/" database-id)
                 {:headers {"Authorization"  (str "Bearer " token)
                            "Notion-Version" notion-api-version}
                  :timeout 10000})
               (catch Exception e
                 {:status 0 :exception e}))
        {:keys [status body]} resp]
    (cond
      (= status 200) (json/parse-string body true)
      (= status 401) {:error :auth}
      (>= status 500) {:error :server}
      (= status 0)   {:error :network}
      :else          {:error :http :status status})))

(defn retrieve-data-source
  "GET /v1/data_sources/<ds-id>. Returns parsed JSON (including :properties)
   or {:error :kw}. In Notion API 2025-09-03 the schema (property defs +
   options) lives on the data source, NOT on the database — call this when
   you need to inspect the property shape."
  [data-source-id token]
  (let [resp (try
               (http-request
                 :get
                 (str "https://api.notion.com/v1/data_sources/" data-source-id)
                 {:headers {"Authorization"  (str "Bearer " token)
                            "Notion-Version" notion-api-version}
                  :timeout 10000})
               (catch Exception e
                 {:status 0 :exception e}))
        {:keys [status body]} resp]
    (cond
      (= status 200) (json/parse-string body true)
      (= status 401) {:error :auth}
      (>= status 500) {:error :server}
      (= status 0)   {:error :network}
      :else          {:error :http :status status})))

(defn resolve-data-source-id
  "Look up the first data-source id for a database (the only one for most
   databases). Cached per-process — call clear-data-source-cache! after
   schema changes. Returns the id or throws if the database has no data
   sources / the API call failed."
  [database-id token]
  (if-let [cached (get @!data-source-cache database-id)]
    cached
    (let [db (retrieve-database database-id token)]
      (if (:error db)
        (throw (ex-info "Failed to resolve data-source id"
                        {:database database-id :error db}))
        (let [ds-id (-> db :data_sources first :id)]
          (when-not ds-id
            (throw (ex-info "Database has no data sources"
                            {:database database-id})))
          (swap! !data-source-cache assoc database-id ds-id)
          ds-id)))))

(defn data-source-query
  "POST /v1/data_sources/<ds-id>/query with a body containing optional
   :filter (Notion filter map), :sorts, and :page-size. Returns
   {:status 200 :results :has_more} on success or {:status :error :kw}.
   Hard 10s timeout."
  [data-source-id token {:keys [filter sorts page-size]
                         :or   {page-size 100}}]
  (let [body (cond-> {:page_size page-size}
               filter (assoc :filter filter)
               sorts  (assoc :sorts sorts))
        resp (try
               (http-request
                 :post
                 (str "https://api.notion.com/v1/data_sources/" data-source-id "/query")
                 {:headers {"Authorization"  (str "Bearer " token)
                            "Notion-Version" notion-api-version
                            "Content-Type"   "application/json"}
                  :body    (json/generate-string body)
                  :timeout 10000})
               (catch Exception e
                 {:status 0 :exception e}))
        {:keys [status body]} resp]
    (cond
      (= status 200)  (let [parsed (json/parse-string body true)]
                        {:status 200 :results (:results parsed) :has_more (:has_more parsed)})
      (= status 401)  {:status status :error :auth}
      (>= status 500) {:status status :error :server}
      (= status 0)    {:status 0 :error :network}
      :else           {:status status :error :http})))

(defn- normalise-property-name
  "\"Ticket ID\" → :ticket-id"
  [s]
  (-> (str s)
      str/lower-case
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-|-$" "")
      keyword))

(defn- extract-value
  "Pull a value out of a Notion property based on its :type. Unknown types
   render as the raw map so we don't crash."
  [{:keys [type] :as prop}]
  (case type
    "title"        (->> (:title prop) (map :plain_text) (apply str))
    "rich_text"    (->> (:rich_text prop) (map :plain_text) (apply str))
    "select"       (some-> (:select prop) :name)
    "multi_select" (->> (:multi_select prop) (mapv :name))
    "status"       (some-> (:status prop) :name)
    "url"          (:url prop)
    "number"       (:number prop)
    "checkbox"     (:checkbox prop)
    "date"         (some-> (:date prop) :start)
    ;; Fallback: hand back the whole property so debugging is possible
    prop))

(defn- title-of
  "Return the value of the first property whose :type is \"title\"."
  [properties]
  (some (fn [[_ p]] (when (= "title" (:type p)) (extract-value p)))
        properties))

(defn normalise-page
  "Turn a Notion API page object into the spec's event-payload shape.
   See spec §Source: :notion-view / Event-payload schema."
  [{:keys [id url created_time last_edited_time properties]}]
  (let [props-kw (into {}
                       (map (fn [[k v]] [(normalise-property-name k) (extract-value v)]))
                       properties)]
    (merge {:source       :notion-view
            :page-id      id
            :url          url
            :title        (title-of properties)
            :created-time created_time
            :edited-time  last_edited_time
            :properties   props-kw}
           ;; Promote each property to top-level too (filter.clj's lookup
           ;; checks top-level first, then :properties).
           props-kw)))
