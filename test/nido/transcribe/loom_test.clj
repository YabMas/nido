(ns nido.transcribe.loom-test
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is]]
   [nido.transcribe.loom :as loom]))

(deftest extract-video-id-from-share-url
  (is (= "abc123def456abc123def456abc123de"
         (loom/extract-video-id "https://www.loom.com/share/abc123def456abc123def456abc123de"))))

(deftest extract-video-id-from-embed-url
  (is (= "abc123def456abc123def456abc123de"
         (loom/extract-video-id "https://www.loom.com/embed/abc123def456abc123def456abc123de"))))

(deftest extract-video-id-with-trailing-query
  (is (= "abc123def456abc123def456abc123de"
         (loom/extract-video-id "https://www.loom.com/share/abc123def456abc123def456abc123de?sid=foo"))))

(deftest extract-video-id-non-loom-returns-nil
  (is (nil? (loom/extract-video-id "https://example.com/video"))))

(defn- stub-http [responses]
  (let [calls (atom [])
        ix    (atom 0)]
    [calls
     (fn [method url opts]
       (swap! calls conj {:method method :url url :opts opts})
       (let [r (nth responses @ix)]
         (swap! ix inc)
         r))]))

(deftest fetch-vtt-happy-path
  (let [[calls stub] (stub-http
                      [{:status 200
                        :body   (json/generate-string
                                  {:data {:fetchVideoTranscript
                                          {:captions_source_url "https://cdn.loom.com/x.vtt"}}})}
                       {:status 200 :body "WEBVTT\n\n00:00.000 --> 00:01.000\nhi\n"}])]
    (with-redefs [loom/http-request stub]
      (let [r (loom/fetch-vtt "abc123def456abc123def456abc123de")]
        (is (:ok? r))
        (is (= "WEBVTT\n\n00:00.000 --> 00:01.000\nhi\n" (:vtt-text r)))))
    (is (= 2 (count @calls)))
    (is (= :post (-> @calls first :method)))
    (is (= "https://www.loom.com/graphql" (-> @calls first :url)))
    (is (= "FetchVideoTranscript" (-> @calls first :opts :headers (get "graphql-operation-name"))))
    (is (= :get (-> @calls second :method)))
    (is (= "https://cdn.loom.com/x.vtt" (-> @calls second :url)))))

(deftest fetch-vtt-handles-transcript-disabled
  (let [[_ stub] (stub-http
                   [{:status 200
                     :body   (json/generate-string
                               {:data {:fetchVideoTranscript {:captions_source_url nil}}})}])]
    (with-redefs [loom/http-request stub]
      (let [r (loom/fetch-vtt "abc123def456abc123def456abc123de")]
        (is (not (:ok? r)))
        (is (= :loom-transcript-unavailable (-> r :error :reason)))))))

(deftest fetch-vtt-handles-public-api-error
  (let [[_ stub] (stub-http
                   [{:status 200
                     :body   (json/generate-string
                               {:data {:fetchVideoTranscript
                                       {:__typename "PublicApiError"
                                        :message    "Video is password protected"}}})}])]
    (with-redefs [loom/http-request stub]
      (let [r (loom/fetch-vtt "abc123def456abc123def456abc123de")]
        (is (not (:ok? r)))
        (is (= :loom-api-error (-> r :error :reason)))
        (is (= "Video is password protected" (-> r :error :detail :message)))))))

(deftest fetch-vtt-handles-5xx
  (let [[_ stub] (stub-http [{:status 502 :body "bad gateway"}])]
    (with-redefs [loom/http-request stub]
      (let [r (loom/fetch-vtt "abc123def456abc123def456abc123de")]
        (is (not (:ok? r)))
        (is (= :loom-server-error (-> r :error :reason)))))))

(deftest video-source-url-returns-mp4
  (let [[_ stub] (stub-http
                   [{:status 200
                     :body   (json/generate-string
                               {:data {:getVideoSource {:url "https://cdn.loom.com/x.mp4"}}})}])]
    (with-redefs [loom/http-request stub]
      (let [r (loom/video-source-url "abc123def456abc123def456abc123de")]
        (is (:ok? r))
        (is (= "https://cdn.loom.com/x.mp4" (:url r)))))))

(deftest fetch-vtt-handles-network-error
  ;; http-request throws (e.g. connection refused) → :loom-network-error
  (with-redefs [loom/http-request (fn [_method _url _opts]
                                    (throw (java.io.IOException. "connection refused")))]
    (let [r (loom/fetch-vtt "abc123def456abc123def456abc123de")]
      (is (not (:ok? r)))
      (is (= :loom-network-error (-> r :error :reason)))
      (is (re-find #"connection refused" (-> r :error :detail :exception))))))

(deftest fetch-vtt-handles-non-5xx-non-200
  ;; e.g. 404 / 400 — distinct from 5xx
  (let [[_ stub] (stub-http [{:status 404 :body ""}])]
    (with-redefs [loom/http-request stub]
      (let [r (loom/fetch-vtt "abc123def456abc123def456abc123de")]
        (is (not (:ok? r)))
        (is (= :loom-http-error (-> r :error :reason)))
        (is (= 404 (-> r :error :detail :status)))))))

(deftest video-source-url-blank-url-returns-source-unavailable
  (let [[_ stub] (stub-http
                   [{:status 200
                     :body   (json/generate-string
                               {:data {:getVideoSource {:url ""}}})}])]
    (with-redefs [loom/http-request stub]
      (let [r (loom/video-source-url "abc123def456abc123def456abc123de")]
        (is (not (:ok? r)))
        (is (= :loom-source-unavailable (-> r :error :reason)))))))
