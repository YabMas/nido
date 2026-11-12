(ns nido.coordinator.lane.pickup-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.coordinator.lane.pickup :as pickup]
   [nido.coordinator.lane.brief :as brief]
   [nido.coordinator.lane.start :as start]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.tickets :as tickets]
   [nido.coordinator.record.workstream :as ws]
   [nido.notion.client :as client]
   [nido.notion.views :as views]))

(defn- with-tmp
  "Run `f` against a throwaway nido root, with a start's session bring-up and its Notion legs
   stubbed. Mandatory for every pickup! test: pickup! starts the ticket, so an unisolated run
   writes a real ticket record into the live ~/.nido and brings a real session up."
  [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root                 (constantly (str tmp))
                       start/start-session!           (fn [_ _ o] (:ws-id o))
                       brief/ensure-ticket-brief!     (fn [p id _ _] (ws/append-entry! p id {:kind :ticket} "words"))
                       client/keychain-token          (constantly "tok")
                       client/update-page-properties! (fn [& _] {:ok true})]
           (cstate/ensure-dirs!) (f))
         (finally (fs/delete-tree tmp)))))

(deftest extract-page-id-from-urls-and-ids
  (let [pid "2a1b3c4d5e6f7081a2b3c4d5e6f70810"]
    (is (= "2a1b3c4d-5e6f-7081-a2b3-c4d5e6f70810"
           (pickup/extract-page-id (str "https://www.notion.so/Some-Title-" pid))))
    (is (= "2a1b3c4d-5e6f-7081-a2b3-c4d5e6f70810"
           (pickup/extract-page-id (str "https://app.notion.com/p/x-" pid "?pvs=4"))))
    (is (= "2a1b3c4d-5e6f-7081-a2b3-c4d5e6f70810"
           (pickup/extract-page-id "2a1b3c4d-5e6f-7081-a2b3-c4d5e6f70810")) "bare dashed uuid")
    (is (= "2a1b3c4d-5e6f-7081-a2b3-c4d5e6f70810"
           (pickup/extract-page-id
             (str "https://app.notion.com/p/ws/Some-Title-" pid
                  "?v=3c3fca9f403c8054971c000cad2213e6&source=copy_link")))
        "the ?v= view id trails the page id — the path wins")
    (is (= "2a1b3c4d-5e6f-7081-a2b3-c4d5e6f70810"
           (pickup/extract-page-id
             (str "https://www.notion.so/ws/124fca9f403c80d4896ffc857e105e35"
                  "?v=3c3fca9f403c8054971c000cad2213e6&p=" pid "&pm=s")))
        "address-bar form: the path holds the DATABASE id, ?p= holds the page")
    (is (nil? (pickup/extract-page-id "BR-4826")) "a BR id is not a page-id")
    (is (nil? (pickup/extract-page-id "nonsense")))))

(deftest resolve-ref-fetches-a-url
  (with-redefs [client/retrieve-page
                (fn [_pid _tok]
                  {:id "pg-1" :url "u" :properties
                   {"ID" {:type "unique_id" :unique_id {:prefix "BR" :number 4826}}
                    "Task result" {:type "title" :title [{:plain_text "Logo bug"}]}}})]
    (let [r (pickup/resolve-ref :brian "https://www.notion.so/Logo-2a1b3c4d5e6f7081a2b3c4d5e6f70810" "tok")]
      (is (= "BR-4826" (:id r)))
      (is (= "pg-1" (:page-id r)))
      (is (= "Logo bug" (:title r))))))

(deftest resolve-ref-fetches-a-br-id
  (with-redefs [views/load-registry
                (fn [_project] {:database "db-1"})
                client/resolve-data-source-id
                (fn [database-id _tok]
                  (is (= "db-1" database-id))
                  "ds-1")
                client/data-source-query
                (fn [ds-id _tok {:keys [filter]}]
                  (is (= "ds-1" ds-id))
                  (is (= {:property "ID" :unique_id {:equals 4826}} filter))
                  {:status 200
                   :results [{:id "pg-1" :url "u" :properties
                              {"ID" {:type "unique_id" :unique_id {:prefix "BR" :number 4826}}
                               "Task result" {:type "title" :title [{:plain_text "Logo bug"}]}}}]
                   :has_more false})]
    (let [r (pickup/resolve-ref :brian "BR-4826" "tok")]
      (is (= "BR-4826" (:id r)))
      (is (= "pg-1" (:page-id r)))
      (is (= "Logo bug" (:title r))))))

(deftest resolve-ref-not-found
  (with-redefs [views/load-registry
                (fn [_project] {:database "db-1"})
                client/resolve-data-source-id
                (fn [_database-id _tok] "ds-1")
                client/data-source-query
                (fn [_ds-id _tok _opts] {:status 200 :results [] :has_more false})]
    (is (= {:error :not-found} (pickup/resolve-ref :brian "BR-9999" "tok")))))

