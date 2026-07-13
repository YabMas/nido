(ns tasks.nido-findings-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.findings :as findings]
   [tasks.nido-findings :as t]))

(deftest file-cmd-reads-edn-and-calls-file!
  (let [calls (atom nil)
        tmp   (fs/create-temp-file)]
    (spit (str tmp) (pr-str {:items [{:summary "A" :severity :blocker}]
                             :staging-ref "s://b"}))
    (with-redefs [findings/file! (fn [p w opts] (reset! calls [p w opts]) {:round 1 :queued "/q"})]
      (t/file-cmd ":project" "brian" ":ws" "ws-1" ":file" (str tmp) ":session" "sess-1")
      (let [[p w opts] @calls]
        (is (= :brian p))
        (is (= "ws-1" w))
        (is (= [{:summary "A" :severity :blocker}] (:items opts)))
        (is (= "s://b" (:staging-ref opts)))
        (is (= "sess-1" (:session opts)))))))

(deftest resolve-cmd-parses-items-and-calls-resolve!
  (let [calls (atom nil)]
    (with-redefs [findings/resolve! (fn [p w ids by] (reset! calls [p w ids by]) {})]
      (t/resolve-cmd ":project" "brian" ":ws" "ws-1" ":items" "[f1 f3]" ":by" "brian#812")
      (is (= [:brian "ws-1" ["f1" "f3"] "brian#812"] @calls)))))
