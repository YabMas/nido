(ns nido.transcribe
  "URL → VTT dispatch. Loom share/embed URLs use the public GraphQL
   transcript endpoint with a whisper fallback for transcript-disabled
   videos. Anything else downloads + whispers.

   `download-to-temp!` is a redef seam so tests stub network I/O."
  (:require
   [babashka.fs :as fs]
   [babashka.http-client :as http]
   [nido.transcribe.loom :as loom]
   [nido.transcribe.whisper :as whisper]))

(defn download-to-temp!
  "GET `url` to a temp file. Returns {:ok? true :path \"...\"}
   or {:ok? false :error {:reason :download-failed :detail {:status n}}}.

   The spec calls for a 1 GB cap; v1 enforces only the 5-minute HTTP
   timeout. If oversized videos become a real concern, wrap the
   transferTo loop with a byte counter and bail at the limit."
  [url]
  (let [tmp  (fs/create-temp-file {:prefix "nido-transcribe-" :suffix ".bin"})
        path (str tmp)]
    (try
      (let [resp (http/get url {:as :stream :throw false :timeout 300000})]
        (if (= 200 (:status resp))
          (do (with-open [in  (:body resp)
                          out (java.io.FileOutputStream. path)]
                (.transferTo in out))
              {:ok? true :path path})
          (do (fs/delete-if-exists path)
              {:ok? false :error {:reason :download-failed
                                  :detail {:status (:status resp) :url url}}})))
      (catch Exception e
        (fs/delete-if-exists path)
        {:ok? false :error {:reason :download-failed
                            :detail {:exception (str e) :url url}}}))))

(defn- write-vtt! [out vtt-text]
  (fs/create-dirs (or (fs/parent out) "."))
  (spit out vtt-text))

(defn- whisper-from-url
  "Download URL, run whisper into a sibling out-dir, then move the
   produced VTT to `out`. Cleans up the temp MP4 on success or failure."
  [{:keys [url out model timeout-s]}]
  (let [dl (download-to-temp! url)]
    (if-not (:ok? dl)
      dl
      (let [in-path (:path dl)
            out-dir (str (or (fs/parent out) "."))]
        (try
          (let [r (whisper/run! {:input    in-path
                                 :model    model
                                 :out-dir  out-dir
                                 :timeout-s timeout-s})]
            (if-not (:ok? r)
              r
              (do (fs/move (:vtt-path r) out {:replace-existing true})
                  {:ok? true :vtt-path out :transcript-source :whisper})))
          (finally
            (fs/delete-if-exists in-path)))))))

(defn video!
  "Transcribe a video URL into a VTT at :out. See ns docstring.
   Required opts: :url :out. Optional: :model (default :small),
   :timeout-s (default 300)."
  [opts]
  (let [{:keys [url out] :as opts} (merge {:model :small :timeout-s 300} opts)
        video-id (loom/extract-video-id url)]
    (if-not video-id
      (whisper-from-url opts)
      (let [r (loom/fetch-vtt video-id)]
        (if (:ok? r)
          (do (write-vtt! out (:vtt-text r))
              {:ok? true :vtt-path out :transcript-source :loom-graphql})
          ;; Loom transcript path failed — fall back via MP4 source.
          (let [src (loom/video-source-url video-id)]
            (if-not (:ok? src)
              src
              (whisper-from-url (assoc opts :url (:url src))))))))))
