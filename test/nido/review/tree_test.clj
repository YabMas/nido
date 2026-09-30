(ns nido.review.tree-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.design.check :as design]
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

(deftest a-worktree-that-wrote-nothing-is-read-in-place
  (let [{:keys [root work]} (repo!)]
    (try
      (with-spec-dirs
        (is (= {:dir work} (tree/reading :baseline :nido work)))
        (is (= {:dir work} (tree/reading :design :nido work)))
        (is (nil? (tree/line {:dir work})) "nothing to say about the tree it would read anyway"))
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
