(ns nido.coordinator.workstreams-view-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.coordinator.session :as session]
   [nido.coordinator.sources.state]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.tickets :as tickets]
   [nido.coordinator.workstream :as workstream]
   [nido.coordinator.workstreams-view :as wsv]))

;; ---------------------------------------------------------------------------
;; Fixtures — write real workstream.edn / session.edn under a tmp nido-root
;; ---------------------------------------------------------------------------

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(defn- make-ws!
  "Create a workstream via the real writer, then apply overrides and re-write
   so tests can set :external-refs / :closed / :entries / :stage-history."
  [project overrides]
  (let [w (workstream/create! project {:stage (:stage overrides :triaging)})]
    (workstream/write! (merge w overrides {:id (:id w) :project project}))))

(defn- make-session!
  "Create a session under a workstream, then apply overrides (substrate, autonomy)."
  [project ws-id sname overrides]
  (let [s (session/create! project ws-id
                           {:name sname :weight (:weight overrides :light)
                            :autonomy (:autonomy overrides)})]
    (session/write! (merge s (dissoc overrides :weight)))))

(def ^:private autonomy-running
  {:skill :triage-bug :first-message "x" :agent :claude :claude-session-id nil
   :trigger :triage-bug :limits {} :priority 0 :uncapped? false :on-promote nil
   :phase :running :phase-history [{:at "2026-06-01T00:00:00Z" :phase :running}]
   :error nil})

;; ---------------------------------------------------------------------------
;; Label fallback chain
;; ---------------------------------------------------------------------------

(deftest label-prefers-notion-ref
  (let [ws {:id "ws-20260601-aaaaaa"
            :external-refs [{:adapter :notion :id "BR-1421" :title "Fix drift"}]
            :entries []}]
    (is (= "BR-1421 · Fix drift" (wsv/label ws [])))))

(deftest label-notion-ref-without-title-uses-id
  (let [ws {:id "ws-20260601-aaaaaa"
            :external-refs [{:adapter :notion :id "BR-1421"}]
            :entries []}]
    (is (= "BR-1421" (wsv/label ws [])))))

(deftest label-uses-slack-message-text
  ;; A slack workstream is labelled by the message text (the slack id is noise).
  (let [ws {:id "ws-20260601-aaaaaa"
            :external-refs [{:adapter :slack-message :id "slack-C1-1781.0" :title "login button broken"}]
            :entries []}]
    (is (= "login button broken" (wsv/label ws [])))))

(deftest label-slack-without-title-falls-back-to-ws-id
  (let [ws {:id "ws-20260601-aaaaaa"
            :external-refs [{:adapter :slack-message :id "slack-C1-1781.0"}]
            :entries []}]
    (is (= "ws-20260601-aaaaaa" (wsv/label ws [])))))

(deftest label-uses-github-issue-title
  ;; A github-issue workstream is labelled by the issue title, not the raw ws-id.
  (let [ws {:id "ws-20260615-20faf3"
            :external-refs [{:adapter :github-issue :id "brian#123" :title "Fix flaky login redirect"}]
            :entries []}]
    (is (= "Fix flaky login redirect" (wsv/label ws [])))))

(deftest label-github-issue-without-title-falls-back-to-repo-number
  (let [ws {:id "ws-20260615-20faf3"
            :external-refs [{:adapter :github-issue :id "brian#77"}]
            :entries []}]
    (is (= "brian#77" (wsv/label ws [])))))

(deftest label-falls-back-to-latest-entry-title
  (let [ws {:id "ws-20260601-aaaaaa"
            :external-refs []
            :entries [{:kind :triage :title "First"} {:kind :plan :title "Latest"}]}]
    (is (= "Latest" (wsv/label ws [])))))

(deftest label-falls-back-to-trigger-and-suffix
  (let [ws {:id "ws-20260601-abcdef" :external-refs [] :entries []}
        sessions [{:autonomy {:trigger :triage-bug}}]]
    (is (= "triage-bug · abcdef" (wsv/label ws sessions)))))

(deftest label-falls-back-to-session-name-before-raw-id
  ;; A human scratch workstream has no ref/entry/trigger — its session name is
  ;; far more legible than the raw ws-id.
  (let [ws {:id "ws-20260613-7aeaed" :external-refs [] :entries []}
        sessions [{:name "text2speech-latex" :autonomy nil}]]
    (is (= "text2speech-latex" (wsv/label ws sessions)))))

