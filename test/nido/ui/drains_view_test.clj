(ns nido.ui.drains-view-test
  "The drains page as an operating surface, over literal drain values: each card leads with its
   phase and the one act it owes the person, stands escalations and proposals as sections of
   their own, shows each proposal as the recipe it would install diffed line by line against the
   current one, and renders every reading the value carries — with nothing folded inside #drains."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.ui.views :as views]))

(def ^:private current
  {:steps ["read the site" "rewrite the call" "run the tests"] :ready-gates ["bb test"]
   :standing-orders [] :counter {:command "bb count"}})

(def ^:private proposed
  {:steps ["rewrite the call" "read the site" "run the tests"] :ready-gates ["bb test" "review"]
   :standing-orders ["never touch main"] :plan-template "sites:\nwhy:" :counter {:command "bb count"}})

(defn- a-drain [& {:as over}]
  (merge {:project "brian" :ws-id "d1" :phase :draining :version 3 :recipe current
          :charter {:exit "wrap-conn deleted" :established-by {:ws-id "ws-origin"} :at "2026-11-20T10:00:00Z"}
          :proposals [] :census {:seq 9 :remaining 4 :groups [{:dir "src/a" :count 3} "src/b"] :at "2026-11-26T10:00:00Z"}
          :trend [7 5 4]
          :plans [{:seq 11 :sites ["src/p.clj"] :rests-on [8] :start :after-landing :state :open}]
          :slices [{:ws-id "s-old" :plan 5 :started-at "2026-11-21T00:00:00Z" :state :cleared :sites ["src/x.clj"] :misfits 1}
                   {:ws-id "s-new" :plan 6 :started-at "2026-11-22T00:00:00Z" :state :cleared :sites ["src/y.clj"] :misfits 0}
                   {:ws-id "s-run" :plan 7 :started-at "2026-11-23T00:00:00Z" :state :started :sites ["src/z.clj"] :misfits 0}]
          :by-state {:cleared 2 :started 1}
          :misfits [{:seq 12 :slice "s-old" :site "src/x.clj:40" :note "macro call site"}]
          :residue [{:seq 13 :site "src/r.clj" :disposition {:to :follow-up :plan 11}}
                    {:seq 14 :site "src/q.clj" :disposition nil}]
          :escalations []}
         over))

(defn- html [d] (views/drains-fragment [d] nil "brian"))

(defn- primaries [h] (re-seq #"class=\"btn btn-primary\"[^>]*>([^<]*)<" h))

(defn- lead-of [h]
  (second (re-find #"class=\"drain-lead\">(.*?)</div>" h)))

(deftest one-due-act-leads-each-drain
  (testing "Answer outranks everything, and names the first escalation in the value's order"
    (let [h (html (a-drain :escalations [{:seq 2 :question "which?"} {:seq 3 :question "later" :slice "s-old"}]
                           :proposals [{:seq 20 :reason "r" :at "2026-11-26T00:00:00Z" :recipe proposed}]
                           :phase :recipe-proposed))]
      (is (= ["Answer"] (map second (primaries h))))
      (is (str/includes? (lead-of h) "/workstreams/brian/d1") "the drain's own escalation, first in order")))
  (testing "Approve the oldest pending proposal"
    (let [h (html (a-drain :phase :recipe-proposed
                           :proposals [{:seq 21 :reason "b" :at "x" :recipe proposed}
                                       {:seq 20 :reason "a" :at "x" :recipe proposed}]))]
      (is (= ["Approve proposal #20"] (map second (primaries h))))
      (is (str/includes? (lead-of h) "/drains/brian/d1/approve/20?scope=brian"))))
  (testing "Land the oldest-started cleared slice"
    (let [h (html (a-drain))]
      (is (= ["Land s-old"] (map second (primaries h))))))
  (testing "Close once the census reads zero"
    (let [h (html (a-drain :phase :exhausted :slices [] :by-state {} :misfits []))]
      (is (= ["Close the drain"] (map second (primaries h))))))
  (testing "a drain that owes the person nothing shows its phase tag and no primary act"
    (let [h (html (a-drain :slices [] :by-state {} :misfits []))]
      (is (empty? (primaries h)))
      (is (str/includes? (lead-of h) "chip tag-working")))))

(deftest escalations-stand-as-their-own-section
  (let [h (html (a-drain :escalations [{:seq 2 :question "which way?"}
                                       {:seq 3 :summary "reseat failed" :question "retry?" :slice "s-old"}]))]
    (is (str/includes? h ">Escalations</h3>"))
    (is (str/includes? h "which way?"))
    (is (str/includes? h "reseat failed"))
    (is (str/includes? h "/workstreams/brian/s-old") "a slice's escalation is answered on the slice")))

(deftest proposal-shown-as-its-recipe-diffed
  (let [h (html (a-drain :phase :recipe-proposed
                         :proposals [{:seq 20 :reason "cover macros" :at "2026-11-26T09:00:00Z" :recipe proposed}]))]
    (is (str/includes? h ">Recipe proposal #20</h3>"))
    (is (str/includes? h "cover macros"))
    (is (str/includes? h "2026-11-26T09:00:00Z") "its timestamp")
    (doseq [field ["steps" "ready gates" "standing orders" "plan template" "census counter"]]
      (is (str/includes? h (str "<div class=\"rd-field-h\">" field "</div>")) field))
    (testing "a reorder of steps is not shown as no change"
      (is (or (str/includes? h "rd-add\" title=\"added\"><span class=\"rd-sign\">+</span>read the site")
              (str/includes? h "rd-add\" title=\"added\"><span class=\"rd-sign\">+</span>rewrite the call")))
      (is (re-find #"rd-del[^>]*><span class=\"rd-sign\">−</span>(read the site|rewrite the call)" h)))
    (is (str/includes? h "rd-same\" title=\"unchanged\"><span class=\"rd-sign\"> </span>bb test")
        "a line both hold in the same place is unchanged")
    (is (str/includes? h "rd-add\" title=\"added\"><span class=\"rd-sign\">+</span>review"))
    (is (str/includes? h "rd-same\" title=\"unchanged\"><span class=\"rd-sign\"> </span>command: bb count")))
  (testing "with no current recipe, every line is added"
    (let [h (html (a-drain :phase :recipe-proposed :version nil :recipe nil
                           :proposals [{:seq 2 :reason "first cut" :at "x" :recipe proposed}]))]
      (is (not (str/includes? h "rd-same")))
      (is (not (str/includes? h "rd-del")))
      (is (str/includes? h "+</span>never touch main")))))

(deftest a-multiline-recipe-entry-diffs-line-by-line
  (let [cur  {:steps ["edit the site\n  then rerun" "bb test"] :counter {:command "bb count\n  --all"}}
        prop {:steps ["edit the site\n  then review" "bb test"] :counter {:command "bb count\n  --fast"}}
        h    (html (a-drain :phase :recipe-proposed :recipe cur
                            :proposals [{:seq 4 :reason "r" :at "x" :recipe prop}]))]
    (is (str/includes? h "rd-same\" title=\"unchanged\"><span class=\"rd-sign\"> </span>edit the site<")
        "the unchanged first line of a multiline step stays unchanged")
    (is (str/includes? h "rd-del\" title=\"removed\"><span class=\"rd-sign\">−</span>  then rerun<"))
    (is (str/includes? h "rd-add\" title=\"added\"><span class=\"rd-sign\">+</span>  then review<"))
    (is (str/includes? h "rd-same\" title=\"unchanged\"><span class=\"rd-sign\"> </span>command: bb count<"))
    (is (str/includes? h "rd-add\" title=\"added\"><span class=\"rd-sign\">+</span>  --fast<"))))

(deftest every-carried-reading-rendered
  (let [h (html (a-drain))]
    (testing "plans" (is (str/includes? h "#11")) (is (str/includes? h "src/p.clj"))
      (is (str/includes? h "rests on #8")) (is (str/includes? h "starts after they land")))
    (testing "misfits under their slice" (is (str/includes? h "src/x.clj:40")) (is (str/includes? h "macro call site"))
      (is (< (str/index-of h "s-old") (str/index-of h "macro call site") (str/index-of h "s-new"))))
    (testing "residue with its disposition, or none"
      (is (str/includes? h "to a follow-up slice, plan #11"))
      (is (str/includes? h "src/q.clj</span> — no disposition yet")))
    (testing "the census and its groups" (is (str/includes? h "4 remaining"))
      (is (str/includes? h "dir src/a · count 3")) (is (str/includes? h "src/b")))
    (testing "the charter's provenance" (is (str/includes? h "ws-origin")) (is (str/includes? h "2026-11-20T10:00:00Z")))
    (testing "slices as state tags with their sites"
      (is (str/includes? h "chip tag-you")) (is (str/includes? h "src/z.clj")))))

(deftest slice-state-totals-said-by-the-state-chip
  (let [h (html (a-drain))]
    (is (str/includes? h "2 <span class=\"chip tag-you\" title=\"cleared by the coordinator — yours to land\">cleared</span>"))
    (is (str/includes? h "1 <span class=\"chip tag-working\" title=\"the slice&apos;s session is building it\">started</span>"))
    (is (not (str/includes? h "2 cleared")))))

(deftest trend-drawn-as-a-sparkline
  (let [h (html (a-drain))]
    (is (str/includes? h "<svg aria-label=\"census readings under the current recipe: 7, 5, 4\""))
    (is (not (str/includes? h " → ")) "no arrow-joined trend")
    (is (not (str/includes? h "style=")))))

(deftest nothing-folded-in-the-polled-fragment
  (is (not (str/includes? (html (a-drain :phase :recipe-proposed
                                         :escalations [{:seq 2 :question "q"}]
                                         :proposals [{:seq 20 :reason "r" :at "x" :recipe proposed}]))
                          "<details"))))
