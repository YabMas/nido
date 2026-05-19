(ns nido.transcribe.loom
  "Loom public GraphQL client. No auth required for public share/embed
   videos. `http-request` is the redef seam (matches the shape used in
   nido.notion.client).

   Operations:
     FetchVideoTranscript — returns captions_source_url (a VTT URL)
                            or a PublicApiError.
     GetVideoSource       — returns a CDN MP4 URL for whisper fallback."
  (:require
   [babashka.http-client :as http]
   [cheshire.core :as json]
   [clojure.string :as str]))

(def ^:private graphql-url "https://www.loom.com/graphql")

(def ^:private base-headers
  {"Content-Type"             "application/json"
   "Accept"                   "application/json"
   "Origin"                   "https://www.loom.com"
   "apollographql-client-name" "web"})

(defn http-request
  "Wrapped HTTP call so tests can stub. Dispatches on method.
   Returns {:status :body}."
  [method url opts]
  (case method
    :get  (http/get  url (assoc opts :throw false))
    :post (http/post url (assoc opts :throw false))))

(def ^:private video-id-re
  #"https?://(?:www\.)?loom\.com/(?:share|embed)/([0-9a-f]{32})")

(defn extract-video-id
  "Extract a Loom video id from a share/embed URL. Returns nil if the URL
   is not a Loom share/embed."
  [url]
  (when (string? url)
    (when-let [m (re-find video-id-re url)]
      (second m))))

(def ^:private fetch-transcript-query
  "query FetchVideoTranscript($videoId: ID!, $password: String) {
     fetchVideoTranscript(videoId: $videoId, password: $password) {
       __typename
       ... on VideoTranscriptDetails { captions_source_url }
       ... on PublicApiError { message }
     }
   }")

(def ^:private get-video-source-query
  "query GetVideoSource($videoId: ID!) {
     getVideoSource(videoId: $videoId) { url }
   }")

(defn- graphql-post [operation query variables]
  (http-request
    :post graphql-url
    {:headers (assoc base-headers
                "graphql-operation-name" operation)
     :body    (json/generate-string
                {:operationName operation
                 :variables     variables
                 :query         query})
     :timeout 10000}))

(defn fetch-vtt
  "Fetch a public Loom video's VTT transcript via GraphQL + captions URL.
   Returns:
     {:ok? true  :vtt-text \"...\"}
     {:ok? false :error {:reason :loom-transcript-unavailable :detail {:video-id ..}}}
     {:ok? false :error {:reason :loom-api-error              :detail {:message ..}}}
     {:ok? false :error {:reason :loom-server-error           :detail {:status n}}}
     {:ok? false :error {:reason :loom-network-error          :detail {:exception ..}}}"
  [video-id]
  (let [resp (try
               (graphql-post "FetchVideoTranscript"
                 fetch-transcript-query
                 {:videoId video-id})
               (catch Exception e
                 {:status 0 :exception e}))
        {:keys [status body exception]} resp]
    (cond
      (= status 0)
      {:ok? false :error {:reason :loom-network-error
                          :detail {:exception (str exception)}}}

      (>= status 500)
      {:ok? false :error {:reason :loom-server-error :detail {:status status}}}

      (not= status 200)
      {:ok? false :error {:reason :loom-http-error :detail {:status status}}}

      :else
      (let [parsed (json/parse-string body true)
            t      (-> parsed :data :fetchVideoTranscript)]
        (cond
          (= "PublicApiError" (:__typename t))
          {:ok? false :error {:reason :loom-api-error :detail {:message (:message t)}}}

          (str/blank? (:captions_source_url t))
          {:ok? false :error {:reason :loom-transcript-unavailable
                              :detail {:video-id video-id}}}

          :else
          (let [vtt-resp (try (http-request :get (:captions_source_url t)
                                            {:timeout 10000})
                              (catch Exception e {:status 0 :exception e}))]
            (if (= 200 (:status vtt-resp))
              {:ok? true :vtt-text (:body vtt-resp)}
              {:ok? false :error {:reason :loom-server-error
                                  :detail {:status (:status vtt-resp)
                                           :phase  :captions-fetch}}})))))))

(defn video-source-url
  "Resolve a Loom video id to a CDN MP4 URL for whisper fallback.
   Returns {:ok? true :url \"...\"} or {:ok? false :error {...}}."
  [video-id]
  (let [resp (try
               (graphql-post "GetVideoSource"
                 get-video-source-query
                 {:videoId video-id})
               (catch Exception e
                 {:status 0 :exception e}))
        {:keys [status body exception]} resp]
    (cond
      (= status 0)
      {:ok? false :error {:reason :loom-network-error
                          :detail {:exception (str exception)}}}

      (>= status 500)
      {:ok? false :error {:reason :loom-server-error :detail {:status status}}}

      (not= status 200)
      {:ok? false :error {:reason :loom-http-error :detail {:status status}}}

      :else
      (let [u (some-> (json/parse-string body true) :data :getVideoSource :url)]
        (if (str/blank? u)
          {:ok? false :error {:reason :loom-source-unavailable
                              :detail {:video-id video-id}}}
          {:ok? true :url u})))))