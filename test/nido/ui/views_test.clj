(ns nido.ui.views-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [nido.ui.views :as views]))

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
                                :scope "all"})]
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
  ;; :ready carries a real row here on purpose — ws-stage-sections must drop it
  ;; (backlog lives in Notion, not on the nido board), so the "no ready band"
  ;; assertion below actually exercises that omission instead of passing
  ;; vacuously against an empty/absent key.
  {:triage {:in-flight [{:ws-id "w1" :origin :notion :stage :triage :label "BR-1 · a" :needs-you true}]
            :queued []}
   :ready [{:ws-id "w2" :origin :notion :stage :ready :label "BR-2 · ready row" :needs-you false}]
   :in-progress [{:ws-id "w3" :origin :scratch :stage :in-progress :label "spike" :needs-you false}]})

(def ^:private sample-ws
  {:ws-id "ws-1" :project "brian" :origin :notion :stage :triage :label "BR-7 · t"
   :ledger {:key "BR-7" :status :investigating :report-count 1}
   :sessions [{:name "auto" :autonomy-level :autonomous :parked? true :status :parked :brakes {:budget "30m"}}
              {:name "me"   :autonomy-level :interactive :parked? false :status :up :brakes nil}]})

(deftest workstreams-fragment-groups-and-links
  (let [html (views/workstreams-fragment {:groups [{:project "brian" :grouped sample-grouped}]
                                          :selection nil :scope "all" :tab :intake})]
    (is (str/includes? html "id=\"workstreams\""))
    (is (str/includes? html "triage"))
    (is (not (str/includes? html "ready")) "no :ready band section")
    (is (str/includes? html "BR-1 · a"))
    (is (str/includes? html "sel=brian:w1"))            ; rows carry the selection in the view-state
    (is (str/includes? html ">N<"))))

