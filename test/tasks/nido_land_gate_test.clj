(ns tasks.nido-land-gate-test
  "The landing gate refuses before it pushes anything it cannot show."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nido.coordinator.daemon.supervisor :as sup]
            [nido.coordinator.daemon.restart :as restart]
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

(defn- restart [{:keys [checkout rebase-exit conflicts? holds? owe-fails?]
                 :or {checkout "/root" rebase-exit 0 holds? true}}]
  (let [calls  (atom [])
        owed   (atom nil)
        kicked (atom nil)]
    (with-redefs [land/daemon-checkout (fn [] checkout)
                  jj/jj! (fn [_ & args]
                           (swap! calls conj (vec args))
                           (cond
                             (= "rebase" (first args)) {:exit rebase-exit :out "" :err "boom"}
                             (some #{"(main@origin..@) & conflicts()"} args)
                             {:exit 0 :out (if conflicts? "abc" "") :err ""}
                             :else {:exit 0 :out "op1" :err ""}))
                  layers/resolve-rev (fn [_ _] (when holds? "tip"))
                  restart/owe!  (fn [commit pid]
                                  (reset! owed [commit pid])
                                  (if owe-fails?
                                    {:failed "disk full"}
                                    {:landing {:state :owed :commit commit :owed-at "t" :by-pid pid}}))
                  restart/waits (fn [_] ["the landing that owed it to finish"])
                  sup/restart! (fn [] (reset! kicked :restart) {:ok? true})
                  sup/start!   (fn [] (reset! kicked :start) {:ok? true})
                  sup/stop!    (fn [] (reset! kicked :stop) {:ok? true})]
      {:result (#'land/restart-daemon! "tip") :calls @calls :owed @owed :kicked @kicked})))

(deftest a-landing-rebases-the-checkout-and-owes-the-restart
  (let [{:keys [result calls owed kicked]} (restart {})]
    (is (some #(= ["rebase" "-b" "@" "-d" "main@origin"] %) calls)
        "the daemon loads the root checkout, so it moves onto the landing at once")
    (is (= "tip" (first owed)))
    (is (= (.pid (java.lang.ProcessHandle/current)) (second owed)) "the debt names the landing's own process")
    (is (= {:owed "tip" :waits ["the landing that owed it to finish"]} result))
    (is (nil? kicked) "no landing restarts, starts or signals the daemon")))

(deftest no-launchd-daemon-owes-nothing
  (let [{:keys [result owed calls]} (restart {:checkout nil})]
    (is (:skipped result))
    (is (nil? owed))
    (is (empty? calls))))

(deftest a-conflicting-checkout-rebase-is-undone-and-owes-nothing
  (doseq [opts [{:conflicts? true} {:rebase-exit 1} {:holds? false}]]
    (let [{:keys [result calls owed kicked]} (restart opts)]
      (is (:failed result) (pr-str opts))
      (is (some #(= ["op" "restore" "op1"] %) calls) (pr-str opts))
      (is (nil? owed) (pr-str opts))
      (is (nil? kicked) (pr-str opts)))))

(deftest a-debt-that-cannot-be-written-fails-and-keeps-the-rebase
  (let [{:keys [result calls]} (restart {:owe-fails? true})]
    (is (str/includes? (:failed result) "disk full"))
    (is (:way-out result))
    (is (not-any? #(= ["op" "restore" "op1"] %) calls))))

(deftest the-landing-prints-one-coordinator-line
  (doseq [[outcome code] [[{:owed "abcdef" :waits ["x" "y"]} 0]
                          [{:skipped "not installed" :way-out "w"} 0]
                          [{:failed "boom" :way-out "w"} 1]]]
    (let [ret (atom nil)
          out (with-out-str (reset! ret (#'land/report-restart! outcome)))]
      (is (= code @ret) (pr-str outcome))
      (is (= 1 (count (re-seq #"(?m)^land · coordinator " out))) out))))
