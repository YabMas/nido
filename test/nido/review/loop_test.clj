;; test/nido/review/loop_test.clj
(ns nido.review.loop-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.review.loop :as rloop]))

(defn- stage [name f] {:name name :run f})

(deftest stops-when-judge-says-stop
  (let [calls (atom [])
        pipe [(stage :review (fn [c] (swap! calls conj :review)
                               (assoc c :findings [{:title "x"}])))
              (stage :judge  (fn [c] (swap! calls conj :judge)
                               (assoc c :control :stop)))
              (stage :fix    (fn [c] (swap! calls conj :fix) c))]
        out (rloop/run-loop {:run-id "r1" :max-iters 5 :pipeline pipe
                             :sink (fn [_])})]
    (is (= :converged (:status out)))
    (is (not (some #{:fix} @calls)))))    ; fix skipped on :stop

(deftest escalate-is-terminal
  (let [pipe [(stage :review (fn [c] (assoc c :findings [{:title "x"}])))
              (stage :judge  (fn [c] (assoc c :control :escalate
                                            :judge {:reason "redesign"})))
              (stage :fix    (fn [c] c))]
        out (rloop/run-loop {:run-id "r1" :max-iters 5 :pipeline pipe :sink (fn [_])})]
    (is (= :escalated (:status out)))))

(deftest caps-at-max-iters
  (let [pipe [(stage :review (fn [c] (assoc c :findings [{:title (str (:iter c))}])))
              (stage :judge  (fn [c] (assoc c :control :continue
                                            :judge {:fix-findings nil})))
              (stage :fix    (fn [c] (update c :history (fnil conj [])
                                             {:iter (:iter c)})))]
        out (rloop/run-loop {:run-id "r1" :max-iters 3 :pipeline pipe :sink (fn [_])})]
    (is (= :max-iters (:status out)))
    (is (= 3 (count (:history out))))))

(deftest stops-on-no-progress
  (let [pipe [(stage :review (fn [c] (assoc c :findings [{:title "same"}])))
              (stage :judge  (fn [c] (assoc c :control :continue
                                            :judge {:fix-findings nil})))
              (stage :fix    (fn [c] (update c :history (fnil conj []) {:iter (:iter c)})))]
        out (rloop/run-loop {:run-id "r1" :max-iters 10 :pipeline pipe :sink (fn [_])})]
    (is (= :no-progress (:status out)))))

(deftest review-clean-terminates
  (let [pipe [(stage :review (fn [c] (assoc c :findings [] :control :stop :status :clean)))
              (stage :judge  (fn [c] c))
              (stage :fix    (fn [c] c))]
        out (rloop/run-loop {:run-id "r1" :max-iters 5 :pipeline pipe :sink (fn [_])})]
    (is (= :clean (:status out)))))

(deftest review-failed-is-terminal
  (let [pipe [(stage :review (fn [_] (throw (ex-info "codex review failed" {:reason :review-failed}))))
              (stage :judge (fn [c] c))
              (stage :fix (fn [c] c))]
        out (rloop/run-loop {:run-id "r1" :max-iters 3 :pipeline pipe :sink (fn [_])})]
    (is (= :review-failed (:status out)))))
