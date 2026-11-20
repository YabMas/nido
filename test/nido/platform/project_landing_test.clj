(ns nido.platform.project-landing-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.platform.project :as project]))

(deftest a-projects-landing-is-what-it-declares
  (with-redefs [project/get-project (fn [n] ({"a" {} "b" {:landing :pr-squash} "c" {:landing :push-main}
                                             "d" {:landing "push-main"}} n))]
    (is (= :pr-auto (project/landing :a)) "undeclared lands by :pr-auto")
    (is (= :pr-squash (project/landing :b)))
    (is (= :push-main (project/landing :c)))
    (is (= {:error :undeclared-landing :value "push-main"} (project/landing :d))
        "anything else is refused by name, never read as the default")))
