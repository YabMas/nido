(ns nido.coordinator.work-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.platform.config]
   [nido.coordinator.facets]
   [nido.coordinator.pickup]
   [nido.coordinator.promote]
   [nido.coordinator.resume :as resume]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.session :as session]
   [nido.coordinator.sources.state]
   [nido.coordinator.spawn]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.tickets :as tickets]
   [nido.coordinator.triggers]
   [nido.coordinator.workstream :as workstream]
   [nido.platform.io :as nido-io]
   [nido.notion.client :as notion-client]
   [nido.notion.views :as views]
   [nido.slack.client :as slack-client]
   [nido.platform.project]
   [nido.platform.process]
   [nido.session.lifecycle]
   [nido.session.state]
   [nido.coordinator.work :as work]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(deftest stages-is-the-canonical-spine
  ;; :incoming, not :intake — these are STORED stage names (see
  ;; spine-is-exactly-the-stages-the-board-honors). :intake is the TAB that holds
  ;; the :triage and :incoming bands, which is what tab-bands takes.
  (is (= [:incoming :triage :ready :in-progress :shipping :done] work/stages)))

(deftest tab-bands-splits-the-spine-into-two-jobs
  (let [grouped {:incoming [{:ws-id "i"}]
                 :triage {:in-flight [{:ws-id "tf"}] :queued [{:ws-id "tq"}]}
                 :in-progress [{:ws-id "p"}]
                 :shipping [{:ws-id "s"}]
                 :winding-down [{:ws-id "w"}]}]
    (is (= [[:triage ["tf" "tq"]] [:incoming ["i"]]]
           (for [[stage rows] (work/tab-bands :intake grouped)]
             [stage (mapv :ws-id rows)]))
        "intake = triage (in-flight then queued) then incoming")
    (is (= [[:shipping ["s"]] [:in-progress ["p"]] [:winding-down ["w"]]]
           (for [[stage rows] (work/tab-bands :active grouped)]
             [stage (mapv :ws-id rows)]))
        "active = shipping then in-progress then winding-down")))

(deftest tab-bands-union-covers-every-row-exactly-once
  ;; The guarantee this whole design exists for: nothing can be hidden by
  ;; default again. Every row the model emits is reachable from exactly one tab.
  (let [grouped {:incoming [{:ws-id "i"}]
                 :triage {:in-flight [{:ws-id "tf"}] :queued [{:ws-id "tq"}]}
                 :in-progress [{:ws-id "p"}]
                 :shipping [{:ws-id "s"}]
                 :winding-down [{:ws-id "w"}]
                 :dismissed [{:ws-id "d"}]}
        rows-of (fn [tab] (mapcat second (work/tab-bands tab grouped)))
        intake  (set (map :ws-id (rows-of :intake)))
        active  (set (map :ws-id (rows-of :active)))]
    (is (= (set (map :ws-id (work/grouped-rows grouped)))
           (into intake active))
        "union of both tabs = every row grouped-rows emits")
    (is (empty? (set/intersection intake active))
        "and no row appears in both tabs")))

(deftest tab-bands-intake-appends-dismissed
  (let [grouped {:incoming [{:ws-id "i"}]
                 :triage {:in-flight [{:ws-id "tf"}] :queued []}
                 :dismissed [{:ws-id "d"}]}]
    (is (= [[:triage ["tf"]] [:incoming ["i"]] [:dismissed ["d"]]]
           (for [[stage rows] (work/tab-bands :intake grouped)]
             [stage (mapv :ws-id rows)]))
        "dismissed is the trailing band on intake")
    (is (= [] (work/tab-bands :intake {:dismissed []}))
        "an empty dismissed band is dropped like any other")))

(deftest winding-down-lists-closed-ws-with-live-sessions
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :p {:stage :in-progress :external-refs []})]
        (session/create! :p (:id w) {:name "s1" :weight :light :autonomy nil})
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
      (let [w (workstream/create! :p {:stage :in-progress :external-refs []})]
        (session/create! :p (:id w) {:name "s1" :weight :light :autonomy nil})
        (is (= [] (work/winding-down :p #{"s1"})))))))

(deftest winding-down-catches-a-row-notion-finished
  ;; A ticket that reached Review (or Done) projects :done with no :closed record
  ;; — stateless by design. Without the row carrier it lands on neither tab while
  ;; its session still holds ports.
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :p {:stage :in-progress :external-refs []})]
        (session/create! :p (:id w) {:name "s1" :weight :light :autonomy nil})
        (is (= [] (work/winding-down :p #{"s1"} [{:ws-id (:id w) :stage :in-progress}]))
            "still in flight → not winding down")
        (let [[row :as rows] (work/winding-down :p #{"s1"} [{:ws-id (:id w) :stage :done}])]
          (is (= 1 (count rows)))
          (is (= (:id w) (:ws-id row)))
          (is (= :done (:outcome row)) "no :closed record to name an outcome")
          (is (= ["s1"] (:sessions row)))
          (is (nil? (:closed (workstream/read-ws :p (:id w))))
              "the band is a read — the projection must not settle the record"))))))

(deftest bring-down!-downs-only-live-sessions
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :p {:stage :in-progress :external-refs []})
            downed (atom [])]
        (session/create! :p (:id w) {:name "live1" :weight :light :autonomy nil})
        (session/create! :p (:id w) {:name "dead1" :weight :light :autonomy nil})
        (workstream/close! :p (:id w) :done)
        (with-redefs [work/live-session-names (constantly #{"live1"})
                      nido.session.lifecycle/down! (fn [n _] (swap! downed conj n))]
          (is (= {:downed ["live1"]} (work/bring-down! :p (:id w))))
          (is (= ["live1"] @downed)))))))

(deftest tab-bands-active-appends-winding-down
  (let [grouped {:in-progress [{:ws-id "p"}] :shipping [{:ws-id "s"}]
                 :winding-down [{:ws-id "w"}]}]
    (is (= [[:shipping ["s"]] [:in-progress ["p"]] [:winding-down ["w"]]]
           (for [[stage rows] (work/tab-bands :active grouped)]
             [stage (mapv :ws-id rows)])))))

(deftest screen-marks-pending-winding-down-rows
  (let [groups [{:project "p" :grouped {:winding-down [{:ws-id "w1"} {:ws-id "w2"}]}}]
        screen (work/screen {:scope "all" :tab :active}
                            {:groups groups :winddown-pending #{"p/w1"}})]
    (is (= [true false]
           (->> screen :groups first :grouped :winding-down (map (comp boolean :pending?)))))))

(deftest tab-bands-drops-empty-bands-and-tolerates-absent-keys
  (is (= [] (work/tab-bands :active {:in-progress [] :shipping []}))
      "empty bands are dropped")
  (is (= [] (work/tab-bands :active {}))
      "absent keys are not an error")
  (is (= [[:triage ["t"]]]
         (for [[stage rows] (work/tab-bands :intake {:triage {:in-flight [{:ws-id "t"}] :queued []}})]
           [stage (mapv :ws-id rows)]))
      "a band with rows survives while its empty sibling is dropped"))

(deftest tab-bands-unknown-tab-behaves-as-intake
  (let [grouped {:triage {:in-flight [{:ws-id "t"}] :queued []} :in-progress [{:ws-id "p"}]}]
    (is (= (work/tab-bands :intake grouped) (work/tab-bands :bogus grouped)))))

(deftest screen-passes-the-tab-through
  (let [s (work/screen {:surface :workstreams :scope "all" :tab :active}
                       {:groups [] :gates [] :pending #{}})]
    (is (= :active (:tab s)))))

(deftest classify-origin-delegates-to-source-classifier
  (is (= :scratch (work/classify-origin {:stage :scratch :external-refs []})))
  (is (= :notion  (work/classify-origin {:stage :triaging
                                         :external-refs [{:adapter :notion :id "BR-1"}]})))
  (is (= :github  (work/classify-origin {:stage :ready
                                         :external-refs [{:adapter :github-issue :id "o/r#1"}]})))
  (is (= :slack   (work/classify-origin {:stage :triaging
                                         :external-refs [{:adapter :slack-message :id "slack-C1-1.0"}]}))))

(deftest list-workstreams-folds-scratch-into-in-progress
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :scratch :external-refs []})]
        (session/create! :brian (:id w) {:name "poke" :weight :light :autonomy nil}))
      (let [row (first (work/list-workstreams :brian #{"poke"}))]
        (is (= :scratch (:origin row)) "origin preserved")
        (is (= :in-progress (:stage row)) "scratch enters the spine at in-progress")
        (is (nil? (:source row)) ":source is renamed to :origin")))))

(deftest list-workstreams-settled-scratch-reads-done
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :scratch :external-refs []})]
        (session/create! :brian (:id w) {:name "poke" :weight :light :autonomy nil})
        (workstream/close! :brian (:id w) :done))
      (is (= :done (:stage (first (work/list-workstreams :brian))))
          "a closed scratch workstream is :done, not :in-progress"))))

(deftest list-workstreams-preserves-ref-stage
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-9" :title "t"}]})]
        (tickets/open! :brian "BR-9" {:title "t"})
        (tickets/set-status! :brian "BR-9" :triaged)
        (session/create! :brian (:id w) {:name "s" :weight :light :autonomy nil}))
      (let [row (first (work/list-workstreams :brian))]
        (is (= :notion (:origin row)))
        (is (= :ready (:stage row)) "a triaged notion ticket projects to :ready, unchanged")))))

(deftest dismissed-rows-project-to-the-dismissed-stage
  (with-tmp
    (fn [_]
      ;; Slack-origin: derive-stage would fold :dismissed to :done, and the
      ;; closed ws reads :settled — the :dismissed? clause must beat both.
      (let [w (workstream/create! :brian
                {:stage :triage
                 :external-refs [{:adapter :slack-message :id "slack-C1-1.2" :title "t"}]})]
        (tickets/open! :brian "slack-C1-1.2" {:title "t"})
        (tickets/dismiss! :brian "slack-C1-1.2")
        (workstream/close! :brian (:id w) :dropped)
        (is (= [:dismissed] (map :stage (work/list-workstreams :brian)))
            "dismissed beats both the :settled fold and derive-stage's :done")))))

