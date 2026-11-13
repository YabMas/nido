(ns nido.review.tree-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.record.activity :as activity]
   [nido.coordinator.record.state :as cstate]
   [nido.design.check :as design]
   [nido.platform.core :as core]
   [nido.review.layers :as layers]
   [nido.review.settled :as settled]
   [nido.review.tree :as tree]
   [nido.vsdd.jj :as jj]))

(defn- repo!
  "A real repo whose `main` holds src/a.clj and canvas/d.clj, and a session-like
   workspace `work` on top of it that has written nothing yet. Answers
   {:root :home :work :base}, `:base` being main's commit."
  []
  (let [root (fs/create-temp-dir {:prefix "nido-tree"})
        home (str (fs/path root "repo"))
        work (str (fs/path root "work"))]
    (jj/jj! (str root) "git" "init" "repo")
    (fs/create-dirs (fs/path home "src"))
    (fs/create-dirs (fs/path home "canvas"))
    (spit (str (fs/path home "src" "a.clj")) "base code\n")
    (spit (str (fs/path home "canvas" "d.clj")) "base declaration\n")
    (jj/jj! home "commit" "-m" "base")
    (jj/jj! home "bookmark" "create" "main" "-r" "@-")
    (jj/jj! home "workspace" "add" "--name" "work" work)
    {:root (str root) :home home :work work
     :base (:out (jj/jj! home "log" "--no-graph" "-r" "main" "-T" "commit_id"))}))

