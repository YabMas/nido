(ns nido.tui-test
  (:require
   [charm.message :as msg]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.scratch :as scratch]
   [nido.session.lifecycle :as lifecycle]
   [nido.tui :as tui]))

(deftest view-order-is-notion-github-scratch-sessions
  (is (= [:notion :github :scratch :sessions] (mapv :id @#'tui/view-defs))))

(deftest github-view-is-a-workstreams-view-on-the-github-source
  (let [v (#'tui/view-for-id :github)]
    (is (= :workstreams (:kind v)))
    (is (= :github (:source v)))))

(deftest cycling-wraps-both-directions
  (is (= :github   (#'tui/next-view :notion)))
  (is (= :scratch  (#'tui/next-view :github)))
  (is (= :sessions (#'tui/next-view :scratch)))
  (is (= :notion   (#'tui/next-view :sessions)) "wraps forward")
  (is (= :sessions (#'tui/prev-view :notion)) "wraps back")
  (is (= :github   (#'tui/prev-view :scratch))))

(deftest view-for-id-resolves
  (is (= :scratch (:id (#'tui/view-for-id :scratch))))
  (is (= :ops     (:kind (#'tui/view-for-id :sessions))))
  (is (= :workstreams (:kind (#'tui/view-for-id :notion)))))

(deftest tab-bar-marks-the-active-view
  (let [s (#'tui/tab-bar :scratch)]
    (is (re-find #"Notion" s))
    (is (re-find #"Scratch" s))
    (is (re-find #"Sessions" s))
    (is (re-find #"\[Scratch\]" s) "active view bracketed")
    (is (not (re-find #"\[Notion\]" s)) "inactive view not bracketed")))

;; ---------------------------------------------------------------------------
;; Scratch view = the place to create a one-off session.
;; ---------------------------------------------------------------------------

(deftest scratch-view-a-opens-create-session
  (let [state  {:screen :board :view :scratch :project "brian"
                :list (#'tui/list-component [])}
        [s' _] (#'tui/update-workstreams state (msg/key-press "a"))]
    (is (= :create-session (:modal s')) "a opens the create-session modal")
    (is (= "brian" (-> s' :modal-target :project)))))

(deftest a-does-not-create-sessions-on-ref-sourced-views
  ;; Notion/GitHub workstreams come from external refs — creating an arbitrary
  ;; session under them is meaningless, so `a` must stay inert there.
  (doseq [view [:notion :github]]
    (let [state  {:screen :board :view view :project "brian"
                  :list (#'tui/list-component [])}
          [s' _] (#'tui/update-workstreams state (msg/key-press "a"))]
      (is (nil? (:modal s')) (str "no create-session on the " view " view")))))

;; ---------------------------------------------------------------------------
;; TUI up/add/destroy stay in sync with the scratch-workstream model
;; (same birth/reap the bb task layer does — spec line 82).
;; ---------------------------------------------------------------------------

(deftest add-births-a-scratch-workstream
  (let [calls (atom [])]
    (with-redefs [lifecycle/up!  (fn [sn opts] (swap! calls conj [:up sn opts]))
                  scratch/birth! (fn [p sn] (swap! calls conj [:birth p sn]))]
      (#'tui/run-session-action! :add "brian" "refshot")
      (is (= [[:up "refshot" {:project "brian"}] [:birth :brian "refshot"]] @calls)
          "up! first, then a keyword-project birth!"))))

(deftest up-births-a-scratch-workstream
  (let [calls (atom [])]
    (with-redefs [lifecycle/up!  (fn [sn opts] (swap! calls conj [:up sn opts]))
                  scratch/birth! (fn [p sn] (swap! calls conj [:birth p sn]))]
      (#'tui/run-session-action! :up "brian" "refshot")
      (is (= [[:up "refshot" {:project "brian"}] [:birth :brian "refshot"]] @calls)))))

(deftest destroy-reaps-the-scratch-workstream
  (let [calls (atom [])]
    (with-redefs [tui/destroy-session! (fn [sn opts] (swap! calls conj [:destroy sn opts]))
                  scratch/reap!        (fn [p sn] (swap! calls conj [:reap p sn]))]
      (#'tui/run-session-action! :destroy "brian" "refshot")
      (is (= [[:destroy "refshot" {:project "brian"}] [:reap :brian "refshot"]] @calls)
          "destroy! first, then a keyword-project reap!"))))

(deftest scratch-footer-advertises-add
  (let [f (#'tui/footer {:screen :board :view :scratch})]
    (is (re-find #"\[a\]dd" f) "Scratch footer surfaces the create affordance")
    (is (not (re-find #"\[p\]romote" f)) "promote omitted for ref-less one-offs")))

(deftest ref-sourced-footer-has-no-add
  (let [f (#'tui/footer {:screen :board :view :notion})]
    (is (not (re-find #"\[a\]dd" f)) "no create affordance on the Notion view")
    (is (re-find #"\[p\]romote" f))))

(deftest live-session-names-are-the-ones-with-ports
  (with-redefs [lifecycle/list-all-data
                (fn [_] {:sessions [{:name "up"      :app-port 3100}
                                    {:name "also-up" :pg-port 5500}
                                    {:name "repl-up" :nrepl-port 49999}
                                    {:name "down"    :app-port nil :pg-port nil :nrepl-port nil}]})]
    (is (= #{"up" "also-up" "repl-up"} (#'tui/live-session-names "brian"))
        "a session is live iff it holds a pg/app/nrepl port")))

(deftest down-touches-no-workstream
  (let [calls (atom [])]
    (with-redefs [lifecycle/down! (fn [sn opts] (swap! calls conj [:down sn opts]))
                  scratch/birth!  (fn [_ _] (swap! calls conj :birth))
                  scratch/reap!   (fn [_ _] (swap! calls conj :reap))]
      (#'tui/run-session-action! :down "brian" "refshot")
      (is (= [[:down "refshot" {:project "brian"}]] @calls)
          "down has no birth/reap hook"))))
