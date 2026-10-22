(ns nido.review.replay-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.review.replay :as replay]))

(defn- round [n judge & [amend?]]
  {:round n :phases (cond-> [(assoc judge :phase "judge")] amend? (conj {:phase "amend"}))})

(deftest a-recorded-circle-would-now-end-stalled
  ;; The shape of design-loop-7d596499, shortened: one judge leaves two subjects unruled, the
  ;; other confirms every subject once, over one unchanged entry.
  (let [rounds (vec (for [i (range 1 13)]
                      (round i (if (odd? i)
                                 {:judged-seq 337 :findings [] :unruled ["a" "b"]}
                                 {:judged-seq 337 :findings [] :read-once ["a" "b" "c"]}))))
        r (replay/replay {:run-id "design-loop-x" :status "asked" :rounds rounds})]
    (is (replay/diverged? r))
    (is (= {:status :stalled :rounds 6} (select-keys (:replayed r) [:status :rounds])))
    (is (= {:status :asked :rounds 12} (:recorded r)))))

(deftest a-run-that-kept-moving-replays-to-its-recorded-end
  (let [rounds [(round 1 {:judged-seq 1 :findings [{:title "f"}]} true)
                (round 2 {:judged-seq 3 :findings [] :read-once ["a" "b"]})
                (round 3 {:judged-seq 3 :findings [] :read-once ["b"]})
                (round 4 {:judged-seq 3 :findings []})]
        r (replay/replay {:run-id "baseline-loop-y" :status "sufficient" :rounds rounds})]
    (is (not (replay/diverged? r)))
    (is (= :sufficient (get-in r [:replayed :status])))))
