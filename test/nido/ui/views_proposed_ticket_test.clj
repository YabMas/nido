(ns nido.ui.views-proposed-ticket-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.ui.views :as views]))

(def ^:private proposed-ticket-report
  {:format :proposed-ticket
   :title "Restore the active-students count on the admin courses tab"
   :description "Regressed in #3802; the shared column component was reused on the admin tab."
   :ticket-type "bug"
   :priority "2 - Should"
   :source-url "https://slack/x"
   :at "2026-07-15T15:00:00Z"})

(deftest gate-pane-renders-proposed-ticket-report
  ;; A :proposed-ticket must render through report->markdown, not the :markdown
  ;; default branch (typed reports carry no :markdown, so that path blanks the pane).
  (let [html (views/gate-pane {:ws-id "w" :project "brian" :origin :slack
                               :label "proposed-ticket" :report proposed-ticket-report
                               :actions []})]
    (is (str/includes? html "Restore the active-students count on the admin courses tab"))
    (is (str/includes? html "Regressed in #3802"))
    (is (str/includes? html "bug"))))
