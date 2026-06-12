(ns nido.notion.client-patch-test
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is]]
   [nido.notion.client :as notion]))

(deftest update-page-status-sends-patch-with-status-shape
  (let [captured (atom nil)]
    (with-redefs [notion/http-request
                  (fn [method url opts]
                    (reset! captured {:method method :url url :opts opts})
                    {:status 200 :body "{}"})]
      (let [res (notion/update-page-status! "PAGE1" "Status" "In progress" "tok")]
        (is (= {:ok true} res))
        (is (= :patch (:method @captured)))
        (is (= "https://api.notion.com/v1/pages/PAGE1" (:url @captured)))
        (is (= {"properties" {"Status" {"status" {"name" "In progress"}}}}
               (json/parse-string (-> @captured :opts :body) false))
            "status-type property uses {:status {:name ...}}")
        (is (= "Bearer tok" (get-in @captured [:opts :headers "Authorization"])))
        (is (= "2025-09-03" (get-in @captured [:opts :headers "Notion-Version"])))))))

(deftest update-page-properties-sends-arbitrary-properties
  (let [captured (atom nil)]
    (with-redefs [notion/http-request
                  (fn [method url opts]
                    (reset! captured {:method method :url url :opts opts})
                    {:status 200 :body "{}"})]
      (let [res (notion/update-page-properties!
                  "PAGE1"
                  {"Ball Holder"  {:people [{:id "u1"}]}
                   "Participants" {:people [{:id "u1"} {:id "u2"}]}}
                  "tok")]
        (is (= {:ok true} res))
        (is (= :patch (:method @captured)))
        (is (= {"properties" {"Ball Holder"  {"people" [{"id" "u1"}]}
                              "Participants" {"people" [{"id" "u1"} {"id" "u2"}]}}}
               (json/parse-string (-> @captured :opts :body) false))
            "people-type properties use {:people [{:id ...}]}")))))

(deftest update-page-status-maps-error-codes
  (with-redefs [notion/http-request (fn [_ _ _] {:status 401 :body ""})]
    (is (= {:error :auth} (notion/update-page-status! "P" "Status" "X" "tok"))))
  (with-redefs [notion/http-request (fn [_ _ _] {:status 503 :body ""})]
    (is (= {:error :server} (notion/update-page-status! "P" "Status" "X" "tok"))))
  (with-redefs [notion/http-request (fn [_ _ _] {:status 0})]
    (is (= {:error :network} (notion/update-page-status! "P" "Status" "X" "tok"))))
  (with-redefs [notion/http-request (fn [_ _ _] {:status 404 :body ""})]
    (is (= {:error :http :status 404} (notion/update-page-status! "P" "Status" "X" "tok")))))