(deftest undismissed-rows-are-unaffected
  (with-tmp
    (fn [_]
      (workstream/create! :brian
        {:stage :triage
         :external-refs [{:adapter :slack-message :id "slack-C1-9.9" :title "t"}]})
      (tickets/open! :brian "slack-C1-9.9" {:title "t"})
      (tickets/set-status! :brian "slack-C1-9.9" :awaiting-input)
      (is (= [:triage] (map :stage (work/list-workstreams :brian)))))))

(deftest grouped-folds-scratch-into-in-progress-group
  (with-tmp
    (fn [_]
      ;; a scratch one-off
      (let [s (workstream/create! :brian {:stage :scratch :external-refs []})]
        (session/create! :brian (:id s) {:name "poke" :weight :light :autonomy nil}))
      ;; a triaged notion ticket → :ready, which is NOT a board band (backlog
      ;; lives in Notion) — it doesn't appear in any group.
      (let [n (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-3" :title "t"}]})]
        (tickets/open! :brian "BR-3" {:title "t"})
        (tickets/set-status! :brian "BR-3" :triaged)
        (session/create! :brian (:id n) {:name "s" :weight :light :autonomy nil}))
      (let [g (work/grouped :brian #{"poke"})]
        (is (not (contains? g :ready)) "no :ready band — backlog is Notion's")
        (is (= 1 (count (:in-progress g))) "the scratch one-off folds into :in-progress")))))

(def ^:private autonomy-running
  {:skill :triage-bug :first-message "x" :agent :claude :claude-session-id nil
   :trigger :triage-bug :limits {:budget "30m"} :priority 4 :uncapped? false :on-promote nil
   :phase :running :phase-history [{:at "2026-06-01T00:00:00Z" :phase :running}]
   :error nil})

(deftest workstream-detail-presents-sessions-on-the-autonomy-axis
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-5" :title "t"}]})]
        (session/create! :brian (:id w) {:name "auto" :weight :heavy :autonomy autonomy-running})
        (session/create! :brian (:id w) {:name "me"   :weight :light :autonomy nil})
        (let [d  (work/workstream :brian (:id w))
              by (into {} (map (juxt :name identity)) (:sessions d))]
          (is (= (:id w) (:ws-id d)) "detail uses the same :ws-id key as list-workstreams rows")
          (is (= :notion (:origin d)))
          (is (= :autonomous (:autonomy-level (by "auto"))))
          (is (= {:budget "30m"} (:brakes (by "auto"))) "brakes = the autonomy :limits")
          (is (= :running (:status (by "auto"))))
          (is (= :interactive (:autonomy-level (by "me"))))
          (is (nil? (:brakes (by "me"))) "interactive sessions carry no brakes")
          (is (= :up (:status (by "me"))) "a live human session reads :up"))))))

(deftest workstream-detail-flags-the-hitl-gate
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (session/create! :brian (:id w) {:name "gate" :weight :heavy
                                         :autonomy (assoc autonomy-running :phase :parked)})
        (is (true? (:parked? (first (:sessions (work/workstream :brian (:id w))))))
            "a parked autonomous session is at the HITL gate")))))

(deftest workstream-detail-flags-the-current-ledger-entry
  (with-tmp
    (fn [_]
      (let [w  (workstream/create! :brian {:stage :triaging :external-refs []})
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
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (is (true? (:on-latest? (work/workstream :brian (:id w))))
            "an entryless workstream is trivially on its current (only) state")))))

(deftest workstream-detail-nil-for-absent
  (with-tmp
    (fn [_]
      (is (nil? (work/workstream :brian "ws-does-not-exist"))))))

(deftest spine-is-exactly-the-stages-the-board-honors
  ;; work/stages is what the TUI stage picker offers and what default-target
  ;; validates a configured override against — both feed set-stage!, so every
  ;; entry must be a stage the board actually honors. The head used to read
  ;; :intake, a TAB name no record ever carries: picking it wrote a stage
  ;; grouped-by-stage has no key for and the workstream left every band.
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

(deftest set-stage-done-closes-the-workstream
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (is (= {:decision :done} (work/set-stage! :brian (:id w) :done)))
        (is (some? (:closed (workstream/read-ws :brian (:id w)))))))))

(deftest set-stage-advance-moves-stage-without-a-leg
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (is (= {:decision :advanced} (work/set-stage! :brian (:id w) :ready)))
        (is (= :ready (:stage (workstream/read-ws :brian (:id w)))))))))

(deftest set-stage-in-progress-runs-the-promote-gesture
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})
            calls (atom [])]
        (with-redefs [nido.coordinator.promote/promote-workstream!
                      (fn [p id] (swap! calls conj [p id]) {:decision :promote})]
          (is (= {:decision :promote} (work/set-stage! :brian (:id w) :in-progress)))
          (is (= [[:brian (:id w)]] @calls) "delegates to the shared promote gesture"))))))

(deftest new!-births-a-scratch-workstream-with-its-session
  (with-tmp
    (fn [_]
      (let [ups (atom [])]
        (with-redefs [nido.session.lifecycle/up! (fn [n opts] (swap! ups conj [n opts]) nil)]
          (let [ws-id (work/new! :brian "spike-thing")]
            (is (string? ws-id))
            (is (= [["spike-thing" {:project "brian"}]] @ups) "session brought up via lifecycle")
            (let [w (workstream/read-ws :brian ws-id)]
              (is (= :scratch (work/classify-origin w)) "ref-less scratch workstream")
              (is (= ["spike-thing"]
                     (map :name (session/list-sessions :brian ws-id)))))))))))

(deftest open-target-prefers-the-live-session
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (session/create! :brian (:id w) {:name "old"  :weight :light :autonomy nil})
        (session/archive! :brian (:id w) "old")
        (session/create! :brian (:id w) {:name "live" :weight :light :autonomy nil})
        (is (= {:project :brian :session "live"} (work/open-target :brian (:id w)))
            "open lands in the live session, not the archived one")))))

(deftest open-target-nil-when-no-sessions
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (is (nil? (work/open-target :brian (:id w))))))))

(def ^:private parked-autonomy
  {:skill :triage-bug :first-message "x" :agent :claude :claude-session-id "sid"
   :trigger :triage-bug :limits {} :priority 0 :uncapped? false :on-promote nil
   :phase :parked :phase-history [{:at "t" :phase :parked}] :error nil})

(defn- write-run! [id ws-id sname sid]
  (fs/create-dirs (cstate/run-dir id))
  (runs/write-run! {:id id :project :brian :trigger :triage-bug :source {:type :manual}
                    :event-payload {} :skill :triage-bug :first-message "/triage-bug"
                    :agent :claude :session-name sname :workstream-id ws-id
                    :claude-session-id sid :limits {:budget "30m"} :priority 0
                    :session-profile :lite :uncapped? false :state :awaiting-review
                    :state-history [{:at "2026-06-18T00:00:00Z" :state :queued}]
                    :artifacts [] :error nil}))

(deftest reclaimed?-true-when-run-home-absent
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (session/create! :brian (:id w) {:name "run-x" :weight :light :autonomy parked-autonomy})
        (write-run! "r1" (:id w) "run-x" "sid")
        (with-redefs [runs/home-present? (constantly false)]
          (is (true? (work/reclaimed? :brian (:id w) "run-x"))
              "a run-owned session whose home was reclaimed is reclaimed?"))))))

(deftest reclaimed?-false-when-home-present
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (session/create! :brian (:id w) {:name "run-x" :weight :light :autonomy parked-autonomy})
        (write-run! "r1" (:id w) "run-x" "sid")
        (with-redefs [runs/home-present? (constantly true)]
          (is (false? (work/reclaimed? :brian (:id w) "run-x"))))))))

(deftest reclaimed?-false-when-no-run
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (session/create! :brian (:id w) {:name "manual" :weight :light :autonomy nil})
        (is (false? (work/reclaimed? :brian (:id w) "manual"))
            "a session with no owning run can't be re-hydrated → not reclaimed?")))))

(deftest ensure-open!-rehydrates-reclaimed-run-session
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})
            spawned (atom 0)]
        (session/create! :brian (:id w) {:name "run-x" :weight :light :autonomy parked-autonomy})
        (write-run! "r1" (:id w) "run-x" "sid")
        (with-redefs [runs/home-present? (constantly false)
                      runs/spawn-session-for-run! (fn [_] (swap! spawned inc))]
          (is (true? (work/ensure-open! :brian (:id w) "run-x"))
              "re-provisions and reports it re-hydrated"))
        (is (= 1 @spawned))))))

(deftest ensure-open!-noop-when-home-present
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})
            spawned (atom 0)]
        (session/create! :brian (:id w) {:name "run-x" :weight :light :autonomy parked-autonomy})
        (write-run! "r1" (:id w) "run-x" "sid")
        (with-redefs [runs/home-present? (constantly true)
                      runs/spawn-session-for-run! (fn [_] (swap! spawned inc))]
          (is (false? (work/ensure-open! :brian (:id w) "run-x"))))
        (is (zero? @spawned))))))

(deftest gate-actions-are-stage-derived
  (is (= [{:id :apply   :label "Apply"   :kind :mutation              :style :primary}
          {:id :dismiss :label "Dismiss" :kind :mutation              :style :danger}
          {:id :reply   :label "Reply"   :kind :resume                :style :default}]
         (work/gate-actions :triage true))
      "a parked triage offers one-click Apply (nido mutation), Dismiss, and free-text Reply")
  (is (= [{:id :dismiss :label "Dismiss" :kind :mutation :style :danger}]
         (work/gate-actions :triage false))
      "an unparked triage can still be dismissed off the radar")
  (is (= [{:id :promote :label "Promote" :kind :mutation :style :primary}
          {:id :drop    :label "Drop"    :kind :mutation :style :danger}]
         (work/gate-actions :ready false)))
  (is (= [{:id :reply :label "Reply" :kind :resume :style :default}
          {:id :done  :label "Done"  :kind :mutation :style :primary}]
         (work/gate-actions :in-progress true)))
  (is (= [] (work/gate-actions :intake true)))
  (is (= [] (work/gate-actions :done true))))

