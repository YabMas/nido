(ns nido.ui.server-operations-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.coordinator.work :as work]
   [nido.coordinator.record.proposal]
   [nido.coordinator.record.workstream :as ws]
   [nido.ui.server :as server]))

(defn- with-one-proposal
  "A review-run workstream holding one analysis with one proposal, in a
   throwaway root, plus the daemon-health seam stubbed so the rail renders."
  [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))
                    server/read-rail-daemon (constantly {:state :up})]
        (let [id (:id (ws/create! :nido {:stage :triaging
                                         :external-refs [{:adapter :review-run :id "r" :title "t"}]}))]
          (ws/append-entry! :nido id {:kind :review-analysis}
                            (pr-str {:format :review-analysis :verdict :degraded :run-id "r"
                                     :status "clean" :rounds 1 :summary "the arc"
                                     :observations [{:kind :waste :where "cache/record"
                                                     :summary "the clean path forgets"
                                                     :evidence "stages.clj:334"
                                                     :proposal "record it there too"}]}))
          (f id)))
      (finally (fs/delete-tree tmp)))))

(deftest the-surface-shows-a-proposal-with-what-earned-it
  (with-one-proposal
    (fn [_id]
      (let [body (str (:body (server/handle-request
                              {:request-method :get :uri "/_fragment/operations"
                               :query-string "scope=nido"})))]
        (is (str/includes? body "1 awaiting you"))
        (is (str/includes? body "the clean path forgets") "the observation")
        (is (str/includes? body "stages.clj:334")
            "and its evidence — a decision is about the evidence, so it is not folded away")
        (is (str/includes? body "record it there too") "and what is proposed")
        (is (str/includes? body "Approve"))))))

(deftest deciding-records-it-and-the-same-click-twice-does-not
  (with-one-proposal
    (fn [id]
      (let [uri (str "/operations/nido/" id "/1/0")
            post (fn [qs] (server/handle-request {:request-method :post :uri uri :query-string qs}))]
        (is (= 200 (:status (post "entry=1&verdict=approved&scope=nido"))))
        (let [d (:decision (first (work/proposals :nido)))]
          (is (= :approved (:verdict d)))
          (is (= 1 (:at-seq d)) "it recorded the position the row was rendered from"))
        (is (str/includes? (str (:body (post "entry=1&verdict=approved&scope=nido")))
                           "The page moved")
            "the same click again is refused and says why — its own decision moved the ledger")))))

(deftest a-verdict-the-surface-never-offered-records-nothing
  ;; The buttons post a fixed verdict, so anything else arrived by hand. It is
  ;; refused rather than coerced: a decision is a durable claim about what a
  ;; human chose, and there is no safe default for one nobody chose.
  (with-one-proposal
    (fn [id]
      (server/handle-request {:request-method :post
                              :uri (str "/operations/nido/" id "/1/0")
                              :query-string "entry=1&verdict=maybe&scope=nido"})
      (is (empty? (filter #(= :improvement-decision (:kind %))
                          (:entries (ws/read-ws :nido id))))))))

(deftest the-page-is-a-rail-destination
  (with-one-proposal
    (fn [_id]
      (let [body (str (:body (server/handle-request
                              {:request-method :get :uri "/operations" :query-string "scope=nido"})))]
        (is (= 200 (:status (server/handle-request
                             {:request-method :get :uri "/operations" :query-string "scope=nido"}))))
        (is (str/includes? body "/operations") "the rail links to it")
        (is (str/includes? body "_fragment/operations") "and the page polls its own fragment")))))

(deftest an-approval-nobody-carried-out-says-so
  ;; The bug the whole band exists for: three proposals were approved on this
  ;; surface and nothing in nido acts on an approval, so they read as finished.
  (with-one-proposal
    (fn [id]
      (server/handle-request {:request-method :post
                              :uri (str "/operations/nido/" id "/1/0")
                              :query-string "entry=1&verdict=approved&scope=nido"})
      (let [body (str (:body (server/handle-request
                              {:request-method :get :uri "/_fragment/operations"
                               :query-string "scope=nido"})))]
        (is (str/includes? body "not yet implemented")
            "an approval carries the state it is actually in")
        (is (str/includes? body "1 approved, not yet implemented")
            "and the head counts it, so the band is visible before you scroll")
        (is (not (str/includes? body "1 settled"))
            "it is not filed with the finished ones")
        (is (str/includes? body "record it there too")
            "and it stays on the page rather than folding into the trail")))))

