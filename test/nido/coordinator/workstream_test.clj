(ns nido.coordinator.workstream-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [malli.core :as m]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.session :as sess]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]))

(def example-ws
  {:id            "ws-20260605-a1b2c3"
   :project       :brian
   :external-refs [{:adapter :notion :id "BR-4659"
                    :page-id "p" :url "u" :title "Firefox loading"}]
   :stage         :investigation
   :stage-history [{:at "2026-06-05T09:00:00Z" :stage :investigation}]
   :closed        nil
   :created-at    "2026-06-05T09:00:00Z"
   :entries       []})

(deftest schema-accepts-valid-workstream
  (is (m/validate ws/Workstream example-ws)))

(deftest schema-rejects-missing-id
  (is (not (m/validate ws/Workstream (dissoc example-ws :id)))))

(deftest stage-is-a-free-keyword
  (is (m/validate ws/Workstream (assoc example-ws :stage :some-project-specific-stage))))

(deftest mint-id-has-ws-prefix-and-is-unique
  (with-redefs [clock/now-iso (constantly "2026-06-05T09:00:00Z")]
    (let [a (ws/mint-id) b (ws/mint-id)]
      (is (str/starts-with? a "ws-20260605-"))
      (is (not= a b)))))

(deftest round-trip
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (ws/write! example-ws)
        (is (= example-ws (ws/read-ws :brian (:id example-ws)))))
      (finally (fs/delete-tree tmp)))))

(deftest read-ws-returns-nil-when-missing
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (is (nil? (ws/read-ws :brian "nope"))))
      (finally (fs/delete-tree tmp)))))

(deftest create-mints-id-and-seeds-stage-history
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T09:00:00Z")]
        (let [w (ws/create! :brian {:stage :investigation
                                    :external-refs [{:adapter :notion :id "BR-1"}]})]
          (is (str/starts-with? (:id w) "ws-"))
          (is (= :investigation (:stage w)))
          (is (= [{:at "2026-06-05T09:00:00Z" :stage :investigation}] (:stage-history w)))
          (is (nil? (:closed w)))
          (is (= [] (:entries w)))
          (is (= w (ws/read-ws :brian (:id w))))))
      (finally (fs/delete-tree tmp)))))

(deftest advance-stage-appends-history-and-updates-stage
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T10:00:00Z")]
        (ws/write! example-ws)
        (let [updated (ws/advance-stage! :brian (:id example-ws) :triaged)]
          (is (= :triaged (:stage updated)))
          (is (= 2 (count (:stage-history updated))))
          (is (= {:at "2026-06-05T10:00:00Z" :stage :triaged}
                 (last (:stage-history updated))))
          (is (= updated (ws/read-ws :brian (:id example-ws))))))
      (finally (fs/delete-tree tmp)))))

(deftest advance-stage-is-noop-when-stage-unchanged
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (ws/write! example-ws)
        (let [updated (ws/advance-stage! :brian (:id example-ws) :investigation)]
          (is (= 1 (count (:stage-history updated))))))
      (finally (fs/delete-tree tmp)))))

(deftest close-sets-closed-with-outcome
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T11:00:00Z")]
        (ws/write! example-ws)
        (let [closed (ws/close! :brian (:id example-ws) :dropped)]
          (is (= {:at "2026-06-05T11:00:00Z" :outcome :dropped} (:closed closed)))))
      (finally (fs/delete-tree tmp)))))

(deftest append-entry-writes-file-and-indexes-it
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T12:00:00Z")]
        (ws/write! example-ws)
        (let [path (ws/append-entry! :brian (:id example-ws)
                                     {:kind :note :session "sx"}
                                     "# Triage report\nbody")
              w    (ws/read-ws :brian (:id example-ws))
              e    (last (:entries w))]
          (is (str/ends-with? path "entries/0001-note.md"))
          (is (= "# Triage report\nbody" (slurp path)))
          (is (= 1 (:seq e)))
          (is (= :note (:kind e)))
          (is (= "sx" (:session e)))
          (is (= "entries/0001-note.md" (:file e)))
          (is (= "2026-06-05T12:00:00Z" (:at e)))))
      (finally (fs/delete-tree tmp)))))

(deftest append-entry-increments-seq
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T12:00:00Z")]
        (ws/write! example-ws)
        (ws/append-entry! :brian (:id example-ws) {:kind :note} "a")
        (let [p2 (ws/append-entry! :brian (:id example-ws) {:kind :plan} "b")]
          (is (str/ends-with? p2 "entries/0002-plan.md"))
          (is (= 2 (count (:entries (ws/read-ws :brian (:id example-ws))))))))
      (finally (fs/delete-tree tmp)))))

