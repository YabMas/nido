(ns nido.coordinator.workstreams-view-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
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

(deftest workstream-rows-marks-closed-as-settled
  (with-tmp
    (fn [_]
      (let [w (make-ws! :brian {:stage :implementing
                                :closed {:at "2026-06-06T00:00:00Z" :outcome :done}})]
        (make-session! :brian (:id w) "run-a" {:autonomy autonomy-running}))
      (let [rows (wsv/workstream-rows :brian)]
        (is (= [:settled] (map :engagement rows)))))))

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
          (is (true? (:needs-you r))))))))

(deftest grouped-by-stage-orders-needs-you-first-and-drops-done
  (let [rows [{:ws-id "r1" :stage :ready       :needs-you true  :last-activity "2026-06-01T00:00:00Z"}
              {:ws-id "t1" :stage :triage      :needs-you false :last-activity "2026-06-03T00:00:00Z"}
              {:ws-id "t2" :stage :triage      :needs-you true  :last-activity "2026-06-02T00:00:00Z"}
              {:ws-id "p1" :stage :in-progress :needs-you false :last-activity "2026-06-01T00:00:00Z"}
              {:ws-id "d1" :stage :done        :needs-you false :last-activity "2026-06-09T00:00:00Z"}]
        g (wsv/grouped-by-stage rows)]
    (is (= ["r1"] (map :ws-id (:ready g))))
    (is (= ["p1"] (map :ws-id (:in-progress g))))
    (is (= ["t2" "t1"] (map :ws-id (:triage g))) "needs-you first, then newest")
    (is (nil? (:done g)) "done is dropped")))

(deftest format-row-marks-needs-you-and-substatus
  (is (= "⏸ BR-1 · x   parked"
         (wsv/format-row {:label "BR-1 · x" :needs-you true :engagement :parked-at-gate})))
  (is (= "  BR-2 · y   queued"
         (wsv/format-row {:label "BR-2 · y" :needs-you false :engagement :queued}))))

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

