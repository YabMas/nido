(ns nido.notion.client-test
  (:require
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
