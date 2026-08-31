(ns tasks.nido-ticket-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.lane.facets :as facets]
   [nido.coordinator.record.tickets :as tickets]
   [nido.coordinator.record.workstream :as workstream]
   [nido.coordinator.work :as work]
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

(deftest apply-cmd-resolves-ws-and-calls-work-apply
  (let [calls (atom [])]
    (with-redefs [workstream/find-by-ref-id (fn [_ br] {:id (str "ws-" br)})
                  work/apply! (fn [p id] (swap! calls conj [p id]) {:decision :applied})]
      (t/apply-cmd ":project" "brian" ":br" "BR-5")
      (is (= [[:brian "ws-BR-5"]] @calls) "resolves the ws by BR and calls work/apply!"))))

(deftest apply-cmd-no-workstream-exits-nonzero
  ;; apply-cmd's exit path goes through the redefable t/exit! (matches this
  ;; codebase's exit-test convention, e.g. nido-transcribe/nido-notion-preprocess-cmd)
  ;; instead of a raw System/exit, so the test can capture the code without
  ;; killing the test JVM.
  (let [exit-code (atom nil)
        err       (java.io.StringWriter.)]
    (with-redefs [workstream/find-by-ref-id (fn [_ _] nil)
                  work/apply! (fn [& _] (throw (ex-info "should not be called" {})))
                  t/exit! (fn [c] (reset! exit-code c))]
      (binding [*err* err]
        (t/apply-cmd ":project" "brian" ":br" "BR-none")))
    (is (= 1 @exit-code) "exits non-zero when no workstream is found for the BR")
    (is (re-find #"no workstream for BR-none" (str err)))))
