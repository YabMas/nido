(ns nido.tui-test
  (:require
   [babashka.fs :as fs]
   [charm.components.text-input :as text-input]
   [charm.message :as msg]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.pickup :as pickup]
   [nido.coordinator.scratch :as scratch]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.triggers]
   [nido.coordinator.workstream :as workstream]
   [nido.notion.client :as client]
   [nido.project :as project]
   [nido.session.dev]
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
  ;; :ready is no longer a band (backlog lives in Notion) — a :ready row supplied
  ;; via work/grouped simply doesn't render on the board. Feed a real :ready row
  ;; here so this genuinely proves board-rows drops it, rather than passing
  ;; vacuously because the mock never carried a :ready key at all.
  (with-redefs [nido.work/grouped
                (fn [_ _]
                  {:in-progress [{:ws-id "p1" :origin :scratch :label "spike"
                                  :needs-you false :engagement :active}]
                   :ready       [{:ws-id "r1" :origin :notion :label "BR-9 · ready row"
                                  :needs-you false :engagement :idle}]
                   :triage      {:in-flight [] :queued []}})
                nido.work/live-session-names (constantly #{})]
    (let [all (#'tui/board-rows "brian" :all)
          labels (keep #(get-in % [:data :ws-id]) all)]
      (is (= ["p1"] (vec labels)) "in-progress renders, no :ready band")
      (is (some #(re-find #"In progress" (:title %)) all))
      (is (not (some #(re-find #"Ready to pick up" (:title %)) all)) "no :ready band header"))
    (let [scratch-only (#'tui/board-rows "brian" :scratch)
          ids (keep #(get-in % [:data :ws-id]) scratch-only)]
      (is (= ["p1"] (vec ids)) "origin filter keeps only scratch rows"))))

(deftest board-folds-intake-queues-by-default
  ;; The intake queues — Queue (inbox/Slack) and Triage·queued — start collapsed:
  ;; header + count only, no selectable item rows. Engaged work stays expanded.
  (with-redefs [nido.work/grouped
                (fn [_ _]
                  {:incoming  [{:ws-id "s1" :origin :slack :label "can you link it"
                             :needs-you true :engagement :idle}]
                   :triage {:in-flight [{:ws-id "f1" :origin :slack :label "teacher report"
                                         :needs-you true :engagement :parked}]
                            :queued    [{:ws-id "q1" :origin :notion :label "BR-1 · a"
                                         :needs-you true :engagement :idle}
                                        {:ws-id "q2" :origin :notion :label "BR-2 · b"
                                         :needs-you true :engagement :idle}]}})
                nido.work/live-session-names (constantly #{})]
    (let [rows   (#'tui/board-rows "brian" :all)
          ids    (set (keep #(get-in % [:data :ws-id]) rows))
          titles (map :title rows)]
      (is (not (contains? ids "s1")) "inbox item hidden while Queue is folded")
      (is (not (contains? ids "q1")) "queued item hidden while Triage·queued is folded")
      (is (contains? ids "f1") "in-flight item stays visible (expanded)")
      (is (some #(re-find #"▸ Queue \(1\)" %) titles) "folded Queue shows ▸ + count")
      (is (some #(re-find #"▸ Triage · queued \(2\)" %) titles) "folded queued shows ▸ + count")
      (is (some #(re-find #"▾ Triage · in flight \(1\)" %) titles) "expanded band shows ▾")
      (is (some #(= {:nido.tui/band :triage-queued} (:data %)) rows)
          "the queued header carries its band key so the fold toggle can flip it"))))

(deftest board-rows-expands-a-band-when-not-collapsed
  ;; With nothing in the collapsed set, Triage·queued renders its items as real,
  ;; selectable workstream rows — the path that makes them promotable.
  (with-redefs [nido.work/grouped
                (fn [_ _]
                  {:triage {:in-flight []
                            :queued [{:ws-id "q1" :origin :notion :label "BR-1 · a"
                                      :needs-you true :engagement :idle}]}})
                nido.work/live-session-names (constantly #{})]
    (let [rows (#'tui/board-rows "brian" :all #{})
          ids  (keep #(get-in % [:data :ws-id]) rows)]
      (is (= ["q1"] (vec ids)) "an expanded Triage·queued renders its item as a selectable row")
      (is (some #(re-find #"▾ Triage · queued \(1\)" (:title %)) rows) "and marks the band expanded"))))

(defn- board-state [origin]
  {:screen :board :origin origin :project "brian" :list (#'tui/list-component [])})

(deftest main-list-scrolls-within-terminal-height
  (let [items (mapv (fn [n] {:title (str "session-" n) :description "" :data {:name (str "session-" n)}})
                    (range 12))
        state (-> {:screen :system :project "brian" :size [100 14]}
                  (#'tui/rebuild-list items))
        [scrolled _] (reduce (fn [[s _] _] (#'tui/update-system s (msg/key-press "down")))
                             [state nil]
                             (range 7))]
    (is (pos? (get-in scrolled [:list :height]))
        "terminal-sized main lists must set a nonzero height so charm scrolls")
    (is (pos? (get-in scrolled [:list :offset]))
        "moving beyond the visible page scrolls the list instead of leaving the cursor off-screen")
    (is (not (str/includes? (#'tui/view scrolled) "session-0"))
        "the rendered list window advances after scrolling")))

(deftest window-size-resizes-main-list-immediately
  (let [items (mapv (fn [n] {:title (str "session-" n) :description "" :data {:name (str "session-" n)}})
                    (range 12))
        state (-> {:screen :system :project "brian" :size [100 40]}
                  (#'tui/rebuild-list items))
        old-height (get-in state [:list :height])]
    (with-redefs [nido.charm-patch/clear-on-resize! (fn [] nil)]
      (let [[resized _] (#'tui/update-fn state (msg/window-size 100 14))]
        (is (< (get-in resized [:list :height]) old-height)
            "the resize event applies the new terminal height to the list immediately")))))

(deftest empty-main-list-keeps-zero-scroll-offset
  (let [state (-> {:screen :system :project "brian" :size [100 14]}
                  (#'tui/rebuild-list []))]
    (is (= 0 (get-in (#'tui/resize-current-list state) [:list :offset]))
        "empty lists stay at offset zero")))

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

(deftest board-d-on-winding-down-row-arms-bring-down
  ;; d on a winding-down row is contextual: it arms the bring-down busy action
  ;; instead of falling through to done-selected.
  (let [state (#'tui/rebuild-list {:screen :board :project "p" :origin :all}
                                  [{:title "x" :description ""
                                    :data {:ws-id "w" :stage :winding-down :label "leftover"
                                           :sessions ["s1"]}}])
        [state' _] (#'tui/update-fn state (msg/key-press "d"))]
    (is (= :bring-down (get-in state' [:busy :verb])))
    (is (= "leftover" (get-in state' [:busy :subject])))))

(deftest board-x-dismisses-selected-workstream
  (let [calls (atom [])]
    (with-redefs [nido.tui/selected-workstream (fn [_] {:ws-id "w1" :br-id "BR-1"})
                  nido.work/dismiss! (fn [p id] (swap! calls conj [p id]) {:decision :dismissed})
                  nido.tui/current-rows (constantly [])]
      (let [[s' _] (#'tui/update-board (board-state :all) (msg/key-press "x"))]
        (is (= [["brian" "w1"]] @calls) "x → work/dismiss!")
        (is (re-find #"dismissed" (:status s')))))))

(deftest board-x-noop-on-notion-row
  (let [calls (atom [])]
    (with-redefs [nido.tui/selected-workstream (fn [_] {:ws-id "w1" :br-id "BR-1" :origin :notion})
                  nido.work/dismiss! (fn [p id] (swap! calls conj [p id]) {:decision :dismissed})
                  nido.tui/current-rows (constantly [])]
      (let [[s' _] (#'tui/update-board (board-state :all) (msg/key-press "x"))]
        (is (= [] @calls) "x does NOT dismiss a Notion row — Notion owns it")
        (is (re-find #"Notion" (:status s')) "status explains why")))))

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

(deftest space-toggles-the-band-under-the-cursor
  ;; Cursor on a folded Triage·queued header → space unfolds it (drops it from the
  ;; collapsed set); space again re-folds. A no-op when the cursor isn't on a band.
  (with-redefs [nido.tui/selected-data (fn [_] {:nido.tui/band :triage-queued})
                nido.tui/current-rows (constantly [])]
    (let [state  (assoc (board-state :all) :collapsed #{:incoming :triage-queued})
          [s1 _] (#'tui/update-board state (msg/key-press " "))]
      (is (= #{:incoming} (:collapsed s1)) "space unfolds the band under the cursor")
      (let [[s2 _] (#'tui/update-board s1 (msg/key-press " "))]
        (is (= #{:incoming :triage-queued} (:collapsed s2)) "space again re-folds it")))))

(deftest space-on-a-non-band-row-is-a-noop
  (with-redefs [nido.tui/selected-data (fn [_] {:ws-id "w1"})
                nido.tui/current-rows (constantly [])]
    (let [state  (assoc (board-state :all) :collapsed #{:triage-queued})
          [s' _] (#'tui/update-board state (msg/key-press " "))]
      (is (= #{:triage-queued} (:collapsed s')) "space leaves the fold set untouched on a workstream row"))))

(deftest board-footer-surfaces-the-fold-toggle
  (is (re-find #"fold" (#'tui/footer {:screen :board :origin :all}))
      "board footer documents the [space] fold toggle"))

(deftest detail-rows-render-sessions-on-the-autonomy-axis
  (with-redefs [nido.work/workstream
                (fn [_ _ & _]
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

(deftest detail-rows-shows-entry-index-and-report
  (with-redefs [nido.work/workstream
                (fn [_ _ & _]
                  {:ledger {:key "BR-1" :status :triaged :report-count 2}
                   :entries [{:seq 2 :kind :implementation-plan :at "t2" :title "Null-check"}
                             {:seq 1 :kind :triage :at "t1" :title "Firefox modal"}]
                   :selected-seq 2
                   :report {:format :implementation-plan :summary "do X" :direction "Null-check" :effort :S}
                   :sessions []})]
    (let [rows (#'nido.tui/detail-rows "brian" "ws-1")
          titles (map :title rows)]
      (is (some #(re-find #"Null-check" %) titles) "entry index present")
      (is (some #(re-find #"do X|Implementation plan" %) titles) "report body rendered"))))

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

(deftest detail-u-starts-the-selected-session
  ;; start-session-up builds a charm cmd (data — {:type :cmd :fn ...}) that the
  ;; event loop invokes asynchronously; update-workstream never calls its :fn,
  ;; so no lifecycle/scratch redefs are needed here — just the busy-state arm.
  (let [state (#'tui/rebuild-list {:screen :workstream :project "p" :ws-id "w"}
                                  [{:title "s" :description ""
                                    :data {:name "sess1" :autonomy-level :interactive}}])
        [state' _] (#'tui/update-workstream state (msg/key-press "u"))]
    (is (= :up (get-in state' [:busy :verb])))
    (is (= "sess1" (get-in state' [:busy :subject])))))

(deftest detail-w-on-a-non-session-row-sets-no-session-status
  ;; A ledger/report/header row has no :name in its :data — `w` must route
  ;; through with-selected-session like its u/d/x siblings, not silently no-op.
  (let [state (#'tui/rebuild-list {:screen :workstream :project "p" :ws-id "w"}
                                  [{:title "entry" :description "" :data {:kind :ledger-entry}}])
        [state' cmd] (#'tui/update-workstream state (msg/key-press "w"))]
    (is (= "(no session selected)" (:status state')))
    (is (nil? cmd))))

(deftest detail-footer-lists-the-plumbing-verbs
  (let [f (#'tui/footer {:screen :workstream})]
    (doseq [verb ["[u]p" "[d]own" "[x] destroy" "[w]orktree"]]
      (is (str/includes? f verb)))))

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
                (constantly {:incoming [{:origin :slack :stage :incoming :needs-you true
                                         :label "the app crashed" :engagement :idle
                                         :last-activity "2026-06-02T00:00:00Z"}]
                             :ready [] :in-progress [] :triage {:in-flight [] :queued []}})
                nido.work/live-session-names (constantly #{})]
    (let [titles (map :title (#'nido.tui/board-rows :brian :all))]
      (is (some #(str/includes? % "Queue (1)") titles))
      (is (str/includes? (first titles) "Queue")
          "Queue band renders first (inbox-first spec requirement)"))))

(deftest board-rows-include-winding-down-band
  ;; A closed workstream still holding live sessions renders as a trailing
  ;; "Winding down" band — the one place bring-down! applies.
  (with-redefs [nido.work/grouped
                (fn [_ _] {:incoming [] :in-progress [] :shipping []
                          :triage {:in-flight [] :queued []}
                          :winding-down [{:ws-id "w" :origin :scratch
                                          :label "leftover" :outcome :done
                                          :sessions ["s1"] :stage :winding-down}]})
                nido.work/live-session-names (constantly #{"s1"})]
    (let [titles (map :title (#'tui/board-rows :brian :all #{} {}))]
      (is (some #(str/includes? % "Winding down") titles))
      (is (some #(str/includes? % "leftover") titles)))))

;; ---------------------------------------------------------------------------
;; Task 8: composable facet sub-queue selectors
;; ---------------------------------------------------------------------------

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [cstate/nido-root (constantly (str tmp))] (cstate/ensure-dirs!) (f))
         (finally (fs/delete-tree tmp)))))

(deftest step-facet-cycles-all-then-present-values
  ;; pure helper: given the value list, stepping wraps through :all + values
  (is (= "Teacher" (#'tui/step-facet :all ["Teacher" "Student"] 1)))
  (is (= "Student" (#'tui/step-facet "Teacher" ["Teacher" "Student"] 1)))
  (is (= :all (#'tui/step-facet "Student" ["Teacher" "Student"] 1)) "wraps to :all"))

(deftest board-rows-filter-by-facet
  (with-tmp
    (fn []
      ;; redef the liveness oracle so the board doesn't hit lifecycle;
      ;; pass an EMPTY collapsed set so no band is folded out of the row list.
      (with-redefs [nido.work/live-session-names (constantly #{})]
        (workstream/create! :brian {:stage :triaging :external-refs [{:adapter :notion :id "BR-1"}]
                                    :facets {:app-domain ["Teacher"]}})
        (workstream/create! :brian {:stage :triaging :external-refs [{:adapter :notion :id "BR-2"}]
                                    :facets {:app-domain ["Student"]}})
        (let [n-ws (fn [ff] (->> (#'tui/board-rows :brian :all #{} ff)
                                 (keep :data) (filter map?) (keep :ws-id) count))]
          (is (= 2 (n-ws {:app-domain :all})) "both visible, no constraint")
          (is (= 1 (n-ws {:app-domain "Teacher"})) "only Teacher with the Teacher selector"))))))

(deftest facet-strip-hidden-on-non-facet-origin
  ;; facet-strip should render nothing on non-facet-bearing origins (:slack,
  ;; :github, :scratch) even when dimensions are configured.
  (with-redefs [nido.work/facet-dimensions (constantly [:app-domain])]
    (is (str/blank? (#'tui/facet-strip :brian :slack {:app-domain :all}))
        "facet-strip returns blank on :slack origin")
    (is (str/blank? (#'tui/facet-strip :brian :github {:app-domain :all}))
        "facet-strip returns blank on :github origin")
    (is (str/blank? (#'tui/facet-strip :brian :scratch {:app-domain :all}))
        "facet-strip returns blank on :scratch origin")
    (is (not (str/blank? (#'tui/facet-strip :brian :notion {:app-domain :all})))
        "facet-strip renders on :notion origin")
    (is (not (str/blank? (#'tui/facet-strip :brian :all {:app-domain :all})))
        "facet-strip renders on :all origin")))

;; ---------------------------------------------------------------------------
;; Task 4.3: gate Apply/Reply for parked workstreams
;; ---------------------------------------------------------------------------

(deftest apply-key-resolves-gate
  (let [calls (atom [])]
    (with-redefs [nido.work/resolve-gate! (fn [p w a & more]
                                            (swap! calls conj (into [p w a] more))
                                            {:resumed "auto"})]
      (#'nido.tui/apply-gate! "brian" "ws-1")
      (is (= [["brian" "ws-1" :apply]] @calls)))))

(deftest board-rows-ignores-facets-on-non-facet-origin
  ;; A non-empty facet-filter (e.g. {:app-domain "Teacher"}) must NOT filter rows
  ;; on :slack/:github/:scratch origins — those workstreams have no facets and
  ;; would disappear otherwise.
  (with-tmp
    (fn []
      (with-redefs [nido.work/live-session-names (constantly #{})]
        ;; Slack workstream: no facets
        (workstream/create! :brian {:stage :triaging
                                    :external-refs [{:adapter :slack-message :id "slack-C-1.0"}]})
        ;; Notion workstream: has facets
        (workstream/create! :brian {:stage :triaging
                                    :external-refs [{:adapter :notion :id "BR-1"}]
                                    :facets {:app-domain ["Teacher"]}})
        (let [count-ws (fn [origin ff]
                         (->> (#'tui/board-rows :brian origin #{} ff)
                              (keep :data) (filter map?) (keep :ws-id) count))]
          ;; :slack origin: facet filter is ignored → Slack ws still shows
          (is (= 1 (count-ws :slack {:app-domain "Teacher"}))
              "Slack workstream still shows on :slack origin even with Teacher facet filter active")
          ;; :notion origin: facet filter IS applied → only the Teacher ws shows
          (is (= 1 (count-ws :notion {:app-domain "Teacher"}))
              "Notion workstream shows on :notion origin with matching Teacher facet filter"))))))

;; ---------------------------------------------------------------------------
;; Task 4.4: per-session dev-environment controls in the workstream detail view
;; ---------------------------------------------------------------------------

(deftest dev-start-key-runs-dev-action
  (let [calls (atom [])]
    (with-redefs [nido.session.dev/dev-action!
                  (fn [p w s a] (swap! calls conj [p w s a]) (future nil))]
      (#'nido.tui/dev-start! "brian" "ws-1" "impl-br-1")
      (is (= [["brian" "ws-1" "impl-br-1" "start"]] @calls)))))

;; ---------------------------------------------------------------------------
;; Task 4.5: TUI new verb routes through work/new! (de-leak)
;; ---------------------------------------------------------------------------

(deftest new-verb-routes-through-work-new
  (let [calls (atom [])]
    (with-redefs [nido.work/new! (fn [p s] (swap! calls conj [p s]) "ws-9")]
      (#'nido.tui/create-workstream! "brian" "scratch-foo")
      (is (= [["brian" "scratch-foo"]] @calls)))))

;; ---------------------------------------------------------------------------
;; Item 1: TUI ledger-entry navigation — report follows the cursor
;; ---------------------------------------------------------------------------

(deftest detail-rows-renders-selected-entry-report
  ;; 3-arity detail-rows passes selected-seq to work/workstream and renders
  ;; the corresponding report body; the cursor-row entry is marked with ▶.
  (let [ws-calls (atom [])]
    (with-redefs [nido.work/workstream
                  (fn [_ _ sel]
                    (swap! ws-calls conj sel)
                    {:ledger nil
                     :entries [{:seq 9 :kind :triage :at "t9" :title "Triage v2"}
                               {:seq 5 :kind :triage :at "t5" :title "Triage v1"}]
                     :selected-seq sel
                     :report {:format :markdown
                              :markdown (str (if (= sel 5) "FIVE" "NINE") " report")}
                     :sessions []})]
      (let [rows   (#'nido.tui/detail-rows "brian" "ws-1" 5)
            titles (map :title rows)]
        (is (= [5] @ws-calls)
            "work/workstream was called with selected-seq 5")
        (is (some #(str/includes? % "FIVE") titles)
            "report body renders the text for the selected entry (seq 5)")
        (is (some #(and (= {:nido.tui/entry-seq 5} (:data %))
                        (str/starts-with? (:title %) "▶"))
                  rows)
            "entry row with seq 5 is marked with ▶")))))

(deftest workstream-cursor-on-entry-selects-it
  ;; When the cursor moves onto an entry row, update-workstream records
  ;; that entry's seq in :selected-entry-seq.
  (with-redefs [nido.work/workstream
                (fn [_ _ & _]
                  {:ledger nil
                   :entries [{:seq 7 :kind :triage :at "t7" :title "Entry 7"}]
                   :selected-seq 7
                   :report {:format :markdown :markdown "Report for 7"}
                   :sessions []})]
    (let [;; item 0: non-entry row; item 1: entry row with seq 7
          items [{:title "report line" :description "" :data :nido.tui/report-body}
                 {:title "entry 7"    :description "" :data {:nido.tui/entry-seq 7}}]
          ;; list-component starts with cursor at 0 (non-entry row)
          lst   (#'tui/list-component items)
          state {:screen           :workstream
                 :project          "brian"
                 :ws-id            "ws-1"
                 :ws-label         "ws-1"
                 :selected-entry-seq nil
                 :list             lst}
          ;; "down" moves cursor from item 0 to item 1 (the entry row)
          [s' _] (#'tui/update-workstream state (msg/key-press "down"))]
      (is (= 7 (:selected-entry-seq s'))
          "navigating onto an entry row updates :selected-entry-seq to that entry's seq"))))

;; ---------------------------------------------------------------------------
;; Task 3 (pickup design): TUI pickup input box — drive a Notion ticket by
;; URL/id from the system surface, no board browsing.
;; ---------------------------------------------------------------------------

(deftest system-p-opens-pickup-input
  (let [st (assoc (board-state :all) :screen :system)
        [s' _] (#'tui/update-system st (msg/key-press "p"))]
    (is (= :pickup-input (:modal s')) "p on the system surface opens the pickup modal")))

(deftest pickup-input-submit-drives-the-resolved-ticket
  (let [calls (atom [])]
    (with-redefs [pickup/pickup! (fn [project input token]
                                   (swap! calls conj [project input token])
                                   {:decision :driving :ref {:id "BR-42"} :queued {:id 1}})
                  client/keychain-token (constantly "tok-123")]
      (let [opened (first (#'tui/open-pickup-input (assoc (board-state :all) :screen :system)))
            typed  (assoc opened :modal-input
                          (text-input/set-value (:modal-input opened) "  BR-42  "))
            [s' _] (#'tui/update-pickup-input typed (msg/key-press "enter"))]
        (is (= [["brian" "BR-42" "tok-123"]] @calls)
            "submit trims the typed input and passes project + keychain token to pickup!")
        (is (nil? (:modal s')) "submit closes the modal")
        (is (str/includes? (:status s') "driving BR-42")
            "status reflects the :driving decision")))))

(deftest pickup-input-submit-reports-an-unresolved-ref
  (with-redefs [pickup/pickup! (constantly {:decision :unresolved :error :not-found})
                client/keychain-token (constantly "tok-123")]
    (let [opened (first (#'tui/open-pickup-input (assoc (board-state :all) :screen :system)))
          typed  (assoc opened :modal-input (text-input/set-value (:modal-input opened) "junk"))
          [s' _] (#'tui/update-pickup-input typed (msg/key-press "enter"))]
      (is (nil? (:modal s')) "submit closes the modal even when unresolved")
      (is (str/includes? (:status s') "could not resolve")
          "status reflects the :unresolved decision"))))

(deftest pickup-input-escape-cancels
  (let [opened (first (#'tui/open-pickup-input (assoc (board-state :all) :screen :system)))
        [s' _] (#'tui/update-pickup-input opened (msg/key-press "escape"))]
    (is (nil? (:modal s')) "esc cancels without calling pickup!")))