(deftest screen-query-composes-the-url-contract
  ;; screen-query is the single place scope + tab + selection are serialized —
  ;; it also produces the 5s poll URL (see workstreams-page-poll-carries-tab
  ;; below). Pin its contract directly since the poll URL is easy to silently
  ;; regress without a caller-side test noticing.
  (is (= "" (#'views/screen-query {:scope "all" :tab :intake}))
      "scope=all and the default tab are both omitted")
  (is (= "?scope=brian" (#'views/screen-query {:scope "brian" :tab :intake}))
      "a real scope is present")
  (is (= "?tab=active" (#'views/screen-query {:scope "all" :tab :active}))
      "a non-default tab is present")
  (is (= "?sel=brian:w1" (#'views/screen-query {:scope "all" :tab :intake} {:sel "brian:w1"}))
      ":sel composes with the rest")
  (is (= "?scope=brian&tab=active&sel=brian:w1"
         (#'views/screen-query {:scope "brian" :tab :intake} {:tab :active :sel "brian:w1"}))
      "an explicit :tab override wins over the screen's tab, and all three compose"))

(deftest workstreams-page-renders-both-tabs-with-the-active-one-marked
  (let [html (views/workstreams-page {:active :workstreams :needs-count 0 :daemon {:state :up}
                                      :scope "all" :projects []}
                                     {:scope "all" :tab :intake :selection nil
                                      :groups [{:project "brian" :grouped sample-grouped}]})]
    (is (str/includes? html "Intake"))
    (is (str/includes? html "Active"))
    (is (str/includes? html "tab active") "the current tab is marked")
    (is (str/includes? html "tab=active") "the other tab is one click away")))

(deftest workstreams-fragment-intake-shows-triage-not-in-progress
  (let [html (views/workstreams-fragment {:groups [{:project "brian" :grouped sample-grouped}]
                                          :selection nil :scope "all" :tab :intake})]
    (is (str/includes? html "triage"))
    (is (str/includes? html "BR-1 · a"))
    (is (not (str/includes? html "spike")) "the in-progress row belongs to the Active tab")
    (is (not (str/includes? html "ready")) "no :ready band — the backlog lives in Notion")))

(deftest workstreams-fragment-active-shows-in-progress-not-triage
  (let [html (views/workstreams-fragment {:groups [{:project "brian" :grouped sample-grouped}]
                                          :selection nil :scope "all" :tab :active})]
    (is (str/includes? html "in-progress"))
    (is (str/includes? html "spike") "the scratch session is reachable — the bug this fixes")
    (is (not (str/includes? html "BR-1 · a")) "the triage row belongs to the Intake tab")))

(deftest tab-links-preserve-scope-and-selection
  (let [html (views/workstreams-page {:active :workstreams :needs-count 0 :daemon {:state :up}
                                      :scope "brian" :projects []}
                                     {:scope "brian" :tab :intake
                                      :selection {:project "brian" :ws-id "w1"}
                                      :groups [{:project "brian" :grouped sample-grouped}]})]
    (is (str/includes? html "scope=brian"))
    (is (str/includes? html "sel=brian:w1"))))

(deftest workstreams-fragment-renders-winding-down-rows
  (let [screen {:scope "all" :tab :active :selection nil
                :groups [{:project "p"
                          :grouped {:winding-down
                                    [{:ws-id "w1" :origin :scratch :label "old-one"
                                      :outcome :done :sessions ["s1"] :rss-str "612 MB"}
                                     {:ws-id "w2" :origin :scratch :label "stopping-one"
                                      :outcome :dropped :sessions ["s2"] :pending? true}]}}]}
        html (views/workstreams-fragment screen)]
    (is (str/includes? html "old-one"))
    (is (str/includes? html "612 MB"))
    (is (str/includes? html "/workstreams/p/w1/winddown"))
    (is (str/includes? html "stopping…") "pending row shows progress, no button")
    (is (not (str/includes? html "/workstreams/p/w2/winddown")))))

(deftest winddown-row-post-url-carries-the-screen-query
  ;; Fix 1: the button's POST url must preserve scope + tab (as query params) so
  ;; the winddown route's derive-screen renders the SAME screen the user was on
  ;; instead of defaulting to intake/all/deselected.
  (let [screen {:scope "brian" :tab :active :selection nil
                :groups [{:project "brian"
                          :grouped {:winding-down
                                    [{:ws-id "w1" :origin :scratch :label "old-one"
                                      :outcome :done :sessions ["s1"]}]}}]}
        html (views/workstreams-fragment screen)]
    (is (str/includes? html "/workstreams/brian/w1/winddown?scope=brian&amp;tab=active")
        "the POST url is the exact winddown route plus the preserved screen query")))

(deftest winddown-row-failed-shows-error-and-keeps-the-button
  ;; Fix 3: a failed bring-down! must be visible on the row AND retryable — the
  ;; button stays so clicking it again sets :stopping, self-clearing the error.
  (let [screen {:scope "all" :tab :active :selection nil
                :groups [{:project "p"
                          :grouped {:winding-down
                                    [{:ws-id "w1" :origin :scratch :label "old-one"
                                      :outcome :done :sessions ["s1"]
                                      :error-msg "bring-down! failed: connection refused"}]}}]}
        html (views/workstreams-fragment screen)]
    (is (str/includes? html "bring-down! failed: connection refused"))
    (is (str/includes? html "/workstreams/p/w1/winddown") "the button still POSTs — retry is one click")
    (is (str/includes? html "Bring down"))
    (is (not (str/includes? html "stopping…")))))

(deftest workstreams-fragment-preserves-selection
  ;; a poll refresh keeps the open row highlighted, and each row link preserves
  ;; the view-state so selecting one lands on the SAME list.
  (let [html (views/workstreams-fragment {:groups [{:project "brian" :grouped sample-grouped}]
                                          :selection {:project "brian" :ws-id "w1"}
                                          :scope "all"})]
    (is (str/includes? html "gate-card sel") "selected row keeps its highlight")
    (is (str/includes? html "sel=brian:w1"))
    (is (not (str/includes? html "source=")) "no source filter in row links")))

(deftest workstream-pane-shows-ledger-report-and-environment
  ;; The pane shows the ledger summary + report, and — bound to the resolved
  ;; environment session — its Open-app link. One environment, no session table.
  (let [ws   (assoc sample-ws :environment {:name "auto"}
                    :report {:format :markdown :kind :triage :at "t" :title "Verdict"
                             :markdown "# Verdict\n\nbug — reproduced."})
        html (views/workstream-pane ws {"auto" {:state :running :url "http://auto.brian.localhost:3142"}})]
    (is (str/includes? html "BR-7"))
    (is (str/includes? html "investigating"))            ; ledger summary status
    (is (str/includes? html "bug — reproduced."))        ; report markdown
    (is (str/includes? html "auto"))                     ; environment session named
    (is (str/includes? html "http://auto.brian.localhost:3142")))) ; env Open-app link

(deftest workstream-pane-environment-running-shows-open-and-stop
  (let [ws   (assoc sample-ws :environment {:name "me"})
        html (views/workstream-pane ws {"me" {:state :running :url "http://me.brian.localhost:3142"}})]
    (is (str/includes? html "Environment"))              ; the environment section
    (is (str/includes? html "Open app"))
    (is (str/includes? html "http://me.brian.localhost:3142"))
    (is (str/includes? html "/workstreams/brian/ws-1/sessions/me/dev/stop"))
    (is (str/includes? html "id=\"ws-pane\""))
    (is (str/includes? html "/_fragment/workstream/brian/ws-1"))))  ; pane interval poll

(deftest workstream-pane-environment-down-shows-start
  (let [ws   (assoc sample-ws :environment {:name "me"})
        html (views/workstream-pane ws {"me" {:state :down}})]
    (is (str/includes? html "/workstreams/brian/ws-1/sessions/me/dev/start"))))

(deftest workstream-pane-environment-failed-shows-retry-and-error
  (let [ws   (assoc sample-ws :environment {:name "me"})
        html (views/workstream-pane ws {"me" {:state :failed :error-msg "boom: port never opened"}})]
    (is (str/includes? html "retry"))
    (is (str/includes? html "boom: port never opened"))
    (is (str/includes? html "/workstreams/brian/ws-1/sessions/me/dev/start"))))   ; retry POSTs to start

(deftest workstream-pane-shows-a-failed-gate-action-above-the-buttons
  ;; A gate resolve fails asynchronously, and this 3s-polled pane is what replaces
  ;; the optimistic confirm fragment — so it must carry the reason, or the click
  ;; reads as a no-op.
  (let [html (views/workstream-pane
              (assoc sample-ws :error-msg "Apply failed: http 400") {})]
    (is (str/includes? html "Apply failed: http 400"))
    (is (str/includes? html "action-err"))
    (is (str/includes? html "/workstreams/brian/ws-1/gate/apply")
        "the Apply button stays clickable — the failure is retryable")))

(deftest workstream-pane-hides-a-gate-error-on-a-read-back-entry
  (let [html (views/workstream-pane
              (assoc sample-ws :error-msg "Apply failed: http 400" :on-latest? false) {})]
    (is (not (str/includes? html "Apply failed: http 400"))
        "older ledger entries are read-back — no live action, so no live error")))

(deftest workstream-pane-no-dev-env-card
  ;; the standalone weight-gated card is gone
  (let [html (views/workstream-pane sample-ws {})]
    (is (not (str/includes? html "No dev environment yet")))))

(deftest workstream-pane-empty-when-nil
  (is (str/includes? (views/workstream-pane nil nil) "Select a workstream")))

(deftest workstream-pane-environment-shows-machine-facts
  (let [html (views/workstream-pane
              {:project "p" :ws-id "w" :origin :scratch :stage :in-progress :label "L"
               :environment {:name "s1" :weight :heavy}
               :sessions [{:name "s1" :autonomy-level :interactive :parked? false :status :up}]}
              {"s1" {:state :running :url "http://localhost:3101"}}
              {"s1" {:pg-port 5501 :nrepl-port 7001 :app-port 3101
                     :repl-rss (* 512 1024 1024) :pg-rss (* 100 1024 1024) :heap-max "2g"}})]
    (is (str/includes? html "5501"))
    (is (str/includes? html "7001"))
    (is (str/includes? html "3101"))
    (is (str/includes? html "max 2g"))
    (is (str/includes? html "restart"))))

(deftest pane-renders-single-environment-block
  (let [ws  {:project "brian" :ws-id "ws-1" :origin :notion :stage :in-progress
             :label "BR-1 · x" :environment {:name "impl-br-1" :weight :heavy}
             :sessions [{:name "impl-br-1" :autonomy-level :interactive}]}
        html (views/workstream-pane ws
                                    {"impl-br-1" {:state :running :url "http://localhost:3100"}}
                                    {"impl-br-1" {:app-port 3100 :pg-port 5500 :nrepl-port 6100}})]
    (is (str/includes? html "Environment") "an Environment section, not Sessions")
    (is (not (str/includes? html "<table")) "no session table")
    (is (str/includes? html "http://localhost:3100") "the environment URL is shown")))

(deftest pane-environment-empty-state
  (let [ws  {:project "brian" :ws-id "ws-1" :origin :notion :stage :triage
             :label "BR-1 · x" :environment nil :sessions []}
        html (views/workstream-pane ws {} {})]
    (is (str/includes? html "no runnable version") "empty state when no environment")))

(deftest workstreams-page-has-shell-and-poll
  (let [html (views/workstreams-page {:active :workstreams :needs-count 0 :daemon {:state :up}
                                      :scope "all" :projects []}
                                     {:scope "all" :selection nil
                                      :groups [{:project "brian" :grouped sample-grouped}]})]
    (is (str/includes? html "rail-link active"))
    (is (str/includes? html "/_fragment/workstreams"))))

(deftest workstreams-page-poll-carries-tab
  ;; The 5s poll's @get URL is built by screen-query too (see workstreams-page,
  ;; the .inbox data-on-interval). Assert on the @get(...) substring itself —
  ;; not merely that "tab=active" appears somewhere in the page, which the tab
  ;; link alone would satisfy — so a regression that drops the tab from the
  ;; poll (but not the link) fails here.
  (let [html (views/workstreams-page {:active :workstreams :needs-count 0 :daemon {:state :up}
                                      :scope "all" :projects []}
                                     {:scope "all" :tab :active :selection nil
                                      :groups [{:project "brian" :grouped sample-grouped}]})]
    (is (str/includes? html "@get(&apos;/_fragment/workstreams?tab=active&apos;)")
        "hiccup escapes the quote to &apos; in the rendered attribute")))

(deftest workstreams-page-renders-no-filter-chrome
  ;; The source + facet chips are gone: no filter row, no Source label. The
  ;; :scratch in-progress row is no longer hidden behind source=notion — it's
  ;; reachable via the Active tab (see workstreams-fragment-active-shows-in-progress-not-triage);
  ;; the default landing is the Intake tab, which doesn't include it.
  (let [html (views/workstreams-page {:active :workstreams :needs-count 0 :daemon {:state :up}
                                      :scope "all" :projects []}
                                     {:scope "all" :selection nil
                                      :groups [{:project "brian" :grouped sample-grouped}]})]
    (is (not (str/includes? html "filter-row")))
    (is (not (str/includes? html "filter-label")))
    (is (not (str/includes? html ">Source<")))))

;; ---------------------------------------------------------------------------
;; Ops panel — ambient chrome behind the rail health dot
;; ---------------------------------------------------------------------------

(deftest ops-panel-renders-daemon-halt-breakers-and-fire
  (let [html (views/ops-panel-fragment
              {:daemon   {:state :breaker :heartbeat-at "2026-07-21T10:00:00Z"}
               :halt     nil
               :breakers [{:project :brian :trigger :triage-new
                           :info {:consecutive-failures 3}}]
               :triggers {:brian [{:name :one-off :payload "{}"}
                                  {:name :with-args :payload "{\"u\":\"{{event/url}}\"}"}
                                  {:name :two-part-name :payload "{\"u\":\"{{event/page-url}}\"}"}]}})]
    (is (str/includes? html "id=\"ops-panel\""))
    (is (str/includes? html "/ops/halt"))
    (is (str/includes? html "/ops/breakers/brian/triage-new/clear"))
    (is (str/includes? html "/ops/fire/brian/one-off"))
    (is (str/includes? html "/ops/fire/brian/with-args"))
    ;; hyphenated trigger/placeholder names must sanitize to JS-identifier-safe
    ;; signal names — the ONE place that mapping is built (views/fire-signal).
    (is (str/includes? html "fire_two_part_name_page_url"))))

(deftest rail-health-toggles-the-ops-panel
  (let [html (str (h/html (#'views/rail-health {:state :up})))]
    (is (str/includes? html "$opsOpen"))))

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
    ;; both destinations always present (no per-page drift)
    (is (str/includes? html "Needs you"))
    (is (str/includes? html "Workstreams"))
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

(deftest shell-ops-poll-carries-the-active-scope
  ;; Fix 2: the ops mount's @get must carry the current scope so a scoped
  ;; badge count isn't periodically clobbered by the unscoped total.
  (let [scoped   (views/shell {:active :workstreams :title "t" :needs-count 0
                               :daemon {:state :up} :scope "brian" :projects []}
                              [:p "body"])
        unscoped (views/shell {:active :workstreams :title "t" :needs-count 0
                               :daemon {:state :up} :scope "all" :projects []}
                              [:p "body"])]
    (is (str/includes? scoped "@get(&apos;/_fragment/ops?scope=brian&apos;)"))
    (is (str/includes? unscoped "@get(&apos;/_fragment/ops&apos;)")
        "\"all\" scope omits the query param, matching the pre-fix url")
    (is (not (str/includes? unscoped "scope=")))))

(deftest rail-status-fragment-has-both-live-bits
  (let [html (views/rail-status-fragment {:needs-count 2 :daemon {:state :halted}})]
    (is (str/includes? html "id=\"rail-needs-count\""))
    (is (str/includes? html ">2<"))
    (is (str/includes? html "id=\"rail-health\""))
    (is (str/includes? html "dot-halted"))))

(deftest rail-scope-link-stays-on-current-surface
  (let [html (str (h/html (#'views/rail {:active :workstreams :scope "all" :needs-count 0
                                         :daemon {:state :up} :projects ["brian"] :tab nil})))]
    (is (re-find #"/workstreams\?scope=brian" html) "a scope link stays on the current (workstreams) surface")
    (is (not (re-find #"href=\"/\?scope=brian\"" html)) "scope link does NOT jump to the home surface")))

(deftest rail-surface-link-carries-current-scope
  (let [html (str (h/html (#'views/rail {:active :needs :scope "brian" :needs-count 0
                                         :daemon {:state :up} :projects ["brian"] :tab nil})))]
    (is (re-find #"/workstreams\?scope=brian" html) "the Workstreams surface link carries the current scope")
    (is (re-find #"href=\"/\?scope=brian\"" html) "the Needs-you surface link carries the current scope")))

(deftest rail-scope-link-preserves-workstreams-tab
  (let [html (str (h/html (#'views/rail {:active :workstreams :scope "brian" :tab :active
                                         :needs-count 0 :daemon {:state :up} :projects ["brian"]})))]
    ;; hiccup escapes "&" to "&amp;" inside a rendered attribute value (same quirk
    ;; documented in workstreams-page-poll-carries-tab for &apos;).
    (is (re-find #"/workstreams\?scope=brian&amp;tab=active" html)
        "on workstreams, a scope link preserves the active tab")))

(def ^:private ledger-entries
  [{:seq 2 :kind :impl   :at "2026-06-19T00:00:00Z" :title "Draft PR"}
   {:seq 1 :kind :triage :at "2026-06-18T00:00:00Z" :title "Verdict"}])

(deftest workstream-pane-shows-the-ledger-with-nothing-open
  ;; The pane's resting state: the ledger, whole, and no viewer. work/workstream
  ;; hands over no :report unless the reader asked for one.
  (let [html (views/workstream-pane (assoc sample-ws :entries ledger-entries) {})]
    (is (str/includes? html "ledger-index"))
    (is (str/includes? html "Draft PR"))
    (is (str/includes? html "Verdict"))
    (is (str/includes? html "?entry=1") "every row is a click target…")
    (is (str/includes? html "?entry=2") "…including the newest")
    (is (not (str/includes? html "ledger-row sel")) "no row is highlighted")
    (is (not (str/includes? html "viewer-bar")) "and no viewer is open")))

(deftest workstream-pane-indexes-a-single-entry-ledger-too
  ;; Nothing opens itself, so the index is the only way in — skipping it for a
  ;; one-entry ledger would strand that entry.
  (let [html (views/workstream-pane
              (assoc sample-ws :entries [{:seq 1 :kind :impl :at "t" :title "Solo"}]) {})]
    (is (str/includes? html "ledger-index"))
    (is (str/includes? html "Solo"))))

(deftest workstream-pane-opens-a-report-in-a-closable-viewer
  (let [ws   (assoc sample-ws
                    :selected-seq 2
                    :entries ledger-entries
                    :report {:format :markdown :kind :impl :at "t" :title "Draft PR"
                             :markdown "# Draft PR\n\nopened it."})
        html (views/workstream-pane ws {})]
    (is (str/includes? html "ledger-index") "the ledger stays put under the viewer")
    (is (str/includes? html "ledger-row sel") "seq 2 highlighted")
    (is (str/includes? html "viewer-bar"))
    (is (str/includes? html "opened it.") "the open report's body")
    (is (str/includes? html "viewer-close"))
    (is (str/includes? html "@get(&apos;/_fragment/workstream/brian/ws-1&apos;)")
        "closing @gets the pane with no ?entry — back to the ledger alone")))

(deftest workstream-pane-poll-url-carries-the-reading-position
  (let [ws   (assoc sample-ws :selected-seq 2 :open-rounds #{3 1} :entries ledger-entries)
        html (views/workstream-pane ws {})]
    (is (str/includes? html "/_fragment/workstream/brian/ws-1?entry=2&amp;rounds=1,3")
        "the 3s self-poll re-requests the reader's exact position, so the refresh
         lands them back on the entry they opened with the rounds they unfolded")))

(deftest workstream-pane-at-rest-polls-the-bare-url
  (let [html (views/workstream-pane (assoc sample-ws :entries ledger-entries) {})]
    (is (str/includes? html
                       "data-on-interval__duration.3s=\"@get(&apos;/_fragment/workstream/brian/ws-1&apos;)\"")
        "nothing open → nothing in the poll's query string")))

(deftest workstream-pane-renders-implementation-plan-card
  (let [ws   (assoc sample-ws :report {:format :implementation-plan :summary "Round on the total."
                                       :direction "Round once on the order total" :effort :M
                                       :steps ["add a render test" "fix the calc"]})
        html (views/workstream-pane ws {})]
    (is (str/includes? html "Implementation plan"))
    (is (str/includes? html "Round once on the order total"))
    (is (str/includes? html "Steps"))
    (is (str/includes? html "fix the calc"))))

(def ^:private review-report
  "A :review ledger event as work/hydrate hands it to the view: the verdict + counts
   the event carries, plus the :detail read back from the report.json it points at."
  {:format :review-report :at "2026-08-10T12:00:00Z" :status :converged
   :base "main" :base-rev "be771a41f567abcdef" :rounds 2 :findings-fixed 1
   :findings-remaining 0 :report-path "/runs/review-1/report.json"
   :detail {:target {:cwd "/w" :base "main" :files ["src/a.clj" "src/b.clj"]}
            :rounds [{:round 1 :status "continued"
                      :phases [{:phase "review" :status "ok"
                                :overall-correctness "incorrect"
                                :findings [{:title "[P1] Backfill races the deploy"
                                            :body "the one-shot update runs early"
                                            :priority 1 :file "/w/src/a.clj"
                                            :line-start 32 :line-end 35}]}
                               {:phase "judge" :status "ok" :decision "continue"
                                :reason "a real correctness risk" :fix-findings [0]}
                               {:phase "fix" :status "ok" :commit "553c4779beef"
                                :fixed-count 1}]}
                     {:round 2 :status "clean"
                      :phases [{:phase "review" :status "ok"
                                :overall-correctness "correct" :findings []}
                               {:phase "judge" :status "ok" :decision "stop"}]}]}})

(defn- review-pane
  "The pane with `review-report` open at ledger entry 7, `rounds` unfolded."
  [rounds]
  (views/workstream-pane (assoc sample-ws :report review-report :selected-seq 7
                                :open-rounds rounds
                                :entries [{:seq 7 :kind :review :at "t" :title "Review"}])
                         {}))

(deftest workstream-pane-renders-review-rounds-folded
  (let [html (review-pane nil)]
    (is (str/includes? html "converged") "the verdict chip")
    (is (str/includes? html "2 rounds · 1 fixed · 0 remaining"))
    (is (str/includes? html "be771a41f567") "base-rev, abbreviated")
    (is (str/includes? html "2 files changed") "target file count from the report")
    (is (str/includes? html "Round 1"))
    (is (str/includes? html "Round 2"))
    ;; a folded round is its shape: what it found, and how it came out
    (is (str/includes? html "Backfill races the deploy") "the finding's title")
    (is (not (str/includes? html "[P1] Backfill"))
        "the priority prefix codex repeats in the title is dropped — the chip says it")
    (is (str/includes? html "1 finding · incorrect · → continue (fix 0) · commit 553c4779 · 1 fixed")
        "the round's phases, summarised on one line")
    ;; …and not its argument
    (is (not (str/includes? html "the one-shot update runs early")) "no finding body")
    (is (not (str/includes? html "a real correctness risk")) "no judge reasoning")
    (is (not (str/includes? html "src/a.clj:32-35")) "no locations")
    (is (str/includes? html "?entry=7&amp;rounds=1") "clicking Round 1 unfolds it")
    (is (str/includes? html "/runs/review-1/report.json") "where the full report lives")))

(deftest workstream-pane-unfolds-the-rounds-the-reader-asked-for
  (let [html (review-pane #{1})]
    (is (str/includes? html "src/a.clj:32-35") "location, relative to the reviewed worktree")
    (is (str/includes? html "the one-shot update runs early") "the finding body")
    (is (str/includes? html "a real correctness risk") "the judge's reasoning")
    (is (str/includes? html "→ continue (fix 0)"))
    (is (str/includes? html "commit 553c4779"))
    (is (str/includes? html "?entry=7&amp;rounds=1,2")
        "round 2 is still folded — clicking it ADDS it, leaving round 1 open")
    (is (str/includes? html "@get(&apos;/_fragment/workstream/brian/ws-1?entry=7&apos;)")
        "and clicking round 1 again folds it back, leaving the entry open")))

(deftest gate-pane-renders-review-rounds-unfolded
  ;; The gate pane has no reading position to navigate, so there is nothing to
  ;; click — a folded round there would hide its detail with no way to reach it.
  (let [html (views/gate-pane {:ws-id "ws-1" :project "brian" :origin :notion
                               :label "BR-7" :report review-report :actions []})]
    (is (str/includes? html "the one-shot update runs early") "bodies render")
    (is (str/includes? html "a real correctness risk") "reasoning renders")
    (is (not (str/includes? html "rv-foldable")) "and no round offers a fold")))

(deftest workstream-pane-review-without-detail-still-shows-the-verdict
  ;; Run dirs get cleaned; the event's own counts must still render, and the pane
  ;; must say why there are no rounds rather than going blank.
  (let [html (views/workstream-pane
              (assoc sample-ws :report (dissoc review-report :detail)) {})]
    (is (str/includes? html "converged"))
    (is (str/includes? html "2 rounds · 1 fixed · 0 remaining"))
    (is (str/includes? html "no longer on disk"))
    (is (not (str/includes? html "Round 1")))))

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
               :selection nil :scope "all" :tab :active})]
    (is (str/includes? html "⚠ blocked")   "blocked sub-state renders the loud badge")
    (is (str/includes? html "ship-blocked") "loud CSS class is emitted")
    (is (str/includes? html "ship-badge")   "ship-badge wrapper class present")
    (is (str/includes? html "shipping")     "shipping section header is present")
    (is (str/includes? html "sel=brian:ws-ship") "row selection carries the ws id")))

;; ---------------------------------------------------------------------------
;; Generic stage action bar below the reader pane (current entry only)
;; ---------------------------------------------------------------------------

(deftest workstream-pane-renders-stage-actions-for-the-current-entry
  ;; sample-ws is :triage (origin :notion) with a parked "auto" session → Apply /
  ;; Dismiss / Reply, all POSTing to the pane-scoped resolve route (patches #ws-pane).
  ;; Dismiss is offered for Notion-origin rows same as any other origin.
  (let [html (views/workstream-pane (assoc sample-ws :on-latest? true) {})]
    (is (str/includes? html "Apply"))
    (is (str/includes? html "Dismiss") "Notion origin: Dismiss offered")
    (is (str/includes? html "Send &amp; resume"))                       ; free-text reply
    (is (str/includes? html "/workstreams/brian/ws-1/gate/apply"))
    (is (str/includes? html "/workstreams/brian/ws-1/gate/dismiss"))
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

(deftest workstream-pane-unparked-notion-triage-offers-only-dismiss
  ;; :triage (origin :notion) with no parked session → just the off-radar Dismiss,
  ;; same as any other origin — Notion no longer fences it off.
  (let [html (views/workstream-pane
              (assoc sample-ws :stage :triage :sessions [] :on-latest? true) {})]
    (is (str/includes? html "/workstreams/brian/ws-1/gate/dismiss"))
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

;; ---------------------------------------------------------------------------
;; Dismissed band (Intake tab) — muted rows, collapsed by default, Restore
;; ---------------------------------------------------------------------------

(deftest dismissed-band-renders-a-restore-button
  (let [screen {:scope "all" :tab :intake :selection nil
                :groups [{:project "brian"
                          :grouped {:dismissed [{:ws-id "ws-9" :origin :notion
                                                 :label "BR-5711 · New Bug (Description)"
                                                 :last-activity "2026-08-14T09:00:00Z"}]}}]}
        html   (views/workstreams-fragment screen)]
    (is (str/includes? html "Restore"))
    (is (str/includes? html "/workstreams/brian/ws-9/gate/restore")
        "posts to the generic pane gate route")
    (is (str/includes? html "BR-5711 · New Bug (Description)"))
    (is (str/includes? html "gate-card dismissed")
        "muted card class")))

(deftest confirm-fragment-covers-dismiss-and-restore
  (is (str/includes? (views/gate-action-confirm-fragment :restore "brian" "ws-9")
                     "Restored"))
  ;; Pin the safety claim itself, not the word "Dismissed" — the OLD copy already
  ;; contained that, so asserting it tested nothing. This sentence is the
  ;; user-facing evidence for the branch's entire guarantee.
  (is (str/includes? (views/gate-action-confirm-fragment :dismiss "brian" "ws-9")
                     "Nothing written to Notion")))

;; ---------------------------------------------------------------------------
;; Bare pane — a watched Notion page with no workstream behind it
;; ---------------------------------------------------------------------------

(def ^:private bare-ws
  {:project "brian" :ws-id "pg-bare" :origin :notion :bare? true
   :stage :triage :label "Move Licences to Brian from Attio"
   :br-id "BR-5569" :notion-status "Needs verification"
   :ledger nil :entries nil :report nil :environment nil :sessions []
   :on-latest? true})

(deftest bare-pane-explains-itself-and-offers-both-actions
  (let [html (views/workstream-pane bare-ws {})]
    (is (str/includes? html "Move Licences to Brian from Attio"))
    (is (str/includes? html "BR-5569"))
    (is (str/includes? html "Needs verification"))
    (is (str/includes? html "No nido workstream yet"))
    (is (str/includes? html "Start triage"))
    (is (str/includes? html "/workstreams/brian/pg-bare/gate/start-triage"))
    (is (str/includes? html "Dismiss"))
    (is (str/includes? html "/workstreams/brian/pg-bare/gate/dismiss"))))

(deftest bare-pane-renders-no-ledger-or-environment
  (let [html (views/workstream-pane bare-ws {})]
    (is (not (str/includes? html "Environment"))
        "no session behind it, so no environment block")
    (is (not (str/includes? html "report(s)")) "no ledger summary")
    (is (not (str/includes? html "Send &amp; resume")) "no agent to reply to")
    (is (not (str/includes? html "Apply")) "nothing has been triaged to apply")))

(deftest bare-pane-keeps-polling-and-shows-a-failed-action
  (let [html (views/workstream-pane (assoc bare-ws :error-msg "No triage trigger") {})]
    (is (str/includes? html "/_fragment/workstream/brian/pg-bare")
        "keeps the 3s poll — it carries errors back and upgrades the pane")
    (is (str/includes? html "No triage trigger"))))

(deftest bare-pane-does-not-shadow-the-empty-placeholder
  (is (str/includes? (views/workstream-pane nil nil) "Select a workstream")))

;; Regression test for the review finding: a bare row's :stage is not always
;; :triage (session/notion-stage yields :done/:in-progress/:ready/:triage), and
;; the card used to claim "Start triage spawns the triage agent now" regardless
;; of stage — with zero buttons beneath it on every stage but :triage. The copy
;; must follow the actual computed action set, never promise one it can't offer.
(deftest bare-pane-in-progress-has-no-actions-and-says-so
  (let [html (views/workstream-pane (assoc bare-ws :stage :in-progress) {})]
    (is (not (str/includes? html "/workstreams/brian/pg-bare/gate/start-triage"))
        "no agent to start — the triage already ran, Notion just says in-progress")
    (is (not (str/includes? html "/workstreams/brian/pg-bare/gate/dismiss")))
    (is (not (str/includes? html "Start triage spawns the triage agent now"))
        "must not promise an action the pane offers no button for")
    (is (str/includes? html "Nothing to do from here")
        "an empty action set must say so explicitly, not leave the card silent")))

(deftest bare-pane-ready-offers-no-lying-promote-or-drop
  (let [html (views/workstream-pane (assoc bare-ws :stage :ready) {})]
    (is (not (str/includes? html "/workstreams/brian/pg-bare/gate/promote"))
        "Promote would only ever no-op — a bare row has no workstream to promote")
    (is (not (str/includes? html "/workstreams/brian/pg-bare/gate/drop")))
    (is (not (str/includes? html "/workstreams/brian/pg-bare/gate/start-triage")))
    (is (str/includes? html "Nothing to do from here"))))

;; :done is squarely inside session/notion-stage's range too — same "nothing to
;; do" shape as :in-progress: empty action set, the explicit line, no routes.
(deftest bare-pane-done-has-no-actions-and-says-so
  (let [html (views/workstream-pane (assoc bare-ws :stage :done) {})]
    (is (not (str/includes? html "/workstreams/brian/pg-bare/gate/start-triage")))
    (is (not (str/includes? html "/workstreams/brian/pg-bare/gate/dismiss")))
    (is (str/includes? html "Nothing to do from here"))))

;; Fix: the pane used to gate "Nothing to do from here" on whether the action
;; set contained :start-triage, which was only ever safe while :dismissed was
;; unreachable as a bare stage. Now that a dismissed bare row's action set is
;; [:restore] (Fix 1), that proxy would render a live Restore button underneath
;; a card that claims there is nothing to do.
(deftest bare-pane-dismissed-offers-restore-not-nothing-to-do
  (let [html (views/workstream-pane (assoc bare-ws :stage :dismissed) {})]
    (is (str/includes? html "/workstreams/brian/pg-bare/gate/restore")
        "a dismissed bare row's Restore is real and reachable")
    (is (not (str/includes? html "Nothing to do from here"))
        "there IS something to do — Restore — so the card must not claim otherwise")))

(deftest confirm-fragment-covers-start-triage
  (is (str/includes? (views/gate-action-confirm-fragment :start-triage "brian" "pg-bare")
                     "Starting triage")))

;; ---------------------------------------------------------------------------
;; Followable external links
;; ---------------------------------------------------------------------------

(def ^:private sample-links
  [{:adapter :notion :label "notion" :id "BR-7" :title "checkout off by a cent"
    :url "https://app.notion.com/p/checkout-abc"}
   {:adapter :github :label "PR" :id "o/r#12" :title "fix rounding"
    :url "https://github.com/o/r/pull/12"}])

(deftest gate-pane-lists-external-refs
  (let [html (views/gate-pane (assoc sample-gate :links sample-links))]
    (is (str/includes? html "href=\"https://app.notion.com/p/checkout-abc\""))
    (is (str/includes? html "href=\"https://github.com/o/r/pull/12\""))
    (is (str/includes? html "target=\"_blank\""))
    (is (str/includes? html "o/r#12"))
    (is (str/includes? html "link-k"))))

(deftest gate-pane-heading-links-to-the-primary-ref
  ;; the primary (first) ref's url appears twice: on the heading label and in the links row
  (let [html (views/gate-pane (assoc sample-gate :links sample-links))]
    (is (= 2 (count (re-seq #"https://app\.notion\.com/p/checkout-abc" html))))))

(deftest gate-pane-without-links-renders-a-plain-heading
  ;; ↗ is emitted only by pane-heading and links-row, and gate-pane renders no
  ;; environment block — so its absence proves both stayed plain.
  (doseq [[what gate] [["empty links" (assoc sample-gate :links [])]
                       ["absent links" sample-gate]]]
    (let [html (views/gate-pane gate)]
      (is (not (str/includes? html "link-k")) (str what ": no links row"))
      (is (not (str/includes? html "↗")) (str what ": heading label is not an anchor")))))

(deftest workstream-pane-lists-external-refs
  (let [html (views/workstream-pane (assoc sample-ws :links sample-links) {})]
    (is (str/includes? html "href=\"https://app.notion.com/p/checkout-abc\""))
    (is (str/includes? html "href=\"https://github.com/o/r/pull/12\""))
    (is (str/includes? html "link-k"))))

(deftest workstream-pane-heading-links-to-the-primary-ref
  (let [html (views/workstream-pane (assoc sample-ws :links sample-links) {})]
    (is (= 2 (count (re-seq #"https://app\.notion\.com/p/checkout-abc" html))))))

(deftest workstream-pane-without-links-renders-a-plain-heading
  ;; sample-ws carries no :environment, so the "Open app ↗" button is not rendered
  ;; either — ↗ is again unique to pane-heading and links-row here.
  (doseq [[what ws] [["empty links" (assoc sample-ws :links [])]
                     ["absent links" sample-ws]]]
    (let [html (views/workstream-pane ws {})]
      (is (not (str/includes? html "link-k")) (str what ": no links row"))
      (is (not (str/includes? html "↗")) (str what ": heading label is not an anchor")))))
