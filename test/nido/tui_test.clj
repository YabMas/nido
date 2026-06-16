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

(deftest board-footer-has-work-verbs
  ;; The board now has a unified footer with work verbs (origin-based board,
  ;; no per-source split). Task 4.2 will revisit once dead code is purged.
  (let [f (#'tui/footer {:screen :board :origin :all})]
    (is (re-find #"\[p\]romote" f) "board footer surfaces promote")
    (is (re-find #"\[d\]one" f) "board footer surfaces done")
    (is (re-find #"\[n\]ew" f) "board footer surfaces new")))

(deftest ref-sourced-footer-has-no-add
  ;; Origin-based board: all origins show the same footer (no per-source split).
  ;; Task 4.2 will revisit once dead code is purged.
  (let [f (#'tui/footer {:screen :board :origin :notion})]
    (is (re-find #"\[p\]romote" f) "board footer has promote")))

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
                nido.tui/selected-workstream (fn [_] {:ws-id "w1"})
                nido.tui/enter-session (fn [s _ sn _] [(assoc s ::opened sn) nil])]
    (let [[s' _] (#'tui/update-board (board-state :all) (msg/key-press "o"))]
      (is (= "live" (::opened s')) "open resolves the session via work/open-target"))))

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
      (let [[s' _] (#'tui/update-board (board-state :all) (msg/key-press "d"))]
        (is (= [["w1" :done]] @calls) "d → set-stage! :done")))))

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
