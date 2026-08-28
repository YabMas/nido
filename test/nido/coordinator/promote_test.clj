(ns nido.coordinator.promote-test
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.coordinator.intake :as intake]
   [nido.coordinator.promote :as promote]
   [nido.coordinator.session :as session]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.tickets :as tickets]
   [nido.coordinator.workstream :as ws]
   [nido.github.client :as gh]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f tmp))
         (finally (fs/delete-tree tmp)))))

(defn- queued-envelopes []
  (->> (fs/list-dir (cstate/queue-dir))
       (filter #(re-matches #".*\.edn$" (str (fs/file-name %))))
       (map #(edn/read-string (slurp (str %))))))

(deftest promote-refuses-non-triaged
  (with-tmp
    (fn [_]
      (is (= {:decision :skip-no-record} (promote/promote! :brian "BR-NONE")))
      (is (empty? (queued-envelopes))))))

(deftest promote-enqueues-direct-target-and-marks-planning
  (with-tmp
    (fn [_]
      (tickets/open! :brian "BR-7" {:notion-page-id "PG7" :url "U7" :title "T7"
                                    :opened-by :triage-new :notion-last-edited-at "t"})
      (tickets/complete! :brian "BR-7" :triaged :applied)
      (let [res (promote/promote! :brian "BR-7")]
        (is (= :promote (:decision res)))
        (is (string? (:queued res)))
        (is (= :planning (tickets/status :brian "BR-7")) "gate flips to :planning")
        (let [[env] (queued-envelopes)]
          (is (= {:project :brian :trigger :plan-bug} (:target env)))
          (is (= "BR-7"  (-> env :payload :id)))
          (is (= "PG7"   (-> env :payload :notion-page-id)))
          (is (= "T7"    (-> env :payload :title)))))
      ;; second promote now refused (already :planning) and enqueues nothing more
      (is (= {:decision :skip-active} (promote/promote! :brian "BR-7")))
      (is (= 1 (count (queued-envelopes)))))))

(deftest promote-workstream-routes-notion-to-the-plan-bug-leg
  (with-tmp
    (fn [_]
      (tickets/open! :brian "BR-7" {:notion-page-id "pg" :url "nu" :title "nt"
                                    :opened-by :triage-new :notion-last-edited-at "t"})
      (tickets/complete! :brian "BR-7" :triaged :applied)
      (let [w   (ws/create! :brian {:stage :ready :external-refs [{:adapter :notion :id "BR-7" :page-id "pg"}]})
            res (promote/promote-workstream! :brian (:id w))]
        (is (= :promote (:decision res)))
        (let [[env] (queued-envelopes)]
          (is (= {:project :brian :trigger :plan-bug} (:target env)))
          (is (= "BR-7" (-> env :payload :id))))))))

(deftest promote-workstream-github-enqueues-issue-leg-and-advances-stage
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :ready
                                  :external-refs [{:adapter :github-issue :id "o/r#42" :url "iu" :title "it"}]})]
        (with-redefs [gh/view-issue (fn [repo n]
                                      (is (= "o/r" repo)) (is (= 42 n))
                                      {:status :ok :issue {:number 42 :url "iu" :title "it" :body "do it"}})]
          (let [res (promote/promote-workstream! :brian (:id w))]
            (is (= :promote (:decision res)))
            (is (= :in-progress (:stage (ws/read-ws :brian (:id w)))))
            (let [[env] (queued-envelopes)]
              (is (= {:project :brian :trigger :plan-github-issue} (:target env)))
              (is (= "o/r#42" (-> env :payload :id)))
              (is (= "do it"  (-> env :payload :body))))))))))

(deftest promote-workstream-github-refuses-when-already-promoted
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :in-progress :external-refs [{:adapter :github-issue :id "o/r#42"}]})]
        (is (= :skip-active (:decision (promote/promote-workstream! :brian (:id w)))))
        (is (empty? (queued-envelopes)))))))

(deftest promote-workstream-github-surfaces-a-fetch-error
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :ready :external-refs [{:adapter :github-issue :id "o/r#42"}]})]
        (with-redefs [gh/view-issue (fn [_ _] {:error :auth})]
          (is (= :gh-error (:decision (promote/promote-workstream! :brian (:id w)))))
          (is (= :ready (:stage (ws/read-ws :brian (:id w)))))
          (is (empty? (queued-envelopes))))))))

(deftest promote-workstream-scratch-is-not-promotable
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :scratch :external-refs []})]
        (is (= :skip-not-promotable (:decision (promote/promote-workstream! :brian (:id w)))))))))

(deftest promote!-enqueues-a-keyword-project-target
  (with-tmp
    (fn [_]
      (tickets/open! :brian "BR-9" {:notion-page-id "PG9" :url "U9" :title "t"
                                    :opened-by :triage-new :notion-last-edited-at "t"})
      (tickets/complete! :brian "BR-9" :triaged :applied)
      (let [res (promote/promote! "brian" "BR-9")]   ;; STRING project, as the web passes
        (is (= :promote (:decision res)))
        (let [[env] (queued-envelopes)]
          (is (= :brian (get-in env [:target :project]))
              "the enqueued envelope carries a KEYWORD project so the router resolves it")
          (is (= :plan-bug (get-in env [:target :trigger]))))))))

(defn- write-triggers! [project ts]
  (let [p (cstate/triggers-path project)]
    (fs/create-dirs (fs/parent p))
    (spit p (pr-str {:triggers ts}))))

(def ^:private slack-trigger
  {:name :triage-slack-bugs :intake :queue :skill :triage-bug :agent :claude
   :session-profile :lite
   :source {:type :slack-channel :channel "C"}
   :payload "Triage Slack bug: {{event/title}}\n\n{{event/text}}"
   :limits {:budget "15m"}})

(deftest promote-inbox-slack-starts-triage
  (with-tmp
    (fn [_]
      (write-triggers! :brian [slack-trigger])
      (let [w   (intake/enqueue-inbox!
                  {:project :brian
                   :trigger {:name :triage-slack-bugs}
                   :payload {:adapter :slack-message :id "slack-C-1.0"
                             :title "boom" :text "it broke"}})
            res (promote/promote-workstream! :brian (:id w))
            w'  (ws/read-ws :brian (:id w))]
        (is (= :triaging (:decision res)))
        ;; advanced off :incoming and a triage session now exists on the SAME ws
        (is (= :triaging (:stage w')))
        (is (= 1 (count (session/list-sessions :brian (:id w)))))
        ;; re-promote: no longer in the queue
        (is (= :skip-not-inbox
               (:decision (promote/promote-workstream! :brian (:id w)))))))))

(deftest promote-inbox-slack-missing-trigger
  (with-tmp
    (fn [_]
      (write-triggers! :brian [])                       ; trigger gone from disk
      (let [w (intake/enqueue-inbox!
                {:project :brian
                 :trigger {:name :triage-slack-bugs}
                 :payload {:adapter :slack-message :id "slack-C-2.0" :text "x"}})]
        (is (= :skip-no-trigger
               (:decision (promote/promote-workstream! :brian (:id w)))))
        ;; no session spawned, still in the queue
        (is (= :incoming (:stage (ws/read-ws :brian (:id w)))))
        (is (empty? (session/list-sessions :brian (:id w))))))))
