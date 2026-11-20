(ns nido.ui.presentation-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nido.platform.project :as project]
            [nido.session.state :as state]
            [nido.ui.presentation :as presentation]
            [nido.ui.server :as server]
            [org.httpkit.server :as httpkit]))

(defn- with-world
  "Run `f` against projects `projects` and a registry `registry`, `up` the app ports that answer."
  [projects registry up f]
  (with-redefs [project/get-project      (fn [p] (get projects p))
                state/read-registry      (constantly registry)
                presentation/answers?    (fn [port] (contains? up port))]
    (f)))

(def ^:private brian {"brian" {:directory "/x" :catalog-path "/_catalog"}})

(def ^:private two-sessions
  {"/w/a" {:project-name "brian" :session-name "old" :app-port 4001 :created-at "2026-01-01"}
   "/w/b" {:project-name "brian" :session-name "new" :app-port 4002 :created-at "2026-02-01"}
   "/w/c" {:project-name "other" :session-name "x"   :app-port 4003 :created-at "2026-03-01"}})

(deftest where-a-catalog-is-served
  (testing "nido's is the dashboard's own"
    (is (= {:status :dashboard :src "/_catalog?embed=1"} (presentation/target "nido"))))
  (testing "a project declaring none says how to declare one"
    (with-world {"brian" {:directory "/x"}} two-sessions #{4001 4002}
      #(let [t (presentation/target "brian")]
         (is (= :no-catalog (:status t)))
         (is (str/includes? (:why t) ":catalog-path")))))
  (testing "the newest session of the project whose app answers, framed under the forward"
    (with-world brian two-sessions #{4001 4002}
      #(is (= {:status :session :session "new" :src "/presentation/app/brian/_catalog"}
              (presentation/target "brian"))))
    (with-world brian two-sessions #{4001}
      #(is (= "old" (:session (presentation/target "brian"))) "one that does not answer is passed over")))
  (testing "no app up, or only another project's"
    (with-world brian two-sessions #{4003}
      #(is (= :no-app (:status (presentation/target "brian"))))))
  (testing "no port ever leaves"
    (with-world brian two-sessions #{4002}
      #(is (not-any? (fn [v] (= 4002 v)) (vals (presentation/target "brian")))))))

(defn- echo [req]
  {:status  200
   :headers {"content-type" "text/plain"}
   :body    (pr-str [(:request-method req) (:uri req) (:query-string req)
                     (some-> (:body req) slurp) (get-in req [:headers "content-type"])])})

(deftest the-forward-carries-a-request-as-it-came
  (let [stop (httpkit/run-server echo {:port 0 :legacy-return-value? false})
        port (httpkit/server-port stop)]
    (try
      (with-world brian {"/w" {:project-name "brian" :session-name "s" :app-port port}} #{port}
        (fn []
          (let [ask (fn [r] (update (presentation/forward "brian" r) :body #(read-string (String. ^bytes %))))]
            (is (= [:get "/_catalog" "block=dialog" nil nil]
                   (:body (ask {:method :get :path "_catalog" :query-string "block=dialog"}))))
            (is (= [:post "/_catalog/x/send" nil "{\"a\":1}" "application/json"]
                   (:body (ask {:method :post :path "_catalog/x/send" :body "{\"a\":1}"
                                :content-type "application/json"})))
                "a catalog's controls post, and the post arrives whole"))))
      (finally (httpkit/server-stop! stop)))))

(deftest with-no-app-the-frame-says-why
  (with-world brian {} #{}
    #(let [r (presentation/forward "brian" {:method :get :path "_catalog"})]
       (is (= 404 (:status r)))
       (is (str/includes? (:body r) "No session of brian")))))

(deftest the-dashboard-hands-over-every-request-under-the-prefix
  (let [seen (atom nil)]
    (with-redefs [presentation/forward (fn [p r] (reset! seen [p (dissoc r :body)]) {:status 200 :body "ok"})]
      (testing "a post, which is what Structure's forward never took"
        (server/handle-request {:request-method :post :uri "/presentation/app/brian/_catalog/x"
                                :query-string "q=1" :headers {"content-type" "application/json"}
                                :body (java.io.ByteArrayInputStream. (.getBytes "{}"))})
        (is (= ["brian" {:method :post :path "_catalog/x" :query-string "q=1"
                         :content-type "application/json"}]
               @seen)))
      (testing "the bare prefix gets its slash, so relative requests resolve under it"
        (is (= "/presentation/app/brian/?a=b"
               (get-in (server/handle-request {:request-method :get :uri "/presentation/app/brian"
                                               :query-string "a=b"})
                       [:headers "Location"]))))
      (testing "any other method is refused"
        (is (= 405 (:status (server/handle-request {:request-method :delete
                                                    :uri "/presentation/app/brian/x"}))))))))
