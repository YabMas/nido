(ns nido.session.on-demand-test
  "What an on-demand JVM changes outside its front: the ports a runtime start allocates, the app
   it does not start, and what the agent is told."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.session.engine :as engine]
   [nido.session.launcher :as launcher]
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
