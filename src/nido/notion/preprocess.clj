(ns nido.notion.preprocess
  "Walk a Notion page, find video blocks (Loom + Notion-uploaded + other
   MP4-like URLs), call `nido.transcribe.core/video!` for each, write a
   manifest + agent-readable digest to <out-dir>."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [nido.platform.io :as io]
   [nido.notion.client :as notion]
   [nido.transcribe.loom :as loom]))

(def ^:private mp4-ext-re #"(?i)\.(?:mp4|mov|webm|mkv)(?:\?|$)")

(defn- loom-url? [url] (some? (loom/extract-video-id url)))
(defn- mp4-url?  [url] (and url (re-find mp4-ext-re url)))

(defn classify
  "Return {:url :kind :block-id} or nil. Pure function.
   :kind is :loom, :notion-upload, or :other."
  [{:keys [id type] :as block}]
  (case type
    "video"
    (let [v   (:video block)
          ext (some-> v :external :url)
          fl  (some-> v :file :url)]
      (cond
        (and ext (loom-url? ext)) {:url ext :kind :loom            :block-id id}
        (and fl  (loom-url? fl))  {:url fl  :kind :loom            :block-id id}
        fl                        {:url fl  :kind :notion-upload   :block-id id}
        (and ext (mp4-url? ext))  {:url ext :kind :other           :block-id id}
        :else                     nil))

    "embed"
    (let [u (some-> block :embed :url)]
      (when (loom-url? u) {:url u :kind :loom :block-id id}))

    "bookmark"
    (let [u (some-> block :bookmark :url)]
      (when (loom-url? u) {:url u :kind :loom :block-id id}))

    nil))

;; ─── Composer (Task 3) ────────────────────────────────────────────────────────

(defn fetch-page-meta!
  "GET /v1/pages/<id> via the notion http-request seam. Returns parsed map
   or nil on failure (last_edited_time is best-effort)."
  [page-id token]
  (let [resp (try
               (notion/http-request
                 :get (str "https://api.notion.com/v1/pages/" page-id)
                 {:headers {"Authorization"  (str "Bearer " token)
                            "Notion-Version" "2025-09-03"}
                  :timeout 10000})
               (catch Exception _ nil))]
    (when (= 200 (:status resp))
      (json/parse-string (:body resp) true))))

(defn shell-bb-task
  "Shell-out to a bb task. Returns {:exit :out :err}. Redef seam."
  [args]
  (let [proc @(p/process args {:out :string :err :string})]
    {:exit (:exit proc)
     :out  (str (:out proc))
     :err  (str (:err proc))}))

(defn- short-id [url]
  (let [id (or (loom/extract-video-id url)
               ;; Strip fragment + query, then take the last path segment.
               (-> url
                   (str/split #"[?#]" 2)
                   first
                   (str/split #"/")
                   last))
        id-str (str (or id "unknown"))
        cleaned (str/replace id-str #"[^A-Za-z0-9]" "")
        cleaned (if (str/blank? cleaned) "unknown" cleaned)]
    (subs cleaned 0 (min 8 (count cleaned)))))

(defn- vtt-filename [idx kind url]
  (format "%02d-%s-%s.vtt" idx (name kind) (short-id url)))

(defn- transcribe-one!
  [{:keys [idx url kind out-dir per-video-s model]}]
  (let [vtt-name (vtt-filename idx kind url)
        vtt-path (str (fs/path out-dir vtt-name))
        args     ["bb" "nido:transcribe-video" url
                  ":out" vtt-path
                  ":model" (str ":" (name model))
                  ":timeout" (str per-video-s "s")]
        {:keys [exit out err]} (shell-bb-task args)]
    (if (zero? exit)
      ;; Parse the bb task's stdout EDN to recover the true transcript-source.
      ;; Falls back to inferring from kind if stdout is missing/unparseable.
      (let [parsed-out (try (edn/read-string (str/trim out))
                            (catch Exception _ nil))
            source     (or (:transcript-source parsed-out)
                           (if (= kind :loom) :loom-graphql :whisper))]
        {:idx idx :url url :kind kind
         :transcript-source source
         :status :ok
         :vtt-path vtt-name
         :error nil})
      ;; bb's `:init` block writes a banner to stderr before tasks run.
      ;; The transcribe task's EDN error map is the LAST non-blank line that starts with `{`.
      (let [last-edn-line (->> (str/split-lines err)
                               (filter (fn [l] (str/starts-with? (str/triml l) "{")))
                               last)
            parsed (try (edn/read-string (str/trim (or last-edn-line err)))
                        (catch Exception _ {:reason :unknown :detail {:stderr err}}))]
        {:idx idx :url url :kind kind
         :transcript-source :none
         :status :failed
         :vtt-path nil
         :error parsed}))))

(defn- ->preview [vtt-path]
  ;; First several caption lines, ~300 chars.
  (when (fs/exists? vtt-path)
    (let [lines (->> (str/split-lines (slurp vtt-path))
                     (remove #(or (str/blank? %) (re-find #"-->" %) (= % "WEBVTT")))
                     (take 6)
                     (str/join " "))]
      (if (> (count lines) 300) (str (subs lines 0 300) "…") lines))))

(defn- digest-md [out-dir videos]
  (let [header (format "# Pre-staged transcripts (%d %s)\n"
                       (count videos)
                       (if (= 1 (count videos)) "video" "videos"))
        sections (for [{:keys [idx url kind status transcript-source vtt-path error]} videos]
                   (if (= status :ok)
                     (let [full (str (fs/path out-dir vtt-path))]
                       (str (format "## %d. %s — %s\n" idx (name kind) url)
                            (format "Transcript source: %s. Full VTT: `%s`.\n\n"
                                    (name (or transcript-source :whisper)) vtt-path)
                            (when-let [pv (->preview full)]
                              (str "> First few lines for context:\n> " pv "\n"))))
                     (str (format "## %d. ⚠️ %s — failed\n" idx (name kind))
                          (format "Source: `%s`.\nReason: %s.\n"
                                  url (-> error :reason name)))))]
    (str/join "\n" (cons header sections))))

(defn preprocess-ticket!
  "Walk a Notion page, transcribe every video, write manifest + digest
   to out-dir. See ns docstring. Returns {:ok? bool [:manifest m] [:error e]}."
  [{:keys [page-id token out-dir budget-s max-videos model]
    :or   {budget-s 600 max-videos 50 model :small}}]
  (fs/create-dirs out-dir)
  (let [meta      (fetch-page-meta! page-id token)
        last-edit (some-> meta :last_edited_time)]
    (try
      (let [blocks  (notion/walk-blocks page-id token {})
            videos  (->> blocks
                         (keep (comp classify :block))
                         (take max-videos)
                         (vec))
            n       (count videos)
            ;; Per-video timeout: spec caps at 5 min wall-clock, with a 1-min floor.
            per-s   (if (zero? n) 0 (min 300 (max 60 (quot budget-s (max 1 n)))))
            results (vec (map-indexed
                           (fn [i v]
                             (transcribe-one!
                               (assoc v :idx (inc i) :out-dir out-dir
                                        :per-video-s per-s :model model)))
                           videos))
            manifest {:page-id page-id
                      :page-last-edited-time last-edit
                      :generated-at (str (java.time.Instant/now))
                      :videos results}]
        (io/write-edn! (str (fs/path out-dir "manifest.edn")) manifest)
        (spit (str (fs/path out-dir "transcripts.md"))
              (digest-md out-dir results))
        {:ok? true :manifest manifest})
      (catch clojure.lang.ExceptionInfo e
        (let [data   (ex-data e)
              reason (case (-> data :error :error)
                       :auth    :notion-auth
                       :server  :notion-server-error
                       :network :notion-network-error
                       :http    :notion-http-error
                       :notion-walk-failed)]
          {:ok? false :error {:reason reason :detail data}})))))
