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
