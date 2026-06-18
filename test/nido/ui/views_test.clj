(ns nido.ui.views-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [nido.ui.views :as views]))

(deftest sessions-table-renders-friendly-host-link
  ;; The registry persists the friendly-host app URL under :url; the table
  ;; renders it as a clickable link.
  (let [html (views/sessions-table-fragment
              "brian"
              [{:name "fix-login" :live? true
                :entry {:url "http://fix-login.brian.localhost:3142" :app-port 3142}}])]
    (is (str/includes? html "href=\"http://fix-login.brian.localhost:3142\""))))

(deftest live-board-fragment-links-live-and-starts-down
  (let [html (views/live-board-fragment
              [{:project "brian" :name "fix-login" :live? true
                :entry {:url "http://fix-login.brian.localhost:3142"}}
               {:project "brian" :name "doc-room" :live? false :entry nil}])]
    ;; live row: clickable friendly-host link (registry :url) opening a new tab
    (is (str/includes? html "href=\"http://fix-login.brian.localhost:3142\""))
    (is (str/includes? html "target=\"_blank\""))
    ;; both sessions listed
    (is (str/includes? html "fix-login"))
    (is (str/includes? html "doc-room"))
    ;; down row: a real start button POSTing the lifecycle action (hiccup2
    ;; escapes the quotes to &apos; — the browser decodes them back)
    (is (str/includes? html "data-on:click"))
    (is (str/includes? html "/brian/sessions/doc-room/start"))))

(deftest live-board-page-renders-header-and-poll
  (let [html (views/live-board-page [])]
    (is (str/includes? html "live sessions"))
    ;; auto-refresh against the board SSE fragment
    (is (str/includes? html "/_fragment/live"))))

(def ^:private sample-gate
  {:ws-id "ws-1" :project "brian" :origin :notion :stage :triage
   :label "BR-7 · checkout off by a cent"
   :report {:kind :triage :at "2026-06-18T00:00:00Z" :title "Verdict"
            :markdown "# Verdict\n\nbug — reproduced."}
   :actions [{:id :skip :label "Skip" :kind :mutation}
             {:id :reply :label "Reply" :kind :reply}]
   :session "auto"})

(deftest gate-inbox-fragment-lists-cards
  (let [html (views/gate-inbox-fragment [sample-gate] nil)]
    (is (str/includes? html "id=\"gate-inbox\""))
    (is (str/includes? html "BR-7"))
    (is (str/includes? html ">N<") "origin badge")
    (is (str/includes? html "brian"))
    (is (str/includes? html "/gate/brian/ws-1") "card links to the gate pane")))

(deftest gate-inbox-fragment-empty-state
  (is (str/includes? (views/gate-inbox-fragment [] nil) "No gates")))

(deftest gate-pane-renders-report-and-actions
  (let [html (views/gate-pane sample-gate)]
    (is (str/includes? html "Verdict"))
    (is (str/includes? html "bug — reproduced."))
    (is (str/includes? html "/gate/brian/ws-1/skip"))
    (is (str/includes? html "/gate/brian/ws-1/reply"))
    (is (str/includes? html "<textarea"))
    (is (str/includes? html "data-bind=\"reply\"") "reply textarea is two-way bound to the reply signal")))

(deftest gate-pane-empty-when-nil
  (is (str/includes? (views/gate-pane nil) "Select a gate")))

(deftest gate-inbox-page-has-master-detail-and-poll
  (let [html (views/gate-inbox-page [sample-gate] sample-gate)]
    (is (str/includes? html "gate-wrap"))
    (is (str/includes? html "/_fragment/gates") "polls the inbox fragment")
    (is (str/includes? html "BR-7"))))

(def ^:private sample-grouped
  {:triage {:in-flight [{:ws-id "w1" :origin :notion :stage :triage :label "BR-1 · a" :needs-you true}]
            :queued []}
   :ready [{:ws-id "w2" :origin :github :stage :ready :label "#2 · b" :needs-you true}]
   :in-progress [{:ws-id "w3" :origin :scratch :stage :in-progress :label "spike" :needs-you false}]})

(deftest board-fragment-groups-by-stage-with-badges
  ;; board-fragment takes a seq of {:project :grouped} so it can thread the project
  ;; into each row's /ws/<project>/<ws-id> link (grouped rows carry no :project).
  (let [html (views/board-fragment [{:project "brian" :grouped sample-grouped}])]
    (is (str/includes? html "triage"))
    (is (str/includes? html "ready"))
    (is (str/includes? html "in-progress"))
    (is (str/includes? html "BR-1 · a"))
    (is (str/includes? html "/ws/brian/w1") "rows link to workstream detail")
    (is (str/includes? html ">N<"))))

(def ^:private sample-ws
  {:ws-id "ws-1" :project "brian" :origin :notion :stage :triage :label "BR-7 · t"
   :ledger {:key "BR-7" :status :investigating :report-count 1}
   :sessions [{:name "auto" :autonomy-level :autonomous :parked? true :status :parked :brakes {:budget "30m"}}
              {:name "me"   :autonomy-level :interactive :parked? false :status :up :brakes nil}]})

(deftest ws-detail-renders-ledger-and-sessions
  (let [html (views/ws-detail-page sample-ws "http://auto.brian.localhost:3142")]
    (is (str/includes? html "BR-7"))
    (is (str/includes? html "investigating"))
    (is (str/includes? html "auto"))
    (is (str/includes? html "me"))
    (is (str/includes? html "autonomous"))
    (is (str/includes? html "parked"))
    (is (str/includes? html "http://auto.brian.localhost:3142") "route-in link when a live url is known")))
