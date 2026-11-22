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
    (commit-command! s "ws1" "k1" {:command :open-unit :unit "u" :project "p" :goal "g"})
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
    (commit-command! s "ws1" "k1" {:command :open-unit :unit "u" :project "p" :goal "g"})
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
    (commit-command! s "ws1" "k1" {:command :open-unit :unit "u" :project "p" :goal "g"})
    (jdbc/execute! (:conn s) ["UPDATE workstreams SET state = ? WHERE ws = 'ws1'" (pr-str d/empty-state)])
    (is (= #{:state-differs-from-fold :rows-differ-from-state} (set (map :problem (store/verify s)))))
    (store/rebuild! s)
    (is (empty? (store/verify s)) "a rebuild restores the cache from the ledger")))

(defn- write! [home rel content]
  (let [f (io/file home rel)] (io/make-parents f) (spit f content) f))

(deftest legacy-entries-are-imported-as-written-and-the-files-only-read
  (let [home (temp-dir)
        f1   (write! home "projects/nido/workstreams/ws-a/entries/0001-intent.edn" "{:format :intent, :goal \"g\"}\n")
        f2   (write! home "projects/brian/workstreams/ws-b/entries/0001-baseline.edn" ";; odd spacing kept\n{:a   1}")
        before (into {} (for [f [f1 f2]] [f [(slurp f) (.lastModified f)]]))]
    (with-store [s]
      (is (= {:imported 2 :unchanged 0 :changed []} (store/import-legacy! s home)))
      (is (= [{:file "0001-baseline.edn" :content ";; odd spacing kept\n{:a   1}"}]
             (store/legacy-entries s "brian" "ws-b")))
      (testing "a re-run imports nothing twice"
        (is (= {:imported 0 :unchanged 2 :changed []} (store/import-legacy! s home))))
      (testing "an entry edited after import is reported, never overwritten"
        (spit f1 "{:format :intent, :goal \"edited\"}\n")
        (is (= [["nido" "ws-a" "0001-intent.edn"]] (:changed (store/import-legacy! s home))))
        (is (= "{:format :intent, :goal \"g\"}\n" (:content (first (store/legacy-entries s "nido" "ws-a")))))))
    (is (= (get before f2) [(slurp f2) (.lastModified f2)]) "the import never wrote a legacy file")))

(deftest a-real-nido-home-imports-without-loss
  ;; QA for the cut-over: run with NIDO_CORE_IMPORT_HOME pointing at a nido home (a snapshot, or
  ;; the real one, which the import only reads). Skipped when it is unset.
  (when-let [home (System/getenv "NIDO_CORE_IMPORT_HOME")]
    (with-store [s]
      (let [r (store/import-legacy! s home)
            files (count (#'store/legacy-entry-files home))]
        (println "import of" home ":" (dissoc r :changed) "of" files "entry files")
        (is (= files (:imported r)))
        (is (empty? (:changed r)))
        (is (= {:imported 0 :unchanged files :changed []} (store/import-legacy! s home)))))))