;; ── The design gate: same stage, different question ────────────────────────
;; No new spine stage. A parked :in-progress workstream whose latest ledger
;; entry is a design decision is asking the one thing the round could not
;; derive, so it offers one question — grant it, or send it back.

(deftest a-parked-design-decision-asks-to-be-approved
  (is (= [{:id :approve :label "Approve" :kind :mutation :style :primary}
          {:id :reply   :label "Reply"   :kind :resume  :style :default}]
         (work/gate-actions :in-progress true nil {:report-format :design-decision}))
      "Done is deliberately absent — settling a workstream is not an answer to
       'should we build this', and offering it beside Approve invites it to be
       read as one"))

(deftest every-other-in-progress-gate-is-unchanged
  (let [usual [{:id :reply :label "Reply" :kind :resume :style :default}
               {:id :done  :label "Done"  :kind :mutation :style :primary}]]
    (is (= usual (work/gate-actions :in-progress true nil {:report-format :blocker})))
    (is (= usual (work/gate-actions :in-progress true nil {:report-format nil})))
    (is (= usual (work/gate-actions :in-progress true))
        "the arity that predates report-format keeps its answer"))
  (is (= [] (work/gate-actions :in-progress false nil {:report-format :design-decision}))
      "a decision nobody is parked on is not a gate — there is no agent to
       resume, so approving it would resume nothing"))

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
  (is (= [:option-a :option-b :dismiss]
         (map :id (work/gate-actions :triage false nil
                                     {:report-format :blocker :options sample-options :seq 3})))
      "same at :triage, where a crashed triage run leaves its question behind")
  (is (= [] (work/gate-actions :in-progress false))
      "and a gate with no branches to offer is unchanged: still nothing"))

(deftest choosing-an-option-resumes-with-that-branch-read-from-the-ledger
  (with-tmp
    (fn [_]
      (let [w   (workstream/create! :brian {:stage :in-progress :external-refs []})
            got (atom nil)]
        (session/create! :brian (:id w) {:name "run-x" :weight :light :autonomy parked-autonomy})
        (workstream/append-entry! :brian (:id w) {:kind :blocker}
          (pr-str {:format :blocker :summary "The modal offers a branch nothing implements."
                   :needs "A product decision on Keep Old." :options sample-options}))
        (with-redefs [resume/resume! (fn [_ _ input] (reset! got input) {:decision :resumed})]
          (is (= :answered (:decision (work/resolve-gate! :brian (:id w) :option-b 1)))
              "the click names the report it was rendered from — entry 1, still the latest")
          (let [answer (:report (work/workstream :brian (:id w) 2))]
            (is (= :blocker-answered (:format answer))
                "the answer is written to the ledger BEFORE the resume, so a turn
                 that dies mid-flight cannot swallow a human decision")
            (is (= ["B" "Implement archive-and-clone" 1 "run-x"]
                   [(:letter answer) (:label answer) (:blocker-seq answer) (:resumed answer)])))
          (is (str/includes? @got "option B — Implement archive-and-clone"))
          (is (str/includes? @got "Build the archive path.")
              "the branch is repeated in full — the resumed turn cannot see the gate")
          (is (str/includes? @got "Needs a prior product call"))
          (is (str/includes? @got "record a new :blocker")
              "an option that does not hold is a new blocker, not a licence to
               take the other branch on the human's behalf"))))))

(deftest an-answer-outlives-the-session-that-asked-the-question
  ;; The case that made this the shape it is: a run writes a blocker and then
  ;; dies (failed, budget-killed, torn down). The question is still the ledger's
  ;; latest entry and still needs a human, but there is nobody to resume — so the
  ;; answer is recorded and waits for whoever picks the workstream up next.
  (with-tmp
    (fn [_]
      (let [w       (workstream/create! :brian {:stage :in-progress :external-refs []})
            resumed (atom false)]
        (workstream/append-entry! :brian (:id w) {:kind :blocker}
          (pr-str {:format :blocker :summary "s" :needs "n" :options sample-options}))
        (with-redefs [resume/resume! (fn [& _] (reset! resumed true) {:decision :resumed})]
          (is (= {:decision :answered-unresumed}
                 (work/resolve-gate! :brian (:id w) :option-a 1))
              "an answer nobody was live to hear is an outcome, not a failure")
          (is (false? @resumed) "there was no parked session to resume"))
        (let [answer (:report (work/workstream :brian (:id w) 2))]
          (is (= :blocker-answered (:format answer)))
          (is (= "A" (:letter answer)))
          (is (nil? (:resumed answer))
              "the record says plainly that nobody was told"))
        (is (= [] (work/gate-actions :in-progress false nil
                                     {:report-format (:format (:action-report (work/workstream :brian (:id w))))
                                      :options (:options (:action-report (work/workstream :brian (:id w))))}))
            "and the gate stops asking: the latest entry is the answer, not the
             question, so the buttons are gone")))))

(deftest answering-twice-is-refused-by-the-same-position-check
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :in-progress :external-refs []})]
        (workstream/append-entry! :brian (:id w) {:kind :blocker}
          (pr-str {:format :blocker :summary "s" :needs "n" :options sample-options}))
        (is (= :answered-unresumed (:decision (work/resolve-gate! :brian (:id w) :option-a 1))))
        (is (= {:decision :option-stale} (work/resolve-gate! :brian (:id w) :option-b 1))
            "entry 1 is no longer the latest — the answer is — so a second click
             on the same rendered page cannot answer the question twice")))))

(deftest a-letter-that-no-longer-resolves-refuses-rather-than-resuming
  (with-tmp
    (fn [_]
      (let [w       (workstream/create! :brian {:stage :in-progress :external-refs []})
            resumed (atom false)]
        (workstream/append-entry! :brian (:id w) {:kind :blocker}
          (pr-str {:format :blocker :summary "s" :needs "n" :options sample-options}))
        (with-redefs [resume/resume! (fn [& _] (reset! resumed true) {:decision :resumed})]
          (is (= {:decision :option-stale} (work/resolve-gate! :brian (:id w) :option-c 1))
              "the click carries a letter; the CURRENT ledger decides what it meant")
          (is (= {:decision :option-stale} (work/resolve-gate! :brian (:id w) :option-a))
              "and a click carrying no position at all cannot be bound to a
               question, so it is refused rather than resolved blind")
          (is (false? @resumed)))))))

(deftest a-click-on-a-report-the-ledger-has-moved-past-is-refused
  (with-tmp
    (fn [_]
      (let [w       (workstream/create! :brian {:stage :in-progress :external-refs []})
            resumed (atom false)]
        (workstream/append-entry! :brian (:id w) {:kind :blocker}
          (pr-str {:format :blocker :summary "Keep Old" :needs "n" :options sample-options}))
        ;; Another tab answered; the agent parked again on a DIFFERENT question
        ;; that happens to have as many branches — so the letter still resolves.
        (workstream/append-entry! :brian (:id w) {:kind :blocker}
          (pr-str {:format :blocker :summary "Retention" :needs "n"
                   :options [{:label "Delete after 30 days" :summary "x"}
                             {:label "Keep forever" :summary "y"}]}))
        (with-redefs [resume/resume! (fn [& _] (reset! resumed true) {:decision :resumed})]
          (is (= {:decision :option-stale} (work/resolve-gate! :brian (:id w) :option-a 1))
              "answering entry 1 after entry 2 replaced it would resume the agent
               with branch A of a question the human never read")
          (is (false? @resumed))
          (is (= {:decision :answered-unresumed}
                 (work/resolve-gate! :brian (:id w) :option-a 2))
              "the same letter on the CURRENT report still answers"))))))

(deftest option-actions-are-not-workstream-less
  (is (empty? (set/intersection @#'work/workstream-less-actions
                                (set work/option-action-ids)))
      "answering resumes a parked agent, so it needs a workstream — the two-way
       membership hazard documented on that set is why this is pinned"))

(deftest approve-is-not-a-workstream-less-action
  (is (not (contains? @#'work/workstream-less-actions :approve))
      "Approve resumes a parked agent, so it needs a workstream; the two-way
       membership hazard documented on that set is why this is pinned"))

(def ^:private approvable-baseline
  {:format :baseline :area "a" :bounded-by "b" :shape "s"
   :modules [{:id "m" :module "m" :hides "h" :interface "i"}]
   :composition "c"
   :load-bearing [{:id "c1" :property "p" :falsified-by "f" :evidence ["src/a.clj:1"]}]
   :read ["src/a.clj"]})

(defn- approvable
  "A workstream whose latest entry is a design decision on a verified baseline —
   the one gate that offers Approve. Returns [ws-id design-seq decision-seq]."
  []
  (let [w  (workstream/create! :brian {:stage :in-progress :external-refs []})
        id (:id w)
        add (fn [kind r] (workstream/append-entry! :brian id {:kind kind} (pr-str r))
              (count (:entries (workstream/read-ws :brian id))))]
    (add :intent {:format :intent :goal "g" :done-when ["d"]})
    (let [b (add :baseline approvable-baseline)
          _ (add :baseline-review {:format :baseline-review :verdict :sufficient
                                   :baseline-seq b :reason "ok"})
          d (add :design {:format :design :summary "s" :shape "sh"
                          :invariants ["one path"] :standing {:relation :conforms}
                          :baseline {:seq b :relation :within} :intent {:seq 1}
                          :effort :S})
          dd (add :design-decision {:format :design-decision :recommend :proceed
                                    :design-seq d :reason "r"
                                    :checks [{:check :goal-served :status :held :note "n"}]
                                    :asks "worth doing now?"})]
      [id b d dd])))

(deftest approving-records-the-grant-then-resumes
  ;; The order is the point, and the same one choose-option! holds: approval used
  ;; to be an argument to a resume and nothing more, so "was this decided" was
  ;; nowhere a reader could ask it and a resume that died swallowed the decision.
  (with-tmp
    (fn [_]
      (let [[id _ d dd] (approvable)
            got (atom nil)]
        (with-redefs [resume/resume! (fn [_ _ input] (reset! got input)
                                       {:decision :resumed})
                      work/parked-session (constantly {:name "auto"})]
          (is (= :approved (:decision (work/resolve-gate! :brian id :approve dd))))
          (let [granted (workstream/latest-entry :brian id :design-approved)]
            (is (= d (get-in granted [:design :seq])) "it names the design")
            (is (= dd (:at-seq granted)) "and the position it was granted at"))
          (is (str/includes? @got "APPROVED"))
          (is (str/includes? @got "not a plan"))
          (is (str/includes? @got "amend or supersede")
              "an approval that reads as a freeze is how the design stops being
               able to receive what the code teaches"))))))

(deftest a-grant-with-nobody-listening-is-still-a-grant
  (with-tmp
    (fn [_]
      (let [[id _ d dd] (approvable)]
        (with-redefs [work/parked-session (constantly nil)]
          (is (= :approved-unresumed (:decision (work/resolve-gate! :brian id :approve dd))))
          (is (= d (get-in (workstream/latest-entry :brian id :design-approved)
                           [:design :seq]))))))))

(deftest an-approval-granted-against-a-page-the-ledger-has-moved-past-is-refused
  ;; A retraction appended since, another tab's grant, or a second click of the
  ;; same button all land here. After granting, the approval IS the latest entry.
  (with-tmp
    (fn [_]
      (let [[id _ _ dd] (approvable)]
        (with-redefs [resume/resume! (fn [& _] {:decision :resumed})
                      work/parked-session (constantly {:name "auto"})]
          (is (= :approval-stale (:decision (work/resolve-gate! :brian id :approve nil)))
              "a click carrying no position fails closed")
          (is (= :approval-stale (:decision (work/resolve-gate! :brian id :approve (dec dd)))))
          (is (= :approved (:decision (work/resolve-gate! :brian id :approve dd))))
          (is (= :approval-stale (:decision (work/resolve-gate! :brian id :approve dd)))
              "the double click refuses on the same check"))))))

(deftest an-approval-is-refused-when-the-premise-went-while-you-were-reading
  ;; The check an option click has no equivalent of. The round said this could be
  ;; decided when it ran; standing is a statement about now.
  (with-tmp
    (fn [_]
      (let [[id b _ dd] (approvable)]
        (workstream/append-entry! :brian id {:kind :retraction}
                                  (pr-str {:format :retraction :retracts {:seq b}
                                           :because "the baseline is not true of the code"
                                           :evidence ["src/a.clj:9"]}))
        (with-redefs [resume/resume! (fn [& _] {:decision :resumed})
                      work/parked-session (constantly {:name "auto"})]
          (let [out (work/resolve-gate! :brian id :approve dd)]
            ;; The retraction moved the ledger on, so the position check catches
            ;; it first — which is the same refusal, arrived at one step sooner.
            (is (contains? #{:approval-stale :approval-refused} (:decision out)))
            (is (nil? (workstream/latest-entry :brian id :design-approved))
                "and no approval is ever written for a premise that is gone")))))))

(deftest approving-a-workstream-that-does-not-exist-is-a-no-op
  (with-tmp
    (fn [_]
      (is (= {:decision :no-workstream}
             (work/resolve-gate! :brian "ws-nope" :approve))
          "past the bare-row set, so it lands behind the read-ws guard"))))

(deftest gates-hydrates-a-parked-triage-workstream
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-7" :title "t"}]})]
        (tickets/open! :brian "BR-7" {:title "t"})
        (tickets/set-status! :brian "BR-7" :investigating)
        (workstream/append-entry! :brian (:id w) {:kind :impl}
                                  "# Verdict\n\nbug — reproduced.")
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy
                          :autonomy (assoc autonomy-running :phase :parked)})
        (let [g (first (work/gates :brian))]
          (is (= (:id w) (:ws-id g)))
          (is (= :notion (:origin g)))
          (is (= :triage (:stage g)))
          (is (= "auto" (:session g)) "the parked session a :reply would resume")
          (is (= [:apply :dismiss :reply] (map :id (:actions g)))
              "Notion origin: Dismiss offered same as any other origin")
          (is (= :markdown (-> g :report :format)))
          (is (= "Verdict" (-> g :report :title)))
          (is (= "# Verdict\n\nbug — reproduced." (-> g :report :markdown))))))))

(deftest gate-not-working-when-session-failed-not-in-flight
  ;; Regression: a live autonomous session stuck at :failed (e.g. a plan-bug spawn
  ;; failure, whose teardown is a no-op so the session stays :live at :failed) must
  ;; NOT strand a permanent 'working…' on the gate — that hides its actions and the
  ;; ticket looks stuck. resuming? counts only actively-executing phases. Vehicle: a
  ;; parked triage gate (a :failed session alongside is still not in flight).
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-9" :title "t"}]})]
        (tickets/open! :brian "BR-9" {:title "t"})
        (tickets/set-status! :brian "BR-9" :investigating)   ; stays :triage ⇒ a real gate
        (session/create! :brian (:id w)
                         {:name "triage" :weight :heavy
                          :autonomy (assoc autonomy-running :phase :parked)})
        (session/create! :brian (:id w)
                         {:name "impl" :weight :heavy
                          :autonomy (assoc autonomy-running :phase :failed)})
        (let [g (work/gate :brian (:id w))]
          (is (= :triage (:stage g)))
          (is (false? (:working? g))
              "a live-but-:failed session is NOT in flight ⇒ no stranded 'working…'")
          (is (= [:apply :dismiss :reply] (map :id (:actions g)))
              "gate actions stay actionable (Notion origin: Dismiss offered)"))))))

(deftest gate-working-when-session-actively-running
  ;; The honest positive case: a parked triage gate whose agent you resumed is now
  ;; mid-turn (:running) ⇒ working… (gate visible, actions gated until it re-parks).
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-10" :title "t"}]})]
        (tickets/open! :brian "BR-10" {:title "t"})
        (tickets/set-status! :brian "BR-10" :investigating)
        (session/create! :brian (:id w)
                         {:name "triage" :weight :heavy
                          :autonomy (assoc autonomy-running :phase :parked)})
        (session/create! :brian (:id w)
                         {:name "impl" :weight :heavy
                          :autonomy (assoc autonomy-running :phase :running)})
        (is (true? (:working? (work/gate :brian (:id w))))
            "an actively-running session ⇒ working… (gate visible, actions gated)")))))

(deftest triaged-failed-spawn-is-not-a-gate-but-stays-on-board
  ;; The scenario the two tests above used to cover on a :ready gate: a triaged
  ;; ticket whose impl spawn failed. :ready is no longer a gate, so it drops off the
  ;; Needs-you inbox entirely — no stranded 'working…' possible — and is pulled from
  ;; the board instead, where its Promote/Drop pane actions render unconditionally.
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-11" :title "t"}]})]
        (tickets/open! :brian "BR-11" {:title "t"})
        (tickets/complete! :brian "BR-11" :triaged :applied)   ; ticket :triaged ⇒ :ready
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
           :directions [] :notion-writes nil :trail []}))

(deftest gates-hydrates-a-slack-triage-report-from-the-workstream-ledger
  ;; A Slack triage report is appended to the workstream ledger, ROUTED by the
  ;; slack-message ref (workstream/append-to-ref!) — the single ledger store
  ;; post ledger-unification. The gate must surface it via work/active-ledger.
  (with-tmp
    (fn [_]
      (let [slack-id "slack-C1-1.0"
            w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :slack-message
                                                           :id slack-id :title "msg"}]})]
        (tickets/open! :brian slack-id {:title "msg"})
        (tickets/set-status! :brian slack-id :awaiting-input)
        (workstream/append-to-ref! :brian slack-id {:kind :triage}
                                   slack-edn-report)
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy
                          :autonomy (assoc autonomy-running :phase :parked)})
        (let [g (first (work/gates :brian))]
          (is (= (:id w) (:ws-id g)))
          (is (= :slack (:origin g)))
          (is (= :triage (:stage g)))
          (is (= :triage-report (-> g :report :format)))
          (is (= "Verdict" (-> g :report :title)))
          (is (= :bug (-> g :report :determination))))))))

(deftest gates-excludes-a-ready-workstream
  (with-tmp
    (fn [_]
      ;; triaged + a non-parked session → stage :ready. Ready is a PULL queue: you
      ;; pull it off the board to promote, so it must NOT appear in the needs-you gates.
      (let [r (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-8" :title "t"}]})]
        (tickets/open! :brian "BR-8" {:title "t"})
        (tickets/set-status! :brian "BR-8" :triaged)
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

(deftest all-gates-merges-across-projects
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (workstream/append-entry! :brian (:id w) {:kind :impl} "# X\n\nrep.")
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy
                          :autonomy (assoc autonomy-running :phase :parked)}))
      (with-redefs [nido.platform.project/list-projects
                    (constantly {"brian" {:directory "/tmp/brian"}})]
        (let [gs (work/all-gates)]
          (is (= 1 (count gs)))
          (is (= "brian" (:project (first gs))) "project name threads through to each gate"))))))

(deftest resolve-gate-promote-runs-the-promote-gesture
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})
            calls (atom [])]
        (with-redefs [nido.coordinator.promote/promote-workstream!
                      (fn [p id] (swap! calls conj [p id]) {:decision :promote})]
          (is (= {:decision :promote} (work/resolve-gate! :brian (:id w) :promote)))
          (is (= [[:brian (:id w)]] @calls)))))))

(deftest resolve-gate-dismiss-settles-dismissed-and-marks-ticket
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-42"}]})]
        (is (= {:decision :dismissed} (work/resolve-gate! :brian (:id w) :dismiss)))
        (is (= :dismissed (:outcome (:closed (workstream/read-ws :brian (:id w)))))
            "closed :dismissed, not :dropped — the outcome is a veto carrier in its
             own right, for rows with no ledger ref to stamp")
        (is (= :dismissed (tickets/status :brian "BR-42"))
            "dismiss records the off-radar disposition so auto-re-triage skips it")))))

(deftest dismiss-creates-ticket-record-when-absent-and-closes-ws
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-99"}]})]
        ;; never-triaged ticket: no meta record yet
        (is (nil? (tickets/status :brian "BR-99")))
        (is (= {:decision :dismissed} (work/dismiss! :brian (:id w))))
        (is (= :dismissed (tickets/status :brian "BR-99")))
        (is (some? (:closed (workstream/read-ws :brian (:id w)))))))))

(deftest dismiss-marks-slack-ticket-via-ledger-key
  ;; A Slack workstream's ticket is keyed on its slack-message id, not a Notion
  ;; BR-####. dismiss! must stamp :dismissed there too (same ledger key the
  ;; triage report lives under) so the off-radar disposition isn't lost.
  (with-tmp
    (fn [_]
      (let [slack-id "slack-C1-2.0"
            w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :slack-message
                                                           :id slack-id :title "msg"}]})]
        (is (= {:decision :dismissed} (work/dismiss! :brian (:id w))))
        (is (= :dismissed (tickets/status :brian slack-id))
            "dismiss stamps the slack-keyed ticket, not just notion ones")
        (is (some? (:closed (workstream/read-ws :brian (:id w)))))))))

