(ns tasks.nido-land-gate-test
  "The landing gate refuses before it pushes anything it cannot show."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nido.coordinator.daemon.launchctl :as lc]
            [nido.review.layers :as layers]
            [nido.session.lifecycle :as lifecycle]
            [nido.vsdd.jj :as jj]
            [tasks.nido-land :as land]))

(defn- gate [{:keys [description on-main? conflicts?]}]
  (let [calls (atom [])
        pushed (atom nil)]
    (with-redefs [jj/jj! (fn [_ & args]
                           (swap! calls conj (vec args))
                           (cond
                             (= "description" (last args)) {:exit 0 :out description :err ""}
                             (some #{"(main@origin..@) & conflicts()"} args)
                             {:exit 0 :out (if conflicts? "abc" "") :err ""}
                             (= ["resolve" "--list"] (take 2 args)) {:exit 0 :out "src/x.clj" :err ""}
                             :else {:exit 0 :out "op1" :err ""}))
                  layers/resolve-rev (fn [_ rev] (if (str/includes? rev "::main@origin") (when on-main? "tip") "tip"))
                  lifecycle/advance-remote! (fn [& args] (reset! pushed args) {:outcome :advanced})]
      (let [result (atom nil)
            out    (with-out-str (reset! result (land/gate! "/wt")))]
        {:result @result :pushed @pushed :calls @calls :out out}))))

(deftest nothing-to-land-is-refused
  (let [{:keys [result pushed]} (gate {:description "x" :on-main? true})]
    (is (str/includes? (:refused result) "nothing to land"))
    (is (nil? pushed))))

(deftest an-undescribed-change-is-refused
  (let [{:keys [result pushed]} (gate {:description ""})]
    (is (str/includes? (:refused result) "no description"))
    (is (nil? pushed))))

(deftest a-conflicting-rebase-is-undone-and-refused
  (let [{:keys [result pushed calls]} (gate {:description "x" :conflicts? true})]
    (is (str/includes? (:refused result) "conflicts: src/x.clj"))
    (is (some #(= ["op" "restore" "op1"] %) calls) "the worktree goes back to before the rebase")
    (is (nil? pushed))))

(defn- restart [{:keys [checkout live rebase-exit conflicts? holds? loaded? inside?]
                 :or {checkout "/root" rebase-exit 0 holds? true loaded? true}}]
  (let [calls (atom [])
        kicked (atom nil)]
    (with-redefs [land/daemon-checkout (fn [] checkout)
                  land/live-run-ids    (fn [] (vec live))
                  land/daemon-ancestor? (fn [] inside?)
                  jj/jj! (fn [_ & args]
                           (swap! calls conj (vec args))
                           (cond
                             (= "rebase" (first args)) {:exit rebase-exit :out "" :err "boom"}
                             (some #{"(main@origin..@) & conflicts()"} args)
                             {:exit 0 :out (if conflicts? "abc" "") :err ""}
                             :else {:exit 0 :out "op1" :err ""}))
                  layers/resolve-rev (fn [_ _] (when holds? "tip"))
                  lc/loaded?    (fn [] loaded?)
                  lc/kickstart! (fn [] (reset! kicked :kickstart) {:exit 0})
                  lc/bootstrap! (fn [] (reset! kicked :bootstrap) {:exit 0})]
      {:result (#'land/restart-daemon! "tip") :calls @calls :kicked @kicked})))

(deftest a-landing-restarts-the-daemon-after-rebasing-its-checkout
  (let [{:keys [result calls kicked]} (restart {})]
    (is (:restarted result))
    (is (= :kickstart kicked))
    (is (some #(= ["rebase" "-b" "@" "-d" "main@origin"] %) calls)
        "the daemon loads the root checkout, so it moves onto the landing first")))

(deftest a-live-run-defers-the-restart-rather-than-being-killed
  (let [{:keys [result calls kicked]} (restart {:live ["run-1"]})]
    (is (str/includes? (:skipped result) "run-1"))
    (is (empty? calls) "nothing is rebased either")
    (is (nil? kicked))))

(deftest a-landing-the-daemon-runs-does-not-restart-it
  (let [{:keys [result calls kicked]} (restart {:inside? true})]
    (is (str/includes? (:skipped result) "inside the daemon"))
    (is (empty? calls))
    (is (nil? kicked))))

(deftest no-launchd-daemon-is-skipped
  (let [{:keys [result kicked]} (restart {:checkout nil})]
    (is (:skipped result))
    (is (nil? kicked))))

(deftest a-conflicting-checkout-rebase-is-undone-and-restarts-nothing
  (doseq [opts [{:conflicts? true} {:rebase-exit 1} {:holds? false}]]
    (let [{:keys [result calls kicked]} (restart opts)]
      (is (:failed result) (pr-str opts))
      (is (some #(= ["op" "restore" "op1"] %) calls) (pr-str opts))
      (is (nil? kicked) (pr-str opts)))))

(deftest an-unloaded-daemon-is-bootstrapped
  (is (= :bootstrap (:kicked (restart {:loaded? false})))))
