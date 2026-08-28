(ns nido.coordinator.notion-cache-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.coordinator.notion-cache :as nc]
   [nido.coordinator.sources.state :as sstate]
   [nido.coordinator.state :as cstate]
   [nido.platform.io :as io]))

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
                :title "Firefox loading" :id "BR-4659"
                :ball-holder {:type "people" :people [{:id "u1"} {:id "u2"}]}}
               {:page-id "p2" :status "In progress" :priority "0 – Release Blocker"
                :title "Modal" :id "BR-1" :ball-holder nil}
               {:page-id "p3"}]                                  ; no props at all
        snap  (nc/pages-snapshot pages)]
    (is (= {:status "Not started" :priority 2 :ball-ids #{"u1" "u2"}
            :title "Firefox loading" :br "BR-4659"} (get snap "p1")))
    (is (= {:status "In progress" :priority 0 :ball-ids #{}
            :title "Modal" :br "BR-1"} (get snap "p2")))
    (is (= {:status nil :priority nil :ball-ids #{} :title nil :br nil} (get snap "p3")))))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
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

(deftest project-page-facts-honors-board-views
  (with-tmp
    (fn [tmp]
      ;; registry restricts the board to :new-reports (drop :open-bugs)
      (fs/create-dirs (str (fs/path tmp "projects" "brian")))
      (io/write-edn! (str (fs/path tmp "projects" "brian" "notion-views.edn"))
                     {:database "db" :board-views [:new-reports]
                      :views {:new-reports {} :open-bugs {}}})
      (sstate/write-state! "vNew"
        {:type :notion-view :source-config {:project :brian :view :new-reports}
         :pages {"p1" {:status "Needs verification" :priority nil :ball-ids #{} :title "R" :br "BR-1"}}})
      (sstate/write-state! "vOpen"
        {:type :notion-view :source-config {:project :brian :view :open-bugs}
         :pages {"p2" {:status "Not started" :priority nil :ball-ids #{} :title "B" :br "BR-2"}}})
      (let [facts (nc/project-page-facts :brian)]
        (is (contains? facts "p1") ":new-reports page is a board view")
        (is (nil? (get facts "p2")) ":open-bugs excluded by :board-views")))))

(deftest project-page-facts-empty-when-no-snapshots
  (with-tmp
    (fn [_tmp]
      (is (= {} (nc/project-page-facts :nobody))
          "no matching notion-view snapshots => empty map, not nil/error"))))
