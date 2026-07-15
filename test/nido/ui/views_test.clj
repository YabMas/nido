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
   :report {:format :markdown :kind :triage :at "2026-06-18T00:00:00Z" :title "Verdict"
            :markdown "# Verdict\n\nbug — reproduced."}
   :actions [{:id :apply   :label "Apply"   :kind :resume :input "apply" :style :primary}
             {:id :dismiss :label "Dismiss" :kind :mutation              :style :danger}
             {:id :reply   :label "Reply"   :kind :resume                :style :default}]
   :session "auto"})

(def ^:private triage-gate
  (assoc sample-gate
         :report {:format :triage-report :at "2026-06-18T00:00:00Z"
                  :ticket-key "BR-7" :determination :bug
                  :title "Checkout off by a cent" :summary "Rounding on each line."
                  :confidence {:level :high :reason "repro"}
                  :directions [{:label "A" :shape "round once" :effort :M
                                :confidence {:level :medium :reason "money math"}}]
                  :notion-writes {:type "bug" :effort :M
                                  :status-transition ["Needs verification" "Not started"]
                                  :title "Checkout off by a cent"
                                  :description-prepend "Rounding bug."}
                  :trail [{:ref "src/order.clj:88" :note "per-line round"}]}))

(deftest needs-fragment-lists-cards
  (let [html (views/needs-fragment {:gates [sample-gate] :selection nil :scope "all"})]
    (is (str/includes? html "id=\"needs\""))
    (is (str/includes? html "BR-7"))
    (is (str/includes? html ">N<"))
    ;; selecting a gate carries the view-state (sel) rather than a bare path
    (is (str/includes? html "sel=brian:ws-1"))))

(deftest needs-fragment-empty-state-is-calm
  (is (str/includes? (views/needs-fragment {:gates [] :selection nil}) "Nothing needs you")))

(deftest needs-fragment-preserves-selection-highlight
  ;; the polled fragment must keep the open gate's highlight (selection threaded
  ;; from the screen, not reset to nil each tick)
  (let [html (views/needs-fragment {:gates [sample-gate]
                                    :selection {:project "brian" :ws-id "ws-1"} :scope "all"})]
    (is (str/includes? html "gate-card sel"))))

(deftest needs-fragment-marks-pending-gate-working
  (let [html (views/needs-fragment {:gates [(assoc sample-gate :pending? true)]
                                    :selection nil :scope "all"})]
    (is (str/includes? html "working…") "a resumed gate shows working in the inbox row")))

(deftest gate-pane-renders-markdown-report-and-actions
  (let [html (views/gate-pane sample-gate)]
    (is (str/includes? html "Verdict"))
    (is (str/includes? html "bug — reproduced."))
    (is (str/includes? html "/gate/brian/ws-1/apply"))     ; one-click Apply button
    (is (str/includes? html "/gate/brian/ws-1/dismiss"))
    (is (str/includes? html "data-bind=\"reply\""))         ; free-text reply still present
    (is (str/includes? html "/gate/brian/ws-1/reply"))
    (is (str/includes? html "<textarea"))
    (is (not (str/includes? html "class=\"breadcrumb\"")))))

(deftest gate-pane-curates-a-triage-report
  (let [html (views/gate-pane triage-gate)]
    (is (str/includes? html "<h2>Checkout off by a cent</h2>"))   ; §1 enriched title (heading, not the §3 li)
    (is (str/includes? html "Rounding on each line."))       ; §1 summary
    (is (str/includes? html "round once"))                   ; §2 direction
    (is (str/includes? html "Needs verification"))           ; §3 notion-writes
    (is (str/includes? html "<details"))                     ; §5 collapsed
    (is (str/includes? html "src/order.clj:88"))
    (is (str/includes? html "/gate/brian/ws-1/apply"))
    (is (not (str/includes? html "dismiss instead")))))

(deftest gate-pane-empty-is-calm
  (is (str/includes? (views/gate-pane nil) "Nothing needs you")))

(deftest needs-page-has-shell-master-detail-and-poll
  (let [html (views/needs-page {:active :needs :needs-count 1 :daemon {:state :up} :scope "all" :projects []}
                               {:gates [sample-gate] :selection {:project "brian" :ws-id "ws-1"}
                                :scope "all" :source :all :facets {}})]
    (is (str/includes? html "rail-link active"))        ; rail present + active
    (is (str/includes? html "/_fragment/needs"))         ; polls the renamed fragment
    (is (str/includes? html "BR-7"))))                   ; the selected gate fills the pane

