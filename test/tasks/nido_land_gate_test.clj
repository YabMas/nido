(ns tasks.nido-land-gate-test
  "The landing gate lands one named revision from a workspace of its own, refuses before it pushes
   anything it cannot show, and leaves every other workspace's commits where they were."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nido.review.layers :as layers]
            [nido.platform.jj :as jj]
            [tasks.nido-land :as land]))

;; ── a real repository with a real origin ─────────────────────────────────────

(defn- sh! [dir & args]
  (let [{:keys [exit out err]} (apply p/shell {:dir (str dir) :out :string :err :string :continue true} args)]
    (when-not (zero? exit) (throw (ex-info (str (str/join " " args) ": " err) {})))
    (str/trim out)))

(defn- jj [dir & args] (apply sh! dir "jj" args))

(defn- rev [dir r] (jj dir "log" "--no-graph" "-r" r "-T" "commit_id"))

(defn- repo!
  "A jj repository whose origin is a bare git repository holding main = one commit, and a
   worktree holding change `A` (adds a.txt) with an empty working copy on top. Returns its dir."
  []
  (let [root (fs/create-temp-dir {:prefix "land-gate-test"})
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
    (jj wt "describe" "-m" "A")
    (jj wt "new")
    wt))

(defn- main-moves!
  "Another landing reaches origin while `wt` is not looking: a commit writing `path` with `text`."
  [wt path text]
  (let [other (str (fs/path (fs/parent wt) (str "other-" (random-uuid))))]
    (jj wt "workspace" "add" "--name" (str (fs/file-name other)) "-r" "main@origin" other)
    (spit (str (fs/path other path)) text)
    (jj other "describe" "-m" (str "main writes " path))
    (jj other "bookmark" "set" "main" "-r" "@")
    (jj other "git" "push" "-b" "main")
    (jj wt "workspace" "forget" (str (fs/file-name other)))
    (jj wt "git" "fetch")))

(defn- run-gate
  "Run the gate over `wt`, `r` the revision named (nil for none). `on-test` runs in place of
   bb nido:test with the landing's directory and returns its exit code."
  [wt r & {:keys [on-test check-exit] :or {on-test (constantly 0) check-exit 0}}]
  (let [checked (atom nil)
        result  (atom nil)
        out     (with-redefs [land/run-tests on-test
                              land/run-check (fn [dir ws-cwd] (reset! checked [dir ws-cwd]) check-exit)]
                  (with-out-str (reset! result (if r (land/gate! wt r) (land/gate! wt)))))]
    {:result @result :out out :checked @checked}))

(defn- workspaces [wt] (set (str/split-lines (jj wt "workspace" "list" "-T" "name ++ \"\\n\""))))

;; ── the gate ─────────────────────────────────────────────────────────────────

(deftest a-named-revision-lands-without-what-the-worktree-gained-since
  (let [wt     (repo!)
        sealed (rev wt "@-")
        tested (atom nil)
        {:keys [result out checked]}
        (run-gate wt sealed
                  :on-test (fn [dir]
                             ;; the agent keeps editing while the landing tests
                             (spit (str (fs/path wt "late.txt")) "late\n")
                             (reset! tested (set (map str (fs/list-dir dir))))
                             0))
        landed (:landed result)]
    (is landed out)
    (is (= landed (rev wt "main@origin")) "origin's main is the copy the gate pushed")
    (is (re-find (re-pattern (str "(?m)^land pushed · " landed "$")) out))
    (is (some #(str/ends-with? % "a.txt") @tested) "the tested tree holds the sealed change")
    (is (not-any? #(str/ends-with? % "late.txt") @tested) "and not the edit made after the start")
    (is (= "" (jj wt "diff" "--summary" "-r" (str sealed "..main@origin") "--" "late.txt"))
        "the pushed commit does not hold the late edit")
    (is (= wt (second checked)) "the check is held to the landing's cwd")
    (is (not= wt (first checked)) "and reads the landing's own tree")
    (is (= sealed (rev wt "@-")) "a named revision leaves the worktree's commits where they were")
    (is (fs/exists? (fs/path wt "late.txt")) "the late edit is still in the worktree")
    (is (= #{"default"} (workspaces wt)) "the landing's workspace is forgotten")))

(deftest a-landing-named-no-revision-brings-the-worktree-onto-main
  (let [wt (repo!)
        _  (main-moves! wt "m.txt" "m\n")
        {:keys [result out]} (run-gate wt nil)]
    (is (:landed result) out)
    (is (some? (layers/resolve-rev wt "main@origin & ::@")) "origin's main is an ancestor of the worktree")
    (is (= "" (jj wt "diff" "--summary" "-r" "main@origin..@")) "nothing it landed is carried again")))

(deftest a-change-main-already-holds-is-nothing-to-land
  (let [wt (repo!)
        _  (main-moves! wt "a.txt" "a\n")
        before (rev wt "main@origin")
        {:keys [result]} (run-gate wt (rev wt "@-"))]
    (is (str/includes? (:refused result) "nothing to land"))
    (is (= before (rev wt "main@origin")) "nothing was pushed")
    (is (= #{"default"} (workspaces wt)))))

(deftest a-change-that-conflicts-with-main-is-refused-and-leaves-the-worktree
  (let [wt     (repo!)
        _      (main-moves! wt "a.txt" "different\n")
        sealed (rev wt "@-")
        {:keys [result]} (run-gate wt sealed)]
    (is (str/includes? (:refused result) "conflicts with origin's main: "))
    (is (= sealed (rev wt "@-")) "the worktree's commits are untouched")
    (is (= "" (jj wt "log" "--no-graph" "-r" "conflicts()" "-T" "change_id")) "no copy is left behind")
    (is (= #{"default"} (workspaces wt)))))

(deftest a-red-run-abandons-the-copy-and-pushes-nothing
  (doseq [opts [{:on-test (constantly 1)} {:check-exit 1}]]
    (let [wt     (repo!)
          main   (rev wt "main@origin")
          sealed (rev wt "@-")
          {:keys [result]} (apply run-gate wt sealed (mapcat identity opts))]
      (is (:refused result) (pr-str opts))
      (is (= main (rev wt "main@origin")) (pr-str opts))
      (is (= sealed (rev wt "@-")) (pr-str opts))
      (is (= "" (jj wt "log" "--no-graph" "-r" "(main@origin..) ~ ::@" "-T" "change_id"))
          (str "no copy outlives the refusal " (pr-str opts))))))

(deftest an-unnamed-landing-takes-the-tip-as-it-stood-before-its-first-line
  (let [wt   (repo!)
        tip  (rev wt "@-")
        step @#'land/step
        {:keys [result out]}
        (with-redefs [land/step (fn [& parts]
                                  ;; an edit written as the landing prints its first line
                                  (when-not (fs/exists? (fs/path wt "late.txt"))
                                    (spit (str (fs/path wt "late.txt")) "late\n"))
                                  (apply step parts))]
          (run-gate wt nil))]
    (is (:landed result) out)
    (is (= "" (jj wt "file" "list" "-r" "main@origin" "late.txt")) "the pushed commit does not hold the late edit")
    (is (= (jj wt "log" "--no-graph" "-r" tip "-T" "description")
           (jj wt "log" "--no-graph" "-r" "main@origin" "-T" "description")) "the tip it read is what landed")
    (is (fs/exists? (fs/path wt "late.txt")) "the late edit is still in the worktree")))

(deftest a-merge-with-nothing-of-its-own-lands-both-its-parents
  (let [wt (repo!)
        a  (rev wt "@-")]
    (jj wt "new" "main@origin" "-m" "B")
    (spit (str (fs/path wt "b.txt")) "b\n")
    (let [b (rev wt "@")]
      (jj wt "new" a b "-m" "merge A and B")
      (let [merge (rev wt "@")
            {:keys [result out]} (run-gate wt merge)
            files (set (str/split-lines (jj wt "file" "list" "-r" "main@origin")))]
        (is (:landed result) out)
        (is (= (:landed result) (rev wt "main@origin")))
        (is (contains? files "a.txt") "the first parent's change landed")
        (is (contains? files "b.txt") "the second parent's change landed")))))

(deftest a-merge-whose-parents-conflict-is-refused
  (let [wt   (repo!)
        a    (rev wt "@-")
        main (rev wt "main@origin")]
    (jj wt "new" "main@origin" "-m" "B")
    (spit (str (fs/path wt "a.txt")) "not a\n")
    (let [b (rev wt "@")]
      (jj wt "new" a b "-m" "merge A and B")
      (let [{:keys [result out]} (run-gate wt (rev wt "@"))]
        (is (str/includes? (str (:refused result)) "conflicts with origin's main") out)
        (is (= main (rev wt "main@origin")) "nothing is pushed")))))

(deftest an-undescribed-change-is-refused
  (let [wt (repo!)]
    (jj wt "describe" "-r" "@-" "-m" "")
    (is (str/includes? (:refused (:result (run-gate wt (rev wt "@-")))) "no description"))))

(deftest no-step-of-a-landing-restores-the-repository
  (is (not (re-find #"\"op\"\s+\"restore\"" (slurp "src/tasks/nido_land.clj")))))

;; ── the core's checkout ─────────────────────────────────────────────────────

(defn- restart [{:keys [checkout rebase-exit conflicts? holds? upgrade-exit]
                 :or {checkout "/root" rebase-exit 0 holds? true upgrade-exit 0}}]
  (let [calls    (atom [])
        upgraded (atom nil)]
    (with-redefs [land/daemon-checkout (fn [] checkout)
                  jj/jj! (fn [_ & args]
                           (swap! calls conj (vec args))
                           (cond
                             (= "rebase" (first args)) {:exit rebase-exit :out "" :err "boom"}
                             ;; The op log's head is another workspace's operation ("op3"),
                             ;; recorded after the rebase; only a query by the rebase's own mark
                             ;; finds the rebase ("op2"), and only when it made one.
                             (= ["op" "log"] (take 2 args))
                             (let [rb   (some #(when (= "rebase" (first %)) %) @calls)
                                   mark (when rb (last rb))]
                               {:exit 0 :err ""
                                :out (cond (nil? rb) "op1"
                                           (and mark (zero? rebase-exit) (some #(str/includes? % mark) args)) "op2"
                                           (some #{"-n"} args) "op3"
                                           :else "")})
                             (some #{"(main@origin..@) & conflicts()"} args)
                             {:exit 0 :out (if conflicts? "abc" "") :err ""}
                             :else {:exit 0 :out "op1" :err ""}))
                  layers/resolve-rev (fn [_ _] (when holds? "tip"))
                  p/sh (fn [opts & argv]
                         (reset! upgraded [(:dir opts) (last argv)])
                         {:exit upgrade-exit :out "" :err "refused"})]
      {:result (#'land/restart-daemon! "tip") :calls @calls :upgraded @upgraded})))

(deftest a-landing-rebases-the-checkout-and-upgrades-the-core
  (let [{:keys [result calls upgraded]} (restart {})]
    (is (some #(= ["rebase" "-b" "@" "-d" "main@origin"] (take 5 %)) calls)
        "the services load the root checkout, so it moves onto the landing at once")
    (is (= ["/root" "nido:core:upgrade"] upgraded) "the upgrade runs from that checkout")
    (is (= {:upgraded "tip"} result))))

(deftest no-supervised-intake-upgrades-nothing
  (let [{:keys [result upgraded calls]} (restart {:checkout nil})]
    (is (:skipped result))
    (is (nil? upgraded))
    (is (empty? calls))))

(deftest a-conflicting-checkout-rebase-is-undone-and-upgrades-nothing
  (doseq [opts [{:conflicts? true} {:rebase-exit 1} {:holds? false}]]
    (let [{:keys [result calls upgraded]} (restart opts)]
      (is (:failed result) (pr-str opts))
      (if (zero? (:rebase-exit opts 0))
        (is (some #(= ["op" "revert" "op2"] %) calls) (str "only the rebase's own operation is reverted " (pr-str opts)))
        (is (not-any? #(= "revert" (second %)) calls) (str "a rebase that made no operation reverts nothing " (pr-str opts))))
      (is (not-any? #(= ["op" "restore"] (take 2 %)) calls) (pr-str opts))
      (is (nil? upgraded) (pr-str opts)))))

(deftest a-refused-upgrade-fails-and-keeps-the-rebase
  (let [{:keys [result calls]} (restart {:upgrade-exit 3})]
    (is (str/includes? (:failed result) "refused"))
    (is (:way-out result))
    (is (not-any? #(#{"restore" "revert"} (second %)) calls))))

(deftest the-landing-prints-one-core-line
  (doseq [[outcome code] [[{:upgraded "abcdef"} 0]
                          [{:skipped "not installed" :way-out "w"} 0]
                          [{:failed "boom" :way-out "w"} 1]]]
    (let [ret (atom nil)
          out (with-out-str (reset! ret (#'land/report-restart! outcome)))]
      (is (= code @ret) (pr-str outcome))
      (is (= 1 (count (re-seq #"(?m)^land · core " out))) out))))
