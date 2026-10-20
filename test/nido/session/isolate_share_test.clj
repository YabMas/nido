(ns nido.session.isolate-share-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.platform.lock :as lock]
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

(deftest a-stopped-session-cannot-reset-the-runtime-its-workstream-is-on
  ;; b is down; a keeps their workstream's runtime up. A reset, isolate or share of b aimed at
  ;; that runtime would drop or re-mode the database a is running on, so each is refused.
  (let [tmp     (fs/create-temp-dir)
        touched (atom [])]
    (try
      (with-redefs [lock/locks-dir                         (constantly (fs/path tmp "locks"))
                    nido.session.lifecycle/resolve-project (constantly ["p" {}])
                    nido.session.lifecycle/with-context
                    (fn [_ _] {:project-name "p" :instance-id "p--ws-1" :on nil :runtime-key "ws-1"
                               :wt-path (str tmp)})
                    nido.session.lifecycle/effective-pg-mode (fn [_ _] :isolated)
                    engine/sessions-on                     (fn [rt] (if (= "p--ws-1" rt) ["a"] []))
                    engine/read-profile-for-session        (constantly nil)
                    engine/stop-session!                   (fn [& _] (swap! touched conj :stop))
                    engine/start-session!                  (fn [& _] (swap! touched conj :start))
                    state/write-pg-mode-override!          (fn [& _] (swap! touched conj :override))
                    state/clear-pg-mode-override!          (fn [& _] (swap! touched conj :override))
                    state/pg-data-dir                      (constantly (str (fs/path tmp "pg-data")))]
        (doseq [verb [lifecycle/reset! lifecycle/isolate! lifecycle/share!]]
          (is (= :runtime-shared
                 (try (verb "b" {:project "p" :runtime-key "ws-1"}) nil
                      (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))))))
      (is (empty? @touched) "nothing is stopped, re-moded or started")
      (finally (fs/delete-tree tmp)))))

(deftest destroying-the-worktree-a-shared-runtime-runs-out-of-is-refused
  (let [tmp     (fs/create-temp-dir)
        touched (atom [])]
    (try
      (with-redefs [lock/locks-dir                      (constantly (fs/path tmp "locks"))
                    nido.session.lifecycle/resolve-project (constantly ["p" {}])
                    nido.session.lifecycle/with-context
                    (fn [_ _] {:project-name "p" :instance-id "p--ws-1" :on "p--ws-1" :wt-path (str tmp)})
                    engine/sessions-backed-by           (fn [_ _] ["b"])
                    engine/stop-session!                (fn [& _] (swap! touched conj :stop))]
        (is (= :runtime-backed
               (try (lifecycle/destroy! "a" {:project "p"}) nil
                    (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))))
        (is (= [:stop] @touched)
            "a is taken off the runtime — membership, registry entry and home — before the refusal,
             so a caller that goes on to archive it leaves no record of it on the runtime")
        (is (fs/exists? tmp) "the worktree is left in place"))
      (finally (fs/delete-tree tmp)))))

(deftest a-restart-the-backing-worktree-refuses-stops-nothing
  ;; a started the runtime b is on; restarting a onto another runtime would start that one in
  ;; the worktree the first runs out of. The refusal comes before the stop, so a stays up.
  (let [tmp     (fs/create-temp-dir)
        touched (atom [])]
    (try
      (with-redefs [lock/locks-dir                         (constantly (fs/path tmp "locks"))
                    nido.session.lifecycle/resolve-project (constantly ["p" {}])
                    nido.session.lifecycle/with-context
                    (fn [_ _] {:project-name "p" :instance-id "p--ws-2" :on "p--ws-1"
                               :runtime-key "ws-2" :wt-path (str tmp)})
                    state/read-session                     (constantly nil)
                    engine/sessions-backed-by              (fn [_ _] ["b"])
                    engine/read-profile-for-session        (constantly nil)
                    engine/stop-session!                   (fn [& _] (swap! touched conj :stop))
                    engine/start-session!                  (fn [& _] (swap! touched conj :start))]
        (is (= :runtime-backed
               (try (lifecycle/restart! "a" {:project "p" :runtime-key "ws-2"}) nil
                    (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))))
        (is (empty? @touched) "a is neither stopped nor started"))
      (finally (fs/delete-tree tmp)))))
