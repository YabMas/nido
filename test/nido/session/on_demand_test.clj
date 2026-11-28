(ns nido.session.on-demand-test
  "What an on-demand JVM changes outside its front: the ports a runtime start allocates, the app
   it does not start, and what the agent is told."
  (:require
   [babashka.fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.session.engine :as engine]
   [nido.session.launcher :as launcher]
   [nido.platform.io]
   [nido.platform.process]
   [nido.session.links :as links]
   [nido.session.state :as state]))

(def ^:private services
  [{:type :config-file :name :local-edn}
   {:type :process :name :repl :command "clojure" :on-demand {:idle-ms 1800000}}
   {:type :eval :name :app :repl-service :repl :port-range [3100 5100]}])

(deftest an-on-demand-runtime-allocates-the-fronts-ports-before-anything-starts
  (let [ctx (#'engine/pre-allocate-ports services "/tmp/wt-on-demand")
        {:keys [port bind-port]} (:app ctx)]
    (is (pos-int? (get-in ctx [:repl :port])) "the front's nREPL port is known before the JVM runs")
    (is (and (pos-int? port) (pos-int? bind-port) (not= port bind-port))
        "the app binds a port of its own behind the session's app port")
    (is (= [{:service :app :port port :bind-port bind-port}] (get-in ctx [:repl :front-apps]))
        "the front is told which app port it holds and where the app listens")))

(deftest no-port-is-handed-out-twice-in-one-allocation
  (with-redefs [nido.platform.process/deterministic-port (fn [_ low _] low)
                nido.platform.process/port-free?         (constantly true)]
    (let [svcs [{:type :process :name :repl :command "clojure" :on-demand {:idle-ms 1 :port-range [4000 4001]}}
                {:type :eval :name :app :repl-service :repl :port-range [4000 4001]}]
          ctx  (#'engine/pre-allocate-ports svcs "/tmp/wt-collide")
          ports [(get-in ctx [:repl :port]) (get-in ctx [:app :port]) (get-in ctx [:app :bind-port])]]
      (is (= 3 (count (distinct ports))) (str "the front's ports and the app's bound port differ: " ports)))))

(deftest a-port-picked-after-the-allocation-avoids-the-fronts
  (with-redefs [nido.platform.process/deterministic-port (fn [_ low _] low)
                nido.platform.process/port-free?         (constantly true)]
    (binding [nido.platform.process/*picked-ports* (atom #{})]
      (let [svcs [{:type :process :name :repl :command "clojure" :on-demand {:idle-ms 1 :port-range [5500 5501]}}]
            ctx  (#'engine/pre-allocate-ports svcs "/tmp/wt-pg")
            ;; What Postgres picks at its own start, from a range beginning at the same port.
            pg   (nido.platform.process/find-available-port 5500 1)]
        (is (= 5500 (get-in ctx [:repl :port])))
        (is (not= 5500 pg) "a later pick in the same start does not take the front's unbound port")))))

(defn- ledger-of [home] (or (nido.platform.io/read-edn (str home "/state/ports.edn")) {}))

(deftest overlapping-starts-never-pick-the-same-port
  (let [home (str (babashka.fs/create-temp-dir))]
    (with-redefs [nido.platform.core/nido-home       (constantly home)
                  nido.platform.process/port-free?   (constantly true)]
      ;; Eight runtimes starting at once, each its own start, all preferring one port; nothing binds
      ;; any of them, so only what the starts publish keeps them apart.
      (let [go     (promise)
            starts (doall (for [i (range 8)]
                            (future
                              (binding [nido.platform.process/*picked-ports* (atom #{})
                                        nido.platform.process/*port-owner*   (str "rt-" i)]
                                @go
                                (nido.platform.process/find-available-port 4000 50)))))
            _      (deliver go true)
            ports  (mapv deref starts)]
        (is (= 8 (count (distinct ports))) (str "every start picked its own port: " ports))
        (is (= (set ports) (set (keys (ledger-of home)))) "each pick is published as it is made")
        (testing "a runtime's own ports are not held against it"
          (binding [nido.platform.process/*picked-ports* (atom #{})
                    nido.platform.process/*port-owner*   "rt-0"]
            (is (= (nth ports 0) (nido.platform.process/find-available-port (nth ports 0) 1)))))))))

(deftest a-failed-start-gives-its-ports-back
  (let [home (str (babashka.fs/create-temp-dir)) during (atom nil)]
    (with-redefs [nido.platform.core/nido-home       (constantly home)
                  nido.platform.process/port-free?   (constantly true)]
      (with-redefs-fn {#'engine/runtime-service-defs (fn [& _] [])
                       #'engine/code-members         (fn [& _] {})
                       #'engine/admission            (fn [& _] nil)
                       #'engine/runtime-dir          (fn [& _] "/tmp/wt-fail")
                       #'engine/start-each!          (fn [& _]
                                                       (nido.platform.process/find-available-port 4500 5)
                                                       (reset! during (ledger-of home))
                                                       (throw (ex-info "service failed" {})))}
        (fn []
          (binding [nido.platform.process/*picked-ports* (atom #{})
                    nido.platform.process/*port-owner*   "rt-other"]
            (nido.platform.process/find-available-port 4500 5))
          (is (thrown-with-msg? Exception #"service failed"
                (engine/start-services! "/tmp/wt-fail" "p" "rt-fail" {} {} {} "/tmp/wt-fail")))
          (is (= #{"rt-fail" "rt-other"} (set (vals @during))) "its pick was published while it ran")
          (is (= {4500 "rt-other"} (ledger-of home))
              "the port its service picked is free for the next start; another runtime's stays"))))))

(deftest a-stopped-runtime-gives-its-ports-back
  (let [home (str (babashka.fs/create-temp-dir))]
    (with-redefs [nido.platform.core/nido-home              (constantly home)
                  nido.platform.process/port-free?          (constantly true)
                  nido.session.shared-pg/release-claims-of! (fn [_] nil)]
      (with-redefs-fn {#'engine/read-session-edn (fn [_] {:services []})}
        (fn []
          (doseq [id ["rt-a" "rt-b"]]
            (binding [nido.platform.process/*picked-ports* (atom #{})
                      nido.platform.process/*port-owner*   id]
              (nido.platform.process/find-available-port 4600 5)))
          (#'engine/stop-services! {:project-name "p"} "rt-a")
          (is (= #{"rt-b"} (set (vals (ledger-of home)))) "only the stopped runtime's ports go"))))))

(deftest an-eager-runtime-allocates-as-before
  (let [ctx (#'engine/pre-allocate-ports (update services 1 dissoc :on-demand) "/tmp/wt-eager")]
    (is (nil? (:repl ctx)) "the JVM's nREPL picks its own port, as it always did")
    (is (= (get-in ctx [:app :port]) (get-in ctx [:app :bind-port]))
        "and the app binds the session's app port")))

(deftest the-join-reconcile-leaves-an-on-demand-app-to-its-front
  (let [calls (atom 0)]
    (with-redefs [nido.session.services.eval/start-app! (fn [& _] (swap! calls inc) true)]
      (engine/reconcile-app! {:service-defs   [{:type :eval :name :app}]
                              :service-states {:app {:on-demand? true :app-port 3300}}})
      (is (zero? @calls) "a join starts no JVM through the front"))))

(deftest the-join-reconcile-recovers-an-on-demand-app-whose-jvm-runs
  (let [calls (atom 0)]
    (with-redefs [nido.session.services.eval/start-app!    (fn [& _] (swap! calls inc) true)
                  nido.session.services.eval/jvm-answers? (fn [s] (= "/tmp/up.sock" (:socket s)))]
      (engine/reconcile-app! {:service-defs   [{:type :eval :name :app}]
                              :service-states {:app {:on-demand? true :app-port 3300 :socket "/tmp/up.sock"}}})
      (is (= 1 @calls) "an app that stopped inside a running JVM is started again, as on any JVM"))))

(deftest the-briefing-says-the-first-call-starts-the-jvm
  (let [rt (fn [on-demand?]
             {:project-dir "/wt-a" :members {"/wt-a" "a"}
              :context {:session {:project-dir "/wt-a"}
                        :repl (cond-> {:port 5200} on-demand? (assoc :on-demand true))
                        :app {:port 3300 :url "http://a.brian.localhost:3300"}}})]
    (with-redefs [links/read-links               (fn [_id] [])
                  launcher/read-project-briefing (fn [_] nil)]
      (with-redefs [state/read-session (fn [_] (rt true))]
        (let [b (launcher/session-briefing "brian" "a" "brian--ws")
              p (launcher/live-services-prompt "brian--ws" "/wt-a")]
          (testing "the front's port, with the same command"
            (is (str/includes? b "- nrepl port: 5200"))
            (is (str/includes? b "clj-nrepl-eval -p 5200")))
          (is (str/includes? b "first call starts the JVM"))
          (is (str/includes? p "The JVM starts on your first call"))
          (is (not (str/includes? p "already warm")) "never that it is warm")))
      (with-redefs [state/read-session (fn [_] (rt false))]
        (is (str/includes? (launcher/live-services-prompt "brian--ws" "/wt-a") "The JVM is already warm")
            "an eager runtime's briefing is as it was")))))
