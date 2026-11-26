(ns nido.coordinator.work-test
  (:require
   [nido.test.fake-core :as fake-core]
   [nido.test.units :as units]
   [nido.coordinator.record.history :as history]
 [babashka.fs :as fs]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.core.client :as client]
   [nido.platform.core :as core]
   [nido.platform.config]
   [nido.coordinator.lane.start :as start]
   [nido.coordinator.lane.facets]
   [nido.coordinator.lane.pipeline :as pipeline]
   [nido.coordinator.record.standing :as standing]
   [nido.coordinator.report :as report]
   [nido.coordinator.report.ask :as ask]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.session-events :as session-events]
   [nido.coordinator.source.state]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.tickets :as tickets]
   [nido.coordinator.record.triggers :as triggers]
   [nido.coordinator.record.proposal :as proposal]
   [nido.coordinator.record.records :as records]
   [nido.test.runs :as test-runs]
   [nido.coordinator.record.workstream :as workstream]
   [nido.coordinator.view.workstreams :as wsv]
   [nido.platform.io :as nido-io]
   [nido.notion.client :as notion-client]
   [nido.notion.views :as views]
   [nido.slack.client :as slack-client]
   [nido.platform.project]
   [nido.platform.process]
   [nido.session.lifecycle]
   [nido.session.state]
   [nido.coordinator.work :as work]))

(defn- write-run!
  "A run owning session `sname` on `ws-id`, registered with the workstream as create-run! does —
   parked for review unless `state` says otherwise."
  ([id ws-id sname sid] (write-run! id ws-id sname sid :awaiting-review))
  ([id ws-id sname sid state]
   (fs/create-dirs (cstate/run-dir id))
   (runs/write-run! {:id id :project :brian :trigger :triage-bug :source {:type :manual}
                     :event-payload {} :skill :triage-bug :first-message "/triage-bug"
                     :agent :claude :session-name sname :workstream-id ws-id
                     :claude-session-id sid :limits {:budget "30m"} :priority 0
                     :session-profile :lite :uncapped? false :state state
                     :state-history [{:at "2026-06-18T00:00:00Z" :state :queued}]
                     :artifacts [] :error nil})
   (runs/register! id)))

(defn- one-off!
  "A ref-less workstream born a one-off: its ledger holds the :scratch birth entry the
   one-offs minted for a person's sessions carried."
  []
  (let [w (workstream/create! :brian {:external-refs []})]
    (workstream/append-entry! :brian (:id w) {:kind :scratch} (pr-str {:format :scratch}))
    (workstream/read-ws :brian (:id w))))


(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(defn- triage-report
  "A typed triage verdict for `ticket` — what a triage run leaves for a person to accept."
  [ticket]
  (pr-str {:format :triage-report :ticket-key ticket :determination :bug
           :title "Verdict" :summary "bug — reproduced."
           :confidence {:level :high :reason "r"}
           :routing nil :questions [] :notion-writes nil :trail []}))

(defn- triaged!
  "A triage verdict on `ws-id`, and — when `accepted?` — a person's acceptance of it."
  [ws-id ticket accepted?]
  (let [path (workstream/append-entry! :brian ws-id {:kind :triage} (triage-report ticket))]
    (when accepted?
      (workstream/append-entry! :brian ws-id {:kind :triage-accepted}
                                (pr-str {:format :triage-accepted
                                         :triage-seq path})))))

(deftest stages-is-the-canonical-spine
  ;; STORED stage names (see spine-is-exactly-the-stages-the-board-honors).
  (is (= [:incoming :triage :ready :in-progress :shipping :done] work/stages)))

(deftest board-bands-is-the-driven-work
  (let [grouped {:incoming [{:ws-id "i"}]
                 :triage {:in-flight [{:ws-id "tf"}] :queued [{:ws-id "tq"}]}
                 :in-progress [{:ws-id "p"}]
                 :shipping [{:ws-id "s"}]
                 :winding-down [{:ws-id "w"}]
                 :dismissed [{:ws-id "d"}]}]
    (is (= [[:shipping ["s"]] [:in-progress ["p"]] [:triage ["tf"]] [:winding-down ["w"]]]
           (for [[stage rows] (work/board-bands grouped)]
             [stage (mapv :ws-id rows)]))
        "shipping, in-progress, then the triages nido has a session on, then winding-down;
         queued triage and the rest of intake are not banded")))

(deftest board-bands-covers-every-driven-row-exactly-once
  ;; The guarantee this whole design exists for: nothing nido drives can be
  ;; hidden by default again. Every row the model emits outside the intake
  ;; stages is on exactly one band.
  ;;
  ;; :analysed is the other deliberate exception and it is checked below rather
  ;; than left implicit — a workstream holding a review-loop analysis is not work
  ;; and has no arc, so the board draws no row for it and the operations surface
  ;; is where it is reachable.
  (let [grouped {:incoming [{:ws-id "i"}]
                 :triage {:in-flight [{:ws-id "tf"}] :queued [{:ws-id "tq"}]}
                 :in-progress [{:ws-id "p"}]
                 :shipping [{:ws-id "s"}]
                 :awaiting-gate [{:ws-id "g"}]
                 :winding-down [{:ws-id "w"}]
                 :dismissed [{:ws-id "d"}]}
        intake  #{"i" "tq" "d"}
        board   (map :ws-id (mapcat second (work/board-bands grouped)))]
    (is (= (set/difference (set (map :ws-id (work/grouped-rows grouped))) intake)
           (set board))
        "the board = every row grouped-rows emits, less intake")
    (is (= (count board) (count (set board)))
        "and no row appears on two bands")))

(deftest analysed-rows-are-off-the-board-on-both-sides-or-neither
  ;; The coverage oracle above only holds because board-bands and grouped-rows
  ;; agree about :analysed. If a later edit teaches one of them about the band
  ;; and not the other, that test starts failing for a reason nobody will connect
  ;; to this; so the agreement is asserted here, where the reason is written down.
  (let [grouped {:in-progress [{:ws-id "p"}] :analysed [{:ws-id "a"}]}]
    (is (= #{"p"} (set (map :ws-id (work/grouped-rows grouped))))
        "grouped-rows does not emit an analysed row")
    (is (= #{"p"} (set (map :ws-id (mapcat second (work/board-bands grouped)))))
        "and the board draws none")
    (is (contains? @#'work/unrendered-bands :analysed)
        "so it must be an unrendered band — otherwise with-position pays to place
         a row nothing will draw")))

(deftest a-workstream-holding-an-analysis-is-not-work
  ;; What put ten analyses in nido's Intake asking to establish intent: they
  ;; arrived through no source ws-source knew, fell to the :notion default, and
  ;; were projected as ordinary un-triaged tickets.
  (with-tmp
    (fn [_]
      (let [mk    #(:id (workstream/create! :brian {:external-refs [{:adapter :review-run :id %}]}))
            open  (mk "r1")
            gone  (mk "r2")
            done  (mk "r3")
            stage #(:stage (first (filter (comp #{%} :ws-id) (work/list-workstreams :brian))))]
        (work/dismiss! :brian gone)
        (workstream/close! :brian done :done)
        (is (= :analysed (stage open))
            "a review-run row folds off the board")
        (is (= :dismissed (stage gone))
            "a dismissed one still reads dismissed — that is a human's decision and it outranks
             what kind of record this is")
        (is (= :done (stage done))
            "and a closed one still reads done")))))

(deftest winding-down-lists-closed-ws-with-live-sessions
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :p {:external-refs []})]
        (units/open-unit! :p (:id w) "u1" {:session "s1"})
        (workstream/close! :p (:id w) :done)
        (let [[row :as rows] (work/winding-down :p #{"s1"})]
          (is (= 1 (count rows)))
          (is (= (:id w) (:ws-id row)))
          (is (= :winding-down (:stage row)))
          (is (= :done (:outcome row)))
          (is (= ["s1"] (:sessions row)))
          (is (false? (:needs-you row))))
        (is (= [] (work/winding-down :p #{})) "downed sessions → gone")))))

(deftest winding-down-ignores-open-workstreams
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :p {:external-refs []})]
        (session/create! :p (:id w) {:name "s1" :weight :light :autonomy nil})
        (is (= [] (work/winding-down :p #{"s1"})))))))

(deftest winding-down-catches-a-row-notion-finished
  ;; A ticket that reached Review (or Done) projects :done with no :closed record
  ;; — stateless by design. Without the row carrier it drops off the board while
  ;; its session still holds ports.
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :p {:external-refs []})]
        (units/open-unit! :p (:id w) "u1" {:session "s1"})
        (is (= [] (work/winding-down :p #{"s1"} [{:ws-id (:id w) :stage :in-progress}]))
            "still in flight → not winding down")
        (let [[row :as rows] (work/winding-down :p #{"s1"} [{:ws-id (:id w) :stage :done}])]
          (is (= 1 (count rows)))
          (is (= (:id w) (:ws-id row)))
          (is (= :done (:outcome row)) "no :closed record to name an outcome")
          (is (= ["s1"] (:sessions row)))
          (is (nil? (workstream/closure (workstream/read-ws :p (:id w))))
              "the band is a read — the projection must not settle the record"))))))

(deftest bring-down!-downs-only-live-sessions
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :p {:external-refs []})
            downed (atom [])]
        (units/open-unit! :p (:id w) "u1" {:session "live1"})
        (units/open-unit! :p (:id w) "u2" {:session "dead1"})
        (workstream/close! :p (:id w) :done)
        (with-redefs [work/live-session-names (constantly #{"live1"})
                      nido.session.lifecycle/down! (fn [n _] (swap! downed conj n))]
          (is (= {:downed ["live1"]} (work/bring-down! :p (:id w))))
          (is (= ["live1"] @downed)))))))

(deftest screen-marks-pending-winding-down-rows
  (let [groups [{:project "p" :grouped {:winding-down [{:ws-id "w1"} {:ws-id "w2"}]}}]
        screen (work/screen {:scope "all"}
                            {:groups groups :winddown-pending #{"p/w1"}})]
    (is (= [true false]
           (->> screen :groups first :grouped :winding-down (map (comp boolean :pending?)))))))

(deftest board-bands-drops-empty-bands-and-tolerates-absent-keys
  (is (= [] (work/board-bands {:in-progress [] :shipping []}))
      "empty bands are dropped")
  (is (= [] (work/board-bands {}))
      "absent keys are not an error")
  (is (= [[:in-progress ["p"]]]
         (for [[stage rows] (work/board-bands {:in-progress [{:ws-id "p"}] :shipping []})]
           [stage (mapv :ws-id rows)]))
      "a band with rows survives while its empty sibling is dropped"))

(deftest classify-origin-delegates-to-source-classifier
  (is (= :scratch (work/classify-origin {:external-refs [] :entries [{:kind :scratch :seq 1}]})))
  (is (= :notion  (work/classify-origin {:stage :triaging
                                         :external-refs [{:adapter :notion :id "BR-1"}]})))
  (is (= :github  (work/classify-origin {:stage :ready
                                         :external-refs [{:adapter :github-issue :id "o/r#1"}]})))
  (is (= :slack   (work/classify-origin {:stage :triaging
                                         :external-refs [{:adapter :slack-message :id "slack-C1-1.0"}]}))))

(deftest list-workstreams-folds-scratch-into-in-progress
  (with-tmp
    (fn [_]
      (let [w (one-off!)]
        (session/create! :brian (:id w) {:name "poke" :weight :light :autonomy nil}))
      (let [row (first (work/list-workstreams :brian))]
        (is (= :scratch (:origin row)) "origin preserved")
        (is (= :in-progress (:stage row)) "scratch enters the spine at in-progress")
        (is (nil? (:source row)) ":source is renamed to :origin")))))

(deftest list-workstreams-settled-scratch-reads-done
  (with-tmp
    (fn [_]
      (let [w (one-off!)]
        (session/create! :brian (:id w) {:name "poke" :weight :light :autonomy nil})
        (workstream/close! :brian (:id w) :done))
      (is (= :done (:stage (first (work/list-workstreams :brian))))
          "a closed scratch workstream is :done, not :in-progress"))))

(deftest list-workstreams-preserves-ref-stage
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:external-refs [{:adapter :notion :id "BR-9" :title "t"}]})]
        (tickets/open! :brian "BR-9" {:title "t"})
        (triaged! (:id w) "BR-9" true)
        (session/create! :brian (:id w) {:name "s" :weight :light :autonomy nil}))
      (let [row (first (work/list-workstreams :brian))]
        (is (= :notion (:origin row)))
        (is (= :ready (:stage row)) "an accepted triage projects to :ready")))))

(deftest a-ticket-settled-in-notion-after-a-stage-choice-is-done
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:external-refs [{:adapter :notion :id "BR-11" :title "t"}]})
            notion! (fn [status]
                      (workstream/append-entry! :brian (:id w) {:kind :notion-status}
                                                (pr-str {:format :notion-status :page-id "p"
                                                         :status status :by :poller})))
            stage   #(:stage (first (filter (comp #{(:id w)} :ws-id) (work/list-workstreams :brian))))]
        (workstream/advance-stage! :brian (:id w) :ready :person)
        (is (= :ready (stage)))
        (notion! "Done")
        (is (= :done (stage)) "a settlement newer than the stage choice wins")
        (let [st (pipeline/status :brian (:id w))]
          (is (= :nobody (:owed-by st)) "status owes nobody the move on a ticket Notion settled")
          (is (= "Done" (get-in st [:position :settled-in-notion]))))
        (workstream/advance-stage! :brian (:id w) :in-progress :person)
        (is (= :in-progress (stage)) "a stage chosen after the settlement takes it back out")))))

(deftest a-ticket-in-notion-review-has-left-the-board
  ;; Review settles a ticket as far as nido is concerned: it is in a person's hands outside nido,
  ;; so it leaves the board even when its ledger still holds an unaccepted triage (BR-6454).
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:external-refs [{:adapter :notion :id "BR-12" :title "t"}]})]
        (triaged! (:id w) "BR-12" false)
        (workstream/append-entry! :brian (:id w) {:kind :notion-status}
                                  (pr-str {:format :notion-status :page-id "p" :status "Review"
                                           :by :poller}))
        (let [row (first (filter (comp #{(:id w)} :ws-id) (work/list-workstreams :brian)))]
          (is (= :done (:stage row)))
          (is (not (:needs-you row)) "and it asks nobody for a triage call"))))))

(deftest a-status-entry-does-not-displace-the-work-report
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian
                {:stage :incoming
                 :external-refs [{:adapter :slack-message :id "slack-C-2.0"}]
                 :intake {:trigger :triage-slack-bugs
                          :payload {:id "slack-C-2.0" :text "the app crashed on load"}}})]
        (workstream/advance-stage! :brian (:id w) :triaging :person)
        (is (= "the app crashed on load" (:markdown (work/latest-report :brian (:id w))))
            "a status-only ledger still falls back to the intake text")
        (workstream/append-entry! :brian (:id w) {:kind :note} "# Finding")
        (workstream/advance-stage! :brian (:id w) :ready :person)
        (let [pane (work/workstream :brian (:id w))]
          (is (= "# Finding" (:markdown (work/latest-report :brian (:id w)))))
          (is (= "# Finding" (:markdown (:action-report pane))))
          (is (:on-latest? (work/workstream :brian (:id w) (:seq (work/latest-report :brian (:id w)))))
              "the work entry stays current after a status entry lands on it"))))))

(deftest dismissed-rows-project-to-the-dismissed-stage
  (with-tmp
    (fn [_]
      ;; Slack-origin: derive-stage would fold :dismissed to :done, and the
      ;; closed ws reads :settled — the :dismissed? clause must beat both.
      (let [w (workstream/create! :brian
                {:stage :triage
                 :external-refs [{:adapter :slack-message :id "slack-C1-1.2" :title "t"}]})]
        (tickets/open! :brian "slack-C1-1.2" {:title "t"})
        (work/dismiss! :brian (:id w))
        (is (= [:dismissed] (map :stage (work/list-workstreams :brian)))
            "a dismissal is the close's outcome, and its band is :dismissed, not :done")
        (let [row (first (work/list-workstreams :brian))]
          (is (= :shipped (get-in row [:position :at])) "a dismissed row is drawn, so it reads status")
          (is (= {:owed-by :nobody :live false :stalled false :paused false :needs-you false :needs nil} (:status row))))))))

(deftest undismissed-rows-are-unaffected
  (with-tmp
    (fn [_]
      (workstream/create! :brian
        {:stage :triage
         :external-refs [{:adapter :slack-message :id "slack-C1-9.9" :title "t"}]})
      (tickets/open! :brian "slack-C1-9.9" {:title "t"})
      (is (= [:triage] (map :stage (work/list-workstreams :brian)))))))

(deftest grouped-folds-scratch-into-in-progress-group
  (with-tmp
    (fn [_]
      ;; a scratch one-off
      (let [s (one-off!)]
        (session/create! :brian (:id s) {:name "poke" :weight :light :autonomy nil}))
      ;; a triaged notion ticket → :ready, which is NOT a board band (backlog
      ;; lives in Notion) — it doesn't appear in any group.
      (let [n (workstream/create! :brian {:external-refs [{:adapter :notion :id "BR-3" :title "t"}]})]
        (tickets/open! :brian "BR-3" {:title "t"})
        (session/create! :brian (:id n) {:name "s" :weight :light :autonomy nil}))
      (let [g (work/grouped :brian #{"poke"})]
        (is (not (contains? g :ready)) "no :ready band — backlog is Notion's")
        (is (= 1 (count (:in-progress g))) "the scratch one-off folds into :in-progress")))))

(def ^:private autonomy-running
  {:skill :triage-bug :first-message "x" :agent :claude :claude-session-id nil
   :trigger :triage-bug :limits {:budget "30m"} :priority 4 :uncapped? false :on-promote nil
   :phase :running :phase-history [{:at "2026-06-01T00:00:00Z" :phase :running}]
   :error nil})

(deftest workstream-detail-presents-its-units-sessions
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:external-refs [{:adapter :notion :id "BR-5" :title "t"}]})]
        (units/open-unit! :brian (:id w) "u1" {:session "up"})
        (units/open-unit! :brian (:id w) "u2")
        (let [d  (work/workstream :brian (:id w))
              by (into {} (map (juxt :name identity)) (:sessions d))]
          (is (= (:id w) (:ws-id d)) "detail uses the same :ws-id key as list-workstreams rows")
          (is (= :notion (:origin d)))
          (is (= #{"up" "u2"} (set (keys by))) "one session per unit, named for its worktree or its unit")
          (is (every? #(= :autonomous (:autonomy-level %)) (vals by)) "the core's turns drive every one")
          (is (= :up (:status (by "up"))) "a session the core has ready reads :up")
          (is (= :down (:status (by "u2"))) "one it still owes reads :down"))))))

(deftest workstream-detail-flags-the-current-ledger-entry
  (with-tmp
    (fn [_]
      (let [w  (workstream/create! :brian {:external-refs []})
            id (:id w)]
        (workstream/append-entry! :brian id {:kind :note} "first")
        (workstream/append-entry! :brian id {:kind :note} "second")
        ;; nothing open by default — the pane at rest acts on the workstream as it
        ;; stands, so it is trivially :on-latest?
        (let [d (work/workstream :brian id)]
          (is (nil? (:selected-seq d)) "no entry opens itself")
          (is (true? (:on-latest? d)) "nothing open → the pane's live actions stand"))
        ;; explicitly viewing the older entry (seq 1) is NOT the current entry
        (is (false? (:on-latest? (work/workstream :brian id 1)))
            "an older ledger entry is not the current entry")
        ;; selecting the newest entry explicitly is still :on-latest?
        (is (true? (:on-latest? (work/workstream :brian id 2))))))))

(deftest workstream-detail-with-no-entries-is-on-latest
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:external-refs []})]
        (is (true? (:on-latest? (work/workstream :brian (:id w))))
            "an entryless workstream is trivially on its current (only) state")))))

(deftest workstream-detail-nil-for-absent
  (with-tmp
    (fn [_]
      (is (nil? (work/workstream :brian "ws-does-not-exist"))))))

(deftest spine-is-exactly-the-stages-the-board-honors
  ;; work/stages is what the TUI stage picker offers and what default-target
  ;; validates a configured override against — both feed set-stage!, so every
  ;; entry must be a stage grouped-by-stage has a key for — a name it lacks
  ;; writes a stage no band holds, and the workstream leaves the board.
  (is (= session/lifecycle-stages (set work/stages)))
  (is (= (count session/lifecycle-stages) (count work/stages)) "ordered, no dupes")
  (is (every? session/storable-stages work/stages)
      "every pickable stage survives create!/advance-stage!"))

(deftest default-target-falls-back-to-in-progress
  (with-redefs [nido.platform.config/read-projects (constantly {})]
    (is (= :in-progress (work/default-target :brian :promote)))
    (is (= :in-progress (work/default-target :brian :new)))))

(deftest default-target-honors-configured-stage
  (with-redefs [nido.platform.config/read-projects
                (constantly {'brian {:workstream-defaults {:promote :ready}}})]
    (is (= :ready (work/default-target :brian :promote))
        "configured target wins, project key matched by name")
    (is (= :in-progress (work/default-target :brian :new))
        "unset action falls back to canonical")))

(deftest default-target-rejects-non-stage-config
  (with-redefs [nido.platform.config/read-projects
                (constantly {'brian {:workstream-defaults {:promote :nonsense}}})]
    (is (= :in-progress (work/default-target :brian :promote))
        "a configured value that isn't a spine stage is ignored")))

(deftest set-stage-advance-moves-stage-without-a-leg
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:external-refs []})]
        (is (= {:decision :advanced} (work/set-stage! :brian (:id w) :ready)))
        (is (= :ready (:stage (workstream/newest-choice (workstream/read-ws :brian (:id w))))))))))

(deftest start!-brings-a-persons-own-session-up-as-no-work
  (with-tmp
    (fn [_]
      (let [ups (atom [])]
        (with-redefs [nido.session.lifecycle/up! (fn [n opts] (swap! ups conj [n opts]) nil)]
          (work/start! :brian "spike-thing" {:wait false})
          (is (= [["spike-thing" {:project "brian" :wait false}]] @ups))
          (is (empty? (workstream/project-records :brian)) "no workstream is minted for it"))))))

(deftest open-target-prefers-a-ready-session
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:external-refs []})]
        (units/open-unit! :brian (:id w) "u1" {:session "live"})
        (units/open-unit! :brian (:id w) "u2")
        (is (= {:project :brian :session "live"} (work/open-target :brian (:id w)))
            "open lands in the session the core has ready, not one it still owes")))))

(deftest environment-is-the-last-open-units-ready-session
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:external-refs []})]
        (is (nil? (work/environment :brian (:id w))) "no unit, no environment")
        (units/open-unit! :brian (:id w) "u1" {:session "first"})
        (units/open-unit! :brian (:id w) "u2" {:session "second"})
        (units/open-unit! :brian (:id w) "u3")
        (is (= "second" (:name (work/environment :brian (:id w))))
            "the last open unit whose session is up; one still owed is not yet an environment")))))

(deftest open-target-nil-when-no-sessions
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:external-refs []})]
        (is (nil? (work/open-target :brian (:id w))))))))

