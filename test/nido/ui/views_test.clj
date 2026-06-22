(ns nido.ui.views-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [nido.ui.views :as views]))

(deftest system-fragment-banner-table-and-actions
  (let [html (views/system-fragment
              [{:project "brian" :name "fix-login" :live? true
                :entry {:url "http://fix-login.brian.localhost:3142"}}
               {:project "brian" :name "doc-room" :live? false :entry nil}]
              {:state :up})]
    (is (str/includes? html "id=\"system\""))
    (is (str/includes? html "dot-up"))                                  ; daemon banner
    (is (str/includes? html "href=\"http://fix-login.brian.localhost:3142\""))
    (is (str/includes? html "target=\"_blank\""))
    (is (str/includes? html "fix-login"))
    (is (str/includes? html "doc-room"))
    (is (str/includes? html "data-on:click"))
    (is (str/includes? html "/system/brian/doc-room/start"))))         ; renamed POST

(deftest system-page-has-shell-and-poll
  (let [html (views/system-page {:active :system :needs-count 0 :daemon {:state :up}
                                 :scope "all" :projects []} [] {:state :up})]
    (is (str/includes? html "rail-link active"))
    (is (str/includes? html "/_fragment/system"))))

(def ^:private sample-gate
  {:ws-id "ws-1" :project "brian" :origin :notion :stage :triage
   :label "BR-7 · checkout off by a cent"
   :report {:kind :triage :at "2026-06-18T00:00:00Z" :title "Verdict"
            :markdown "# Verdict\n\nbug — reproduced."}
   :actions [{:id :dismiss :label "Dismiss" :kind :mutation}
             {:id :reply :label "Reply" :kind :reply}]
   :session "auto"})

(deftest needs-fragment-lists-cards
  (let [html (views/needs-fragment [sample-gate] nil)]
    (is (str/includes? html "id=\"needs\""))
    (is (str/includes? html "BR-7"))
    (is (str/includes? html ">N<"))
    (is (str/includes? html "/gate/brian/ws-1"))))

(deftest needs-fragment-empty-state-is-calm
  (is (str/includes? (views/needs-fragment [] nil) "Nothing needs you")))

(deftest gate-pane-renders-report-and-actions     ; keep; assert no breadcrumb
  (let [html (views/gate-pane sample-gate)]
    (is (str/includes? html "Verdict"))
    (is (str/includes? html "/gate/brian/ws-1/dismiss"))
    (is (str/includes? html "data-bind=\"reply\""))
    (is (not (str/includes? html "class=\"breadcrumb\"")))
    (is (str/includes? html "bug — reproduced."))
    (is (str/includes? html "/gate/brian/ws-1/reply"))
    (is (str/includes? html "<textarea"))))

(deftest gate-pane-empty-is-calm
  (is (str/includes? (views/gate-pane nil) "Nothing needs you")))

(deftest needs-page-has-shell-master-detail-and-poll
  (let [html (views/needs-page {:active :needs :needs-count 1 :daemon {:state :up} :scope "all" :projects []}
                               [sample-gate] sample-gate)]
    (is (str/includes? html "rail-link active"))        ; rail present + active
    (is (str/includes? html "/_fragment/needs"))         ; polls the renamed fragment
    (is (str/includes? html "BR-7"))))

(def ^:private sample-grouped
  {:triage {:in-flight [{:ws-id "w1" :origin :notion :stage :triage :label "BR-1 · a" :needs-you true}]
            :queued []}
   :ready [{:ws-id "w2" :origin :github :stage :ready :label "#2 · b" :needs-you true}]
   :in-progress [{:ws-id "w3" :origin :scratch :stage :in-progress :label "spike" :needs-you false}]})

(def ^:private sample-ws
  {:ws-id "ws-1" :project "brian" :origin :notion :stage :triage :label "BR-7 · t"
   :ledger {:key "BR-7" :status :investigating :report-count 1}
   :sessions [{:name "auto" :autonomy-level :autonomous :parked? true :status :parked :brakes {:budget "30m"}}
              {:name "me"   :autonomy-level :interactive :parked? false :status :up :brakes nil}]})