(deftest gate-pane-pending-shows-working-without-actions
  ;; after Apply/Reply the agent is running → the pane shows "working…" and NO
  ;; action buttons, so a poll can't flip it back to a fresh Apply button.
  (let [html (views/gate-pane (assoc sample-gate :pending? true))]
    (is (str/includes? html "working…"))
    (is (not (str/includes? html "/gate/brian/ws-1/apply")) "no action buttons while working")
    (is (not (str/includes? html "<textarea")))))

(def ^:private sample-grouped
  ;; :ready is intentionally NOT a rendered band — backlog lives in Notion, not
  ;; on the nido board (a :ready row here would simply never appear).
  {:triage {:in-flight [{:ws-id "w1" :origin :notion :stage :triage :label "BR-1 · a" :needs-you true}]
            :queued []}
   :in-progress [{:ws-id "w3" :origin :scratch :stage :in-progress :label "spike" :needs-you false}]})

(def ^:private sample-ws
  {:ws-id "ws-1" :project "brian" :origin :notion :stage :triage :label "BR-7 · t"
   :ledger {:key "BR-7" :status :investigating :report-count 1}
   :sessions [{:name "auto" :autonomy-level :autonomous :parked? true :status :parked :brakes {:budget "30m"}}
              {:name "me"   :autonomy-level :interactive :parked? false :status :up :brakes nil}]})

(deftest workstreams-fragment-groups-and-links
  (let [html (views/workstreams-fragment {:groups [{:project "brian" :grouped sample-grouped}]
                                          :selection nil :scope "all" :source :all :facets {}})]
    (is (str/includes? html "id=\"workstreams\""))
    (is (str/includes? html "triage"))
    (is (not (str/includes? html "ready")) "no :ready band section")
    (is (str/includes? html "in-progress"))
    (is (str/includes? html "BR-1 · a"))
    (is (str/includes? html "sel=brian:w1"))            ; rows carry the selection in the view-state
    (is (str/includes? html ">N<"))))

(deftest workstreams-fragment-preserves-selection-and-filters
  ;; a poll refresh keeps the open row highlighted, and each row link preserves
  ;; the active source filter so selecting one lands on the SAME filtered list.
  (let [html (views/workstreams-fragment {:groups [{:project "brian" :grouped sample-grouped}]
                                          :selection {:project "brian" :ws-id "w1"}
                                          :scope "all" :source :notion :facets {}})]
    (is (str/includes? html "gate-card sel") "selected row keeps its highlight")
    (is (str/includes? html "sel=brian:w1"))
    (is (str/includes? html "source=notion") "row link preserves the active source filter")))

(deftest workstream-pane-shows-ledger-report-and-sessions
  (let [ws   (assoc sample-ws :report {:format :markdown :kind :triage :at "t" :title "Verdict"
                                       :markdown "# Verdict\n\nbug — reproduced."})
        html (views/workstream-pane ws {"auto" {:state :running :url "http://auto.brian.localhost:3142"}})]
    (is (str/includes? html "BR-7"))
    (is (str/includes? html "investigating"))            ; ledger summary status
    (is (str/includes? html "bug — reproduced."))        ; report markdown
    (is (str/includes? html "auto"))                     ; session listed
    (is (str/includes? html "http://auto.brian.localhost:3142")))) ; per-row Open-app link

(deftest workstream-pane-session-running-shows-open-and-stop
  (let [html (views/workstream-pane sample-ws {"me" {:state :running :url "http://me.brian.localhost:3142"}})]
    (is (str/includes? html "dev env"))                  ; new table column header
    (is (str/includes? html "Open app"))
    (is (str/includes? html "http://me.brian.localhost:3142"))
    (is (str/includes? html "/workstreams/brian/ws-1/sessions/me/dev/stop"))
    (is (str/includes? html "id=\"ws-pane\""))
    (is (str/includes? html "/_fragment/workstream/brian/ws-1"))   ; pane interval poll
    (is (str/includes? html "auto"))))                             ; other session row intact

(deftest workstream-pane-session-down-shows-start
  (let [html (views/workstream-pane sample-ws {"me" {:state :down}})]
    (is (str/includes? html "/workstreams/brian/ws-1/sessions/me/dev/start"))))

