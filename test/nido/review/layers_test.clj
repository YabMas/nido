;; test/nido/review/layers_test.clj
(ns nido.review.layers-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.review.layers :as layers]
   [nido.vsdd.jj :as jj]))

(defn- log-out
  "Render rows the way the `row-template` does: commit \\t change \\t bookmarks."
  [rows]
  (str/join "\n" (map (fn [[c ch bms]] (str c "\t" ch "\t" bms)) rows)))

(defn- stub-log
  "jj/jj! stub that answers the `log` call with `out` and records every call."
  [out calls]
  (fn [_dir & args]
    (swap! calls conj (vec args))
    {:exit 0 :out out :err ""}))

;; ---- stack ---------------------------------------------------------------

(deftest stack-reads-layers-bottom-to-top
  (let [out (log-out [["c1" "ch1" ""]
                      ["c2" "ch2" "sess--drop-legacy"]
                      ["c3" "ch3" ""]
                      ["c4" "ch4" "sess--shape-the-seam"]])]
    (with-redefs [jj/jj! (stub-log out (atom []))]
      (is (= [{:bookmark "sess--drop-legacy" :slug "drop-legacy" :tip "c2" :change "ch2"}
              {:bookmark "sess--shape-the-seam" :slug "shape-the-seam" :tip "c4" :change "ch4"}]
             (layers/stack "/w" "sess" "main"))))))

(deftest stack-takes-the-bare-session-bookmark-as-one-implicit-layer
  (let [out (log-out [["c1" "ch1" ""] ["c2" "ch2" "sess"]])]
    (with-redefs [jj/jj! (stub-log out (atom []))]
      (is (= [{:bookmark "sess" :slug nil :tip "c2" :change "ch2"}]
             (layers/stack "/w" "sess" "main"))))))

(deftest stack-ignores-another-sessions-bookmark-in-our-history
  (let [out (log-out [["c1" "ch1" "other--thing"]
                      ["c2" "ch2" "sess--mine"]])]
    (with-redefs [jj/jj! (stub-log out (atom []))]
      (is (= ["sess--mine"] (map :bookmark (layers/stack "/w" "sess" "main")))))))

(deftest stack-prefers-the-layer-bookmark-when-a-commit-carries-several
  (let [out (log-out [["c1" "ch1" "sess sess--mine"]])]
    (with-redefs [jj/jj! (stub-log out (atom []))]
      (is (= ["sess--mine"] (map :bookmark (layers/stack "/w" "sess" "main")))))))

(deftest stack-is-empty-when-nothing-is-bookmarked
  (let [out (log-out [["c1" "ch1" ""] ["c2" "ch2" ""]])]
    (with-redefs [jj/jj! (stub-log out (atom []))]
      (is (= [] (layers/stack "/w" "sess" "main"))))))

(deftest stack-is-empty-when-jj-fails
  (with-redefs [jj/jj! (fn [& _] {:exit 1 :out "" :err "not a workspace"})]
    (is (= [] (layers/stack "/w" "sess" "main")))))

