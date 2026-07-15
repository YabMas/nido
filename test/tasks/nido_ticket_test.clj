(ns tasks.nido-ticket-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.facets :as facets]
   [nido.coordinator.tickets :as tickets]
   [nido.coordinator.workstream :as workstream]
   [tasks.nido-ticket :as t]))

(deftest complete-refreshes-facets-after-completing
  (let [calls (atom [])]
    (with-redefs [tickets/complete!         (fn [p br st disp] (swap! calls conj [:complete p br st disp]))
                  facets/refresh-for-ticket! (fn [p br] (swap! calls conj [:refresh p br]))]
      (t/complete-cmd ":project" "brian" ":br" "BR-1" ":status" "triaged" ":disposition" "real-bug")
      (is (= [[:complete :brian "BR-1" :triaged :real-bug]
              [:refresh :brian "BR-1"]]
             @calls)
          "complete! first, then a single facet refresh"))))

(deftest append-cmd-routes-to-the-workstream-ledger
  ;; Ledger unification Task 2: nido:ticket:append no longer writes to the
  ;; per-ticket store (tickets/append-entry! is gone) — it always lands on the
  ;; workstream carrying the BR-#### ref, via workstream/append-to-ref! (Task 1).
  (let [f     (java.io.File/createTempFile "nido-ticket-append-test" ".md")
        _     (spit f "body text")
        calls (atom [])]
    (with-redefs [workstream/append-to-ref!
                  (fn [project external-id entry content]
                    (swap! calls conj [project external-id entry content])
                    "/ws/x/entries/0001-note.md")]
      (t/append-cmd ":project" "brian" ":br" "BR-7" ":kind" "note"
                    ":session" "s1" ":run-id" "r1" ":file" (.getPath f))
      (is (= [[:brian "BR-7" {:kind :note :session "s1" :run-id "r1"} "body text"]]
             @calls)
          "append-cmd hands off to workstream/append-to-ref!, not tickets/"))))