(deftest label-trigger-still-beats-session-name
  (let [ws {:id "ws-20260601-abcdef" :external-refs [] :entries []}
        sessions [{:name "run-a" :autonomy {:trigger :triage-bug}}]]
    (is (= "triage-bug · abcdef" (wsv/label ws sessions)))))

(deftest label-falls-back-to-raw-id
  (let [ws {:id "ws-20260601-abcdef" :external-refs [] :entries []}]
    (is (= "ws-20260601-abcdef" (wsv/label ws [])))))

;; ---------------------------------------------------------------------------
;; last-activity — max ISO timestamp across stage-history + session histories
;; ---------------------------------------------------------------------------

(deftest last-activity-takes-max-across-sources
  (let [ws {:stage-history [{:at "2026-06-01T00:00:00Z" :stage :investigating}]}
        sessions [{:created-at "2026-06-02T00:00:00Z"
                   :substrate-history [{:at "2026-06-02T00:00:00Z" :substrate :live}]
                   :autonomy {:phase-history [{:at "2026-06-05T09:00:00Z" :phase :running}]}}]]
    (is (= "2026-06-05T09:00:00Z" (wsv/last-activity ws sessions)))))

(deftest last-activity-handles-no-sessions
  (let [ws {:stage-history [{:at "2026-06-01T00:00:00Z" :stage :investigating}]}]
    (is (= "2026-06-01T00:00:00Z" (wsv/last-activity ws [])))))

;; ---------------------------------------------------------------------------
;; workstream-rows — assembled from disk
;; ---------------------------------------------------------------------------

