(ns nido.coordinator.preprocess-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.preprocess :as pp]
   [nido.coordinator.state :as cstate]))

(defn- with-tmp-runs [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (fs/create-dirs (str (fs/path tmp "runs")))
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(deftest run-noop-when-no-preprocess-config
  (with-tmp-runs
    (fn [_]
      (let [run {:id "r1" :preprocess [] :payload "" :event-payload {}}]
        (is (= {:ok? true} (pp/run! {:run run})))))))

(deftest run-noop-when-preprocess-key-missing
  (with-tmp-runs
    (fn [_]
      (let [run {:id "r1" :payload "" :event-payload {}}]
        (is (= {:ok? true} (pp/run! {:run run})))))))

(deftest run-shells-out-to-notion-preprocessor
  (with-tmp-runs
    (fn [_]
      (let [calls (atom [])
            run   {:id "r1"
                   :preprocess [:notion-ticket]
                   :limits {:preprocess-budget "10m"}
                   :event-payload {:page-id "page-1"}}]
        (with-redefs [pp/shell-bb-task (fn [args]
                                         (swap! calls conj args)
                                         {:exit 0 :out "" :err ""})]
          (is (:ok? (pp/run! {:run run}))))
        (let [[args] @calls]
          (is (= "bb" (first args)))
          (is (= "nido:notion:preprocess-ticket" (second args)))
          (is (some #{"page-1"} args))
          (is (some #(re-find #":budget" %) (map str args)))
          (is (some #(re-find #"600s" %) (map str args))))))))

(deftest run-returns-structured-error-on-preprocessor-failure
  (with-tmp-runs
    (fn [_]
      (let [run {:id "r1"
                 :preprocess [:notion-ticket]
                 :limits {:preprocess-budget "10m"}
                 :event-payload {:page-id "page-1"}}]
        (with-redefs [pp/shell-bb-task (fn [_args]
                                         {:exit 1
                                          :err "{:reason :notion-auth}\n"})]
          (let [r (pp/run! {:run run})]
            (is (not (:ok? r)))
            (is (= :preprocess-failed (-> r :error :reason)))
            (is (= :notion-ticket (-> r :error :preprocessor)))))))))

(deftest run-fails-when-page-id-missing
  (with-tmp-runs
    (fn [_]
      (let [run {:id "r1"
                 :preprocess [:notion-ticket]
                 :event-payload {}}
            r   (pp/run! {:run run})]
        (is (not (:ok? r)))
        (is (= :missing-page-id (-> r :error :reason)))))))

(deftest run-stops-at-first-failure
  (with-tmp-runs
    (fn [_]
      (let [calls (atom [])
            run   {:id "r1"
                   :preprocess [:notion-ticket :nonexistent]
                   :event-payload {:page-id "page-1"}}]
        (with-redefs [pp/shell-bb-task (fn [args]
                                         (swap! calls conj args)
                                         {:exit 1 :err "{:reason :x}\n"})]
          (pp/run! {:run run}))
        (is (= 1 (count @calls))
            "second preprocessor not invoked after first fails")))))

(deftest run-parses-budget-duration
  (with-tmp-runs
    (fn [_]
      (let [calls (atom [])
            run   {:id "r1"
                   :preprocess [:notion-ticket]
                   :limits {:preprocess-budget "15m"}
                   :event-payload {:page-id "page-1"}}]
        (with-redefs [pp/shell-bb-task (fn [args]
                                         (swap! calls conj args)
                                         {:exit 0 :out "" :err ""})]
          (pp/run! {:run run}))
        (is (some #(re-find #"900s" %) (map str (first @calls))))))))

(deftest run-reads-page-id-from-event-payload-not-event
  ;; Regression: the Run schema uses :event-payload (not :event). Verify the
  ;; preprocessor reads from the correct key by setting :event {} (decoy)
  ;; and :event-payload {:page-id ...} (real) — preprocess must use the latter.
  (with-tmp-runs
    (fn [_]
      (let [calls (atom [])
            run   {:id "r1"
                   :preprocess [:notion-ticket]
                   :limits {:preprocess-budget "10m"}
                   :event {:page-id "wrong-decoy"}
                   :event-payload {:page-id "right-real"}}]
        (with-redefs [pp/shell-bb-task (fn [args]
                                         (swap! calls conj args)
                                         {:exit 0 :out "" :err ""})]
          (is (:ok? (pp/run! {:run run}))))
        (let [[args] @calls]
          (is (some #{"right-real"} args))
          (is (not (some #{"wrong-decoy"} args))
              "page-id must be read from :event-payload, not :event"))))))
