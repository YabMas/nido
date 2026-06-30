;; test/nido/review/loop_test.clj
(ns nido.review.loop-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.review.loop :as rloop]))

(defn- stage [name f] {:name name :run f})

(defn- capturing []
  (let [events (atom [])]
    [events (fn [e] (swap! events conj e))]))

(deftest stops-when-judge-says-stop
  (let [calls (atom [])
        [_ emit] (capturing)
        pipe [(stage :review (fn [c] (swap! calls conj :review)
                               (assoc c :findings [{:title "x"}])))
              (stage :judge  (fn [c] (swap! calls conj :judge)
                               (assoc c :control :stop)))
              (stage :fix    (fn [c] (swap! calls conj :fix) c))]
        out (rloop/run-loop {:run-id "r1" :max-iters 5 :pipeline pipe :emit emit})]
    (is (= :converged (:status out)))
    (is (not (some #{:fix} @calls)))))

(deftest escalate-is-terminal
  (let [[_ emit] (capturing)
        pipe [(stage :review (fn [c] (assoc c :findings [{:title "x"}])))
              (stage :judge  (fn [c] (assoc c :control :escalate
                                            :judge {:reason "redesign"})))
              (stage :fix    (fn [c] c))]
        out (rloop/run-loop {:run-id "r1" :max-iters 5 :pipeline pipe :emit emit})]
    (is (= :escalated (:status out)))))

(deftest caps-at-max-iters
  (let [[_ emit] (capturing)
        pipe [(stage :review (fn [c] (assoc c :findings [{:title (str (:iter c))}])))
              (stage :judge  (fn [c] (assoc c :control :continue :judge {:fix-findings nil})))
              (stage :fix    (fn [c] (update c :history (fnil conj []) {:iter (:iter c)})))]
        out (rloop/run-loop {:run-id "r1" :max-iters 3 :pipeline pipe :emit emit})]
    (is (= :max-iters (:status out)))
    (is (= 3 (count (:history out))))))

(deftest stops-on-no-progress
  (let [[_ emit] (capturing)
        pipe [(stage :review (fn [c] (assoc c :findings [{:title "same"}])))
              (stage :judge  (fn [c] (assoc c :control :continue :judge {:fix-findings nil})))
              (stage :fix    (fn [c] (update c :history (fnil conj []) {:iter (:iter c)})))]
        out (rloop/run-loop {:run-id "r1" :max-iters 10 :pipeline pipe :emit emit})]
    (is (= :no-progress (:status out)))))

(deftest review-clean-terminates
  (let [[_ emit] (capturing)
        pipe [(stage :review (fn [c] (assoc c :findings [] :control :stop :status :clean)))
              (stage :judge  (fn [c] c))
              (stage :fix    (fn [c] c))]
        out (rloop/run-loop {:run-id "r1" :max-iters 5 :pipeline pipe :emit emit})]
    (is (= :clean (:status out)))))

(deftest review-failed-is-terminal
  (let [[_ emit] (capturing)
        pipe [(stage :review (fn [_] (throw (ex-info "codex review failed" {:reason :review-failed}))))
              (stage :judge (fn [c] c))
              (stage :fix (fn [c] c))]
        out (rloop/run-loop {:run-id "r1" :max-iters 3 :pipeline pipe :emit emit})]
    (is (= :review-failed (:status out)))))

(deftest emit-narrates-the-lifecycle
  (let [[events emit] (capturing)
        pipe [(stage :review (fn [c] (assoc c :findings [{:title "x"}])))
              (stage :judge  (fn [c] (assoc c :control :stop :judge {:decision :stop})))
              (stage :fix    (fn [c] c))]
        _ (rloop/run-loop {:run-id "r1" :max-iters 5 :pipeline pipe :emit emit
                           :cwd "/w" :base "main"})
        kinds (map :event @events)]
    (is (= :run-started (first kinds)))
    (is (= :run-finalized (last kinds)))
    (is (= [:phase-started :phase-finished]
           (->> @events (filter #(= :review (:phase %))) (map :event))))
    ;; fix never runs on :stop, so no fix phase events
    (is (not-any? #(= :fix (:phase %)) @events))))

(deftest emit-narrates-phase-error
  (let [[events emit] (capturing)
        pipe [(stage :review (fn [_] (throw (ex-info "boom" {:reason :review-failed}))))]
        _ (rloop/run-loop {:run-id "r1" :max-iters 2 :pipeline pipe :emit emit
                           :cwd "/w" :base "main"})]
    (is (some #(= :phase-errored (:event %)) @events))
    (is (= :review-failed (:status (last (filter #(= :run-finalized (:event %)) @events)))))))