(deftest gate-actions-are-stage-derived
  (is (= [{:id :apply   :label "Apply"   :kind :mutation              :style :primary}
          {:id :dismiss :label "Dismiss" :kind :mutation              :style :danger}
          {:id :reply   :label "Reply"   :kind :resume                :style :default}]
         (work/gate-actions :triage true))
      "a parked triage offers one-click Apply (nido mutation), Dismiss, and free-text Reply")
  (is (= [{:id :apply   :label "Apply"   :kind :mutation :style :primary}
          {:id :dismiss :label "Dismiss" :kind :mutation :style :danger}]
         (work/gate-actions :triage false))
      "an unparked triage can still be applied AND dismissed: apply! writes the
       verdict nido-side and resumes nobody, so gating it on a live session left
       the ticket unappliable in exactly the case it was already stuck in — a
       triage whose run failed after writing its report. Only Reply is gone")
  (is (= [{:id :promote :label "Promote" :kind :mutation :style :primary}
          {:id :drop    :label "Drop"    :kind :mutation :style :danger}]
         (work/gate-actions :ready false)))
  (is (= [{:id :reply :label "Reply" :kind :resume :style :default}
          {:id :done  :label "Done"  :kind :mutation :style :primary}]
         (work/gate-actions :in-progress true)))
  (is (= [] (work/gate-actions :intake true)))
  (is (= [] (work/gate-actions :done true))))

(def ^:private sample-directions
  [{:label "Round once on the total" :shape "Move the rounding to the aggregate."
    :effort :S :confidence {:level :high :reason "one call site"}}
   {:label "Introduce a Money type" :shape "Make per-line rounding unrepresentable."
    :effort :L :confidence {:level :medium :reason "touches every total"}}])

;; A button rendered with its reading position and read back without one
;; resolves as stale every time. Derived from gate-actions rather than listed,
;; so a new position-carrying action cannot be added on one side only.
(deftest every-button-given-a-position-is-read-back-with-one
  (let [descriptors (concat
                     (work/gate-actions :in-progress true nil
                                        {:options [{:label "A"} {:label "B"}] :seq 7})
                     (work/gate-actions :triage true nil
                                        {:report-format :triage-report
                                         :questions [{:directions sample-directions}] :seq 7}))
        carries-seq (into #{} (comp (filter :seq) (map :id)) descriptors)]
    (is (seq carries-seq) "the fixture must actually produce position-carrying buttons")
    (is (contains? carries-seq :apply)
        "the accept is rendered with the position it was read at, because it appends a
         record a stale click must not write")
    (doseq [id carries-seq]
      (is (work/position-carrying-action? id)
          (str id " is rendered with a :seq, so the surface must read its ?entry= back")))))

(deftest every-other-in-progress-gate-is-unchanged
  (let [usual [{:id :reply :label "Reply" :kind :resume :style :default}
               {:id :done  :label "Done"  :kind :mutation :style :primary}]]
    (is (= usual (work/gate-actions :in-progress true nil {:report-format :blocker})))
    (is (= usual (work/gate-actions :in-progress true nil {:report-format :design-decision :seq 7}))
        "a parked design decision offers no approval: a design is built when its round proceeds")
    (is (= usual (work/gate-actions :in-progress true nil {:report-format nil})))
    (is (= usual (work/gate-actions :in-progress true))
        "the arity that predates report-format keeps its answer"))
  (is (= [] (work/gate-actions :in-progress false nil {:report-format :design-decision}))
      "a decision nobody is parked on is not a gate — there is no agent to resume"))

;; ── The answerable blocker: A/B/… rather than an essay ─────────────────────
;; Same gate, same stage. A blocker that NAMED its branches is a question with
;; answers, so the gate offers them as buttons; one written as prose can only be
;; answered by typing prose back, which is the answer that never arrives.

(def ^:private sample-options
  [{:label "Drop Keep Old" :summary "Collapse the modal to Continue/Cancel."
    :consequence "Retires the /keep-old route." :recommended? true}
   {:label "Implement archive-and-clone" :summary "Build the archive path."
    :consequence "Needs a prior product call on what archived means."}])

(deftest a-parked-blocker-with-options-offers-one-button-per-branch
  (is (= [{:id :option-a :label "A" :title "Drop Keep Old"
           :kind :mutation :style :primary}
          {:id :option-b :label "B" :title "Implement archive-and-clone"
           :kind :mutation :style :default}
          {:id :reply    :label "Reply" :kind :resume :style :default}]
         (work/gate-actions :in-progress true nil
                            {:report-format :blocker :options sample-options}))
      "the recommended branch is the primary button; Reply stays for the answer
       that is none of them; Done is absent for the reason it is absent from the
       design gate — beside A/B it reads as an answer to the question")
  (is (= [7 7] (->> (work/gate-actions :in-progress true nil
                                       {:report-format :blocker :options sample-options :seq 7})
                    (filter (comp work/option-action? :id))
                    (map :seq)))
      "each button names the ledger position it was rendered from, so the click
       can be checked against the question that was on screen")
  (is (= [:option-a :option-b :reply :drop]
         (map :id (work/gate-actions :shipping true nil
                                     {:report-format :blocker :options sample-options})))
      "a drive-home halt in the merge lane is where a choice most often lands")
  (is (= [:reply :drop] (map :id (work/gate-actions :shipping true)))
      "and a shipping gate with nothing to choose between is unchanged")
  (is (= [:option-a :option-b]
         (map :id (work/gate-actions :in-progress false nil
                                     {:report-format :blocker :options sample-options :seq 3})))
      "the branches stand with NOBODY parked — the question outlives the session
       that asked it, and four of five blockers on this box outlived theirs. Reply
       and Done are absent there: both reach for an agent that is gone")
  (is (= [:option-a :option-b :apply :dismiss]
         (map :id (work/gate-actions :triage false nil
                                     {:report-format :blocker :options sample-options :seq 3})))
      "same at :triage, where a crashed triage run leaves its question behind —
       beside the accept, which is unparked-safe for the same reason the branches
       are: neither reaches for an agent")
  (is (= [] (work/gate-actions :in-progress false))
      "and a gate with no branches to offer is unchanged: still nothing"))

;; ── A blocker buried under later records ───────────────────────────────────
;; A blocker holds the workstream until an answer names it. When a review round, a verdict
;; or a close-then-reopen lands on top of it, the latest report is not the blocker any more.
;; A gate read only from that latest report then said "needs you" and offered nothing.

(defn- bury-a-blocker!
  "An activated workstream whose blocker (entry 1) sits under a later design verdict (entry 2),
   with nobody parked."
  []
  (let [w (workstream/create! :brian {:external-refs [] :activated? true})]
    (workstream/append-entry! :brian (:id w) {:kind :blocker}
      (pr-str {:format :blocker :summary "s" :needs "n?" :options sample-options :after "The work resumes once you answer."}))
    (workstream/append-entry! :brian (:id w) {:kind :design-verdict}
      (pr-str {:format :design-verdict :verdict :sound :round 1 :reason "Nothing moved."}))
    w))

(deftest a-workstream-carries-its-gate-and-the-gate-carries-no-pane-facts
  (with-tmp
    (fn [_]
      (let [w  (bury-a-blocker!)
            g  (work/gate :brian (:id w))
            ws (work/workstream :brian (:id w))]
        (is (= g (:gate ws))
            "the pane is handed the very gate the needs-you list holds, so the two ask one question")
        (is (not-any? #(contains? g %) [:arc :position])
            "the arc and position are the workstream map's alone")
        (is (some? (:arc ws)))
        (is (= [[(get-in ws [:position :stage]) :awaiting]]
               (keep #(when (:now %) [(:stage %) (:now %)]) (get-in ws [:arc :stages])))
            "the stage the blocker holds is marked as waiting on the person")))))

(deftest a-blocker-owed-an-answer-offers-no-done
  (is (= [:reply] (mapv :id (work/gate-actions :in-progress true nil {:awaiting :answer-blocker})))
      "marking the workstream done is no answer to the blocker it is halted on")
  (is (= [:reply :done] (mapv :id (work/gate-actions :in-progress true)))
      "a parked gate that owes no blocker still offers Done"))

(deftest an-unparked-blocker-without-branches-offers-an-answer
  (is (= [{:id :reply :label "Answer" :kind :resume :style :default :answers :ledger}]
         (work/gate-actions :in-progress false nil {:awaiting :answer-blocker}))
      "it says its words reach the ledger alone, so the card cannot promise a resume")
  (is (= [:conductor] (keep :answers (work/gate-actions :in-progress true nil {:reply-to :conductor})))
      "a Reply that answers a conductor's escalation says so")
  (is (= [] (work/gate-actions :in-progress false nil {}))
      "with nothing owed and nobody parked there is still nothing to offer"))


;; ── The triage gate: one Accept for every question ─────────────────────────
;; A report asks one question per defect, each with its own directions. No one
;; direction is the whole answer, so none is an action: Accept sends a choice per
;; question, and Defer decision accepts with all of them open.

(defn- triage-gate [parked? qs & {:as more}]
  (work/gate-actions :triage parked? nil (merge {:report-format :triage-report :questions qs} more)))

(deftest a-triage-that-asks-offers-accept-defer-and-dismiss
  (is (= [{:id :apply :label "Accept" :kind :mutation :style :primary :seq 7 :answers :questions}
          {:id :defer :label "Defer decision" :kind :mutation :style :default :seq 7}
          {:id :dismiss :label "Dismiss" :kind :mutation :style :danger}
          {:id :reply :label "Reply" :kind :resume :style :default}]
         (triage-gate true [{:directions sample-directions}
                            {:question "Q2?" :directions (vec (take 1 sample-directions))}] :seq 7))
      "Accept is the one primary, and both carry the position they were rendered at")
  (is (= [:apply :defer :dismiss] (map :id (triage-gate false [{:directions sample-directions}])))
      "they stand with nobody parked: apply! resumes no agent")
  (is (not-any? #(str/starts-with? (name (:id %)) "direction-")
                (triage-gate true [{:directions sample-directions}]))
      "no direction is an action of its own"))

(deftest a-triage-that-asks-nothing-offers-apply
  (is (= [:apply :dismiss] (map :id (triage-gate false []))))
  (is (= "Apply" (->> (triage-gate true [{:directions []}]) (filter #(= :apply (:id %))) first :label))
      "with nothing on offer there is nothing to defer")
  (is (= [:apply :dismiss]
         (map :id (triage-gate false [{:directions (vec (repeat 27 (first sample-directions)))}])))
      "a stored report past the alphabet cannot be lettered, so it is asked nothing"))

(deftest a-stored-report-past-the-authoring-cap-still-asks
  (is (= [:apply :defer :dismiss]
         (map :id (triage-gate false [{:directions (vec (repeat 7 (first sample-directions)))}])))))

(deftest accepted-report-is-the-only-route-a-choice-takes-to-notion
  (let [report {:format :triage-report
                :notion-writes {:effort :squirrel :description-prepend "why it broke"}}
        chosen (#'work/accepted-report report [(second sample-directions)])]
    (is (= :L (get-in chosen [:notion-writes :effort]))
        "the branch's own size replaces the deferred one")
    (is (= "why it broke" (get-in chosen [:notion-writes :description-prepend]))
        "and nothing else the branch carries lands: WHICH branch was taken lives in the acceptance")
    (is (not (str/includes? (get-in chosen [:notion-writes :description-prepend]) "Money type"))))
  (is (= {:format :proposed-ticket} (#'work/accepted-report {:format :proposed-ticket} nil))
      "a report with nothing to write to Notion is returned untouched"))

(deftest the-effort-is-the-largest-chosen-size
  (let [note   (fn [r] (get-in r [:notion-writes :description-prepend]))
        effort (fn [r] (get-in r [:notion-writes :effort]))
        sq     {:format :triage-report
                :notion-writes {:effort :squirrel :description-prepend "why it broke"}}
        sized  (assoc-in sq [:notion-writes :effort] :M)
        of     (fn [r ds] (#'work/accepted-report r ds))]
    (is (= :L (effort (of sq [{:effort :S} {:effort :L} {:effort :M}])))
        "the ticket holds every chosen fix, so it is sized by the largest")
    (is (str/includes? (note (of sq [{:effort :S} {:effort :L}]))
                       "Effort is the largest of the chosen fixes (S, L).")
        "and says so, listing the sizes, when they differ")
    (is (not (str/includes? (note (of sq [{:effort :S} {:effort :S}])) "largest"))
        "sizes that agree need no note")
    (is (= :S (effort (of sq [{:effort :S} {:effort :squirrel}])))
        "a fix that defers its size does not outweigh a sized one")
    (is (= [:squirrel true] ((juxt effort #(str/includes? (note %) "Effort left open"))
                             (of sq [{:effort :squirrel}])))
        "every chosen fix deferring its size leaves it open, and says so")
    (is (= [:squirrel true] ((juxt effort #(str/includes? (note %) "Effort left open")) (of sq nil)))
        "as does deferring every question")
    (is (= [:M false] ((juxt effort #(str/includes? (note %) "Effort left open")) (of sized nil)))
        "a sized report deferred lands its own size")))

(deftest option-actions-are-not-workstream-less
  (is (empty? (set/intersection @#'work/workstream-less-actions
                                (set work/option-action-ids)))
      "answering resumes a parked agent, so it needs a workstream — the two-way
       membership hazard documented on that set is why this is pinned"))

(def ^:private decided-baseline
  ;; :intent names entry 1 — the goal is appended before the survey it scopes.
  {:format :baseline :strata [] :intent {:seq 1} :area "a" :bounded-by "b" :shape "s"
   :model {:elements [{:id "m" :sort :module :hides "h" :interface "i"}]
           :claims [{:id "c1" :about ["m"] :statement "p" :falsified-by "f"
                     :evidence {:by :round} :read-at ["src/a.clj:1"]}]}
   :read ["src/a.clj"]})

(defn- decided-design
  "A workstream whose latest entry is a proceeding design decision on a verified
   baseline. Returns [ws-id baseline-seq design-seq decision-seq]."
  []
  (let [w  (workstream/create! :brian {:external-refs []})
        id (:id w)
        add (fn [kind r] (if (= :design-decision kind) (workstream/append-decision! :brian id (pr-str r)) (workstream/append-entry! :brian id {:kind kind} (pr-str r)))
              (count (:entries (workstream/read-ws :brian id))))]
    (add :intent {:format :intent :goal "g" :done-when ["d"]})
    (let [b (add :baseline decided-baseline)
          _ (add :baseline-review {:format :baseline-review :verdict :sufficient
                                   :baseline-seq b :reason "ok"})
          d (add :design {:format :design :strata [] :summary "s" :shape "sh"
                          :model {:elements [{:id "m" :sort :module}]
                                  :claims [{:id "one-path" :about ["m"] :statement "one path"
                                            :falsified-by "a second path" :evidence {:by :round}}]}
                          :standing {:relation :conforms}
                          :baseline {:seq b :relation :within} :intent {:seq 1}
                          :effort :S})
          dd (add :design-decision {:format :design-decision :recommend :proceed
                                    :design-seq d :reason "r"
                                    :checks [{:check :goal-served :status :held :note "n"}]
                                    :asks "worth doing now?"})]
      [id b d dd])))



(deftest an-option-may-not-say-it-approves-a-design
  ;; Picking an option grants nothing, so a blocker may not be written claiming it does.
  (with-tmp
    (fn [_]
      (let [[id _ d _] (decided-design)]
        (is (thrown? Exception
                     (workstream/append-entry!
                      :brian id {:kind :blocker}
                      (pr-str {:format :blocker :summary "s" :needs "Should we build this design?"
                               :after "The next decision round reads your answer."
                               :options [{:label "Build it" :summary "As designed."
                                          :approves-design d}
                                         {:label "Hold" :summary "Leave it."}]}))))))))

(deftest triaged-failed-spawn-is-not-a-gate-but-stays-on-board
  ;; The scenario the two tests above used to cover on a :ready gate: a triaged
  ;; ticket whose impl spawn failed. :ready is no longer a gate, so it drops off the
  ;; Needs-you inbox entirely — no stranded 'working…' possible — and is pulled from
  ;; the board instead, where its Promote/Drop pane actions render unconditionally.
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:external-refs [{:adapter :notion :id "BR-11" :title "t"}]})]
        (tickets/open! :brian "BR-11" {:title "t"})
        (tickets/complete! :brian "BR-11")   ; ticket :triaged ⇒ :ready
        (session/create! :brian (:id w)
                         {:name "impl" :weight :heavy
                          :autonomy (assoc autonomy-running :phase :failed)})
        (is (nil? (work/gate :brian (:id w))) "a :ready workstream is not a gate")
        (is (= [:promote :drop] (map :id (work/gate-actions :ready false)))
            "board pane still offers promote/drop")))))

(def ^:private slack-edn-report
  "Valid TriageReport EDN for slack triage ledger tests."
  (pr-str {:format :triage-report :ticket-key "slack-C1-1.0" :determination :bug
           :title "Verdict" :summary "bug — slack report."
           :confidence {:level :high :reason "r"}
           :routing nil
           :questions [] :notion-writes nil :trail []}))

(deftest gates-excludes-a-ready-workstream
  (with-tmp
    (fn [_]
      ;; triaged + a non-parked session → stage :ready. Ready is a PULL queue: you
      ;; pull it off the board to promote, so it must NOT appear in the needs-you gates.
      (let [r (workstream/create! :brian {:external-refs [{:adapter :notion :id "BR-8" :title "t"}]})]
        (tickets/open! :brian "BR-8" {:title "t"})
        (triaged! (:id r) "BR-8" true)
        (session/create! :brian (:id r) {:name "s" :weight :light :autonomy nil}))
      (is (empty? (work/gates :brian)) "a :ready workstream is not a gate")
      ;; …but it is still on the spine at :ready with its board actions available.
      (let [row (first (filter #(= :ready (:stage %)) (work/list-workstreams :brian)))]
        (is (some? row) "ready workstream is still on the board")
        (is (= [:promote :drop] (map :id (work/gate-actions :ready false)))
            "and its board pane still offers promote/drop")))))

(deftest gate-detail-nil-for-absent
  (with-tmp
    (fn [_]
      (is (nil? (work/gate :brian "ws-nope"))))))

(deftest with-shared-rows-reads-each-project-once
  ;; The board's bands are a fold of every row; the gates are not — the core answers them — so a
  ;; render reads each project's rows once, shared or not, and the gates read none.
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:external-refs []})]
        (triaged! (:id w) "x" false)
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy
                          :autonomy (assoc autonomy-running :phase :parked)}))
      (with-redefs [nido.platform.project/list-projects
                    (constantly {"brian" {:directory "/tmp/brian"}})]
        (let [reads (atom 0)
              orig  wsv/workstream-rows]
          (with-redefs [wsv/workstream-rows (fn [& args]
                                              (swap! reads inc)
                                              (apply orig args))]
            (reset! reads 0)
            (work/all-gates)
            (is (= 0 @reads) "the gates read no row: the core answers them")

            (reset! reads 0)
            (work/with-shared-rows (fn [] (work/all-grouped) (work/all-grouped)))
            (is (= 1 @reads)
                "shared, the second projection reuses the first's rows")))))))

(def ^:private stop-content
  {:question "This workstream's conductor has stopped. Wake it again?" :explored "e"
   :recommendation "r" :options [{:option "Wake it"}]})

(defn- raise-person-ask!
  "Raise ask `ask` on workstream `ws-id` the way a conductor turn's ask reaches the person: the
   turn asks, the rules raise its call — a question, not a machine failure."
  [ws-id ask question]
  (let [k (client/ws-key :brian ws-id)
        t (str "conductor-" ask)]
    (doseq [[i c] (map-indexed vector
                               [{:command :start-turn :turn t :unit nil :spec {:executor :fake :instructions "i"}}
                                {:command :record-outcome :turn t :run 1 :outcome :asked
                                 :call {:id (str "call-" ask) :question question}}
                                {:command :raise-operator-ask :ask ask :binds #{t}
                                 :content {:question question :explored "e" :recommendation "r"
                                           :options ["yes" "no"]}}])]
      (is (:events (client/command! k (str ws-id "/" ask "/" i) c))))))

(defn- stop-conductor!
  "Fail workstream `ws-id`'s conductor turns until the rules stop it, as a machine out of
   resources does: each turn ends without an outcome, the last saying on stderr why."
  [ws-id]
  (let [k (client/ws-key :brian ws-id)]
    (doseq [i (range 3)
            :let [t (str "conductor-" (+ 10 i))]]
      (is (:events (client/command! k (str ws-id "/start-" t) {:command :start-turn :turn t :unit nil
                                                                :spec {:executor :fake :instructions "i"}})))
      (is (:events (client/command! k (str ws-id "/end-" t)
                                    {:command :record-outcome :turn t :run 1 :outcome :failed
                                     :detail (cond-> {:reason :ended-without-outcome}
                                               (= 2 i) (assoc :stderr "oh no: Bun has crashed."))}))))
    (is (:events (client/command! k (str ws-id "/stop") {:command :raise-conductor-stop :ask "conductor stopped 7"
                                                        :content stop-content})))))

(deftest a-stopped-conductor-is-a-system-error-on-needs-you
  (with-tmp
    (fn [_]
      (with-redefs [nido.platform.project/list-projects
                    (constantly {"brian" {:directory "/tmp/brian"}})]
        (let [a (workstream/create! :brian {:external-refs [{:adapter :notion :id "BR-9" :title "Fix the thing"}]})
              b (workstream/create! :brian {:external-refs [{:adapter :notion :id "BR-10" :title "Ask me"}]})]
          (stop-conductor! (:id a))
          (raise-person-ask! (:id b) "ask-b" "Ship it?")
          (let [{:keys [gates] :as reading} (work/read-gates)
                by (into {} (map (juxt :ws-id identity)) gates)
                e  (by (:id a))]
            (is (= #{(:id a) (:id b)} (set (map :ws-id gates))) "both need the person: one Needs you list")
            (is (not (contains? reading :errors)) "and no list of its own for failures")
            (is (= :system-error (:needs e)) "the stopped conductor is a system error")
            (is (= :ask (:needs (by (:id b)))) "the question an ask")
            (is (= ["brian" "Fix the thing" "Its conductor stopped" "conductor stopped 7"]
                   ((juxt :project :label :what :core-ask) e)))
            (is (re-find #"last 3 conductor turns failed — its agent exited without reporting an outcome \(conductor-12\)"
                         (:because e))
                "it says what failed")
            (is (= "oh no: Bun has crashed." (:stderr (last (:failures e))))
                "and what the run said on its way down")
            (is (= [:wake] (map :id (:actions e))) "its one action is the recovery")
            (is (nil? (:ask e)) "and it asks no question")
            (is (= e (work/gate :brian (:id a))) "the pane's card is the list's"))
          (testing "waking it answers the stop and takes it off Needs you"
            (is (= {:decision :woken} (work/resolve-gate! :brian (:id a) :wake)))
            (is (= [(:id b)] (map :ws-id (work/all-gates))))
            (is (= {:decision :core-refused :because "its conductor is not stopped"}
                   (work/resolve-gate! :brian (:id a) :wake))))
          (testing "a workstream holding a question and a stop needs the person as a system error first"
            (stop-conductor! (:id b))
            (let [g (work/gate :brian (:id b))]
              (is (= :system-error (:needs g)))
              (is (= [:wake] (map :id (:actions g)))))
            (testing "and an option answers the question, never the stop"
              (is (= {:decision :answered} (work/resolve-gate! :brian (:id b) :option-a)))
              (is (= :system-error (:needs (work/gate :brian (:id b))))))
            (testing "a workstream its ledger settled needs nobody, whatever the core holds open"
              (raise-person-ask! (:id b) "ask-b2" "And this?")
              (workstream/close! :brian (:id b) :done)
              (is (nil? (work/gate :brian (:id b)))))))))))

(deftest all-gates-are-the-core-s-open-asks
  (with-tmp
    (fn [_]
      (with-redefs [nido.platform.project/list-projects
                    (constantly {"brian" {:directory "/tmp/brian"}})]
        (let [a (workstream/create! :brian {:external-refs [{:adapter :notion :id "BR-9" :title "Fix the thing"}]})
              b (workstream/create! :brian {:external-refs []})]
          (is (empty? (work/all-gates)) "no open ask, no gate")
          (is (= {:gates [] :unavailable {}} (work/read-gates)))
          (raise-person-ask! (:id a) "ask-1" "Carry on with the rebase?")
          (let [[g :as gs] (work/all-gates)]
            (is (= 1 (count gs)) "only the workstream with an open ask is a gate")
            (is (= :ask (:needs g)) "a question the core asks is an ask")
            (is (= [(:id a) "brian" "Fix the thing" "BR-9" "Carry on with the rebase?" "ask-1"]
                   ((juxt :ws-id :project :label :tag (comp :question :ask) :core-ask) g))))
          (testing "a closed workstream is no gate, whatever ask is open on it"
            (client/command! (client/ws-key :brian (:id a)) "close-a" {:command :close-workstream})
            (is (empty? (work/all-gates))))
          (is (some? b)))))))

(deftest the-pane-s-gate-is-the-list-s-card
  (with-tmp
    (fn [_]
      (with-redefs [nido.platform.project/list-projects
                    (constantly {"brian" {:directory "/tmp/brian"}})]
        (let [a (workstream/create! :brian {:external-refs []})
              b (workstream/create! :brian {:external-refs []})]
          (raise-person-ask! (:id a) "ask-1" "Carry on with the rebase?")
          (let [reads (atom 0)
                orig  wsv/workstream-rows]
            (with-redefs [wsv/workstream-rows (fn [& args] (swap! reads inc) (apply orig args))]
              (is (= (first (filter #(= (:id a) (:ws-id %)) (work/all-gates)))
                     (work/gate :brian (:id a)))
                  "the pane's card is the one the list holds")
              (is (nil? (work/gate :brian (:id b))) "a workstream the list holds no card for has none")
              (is (= 0 @reads) "and it reads no held row"))))))))

(deftest a-project-the-core-cannot-read-is-unavailable-not-empty
  (with-tmp
    (fn [_]
      (with-redefs [nido.platform.project/list-projects
                    (constantly {"brian" {:directory "/tmp/brian"}})
                    client/asking (fn [_] (client/unavailable "/tmp/core.sock"))]
        (let [r (work/read-gates)]
          (is (empty? (:gates r)))
          (is (= ["brian"] (keys (:unavailable r))))
          (is (= ["brian"] (keys (:unavailable (work/screen {:scope "all"} r))))))))))

(deftest shared-rows-memo-does-not-outlive-its-render
  ;; Scoped, never global: the rows project the ledger on disk and nothing here
  ;; invalidates, so a memo that survived its render would serve a board another
  ;; tab had already moved on from.
  (is (nil? @#'work/*rows-memo*) "no memo before")
  (is (true? (work/with-shared-rows (fn [] (some? @#'work/*rows-memo*)))) "one during")
  (is (nil? @#'work/*rows-memo*) "and none after"))

(deftest resolve-gate-dismiss-settles-dismissed-and-marks-ticket
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:external-refs [{:adapter :notion :id "BR-42"}]})]
        (is (= {:decision :dismissed} (work/resolve-gate! :brian (:id w) :dismiss)))
        (is (= :dismissed (:outcome (workstream/closure (workstream/read-ws :brian (:id w)))))
            "closed :dismissed, not :dropped — the outcome is a veto carrier in its
             own right, for rows with no ledger ref to stamp")
        (is (= :dismissed (tickets/status :brian "BR-42"))
            "dismiss records the off-radar disposition so auto-re-triage skips it")))))

(deftest dismiss-creates-ticket-record-when-absent-and-closes-ws
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:external-refs [{:adapter :notion :id "BR-99"}]})]
        ;; never-triaged ticket: no meta record yet
        (is (nil? (tickets/status :brian "BR-99")))
        (is (= {:decision :dismissed} (work/dismiss! :brian (:id w))))
        (is (= :dismissed (tickets/status :brian "BR-99")))
        (is (some? (workstream/closure (workstream/read-ws :brian (:id w)))))))))

(deftest dismiss-marks-slack-ticket-via-ledger-key
  ;; A Slack workstream's ticket is keyed on its slack-message id, not a Notion
  ;; BR-####. dismiss! must stamp :dismissed there too (same ledger key the
  ;; triage report lives under) so the off-radar disposition isn't lost.
  (with-tmp
    (fn [_]
      (let [slack-id "slack-C1-2.0"
            w (workstream/create! :brian {:external-refs [{:adapter :slack-message
                                                           :id slack-id :title "msg"}]})]
        (is (= {:decision :dismissed} (work/dismiss! :brian (:id w))))
        (is (= :dismissed (tickets/status :brian slack-id))
            "dismiss stamps the slack-keyed ticket, not just notion ones")
        (is (some? (workstream/closure (workstream/read-ws :brian (:id w)))))))))

(deftest dismissing-a-ref-less-workstream-still-reaches-the-dismissed-band
  ;; ledger-ref is notion-or-slack only, so a ref-less coordinator workstream has
  ;; no ticket record for the veto to live on. If :closed doesn't carry it, the
  ;; :settled fold takes over and projects :done, and the veto is lost: the row
  ;; can no longer be told apart from finished work, and Restore has nothing to
  ;; undo.
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:external-refs []})]
        (is (= [:triage] (map :stage (work/list-workstreams :brian))))
        (is (= {:decision :dismissed} (work/dismiss! :brian (:id w))))
        (is (= [:dismissed] (map :stage (work/list-workstreams :brian)))
            "with no ledger key the :closed outcome is the veto's only carrier")
        (is (= [(:id w)] (mapv :ws-id (:dismissed (work/grouped :brian))))
            "and it is grouped as dismissed")
        (is (= {:decision :restored} (work/restore! :brian (:id w))))
        (is (= [:triage] (map :stage (work/list-workstreams :brian)))
            "Restore is the way back, with no ticket record involved either")))))

(deftest resolve-gate-apply-finalizes-the-ticket-nido-side
  ;; Apply is a direct nido-side mutation (an acceptance on the ledger), NOT a
  ;; claude resume — so it works for legacy pre-notion-cli reviews whose apply
  ;; conversation calls the removed Notion MCP tools (which stranded them in triage).
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:external-refs [{:adapter :notion :id "BR-A9" :title "t"}]})
            resumed (atom false)]
        (tickets/open! :brian "BR-A9" {:title "t"})
        (with-redefs [nido.coordinator.lane.facets/refresh-for-ticket! (fn [& _] nil)]
          (is (= {:decision :applied} (work/resolve-gate! :brian (:id w) :apply)))
          (is (false? @resumed) "Apply does NOT resume a conversation")
          (is (= :triaged (tickets/status :brian "BR-A9"))
              "Apply finalizes the ticket :triaged nido-side ⇒ it leaves :triage"))))))

(def ^:private notion-edn-report
  "Valid TriageReport EDN for notion triage ledger tests."
  (pr-str {:format :triage-report :ticket-key "BR-9" :determination :bug
           :title "Verdict" :summary "ticket-ledger report."
           :confidence {:level :high :reason "r"}
           :routing nil
           :questions [] :notion-writes nil :trail []}))

(deftest gates-excludes-settled-workstreams
  (with-tmp
    (fn [_]
      ;; a CLOSED workstream with a stale :ready stage-override must still fold to
      ;; :done and stay out of the inbox — closed wins over any stage-override
      (let [w (workstream/create! :brian {:external-refs []})]
        (workstream/close! :brian (:id w) :done))
      (is (empty? (work/gates :brian))
          "a settled (closed) workstream is never a gate, even with a :ready stage-override"))))

(deftest gates-resume-error-nil-when-clean
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:external-refs [{:adapter :notion :id "BR-3" :title "t"}]})]
        (tickets/open! :brian "BR-3" {:title "t"})
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy :autonomy (assoc autonomy-running :phase :parked)})
        (is (nil? (:resume-error (first (work/gates :brian)))))))))

(deftest gate-actions-incoming
  (is (= [{:id :promote :label "Promote" :kind :mutation :style :primary}
          {:id :drop    :label "Dismiss" :kind :mutation :style :danger}]
         (work/gate-actions :incoming false)))
  (is (= (work/gate-actions :incoming false) (work/gate-actions :incoming true))
      "incoming actions don't depend on parked state"))

(deftest latest-report-falls-back-to-intake-text
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian
                {:stage :incoming
                 :external-refs [{:adapter :slack-message :id "slack-C-1.0"}]
                 :intake {:trigger :triage-slack-bugs
                          :payload {:id "slack-C-1.0" :text "the app crashed on save"}}})
            r (work/latest-report :brian (:id w))]
        (is (= :slack-report (:kind r)))
        (is (= "the app crashed on save" (:markdown r)))))))

