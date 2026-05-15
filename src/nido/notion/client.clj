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
  "Wrapped HTTP call (POST) so tests can stub. Returns {:status :body}."
  [_method url opts]
  (http/post url (assoc opts :throw false)))

(defn database-query
  "POST https://api.notion.com/v1/databases/<id>/query. Returns
   {:status :results :has_more} on 2xx, or {:status :error <kw>} on
   4xx/5xx / network failures. Hard 10s timeout."
  [database-id token]
  (let [resp (try
               (http-request
                :post
                (str "https://api.notion.com/v1/databases/" database-id "/query")
                {:headers {"Authorization"  (str "Bearer " token)
                           "Notion-Version" "2022-06-28"
                           "Content-Type"   "application/json"}
                 :body    "{\"page_size\":100}"
                 :timeout 10000})
               (catch Exception e
                 {:status 0 :exception e}))
        {:keys [status body]} resp]
    (cond
      (= status 200)  (let [parsed (json/parse-string body true)]
                        {:status   200
                         :results  (:results parsed)
                         :has_more (:has_more parsed)})
      (= status 401)  {:status status :error :auth}
      (>= status 500) {:status status :error :server}
      (= status 0)    {:status 0     :error :network}
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
