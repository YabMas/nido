(ns nido.coordinator.workstreams-view-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.session :as session]
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
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(defn- make-ws!
  "Create a workstream via the real writer, then apply overrides and re-write
   so tests can set :external-refs / :closed / :entries / :stage-history."
  [project overrides]
  (let [w (workstream/create! project {:stage (:stage overrides :investigating)})]
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
      (make-ws! :brian {:stage :triaged})
      ;; active: one live autonomous session, running
      (let [w (make-ws! :brian {:stage :implementing
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
      (let [w (make-ws! :brian {:stage :implementing
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
      (let [w (make-ws! :brian {:stage :implementing
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
      (let [w (make-ws! :brian {:stage :planning})]
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
          (is (true? (:needs-you r)))
          (is (= "BR-9" (:br-id r)) "row carries the ticket id for the promote shortcut"))))))

(deftest grouped-by-stage-splits-triage-and-drops-done
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
    (is (= ["r1"] (map :ws-id (:ready g))))
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
      (let [w (make-ws! :brian {:stage :implementing})]
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
                                  :external-refs [{:adapter :github-issue :id "42"}]}))))

(deftest workstream-row-carries-source
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :scratch :external-refs []})]
        (session/create! :brian (:id w) {:name "refshot" :weight :light :autonomy nil})
        (is (= :scratch (:source (wsv/workstream-row :brian (workstream/read-ws :brian (:id w))))))))))

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