(deftest workstream-pane-session-failed-shows-retry-and-error
  (let [html (views/workstream-pane sample-ws {"me" {:state :failed :error-msg "boom: port never opened"}})]
    (is (str/includes? html "retry"))
    (is (str/includes? html "boom: port never opened"))
    (is (str/includes? html "/workstreams/brian/ws-1/sessions/me/dev/start"))))   ; retry POSTs to start

(deftest workstream-pane-no-dev-env-card
  ;; the standalone weight-gated card is gone
  (let [html (views/workstream-pane sample-ws {})]
    (is (not (str/includes? html "No dev environment yet")))))

(deftest workstream-pane-empty-when-nil
  (is (str/includes? (views/workstream-pane nil nil) "Select a workstream")))

(deftest workstreams-page-has-shell-and-poll
  (let [html (views/workstreams-page {:active :workstreams :needs-count 0 :daemon {:state :up}
                                      :scope "all" :projects []}
                                     {:scope "all" :source :all :facets {} :facet-dims []
                                      :source-counts {} :selection nil
                                      :groups [{:project "brian" :grouped sample-grouped}]})]
    (is (str/includes? html "rail-link active"))
    (is (str/includes? html "/_fragment/workstreams"))))

(deftest gate-action-confirm-reply-targets-the-pane
  (let [html (views/gate-action-confirm-fragment :reply "brian" "ws-1")]
    (is (str/includes? html "id=\"gate-pane\""))
    (is (str/includes? html "Resuming"))))

(deftest gate-action-confirm-renders-per-action-message-and-follow-links
  (doseq [[action needle] [[:promote "Promoting"] [:apply "Applying"] [:dismiss "Dismissed"]
                           [:drop "Dropped"] [:done "done"] [:reply "Resuming"]]]
    (let [html (views/gate-action-confirm-fragment action "brian" "ws-1")]
      (is (str/includes? html needle) (str action " message"))
      (is (str/includes? html "/workstreams/brian/ws-1") (str action " links to the workstream"))
      (is (str/includes? html "/workstreams") (str action " links to workstreams"))))
  (let [html (views/gate-action-confirm-fragment :wat "brian" "ws-1")]
    (is (str/includes? html "id=\"gate-pane\""))))