(deftest dismissing-a-ref-less-workstream-still-reaches-the-dismissed-band
  ;; ledger-ref is notion-or-slack only, so a ref-less coordinator workstream has
  ;; no ticket record for the veto to live on. If :closed doesn't carry it, the
  ;; :settled fold takes over and projects :done — a band on NEITHER tab — so the
  ;; row leaves every surface with no Restore, while the toast promises the
  ;; opposite. That is exactly the silent loss this whole band exists to prevent.
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triage :external-refs []})]
        (is (= [:triage] (map :stage (work/list-workstreams :brian))))
        (is (= {:decision :dismissed} (work/dismiss! :brian (:id w))))
        (is (= [:dismissed] (map :stage (work/list-workstreams :brian)))
            "with no ledger key the :closed outcome is the veto's only carrier")
        (is (= [[:dismissed [(:id w)]]]
               (for [[stage rows] (work/tab-bands :intake (work/grouped :brian))]
                 [stage (mapv :ws-id rows)]))
            "and it is reachable as the Intake tab's trailing band")
        (is (= {:decision :restored} (work/restore! :brian (:id w))))
        (is (= [:triage] (map :stage (work/list-workstreams :brian)))
            "Restore is the way back, with no ticket record involved either")))))

(deftest resolve-gate-done-closes-done
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (work/resolve-gate! :brian (:id w) :done)
        (is (= :done (:outcome (:closed (workstream/read-ws :brian (:id w))))))))))