(deftest workstream-rows-projects-engagement-and-counts
  (with-tmp
    (fn [_]
      ;; idle: open workstream, no sessions
      (make-ws! :brian {:stage :triaging})
      ;; active: one live autonomous session, running
      (let [w (make-ws! :brian {:stage :triaging
                                :external-refs [{:adapter :notion :id "BR-1" :title "Active one"}]})]
        (make-session! :brian (:id w) "run-a" {:autonomy autonomy-running}))
      (let [rows (wsv/workstream-rows :brian)
            by-label (into {} (map (juxt :label identity)) rows)]
        (is (= 2 (count rows)))
        (is (= :active (:engagement (by-label "BR-1 · Active one"))))
        (is (= 1 (:session-count (by-label "BR-1 · Active one"))))
        (is (some #(= :idle (:engagement %)) rows))
        (is (every? :ws-id rows))
        (is (every? #(= :brian (:project %)) rows))))))

(deftest human-engagement-reflects-real-liveness-via-live-names
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :scratch :external-refs []})]
        (session/create! :brian (:id w) {:name "refshot" :weight :light :autonomy nil})
        (let [ws (workstream/read-ws :brian (:id w))]
          (is (= :active (:engagement (wsv/workstream-row :brian ws)))
              "default arity = legacy behavior (static :live ⇒ :active)")
          (is (= :idle (:engagement (wsv/workstream-row :brian ws #{})))
              "human session not in the live-set ⇒ downgraded to :idle")
          (is (= :active (:engagement (wsv/workstream-row :brian ws #{"refshot"})))
              "human session in the live-set ⇒ stays :active"))))))

(deftest autonomous-engagement-ignores-live-names
  (with-tmp
    (fn [_]
      (let [w (make-ws! :brian {:stage :triaging
                                :external-refs [{:adapter :notion :id "BR-1"}]})]
        (make-session! :brian (:id w) "run-a" {:autonomy autonomy-running})
        (is (= :active (:engagement (wsv/workstream-row :brian (workstream/read-ws :brian (:id w)) #{})))
            "autonomous (coordinator-managed) sessions aren't downgraded by the live-set")))))

(deftest workstream-rows-threads-live-names
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :scratch :external-refs []})]
        (session/create! :brian (:id w) {:name "downie" :weight :light :autonomy nil}))
      (is (= [:idle]   (map :engagement (wsv/workstream-rows :brian #{}))))
      (is (= [:active] (map :engagement (wsv/workstream-rows :brian #{"downie"})))))))

(deftest workstream-rows-marks-closed-as-settled
  (with-tmp
    (fn [_]
      (let [w (make-ws! :brian {:stage :triaging
                                :closed {:at "2026-06-06T00:00:00Z" :outcome :done}})]
        (make-session! :brian (:id w) "run-a" {:autonomy autonomy-running}))
      (let [rows (wsv/workstream-rows :brian)]
        (is (= [:settled] (map :engagement rows)))))))

(deftest workstream-rows-carries-max-session-priority
  (with-tmp
    (fn [_]
      (let [w (make-ws! :brian {:stage :triaging})]
        (make-session! :brian (:id w) "lo" {:autonomy (assoc autonomy-running :priority 3)})
        (make-session! :brian (:id w) "hi" {:autonomy (assoc autonomy-running :priority 7)}))
      (is (= 7 (:priority (first (wsv/workstream-rows :brian))))
          "row severity = highest autonomy :priority across its sessions"))))

(deftest workstream-rows-parked-beats-active
  (with-tmp
    (fn [_]
      (let [w (make-ws! :brian {:stage :triaging})]
        (make-session! :brian (:id w) "run-parked"
                       {:autonomy (assoc autonomy-running :phase :parked)}))
      (is (= [:parked-at-gate] (map :engagement (wsv/workstream-rows :brian)))))))


;; ---------------------------------------------------------------------------
;; grouped-by-stage + workstream-rows stage projection
;; ---------------------------------------------------------------------------

(deftest workstream-rows-projects-stage-and-needs-you
  (with-tmp
    (fn [_]
      (let [w (make-ws! :brian {:external-refs [{:adapter :notion :id "BR-9" :title "Ready one"}]
                                :stage :triaging})]
        (tickets/open! :brian "BR-9" {:title "Ready one"})
        (tickets/set-status! :brian "BR-9" :triaged)
        (make-session! :brian (:id w) "s1" {:substrate :archived
                                            :autonomy (assoc autonomy-running :phase :done)})
        (let [r (first (wsv/workstream-rows :brian))]
          (is (= :ready (:stage r)))
          (is (false? (:needs-you r)) ":ready is a pull queue, not a needs-you gate")
          (is (= "BR-9" (:br-id r)) "row carries the ticket id for the promote shortcut"))))))

(deftest grouped-by-stage-splits-triage-and-drops-done-and-ready
  (let [rows [{:ws-id "r1" :stage :ready       :needs-you true  :last-activity "2026-06-01T00:00:00Z"}
              ;; triage in-flight: parked (high prio) + active (low prio)
              {:ws-id "t-parked" :stage :triage :engagement :parked-at-gate :priority 5 :last-activity "2026-06-03T00:00:00Z"}
              {:ws-id "t-active" :stage :triage :engagement :active         :priority 9 :last-activity "2026-06-02T00:00:00Z"}
              ;; triage backlog: two queued, different severities
              {:ws-id "t-q-lo"   :stage :triage :engagement :queued :priority 1 :last-activity "2026-06-04T00:00:00Z"}
              {:ws-id "t-q-hi"   :stage :triage :engagement :queued :priority 8 :last-activity "2026-06-05T00:00:00Z"}
              {:ws-id "p1" :stage :in-progress :needs-you false :last-activity "2026-06-01T00:00:00Z"}
              {:ws-id "d1" :stage :done        :needs-you false :last-activity "2026-06-09T00:00:00Z"}]
        g (wsv/grouped-by-stage rows)]
    (is (not (contains? g :ready)) "no :ready band — backlog is Notion's")
    (is (= ["p1"] (map :ws-id (:in-progress g))))
    (is (= ["t-active" "t-parked"]
           (map :ws-id (get-in g [:triage :in-flight])))
        "in-flight is parked+active, highest-severity first")
    (is (= ["t-q-hi" "t-q-lo"]
           (map :ws-id (get-in g [:triage :queued])))
        "queued backlog separated, highest-severity first")
    (is (nil? (:done g)) "done is dropped")))

(deftest triage-severity-ties-break-by-longest-waiting
  (let [rows [{:ws-id "old" :stage :triage :engagement :parked-at-gate :priority 5 :last-activity "2026-06-01T00:00:00Z"}
              {:ws-id "new" :stage :triage :engagement :parked-at-gate :priority 5 :last-activity "2026-06-09T00:00:00Z"}]
        in-flight (get-in (wsv/grouped-by-stage rows) [:triage :in-flight])]
    (is (= ["old" "new"] (map :ws-id in-flight))
        "equal severity → longest-waiting (oldest activity) at top")))

(deftest format-row-marks-needs-you-and-substatus
  (is (= "⏸ BR-1 · x   parked"
         (wsv/format-row {:label "BR-1 · x" :needs-you true :engagement :parked-at-gate})))
  (is (= "  BR-2 · y   queued"
         (wsv/format-row {:label "BR-2 · y" :needs-you false :engagement :queued}))))

(deftest promote-result-message-reads-the-decision
  (is (= "promoted BR-7 → in progress"           (wsv/promote-result-message "BR-7" :promote)))
  (is (= "BR-7 already promoted"                  (wsv/promote-result-message "BR-7" :skip-active)))
  (is (= "BR-7 isn't triaged yet — not ready to pick up"
         (wsv/promote-result-message "BR-7" :skip-untriaged)))
  (is (= "nothing to promote on this workstream" (wsv/promote-result-message nil :promote))))

;; ---------------------------------------------------------------------------
;; session-rows (workstream detail) + formatting
;; ---------------------------------------------------------------------------

(deftest session-rows-reports-phase-weight-substrate
  (with-tmp
    (fn [_]
      (let [w (make-ws! :brian {:stage :triaging})]
        (make-session! :brian (:id w) "run-auto"
                       {:weight :heavy :autonomy autonomy-running})
        (make-session! :brian (:id w) "human-sess" {:autonomy nil})
        (let [rows  (wsv/session-rows :brian (:id w))
              by    (into {} (map (juxt :name identity)) rows)]
          (is (= 2 (count rows)))
          (is (= :running (:phase (by "run-auto"))))
          (is (= :heavy   (:weight (by "run-auto"))))
          (is (= :live    (:substrate (by "run-auto"))))
          (is (nil?       (:phase (by "human-sess")))))))))

(deftest format-session-row-human-vs-autonomous
  (is (= "run-auto  ·  running  ·  heavy  ·  live"
         (wsv/format-session-row {:name "run-auto" :phase :running :weight :heavy :substrate :live})))
  (is (= "human-sess  ·  human  ·  light  ·  live"
         (wsv/format-session-row {:name "human-sess" :phase nil :weight :light :substrate :live}))))

;; ---------------------------------------------------------------------------
;; ws-source + workstream-row :source + grouped-by-engagement (Phase 2)
;; ---------------------------------------------------------------------------

(deftest ws-source-classifies-from-the-raw-record
  (is (= :scratch (wsv/ws-source {:stage :scratch :external-refs []})))
  (is (= :notion  (wsv/ws-source {:stage :triaging
                                  :external-refs [{:adapter :notion :id "BR-1"}]})))
  (is (= :notion  (wsv/ws-source {:stage :triaging :external-refs []})))
  (is (= :github  (wsv/ws-source {:stage :ready
                                  :external-refs [{:adapter :github-issue :id "42"}]})))
  (is (= :slack   (wsv/ws-source {:stage :triaging
                                  :external-refs [{:adapter :slack-message :id "slack-C1-1.0"}]}))))

(deftest workstream-row-carries-source
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :scratch :external-refs []})]
        (session/create! :brian (:id w) {:name "refshot" :weight :light :autonomy nil})
        (is (= :scratch (:source (wsv/workstream-row :brian (workstream/read-ws :brian (:id w))))))))))

(deftest workstream-row-resolves-slack-ledger-key-and-is-not-promotable
  ;; A slack workstream's ledger is keyed by the slack ref id, not a Notion BR.
  ;; The row must resolve :br-id from the slack ref (so triage status is read)
  ;; and expose no :promote-id (slack workstreams aren't promotable).
  (with-tmp
    (fn [_]
      (let [slack-id "slack-C1-1781.000123"
            w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :slack-message
                                                           :id slack-id :title "boom"}]})]
        (tickets/open! :brian slack-id {:title "boom"})
        (tickets/set-status! :brian slack-id :awaiting-input)
        (let [row (wsv/workstream-row :brian (workstream/read-ws :brian (:id w)))]
          (is (= :slack (:source row)))
          (is (= slack-id (:br-id row)) "ledger key resolves from the slack ref")
          (is (nil? (:promote-id row)) "slack workstreams are not promotable")
          (is (= "boom" (:label row)) "labelled by the slack message text"))))))

(deftest grouped-by-engagement-splits-active-and-idle
  (let [rows [{:engagement :active :label "a"}
              {:engagement :parked-at-gate :label "b"}
              {:engagement :idle :label "c"}
              {:engagement :queued :label "d"}]
        g (wsv/grouped-by-engagement rows)]
    (is (= #{"a" "b" "d"} (set (map :label (:active g)))))
    (is (= #{"c"} (set (map :label (:idle g)))))))

(deftest promote-result-message-covers-github-and-source-decisions
  ;; GitHub uses an issue ref id (e.g. "o/r#42") in place of a BR-####:
  (is (re-find #"in progress" (wsv/promote-result-message "o/r#42" :promote)))
  (is (= "o/r#42 already promoted" (wsv/promote-result-message "o/r#42" :skip-active)))
  (is (re-find #"couldn't reach GitHub" (wsv/promote-result-message "o/r#42" :gh-error)))
  ;; scratch / ref-less workstream (no promote id):
  (is (re-find #"nothing to promote" (wsv/promote-result-message nil :skip-not-promotable))))

(deftest grouped-by-stage-buckets-incoming
  (let [rows [{:stage :incoming :needs-you true :last-activity "2026-06-02T00:00:00Z"}
              {:stage :incoming :needs-you true :last-activity "2026-06-03T00:00:00Z"}
              {:stage :ready :needs-you true :last-activity "2026-06-01T00:00:00Z"}]
        g    (wsv/grouped-by-stage rows)]
    (is (= 2 (count (:incoming g))))
    ;; newest-first within the incoming band
    (is (= "2026-06-03T00:00:00Z" (-> g :incoming first :last-activity)))))

(deftest promote-result-message-inbox-decisions
  (is (= "started triage" (wsv/promote-result-message nil :triaging)))
  (is (= "already picked up — not in the queue anymore"
         (wsv/promote-result-message nil :skip-not-inbox)))
  (is (= "can't start triage — its trigger is gone from triggers.edn"
         (wsv/promote-result-message nil :skip-no-trigger)))
  ;; existing behavior preserved
  (is (= "nothing to promote on this workstream"
         (wsv/promote-result-message nil :skip-not-promotable))))

;; ---------------------------------------------------------------------------
;; workstream-row :ship-substate — populated only for :shipping workstreams
;; ---------------------------------------------------------------------------

(def ^:private autonomy-parked
  (assoc autonomy-running :phase :parked
         :phase-history [{:at "2026-06-01T00:00:00Z" :phase :parked}]))

(deftest workstream-row-shipping-with-parked-session-has-blocked-substate
  (with-tmp
    (fn [_]
      (let [w (make-ws! :brian {:stage :shipping})]
        (make-session! :brian (:id w) "ship-auto" {:autonomy autonomy-parked})
        (let [row (wsv/workstream-row :brian (workstream/read-ws :brian (:id w)))]
          (is (= :shipping (:stage row)) "stage is projected from the stored :shipping override")
          (is (= :blocked (:ship-substate row)) "parked autonomous session → :blocked"))))))

(deftest workstream-row-non-shipping-has-nil-substate
  (with-tmp
    (fn [_]
      (let [w (make-ws! :brian {:stage :triaging})]
        (make-session! :brian (:id w) "run-a" {:autonomy autonomy-running})
        (let [row (wsv/workstream-row :brian (workstream/read-ws :brian (:id w)))]
          (is (nil? (:ship-substate row)) ":ship-substate is nil for non-shipping workstreams"))))))

(deftest by-notion-priority-orders-lowest-rank-first
  (let [rows [{:ws-id "a" :notion-priority 2 :priority 0 :last-activity "t"}
              {:ws-id "b" :notion-priority nil :priority 0 :last-activity "t"}
              {:ws-id "c" :notion-priority 0 :priority 0 :last-activity "t"}
              {:ws-id "d" :notion-priority 1 :priority 0 :last-activity "t"}]]
    (is (= ["c" "d" "a" "b"]
           (mapv :ws-id (#'wsv/by-notion-priority rows)))
        "0 first, nils last")))

(deftest workstream-row-stamps-notion-priority-from-facts
  (with-tmp
    (fn [_tmp]
      (let [w     (make-ws! :brian {:external-refs [{:adapter :notion :id "BR-1"
                                                     :page-id "pg-1"}]})
            facts {"pg-1" {:status "Not started" :priority 2 :ball-ids #{}}}
            row   (wsv/workstream-row :brian w nil facts)]
        (is (= 2 (:notion-priority row)))))))

(deftest grouped-by-stage-includes-shipping-band
  (let [rows [{:ws-id "s1" :stage :shipping :needs-you true  :last-activity "2026-06-05T00:00:00Z"}
              {:ws-id "s2" :stage :shipping :needs-you false :last-activity "2026-06-04T00:00:00Z"}
              {:ws-id "r1" :stage :ready    :needs-you true  :last-activity "2026-06-01T00:00:00Z"}]
        g (wsv/grouped-by-stage rows)]
    (is (= ["s1" "s2"] (map :ws-id (:shipping g))) "shipping rows collected; needs-you first")
    (is (not (contains? g :ready)) "no :ready band — backlog is Notion's")))

;; ---------------------------------------------------------------------------
;; Notion-driven rows — stage/engagement derive from the Notion cache, not
;; nido's stored :closed/:stage, when the row is Notion-backed AND either the
;; page is cached or nido stored an open :shipping stage.
;; ---------------------------------------------------------------------------

(defn- notion-ws! [project overrides]
  (make-ws! project (merge {:external-refs [{:adapter :notion :id "BR-1" :page-id "pg-1"}]}
                           overrides)))

(deftest notion-driven-stale-close-reappears
  (with-tmp
    (fn [_]
      ;; nido closed it, but Notion says In progress and the page is cached → reappears.
      (let [w     (notion-ws! :brian {:closed {:at "t" :outcome :done}})
            facts {"pg-1" {:status "In progress" :priority nil :ball-ids #{}}}
            row   (wsv/workstream-row :brian w nil facts)]
        (is (= :in-progress (:stage row)) "Notion status drives, nido close ignored")
        (is (not= :settled (:engagement row)) "engagement not settled → to-spine won't fold to :done")))))

(deftest notion-driven-dismissed-reappears
  (with-tmp
    (fn [_]
      (tickets/write-meta! :brian "BR-1"
        {:br-id "BR-1" :status :dismissed :entries [{:kind :triage :seq 1}]})
      (let [w     (notion-ws! :brian {})
            facts {"pg-1" {:status "Not started" :priority nil :ball-ids #{}}}
            row   (wsv/workstream-row :brian w nil facts)]
        ;; Reappears (not hidden as :done), as :triage — it has an OLD report but
        ;; status isn't :triaged, so it is NOT promotable; :ready would show a dead
        ;; Promote button (promote accepts only :triaged).
        (is (= :triage (:stage row)) "dismissed reappears as :triage, not :ready")))))

(deftest notion-driven-triaged-status-is-ready
  (with-tmp
    (fn [_]
      ;; :triaged (the promotable disposition) + non-terminal Notion → :ready.
      (tickets/write-meta! :brian "BR-1"
        {:br-id "BR-1" :status :triaged :entries [{:kind :triage :seq 1}]})
      (let [w   (notion-ws! :brian {})
            row (wsv/workstream-row :brian w nil {"pg-1" {:status "Not started" :priority nil :ball-ids #{}}})]
        (is (= :ready (:stage row)) "status :triaged → :ready (promotable)")))))

(deftest notion-driven-reopened-with-old-report-is-triage
  (with-tmp
    (fn [_]
      ;; The BR-4826 case: has an OLD :triage entry but was re-opened to :investigating.
      (tickets/write-meta! :brian "BR-1"
        {:br-id "BR-1" :status :investigating :entries [{:kind :triage :seq 1}]})
      (let [w   (notion-ws! :brian {})
            row (wsv/workstream-row :brian w nil {"pg-1" {:status "Not started" :priority nil :ball-ids #{}}})]
        (is (= :triage (:stage row)) "old report but :investigating → :triage, not :ready")))))

(deftest notion-driven-terminal-status-is-done
  (with-tmp
    (fn [_]
      (let [w   (notion-ws! :brian {})
            row (wsv/workstream-row :brian w nil {"pg-1" {:status "Done" :priority nil :ball-ids #{}}})]
        (is (= :done (:stage row)))))))

(deftest notion-uncached-uses-legacy-path
  (with-tmp
    (fn [_]
      ;; Notion-backed but page NOT in the cache → legacy: closed → :done, no crash.
      (let [w   (notion-ws! :brian {:closed {:at "t" :outcome :done}})
            row (wsv/workstream-row :brian w nil {})]  ; empty cache
        (is (= :done (:stage row)))
        (is (= :settled (:engagement row)) "legacy engagement still reads :closed")))))

;; ---------------------------------------------------------------------------
;; workstream-rows — bare rows for uncovered watched-view pages
;; ---------------------------------------------------------------------------

(deftest workstream-rows-materializes-bare-rows-for-uncovered-pages
  (with-tmp
    (fn [_]
      ;; Cache has two pages; only pg-cov is covered by a workstream.
      (nido.coordinator.sources.state/write-state! "v1"
        {:type :notion-view :source-config {:project :brian}
         :pages {"pg-cov"  {:status "Not started" :priority 2 :ball-ids #{} :title "Covered" :br "BR-1"}
                 "pg-bare" {:status "Not started" :priority 1 :ball-ids #{} :title "Orphan" :br "BR-9"}}})
      (make-ws! :brian {:external-refs [{:adapter :notion :id "BR-1" :page-id "pg-cov"}]})
      (let [rows (wsv/workstream-rows :brian nil)
            bare (first (filter :bare? rows))]
        (is (= 1 (count (filter :bare? rows))) "exactly one bare row (pg-bare)")
        (is (= "pg-bare" (:ws-id bare)) "bare ws-id is the page-id")
        (is (= "BR-9" (:br-id bare)))
        (is (= "Orphan" (:label bare)))
        (is (= :notion (:source bare)))
        (is (= :triage (:stage bare)) "untriaged Not-started → triage")
        (is (= 1 (:notion-priority bare)))
        (is (false? (:needs-you bare)))
        (is (nil? (first (filter #(= "pg-cov" (:ws-id %)) rows)))
            "covered page does NOT get a bare row (real ws row instead)")))))

(deftest notion-driven-in-flight-promote-shows-in-progress
  (with-tmp
    (fn [_]
      ;; Promoted ticket: local status :implementing + live impl work, but the Notion
      ;; cache still lags at "Not started". The board must show :in-progress — nido's
      ;; in-flight work is ahead of the eventually-consistent cache. (Bug was :ready.)
      (tickets/write-meta! :brian "BR-1"
        {:br-id "BR-1" :status :implementing :entries [{:kind :triage :seq 1}]})
      (let [w   (notion-ws! :brian {})
            row (wsv/workstream-row :brian w nil {"pg-1" {:status "Not started" :priority nil :ball-ids #{}}})]
        (is (= :in-progress (:stage row))
            "local :implementing overrides the stale Notion 'Not started'")))))

(deftest notion-driven-in-flight-yields-to-terminal-notion
  (with-tmp
    (fn [_]
      ;; local :implementing but Notion is Done → terminal wins (really finished, off board).
      (tickets/write-meta! :brian "BR-1"
        {:br-id "BR-1" :status :implementing :entries [{:kind :triage :seq 1}]})
      (let [w   (notion-ws! :brian {})
            row (wsv/workstream-row :brian w nil {"pg-1" {:status "Done" :priority nil :ball-ids #{}}})]
        (is (= :done (:stage row))
            "terminal Notion status wins over the in-flight overlay")))))

(deftest grouped-by-stage-omits-ready-band
  (let [rows [{:stage :ready :last-activity "t" :needs-you false}
              {:stage :triage :engagement :idle :last-activity "t" :needs-you false}
              {:stage :in-progress :last-activity "t" :needs-you false}]
        g    (wsv/grouped-by-stage rows)]
    (is (not (contains? g :ready)) "no :ready band — backlog is Notion's")
    (is (contains? g :triage))
    (is (contains? g :in-progress))))

;; ---------------------------------------------------------------------------
;; :dismissed? — the local dismiss veto carried onto every row
;; ---------------------------------------------------------------------------

(deftest workstream-row-flags-a-dismissed-ticket
  (with-tmp
    (fn [_]
      (let [w (make-ws! :brian {:external-refs [{:adapter :notion :id "BR-1"
                                                 :page-id "pg-1" :url "u"}]})]
        (tickets/open! :brian "BR-1" {:title "t"})
        (tickets/dismiss! :brian "BR-1")
        (is (true? (:dismissed? (wsv/workstream-row :brian w nil
                                                    {"pg-1" {:status "Needs verification"}})))
            "a :dismissed ticket flags its row")))))

(deftest workstream-row-not-dismissed-by-default
  (with-tmp
    (fn [_]
      (let [w (make-ws! :brian {:external-refs [{:adapter :notion :id "BR-2"
                                                 :page-id "pg-2" :url "u"}]})]
        (tickets/open! :brian "BR-2" {:title "t"})
        (tickets/set-status! :brian "BR-2" :awaiting-input)
        (is (false? (:dismissed? (wsv/workstream-row :brian w nil
                                                     {"pg-2" {:status "Needs verification"}}))))))))

(deftest bare-row-flags-a-dismissed-ticket
  (with-tmp
    (fn [_]
      (tickets/open! :brian "BR-3" {:title "t"})
      (tickets/dismiss! :brian "BR-3")
      (is (true? (:dismissed? (wsv/bare-row :brian "pg-3"
                                            {:status "Needs verification" :br "BR-3"})))
          "a CLI-dismissed orphan ticket stays hidden")
      (is (false? (:dismissed? (wsv/bare-row :brian "pg-4"
                                             {:status "Needs verification" :br nil})))
          "no ledger ref → not dismissed"))))

(deftest grouped-by-stage-collects-a-dismissed-band
  (let [rows [{:ws-id "a" :stage :triage     :needs-you false :last-activity "2026-01-01"}
              {:ws-id "d1" :stage :dismissed :needs-you false :last-activity "2026-01-01"}
              {:ws-id "d2" :stage :dismissed :needs-you false :last-activity "2026-03-01"}]
        g    (wsv/grouped-by-stage rows)]
    (is (= ["d2" "d1"] (map :ws-id (:dismissed g)))
        "newest-activity first")
    (is (= ["a"] (map :ws-id (get-in g [:triage :queued])))
        "dismissed rows are not in the triage band")))


;; ---------------------------------------------------------------------------
;; ref-links — followable external refs
;; ---------------------------------------------------------------------------

(deftest ref-links-orders-adapters-and-labels-them
  (let [ws {:external-refs
            [{:adapter :slack-message :id "slack-C1-1.2" :url "https://slack.example/x"}
             {:adapter :github :id "o/r#1" :url "https://github.com/o/r/pull/1"}
             {:adapter :notion :id "BR-7" :title "Fix drift"
              :url "https://app.notion.com/p/Fix-drift-abc"}
             {:adapter :github-issue :id "o/r#2" :url "https://github.com/o/r/issues/2"}]}]
    (is (= [["notion" "BR-7"]
            ["GitHub issue" "o/r#2"]
            ["PR" "o/r#1"]
            ["slack" "slack-C1-1.2"]]
           (map (juxt :label :id) (wsv/ref-links ws))))))

(deftest ref-links-falls-back-to-page-id-for-a-urlless-notion-ref
  (let [ws {:external-refs [{:adapter :notion :id "BR-7"
                             :page-id "31efca9f-403c-80a3-9401-c534aaaabbbb"}]}]
    (is (= ["https://www.notion.so/31efca9f403c80a39401c534aaaabbbb"]
           (map :url (wsv/ref-links ws))))))

(deftest ref-links-drops-a-ref-with-nothing-to-follow
  (let [ws {:external-refs [{:adapter :notion :id "BR-7"}
                            {:adapter :github :id "o/r#1"
                             :url "https://github.com/o/r/pull/1"}]}]
    (is (= ["o/r#1"] (map :id (wsv/ref-links ws))))))

(deftest ref-links-empty-when-there-is-nothing-to-project
  (is (= [] (wsv/ref-links {:external-refs []})))
  (is (= [] (wsv/ref-links nil))))

(deftest ref-links-labels-an-unknown-adapter-by-its-name
  (let [ws {:external-refs [{:adapter :linear :id "LIN-1" :url "https://linear.example/1"}]}]
    (is (= ["linear"] (map :label (wsv/ref-links ws))))))

(deftest workstream-row-carries-ref-links
  (with-tmp
    (fn [_]
      (let [w   (make-ws! :brian {:external-refs
                                  [{:adapter :notion :id "BR-7"
                                    :url "https://app.notion.com/p/x"}]})
            row (wsv/workstream-row :brian (workstream/read-ws :brian (:id w)))]
        (is (= ["https://app.notion.com/p/x"] (map :url (:links row))))))))

(deftest bare-row-links-to-its-notion-page
  (with-tmp
    (fn [_]
      (let [row (wsv/bare-row :brian "31efca9f-403c-80a3-9401-c534aaaabbbb"
                              {:status "Not started" :priority 1
                               :title "Orphan ticket" :br "BR-9"})]
        (is (= ["https://www.notion.so/31efca9f403c80a39401c534aaaabbbb"]
               (map :url (:links row))))
        (is (= ["BR-9"] (map :id (:links row))))))))
