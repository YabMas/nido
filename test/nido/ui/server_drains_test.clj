(ns nido.ui.server-drains-test
  "Every drains-page response renders under the scope it was requested from, and the page's start of
   a drain routes through lane-drain's start."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.work :as work]
   [nido.ui.server :as server]))

(defn- post [uri qs body]
  (server/handle-request {:request-method :post :uri uri :query-string qs
                          :body (java.io.ByteArrayInputStream. (.getBytes (str body) "UTF-8"))}))

(deftest a-drain-act-answers-under-the-scope-it-was-asked-from
  (let [scopes (atom [])]
    (with-redefs [work/drain-act!    (fn [& _] {:refused "nope"})
                  work/start-drain!  (fn [& _] {:refused "nope"})
                  work/drains        (fn [scope] (swap! scopes conj scope) [])]
      (doseq [uri ["/drains/brian/d1/approve/3" "/drains/brian/d1/land/s1" "/drains/brian/d1/close"
                   "/drains/start"]]
        (reset! scopes [])
        (testing uri
          (let [resp (post uri "scope=brian" "{}")]
            (is (= ["brian"] @scopes) "the list is read under the request's scope")
            (is (str/includes? (str (:body resp)) "nope"))))))))

(deftest the-page-starts-a-drain-through-lane-drains-start
  (let [called (atom nil)]
    (with-redefs [work/start-drain! (fn [p opts] (reset! called [p opts]) {:ok "d1"})
                  work/drains       (fn [_] [])]
      (post "/drains/start" "scope=brian"
            "{\"drain_exit\":\"wrap-conn deleted\",\"drain_established_ws\":\"ws-1\",\"drain_project\":\"other\"}")
      (is (= ["brian" {:exit "wrap-conn deleted" :established-by-ws "ws-1" :established-by-commit nil}]
             @called)
          "a scoped page starts the drain in its scope")
      (post "/drains/start" nil "{\"drain_exit\":\"x\",\"drain_established_commit\":\"abc\",\"drain_project\":\"nido\"}")
      (is (= "nido" (first @called)) "an unscoped page starts it in the project the person picked"))))

(deftest the-drains-page-offers-the-start
  (with-redefs [work/drains (fn [_] [])]
    (let [body (str (:body (server/handle-request {:request-method :get :uri "/structure/drains" :query-string "scope=brian"})))]
      (is (str/includes? body "/drains/start?scope=brian"))
      (is (str/includes? body "/structure?scope=brian") "it is a view of the Structure slice, beside the declared design"))))

(deftest the-old-drains-path-opens-the-structure-slice
  (let [r (server/handle-request {:request-method :get :uri "/drains" :query-string "scope=brian"})]
    (is (= 302 (:status r)))
    (is (= "/structure/drains?scope=brian" (get-in r [:headers "Location"])))))
