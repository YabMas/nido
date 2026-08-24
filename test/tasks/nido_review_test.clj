(ns tasks.nido-review-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.session :as csession]
   [nido.coordinator.workstream :as ws]
   [nido.review.loop :as rloop]
   [nido.session.lifecycle :as lifecycle]
   [tasks.nido-review :as t]))

(deftest loop-cmd-passes-config-and-defaults
  (let [seen (atom nil)]
    (with-redefs [rloop/run-loop (fn [cfg] (reset! seen cfg) {:status :converged :history []})]
      (t/loop-cmd ":base" "develop" ":max-iters" "3" ":cwd" "/w")
      (is (= "develop" (:base @seen)))
      (is (= 3 (:max-iters @seen)))
      (is (= "/w" (:cwd @seen)))
      (is (string? (:run-id @seen)))
      (is (fn? (:emit @seen)) "engine is given an emit fn")
      (is (fn? (:clock @seen)) "engine is given a clock"))))

(deftest loop-cmd-defaults-base-to-main
  (let [seen (atom nil)]
    (with-redefs [rloop/run-loop (fn [cfg] (reset! seen cfg) {:status :clean :history []})]
      (t/loop-cmd ":cwd" "/w")
      (is (= "main" (:base @seen)))
      (is (nil? (:max-iters @seen)) "uncapped by default — runs as long as it takes"))))

(deftest loop-cmd-resolves-worktree-when-cwd-absent
  (let [seen (atom nil)]
    (with-redefs [lifecycle/worktree-from-cwd (fn [] "/resolved/wt")
                  rloop/run-loop (fn [cfg] (reset! seen cfg) {:status :clean :history []})]
      (t/loop-cmd)
      (is (= "/resolved/wt" (:cwd @seen))))))

(deftest loop-cmd-explicit-cwd-overrides-resolution
  (let [seen (atom nil)]
    (with-redefs [lifecycle/worktree-from-cwd (fn [] "/resolved/wt")
                  rloop/run-loop (fn [cfg] (reset! seen cfg) {:status :clean :history []})]
      (t/loop-cmd ":cwd" "/explicit")
      (is (= "/explicit" (:cwd @seen))))))

(deftest loop-cmd-exit-maps-status
  (with-redefs [rloop/run-loop (fn [_] {:status :clean :history []})]
    (is (zero? (t/exit-code :clean))))
  (is (zero? (t/exit-code :converged)))
  (is (zero? (t/exit-code :escalated)))
  (is (= 1 (t/exit-code :review-failed))))

(deftest review-event-derives-verdict-and-counts
  (let [final  {:status :escalated :findings [{:file "a" :line-start 1 :title "x"}
                                              {:file "b" :line-start 2 :title "y"}]}
        report {:summary {:rounds 3 :findings-fixed 4}
                :target  {:base "main" :base-rev "deadbee"}}
        ev     (t/review-event final report "/runs/r/report.json")]
    (is (= :review-report (:format ev)))
    (is (= :escalated (:status ev)))
    (is (= "main" (:base ev)))
    (is (= "deadbee" (:base-rev ev)))
    (is (= 3 (:rounds ev)))
    (is (= 4 (:findings-fixed ev)))
    (is (= 2 (:findings-remaining ev)))
    (is (= "/runs/r/report.json" (:report-path ev)))))

(deftest review-event-defaults-missing-counts-to-zero
  (let [ev (t/review-event {:status :review-failed}
                           {:target {:base "main" :base-rev nil}}
                           nil)]
    (is (= 0 (:rounds ev)))
    (is (= 0 (:findings-fixed ev)))
    (is (= 0 (:findings-remaining ev)))
    (is (nil? (:base-rev ev)))))

(deftest append-review-entry-writes-when-workstream-resolves
  (let [appended (atom nil)]
    (with-redefs [lifecycle/session-from-cwd (fn [_] {:project "brian" :session "s1"})
                  csession/workstream-id-for (fn [_ _] "ws-1")
                  ws/append-entry! (fn [p id entry content]
                                     (reset! appended {:p p :id id :entry entry :content content})
                                     "/path")]
      (let [ret (t/append-review-entry! "/w"
                                        {:status :converged :findings []}
                                        {:summary {:rounds 1 :findings-fixed 0}
                                         :target {:base "main" :base-rev "abc"}}
                                        "/runs/r/report.json")]
        (is (= "ws-1" ret))
        (is (= :brian (:p @appended)))
        (is (= :review (:kind (:entry @appended))))
        (is (str/includes? (:content @appended) ":review-report"))))))

(deftest append-review-entry-noops-without-workstream
  (let [called (atom false)]
    (with-redefs [lifecycle/session-from-cwd (fn [_] nil)
                  ws/append-entry! (fn [& _] (reset! called true) "/path")]
      (is (nil? (t/append-review-entry! "/w" {:status :clean :findings []}
                                        {:target {:base "main"}} nil)))
      (is (false? @called) "no append when cwd resolves to no session"))))

(deftest append-review-entry-swallows-append-failure
  (with-redefs [lifecycle/session-from-cwd (fn [_] {:project "brian" :session "s1"})
                csession/workstream-id-for (fn [_ _] "ws-1")
                ws/append-entry! (fn [& _] (throw (ex-info "disk boom" {})))]
    (is (nil? (t/append-review-entry! "/w" {:status :converged :findings []}
                                      {:summary {:rounds 1 :findings-fixed 0}
                                       :target {:base "main" :base-rev "abc"}}
                                      "/runs/r/report.json"))
        "a ledger-write failure is swallowed — returns nil, does not throw")))
