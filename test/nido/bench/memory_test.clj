(ns nido.bench.memory-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.bench.memory :as bench]
   [nido.platform.lock :as lock]
   [nido.session.engine :as engine]
   [nido.session.lifecycle :as lifecycle]))

(deftest a-boot-is-not-measured-on-a-runtime-another-session-is-on
  ;; With a still on the workstream's runtime, b's down/up would only leave and rejoin a's JVM:
  ;; the lever is refused before any deps file is patched or any session moved.
  (let [tmp     (fs/create-temp-dir)
        touched (atom [])]
    (try
      (with-redefs [lock/locks-dir                         (constantly (fs/path tmp "locks"))
                    nido.session.lifecycle/resolve-project (constantly ["p" {}])
                    nido.session.lifecycle/with-context
                    (fn [_ _] {:project-name "p" :instance-id "p--ws-1" :on "p--ws-1"
                               :runtime-key "ws-1" :wt-path (str tmp)})
                    engine/sessions-on                     (fn [rt] (if (= "p--ws-1" rt) ["a" "b"] []))
                    engine/sessions-backed-by              (fn [_ _] [])
                    bench/backup-deps!                     (fn [& _] (swap! touched conj :patch))
                    lifecycle/down!                        (fn [& _] (swap! touched conj :down))
                    lifecycle/up!                          (fn [& _] (swap! touched conj :up))
                    bench/wait-for-repl-pid                (constantly nil)]
        (is (= :runtime-shared
               (try (#'bench/run-boot-lever! {:project-name "p" :session-name "b"
                                               :worktree-dir (str tmp) :runtime-key "ws-1"}
                                              {:name :baseline})
                    nil
                    (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))))
        (is (empty? @touched)))
      (finally (fs/delete-tree tmp)))))
