(ns nido.ui.server-fleet-test
  "The fleet card's one mutation: down a single idle session."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.session.fleet :as fleet]
   [nido.ui.dev :as dev]
   [nido.ui.server :as server]))

(defn- post [uri]
  (server/handle-request {:request-method :post :uri uri
                          :body (java.io.ByteArrayInputStream. (.getBytes "" "UTF-8"))}))

(deftest down-stops-exactly-the-named-session
  (let [called (atom [])]
    (with-redefs [dev/stop-session! (fn [p s] (swap! called conj [p s]) "brian--x")
                  fleet/snapshot    (fn [] [])]
      (let [resp (post "/ops/fleet/brian/impl-br-5559/down")]
        (is (= 200 (:status resp)))
        (is (= [["brian" "impl-br-5559"]] @called)
            "one session, by name — not its workstream")
        (is (str/includes? (:body resp) "ops-panel")
            "and the answer patches the panel the click came from")))))

(deftest a-branch-prefixed-session-name-survives-the-round-trip
  ;; `feat/learning-goals` is a real session name. Unencoded it would split the
  ;; path and address a session called "feat".
  (let [called (atom [])]
    (with-redefs [dev/stop-session! (fn [p s] (swap! called conj [p s]) "brian--learning-goals")
                  fleet/snapshot    (fn [] [])]
      (post "/ops/fleet/brian/feat%2Flearning-goals/down")
      (is (= [["brian" "feat/learning-goals"]] @called)))))

(deftest an-in-flight-stop-is-visible-on-the-row
  (let [row {:instance-id "brian--idle" :project "brian" :session "idle"
             :bytes 1000 :candidate? true :signals-ok? true :idle-ms (* 50 3600000)}]
    (testing "stopping"
      (with-redefs [fleet/snapshot          (fn [] [row])
                    fleet/candidates        (fn [_] [row])
                    dev/current-app-state   (fn [_] {:state :stopping})]
        (let [body (:body (server/handle-request {:request-method :get :uri "/_fragment/ops"}))]
          (is (str/includes? body "stopping"))
          (is (not (str/includes? body "/ops/fleet/brian/idle/down"))
              "no second click while the first is in flight"))))

    (testing "failed keeps the button and says why"
      (with-redefs [fleet/snapshot        (fn [] [row])
                    fleet/candidates      (fn [_] [row])
                    dev/current-app-state (fn [_] {:state :failed :error-msg "pg_ctl refused"})]
        (let [body (:body (server/handle-request {:request-method :get :uri "/_fragment/ops"}))]
          (is (str/includes? body "pg_ctl refused"))
          (is (str/includes? body "/ops/fleet/brian/idle/down")
              "the action stays retryable"))))))
