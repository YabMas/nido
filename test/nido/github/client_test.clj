(ns nido.github.client-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.github.client :as gh]))

(deftest list-merged-parses-json
  (with-redefs [gh/sh! (fn [args]
                         (is (= ["gh" "pr" "list" "--repo" "brian-study/brian"
                                 "--state" "merged" "--json" "number,url,title,mergedAt"
                                 "--limit" "50"] args))
                         {:exit 0
                          :out "[{\"number\":412,\"url\":\"https://github.com/brian-study/brian/pull/412\",\"title\":\"Fix X\",\"mergedAt\":\"2026-06-12T10:00:00Z\"}]"})]
    (let [res (gh/list-merged-prs "brian-study/brian")]
      (is (= :ok (:status res)))
      (is (= [{:number    412
               :url       "https://github.com/brian-study/brian/pull/412"
               :title     "Fix X"
               :merged-at "2026-06-12T10:00:00Z"}]
             (:prs res))))))

(deftest list-merged-maps-auth-error
  (with-redefs [gh/sh! (fn [_] {:exit 1 :out "" :err "gh: To get started with GitHub CLI, please run: gh auth login"})]
    (is (= :auth (:error (gh/list-merged-prs "o/r"))))))

(deftest list-merged-maps-generic-error
  (with-redefs [gh/sh! (fn [_] {:exit 1 :out "" :err "could not resolve to a Repository"})]
    (is (= :gh (:error (gh/list-merged-prs "o/r"))))))