(deftest resolve-ref-unrecognized-input
  (is (= {:error :unrecognized-input} (pickup/resolve-ref :brian "not a ref" "tok"))))

(deftest resolve-ref-br-id-data-source-resolution-throws
  (with-redefs [views/load-registry
                (fn [_project] {:database "db-1"})
                client/resolve-data-source-id
                (fn [_database-id _tok]
                  (throw (ex-info "Failed to resolve data-source id" {})))]
    (is (= {:error :notion-error} (pickup/resolve-ref :brian "BR-4826" "tok")))))

(deftest resolve-ref-br-id-query-failure-is-not-mistaken-for-not-found
  (with-redefs [views/load-registry
                (fn [_project] {:database "db-1"})
                client/resolve-data-source-id
                (fn [_database-id _tok] "ds-1")
                client/data-source-query
                (fn [_ds-id _tok _opts] {:error :auth :status 401})]
    (is (= {:error :auth} (pickup/resolve-ref :brian "BR-4826" "tok")))))

(deftest pickup-starts-the-resolved-ticket-at-once
  (with-tmp
    (fn []
      (let [started (atom nil)]
        (with-redefs [pickup/resolve-ref  (fn [_ _ _] {:id "BR-1" :page-id "pg-1" :url "u" :title "t"})
                      start/start-ticket! (fn [p st] (reset! started [p st])
                                            {:decision :started :ws-id (:ws-id st) :session "impl-br-1-t"})]
          (let [r (pickup/pickup! :brian "https://notion.so/x-<id>" "tok")]
            (is (= :driving (:decision r)))
            (is (= "impl-br-1-t" (:session r)))
            (is (= {:adapter :notion :id "BR-1" :page-id "pg-1" :url "u" :title "t"}
                   (:ref (second @started)))
                "the start is handed the resolved ref, page id included")
            (is (empty? (fs/list-dir (cstate/queue-dir))) "nothing is queued for the daemon")))))))

(deftest a-refused-start-is-reported-unresolved
  (with-tmp
    (fn []
      (with-redefs [pickup/resolve-ref  (fn [_ _ _] {:id "BR-1" :page-id "pg-1" :url "u" :title "t"})
                    start/start-ticket! (constantly {:decision :ticket-unreadable})]
        (is (= {:decision :unresolved :error :ticket-unreadable
                :ref {:id "BR-1" :page-id "pg-1" :url "u" :title "t"}}
               (pickup/pickup! :brian "BR-1" "tok")))))))

(deftest pickup-claims-the-ticket-so-the-board-reads-it-as-active
  (with-tmp
    (fn []
      (with-redefs [pickup/resolve-ref     (fn [_ _ _] {:id "BR-1" :page-id "pg-1" :url "u" :title "t"})
                    views/facet-properties (constantly nil)]
        (pickup/pickup! :brian "https://notion.so/x" "tok")
        (is (= :planning (tickets/status :brian "BR-1"))
            "the person's start puts it in progress")
        (is (= "pg-1" (:page-id (first (:external-refs (ws/find-by-ref-id :brian "BR-1")))))
            "the workstream is minted with the page id")
        (let [m (tickets/read-meta :brian "BR-1")]
          (is (= "pg-1" (:notion-page-id m)))
          (is (= "t" (:title m)))
          (is (= :pickup (:opened-by m))))))))

(deftest pickup-does-not-regress-a-ticket-already-being-implemented
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:external-refs [{:adapter :notion :id "BR-1" :page-id "pg-1"}]})]
        (ws/append-entry! :brian (:id w) {:kind :ticket} "told")
        (ws/append-entry! :brian (:id w) {:kind :impl} "# work")
        (with-redefs [pickup/resolve-ref     (fn [_ _ _] {:id "BR-1" :page-id "pg-1" :url "u" :title "t"})
                      views/facet-properties (constantly nil)]
          (is (= :driving (:decision (pickup/pickup! :brian "https://notion.so/x" "tok"))))
          (is (= :implementing (tickets/status :brian "BR-1"))
              "re-driving a live ticket must not pull it back to :planning")
          (is (empty? (ws/entries-of :brian (:id w) :stage-set))
              "and records no second choice to plan it"))))))

(deftest pickup-claims-a-ticket-that-was-never-triaged-in-nido
  (with-tmp
    (fn []
      (with-redefs [pickup/resolve-ref     (fn [_ _ _] {:id "FU-15" :page-id "pg-9" :url "u" :title "t"})
                    views/facet-properties (constantly nil)]
        (is (nil? (tickets/read-meta :brian "FU-15")) "no record to start from")
        (pickup/pickup! :brian "https://notion.so/x" "tok")
        (is (= :planning (tickets/status :brian "FU-15"))
            "pickup bypasses the triage gate promote enforces — a record-less ticket still starts")))))

