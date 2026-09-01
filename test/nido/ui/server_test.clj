(ns nido.ui.server-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [nido.ui.server :as server]
            [nido.ui.views :as views]
            [nido.coordinator.control]
            [nido.ui.dev :as dev]
            [nido.session.engine]
            [nido.session.lifecycle :as lifecycle]
            [nido.session.state]
            [nido.platform.process]
            [nido.coordinator.work]
            [nido.platform.project :as project]))

(deftest slow-request-log-logs-only-what-crosses-the-threshold
  ;; The handler is timed, not stubbed with a fake clock: the threshold is read
  ;; from the environment at load, so the only way to assert on it in-process is
  ;; to be genuinely slower than it. Kept at 30ms and one call each way.
  (let [slow (server/wrap-slow-request-log (fn [_] (Thread/sleep 60) {:status 200}))
        fast (server/wrap-slow-request-log (fn [_] {:status 200}))
        line #(with-out-str (%))]
    (with-redefs [server/slow-request-ms 30]
      (let [quiet (line #(fast {:request-method :get :uri "/fast"}))
            loud  (line #(slow {:request-method :get :uri "/workstreams"
                                :query-string "sel=brian:ws-1"}))]
        (is (= "" quiet) "a request under the threshold leaves no line")
        (is (str/includes? loud "/workstreams?sel=brian:ws-1")
            "the line names the path AND the query, which is what identifies the screen")
        (is (str/includes? loud "load=")
            "the line carries the load average — the fact that tells a slow render apart
             from a busy machine")))))

(deftest slow-request-log-still-rethrows
  ;; A handler that throws after a long stall is exactly the event worth a line;
  ;; logging must not turn it into a 200 or swallow the cause.
  (let [boom (server/wrap-slow-request-log
              (fn [_] (Thread/sleep 60) (throw (ex-info "kaboom" {}))))]
    (with-redefs [server/slow-request-ms 30]
      (let [out (java.io.StringWriter.)]
        (binding [*out* out]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"kaboom"
                                (boom {:request-method :post :uri "/gate/x"}))))
        (is (str/includes? (str out) "/gate/x") "the failing request is logged too")))))

(deftest system-redirects-to-workstreams
  (let [resp (server/handle-request {:request-method :get :uri "/system"})]
    (is (= 302 (:status resp)))
    (is (= "/workstreams" (get-in resp [:headers "Location"])))))

(deftest home-route-renders-needs-page
  (with-redefs [nido.coordinator.work/all-gates (fn [] [])
                nido.coordinator.work/all-grouped (fn [] [])
                nido.ui.dev/pending-resolve-keys (fn [] #{})
                project/list-projects (fn [] {"brian" {:directory "/x"}})
                nido.ui.server/read-rail-daemon (fn [] {:state :up})]
    (let [resp (server/handle-request {:request-method :get :uri "/"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "Needs you")))))

(deftest needs-fragment-route-is-sse-and-patches-rail
  (with-redefs [nido.coordinator.work/all-gates (fn [] [])
                nido.coordinator.work/all-grouped (fn [] [])
                nido.ui.dev/pending-resolve-keys (fn [] #{})
                project/list-projects (fn [] {"brian" {:directory "/x"}})
                nido.ui.server/read-rail-daemon (fn [] {:state :up})]
    (let [resp (server/handle-request {:request-method :get :uri "/_fragment/needs"})]
      (is (str/includes? (get-in resp [:headers "Content-Type"]) "text/event-stream"))
      (is (str/includes? (:body resp) "rail-needs-count")))))   ; rail patched too

(deftest gate-pane-route-renders                ; legacy URI /gate/… → needs surface, gate selected
  (let [g {:ws-id "ws-1" :project "brian" :origin :notion :stage :triage
           :label "BR-7" :report nil :actions [] :session nil}]
    (with-redefs [nido.coordinator.work/all-gates (fn [] [g])
                  nido.coordinator.work/all-grouped (fn [] [])
                  nido.ui.dev/pending-resolve-keys (fn [] #{})
                  project/list-projects (fn [] {"brian" {:directory "/x"}})
                  nido.ui.server/read-rail-daemon (fn [] {:state :up})]
      (let [resp (server/handle-request {:request-method :get :uri "/gate/brian/ws-1"})]
        (is (= 200 (:status resp)))
        (is (str/includes? (:body resp) "BR-7"))))))

(deftest post-gate-mutation-calls-resolve-and-returns-sse
  (let [calls (atom [])]
    (with-redefs [nido.coordinator.work/resolve-gate! (fn [p w a & [in]] (swap! calls conj [p w a in]) {:decision :dropped})
                  nido.coordinator.work/all-gates    (fn [] [])]
      (let [resp (server/handle-request {:request-method :post :uri "/gate/brian/ws-1/skip"})]
        (Thread/sleep 50)   ; resolve runs on a background future
        (is (= [["brian" "ws-1" :skip nil]] @calls))
        (is (str/includes? (get-in resp [:headers "Content-Type"]) "text/event-stream"))))))

(deftest post-gate-apply-resolves-with-no-body
  (let [calls (atom [])]
    (with-redefs [nido.coordinator.work/resolve-gate! (fn [p w a & [in]] (swap! calls conj [p w a in]) {:resumed "auto"})
                  nido.coordinator.work/all-gates    (fn [] [])]
      (let [resp (server/handle-request {:request-method :post :uri "/gate/brian/ws-1/apply"})]
        (Thread/sleep 50)   ; resolve runs on a background future
        (is (= 200 (:status resp)))
        (is (= [["brian" "ws-1" :apply nil]] @calls)
            "apply posts with no input — resolve-gate! supplies the canned verb")))))

(deftest post-gate-reply-passes-input-from-body
  (let [calls (atom [])]
    (with-redefs [nido.coordinator.work/resolve-gate! (fn [p w a & [in]] (swap! calls conj [p w a in]) {:resumed "auto"})
                  nido.coordinator.work/all-gates    (fn [] [])]
      (let [body (java.io.ByteArrayInputStream. (.getBytes "{\"reply\":\"do the fix\"}"))]
        (server/handle-request {:request-method :post :uri "/gate/brian/ws-1/reply" :body body})
        (Thread/sleep 50)
        (is (= [["brian" "ws-1" :reply "do the fix"]] @calls))))))

(deftest post-gate-option-passes-the-rendered-entry-position
  (let [calls (atom [])]
    (with-redefs [nido.coordinator.work/resolve-gate! (fn [p w a & [in]] (swap! calls conj [p w a in]) {:resumed "auto"})
                  nido.coordinator.work/all-gates    (fn [] [])]
      (server/handle-request {:request-method :post :uri "/gate/brian/ws-1/option-a"
                              :query-string "entry=4"})
      ;; ws-2, not ws-1: gate-resolve!'s in-flight guard is keyed per workstream
      ;; and would drop a second click landing while the first is still resolving.
      (server/handle-request {:request-method :post
                              :uri "/workstreams/brian/ws-2/gate/option-b"
                              :query-string "entry=4"})
      (Thread/sleep 50)
      (is (= [["brian" "ws-1" :option-a 4] ["brian" "ws-2" :option-b 4]] @calls)
          "both gate routes forward the position the button was rendered at —
           resolve-gate! refuses a letter whose report the ledger has moved past"))))

(deftest post-gate-reply-returns-resuming-pane
  (with-redefs [nido.coordinator.work/resolve-gate! (fn [& _] {:resumed "auto"})]
    (let [body (java.io.ByteArrayInputStream. (.getBytes "{\"reply\":\"apply\"}"))
          resp (server/handle-request {:request-method :post :uri "/gate/brian/ws-1/reply" :body body})]
      (Thread/sleep 50)
      (is (str/includes? (:body resp) "Resuming"))
      (is (str/includes? (:body resp) "gate-pane")))))

(deftest workstreams-route-renders
  (with-redefs [nido.coordinator.work/grouped (fn [_] {:triage {:in-flight [] :queued []} :ready [] :in-progress [] :incoming []})
                project/list-projects (fn [] {"brian" {:directory "/x"}})
                nido.coordinator.work/all-gates (fn [] [])
                nido.ui.server/read-rail-daemon (fn [] {:state :up})]
    (let [resp (server/handle-request {:request-method :get :uri "/workstreams"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "Workstreams")))))

(deftest workstreams-route-renders-the-requested-tab-end-to-end
  ;; parse → derive-screen → views: ?tab= flows through with no route of its own.
  ;; Same four stubs the deleted source/facet end-to-end test used.
  (with-redefs [nido.coordinator.work/all-grouped
                (fn [] [{:project "brian"
                         :grouped {:incoming []
                                   :triage {:in-flight [{:ws-id "t" :origin :notion :stage :triage
                                                         :label "Triage-row"}]
                                            :queued []}
                                   :in-progress [{:ws-id "p" :origin :scratch :stage :in-progress
                                                  :label "Scratch-row"}]
                                   :shipping []}}])
                nido.coordinator.work/all-gates (fn [] [])
                project/list-projects (fn [] {"brian" {:directory "/x"}})
                nido.ui.server/read-rail-daemon (fn [] {:state :up})]
    (let [intake (:body (server/handle-request {:request-method :get :uri "/workstreams"}))
          active (:body (server/handle-request {:request-method :get :uri "/workstreams"
                                                :query-string "tab=active"}))]
      (is (str/includes? intake "Triage-row"))
      (is (not (str/includes? intake "Scratch-row")))
      (is (str/includes? active "Scratch-row") "the scratch row is reachable — the bug this fixes")
      (is (not (str/includes? active "Triage-row"))))))

(deftest workstream-pane-route-renders
  (with-redefs [nido.coordinator.work/grouped (fn [_] {:triage {:in-flight [] :queued []} :ready [] :in-progress [] :incoming []})
                project/list-projects (fn [] {"brian" {:directory "/x"}})
                nido.coordinator.work/all-gates (fn [] [])
                nido.coordinator.work/workstream (fn [_ _ & _] {:ws-id "ws-1" :project "brian" :origin :notion
                                                  :stage :triage :label "BR-7" :ledger nil
                                                  :report {:markdown "# V\n\nbody"} :sessions []})
                nido.ui.server/read-rail-daemon (fn [] {:state :up})]
    (let [resp (server/handle-request {:request-method :get :uri "/workstreams/brian/ws-1"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "BR-7")))))

(deftest dashboard-routes-smoke
  (with-redefs [nido.coordinator.work/all-gates (fn [] [])
                nido.coordinator.work/all-grouped (fn [] [])
                project/list-projects (fn [] {"brian" {:directory "/x"}})
                nido.ui.server/read-rail-daemon (fn [] {:state :up})]
    (doseq [uri ["/" "/workstreams"]]
      (is (= 200 (:status (server/handle-request {:request-method :get :uri uri})))
          (str uri " serves 200")))))

(deftest post-gate-mutation-returns-confirm-pane-with-follow-links
  (with-redefs [nido.coordinator.work/resolve-gate! (fn [& _] {:decision :promote})]
    (let [resp (server/handle-request {:request-method :post :uri "/gate/brian/ws-1/promote"})]
      (Thread/sleep 50)
      (is (str/includes? (:body resp) "gate-pane"))
      (is (str/includes? (:body resp) "Promoting"))
      (is (str/includes? (:body resp) "/workstreams/brian/ws-1"))
      (is (str/includes? (:body resp) "/workstreams")))))

(deftest gate-resolve-surfaces-a-notion-failed-decision
  ;; resolve-gate! signals failure by RETURNING a decision, not by throwing —
  ;; gate-resolve! must route that value-level failure to :failed so the board
  ;; doesn't silently clear the working state on a lost Notion write.
  (with-redefs [nido.coordinator.work/resolve-gate! (fn [& _] {:decision :notion-failed :error :server})]
    (try
      (#'server/gate-resolve! "brian" "ws-notion-fail" :apply nil)
      (Thread/sleep 50)   ; resolve runs on a background future
      (is (= {:state :failed :error-msg "Apply failed: server"}
             (dev/current-app-state "brian/ws-notion-fail")))
      (finally (dev/clear-app-state! "brian/ws-notion-fail")))))

(deftest gate-resolve-includes-the-http-status-in-the-failure-message
  ;; ":error :http" alone is unactionable — a 400 is a payload we built wrong,
  ;; a 404 is a sharing problem. The status has to reach the user.
  (with-redefs [nido.coordinator.work/resolve-gate! (fn [& _] {:decision :error :error :http :status 400})]
    (try
      (#'server/gate-resolve! "brian" "ws-http-fail" :apply nil)
      (Thread/sleep 50)
      (is (= {:state :failed :error-msg "Apply failed: http 400"}
             (dev/current-app-state "brian/ws-http-fail")))
      (finally (dev/clear-app-state! "brian/ws-http-fail")))))

(deftest gate-resolve-surfaces-a-no-workstream-decision
  ;; :no-workstream means the click landed on a row with nothing behind it — the
  ;; resolver did nothing at all. Clearing the state on that leaves the optimistic
  ;; "✓ Restored — back in the triage queue." toast as the last word the user ever
  ;; sees, which is the band lying about its one guarantee.
  (with-redefs [nido.coordinator.work/resolve-gate! (fn [& _] {:decision :no-workstream})]
    (try
      (#'server/gate-resolve! "brian" "pg-orphan" :restore nil)
      (Thread/sleep 50)
      (is (= {:state :failed
              :error-msg "Nothing happened — no workstream or ticket behind this row."}
             (dev/current-app-state "brian/pg-orphan")))
      (finally (dev/clear-app-state! "brian/pg-orphan")))))

(deftest derive-screen-attaches-a-failed-resolve-to-its-gate
  ;; The regression this guards: a failed Apply was written to the app-states atom
  ;; and read by nothing, so the click looked like a no-op. pending-resolve-keys
  ;; drops :failed (to keep the action retryable), so the gate must carry the
  ;; reason instead.
  (with-redefs [nido.coordinator.work/all-gates (fn [] [{:ws-id "ws-1" :project "brian" :origin :slack
                                             :stage :triage :label "L" :report nil
                                             :actions [] :session nil}])
                nido.coordinator.work/all-grouped (fn [] [])
                nido.ui.dev/pending-resolve-keys (fn [] #{})
                nido.ui.dev/failed-ws-errors (fn [] {"brian/ws-1" "Apply failed: http 400"})]
    (let [gate (first (:gates (server/derive-screen {:scope "all" :surface :needs})))]
      (is (= "Apply failed: http 400" (:error-msg gate)))
      (is (str/includes? (views/gate-pane (assoc gate :actions [{:id :apply :label "Apply"
                                                                 :kind :mutation}]))
                         "Apply failed: http 400")
          "the pane renders it, and keeps the Apply button clickable to retry"))))

(deftest derive-screen-hides-a-stale-error-on-a-gate-that-is-working-again
  (with-redefs [nido.coordinator.work/all-gates (fn [] [{:ws-id "ws-1" :project "brian" :origin :slack
                                             :stage :triage :label "L" :report nil
                                             :actions [] :session nil}])
                nido.coordinator.work/all-grouped (fn [] [])
                nido.ui.dev/pending-resolve-keys (fn [] #{"brian/ws-1"})
                nido.ui.dev/failed-ws-errors (fn [] {"brian/ws-1" "Apply failed: http 400"})]
    (is (nil? (:error-msg (first (:gates (server/derive-screen {:scope "all" :surface :needs})))))
        "a retry in flight supersedes the previous failure")))

(deftest gate-resolve-clears-on-success-decision
  (with-redefs [nido.coordinator.work/resolve-gate! (fn [& _] {:decision :applied})]
    (try
      (#'server/gate-resolve! "brian" "ws-notion-ok" :apply nil)
      (Thread/sleep 50)
      (is (nil? (dev/current-app-state "brian/ws-notion-ok")))
      (finally (dev/clear-app-state! "brian/ws-notion-ok")))))

(deftest scope-filters-needs-to-one-project
  (with-redefs [nido.coordinator.work/all-gates (fn [] [{:ws-id "a" :project "brian" :origin :notion :stage :triage
                                             :label "BR-1" :report nil :actions [] :session nil}
                                            {:ws-id "b" :project "foo" :origin :notion :stage :triage
                                             :label "FOO-1" :report nil :actions [] :session nil}])
                nido.coordinator.work/all-grouped (fn [] [])
                nido.ui.dev/pending-resolve-keys (fn [] #{})
                nido.ui.server/read-rail-daemon (fn [] {:state :up})
                project/list-projects (fn [] {"brian" {:directory "/x"} "foo" {:directory "/y"}})]
    (let [resp (server/handle-request {:request-method :get :uri "/" :query-string "scope=brian"})]
      (is (str/includes? (:body resp) "BR-1"))
      (is (not (str/includes? (:body resp) "FOO-1"))))))

;; (view-state parsing — scope/tab/selection/entry — is tested in
;;  nido.ui.view-state-test. nido.coordinator.work/screen does no row filtering at all —
;;  see nido.coordinator.work-test's screen-does-not-filter-rows.)

(deftest removed-routes-404
  (with-redefs [nido.coordinator.work/all-gates (fn [] [])
                nido.coordinator.work/all-grouped (fn [] [])
                project/list-projects (fn [] {"brian" {:directory "/x"}})
                nido.ui.server/read-rail-daemon (fn [] {:state :up})]
    (doseq [uri ["/projects" "/board" "/ws/brian/ws-1"
                 "/brian/sessions" "/brian/vsdd" "/_fragment/board" "/_fragment/live"]]
      (is (= 404 (:status (server/handle-request {:request-method :get :uri uri})))
          (str uri " is gone")))))

(deftest live-routes-still-200
  (with-redefs [nido.coordinator.work/all-gates (fn [] [])
                nido.coordinator.work/all-grouped (fn [] [])
                project/list-projects (fn [] {"brian" {:directory "/x"}})
                nido.ui.server/read-rail-daemon (fn [] {:state :up})]
    (doseq [uri ["/" "/workstreams"]]
      (is (= 200 (:status (server/handle-request {:request-method :get :uri uri})))))))

(deftest workstreams-overview-and-detail-render-same-list
  ;; Selecting a workstream must NOT change the rendered list — the overview and
  ;; the detail (with ?sel=) render the same rows. This is the overview≡detail
  ;; invariant (the "list jumps" bug). screen no longer filters rows by origin,
  ;; so both BR-1 (notion) and BR-2 (github) render in both overview and detail.
  ;; r1 and p1 both sit under :incoming — the point is the overview≡detail
  ;; invariant across origins within a band, not a specific stage; :in-progress
  ;; belongs to the Active tab (see nido.coordinator.work/tab-bands) so it would confound
  ;; the assertion here.
  (let [grouped {:incoming [{:ws-id "r1" :origin :notion :facets {} :stage :ready :label "BR-1" :needs-you false}
                             {:ws-id "p1" :origin :github :facets {} :stage :incoming :label "BR-2" :needs-you false}]
                 :triage {:in-flight [] :queued []}
                 :in-progress []
                 :shipping []}]
    (with-redefs [nido.coordinator.work/all-grouped (fn [] [{:project "brian" :grouped grouped}])
                  nido.coordinator.work/all-gates   (fn [] [])
                  nido.coordinator.work/workstream  (fn [_ _ _] {:project "brian" :ws-id "r1" :origin :notion
                                                     :stage :ready :label "BR-1" :sessions []})
                  nido.ui.dev/pending-resolve-keys (fn [] #{})
                  nido.ui.dev/ws-session-dev-states (fn [_ _] {})
                  project/list-projects (fn [] {"brian" {:directory "/x"}})
                  nido.ui.server/read-rail-daemon (fn [] {:state :up})]
      (let [over (:body (server/handle-request {:request-method :get :uri "/workstreams"
                                                :query-string "source=notion"}))
            det  (:body (server/handle-request {:request-method :get :uri "/workstreams"
                                                :query-string "source=notion&sel=brian:r1"}))]
        (is (str/includes? over "BR-1"))
        (is (str/includes? det "BR-1"))
        (is (str/includes? over "BR-2") "github row survives in overview — screen does not filter")
        (is (str/includes? det "BR-2")  "github row survives in detail too")))))

(deftest dev-state-for-derives-from-registry-probe-and-pending
  (let [reg {"/wt" {:app-port 3142 :url "http://x.localhost:3142"}}]
    ;; running: live port wins
    (is (= {:state :running :url "http://x.localhost:3142"}
           (dev/dev-state-for "/wt" "brian--x" reg (fn [_] true) (fn [_] nil))))
    ;; down: port closed, no pending
    (is (= {:state :down}
           (dev/dev-state-for "/wt" "brian--x" reg (fn [_] false) (fn [_] nil))))
    ;; down: no registry entry at all
    (is (= {:state :down}
           (dev/dev-state-for "/missing" "brian--x" reg (fn [_] true) (fn [_] nil))))
    ;; starting: pending keyword shows when not live
    (is (= {:state :starting :error-msg nil}
           (dev/dev-state-for "/wt" "brian--x" reg (fn [_] false) (fn [_] :starting))))
    ;; failed: pending map carries the error message
    (is (= {:state :failed :error-msg "boom"}
           (dev/dev-state-for "/wt" "brian--x" reg (fn [_] false)
                              (fn [_] {:state :failed :error-msg "boom"}))))))

(deftest session-dev-state-wires-coords-registry-and-probe
  (with-redefs [nido.session.lifecycle/session-coords
                (fn [_ _] {:wt-path "/wt" :instance-id "brian--x"})
                nido.session.state/read-registry
                (fn [] {"/wt" {:app-port 3142 :url "http://x.localhost:3142"}})
                nido.platform.process/tcp-open? (fn [_] true)]
    (is (= {:state :running :url "http://x.localhost:3142"}
           (dev/session-dev-state "brian" "feat/x")))))

(deftest session-dev-state-3-arity-uses-passed-registry
  (with-redefs [nido.session.lifecycle/session-coords
                (fn [_ _] {:wt-path "/wt" :instance-id "brian--x"})
                nido.session.state/read-registry
                (fn [] (throw (ex-info "should not read registry in 3-arity" {})))
                nido.platform.process/tcp-open? (fn [_] true)]
    (is (= {:state :running :url "http://x.localhost:3142"}
           (dev/session-dev-state "brian" "feat/x"
                                  {"/wt" {:app-port 3142 :url "http://x.localhost:3142"}})))))

(deftest fragment-workstream-route-is-sse-and-renders-environment
  (with-redefs [nido.coordinator.work/workstream
                (fn [_ _ & _] {:project "brian" :ws-id "ws-1" :origin :notion
                               :stage :triage :label "BR-7 · t" :ledger nil :report nil
                               :environment {:name "me" :weight :heavy}
                               :sessions [{:name "me" :autonomy-level :interactive
                                           :parked? false :status :up :brakes nil}]})
                nido.session.state/read-registry (fn [] {})
                nido.ui.dev/session-dev-state
                (fn [_ _ & _] {:state :running :url "http://me.brian.localhost:3142"})]
    (let [resp (server/handle-request {:request-method :get :uri "/_fragment/workstream/brian/ws-1"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (get-in resp [:headers "Content-Type"]) "text/event-stream"))
      (is (str/includes? (:body resp) "ws-pane"))
      (is (str/includes? (:body resp) "Open app"))
      (is (str/includes? (:body resp) "/sessions/me/dev/stop")))))

(deftest post-session-dev-start-dispatches-and-returns-pane-sse
  (let [calls (atom [])]
    (with-redefs [nido.session.lifecycle/session-coords
                  (fn [s _] {:wt-path (str "/wt/" s) :instance-id (str "brian--" s)})
                  nido.session.engine/read-profile-for-session (fn [_] {:services []})
                  nido.coordinator.work/ensure-open! (fn [& _] false)
                  nido.session.lifecycle/up! (fn [s opts] (swap! calls conj [:up s (:profile opts)]))
                  nido.platform.process/tcp-open? (fn [_] true)
                  nido.ui.dev/app-port-for-instance (fn [_] 4096)
                  nido.coordinator.work/workstream
                  (fn [_ _ & _] {:project "brian" :ws-id "ws-1" :origin :notion
                                 :stage :triage :label "BR-7 · t" :ledger nil :report nil :sessions []})]
      (let [resp (server/handle-request
                  {:request-method :post :uri "/workstreams/brian/ws-1/sessions/feat%2Fx/dev/start"})]
        (Thread/sleep 50)
        (is (= 200 (:status resp)))
        (is (str/includes? (get-in resp [:headers "Content-Type"]) "text/event-stream"))
        (is (str/includes? (:body resp) "ws-pane"))
        ;; decoded session name + reused persisted profile threaded into up!
        (is (= [[:up "feat/x" {:services []}]] @calls))))))

(deftest post-session-dev-stop-calls-down
  (let [calls (atom [])]
    (with-redefs [nido.session.lifecycle/session-coords
                  (fn [s _] {:wt-path (str "/wt/" s) :instance-id (str "brian--" s)})
                  nido.session.engine/read-profile-for-session (fn [_] nil)
                  nido.session.lifecycle/down! (fn [s _] (swap! calls conj [:down s]))
                  nido.coordinator.work/workstream
                  (fn [_ _ & _] {:project "brian" :ws-id "ws-1" :origin :notion
                                 :stage :triage :label "BR-7 · t" :ledger nil :report nil :sessions []})]
      (let [resp (server/handle-request
                  {:request-method :post :uri "/workstreams/brian/ws-1/sessions/me/dev/stop"})]
        (Thread/sleep 50)
        (is (= 200 (:status resp)))
        (is (= [[:down "me"]] @calls))))))

(deftest workstream-route-honours-entry-param
  (with-redefs [nido.coordinator.work/grouped (fn [_] {:triage {:in-flight [] :queued []}
                                           :ready [] :in-progress [] :incoming []})
                project/list-projects (fn [] {"brian" {:directory "/x"}})
                nido.coordinator.work/all-gates (fn [] [])
                nido.coordinator.work/workstream
                (fn [_ _ sel] {:ws-id "ws-1" :project "brian" :origin :notion
                               :stage :triage :label "BR-7" :ledger nil
                               :selected-seq sel :entries nil
                               :report {:markdown (str "# V\n\nsel=" (pr-str sel))} :sessions []})
                nido.ui.server/read-rail-daemon (fn [] {:state :up})]
    (let [resp (server/handle-request {:request-method :get
                                       :uri "/workstreams/brian/ws-1"
                                       :query-string "entry=2"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "sel=2") "entry param threaded to work/workstream"))
    (let [resp (server/handle-request {:request-method :get
                                       :uri "/_fragment/workstream/brian/ws-1"
                                       :query-string "entry=5"})]
      (is (str/includes? (:body resp) "sel=5") "fragment route honours entry too"))
    (let [resp (server/handle-request {:request-method :get
                                       :uri "/workstreams/brian/ws-1"})]
      (is (str/includes? (:body resp) "sel=nil") "absent entry → nil → nothing open"))))

(deftest workstream-route-threads-the-unfolded-rounds-to-the-pane
  ;; ?rounds= is the other half of the reader's position. work/workstream never
  ;; sees it (it changes no data — only how the open report renders), so the route
  ;; has to hand it to the view itself, on BOTH render paths.
  (with-redefs [nido.coordinator.work/grouped (fn [_] {:triage {:in-flight [] :queued []}
                                           :ready [] :in-progress [] :incoming []})
                project/list-projects (fn [] {"brian" {:directory "/x"}})
                nido.coordinator.work/all-gates (fn [] [])
                nido.coordinator.work/workstream
                (fn [_ _ sel] {:ws-id "ws-1" :project "brian" :origin :notion
                               :stage :triage :label "BR-7" :ledger nil
                               :selected-seq sel :entries nil :sessions []})
                nido.ui.server/read-rail-daemon (fn [] {:state :up})]
    ;; the pane's self-poll URL is where the position surfaces verbatim
    (doseq [uri ["/workstreams/brian/ws-1" "/_fragment/workstream/brian/ws-1"]]
      (let [resp (server/handle-request {:request-method :get :uri uri
                                         :query-string "entry=2&rounds=3,1"})]
        (is (str/includes? (:body resp) "/_fragment/workstream/brian/ws-1?entry=2&amp;rounds=1,3")
            (str uri " carries the whole reading position into the pane"))))))

;; ---------------------------------------------------------------------------
;; POST /workstreams/:project/:ws-id/gate/:action — pane-scoped resolve route
;; ---------------------------------------------------------------------------

(deftest post-pane-gate-promote-resolves-and-targets-ws-pane
  (let [calls (atom [])]
    (with-redefs [nido.coordinator.work/resolve-gate! (fn [p w a & [in]] (swap! calls conj [p w a in]) {:decision :triaging})
                  nido.coordinator.work/all-gates    (fn [] [])]
      (let [resp (server/handle-request
                  {:request-method :post :uri "/workstreams/brian/ws-1/gate/promote"})]
        (Thread/sleep 50)   ; resolve runs on a background future
        (is (= 200 (:status resp)))
        (is (= [["brian" "ws-1" :promote nil]] @calls))
        (is (str/includes? (:body resp) "ws-pane") "confirmation patches the overview pane")
        (is (str/includes? (:body resp) "Promoting"))))))

(deftest post-pane-gate-dismiss-resolves
  (let [calls (atom [])]
    (with-redefs [nido.coordinator.work/resolve-gate! (fn [p w a & [in]] (swap! calls conj [p w a in]) {:decision :dismissed})
                  nido.coordinator.work/all-gates    (fn [] [])]
      (server/handle-request {:request-method :post :uri "/workstreams/brian/ws-1/gate/drop"})
      (Thread/sleep 50)
      (is (= [["brian" "ws-1" :drop nil]] @calls)))))

(deftest pending-resolve-keys-returns-slash-keys
  (nido.ui.dev/set-app-state! "brian/ws-9" :resuming)
  (try
    (is (contains? (nido.ui.dev/pending-resolve-keys) "brian/ws-9"))
    (finally (nido.ui.dev/clear-app-state! "brian/ws-9"))))

(deftest pending-resolve-keys-excludes-failed-and-instance-ids
  ;; a :failed resolve is not mid-flight — it must drop out so the gate stays
  ;; retryable; a slashed-session instance-id key must never count as pending.
  (nido.ui.dev/set-app-state! "brian/ws-failed" :failed "boom")
  (nido.ui.dev/set-app-state! "brian--fix/foo" :starting)
  (try
    (let [ks (nido.ui.dev/pending-resolve-keys)]
      (is (not (contains? ks "brian/ws-failed")) ":failed is not mid-flight")
      (is (not (contains? ks "brian--fix/foo")) "an instance-id key is never pending"))
    (finally
      (nido.ui.dev/clear-app-state! "brian/ws-failed")
      (nido.ui.dev/clear-app-state! "brian--fix/foo"))))

;; ---------------------------------------------------------------------------
;; POST /workstreams/:project/:ws-id/winddown — bring a closed workstream's
;; leftover sessions down
;; ---------------------------------------------------------------------------

(deftest post-winddown-sets-stopping-and-responds-with-fragment
  (let [called (atom nil)]
    (with-redefs [nido.coordinator.work/bring-down! (fn [p w] (reset! called [p w]) {:downed []})
                  nido.coordinator.work/all-grouped (fn [] [])
                  nido.coordinator.work/all-gates (fn [] [])
                  server/read-rail-daemon (fn [] {:state :up})]
      (let [resp (server/handle-request {:request-method :post
                                         :uri "/workstreams/p/w1/winddown"})]
        (is (= 200 (:status resp)))
        (is (str/includes? (get-in resp [:headers "Content-Type"]) "text/event-stream"))
        ;; the future runs async — poll until the pending key CLEARS (asserting
        ;; on @called alone races the clear-app-state! that follows it)
        (loop [n 40]
          (when (and (seq (dev/pending-winddown-keys)) (pos? n))
            (Thread/sleep 50) (recur (dec n))))
        (is (= ["p" "w1"] @called))
        (is (empty? (dev/pending-winddown-keys)) "cleared after bring-down returns")))))

;; ---------------------------------------------------------------------------
;; Ops panel — ambient chrome behind the rail health dot
;; ---------------------------------------------------------------------------

(deftest post-ops-halt-writes-halt-and-responds-with-ops-fragment
  (let [halted (atom false)]
    (with-redefs [nido.coordinator.control/halt! (fn [_] (reset! halted true))
                  nido.coordinator.control/halt-info (fn [] nil)
                  nido.coordinator.control/tripped-triggers (fn [] [])
                  nido.coordinator.control/triggers-for (fn [_] [])
                  server/read-rail-daemon (fn [] {:state :up})
                  nido.coordinator.work/all-gates (fn [] [])]
      (let [resp (server/handle-request {:request-method :post :uri "/ops/halt"})]
        (is @halted)
        (is (str/includes? (:body resp) "ops-panel"))))))

(deftest ops-fragment-badge-count-is-scoped-when-scope-param-present
  ;; Fix 2: GET /_fragment/ops?scope=brian must filter the rail badge to
  ;; brian's gates only; the scope-less request keeps counting every gate.
  (with-redefs [nido.coordinator.work/all-gates (fn [] [{:project "brian" :ws-id "w1"}
                                            {:project "brian" :ws-id "w2"}
                                            {:project "fukan" :ws-id "w3"}])
                nido.coordinator.control/halt-info (fn [] nil)
                nido.coordinator.control/tripped-triggers (fn [] [])
                nido.coordinator.control/triggers-for (fn [_] [])
                project/list-projects (fn [] {"brian" {:directory "/x"} "fukan" {:directory "/y"}})
                server/read-rail-daemon (fn [] {:state :up})]
    (let [scoped   (server/handle-request {:request-method :get :uri "/_fragment/ops"
                                           :query-string "scope=brian"})
          unscoped (server/handle-request {:request-method :get :uri "/_fragment/ops"})]
      (is (str/includes? (:body scoped) ">2<") "scoped badge counts only brian's 2 gates")
      (is (str/includes? (:body unscoped) ">3<") "unscoped badge counts all 3 gates"))))

(deftest post-pane-gate-reply-passes-input-from-body
  (let [calls (atom [])]
    (with-redefs [nido.coordinator.work/resolve-gate! (fn [p w a & [in]] (swap! calls conj [p w a in]) {:resumed "auto"})
                  nido.coordinator.work/all-gates    (fn [] [])]
      (let [body (java.io.ByteArrayInputStream. (.getBytes "{\"reply\":\"do the fix\"}"))]
        (server/handle-request {:request-method :post :uri "/workstreams/brian/ws-1/gate/reply" :body body})
        (Thread/sleep 50)
        (is (= [["brian" "ws-1" :reply "do the fix"]] @calls)
            "pane Reply resumes the parked agent with the textarea input")))))

(deftest pane-fragment-renders-a-bare-pane
  ;; Pins the /_fragment/workstream route (ws-pane-fragment-response, which calls
  ;; work/workstream directly, not derive-screen): given a bare-shaped
  ;; work/workstream result it renders the bare pane body instead of blanking.
  ;; It stubs work/workstream itself, so it would pass identically whether the
  ;; bare fallback lived here or in derive-screen — that seam (the page-id →
  ;; bare-pane fallback in work/workstream) is covered instead by
  ;; nido.coordinator.work-test/workstream-falls-back-to-a-bare-pane-for-an-uncovered-page.
  (with-redefs [nido.coordinator.work/workstream
                (fn [_ _ & _] {:project "brian" :ws-id "pg-bare" :origin :notion
                               :bare? true :stage :triage :label "Move Licences"
                               :br-id "BR-5569" :notion-status "Needs verification"
                               :ledger nil :entries nil :report nil
                               :environment nil :sessions [] :on-latest? true})
                nido.ui.dev/failed-ws-errors (fn [] {})]
    (let [resp (server/handle-request
                {:request-method :get :uri "/_fragment/workstream/brian/pg-bare"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "No nido workstream yet"))
      (is (str/includes? (:body resp) "/workstreams/brian/pg-bare/gate/start-triage")))))

(deftest gate-resolve-drops-a-key-already-in-flight
  ;; The double-click guard. Task 3's ref-dedup cannot fire on a bare row's first
  ;; click, so this is what prevents two triage sessions on one ticket.
  (let [calls (atom 0)]
    (with-redefs [nido.ui.dev/pending-resolve-keys (fn [] #{"brian/pg-bare"})
                  nido.coordinator.work/resolve-gate! (fn [& _] (swap! calls inc) {:decision :triaging})]
      (let [resp (server/handle-request
                  {:request-method :post
                   :uri "/workstreams/brian/pg-bare/gate/start-triage"})]
        (Thread/sleep 50)
        (is (= 200 (:status resp)) "still answers the POST")
        (is (zero? @calls) "but does not resolve a second time while one is in flight")))))

(deftest gate-resolve-runs-when-no-key-is-in-flight
  ;; The guard must not deadlock the normal path.
  (let [calls (atom 0)]
    (with-redefs [nido.ui.dev/pending-resolve-keys (fn [] #{})
                  nido.coordinator.work/resolve-gate! (fn [& _] (swap! calls inc) {:decision :triaging})]
      (server/handle-request {:request-method :post
                              :uri "/workstreams/brian/pg-bare/gate/start-triage"})
      (Thread/sleep 50)
      (is (= 1 @calls)))))

;; Fix 3: the in-flight guard is keyed per WORKSTREAM, not per action, so a
;; second click for a DIFFERENT action while one is in flight must not resolve
;; again — but handle-post used to render the success toast unconditionally
;; regardless of whether gate-resolve! actually started anything, so the click
;; looked like it worked when it did nothing at all.
(deftest post-gate-action-while-another-is-in-flight-shows-skip-not-success
  (with-redefs [nido.ui.dev/pending-resolve-keys (fn [] #{"brian/pg-bare"})
                nido.coordinator.work/resolve-gate! (fn [& _] {:decision :dismissed})]
    (let [resp (server/handle-request
                {:request-method :post
                 :uri "/workstreams/brian/pg-bare/gate/dismiss"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "already in flight")
          "the honest already-in-flight copy, not the per-action success toast")
      (is (not (str/includes? (:body resp) "Dismissed — off your radar"))
          "must not claim the dismiss succeeded when it never ran"))))

(deftest post-gate-action-with-nothing-in-flight-still-shows-success
  (with-redefs [nido.ui.dev/pending-resolve-keys (fn [] #{})
                nido.coordinator.work/resolve-gate! (fn [& _] {:decision :dismissed})]
    (let [resp (server/handle-request
                {:request-method :post
                 :uri "/workstreams/brian/pg-bare/gate/dismiss"})]
      (Thread/sleep 50)
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "Dismissed — off your radar")
          "the normal path still shows the success confirmation"))))

(deftest resolve-failure-msg-covers-the-start-triage-decisions
  (is (nil? (server/resolve-failure-msg {:decision :triaging})))
  (is (str/includes? (server/resolve-failure-msg {:decision :no-trigger}) "trigger"))
  (is (str/includes? (server/resolve-failure-msg {:decision :already-in-flight})
                     "already"))
  (is (str/includes? (server/resolve-failure-msg {:decision :unresolved :error :no-token})
                     "no-token")))

(deftest resolve-failure-msg-covers-the-approval-decisions
  ;; A grant is the one click on this surface that writes a durable decision, so
  ;; a refusal that reads as success is worse here than anywhere else.
  (is (nil? (server/resolve-failure-msg {:decision :approved})))
  (is (nil? (server/resolve-failure-msg {:decision :approved-unresumed}))
      "granted with nobody listening is a real outcome, not a failure — the
       record stands and the next session reads it")
  (is (str/includes? (server/resolve-failure-msg {:decision :approval-stale})
                     "were not looking at"))
  (is (str/includes? (server/resolve-failure-msg {:decision :no-design}) "no design"))
  (let [msg (server/resolve-failure-msg
             {:decision :approval-refused
              :because {:reason :premise-retracted
                        :detail "the baseline at entry 2 was retracted by entry 9"}})]
    (is (str/includes? msg "no longer stands"))
    (is (str/includes? msg "entry 9") "naming the entry is what a reader can act on")))
