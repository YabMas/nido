(ns nido.coordinator.workstreams-view-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.session :as session]
   [nido.coordinator.state :as cstate]
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
