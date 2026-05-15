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
