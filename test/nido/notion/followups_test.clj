(ns nido.notion.followups-test
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.notion.client :as client]
   [nido.notion.followups :as followups]))

(def ^:private cfg
  {:database "db-1"
   :properties {:title      "Title"
                :origin     "Origin"
                :kind       "Kind"
                :reason     "Reason"
                :decay      "Decay"
                :cold-start "Cold start"
                :effort     "Effort"
                :status     "Status"
                :project    "Project"}})

(def ^:private valid-entry
  {:title      "drop the compat shim in work.clj"
   :origin     "nido/task-splitting"
   :kind       "cleanup"
   :reason     "revealed not caused; cheaper once the callers converge"
   :decay      "cheaper-later"
   :cold-start "cheap"})

;; ---------------------------------------------------------------------------
;; validate
;; ---------------------------------------------------------------------------

(deftest validate-accepts-a-complete-entry
  (is (= [] (followups/validate valid-entry))))

(deftest validate-requires-the-fields-that-make-the-db-drainable
  (testing "reason is required — a deferral without its why is a shrug"
    (is (some #(str/includes? % ":reason")
              (followups/validate (dissoc valid-entry :reason)))))
  (testing "decay and cold-start are required — they are the drain ordering"
    (is (some #(str/includes? % ":decay")
              (followups/validate (dissoc valid-entry :decay))))
    (is (some #(str/includes? % ":cold-start")
              (followups/validate (dissoc valid-entry :cold-start)))))
  (testing "a blank string counts as missing, not as a value"
    (is (some #(str/includes? % ":reason")
              (followups/validate (assoc valid-entry :reason "   "))))))

(deftest validate-rejects-values-outside-the-vocabulary
  (let [errs (followups/validate (assoc valid-entry :decay "eventually"))]
    (is (= 1 (count errs)))
    (is (str/includes? (first errs) "compounding")
        "the error should list the legal values"))
  (testing "every closed field is checked"
    (doseq [f [:kind :decay :cold-start :effort :status]]
      (is (seq (followups/validate (assoc valid-entry f "nonsense")))
          (str f " should be vocabulary-checked")))))

;; ---------------------------------------------------------------------------
;; ->properties
;; ---------------------------------------------------------------------------

(deftest ->properties-uses-configured-display-names-and-nido-types
  (let [props (followups/->properties cfg valid-entry)]
    (testing "names come from config, not from code"
      (is (contains? props "Cold start"))
      (is (not (contains? props "cold-start"))))
    (testing "types come from code"
      (is (= [{:text {:content "drop the compat shim in work.clj"}}]
             (get-in props ["Title" :title])))
      (is (= [{:text {:content "nido/task-splitting"}}]
             (get-in props ["Origin" :rich_text])))
      (is (= {:name "cleanup"} (get-in props ["Kind" :select]))))))

(deftest ->properties-applies-defaults
  (let [props (followups/->properties cfg valid-entry)]
    (is (= {:name "Open"} (get-in props ["Status" :select]))
        "a filed follow-up starts Open")
    (is (= {:name "squirrel"} (get-in props ["Effort" :select]))
        "unsized defers to the existing squirrel vocabulary")))

(deftest ->properties-omits-absent-and-blank-fields
  (let [props (followups/->properties (update cfg :properties dissoc :project)
                                      valid-entry)]
    (is (not (contains? props "Project"))
        "a DB without an optional property never receives it"))
  (let [props (followups/->properties cfg (assoc valid-entry :project "  "))]
    (is (not (contains? props "Project"))
        "blank values are omitted rather than written empty")))

(deftest ->properties-throws-on-an-unmapped-field
  (let [ex (try (followups/->properties (update cfg :properties dissoc :kind)
                                        valid-entry)
                nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex) "an unmapped field must throw, not silently drop")
    (is (= :kind (:field (ex-data ex))))))

;; ---------------------------------------------------------------------------
;; create! / list-entries
;; ---------------------------------------------------------------------------

(deftest create!-refuses-an-invalid-entry-before-any-http-call
  (with-redefs [                client/keychain-token (constantly "tok")
                client/http-request (fn [& _]
                                      (throw (ex-info "must not be called" {})))]
    (let [res (followups/create! cfg (dissoc valid-entry :reason))]
      (is (= :invalid (:error res)))
      (is (seq (:problems res))))))

