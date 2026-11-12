(ns nido.coordinator.lane.promote-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.coordinator.lane.brief :as brief]
   [nido.coordinator.lane.intake :as intake]
   [nido.coordinator.lane.start :as start]
   [nido.coordinator.lane.promote :as promote]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.standing :as standing]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.tickets :as tickets]
   [nido.coordinator.record.workstream :as ws]
   [nido.github.client :as gh]
   [nido.notion.client :as notion]))

(defn- one-off!
  "A ref-less workstream born a one-off: its ledger holds the :scratch birth entry that
   `scratch/birth!` appends as it mints one."
  []
  (let [w (ws/create! :brian {:external-refs []})]
    (ws/append-entry! :brian (:id w) {:kind :scratch} (pr-str {:format :scratch}))
    (ws/read-ws :brian (:id w))))


(def ^:private ups
  "The sessions a start brought up in the current test, as [project session opts]."
  (atom []))

(defn- with-tmp
  "Run `f` against a throwaway nido root, with a start's session bring-up and Notion legs stubbed."
  [f]
  (let [tmp (fs/create-temp-dir)]
    (reset! ups [])
    (try (with-redefs [core/nido-root                 (constantly (str tmp))
                       start/start-session!           (fn [p s o] (swap! ups conj [p s o]) (:ws-id o))
                       brief/ensure-ticket-brief!     (fn [p id _ _] (ws/append-entry! p id {:kind :ticket} "words"))
                       notion/keychain-token          (constantly "tok")
                       notion/update-page-properties! (fn [& _] {:ok true})]
           (cstate/ensure-dirs!) (f tmp))
         (finally (fs/delete-tree tmp)))))

(defn- triaged!
  "A typed verdict on the workstream carrying `br` — minting it when none does — that a person
   accepted: what makes a ticket :triaged, the one promotable status."
  [br]
  (ws/append-to-ref! :brian br {:kind :triage}
                     (pr-str {:format :triage-report :ticket-key br :determination :bug
                              :title "Verdict" :summary "bug — reproduced."
                              :confidence {:level :high :reason "r"}
                              :routing nil :questions [] :notion-writes nil :trail []}))
  (tickets/complete! :brian br))

(defn- no-queue? [] (empty? (fs/list-dir (cstate/queue-dir))))

(deftest promote-refuses-non-triaged
  (with-tmp
    (fn [_]
      (is (= {:decision :skip-no-record} (promote/promote! :brian "BR-NONE")))
      (is (empty? @ups)))))

(deftest promote-starts-the-ticket-at-once
  (with-tmp
    (fn [_]
      (tickets/open! :brian "BR-7" {:notion-page-id "PG7" :url "U7" :title "T7"
                                    :opened-by :triage-new :notion-last-edited-at "t"})
      (triaged! "BR-7")
      (let [res (promote/promote! :brian "BR-7")
            w   (ws/find-by-ref-id :brian "BR-7")]
        (is (= {:decision :promote :ws-id (:id w) :session "impl-br-7-t7"} res))
        (is (= :planning (tickets/status :brian "BR-7")) "the person's choice reads as :planning")
        (is (standing/activated? w) "the start activates the workstream")
        (is (= [[:brian "impl-br-7-t7" {:ws-id (:id w) :carry? true :session-profile :full :wait false}]] @ups)
            "the impl session comes up at the click")
        (is (no-queue?) "no envelope, so no Run, trigger, budget or breaker"))
      (is (= {:decision :skip-active} (promote/promote! :brian "BR-7"))
          "a second promote is refused: it is already in progress")
      (is (= 1 (count @ups))))))

(deftest promote-workstream-starts-a-notion-ticket
  (with-tmp
    (fn [_]
      (tickets/open! :brian "BR-7" {:notion-page-id "pg" :url "nu" :title "nt"
                                    :opened-by :triage-new :notion-last-edited-at "t"})
      (let [w   (ws/create! :brian {:external-refs [{:adapter :notion :id "BR-7" :page-id "pg"}]})
            _   (triaged! "BR-7")
            res (promote/promote-workstream! :brian (:id w))]
        (is (= :promote (:decision res)))
        (is (= (:id w) (:ws-id res)))
        (is (no-queue?))))))

(deftest promote-workstream-github-starts-the-issue
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:external-refs [{:adapter :github-issue :id "o/r#42" :url "iu" :title "it"}]})]
        (with-redefs [gh/view-issue (fn [repo n]
                                      (is (= "o/r" repo)) (is (= 42 n))
                                      {:status :ok :issue {:number 42 :url "iu" :title "it" :body "do it"}})]
          (let [res (promote/promote-workstream! :brian (:id w))
                w'  (ws/read-ws :brian (:id w))]
            (is (= :promote (:decision res)))
            (is (= :in-progress (:stage (ws/newest-choice w'))))
            (is (standing/activated? w'))
            (is (= :ticket (:kind (first (:entries w')))) "the issue is the brief, on the ledger")
            (is (= 1 (count @ups)))
            (is (no-queue?))))))))

(deftest promote-workstream-github-refuses-when-already-promoted
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:external-refs [{:adapter :github-issue :id "o/r#42"}]})]
        ;; What promoting it records: a person's choice to put it in progress.
        (ws/advance-stage! :brian (:id w) :in-progress :person)
        (is (= :skip-active (:decision (promote/promote-workstream! :brian (:id w)))))
        (is (empty? @ups))))))

(deftest promote-workstream-github-refuses-an-unreadable-issue
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:external-refs [{:adapter :github-issue :id "o/r#42"}]})]
        (with-redefs [gh/view-issue (fn [_ _] {:error :auth})]
          (is (= :ticket-unreadable (:decision (promote/promote-workstream! :brian (:id w)))))
          (is (nil? (ws/newest-choice (ws/read-ws :brian (:id w)))) "a failed fetch chooses nothing")
          (is (empty? @ups)))))))

(deftest promote-workstream-scratch-is-not-promotable
  (with-tmp
    (fn [_]
      (let [w (one-off!)]
        (is (= :skip-not-promotable (:decision (promote/promote-workstream! :brian (:id w)))))))))

(deftest promote!-takes-a-string-project
  (with-tmp
    (fn [_]
      (tickets/open! :brian "BR-9" {:notion-page-id "PG9" :url "U9" :title "t"
                                    :opened-by :triage-new :notion-last-edited-at "t"})
      (triaged! "BR-9")
      (let [res (promote/promote! "brian" "BR-9")]   ;; STRING project, as the web passes
        (is (= :promote (:decision res)))
        (is (= :brian (ffirst @ups)) "the start resolves the project as a keyword")))))

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
        (is (= :triaging (:stage (ws/newest-choice w'))))
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
        (is (= :incoming (:stage (ws/newest-choice (ws/read-ws :brian (:id w))))))
        (is (empty? (session/list-sessions :brian (:id w))))))))
