(ns nido.coordinator.notion-cache-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.notion-cache :as nc]
   [nido.coordinator.sources.state :as sstate]
   [nido.coordinator.state :as cstate]))

(deftest parse-priority-rank-cases
  (is (= 0 (nc/parse-priority-rank "0 – Release Blocker")) "en-dash separator")
  (is (= 1 (nc/parse-priority-rank "1 - Must")))
  (is (= 2 (nc/parse-priority-rank "2 - Should")))
  (is (= 3 (nc/parse-priority-rank "3 - Could")))
  (is (= 5 (nc/parse-priority-rank "5 - Wont")))
  (is (nil? (nc/parse-priority-rank nil)) "nil in → nil")
  (is (nil? (nc/parse-priority-rank "")) "blank → nil")
  (is (nil? (nc/parse-priority-rank "Must")) "no leading digit → nil"))

(deftest pages-snapshot-shape
  (let [pages [{:page-id "p1" :status "Not started" :priority "2 - Should"
                :ball-holder {:type "people" :people [{:id "u1"} {:id "u2"}]}}
               {:page-id "p2" :status "In progress" :priority "0 – Release Blocker"
                :ball-holder nil}
               {:page-id "p3"}]                                  ; no props at all
        snap  (nc/pages-snapshot pages)]
    (is (= {:status "Not started" :priority 2 :ball-ids #{"u1" "u2"}} (get snap "p1")))
    (is (= {:status "In progress" :priority 0 :ball-ids #{}} (get snap "p2")))
    (is (= {:status nil :priority nil :ball-ids #{}} (get snap "p3")))))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(deftest project-page-facts-merges-notion-view-snapshots
  (with-tmp
    (fn [_tmp]
      (sstate/write-state! "viewA"
        {:type :notion-view :source-config {:project :brian}
         :pages {"p1" {:status "Not started" :priority 2 :ball-ids #{}}}})
      (sstate/write-state! "viewB"
        {:type :notion-view :source-config {:project :brian}
         :pages {"p2" {:status "In progress" :priority 0 :ball-ids #{"u1"}}}})
      (sstate/write-state! "otherProj"
        {:type :notion-view :source-config {:project :fukan}
         :pages {"p9" {:status "x" :priority 1 :ball-ids #{}}}})
      (sstate/write-state! "notion-sync-brian"
        {:type :notion-sync :pages {"pX" {:status "x" :priority 9 :ball-ids #{}}}})
      (let [facts (nc/project-page-facts :brian)]
        (is (= 2 (get-in facts ["p1" :priority])))
        (is (= 0 (get-in facts ["p2" :priority])))
        (is (nil? (get facts "p9")) "other project excluded")
        (is (nil? (get facts "pX")) "non-:notion-view snapshot excluded")))))

(deftest project-page-facts-empty-when-no-snapshots
  (with-tmp
    (fn [_tmp]
      (is (= {} (nc/project-page-facts :nobody))
          "no matching notion-view snapshots => empty map, not nil/error"))))
