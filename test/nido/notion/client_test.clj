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
