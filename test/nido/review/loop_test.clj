;; test/nido/review/loop_test.clj
(ns nido.review.loop-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.review.loop :as rloop]))

(defn- stage [name f] {:name name :run f})

(defn- capturing []
  (let [events (atom [])]
    [events (fn [e] (swap! events conj e))]))

(deftest stops-when-warden-says-stop
  (let [calls (atom [])
        [_ emit] (capturing)
        pipe [(stage :review (fn [c] (swap! calls conj :review)
                               (assoc c :findings [{:title "x"}])))
              (stage :warden  (fn [c] (swap! calls conj :warden)
                               (assoc c :control :stop)))
              (stage :fix    (fn [c] (swap! calls conj :fix) c))]
        out (rloop/run-loop {:run-id "r1" :max-iters 5 :pipeline pipe :emit emit})]
    (is (= :converged (:status out)))
    (is (not (some #{:fix} @calls)))))

(deftest escalate-is-terminal
  (let [[_ emit] (capturing)
        pipe [(stage :review (fn [c] (assoc c :findings [{:title "x"}])))
              (stage :warden  (fn [c] (assoc c :control :escalate
                                            :warden {:reason "redesign"})))
              (stage :fix    (fn [c] c))]
        out (rloop/run-loop {:run-id "r1" :max-iters 5 :pipeline pipe :emit emit})]
    (is (= :escalated (:status out)))))

(deftest caps-at-max-iters
  (let [[_ emit] (capturing)
        pipe [(stage :review (fn [c] (assoc c :findings [{:title (str (:iter c))}])))
              (stage :warden  (fn [c] (assoc c :control :continue :warden {:fix-findings nil})))
              (stage :fix    (fn [c] (update c :history (fnil conj []) {:iter (:iter c)})))]
        out (rloop/run-loop {:run-id "r1" :max-iters 3 :pipeline pipe :emit emit})]
    (is (= :max-iters (:status out)))
    (is (= 3 (count (:history out))))))

(deftest uncapped-when-max-iters-absent
  ;; No :max-iters => no iteration ceiling at all. Each round yields a fresh
  ;; finding, so `no-progress?` never fires and the run only ends when the
  ;; pipeline itself says stop — well past the 5 rounds that used to be the
  ;; hardcoded default.
  (let [[_ emit] (capturing)
        pipe [(stage :review (fn [c] (assoc c :findings [{:title (str (:iter c))}])))
              (stage :warden (fn [c] (cond-> (assoc c :warden {:fix-findings nil})
                                        (>= (:iter c) 9) (assoc :control :stop)
                                        (< (:iter c) 9)  (assoc :control :continue))))
              (stage :fix (fn [c] (update c :history (fnil conj []) {:iter (:iter c)})))]
        out (rloop/run-loop {:run-id "r1" :pipeline pipe :emit emit})]
    (is (= :converged (:status out)))
    (is (= 9 (:iter out)))))

(deftest stops-on-no-progress
  (let [[_ emit] (capturing)
        pipe [(stage :review (fn [c] (assoc c :findings [{:title "same"}])))
              (stage :warden  (fn [c] (assoc c :control :continue :warden {:fix-findings nil})))
              (stage :fix    (fn [c] (update c :history (fnil conj []) {:iter (:iter c)})))]
        out (rloop/run-loop {:run-id "r1" :max-iters 10 :pipeline pipe :emit emit})]
    (is (= :no-progress (:status out)))))

(deftest review-clean-terminates
  (let [[_ emit] (capturing)
        pipe [(stage :review (fn [c] (assoc c :findings [] :control :stop :status :clean)))
              (stage :warden  (fn [c] c))
              (stage :fix    (fn [c] c))]
        out (rloop/run-loop {:run-id "r1" :max-iters 5 :pipeline pipe :emit emit})]
    (is (= :clean (:status out)))))

(deftest review-failed-is-terminal
  (let [[_ emit] (capturing)
        pipe [(stage :review (fn [_] (throw (ex-info "codex review failed" {:reason :review-failed}))))
              (stage :warden (fn [c] c))
              (stage :fix (fn [c] c))]
        out (rloop/run-loop {:run-id "r1" :max-iters 3 :pipeline pipe :emit emit})]
    (is (= :review-failed (:status out)))))

(deftest emit-narrates-the-lifecycle
  (let [[events emit] (capturing)
        pipe [(stage :review (fn [c] (assoc c :findings [{:title "x"}])))
              (stage :warden  (fn [c] (assoc c :control :stop :warden {:decision :stop})))
              (stage :fix    (fn [c] c))]
        _ (rloop/run-loop {:run-id "r1" :max-iters 5 :pipeline pipe :emit emit
                           :cwd "/w" :base "main"})
        kinds (map :event @events)]
    (is (= :run-started (first kinds)))
    (is (= :run-finalized (last kinds)))
    (is (= [:phase-started :phase-finished]
           (->> @events (filter #(= :review (:phase %))) (map :event))))
    ;; fix never runs on :stop, so no fix phase events
    (is (not-any? #(= :fix (:phase %)) @events))))

(deftest emit-narrates-phase-error
  (let [[events emit] (capturing)
        pipe [(stage :review (fn [_] (throw (ex-info "boom" {:reason :review-failed}))))]
        _ (rloop/run-loop {:run-id "r1" :max-iters 2 :pipeline pipe :emit emit
                           :cwd "/w" :base "main"})]
    (is (some #(= :phase-errored (:event %)) @events))
    (is (= :review-failed (:status (last (filter #(= :run-finalized (:event %)) @events)))))))

;; ── The injected finding identity ──────────────────────────────────────────

(deftest injected-finding-key-detects-a-stall-the-default-cannot
  ;; Record findings carry no :file, :line-start or :title, so every one of them
  ;; keys to [nil nil nil] under the default — which makes the SAME finding and a
  ;; DIFFERENT one indistinguishable. This is the case the seam exists for: two
  ;; distinct findings must not read as a stall, and the same one must.
  (let [[_ emit] (capturing)
        findings [{:cites ["a"] :claim "one"}]
        pipe [(stage :judge (fn [c] (assoc c :findings findings)))
              (stage :amend (fn [c] (assoc c :control :continue)))]
        out (rloop/run-loop {:run-id "r1" :max-iters 10 :pipeline pipe :emit emit
                             :finding-key (juxt :cites :claim)})]
    (is (= :no-progress (:status out))
        "the same record finding twice is a stall")))

(deftest injected-finding-key-lets-a-changing-record-round-continue
  (let [[_ emit] (capturing)
        pipe [(stage :judge (fn [c] (assoc c :findings [{:cites ["a"]
                                                         :claim (str "round " (:iter c))}])))
              (stage :amend (fn [c] (assoc c :control :continue)))]
        out (rloop/run-loop {:run-id "r1" :max-iters 4 :pipeline pipe :emit emit
                             :finding-key (juxt :cites :claim)})]
    (is (= :max-iters (:status out))
        "a different finding each round is progress, so only the cap ends it")))

(deftest default-finding-key-is-the-handle-the-warden-filed-a-finding-under
  (is (= "h-7"
         (rloop/default-finding-key {:file "a.clj" :line-start 4 :line-end 9
                                     :title "t" :priority 1 :handle "h-7"})))
  (is (= "h-7"
         (rloop/default-finding-key {:file "moved.clj" :line-start 91
                                     :title "the same defect, said differently"
                                     :handle "h-7"}))
      "a restatement at a new place under a new title is one finding"))

(deftest default-finding-key-falls-back-to-the-diff-triple
  ;; A finding that never reached the warden has no handle. Falling back means
  ;; an unrecognised repeat, which costs a round; the alternative is every such
  ;; finding colliding on nil, which ends a run that was still working.
  (is (= ["a.clj" 4 "t"]
         (rloop/default-finding-key {:file "a.clj" :line-start 4 :line-end 9
                                     :title "t" :priority 1}))))

(deftest record-findings-all-collide-under-the-default-key
  ;; Not a wish — the reason the seam is not optional. Two unrelated record
  ;; findings are one key under the default, so an uncapped record loop would
  ;; stop on its second round no matter what the judge said.
  (is (= (rloop/default-finding-key {:cites ["a"] :claim "one"})
         (rloop/default-finding-key {:cites ["b"] :claim "two"}))))

;; ── A finding nothing can fix ───────────────────────────────────────────────

(deftest a-finding-raised-three-rounds-running-ends-the-run
  ;; Watched live: a survey got to two findings, resolved one each round and
  ;; re-raised the other under the same key. The SET differed every round, so
  ;; no-progress? never fired, while the one finding that mattered went round
  ;; and round.
  (let [[_ emit] (capturing)
        stuck {:id "cannot-fix"}
        pipe [(stage :judge (fn [c] (assoc c :findings [stuck {:id (str "fresh-" (:iter c))}])))
              (stage :amend (fn [c] (update c :history (fnil conj [])
                                            {:iter (:iter c) :findings (:findings c)})))]
        out (rloop/run-loop {:run-id "r" :pipeline pipe :emit emit :finding-key :id})]
    (is (= :unfixable (:status out)))
    (is (= ["cannot-fix"] (:unfixable out))
        "and it names the finding, not the round")))

(deftest a-run-that-keeps-resolving-findings-is-not-called-unfixable
  (let [[_ emit] (capturing)
        pipe [(stage :judge (fn [c] (assoc c :findings [{:id (str "new-" (:iter c))}])))
              (stage :amend (fn [c] (cond-> (update c :history (fnil conj [])
                                                    {:iter (:iter c) :findings (:findings c)})
                                      (>= (:iter c) 6) (assoc :control :stop))))]
        out (rloop/run-loop {:run-id "r" :pipeline pipe :emit emit :finding-key :id})]
    (is (= :converged (:status out)) "every finding was different, so nothing is stuck")))

(deftest two-rounds-of-the-same-finding-is-not-yet-unfixable
  ;; One re-raise is an amendment that missed; three is a wall. Ending on the
  ;; first repeat would stop loops that were about to succeed.
  (let [[_ emit] (capturing)
        pipe [(stage :judge (fn [c] (assoc c :findings [{:id "same"} {:id (str "x" (:iter c))}])))
              (stage :amend (fn [c] (cond-> (update c :history (fnil conj [])
                                                    {:iter (:iter c) :findings (:findings c)})
                                      (>= (:iter c) 2) (assoc :control :stop))))]
        out (rloop/run-loop {:run-id "r" :pipeline pipe :emit emit :finding-key :id})]
    (is (= :converged (:status out)))))

(deftest a-finding-in-two-rounds-does-not-end-the-run
  ;; The off-by-one this pins: the amend stage appends THIS round to history
  ;; before the check runs, so counting the current round as one of its own prior
  ;; appearances made three out of two — and a live run that had resolved
  ;; everything it could in two rounds ended a round early declaring the rest
  ;; unfixable.
  (let [[_ emit] (capturing)
        rounds (atom 0)
        pipe [(stage :judge (fn [c] (assoc c :findings [{:id "same"}])))
              (stage :amend (fn [c] (swap! rounds inc)
                              (cond-> (update c :history (fnil conj [])
                                              {:iter (:iter c) :findings (:findings c)})
                                (>= (:iter c) 2) (assoc :control :stop))))]
        out (rloop/run-loop {:run-id "r" :pipeline pipe :emit emit :finding-key :id})]
    (is (= :converged (:status out)) "two rounds of the same finding is not a wall")
    (is (= 2 @rounds))))

(deftest the-same-finding-is-named-once-however-often-it-was-raised
  (let [[_ emit] (capturing)
        pipe [(stage :judge (fn [c] (assoc c :findings [{:id "stuck"} {:id "stuck"}
                                                        {:id (str "fresh-" (:iter c))}])))
              (stage :amend (fn [c] (update c :history (fnil conj [])
                                            {:iter (:iter c) :findings (:findings c)})))]
        out (rloop/run-loop {:run-id "r" :pipeline pipe :emit emit :finding-key :id})]
    (is (= :unfixable (:status out)))
    (is (= ["stuck"] (:unfixable out)) "one entry, not one per raising")))

(deftest a-stuck-finding-is-named-rather-than-the-round-being-called-stalled
  ;; Both are true when a run ends holding the same findings, and only one says
  ;; which. Watched: a survey converged 8 → 1 → 1 with eleven of twelve items
  ;; confirmed, and reported :no-progress — sending a reader to look at
  ;; everything when the answer was a single claim.
  (let [[_ emit] (capturing)
        pipe [(stage :judge (fn [c] (assoc c :findings [{:id "immovable"}])))
              (stage :amend (fn [c] (update c :history (fnil conj [])
                                            {:iter (:iter c) :findings (:findings c)})))]
        out (rloop/run-loop {:run-id "r" :pipeline pipe :emit emit :finding-key :id})]
    (is (= :no-progress (:status out))
        "an identical set trips the stall check at round two, before three rounds pass")
    (is (= ["immovable"] (:unfixable out))
        "and it is named — a run that stops holding findings says which")))

(deftest a-round-hands-the-next-one-what-it-put-in-carry
  ;; The record pipelines' whole "repair the record you were pointed at" rule
  ;; rides on this: without it every judge falls back to re-reading the ledger.
  (let [seen (atom [])
        pipe [(stage :judge (fn [c]
                              (swap! seen conj (:under-repair (:carry c)))
                              (assoc c :findings [{:title (str "f" (:iter c))}])))
              (stage :amend (fn [c]
                              (assoc-in c [:carry :under-repair]
                                        (str "record-" (:iter c)))))]
        out (rloop/run-loop {:run-id "carry" :max-iters 3 :pipeline pipe
                             :finding-key :title})]
    (is (= [nil "record-1" "record-2"] @seen))
    ;; and it is still there for whoever reads the terminal ctx
    (is (= "record-3" (:under-repair (:carry out))))))

(deftest carry-survives-onto-a-ctx-that-stopped-in-the-first-stage
  ;; The success path of a nested baseline loop is exactly this shape: the judge
  ;; declares the record sufficient and the amend stage never runs, so a caller
  ;; reading the corrected record off the result reads it from an earlier round.
  (let [pipe [(stage :judge (fn [c]
                              (if (= 2 (:iter c))
                                (assoc c :control :stop)
                                (assoc c :findings [{:title "x"}]))))
              (stage :amend (fn [c]
                              (assoc-in c [:carry :under-repair]
                                        (str "record-" (:iter c)))))]
        out (rloop/run-loop {:run-id "carry2" :max-iters 4 :pipeline pipe
                             :finding-key :title})]
    (is (= :converged (:status out)))
    (is (= "record-1" (:under-repair (:carry out))))))

;; ── Ending on a judgement ───────────────────────────────────────────────────

(defn- judged-run
  "A record-shaped pipeline: a judge reporting whatever `stuck?` says for the
   round PLUS a finding unique to that round, and an amend that appends to
   history the way the real one does.

   The per-round finding is what makes this the case `unfixable` exists for: a
   set that repeats trips `no-progress?` at round two and the per-finding check
   never gets to run. Returns [result judge-calls amend-calls]."
  [stuck? & {:as extra}]
  (let [judges (atom 0) amends (atom 0)
        pipe [(stage :judge (fn [c]
                              (swap! judges inc)
                              (assoc c :findings
                                     (cond-> [{:title (str "round-" (:iter c))}]
                                       (stuck? (:iter c)) (conj {:title "x"})))))
              (stage :amend (fn [c]
                              (swap! amends inc)
                              (update c :history (fnil conj [])
                                      {:iter (:iter c) :findings (:findings c)
                                       :amended? true})))]
        out (rloop/run-loop (merge {:run-id "j" :max-iters 12 :pipeline pipe
                                    :judged-after :judge :finding-key :title}
                                   extra))]
    [out @judges @amends]))

(deftest a-finding-is-given-three-repairs-and-every-one-is-judged
  ;; It was two, and the third — untested — was reported as having failed.
  ;; Twice in one day that third attempt was the one that worked.
  (let [[out judges amends] (judged-run (constantly true))]
    (is (= :unfixable (:status out)))
    (is (= ["x"] (:unfixable out)))
    (is (= 4 judges) "raised in four rounds")
    (is (= 3 amends) "and every repair between them was judged by the next round")))

(deftest a-run-that-ends-on-a-judgement-does-not-repair-what-it-is-about-to-report
  ;; The amend that would answer the final judgement never runs: its result
  ;; would be reported as a failure without anything having tested it.
  (let [[_ judges amends] (judged-run (constantly true))]
    (is (= (dec judges) amends))))

(deftest a-third-repair-that-works-is-not-called-unfixable
  ;; The exact shape that was misreported: raised three rounds running, fixed on
  ;; the third repair, clean on the fourth judgement.
  (let [[out judges amends] (judged-run #(< % 4) :max-iters 4)]
    (is (not= :unfixable (:status out)))
    (is (= 4 judges))
    (is (= 3 amends))))

(deftest a-pipeline-that-names-no-judged-stage-still-ends-after-its-last-stage
  ;; The diff loop passes none: its last stage does work rather than reporting,
  ;; and nothing has shown the same cost there.
  (let [fixes (atom 0)
        pipe [(stage :review (fn [c] (assoc c :findings
                                            [{:title (str "round-" (:iter c))}
                                             {:title "x"}])))
              (stage :fix (fn [c] (swap! fixes inc)
                            (update c :history (fnil conj [])
                                    {:iter (:iter c) :findings (:findings c)})))]
        out (rloop/run-loop {:run-id "nj" :max-iters 12 :pipeline pipe
                             :finding-key :title})]
    (is (= :unfixable (:status out)))
    (is (= 4 @fixes) "every round ran its last stage, as before")))