(deftest workstream-includes-its-report
  (with-redefs [nido.coordinator.record.workstream/read-ws (fn [_ _] {:id "x"})
                work/latest-report (fn [_ _] {:kind :triage :at "t" :title "V" :markdown "# V"})
                ;; minimal stubs so workstream's other projections don't throw:
                nido.coordinator.record.session/list-sessions (fn [_ _] [])
                nido.coordinator.view.workstreams/workstream-row (fn [_ _ & _] {:ws-id "ws-1" :stage :triage :label "BR-7" :source :notion})
                nido.coordinator.record.workstream/closure (constantly nil)
                nido.coordinator.lane.pipeline/status (constantly {:position {:at :intake} :owed-by :agent})
                nido.coordinator.record.standing/open-findings (constantly #{})
                work/live-session-names (constantly #{})]
    (is (= "# V" (-> (work/workstream "brian" "ws-1") :report :markdown)))))

(deftest list-workstreams-rows-carry-facets
  (with-tmp
    (fn [_]
      (workstream/create! :brian {:external-refs [{:adapter :notion :id "BR-1"}]
                                  :facets {:app-domain ["Teacher"] :type "bug"}})
      (let [row (first (work/list-workstreams :brian))]
        (is (= {:app-domain ["Teacher"] :type "bug"} (:facets row)))))))

(deftest facet-dimensions-from-config
  (with-tmp
    (fn [_]
      (with-redefs [views/facet-properties (constantly ["App Domain" "Type"])]
        (is (= [:app-domain :type] (work/facet-dimensions :brian)))))))

(deftest facet-values-distinct-plus-unclassified
  (with-tmp
    (fn [_]
      (workstream/create! :brian {:external-refs [{:adapter :notion :id "BR-1"}]
                                  :facets {:app-domain ["Teacher"]}})
      (workstream/create! :brian {:external-refs [{:adapter :notion :id "BR-2"}]
                                  :facets {:app-domain ["Student" "Teacher"]}})
      (workstream/create! :brian {:external-refs [{:adapter :notion :id "BR-3"}]})
      (let [vals (work/facet-values :brian :app-domain)]
        (is (= #{"Teacher" "Student"} (set (remove #{:unclassified} vals)))
            "both present domain values, order-independent")
        (is (= :unclassified (last vals)) ":unclassified is appended last")))))

(deftest facet-match-composes-and-handles-vectors
  (let [row {:facets {:app-domain ["Teacher"] :type "bug"}}]
    (is (work/facet-match? {:app-domain :all :type :all} row) "all passes")
    (is (work/facet-match? {:app-domain "Teacher" :type "bug"} row))
    (is (not (work/facet-match? {:app-domain "Student"} row)))
    (is (work/facet-match? {:app-domain "Teacher"} row) "vector membership"))
  (is (work/facet-match? {:app-domain :unclassified} {:facets {}}) "unclassified matches missing")
  (is (not (work/facet-match? {:app-domain :unclassified} {:facets {:app-domain ["Teacher"]}}))))

(deftest facet-dimensions-is-source-aware
  (with-redefs [views/facet-properties (constantly ["App Domain" "Type"])]
    (is (= [:app-domain :type] (work/facet-dimensions :brian :notion)))
    (is (= [:app-domain :type] (work/facet-dimensions :brian :all)))
    (is (= [] (work/facet-dimensions :brian :slack)))
    (is (= [] (work/facet-dimensions :brian :github)))
    (is (= [:app-domain :type] (work/facet-dimensions :brian)) "1-arity = project-wide (:all)")))

(deftest shipping-gate-actions
  (is (= #{:reply :drop} (set (map :id (work/gate-actions :shipping true)))))
  (is (= [] (work/gate-actions :shipping false))))

(deftest grouped-rows-flattens-all-bands
  ;; :ready is not a board band — grouped-rows never includes it, even when
  ;; present in the map (e.g. a hand-built fixture).
  (let [g {:incoming [{:id 1}] :triage {:in-flight [{:id 2}] :queued [{:id 3}]}
           :ready [{:id 4}] :in-progress [{:id 5}]}]
    (is (= #{1 2 3 5} (set (map :id (work/grouped-rows g)))))))

(deftest latest-report-prefers-workstream-entries
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:external-refs []})]
        (workstream/append-entry! :brian (:id w) {:kind :impl} "# First\n\none")
        (workstream/append-entry! :brian (:id w) {:kind :impl} "# Second\n\ntwo")
        (let [r (work/latest-report :brian (:id w))]
          (is (= :markdown (:format r)))
          (is (str/includes? (:markdown r) "Second") "returns the LATEST workstream entry"))))))

(deftest latest-report-reads-the-workstream-ledger-routed-by-ref
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:external-refs [{:adapter :notion :id "BR-9"}]})]
        (tickets/open! :brian "BR-9" {:title "t"})
        (workstream/append-to-ref! :brian "BR-9" {:kind :impl} "# Impl\n\ndid it")
        (let [r (work/latest-report :brian (:id w))]
          (is (str/includes? (:markdown r) "did it")
              "reads the workstream ledger routed by the BR-#### ref"))))))

(deftest active-ledger-empty-when-no-entries
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:external-refs []})]
        (is (= [] (:entries (#'work/active-ledger :brian (:id w))))
            "no entries anywhere → empty vector")))))

(deftest active-ledger-reads-the-workstream-store-only
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:external-refs [{:adapter :notion :id "BR-1"}]})]
        (tickets/open! :brian "BR-1" {:title "t"})       ; descriptive record, no entries
        (workstream/append-to-ref! :brian "BR-1" {:kind :note} "plan")
        (tickets/complete! :brian "BR-1")                ; accepted as reported
        (let [d (work/workstream :brian (:id w) 1)]
          (is (= :triaged (:status (:ledger d))) "status derived from the workstream's ledger")
          (is (= 2 (:report-count (:ledger d))) "count from the workstream ledger")
          (is (some? (:report d)) "the workstream entry renders"))))))

(deftest workstream-browses-entries-newest-first
  (with-tmp
    (fn [_]
      (let [id (:id (workstream/create! :brian {:external-refs []}))]
        (workstream/append-entry! :brian id {:kind :impl} "# One\n\nfirst")
        (workstream/append-entry! :brian id {:kind :impl} "# Two\n\nsecond")
        (workstream/append-entry! :brian id {:kind :impl} "# Three\n\nthird")
        (let [d (work/workstream :brian id)]
          (is (= [3 2 1] (mapv :seq (:entries d))) "index is newest-first")
          (is (= ["Three" "Two" "One"] (mapv :title (:entries d))) "titles from headings")
          (is (= [:impl :impl :impl] (mapv :kind (:entries d))) "kinds carried")
          (is (nil? (:selected-seq d)) "the index alone — nothing opens itself")
          (is (nil? (:report d)) "and no report until the reader opens one"))))))

(defn- design-ledger!
  "Seed a workstream whose ledger a :design record may legally join: an :intent
   at seq 1 and a :baseline at seq 2, the two entries check-baseline-citation!
   makes every design cite. Returns the ws-id.

   The goal is appended FIRST because the survey now cites it, and a citation
   can only name an entry already on the ledger. That order is the record model
   made observable: a baseline is scoped for a goal, so the goal exists first."
  [id]
  (workstream/append-entry! :brian id {:kind :intent}
    (pr-str {:format :intent :goal "the goal" :done-when ["it is done"]}))
  (workstream/append-entry! :brian id {:kind :baseline}
    (pr-str {:format :baseline :strata [] :intent {:seq 1}
             :area "the area" :bounded-by "the bound" :shape "the shape"
             :model {:elements [{:id "mod-m" :sort :module
                                 :hides "how p is stored" :interface "p"}]
                     :claims [{:id "c1" :about ["mod-m"] :statement "p holds"
                               :falsified-by "a caller that reads p without going through m"
                               :evidence {:by :round} :read-at ["src/x.clj:1"]}]}
             :read ["src/x.clj"]}))
  id)

(defn- design-entry
  "A schema-valid :design citing design-ledger!'s baseline + intent, optionally
   amending entry `amends`."
  ([summary] (design-entry summary nil))
  ([summary amends]
   (pr-str (cond-> {:format     :design :strata []
                    :summary    summary
                    :shape      "the shape"
                    :standing   {:relation :conforms}
                    :baseline   {:seq 2 :relation :within}
                    :intent     {:seq 1}
                    :model      {:elements [{:id "mod-m" :sort :module}]
                                 :claims [{:id "holds" :about ["mod-m"]
                                           :statement "the thing holds"
                                           :falsified-by "the thing does not hold"
                                           :evidence {:by :round}}]}
                    :effort     :S}
             amends (assoc :supersedes {:seq amends :why "the premise moved"})))))

(deftest workstream-index-back-references-a-superseded-entry
  ;; The amend path appends; the superseded record stays. No single row can see
  ;; that relation — without the back-reference the index is two rows both
  ;; reading "design" and the reader has to open the newer one to learn the
  ;; older is dead.
  (with-tmp
    (fn [_]
      (let [id (design-ledger! (:id (workstream/create! :brian {:external-refs []})))]
        (workstream/append-entry! :brian id {:kind :design} (design-entry "first shape"))
        (workstream/append-entry! :brian id {:kind :design} (design-entry "better shape" 3))
        (let [by-seq (into {} (map (juxt :seq identity)) (:entries (work/workstream :brian id)))]
          (is (= 3 (:supersedes (by-seq 4))) "the amending row carries what it amends")
          (is (nil? (:superseded-by (by-seq 4))) "nothing replaced it")
          (is (= 4 (:superseded-by (by-seq 3))) "and the amended row points forward")
          (is (nil? (:superseded-by (by-seq 1))) "the baseline it cites is not superseded"))))))

(deftest workstream-index-keeps-the-newest-superseder-of-a-twice-amended-entry
  ;; Two records amending the same seq is the patching /design §5 warns about,
  ;; but the index must still resolve: a reader follows the newest.
  (with-tmp
    (fn [_]
      (let [id (design-ledger! (:id (workstream/create! :brian {:external-refs []})))]
        (workstream/append-entry! :brian id {:kind :design} (design-entry "first"))
        (workstream/append-entry! :brian id {:kind :design} (design-entry "second" 3))
        (workstream/append-entry! :brian id {:kind :design} (design-entry "third" 3))
        (let [by-seq (into {} (map (juxt :seq identity)) (:entries (work/workstream :brian id)))]
          (is (= 5 (:superseded-by (by-seq 3))) "newest superseder wins")
          (is (nil? (:superseded-by (by-seq 5)))))))))

(deftest workstream-index-leaves-an-unamended-ledger-unmarked
  (with-tmp
    (fn [_]
      (let [id (:id (workstream/create! :brian {:external-refs []}))]
        (workstream/append-entry! :brian id {:kind :impl} "# One\n\nfirst")
        (workstream/append-entry! :brian id {:kind :impl} "# Two\n\nsecond")
        (is (every? #(and (nil? (:supersedes %)) (nil? (:superseded-by %)))
                    (:entries (work/workstream :brian id)))
            "markdown entries carry no supersession, and nothing is stamped")))))

