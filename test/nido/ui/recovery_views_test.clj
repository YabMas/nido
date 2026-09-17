(ns nido.ui.recovery-views-test
  "The Operations page's session-recovery section, rendered from an overview."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.ui.views :as views]))

(def ^:private empty-overview
  {:counts {:owed 0 :recovering 0 :needs-you 0 :waiting 0 :restored 0} :causes []
   :feed {:from nil :events [] :next nil}})

(deftest a-quiet-machine-says-nothing-failed
  (let [html (views/recovery-fragment empty-overview)]
    (is (str/includes? html "id=\"recovery\"") "the poll patches the section by this id")
    (is (str/includes? html "No session has failed to start."))))

(deftest an-unreadable-recovery-says-so
  (is (str/includes? (views/recovery-fragment nil) "could not be read")
      "a section with nothing in it would read as a machine with nothing failing"))

(deftest a-parked-recovery-links-to-its-gate
  (let [html (views/recovery-fragment
              {:counts {:owed 1 :recovering 0 :needs-you 1 :waiting 0 :restored 0}
               :causes [{:state :needs-you :cause "brian-1a4f" :gate-ws "ws-r" :ws-id "ws-r"
                         :sessions ["brian/impl-br-6346"] :failures ["F1"]
                         :message "Advancing shared cluster failed" :last-at "2026-09-17T10:00:00Z"
                         :diagnosis {:verdict :project-defect :cause "the migration needs a table it does not create"}
                         :prs [{:url "https://github.com/o/r/pull/1" :title "fix migration"}]
                         :landed [] :restores {} :runs 1}]
               :feed {:from nil :next "0001789000000000~failure-kept~F0"
                      :events [{:at "2026-09-17T10:00:00Z" :kind :failure-kept :session "brian/impl-br-6346"
                                :message "Advancing shared cluster failed"}]}})]
    (is (str/includes? html "needs you"))
    (is (str/includes? html "/?sel=nido:ws-r") "the person is sent to the gate that asks them")
    (is (str/includes? html "project-defect"))
    (is (str/includes? html "https://github.com/o/r/pull/1"))
    (is (str/includes? html "Activity"))
    (is (str/includes? html "/operations?feed=0001789000000000%7Efailure-kept%7EF0")
        "the older page is a link away")))

(deftest an-older-page-keeps-its-position-across-polls
  (let [html (str (views/operations-page {:active :operations} []
                                         (assoc empty-overview :feed {:from "0001789000000000~failure-kept~F9"
                                                                      :events [] :next nil})))]
    (is (str/includes? html "_fragment/operations?feed=0001789000000000%7Efailure-kept%7EF9")
        "the poll asks for the page being read, not the newest")
    (is (str/includes? html "href=\"/operations\"") "and there is a way back to the newest")))

(deftest the-operations-page-carries-recovery-above-the-backlog
  (let [html (str (views/operations-page {:active :operations} [] empty-overview))]
    (is (< (str/index-of html "id=\"recovery\"") (str/index-of html "id=\"operations\""))
        "what is happening now sits above the backlog")))