(defmacro ^:private with-spec-dirs
  "The spec dirs nido's registry would give, without the registry."
  [& body]
  `(with-redefs [design/spec-dirs (fn [_#] ["canvas"])]
     ~@body))

(defn- workspaces [home]
  (:out (jj/jj! home "workspace" "list")))

(deftest a-design-whose-worktree-wrote-nothing-is-read-in-place
  (let [{:keys [root work]} (repo!)]
    (try
      (with-spec-dirs
        (is (= {:dir work} (tree/reading :design :nido work)))
        (is (nil? (tree/line {:dir work})) "nothing to say about the tree it would read anyway"))
      (finally (fs/delete-tree root)))))

(deftest a-baseline-reads-its-fork-point-even-where-the-worktree-holds-it
  ;; A baseline run started while the reviewed session's worktree still held main's tree; the
  ;; session then wrote its fix there, and every round the judge read was unbanked as a moved tree
  ;; although the revision the baseline is about never moved.
  (let [{:keys [root work base]} (repo!)
        dir (str (fs/path root "produced"))]
    (try
      (let [reading (tree/reading :baseline :nido work)
            at-start (settled/code-identity work)]
        (is (= {:rev base :overlay [] :ahead 0} reading)
            "a worktree matching the fork point now can stop matching it while the round reads")
        (tree/with-reading!
          work reading "round-1" dir
          (fn [d]
            (let [before (settled/code-identity d)]
              (spit (str (fs/path work "src" "a.clj")) "the fix, written mid-round\n")
              (is (= before (settled/code-identity d))
                  "an edit to the live worktree does not move the tree the round's identity is over")
              (is (= at-start before)
                  "and that identity is the tree's, so judgements already banked at it still match")))))
      (finally (fs/delete-tree root)))))

(deftest a-worktree-carrying-the-change-is-not-what-a-baseline-reads
  (let [{:keys [root work base]} (repo!)]
    (try
      (spit (str (fs/path work "src" "a.clj")) "the change\n")
      (spit (str (fs/path work "canvas" "d.clj")) "the design's declaration\n")
      (with-spec-dirs
        (is (= {:rev base :overlay [] :ahead 2} (tree/reading :baseline :nido work)))
        (testing "a design reads the base under the worktree's declaration"
          (is (= {:rev base :overlay ["canvas"] :ahead 1} (tree/reading :design :nido work)))))
      (let [line (tree/line (tree/reading :baseline nil work))]
        (is (str/includes? line (subs base 0 12)) "the round names the commit it judges")
        (is (str/includes? line ":code-cwd") "and how to judge another"))
      (finally (fs/delete-tree root)))))

(deftest a-design-that-only-declared-reads-the-worktree
  ;; The usual state at design time: the canvas edit is written, no code is.
  (let [{:keys [root work base]} (repo!)]
    (try
      (spit (str (fs/path work "canvas" "d.clj")) "the design's declaration\n")
      (with-spec-dirs
        (is (= {:dir work} (tree/reading :design :nido work)))
        (is (= {:rev base :overlay [] :ahead 1} (tree/reading :baseline :nido work))
            "while a baseline still must not read the declaration"))
      (finally (fs/delete-tree root)))))

(deftest a-tree-with-no-fork-point-is-read-as-it-stands-and-says-so
  (let [dir (str (fs/create-temp-dir {:prefix "nido-tree-plain"}))]
    (try
      (let [r (tree/reading :baseline nil dir)]
        (is (= dir (:dir r)))
        (is (string? (:unresolved r)))
        (is (str/starts-with? (tree/line r) "note:")))
      (finally (fs/delete-tree dir)))))

(deftest a-produced-tree-holds-the-base-under-the-overlay-and-is-removed
  (let [{:keys [root home work base]} (repo!)
        dir (str (fs/path root "produced"))]
    (try
      (spit (str (fs/path work "src" "a.clj")) "the change\n")
      (spit (str (fs/path work "canvas" "d.clj")) "the design's declaration\n")
      (spit (str (fs/path work "canvas" "e.clj")) "a new declaration\n")
      (let [seen (tree/with-reading!
                   work {:rev base :overlay ["canvas"]} "round-1" dir
                   (fn [d]
                     {:dir  d
                      :code (slurp (str (fs/path d "src" "a.clj")))
                      :decl (slurp (str (fs/path d "canvas" "d.clj")))
                      :new  (fs/exists? (fs/path d "canvas" "e.clj"))
                      :ws   (workspaces home)}))]
        (is (= dir (:dir seen)))
        (is (= "base code\n" (:code seen)) "the code is the fork point's")
        (is (= "the design's declaration\n" (:decl seen)) "the declaration is the worktree's")
        (is (:new seen))
        (is (str/includes? (:ws seen) "round-1")))
      (is (not (fs/exists? dir)))
      (is (not (str/includes? (workspaces home) "round-1")))
      (is (= "" (:out (jj/jj! home "log" "--no-graph" "-r" "heads(all()) ~ ::(main | work@ | default@)"
                              "-T" "commit_id")))
          "the overlay's working-copy commit is not left behind as a head")
      (finally (fs/delete-tree root)))))

(deftest a-produced-tree-is-removed-when-the-round-throws
  (let [{:keys [root home work base]} (repo!)
        dir (str (fs/path root "produced"))]
    (try
      (spit (str (fs/path work "src" "a.clj")) "the change\n")
      (is (thrown-with-msg? Exception #"boom"
                            (tree/with-reading! work {:rev base :overlay []} "round-2" dir
                              (fn [_] (throw (ex-info "boom" {}))))))
      (is (not (fs/exists? dir)))
      (is (not (str/includes? (workspaces home) "round-2")))
      (finally (fs/delete-tree root)))))

(deftest a-produced-tree-whose-run-directory-does-not-exist-yet-is-produced
  ;; A round produces its tree at <run-dir>/tree before anything else has written the run's
  ;; directory, and jj will not add a workspace under a parent that does not exist.
  (let [{:keys [root work base]} (repo!)
        dir (str (fs/path root "runs" "design-loop-x" "tree"))]
    (try
      (spit (str (fs/path work "src" "a.clj")) "the change\n")
      (is (= "base code\n"
             (tree/with-reading! work {:rev base :overlay []} "round-4" dir
               (fn [d] (slurp (str (fs/path d "src" "a.clj")))))))
      (is (not (fs/exists? dir)))
      (finally (fs/delete-tree root)))))

(deftest a-tree-that-cannot-be-produced-throws-before-the-round
  (let [{:keys [root work]} (repo!)
        called (atom false)]
    (try
      (is (thrown-with-msg? Exception #"could not check out"
                            (tree/with-reading! work {:rev "no-such-revision" :overlay []} "round-3"
                              (str (fs/path root "produced"))
                              (fn [_] (reset! called true)))))
      (is (false? @called))
      (finally (fs/delete-tree root)))))

(deftest a-reading-naming-a-directory-is-handed-through
  (is (= "/some/where" (tree/with-reading! "/w" {:dir "/some/where"} "x" "/unused" identity))))

(deftest a-round-stamps-the-revision-it-judges
  (let [{:keys [root work base]} (repo!)]
    (try
      (testing "a produced tree is the fork point, however far the worktree is ahead of it"
        (is (= {:rev base :overlay ["canvas"] :ahead 1}
               (tree/stamp {:rev base :overlay ["canvas"] :ahead 1} "/runs/r/tree"))))
      (testing "a worktree read in place is named by its working-copy commit, not left anonymous"
        (let [at (:out (jj/jj! work "log" "--no-graph" "-r" "@" "-T" "commit_id"))
              st (tree/stamp {:dir work} work)]
          (is (= {:rev (str/trim at)} st))
          (is (not= base (:rev st)) "the working copy is not the fork point, and a reader must not take it for one")))
      (finally (fs/delete-tree root))))
  (let [dir (str (fs/create-temp-dir {:prefix "nido-tree-plain"}))]
    (try
      (is (= {:unresolved "no fork point"} (tree/stamp {:dir dir :unresolved "no fork point"} dir))
          "a tree nothing names a revision for says why, and guesses none")
      (finally (fs/delete-tree dir)))))

;; ── A stale working copy ────────────────────────────────────────────────────

(defn- stale!
  "`work` made stale from the default workspace: its commit's tree rewritten to drop src/a.clj, its
   files on disk left as they were."
  [{:keys [home]}]
  (jj/jj! home "restore" "--from" "root()" "--into" "work@" "src/a.clj"))

(deftest a-copy-that-is-not-stale-is-left-alone
  (let [{:keys [root work]} (repo!)]
    (try (is (nil? (tree/heal-stale! work)))
         (finally (fs/delete-tree root)))))

(deftest a-stale-copy-whose-heal-would-move-the-reviewed-tree-is-left-and-named
  ;; Healing checks the rewritten commit out over the files a judge is reading. A round may not do
  ;; that to a tree it reviews, so it says what a person has to run instead — the one sentence the
  ;; runs that banked nothing for ten rounds never carried.
  (let [{:keys [root work] :as r} (repo!)]
    (try
      (stale! r)
      (let [h (tree/heal-stale! work)]
        (is (false? (:healed h)))
        (is (str/includes? (:line h) "would rewrite 1 file(s)") "it says how much the heal would move")
        (is (str/includes? (:line h) (str "run `jj workspace update-stale` in " work))
            "and names the command and where, so nobody has to work it out")
        (is (fs/exists? (fs/path work "src" "a.clj")) "and the reviewed file is where it was"))
      (finally (fs/delete-tree root)))))

(deftest a-stale-copy-holding-unrecorded-edits-is-not-healed
  ;; The disk matches the rewritten commit but not the one it was checked out from, so jj would
  ;; record the difference as an edit beside the rewritten change — a divergent change in a
  ;; repository a review only reads.
  (let [{:keys [root work] :as r} (repo!)]
    (try
      (stale! r)
      (fs/delete (fs/path work "src" "a.clj"))
      (let [h (tree/heal-stale! work)]
        (is (false? (:healed h)))
        (is (str/includes? (:line h) "1 file(s) on disk hold edits jj has not recorded")))
      (finally (fs/delete-tree root)))))

(deftest a-stale-copy-whose-heal-changes-nothing-is-healed
  ;; Both trees read as the disk, so the heal writes and records nothing, and every round after it
  ;; can bank what it confirms.
  (let [{:keys [root work] :as r} (repo!)]
    (try
      (stale! r)
      (with-redefs [layers/working-copy-patch (fn [_ _] "")]
        (is (:healed (tree/heal-stale! work))))
      (is (not (layers/stale? work)) "the copy is no longer refused")
      (finally (fs/delete-tree root)))))

(defn- run-with-tree!
  "A run directory under `runs` named `run-id`, whose report says `status` (no report when nil),
   holding a tree produced at `rev` and registered as `run-id`, as `with-reading!` leaves one
   whose round never returned to remove it. The tree carries a file so its working-copy commit
   is not empty."
  [home runs run-id status rev]
  (let [run (fs/path runs run-id)
        dir (str (fs/path run "tree"))]
    (fs/create-dirs run)
    (jj/jj! home "workspace" "add" "--name" run-id "--revision" rev dir)
    (spit (str (fs/path dir "overlay.txt")) "laid over the revision\n")
    (jj/jj! dir "status")
    (when status
      (spit (str (fs/path run "report.json")) (str "{\"status\": \"" status "\"}")))
    dir))

(deftest an-ended-run-s-tree-is-reaped-and-a-live-one-is-left
  ;; A stopped round seals its report from the shutdown hook while still inside with-reading!'s
  ;; function, and the process exits before the removal runs: the tree stays, registered.
  (let [{:keys [root home base]} (repo!)
        runs (str (fs/path root "runs"))]
    (try
      (let [interrupted (run-with-tree! home runs "design-loop-stopped" "interrupted" base)
            finished    (run-with-tree! home runs "baseline-loop-done" "converged" base)
            running     (run-with-tree! home runs "design-loop-live" "running" base)
            unreported  (run-with-tree! home runs "design-loop-new" nil base)
            {:keys [reaped failed]} (tree/reap-ended! runs)]
        (is (= ["baseline-loop-done" "design-loop-stopped"] reaped))
        (is (empty? failed))
        (is (not (fs/exists? interrupted)))
        (is (not (fs/exists? finished)))
        (is (fs/exists? running) "a running report may be a round still reading its tree")
        (is (fs/exists? unreported) "with no report nothing says the run has ended")
        (let [ws (workspaces home)]
          (is (not (str/includes? ws "design-loop-stopped")))
          (is (not (str/includes? ws "baseline-loop-done")))
          (is (str/includes? ws "design-loop-live"))
          (is (str/includes? ws "design-loop-new")))
        (is (= "" (:out (jj/jj! home "log" "--no-graph" "-r"
                                "heads(all()) ~ ::(main | work@ | default@ | design-loop-live@ | design-loop-new@)"
                                "-T" "commit_id")))
            "a reaped tree's working-copy commit is abandoned, not left as a head"))
      (finally (fs/delete-tree root)))))

(deftest an-ended-run-s-tree-jj-no-longer-lists-is-deleted
  (let [{:keys [root home base]} (repo!)
        runs (str (fs/path root "runs"))]
    (try
      (let [dir (run-with-tree! home runs "design-loop-forgotten" "interrupted" base)]
        (jj/jj! home "workspace" "forget" "design-loop-forgotten")
        (is (not= "" (:out (jj/jj! home "log" "--no-graph" "-r"
                                   "heads(all()) ~ ::(main | work@ | default@)" "-T" "commit_id")))
            "forgetting keeps the tree's non-empty working-copy commit as a head")
        (is (= ["design-loop-forgotten"] (:reaped (tree/reap-ended! runs))))
        (is (not (fs/exists? dir)))
        (is (= "" (:out (jj/jj! home "log" "--no-graph" "-r"
                                "heads(all()) ~ ::(main | work@ | default@)" "-T" "commit_id")))
            "the forgotten tree's working-copy commit is abandoned, not left as a head"))
      (finally (fs/delete-tree root)))))

(deftest an-ended-run-still-holding-its-claim-keeps-its-tree
  ;; The shutdown hook seals the report while the round may still be inside with-reading!'s
  ;; function, and the round holds its workstream's claim until it returns. Stopping the session
  ;; drops its registry entry and not the claim, so the claim is found with no session registered.
  (let [{:keys [root home base work]} (repo!)
        runs (str (fs/path root "runs"))]
    (try
      (with-redefs [core/nido-root (constantly (str (fs/path root "nido")))]
        (let [held  (run-with-tree! home runs "design-loop-held" "interrupted" base)
              other (run-with-tree! home runs "design-loop-other" "interrupted" base)]
          (doseq [run ["design-loop-held" "design-loop-other"]]
            (spit (str (fs/path runs run "report.json"))
                  (str "{\"status\": \"interrupted\", \"target\": {\"cwd\": \"" work "\"}}")))
          (fs/create-dirs (cstate/workstream-dir :p "ws-1"))
          (activity/with-claim :p "ws-1" {:kind :design-round :run-id "design-loop-held"
                                          :report-path nil}
            (fn []
              (let [{:keys [reaped failed]} (tree/reap-ended! runs)]
                (is (= ["design-loop-other"] reaped)
                    "a claim naming another run does not keep this one's tree")
                (is (empty? failed) "a tree kept for its claim is not a failure")
                (is (fs/exists? held))
                (is (str/includes? (workspaces home) "design-loop-held"))
                (is (not (fs/exists? other))))))
          (is (= ["design-loop-held"] (:reaped (tree/reap-ended! runs)))
              "once the claim is let go the tree is reaped")))
      (finally (fs/delete-tree root)))))

(deftest reaping-a-runs-dir-that-does-not-exist-reaps-nothing
  (is (= {:reaped [] :failed []} (tree/reap-ended! "/nonexistent/nido/runs"))))
