(ns nido.coordinator.lane.notion-sync-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is are testing]]
   [nido.platform.core :as core]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.lane.notion-sync :as ns-sync]
   [nido.coordinator.source.state :as sstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.notion.client :as notion]))

(def ^:private me "me-id")

(defn- action
  "Convenience: run sync-action with the defaults and a given status/ballholders/stage."
  [status ballholders current-stage]
  (ns-sync/sync-action {:status         status
                        :ballholder-ids (set ballholders)
                        :me             me
                        :terminal       ns-sync/default-terminal
                        :status->stage  ns-sync/default-status->stage
                        :current-stage  current-stage}))

(deftest sync-action-truth-table
  (testing "terminal statuses close :done regardless of ball holder"
    (are [status bh] (= :close-done (action status bh :triage))
      "Done"     []
      "Not Done" [me]
      "Done"     ["someone-else"]))
  (testing "non-terminal, claimed by someone else → drop"
    (is (= :close-dropped (action "In progress" ["someone-else"] :triage)))
    (is (= :close-dropped (action "Review" ["a" "b"] :triage))))
  (testing "non-terminal, mine or unassigned → advance to mapped stage"
    (is (= [:advance :in-progress] (action "In progress" [me] :triage)))
    (is (= [:advance :in-progress] (action "In progress" [] :triage)))
    (is (= [:advance :ready]       (action "Not Started" [] :triage)))
    (is (= [:advance :ready]       (action "On Hold" [] :in-progress)))
    (is (= [:advance :done]        (action "Review" [me] :in-progress))))
  (testing "idempotent: mapped stage already current → no-op"
    (is (= :noop (action "In progress" [] :in-progress)))
    (is (= :noop (action "Needs verification" [] :triage))))
  (testing "unknown / missing status, mine or unassigned → no-op"
    (is (= :noop (action "Some Weird Status" [] :triage)))
    (is (= :noop (action nil [] :triage))))
  (testing "unknown status but claimed by other still drops"
    (is (= :close-dropped (action "Some Weird Status" ["other"] :triage)))))

(defn- with-tmp
  "Run f with a fresh temp nido-root (mirrors github_merge_test)."
  [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root (constantly (str tmp))]
           (f tmp))
         (finally (fs/delete-tree tmp)))))

(defn- write-config! [tmp project m]
  (let [p (fs/path tmp "projects" (name project) "notion-sync.edn")]
    (fs/create-dirs (fs/parent p))
    (spit (str p) (pr-str m))))

(deftest load-config-absent-returns-nil
  (with-tmp (fn [_] (is (nil? (ns-sync/load-config :brian))))))

(deftest load-config-without-me-returns-nil
  (with-tmp
    (fn [tmp]
      (write-config! tmp :brian {:poll "10m"})
      (is (nil? (ns-sync/load-config :brian))
          ":me is required — no way to tell 'claimed by me' from 'by someone else'"))))

(deftest load-config-merges-defaults
  (with-tmp
    (fn [tmp]
      (write-config! tmp :brian {:me "me-id"})
      (let [c (ns-sync/load-config :brian)]
        (is (= "me-id" (:me c)))
        (is (= ns-sync/default-terminal (:terminal c)))
        (is (= ns-sync/default-status->stage (:status->stage c)))
        (is (= "10m" (:poll c)))
        (is (false? (:dry-run? c)))))))

