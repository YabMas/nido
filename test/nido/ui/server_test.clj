(ns nido.ui.server-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [nido.ui.server :as server]
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

(deftest home-route-renders-board
  ;; The flat live-sessions board relocated from / to /system (the gate inbox
  ;; is now the home page); this test follows the board to its new route.
  (with-redefs [server/all-session-rows (fn [] [])]
    (let [resp (server/handle-request {:request-method :get :uri "/system"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "live sessions")))))

(deftest projects-route-renders-grid
  (with-redefs [project/list-projects (fn [] {"brian" {:directory "/x"}})]
    (let [resp (server/handle-request {:request-method :get :uri "/projects"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "brian")))))

(deftest live-fragment-route-is-sse
  (with-redefs [server/all-session-rows (fn [] [])]
    (let [resp (server/handle-request {:request-method :get :uri "/_fragment/live"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (get-in resp [:headers "Content-Type"]) "text/event-stream")))))

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

(deftest home-route-renders-gate-inbox
  (with-redefs [nido.work/all-gates (fn [] [])]
    (let [resp (server/handle-request {:request-method :get :uri "/"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "gate-wrap")))))

(deftest gates-fragment-route-is-sse
  (with-redefs [nido.work/all-gates (fn [] [])]
    (let [resp (server/handle-request {:request-method :get :uri "/_fragment/gates"})]
      (is (str/includes? (get-in resp [:headers "Content-Type"]) "text/event-stream")))))

(deftest gate-pane-route-renders
  (let [g {:ws-id "ws-1" :project "brian" :origin :notion :stage :triage
           :label "BR-7" :report nil :actions [] :session nil}]
    (with-redefs [nido.work/all-gates (fn [] [g])
                  nido.work/gate (fn [_ _] g)]
      (let [resp (server/handle-request {:request-method :get :uri "/gate/brian/ws-1"})]
        (is (= 200 (:status resp)))
        (is (str/includes? (:body resp) "BR-7"))))))

(deftest system-route-still-serves-the-session-board
  (with-redefs [server/all-session-rows (fn [] [])]
    (let [resp (server/handle-request {:request-method :get :uri "/system"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "live sessions")))))

(deftest post-gate-mutation-calls-resolve-and-returns-sse
  (let [calls (atom [])]
    (with-redefs [nido.work/resolve-gate! (fn [p w a & [in]] (swap! calls conj [p w a in]) {:decision :dropped})
                  nido.work/all-gates    (fn [] [])]
      (let [resp (server/handle-request {:request-method :post :uri "/gate/brian/ws-1/skip"})]
        (Thread/sleep 50)   ; resolve runs on a background future
        (is (= [["brian" "ws-1" :skip nil]] @calls))
        (is (str/includes? (get-in resp [:headers "Content-Type"]) "text/event-stream"))))))

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

(deftest board-route-renders
  (with-redefs [nido.work/grouped (fn [_] {:triage {:in-flight [] :queued []} :ready [] :in-progress []})
                project/list-projects (fn [] {"brian" {:directory "/x"}})]
    (let [resp (server/handle-request {:request-method :get :uri "/board"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "board")))))

(deftest ws-detail-route-renders
  (with-redefs [nido.work/workstream (fn [_ _] {:ws-id "ws-1" :project "brian" :origin :notion
                                                :stage :triage :label "BR-7" :ledger nil :sessions []})
                server/workstream-live-url (fn [_ _] nil)]
    (let [resp (server/handle-request {:request-method :get :uri "/ws/brian/ws-1"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "BR-7")))))

(deftest dashboard-routes-smoke
  (with-redefs [nido.work/all-gates (fn [] [])
                server/all-grouped  (fn [] [])
                server/all-session-rows (fn [] [])]
    (doseq [uri ["/" "/board" "/system"]]
      (is (= 200 (:status (server/handle-request {:request-method :get :uri uri})))
          (str uri " serves 200")))))
