(ns nido.ui.structure-test
  "The Structure slice's explorer: pinned to the commit `main` names, replaced when `main` moves,
   and reached only through the dashboard."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [nido.design.explorer :as explorer]
   [nido.platform.project :as project]
   [nido.ui.server :as server]
   [nido.ui.structure :as structure]
   [nido.ui.views :as views]
   [nido.platform.jj :as jj]))

(use-fixtures :each (fn [t] (reset! @#'structure/held {}) (t) (reset! @#'structure/held {})))

(defn- world
  "Stubs for a project whose `main` is `@commit`: jj answers it, materializing makes a fresh dir,
   and starting an explorer records the dir it was started over and answers `address`."
  [commit address started stopped]
  {#'project/get-project          (constantly {:directory "/repo"})
   #'jj/jj!                       (fn [& _] {:exit 0 :out @commit :err ""})
   #'jj/materialize-spec-dirs!    (fn [_ rev _] (str (fs/create-temp-dir {:prefix (str "m-" rev "-")})))
   #'explorer/start!              (fn [_ dir] (swap! started conj dir) {:dir dir})
   #'explorer/address             (fn [_] @address)
   #'explorer/stop!               (fn [h] (swap! stopped conj (:dir h)) nil)})

(deftest one-explorer-per-commit-of-main
  (let [commit  (atom "c1")
        address (atom {:status :serving :port 1})
        started (atom [])
        stopped (atom [])]
    (with-redefs-fn (world commit address started stopped)
      (fn []
        (is (= {:status :serving :port 1} (structure/ensure! "p")))
        (structure/ensure! "p")
        (is (= 1 (count @started)) "a visit with main where it was reuses the explorer")
        (reset! commit "c2")
        (structure/ensure! "p")
        (is (= 2 (count @started)) "main moved: a new explorer over its declaration")
        (is (= [(first @started)] @stopped) "and the old one is stopped")
        (is (not (fs/exists? (first @started))) "with its directory deleted")
        (reset! address {:status :failed :error "x"})
        (structure/ensure! "p")
        (is (= 3 (count @started)) "a failed explorer is started again by the next visit")
        (doseq [d @started] (fs/delete-tree d))))))

(deftest a-main-with-no-declaration-is-unmodelled
  (let [started (atom [])]
    (with-redefs-fn (assoc (world (atom "c1") (atom nil) started (atom []))
                           #'explorer/start! (fn [_ _] nil))
      (fn []
        (is (= {:status :unmodelled} (structure/ensure! "p")))
        (is (= {:status :unmodelled} (structure/ensure! "p")))))))

(deftest a-project-without-main-says-so
  (with-redefs [project/get-project (constantly {:directory "/repo"})
                jj/jj!              (fn [& _] {:exit 1 :out "" :err "no such revision"})]
    (is (= {:status :no-main} (structure/ensure! "p")))))

(deftest forwarding-before-anything-started-starts-nothing
  (testing "a page left open never restarts an explorer; only a visit to the slice does"
    (let [resp (structure/forward "p" "api/views" nil)]
      (is (= 502 (:status resp)))
      (is (str/includes? (:body resp) "Open the Structure page")))))

(deftest a-starting-explorer-asks-the-frame-to-try-again
  (reset! @#'structure/held {"p" {:handle :h}})
  (with-redefs [explorer/address (constantly {:status :starting})]
    (let [resp (structure/forward "p" "" nil)]
      (is (= 503 (:status resp)))
      (is (str/includes? (:body resp) "http-equiv=\"refresh\"")))))

(deftest the-explorer-root-needs-its-trailing-slash
  (testing "the explorer's requests are relative, so without the slash they would resolve one
            level up — outside the project's prefix"
    (let [resp (server/handle-request {:request-method :get :uri "/structure/explorer/nido"
                                       :query-string "embed=1"})]
      (is (= 302 (:status resp)))
      (is (= "/structure/explorer/nido/?embed=1" (get-in resp [:headers "Location"]))))))

(deftest the-explorer-is-reached-under-its-project
  (let [seen (atom nil)]
    (with-redefs [structure/forward (fn [& args] (reset! seen args) {:status 200 :body ""})]
      (server/handle-request {:request-method :get :uri "/structure/explorer/nido/api/graph"
                              :query-string "nest=child"})
      (is (= ["nido" "api/graph" "nest=child"] @seen)))))

(def ^:private ctx {:needs-count 0 :daemon {:state :up} :scope "nido" :projects ["nido"]})

(deftest the-slice-frames-the-explorer-edge-to-edge
  (let [html (views/structure-page ctx {:status :starting} "embed=1&nest=child")]
    (is (str/includes? html "content bleed"))
    (is (str/includes? html "src=\"/structure/explorer/nido/?embed=1&amp;nest=child\""))))

(deftest a-slice-with-nothing-to-draw-says-why
  (is (str/includes? (views/structure-page ctx {:status :unmodelled} "") "declares no design on main"))
  (is (str/includes? (views/structure-page ctx {:status :no-main} "") "no main bookmark")))
