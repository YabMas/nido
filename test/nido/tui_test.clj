(ns nido.tui-test
  (:require
   [charm.message :as msg]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.scratch :as scratch]
   [nido.session.lifecycle :as lifecycle]
   [nido.tui :as tui]
   [nido.work]))

(deftest origin-filter-cycles-all-then-each-origin
  (is (= [:all :notion :github :slack :scratch] (mapv :id @#'tui/origin-filters)))
  (is (= :notion  (#'tui/step-origin :all 1)))
  (is (= :scratch (#'tui/step-origin :slack 1)))
  (is (= :all     (#'tui/step-origin :scratch 1)) "wraps forward")
  (is (= :scratch (#'tui/step-origin :all -1)) "wraps back"))

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

(deftest origin-badge-tags-each-source
  (is (= "N" (#'tui/origin-badge :notion)))
  (is (= "G" (#'tui/origin-badge :github)))
  (is (= "S" (#'tui/origin-badge :slack)))
  (is (= "·" (#'tui/origin-badge :scratch))))

(deftest board-rows-group-by-spine-and-filter-by-origin
  (with-redefs [nido.work/grouped
                (fn [_ _]
                  {:ready       [{:ws-id "r1" :origin :notion :label "BR-1 · a"
                                  :needs-you true :engagement :idle}]
                   :in-progress [{:ws-id "p1" :origin :scratch :label "spike"
                                  :needs-you false :engagement :active}]
                   :triage      {:in-flight [] :queued []}})
                nido.tui/live-session-names (constantly #{})]
    (let [all (#'tui/board-rows "brian" :all)
          labels (keep #(get-in % [:data :ws-id]) all)]
      (is (= ["r1" "p1"] (vec labels)) "ready then in-progress, all origins")
      (is (some #(re-find #"Ready to pick up" (:title %)) all))
      (is (some #(re-find #"In progress" (:title %)) all)))
    (let [scratch-only (#'tui/board-rows "brian" :scratch)
          ids (keep #(get-in % [:data :ws-id]) scratch-only)]
      (is (= ["p1"] (vec ids)) "origin filter keeps only scratch rows"))))