(deftest pickup-reports-unresolved
  (with-redefs [pickup/resolve-ref (fn [_ _ _] {:error :not-found})]
    (is (= :unresolved (:decision (pickup/pickup! :brian "BR-nope" "tok"))))))

(deftest resolve-ref-url-page-without-id-property-is-not-a-ticket
  (with-redefs [client/retrieve-page
                (fn [_pid _tok]
                  {:id "pg-1" :url "u" :properties
                   {"Task result" {:type "title" :title [{:plain_text "Some page"}]}}})]
    (is (= {:error :not-a-ticket}
           (pickup/resolve-ref :brian "https://www.notion.so/Some-2a1b3c4d5e6f7081a2b3c4d5e6f70810" "tok")))))

(deftest resolve-ref-nil-token-short-circuits
  (with-redefs [client/retrieve-page (fn [& _] (throw (ex-info "should not be called" {})))
                views/load-registry (fn [& _] (throw (ex-info "should not be called" {})))]
    (is (= {:error :no-token} (pickup/resolve-ref :brian "BR-4826" nil)))
    (is (= {:error :no-token} (pickup/resolve-ref :brian "BR-4826" "  ")))
    (is (= {:error :no-token}
           (pickup/resolve-ref :brian "https://www.notion.so/x-2a1b3c4d5e6f7081a2b3c4d5e6f70810" "")))))

(deftest pickup-reports-no-token
  (with-redefs [client/retrieve-page (fn [& _] (throw (ex-info "should not be called" {})))]
    (let [r (pickup/pickup! :brian "BR-4826" nil)]
      (is (= :unresolved (:decision r)))
      (is (= :no-token (:error r))))))

(deftest pickup-reports-continuing-when-workstream-exists
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:external-refs [{:adapter :notion :id "BR-1" :page-id "pg-1"}]})]
        (with-redefs [pickup/resolve-ref (fn [_ _ _] {:id "BR-1" :page-id "pg-1" :url "u" :title "t"})]
          (let [r (pickup/pickup! :brian "https://notion.so/x" "tok")]
            (is (= :driving (:decision r)))
            (is (true? (:continuing? r)))
            (is (= (:id w) (:ws-id r)))))))))

(deftest pickup-reports-starting-fresh-when-no-workstream
  (with-tmp
    (fn []
      (with-redefs [pickup/resolve-ref (fn [_ _ _] {:id "BR-1" :page-id "pg-1" :url "u" :title "t"})]
        (let [r (pickup/pickup! :brian "https://notion.so/x" "tok")]
          (is (= :driving (:decision r)))
          (is (false? (:continuing? r)))
          (is (= (:id (ws/find-by-ref-id :brian "BR-1")) (:ws-id r))
              "the start minted the workstream it names"))))))

(defn- held!
  "A Slack workstream whose proposal was filed as BR-1 and nobody has accepted: the ledger shape
   that holds the ticket for the gate."
  []
  (let [w (ws/create! :brian {:external-refs [{:adapter :slack-message :id "slack-C1-1.0" :title "msg"}
                                              {:adapter :notion :id "BR-1" :page-id "pg-1" :url "u"}]})]
    (ws/append-entry! :brian (:id w) {:kind :proposed-ticket}
                      (pr-str {:format :proposed-ticket :title "t" :ticket-type "bug" :priority nil
                               :source-url "https://myco.slack.com/archives/C1/p1"
                               :problem "p" :root-cause "r" :fix "f" :app-domain "Teacher"
                               :product {:where "w" :what-happens "h" :expected "e"
                                         :who-is-affected "a"}}))
    w))

(deftest pickup-refuses-a-ticket-a-slack-proposal-holds
  (with-tmp
    (fn []
      (held!)
      (is (tickets/held-by-gate? :brian "BR-1"))
      (with-redefs [pickup/resolve-ref  (fn [_ _ _] {:id "BR-1" :page-id "pg-1" :url "u" :title "t"})
                    start/start-ticket! (fn [& _] (throw (ex-info "must not start" {})))]
        (let [r (pickup/pickup! :brian "BR-1" "tok")]
          (is (= :awaiting-gate (:error r)))
          (is (= :awaiting-input (tickets/status :brian "BR-1"))
              "the hold is untouched — only accepting the proposal ends it"))))))

(deftest claim-and-drive-ends-the-hold
  (with-tmp
    (fn []
      (held!)
      (is (= :driving (:decision (pickup/claim-and-drive!
                                  :brian {:id "BR-1" :page-id "pg-1" :url "u" :title "t"}))))
      (is (= :planning (tickets/status :brian "BR-1")))
      (is (not (tickets/held-by-gate? :brian "BR-1"))))))
