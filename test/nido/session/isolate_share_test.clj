(ns nido.session.isolate-share-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.session.engine :as engine]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.state :as state]))

(deftest effective-pg-mode-prefers-override
  (with-redefs [state/read-pg-mode-override (fn [_] {:mode :isolated})]
    (is (= :isolated (#'lifecycle/effective-pg-mode "brian" "inst")))))

(deftest effective-pg-mode-falls-back-to-project-config
  (with-redefs [state/read-pg-mode-override (fn [_] nil)
                engine/load-session-edn (fn [_] {:services [{:type :postgresql :mode :shared}]})]
    (is (= :shared (#'lifecycle/effective-pg-mode "brian" "inst"))))
  (with-redefs [state/read-pg-mode-override (fn [_] nil)
                engine/load-session-edn (fn [_] {:services [{:type :postgresql :clone-from-template true}]})]
    (is (= :clone (#'lifecycle/effective-pg-mode "brian" "inst")))))

(deftest reset-refuses-on-shared-session
  (with-redefs [nido.session.lifecycle/with-context
                (fn [_ _] {:project-name "brian" :instance-id "i" :wt-path "/tmp/nonexistent-xyz"})
                nido.session.lifecycle/effective-pg-mode
                (fn [_ _] :shared)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"shared"
          (lifecycle/reset! "s" {})))))