(deftest workstreams-fragment-groups-and-links
  (let [html (views/workstreams-fragment [{:project "brian" :grouped sample-grouped}] nil)]
    (is (str/includes? html "id=\"workstreams\""))
    (is (str/includes? html "triage"))
    (is (str/includes? html "ready"))
    (is (str/includes? html "in-progress"))
    (is (str/includes? html "BR-1 · a"))
    (is (str/includes? html "/workstreams/brian/w1"))   ; rows link to the ledger pane
    (is (str/includes? html ">N<"))))

(deftest workstream-pane-shows-ledger-report-and-sessions
  (let [ws (assoc sample-ws :report {:kind :triage :at "t" :title "Verdict"
                                     :markdown "# Verdict\n\nbug — reproduced."})
        html (views/workstream-pane ws "http://auto.brian.localhost:3142")]
    (is (str/includes? html "BR-7"))
    (is (str/includes? html "investigating"))            ; ledger summary status
    (is (str/includes? html "bug — reproduced."))        ; report markdown
    (is (str/includes? html "auto"))                     ; session listed
    (is (str/includes? html "http://auto.brian.localhost:3142"))))   ; route-in

(deftest workstream-pane-empty-when-nil
  (is (str/includes? (views/workstream-pane nil nil) "Select a workstream")))

(deftest workstreams-page-has-shell-and-poll
  (let [html (views/workstreams-page {:active :workstreams :needs-count 0 :daemon {:state :up}
                                      :scope "all" :projects []}
                                     [{:project "brian" :grouped sample-grouped}] nil nil)]
    (is (str/includes? html "rail-link active"))
    (is (str/includes? html "/_fragment/workstreams"))))

(deftest gate-action-confirm-reply-targets-the-pane
  (let [html (views/gate-action-confirm-fragment :reply "brian" "ws-1")]
    (is (str/includes? html "id=\"gate-pane\""))
    (is (str/includes? html "Resuming"))))

(deftest gate-action-confirm-renders-per-action-message-and-follow-links
  (doseq [[action needle] [[:promote "Promoting"] [:dismiss "Dismissed"]
                           [:drop "Dropped"] [:done "done"] [:reply "Resuming"]]]
    (let [html (views/gate-action-confirm-fragment action "brian" "ws-1")]
      (is (str/includes? html needle) (str action " message"))
      (is (str/includes? html "/workstreams/brian/ws-1") (str action " links to the workstream"))
      (is (str/includes? html "/workstreams") (str action " links to workstreams"))))
  (let [html (views/gate-action-confirm-fragment :wat "brian" "ws-1")]
    (is (str/includes? html "id=\"gate-pane\""))))

(deftest gate-card-shows-resume-error-badge
  (let [g (assoc sample-gate :resume-error {:reason :resume-failed :message "exec failed"})
        html (views/needs-fragment [g] nil)]
    (is (str/includes? html "resume failed"))
    (is (str/includes? html "exec failed"))))

(deftest shell-renders-rail-with-active-and-badge
  (let [html (views/shell {:active :workstreams :title "Workstreams"
                           :needs-count 3 :daemon {:state :up} :scope "all" :projects []}
                          [:p "body-here"])]
    ;; all three destinations always present (no per-page drift)
    (is (str/includes? html "Needs you"))
    (is (str/includes? html "Workstreams"))
    (is (str/includes? html "System"))
    ;; active highlight on the current destination
    (is (str/includes? html "rail-link active"))
    ;; live needs badge + count
    (is (str/includes? html "id=\"rail-needs-count\""))
    (is (str/includes? html ">3<"))
    ;; daemon dot
    (is (str/includes? html "id=\"rail-health\""))
    (is (str/includes? html "dot-up"))
    ;; the page body lands in the content area
    (is (str/includes? html "body-here"))))

(deftest rail-status-fragment-has-both-live-bits
  (let [html (views/rail-status-fragment {:needs-count 2 :daemon {:state :halted}})]
    (is (str/includes? html "id=\"rail-needs-count\""))
    (is (str/includes? html ">2<"))
    (is (str/includes? html "id=\"rail-health\""))
    (is (str/includes? html "dot-halted"))))