(deftest gate-card-shows-resume-error-badge
  (let [g (assoc sample-gate :resume-error {:reason :resume-failed :message "exec failed"})
        html (views/needs-fragment {:gates [g] :selection nil :scope "all"})]
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

(deftest workstreams-filter-bar-renders-source-chips-with-counts
  (let [screen {:active :workstreams :scope "all" :projects [] :needs-count 0 :daemon {:state :up}
                :source :notion :facets {} :facet-dims [:app-domain :type]
                :source-counts {:notion 3 :slack 1} :selection nil :groups []}
        html (views/workstreams-page screen screen)]
    (is (str/includes? html "Notion"))
    (is (str/includes? html "(3)") "per-source count shown")
    (is (str/includes? html "App Domain") "facet row for the Notion source")
    (is (str/includes? html "Type"))
    ;; the interval @get must carry the active source so SSE refresh preserves it
    (is (str/includes? html "source=notion"))))

(deftest workstreams-filter-bar-hides-facets-for-facetless-source
  (let [screen {:active :workstreams :scope "all" :projects [] :needs-count 0 :daemon {:state :up}
                :source :slack :facets {} :facet-dims []
                :source-counts {:notion 3 :slack 1} :selection nil :groups []}
        html (views/workstreams-page screen screen)]
    (is (not (str/includes? html "App Domain")) "no facet rows for a facet-less source")
    (is (str/includes? html "source=slack"))))

(deftest workstreams-filter-bar-renders-facet-values-from-rows
  ;; facet value chips are derived from the displayed rows: distinct present
  ;; App Domain values + :unclassified for the row that lacks the facet.
  (let [groups [{:project :brian
                 :grouped {:incoming [{:origin :notion :facets {:app-domain ["Teacher"]}}
                                      {:origin :notion :facets {:app-domain ["Student"]}}
                                      {:origin :notion :facets {}}]
                           :triage {:in-flight [] :queued []} :ready [] :in-progress []}}]
        screen {:active :workstreams :scope "all" :projects [] :needs-count 0 :daemon {:state :up}
                :source :notion :facets {} :facet-dims [:app-domain]
                :source-counts {:notion 3} :selection nil :groups groups}
        html (views/workstreams-page screen screen)]
    (is (str/includes? html "Teacher")     "present value chip")
    (is (str/includes? html "Student")     "second present value chip")
    (is (str/includes? html "Unclassified") ":unclassified chip for the facet-less row")))

(deftest screen-query-encodes-facet-values
  ;; :unclassified emits as "unclassified" (no colon); spaces are encoded.
  (let [q (#'views/screen-query {:scope "all" :source :notion
                                 :facets {:app-domain :unclassified}})]
    (is (str/includes? q "app-domain=unclassified"))
    (is (not (str/includes? q ":unclassified"))))
  (let [q (#'views/screen-query {:scope "all" :source :notion
                                 :facets {:app-domain "Onboarding Flow"}})]
    (is (not (str/includes? q "Onboarding Flow")) "raw space not in the query")
    (is (or (str/includes? q "Onboarding%20Flow") (str/includes? q "Onboarding+Flow"))))
  ;; a :sel override adds the selection while keeping the filters
  (let [q (#'views/screen-query {:scope "all" :source :notion :facets {}} {:sel "brian:ws-7"})]
    (is (str/includes? q "source=notion"))
    (is (str/includes? q "sel=brian:ws-7"))))

(deftest workstream-pane-shows-ledger-index-and-selected-report
  (let [ws   (assoc sample-ws
                    :selected-seq 2
                    :entries [{:seq 2 :kind :impl   :at "2026-06-19T00:00:00Z" :title "Draft PR"}
                              {:seq 1 :kind :triage :at "2026-06-18T00:00:00Z" :title "Verdict"}]
                    :report {:format :markdown :kind :impl :at "t" :title "Draft PR"
                             :markdown "# Draft PR\n\nopened it."})
        html (views/workstream-pane ws {})]
    (is (str/includes? html "ledger-index"))
    (is (str/includes? html "Draft PR"))                      ; row + report title
    (is (str/includes? html "Verdict"))                       ; other row
    (is (str/includes? html "?entry=1"))                      ; click target for the unselected row
    (is (str/includes? html "ledger-row sel"))                ; seq 2 highlighted
    (is (str/includes? html "opened it."))))                  ; selected report body rendered

(deftest workstream-pane-poll-url-carries-selected-entry
  (let [ws   (assoc sample-ws
                    :selected-seq 2
                    :entries [{:seq 2 :kind :impl :at "t" :title "a"}
                              {:seq 1 :kind :triage :at "t" :title "b"}])
        html (views/workstream-pane ws {})]
    (is (str/includes? html "/_fragment/workstream/brian/ws-1?entry=2")
        "self-poll re-requests the same selected entry so it survives the refresh")))

(deftest workstream-pane-renders-implementation-plan-card
  (let [ws   (assoc sample-ws :report {:format :implementation-plan :summary "Round on the total."
                                       :direction "Round once on the order total" :effort :M
                                       :steps ["add a render test" "fix the calc"]})
        html (views/workstream-pane ws {})]
    (is (str/includes? html "Implementation plan"))
    (is (str/includes? html "Round once on the order total"))
    (is (str/includes? html "Steps"))
    (is (str/includes? html "fix the calc"))))

(deftest workstream-pane-renders-blocker-completed-pr-cards
  (let [pane (fn [report] (views/workstream-pane (assoc sample-ws :report report) {}))]
    (is (str/includes? (pane {:format :blocker :summary "Waiting." :needs "Stripe key"}) "Blocker"))
    (is (str/includes? (pane {:format :blocker :summary "Waiting." :needs "Stripe key"}) "Stripe key"))
    (is (str/includes? (pane {:format :implementation-completed :summary "Done."
                              :artifacts [{:kind :pr :ref "o/r#1"}]}) "Artifacts"))
    (is (str/includes? (pane {:format :pr-opened :url "http://x/1" :title "Fix it"}) "Fix it"))))

;; ---------------------------------------------------------------------------
;; Shipping badge — :blocked is loud in the board fragment
;; ---------------------------------------------------------------------------

(deftest workstreams-fragment-shipping-row-blocked-shows-loud-badge
  ;; A :shipping row with :ship-substate :blocked must render "⚠ blocked" in
  ;; the board fragment. This covers the loud-blocked requirement and confirms
  ;; the CSS class is emitted (descendant selector safe — no > combinators).
  (let [ship-row {:ws-id "ws-ship" :origin :notion :label "BR-99 · Checkout"
                  :needs-you true :stage :shipping :ship-substate :blocked}
        html (views/workstreams-fragment
              {:groups [{:project "brian"
                         :grouped {:shipping [ship-row]
                                   :triage {:in-flight [] :queued []}
                                   :incoming [] :ready [] :in-progress []}}]
               :selection nil :scope "all" :source :all :facets {}})]
    (is (str/includes? html "⚠ blocked")   "blocked sub-state renders the loud badge")
    (is (str/includes? html "ship-blocked") "loud CSS class is emitted")
    (is (str/includes? html "ship-badge")   "ship-badge wrapper class present")
    (is (str/includes? html "shipping")     "shipping section header is present")
    (is (str/includes? html "sel=brian:ws-ship") "row selection carries the ws id")))

;; ---------------------------------------------------------------------------
;; Generic stage action bar below the reader pane (current entry only)
;; ---------------------------------------------------------------------------

(deftest workstream-pane-renders-stage-actions-for-the-current-entry
  ;; sample-ws is :triage (origin :notion) with a parked "auto" session → Apply / Reply,
  ;; all POSTing to the pane-scoped resolve route (patches #ws-pane). Dismiss is dropped
  ;; for Notion-origin rows — Notion drives the board.
  (let [html (views/workstream-pane (assoc sample-ws :on-latest? true) {})]
    (is (str/includes? html "Apply"))
    (is (not (str/includes? html "Dismiss")) "Notion origin: Dismiss dropped")
    (is (str/includes? html "Send &amp; resume"))                       ; free-text reply
    (is (str/includes? html "/workstreams/brian/ws-1/gate/apply"))
    (is (not (str/includes? html "/workstreams/brian/ws-1/gate/dismiss")))
    (is (str/includes? html "/workstreams/brian/ws-1/gate/reply"))
    (is (not (str/includes? html "/gate/brian/ws-1/apply"))
        "pane actions go through the pane route, not the home gate")))

(deftest workstream-pane-hides-stage-actions-on-older-entries
  (let [html (views/workstream-pane (assoc sample-ws :on-latest? false) {})]
    (is (not (str/includes? html "/workstreams/brian/ws-1/gate/"))
        "an older ledger entry shows no live actions")))

(deftest workstream-pane-stage-actions-vary-by-stage
  ;; :ready → Promote / Drop regardless of parked-ness.
  (let [html (views/workstream-pane
              (assoc sample-ws :stage :ready :sessions [] :on-latest? true) {})]
    (is (str/includes? html "Promote"))
    (is (str/includes? html "/workstreams/brian/ws-1/gate/promote"))
    (is (str/includes? html "/workstreams/brian/ws-1/gate/drop"))
    (is (not (str/includes? html "/gate/apply")) ":ready offers no Apply")))

(deftest workstream-pane-unparked-notion-triage-offers-no-actions
  ;; :triage (origin :notion) with no parked session → Dismiss is dropped for Notion,
  ;; so the action bar renders nothing (Notion drives the board).
  (let [html (views/workstream-pane
              (assoc sample-ws :stage :triage :sessions [] :on-latest? true) {})]
    (is (not (str/includes? html "/workstreams/brian/ws-1/gate/dismiss")))
    (is (not (str/includes? html "/gate/apply")))
    (is (not (str/includes? html "Send &amp; resume")))))

(deftest workstream-pane-unparked-slack-triage-offers-only-dismiss
  ;; :triage (origin :slack) with no parked session → just the off-radar Dismiss,
  ;; no Apply/Reply. Slack rows keep the local Dismiss (nothing else drives them off
  ;; the board).
  (let [html (views/workstream-pane
              (assoc sample-ws :origin :slack :stage :triage :sessions [] :on-latest? true) {})]
    (is (str/includes? html "/workstreams/brian/ws-1/gate/dismiss"))
    (is (not (str/includes? html "/gate/apply")))
    (is (not (str/includes? html "Send &amp; resume")))))

(deftest workstream-pane-renders-incoming-actions
  ;; :incoming has no ledger entries → trivially the current state → Promote/Dismiss.
  (let [html (views/workstream-pane
              {:project "brian" :ws-id "ws-1" :origin :slack :stage :incoming
               :label "can you link it" :ledger nil :report nil :entries nil :sessions []}
              {})]
    (is (str/includes? html "Promote"))
    (is (str/includes? html "Dismiss"))
    (is (str/includes? html "/workstreams/brian/ws-1/gate/promote"))
    (is (str/includes? html "/workstreams/brian/ws-1/gate/drop"))))
