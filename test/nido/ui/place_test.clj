(ns nido.ui.place-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nido.coordinator.work :as work]
            [nido.ui.place :as place]
            [nido.ui.view-state :as view-state]))

(def ^:private table @#'place/table)

(defn- sent-from [path]
  {:headers {"referer" (str "http://localhost:8800" path)}})

(deftest the-table-declares-every-surface-a-view-state-can-name
  ;; One path per branch of view-state's surface reading, the catch-all included.
  (let [named (set (map #(:surface (view-state/parse {:uri % :query-string nil}))
                        ["/" "/workstreams" "/domain" "/functionality" "/structure" "/operations"
                         "/development" "/nowhere-the-dashboard-names"]))]
    (is (= named (set (keys table))))))

(def ^:private chat-pages
  "Every route whose page carries the side chat — each route, so the Operations pages, which share
   one surface, are listed one by one. The catalog is not here: it carries its own chat."
  ["/" "/workstreams" "/workstreams?sel=nido:ws-1" "/workstreams/nido/ws-1"
   "/domain" "/functionality" "/structure" "/structure/drains"
   "/operations" "/operations/improvements" "/operations/recovery" "/operations/review-queue"
   "/development"])

(deftest every-page-carrying-the-chat-has-a-declared-place
  (with-redefs [work/workstream (fn [p w _] {:label "a workstream" :stage :design :project p :ws-id w})]
    (doseq [route chat-pages]
      (let [{:keys [known? page]} (place/place (sent-from route))]
        (testing route
          (is known?)
          (is (not= :other (:surface page)) "a chat page the dashboard's view-state does not name")
          (is (contains? table (:surface page)) "a chat page whose surface the table does not declare"))))))

(deftest a-new-page-carrying-the-chat-is-listed-here
  ;; The shell is given the side chat's panel at rail-ctx, once per page handler; the slices share
  ;; one, and the catalog's drops the panel. A count that moves means a page was added or removed:
  ;; list its route in chat-pages above, then move this number.
  (let [src (slurp (io/file "src/nido/ui/server.clj"))]
    (is (= 12 (count (re-seq #"\(rail-ctx req" src))))))

(deftest a-selected-workstream-is-named-with-its-gate-and-open-entry
  (with-redefs [work/workstream
                (fn [p w entry]
                  (is (= ["nido" "ws-1" 24] [p w entry]) "read at the entry the page has open")
                  {:project p :ws-id w
                   :label "Side chat knows the page" :stage :design :selected-seq 24
                   :entries [{:seq 24 :kind :design :title "Two task operations"}]
                   :gate {:ask {:question "Approve this design?"}
                          :actions [{:id :approve :label "Approve"} {:id :amend :label "Amend"}]}})]
    (let [p    (place/place (sent-from "/workstreams?sel=nido:ws-1&entry=24"))
          refs (place/references p)]
      (is (= {:kind :workstream :project "nido" :ws-id "ws-1" :title "Side chat knows the page"
              :stage :design
              :gate {:question "Approve this design?" :actions ["Approve" "Amend"]}
              :entry {:seq 24 :kind :design :title "Two task operations"}}
             (:subject p)))
      (is (= ["page · Workstreams" "workstream · nido/ws-1" "gate · open" "entry · 24"]
             (map :label refs)))
      (is (str/includes? (:detail (nth refs 2)) "Approve this design?")))))

(deftest the-subject-is-the-workstream-the-work-plane-resolved
  ;; A page id in the location resolves to the workstream that took the page: the subject
  ;; names that one, the id its title, stage and gate belong to.
  (with-redefs [work/workstream (fn [p _ _] {:project p :ws-id "ws-minted" :label "Move Licences"
                                             :stage :triage})]
    (let [p (place/place (sent-from "/workstreams?sel=brian:pg-bare"))]
      (is (= ["brian" "ws-minted"] ((juxt :project :ws-id) (:subject p))))
      (is (= ["page · Workstreams" "workstream · brian/ws-minted"]
             (map :label (place/references p)))))))

(deftest a-ticket-with-no-workstream-is-a-page-with-nothing-open
  ;; A bare pane says "No nido workstream yet": the place is the known page, and names none.
  (with-redefs [work/workstream (fn [p w _] {:project p :ws-id w :bare? true :label "Move Licences"
                                             :stage :intake})]
    (let [p (place/place (sent-from "/workstreams?sel=brian:pg-bare"))]
      (is (:known? p))
      (is (nil? (:subject p)))
      (is (= ["page · Workstreams"] (map :label (place/references p)))))))

(deftest a-page-with-nothing-open-is-known-and-says-so
  (let [p (place/place (sent-from "/operations/recovery?feed=3"))]
    (is (:known? p))
    (is (nil? (:subject p)))
    (is (= "Operations · Recovery" (get-in p [:page :title])))
    (is (= ["page · Operations · Recovery"] (map :label (place/references p))))
    (is (str/includes? (:detail (first (place/references p))) "nothing is open"))))

(deftest a-request-that-names-no-page-is-an-unknown-place-never-left-out
  (doseq [req [{:headers {}} {:headers {"referer" ""}} {:headers {"referer" "::not a url"}}]]
    (let [p (place/place req)]
      (is (= {:known? false} p))
      (is (= ["place · unknown"] (map :label (place/references p)))
          "an unknown place is still a reference, worded apart from a page with nothing open"))))

(deftest a-page-is-placed-by-its-own-address-not-the-one-it-was-reached-from
  (with-redefs [work/workstream (fn [p w _] {:project p :ws-id w :label w :stage :design})]
    (let [req {:uri "/workstreams" :query-string "sel=nido:ws-drawn"
               :headers {"referer" "http://localhost:8800/workstreams?sel=nido:ws-before"}}]
      (is (= "ws-drawn" (get-in (place/page-place req) [:subject :ws-id])) "the page being drawn")
      (is (= "ws-before" (get-in (place/place req) [:subject :ws-id])) "a send's page is its Referer")
      (is (= {:known? false} (place/page-place {:uri nil :headers {}}))))))
