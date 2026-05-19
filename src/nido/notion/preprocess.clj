(ns nido.notion.preprocess
  "Walk a Notion page, find video blocks (Loom + Notion-uploaded + other
   MP4-like URLs), call `nido.transcribe/video!` for each, write a
   manifest + agent-readable digest to <out-dir>.

   Task 2 only ships the classifier; Task 3 appends the composer."
  (:require
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