(deftest stack-asks-for-the-base-to-head-revset-reversed
  (let [calls (atom [])]
    (with-redefs [jj/jj! (stub-log "" calls)]
      (layers/stack "/w" "sess" "trunk()")
      (let [args (first @calls)]
        (is (= "log" (first args)))
        (is (some #{"trunk()..@"} args))
        (is (some #{"--reversed"} args))))))

(deftest stack-asks-jj-for-bookmark-NAMES-not-their-display-form
  ;; A bare `local_bookmarks` renders jj's decorated form of each ref: `<name>*`
  ;; once the local bookmark has diverged from a tracked remote — which is
  ;; precisely what landing a fix on an already-pushed layer does. The marker is
  ;; not part of the name, so the round after the first fix would ask jj to
  ;; position on `<layer>*`, which does not exist.
  (let [calls (atom [])]
    (with-redefs [jj/jj! (stub-log "" calls)]
      (layers/stack "/w" "sess" "main")
      (let [args (first @calls)
            tmpl (second (drop-while #(not= "-T" %) args))]
        (is (str/includes? tmpl "local_bookmarks.map(|b| b.name())"))))))

;; ---- positioning ---------------------------------------------------------

(deftest position-for-fix-inserts-after-the-layer-bookmark
  ;; By bookmark, not the tip read when the stack was enumerated: landing a fix
  ;; on a lower layer rewrites every layer above, so those tips go stale within
  ;; the round while the bookmark keeps naming the right commit.
  (let [calls (atom [])]
    (with-redefs [jj/jj! (stub-log "" calls)]
      (layers/position-for-fix! "/w" {:bookmark "sess--l1" :tip "c2"})
      (is (= [["new" "--insert-after" "sess--l1"]] @calls)))))

(deftest position-for-fix-is-a-noop-without-a-layer
  (let [calls (atom [])]
    (with-redefs [jj/jj! (stub-log "" calls)]
      (layers/position-for-fix! "/w" nil)
      (is (= [] @calls)))))

(deftest position-for-fix-throws-review-failed-when-jj-refuses
  (with-redefs [jj/jj! (fn [& _] {:exit 1 :out "" :err "no such revision"})]
    (is (= :review-failed
           (try (layers/position-for-fix! "/w" {:bookmark "sess--l1" :tip "c2"})
                nil
                (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))))))

;; ---- landing -------------------------------------------------------------

(deftest land-fix-describes-and-moves-the-layer-bookmark
  (let [calls (atom [])]
    (with-redefs [jj/jj! (stub-log "abc123" calls)]
      (is (= "abc123" (layers/land-fix! "/w" {:bookmark "sess--l1" :tip "c2"} "msg")))
      (is (= ["describe" "-m" "msg"] (first @calls)))
      (is (= ["bookmark" "set" "sess--l1" "-r" "@"] (second @calls))))))

(deftest land-fix-never-uses-commit-on-a-layer
  ;; `jj commit` mid-stack creates a second child and forks the stack.
  (let [calls (atom [])]
    (with-redefs [jj/jj! (stub-log "abc" calls)]
      (layers/land-fix! "/w" {:bookmark "sess--l1" :tip "c2"} "msg")
      (is (not-any? #(= "commit" (first %)) @calls)))))

(deftest land-fix-falls-back-to-commit-without-a-layer
  (let [calls (atom [])]
    (with-redefs [jj/jj! (stub-log "abc" calls)]
      (layers/land-fix! "/w" nil "msg")
      (is (= ["commit" "-m" "msg"] (first @calls)))
      (is (some #{"@-"} (second @calls))))))

;; ---- restoring -----------------------------------------------------------

(deftest restore-top-news-onto-the-top-layers-bookmark
  (let [calls (atom [])]
    (with-redefs [jj/jj! (stub-log "" calls)]
      (layers/restore-top! "/w" [{:bookmark "sess--l1"} {:bookmark "sess--l2"}])
      (is (= [["new" "sess--l2"]] @calls)))))

(deftest restore-top-is-a-noop-on-an-empty-stack
  (let [calls (atom [])]
    (with-redefs [jj/jj! (stub-log "" calls)]
      (layers/restore-top! "/w" [])
      (is (= [] @calls)))))

;; ---- ranges --------------------------------------------------------------

(deftest ranges-run-from-the-layer-beneath-and-the-bottom-from-the-fork-point
  (let [st [{:bookmark "s--a" :tip "cA"} {:bookmark "s--b" :tip "cB"}
            {:bookmark "s--c" :tip "cC"}]]
    (is (= [["FORK" "cA"] ["cA" "cB"] ["cB" "cC"]]
           (map (juxt :from :to) (layers/ranges st "FORK"))))))

;; ---- what a range contributes -------------------------------------------
;;
;; Both diffs below are jj 0.45 output for ONE layer, captured either side of a
;; fix landing two commits beneath it that inserted two lines near the top of the
;; file they share. What that layer adds and removes is byte-identical; the blob
;; ids and the hunk offset are not.

(def ^:private upper-layer-before
  (str "diff --git a/shared.txt b/shared.txt\n"
       "index 494fdae787..9c1e1983c8 100644\n"
       "--- a/shared.txt\n"
       "+++ b/shared.txt\n"
       "@@ -15,6 +15,6 @@\n"
       " l15\n l16\n l17\n-l18\n+l18-upper\n l19\n l20\n"))

(def ^:private upper-layer-after
  (str "diff --git a/shared.txt b/shared.txt\n"
       "index 51c80da62f..5fd2c4c831 100644\n"
       "--- a/shared.txt\n"
       "+++ b/shared.txt\n"
       "@@ -17,6 +17,6 @@\n"
       " l15\n l16\n l17\n-l18\n+l18-upper\n l19\n l20\n"))

(deftest a-fix-beneath-a-layer-does-not-change-what-that-layer-contributes
  (is (= (layers/contribution upper-layer-before)
         (layers/contribution upper-layer-after))
      "a layer nobody edited must keep one identity, or the cache cannot skip it"))

(deftest contribution-keeps-the-edit-and-the-file-it-lands-in
  (let [c (layers/contribution upper-layer-before)]
    (is (str/includes? c "-l18\n+l18-upper")
        "dropping the coordinates must not drop the change they were around")
    (is (str/includes? c "--- a/shared.txt")
        "which file a range touches is part of what it contributes")))

(deftest a-different-edit-is-a-different-contribution
  (is (not= (layers/contribution upper-layer-before)
            (layers/contribution (str/replace upper-layer-before "l18-upper" "l18-other")))
      "under-invalidating ships unreviewed code, so only coordinates may be removed"))

(deftest patch-hash-is-taken-over-the-contribution-not-over-jjs-output
  (let [h (fn [out] (with-redefs [jj/jj! (fn [& _] {:exit 0 :out out :err ""})]
                      (layers/patch-hash "/w" "a" "b")))]
    (is (= (h upper-layer-before) (h upper-layer-after))
        "the identity the cache is keyed on is what the range changes")))

(deftest ranges-of-an-empty-stack-are-empty
  (is (= [] (layers/ranges [] "FORK"))))

;; ---- the review brief ----------------------------------------------------

(def ^:private layer-message
  (str "refactor(pay): fold the rounding into the aggregate\n"
       "\n"
       "Body text that is not part of the brief.\n"
       "\n"
       "Layer: mechanical\n"
       "\n"
       "Claims: the rename is uniform across all 40 call sites.\n"
       "Verify: confirm no call site got special handling;\n"
       "  confirm no behavior changed alongside the rename.\n"
       "Lane: lane-malli\n"
       "Out of scope: the new validation logic — that lands in\n"
       "  the layer above.\n"))

(deftest parse-brief-reads-the-four-fields-and-the-mode
  (let [b (layers/parse-brief layer-message)]
    (is (= :mechanical (:mode b)))
    (is (= "the rename is uniform across all 40 call sites." (:claims b)))
    (is (= "lane-malli" (:lane b)))
    (is (= "refactor(pay): fold the rounding into the aggregate" (:subject b)))))

(deftest parse-brief-joins-a-fields-indented-continuation-lines
  (let [b (layers/parse-brief layer-message)]
    (is (= "confirm no call site got special handling; confirm no behavior changed alongside the rename."
           (:verify b)))
    (is (= "the new validation logic — that lands in the layer above."
           (:out-of-scope b)))))

(deftest parse-brief-tolerates-a-commit-with-no-brief
  ;; A fixup on a layer, or any commit predating the doctrine. Not an error.
  (let [b (layers/parse-brief "review-loop: iter 1 fixes")]
    (is (nil? (:mode b)))
    (is (nil? (:claims b)))
    (is (= "review-loop: iter 1 fixes" (:subject b)))))

(deftest parse-brief-of-nothing-is-nil
  (is (nil? (layers/parse-brief nil)))
  (is (nil? (layers/parse-brief "   "))))

(deftest brief-reads-the-description-of-the-given-revision
  (let [calls (atom [])]
    (with-redefs [jj/jj! (fn [_dir & args]
                           (swap! calls conj (vec args))
                           {:exit 0 :out layer-message :err ""})]
      (is (= :mechanical (:mode (layers/brief "/w" "cB"))))
      (is (some #{"cB"} (first @calls))))))

(deftest restore-top-fails-loud-rather-than-parking-the-working-copy-mid-stack
  ;; Swallowing the exit code here does not degrade this run, it corrupts the
  ;; next one: `<base>..@` read from mid-stack spans fewer layers than the
  ;; branch has, and the review reports success over the part it could see.
  (with-redefs [jj/jj! (fn [& _] {:exit 1 :out "" :err "Revision doesn't exist"})]
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (layers/restore-top! "/w" [{:bookmark "sess--top"}])))]
      (is (= :review-failed (:reason (ex-data e))))
      (is (str/includes? (ex-message e) "sess--top")))))

(deftest restore-top-is-a-no-op-on-an-empty-stack
  (let [calls (atom [])]
    (with-redefs [jj/jj! (stub-log "" calls)]
      (is (nil? (layers/restore-top! "/w" [])))
      (is (empty? @calls)))))

;; ---- reshaping the stack -------------------------------------------------

(defn- scripted
  "jj/jj! stub answering each command from `answers` (keyed by the first arg),
   recording every call. Anything unscripted succeeds silently."
  [answers calls]
  (fn [_dir & args]
    (swap! calls conj (vec args))
    (get answers (first args) {:exit 0 :out "" :err ""})))

(deftest conflicted-asks-the-conflicts-revset-not-resolve
  ;; `jj resolve --list` inspects ONE revision, and an illegal reorder leaves
  ;; its conflict on a rewritten commit mid-stack — so it answers "no conflicts"
  ;; for the legal and the illegal case alike. Scoped to this stack because the
  ;; revset is repo-wide.
  (let [calls (atom [])]
    (with-redefs [jj/jj! (scripted {"log" {:exit 0 :out "abc\ndef" :err ""}} calls)]
      (is (= ["abc" "def"] (layers/conflicted "/w" "main"))))
    (is (str/starts-with? (nth (first @calls) 2) "conflicts() & (main..@)"))
    (is (not-any? #(some #{"resolve"} %) @calls))))

(deftest conflicted-leaves-out-a-working-copy-that-holds-nothing-of-its-own
  ;; A conflict propagates to every descendant and restore-top! parks an empty
  ;; @ on the stack top, so an unfiltered sweep leads with a change id that is
  ;; new every round. Across three runs on one branch the two ids that named
  ;; the actual seam were identical and the first differed each time, so
  ;; comparing two fix-conflicted entries meant reading past the difference.
  ;; A NON-empty @ is still swept: on an unstacked branch the session bookmark
  ;; is on the working copy, which makes @ the top layer.
  (let [calls (atom [])]
    (with-redefs [jj/jj! (scripted {"log" {:exit 0 :out "abc" :err ""}} calls)]
      (layers/conflicted "/w" "main"))
    (is (= "conflicts() & (main..@) ~ (@ & empty())" (nth (first @calls) 2))
        "`..@-` would exclude the working copy even when it is the top layer")))

(deftest a-reshape-that-conflicts-leaves-the-stack-as-it-was
  (let [calls (atom [])]
    (with-redefs [jj/jj! (scripted {"op"  {:exit 0 :out "op42" :err ""}
                                    "log" {:exit 0 :out "abc" :err ""}} calls)]
      (let [r (layers/attempt-reshape! "/w" "main" (fn [] {:exit 0 :out "" :err ""}))]
        (is (false? (:ok? r)))
        (is (str/includes? (:reason r) "the order they are in is the order they need"))))
    (is (some #{["op" "restore" "op42"]} @calls) "rolled back by operation id")))

(deftest a-reshape-jj-refuses-leaves-the-stack-as-it-was
  (let [calls (atom [])]
    (with-redefs [jj/jj! (scripted {"op" {:exit 0 :out "op42" :err ""}} calls)]
      (let [r (layers/attempt-reshape! "/w" "main"
                                       (fn [] {:exit 1 :out "" :err "no such bookmark"}))]
        (is (false? (:ok? r)))
        (is (str/includes? (:reason r) "no such bookmark"))))
    (is (some #{["op" "restore" "op42"]} @calls))))

(deftest a-clean-reshape-is-kept
  (let [calls (atom [])]
    (with-redefs [jj/jj! (scripted {"op"  {:exit 0 :out "op42" :err ""}
                                    "log" {:exit 0 :out "" :err ""}} calls)]
      (is (:ok? (layers/attempt-reshape! "/w" "main" (fn [] {:exit 0 :out "" :err ""})))))
    (is (not-any? #{["op" "restore" "op42"]} @calls) "nothing to roll back")))

(deftest a-fold-deletes-the-bookmark-it-absorbed
  ;; jj leaves both bookmarks on the squashed commit and layer-bookmark takes
  ;; the first match, so leaving them would hide one layer from every later read
  ;; while whatever it published stayed published.
  (let [calls (atom [])]
    (with-redefs [jj/jj! (scripted {"op"  {:exit 0 :out "op42" :err ""}
                                    "log" {:exit 0 :out "" :err ""}} calls)]
      (is (:ok? (layers/fold! "/w" "main" {:bookmark "s--upper"} {:bookmark "s--lower"}))))
    (is (some #{["bookmark" "delete" "s--upper"]} @calls))))

(deftest a-fold-that-did-not-apply-deletes-nothing
  (let [calls (atom [])]
    (with-redefs [jj/jj! (scripted {"op"     {:exit 0 :out "op42" :err ""}
                                    "squash" {:exit 1 :out "" :err "nope"}} calls)]
      (is (false? (:ok? (layers/fold! "/w" "main" {:bookmark "s--upper"} {:bookmark "s--lower"})))))
    (is (not-any? #(= ["bookmark" "delete" "s--upper"] %) @calls))))

(deftest reorder-moves-the-layer-below-the-other
  (let [calls (atom [])]
    (with-redefs [jj/jj! (scripted {"op"  {:exit 0 :out "op42" :err ""}
                                    "log" {:exit 0 :out "" :err ""}} calls)]
      (is (:ok? (layers/reorder! "/w" "main" {:bookmark "s--upper"} {:bookmark "s--lower"}))))
    (is (some #{["rebase" "-r" "s--upper" "--insert-before" "s--lower"]} @calls))))
