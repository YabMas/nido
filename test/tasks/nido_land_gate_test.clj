(ns tasks.nido-land-gate-test
  "The landing gate refuses before it pushes anything it cannot show."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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