(deftest workstream-at-rest-still-carries-the-current-report-for-its-actions
  (with-tmp
    (fn [_]
      (let [id (:id (workstream/create! :brian {:external-refs []}))]
        (workstream/append-entry! :brian id {:kind :impl} "# One\n\nfirst")
        (workstream/append-entry! :brian id {:kind :blocker}
          (pr-str {:format :blocker :summary "s" :needs "n?" :options sample-options :after "The work resumes once you answer."}))
        (let [at-rest (work/workstream :brian id)]
          (is (nil? (:report at-rest)) "the viewer stays closed — nothing opens itself")
          (is (= sample-options (:options (:action-report at-rest)))
              "the ACTIONS still see the parked blocker's branches: at rest the pane
               acts on the workstream as it stands, so the option buttons must not
               wait for the reader to open the entry first")
          (is (= 2 (:seq (:action-report at-rest)))
              "and it names its ledger position, which is what the click carries back"))
        (is (some? (:action-report (work/workstream :brian id 2)))
            "opening the CURRENT entry changes nothing")
        (is (nil? (:action-report (work/workstream :brian id 1)))
            "an older entry is a read-back — :on-latest? is false and no actions render")))))

(deftest workstream-selects-requested-entry
  (with-tmp
    (fn [_]
      (let [id (:id (workstream/create! :brian {:external-refs []}))]
        (workstream/append-entry! :brian id {:kind :impl} "# One\n\nfirst")
        (workstream/append-entry! :brian id {:kind :impl} "# Two\n\nsecond")
        (let [picked (work/workstream :brian id 1)]
          (is (= 1 (:selected-seq picked)))
          (is (str/includes? (:markdown (:report picked)) "first")))
        (let [oob (work/workstream :brian id 99)]
          (is (nil? (:selected-seq oob)) "a seq no entry carries opens nothing")
          (is (nil? (:report oob)) "…rather than silently substituting another entry"))))))

(deftest workstream-single-entry-still-gets-an-index
  ;; Nothing opens by default, so the index is the ONLY way to reach an entry — a
  ;; single-entry ledger that skipped it would be unreadable.
  (with-tmp
    (fn [_]
      (let [id (:id (workstream/create! :brian {:external-refs []}))]
        (workstream/append-entry! :brian id {:kind :impl} "# Solo\n\nonly")
        (is (= ["Solo"] (mapv :title (:entries (work/workstream :brian id)))))
        (is (str/includes? (:markdown (:report (work/workstream :brian id 1))) "only"))))))

(deftest workstream-index-title-falls-back-to-first-line
  (with-tmp
    (fn [_]
      (let [id (:id (workstream/create! :brian {:external-refs []}))]
        (workstream/append-entry! :brian id {:kind :impl} "just prose no heading")
        (workstream/append-entry! :brian id {:kind :impl} "# Has heading\n\nx")
        (is (= ["Has heading" "just prose no heading"]
               (mapv :title (:entries (work/workstream :brian id))))
            "no markdown heading → first non-blank line")))))

(deftest workstream-index-title-uses-event-fields
  (with-tmp
    (fn [_]
      (let [id (:id (workstream/create! :brian {:external-refs []}))]
        (workstream/append-entry! :brian id {:kind :implementation-plan}
          (pr-str {:format :implementation-plan :summary "x" :direction "First direction" :effort :S}))
        (workstream/append-entry! :brian id {:kind :blocker}
          (pr-str {:format :blocker :summary "y" :needs "Second needs?" :after "The work resumes once you answer."}))
        (let [d (work/workstream :brian id)]
          (is (= ["Second needs?" "First direction"] (mapv :title (:entries d)))
              "index titles come from each event's fields via report/report-title"))))))

(defn- review-event
  "A :review ledger event pointing at `path` (the review-loop's report.json)."
  [path]
  (pr-str {:format :review-report :status :converged :base "main" :base-rev "abc123"
           :rounds 2 :fix-attempts 1 :defects-settled 1 :findings-remaining 0
           :targets-reviewed 1 :targets-skipped 0 :report-path path}))

(def ^:private review-report-json
  {:schema 1 :run-id "review-1" :status "converged"
   :target {:cwd "/w" :base "main" :base-rev "abc123" :files ["src/a.clj"]}
   :rounds [{:round 1 :status "continued"
             :phases [{:phase "review" :status "ok" :overall-correctness "incorrect"
                       :findings [{:title "[P2] Off by one" :body "the loop runs once too many"
                                   :priority 2 :file "/w/src/a.clj"
                                   :line-start 10 :line-end 12}]}
                      {:phase "fix" :status "ok" :commit "deadbeefcafe" :fixed-count 1}]}]})

(deftest review-entry-hydrates-its-rounds-from-the-report-it-points-at
  ;; The ledger event carries the verdict + counts and POINTS at report.json; the
  ;; reader hydrates the rounds so a surface can show what the review found.
  (with-tmp
    (fn [tmp]
      (let [path (str (fs/path tmp "report.json"))
            id   (:id (workstream/create! :brian {:external-refs []}))]
        (nido-io/write-json! path review-report-json)
        (workstream/append-entry! :brian id {:kind :review} (review-event path))
        (let [r (:report (work/workstream :brian id 1))]
          (is (= :review-report (:format r)))
          (is (= 1 (count (get-in r [:detail :rounds]))) "rounds come from report.json")
          (is (= "Off by one"
                 (-> r :detail :rounds first :phases first :findings first :title
                     (str/replace #"^\[P\d\]\s*" "")))
              "findings ride along with their round"))))))

(deftest review-entry-degrades-when-the-report-is-gone
  ;; Run dirs get cleaned; the event must still render its verdict.
  (with-tmp
    (fn [tmp]
      (let [id (:id (workstream/create! :brian {:external-refs []}))]
        (workstream/append-entry! :brian id {:kind :review}
                                  (review-event (str (fs/path tmp "gone.json"))))
        (let [r (:report (work/workstream :brian id 1))]
          (is (= :converged (:status r)) "the verdict survives")
          (is (nil? (:detail r)) "a missing report.json hydrates to nil, not a throw"))))))

(deftest review-index-row-does-not-hydrate
  ;; The index needs titles, not every round of every review — a corrupt or absent
  ;; report.json must not cost the index anything.
  (with-tmp
    (fn [tmp]
      (let [path (str (fs/path tmp "broken.json"))
            id   (:id (workstream/create! :brian {:external-refs []}))]
        (spit path "{not json")
        (workstream/append-entry! :brian id {:kind :impl} "# One\n\nx")
        (workstream/append-entry! :brian id {:kind :review} (review-event path))
        (is (= ["Review: converged" "One"] (mapv :title (:entries (work/workstream :brian id))))
            "the index reads titles off the events themselves, never report.json")
        (is (nil? (:detail (:report (work/workstream :brian id 2))))
            "and opening the entry survives an unparseable report.json — no detail")))))

(deftest screen-overview-and-detail-groups-are-identical
  ;; The same view-state must produce the same :groups regardless of whether a
  ;; selection is present (the overview vs detail bug).
  (let [grouped {:incoming [] :triage {:in-flight [] :queued []}
                 :in-progress [{:ws-id "p1" :origin :github :stage :in-progress}]
                 :shipping []}
        groups [{:project "brian" :grouped grouped}]
        vs-over {:surface :workstreams :scope "all" :selection nil}
        vs-det  (assoc vs-over :selection {:project "brian" :ws-id "p1"})
        g1 (:groups (work/screen vs-over {:groups groups :gates [] :pending #{}}))
        g2 (:groups (work/screen vs-det  {:groups groups :gates [] :pending #{}}))]
    (is (= g1 g2) "selection must not change the list")
    (is (= ["p1"] (map :ws-id (get-in (first g1) [:grouped :in-progress])))
        "rows survive regardless of origin — screen does not filter")))

(deftest screen-does-not-filter-rows
  ;; The regression guard for the bug this design fixes: a scratch :in-progress
  ;; row used to be invisible because the surface defaulted to source=notion.
  ;; screen must now return every row it is given, whatever the origin.
  (let [grouped {:incoming [{:ws-id "i1" :origin :slack :stage :incoming}]
                 :triage {:in-flight [{:ws-id "t1" :origin :notion :stage :triage}]
                          :queued []}
                 :in-progress [{:ws-id "p1" :origin :scratch :stage :in-progress}]
                 :shipping [{:ws-id "s1" :origin :github :stage :shipping}]}
        s (work/screen {:surface :workstreams :scope "all" :selection nil}
                       {:groups [{:project "brian" :grouped grouped}]
                        :gates [] :pending #{}})
        ids (set (map :ws-id (work/grouped-rows (:grouped (first (:groups s))))))]
    (is (= #{"i1" "t1" "p1" "s1"} ids) "every row survives, every origin")
    (is (not (contains? s :source-counts)) "source-counts is gone")
    (is (not (contains? s :facet-dims)) "facet-dims is gone")))

(deftest environment-nil-when-no-heavy-session
  (with-redefs [nido.coordinator.record.session/list-sessions
                (fn [_ _] [{:name "triage-BR-1" :weight :light :substrate :live :created-at "2026-07-20T10:00:00Z"}])]
    (is (nil? (work/environment :brian "ws-1"))
        "triage-only (light) workstream has no environment")))

(deftest work-session-excludes-chat-and-conductor
  (is (session/work-session? {:name "impl" :weight :heavy}))
  (is (not (session/work-session? {:name "c" :conductor true})))
  (is (not (session/work-session? {:name "ch" :chat true}))))

(deftest screen-needs-count-equals-gate-count
  (let [gates [{:ws-id "a" :project "brian"} {:ws-id "b" :project "brian"}]
        s (work/screen {:surface :needs :scope "all" :source :notion :facets {}}
                       {:groups [] :gates gates :pending #{}})]
    (is (= 2 (:needs-count s)))
    (is (= 2 (count (:gates s))))))

(deftest screen-needs-count-spans-every-project
  (let [gates [{:ws-id "a" :project "brian"} {:ws-id "b" :project "nido"}]
        s (work/screen {:scope "nido"} {:groups [] :gates gates :pending #{}})]
    (is (= 2 (:needs-count s)) "the badge counts every project's gates")
    (is (= ["b"] (map :ws-id (:gates s))) "the scoped view keeps its own")))

(deftest screen-marks-pending-gates-working
  (let [gates [{:ws-id "a" :project "brian" :working? false}
               {:ws-id "b" :project "brian" :working? true}]
        s (work/screen {:surface :needs :scope "all" :source :notion :facets {}}
                       {:groups [] :gates gates :pending #{"brian/a"}})]
    (is (true? (:pending? (first (filter #(= "a" (:ws-id %)) (:gates s)))))
        "optimistic bridge key marks pending")
    (is (true? (:pending? (first (filter #(= "b" (:ws-id %)) (:gates s)))))
        "a running (resumed) session marks pending")))

(deftest screen-scope-filters-both-groups-and-gates
  (let [groups [{:project "brian" :grouped {:incoming [] :triage {:in-flight [] :queued []}
                                            :ready [{:ws-id "r1" :origin :notion :facets {} :stage :ready}]
                                            :in-progress [] :shipping []}}
                {:project "foo" :grouped {:incoming [] :triage {:in-flight [] :queued []}
                                          :ready [{:ws-id "r2" :origin :notion :facets {} :stage :ready}]
                                          :in-progress [] :shipping []}}]
        gates [{:ws-id "r1" :project "brian"} {:ws-id "r2" :project "foo"}]
        s (work/screen {:surface :workstreams :scope "brian" :source :notion :facets {} :selection nil}
                       {:groups groups :gates gates :pending #{}})]
    (is (= ["brian"] (map :project (:groups s))))
    (is (= ["r1"] (map :ws-id (:gates s))))))

(deftest triage-dismiss-offered-for-every-origin
  ;; Reverses the retirement in 2026-07-17-triage-routing-model-design.md §5.
  ;; Dismiss is a nido-side veto now, made safe by the Dismissed band + Restore
  ;; rather than by refusing the action.
  (is (some #{:dismiss} (map :id (work/gate-actions :triage true :notion)))
      "Notion parked triage: Dismiss offered")
  (is (some #{:dismiss} (map :id (work/gate-actions :triage true :slack)))
      "Slack parked triage: Dismiss offered")
  (is (= [:apply :dismiss] (map :id (work/gate-actions :triage false :notion)))
      "Notion unparked triage: Dismiss, beside the accept that needs no agent")
  (is (= [:apply :dismiss] (map :id (work/gate-actions :triage false :slack)))
      "Slack unparked triage: same")
  (is (= (work/gate-actions :triage true :notion) (work/gate-actions :triage true))
      "origin no longer changes the result"))

(deftest dismissed-stage-offers-only-restore
  (let [restore [{:id :restore :label "Restore" :kind :mutation :style :default}]]
    (is (= restore (work/gate-actions :dismissed false)))
    (is (= restore (work/gate-actions :dismissed true))
        "a dismissed row offers Restore whether or not a session is still parked")
    (is (= restore (work/gate-actions :dismissed false :notion)))))

(def ^:private product-proposal
  {:format      :proposed-ticket
   :title       "Long links run past the chat bubble"
   :ticket-type "bug"
   :priority    "3 - Could"
   :source-url  "https://myco.slack.com/archives/C1/p100"
   :problem     "Message text overflows the bubble."
   :root-cause  "The body paragraph has no break-words; verified live in REPL."
   :fix         "Add break-words at chat.clj:477. Small."
   :app-domain  "Teacher"
   :product     {:where           "The Teaching Assistant chat, as a teacher."
                 :what-happens    "Long links run past the right edge of the bubble."
                 :expected        "Text wraps inside the bubble."
                 :who-is-affected "Any teacher using the chat."}})

(def ^:private proposed-ticket-edn
  "A ProposedTicket as written before :product/:doubt — no longer writable, still on disk in
   ledgers, and still accepted the way it was when written. Plant it with `legacy-append!`."
  (pr-str {:format :proposed-ticket
           :title "Logo bug on the pricing page"
           :ticket-type "bug"
           :priority "2 - Should"
           :source-url "https://myco.slack.com/archives/C1/p100"
           :problem "The logo disappears on mobile Safari."
           :root-cause "CSS media-query regression; verified live in REPL."
           :fix "Restore the logo rule for the mobile breakpoint. pricing.clj:42. Revert-shaped, small."}))

(defn- legacy-append!
  "Append `edn` as an old-era entry: what the write contract accepted when it was written."
  [ref edn]
  (with-redefs [report/validate-event (fn [_kind r] r)]
    (workstream/append-to-ref! :brian ref {:kind :proposed-ticket} edn)))

(deftest apply-proposed-creates-ticket-associates-ref-and-posts-link
  (with-tmp
    (fn [_]
      (let [ws       (workstream/create! :brian {:external-refs [{:adapter :slack-message
                                                                  :id "slack-C1-100.0" :title "msg"}]})
            posted   (atom nil)
            captured (atom nil)]
        (tickets/open! :brian "slack-C1-100.0" {:title "msg"})
        (legacy-append! "slack-C1-100.0" proposed-ticket-edn)
        (with-redefs [views/load-registry (fn [_] {:database "db-1"})
                      notion-client/keychain-token (fn [] "ntok")
                      notion-client/resolve-data-source-id (fn [_ _] "ds-1")
                      notion-client/create-page!
                      (fn [_ds _tok fields]
                        (reset! captured fields)
                        {:id "BR-4900" :page-id "pg" :url "https://notion/pg"})
                      slack-client/keychain-token (fn [] "stok")
                      slack-client/post-message (fn [ch _ opts] (reset! posted {:ch ch :opts opts}) {:ok true})]
          (let [r (work/apply! :brian (:id ws))]
            (is (= :created (:decision r)))
            (is (= "BR-4900" (:br r)))
            (is (= (:id ws) (:id (workstream/find-by-ref-id :brian "BR-4900")))
                "the BR-#### associates to THIS workstream — one ledger")
            (is (str/includes? (:description @captured) "**Problem**")
                "Notion body is the compact render, not a raw :description essay")
            (is (str/includes? (:description @captured) "**Fix**"))
            (is (= "C1" (:ch @posted)) "posts to the origin channel")
            (is (= "100.0" (get-in @posted [:opts :thread-ts])) "threaded on the message ts")
            (is (re-find #"notion/pg" (get-in @posted [:opts :text])) "link-back carries the page url")
            (is (= :triaged (tickets/status :brian "slack-C1-100.0"))
                "the slack-id ticket record completes → parked session sweeps to :done")))))))

(deftest apply-legacy-proposal-files-at-most-once
  (with-tmp
    (fn [_]
      (let [ws      (workstream/create! :brian {:external-refs [{:adapter :slack-message
                                                                 :id "slack-C1-100.0" :title "msg"}]})
            creates (atom 0)]
        (tickets/open! :brian "slack-C1-100.0" {:title "msg"})
        (legacy-append! "slack-C1-100.0" proposed-ticket-edn)
        (with-redefs [views/load-registry (fn [_] {:database "db-1"})
                      notion-client/keychain-token (fn [] "ntok")
                      notion-client/resolve-data-source-id (fn [_ _] "ds-1")
                      notion-client/create-page! (fn [& _] (swap! creates inc) {:error :network})
                      slack-client/keychain-token (fn [] "stok")
                      slack-client/post-message (fn [& _] {:ok true})]
          (is (= :network (:error (work/apply! :brian (:id ws)))))
          (is (= {:decision :error :error :filing-interrupted} (work/apply! :brian (:id ws)))
              "an Apply whose create may have landed is not retried blind")
          (is (= 1 @creates)))))))

(deftest apply-proposed-error-leaves-ws-parked
  (with-tmp
    (fn [_]
      (let [ws     (workstream/create! :brian {:external-refs [{:adapter :slack-message
                                                                :id "slack-C1-100.0" :title "msg"}]})
            posted (atom nil)]
        (tickets/open! :brian "slack-C1-100.0" {:title "msg"})
        (workstream/append-to-ref! :brian "slack-C1-100.0" {:kind :proposed-ticket}
                                   (pr-str product-proposal))
        (with-redefs [views/load-registry (fn [_] {:database "db-1"})
                      notion-client/keychain-token (fn [] "ntok")
                      notion-client/resolve-data-source-id (fn [_ _] "ds-1")
                      notion-client/create-page! (fn [_ds _tok _fields] {:error :auth})
                      slack-client/keychain-token (fn [] "stok")
                      slack-client/post-message (fn [ch _ opts] (reset! posted {:ch ch :opts opts}) {:ok true})]
          (let [r (work/apply! :brian (:id ws))]
            (is (= {:decision :error :error :auth} r))
            (is (nil? (workstream/find-by-ref-id :brian "BR-4900")) "no notion ref added on error")
            (is (nil? @posted) "no Slack post on error")
            (is (= :awaiting-input (tickets/status :brian "slack-C1-100.0"))
                "ticket not completed → ws stays parked & re-approvable")))))))

(deftest apply-proposed-guards-against-missing-slack-ref
  ;; Defensive: apply-proposed! is only reachable via the real Slack intake path,
  ;; where a proposal workstream always carries a :slack-message ref — but if one
  ;; ever lacked it (or carried a malformed id), the guard must short-circuit
  ;; BEFORE create-page! rather than create a Notion page and write ticket meta
  ;; under a nil key.
  (with-tmp
    (fn [_]
      (let [ws (workstream/create! :brian {:external-refs []})]
        (workstream/append-entry! :brian (:id ws) {:kind :proposed-ticket} (pr-str product-proposal))
        (with-redefs [notion-client/create-page!
                      (fn [& _] (throw (ex-info "create-page! must not be called" {})))]
          (let [r (work/apply! :brian (:id ws))]
            (is (= {:decision :error :error :no-slack-ref} r))
            (is (nil? (workstream/find-by-ref-id :brian "BR-4900"))
                "no notion ref added")))))))

;; ── Slack proposals concluded before acceptance ─────────────────────────────

(defn- slack-proposal-ws!
  "A triaging Slack workstream whose skill has recorded its ticket and appended `proposal`."
  [proposal]
  (let [ws (workstream/create! :brian {:external-refs [{:adapter :slack-message
                                                        :id "slack-C1-100.0" :title "msg"}]})]
    (tickets/open! :brian "slack-C1-100.0" {:title "msg"})
    (workstream/append-to-ref! :brian "slack-C1-100.0" {:kind :proposed-ticket} (pr-str proposal))
    ws))

(defn- with-slack-stubs
  "Run `f` with Notion and Slack stubbed. `opts`: :create (fn [fields] result), :post (fn
   [opts] result), :toggle (fn [page-id title sections] result). Returns what was called:
   {:created [fields …] :posted [opts …] :toggled [[page title sections] …] :started [ws-id …]}, a
   start being the workstream's unit opened in the core."
  [{:keys [create post toggle]} f]
  (let [calls (atom {:created [] :posted [] :toggled [] :started []})]
    (with-redefs [views/load-registry                  (fn [_] {:database "db-1"})
                  notion-client/keychain-token         (fn [] "ntok")
                  notion-client/resolve-data-source-id (fn [_ _] "ds-1")
                  notion-client/create-page!
                  (fn [_ds _tok fields]
                    (swap! calls update :created conj fields)
                    ((or create (constantly {:id "BR-4900" :page-id "pg" :url "https://notion/pg"}))
                     fields))
                  notion-client/append-to-toggle!
                  (fn [page-id title sections _tok]
                    (swap! calls update :toggled conj [page-id title sections])
                    ((or toggle (constantly {:ok true})) page-id title sections))
                  slack-client/keychain-token          (fn [] "stok")
                  slack-client/post-message
                  (fn [_ch _tok opts]
                    (swap! calls update :posted conj opts)
                    ((or post (constantly {:ok true})) opts))
                  work/core-resolve!                   (fn [_ ws-id action _]
                                                         (when (= :promote action) (swap! calls update :started conj ws-id))
                                                         {:decision :promoted})]
      (f)
      @calls)))

(deftest conclude-files-the-product-page-for-verification
  (with-tmp
    (fn [_]
      (let [ws (slack-proposal-ws! product-proposal)
            r  (atom nil)
            c  (with-slack-stubs {} #(reset! r (work/conclude! :brian (:id ws))))
            fields (first (:created c))]
        (is (= :filed (:decision @r)))
        (is (= :posted (:link @r)))
        (is (= {:status "Needs verification" :lifecycle "Development" :app-domain "Teacher"
                :ball-holder "169d872b-594c-8160-b432-000250f98e86"}
               (select-keys fields [:status :lifecycle :app-domain :ball-holder])))
        (let [body (pr-str (:children fields))]
          (is (= 1 (count (:children fields))) "the body is one callout")
          (is (= "callout" (:type (first (:children fields)))))
          (is (str/includes? body "Long links run past the right edge"))
          (is (not (str/includes? body "break-words")) "no technical text on the page")
          (is (not (str/includes? body "3 - Could")) "no property restated in the body"))
        (is (= (:id ws) (:id (workstream/find-by-ref-id :brian "BR-4900"))))
        (is (= "100.0" (:thread-ts (first (:posted c)))))
        (is (str/includes? (:text (first (:posted c))) "https://notion/pg"))
        (is (empty? (:started c)) "conclude never starts a drive")
        (is (tickets/held-by-gate? :brian "BR-4900"))
        (is (= :skip-active (tickets/gate-decision :brian "BR-4900"))
            "the Notion-side triage stands down for the filed page")))))

(deftest conclude-files-once-and-retries-a-failed-link
  (with-tmp
    (fn [_]
      (let [ws (slack-proposal-ws! product-proposal)
            c1 (with-slack-stubs {:post (constantly {:error :network})}
                 #(is (= :failed (:link (work/conclude! :brian (:id ws))))))
            r  (atom nil)
            c2 (with-slack-stubs {} #(reset! r (work/conclude! :brian (:id ws))))]
        (is (= 1 (count (:created c1))))
        (is (= :already-filed (:decision @r)))
        (is (empty? (:created c2)) "a filed workstream is never filed again")
        (is (= :posted (:link @r)) "the link that failed is posted on the next call")
        (let [c3 (with-slack-stubs {} #(reset! r (work/conclude! :brian (:id ws))))]
          (is (= :already (:link @r)))
          (is (empty? (:posted c3)) "a link Slack confirmed is not posted twice"))))))

(deftest conclude-mark-refuses-an-interrupted-filing
  (with-tmp
    (fn [_]
      (let [ws (slack-proposal-ws! product-proposal)]
        (with-slack-stubs {:create (constantly {:error :http :status 400})}
          #(is (= :http (:error (work/conclude! :brian (:id ws))))))
        (is (nil? (:filing-started-at (tickets/read-meta :brian "slack-C1-100.0")))
            "a definite refusal created nothing, so the mark is cleared and filing may retry")
        (with-slack-stubs {:create (constantly {:error :network})}
          #(is (= :network (:error (work/conclude! :brian (:id ws))))))
        (let [c (with-slack-stubs {}
                  #(is (= {:decision :error :error :filing-interrupted}
                          (work/conclude! :brian (:id ws)))))]
          (is (empty? (:created c)) "an unknown outcome is never retried blind"))))))

(deftest conclude-doubt-mentions-the-owner-and-files-nothing
  (with-tmp
    (fn [_]
      (let [doubt (-> product-proposal (dissoc :product :app-domain)
                      (assoc :doubt "Works as designed at normal zoom."))
            ws    (slack-proposal-ws! doubt)
            c     (with-slack-stubs {}
                    #(is (= {:decision :doubted} (work/conclude! :brian (:id ws)))))]
        (is (empty? (:created c)))
        (is (= "<@U08720YNAT1> Works as designed at normal zoom." (:text (first (:posted c)))))
        (is (= "100.0" (:thread-ts (first (:posted c)))))
        (with-slack-stubs {}
          #(is (= {:decision :error :error :doubted} (work/apply! :brian (:id ws)))
               "a doubt is overridden by a revised proposal, never accepted"))))))

(deftest apply-writes-the-analysis-then-starts-the-drive
  (with-tmp
    (fn [_]
      (let [ws (slack-proposal-ws! product-proposal)
            _  (with-slack-stubs {} #(work/conclude! :brian (:id ws)))
            r  (atom nil)
            c  (with-slack-stubs {} #(reset! r (work/apply! :brian (:id ws))))
            [page title sections] (first (:toggled c))]
        (is (= {:decision :created :br "BR-4900"} @r))
        (is (empty? (:created c)) "apply after conclude files nothing more")
        (is (= ["pg" "Engineering notes & history"] [page title]))
        (is (= ["Problem" "Root cause" "Fix"] (map :heading sections)))
        (is (= 1 (count (:started c))) "the ticket is started at once, its impl session brought up")
        (is (= :planning (tickets/status :brian "BR-4900") (tickets/status :brian "slack-C1-100.0"))
            "both refs name the one workstream, whose drive is now claimed")))))

(deftest apply-starts-nothing-when-the-analysis-write-fails
  (with-tmp
    (fn [_]
      (let [ws (slack-proposal-ws! product-proposal)
            c  (with-slack-stubs {:toggle (constantly {:error :server})}
                 #(is (= {:decision :error :error :server} (work/apply! :brian (:id ws)))))]
        (is (= 1 (count (:created c))) "an unfiled proposal is filed first")
        (is (empty? (:started c)))
        (is (tickets/held-by-gate? :brian "BR-4900") "the gate keeps holding the ticket")))))

(deftest apply-non-proposal-still-finalizes-nido-side
  ;; Regression: the proposal branch must not disturb the legacy nido-only apply.
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:external-refs [{:adapter :notion :id "BR-B1" :title "t"}]})]
        (tickets/open! :brian "BR-B1" {:title "t"})
        (with-redefs [nido.coordinator.lane.facets/refresh-for-ticket! (fn [& _] nil)]
          (is (= {:decision :applied} (work/apply! :brian (:id w))))
          (is (= :triaged (tickets/status :brian "BR-B1"))))))))

(defn- routed-report-edn
  "TriageReport EDN string for a routed triage. `depth` :shallow ⇒ notion-writes nil.
   `directions` are asked as one question; `questions` adds further ones after it. Both, and
   `effort`, default to the questionless shape the older apply tests were written against."
  [{:keys [owner app-domain depth directions questions effort]}]
  (pr-str
   (cond-> {:format :triage-report :ticket-key "BR-77" :determination :bug
            :title "t" :summary "s" :confidence {:level :high :reason "r"}
            :routing {:owner owner :app-domain app-domain :depth depth}
            :questions (into (if (seq directions)
                               [{:question "Where should the total be rounded?" :context "c"
                                 :after "The ticket moves on with the fix you chose."
                                 :directions directions}]
                               [])
                             questions)
            :notion-writes nil :trail []}
     (= depth :deep)
     (assoc :notion-writes {:type "bug" :effort (or effort :M)
                            :status-transition ["Needs verification" "Not started"]
                            :title "enriched title"
                            :description-prepend "the enriched body"}))))

(defn- with-routed-ws
  "A parked Notion triage ws whose latest ledger entry is a routed :triage-report.
   `opts` reaches routed-report-edn, so a caller can give the report directions."
  ([depth owner app-domain f] (with-routed-ws depth owner app-domain nil f))
  ([depth owner app-domain opts f]
   (let [w (workstream/create! :brian {:external-refs [{:adapter :notion :id "BR-77"
                                                        :page-id "pg-77" :title "t"}]})]
     (tickets/open! :brian "BR-77" {:title "t"})
     (workstream/append-to-ref! :brian "BR-77" {:kind :triage}
                                (routed-report-edn (merge {:owner owner :app-domain app-domain
                                                           :depth depth}
                                                          opts)))
     (f w))))

(deftest apply-routed-shallow-writes-ball-holder-and-additive-app-domain
  (with-tmp
    (fn [_]
      (with-routed-ws :shallow :eric "Backend"
        (fn [w]
          (let [props (atom nil)]
            (with-redefs [notion-client/keychain-token (fn [] "tok")
                          notion-client/retrieve-page
                          (fn [_ _] {:properties {(keyword "App Domain")
                                                  {:multi_select [{:name "Teacher"}]}}})
                          notion-client/update-page-properties!
                          (fn [_pg p _tok] (reset! props p) {:ok true})
                          nido.coordinator.lane.facets/refresh-for-ticket! (fn [& _] nil)]
              (let [r (work/apply! :brian (:id w))]
                (is (= :applied (:decision r)))
                (is (= {:people [{:id "955b4c25-7bce-4ca2-ab5e-d99acbcd423a"}]}
                       (get @props "Ball Holder")))
                (is (= #{"Teacher" "Backend"}
                       (set (map :name (:multi_select (get @props "App Domain")))))
                    "App Domain unions the routed value with the page's current tags")
                (is (nil? (get @props "Status")) "shallow writes no Status")
                (is (nil? (get @props "Type")) "shallow writes no Type")
                (is (= :triaged (tickets/status :brian "BR-77")))))))))))

(deftest apply-routed-deep-writes-properties-and-prepends-callout
  (with-tmp
    (fn [_]
      (with-routed-ws :deep :jaap "Teacher"
        (fn [w]
          (let [props (atom nil) prepended (atom nil)]
            (with-redefs [notion-client/keychain-token (fn [] "tok")
                          notion-client/retrieve-page (fn [_ _] {:properties {}})
                          notion-client/update-page-properties!
                          (fn [_pg p _tok] (reset! props p) {:ok true})
                          notion-client/retrieve-block-children (fn [_ _ _] {:results []})
                          notion-client/prepend-block-children!
                          (fn [_pg children _tok] (reset! prepended children) {:ok true})
                          nido.coordinator.lane.facets/refresh-for-ticket! (fn [& _] nil)]
              ;; make the placement verify pass: first child after prepend is our callout
              (with-redefs [notion-client/retrieve-block-children
                            (fn [_ _ _] {:results [{:type "callout"
                                                    :callout {:rich_text [{:text {:content "🤖 Enriched (triage BR-77)\nx"}}]}}]})]
                (let [r (work/apply! :brian (:id w))]
                  (is (= :applied (:decision r)))
                  (is (= {:name "Not started"} (:status (get @props "Status"))) "deep sets Status to the transition target")
                  (is (= {:name "bug"} (:select (get @props "Type"))))
                  (is (= {:name "M"} (:select (get @props "Effort"))))
                  (is (= "enriched title" (get-in @props ["Task result" :title 0 :text :content])))
                  (is (some? @prepended) "deep prepends a callout")
                  (is (= :triaged (tickets/status :brian "BR-77"))))))))))))


;; ── Accepting a verdict, and choosing a branch while you do it ─────────────
;; Every other answer at a gate appends an entry naming what was decided. Apply
;; wrote to Notion and to the ticket record and left the ledger silent, so a
;; later session could not tell an accepted verdict from an unanswered one.

(defn- notion-write-redefs
  "with-redefs bindings for a deep apply that succeeds. `props` and `prepended`
   capture what would have been written."
  [props prepended]
  {#'notion-client/keychain-token        (fn [] "tok")
   #'notion-client/retrieve-page         (fn [_ _] {:properties {}})
   #'notion-client/update-page-properties! (fn [_pg p _tok] (reset! props p) {:ok true})
   #'notion-client/retrieve-block-children
   (fn [_ _ _] {:results [{:type "callout"
                           :callout {:rich_text [{:text {:content "🤖 Enriched (triage BR-77)\nx"}}]}}]})
   #'notion-client/prepend-block-children! (fn [_pg children _tok]
                                             (reset! prepended children) {:ok true})
   #'nido.coordinator.lane.facets/refresh-for-ticket! (fn [& _] nil)})

(deftest choosing-a-direction-lands-its-size-and-records-the-choice
  (with-tmp
    (fn [_]
      (with-routed-ws :deep :jaap "Teacher"
        {:directions sample-directions :effort :squirrel}
        (fn [w]
          (let [props (atom nil) prepended (atom nil)]
            (with-redefs-fn (notion-write-redefs props prepended)
              (fn []
                (is (= :applied (:decision (work/resolve-gate! :brian (:id w) :apply {:seq 1 :answers ["B"]}))))
                (is (= {:name "L"} (:select (get @props "Effort")))
                    "the branch's own size lands, in place of the deferred one the
                     report carried — which is the whole reason to click a branch")))
            (let [accepted (:report (work/workstream :brian (:id w) 2))]
              (is (= :triage-accepted (:format accepted)))
              (is (= 1 (:triage-seq accepted)) "it cites the report it accepted")
              (is (= [{:question  "Where should the total be rounded?"
                       :direction {:letter "B" :label "Introduce a Money type"
                                   :shape "Make per-line rounding unrepresentable." :effort :L}}]
                     (:answers accepted))
                  "and carries the branch in full — a reader of the timeline should
                   not have to open the entry this points at to learn what was decided"))
            (is (not (str/includes? (get-in (first @prepended) [:callout :rich_text 0 :text :content])
                                    "Money type"))
                "the label reaches Notion nowhere: which branch was taken is the
                 decision itself, and it lives in the acceptance entry alone")))))))

(deftest deferring-the-decision-applies-the-routing-and-says-so
  (with-tmp
    (fn [_]
      (with-routed-ws :deep :jaap "Teacher"
        {:directions sample-directions :effort :squirrel}
        (fn [w]
          (let [props (atom nil) prepended (atom nil)]
            (with-redefs-fn (notion-write-redefs props prepended)
              (fn []
                (is (= :applied (:decision (work/resolve-gate! :brian (:id w) :defer 1))))
                (is (nil? (get @props "Effort"))
                    "nothing guesses a size to fill the hole — :squirrel is not a
                     value the Effort select holds")
                (is (str/includes? (get-in (first @prepended) [:callout :rich_text 0 :text :content])
                                   "Effort left open")
                    "so the page says the size is open on purpose, which is what
                     keeps the seam visible rather than reading as neglect")))
            (let [accepted (:report (work/workstream :brian (:id w) 2))]
              (is (= :triage-accepted (:format accepted)))
              (is (= [{:question "Where should the total be rounded?"}] (:answers accepted))
                  "every question answered as open — the routing stands and the
                   fixes were deliberately left to whoever builds it"))))))))

(def ^:private second-question
  {:question "Should the receipt show the rounding?" :context "c2"
   :after "The receipt is built as you choose."
   :directions [{:label "Show it" :shape "Print a rounding line." :effort :XS
                 :confidence {:level :high :reason "r"}}
                {:label "Hide it" :shape "Print nothing." :effort :M
                 :confidence {:level :high :reason "r"}}]})

(deftest accepting-several-questions-records-an-answer-for-each
  (with-tmp
    (fn [_]
      (with-routed-ws :deep :jaap "Teacher"
        {:directions sample-directions :questions [second-question] :effort :squirrel}
        (fn [w]
          (let [props (atom nil) prepended (atom nil)]
            (with-redefs-fn (notion-write-redefs props prepended)
              (fn []
                (is (= :applied (:decision (work/resolve-gate! :brian (:id w) :apply
                                                               {:seq 1 :answers ["A" "B"]}))))
                (is (= {:name "M"} (:select (get @props "Effort")))
                    "the largest of the chosen sizes — S and M — is the ticket's")
                (is (str/includes? (get-in (first @prepended) [:callout :rich_text 0 :text :content])
                                   "Effort is the largest of the chosen fixes (S, M).")
                    "and the page says the sizes differed")))
            (is (= [["Where should the total be rounded?" "A"]
                    ["Should the receipt show the rounding?" "B"]]
                   (map (juxt :question (comp :letter :direction))
                        (:answers (workstream/latest-entry :brian (:id w) :triage-accepted))))
                "one answer per question, in the report's order")))))))

(deftest a-question-may-be-left-open
  (with-tmp
    (fn [_]
      (with-routed-ws :deep :jaap "Teacher"
        {:directions sample-directions :questions [second-question] :effort :squirrel}
        (fn [w]
          (let [props (atom nil) prepended (atom nil)]
            (with-redefs-fn (notion-write-redefs props prepended)
              (fn []
                (is (= :applied (:decision (work/resolve-gate! :brian (:id w) :apply
                                                               {:seq 1 :answers [nil "A"]}))))
                (is (= {:name "XS"} (:select (get @props "Effort"))))))
            (is (= [nil "A"]
                   (map (comp :letter :direction)
                        (:answers (workstream/latest-entry :brian (:id w) :triage-accepted)))))))))))

(deftest an-accept-that-does-not-answer-this-report-writes-nothing
  (with-tmp
    (fn [_]
      (with-routed-ws :deep :jaap "Teacher"
        {:directions sample-directions :questions [second-question]}
        (fn [w]
          (with-redefs [notion-client/keychain-token
                        (fn [] (throw (ex-info "Notion must not be touched" {})))]
            (is (= {:decision :triage-stale}
                   (work/resolve-gate! :brian (:id w) :apply {:seq 1 :answers ["A"]}))
                "one answer for two questions was given over a different report")
            (is (= {:decision :triage-stale}
                   (work/resolve-gate! :brian (:id w) :apply {:seq 1 :answers ["A" "C"]}))
                "a letter that names no direction of its question")
            (is (= {:decision :triage-stale} (work/resolve-gate! :brian (:id w) :apply 1))
                "an Accept that carried no answers answers none of the questions"))
          (is (= 1 (count (:entries (workstream/read-ws :brian (:id w)))))))))))

(deftest accepting-a-report-the-ledger-has-moved-past-writes-nothing
  (with-tmp
    (fn [_]
      (with-routed-ws :deep :jaap "Teacher" {:directions sample-directions}
        (fn [w]
          ;; Another tab answered; the ledger's latest entry is the acceptance.
          (workstream/append-to-ref! :brian "BR-77" {:kind :triage-accepted}
                                     (pr-str {:format :triage-accepted :triage-seq 1}))
          (with-redefs [notion-client/keychain-token
                        (fn [] (throw (ex-info "Notion must not be touched" {})))]
            (is (= {:decision :triage-stale} (work/resolve-gate! :brian (:id w) :apply 1)))
            (is (= {:decision :triage-stale} (work/resolve-gate! :brian (:id w) :apply {:seq 1 :answers ["A"]}))
                "a click against a page the ledger has moved past accepts a verdict
                 the human was not looking at"))
          (is (= 2 (count (:entries (workstream/read-ws :brian (:id w)))))
              "and nothing was appended for the refusal either"))))))

(deftest accepting-twice-is-refused-by-the-same-position-check
  (with-tmp
    (fn [_]
      (with-routed-ws :deep :jaap "Teacher" {:directions sample-directions}
        (fn [w]
          (let [props (atom nil) prepended (atom nil)]
            (with-redefs-fn (notion-write-redefs props prepended)
              (fn []
                (is (= :applied (:decision (work/resolve-gate! :brian (:id w) :apply {:seq 1 :answers ["A"]}))))
                (reset! props nil)
                (reset! prepended nil)
                (is (= {:decision :triage-stale}
                       (work/resolve-gate! :brian (:id w) :apply {:seq 1 :answers ["B"]}))
                    "a second click of the same rendered page answers a report already accepted")
                (is (nil? @props) "and carries nothing out to Notion again")
                (is (nil? @prepended))
                (is (= 1 (count (workstream/entries-of :brian (:id w) :triage-accepted)))
                    "nor records a second acceptance")))))))))

(deftest a-letter-no-direction-answers-is-refused
  (with-tmp
    (fn [_]
      (with-routed-ws :deep :jaap "Teacher" {:directions sample-directions}
        (fn [w]
          (with-redefs [notion-client/keychain-token
                        (fn [] (throw (ex-info "Notion must not be touched" {})))]
            (is (= {:decision :triage-stale} (work/resolve-gate! :brian (:id w) :apply {:seq 1 :answers ["C"]}))
                "the click carries a letter; the CURRENT ledger decides what it meant")))))))

(deftest a-failed-notion-write-records-nothing-and-stays-retryable
  ;; The order is the point, and it is the OPPOSITE of choose-option!'s. A resume
  ;; is a notification the decision stands without, so that record goes first. The
  ;; Notion write is the decision being carried out — it can fail, and the gate's
  ;; contract is that a failed apply is retried — so recording first would make the
  ;; acceptance the latest entry and the position check would refuse the retry.
  (with-tmp
    (fn [_]
      (with-routed-ws :deep :jaap "Teacher" {:directions sample-directions}
        (fn [w]
          (with-redefs [notion-client/keychain-token (fn [] "tok")
                        notion-client/retrieve-page (fn [_ _] {:error :http})]
            (is (= {:decision :notion-failed :error :http}
                   (work/resolve-gate! :brian (:id w) :apply {:seq 1 :answers ["A"]}))))
          (is (= 1 (count (:entries (workstream/read-ws :brian (:id w)))))
              "nothing was appended, so the gate is exactly as it was")
          (is (= :awaiting-input (tickets/status :brian "BR-77"))
              "and the ticket is still parked")
          (let [props (atom nil) prepended (atom nil)]
            (with-redefs-fn (notion-write-redefs props prepended)
              (fn []
                (is (= :applied (:decision (work/resolve-gate! :brian (:id w) :apply {:seq 1 :answers ["A"]})))
                    "so the same click, from the same page, works on the retry")))))))))

(deftest the-position-check-is-equality-not-presence
  ;; A click that carried NO position is stale against a ledger holding a
  ;; positioned report — that is the fail-closed. It is NOT stale against a ledger
  ;; holding none: the intake-text fallback has no :seq, and a ledger with nothing
  ;; in it cannot have moved past what was on screen. Reading the rule as
  ;; "a position is required" instead took the legacy nido-side apply away from
  ;; exactly the rows it exists for.
  (with-tmp
    (fn [_]
      (with-routed-ws :deep :jaap "Teacher" {:directions sample-directions}
        (fn [w]
          (with-redefs [notion-client/keychain-token
                        (fn [] (throw (ex-info "Notion must not be touched" {})))]
            (is (= {:decision :triage-stale} (work/resolve-gate! :brian (:id w) :apply))
                "a positioned report, a click with no position → refused"))))))
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:external-refs [{:adapter :notion :id "BR-L1" :title "t"}]})]
        (tickets/open! :brian "BR-L1" {:title "t"})
        (with-redefs [nido.coordinator.lane.facets/refresh-for-ticket! (fn [& _] nil)]
          (is (= {:decision :applied} (work/resolve-gate! :brian (:id w) :apply))
              "an empty ledger, a click with no position → applied")
          (is (= :triaged (tickets/status :brian "BR-L1")))
          (is (= [{:format :triage-accepted}]
                 (map #(select-keys % [:format :triage-seq])
                      (workstream/entries-of :brian (:id w) :triage-accepted)))
              "and the acceptance cites nothing — there is no report to cite — accepting the
               ticket as reported"))))))

(deftest the-cli-apply-carries-no-position-and-still-records
  ;; `bb nido:ticket:apply` is the agent executing its own verdict inside its own
  ;; turn: there is no render-and-click gap for a position to guard. It still
  ;; leaves the trace, because a human confirmed it in chat.
  (with-tmp
    (fn [_]
      (with-routed-ws :deep :jaap "Teacher" {:directions sample-directions}
        (fn [w]
          (let [props (atom nil) prepended (atom nil)]
            (with-redefs-fn (notion-write-redefs props prepended)
              (fn [] (is (= :applied (:decision (work/apply! :brian (:id w)))))))
            (let [accepted (:report (work/workstream :brian (:id w) 2))]
              (is (= :triage-accepted (:format accepted)))
              (is (= 1 (:triage-seq accepted)))
              (is (not-any? :direction (:answers accepted))
                  "the CLI path chooses no branch — it applies the report as written"))))))))

(deftest enriched-callout-splits-a-long-body-into-capped-runs
  ;; The callout is best-effort, so an over-2000-char run only ever showed up as
  ;; :warn — silently dropping the enrichment on the longest reports.
  (let [prepended (atom nil)
        desc      (apply str (repeat 700 "long body "))]   ; 7000 chars
    (with-redefs [notion-client/retrieve-block-children
                  (fn [_ _ _] {:results [{:type "callout"
                                          :callout {:rich_text [{:text {:content "🤖 Enriched (triage BR-77)\nx"}}]}}]})
                  notion-client/prepend-block-children!
                  (fn [_pg children _tok] (reset! prepended children) {:ok true})]
      (is (= :ok (#'work/prepend-enriched-callout! "pg-77" "BR-77" desc "tok")))
      (let [runs (get-in (first @prepended) [:callout :rich_text])]
        (is (< 1 (count runs)) "a 7000-char body must be split across runs")
        (is (every? #(<= (count (get-in % [:text :content])) notion-client/rich-text-limit) runs))
        (is (str/starts-with? (get-in (first runs) [:text :content]) "🤖 Enriched (triage BR-77)")
            "the marker stays in the FIRST run — our-callout? idempotency reads it there")))))

(deftest apply-routed-notion-failure-does-not-complete
  (with-tmp
    (fn [_]
      (with-routed-ws :shallow :eric "Backend"
        (fn [w]
          (with-redefs [notion-client/keychain-token (fn [] "tok")
                        notion-client/retrieve-page (fn [_ _] {:properties {}})
                        notion-client/update-page-properties! (fn [_ _ _] {:error :server})
                        nido.coordinator.lane.facets/refresh-for-ticket! (fn [& _] nil)]
            (let [r (work/apply! :brian (:id w))]
              (is (= :notion-failed (:decision r)))
              (is (= :server (:error r)))
              (is (= :awaiting-input (tickets/status :brian "BR-77"))
                  "a failed Notion write leaves the ticket parked, NOT triaged"))))))))

(deftest apply-routed-page-read-failure-does-not-clobber-or-complete
  (with-tmp
    (fn [_]
      (with-routed-ws :shallow :eric "Backend"
        (fn [w]
          (let [wrote (atom false)]
            (with-redefs [notion-client/keychain-token (fn [] "tok")
                          notion-client/retrieve-page (fn [_ _] {:error :server})
                          notion-client/update-page-properties! (fn [_ _ _] (reset! wrote true) {:ok true})
                          nido.coordinator.lane.facets/refresh-for-ticket! (fn [& _] nil)]
              (let [r (work/apply! :brian (:id w))]
                (is (= :notion-failed (:decision r)))
                (is (false? @wrote) "no property write is attempted when the page read failed — never clobber")
                (is (= :awaiting-input (tickets/status :brian "BR-77"))
                    "a failed read leaves the ticket parked, not triaged")))))))))

(deftest apply-routed-callout-bottom-landing-warns-but-completes
  (with-tmp
    (fn [_]
      (with-routed-ws :deep :jaap "Teacher"
        (fn [w]
          (with-redefs [notion-client/keychain-token (fn [] "tok")
                        notion-client/retrieve-page (fn [_ _] {:properties {}})
                        notion-client/update-page-properties! (fn [_ _ _] {:ok true})
                        ;; first child never becomes our callout ⇒ position stripped
                        notion-client/retrieve-block-children (fn [_ _ _] {:results [{:type "paragraph"}]})
                        notion-client/prepend-block-children! (fn [_ _ _] {:ok true})
                        nido.coordinator.lane.facets/refresh-for-ticket! (fn [& _] nil)]
            (let [r (work/apply! :brian (:id w))]
              (is (= :applied (:decision r)) "properties landed ⇒ still applied")
              (is (= :warn (:callout r)) "callout didn't land at the top ⇒ flagged")
              (is (= :triaged (tickets/status :brian "BR-77"))))))))))

(deftest mutations-noop-on-workstream-less-id
  (with-tmp
    (fn [_]
      ;; A page-id that is not a real workstream (a bare row's ws-id).
      (is (= {:decision :no-workstream} (work/set-stage! :brian "pg-bare" :in-progress)))
      (is (= {:decision :no-workstream} (work/set-stage! :brian "pg-bare" :done)))
      (is (= {:decision :no-workstream} (work/resolve-gate! :brian "pg-bare" :promote)))
      (is (= {:decision :no-workstream} (work/resolve-gate! :brian "pg-bare" :drop))
          "drop would otherwise throw Workstream-not-found")
      (is (= {:decision :no-workstream} (work/dismiss! :brian "pg-bare"))))))

(deftest live-session-names-probe-ports-rather-than-trusting-the-registry
  ;; The registry is only cleaned by a graceful down!; a reboot or a crash leaves
  ;; an entry with its port numbers intact forever. Liveness therefore has to be
  ;; measured, not read.
  (with-redefs [nido.session.lifecycle/list-all-data
                (fn [_] {:sessions (mapv (fn [n] {:name n :worktree (str "/w/" n)})
                                         ["app-up" "repl-up" "crashed" "shared-pg" "lite" "down"])})
                nido.session.state/read-registry
                (constantly {"/w/app-up"    {:instance-id "i1" :app-port 3101}
                             "/w/repl-up"   {:instance-id "i2" :nrepl-port 5601}
                             "/w/crashed"   {:instance-id "i3" :app-port 3102 :nrepl-port 5602}
                             "/w/shared-pg" {:instance-id "i4" :app-port 3103 :pg-port 6145}
                             "/w/lite"      {:instance-id "i5"}})
                nido.platform.process/tcp-open? (fn [port] (contains? #{3101 5601 6145} port))]
    (is (= #{"app-up" "repl-up" "lite"} (work/live-session-names "p"))
        "a session recording no app/nREPL port — a :lite one — is up while its entry stands")
    (is (not (contains? (work/live-session-names "p") "crashed"))
        "recorded ports that no longer answer are not liveness")
    (is (not (contains? (work/live-session-names "p") "shared-pg"))
        "pg is never a signal — the shared cluster answers for every session at once")
    (is (not (contains? (work/live-session-names "p") "down"))
        "a session the registry holds no entry for is down")))

(deftest nothing-but-the-core-says-a-row-runs
  (with-tmp
    (fn [_]
      (let [w   (workstream/create! :brian {:external-refs []})
            id  (:id w)
            row #(first (filter (fn [r] (= id (:ws-id r))) (work/list-workstreams :brian)))
            quiet {:owed-by :agent :live false :stalled false :paused false :needs-you false :needs nil}]
        (testing "an agent owes it and the core holds no unit of it: nothing is the core's to move"
          (is (= quiet (:status (row))))
          (is (= :idle (:engagement (row)))))
        (testing "a run's lock, a claim and a session up are processes, not what runs"
          (session/create! :brian id {:name "human-1" :weight :light :autonomy nil})
          (with-redefs [runs/live-runs                             (constantly ["r1"])
                        nido.coordinator.record.activity/held?     (constantly true)
                        nido.coordinator.record.activity/read-live (constantly {:kind :diff-review})
                        work/live-session-names                    (constantly #{"human-1"})]
            (is (= quiet (:status (row))))
            (is (nil? (:doing (row))))))))))

(deftest a-blocker-at-an-idle-workstream-reads-one-way-on-every-surface
  ;; brian ws-20261124-35a103 and ws-20261119-1c9605: a unit's agent wrote a blocker to the ledger
  ;; instead of asking through the core, and the core does nothing on it. They read Blocked with a
  ;; working dot, owed by the person, and were missing from Needs you.
  (with-tmp
    (fn [_]
      (let [w  (workstream/create! :brian {:external-refs [] :activated? true})
            id (:id w)
            _  (session/create! :brian id {:name "unit-1" :weight :heavy :autonomy nil})
            _  (workstream/append-entry! :brian id {:kind :blocker}
                 (pr-str {:format :blocker :summary "s" :needs "Leave the template change out?"
                          :options sample-options :after "The work resumes once you answer."}))
            row (first (filter #(= id (:ws-id %)) (work/list-workstreams :brian)))
            ws  (work/workstream :brian id)
            g   (first (filter #(= id (:ws-id %)) (work/gates :brian)))]
        (is (= :blocked (get-in row [:position :at])))
        (is (= {:owed-by :person :live false :stalled false :paused false :needs-you true :needs :ask}
               (:status row)))
        (is (nil? (:doing row)))
        (is (:needs-you row) "the board row needs the person")
        (is (= :parked-at-gate (:engagement row)))
        (is (= (:status row) (:status ws)) "the pane reads the row's activity")
        (is (= [[(get-in ws [:position :stage]) :awaiting]]
               (keep #(when (:now %) [(:stage %) (:now %)]) (get-in ws [:arc :stages])))
            "the arc waits on the person at the stage the blocker holds")
        (is (some? g) "it is on Needs you")
        (is (= :answer-blocker (:owes g)))
        (is (= :ask (:needs g)) "carded as an ask")
        (is (= :blocker (get-in g [:report :format])) "the card carries the blocker it answers")))))

(deftest a-blocker-past-the-implementation-is-awaited-past-the-spine
  ;; A position past the implementation names no spine stage, so no stage can be the one the
  ;; person is awaited at — and the arc still says they are, past the spine, in the category the
  ;; activity names: a blocker there as an ask, a release candidate as a landing.
  (with-tmp
    (fn [_]
      (let [w  (workstream/create! :brian {:external-refs [] :activated? true})
            id (:id w)]
        (doseq [[pos needs] [[{:at :blocked :next {:stage :answer-blocker :mode :human}} :ask]
                             [{:at :release-candidate :next {:stage :land :mode :release}} :landing]]]
          (with-redefs [pipeline/of (constantly pos)]
            (let [ws (work/workstream :brian id)]
              (is (nil? (get-in ws [:position :stage])) "the position names no spine stage")
              (is (= needs (get-in ws [:status :needs])))
              (is (empty? (filter :now (get-in ws [:arc :stages]))) "no stage is marked")
              (is (= {:needs needs} (get-in ws [:arc :past]))
                  "the arc says the person is awaited past the spine, in the activity's category"))))
        (with-redefs [pipeline/of (constantly {:at :implementing :next {:stage :implement :mode :authoring}
                                               :stage :implementation})]
          (is (nil? (get-in (work/workstream :brian id) [:arc :past]))
              "nothing is awaited past the spine while the person is not needed"))))))

(deftest a-merge-waiting-its-turn-reads-merging-queued-and-runs-nothing
  (with-tmp
    (fn [_]
      (let [w  (workstream/create! :brian {:external-refs [] :activated? true})
            id (:id w)]
        (with-redefs-fn {#'work/band-of (fn [& _] :shipping)}
          (fn []
            (let [row (first (filter #(= id (:ws-id %)) (work/list-workstreams :brian)))
                  ws  (work/workstream :brian id)]
              (is (= :shipping (:stage row)))
              (is (= {:source :merge :phase :queued} (:doing row)))
              (is (false? (get-in row [:status :live])) "no working dot")
              (is (= (:doing row) (:doing ws)))
              (is (empty? (filter #(= :running (:now %)) (get-in ws [:arc :stages])))
                  "no arc stage reads running")
              (is (= (:doing row) (get-in ws [:arc :past :doing]))
                  "the arc carries the queued merge past the spine, as the row's mark reads it"))))))))

(deftest entry-phase-reads-the-kind-of-entry
  (is (= :serving (nido.session.state/entry-phase {:instance-id "i" :app-port 4000})))
  (is (= :serving (nido.session.state/entry-phase {:instance-id "i" :nrepl-port 4000 :boot-pid 7}))
      "a recorded port wins: the boot's registration replaced the placeholder")
  (is (= :booting (nido.session.state/entry-phase {:instance-id "i" :boot-pid 7})))
  (is (= :lite    (nido.session.state/entry-phase {:instance-id "i"})))
  (is (nil? (nido.session.state/entry-phase {})))
  (is (nil? (nido.session.state/entry-phase nil))))

(deftest session-live?-reads-app-or-nrepl-only
  (with-redefs [nido.platform.process/tcp-open? (fn [port] (= 4000 port))]
    (is (true?  (nido.session.state/session-live? {:app-port 4000})))
    (is (true?  (nido.session.state/session-live? {:nrepl-port 4000})))
    (is (false? (nido.session.state/session-live? {:app-port 4001 :nrepl-port 4002})))
    (is (false? (nido.session.state/session-live? {:pg-port 4000}))  "pg excluded")
    (is (false? (nido.session.state/session-live? {:repl-pid 12345})) "pid excluded — PIDs get recycled")
    (is (false? (nido.session.state/session-live? {})))
    (is (false? (nido.session.state/session-live? nil)) "no entry is a session that is down")
    (is (true?  (nido.session.state/session-live? {:instance-id "i"})) "a :lite entry records no port")
    (is (false? (nido.session.state/session-live? {:instance-id "i" :boot-pid 77}))
        "a boot placeholder records no port and is not up")
    (is (false? (nido.session.state/session-live? {:instance-id "i" :app-port 4001}))
        "a recorded port is probed, whatever else the entry holds")
    (is (false? (nido.session.state/session-live? {:instance-id "i" :created-at "2000-01-01T00:00:00Z"}))
        "a :lite entry registered before this host boot died with it")
    (is (true?  (nido.session.state/session-live? {:instance-id "i" :created-at (str (java.time.Instant/now))}))
        "a :lite entry registered in this boot stands")))

(deftest machine-facts-keyed-by-session-name
  (with-redefs [work/machine-rows
                (fn [_ _] [{:name "a" :live? true :entry {:url "u" :pg-port 5501 :app-port 3101}
                            :repl-rss 1024 :pg-rss 2048 :heap-max "2g"}
                           {:name "b" :live? false :entry nil}])
                nido.platform.project/list-projects (constantly {"p" {:directory "/x"}})]
    (let [facts (work/machine-facts "p" ["a"])]
      (is (= ["a"] (keys facts)))
      (is (= {:live? true :url "u" :pg-port 5501 :nrepl-port nil :app-port 3101
              :runtime nil :pg-pid nil
              :repl-rss 1024 :pg-rss 2048 :heap-max "2g"}
             (get facts "a"))))))

(deftest machine-rows-name-the-runtime-their-rss-was-read-from
  (let [base (fs/create-temp-dir)]
    (try
      (doseq [n ["a" "b"]] (fs/create-dirs (fs/path base n)))
      (let [rt  {:instance-id "p--ws" :app-port 3101 :repl-pid 11}
            reg {(str (fs/path base "a")) rt (str (fs/path base "b")) rt}]
        (with-redefs [nido.session.lifecycle/worktrees-dir (constantly (str base))
                      nido.session.state/read-registry   (constantly reg)
                      nido.session.state/read-session    (constantly {:service-states {:pg {:pg-pid 22}}})
                      nido.platform.process/tcp-open?    (constantly true)
                      nido.platform.process/rss-bytes    (constantly 100)]
          (let [rows (work/machine-rows "p" "/x")]
            (is (= [["a" "p--ws" 22] ["b" "p--ws" 22]] (mapv (juxt :name :runtime :pg-pid) rows))
                "two sessions on one runtime say so, so a sum counts its RSS once"))))
      (finally (fs/delete-tree base)))))

(deftest all-machine-rows-aggregates-and-sorts-live-first
  (let [rows-fn  (fn [pname _dir]
                   (case pname
                     "brian" [{:name "b-down" :live? false :entry nil}
                              {:name "b-up"   :live? true  :entry {:url "u1"}}]
                     "foo"   [{:name "f-up"   :live? true  :entry {:url "u2"}}]))
        projects {"brian" {:directory "/x"} "foo" {:directory "/y"}}
        rows     (work/all-machine-rows rows-fn projects)]
    (is (= [["brian" "b-up" true] ["foo" "f-up" true] ["brian" "b-down" false]]
           (map (juxt :project :name :live?) rows)))))

(deftest prune-dead-registry-drops-only-dead-and-old-entries
  (let [now     1000000000000
        recent  (java.time.Instant/ofEpochMilli (- now 60000))       ; 1 min old
        ancient (java.time.Instant/ofEpochMilli (- now 86400000))    ; 1 day old
        removed (atom nil)]
    (with-redefs [nido.session.state/read-registry
                  (constantly {"/wt/live"    {:instance-id "p--live"    :app-port 3000
                                              :created-at (str ancient)}
                               "/wt/dead"    {:instance-id "p--dead"    :app-port 3001
                                              :created-at (str ancient)}
                               "/wt/young"   {:instance-id "p--young"   :app-port 3002
                                              :created-at (str recent)}
                               "/wt/no-ts"   {:instance-id "p--no-ts"   :app-port 3003}
                               ;; :lite session: :services [] means the engine never recorded a
                               ;; port at all — never a prune candidate (prunable?), else it would
                               ;; be pruned mid-use and reclaim would delete its state dir.
                               "/wt/lite"    {:instance-id "p--lite"    :created-at (str ancient)}
                               ;; dead port, but the JVM is still up — prune-veto? keeps it (a
                               ;; false "dead" here costs a PGDATA via reclaim).
                               "/wt/repl-up" {:instance-id "p--repl-up" :app-port 3004 :repl-pid 4242
                                              :created-at (str ancient)}
                               ;; boot placeholders: no port, only the pid of the background
                               ;; boot. Kept while that boot runs; prunable once it has died.
                               "/wt/booting" {:instance-id "p--booting" :boot-pid 5151
                                              :created-at (str ancient)}
                               "/wt/boot-died" {:instance-id "p--boot-died" :boot-pid 5252
                                                :created-at (str ancient)}
                               ;; malformed :created-at: entry-age-ms swallows the parse failure
                               ;; and returns nil, which reads as "old enough" — pruned.
                               "/wt/bad-ts"  {:instance-id "p--bad-ts"  :app-port 3005
                                              :created-at "not-a-timestamp"}})
                  nido.session.state/remove-many-from-registry!
                  (fn [ks] (reset! removed (set ks)))
                  nido.session.casualties/record! (constantly {:outcome :held :held []})
                  nido.platform.process/tcp-open? (fn [port] (= 3000 port))
                  nido.platform.process/process-alive? (fn [pid] (contains? #{4242 5151} pid))]
      (let [pruned (work/prune-dead-registry! now)]
        (is (= #{"/wt/dead" "/wt/no-ts" "/wt/bad-ts" "/wt/boot-died"} @removed)
            "dead + old is pruned; no timestamp or a malformed one reads as old and is pruned")
        (is (not (contains? @removed "/wt/live"))    "a listening session survives")
        (is (not (contains? @removed "/wt/young"))   "inside the grace window it survives")
        (is (not (contains? @removed "/wt/lite"))    "a lite session with no recorded port is never a prune candidate")
        (is (not (contains? @removed "/wt/repl-up")) "a dead port but a live repl-pid vetoes the delete")
        (is (not (contains? @removed "/wt/booting")) "a placeholder whose boot still runs survives")
        (is (= #{"p--dead" "p--no-ts" "p--bad-ts" "p--boot-died"} (set pruned))
            "returns instance-ids for the coordinator's log line")))))

(deftest prune-holds-every-entry-before-removing-any
  ;; A boot reads as a crash to every prune guard, so the casualty record is asked to hold the
  ;; WHOLE registry — removable or not — before anything is removed.
  (let [ancient "2001-09-09T01:46:40Z"
        reg     {"/wt/dead" {:instance-id "p--dead" :app-port 3001 :created-at ancient}
                 "/wt/live" {:instance-id "p--live" :app-port 3000 :created-at ancient}}
        asked   (atom nil)
        removed (atom nil)]
    (with-redefs [nido.session.state/read-registry (constantly reg)
                  nido.session.state/remove-many-from-registry! (fn [ks] (reset! removed (set ks)))
                  nido.session.casualties/record! (fn [es] (reset! asked (set es)) {:outcome :held :held []})
                  nido.platform.process/tcp-open? (fn [port] (= 3000 port))]
      (work/prune-dead-registry! (.toEpochMilli (java.time.Instant/parse "2026-01-01T00:00:00Z")))
      (is (= (set (vals reg)) @asked) "every entry is offered, the live one included")
      (is (= #{"/wt/dead"} @removed)))))

(deftest prune-removes-nothing-old-when-the-record-cannot-hold-it
  ;; :failed and :no-boot both mean the boot's casualties were not held, so only an entry
  ;; registered since this process started — which cannot have died with the host — may go.
  (let [old     "2001-09-09T01:46:40Z"
        fresh   (str (.plusSeconds (java.time.Instant/now) 1))
        reg     {"/wt/old"   {:instance-id "p--old"   :app-port 3001 :created-at old}
                 "/wt/fresh" {:instance-id "p--fresh" :app-port 3002 :created-at fresh}}
        now-ms  (+ (System/currentTimeMillis) (* 60 60 1000))]
    (doseq [holding [{:outcome :failed :error "disk full"} {:outcome :no-boot}]]
      (let [removed (atom nil)]
        (with-redefs [nido.session.state/read-registry (constantly reg)
                      nido.session.state/remove-many-from-registry! (fn [ks] (reset! removed (set ks)))
                      nido.session.casualties/record! (constantly holding)
                      nido.platform.process/tcp-open? (constantly false)]
          (work/prune-dead-registry! now-ms)
          (is (= #{"/wt/fresh"} @removed) (str (:outcome holding) " keeps the old entry")))))))

(deftest restore-clears-the-status-and-reopens-the-workstream
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian
                {:stage :triage
                 :external-refs [{:adapter :notion :id "BR-77" :page-id "pg" :url "u"}]})]
        (tickets/open! :brian "BR-77" {:title "t"})
        (workstream/append-entry! :brian (:id w) {:kind :impl} "# Verdict\n\nneeds-info.")
        (is (= {:decision :dismissed} (work/dismiss! :brian (:id w))))
        (is (= :dismissed (tickets/status :brian "BR-77")))

        (is (= {:decision :restored} (work/restore! :brian (:id w))))
        (is (nil? (tickets/status :brian "BR-77"))
            "the auto-triage gate treats it as never-triaged")
        (is (nil? (workstream/closure (workstream/read-ws :brian (:id w))))
            "workstream reopened")
        (is (= :triaging (:stage (workstream/newest-choice (workstream/read-ws :brian (:id w)))))
            "stored OUTSIDE lifecycle-stages so it never becomes a manual override")))))

(deftest restore-leaves-the-row-free-to-progress-past-triage
  ;; stage-projection honors a stored lifecycle stage as a manual override on any
  ;; open workstream, and :triage IS one — so reopening there stops the projection
  ;; deriving from the ticket status for the rest of the workstream's life. A
  ;; restored legacy/Slack row would pin at :triage forever: never :ready, never
  ;; promotable. :triaging is the create! default, deliberately absent from
  ;; lifecycle-stages, and still derives to :triage on a status-less ticket.
  (with-tmp
    (fn [_]
      (let [slack-id "slack-C1-3.3"
            w (workstream/create! :brian
                {:stage :triage
                 :external-refs [{:adapter :slack-message :id slack-id :title "t"}]})]
        (tickets/open! :brian slack-id {:title "t"})
        (work/dismiss! :brian (:id w))
        (work/restore! :brian (:id w))
        (is (= [:triage] (map :stage (work/list-workstreams :brian)))
            "restore still lands in the triage queue")
        (triaged! (:id w) slack-id true)
        (is (= [:ready] (map :stage (work/list-workstreams :brian)))
            "and triaging it afterwards reaches :ready — i.e. it is promotable again")))))

(deftest restore-is-a-no-op-without-a-workstream
  (with-tmp
    (fn [_]
      (is (= {:decision :no-workstream} (work/restore! :brian "nope"))))))

(deftest restore-by-page-id-restores-the-workstream-a-ticket-dismissal-minted
  ;; A ticket dismissed via `bb nido:ticket:dismiss` with no workstream gets one, which lands in
  ;; the band and renders Restore. A click carrying the page's id — a bare row's synthetic ws-id
  ;; IS its Notion page-id — has to actually restore it: reporting "✓ Restored" while nothing
  ;; happened is the band lying about the one guarantee it exists to make. The BR is recoverable
  ;; from the watched-view cache the row was synthesized from.
  (with-tmp
    (fn [_]
      (nido.coordinator.source.state/write-state! "v1"
        {:type :notion-view :source-config {:project :brian}
         :pages {"pg-orphan" {:status "Needs verification" :priority nil :ball-ids #{}
                              :title "t" :br "BR-500"}}})
      (tickets/open! :brian "BR-500" {:title "t"})
      (tickets/dismiss! :brian "BR-500")
      (is (= [:dismissed] (map :stage (work/list-workstreams :brian)))
          "the orphan ticket is visible in the band")
      (is (= {:decision :restored} (work/resolve-gate! :brian "pg-orphan" :restore)))
      (is (nil? (tickets/status :brian "BR-500"))
          "re-triable, which is the whole undo")
      (is (= [:triage] (map :stage (work/list-workstreams :brian)))
          "and the row leaves the band"))))

(deftest resolve-gate-dispatches-restore
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian
                {:stage :triage
                 :external-refs [{:adapter :notion :id "BR-78" :page-id "pg" :url "u"}]})]
        (tickets/open! :brian "BR-78" {:title "t"})
        (work/dismiss! :brian (:id w))
        (is (= {:decision :restored} (work/resolve-gate! :brian (:id w) :restore)))))))

;; ---------------------------------------------------------------------------
;; Bare watched-view rows — a Notion page with no workstream of its own.
;; Its synthetic ws-id IS the page-id (wsv/bare-row), so read-ws always misses.
;; ---------------------------------------------------------------------------

(defn- seed-page! [pages]
  (nido.coordinator.source.state/write-state! "v1"
    {:type :notion-view :source-config {:project :brian} :pages pages}))

(deftest workstream-falls-back-to-a-bare-pane-for-an-uncovered-page
  (with-tmp
    (fn [_]
      (seed-page! {"pg-bare" {:status "Needs verification" :priority 1 :ball-ids #{}
                              :title "Move Licences" :br "BR-9"}})
      (let [d (work/workstream :brian "pg-bare")]
        (is (true? (:bare? d)))
        (is (= "pg-bare" (:ws-id d)))
        (is (= "BR-9" (:br-id d)))
        (is (= "Move Licences" (:label d)))
        (is (= :triage (:stage d)) "untriaged Needs-verification → triage")
        (is (= :notion (:origin d)))
        (is (= "Needs verification" (:notion-status d)))
        (is (nil? (:report d)))
        (is (nil? (:environment d)))
        (is (nil? (:ledger d)))
        (is (empty? (:sessions d)))))))

(deftest workstream-is-nil-for-a-page-id-that-is-not-in-the-cache
  (with-tmp
    (fn [_]
      (seed-page! {"pg-other" {:status "Not started" :priority nil :ball-ids #{}
                               :title "Other" :br "BR-1"}})
      (is (nil? (work/workstream :brian "pg-unknown"))
          "an unknown id is still nil — the bare branch must not invent a pane"))))

(deftest workstream-follows-the-handoff-once-the-page-has-a-workstream
  (with-tmp
    (fn [_]
      ;; Start triage mints a workstream under a FRESH nido ws-id while the URL
      ;; still says ?sel=brian:pg-bare. Selecting by page-id must resolve to it.
      (seed-page! {"pg-bare" {:status "Needs verification" :priority 1 :ball-ids #{}
                              :title "Move Licences" :br "BR-9"}})
      (let [w (workstream/create! :brian
                {:stage :triaging
                 :external-refs [{:adapter :notion :id "BR-9" :page-id "pg-bare"}]})
            d (work/workstream :brian "pg-bare")]
        (is (= (:id w) (:ws-id d)) "resolves to the real workstream, not the page-id")
        (is (not (:bare? d)) "and it is no longer a bare pane")))))

(deftest dismiss-a-bare-row-dismisses-its-ticket
  (with-tmp
    (fn [_]
      ;; No workstream to close, so dismissing the ticket mints the one that carries the veto;
      ;; it covers the page, which moves the row to the Dismissed band where Restore undoes it.
      (seed-page! {"pg-bare" {:status "Needs verification" :priority 1 :ball-ids #{}
                              :title "Move Licences" :br "BR-9"}})
      (is (= {:decision :dismissed} (work/dismiss! :brian "pg-bare")))
      (is (= :dismissed (tickets/status :brian "BR-9"))
          "the ticket is dismissed, though it had no record and no workstream before"))))

(deftest dismiss-refuses-a-page-with-no-br
  (with-tmp
    (fn [_]
      (seed-page! {"pg-nobr" {:status "Needs verification" :priority nil
                              :ball-ids #{} :title "No id" :br nil}})
      (is (= {:decision :no-workstream} (work/dismiss! :brian "pg-nobr"))
          "nothing to stamp and nothing to close — a genuine no-op"))))

(deftest dismiss-round-trips-through-restore-on-a-bare-row
  (with-tmp
    (fn [_]
      (seed-page! {"pg-bare" {:status "Needs verification" :priority 1 :ball-ids #{}
                              :title "Move Licences" :br "BR-9"}})
      (work/dismiss! :brian "pg-bare")
      (is (= {:decision :restored} (work/restore! :brian "pg-bare")))
      (is (nil? (tickets/status :brian "BR-9")) "re-triable again"))))

(deftest resolve-gate-lets-dismiss-past-the-workstream-less-guard
  (with-tmp
    (fn [_]
      (seed-page! {"pg-bare" {:status "Needs verification" :priority 1 :ball-ids #{}
                              :title "Move Licences" :br "BR-9"}})
      (is (= {:decision :dismissed} (work/resolve-gate! :brian "pg-bare" :dismiss))
          "the nil-read-ws guard must not swallow :dismiss")
      (is (= {:decision :no-workstream} (work/resolve-gate! :brian "pg-bare" :done))
          "an action with no bare meaning is still refused"))))

(deftest gate-actions-offers-start-triage-only-on-a-bare-triage-row
  (is (= [:start-triage :dismiss]
         (mapv :id (work/gate-actions :triage false nil {:bare? true})))
      "bare :triage → force-start plus the off-radar veto")
  (is (= [:apply :dismiss] (mapv :id (work/gate-actions :triage false nil {:bare? false})))
      "a real unparked triage row can be accepted; only the bare one cannot, and
       the filter is why — :apply is not workstream-less, so there is no ledger
       behind it to cite an acceptance against")
  (is (= [:apply :dismiss] (mapv :id (work/gate-actions :triage false nil)))
      "3-arity call sites see the same set")
  (is (= [] (mapv :id (work/gate-actions :in-progress false nil {:bare? true})))
      "a bare :in-progress row gets no Start triage — it is not a triage"))

;; Review finding: a bare row's :stage is not always :triage — session/notion-stage
;; also yields :ready, and gate-actions' :ready branch (Promote/Drop) assumes a
;; workstream to promote or drop, which a bare row does not have. Without this
;; filter those buttons render and can only ever return {:decision :no-workstream}.
(deftest gate-actions-narrows-a-bare-ready-row-to-nothing
  (is (= [] (mapv :id (work/gate-actions :ready false nil {:bare? true})))
      "Promote/Drop would only ever no-op with no workstream behind the row")
  (is (= [:promote :drop] (mapv :id (work/gate-actions :ready false nil)))
      "the non-bare 3-arity is unaffected — this narrows only the bare path"))

(deftest a-dismissed-bare-row-becomes-a-dismissed-workstream-pane-and-list-agree-on
  ;; Dismissing a bare row mints the workstream that carries its ticket's dismissal, and that
  ;; workstream covers the page by its BR — so the page is no longer bare, and selecting it by
  ;; page-id resolves to the workstream. Pane and list must agree it is dismissed, and the pane
  ;; offers Restore rather than a re-hidden Dismiss/Start-triage.
  (with-tmp
    (fn [_]
      (seed-page! {"pg-bare" {:status "Needs verification" :priority 1 :ball-ids #{}
                              :title "Move Licences" :br "BR-9"}})
      (work/dismiss! :brian "pg-bare")
      (let [pane     (work/workstream :brian "pg-bare")
            list-row (first (filter #(= (:ws-id pane) (:ws-id %))
                                    (work/list-workstreams :brian)))]
        (is (not (:bare? pane)))
        (is (= :dismissed (:stage pane))
            "the pane must show the same :dismissed the board list shows")
        (is (= (:stage list-row) (:stage pane))
            "pane and list must never disagree about stage")
        (is (empty? (filter #(= "pg-bare" (:ws-id %)) (work/list-workstreams :brian)))
            "and no bare row stands beside it")))))

(deftest start-triage-page-refuses-a-project-with-no-triage-trigger
  (with-tmp
    (fn [_]
      (with-redefs [nido.coordinator.record.triggers/load-for-project (fn [_] [])]
        (is (= {:decision :no-trigger} (work/start-triage-page! :brian "pg-bare")))))))

(deftest start-triage-page-reports-an-unresolvable-page
  (with-tmp
    (fn [_]
      (with-redefs [nido.coordinator.record.triggers/load-for-project
                    (fn [_] [{:name :triage-new :skill :triage-bug}])
                    nido.notion.client/keychain-token (constantly "")
                    nido.coordinator.work/resolve-ref (fn [_ _ _] {:error :no-token})]
        (is (= {:decision :unresolved :error :no-token}
               (work/start-triage-page! :brian "pg-bare")))))))

(deftest resolve-gate-lets-start-triage-past-the-workstream-less-guard
  (with-tmp
    (fn [_]
      (with-redefs [nido.coordinator.record.triggers/load-for-project (fn [_] [])]
        (is (= {:decision :no-trigger}
               (work/resolve-gate! :brian "pg-bare" :start-triage))
            "reaches start-triage-page! rather than the guard's :no-workstream")))))

;; ── Proposals ───────────────────────────────────────────────────────────────

(defn- journal [] (records/of (records/journal :nido proposal/journal-name)))

(defn- journal-kinds [kind] (filter #(= kind (:kind %)) (journal)))


(defn- with-analysis-ws
  "A review-analysis run holding one analysis, in a throwaway nido root; `f` is given its id."
  [observations f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (f (test-runs/analysis! :nido "run-1" observations {:rounds 2})))
      (finally (fs/delete-tree tmp)))))

(deftest a-proposal-is-addressed-by-where-it-sits
  (with-analysis-ws
    [{:kind :waste :where "w0" :summary "s0" :evidence "e0" :proposal "p0"}
     {:kind :working-well :where "w1" :summary "s1" :evidence "e1"}
     {:kind :miss :where "w2" :summary "s2" :evidence "e2" :proposal "p2"}]
    (fn [id]
      (let [ps (work/proposals :nido)]
        (is (= 2 (count ps))
            "an observation proposing nothing is not a row — there is nothing to decide")
        (is (= #{[1 0] [1 2]} (set (map (juxt :analysis-seq :observation) ps)))
            "each row is addressed by its entry and its index in that entry, and the
             index survives the gap the dropped observation leaves")
        (is (every? #(seq (:evidence %)) ps)
            "evidence rides along — it is what the decision is about")))))

(deftest a-decision-answers-the-page-it-was-read-from
  (with-analysis-ws
    [{:kind :waste :where "w" :summary "s" :evidence "e" :proposal "p"}]
    (fn [id]
      (let [p (first (work/proposals :nido))]
        (is (= 0 (:at-seq p)) "the position to answer is the improvement journal's, not the analysis's seq")
        (is (= {:decision :stale :latest 0}
               (work/decide-proposal! :nido id (assoc p :verdict :approved :at-seq 99)))
            "a decision naming a position the journal never had is refused")
        (is (= :recorded (:decision (work/decide-proposal! :nido id (assoc p :verdict :approved)))))
        (let [after (first (work/proposals :nido))]
          (is (= :approved (:verdict (:decision after))) "the row carries what was decided")
          (is (seq (:decided-by (:decision after)))
              "and who decided, stamped here rather than sent by a caller")
          (is (= {:decision :stale :latest 1}
                 (work/decide-proposal! :nido id (assoc p :verdict :declined)))
              "the same click again is stale — the decision it made is now the latest record")
          (is (empty? (workstream/entries-of :nido id :improvement-decision))
              "it is kept in the journal, not on the analysis's workstream"))))))

(deftest a-landing-is-a-fact-about-the-repo-not-an-answer-to-a-page
  (with-analysis-ws
    [{:kind :waste :where "w" :summary "s" :evidence "e" :proposal "p"}]
    (fn [id]
      (let [p (first (work/proposals :nido))]
        (work/decide-proposal! :nido id (assoc p :verdict :approved))
        (is (nil? (:landed (first (work/proposals :nido))))
            "an approval on its own discharges nothing")
        (is (= :recorded (:landed (work/record-landing!
                                   :nido id {:analysis-seq 1 :observation 0
                                             :rev "abcdefgh"}))))
        (let [after (first (work/proposals :nido))]
          (is (= "abcdefgh" (:rev (:landed after))))
          (is (seq (:landed-by (:landed after))))
          (is (= :approved (:verdict (:decision after)))
              "and the decision it discharges is untouched — the landing is a
               second record, not a third verdict"))
        (is (= :recorded (:landed (work/record-landing!
                                   :nido id {:analysis-seq 1 :observation 0
                                             :rev "zzzzzzzz"})))
            "no at-seq guard: the journal has moved twice and the record is still true")
        (is (= "zzzzzzzz" (:rev (:landed (first (work/proposals :nido)))))
            "and the newest one is where a reader should go look")))))

(deftest a-landing-with-no-rev-is-recorded-rather-than-refused
  ;; The backfill's case. Its accounts name behaviours, not changes, and a
  ;; closed schema demanding :rev of those would force an invented change id
  ;; into a durable record — which reads as retrievable and is not.
  (with-analysis-ws
    [{:kind :waste :where "w" :summary "s" :evidence "e" :proposal "p"}]
    (fn [id]
      (work/record-landing! :nido id {:analysis-seq 1 :observation 0
                                      :note "already closed by an earlier round"})
      (let [l (:landed (first (work/proposals :nido)))]
        (is (nil? (:rev l)) "absent rather than blank or invented")
        (is (str/includes? (:note l) "already closed"))))))

(deftest the-backfill-discharges-an-approval-whose-note-says-it-is-done
  (with-analysis-ws
    [{:kind :waste :where "w0" :summary "s0" :evidence "e0" :proposal "p0"}
     {:kind :miss :where "w1" :summary "s1" :evidence "e1" :proposal "p1"}
     {:kind :miss :where "w2" :summary "s2" :evidence "e2" :proposal "p2"}]
    (fn [id]
      (work/decide-proposal! :nido id {:analysis-seq 1 :observation 0 :verdict :approved
                                       :at-seq 0 :note "implemented: the guard fails open"})
      (work/decide-proposal! :nido id {:analysis-seq 1 :observation 1 :verdict :approved
                                       :at-seq 1 :note "worth doing when someone has a day"})
      (work/decide-proposal! :nido id {:analysis-seq 1 :observation 2 :verdict :declined
                                       :at-seq 2 :note "the seam is deliberate"})
      (is (= {:landed 1 :skipped 2} (work/backfill-landings! :nido)))
      (let [by-obs (into {} (map (juxt :observation identity)) (work/proposals :nido))]
        (is (some? (:landed (by-obs 0))))
        (is (str/includes? (:note (:landed (by-obs 0))) "implemented: the guard fails open")
            "the account that era wrote is carried across, not summarised away")
        (is (nil? (:landed (by-obs 1)))
            "an approval whose note is a plan rather than an outcome is untouched")
        (is (nil? (:landed (by-obs 2))) "and a decline discharges nothing"))
      (is (= {:landed 0 :skipped 3} (work/backfill-landings! :nido))
          "idempotent — the journal has no delete, so a second run must add nothing"))))

(deftest changing-your-mind-is-an-append-and-the-last-one-counts
  (with-analysis-ws
    [{:kind :waste :where "w" :summary "s" :evidence "e" :proposal "p"}]
    (fn [id]
      (let [p (first (work/proposals :nido))]
        (work/decide-proposal! :nido id (assoc p :verdict :approved))
        (let [p2 (first (work/proposals :nido))]
          (work/decide-proposal! :nido id (assoc p2 :verdict :declined :note "on reflection")))
        (let [d (:decision (first (work/proposals :nido)))]
          (is (= :declined (:verdict d)) "the later decision is the one that counts")
          (is (= "on reflection" (:note d)))
          (is (= 2 (count (journal)))
              "and the first one is still there — the journal has no delete"))))))

;; ── The move a person owes ─────────────────────────────────────────────────
;; Rounds run as tasks and park nobody, so a move gated on a parked session would
;; be unreachable for most of what a person owes. What is owed is a fact about the
;; POSITION, and these pin that it is read from there.

(deftest awaiting-human-reads-a-human-position-and-nothing-else
  (is (= :accept-triage
         (work/awaiting-human {:next {:stage :accept-triage :mode :human}})))
  (is (= :answer-blocker
         (work/awaiting-human {:next {:stage :answer-blocker :mode :human}}))
      "every human stage answers — which one it is belongs to the caller")
  (is (nil? (work/awaiting-human {:next {:stage :decide-design :mode :mechanical}}))
      "a stage nido runs itself owes a person nothing")
  (is (nil? (work/awaiting-human {:next nil})) "a terminal position owes nothing")
  (is (nil? (work/awaiting-human nil))
      "and a row that carries no position at all must not throw — :done,
       :dismissed and :analysed are never stamped with one"))

(deftest a-published-pr-is-landed-from-its-own-button
  (is (= [{:id :land :label "Land" :kind :mutation :style :primary :seq 37}]
         (work/gate-actions :shipping false nil {:report-format :pr-opened :seq 37 :awaiting :land
                                                 :landable? true}))
      "a landing owed is one button carrying the record it was owed over — not a bare question")
  (is (work/position-carrying-action? :land)
      "so the click comes back with the record it answers, and a stale page is refused")
  (is (= "Land" (-> (ask/ask :land {:report  nil
                                    :entries [{:format :pr-opened :seq 37 :url "https://x/pull/1" :title "t"}]
                                    :actions [{:id :land :label "Land"}]})
                    :answer-does first :label))
      "and the card says what the button does beside it"))

(deftest reply-still-needs-an-agent-to-resume
  (is (= [:land]
         (mapv :id (work/gate-actions :shipping false nil
                                      {:report-format :pr-opened :seq 7 :awaiting :land :landable? true})))
      "Reply resumes a parked agent; beside a landing with none it could only fail")
  (is (= [:land :reply]
         (mapv :id (work/gate-actions :shipping true nil
                                      {:report-format :pr-opened :seq 7 :awaiting :land :landable? true})))
      "and when there is one, a note to it is still an answer"))

(deftest a-blocker-raised-over-a-design-decision-is-answered-not-approved
  (is (= [{:id :reply :label "Answer" :kind :resume :style :default :answers :ledger :seq 7}]
         (work/gate-actions :in-progress false nil
                            {:report-format :design-decision :seq 7 :awaiting :answer-blocker}))
      "the person's judgement reaches the next round as the blocker's answer, naming the record it answers"))

;; ── the improvement sweep's writes ──────────────────────────────────────────

(defn- plan-run!
  "A plan run of the sweep, running, as its fire starts one. Answers its core key."
  []
  (client/run-key :nido (test-runs/run! :nido :improvement-plan
                                        {:adapter :improvement :id "plan/2026-09-04"} :running)))

(defn- claim-run!
  "A claim run of the sweep for claim 0 of plan seq 1 held by `plan-holder`, running."
  ([] (claim-run! "plan-run"))
  ([plan-holder]
   (client/run-key :nido (test-runs/run! :nido :improvement-claim
                                         {:adapter :improvement :id (str plan-holder "/1#0")
                                          :plan-ws plan-holder :plan-seq 1 :claim 0}
                                         :running))))

(defn- closed-as [run-key]
  (some #(when (= :closed (:kind %)) (:outcome %)) (records/of run-key)))

(defn- seed-analysis!
  "A review-analysis run holding one analysis with `n` proposal-bearing observations, as
   `of-project` derives proposals from. Answers the run's id."
  [ws-id n]
  (test-runs/analysis! :nido (str "run-" ws-id)
                       (vec (for [i (range n)]
                              {:kind :waste :where "w" :summary (str "s" i)
                               :evidence "e" :proposal (str "p" i)}))
                       {:reviewed "nido/x" :status "converged" :summary "one analysis"}))

(deftest a-plan-covering-what-is-owed-is-recorded
  (with-tmp
    (fn [_]
      (let [ws (seed-analysis! "a" 2)
            plan (plan-run!)
            res (work/record-plan!
                 :nido plan
                 {:date "2026-09-04"
                  :frontier {:proposals [{:ws-id ws :at-seq 1}] :attempts []}
                  :claims [{:statement "one change" :disposition :land
                            :addresses [(str ws "/1.0") (str ws "/1.1")]}]})]
        (is (= :recorded (:plan res)))
        (is (= [["2026-09-04" (second (re-find #"run:nido/(.+)" plan))]]
               (map (juxt :date :ws-id) (proposal/plans-of :nido)))
            "the plan is what its run did, held by that run")))))

(deftest a-plan-that-leaves-an-owed-proposal-unclaimed-is-refused
  ;; The refusal is the point: the generic ledger boundary validates shape and
  ;; has no access to the owed set, so this is the only place the claim is real.
  (with-tmp
    (fn [_]
      (let [ws (seed-analysis! "a" 2)
            res (work/record-plan!
                 :nido (plan-run!)
                 {:date "2026-09-04"
                  :frontier {:proposals [{:ws-id ws :at-seq 1}] :attempts []}
                  :claims [{:statement "half of it" :disposition :land
                            :addresses [(str ws "/1.0")]}]})]
        (is (= :refused (:plan res)))
        (is (= [(str ws "/1.1")] (get-in res [:defect :uncovered])))))))

(deftest a-plan-naming-an-address-that-is-not-owed-is-refused
  (with-tmp
    (fn [_]
      (let [ws (seed-analysis! "a" 1)
            res (work/record-plan!
                 :nido (plan-run!)
                 {:date "2026-09-04"
                  :frontier {:proposals [{:ws-id ws :at-seq 1}] :attempts []}
                  :claims [{:statement "x" :disposition :land
                            :addresses [(str ws "/1.0") "ws-gone/9.9"]}]})]
        (is (= :refused (:plan res)))
        (is (= ["ws-gone/9.9"] (get-in res [:defect :unowed])))))))

(deftest a-claim-whose-addresses-are-clean-reserves
  (with-tmp
    (fn [_]
      (let [ws (seed-analysis! "a" 1)
            claim (claim-run!)
            res (work/reserve-claim! :nido claim
                                     {:plan-seq 1 :claim 0 :addresses [(str ws "/1.0")]})]
        (is (= :recorded (:reserved res)))
        (is (= [["plan-run" 1 0]] (map (juxt :holder :plan-seq :claim)
                                       (journal-kinds :improvement-claim-reserved)))
            "the reservation is in the journal, naming the plan that holds the claim")
        (is (nil? (closed-as claim)) "and the claim goes on")))))

(deftest a-claim-covering-a-declined-address-is-vetoed-and-reserves-nothing
  ;; This is the veto. A refused reservation is what stops the push, because
  ;; discharge-claim! will not push without one.
  (with-tmp
    (fn [_]
      (let [ws (seed-analysis! "a" 1)
            claim (claim-run!)]
        (work/decide-proposal! :nido ws {:analysis-seq 1 :observation 0
                                         :verdict :declined :at-seq 0})
        (let [res (work/reserve-claim! :nido claim
                                       {:plan-seq 1 :claim 0 :addresses [(str ws "/1.0")]})]
          (is (= :vetoed (:reserved res)))
          (is (= [(str ws "/1.0")] (:declined res)))
          (is (empty? (journal-kinds :improvement-claim-reserved)))
          (is (= :vetoed (closed-as claim)) "the claim run's work is ended vetoed, by the verb"))))))

(deftest a-decline-the-reservation-did-not-read-is-seen-on-its-retry
  ;; The race the journal's compare-and-append settles: a decline committed between the
  ;; reservation's read and its append makes the append stale, and the claim is decided again.
  (with-tmp
    (fn [_]
      (let [ws (seed-analysis! "a" 1)
            claim (claim-run!)
            raced (atom false)
            real  client/append-record!]
        (with-redefs [client/append-record!
                      (fn [k key r after]
                        (when (and (= :improvement-claim-reserved (:kind r)) (not @raced))
                          (reset! raced true)
                          (work/decide-proposal! :nido ws {:analysis-seq 1 :observation 0
                                                           :verdict :declined :at-seq after}))
                        (real k key r after))]
          (is (= :vetoed (:reserved (work/reserve-claim! :nido claim
                                                         {:plan-seq 1 :claim 0
                                                          :addresses [(str ws "/1.0")]})))))
        (is (empty? (journal-kinds :improvement-claim-reserved)))))))

(deftest an-unreserved-claim-is-never-pushed
  ;; The restriction the whole design turns on: no reservation, no push. The
  ;; test would spawn a real jj push if this leaked, so it also proves it did not.
  (with-tmp
    (fn [_]
      (let [claim (claim-run!)
            pushed (atom false)]
        (with-redefs [nido.session.lifecycle/advance-remote!
                      (fn [& _] (reset! pushed true) {:outcome :advanced})]
          (let [res (work/discharge-claim! :nido claim
                                           {:worktree "/tmp/x" :bookmark "main"
                                            :rev "abc" :addresses ["ws-a/1.0"]})]
            (is (= :unreserved (:discharged res)))
            (is (false? @pushed) "nothing may reach the remote without a reservation")))))))

(deftest a-push-that-did-not-land-records-nothing-and-leaves-the-claim-open
  (with-tmp
    (fn [_]
      (let [ws (seed-analysis! "a" 1)
            claim (claim-run!)]
        (work/reserve-claim! :nido claim {:plan-seq 1 :claim 0 :addresses [(str ws "/1.0")]})
        (with-redefs [nido.session.lifecycle/advance-remote!
                      (fn [& _] {:outcome :rejected :detail "non-fast-forward"})]
          (let [res (work/discharge-claim! :nido claim
                                           {:worktree "/tmp/x" :bookmark "main"
                                            :rev "abc" :addresses [(str ws "/1.0")]})]
            (is (= :not-pushed (:discharged res)))
            (is (nil? (closed-as claim)))
            (is (empty? (journal-kinds :improvement-landed)))))))))

(deftest a-remote-that-already-held-the-revision-records-the-landing
  ;; A crash between a successful push and its first append leaves a retry
  ;; finding exactly this. Treating it as "did not land" loses the landing.
  (with-tmp
    (fn [_]
      (let [ws (seed-analysis! "a" 2)
            claim (claim-run!)
            addrs [(str ws "/1.0") (str ws "/1.1")]]
        (work/reserve-claim! :nido claim {:plan-seq 1 :claim 0 :addresses addrs})
        (with-redefs [nido.session.lifecycle/advance-remote!
                      (fn [& _] {:outcome :already-there})]
          (let [res (work/discharge-claim! :nido claim
                                           {:worktree "/tmp/x" :bookmark "main"
                                            :rev "abc" :addresses addrs})]
            (is (= :recorded (:discharged res)))
            (is (= 2 (count (journal-kinds :improvement-landed))))
            (is (= :done (closed-as claim)))))))))

(deftest a-claim-spanning-two-proposals-lands-both-at-one-revision
  (with-tmp
    (fn [_]
      (let [ws (seed-analysis! "a" 2)
            claim (claim-run!)
            addrs [(str ws "/1.0") (str ws "/1.1")]]
        (work/reserve-claim! :nido claim {:plan-seq 1 :claim 0 :addresses addrs})
        (with-redefs [nido.session.lifecycle/advance-remote! (fn [& _] {:outcome :advanced})]
          (work/discharge-claim! :nido claim {:worktree "/tmp/x" :bookmark "main"
                                              :rev "deadbeef" :addresses addrs}))
        (let [ps (proposal/of-project :nido)]
          (is (= #{"deadbeef"} (set (map #(get-in % [:landed :rev]) ps)))
              "one revision carries every proposal the claim covered"))))))

(deftest re-running-an-interrupted-discharge-completes-it-without-doubling
  ;; Interrupted between appends it leaves the claim unended; running again
  ;; appends only for what does not already carry a landing at this revision.
  (with-tmp
    (fn [_]
      (let [ws (seed-analysis! "a" 2)
            claim (claim-run!)
            addrs [(str ws "/1.0") (str ws "/1.1")]]
        (work/reserve-claim! :nido claim {:plan-seq 1 :claim 0 :addresses addrs})
        (work/record-landing! :nido ws {:analysis-seq 1 :observation 0 :rev "deadbeef"})
        (with-redefs [nido.session.lifecycle/advance-remote! (fn [& _] {:outcome :already-there})]
          (work/discharge-claim! :nido claim {:worktree "/tmp/x" :bookmark "main"
                                              :rev "deadbeef" :addresses addrs}))
        (is (= 2 (count (journal-kinds :improvement-landed)))
            "the address already recorded is not recorded twice")))))

(deftest an-analysis-a-run-recorded-is-proposed-and-decided-by-its-run
  (with-tmp
    (fn [_]
      (let [id (test-runs/run! :nido :review-analysis {:id "rr-9" :title "a review"} :running)]
        (records/append-to-run! (client/run-key :nido id)
                                {:kind :review-analysis :verdict :degraded :run-id "rr-9"
                                 :status "clean" :rounds 1 :summary "s"
                                 :observations [{:kind :waste :where "w" :summary "s" :evidence "e"
                                                 :proposal "p"}]})
        (let [[p :as ps] (work/proposals :nido)]
          (is (= [(str id "/1.0")] (map proposal/address ps)) "addressed by the run that holds it")
          (is (= "a review" (:title p)))
          (is (= :recorded (:decision (work/decide-proposal! :nido id (assoc p :verdict :declined)))))
          (is (= :declined (get-in (first (work/proposals :nido)) [:decision :verdict]))))))))

(deftest an-archived-analysis-is-proposed-at-the-address-it-was-written-at
  ;; What a workstream held before runs did is in the archive, each record under its seq there:
  ;; the address a decision or a plan names is unchanged, and a decision in the journal is newer
  ;; than any the workstream held.
  (with-tmp
    (fn [_]
      (let [ref  {:adapter :review-run :id "rr-1" :title "an old review"}
            from (fn [n] {:holder "ws-old" :seq n :ref ref})
            rs   [{:kind :review-analysis :verdict :degraded :run-id "rr-1" :status "clean" :rounds 1
           :summary "s" :at "2026-09-01T10:00:00Z" :from (from 3)
           :observations [{:kind :waste :where "w" :summary "s0" :evidence "e" :proposal "p0"}
                          {:kind :waste :where "w" :summary "s1" :evidence "e" :proposal "p1"}]}
          {:kind :improvement-decision :analysis-seq 3 :observation 0 :at-seq 3 :verdict :declined
           :decided-by "x" :at "2026-09-02T10:00:00Z" :from (from 4)}
          {:kind :improvement-decision :analysis-seq 3 :observation 1 :at-seq 4 :verdict :declined
           :decided-by "x" :at "2026-09-02T11:00:00Z" :from (from 5)}]]
        (is (= 2 (records/archive! :nido (take 2 rs))))
        (is (= 1 (records/archive! :nido rs)) "a second run appends only what the first did not")
        (is (zero? (records/archive! :nido rs)))
        (let [ps (work/proposals :nido)]
          (is (= ["ws-old/3.0" "ws-old/3.1"] (sort (map proposal/address ps))))
          (is (= "an old review" (:title (first ps))) "titled by the ref its workstream was minted by")
          (is (every? #(= :declined (get-in % [:decision :verdict])) ps)))
        (is (= :recorded (:decision (work/decide-proposal!
                                     :nido "ws-old"
                                     (assoc (first (filter #(= 1 (:observation %)) (work/proposals :nido)))
                                            :verdict :approved)))))
        (is (= {0 :declined 1 :approved}
               (into {} (map (juxt :observation (comp :verdict :decision))) (work/proposals :nido)))
            "the journal's decision is the latest")))))

(deftest a-claim-run-that-died-returns-its-addresses
  ;; A failed or cancelled run is the run dying, not the work given up: its addresses are owed
  ;; again. One whose turn completed without saying how its claim ended gave up: they are tried.
  (with-tmp
    (fn [_]
      (let [died (test-runs/run! :nido :improvement-claim {:adapter :improvement :id "p/1#0"} :failed)
            quit (test-runs/run! :nido :improvement-claim {:adapter :improvement :id "p/1#1"} :done)
            by   (into {} (map (juxt :ws-id identity)) (proposal/attempts :nido))]
        (is (= [false :orphaned] ((juxt :open? :outcome) (by died))))
        (is (= [false :dropped] ((juxt :open? :outcome) (by quit))))))))


(defn- invalidated
  "A workstream whose cleared design a review round has just called invalid.
   Returns [ws-id baseline-seq design-seq verdict-seq]."
  []
  (let [[id b d _] (decided-design)
        add (fn [kind r] (if (= :design-decision kind) (workstream/append-decision! :brian id (pr-str r)) (workstream/append-entry! :brian id {:kind kind} (pr-str r)))
              (count (:entries (workstream/read-ws :brian id))))]
    (workstream/append-entry-at! :brian id (count (:entries (workstream/read-ws :brian id)))
                                 {:kind :design-cleared}
                                 (pr-str {:format :design-cleared :design {:seq d}}))
    (let [v (add :design-verdict
                 {:format :design-verdict :verdict :invalidated :round 2
                  :design-seq d :reason "a second path sums lines"
                  :invariants-broken [{:invariant "one summing path"
                                       :finding "the invoice renderer sums independently"}]
                  :needs "redesign the totalling seam"})]
      [id b d v])))

(deftest an-invalidated-design-goes-back-to-be-redesigned-with-nobody-asked
  (with-tmp
    (fn [_]
      (let [[id _ _ v] (invalidated)
            pos (pipeline/of :brian id)]
        (is (= :design-invalidated (:at pos)))
        (is (= {:stage :design :mode :authoring} (:next pos))
            "the redesign is nido's own move")
        (is (nil? (work/awaiting-human pos)) "so no person owes it anything")
        (is (empty? (work/gate-actions :in-progress false nil
                                       {:awaiting (work/awaiting-human pos) :seq v}))
            "and no gate offers a way to answer the verdict")))))

(deftest a-superseding-design-answers-the-verdict
  (with-tmp
    (fn [_]
      (let [[id b d _] (invalidated)]
        (workstream/append-entry!
         :brian id {:kind :design}
         (pr-str {:format :design :strata [] :summary "one totalling seam" :shape "sh"
                  :model {:elements [{:id "m" :sort :module}]
                          :claims [{:id "one-path" :about ["m"] :statement "one path"
                                    :falsified-by "a second path" :evidence {:by :round}}]}
                  :standing {:relation :conforms}
                  :baseline {:seq b :relation :within} :intent {:seq 1}
                  :supersedes {:seq d :why "the renderer sums independently"} :effort :S}))
        (let [pos (pipeline/of :brian id)]
          (is (= :designed (:at pos)) "the verdict is answered by the design that replaced it")
          (is (= :decide-design (:stage (:next pos))) "which its own round decides next"))))))

;; ── what holds the improvement sweep ────────────────────────────────────────

(defn- with-sweep-declared-on-nido [f]
  (with-redefs [nido.platform.project/list-projects (constantly {"nido" {} "other" {}})
                nido.coordinator.record.triggers/load-for-project
                (fn [pk] (if (= :nido pk)
                           [{:name :improvement-sweep :source {:type :improvement-sweep :project :nido}}]
                           []))]
    (f)))

(deftest improvement-holds-are-the-improvement-runs-not-yet-over
  (with-tmp
    (fn [_]
      (with-sweep-declared-on-nido
        (fn []
          (let [held (test-runs/run! :nido :improvement-claim
                                     {:adapter :improvement :id "ws-p/1#0" :title "improve: it"} :running)]
            (test-runs/run! :nido :improvement-claim {:adapter :improvement :id "ws-p/1#1"} :done)
            (test-runs/run! :nido :review-analysis {:adapter :review-run :id "r"} :running)
            (is (= [{:project "nido" :ws-id held :address "ws-p/1#0" :title "improve: it"
                     :state :working}]
                   (map #(dissoc % :started-at) (work/improvement-holds)))
                "only the improvement run still running, only where a sweep is declared")
            (is (string? (:started-at (first (work/improvement-holds)))) "since its fire")))))))

(deftest an-improvement-attempt-that-ended-carries-the-blocker-it-stopped-on
  (with-tmp
    (fn [_]
      (let [mk (fn [id] (test-runs/run! :nido :improvement-claim
                                        {:adapter :improvement :id id :title "improve: it"} :running))
            blocked (mk "ws-p/1#0")
            running (mk "ws-p/1#1")
            k       (client/run-key :nido blocked)]
        (records/append-to-run! (client/run-key :nido running)
                                {:kind :blocker :summary "s" :needs "may I go on?" :after "a"})
        (records/append-to-run! k {:kind :blocker :summary "s" :needs "may I change the schema?" :after "a"})
        (client/command! k (str "end/" blocked)
                         {:command :record-outcome :turn (str blocked " first") :outcome :completed})
        (let [by (into {} (map (juxt :ws-id identity)) (proposal/attempts :nido))]
          (is (= "may I change the schema?" (-> (by blocked) :blocker :needs)))
          (is (nil? (:blocker (by running))) "a run still going asks through its own turn, not here"))))))

;; ── Phases ──────────────────────────────────────────────────────────────────

(def ^:private phase-baseline
  {:format :baseline :strata [] :intent {:seq 1}
   :area "a" :bounded-by "b" :shape "s"
   :model {:elements [{:id "m" :sort :module :hides "h" :interface "i"}]
           :claims [{:id "c1" :about ["m"] :statement "st" :falsified-by "f" :evidence {:by :round}}]}
   :read ["src/a.clj"]})

(def ^:private phase-design
  {:format :design :strata [] :summary "s" :shape "sh"
   :model {:elements [{:id "m" :sort :module}]
           :claims [{:id "c" :about ["m"] :statement "st" :falsified-by "f" :evidence {:by :round}}]}
   :holds {"c" :on-completion}
   :standing {:relation :conforms} :intent {:seq 1}
   :baseline {:seq 2 :relation :within} :effort :S
   :phases [{:claim "both paths run" :habitable "h" :exit {:kind :soak :criterion "a quiet week"}
             :undo {:how :revert :by "r"}}
            {:claim "the old path is removed" :habitable "h"
             :exit {:kind :completion :criterion "nothing reads the old path"}
             :undo {:how :none :why "gone"}}]})

(defn- landed-between-phases!
  "A ref-less workstream on the two-phase design, standing and cleared by its
   round, its first phase landed."
  []
  (let [id   (:id (workstream/create! :brian {:external-refs []}))
        add! #(if (= :design-decision %1) (workstream/append-decision! :brian id (pr-str %2)) (workstream/append-entry! :brian id {:kind %1} (pr-str %2)))]
    (add! :intent {:format :intent :goal "g" :done-when ["d"]})
    (add! :baseline phase-baseline)
    (add! :baseline-review {:format :baseline-review :verdict :sufficient
                            :baseline-seq 2 :reason "ok"})
    (add! :design phase-design)
    (add! :design-decision {:format :design-decision :recommend :proceed :design-seq 4 :reason "r"
                            :checks [{:check :relation-honest :status :held :note "n"}]})
    (workstream/append-entry-at! :brian id 5 {:kind :design-cleared}
                                 (pr-str {:format :design-cleared :design {:seq 4}}))
    (add! :merged {:format :merged :pr "o/r#1" :url "u" :title "t" :design {:seq 4}})
    (workstream/close! :brian id :between-phases 4)
    id))

(defn- escalate-over!
  "Record a conductor escalation that parks nothing, over the move the workstream owes now."
  [id needs]
  (let [pos (pipeline/of :brian id)]
    (workstream/append-stamped! :brian id (cond-> {:format :conduct-decision :hand-back "m-1"
                                                   :position (:at pos) :by :conductor
                                                   :decision [:escalate {:needs needs}]}
                                            (:next pos) (assoc :next (:next pos))
                                            (:owes pos) (assoc :owes (:owes pos))))))

(deftest a-retracted-design-supplies-no-plan-and-opens-no-phase
  (with-tmp
    (fn [_]
      (let [id (landed-between-phases!)]
        (workstream/append-entry! :brian id {:kind :retraction}
                                  (pr-str {:format :retraction :retracts {:seq 4}
                                           :because "the design is not true of the code"
                                           :evidence ["src/a.clj:9"]}))
        (is (nil? (:phase (first (:awaiting-gate (work/grouped :brian #{}))))))
        (is (= :phase-refused
               (:decision (work/resolve-gate! :brian id :assert-gate "flat for a week"))))))))

(deftest a-between-phases-workstream-with-a-live-session-is-not-winding-down
  (with-tmp
    (fn [_]
      (let [id (landed-between-phases!)]
        (session/create! :brian id {:name "p1" :weight :light :autonomy nil})
        (is (empty? (work/winding-down :brian #{"p1"})) "one band, not two")
        (is (= [id] (map :ws-id (:awaiting-gate (work/grouped :brian #{"p1"})))))))))

(deftest the-gate-is-asserted-with-evidence-and-carries-its-criterion
  (is (= [{:id :assert-gate :label "Assert gate" :kind :evidence :style :primary
           :criterion "a quiet week"}]
         (work/gate-actions :awaiting-gate false nil {:awaiting :assert-gate
                                                      :gate-criterion "a quiet week"}))))

(deftest asserting-the-gate-opens-the-next-phase
  (with-tmp
    (fn [_]
      (let [id (landed-between-phases!)]
        (is (= :phase-refused (:decision (work/resolve-gate! :brian id :assert-gate "  "))))
        (is (= {:decision :phase-opened :opened "the old path is removed" :phase 2 :of 2}
               (work/resolve-gate! :brian id :assert-gate "error rate flat for a week")))
        (is (empty? (:awaiting-gate (work/grouped :brian #{}))) "off the gate band, back to work")))))

(deftest restore-refuses-a-workstream-between-phases
  (with-tmp
    (fn [_]
      (let [id (landed-between-phases!)]
        (is (= {:decision :between-phases} (work/restore! :brian id)))
        (is (= :between-phases (:outcome (workstream/closure (workstream/read-ws :brian id)))))))))

(deftest the-detail-carries-the-plan-phase-by-phase
  (with-tmp
    (fn [_]
      (let [id   (landed-between-phases!)
            plan (:plan (work/workstream :brian id))]
        (is (= {:current 1 :of 2 :waiting? true} (select-keys plan [:current :of :waiting?])))
        (is (= "a quiet week" (get-in plan [:owed-gate :criterion])))
        (is (= [:landed :ahead] (mapv :state (:phases plan))))
        (work/resolve-gate! :brian id :assert-gate "error rate flat for a week")
        (let [plan (:plan (work/workstream :brian id))]
          (is (= [:landed :current] (mapv :state (:phases plan))))
          (is (nil? (:owed-gate plan)))
          (is (= "error rate flat for a week" (get-in plan [:phases 1 :opened-by :evidence]))))))))

(deftest the-arc-draws-the-phase-underway
  (with-tmp
    (fn [_]
      (let [id (landed-between-phases!)]
        (workstream/append-entry! :brian id {:kind :implementation-completed}
                                  (pr-str {:format :implementation-completed :summary "p1" :artifacts []
                                           :design {:seq 4}}))
        (work/resolve-gate! :brian id :assert-gate "a quiet week, observed")
        (let [arc  (:arc (work/workstream :brian id))
              impl (first (filter #(= :implementation (:stage %)) (:stages arc)))]
          (is (zero? (:entries impl)) "phase 1's implementation is not phase 2's")
          (is (empty? (:excursions arc)) "nor are its landing or the gate"))))))

(defn- land-rung [id] (:land (:arc (work/workstream :brian id))))

(deftest the-land-rung-follows-a-landing-from-the-grant-to-the-merge
  (with-tmp
    (fn [_]
      (let [id    (:id (workstream/create! :brian {:external-refs [{:adapter :github :id "o/r#57"}]}))
            step! #(workstream/append-entry! :brian id {:kind :landing}
                                             (pr-str (merge {:format :landing :pr "o/r#57"} %)))]
        (is (= :ahead (:state (land-rung id))) "never granted: not reached")
        (is (= {:granted? false :steps []} (:trail (land-rung id))))
        (is (= work/arc-stages (mapv :stage (:stages (:arc (work/workstream :brian id)))))
            "the arc's stages stay the unit's: the rung is past them, never one of them")
        (with-redefs [work/core-of (fn [_ _] {:state {:grants #{:merge}}})]
          (is (= :current (:state (land-rung id))) "the person's grant makes the rung current")
          (step! {:step :submitted :via :queue})
          (step! {:step :queued :event-id "A1" :event-at "t1"})
          (step! {:step :ejected :event-id "R1" :event-at "t2" :reason "CI failed"})
          (step! {:step :queued :event-id "A2" :event-at "t3"})
          (let [{:keys [trail entries seqs]} (land-rung id)]
            (is (= 4 entries))
            (is (= 4 (count seqs)) "its records open like a stage's")
            (is (:granted? trail) "granted is read from the grant")
            (is (= [:submitted :queued :ejected :queued] (mapv :step (:steps trail)))
                "every step, the ejection kept after the re-queue")))
        (workstream/append-entry! :brian id {:kind :merged}
                                  (pr-str {:format :merged :pr "o/r#57" :url "u" :title "t"}))
        (let [{:keys [state trail]} (land-rung id)]
          (is (= :done state) "the merge is recorded: the rung is done")
          (is (not (:granted? trail)) "and the spent grant is no longer a step")
          (is (= [:landing :landing :landing :landing :merged] (mapv :format (:steps trail)))))))))

(deftest a-merge-nobody-landed-still-reads-done
  (with-tmp
    (fn [_]
      (let [id (:id (workstream/create! :brian {}))]
        (workstream/append-entry! :brian id {:kind :merged}
                                  (pr-str {:format :merged :pr "o/r#9" :url "u" :title "t"}))
        (is (= :done (:state (land-rung id))) "whoever merged it")))))

(deftest the-land-rung-carries-only-what-the-arc-says-past-its-stages
  (let [rung (fn [arc & {:keys [live? granted?] :or {granted? true}}]
               (#'work/land-rung {} [] arc {:at :land-granted} live? granted?))
        bare {:stages [{:stage :implementation :state :done}]}]
    (is (= [:awaiting :ask] ((juxt :now :needs) (rung (assoc bare :past {:needs :ask}))))
        "the person awaited past the stages is awaited on the rung")
    (is (= :running (:now (rung bare :live? true))) "a live landing runs on the rung")
    (is (nil? (:now (rung {:stages [{:stage :implementation :state :current :now :running}]} :live? true)))
        "where a stage carries the mark, the rung carries none")
    (is (= {:source :merge :phase :queued}
           (:doing (rung (assoc bare :past {:doing {:source :merge :phase :queued}}))))
        "a merge underway rides on the rung in its own words")
    (let [r (rung (assoc bare :past {:needs :landing :doing {:source :merge :phase :queued}}) :granted? false)]
      (is (= :ahead (:state r)) "no grant: not reached")
      (is (not-any? r [:now :needs :doing]) "and a rung that is not current carries no mark"))))

(deftest a-phase-landed-between-phases-reads-the-land-rung-done
  (with-tmp
    (fn [_]
      (let [id (landed-between-phases!)
            d  (work/workstream :brian id)]
        (is (= :done (:state (land-rung id))))
        (is (= :awaiting-gate (get-in d [:position :at])))))))

(deftest an-unphased-detail-has-no-plan
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:external-refs []})]
        (is (nil? (:plan (work/workstream :brian (:id w)))))))))

(deftest the-stage-a-workstream-was-minted-at-places-nothing
  (with-tmp
    (fn [_]
      (let [w   (workstream/create! :brian {:external-refs []})
            row #(first (filter (comp #{(:id w)} :ws-id) (work/list-workstreams :brian)))]
        (is (= :intake (get-in (row) [:position :at])))
        (is (= :triage (:stage (row))) "the band is status's, not the stored stage")
        (is (= {:decision :advanced} (work/set-stage! :brian (:id w) :ready)))
        (is (= :ready (:stage (row))) "choosing the stored stage records the choice")))))

(deftest choosing-a-foreign-stored-stage-is-refused
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:external-refs []})]
        (history/write! (assoc (workstream/read-ws :brian (:id w)) :stage :planning))
        (is (thrown-with-msg? Exception #"Unknown workstream stage"
                              (work/set-stage! :brian (:id w) :planning))
            "naming the stage already stored does not skip the vocabulary check")
        (is (empty? (workstream/entries-of :brian (:id w) :stage-set)) "and records no choice")))))

(deftest an-issue-arrival-recorded-after-a-stage-choice-leaves-the-choice-standing
  (with-tmp
    (fn [_]
      (let [w   (workstream/create! :brian {:external-refs []})
            row #(first (filter (comp #{(:id w)} :ws-id) (work/list-workstreams :brian)))]
        (workstream/advance-stage! :brian (:id w) :ready :person)
        (workstream/append-entry! :brian (:id w) {:kind :issue}
                                  (pr-str {:format :issue :id "brian#1"}))
        (is (= :ready (:stage (row)))
            "the backfill records an issue's arrival after the stage chosen for it")))))

(deftest a-queued-intake-stays-in-the-pen
  (with-tmp
    (fn [_]
      (let [w   (with-redefs [nido.notion.views/facet-properties (constantly nil)]
                  (work/enqueue-inbox! {:project :brian
                                          :trigger {:name :t :skill :triage-bug}
                                          :payload {:adapter :slack-message :id "slack-C-1.0"
                                                    :title "hi" :text "hi" :url "u"}}))
            row #(first (filter (comp #{(:id w)} :ws-id) (work/list-workstreams :brian)))]
        (is (= :incoming (:stage (row))) "queueing records the pen; status alone reads :intake")
        (workstream/advance-stage! :brian (:id w) :triaging)
        (is (= :triage (:stage (row))) "once let in, status places it")))))

(deftest every-arrival-is-born-running-the-recipe
  (with-tmp
    (fn [_]
      (with-redefs [nido.notion.views/facet-properties (constantly nil)]
        (let [penned (work/enqueue-inbox! {:project :brian :trigger {:name :t :skill :triage-bug}
                                           :payload {:adapter :slack-message :id "slack-C-2.0"
                                                     :title "hi" :text "hi" :url "u"}})
              fired  (work/ensure-workstream! :brian {:id "BR-77" :title "t"})
              bare   (work/ensure-workstream! :brian {:title "no ref"})
              minted (workstream/find-or-mint-by-ref! :brian "BR-78")]
          (doseq [w [penned fired bare minted]]
            (is (standing/activated? (workstream/read-ws :brian (:id w))) (str (:id w))))
          (is (= :intake (:at (pipeline/of :brian (:id fired))))
              "activation is a status entry: the arc still starts at the intent"))))))

(deftest a-stage-choice-takes-a-ticket-back-from-a-notion-settlement
  (with-tmp
    (fn [_]
      (let [w   (workstream/create! :brian {:external-refs []})
            row #(first (filter (comp #{(:id w)} :ws-id) (work/list-workstreams :brian)))]
        (workstream/advance-stage! :brian (:id w) :ready :person)
        (workstream/append-entry! :brian (:id w) {:kind :notion-status}
          (pr-str {:format :notion-status :page-id "abc" :status "Done" :by :poller}))
        (is (= :done (:stage (row))))
        (work/set-stage! :brian (:id w) :ready)
        (is (= :ready (:stage (row))) "re-choosing the stored stage reverses the settlement")))))

(deftest an-unanswered-blocker-outranks-the-analysis-band
  (with-tmp
    (fn [_]
      (let [w   (workstream/create! :brian {:external-refs [{:adapter :review-run :id "run-1" :title "t"}]})
            row #(first (filter (comp #{(:id w)} :ws-id) (work/list-workstreams :brian)))]
        (is (= :analysed (:stage (row))))
        (workstream/append-entry! :brian (:id w) {:kind :blocker}
          (pr-str {:format :blocker :summary "s" :needs "n?" :options sample-options :after "The work resumes once you answer."}))
        (is (= :blocked (get-in (row) [:position :at])))
        (is (= :in-progress (:stage (row))) "status places it, not where it came from")))))

(deftest a-shipment-carries-its-shipping-choice-out
  (with-tmp
    (fn [_]
      (let [w   (workstream/create! :brian {:external-refs []})
            row #(first (filter (comp #{(:id w)} :ws-id) (work/list-workstreams :brian)))]
        (workstream/advance-stage! :brian (:id w) :shipping)
        (workstream/append-entry! :brian (:id w) {:kind :ship-submitted :session "s" :run-id "r"}
          (pr-str {:format :ship-submitted :session "s"}))
        (is (= :shipping (:stage (row))) "the shipment it started does not supersede the choice")
        (workstream/append-entry! :brian (:id w) {:kind :note} "notion-sync: an account of it")
        (is (= :shipping (:stage (row))) "nor does a note")
        (workstream/append-entry! :brian (:id w) {:kind :blocker}
          (pr-str {:format :blocker :summary "s" :needs "n?" :options sample-options :after "The work resumes once you answer."}))
        (is (not= :shipping (:stage (row))) "work past the shipment still moves it on")))))

(deftest a-settled-row-skips-status
  (with-tmp
    (fn [_]
      (let [w    (workstream/create! :brian {:external-refs []})
            _    (workstream/close! :brian (:id w) :done)
            row  #(#'work/with-status :brian (wsv/workstream-row :brian (workstream/read-ws :brian (:id w))))]
        (is (nil? (:status (row))) "settled: nothing reads its status")
        (with-redefs [runs/live-runs (constantly [{:run-id "r"}])]
          (is (nil? (:status (row))) "a run's lock under it is a process, not something running on it"))))))

;; The TUI skips a board rebuild while this holds, so a write it misses is a
;; board that never shows the write. Sleeps keep the writes in distinct mtime
;; milliseconds.

(deftest board-stamp-holds-while-nothing-is-written
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:external-refs []})]
        (session/create! :brian (:id w) {:name "s1" :weight :light :autonomy nil})
        (is (= (work/board-stamp :brian) (work/board-stamp :brian)))))))

(deftest board-stamp-moves-with-the-ledger-sessions-and-runs
  (with-tmp
    (fn [_]
      (let [w      (workstream/create! :brian {:external-refs []})
            moved? (fn [write!]
                     (let [before (work/board-stamp :brian)]
                       (Thread/sleep 15)
                       (write!)
                       (not= before (work/board-stamp :brian))))]
        (is (moved? #(workstream/append-entry! :brian (:id w) {:kind :scratch}
                                               (pr-str {:format :scratch})))
            "a ledger append")
        (is (moved? #(session/create! :brian (:id w) {:name "s1" :weight :light :autonomy nil}))
            "a new session")
        (is (moved? #(write-run! "run-1" (:id w) "s1" "sid"))
            "a run registered")
        (is (moved? #(runs/write-run! (assoc (runs/read-run "run-1") :state :done)))
            "a registered run changing state")
        (is (moved? #(workstream/create! :brian {:external-refs []}))
            "a new workstream")))))

(deftest a-revive-is-owed-to-a-workstream-a-person-works-in
  (with-tmp
    (fn [_]
      (let [mk      (fn [] (:id (workstream/create! :brian {:external-refs []})))
            person  (mk)
            run     (mk)
            closed  (mk)
            human   (fn [id n] (session/create! :brian id {:name n :weight :heavy :autonomy nil}))
            dead    (fn [id n] {:boot-id "b" :instance-id (str "brian--" id) :project "brian"
                                :worktree (str "/wt/" n) :registered-at nil :runtime-key id :session n})]
        (human person "fix-it")
        (session/create! :brian person {:name (str person "--conductor") :weight :light :autonomy nil
                                        :conductor true})
        (session/create! :brian run {:name "review-analysis-x" :weight :light
                                     :autonomy {:skill :x :first-message "x" :agent :claude
                                                :claude-session-id nil :trigger :t
                                                :limits {} :priority 0 :uncapped? false
                                                :on-promote nil :phase :running
                                                :phase-history [] :error nil}})
        (human closed "done-already")
        (workstream/close! :brian closed :done)
        (with-redefs [nido.session.casualties/unsettled
                      (constantly {:casualties [(dead person (str person "--conductor"))
                                                (dead run (str run "--conductor"))
                                                (dead closed "done-already")
                                                (dead "not-a-ws" "legacy")]
                                   :unreadable []})
                      nido.session.lifecycle/session-coords (fn [n _] {:wt-path (str "/wt/" n)})]
          (let [owed (:casualties (work/owed-revivals))]
            (is (= [[person "fix-it" "/wt/fix-it"]]
                   (map (juxt :runtime-key :session :worktree) owed))
                "the person's session comes back, not the conductor it was registered under; a Run's
                 workstream, a closed one and one naming no readable workstream are owed nothing")))))))

;; ── A Slack thread's verdict: one workstream per proposed ticket ─────────────

(defn- slack-thread-ws!
  "A thread workstream whose triage appended `verdict` (a :proposed-tickets)."
  [verdict]
  (let [ws (workstream/create! :brian {:external-refs [{:adapter :slack-message
                                                        :id "slack-C1-100.0" :title "thread"}]})]
    (tickets/open! :brian "slack-C1-100.0" {:title "thread"})
    (workstream/append-entry! :brian (:id ws) {:kind :proposed-tickets} (pr-str verdict))
    ws))

(defn- thread-verdict [& keys]
  {:format :proposed-tickets :title "thread" :source-url "https://slack/p100"
   :tickets (vec (for [k keys] {:key k :ticket (assoc product-proposal :title (str "Ticket " k))}))})

(defn- counting-create []
  (let [n (atom 4900)]
    (fn [_] (let [br (str "BR-" (swap! n inc))]
              {:id br :page-id (str "pg-" br) :url (str "https://notion/" br)}))))

(deftest conclude-thread-gives-each-ticket-a-workstream-and-closes-the-thread
  (with-tmp
    (fn [_]
      (let [ws (slack-thread-ws! (thread-verdict "a" "b"))
            r  (atom nil)
            c  (with-slack-stubs {:create (counting-create)}
                 #(reset! r (work/conclude! :brian (:id ws))))
            ta (workstream/find-by-ref :brian :slack-message "slack-C1-100.0~a")
            tb (workstream/find-by-ref :brian :slack-message "slack-C1-100.0~b")]
        (is (= :fanned-out (:decision @r)))
        (is (= [:filed :filed] (map :decision (:tickets @r))))
        (is (= 2 (count (:created c))) "one page per ticket")
        (is (and ta tb (not= (:id ta) (:id tb))))
        (is (= (:id ta) (:id (workstream/find-by-ref-id :brian "BR-4901")))
            "the page is a ref of its ticket's workstream, where its drive will land")
        (is (nil? (some #(= :notion (:adapter %)) (:external-refs (workstream/read-ws :brian (:id ws)))))
            "the thread workstream files nothing itself")
        (is (= ["100.0" "100.0"] (map :thread-ts (:posted c))) "both links go into the thread")
        (is (= :done (:outcome (workstream/closure (workstream/read-ws :brian (:id ws))))))
        (is (empty? (:started c)) "nothing is driven before a person accepts it")
        (is (tickets/held-by-gate? :brian "BR-4901"))))))

(deftest conclude-thread-re-run-completes-and-never-doubles
  (with-tmp
    (fn [_]
      (let [ws (slack-thread-ws! (thread-verdict "a" "b"))]
        ;; Filing fails definitely for both: the workstreams exist, nothing is filed.
        (with-slack-stubs {:create (constantly {:error :http :status 400})}
          #(work/conclude! :brian (:id ws)))
        (let [ids (set (map :id (keep #(workstream/find-by-ref :brian :slack-message %)
                                      ["slack-C1-100.0~a" "slack-C1-100.0~b"])))
              c   (with-slack-stubs {:create (counting-create)} #(work/conclude! :brian (:id ws)))]
          (is (= 2 (count ids)))
          (is (= 2 (count (:created c))) "the re-run files what the first could not")
          (is (= ids (set (map :id (keep #(workstream/find-by-ref :brian :slack-message %)
                                         ["slack-C1-100.0~a" "slack-C1-100.0~b"]))))
              "no second workstream for a key")
          (is (= 1 (count (workstream/entries-of :brian (first ids) :proposed-ticket)))
              "the ticket is appended once"))))))

(deftest accepting-one-ticket-leaves-its-sibling-gated
  (with-tmp
    (fn [_]
      (let [ws (slack-thread-ws! (thread-verdict "a" "b"))
            _  (with-slack-stubs {:create (counting-create)} #(work/conclude! :brian (:id ws)))
            ta (workstream/find-by-ref :brian :slack-message "slack-C1-100.0~a")
            c  (with-slack-stubs {} #(work/apply! :brian (:id ta)))]
        (is (= 1 (count (:started c))))
        (is (= :planning (tickets/status :brian "BR-4901")))
        (is (tickets/held-by-gate? :brian "BR-4902") "the sibling still waits for a person")))))

(deftest a-thread-doubt-is-posted-and-never-accepted
  (with-tmp
    (fn [_]
      (let [ws (slack-thread-ws! {:format :proposed-tickets :title "thread"
                                  :source-url "https://slack/p100" :doubt "Works as designed."})
            c  (with-slack-stubs {}
                 #(is (= {:decision :doubted} (work/conclude! :brian (:id ws)))))]
        (is (empty? (:created c)))
        (is (= "<@U08720YNAT1> Works as designed." (:text (first (:posted c)))))
        (is (nil? (workstream/closure (workstream/read-ws :brian (:id ws))))
            "a doubted thread stays at the gate for a Reply")
        (with-slack-stubs {}
          #(is (= {:decision :error :error :doubted} (work/apply! :brian (:id ws)))))
        (is (empty? (workstream/entries-of :brian (:id ws) :triage-accepted)))))))

(deftest a-single-proposal-on-a-thread-workstream-is-refused
  (with-tmp
    (fn [_]
      ;; A thread triage that wrote the single-ticket kind under the thread id.
      (let [ws (slack-proposal-ws! product-proposal)]
        ;; It came to exist for a thread: a fire of a :slack-events trigger opened its unit.
        (units/fire! :brian (:id ws) :triage-slack-threads)
        (let [c (with-redefs [triggers/load-for-project
                              (constantly [{:name :triage-slack-threads :skill :triage-slack
                                            :source {:type :slack-events :emoji "owl"}}])]
                  (with-slack-stubs {}
                  #(do (is (= {:decision :error :error :wrong-kind} (work/conclude! :brian (:id ws))))
                       (is (= {:decision :error :error :wrong-kind} (work/apply! :brian (:id ws)))))))]
          (is (empty? (:created c)) "nothing is filed onto the thread")
          (is (nil? (workstream/find-by-ref-id :brian "BR-4900")))
          (is (empty? (workstream/entries-of :brian (:id ws) :triage-accepted))))))))

;; ── A blocker that asks several questions ───────────────────────────────────

(def ^:private two-question-blocker
  {:format    :blocker
   :summary   "The design round stopped on two things only you can decide."
   :after     "The seat amends the design with your answers."
   :questions [{:id "q1" :question "Should the card ask the general question?"
                :options [{:label "General" :summary "Ask the general question." :grants [:intent]}
                          {:label "As written" :summary "Show the long question."}]}
               {:id "q2" :question "May alternatives show as their own questions?"}]})

(deftest a-blocker-asking-several-is-answered-together-and-never-by-reply
  (is (= [{:id :answer-questions :label "Send answers" :kind :mutation :style :primary :seq 5 :words? true}]
         (work/gate-actions :in-progress true nil {:report-format :blocker :several? true :seq 5
                                                   :awaiting :answer-blocker}))
      "parked or not, a card's per-question answers are sent together — no whole-blocker Reply")
  (is (= [:answer-questions]
         (mapv :id (work/gate-actions :in-progress false nil
                                      {:report-format :blocker :several? true :seq 5 :awaiting :answer-blocker})))
      "nor the Answer offered with nobody parked")
  (is (not-any? #(= :answer-questions (:id %))
                (work/gate-actions :in-progress true nil {:report-format :blocker :options sample-options}))
      "a one-question blocker is answered as before")
  (is (work/position-carrying-action? :answer-questions))
  (is (work/asks-several? two-question-blocker))
  (is (not (work/asks-several? {:format :blocker :needs "One?" :summary "s" :after "a"}))))

(deftest land-is-offered-only-where-a-seat-can-act-on-it
  (is (= [] (work/gate-actions :shipping false nil {:report-format :pr-opened :seq 7 :awaiting :land}))
      "a workstream with nobody to land it — not activated, or a drain's slice — is offered no Land")
  (is (= [:reply] (mapv :id (work/gate-actions :shipping true nil {:report-format :pr-opened :seq 7
                                                                    :awaiting :land})))
      "though a parked agent can still be replied to")
  (is (= ["Land" "B" "Answer"]
         (mapv :label (work/gate-actions :in-progress false nil
                                         {:report-format :blocker :seq 9 :awaiting :answer-blocker
                                          :options [{:label "Land it now" :grants [:merge]}
                                                    {:label "Hold"}]})))
      "a question's landing option is the same Land, whatever it was called"))