(deftest resolve-gate-reply-delegates-to-resume
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})
            calls (atom nil)]
        (with-redefs [nido.coordinator.resume/resume!
                      (fn [p id input] (reset! calls [p id input]) {:resumed "auto"})]
          (is (= {:resumed "auto"} (work/resolve-gate! :brian (:id w) :reply "do it")))
          (is (= [:brian (:id w) "do it"] @calls)))))))

(deftest resolve-gate-apply-finalizes-the-ticket-nido-side
  ;; Apply is a direct nido-side mutation (ticket:complete → :triaged/:applied), NOT a
  ;; claude resume — so it works for legacy pre-notion-cli reviews whose apply
  ;; conversation calls the removed Notion MCP tools (which stranded them in triage).
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-A9" :title "t"}]})
            resumed (atom false)]
        (tickets/open! :brian "BR-A9" {:title "t"})
        (tickets/set-status! :brian "BR-A9" :awaiting-input)
        (with-redefs [nido.coordinator.resume/resume! (fn [& _] (reset! resumed true) {:resumed "x"})
                      nido.coordinator.facets/refresh-for-ticket! (fn [& _] nil)]
          (is (= {:decision :applied} (work/resolve-gate! :brian (:id w) :apply)))
          (is (false? @resumed) "Apply does NOT resume a conversation")
          (is (= :triaged (tickets/status :brian "BR-A9"))
              "Apply finalizes the ticket :triaged nido-side ⇒ it leaves :triage")
          (is (= :applied (:disposition (tickets/read-meta :brian "BR-A9")))))))))

(def ^:private notion-edn-report
  "Valid TriageReport EDN for notion triage ledger tests."
  (pr-str {:format :triage-report :ticket-key "BR-9" :determination :bug
           :title "Verdict" :summary "ticket-ledger report."
           :confidence {:level :high :reason "r"}
           :routing nil
           :directions [] :notion-writes nil :trail []}))

(deftest gates-report-reads-a-triage-report-from-the-workstream-ledger
  (with-tmp
    (fn [_]
      ;; a parked Notion triage gate whose report is appended to the workstream
      ;; ledger routed by the BR-#### ref (workstream/append-to-ref!) — the
      ;; single ledger store post ledger-unification.
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-9" :title "t"}]})]
        (tickets/open! :brian "BR-9" {:title "t"})
        (tickets/set-status! :brian "BR-9" :investigating)
        (workstream/append-to-ref! :brian "BR-9" {:kind :triage} notion-edn-report)
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy
                          :autonomy (assoc autonomy-running :phase :parked)})
        (let [g (first (work/gates :brian))]
          (is (= :triage-report (-> g :report :format)))
          (is (= "Verdict" (-> g :report :title))))))))

(deftest gates-excludes-settled-workstreams
  (with-tmp
    (fn [_]
      ;; a CLOSED workstream with a stale :ready stage-override must still fold to
      ;; :done and stay out of the inbox — closed wins over any stage-override
      (let [w (workstream/create! :brian {:stage :ready :external-refs []})]
        (workstream/close! :brian (:id w) :done))
      (is (empty? (work/gates :brian))
          "a settled (closed) workstream is never a gate, even with a :ready stage-override"))))

(deftest gates-surface-the-parked-session-resume-error
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-2" :title "t"}]})]
        (tickets/open! :brian "BR-2" {:title "t"})
        (tickets/set-status! :brian "BR-2" :investigating)
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy
                          :autonomy (assoc autonomy-running :phase :parked
                                           :error {:reason :resume-failed :message "boom"})})
        (let [g (first (work/gates :brian))]
          (is (= :resume-failed (-> g :resume-error :reason)))
          (is (= "boom" (-> g :resume-error :message))))))))

(deftest gates-resume-error-nil-when-clean
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-3" :title "t"}]})]
        (tickets/open! :brian "BR-3" {:title "t"})
        (tickets/set-status! :brian "BR-3" :investigating)
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
  (with-redefs [nido.coordinator.workstream/read-ws (fn [_ _] {:id "x"})
                work/latest-report (fn [_ _] {:kind :triage :at "t" :title "V" :markdown "# V"})
                ;; minimal stubs so workstream's other projections don't throw:
                nido.coordinator.session/list-sessions (fn [_ _] [])
                nido.coordinator.workstreams-view/workstream-row (fn [_ _ & _] {:stage :triage :label "BR-7" :source :notion})]
    (is (= "# V" (-> (work/workstream "brian" "ws-1") :report :markdown)))))

(deftest list-workstreams-rows-carry-facets
  (with-tmp
    (fn [_]
      (workstream/create! :brian {:stage :triaging
                                  :external-refs [{:adapter :notion :id "BR-1"}]
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
      (workstream/create! :brian {:stage :triaging :external-refs [{:adapter :notion :id "BR-1"}]
                                  :facets {:app-domain ["Teacher"]}})
      (workstream/create! :brian {:stage :triaging :external-refs [{:adapter :notion :id "BR-2"}]
                                  :facets {:app-domain ["Student" "Teacher"]}})
      (workstream/create! :brian {:stage :triaging :external-refs [{:adapter :notion :id "BR-3"}]})
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
      (let [w (workstream/create! :brian {:stage :scratch :external-refs []})]
        (workstream/append-entry! :brian (:id w) {:kind :impl} "# First\n\none")
        (workstream/append-entry! :brian (:id w) {:kind :impl} "# Second\n\ntwo")
        (let [r (work/latest-report :brian (:id w))]
          (is (= :markdown (:format r)))
          (is (str/includes? (:markdown r) "Second") "returns the LATEST workstream entry"))))))

(deftest latest-report-reads-the-workstream-ledger-routed-by-ref
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-9"}]})]
        (tickets/open! :brian "BR-9" {:title "t"})
        (workstream/append-to-ref! :brian "BR-9" {:kind :impl} "# Impl\n\ndid it")
        (let [r (work/latest-report :brian (:id w))]
          (is (str/includes? (:markdown r) "did it")
              "reads the workstream ledger routed by the BR-#### ref"))))))

(deftest active-ledger-empty-when-no-entries
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :scratch :external-refs []})]
        (is (= [] (:entries (#'work/active-ledger :brian (:id w))))
            "no entries anywhere → empty vector")))))

(deftest active-ledger-reads-the-workstream-store-only
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-1"}]})]
        (tickets/open! :brian "BR-1" {:title "t"})       ; status record, no entries
        (tickets/set-status! :brian "BR-1" :triaged)
        (workstream/append-to-ref! :brian "BR-1" {:kind :note} "plan")
        (let [d (work/workstream :brian (:id w) 1)]
          (is (= :triaged (:status (:ledger d))) "status still from the ticket meta")
          (is (= 1 (:report-count (:ledger d))) "count from the workstream ledger")
          (is (some? (:report d)) "the workstream entry renders"))))))

