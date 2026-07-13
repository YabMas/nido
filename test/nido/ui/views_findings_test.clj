(ns nido.ui.views-findings-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.ui.views :as views]))

(def ^:private findings-report
  {:format :findings :round 2
   :items [{:id "f1" :summary "Save 500s" :severity :blocker}]
   :at "2026-07-13T10:00:00Z"})

(deftest gate-pane-renders-findings-report
  (let [html (views/gate-pane {:ws-id "w" :project "brian" :origin :notion
                               :label "BR-7" :report findings-report :actions []})]
    (is (str/includes? html "Findings round 2"))
    (is (str/includes? html "Save 500s"))))
