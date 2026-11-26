(ns nido.core.asking-test
  "resources/asking/ is the one source of how a core ask is written: what the ask tool, the
   conductor's raise_operator_ask and its brief say, and the question cap both the core's check
   and the ledger's are held to."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nido.coordinator.report]
            [nido.core.ask-tool :as ask-tool]
            [nido.core.buffer :as b]
            [nido.core.domain :as d]
            [nido.platform.core :as platform]))

(def ^:private asking (fs/path (platform/nido-source-dir) "resources" "asking"))

(defn- tool-description [tools n]
  (:description (first (filter #(= n (:name %)) tools))))

(deftest an-edit-to-ask-md-alone-is-seen-in-every-core-briefing
  (let [dir    (fs/create-temp-dir)
        marker "Every question is a sentence about otters, which this test alone writes."]
    (try
      (spit (str (fs/path dir "ask.md")) (str (slurp (str (fs/path asking "ask.md"))) "\n" marker "\n"))
      (fs/copy (fs/path asking "core-ask.md") (fs/path dir "core-ask.md"))
      (with-redefs-fn {#'ask-tool/asking-dir (constantly (fs/file dir))}
        #(let [ask   (tool-description (get-in (ask-tool/respond {:id 1 :method "tools/list"}) [:result :tools]) "ask")
              raise (tool-description (get-in (b/respond {} {:id 1 :method "tools/list"}) [:result :tools])
                                      "raise_operator_ask")
              brief (b/brief "ws" d/empty-state [])]
          (doseq [[where text] [["the ask tool" ask] ["raise_operator_ask" raise] ["the conductor brief" brief]]]
            (testing where
              (is (str/includes? text marker) "carries ask.md as it is on disk")
              (is (str/includes? text "Raising a unit's ask") "then the core addendum")))))
      (finally (fs/delete-tree dir)))))

(deftest the-brief-no-longer-says-to-carry-a-units-ask-over
  (let [brief (str/replace (b/brief "ws" d/empty-state []) #"\s+" " ")]
    (is (not (str/includes? brief "as the unit wrote them")))
    (is (str/includes? brief "rewriting it to the rules above"))))

(deftest neither-schema-requires-a-recommendation
  (let [ask   (first (get-in (ask-tool/respond {:id 1 :method "tools/list"}) [:result :tools]))
        raise (first (filter #(= "raise_operator_ask" (:name %))
                             (get-in (b/respond {} {:id 1 :method "tools/list"}) [:result :tools])))]
    (doseq [t [ask raise]]
      (is (some #{"explored"} (get-in t [:inputSchema :required])))
      (is (not (some #{"recommendation"} (get-in t [:inputSchema :required])))))
    (testing "explored is described as context only, never how the question got here"
      (let [desc (get-in ask-tool/properties [:explored :description])]
        (is (str/includes? desc "Only the context the answer depends on"))
        (is (str/includes? desc "Never how the question reached the person"))
        (is (not (str/includes? desc "what you looked at")))))))

(defn- ask-md-cap []
  (some->> (slurp (str (fs/path asking "ask.md")))
           (re-find #"at most (\d+) characters")
           second parse-long))

(deftest the-question-cap-is-the-one-ask-md-states
  (let [cap           (ask-md-cap)
        at-cap        (str (apply str (repeat (dec cap) "x")) "?")
        over          (str "x" at-cap)
        ledger-holds? @#'nido.coordinator.report/one-question?]
    (is (some? cap) "ask.md states the cap")
    (is (= cap d/question-max) "the core's check")
    (is (= cap @#'nido.coordinator.report/needs-max) "the ledger's write contract")
    (testing "both checks take a question at the cap and refuse one over it"
      (is (empty? (d/ask-problems {:question at-cap})))
      (is (seq (d/ask-problems {:question over})))
      (is (ledger-holds? at-cap))
      (is (not (ledger-holds? over))))))
