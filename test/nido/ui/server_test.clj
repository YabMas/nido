(ns nido.ui.server-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [nido.ui.server :as server]
            [nido.session.dev :as dev]
            [nido.session.engine]
            [nido.session.lifecycle :as lifecycle]
            [nido.session.state]
            [nido.process]
            [nido.work]
            [nido.project :as project]))

(deftest all-session-rows-aggregates-and-sorts-live-first
  ;; Pure 2-arity: inject the per-project row builder + the projects map so the
  ;; aggregation/sort is testable without a real registry or worktrees on disk.
  (let [rows-fn  (fn [pname _dir]
                   (case pname
                     "brian" [{:name "b-down" :live? false :entry nil}
                              {:name "b-up"   :live? true  :entry {:url "u1"}}]
                     "foo"   [{:name "f-up"   :live? true  :entry {:url "u2"}}]))
        projects {"brian" {:directory "/x"} "foo" {:directory "/y"}}
        rows     (server/all-session-rows rows-fn projects)]
    ;; live-first, then project, then name; each row tagged with :project
    (is (= [["brian" "b-up" true] ["foo" "f-up" true] ["brian" "b-down" false]]
           (map (juxt :project :name :live?) rows)))))

(deftest system-route-renders-on-shell
  (with-redefs [server/all-session-rows (fn [] [])
                nido.work/all-gates (fn [] [])
                nido.ui.server/read-rail-daemon (fn [] {:state :up})]
    (let [resp (server/handle-request {:request-method :get :uri "/system"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "id=\"system\"")))))


(deftest system-fragment-route-is-sse-and-patches-rail
  (with-redefs [server/all-session-rows (fn [] [])
                nido.work/all-gates (fn [] [])
                nido.ui.server/read-rail-daemon (fn [] {:state :up})]
    (let [resp (server/handle-request {:request-method :get :uri "/_fragment/system"})]
      (is (str/includes? (get-in resp [:headers "Content-Type"]) "text/event-stream"))
      (is (str/includes? (:body resp) "rail-needs-count")))))

(deftest all-session-rows-skips-unreadable-projects
  ;; A project with no session.edn makes the real session-rows throw
  ;; (worktrees-dir → load-session-edn). The board must skip it, not crash.
  (let [rows-fn  (fn [pname _dir]
                   (if (= pname "broken")
                     (throw (ex-info "No session.edn found for project 'broken'" {}))
                     [{:name "ok" :live? true :entry {:url "u"}}]))
        projects {"good" {:directory "/g"} "broken" {:directory "/b"}}
        rows     (server/all-session-rows rows-fn projects)]
    (is (= [["good" "ok" true]] (map (juxt :project :name :live?) rows)))))

(deftest home-route-renders-needs-page
  (with-redefs [nido.work/all-gates (fn [] [])
                nido.work/all-grouped (fn [] [])
                nido.session.dev/pending-resolve-keys (fn [] #{})
                project/list-projects (fn [] {"brian" {:directory "/x"}})
                nido.ui.server/read-rail-daemon (fn [] {:state :up})]
    (let [resp (server/handle-request {:request-method :get :uri "/"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "Needs you")))))

(deftest needs-fragment-route-is-sse-and-patches-rail
  (with-redefs [nido.work/all-gates (fn [] [])
                nido.work/all-grouped (fn [] [])
                nido.session.dev/pending-resolve-keys (fn [] #{})
                project/list-projects (fn [] {"brian" {:directory "/x"}})
                nido.ui.server/read-rail-daemon (fn [] {:state :up})]
    (let [resp (server/handle-request {:request-method :get :uri "/_fragment/needs"})]
      (is (str/includes? (get-in resp [:headers "Content-Type"]) "text/event-stream"))
      (is (str/includes? (:body resp) "rail-needs-count")))))   ; rail patched too

(deftest gate-pane-route-renders                ; legacy URI /gate/… → needs surface, gate selected
  (let [g {:ws-id "ws-1" :project "brian" :origin :notion :stage :triage
           :label "BR-7" :report nil :actions [] :session nil}]
    (with-redefs [nido.work/all-gates (fn [] [g])
                  nido.work/all-grouped (fn [] [])
                  nido.session.dev/pending-resolve-keys (fn [] #{})
                  project/list-projects (fn [] {"brian" {:directory "/x"}})
                  nido.ui.server/read-rail-daemon (fn [] {:state :up})]
      (let [resp (server/handle-request {:request-method :get :uri "/gate/brian/ws-1"})]
        (is (= 200 (:status resp)))
        (is (str/includes? (:body resp) "BR-7"))))))

(deftest post-system-lifecycle-renamed-path
  (with-redefs [server/all-session-rows (fn [] [])
                lifecycle/up! (fn [& _] nil)
                nido.ui.server/read-rail-daemon (fn [] {:state :up})]
    (let [resp (server/handle-request {:request-method :post :uri "/system/brian/doc-room/start"})]
      (Thread/sleep 30)
      (is (str/includes? (get-in resp [:headers "Content-Type"]) "text/event-stream")))))

(deftest post-gate-mutation-calls-resolve-and-returns-sse
  (let [calls (atom [])]
    (with-redefs [nido.work/resolve-gate! (fn [p w a & [in]] (swap! calls conj [p w a in]) {:decision :dropped})
                  nido.work/all-gates    (fn [] [])]
      (let [resp (server/handle-request {:request-method :post :uri "/gate/brian/ws-1/skip"})]
        (Thread/sleep 50)   ; resolve runs on a background future
        (is (= [["brian" "ws-1" :skip nil]] @calls))
        (is (str/includes? (get-in resp [:headers "Content-Type"]) "text/event-stream"))))))

(deftest post-gate-apply-resolves-with-no-body
  (let [calls (atom [])]
    (with-redefs [nido.work/resolve-gate! (fn [p w a & [in]] (swap! calls conj [p w a in]) {:resumed "auto"})
                  nido.work/all-gates    (fn [] [])]
      (let [resp (server/handle-request {:request-method :post :uri "/gate/brian/ws-1/apply"})]
        (Thread/sleep 50)   ; resolve runs on a background future
        (is (= 200 (:status resp)))
        (is (= [["brian" "ws-1" :apply nil]] @calls)
            "apply posts with no input — resolve-gate! supplies the canned verb")))))

(deftest post-gate-reply-passes-input-from-body
  (let [calls (atom [])]
    (with-redefs [nido.work/resolve-gate! (fn [p w a & [in]] (swap! calls conj [p w a in]) {:resumed "auto"})
                  nido.work/all-gates    (fn [] [])]
      (let [body (java.io.ByteArrayInputStream. (.getBytes "{\"reply\":\"do the fix\"}"))]
        (server/handle-request {:request-method :post :uri "/gate/brian/ws-1/reply" :body body})
        (Thread/sleep 50)
        (is (= [["brian" "ws-1" :reply "do the fix"]] @calls))))))

(deftest post-gate-reply-returns-resuming-pane
  (with-redefs [nido.work/resolve-gate! (fn [& _] {:resumed "auto"})]
    (let [body (java.io.ByteArrayInputStream. (.getBytes "{\"reply\":\"apply\"}"))
          resp (server/handle-request {:request-method :post :uri "/gate/brian/ws-1/reply" :body body})]
      (Thread/sleep 50)
      (is (str/includes? (:body resp) "Resuming"))
      (is (str/includes? (:body resp) "gate-pane")))))

(deftest workstreams-route-renders
  (with-redefs [nido.work/grouped (fn [_] {:triage {:in-flight [] :queued []} :ready [] :in-progress [] :incoming []})
                project/list-projects (fn [] {"brian" {:directory "/x"}})
                nido.work/all-gates (fn [] [])
                nido.ui.server/read-rail-daemon (fn [] {:state :up})]
    (let [resp (server/handle-request {:request-method :get :uri "/workstreams"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "Workstreams")))))

(deftest workstreams-route-renders-the-requested-tab-end-to-end
  ;; parse → derive-screen → views: ?tab= flows through with no route of its own.
  ;; Same four stubs the deleted source/facet end-to-end test used.
  (with-redefs [nido.work/all-grouped
                (fn [] [{:project "brian"
                         :grouped {:incoming []
                                   :triage {:in-flight [{:ws-id "t" :origin :notion :stage :triage
                                                         :label "Triage-row"}]
                                            :queued []}
                                   :in-progress [{:ws-id "p" :origin :scratch :stage :in-progress
                                                  :label "Scratch-row"}]
                                   :shipping []}}])
                nido.work/all-gates (fn [] [])
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
  (with-redefs [nido.work/grouped (fn [_] {:triage {:in-flight [] :queued []} :ready [] :in-progress [] :incoming []})
                project/list-projects (fn [] {"brian" {:directory "/x"}})
                nido.work/all-gates (fn [] [])
                nido.work/workstream (fn [_ _ & _] {:ws-id "ws-1" :project "brian" :origin :notion
                                                  :stage :triage :label "BR-7" :ledger nil
                                                  :report {:markdown "# V\n\nbody"} :sessions []})
                nido.ui.server/read-rail-daemon (fn [] {:state :up})]
    (let [resp (server/handle-request {:request-method :get :uri "/workstreams/brian/ws-1"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "BR-7")))))

(deftest dashboard-routes-smoke
  (with-redefs [nido.work/all-gates (fn [] [])
                nido.work/all-grouped (fn [] [])
                server/all-session-rows (fn [] [])
                project/list-projects (fn [] {"brian" {:directory "/x"}})
                nido.ui.server/read-rail-daemon (fn [] {:state :up})]
    (doseq [uri ["/" "/workstreams" "/system"]]
      (is (= 200 (:status (server/handle-request {:request-method :get :uri uri})))
          (str uri " serves 200")))))

(deftest post-gate-mutation-returns-confirm-pane-with-follow-links
  (with-redefs [nido.work/resolve-gate! (fn [& _] {:decision :promote})]
    (let [resp (server/handle-request {:request-method :post :uri "/gate/brian/ws-1/promote"})]
      (Thread/sleep 50)
      (is (str/includes? (:body resp) "gate-pane"))
      (is (str/includes? (:body resp) "Promoting"))
      (is (str/includes? (:body resp) "/workstreams/brian/ws-1"))
      (is (str/includes? (:body resp) "/workstreams")))))

(deftest scope-filters-needs-to-one-project
  (with-redefs [nido.work/all-gates (fn [] [{:ws-id "a" :project "brian" :origin :notion :stage :triage
                                             :label "BR-1" :report nil :actions [] :session nil}
                                            {:ws-id "b" :project "foo" :origin :notion :stage :triage
                                             :label "FOO-1" :report nil :actions [] :session nil}])
                nido.work/all-grouped (fn [] [])
                nido.session.dev/pending-resolve-keys (fn [] #{})
                nido.ui.server/read-rail-daemon (fn [] {:state :up})
                project/list-projects (fn [] {"brian" {:directory "/x"} "foo" {:directory "/y"}})]
    (let [resp (server/handle-request {:request-method :get :uri "/" :query-string "scope=brian"})]
      (is (str/includes? (:body resp) "BR-1"))
      (is (not (str/includes? (:body resp) "FOO-1"))))))

;; (view-state parsing — scope/tab/selection/entry — is tested in
;;  nido.ui.view-state-test. nido.work/screen does no row filtering at all —
;;  see nido.work-test's screen-does-not-filter-rows.)

(deftest removed-routes-404
  (with-redefs [nido.work/all-gates (fn [] [])
                server/all-session-rows (fn [] [])
                nido.work/all-grouped (fn [] [])
                project/list-projects (fn [] {"brian" {:directory "/x"}})
                nido.ui.server/read-rail-daemon (fn [] {:state :up})]
    (doseq [uri ["/projects" "/board" "/ws/brian/ws-1"
                 "/brian/sessions" "/brian/vsdd" "/_fragment/board" "/_fragment/live"]]
      (is (= 404 (:status (server/handle-request {:request-method :get :uri uri})))
          (str uri " is gone")))))

(deftest live-routes-still-200
  (with-redefs [nido.work/all-gates (fn [] [])
                server/all-session-rows (fn [] [])
                nido.work/all-grouped (fn [] [])
                project/list-projects (fn [] {"brian" {:directory "/x"}})
                nido.ui.server/read-rail-daemon (fn [] {:state :up})]
    (doseq [uri ["/" "/workstreams" "/system"]]
      (is (= 200 (:status (server/handle-request {:request-method :get :uri uri})))))))

(deftest workstreams-overview-and-detail-render-same-list
  ;; Selecting a workstream must NOT change the rendered list — the overview and
  ;; the detail (with ?sel=) render the same rows. This is the overview≡detail
  ;; invariant (the "list jumps" bug). screen no longer filters rows by origin,
  ;; so both BR-1 (notion) and BR-2 (github) render in both overview and detail.
  ;; r1 and p1 both sit under :incoming — the point is the overview≡detail
  ;; invariant across origins within a band, not a specific stage; :in-progress
  ;; belongs to the Active tab (see nido.work/tab-bands) so it would confound
  ;; the assertion here.
  (let [grouped {:incoming [{:ws-id "r1" :origin :notion :facets {} :stage :ready :label "BR-1" :needs-you false}
                             {:ws-id "p1" :origin :github :facets {} :stage :incoming :label "BR-2" :needs-you false}]
                 :triage {:in-flight [] :queued []}
                 :in-progress []
                 :shipping []}]
    (with-redefs [nido.work/all-grouped (fn [] [{:project "brian" :grouped grouped}])
                  nido.work/all-gates   (fn [] [])
                  nido.work/workstream  (fn [_ _ _] {:project "brian" :ws-id "r1" :origin :notion
                                                     :stage :ready :label "BR-1" :sessions []})
                  nido.session.dev/pending-resolve-keys (fn [] #{})
                  nido.session.dev/ws-session-dev-states (fn [_ _] {})
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
                nido.process/tcp-open? (fn [_] true)]
    (is (= {:state :running :url "http://x.localhost:3142"}
           (dev/session-dev-state "brian" "feat/x")))))

(deftest session-dev-state-3-arity-uses-passed-registry
  (with-redefs [nido.session.lifecycle/session-coords
                (fn [_ _] {:wt-path "/wt" :instance-id "brian--x"})
                nido.session.state/read-registry
                (fn [] (throw (ex-info "should not read registry in 3-arity" {})))
                nido.process/tcp-open? (fn [_] true)]
    (is (= {:state :running :url "http://x.localhost:3142"}
           (dev/session-dev-state "brian" "feat/x"
                                  {"/wt" {:app-port 3142 :url "http://x.localhost:3142"}})))))

(deftest fragment-workstream-route-is-sse-and-renders-per-session-dev-env
  (with-redefs [nido.work/workstream
                (fn [_ _ & _] {:project "brian" :ws-id "ws-1" :origin :notion
                               :stage :triage :label "BR-7 · t" :ledger nil :report nil
                               :sessions [{:name "me" :autonomy-level :interactive
                                           :parked? false :status :up :brakes nil}]})
                nido.session.state/read-registry (fn [] {})
                nido.session.dev/session-dev-state
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
                  nido.work/ensure-open! (fn [& _] false)
                  nido.session.lifecycle/up! (fn [s opts] (swap! calls conj [:up s (:profile opts)]))
                  nido.process/tcp-open? (fn [_] true)
                  nido.session.dev/app-port-for-instance (fn [_] 4096)
                  nido.work/workstream
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
                  nido.work/workstream
                  (fn [_ _ & _] {:project "brian" :ws-id "ws-1" :origin :notion
                                 :stage :triage :label "BR-7 · t" :ledger nil :report nil :sessions []})]
      (let [resp (server/handle-request
                  {:request-method :post :uri "/workstreams/brian/ws-1/sessions/me/dev/stop"})]
        (Thread/sleep 50)
        (is (= 200 (:status resp)))
        (is (= [[:down "me"]] @calls))))))

(deftest workstream-route-honours-entry-param
  (with-redefs [nido.work/grouped (fn [_] {:triage {:in-flight [] :queued []}
                                           :ready [] :in-progress [] :incoming []})
                project/list-projects (fn [] {"brian" {:directory "/x"}})
                nido.work/all-gates (fn [] [])
                nido.work/workstream
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
      (is (str/includes? (:body resp) "sel=nil") "absent entry → nil → latest"))))

;; ---------------------------------------------------------------------------
;; POST /workstreams/:project/:ws-id/gate/:action — pane-scoped resolve route
;; ---------------------------------------------------------------------------

(deftest post-pane-gate-promote-resolves-and-targets-ws-pane
  (let [calls (atom [])]
    (with-redefs [nido.work/resolve-gate! (fn [p w a & [in]] (swap! calls conj [p w a in]) {:decision :triaging})
                  nido.work/all-gates    (fn [] [])]
      (let [resp (server/handle-request
                  {:request-method :post :uri "/workstreams/brian/ws-1/gate/promote"})]
        (Thread/sleep 50)   ; resolve runs on a background future
        (is (= 200 (:status resp)))
        (is (= [["brian" "ws-1" :promote nil]] @calls))
        (is (str/includes? (:body resp) "ws-pane") "confirmation patches the overview pane")
        (is (str/includes? (:body resp) "Promoting"))))))

(deftest post-pane-gate-dismiss-resolves
  (let [calls (atom [])]
    (with-redefs [nido.work/resolve-gate! (fn [p w a & [in]] (swap! calls conj [p w a in]) {:decision :dismissed})
                  nido.work/all-gates    (fn [] [])]
      (server/handle-request {:request-method :post :uri "/workstreams/brian/ws-1/gate/drop"})
      (Thread/sleep 50)
      (is (= [["brian" "ws-1" :drop nil]] @calls)))))

(deftest pending-resolve-keys-returns-slash-keys
  (nido.session.dev/set-app-state! "brian/ws-9" :resuming)
  (try
    (is (contains? (nido.session.dev/pending-resolve-keys) "brian/ws-9"))
    (finally (nido.session.dev/clear-app-state! "brian/ws-9"))))

(deftest pending-resolve-keys-excludes-failed-and-instance-ids
  ;; a :failed resolve is not mid-flight — it must drop out so the gate stays
  ;; retryable; a slashed-session instance-id key must never count as pending.
  (nido.session.dev/set-app-state! "brian/ws-failed" :failed "boom")
  (nido.session.dev/set-app-state! "brian--fix/foo" :starting)
  (try
    (let [ks (nido.session.dev/pending-resolve-keys)]
      (is (not (contains? ks "brian/ws-failed")) ":failed is not mid-flight")
      (is (not (contains? ks "brian--fix/foo")) "an instance-id key is never pending"))
    (finally
      (nido.session.dev/clear-app-state! "brian/ws-failed")
      (nido.session.dev/clear-app-state! "brian--fix/foo"))))

(deftest post-pane-gate-reply-passes-input-from-body
  (let [calls (atom [])]
    (with-redefs [nido.work/resolve-gate! (fn [p w a & [in]] (swap! calls conj [p w a in]) {:resumed "auto"})
                  nido.work/all-gates    (fn [] [])]
      (let [body (java.io.ByteArrayInputStream. (.getBytes "{\"reply\":\"do the fix\"}"))]
        (server/handle-request {:request-method :post :uri "/workstreams/brian/ws-1/gate/reply" :body body})
        (Thread/sleep 50)
        (is (= [["brian" "ws-1" :reply "do the fix"]] @calls)
            "pane Reply resumes the parked agent with the textarea input")))))