(deftest a-landing-discharges-the-approval
  (with-one-proposal
    (fn [id]
      (server/handle-request {:request-method :post
                              :uri (str "/operations/nido/" id "/1/0")
                              :query-string "entry=1&verdict=approved&scope=nido"})
      (work/record-landing! :nido id {:analysis-seq 1 :observation 0 :rev "qlosnwus"
                                      :note "the second half is spun out"})
      (let [body (str (:body (server/handle-request
                              {:request-method :get :uri "/_fragment/operations"
                               :query-string "scope=nido"})))]
        (is (str/includes? body "landed · qlosnwus")
            "the row names what carries it, so a reader can go and look")
        (is (not (str/includes? body "not yet implemented")))
        (is (str/includes? body "1 settled") "and it is finished")
        (is (str/includes? body "the second half is spun out")
            "the landing's own words are rendered — what it did NOT cover is the
             one thing a reader cannot get from the proposal")))))

(deftest a-declines-reason-is-shown-rather-than-only-stored
  (with-one-proposal
    (fn [id]
      (work/decide-proposal! :nido id {:analysis-seq 1 :observation 0 :verdict :declined
                                       :at-seq 1 :note "the seam it names is deliberate"})
      (let [body (str (:body (server/handle-request
                              {:request-method :get :uri "/_fragment/operations"
                               :query-string "scope=nido"})))]
        (is (str/includes? body "the seam it names is deliberate")
            "a decline without its reason is indistinguishable from an oversight,
             and the reason was written to the ledger and dropped by the surface")))))

;; ── the veto has to be reachable ────────────────────────────────────────────

(defn- board [] (str (:body (server/handle-request
                             {:request-method :get :uri "/_fragment/operations"
                              :query-string "scope=nido"}))))

(deftest an-approved-proposal-can-still-be-declined
  ;; Under the sweep an approval is not what lets a proposal be carried — a
  ;; decline is what stops it. A veto unreachable for an approved row would be
  ;; no veto at all, and most of the backlog is approved.
  (with-one-proposal
    (fn [id]
      (work/decide-proposal! :nido id {:analysis-seq 1 :observation 0
                                       :verdict :approved :at-seq 1})
      (let [body (board)]
        (is (str/includes? body "verdict=declined") "the veto is still offered")
        (is (not (str/includes? body "verdict=approved"))
            "approving twice is not a thing to offer")))))

(deftest a-declined-proposal-offers-no-further-decision
  (with-one-proposal
    (fn [id]
      (work/decide-proposal! :nido id {:analysis-seq 1 :observation 0
                                       :verdict :declined :at-seq 1})
      (let [body (board)]
        (is (not (str/includes? body "verdict=declined")) "declining twice changes nothing")))))

(deftest a-landed-proposal-offers-no-decision-at-all
  (with-one-proposal
    (fn [id]
      (work/record-landing! :nido id {:analysis-seq 1 :observation 0 :rev "abc"})
      (let [body (board)]
        (is (not (str/includes? body "verdict=approved")))
        (is (not (str/includes? body "verdict=declined"))
            "nothing to veto — it is already on main")))))

(deftest a-decline-that-arrives-after-a-reservation-is-shown-as-late
  ;; The honest reading of silence being the wrong one: without this the board
  ;; renders this decline exactly like one that stopped something, and a reader
  ;; believes they vetoed a change that lands minutes later.
  (with-one-proposal
    (fn [id]
      (work/decide-proposal! :nido id {:analysis-seq 1 :observation 0
                                       :verdict :declined :at-seq 1})
      (with-redefs [nido.coordinator.record.proposal/claim-attempts
                    (constantly [{:address "ws-p/1#0" :ws-id "ws-c" :open? true
                                  :addresses [(str id "/1.0")]}])]
        (let [body (board)]
          (is (str/includes? body "declined too late"))
          (is (str/includes? body "return to the backlog")
              "and what happens to the proposals it was grouped with"))))))