(deftest load-config-overrides-defaults
  (with-tmp
    (fn [tmp]
      (write-config! tmp :brian {:me "me-id" :terminal #{"Done"} :dry-run? true :poll "5m"})
      (let [c (ns-sync/load-config :brian)]
        (is (= #{"Done"} (:terminal c)))
        (is (true? (:dry-run? c)))
        (is (= "5m" (:poll c)))))))

(def ^:private page-done
  {:properties {:Status {:status {:name "Done"}}
                (keyword "Ball Holder") {:people []}}})

(def ^:private page-claimed
  {:properties {:Status {:status {:name "In progress"}}
                (keyword "Ball Holder") {:people [{:id "other-id" :name "A Colleague" :object "user"}]}}})

(def ^:private page-mine
  {:properties {:Status {:status {:name "Code Review"}}
                (keyword "Ball Holder") {:people [{:id me :name "Me" :object "user"}]}}})

(def ^:private page-claimed-noname
  {:properties {:Status {:status {:name "In progress"}}
                (keyword "Ball Holder") {:people [{:id "other-id" :object "user"}]}}})

(def ^:private page-no-status
  {:properties {(keyword "Ball Holder") {:people []}}})

(deftest extractors
  (is (= "Done" (ns-sync/page-status page-done)))
  (is (nil? (ns-sync/page-status page-no-status)))
  (is (= #{} (ns-sync/page-ballholder-ids page-done)))
  (is (= #{"other-id"} (ns-sync/page-ballholder-ids page-claimed)))
  (is (= #{me} (ns-sync/page-ballholder-ids page-mine)))
  (is (= "A Colleague" (ns-sync/page-ballholder-name page-claimed me)))
  (is (= "other-id" (ns-sync/page-ballholder-name page-claimed-noname me))
      "no :name on the other holder → falls back to :id")
  (is (= "someone" (ns-sync/page-ballholder-name page-mine me))
      "only ball holder is me → no 'other' holder → 'someone' default"))

(deftest open-notion-workstreams-filters
  (with-tmp
    (fn [_]
      (let [with-ref (ws/create! :brian {:stage :triage
                                         :external-refs [{:adapter :notion :id "BR-1" :page-id "P1"}]})
            _no-ref  (ws/create! :brian {:stage :triage :external-refs []})
            closed   (ws/create! :brian {:stage :triage
                                         :external-refs [{:adapter :notion :id "BR-2" :page-id "P2"}]})
            done-open (ws/create! :brian {:stage :done
                                          :external-refs [{:adapter :notion :id "BR-3" :page-id "P3"}]})]
        (ws/close! :brian (:id closed) :done)
        (let [ids (set (map :id (ns-sync/open-notion-workstreams :brian)))]
          (is (contains? ids (:id with-ref)) "open + notion ref included")
          (is (contains? ids (:id done-open)) "open workstream at :done STAGE still included (filter is :closed, not stage)")
          (is (not (contains? ids (:id closed))) "closed excluded")
          (is (= 2 (count ids)) "only the two open + notion-ref workstreams; no-ref and closed excluded"))))))

(def ^:private cfg
  {:poll "10m" :me me
   :terminal ns-sync/default-terminal
   :status->stage ns-sync/default-status->stage
   :dry-run? false})

(defn- ws-with [project stage page-id]
  (ws/create! project {:stage stage
                       :external-refs [{:adapter :notion :id (str "BR-" page-id) :page-id page-id}]}))

(deftest poll-reacts-close-drop-advance
  (with-tmp
    (fn [_]
      (let [done    (ws-with :brian :triage "P-done")
            claimed (ws-with :brian :triage "P-claimed")
            mine    (ws-with :brian :triage "P-mine")
            pages   {"P-done" page-done "P-claimed" page-claimed "P-mine" page-mine}]
        (with-redefs [notion/keychain-token (constantly "tok")
                      notion/retrieve-page  (fn [pid _] (get pages pid))]
          (ns-sync/poll-and-react! :brian cfg))
        (is (= :done (get-in (ws/read-ws :brian (:id done)) [:closed :outcome])))
        (is (= :dropped (get-in (ws/read-ws :brian (:id claimed)) [:closed :outcome])))
        (let [w (ws/read-ws :brian (:id mine))]
          (is (nil? (:closed w)))
          (is (= :in-progress (:stage w)) "Code Review → :in-progress"))
        (testing "actions leave an explanatory ledger entry"
          (is (pos? (count (:entries (ws/read-ws :brian (:id done)))))))))))

(deftest poll-is-idempotent
  (with-tmp
    (fn [_]
      (let [mine  (ws-with :brian :triage "P-mine")
            pages {"P-mine" page-mine}]
        (with-redefs [notion/keychain-token (constantly "tok")
                      notion/retrieve-page  (fn [pid _] (get pages pid))]
          (ns-sync/poll-and-react! :brian cfg)
          (ns-sync/poll-and-react! :brian cfg))
        (let [w (ws/read-ws :brian (:id mine))]
          (is (= :in-progress (:stage w)))
          (is (= 1 (count (:entries w))) "second poll is a no-op, appends nothing"))))))

(deftest poll-dry-run-mutates-nothing
  (with-tmp
    (fn [_]
      (let [mine  (ws-with :brian :triage "P-mine")
            pages {"P-mine" page-mine}]
        (with-redefs [notion/keychain-token (constantly "tok")
                      notion/retrieve-page  (fn [pid _] (get pages pid))]
          (ns-sync/poll-and-react! :brian (assoc cfg :dry-run? true)))
        (let [w (ws/read-ws :brian (:id mine))]
          (is (= :triage (:stage w)) "dry-run leaves stage untouched")
          (is (empty? (:entries w))))))))

(deftest poll-read-error-skips-fail-safe
  (with-tmp
    (fn [_]
      (let [mine  (ws-with :brian :triage "P-mine")
            pages {"P-mine" {:error :http :status 404}}]
        (with-redefs [notion/keychain-token (constantly "tok")
                      notion/retrieve-page  (fn [pid _] (get pages pid))]
          (ns-sync/poll-and-react! :brian cfg))
        (is (= :triage (:stage (ws/read-ws :brian (:id mine)))) "unreadable page → untouched")))))

(deftest poll-auth-error-opens-breaker
  (with-tmp
    (fn [_]
      (let [_mine (ws-with :brian :triage "P-mine")]
        (with-redefs [notion/keychain-token (constantly "tok")
                      notion/retrieve-page  (fn [_ _] {:error :auth})]
          (ns-sync/poll-and-react! :brian cfg))
        (is (= :open (:breaker (sstate/read-state "notion-sync-brian"))))))))

(deftest poll-open-breaker-skips-until-cooldown
  (with-tmp
    (fn [_]
      (sstate/write-state! "notion-sync-brian"
                           {:type :notion-sync :project :brian
                            :breaker :open :breaker-opened-at (clock/now-iso)})
      (let [called (atom 0)]
        (with-redefs [notion/keychain-token (constantly "tok")
                      notion/retrieve-page  (fn [_ _] (swap! called inc) {:error :auth})]
          (ns-sync/poll-and-react! :brian cfg))
        (is (zero? @called) "open breaker within cooldown skips the poll entirely")))))

(deftest load-config-warns-missing-me-only-once
  (with-tmp
    (fn [tmp]
      (reset! @#'ns-sync/!warned-missing-me #{})
      (write-config! tmp :brian {:poll "10m"})   ; config present but no :me
      (let [sw (java.io.StringWriter.)
            pw (java.io.PrintWriter. sw)]
        (binding [*err* pw]
          (is (nil? (ns-sync/load-config :brian)))
          (is (nil? (ns-sync/load-config :brian)))
          (.flush pw))
        (is (= 1 (count (re-seq #"missing :me" (str sw))))
            "warns once per project across repeated ticks, not on every call")))))
