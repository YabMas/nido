(ns nido.coordinator.record.sent-back-test
  "A :sent-back — the person's word sending a landing back for rework — is a record only its
   guarded writer appends, in one core commit with the asks it supersedes and the grant it read;
   it restarts the trail, so the position leaves the landing rungs until a review after it passes."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.lane.landing :as landing]
   [nido.coordinator.lane.pipeline :as p]
   [nido.coordinator.lane.reentry :as reentry]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.work.core :as wcore]
   [nido.platform.core :as core]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(def ^:private a-baseline
  {:format :baseline :strata [] :intent {:seq 1}
   :area "order totalling" :bounded-by "money on an order"
   :shape "one summing path"
   :model {:elements [{:id "agg" :sort :module :hides "the summing order"
                       :interface "an order's total"}]
           :claims [{:id "c1" :about ["agg"]
                     :statement "the aggregate is the only summing path"
                     :falsified-by "a second path that sums lines"
                     :evidence {:by :round} :read-at ["src/a.clj:1"]}]}
   :read ["src/a.clj"]})

(defn- a-design [b]
  {:format :design :strata [] :summary "s" :shape "sh"
   :model {:elements [{:id "agg" :sort :module}]
           :claims [{:id "one-summing-path" :about ["agg"]
                     :statement "one summing path"
                     :falsified-by "a second path that sums lines"
                     :evidence {:by :round}}]}
   :standing {:relation :conforms}
   :baseline {:seq b :relation :within}
   :intent {:seq 1} :effort :S})

(defn- a-review [status d]
  {:format :review-report :status status :base "main" :base-rev nil :rounds 1 :fix-attempts 0
   :defects-settled 0 :findings-remaining 0 :report-path "r.edn" :design {:seq d}})

(defn- latest [id] (count (:entries (ws/read-ws :brian id))))

(defn- add! [id kind record]
  (case kind
    :design-decision (ws/append-decision! :brian id (pr-str record))
    :design-cleared  (ws/append-entry-at! :brian id (latest id) {:kind kind} (pr-str record))
    (ws/append-entry! :brian id {:kind kind} (pr-str record)))
  (latest id))

(defn ^{:malli/schema [:=> [:cat] [:tuple :string :int]]} candidate!
  "A workstream whose cleared design was built and whose implementation review converged: a
   release candidate on a project that lands its working copy. Answers [ws-id design-seq]."
  []
  (let [id (:id (ws/create! :brian {:external-refs []}))]
    (add! id :intent {:format :intent :goal "g" :done-when ["d"]})
    (let [b (add! id :baseline a-baseline)
          _ (add! id :baseline-review {:format :baseline-review :verdict :sufficient
                                       :baseline-seq b :reason "holds"})
          d (add! id :design (a-design b))]
      (add! id :design-decision {:format :design-decision :recommend :proceed :design-seq d
                                 :reason "r" :checks [{:check :relation-honest :status :held :note "n"}]})
      (add! id :design-cleared {:format :design-cleared :design {:seq d}})
      (add! id :implementation-completed {:format :implementation-completed :summary "built"
                                          :artifacts [] :design {:seq d}})
      (add! id :review (a-review :converged d))
      [id d])))

(defn- send-back! [id & [opts]]
  (ws/append-sent-back! :brian id (latest id)
                        {:format :sent-back :words "rework the totals" :from :release-candidate}
                        (merge {:asks [] :reason "sent back for rework"} opts)))

(deftest only-its-guarded-writer-appends-a-sent-back
  (with-tmp
    (fn [_]
      (let [[id] (candidate!)
            body (pr-str {:format :sent-back :words "w" :from :release-candidate})]
        (testing "every generic append refuses it"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"append-sent-back!"
                                (ws/append-entry! :brian id {:kind :sent-back} body)))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"append-sent-back!"
                                (ws/append-entry-at! :brian id (latest id) {:kind :sent-back} body)))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"append-sent-back!"
                                (ws/check-entry :brian id {:kind :sent-back} body))))
        (testing "the writer refuses a ledger that moved since its caller read it, writing nothing"
          (let [before (latest id)]
            (is (= {:refused :stale :latest before}
                   (ws/append-sent-back! :brian id (dec before)
                                         {:format :sent-back :words "w" :from :release-candidate}
                                         {:asks [] :reason "r"})))
            (is (= before (latest id)))))
        (testing "and appends it at the position read"
          (let [n (send-back! id)]
            (is (= n (latest id)))
            (is (= :sent-back (:kind (last (:entries (ws/read-ws :brian id))))))))))))

