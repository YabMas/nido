(ns nido.tui-test
  (:require
   [charm.message :as msg]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.scratch :as scratch]
   [nido.coordinator.triggers]
   [nido.project :as project]
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

(deftest board-footer-has-work-verbs
  ;; The unified spine board surfaces the work verbs in one footer (no per-source
  ;; split). System levers live on the system surface, not here.
  (let [f (#'tui/footer {:screen :board :origin :all})]
    (is (re-find #"\[p\]romote" f) "board footer surfaces promote")
    (is (re-find #"\[d\]one" f) "board footer surfaces done")
    (is (re-find #"\[n\]ew" f) "board footer surfaces new")
    (is (re-find #"\[s\]ystem" f) "board footer points at the system surface")
    (is (not (re-find #"\[h\]alt" f)) "no coordinator levers on the board footer")))

(deftest board-footer-is-origin-agnostic
  ;; Origin is a filter/badge, not a screen — the footer is identical regardless
  ;; of which origin is active.
  (is (= (#'tui/footer {:screen :board :origin :all})
         (#'tui/footer {:screen :board :origin :notion}))))

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

(defn- board-state [origin]
  {:screen :board :origin origin :project "brian" :list (#'tui/list-component [])})

(deftest board-open-routes-through-open-target
  (with-redefs [nido.work/open-target (fn [_ _] {:project :brian :session "live"})
                nido.work/reclaimed? (fn [_ _ _] false)
                nido.tui/selected-workstream (fn [_] {:ws-id "w1"})
                nido.tui/enter-session (fn [s _ sn _] [(assoc s ::opened sn) nil])]
    (let [[s' _] (#'tui/update-board (board-state :all) (msg/key-press "o"))]
      (is (= "live" (::opened s')) "open resolves the session via work/open-target"))))

(deftest board-open-rehydrates-a-reclaimed-session
  (with-redefs [nido.work/open-target (fn [_ _] {:project :brian :session "run-x"})
                nido.work/reclaimed? (fn [_ _ _] true)
                nido.tui/selected-workstream (fn [_] {:ws-id "w1"})]
    (let [[s' _] (#'tui/update-board (board-state :all) (msg/key-press "o"))]
      (is (= :rehydrate (-> s' :busy :verb))
          "a reclaimed home opens via an async re-hydrate spinner, not a hard error")
      (is (= "run-x" (-> s' :busy :subject))))))

(deftest rehydrated-message-enters-the-session
  (with-redefs [nido.tui/enter-session (fn [s _ sn _] [(assoc s ::opened sn) nil])]
    (let [state (assoc (board-state :all) :busy {:verb :rehydrate :subject "run-x"})
          [s' _] (#'tui/update-fn state {:type :nido.tui/rehydrated :project :brian :session "run-x"})]
      (is (= "run-x" (::opened s')) "::rehydrated chains into enter-session once the home is back")
      (is (nil? (:busy s')) "and clears the busy spinner"))))

(deftest board-promote-uses-default-target
  (with-redefs [nido.tui/selected-workstream (fn [_] {:ws-id "w1" :promote-id "BR-1"})
                nido.work/default-target (fn [_ action] (is (= :promote action)) :in-progress)
                nido.work/set-stage! (fn [_ id target] {:decision :promote :id id :target target})
                nido.tui/current-rows (constantly [])]
    (let [[s' _] (#'tui/update-board (board-state :all) (msg/key-press "p"))]
      (is (re-find #"promoted|in progress" (:status s'))))))

(deftest board-done-sets-stage-done
  (let [calls (atom [])]
    (with-redefs [nido.tui/selected-workstream (fn [_] {:ws-id "w1" :br-id "BR-1"})
                  nido.work/set-stage! (fn [_ id target] (swap! calls conj [id target]) {:decision :done})
                  nido.tui/current-rows (constantly [])]
      (#'tui/update-board (board-state :all) (msg/key-press "d"))
      (is (= [["w1" :done]] @calls) "d → set-stage! :done"))))

(deftest board-x-dismisses-selected-workstream
  (let [calls (atom [])]
    (with-redefs [nido.tui/selected-workstream (fn [_] {:ws-id "w1" :br-id "BR-1"})
                  nido.work/dismiss! (fn [p id] (swap! calls conj [p id]) {:decision :dismissed})
                  nido.tui/current-rows (constantly [])]
      (let [[s' _] (#'tui/update-board (board-state :all) (msg/key-press "x"))]
        (is (= [["brian" "w1"]] @calls) "x → work/dismiss!")
        (is (re-find #"dismissed" (:status s')))))))

(deftest board-n-opens-create-session
  (let [[s' _] (#'tui/update-board (board-state :all) (msg/key-press "n"))]
    (is (= :create-session (:modal s')) "n opens the new-workstream modal"))
  (with-redefs [nido.tui/session-rows (constantly [])]
    (is (= :system (:screen (first (#'tui/update-board (board-state :all) (msg/key-press "s")))))
        "s opens the system surface")))

(deftest board-tab-cycles-origin-filter
  (with-redefs [nido.tui/current-rows (constantly [])]
    (let [[s' _] (#'tui/update-board (board-state :all) (msg/key-press "tab"))]
      (is (= :notion (:origin s'))))))

(deftest detail-rows-render-sessions-on-the-autonomy-axis
  (with-redefs [nido.work/workstream
                (fn [_ _]
                  {:ws-id "w1" :origin :notion :stage :triage :label "BR-1 · a"
                   :ledger {:key "BR-1" :status :investigating :report-count 1}
                   :sessions [{:name "auto" :autonomy-level :autonomous :parked? true
                               :status :parked :brakes {:budget "30m"}}
                              {:name "me" :autonomy-level :interactive :parked? false
                               :status :up :brakes nil}]})]
    (let [rows (#'tui/detail-rows "brian" "w1")
          titles (mapv :title rows)]
      (is (some #(re-find #"ledger: BR-1" %) titles) "ledger line rendered first")
      (is (some #(re-find #"auto" %) titles))
      (is (some #(re-find #"parked|autonomous" %) titles) "autonomy state shown")
      (is (some #(re-find #"me" %) titles))
      (is (some #(= "auto" (-> % :data :name)) rows) "session rows carry :name for open"))))

(deftest workstream-detail-transitions
  ;; esc → board (set-origin → current-rows); stub current-rows for hermeticity.
  (with-redefs [nido.tui/current-rows (constantly [])]
    (let [st (assoc (board-state :all) :screen :workstream :ws-id "w1" :ws-label "x")
          [back _] (#'tui/update-workstream st (msg/key-press "escape"))]
      (is (= :board (:screen back)) "esc returns to the board")))
  ;; ↵ opens the highlighted session via enter-session
  (with-redefs [nido.tui/selected-data (fn [_] {:name "sess"})
                nido.tui/enter-session (fn [s _ sn _] [(assoc s ::opened sn) nil])]
    (let [st (assoc (board-state :all) :screen :workstream)
          [s' _] (#'tui/update-workstream st (msg/key-press "enter"))]
      (is (= "sess" (::opened s')) "enter opens the highlighted session"))))

(deftest stage-picker-promotes-to-the-chosen-target
  (let [calls (atom [])]
    (with-redefs [nido.tui/selected-workstream (fn [_] {:ws-id "w1" :promote-id "BR-1"})
                  nido.work/set-stage! (fn [_ id t] (swap! calls conj [id t]) {:decision :advanced})
                  nido.tui/current-rows (constantly [])]
      (let [opened (first (#'tui/open-stage-picker (board-state :all)))]
        (is (= :stage-picker (:modal opened)))
        (let [picked (assoc-in opened [:modal-target :picker]
                               (#'tui/picker-list [{:title "ready" :data :ready}]))
              [s' _] (#'tui/update-stage-picker picked (msg/key-press "enter"))]
          (is (= [["w1" :ready]] @calls) "P → set-stage! to the picked stage")
          (is (nil? (:modal s')) "picker closes after pick"))))))

(deftest system-surface-opens-from-board-and-returns
  ;; enter-system reads session-rows; esc → set-origin → current-rows. Stub both.
  (with-redefs [nido.tui/session-rows (constantly [])
                nido.tui/current-rows (constantly [])]
    (let [opened (#'tui/enter-system (board-state :all))]
      (is (= :system (:screen opened)))
      (let [[back _] (#'tui/update-system opened (msg/key-press "escape"))]
        (is (= :board (:screen back)) "esc returns to the board")))))

(deftest system-down-runs-the-async-action
  (with-redefs [nido.tui/selected-data (fn [_] {:name "sess"})
                nido.tui/start-session-down (fn [s _ sn] [(assoc s ::down sn) nil])]
    (let [st (assoc (board-state :all) :screen :system)
          [s' _] (#'tui/update-system st (msg/key-press "d"))]
      (is (= "sess" (::down s')) "d on the system surface stops the highlighted session"))))

(deftest system-x-opens-confirm-destroy
  (with-redefs [nido.tui/selected-data (fn [_] {:name "sess"})]
    (let [st (assoc (board-state :all) :screen :system)
          [s' _] (#'tui/update-system st (msg/key-press "x"))]
      (is (= :confirm-destroy (:modal s'))))))

(deftest system-f-opens-fire-trigger
  ;; Stub the project list (1 project skips the project picker) and triggers so
  ;; fire opens the trigger picker in its no-triggers error state — hermetic.
  (with-redefs [nido.project/list-projects (constantly {"brian" {}})
                nido.coordinator.triggers/load-for-project (constantly [])]
    (let [st (assoc (board-state :all) :screen :system)
          [s' _] (#'tui/update-system st (msg/key-press "f"))]
      (is (= :fire-pick-trigger (:modal s'))))))

(deftest board-no-longer-handles-system-levers
  (doseq [k ["f" "h" "c"]]
    (let [[s' _] (#'tui/update-board (board-state :all) (msg/key-press k))]
      (is (nil? (:modal s')) (str "board key " k " no longer opens a coordinator modal")))))

(deftest board-rows-shows-queue-band
  (with-redefs [nido.work/grouped
                (constantly {:inbox [{:origin :slack :stage :inbox :needs-you true
                                      :label "the app crashed" :engagement :idle
                                      :last-activity "2026-06-02T00:00:00Z"}]
                             :ready [] :in-progress [] :triage {:in-flight [] :queued []}})
                nido.tui/live-session-names (constantly #{})]
    (let [titles (map :title (#'nido.tui/board-rows :brian :all))]
      (is (some #(str/includes? % "Queue (1)") titles))
      (is (str/includes? (first titles) "Queue")
          "Queue band renders first (inbox-first spec requirement)"))))
