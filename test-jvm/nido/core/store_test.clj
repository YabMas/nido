(ns nido.core.store-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [next.jdbc :as jdbc]
            [nido.core.domain :as d]
            [nido.core.domain-test :as dt]
            [nido.core.store :as store])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir [] (str (Files/createTempDirectory "nido-core-" (make-array FileAttribute 0))))

(defmacro with-store [[sym] & body]
  `(let [dir# (temp-dir)
         ~sym (store/open (str dir# "/core.db"))]
     (try ~@body (finally (store/close ~sym)))))

(defn- commit-command!
  "Decide `command` against the stored state of `ws` and commit it as a batch of its own, as
   core-writer will. Answers the refusal, or nil."
  [s ws key command]
  (let [before (store/state s ws)
        [state events refused] (d/step before (dt/resolve-command before command))]
    (store/commit! s [{:key key :ws ws :events (or events []) :state state
                       :outcome (or refused {:events (count events)})}])
    refused))

(deftest a-commit-is-read-back-and-a-repeated-key-answered
  (with-store [s]
    (commit-command! s "ws1" "k1" {:command :open-unit :unit "u" :project "p" :goal "g" :session-cwd "/tmp"})
    (is (= :open (get-in (store/state s "ws1") [:units "u" :status])))
    (is (= {:events 1} (store/command-outcome s "k1")))
    (is (nil? (store/command-outcome s "never-sent")))
    (is (= 1 (store/position s)))
    (is (= ["ws1"] (store/workstreams s)))
    (is (= [{:ws "ws1" :pos 1 :state (store/state s "ws1")}] (store/changed-since s 0)))
    (is (empty? (store/changed-since s 1)) "nothing changed after the last position")
    (is (empty? (store/verify s)))))

(deftest commit-answers-each-entry-s-position
  (with-store [s]
    (is (= [1 nil 3]
           (store/commit! s [{:key "a" :ws "w1" :outcome {} :state (d/fold [{:event :unit-opened :unit "u" :project "p" :goal "g"}])
                              :events [{:event :unit-opened :unit "u" :project "p" :goal "g"}]}
                             {:key "b" :ws "w1" :outcome {:refused {}} :events [] :state nil}
                             {:key "c" :ws "w2" :outcome {} :state (d/fold [{:event :unit-opened :unit "v" :project "p" :goal "g"}
                                                                          {:event :unit-opened :unit "x" :project "p" :goal "g"}])
                              :events [{:event :unit-opened :unit "v" :project "p" :goal "g"}
                                       {:event :unit-opened :unit "x" :project "p" :goal "g"}]}])))
    (is (= [["w1" 1] ["w2" 3]] (map (juxt :ws :pos) (store/changed-since s 0))))))

(deftest the-store-refuses-illegal-rows-written-around-the-domain
  (with-store [s]
    (let [c (:conn s)
          refused? (fn [sql] (try (jdbc/execute! c sql) false
                                  (catch java.sql.SQLException _ true)))]
      (jdbc/execute! c ["INSERT INTO units(ws,id,status,held) VALUES ('w','u','open',0)"])
      (jdbc/execute! c ["INSERT INTO units(ws,id,status,held) VALUES ('w','h','open',1)"])
      (jdbc/execute! c ["INSERT INTO turns(ws,id,unit,status,call_id) VALUES ('w','t1','u','asked','c1')"])
      (testing "two active turns on one unit"
        (is (refused? ["INSERT INTO turns(ws,id,unit,status) VALUES ('w','t2','u','running')"])))
      (testing "an asked turn without its call, and a call on a turn not asked"
        (is (refused? ["INSERT INTO turns(ws,id,unit,status) VALUES ('w','t3',NULL,'asked')"]))
        (is (refused? ["INSERT INTO turns(ws,id,unit,status,call_id) VALUES ('w','t4',NULL,'running','c')"])))
      (testing "two running conductor turns"
        (jdbc/execute! c ["INSERT INTO turns(ws,id,unit,status) VALUES ('w','k1',NULL,'running')"])
        (is (refused? ["INSERT INTO turns(ws,id,unit,status) VALUES ('w','k2',NULL,'running')"])))
      (testing "an active turn on a held unit"
        (is (refused? ["INSERT INTO turns(ws,id,unit,status) VALUES ('w','t5','h','running')"])))
      (testing "an open ask binding a turn that is not asked, or a call bound twice"
        (jdbc/execute! c ["INSERT INTO operator_asks(ws,id,status) VALUES ('w','a1','open')"])
        (jdbc/execute! c ["INSERT INTO operator_asks(ws,id,status) VALUES ('w','a2','open')"])
        (is (refused? ["INSERT INTO ask_binds(ws,ask,turn) VALUES ('w','a1','k1')"]))
        (jdbc/execute! c ["INSERT INTO ask_binds(ws,ask,turn) VALUES ('w','a1','t1')"])
        (is (refused? ["INSERT INTO ask_binds(ws,ask,turn) VALUES ('w','a2','t1')"])))
      (testing "an open ask concerning a unit with a running turn, seen at commit"
        (let [bad (-> d/empty-state
                      (assoc-in [:units "u"] {:id "u" :status :open :held? false :unknowns #{}})
                      (assoc-in [:turns "t1"] {:id "t1" :unit "u" :status :running})
                      (assoc-in [:turns "t2"] {:id "t2" :unit nil :status :asked :call {:id "c"}})
                      (assoc-in [:operator-asks "a"] {:id "a" :binds #{"t2"} :concerns "u" :status :open}))]
          (is (thrown? Exception (store/commit! s [{:key "k" :ws "w2" :outcome {} :state bad
                                                   :events [{:event :x}]}]))))))))

(deftest a-batch-that-breaks-a-constraint-writes-nothing
  (with-store [s]
    (commit-command! s "ws1" "k1" {:command :open-unit :unit "u" :project "p" :goal "g" :session-cwd "/tmp"})
    (let [bad (-> (store/state s "ws1")
                  (assoc-in [:turns "t1"] {:id "t1" :unit "u" :status :running})
                  (assoc-in [:turns "t2"] {:id "t2" :unit "u" :status :running}))]
      (is (thrown? Exception
                   (store/commit! s [{:key "k2" :ws "ws1" :outcome {} :state bad
                                      :events [{:event :turn-started :turn "t1" :unit "u"}
                                               {:event :turn-started :turn "t2" :unit "u"}]}])))
      (is (= 1 (store/position s)) "no event of the refused batch was written")
      (is (nil? (store/command-outcome s "k2")))
      (is (empty? (store/verify s))))))

(def stored-state-equals-the-fold
  (prop/for-all [commands (gen/vector dt/gen-command 0 40)]
    (with-store [s]
      (doseq [[i c] (map-indexed vector commands)]
        (commit-command! s "ws" (str "k" i) c))
      (and (empty? (store/verify s))
           (= (store/state s "ws") (d/fold (store/events s "ws")))
           (do (store/rebuild! s) (empty? (store/verify s)))))))

(deftest generated-commits-keep-the-cache-equal-to-the-fold
  (let [r (tc/quick-check 60 stored-state-equals-the-fold)]
    (is (:pass? r) (pr-str (select-keys r [:shrunk])))))

(deftest verify-reports-a-cache-that-drifted
  (with-store [s]
    (commit-command! s "ws1" "k1" {:command :open-unit :unit "u" :project "p" :goal "g" :session-cwd "/tmp"})
    (jdbc/execute! (:conn s) ["UPDATE workstreams SET state = ? WHERE ws = 'ws1'" (pr-str d/empty-state)])
    (is (= #{:state-differs-from-fold :rows-differ-from-state} (set (map :problem (store/verify s)))))
    (store/rebuild! s)
    (is (empty? (store/verify s)) "a rebuild restores the cache from the ledger")))

(def ^:private identity* {:project :nido :external-refs [] :created-at "2026-11-23T00:00:00Z"})

(defn- row [kind at] {:kind kind :at at :ext "edn"})

(deftest an-entry-is-kept-by-seq-in-the-commit-that-appends-it
  (with-store [s]
    (is (nil? (commit-command! s "nido/ws" "c" {:command :create-workstream :identity identity*})))
    (is (nil? (commit-command! s "nido/ws" "a1" {:command :append-entry :after 0
                                                 :row (row :intent "t1") :content "{:format :intent}"})))
    (is (nil? (commit-command! s "nido/ws" "a2" {:command :append-entry :after 1
                                                 :row (row :note "t2") :content "a note"})))
    (is (= [1 2] (map :seq (:ledger (store/state s "nido/ws")))))
    (is (= {1 "{:format :intent}" 2 "a note"} (store/entry-contents s "nido/ws" [1 2 3]))
        "a seq the ledger does not hold is left out")
    (is (= #{"nido/ws"} (set (keys (store/states s "nido/")))))
    (is (empty? (store/states s "brian/")))
    (testing "an append made against a ledger that has moved writes nothing"
      (is (= :stale (:reason (commit-command! s "nido/ws" "a3" {:command :append-entry :after 1
                                                                :row (row :note "t3") :content "late"}))))
      (is (= {2 "a note"} (store/entry-contents s "nido/ws" [2]))))
    (testing "the entries table is a cache the events rebuild"
      (jdbc/execute! (:conn s) ["DELETE FROM entries"])
      (is (= #{:entries-differ-from-events} (set (map :problem (store/verify s)))))
      (store/rebuild! s)
      (is (empty? (store/verify s)))
      (is (= {1 "{:format :intent}" 2 "a note"} (store/entry-contents s "nido/ws" [1 2]))))))

(deftest nothing-rewrites-an-entry
  (with-store [s]
    (commit-command! s "nido/ws" "c" {:command :create-workstream :identity identity*})
    (commit-command! s "nido/ws" "a1" {:command :append-entry :after 0 :row (row :note "t") :content "one"})
    (is (thrown? Exception (jdbc/execute! (:conn s) ["INSERT INTO entries(ws,seq,content) VALUES ('nido/ws',1,'two')"])))
    (is (= {1 "one"} (store/entry-contents s "nido/ws" [1])))))

(deftest a-summary-is-kept-with-the-state-it-reads
  (with-store [s]
    (commit-command! s "nido/a" "c-a" {:command :create-workstream
                                        :identity (assoc identity* :title {:text "Alpha" :basis "b"})})
    (commit-command! s "nido/b" "c-b" {:command :create-workstream :identity identity*})
    (commit-command! s "brian/c" "c-c" {:command :create-workstream :identity (assoc identity* :project :brian)})
    (testing "every committed workstream is summarised under the project its key names"
      (is (= #{"nido/a" "nido/b"} (set (keys (store/summaries s "nido")))))
      (is (= "Alpha" (get-in (store/summaries s "nido") ["nido/a" :title])))
      (is (= #{"brian/c"} (set (keys (store/summaries s "brian"))))))
    (testing "only a workstream with an open ask is asking, and asking answers its state"
      (is (empty? (store/asking s "nido")))
      (is (nil? (commit-command! s "nido/a" "stop" {:command :raise-conductor-stop :ask "ask-1"
                                                    :content {:question "Go on?" :explored "e" :recommendation "r" :options ["yes" "no"]}})))
      (is (= ["nido/a"] (keys (store/asking s "nido"))))
      (is (= ["ask-1"] (get-in (store/summaries s "nido") ["nido/a" :asks])))
      (is (= "ask-1" (:id (first (d/awaiting-person (get (store/asking s "nido") "nido/a"))))))
      (is (empty? (store/asking s "brian"))))
    (testing "a closed workstream owes no move, whatever ask is open on it"
      (commit-command! s "nido/a" "close" {:command :close-workstream})
      (is (empty? (store/asking s "nido"))))
    (is (empty? (store/verify s)))
    (testing "a summary that drifted from its state is reported, and a rebuild restores it"
      (jdbc/execute! (:conn s) ["UPDATE summaries SET data = '{}' WHERE ws = 'nido/b'"])
      (is (= [{:ws "nido/b" :problem :summary-differs-from-state}] (store/verify s)))
      (store/rebuild! s)
      (is (empty? (store/verify s))))))

(deftest a-store-without-summaries-is-summarised-when-opened
  (let [path (str (temp-dir) "/core.db")
        s    (store/open path)]
    (commit-command! s "nido/a" "c-a" {:command :create-workstream :identity identity*})
    (jdbc/execute! (:conn s) ["DELETE FROM summaries"])
    (store/close s)
    (let [s (store/open path)]
      (try
        (is (= #{"nido/a"} (set (keys (store/summaries s "nido")))))
        (is (empty? (store/verify s)))
        (finally (store/close s))))))

(def ^:private every-summary-equals-the-fold
  (prop/for-all [commands (gen/vector dt/gen-command 0 40)]
    (with-store [s]
      (doseq [[i c] (map-indexed vector commands)]
        (commit-command! s "nido/ws" (str "k" i) c))
      (let [stored (store/summaries s "nido")]
        (or (empty? (store/workstreams s))
            (= {"nido/ws" (d/summary (d/fold (store/events s "nido/ws")))} stored))))))

(deftest every-command-keeps-its-summary-equal-to-the-fold
  (let [r (tc/quick-check 60 every-summary-equals-the-fold)]
    (is (:pass? r) (pr-str (select-keys r [:shrunk])))))

(deftest a-project-is-read-whole-in-one-pass
  (with-store [s]
    (commit-command! s "nido/a" "c-a" {:command :create-workstream :identity identity*})
    (commit-command! s "nido/a" "a1" {:command :append-entry :after 0 :row (row :closed "t1") :content "{:format :closed :outcome :done}"})
    (commit-command! s "nido/a" "a2" {:command :append-entry :after 1 :row (row :note "t2") :content "after"})
    (commit-command! s "nido/b" "c-b" {:command :create-workstream :identity identity*})
    (commit-command! s "nido/c" "c-c" {:command :create-workstream :identity identity*})
    (commit-command! s "nido/c" "close" {:command :close-workstream})
    (commit-command! s "brian/d" "c-d" {:command :create-workstream :identity (assoc identity* :project :brian)})
    (testing "every workstream of the project, and no other's"
      (let [r (store/project-states s "nido" {})]
        (is (= #{"nido/a" "nido/b" "nido/c"} (set (keys (:states r)))))
        (is (= (store/state s "nido/a") (get-in r [:states "nido/a"])))
        (is (not (contains? r :summaries)))
        (is (not (contains? r :contents)))))
    (testing "open only, read off the summary"
      (is (= #{"nido/a" "nido/b"} (set (keys (:states (store/project-states s "nido" {:open? true})))))))
    (testing "the summaries beside the states"
      (is (= (store/summaries s "nido") (:summaries (store/project-states s "nido" {:summaries? true})))))
    (testing "the newest entry of the named kinds, for those that hold one"
      (is (= {"nido/a" {1 "{:format :closed :outcome :done}"}}
             (:contents (store/project-states s "nido" {:newest-of [:closed :reopened]})))))))