(deftest the-send-back-withdraws-what-it-names-in-its-own-commit
  (with-tmp
    (fn [_]
      (let [[id] (candidate!)]
        (wcore/act! :brian id {:command :start-turn :turn "k1" :unit nil
                               :spec {:executor :fake :instructions "i"}})
        (wcore/act! :brian id {:command :record-outcome :turn "k1" :run 1 :outcome :asked
                               :call {:id "c" :question "Land?"}})
        (wcore/act! :brian id {:command :raise-operator-ask :ask "a1" :binds ["k1"]
                               :content {:question "Land it?" :explored "It converged."
                                         :options [{:option "Yes" :consequence "It lands."} "No"]
                                         :recommendation "Yes"}})
        (wcore/act! :brian id {:command :give-grant :grant :merge :by "person"})
        (let [occ (get-in (wcore/state :brian id) [:grants :merge :occurrence])]
          (testing "a named ask that is not open refuses the whole send-back"
            (let [before (latest id)]
              (is (= :ask-not-open (get-in (send-back! id {:asks ["a1" "nope"]}) [:refused :reason])))
              (is (= before (latest id)))
              (is (= :open (get-in (wcore/state :brian id) [:operator-asks "a1" :status])))))
          (send-back! id {:asks ["a1"] :merge-occurrence occ})
          (let [st (wcore/state :brian id)]
            (is (= :withdrawn (get-in st [:operator-asks "a1" :status])))
            (is (not (contains? (:grants st) :merge)))
            (is (= :sent-back (:kind (last (:ledger st)))))))))))

(deftest a-sent-back-spends-the-merge-grant-on-the-ledger
  (with-tmp
    (fn [_]
      (let [[id] (candidate!)]
        (add! id :grant {:format :grant :grants [:merge :intent] :words "land it, and amend"})
        (is (= #{:merge :intent} (set (map :kind (ws/unspent-grants :brian id)))))
        (send-back! id)
        (is (= [:intent] (map :kind (ws/unspent-grants :brian id)))
            "the :merge grant older than the :sent-back is spent; no other act's is")
        (add! id :grant {:format :grant :grants [:merge] :words "land it now"})
        (is (= [:intent :merge] (map :kind (ws/unspent-grants :brian id)))
            "a :merge granted after the send-back stands")))))

(deftest a-sent-back-candidate-is-a-candidate-again-only-after-a-new-review
  (with-tmp
    (fn [_]
      (with-redefs [landing/names-pr? (constantly false)]
        (let [[id d] (candidate!)]
          (is (= :release-candidate (:at (p/of :brian id))))
          (let [n (send-back! id)]
            (is (= n (reentry/restart (ws/read-ws :brian id))) "the :sent-back is the newest restart"))
          (let [pos (p/of :brian id)]
            (is (not (#{:release-candidate :published :land-granted} (:at pos)))
                "off the landing rungs")
            (is (= {:stage :implement :mode :working-copy} (select-keys (:next pos) [:stage :mode]))
                "at a move an agent makes")
            (is (false? (p/implementation-review-passed? (ws/read-ws :brian id)))
                "the review before the send-back no longer passes it"))
          (add! id :implementation-completed {:format :implementation-completed :summary "reworked"
                                              :artifacts [] :design {:seq d}})
          (is (= :implemented (:at (p/of :brian id))))
          (add! id :review (a-review :converged d))
          (is (= :release-candidate (:at (p/of :brian id)))
              "a passing review after the send-back makes it a candidate again"))))))

(deftest a-sent-back-pr-candidate-is-published-again-only-by-a-new-pr
  (with-tmp
    (fn [_]
      (with-redefs [landing/names-pr? (constantly true)]
        (let [[id d] (candidate!)]
          (add! id :pr-opened {:format :pr-opened :url "https://x/1" :title "t" :design {:seq d}})
          (is (= :release-candidate (:at (p/of :brian id))))
          (send-back! id)
          (is (not (#{:release-candidate :published} (:at (p/of :brian id)))))
          (add! id :implementation-completed {:format :implementation-completed :summary "reworked"
                                              :artifacts [] :design {:seq d}})
          (add! id :review (a-review :converged d))
          (is (= :reviewed (:at (p/of :brian id))) "the PR opened before it places nothing")
          (add! id :pr-opened {:format :pr-opened :url "https://x/1" :title "t" :design {:seq d}})
          (is (= :release-candidate (:at (p/of :brian id)))))))))
