(ns nido.notion.client-test
  (:require
   [cheshire.core]
   [clojure.test :refer [deftest is]]
   [nido.notion.client :as notion]))

(defn- stub-sh [result]
  (fn [_args] result))

(deftest keychain-token-returns-trimmed-secret-on-success
  (with-redefs [notion/sh! (stub-sh {:exit 0 :out "secret_token\n" :err ""})]
    (is (= "secret_token" (notion/keychain-token)))))

(deftest keychain-token-returns-nil-on-non-zero-exit
  (with-redefs [notion/sh! (stub-sh {:exit 44 :out "" :err "not found"})]
    (is (nil? (notion/keychain-token)))))

(deftest keychain-set-shells-security-add-with--U-and--w
  (let [calls (atom [])]
    (with-redefs [notion/sh! (fn [args] (swap! calls conj args)
                                        {:exit 0 :out "" :err ""})]
      (notion/keychain-set! "my-token")
      (let [[args] @calls]
        (is (= "security"             (nth args 0)))
        (is (= "add-generic-password" (nth args 1)))
        (is (some #{"-U"} args))
        (is (some #{"-w"} args))
        ;; -s nido-notion appears as consecutive args
        (is (some (fn [[a b]] (and (= a "-s") (= b "nido-notion")))
                  (partition 2 1 args)))))))

(deftest data-source-query-posts-filter-body
  (let [captured (atom nil)]
    (with-redefs [notion/http-request
                  (fn [_method url opts]
                    (reset! captured {:url url :body (:body opts)})
                    {:status 200 :body "{\"results\":[],\"has_more\":false}"})]
      (notion/data-source-query "ds-1" "token-x"
                                {:filter {:property "Status"
                                          :status {:equals "Needs verification"}}})
      (let [{:keys [url body]} @captured
            decoded (cheshire.core/parse-string body true)]
        (is (re-find #"/v1/data_sources/ds-1/query" url))
        (is (= {:property "Status" :status {:equals "Needs verification"}}
               (:filter decoded)))))))

(deftest data-source-query-no-filter-still-works
  (let [captured (atom nil)]
    (with-redefs [notion/http-request
                  (fn [_method url opts]
                    (reset! captured {:url url :body (:body opts)})
                    {:status 200 :body "{\"results\":[],\"has_more\":false}"})]
      (notion/data-source-query "ds-1" "token-x" {})
      (let [decoded (cheshire.core/parse-string (:body @captured) true)]
        (is (nil? (:filter decoded))
            "no :filter in opts means no filter key in the request body")
        (is (= 100 (:page_size decoded))
            "default page-size is 100")))))

(deftest resolve-data-source-id-extracts-from-database-fetch
  (notion/clear-data-source-cache!)
  (with-redefs [notion/http-request
                (fn [_method url _opts]
                  (cond
                    (re-find #"/v1/databases/db-1" url)
                    {:status 200
                     :body (cheshire.core/generate-string
                             {:id "db-1"
                              :data_sources [{:id "ds-from-db-1" :name "main"}]})}
                    :else {:status 404 :body ""}))]
    (is (= "ds-from-db-1" (notion/resolve-data-source-id "db-1" "token-x")))))

(deftest resolve-data-source-id-caches
  (let [calls (atom 0)]
    (notion/clear-data-source-cache!)
    (with-redefs [notion/http-request
                  (fn [_method _url _opts]
                    (swap! calls inc)
                    {:status 200
                     :body (cheshire.core/generate-string
                             {:id "db-cached" :data_sources [{:id "ds-cached"}]})})]
      (notion/resolve-data-source-id "db-cached" "token-x")
      (notion/resolve-data-source-id "db-cached" "token-x")
      (is (= 1 @calls) "second call should hit the cache, not the API"))))

(def ^:private fixture
  (cheshire.core/parse-string (slurp "test/fixtures/notion/query-response.json") true))

(deftest normalise-page-extracts-required-top-level-fields
  (let [[page] (:results fixture)
        ev    (notion/normalise-page page)]
    (is (= :notion-view (:source ev)))
    (is (= "page-abc-123" (:page-id ev)))
    (is (= "https://notion.so/Login-loops-page-abc-123" (:url ev)))
    (is (= "Login redirect loops on Safari" (:title ev)))
    (is (= "2026-05-15T13:00:00.000Z" (:created-time ev)))
    (is (= "2026-05-15T13:30:00.000Z" (:edited-time ev)))))

(deftest normalise-page-promotes-properties-to-top-level
  (let [[page] (:results fixture)
        ev    (notion/normalise-page page)]
    (is (= "Untriaged" (:status ev)))
    (is (= "P0"        (:priority ev)))
    (is (= "alice"     (:owner ev)))
    (is (= "ABC-123"   (:ticket-id ev)))
    (is (= ["auth" "browser"] (:tags ev)))))

(deftest normalise-page-keeps-properties-map
  (let [[page] (:results fixture)
        ev    (notion/normalise-page page)]
    (is (= "Untriaged" (get-in ev [:properties :status])))
    (is (= "ABC-123"   (get-in ev [:properties :ticket-id])))))

(deftest normalise-page-handles-empty-title
  (let [page {:id "p" :url "u" :created_time "t" :last_edited_time "t"
              :properties {:Name {:type "title" :title []}}}]
    (is (= "" (:title (notion/normalise-page page))))))

(deftest normalise-page-handles-unknown-property-type
  (let [page {:id "p" :url "u" :created_time "t" :last_edited_time "t"
              :properties {:Weird {:type "files" :files [{:name "x"}]}}}]
    ;; Unknown types render as the raw property map so debugging is possible.
    (is (some? (get-in (notion/normalise-page page) [:properties :weird])))))

(deftest retrieve-block-children-returns-results-and-cursor
  (let [captured (atom nil)]
    (with-redefs [notion/http-request
                  (fn [method url opts]
                    (reset! captured {:method method :url url :opts opts})
                    {:status 200
                     :body (cheshire.core/generate-string
                             {:results [{:id "b1" :type "paragraph"}]
                              :has_more true
                              :next_cursor "cur-2"})})]
      (let [r (notion/retrieve-block-children "page-1" "tok" {})]
        (is (= 200 (:status r)))
        (is (= [{:id "b1" :type "paragraph"}] (:results r)))
        (is (true? (:has_more r)))
        (is (= "cur-2" (:next_cursor r)))
        (is (re-find #"/v1/blocks/page-1/children" (:url @captured)))))))

(deftest retrieve-block-children-passes-cursor
  (let [captured (atom nil)]
    (with-redefs [notion/http-request
                  (fn [_method url _opts]
                    (reset! captured url)
                    {:status 200
                     :body (cheshire.core/generate-string
                             {:results [] :has_more false})})]
      (notion/retrieve-block-children "page-1" "tok" {:start-cursor "cur-X"})
      (is (re-find #"start_cursor=cur-X" @captured)))))

(deftest retrieve-block-children-handles-auth
  (with-redefs [notion/http-request (fn [_ _ _] {:status 401 :body ""})]
    (is (= :auth (-> (notion/retrieve-block-children "p" "t" {}) :error)))))

(deftest walk-blocks-paginates-and-recurses-children
  ;; page-1 has [b1 (no children), b2 (has children=true)]; b2 has [b3].
  ;; Two pages of /blocks/page-1/children, then b2's children.
  (let [responses (atom {"page-1?"        [{:status 200
                                            :body (cheshire.core/generate-string
                                                    {:results [{:id "b1" :type "paragraph" :has_children false}]
                                                     :has_more true
                                                     :next_cursor "p1-cur"})}
                                           {:status 200
                                            :body (cheshire.core/generate-string
                                                    {:results [{:id "b2" :type "toggle" :has_children true}]
                                                     :has_more false})}]
                         "b2?"             [{:status 200
                                            :body (cheshire.core/generate-string
                                                    {:results [{:id "b3" :type "paragraph" :has_children false}]
                                                     :has_more false})}]})
        next!     (fn [k]
                    (let [[h & t] (get @responses k)]
                      (swap! responses assoc k (vec t))
                      h))]
    (with-redefs [notion/http-request
                  (fn [_method url _opts]
                    (cond
                      (re-find #"/v1/blocks/page-1/children" url) (next! "page-1?")
                      (re-find #"/v1/blocks/b2/children"     url) (next! "b2?")))]
      (let [ids (->> (notion/walk-blocks "page-1" "tok" {})
                     (map (comp :id :block)))]
        (is (= ["b1" "b2" "b3"] ids))))))

(deftest walk-blocks-respects-max-total
  ;; Two paginated responses of 50 + 100 blocks; max-total=30 caps result at 30.
  (let [responses (atom
                    [{:status 200
                      :body (cheshire.core/generate-string
                              {:results (vec (for [i (range 50)]
                                               {:id (str "b" i)
                                                :type "paragraph"
                                                :has_children false}))
                               :has_more true
                               :next_cursor "more"})}
                     {:status 200
                      :body (cheshire.core/generate-string
                              {:results (vec (for [i (range 100 200)]
                                               {:id (str "b" i)
                                                :type "paragraph"
                                                :has_children false}))
                               :has_more false})}])
        ix        (atom 0)]
    (with-redefs [notion/http-request
                  (fn [_ _ _]
                    (let [r (nth @responses @ix)]
                      (swap! ix inc)
                      r))]
      (let [walked (notion/walk-blocks "p" "t" {:max-total 30})]
        (is (= 30 (count walked)))))))

(deftest walk-blocks-assigns-depths-with-children-incremented
  ;; Same shape as walk-blocks-paginates-and-recurses-children, but verifying :depth.
  (let [responses (atom {"page-1?" [{:status 200
                                     :body (cheshire.core/generate-string
                                             {:results [{:id "b1" :type "paragraph" :has_children false}
                                                        {:id "b2" :type "toggle"    :has_children true}]
                                              :has_more false})}]
                         "b2?"     [{:status 200
                                     :body (cheshire.core/generate-string
                                             {:results [{:id "b3" :type "paragraph" :has_children false}]
                                              :has_more false})}]})
        next!     (fn [k]
                    (let [[h & t] (get @responses k)]
                      (swap! responses assoc k (vec t))
                      h))]
    (with-redefs [notion/http-request
                  (fn [_method url _opts]
                    (cond
                      (re-find #"/v1/blocks/page-1/children" url) (next! "page-1?")
                      (re-find #"/v1/blocks/b2/children"     url) (next! "b2?")))]
      (let [walked (notion/walk-blocks "page-1" "tok" {})]
        (is (= [0 0 1] (mapv :depth walked))
            "root-level blocks are depth 0; b3 (child of b2) is depth 1")))))

(deftest walk-blocks-respects-max-depth
  ;; A 3-deep tree (page → b1 → b1c → b1cc). With :max-depth 1, only depth 0 and 1
  ;; should appear — depth 2 is excluded.
  (let [responses (atom {"page-1?" [{:status 200
                                     :body (cheshire.core/generate-string
                                             {:results [{:id "b1" :type "toggle" :has_children true}]
                                              :has_more false})}]
                         "b1?"     [{:status 200
                                     :body (cheshire.core/generate-string
                                             {:results [{:id "b1c" :type "toggle" :has_children true}]
                                              :has_more false})}]
                         "b1c?"    [{:status 200
                                     :body (cheshire.core/generate-string
                                             {:results [{:id "b1cc" :type "paragraph" :has_children false}]
                                              :has_more false})}]})
        next!     (fn [k]
                    (let [[h & t] (get @responses k)]
                      (swap! responses assoc k (vec t))
                      h))]
    (with-redefs [notion/http-request
                  (fn [_method url _opts]
                    (cond
                      (re-find #"/v1/blocks/page-1/children" url) (next! "page-1?")
                      (re-find #"/v1/blocks/b1/children"     url) (next! "b1?")
                      (re-find #"/v1/blocks/b1c/children"    url) (next! "b1c?")))]
      (let [walked (notion/walk-blocks "page-1" "tok" {:max-depth 1})
            ids    (mapv (comp :id :block) walked)]
        (is (= ["b1" "b1c"] ids)
            "with :max-depth 1, depth-0 (b1) and depth-1 (b1c) included; depth-2 (b1cc) excluded")))))

(deftest extract-value-formats-unique-id
  (is (= "BR-5236"
         (#'notion/extract-value {:type "unique_id"
                                  :unique_id {:prefix "BR" :number 5236}}))
      "unique_id renders as <prefix>-<number>")
  (is (= "42"
         (#'notion/extract-value {:type "unique_id"
                                  :unique_id {:prefix nil :number 42}}))
      "missing prefix renders the number alone"))

(deftest walk-blocks-throws-on-fetch-failure
  ;; A page-level error (e.g. auth) during the walk must throw — not return partial results.
  (with-redefs [notion/http-request (fn [_ _ _] {:status 401 :body ""})]
    (let [ex (try
               (notion/walk-blocks "page-1" "tok" {})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex)
          "walk-blocks must throw, not return")
      (is (= :auth (-> (ex-data ex) :error :error))
          "ex-data carries the underlying retrieve-block-children error")
      (is (= "page-1" (-> (ex-data ex) :block))
          "ex-data identifies which block failed"))))

(deftest create-page!-posts-a-data-source-page-and-reads-back-br
  ;; NB: the captured request body is parsed WITHOUT keywordizing (plain string
  ;; keys throughout) — "Task result" as a keywordized key would be the keyword
  ;; :Task\ result, which isn't valid Clojure reader syntax to write literally.
  ;; String keys keep every assertion below unambiguous.
  (let [captured (atom nil)]
    (with-redefs [notion/http-request
                  (fn [method url opts]
                    (reset! captured {:method method :url url
                                      :body (cheshire.core/parse-string (:body opts) false)})
                    {:status 200
                     :body (cheshire.core/generate-string
                             {:id "pg-new" :url "https://notion.so/pg-new"
                              :properties {"ID" {:type "unique_id"
                                                 :unique_id {:prefix "BR" :number 4900}}
                                           "Task result" {:type "title"
                                                          :title [{:plain_text "Logo bug"}]}}})})]
      (let [r (notion/create-page! "ds-1" "tok"
                                   {:title "Logo bug" :description "grounded findings"
                                    :type "bug" :status "Not started" :priority "2 - Should"})]
        (is (= "BR-4900" (:id r)))
        (is (= "pg-new" (:page-id r)))
        ;; request shape
        (is (= :post (:method @captured)))
        (is (= "https://api.notion.com/v1/pages" (:url @captured)))
        (let [b (:body @captured)]
          (is (= {"type" "data_source_id" "data_source_id" "ds-1"} (get b "parent")))
          (is (= [{"text" {"content" "Logo bug"}}] (get-in b ["properties" "Task result" "title"])))
          (is (= {"name" "Not started"} (get-in b ["properties" "Status" "status"])))
          (is (= {"name" "bug"} (get-in b ["properties" "Type" "select"])))
          (is (= {"name" "2 - Should"} (get-in b ["properties" "Priority" "select"]))))))))

(deftest create-page!-omits-priority-when-nil
  (with-redefs [notion/http-request
                (fn [_ _ opts]
                  (let [b (cheshire.core/parse-string (:body opts) false)]
                    (is (not (contains? (get b "properties") "Priority")))
                    {:status 200 :body (cheshire.core/generate-string
                                         {:id "p" :url "u" :properties {}})}))]
    (notion/create-page! "ds-1" "tok" {:title "t" :description "d" :type "bug"
                                       :status "Not started" :priority nil})))

(deftest create-page!-maps-errors
  (with-redefs [notion/http-request (fn [_ _ _] {:status 401})]
    (is (= {:error :auth} (notion/create-page! "ds-1" "tok" {:title "t" :description "d"
                                                             :type "bug" :status "Not started"})))))

(deftest delete-block-sends-delete-and-maps-status
  (let [captured (atom nil)]
    (with-redefs [notion/http-request
                  (fn [method url _opts]
                    (reset! captured {:method method :url url})
                    {:status 200 :body ""})]
      (is (= {:ok true} (notion/delete-block! "blk-1" "tok")))
      (is (= :delete (:method @captured)))
      (is (re-find #"/v1/blocks/blk-1$" (:url @captured)))))
  (with-redefs [notion/http-request (fn [_ _ _] {:status 401 :body ""})]
    (is (= :auth (:error (notion/delete-block! "b" "t"))))))

(deftest prepend-block-children-patches-with-position-start
  (let [captured (atom nil)]
    (with-redefs [notion/http-request
                  (fn [method url opts]
                    (reset! captured {:method method :url url
                                      :body (cheshire.core/parse-string (:body opts) true)})
                    {:status 200 :body "{}"})]
      (is (= {:ok true}
             (notion/prepend-block-children! "page-1" [{:object "block" :type "callout"}] "tok")))
      (is (= :patch (:method @captured)))
      (is (re-find #"/v1/blocks/page-1/children$" (:url @captured)))
      (is (= {:type "start"} (get-in @captured [:body :position])))
      (is (= 1 (count (get-in @captured [:body :children]))))))
  (with-redefs [notion/http-request (fn [_ _ _] {:status 500 :body ""})]
    (is (= :server (:error (notion/prepend-block-children! "p" [] "t"))))))