(deftest config!-throws-a-setup-hint-when-unconfigured
  ;; the guard moved off create! and onto config!, which is now where the caller
  ;; resolves configuration — the write paths take an already-validated cfg.
  (let [ex (try (followups/config! nil "~/.nido/coordinator/config.edn")
                nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (str/includes? (:hint (ex-data ex)) ":followups")
        "the error should say what to configure")
    (is (str/includes? (:hint (ex-data ex)) "config.edn")
        "…and where to put it — the caller supplies the location")))

(deftest create!-posts-the-page-and-reads-back-the-unique-id
  (let [captured (atom nil)]
    (with-redefs [                  client/keychain-token (constantly "tok")
                  client/resolve-data-source-id (constantly "ds-1")
                  client/http-request
                  (fn [_method _url opts]
                    (reset! captured (json/parse-string (:body opts) false))
                    {:status 200
                     :body (json/generate-string
                            {:id "pg-1" :url "https://notion.so/pg-1"
                             :properties {"ID" {:type "unique_id"
                                                :unique_id {:prefix "FU" :number 12}}
                                          "Title" {:type "title"
                                                   :title [{:plain_text "shim"}]}}})})]
      (let [res (followups/create! cfg (assoc valid-entry :description "why + where"))]
        (is (= "FU-12" (:id res)) "the DB's unique-id becomes the spin-out ref")
        (is (= "pg-1" (:page-id res)))
        (testing ":description becomes page body, not a property"
          (is (= "why + where"
                 (get-in @captured ["children" 0 "paragraph" "rich_text" 0 "text" "content"])))
          (is (not (contains? (get @captured "properties") "description"))))))))

(deftest list-entries-orders-by-decay-pressure
  (let [page (fn [id decay]
               {:id id :url "u"
                :properties {"ID" {:type "unique_id"
                                   :unique_id {:prefix "FU" :number id}}
                             "Decay" {:type "select" :select {:name decay}}
                             "Title" {:type "title" :title [{:plain_text "t"}]}}})]
    (with-redefs [                  client/keychain-token (constantly "tok")
                  client/resolve-data-source-id (constantly "ds-1")
                  client/data-source-query
                  (fn [_ds _tok _opts]
                    {:status 200
                     :results [(page 1 "cheaper-later")
                               (page 2 "compounding")
                               (page 3 "flat")]})]
      (is (= ["FU-2" "FU-3" "FU-1"] (mapv :id (followups/list-entries cfg)))
          "what rots fastest comes first, not what was filed first"))))

(deftest list-entries-filters-on-the-configured-status-property
  (let [captured (atom nil)]
    (with-redefs [                  client/keychain-token (constantly "tok")
                  client/resolve-data-source-id (constantly "ds-1")
                  client/data-source-query
                  (fn [_ds _tok opts] (reset! captured opts) {:status 200 :results []})]
      (followups/list-entries cfg "Declined")
      (is (= {:property "Status" :select {:equals "Declined"}}
             (:filter @captured))))))

;; ---------------------------------------------------------------------------
;; check-config
;; ---------------------------------------------------------------------------

(defn- ds-with
  "A live data-source schema carrying `props`."
  [props]
  {:properties props})

(def ^:private full-schema
  (ds-with
   {"Title"      {:type "title"}
    "Origin"     {:type "rich_text"}
    "Reason"     {:type "rich_text"}
    "Kind"       {:type "select"
                  :select {:options (mapv #(hash-map :name %)
                                          (sort (:kind followups/vocabularies)))}}
    "Decay"      {:type "select"
                  :select {:options (mapv #(hash-map :name %)
                                          (sort (:decay followups/vocabularies)))}}
    "Cold start" {:type "select"
                  :select {:options (mapv #(hash-map :name %)
                                          (sort (:cold-start followups/vocabularies)))}}
    "Effort"     {:type "select"
                  :select {:options (mapv #(hash-map :name %)
                                          (sort (:effort followups/vocabularies)))}}
    "Status"     {:type "select"
                  :select {:options (mapv #(hash-map :name %)
                                          (sort (:status followups/vocabularies)))}}
    "Project"    {:type "select" :select {:options [{:name "nido"}]}}}))

(deftest check-config-passes-on-a-matching-schema
  (with-redefs [                client/resolve-data-source-id (constantly "ds-1")
                client/retrieve-data-source (constantly full-schema)]
    (is (= {:status :ok} (followups/check-config cfg "tok")))))

(deftest check-config-reports-a-renamed-property
  (with-redefs [                client/resolve-data-source-id (constantly "ds-1")
                client/retrieve-data-source
                (constantly (ds-with (-> (:properties full-schema)
                                         (dissoc "Cold start")
                                         (assoc "Pickup cost" {:type "select"}))))]
    (let [res (followups/check-config cfg "tok")]
      (is (= :error (:status res)))
      (is (some #(str/includes? (:message %) "Cold start") (:errors res))
          "the drift is named, so a rename is a config fix not a mystery"))))

(deftest check-config-reports-a-missing-select-option
  (with-redefs [                client/resolve-data-source-id (constantly "ds-1")
                client/retrieve-data-source
                (constantly (ds-with (assoc (:properties full-schema)
                                            "Decay"
                                            {:type "select"
                                             :select {:options [{:name "flat"}]}})))]
    (let [res (followups/check-config cfg "tok")]
      (is (= :error (:status res)))
      (is (some #(str/includes? (:message %) "compounding") (:errors res))
          "a vocabulary nido writes but the DB lacks is drift"))))
