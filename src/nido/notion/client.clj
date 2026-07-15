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
  "Wrapped HTTP call so tests can stub. Dispatches on method (:get/:post/:patch).
   Returns {:status :body}."
  [method url opts]
  (case method
    :get   (http/get   url (assoc opts :throw false))
    :post  (http/post  url (assoc opts :throw false))
    :patch (http/patch url (assoc opts :throw false))))

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

(defn retrieve-page
  "GET /v1/pages/<page-id>. Returns parsed JSON (including :properties) or
   {:error :kw}. Keys are keywordised, so a property named \"Participants\"
   is reached at (get-in page [:properties :Participants])."
  [page-id token]
  (let [resp (try
               (http-request
                 :get
                 (str "https://api.notion.com/v1/pages/" page-id)
                 {:headers {"Authorization"  (str "Bearer " token)
                            "Notion-Version" notion-api-version}
                  :timeout 10000})
               (catch Exception e {:status 0 :exception e}))
        {:keys [status body]} resp]
    (cond
      (= status 200) (json/parse-string body true)
      (= status 401) {:error :auth}
      (>= status 500) {:error :server}
      (= status 0)   {:error :network}
      :else          {:error :http :status status})))

(defn update-page-properties!
  "PATCH /v1/pages/<page-id> with a Notion-shaped :properties map. Keys are
   property names used verbatim as JSON keys; values are Notion property-value
   maps (e.g. {:status {:name \"In progress\"}} for a status property,
   {:people [{:id <user-id>}]} for a people property). Returns {:ok true} on
   200, else {:error :kw} (with :status for generic HTTP)."
  [page-id properties token]
  (let [resp (try
               (http-request
                 :patch
                 (str "https://api.notion.com/v1/pages/" page-id)
                 {:headers {"Authorization"  (str "Bearer " token)
                            "Notion-Version" notion-api-version
                            "Content-Type"   "application/json"}
                  :body    (json/generate-string {:properties properties})
                  :timeout 10000})
               (catch Exception e {:status 0 :exception e}))
        {:keys [status]} resp]
    (cond
      (= status 200)  {:ok true}
      (= status 401)  {:error :auth}
      (>= status 500) {:error :server}
      (= status 0)    {:error :network}
      :else           {:error :http :status status})))

(defn update-page-status!
  "PATCH /v1/pages/<page-id>, setting a Status-type property to a named option.
   Notion 'Status' properties take {:status {:name <option>}} — NOT :select.
   property-name is a string matching the Notion property name exactly (e.g.
   \"Status\"). Thin wrapper over update-page-properties!."
  [page-id property-name status-name token]
  (update-page-properties! page-id {property-name {:status {:name status-name}}} token))

(defn normalise-property-name
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
    "unique_id"    (let [{:keys [prefix number]} (:unique_id prop)]
                     (if prefix (str prefix "-" number) (str number)))
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

(defn create-page!
  "POST /v1/pages creating a page in the data source `data-source-id`. `fields`:
     {:title s :description s :type s :status s :priority s-or-nil}
   Builds the Notion property payload (title = \"Task result\", status = \"Status\",
   type = \"Type\", priority = \"Priority\" when non-blank) plus a single paragraph
   block holding :description, and returns the created page via `normalise-page`
   (so the caller reads back :id = the auto-assigned BR-####, :page-id, :url).
   Returns {:error :kw} on failure; never throws."
  [data-source-id token {:keys [title description type status priority]}]
  (let [props (cond-> {"Task result" {:title [{:text {:content title}}]}
                       "Status"      {:status {:name status}}
                       "Type"        {:select {:name type}}}
                (not (str/blank? priority)) (assoc "Priority" {:select {:name priority}}))
        body  {:parent     {:type "data_source_id" :data_source_id data-source-id}
               :properties props
               :children   [{:object "block" :type "paragraph"
                             :paragraph {:rich_text [{:text {:content (or description "")}}]}}]}
        resp  (try
                (http-request
                  :post "https://api.notion.com/v1/pages"
                  {:headers {"Authorization"  (str "Bearer " token)
                             "Notion-Version" notion-api-version
                             "Content-Type"   "application/json"}
                   :body    (json/generate-string body)
                   :timeout 10000})
                (catch Exception e {:status 0 :exception e}))
        {:keys [status body]} resp]
    (cond
      (= status 200) (normalise-page (json/parse-string body true))
      (= status 401) {:error :auth}
      (>= (or status 0) 500) {:error :server}
      (= status 0)   {:error :network}
      :else          {:error :http :status status})))

(defn retrieve-block-children
  "GET /v1/blocks/<block-id>/children. Single page; pass `:start-cursor`
   to get the next page. Returns:
     {:status 200 :results [...] :has_more bool :next_cursor str}
     {:status N   :error :auth|:server|:network|:http}"
  [block-id token {:keys [start-cursor]}]
  (let [base-url (str "https://api.notion.com/v1/blocks/" block-id "/children?page_size=100")
        url      (if start-cursor (str base-url "&start_cursor=" start-cursor) base-url)
        resp     (try
                   (http-request
                     :get url
                     {:headers {"Authorization"  (str "Bearer " token)
                                "Notion-Version" notion-api-version}
                      :timeout 10000})
                   (catch Exception e {:status 0 :exception e}))
        {:keys [status body]} resp]
    (cond
      (= status 200) (let [parsed (json/parse-string body true)]
                       {:status 200
                        :results     (:results parsed)
                        :has_more    (:has_more parsed)
                        :next_cursor (:next_cursor parsed)})
      (= status 401) {:status status :error :auth}
      (>= status 500) {:status status :error :server}
      (= status 0)    {:status 0     :error :network}
      :else           {:status status :error :http})))

(defn walk-blocks
  "Recursively walk a page's block tree. Returns a vector of
   {:block <notion-block-map> :depth n}. Bounded by :max-depth (default 10)
   and :max-total (default 1000). Pagination is followed automatically.

   Throws ex-info on auth/network/server failure — the caller is expected
   to surface this as a page-level failure (no partial manifest written)."
  [root-id token {:keys [max-depth max-total]
                  :or   {max-depth 10 max-total 1000}}]
  (let [seen (volatile! 0)]
    (letfn [(visit [block-id depth]
              (if (or (> depth max-depth) (>= @seen max-total))
                []
                (loop [cursor nil
                       acc    []]
                  (let [{:keys [status results has_more next_cursor]
                         :as page} (retrieve-block-children
                                     block-id token
                                     (when cursor {:start-cursor cursor}))]
                    (when-not (= 200 status)
                      (throw (ex-info "Notion block fetch failed"
                                      {:block block-id :error page})))
                    (let [acc' (reduce
                                 (fn [a b]
                                   (if (>= @seen max-total)
                                     (reduced a)
                                     (do (vswap! seen inc)
                                         (let [entry {:block b :depth depth}
                                               kids  (when (:has_children b)
                                                       (visit (:id b) (inc depth)))]
                                           (cond-> (conj a entry)
                                             (seq kids) (into kids))))))
                                 acc results)]
                      (if (and has_more next_cursor (< @seen max-total))
                        (recur next_cursor acc')
                        acc'))))))]
      (visit root-id 0))))
