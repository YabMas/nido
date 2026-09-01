;; test/nido/review/conformance_test.clj
(ns nido.review.conformance-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.design.check :as design]
   [nido.review.conformance :as conformance]))

(defn- with-status
  "Run `f` with the checker answering `status`.

   `:files` rides on the result rather than being stubbed separately, because that is now the
   contract: the check resolved the config to run at all, so a reading that needs to name the
   declaration is handed it rather than resolving it a second time."
  [status f]
  (with-redefs [design/check (constantly (cond-> status
                                           (not= :unmodelled (:status status))
                                           (assoc :files ["/wt/canvas/bands.clj"])))]
    (f)))

(deftest a-clean-or-unmodelled-project-adds-nothing-to-the-round
  (doseq [s [{:status :satisfied} {:status :unmodelled}]]
    (with-status s #(is (empty? (conformance/findings "p" "/wt"))))))

(deftest one-finding-per-broken-law-not-one-per-offending-row
  (testing "a law broken in forty places is almost always one root cause; forty findings would
            be forty handles, forty rulings and forty fixer turns for it"
    (with-status {:status :violated
                  :violations [{:law "no undeclared edge"
                                :vars ["?from" "?to"]
                                :offenders [["a.b" "c.d"] ["e.f" "g.h"] ["i.j" "k.l"]]}]}
      #(let [fs (conformance/findings "p" "/wt")]
         (is (= 1 (count fs)))
         (is (str/includes? (:title (first fs)) "no undeclared edge"))
         (doseq [row ["from=a.b  to=c.d" "from=e.f  to=g.h" "from=i.j  to=k.l"]]
           (is (str/includes? (:body (first fs)) row) "every row is in the body"))
         (is (str/includes? (:body (first fs)) "canvas/bands.clj")
             "and where the declaration lives, since changing it is a legitimate fix")))))

(deftest a-finding-s-identity-is-the-law-so-a-stuck-loop-can-be-seen
  (testing "the title is the law's own description, so the same broken law is the same finding
            across rounds — which is what lets the loop notice it has stopped shifting it"
    (let [id-of (fn [offenders]
                  (with-status {:status :violated
                                :violations [{:law "no undeclared edge"
                                              :vars ["?from" "?to"]
                                              :offenders offenders}]}
                    #(:id (first (conformance/findings "p" "/wt")))))]
      (is (= (id-of [["a.b" "c.d"]]) (id-of [["a.b" "c.d"] ["e.f" "g.h"]]))
          "a round that fixed one of two offenders has not found a new defect"))))

(deftest a-huge-violation-is-summarised-rather-than-dumped
  (with-status {:status :violated
                :violations [{:law "no undeclared edge"
                              :vars ["?from" "?to"]
                              :offenders (for [i (range 30)] [(str "a" i) "b"])}]}
    #(let [b (:body (first (conformance/findings "p" "/wt")))]
       (is (str/includes? b "and 18 more")
           "enough rows to see the shape, and the count so the fixer knows when it is done"))))

(deftest a-checker-that-would-not-run-is-itself-a-finding
  (testing "reporting nothing would let the round pass on an answer nobody gave"
    (with-status {:status :undecidable :error "a law would not compile"}
      #(let [fs (conformance/findings "p" "/wt")]
         (is (= 1 (count fs)))
         (is (str/includes? (:body (first fs)) "a law would not compile"))
         (is (str/includes? (:body (first fs)) "nobody being able to tell"))))))
