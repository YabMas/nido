(ns nido.shared-pg-test
  (:require
   [babashka.fs :as fs]
   [babashka.process]
   [clojure.test :refer [deftest is]]
   [nido.shared-pg :as shared]))

(deftest with-lock-serializes-and-returns-body-value
  (let [lock (str (fs/path (fs/create-temp-dir) "t.lock"))]
    (is (= :ok (shared/with-lock lock (fn [] :ok))))
    ;; lock file is released (deletable) after the body returns
    (is (= :again (shared/with-lock lock (fn [] :again))))))

(deftest resolve-shared-port-is-deterministic-per-project
  (is (= (shared/resolve-shared-port "brian")
         (shared/resolve-shared-port "brian")))
  (is (<= 5500 (shared/resolve-shared-port "brian") 7499)))

(deftest ensure-up-requires-an-initialized-template
  ;; With no template for a bogus project, ensure-up! must fail clearly rather
  ;; than initdb a blank cluster.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"[Tt]emplate"
       (shared/ensure-up! "definitely-not-a-real-project-xyz"))))
