(ns nido.ui.server-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [nido.ui.server :as server]
            [nido.session.lifecycle :as lifecycle]
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
                nido.ui.server/read-rail-daemon (fn [] {:state :up})]
    (let [resp (server/handle-request {:request-method :get :uri "/"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "Needs you")))))

(deftest needs-fragment-route-is-sse-and-patches-rail
  (with-redefs [nido.work/all-gates (fn [] [])
                nido.ui.server/read-rail-daemon (fn [] {:state :up})]
    (let [resp (server/handle-request {:request-method :get :uri "/_fragment/needs"})]
      (is (str/includes? (get-in resp [:headers "Content-Type"]) "text/event-stream"))
      (is (str/includes? (:body resp) "rail-needs-count")))))   ; rail patched too

(deftest gate-pane-route-renders                ; keep, unchanged URI /gate/...
  (let [g {:ws-id "ws-1" :project "brian" :origin :notion :stage :triage
           :label "BR-7" :report nil :actions [] :session nil}]
    (with-redefs [nido.work/all-gates (fn [] [g]) nido.work/gate (fn [_ _] g)
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
  (with-redefs [nido.work/grouped (fn [_] {:triage {:in-flight [] :queued []} :ready [] :in-progress [] :inbox []})
                project/list-projects (fn [] {"brian" {:directory "/x"}})
                nido.work/all-gates (fn [] [])
                nido.ui.server/read-rail-daemon (fn [] {:state :up})]
    (let [resp (server/handle-request {:request-method :get :uri "/workstreams"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "Workstreams")))))

(deftest workstream-pane-route-renders
  (with-redefs [nido.work/grouped (fn [_] {:triage {:in-flight [] :queued []} :ready [] :in-progress [] :inbox []})
                project/list-projects (fn [] {"brian" {:directory "/x"}})
                nido.work/all-gates (fn [] [])
                nido.work/workstream (fn [_ _] {:ws-id "ws-1" :project "brian" :origin :notion
                                                :stage :triage :label "BR-7" :ledger nil
                                                :report {:markdown "# V\n\nbody"} :sessions []})
                nido.ui.server/workstream-live-url (fn [_ _] nil)
                nido.ui.server/read-rail-daemon (fn [] {:state :up})]
    (let [resp (server/handle-request {:request-method :get :uri "/workstreams/brian/ws-1"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "BR-7")))))

(deftest dashboard-routes-smoke
  (with-redefs [nido.work/all-gates (fn [] [])
                server/all-grouped  (fn [] [])
                server/all-session-rows (fn [] [])
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
                nido.ui.server/read-rail-daemon (fn [] {:state :up})
                project/list-projects (fn [] {"brian" {:directory "/x"} "foo" {:directory "/y"}})]
    (let [resp (server/handle-request {:request-method :get :uri "/" :query-string "scope=brian"})]
      (is (str/includes? (:body resp) "BR-1"))
      (is (not (str/includes? (:body resp) "FOO-1"))))))

(deftest parse-scope-defaults-to-all
  (is (= "all" (#'server/parse-scope nil)))
  (is (= "all" (#'server/parse-scope "")))
  (is (= "brian" (#'server/parse-scope "scope=brian"))))

(deftest removed-routes-404
  (with-redefs [nido.work/all-gates (fn [] [])
                server/all-session-rows (fn [] [])
                server/all-grouped (fn [] [])
                project/list-projects (fn [] {"brian" {:directory "/x"}})
                nido.ui.server/read-rail-daemon (fn [] {:state :up})]
    (doseq [uri ["/projects" "/board" "/ws/brian/ws-1"
                 "/brian/sessions" "/brian/vsdd" "/_fragment/board" "/_fragment/live"]]
      (is (= 404 (:status (server/handle-request {:request-method :get :uri uri})))
          (str uri " is gone")))))

(deftest live-routes-still-200
  (with-redefs [nido.work/all-gates (fn [] [])
                server/all-session-rows (fn [] [])
                server/all-grouped (fn [] [])
                project/list-projects (fn [] {"brian" {:directory "/x"}})
                nido.ui.server/read-rail-daemon (fn [] {:state :up})]
    (doseq [uri ["/" "/workstreams" "/system"]]
      (is (= 200 (:status (server/handle-request {:request-method :get :uri uri})))))))

(deftest parse-filters-reads-source-and-facets
  (is (= {:source :notion :facets {:app-domain "Teacher"}}
         (#'server/parse-filters "scope=brian&source=notion&app-domain=Teacher")))
  (is (= {:source :all :facets {}} (#'server/parse-filters nil)))
  (is (= {:source :all :facets {}} (#'server/parse-filters "scope=brian"))))

(deftest apply-filters-narrows-each-grouped-by-source-and-facet
  (let [groups [{:project :brian
                 :grouped {:inbox [{:origin :notion :facets {:app-domain ["Teacher"]}}
                                   {:origin :notion :facets {:app-domain ["Student"]}}
                                   {:origin :slack}]
                           :triage {:in-flight [] :queued []} :ready [] :in-progress []}}]
        out (#'server/apply-filters :notion {:app-domain "Teacher"} groups)]
    (is (= [{:origin :notion :facets {:app-domain ["Teacher"]}}]
           (get-in (first out) [:grouped :inbox])))))

(deftest source-counts-tallies-by-origin
  (let [groups [{:project :brian
                 :grouped {:inbox [{:origin :notion} {:origin :slack}]
                           :triage {:in-flight [{:origin :notion}] :queued []}
                           :ready [] :in-progress [{:origin :scratch}]}}]]
    (is (= {:notion 2 :slack 1 :scratch 1} (#'server/source-counts groups)))))

(deftest workstreams-route-honors-source-filter
  (with-redefs [server/all-grouped
                (fn [] [{:project :brian
                         :grouped {:inbox [{:origin :notion :stage :inbox :label "N-one"
                                            :last-activity "t" :engagement :idle}
                                           {:origin :slack :stage :inbox :label "S-one"
                                            :last-activity "t" :engagement :idle}]
                                   :triage {:in-flight [] :queued []} :ready [] :in-progress []}}])
                nido.work/all-gates (fn [] [])
                project/list-projects (fn [] {"brian" {:directory "/x"}})
                nido.ui.server/read-rail-daemon (fn [] {:state :up})]
    (let [resp (server/handle-request {:request-method :get :uri "/workstreams"
                                       :query-string "source=notion"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "N-one"))
      (is (not (str/includes? (:body resp) "S-one")) "slack row filtered out by source=notion"))))
