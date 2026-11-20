(ns nido.coordinator.lane.landing-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.lane.landing :as sut]
   [nido.github.client :as github]
   [nido.platform.project :as project]))

(defn- with-landing [l f]
  (with-redefs [project/get-project (fn [_] (cond-> {:directory "/x"} (some? l) (assoc :landing l)))]
    (f)))

(deftest a-project-lands-by-the-procedure-it-declares
  (let [calls (atom [])]
    (with-redefs [github/land-pr!   (fn [r n] (swap! calls conj [:auto r n]) {:status :ok :via :auto})
                  github/squash-pr! (fn [r n] (swap! calls conj [:squash r n]) {:status :ok :via :squash})]
      (with-landing nil
        #(is (= {:status :ok :via :auto :procedure :pr-auto :pr "o/r#4"} (sut/land! :p "ws" {:pr "o/r#4"}))
             "undeclared is :pr-auto"))
      (with-landing :pr-squash
        #(is (= :squash (:via (sut/land! :p "ws" {:pr "o/r#5"})))))
      (is (= [[:auto "o/r" 4] [:squash "o/r" 5]] @calls) "each runs its own merge and nothing else"))))

(deftest a-landing-that-cannot-run-is-refused-before-anything-runs
  (with-redefs [github/land-pr!   (fn [& _] (throw (ex-info "ran" {})))
                github/squash-pr! (fn [& _] (throw (ex-info "ran" {})))]
    (with-landing :pr-squash
      #(is (= :refused (:error (sut/land! :p "ws" {}))) "a PR procedure needs the PR"))
    (with-landing :pr-merge-please
      (fn []
        (is (= :refused (:error (sut/land! :p "ws" {:pr "o/r#1"}))) "an undeclared value is refused by name")
        (is (map? (sut/names-pr? :p)) "and is read as neither answer")))
    (with-landing :push-main
      (fn []
        (is (false? (sut/names-pr? :p)))
        (is (= :refused (:error (sut/land! :p "ws" {}))) "a push needs the worktree to run in")))))
