(ns nido.coordinator.intake-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.intake :as intake]
   [nido.coordinator.session :as session]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]
   [nido.notion.views :as views]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f tmp))
         (finally (fs/delete-tree tmp)))))

(def ^:private routed
  {:project :brian
   :trigger {:name :triage-slack-bugs :skill :triage-bug}
   :payload {:adapter :slack-message :id "slack-C-1.0"
             :title "boom" :text "it broke" :url "u"}})

(deftest enqueue-creates-sessionless-inbox-workstream
  (with-tmp
    (fn [_]
      (let [w (intake/enqueue-inbox! routed)]
        (is (= :incoming (:stage w)))
        (is (= :triage-slack-bugs (-> w :intake :trigger)))
        (is (= "it broke" (-> w :intake :payload :text)))
        (is (= {:adapter :slack-message :id "slack-C-1.0"}
               (-> w :external-refs first (select-keys [:adapter :id]))))
        (is (empty? (session/list-sessions :brian (:id w))))))))

(deftest enqueue-dedups-on-ref
  (with-tmp
    (fn [_]
      (let [a (intake/enqueue-inbox! routed)
            b (intake/enqueue-inbox! routed)]
        (is (= (:id a) (:id b)))
        (is (= 1 (count (ws/list-ids :brian))))))))

(defn- iso->ms [iso] (.toEpochMilli (java.time.Instant/parse iso)))

(deftest enqueue-stamps-no-facets-for-slack
  (with-tmp
    (fn [_]
      (with-redefs [views/facet-properties (constantly ["App Domain" "Type"])]
        (let [w (intake/enqueue-inbox! routed)]
          (is (nil? (:facets w)) "Slack payload has no configured facet props"))))))

(deftest expire-stale-closes-only-old-open-inbox
  (with-tmp
    (fn [_]
      (let [t0 "2026-06-01T00:00:00Z"
            three-days (* 3 24 60 60 1000)]
        (with-redefs [clock/now-iso (constantly t0)]
          ;; old inbox entry — should expire
          (intake/enqueue-inbox! (assoc-in routed [:payload :id] "slack-C-1.0"))
          ;; promoted-away entry — advanced off :incoming, should NOT expire
          (let [p (intake/enqueue-inbox! (assoc-in routed [:payload :id] "slack-C-2.0"))]
            (ws/advance-stage! :brian (:id p) :triaging)))
        ;; fresh inbox entry created 3 days later — younger than 3 days, should NOT expire
        (with-redefs [clock/now-iso (constantly "2026-06-04T00:00:00Z")]
          (intake/enqueue-inbox! (assoc-in routed [:payload :id] "slack-C-3.0")))
        (let [now-ms  (+ (iso->ms t0) three-days 1000)     ; t0 + 3 days + 1s
              expired (intake/expire-stale! :brian three-days now-ms)]
          (is (= 1 (count expired)))
          (is (= :dropped (-> (ws/find-by-ref :brian :slack-message "slack-C-1.0")
                              :closed :outcome)))
          (is (nil? (:closed (ws/find-by-ref :brian :slack-message "slack-C-2.0"))))
          (is (nil? (:closed (ws/find-by-ref :brian :slack-message "slack-C-3.0")))))))))
