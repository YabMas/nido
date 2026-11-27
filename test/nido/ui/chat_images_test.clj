(ns nido.ui.chat-images-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [nido.libraries.ui.block :as block]
            [nido.session.chat :as chat]
            [nido.ui.chat-host :as host]
            [nido.ui.components.chat :as panel]
            [org.httpkit.server :as http])
  (:import (java.io ByteArrayInputStream)))

(def ^:private png-bytes
  (byte-array (concat [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A] (repeat 24 0))))

(defn- bytes-of [& xs] (byte-array (map unchecked-byte xs)))

(def ^:private view {:id "nido-chat" :router (host/router "/_chat") :attach? true})

(defn- fresh-host []
  (host/host {:project "nido" :session "test" :name (str (gensym "img"))} view
             ["_imgtest" (str (gensym))] (constantly [])))

(defn- post-image [h ^bytes bs]
  (host/handle! h {:request-method :post :uri (str "/" (str/join "/" (:prefix h)) "/attach")
                   :body (ByteArrayInputStream. bs)}))

(use-fixtures :each
  (fn [t] (with-redefs [http/send! (fn [& _] nil)] (t))))

(deftest an-upload-becomes-an-image-only-by-its-bytes
  (testing "each accepted type is read from its signature"
    (is (= "image/png" (:mime (host/parse-image {:bytes png-bytes}))))
    (is (= "image/jpeg" (:mime (host/parse-image {:bytes (bytes-of 0xFF 0xD8 0xFF 0xE0 0 0)}))))
    (is (= "image/gif" (:mime (host/parse-image {:bytes (.getBytes "GIF89a....")}))))
    (is (= "image/webp" (:mime (host/parse-image {:bytes (.getBytes "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ")})))))
  (testing "an image carries its bytes and is named by their digest"
    (let [img (host/parse-image {:bytes png-bytes})]
      (is (= (seq png-bytes) (seq (.decode (java.util.Base64/getDecoder) ^String (:data img)))))
      (is (re-matches #"[0-9a-f]{64}" (:digest img)))))
  (testing "anything else is refused, saying why"
    (is (re-find #"not an image" (:refused (host/parse-image {:bytes (.getBytes "%PDF-1.7")}))))
    (is (re-find #"5 MB" (:refused (host/parse-image {:bytes (byte-array (concat (seq png-bytes) (repeat (* 5 1024 1024) 0)))}))))
    (is (:refused (host/parse-image {:bytes (byte-array 0)})))))

(deftest staged-images-are-the-conversations-until-a-send-takes-them
  (let [h    (fresh-host)
        sent (atom nil)]
    (is (= 204 (:status (post-image h png-bytes))))
    (is (= 204 (:status (post-image h png-bytes))) "the same image twice is staged once")
    (is (= 1 (count @(:staged h))))
    (testing "a refused upload changes nothing staged and says why"
      (let [r (post-image h (.getBytes "not an image"))]
        (is (= 422 (:status r)))
        (is (re-find #"PNG, JPEG, GIF or WebP" (:body r)))
        (is (= 1 (count @(:staged h))))))
    (testing "the image is served by its digest, for good"
      (let [d (:digest (first @(:staged h)))
            r (host/handle! h {:request-method :get :uri (str "/" (str/join "/" (:prefix h)) "/image/" d)})]
        (is (= "image/png" (get-in r [:headers "Content-Type"])))
        (is (str/includes? (get-in r [:headers "Cache-Control"]) "immutable"))))
    (testing "a send takes the staged images with the words, and clears them"
      (with-redefs [chat/send! (fn [_ text refs images] (reset! sent [text refs images]) true)]
        (host/handle! h {:request-method :post :uri (str "/" (str/join "/" (:prefix h)) "/send")
                         :body (ByteArrayInputStream. (.getBytes "{\"chat\":{\"nido_chat\":{\"text\":\"what is this?\"}}}"))}))
      (is (= "what is this?" (first @sent)))
      (is (= ["image/png"] (map :mime (nth @sent 2))))
      (is (empty? @(:staged h))))))

(deftest images-alone-are-a-message
  (let [h    (fresh-host)
        sent (atom nil)
        send #(host/handle! h {:request-method :post :uri (str "/" (str/join "/" (:prefix h)) "/send")
                               :body (ByteArrayInputStream. (.getBytes "{\"chat\":{\"nido_chat\":{\"text\":\"  \"}}}"))})]
    (with-redefs [chat/send! (fn [_ text _ images] (reset! sent [text images]) true)]
      (send)
      (is (nil? @sent) "no words and no images send nothing")
      (post-image h png-bytes)
      (send))
    (is (= "" (first @sent)))
    (is (= ["image/png"] (map :mime (second @sent))))
    (is (empty? @(:staged h)))))

(deftest a-refused-send-keeps-the-staged-images
  (let [h (fresh-host)]
    (post-image h png-bytes)
    (with-redefs [chat/send! (fn [& _] nil)]
      (host/handle! h {:request-method :post :uri (str "/" (str/join "/" (:prefix h)) "/send")
                       :body (ByteArrayInputStream. (.getBytes "{\"chat\":{\"nido_chat\":{\"text\":\"hi\"}}}"))}))
    (is (= 1 (count @(:staged h))))))

(deftest a-body-the-decoder-refuses-sends-nothing-and-keeps-the-staged-images
  (let [h    (fresh-host)
        sent (atom nil)]
    (post-image h png-bytes)
    (with-redefs [chat/send! (fn [& args] (reset! sent args) true)]
      (doseq [body ["not json" "{\"chat\":{\"nido_chat\":{}}}"]]
        (host/handle! h {:request-method :post :uri (str "/" (str/join "/" (:prefix h)) "/send")
                         :body (ByteArrayInputStream. (.getBytes ^String body))})))
    (is (nil? @sent))
    (is (= 1 (count @(:staged h))))))

(deftest a-message-carries-at-most-four-images
  (let [h (fresh-host)]
    (doseq [i (range 4)]
      (post-image h (byte-array (concat (seq png-bytes) [i]))))
    (let [r (post-image h (byte-array (concat (seq png-bytes) [9])))]
      (is (= 409 (:status r)))
      (is (re-find #"at most 4" (:body r))))
    (testing "unattaching one makes room"
      (host/handle! h {:request-method :post
                       :uri (str "/" (str/join "/" (:prefix h)) "/unattach/" (:digest (first @(:staged h))))})
      (is (= 3 (count @(:staged h)))))))

(defn- panel-html [v]
  (host/html (block/render panel/panel (:id v) {:transcript {:items []} :attach? (boolean (:attach? v))}
                           {} (:router v))))

(deftest attaching-is-offered-only-where-the-view-says
  (let [with    (panel-html view)
        without (panel-html (assoc view :id "catalog-chat" :attach? false))]
    (is (str/includes? with "chat:nido-chat:attachments"))
    (is (str/includes? with "type=\"file\""))
    (is (not (str/includes? without "type=\"file\"")))
    (is (not (str/includes? without ":attachments")))
    (is (nil? (host/handle! (assoc (fresh-host) :view (assoc view :attach? false))
                            {:request-method :post :uri "/x/attach" :body nil})))))

(deftest images-are-shown-by-url-never-inline
  (let [img  {:mime "image/png" :data "iVBORw0KGgoAAAA=" :digest "abc"}
        item (str (host/html (host/part view :item {:item {:id "i0" :kind :user :text "look" :refs [] :images [img]}})))
        chips (str (host/html (host/part view :attachments {:staged [img]})))]
    (is (str/includes? item "src=\"/_chat/image/abc\""))
    (is (str/includes? chips "src=\"/_chat/image/abc\""))
    (is (not (str/includes? (str item chips) "iVBORw0KGgo")))
    (is (not (str/includes? (str item chips) "data:image")))
    (testing "a message without images renders no image markup"
      (is (not (str/includes? (str (host/html (host/part view :item {:item {:id "i0" :kind :user :text "look" :refs [] :images []}})))
                              "chat-imgs"))))))

(deftest the-limit-holds-against-concurrent-attaches
  (let [h       (fresh-host)
        parse   host/parse-image
        entered (promise)
        release (promise)]
    (doseq [i (range 3)]
      (post-image h (byte-array (concat (seq png-bytes) [i]))))
    ;; The first upload passes the early check, then waits while a second fills the stage.
    (with-redefs [host/parse-image (fn [m] (when (deliver entered true) (deref release 2000 nil)) (parse m))]
      (let [slow (future (post-image h (byte-array (concat (seq png-bytes) [7]))))]
        (deref entered 2000 nil)
        (is (= 204 (:status (post-image h (byte-array (concat (seq png-bytes) [8]))))))
        (deliver release true)
        (is (= 409 (:status (deref slow 2000 nil))) "the upload that would make five is refused")
        (is (= 4 (count @(:staged h))))))))

(deftest an-unattach-does-not-fall-between-a-send-and-its-acceptance
  (let [h       (fresh-host)
        entered (promise)
        release (promise)
        sent    (atom nil)
        uri     #(str "/" (str/join "/" (:prefix h)) %)]
    (post-image h png-bytes)
    (post-image h (byte-array (concat (seq png-bytes) [1])))
    (let [d (:digest (second @(:staged h)))]
      (with-redefs [chat/send! (fn [_ _ _ images] (deliver entered true) (deref release 2000 nil)
                                 (reset! sent images) true)]
        (let [sending   (future (host/handle! h {:request-method :post :uri (uri "/send")
                                                 :body (ByteArrayInputStream. (.getBytes "{\"chat\":{\"nido_chat\":{\"text\":\"hi\"}}}"))}))
              _         (deref entered 2000 nil)
              unattach  (future (host/handle! h {:request-method :post :uri (uri (str "/unattach/" d))}))]
          (Thread/sleep 100)
          (is (not (realized? unattach)) "the unattach waits for the send it raced")
          (deliver release true)
          @sending @unattach
          (is (= 2 (count @sent)))
          (is (empty? @(:staged h))))))))

(deftest a-new-conversation-drops-the-staged-images
  (let [h   (fresh-host)
        uri (str "/" (str/join "/" (:prefix h)) "/reset")]
    (post-image h png-bytes)
    (testing "a reset refused while a turn runs keeps them"
      (with-redefs [chat/reset! (constantly nil)]
        (host/handle! h {:request-method :post :uri uri}))
      (is (= 1 (count @(:staged h)))))
    (testing "an accepted reset drops them"
      (with-redefs [chat/reset! (constantly true)]
        (is (= 204 (:status (host/handle! h {:request-method :post :uri uri})))))
      (is (empty? @(:staged h))))))

(deftest a-send-does-not-fall-between-a-reset-and-its-stage-clear
  (let [h       (fresh-host)
        entered (promise)
        release (promise)
        sent    (atom nil)
        uri     #(str "/" (str/join "/" (:prefix h)) %)]
    (post-image h png-bytes)
    (with-redefs [chat/reset! (fn [_] (deliver entered true) (deref release 2000 nil) true)
                  chat/send!  (fn [_ _ _ images] (reset! sent images) true)]
      (let [resetting (future (host/handle! h {:request-method :post :uri (uri "/reset")}))
            _         (deref entered 2000 nil)
            sending   (future (host/handle! h {:request-method :post :uri (uri "/send")
                                               :body (ByteArrayInputStream. (.getBytes "{\"chat\":{\"nido_chat\":{\"text\":\"hi\"}}}"))}))]
        (Thread/sleep 100)
        (is (not (realized? sending)) "the send waits for the reset it raced")
        (deliver release true)
        @resetting @sending
        (is (= [] @sent) "the reset discarded the images before the send took any")
        (is (empty? @(:staged h)))))))
