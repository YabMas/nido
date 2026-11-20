(ns nido.session.change-test
  "A session's change read, sealed and brought onto main, against a real jj repository."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nido.session.change :as change]))

(defn- sh! [dir & args]
  (let [{:keys [exit out err]} (apply p/shell {:dir (str dir) :out :string :err :string :continue true} args)]
    (when-not (zero? exit) (throw (ex-info (str (str/join " " args) ": " err) {})))
    (str/trim out)))

(defn- jj [dir & args] (apply sh! dir "jj" args))

(defn- rev [dir r] (jj dir "log" "--no-graph" "-r" r "-T" "commit_id"))

(defn- repo!
  "A worktree whose origin's main is one commit, and whose working copy writes a.txt, described."
  []
  (let [root (fs/create-temp-dir {:prefix "change-test"})
        bare (str (fs/path root "origin.git"))
        wt   (str (fs/path root "wt"))]
    (sh! root "git" "init" "--bare" "-q" bare)
    (sh! root "jj" "git" "init" wt)
    (spit (str (fs/path wt "base.txt")) "base\n")
    (jj wt "describe" "-m" "base")
    (jj wt "bookmark" "create" "main" "-r" "@")
    (jj wt "new")
    (jj wt "git" "remote" "add" "origin" bare)
    (jj wt "git" "push" "-b" "main")
    (spit (str (fs/path wt "a.txt")) "a\n")
    (jj wt "describe" "-m" "Write a")
    wt))

(defn- land-copy!
  "What a landing does to origin: main gains a copy of `r`'s arc. Returns the copy's commit."
  [wt r]
  (jj wt "duplicate" (str "main@origin.." r) "-d" "main@origin")
  (let [copy (rev wt (str "heads((main@origin..) ~ ::@ ~ ::" r ")"))]
    (jj wt "bookmark" "set" "main" "-r" copy)
    (jj wt "git" "push" "-b" "main")
    copy))

(defmacro with-wt [[wt] & body]
  `(let [~wt (repo!)]
     (with-redefs [change/worktree (fn [_# _#] ~wt)]
       ~@body)))

(deftest a-change-is-read-with-the-revision-it-was-read-at
  (with-wt [wt]
    (let [c (change/pending :nido "s")]
      (is (= [{:path "a.txt" :status :added}] (:files c)))
      (is (= "Write a" (str/trim (:description c))))
      (is (= (rev wt "@") (:rev c)))
      (is (= [] (:conflicts c))))))

(deftest a-seal-fixes-the-change-it-was-shown-and-nothing-newer
  (with-wt [wt]
    (let [{r :rev} (change/pending :nido "s")]
      (testing "a working copy that moved since the reading is not sealed"
        (spit (str (fs/path wt "b.txt")) "b\n")
        (is (nil? (change/seal! :nido "s" r)))
        (is (= #{"a.txt" "b.txt"} (set (map :path (:files (change/pending :nido "s")))))))
      (let [{r :rev} (change/pending :nido "s")
            sealed   (change/seal! :nido "s" r)]
        (is (= r sealed))
        (is (= sealed (rev wt "@-")) "the agent's next edits go on a change above the seal")
        (spit (str (fs/path wt "c.txt")) "c\n")
        (is (= ["c.txt"] (map :path (:files (change/pending :nido "s" sealed)))) "pending is only what is above it")
        (is (= #{"a.txt" "b.txt"} (set (map :path (:files (change/landing :nido "s" sealed))))) "the landing is the seal's arc")))))

(deftest a-change-not-on-main-is-not-sealed
  (with-wt [wt]
    (let [other (str (fs/path (fs/parent wt) "other"))]
      (jj wt "workspace" "add" "--name" "other" "-r" "main@origin" other)
      (spit (str (fs/path other "m.txt")) "m\n")
      (jj other "describe" "-m" "m")
      (jj other "bookmark" "set" "main" "-r" "@")
      (jj other "git" "push" "-b" "main")
      (let [{r :rev} (change/pending :nido "s")]
        (is (nil? (change/seal! :nido "s" r)))))))

(deftest a-rejoin-after-a-landing-keeps-only-the-newer-work
  (with-wt [wt]
    (let [{r :rev} (change/pending :nido "s")
          sealed   (change/seal! :nido "s" r)]
      (spit (str (fs/path wt "c.txt")) "c\n")
      (land-copy! wt sealed)
      (is (= [] (change/onto-main! :nido "s")))
      (is (some? (not-empty (jj wt "log" "--no-graph" "-r" "main@origin & ::@" "-T" "commit_id"))))
      (is (= ["c.txt"] (map :path (:files (change/pending :nido "s")))) "the landed change is dropped, the newer edit stays")
      (is (= [] (change/onto-main! :nido "s")) "a worktree already on main is left alone"))))

(deftest a-conflicting-rejoin-stays-and-names-its-paths
  (with-wt [wt]
    (let [{r :rev} (change/pending :nido "s")
          sealed   (change/seal! :nido "s" r)]
      (spit (str (fs/path wt "a.txt")) "a, edited after the seal\n")
      ;; main gets the sealed change and then someone else rewrites a.txt
      (let [copy  (land-copy! wt sealed)
            other (str (fs/path (fs/parent wt) "other"))]
        (jj wt "workspace" "add" "--name" "other" "-r" copy other)
        (spit (str (fs/path other "a.txt")) "a, by someone else\n")
        (jj other "describe" "-m" "other")
        (jj other "bookmark" "set" "main" "-r" "@")
        (jj other "git" "push" "-b" "main"))
      (is (= ["a.txt"] (change/onto-main! :nido "s")))
      (is (= ["a.txt"] (:conflicts (change/pending :nido "s"))))
      (is (str/includes? (slurp (str (fs/path wt "a.txt"))) "edited after the seal")
          "the newer edit is a side of the conflict, not lost")
      (is (nil? (change/seal! :nido "s" (:rev (change/pending :nido "s")))) "a conflicted change is not sealed"))))

(deftest a-merge-with-nothing-of-its-own-is-sealed-as-itself
  (doseq [above? [false true]]
    (with-wt [wt]
      (let [a (rev wt "@")]
        (jj wt "new" "main@origin" "-m" "Write b")
        (spit (str (fs/path wt "b.txt")) "b\n")
        (let [b (rev wt "@")]
          (jj wt "new" a b "-m" "Join a and b")
          (let [merge (rev wt "@")]
            (when above? (jj wt "new"))
            (let [{r :rev} (change/pending :nido "s")
                  sealed   (change/seal! :nido "s" r)]
              (is (= merge sealed) (str "the merge, not its parents" (when above? ", under an empty working copy")))
              (is (= merge (rev wt "@-")) "the agent's next edits go on a change above it")
              (is (= #{"a.txt" "b.txt"} (set (map :path (:files (change/landing :nido "s" sealed)))))))))))))
