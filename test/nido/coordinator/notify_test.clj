(ns nido.coordinator.notify-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.notify :as notify]
   [nido.notion.client :as notion]))

(def ^:private base-run
  {:id "run-1"
   :skill :plan-bug
   :event-payload {:id "BR-1" :notion-page-id "PAGE1"}
   :on-promote {:notion-status "In progress"}})

(deftest on-plan-spawn-writes-configured-status
  (let [calls (atom [])]
    (with-redefs [notion/keychain-token   (constantly "tok")
                  notion/update-page-status! (fn [pg prop st tok]
                                               (swap! calls conj [pg prop st tok])
                                               {:ok true})]
      (notify/on-plan-spawn! base-run)
      (is (= [["PAGE1" "Status" "In progress" "tok"]] @calls)
          "defaults property name to \"Status\""))))

(deftest on-plan-spawn-honours-explicit-property
  (let [calls (atom [])]
    (with-redefs [notion/keychain-token (constantly "tok")
                  notion/update-page-status! (fn [pg prop st _] (swap! calls conj [pg prop st]) {:ok true})]
      (notify/on-plan-spawn! (assoc base-run :on-promote {:notion-status "In progress" :property "State"}))
      (is (= [["PAGE1" "State" "In progress"]] @calls)))))

(deftest on-plan-spawn-is-noop-without-config-or-page
  (let [calls (atom 0)]
    (with-redefs [notion/keychain-token (constantly "tok")
                  notion/update-page-status! (fn [& _] (swap! calls inc) {:ok true})]
      (notify/on-plan-spawn! (dissoc base-run :on-promote))                 ; no config
      (notify/on-plan-spawn! (assoc base-run :event-payload {:id "BR-1"}))  ; no page-id
      (is (= 0 @calls)))))

(deftest on-plan-spawn-swallows-errors
  (with-redefs [notion/keychain-token (constantly "tok")
                notion/update-page-status! (fn [& _] (throw (ex-info "boom" {})))]
    (is (nil? (notify/on-plan-spawn! base-run)) "throwing client must not propagate"))
  (with-redefs [notion/keychain-token (constantly nil)
                notion/update-page-status! (fn [& _] (throw (ex-info "should-not-call" {})))]
    (is (nil? (notify/on-plan-spawn! base-run)) "no token ⇒ skip, no throw")))
