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

(defn- stub-http [result]
  (fn [_method _url _opts] result))

(deftest database-query-builds-the-right-request
  (let [calls (atom [])]
    (with-redefs [notion/http-request
                  (fn [method url opts]
                    (swap! calls conj {:method method :url url :opts opts})
                    {:status 200 :body "{\"results\":[],\"has_more\":false}"})]
      (notion/database-query "abc-123" "the-token")
      (let [[{:keys [method url opts]}] @calls]
        (is (= :post method))
        (is (= "https://api.notion.com/v1/databases/abc-123/query" url))
        (is (= "Bearer the-token" (get-in opts [:headers "Authorization"])))
        (is (= "2022-06-28"       (get-in opts [:headers "Notion-Version"])))
        (is (= "application/json" (get-in opts [:headers "Content-Type"])))
        (is (= "{\"page_size\":100}" (:body opts)))
        (is (= 10000 (:timeout opts)))))))

(deftest database-query-returns-parsed-result-on-200
  (with-redefs [notion/http-request
                (stub-http {:status 200
                            :body "{\"results\":[{\"id\":\"p1\"}],\"has_more\":false}"})]
    (let [r (notion/database-query "x" "t")]
      (is (= 200 (:status r)))
      (is (= [{:id "p1"}] (:results r)))
      (is (false? (:has_more r))))))

(deftest database-query-marks-401-as-auth-error
  (with-redefs [notion/http-request
                (stub-http {:status 401 :body "{\"message\":\"Invalid token\"}"})]
    (let [r (notion/database-query "x" "bad")]
      (is (= 401 (:status r)))
      (is (= :auth (:error r))))))

(deftest database-query-marks-5xx-as-server-error
  (with-redefs [notion/http-request
                (stub-http {:status 503 :body "service unavailable"})]
    (is (= :server (:error (notion/database-query "x" "t"))))))

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