(deftest workstream-browses-entries-newest-first
  (with-tmp
    (fn [_]
      (let [id (:id (workstream/create! :brian {:stage :scratch :external-refs []}))]
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
  "Seed a workstream whose ledger a :design record may legally join: a :baseline
   at seq 1 and an :intent at seq 2, the two entries check-baseline-citation!
   makes every design cite. Returns the ws-id."
  [id]
  (workstream/append-entry! :brian id {:kind :baseline}
    (pr-str {:format :baseline :area "the area" :bounded-by "the bound" :shape "the shape"
             :modules [{:id "mod-m" :module "m" :hides "how p is stored" :interface "p"}]
             :composition "m is the only reader of p, so p holds"
             :load-bearing [{:id "c1" :property "p holds"
                             :falsified-by "a caller that reads p without going through m"
                             :evidence ["src/x.clj:1"]}]
             :read ["src/x.clj"]}))
  (workstream/append-entry! :brian id {:kind :intent}
    (pr-str {:format :intent :goal "the goal" :done-when ["it is done"]}))
  id)

(defn- design-entry
  "A schema-valid :design citing design-ledger!'s baseline + intent, optionally
   amending entry `amends`."
  ([summary] (design-entry summary nil))
  ([summary amends]
   (pr-str (cond-> {:format     :design
                    :summary    summary
                    :shape      "the shape"
                    :standing   {:relation :conforms}
                    :baseline   {:seq 1 :relation :within}
                    :intent     {:seq 2}
                    :invariants ["the thing holds"]
                    :effort     :S}
             amends (assoc :supersedes {:seq amends :why "the premise moved"})))))

(deftest workstream-index-back-references-a-superseded-entry
  ;; The amend path appends; the superseded record stays. No single row can see
  ;; that relation — without the back-reference the index is two rows both
  ;; reading "design" and the reader has to open the newer one to learn the
  ;; older is dead.
  (with-tmp
    (fn [_]
      (let [id (design-ledger! (:id (workstream/create! :brian {:stage :in-progress :external-refs []})))]
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
      (let [id (design-ledger! (:id (workstream/create! :brian {:stage :in-progress :external-refs []})))]
        (workstream/append-entry! :brian id {:kind :design} (design-entry "first"))
        (workstream/append-entry! :brian id {:kind :design} (design-entry "second" 3))
        (workstream/append-entry! :brian id {:kind :design} (design-entry "third" 3))
        (let [by-seq (into {} (map (juxt :seq identity)) (:entries (work/workstream :brian id)))]
          (is (= 5 (:superseded-by (by-seq 3))) "newest superseder wins")
          (is (nil? (:superseded-by (by-seq 5)))))))))

(deftest workstream-index-leaves-an-unamended-ledger-unmarked
  (with-tmp
    (fn [_]
      (let [id (:id (workstream/create! :brian {:stage :scratch :external-refs []}))]
        (workstream/append-entry! :brian id {:kind :impl} "# One\n\nfirst")
        (workstream/append-entry! :brian id {:kind :impl} "# Two\n\nsecond")
        (is (every? #(and (nil? (:supersedes %)) (nil? (:superseded-by %)))
                    (:entries (work/workstream :brian id)))
            "markdown entries carry no supersession, and nothing is stamped")))))

(deftest workstream-at-rest-still-carries-the-current-report-for-its-actions
  (with-tmp
    (fn [_]
      (let [id (:id (workstream/create! :brian {:stage :in-progress :external-refs []}))]
        (workstream/append-entry! :brian id {:kind :impl} "# One\n\nfirst")
        (workstream/append-entry! :brian id {:kind :blocker}
          (pr-str {:format :blocker :summary "s" :needs "n" :options sample-options}))
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
      (let [id (:id (workstream/create! :brian {:stage :scratch :external-refs []}))]
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
      (let [id (:id (workstream/create! :brian {:stage :scratch :external-refs []}))]
        (workstream/append-entry! :brian id {:kind :impl} "# Solo\n\nonly")
        (is (= ["Solo"] (mapv :title (:entries (work/workstream :brian id)))))
        (is (str/includes? (:markdown (:report (work/workstream :brian id 1))) "only"))))))

(deftest workstream-index-title-falls-back-to-first-line
  (with-tmp
    (fn [_]
      (let [id (:id (workstream/create! :brian {:stage :scratch :external-refs []}))]
        (workstream/append-entry! :brian id {:kind :impl} "just prose no heading")
        (workstream/append-entry! :brian id {:kind :impl} "# Has heading\n\nx")
        (is (= ["Has heading" "just prose no heading"]
               (mapv :title (:entries (work/workstream :brian id))))
            "no markdown heading → first non-blank line")))))

(deftest workstream-index-title-uses-event-fields
  (with-tmp
    (fn [_]
      (let [id (:id (workstream/create! :brian {:stage :scratch :external-refs []}))]
        (workstream/append-entry! :brian id {:kind :implementation-plan}
          (pr-str {:format :implementation-plan :summary "x" :direction "First direction" :effort :S}))
        (workstream/append-entry! :brian id {:kind :blocker}
          (pr-str {:format :blocker :summary "y" :needs "Second needs"}))
        (let [d (work/workstream :brian id)]
          (is (= ["Second needs" "First direction"] (mapv :title (:entries d)))
              "index titles come from each event's fields via report/report-title"))))))

(defn- review-event
  "A :review ledger event pointing at `path` (the review-loop's report.json)."
  [path]
  (pr-str {:format :review-report :status :converged :base "main" :base-rev "abc123"
           :rounds 2 :findings-fixed 1 :findings-remaining 0 :report-path path}))

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
            id   (:id (workstream/create! :brian {:stage :scratch :external-refs []}))]
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
      (let [id (:id (workstream/create! :brian {:stage :scratch :external-refs []}))]
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
            id   (:id (workstream/create! :brian {:stage :scratch :external-refs []}))]
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

(deftest environment-resolves-latest-live-heavy-session
  (with-redefs [nido.coordinator.session/list-sessions
                (fn [_ _]
                  [{:name "triage-BR-1" :weight :light :substrate :live :created-at "2026-07-20T10:00:00Z"}
                   {:name "impl-BR-1"   :weight :heavy :substrate :live :created-at "2026-07-21T10:00:00Z"}
                   {:name "impl-BR-1b"  :weight :heavy :substrate :live :created-at "2026-07-22T10:00:00Z"}])]
    (is (= "impl-BR-1b" (:name (work/environment :brian "ws-1")))
        "latest heavy session by :created-at")))

(deftest environment-nil-when-no-heavy-session
  (with-redefs [nido.coordinator.session/list-sessions
                (fn [_ _] [{:name "triage-BR-1" :weight :light :substrate :live :created-at "2026-07-20T10:00:00Z"}])]
    (is (nil? (work/environment :brian "ws-1"))
        "triage-only (light) workstream has no environment")))

(deftest environment-excludes-archived-heavy
  (with-redefs [nido.coordinator.session/list-sessions
                (fn [_ _]
                  [{:name "impl-old" :weight :heavy :substrate :archived :created-at "2026-07-22T10:00:00Z"}
                   {:name "impl-new" :weight :heavy :substrate :live     :created-at "2026-07-21T10:00:00Z"}])]
    (is (= "impl-new" (:name (work/environment :brian "ws-1")))
        "an archived heavy session is excluded even if newer")))

(deftest work-core-does-not-require-a-ui-namespace
  ;; Layering: nido.coordinator.work is the model core every surface wraps. It must not
  ;; depend on a UI namespace (it used to require nido.ui.view-state purely to
  ;; borrow the source-filter defaults). Read via io/resource, not a relative
  ;; path: `bb --config ~/Code/nido/bb.edn` runs with the caller's cwd (that is
  ;; how the `nido` shell wrapper dispatches), so "src/nido/coordinator/work.clj" would not
  ;; resolve. "src" is on :paths, so the file is a classpath resource.
  (let [ns-form  (read-string (slurp (io/resource "nido/coordinator/work.clj")))
        required (->> ns-form
                      (filter list?)
                      (filter #(= :require (first %)))
                      (mapcat rest)
                      (map first)
                      (map str))]
    (is (seq required) "sanity: the ns form was actually parsed")
    (is (not-any? #(str/starts-with? % "nido.ui") required)
        "the model core must not require a UI namespace")))

(deftest screen-needs-count-equals-gate-count
  (let [gates [{:ws-id "a" :project "brian"} {:ws-id "b" :project "brian"}]
        s (work/screen {:surface :needs :scope "all" :source :notion :facets {}}
                       {:groups [] :gates gates :pending #{}})]
    (is (= 2 (:needs-count s)))
    (is (= 2 (count (:gates s))))))

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
  (is (= [:dismiss] (map :id (work/gate-actions :triage false :notion)))
      "Notion unparked triage: Dismiss is the only action")
  (is (= [:dismiss] (map :id (work/gate-actions :triage false :slack)))
      "Slack unparked triage: same")
  (is (= (work/gate-actions :triage true :notion) (work/gate-actions :triage true))
      "origin no longer changes the result"))

(deftest dismissed-stage-offers-only-restore
  (let [restore [{:id :restore :label "Restore" :kind :mutation :style :default}]]
    (is (= restore (work/gate-actions :dismissed false)))
    (is (= restore (work/gate-actions :dismissed true))
        "a dismissed row offers Restore whether or not a session is still parked")
    (is (= restore (work/gate-actions :dismissed false :notion)))))

(def ^:private proposed-ticket-edn
  "Valid ProposedTicket EDN for the Slack-approval path."
  (pr-str {:format :proposed-ticket
           :title "Logo bug on the pricing page"
           :ticket-type "bug"
           :priority "2 - Should"
           :source-url "https://myco.slack.com/archives/C1/p100"
           :problem "The logo disappears on mobile Safari."
           :root-cause "CSS media-query regression; verified live in REPL."
           :fix "Restore the logo rule for the mobile breakpoint. pricing.clj:42. Revert-shaped, small."}))

(deftest parse-slack-id-splits-channel-and-ts
  (is (= {:channel "C07N0U273AR" :ts "1718000000.000123"}
         (#'work/parse-slack-id "slack-C07N0U273AR-1718000000.000123"))
      "id is slack-<channel>-<ts>: channel dashless, ts digits+dot")
  (is (= {:channel "C1" :ts "100.0"} (#'work/parse-slack-id "slack-C1-100.0"))))

(deftest apply-proposed-creates-ticket-associates-ref-and-posts-link
  (with-tmp
    (fn [_]
      (let [ws       (workstream/create! :brian {:stage :triaging
                                                 :external-refs [{:adapter :slack-message
                                                                  :id "slack-C1-100.0" :title "msg"}]})
            posted   (atom nil)
            captured (atom nil)]
        (tickets/open! :brian "slack-C1-100.0" {:title "msg"})
        (tickets/set-status! :brian "slack-C1-100.0" :awaiting-input)
        (workstream/append-to-ref! :brian "slack-C1-100.0" {:kind :proposed-ticket}
                                   proposed-ticket-edn)
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

(deftest apply-proposed-error-leaves-ws-parked
  (with-tmp
    (fn [_]
      (let [ws     (workstream/create! :brian {:stage :triaging
                                               :external-refs [{:adapter :slack-message
                                                                :id "slack-C1-100.0" :title "msg"}]})
            posted (atom nil)]
        (tickets/open! :brian "slack-C1-100.0" {:title "msg"})
        (tickets/set-status! :brian "slack-C1-100.0" :awaiting-input)
        (workstream/append-to-ref! :brian "slack-C1-100.0" {:kind :proposed-ticket}
                                   proposed-ticket-edn)
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
      (let [ws (workstream/create! :brian {:stage :triaging :external-refs []})]
        (workstream/append-entry! :brian (:id ws) {:kind :proposed-ticket} proposed-ticket-edn)
        (with-redefs [notion-client/create-page!
                      (fn [& _] (throw (ex-info "create-page! must not be called" {})))]
          (let [r (work/apply! :brian (:id ws))]
            (is (= {:decision :error :error :no-slack-ref} r))
            (is (nil? (workstream/find-by-ref-id :brian "BR-4900"))
                "no notion ref added")))))))

(deftest apply-non-proposal-still-finalizes-nido-side
  ;; Regression: the proposal branch must not disturb the legacy nido-only apply.
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-B1" :title "t"}]})]
        (tickets/open! :brian "BR-B1" {:title "t"})
        (tickets/set-status! :brian "BR-B1" :awaiting-input)
        (with-redefs [nido.coordinator.facets/refresh-for-ticket! (fn [& _] nil)]
          (is (= {:decision :applied} (work/apply! :brian (:id w))))
          (is (= :triaged (tickets/status :brian "BR-B1"))))))))

(defn- routed-report-edn
  "TriageReport EDN string for a routed triage. `depth` :shallow ⇒ notion-writes nil."
  [{:keys [owner app-domain depth]}]
  (pr-str
   (cond-> {:format :triage-report :ticket-key "BR-77" :determination :bug
            :title "t" :summary "s" :confidence {:level :high :reason "r"}
            :routing {:owner owner :app-domain app-domain :depth depth}
            :directions [] :notion-writes nil :trail []}
     (= depth :deep)
     (assoc :notion-writes {:type "bug" :effort :M
                            :status-transition ["Needs verification" "Not started"]
                            :title "enriched title"
                            :description-prepend "the enriched body"}))))

(defn- with-routed-ws [depth owner app-domain f]
  ;; a parked Notion triage ws whose latest ledger entry is a routed :triage-report
  (let [w (workstream/create! :brian {:stage :triaging
                                      :external-refs [{:adapter :notion :id "BR-77"
                                                       :page-id "pg-77" :title "t"}]})]
    (tickets/open! :brian "BR-77" {:title "t"})
    (tickets/set-status! :brian "BR-77" :awaiting-input)
    (workstream/append-to-ref! :brian "BR-77" {:kind :triage}
                               (routed-report-edn {:owner owner :app-domain app-domain :depth depth}))
    (f w)))

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
                          nido.coordinator.facets/refresh-for-ticket! (fn [& _] nil)]
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
                          nido.coordinator.facets/refresh-for-ticket! (fn [& _] nil)]
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
                        nido.coordinator.facets/refresh-for-ticket! (fn [& _] nil)]
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
                          nido.coordinator.facets/refresh-for-ticket! (fn [& _] nil)]
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
                        nido.coordinator.facets/refresh-for-ticket! (fn [& _] nil)]
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
                (fn [_] {:sessions [{:name "app-up"    :app-port 3101 :nrepl-port nil  :pg-port nil}
                                    {:name "repl-up"   :app-port nil  :nrepl-port 5601 :pg-port nil}
                                    {:name "crashed"   :app-port 3102 :nrepl-port 5602 :pg-port nil}
                                    {:name "shared-pg" :app-port nil  :nrepl-port nil  :pg-port 6145}
                                    {:name "down"      :app-port nil  :nrepl-port nil  :pg-port nil}]})
                nido.platform.process/tcp-open? (fn [port] (contains? #{3101 5601 6145} port))]
    (is (= #{"app-up" "repl-up"} (work/live-session-names "p")))
    (is (not (contains? (work/live-session-names "p") "crashed"))
        "recorded ports that no longer answer are not liveness")
    (is (not (contains? (work/live-session-names "p") "shared-pg"))
        "pg is never a signal — the shared cluster answers for every session at once")))

(deftest session-live?-reads-app-or-nrepl-only
  (with-redefs [nido.platform.process/tcp-open? (fn [port] (= 4000 port))]
    (is (true?  (work/session-live? {:app-port 4000})))
    (is (true?  (work/session-live? {:nrepl-port 4000})))
    (is (false? (work/session-live? {:app-port 4001 :nrepl-port 4002})))
    (is (false? (work/session-live? {:pg-port 4000}))  "pg excluded")
    (is (false? (work/session-live? {:repl-pid 12345})) "pid excluded — PIDs get recycled")
    (is (false? (work/session-live? {})))))

(deftest machine-facts-keyed-by-session-name
  (with-redefs [work/machine-rows
                (fn [_ _] [{:name "a" :live? true :entry {:url "u" :pg-port 5501 :app-port 3101}
                            :repl-rss 1024 :pg-rss 2048 :heap-max "2g"}
                           {:name "b" :live? false :entry nil}])
                nido.platform.project/list-projects (constantly {"p" {:directory "/x"}})]
    (let [facts (work/machine-facts "p" ["a"])]
      (is (= ["a"] (keys facts)))
      (is (= {:live? true :url "u" :pg-port 5501 :nrepl-port nil :app-port 3101
              :repl-rss 1024 :pg-rss 2048 :heap-max "2g"}
             (get facts "a"))))))

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

(deftest orphan-live-sessions-is-the-set-difference
  (is (= #{"a"} (work/orphan-live-sessions #{"a" "b"} #{"b" "c"}))))

(deftest adopt-orphans!-births-scratch-for-live-orphans-idempotently
  (with-tmp
    (fn [_]
      (with-redefs [work/live-session-names (constantly #{"one-off"})]
        (is (= ["one-off"] (:adopted (work/adopt-orphans! :p))))
        ;; the born scratch ws owns the session now → second pass adopts nothing
        (is (= [] (:adopted (work/adopt-orphans! :p))))
        (let [ws (map #(workstream/read-ws :p %) (workstream/list-ids :p))]
          (is (= 1 (count ws)))
          (is (empty? (:external-refs (first ws)))))))))

(deftest adopt-orphans!-skips-closed-owned-sessions
  ;; A session under a CLOSED ws is a winding-down leftover, not an orphan.
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :p {:stage :in-progress :external-refs []})]
        (session/create! :p (:id w) {:name "left" :weight :light :autonomy nil})
        (workstream/close! :p (:id w) :done)
        (with-redefs [work/live-session-names (constantly #{"left"})]
          (is (= [] (:adopted (work/adopt-orphans! :p)))))))))

(deftest adopt-orphans!-yields-a-bare-scratch-double-owner
  ;; Adopted-then-claimed: when a REAL open ws also owns the session, the bare
  ;; scratch ws is deleted (newest real owner wins).
  (with-tmp
    (fn [_]
      (with-redefs [work/live-session-names (constantly #{"claimed"})]
        (work/adopt-orphans! :p)                              ; births scratch owner
        (let [real (workstream/create! :p {:stage :in-progress
                                           :external-refs [{:adapter :notion :id "BR-1"}]})]
          (session/create! :p (:id real) {:name "claimed" :weight :light :autonomy nil})
          (let [{:keys [yielded]} (work/adopt-orphans! :p)]
            (is (= 1 (count yielded)))
            (is (= [(:id real)]
                   (map :id (keep #(workstream/read-ws :p %) (workstream/list-ids :p)))))))))))

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
                               ;; malformed :created-at: entry-age-ms swallows the parse failure
                               ;; and returns nil, which reads as "old enough" — pruned.
                               "/wt/bad-ts"  {:instance-id "p--bad-ts"  :app-port 3005
                                              :created-at "not-a-timestamp"}})
                  nido.session.state/remove-many-from-registry!
                  (fn [ks] (reset! removed (set ks)))
                  nido.platform.process/tcp-open? (fn [port] (= 3000 port))
                  nido.platform.process/process-alive? (fn [pid] (= 4242 pid))]
      (let [pruned (work/prune-dead-registry! now)]
        (is (= #{"/wt/dead" "/wt/no-ts" "/wt/bad-ts"} @removed)
            "dead + old is pruned; no timestamp or a malformed one reads as old and is pruned")
        (is (not (contains? @removed "/wt/live"))    "a listening session survives")
        (is (not (contains? @removed "/wt/young"))   "inside the grace window it survives")
        (is (not (contains? @removed "/wt/lite"))    "a lite session with no recorded port is never a prune candidate")
        (is (not (contains? @removed "/wt/repl-up")) "a dead port but a live repl-pid vetoes the delete")
        (is (= #{"p--dead" "p--no-ts" "p--bad-ts"} (set pruned))
            "returns instance-ids for the coordinator's log line")))))

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
            "status cleared → the auto-triage gate treats it as never-triaged")
        (is (nil? (:closed (workstream/read-ws :brian (:id w))))
            "workstream reopened")
        (is (= :triaging (:stage (workstream/read-ws :brian (:id w))))
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
        (tickets/complete! :brian slack-id :triaged :routed)
        (is (= [:ready] (map :stage (work/list-workstreams :brian)))
            "and triaging it afterwards reaches :ready — i.e. it is promotable again")))))

(deftest restore-is-a-no-op-without-a-workstream
  (with-tmp
    (fn [_]
      (is (= {:decision :no-workstream} (work/restore! :brian "nope"))))))

(deftest restore-clears-the-ticket-status-of-a-bare-watched-view-row
  ;; bare-row stamps :dismissed?, so a ticket dismissed via `bb nido:ticket:dismiss`
  ;; with no covering workstream lands in the band and renders Restore. That click
  ;; has to actually restore it — reporting "✓ Restored" while nothing happened is
  ;; the band lying about the one guarantee it exists to make. A bare row's
  ;; synthetic ws-id IS its Notion page-id, so the BR is recoverable from the
  ;; watched-view cache the row was synthesized from.
  (with-tmp
    (fn [_]
      (nido.coordinator.sources.state/write-state! "v1"
        {:type :notion-view :source-config {:project :brian}
         :pages {"pg-orphan" {:status "Needs verification" :priority nil :ball-ids #{}
                              :title "t" :br "BR-500"}}})
      (tickets/open! :brian "BR-500" {:title "t"})
      (tickets/dismiss! :brian "BR-500")
      (is (= [:dismissed] (map :stage (work/list-workstreams :brian)))
          "the orphan ticket is visible in the band")
      (is (= {:decision :restored} (work/resolve-gate! :brian "pg-orphan" :restore)))
      (is (nil? (tickets/status :brian "BR-500"))
          "status cleared → re-triable, which is the whole undo for a bare row")
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

(deftest gates-exclude-a-dismissed-row-with-a-parked-session
  (with-tmp
    (fn [_]
      ;; Seed the Notion page-facts cache so this row is notion-driven (page "pg"
      ;; in-cache with a non-terminal status). That's the branch the new filter is
      ;; load-bearing for: notion-stage-projection ignores the local ticket status
      ;; entirely for :needs-you, and engagement-state is fed nil instead of
      ;; :closed — so dismiss! (which only touches the ticket + :closed) leaves
      ;; :needs-you true and :engagement :parked-at-gate even after the workstream
      ;; is closed. Without seeding the cache the row takes the legacy path, whose
      ;; :closed → :done fold already drops :needs-you on its own, and the
      ;; assertion below would pass whether or not the new filter exists.
      (nido.coordinator.sources.state/write-state! "v1"
        {:type :notion-view :source-config {:project :brian}
         :pages {"pg" {:status "Needs verification" :priority nil :ball-ids #{}
                       :title "t" :br "BR-79"}}})
      (let [w (workstream/create! :brian
                {:stage :triage
                 :external-refs [{:adapter :notion :id "BR-79" :page-id "pg" :url "u"}]})]
        (tickets/open! :brian "BR-79" {:title "t"})
        (tickets/set-status! :brian "BR-79" :awaiting-input)
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy
                          :autonomy (assoc autonomy-running :phase :parked)})
        (is (= 1 (count (work/gates :brian))) "parked triage is a gate")
        (work/dismiss! :brian (:id w))
        (is (= [] (work/gates :brian))
            "dismiss removes it from Needs-you immediately, before the daemon
             sweep tears the session down")))))

;; ---------------------------------------------------------------------------
;; Bare watched-view rows — a Notion page with no workstream of its own.
;; Its synthetic ws-id IS the page-id (wsv/bare-row), so read-ws always misses.
;; ---------------------------------------------------------------------------

(defn- seed-page! [pages]
  (nido.coordinator.sources.state/write-state! "v1"
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

(deftest dismiss-a-bare-row-stamps-the-ticket-status
  (with-tmp
    (fn [_]
      ;; No workstream to close, so the ticket stamp IS the whole veto. bare-row
      ;; reads :dismissed? off ticket status, which moves the row to the
      ;; Dismissed band where Restore (already bare-aware) undoes it.
      (seed-page! {"pg-bare" {:status "Needs verification" :priority 1 :ball-ids #{}
                              :title "Move Licences" :br "BR-9"}})
      (is (= {:decision :dismissed} (work/dismiss! :brian "pg-bare")))
      (is (= :dismissed (tickets/status :brian "BR-9"))
          "ticket record created and stamped, though it never existed before"))))

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
      (is (nil? (tickets/status :brian "BR-9")) "status cleared — re-triable again"))))

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
  (is (= [:dismiss] (mapv :id (work/gate-actions :triage false nil {:bare? false})))
      "a real unparked triage row is unchanged")
  (is (= [:dismiss] (mapv :id (work/gate-actions :triage false nil)))
      "3-arity call sites keep their old behaviour")
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

;; Fix: bare-pane used to read bare-row's raw :stage instead of routing it
;; through to-spine (the fold both list-workstreams and work/workstream apply),
;; so a dismissed bare row's PANE still said :triage — offering Dismiss and
;; Start-triage again and no Restore — while the board LIST already said
;; :dismissed. Assert the two can never disagree, which is the invariant.
(deftest dismissed-bare-row-pane-agrees-with-the-board-list
  (with-tmp
    (fn [_]
      (seed-page! {"pg-bare" {:status "Needs verification" :priority 1 :ball-ids #{}
                              :title "Move Licences" :br "BR-9"}})
      (work/dismiss! :brian "pg-bare")
      (let [pane     (work/workstream :brian "pg-bare")
            list-row (first (filter #(= "pg-bare" (:ws-id %))
                                    (work/list-workstreams :brian)))]
        (is (true? (:bare? pane)))
        (is (= :dismissed (:stage pane))
            "the pane must show the same :dismissed the board list shows")
        (is (= (:stage list-row) (:stage pane))
            "pane and list must never disagree about stage")
        (is (= [:restore]
               (mapv :id (work/gate-actions (:stage pane) false nil {:bare? true})))
            "a dismissed bare pane offers Restore, not a re-hidden Dismiss/Start-triage")))))

(deftest start-triage-page-refuses-a-project-with-no-triage-trigger
  (with-tmp
    (fn [_]
      (with-redefs [nido.coordinator.triggers/load-for-project (fn [_] [])]
        (is (= {:decision :no-trigger} (work/start-triage-page! :brian "pg-bare")))))))

(deftest start-triage-page-force-spawns-the-triage-trigger
  (with-tmp
    (fn [_]
      (let [spawned (atom nil)]
        (with-redefs [nido.coordinator.triggers/load-for-project
                      (fn [_] [{:name :smoke :skill :investigate-bug}
                               {:name :triage-new :skill :triage-bug :priority 10
                                :session-profile :lite :uncapped? true}])
                      nido.notion.client/keychain-token (constantly "tok")
                      nido.coordinator.pickup/resolve-ref
                      (fn [_ input _] {:id "BR-9" :page-id input
                                       :url "https://notion.so/pg-bare" :title "Move Licences"})
                      nido.coordinator.spawn/ref-has-pending-session? (constantly false)
                      nido.coordinator.spawn/spawn-and-submit!
                      (fn [routed _] (reset! spawned routed))]
          (is (= {:decision :triaging} (work/start-triage-page! :brian "pg-bare")))
          (is (= :triage-new (-> @spawned :trigger :name))
              "picks the :triage-bug trigger, not the :investigate-bug one")
          (is (= "BR-9" (-> @spawned :payload :id)))
          (is (= "pg-bare" (-> @spawned :payload :page-id))
              "page-id rides the payload — the trigger template needs it")
          (is (= 10 (:priority @spawned)))
          (is (= :lite (:session-profile @spawned)))
          (is (true? (:uncapped? @spawned))))))))

(deftest start-triage-page-reports-an-unresolvable-page
  (with-tmp
    (fn [_]
      (with-redefs [nido.coordinator.triggers/load-for-project
                    (fn [_] [{:name :triage-new :skill :triage-bug}])
                    nido.notion.client/keychain-token (constantly "")
                    nido.coordinator.pickup/resolve-ref (fn [_ _ _] {:error :no-token})]
        (is (= {:decision :unresolved :error :no-token}
               (work/start-triage-page! :brian "pg-bare")))))))

(deftest start-triage-page-does-not-double-spawn
  (with-tmp
    (fn [_]
      (with-redefs [nido.coordinator.triggers/load-for-project
                    (fn [_] [{:name :triage-new :skill :triage-bug}])
                    nido.notion.client/keychain-token (constantly "tok")
                    nido.coordinator.pickup/resolve-ref
                    (fn [_ _ _] {:id "BR-9" :page-id "pg-bare" :url "u" :title "t"})
                    nido.coordinator.spawn/ref-has-pending-session? (constantly true)
                    nido.coordinator.spawn/spawn-and-submit!
                    (fn [_ _] (throw (ex-info "must not spawn" {})))]
        (is (= {:decision :already-in-flight}
               (work/start-triage-page! :brian "pg-bare"))
            "a second click while one is in flight is a no-op, not a duplicate")))))

(deftest resolve-gate-lets-start-triage-past-the-workstream-less-guard
  (with-tmp
    (fn [_]
      (with-redefs [nido.coordinator.triggers/load-for-project (fn [_] [])]
        (is (= {:decision :no-trigger}
               (work/resolve-gate! :brian "pg-bare" :start-triage))
            "reaches start-triage-page! rather than the guard's :no-workstream")))))