(deftest add-ref-appends-and-dedupes
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (let [w0 (ws/create! :brian {:stage :investigation :external-refs []})
              w1 (ws/add-ref! :brian (:id w0) {:adapter :notion :id "BR-9"})
              w2 (ws/add-ref! :brian (:id w0) {:adapter :notion :id "BR-9"})]
          (is (= 1 (count (:external-refs w1))))
          (is (= 1 (count (:external-refs w2))))))
      (finally (fs/delete-tree tmp)))))

(deftest find-by-ref-locates-the-workstream
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (let [w (ws/create! :brian {:stage :investigation
                                    :external-refs [{:adapter :notion :id "BR-42"}]})]
          (ws/create! :brian {:stage :investigation
                              :external-refs [{:adapter :notion :id "BR-99"}]})
          (is (= (:id w) (:id (ws/find-by-ref :brian :notion "BR-42"))))
          (is (nil? (ws/find-by-ref :brian :notion "BR-nope")))))
      (finally (fs/delete-tree tmp)))))

(deftest engagement-reads-sessions-off-disk
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T09:00:00Z")]
        (let [w (ws/create! :brian {:stage :investigation})]
          (is (= :idle (ws/engagement :brian (:id w))))
          (sess/create! :brian (:id w) {:name "s1" :weight :light :autonomy nil})
          (is (= :active (ws/engagement :brian (:id w))))
          (ws/close! :brian (:id w) :dropped)
          (is (= :settled (ws/engagement :brian (:id w))))))
      (finally (fs/delete-tree tmp)))))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(deftest create-persists-intake
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian
                {:stage :incoming
                 :external-refs [{:adapter :slack-message :id "slack-C-1.0"}]
                 :intake {:trigger :triage-slack-bugs
                          :payload {:id "slack-C-1.0" :text "it broke"}}})]
        (is (= :incoming (:stage w)))
        (is (= :triage-slack-bugs (-> w :intake :trigger)))
        (is (= "it broke" (-> w :intake :payload :text)))
        ;; round-trips through validation on read
        (is (= w (ws/read-ws :brian (:id w))))))))

(deftest create-without-intake-is-valid
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :triaging})]
        (is (nil? (:intake w)))
        (is (= w (ws/read-ws :brian (:id w))))))))

(deftest schema-accepts-facets
  (is (m/validate ws/Workstream (assoc example-ws :facets {:app-domain ["Teacher"] :type "bug"}))))

(deftest schema-omits-facets-ok
  (is (m/validate ws/Workstream example-ws)))

(deftest create-threads-facets
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (let [w (ws/create! :brian {:stage :triaging :external-refs []
                                    :facets {:type "bug"}})]
          (is (= {:type "bug"} (:facets (ws/read-ws :brian (:id w)))))))
      (finally (fs/delete-tree tmp)))))

(deftest set-facets-updates-existing
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (let [w (ws/create! :brian {:stage :triaging :external-refs []})]
          (ws/set-facets! :brian (:id w) {:app-domain ["Teacher"]})
          (is (= {:app-domain ["Teacher"]} (:facets (ws/read-ws :brian (:id w)))))))
      (finally (fs/delete-tree tmp)))))

(deftest set-facets-throws-on-absent
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (is (thrown? clojure.lang.ExceptionInfo
                     (ws/set-facets! :brian "ws-nonexistent" {:type "bug"}))))
      (finally (fs/delete-tree tmp)))))

(deftest set-facets-empty-map-removes-key
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (let [w (ws/create! :brian {:stage :triaging :external-refs []
                                    :facets {:type "bug"}})]
          (ws/set-facets! :brian (:id w) {})
          (is (nil? (:facets (ws/read-ws :brian (:id w)))))))
      (finally (fs/delete-tree tmp)))))

(deftest append-entry-validates-and-stores-typed-event
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T12:00:00Z")]
        (ws/write! example-ws)
        (let [path (ws/append-entry! :brian (:id example-ws) {:kind :implementation-plan}
                                     (pr-str {:format :implementation-plan :summary "x"
                                              :direction "Round once" :effort :S}))]
          (is (str/ends-with? path "entries/0001-implementation-plan.edn"))
          (is (str/includes? (slurp path) ":implementation-plan"))
          (is (str/includes? (slurp path) "Round once"))))
      (finally (fs/delete-tree tmp)))))

(deftest append-entry-rejects-malformed-typed-event
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T12:00:00Z")]
        (ws/write! example-ws)
        (is (thrown? clojure.lang.ExceptionInfo
                     (ws/append-entry! :brian (:id example-ws) {:kind :blocker} "not a map"))))
      (finally (fs/delete-tree tmp)))))

(deftest read-ws-normalizes-legacy-inbox-stage
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T09:00:00Z")]
        ;; A record persisted before the rename carries the legacy :stage :inbox.
        (let [w (ws/create! :brian {:stage :inbox :external-refs []})]
          (is (= :incoming (:stage (ws/read-ws :brian (:id w))))
              "legacy :inbox is mapped to :incoming on read")))
      (finally (fs/delete-tree tmp)))))
