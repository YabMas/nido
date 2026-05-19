(ns nido.notion.preprocess-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.notion.preprocess :as pp]))

(deftest classify-loom-video-external
  (let [b {:id "b1" :type "video"
           :video {:type "external"
                   :external {:url "https://www.loom.com/share/abc123def456abc123def456abc123de"}}}]
    (is (= {:url "https://www.loom.com/share/abc123def456abc123def456abc123de"
            :kind :loom
            :block-id "b1"}
           (pp/classify b)))))

(deftest classify-notion-uploaded-video
  (let [b {:id "b2" :type "video"
           :video {:type "file"
                   :file {:url "https://prod-files-secure.s3/x.mp4?sig=z"}}}]
    (is (= {:url "https://prod-files-secure.s3/x.mp4?sig=z"
            :kind :notion-upload
            :block-id "b2"}
           (pp/classify b)))))

(deftest classify-loom-via-embed-block
  (let [b {:id "b3" :type "embed"
           :embed {:url "https://www.loom.com/embed/abc123def456abc123def456abc123de"}}]
    (is (= :loom (:kind (pp/classify b))))))

(deftest classify-loom-via-bookmark
  (let [b {:id "b4" :type "bookmark"
           :bookmark {:url "https://www.loom.com/share/abc123def456abc123def456abc123de"}}]
    (is (= :loom (:kind (pp/classify b))))))

(deftest classify-mp4-external-as-other
  (let [b {:id "b5" :type "video"
           :video {:type "external"
                   :external {:url "https://cdn.example.com/clip.mp4"}}}]
    (is (= :other (:kind (pp/classify b))))))

(deftest classify-ignores-paragraph
  (is (nil? (pp/classify {:id "b6" :type "paragraph"
                          :paragraph {:rich_text [{:plain_text "hi"}]}}))))

(deftest classify-ignores-embed-with-non-video
  (is (nil? (pp/classify {:id "b7" :type "embed"
                          :embed {:url "https://www.figma.com/file/X"}}))))
