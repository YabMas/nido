(ns nido.session.lifecycle-background-test
  "up! :wait false makes a session addressable and hands its services to a
   background boot; the boot is the ordinary blocking up!."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.process :as proc]
   [nido.session.engine :as engine]
   [nido.session.failure :as failure]
   [nido.session.launcher :as launcher]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.profiles :as profiles]
   [nido.session.state :as state]))

(defn- with-session
  "Call `f` with a worktree that exists, every collaborator a start reaches
   stubbed, and a map of atoms recording what happened: the registry, the boots
   spawned, the starts run and the session homes written. `alive` is the set of
   pids that read as running."
  [{:keys [registry alive start]} f]
  (let [tmp   (fs/create-temp-dir)
        wt    (str (fs/create-dirs (fs/path tmp "feat")))
        rec   {:registry (atom (or registry {}))
               :spawned  (atom [])
               :started  (atom [])
               :homes    (atom [])}]
    (try
      (with-redefs [lifecycle/resolve-project                    (constantly ["brian" {:directory (str tmp)}])
                    lifecycle/worktree-path                      (constantly wt)
                    nido.session.lifecycle/jj-worktree-poisoned? (constantly false)
                    nido.session.lifecycle/spawn-boot!           (fn [name _ _] (swap! (:spawned rec) conj name) 4321)
                    engine/resolve-instance-id                   (constantly "brian--feat")
                    engine/runtime-instance-id                   (constantly "brian--feat")
                    engine/runtime-key-of                        (constantly nil)
                    engine/refuse-start-in-backing-worktree!     (constantly nil)
                    engine/start-session!                        (fn [wt opts]
                                                                   (swap! (:started rec) conj [wt opts])
                                                                   (when start (start)))
                    engine/write-profile-for-session!            (constantly nil)
                    engine/load-session-edn                      (constantly {:services []})
                    launcher/write-artifacts!                    (fn [ctx _ _] (swap! (:homes rec) conj ctx))
                    profiles/resolve-profile                     (constantly {:services :all :worktree {:strategy :jj}})
                    state/read-session                           (constantly nil)
                    state/read-registry                          #(deref (:registry rec))
                    state/upsert-registry!                       (fn [k v] (swap! (:registry rec) assoc k v))
                    state/remove-from-registry!                  (fn [k] (swap! (:registry rec) dissoc k))
                    proc/process-alive?                          #(contains? (or alive #{}) %)
                    failure/record!                              (constantly {:id "F1"})]
        (f wt rec))
      (finally (fs/delete-tree tmp)))))

(deftest wait-false-returns-addressable-without-starting-services
  (with-session {}
    (fn [wt {:keys [registry spawned started homes]}]
      (is (= {:session "feat" :boot-pid 4321
              :log (str (state/log-dir "brian--feat") "/boot-brian--feat.log")}
             (lifecycle/up! "feat" {:project "brian" :wait false}))
          "it returns the BootHandoff of the boot it spawned")
      (is (empty? @started) "no service starts in the calling process")
      (is (= ["feat"] @spawned) "one background boot is spawned")
      (is (= {:project-dir wt :project-name "brian" :instance-id "brian--feat"
              :runtime-key "feat" :session-name "feat" :boot-pid 4321}
             (dissoc (get @registry wt) :created-at))
          "the placeholder names the boot and no port, so cwd resolution finds the session")
      (is (= [{:session {:name "feat" :project-dir wt :project-name "brian"
                         :instance-id "brian--feat"}}]
             @homes)
          "the session home is written from a context with no ports"))))

(deftest wait-false-spawns-no-second-boot
  (testing "while a boot is running"
    (with-session {:alive #{4321}}
      (fn [wt {:keys [registry spawned started]}]
        (swap! registry assoc wt {:instance-id "brian--feat" :boot-pid 4321})
        (lifecycle/up! "feat" {:project "brian" :wait false})
        (is (empty? @spawned))
        (is (empty? @started)))))
  (testing "for a lite profile, which has nothing to boot"
    (with-session {}
      (fn [_ {:keys [spawned started]}]
        (with-redefs [profiles/resolve-profile (constantly {:services [] :worktree {:strategy :jj}})]
          (lifecycle/up! "feat" {:project "brian" :wait false}))
        (is (empty? @spawned))
        (is (= 1 (count @started)) "it is started in place, as it always was"))))
  (testing "for a runtime already up, which the session joins in place"
    (with-session {}
      (fn [_ {:keys [spawned started]}]
        (with-redefs [state/read-session (constantly {:instance-id "brian--feat"
                                                      :context {:app {:port 4000}}})
                      proc/tcp-open?     #(= 4000 %)]
          (lifecycle/up! "feat" {:project "brian" :wait false}))
        (is (empty? @spawned))
        (is (= 1 (count @started))))))
  (testing "but a runtime recorded and no longer up is booted in the background"
    (with-session {}
      (fn [_ {:keys [spawned started]}]
        (with-redefs [state/read-session (constantly {:instance-id "brian--feat"
                                                      :context {:app {:port 4000}}})
                      proc/tcp-open?     (constantly false)]
          (lifecycle/up! "feat" {:project "brian" :wait false}))
        (is (= ["feat"] @spawned))
        (is (empty? @started))))))

(deftest a-failed-boot-removes-its-placeholder
  (with-session {:start #(throw (ex-info "Timed out waiting for .nrepl-port" {}))}
    (fn [wt {:keys [registry]}]
      (swap! registry assoc wt {:instance-id "brian--feat" :boot-pid 4321})
      (is (thrown? Exception (lifecycle/up! "feat" {:project "brian" :wait true})))
      (is (not (contains? @registry wt))
          "the session reads down, not starting, once its boot has failed"))))

(deftest a-failed-start-leaves-a-full-entry-alone
  (with-session {:start #(throw (ex-info "boom" {}))}
    (fn [wt {:keys [registry]}]
      (swap! registry assoc wt {:instance-id "brian--feat" :app-port 4000})
      (is (thrown? Exception (lifecycle/up! "feat" {:project "brian"})))
      (is (contains? @registry wt) "only a placeholder is the failed start's to remove"))))
