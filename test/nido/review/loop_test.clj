;; test/nido/review/loop_test.clj
(ns nido.review.loop-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.review.loop :as rloop]))

(defn- stage [name f] {:name name :run f})

(defn- capturing []
  (let [events (atom [])]
    [events (fn [e] (swap! events conj e))]))

(deftest stops-when-warden-says-stop
  (let [calls (atom [])
        [_ emit] (capturing)
        pipe [(stage :review (fn [c] (swap! calls conj :review)
                               (assoc c :findings [{:title "x"}])))
              (stage :warden  (fn [c] (swap! calls conj :warden)
                               (assoc c :control :stop)))
              (stage :fix    (fn [c] (swap! calls conj :fix) c))]
        out (rloop/run-loop {:run-id "r1" :max-iters 5 :pipeline pipe :emit emit})]
    (is (= :converged (:status out)))
    (is (not (some #{:fix} @calls)))))

(deftest escalate-is-terminal
  (let [[_ emit] (capturing)
        pipe [(stage :review (fn [c] (assoc c :findings [{:title "x"}])))
              (stage :warden  (fn [c] (assoc c :control :escalate
                                            :warden {:reason "redesign"})))
              (stage :fix    (fn [c] c))]
        out (rloop/run-loop {:run-id "r1" :max-iters 5 :pipeline pipe :emit emit})]
    (is (= :escalated (:status out)))))

(deftest caps-at-max-iters
  (let [[_ emit] (capturing)
        pipe [(stage :review (fn [c] (assoc c :findings [{:title (str (:iter c))}])))
              (stage :warden  (fn [c] (assoc c :control :continue :warden {:fix-findings nil})))
              (stage :fix    (fn [c] (update c :history (fnil conj []) {:iter (:iter c)})))]
        out (rloop/run-loop {:run-id "r1" :max-iters 3 :pipeline pipe :emit emit})]
    (is (= :max-iters (:status out)))
    (is (= 3 (count (:history out))))))

(deftest uncapped-when-max-iters-absent
  ;; No :max-iters => no iteration ceiling at all. Each round yields a fresh
  ;; finding, so `no-progress?` never fires and the run only ends when the
  ;; pipeline itself says stop — well past the 5 rounds that used to be the
  ;; hardcoded default.
  (let [[_ emit] (capturing)
        pipe [(stage :review (fn [c] (assoc c :findings [{:title (str (:iter c))}])))
              (stage :warden (fn [c] (cond-> (assoc c :warden {:fix-findings nil})
                                        (>= (:iter c) 9) (assoc :control :stop)
                                        (< (:iter c) 9)  (assoc :control :continue))))
              (stage :fix (fn [c] (update c :history (fnil conj []) {:iter (:iter c)})))]
        out (rloop/run-loop {:run-id "r1" :pipeline pipe :emit emit})]
    (is (= :converged (:status out)))
    (is (= 9 (:iter out)))))

(deftest stops-on-no-progress
  (let [[_ emit] (capturing)
        pipe [(stage :review (fn [c] (assoc c :findings [{:title "same"}])))
              (stage :warden  (fn [c] (assoc c :control :continue :warden {:fix-findings nil})))
              (stage :fix    (fn [c] (update c :history (fnil conj []) {:iter (:iter c)})))]
        out (rloop/run-loop {:run-id "r1" :max-iters 10 :pipeline pipe :emit emit})]
    (is (= :no-progress (:status out)))))

(deftest review-clean-terminates
  (let [[_ emit] (capturing)
        pipe [(stage :review (fn [c] (assoc c :findings [] :control :stop :status :clean)))
              (stage :warden  (fn [c] c))
              (stage :fix    (fn [c] c))]
        out (rloop/run-loop {:run-id "r1" :max-iters 5 :pipeline pipe :emit emit})]
    (is (= :clean (:status out)))))

(deftest review-failed-is-terminal
  (let [[_ emit] (capturing)
        pipe [(stage :review (fn [_] (throw (ex-info "codex review failed" {:reason :review-failed}))))
              (stage :warden (fn [c] c))
              (stage :fix (fn [c] c))]
        out (rloop/run-loop {:run-id "r1" :max-iters 3 :pipeline pipe :emit emit})]
    (is (= :review-failed (:status out)))))

(deftest emit-narrates-the-lifecycle
  (let [[events emit] (capturing)
        pipe [(stage :review (fn [c] (assoc c :findings [{:title "x"}])))
              (stage :warden  (fn [c] (assoc c :control :stop :warden {:decision :stop})))
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
