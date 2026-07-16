(ns nido.ui.views-proposed-ticket-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.ui.views :as views]))

(def ^:private proposed-ticket-report
  {:format :proposed-ticket
   :title "Restore the active-students count on the admin courses tab"
   :ticket-type "bug" :priority "2 - Should"
   :source-url "https://slack/x"
   :problem "Admin courses tab lost the per-course active-students count."
   :root-cause "Regressed in #3802 (bea3fac); the shared column component was reused on the admin tab."
   :fix "Restore the column on the admin tab only (conditional). courses.clj:913."
   :at "2026-07-16T10:00:00Z"})

(deftest gate-pane-renders-proposed-ticket-report
  (let [html (views/gate-pane {:ws-id "w" :project "brian" :origin :slack
                               :label "proposed-ticket" :report proposed-ticket-report
                               :actions []})]
    (is (str/includes? html "Restore the active-students count on the admin courses tab"))
    (is (str/includes? html "Regressed in #3802"))
    (is (str/includes? html "Problem"))
    (is (str/includes? html "bug"))))

(def ^:private improvement-report
  {:format :proposed-ticket
   :title "Bulk-export button on the admin courses tab"
   :ticket-type "improvement" :priority "3 - Could"
   :source-url "https://slack/x"
   :request "No export on the admin courses list."
   :proposed-change "Add an Export CSV toolbar action. admin.clj:684."
   :rationale "Recurring manual toil for ops."
   :at "2026-07-16T10:00:00Z"})

(deftest gate-pane-renders-improvement-proposal
  (let [html (views/gate-pane {:ws-id "w" :project "brian" :origin :slack
                               :label "proposed-ticket" :report improvement-report
                               :actions []})]
    (is (str/includes? html "Bulk-export button on the admin courses tab"))
    (is (str/includes? html "Request"))
    (is (str/includes? html "Proposed change"))
    (is (str/includes? html "Rationale"))
    (is (str/includes? html "improvement"))))
