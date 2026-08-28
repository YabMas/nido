(ns nido.design.check-test
  "The seam between nido and fukan, exercised without a JVM: `:cmd` is configuration, so a test
   substitutes a shell script that answers the way fukan would and asserts what nido makes of it."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.design.check :as design]
   [nido.platform.project :as project]))

(defn- worktree-with
  "A temp worktree holding `files` (path → content)."
  [files]
  (let [tmp (fs/create-temp-dir)]
    (doseq [[p content] files
            :let [f (fs/path tmp p)]]
      (fs/create-dirs (fs/parent f))
      (spit (str f) content))
    tmp))

(defn- answering
  "A `:cmd` that prints `out` and exits `code` — fukan's contract, without fukan."
  [out code]
  ["sh" "-c" (str "cat <<'EOF'\n" out "\nEOF\nexit " code)])

(deftest a-project-with-no-canvas-declares-no-design
  (let [wt (worktree-with {"src/nido/platform/core.clj" "(ns nido.platform.core)"})]
    (try
      (with-redefs [project/get-project (constantly nil)]
        (is (nil? (design/design-of "p" wt)))
        (is (= :unmodelled (:status (design/check "p" wt)))
            "an unmodelled project is not a broken one — most projects nido drives declare nothing"))
      (finally (fs/delete-tree wt)))))

(deftest a-canvas-in-the-worktree-is-the-declaration
  (testing "detection is by convention: .clj under the spec-dirs, no registration"
    (let [wt (worktree-with {"canvas/bands.clj" "(ns canvas.bands)"})]
      (try
        (with-redefs [project/get-project (constantly nil)]
          (let [d (design/design-of "p" wt)]
            (is (some? d))
            (is (= 1 (count (:files d))))
            (is (str/ends-with? (first (:files d)) "canvas/bands.clj"))))
        (finally (fs/delete-tree wt))))))

(deftest a-project-may-override-where-its-design-lives
  (let [wt (worktree-with {"design/model.clj" "(ns design.model)"})]
    (try
      (with-redefs [project/get-project (constantly {:design {:spec-dirs ["design"] :src "src/main"}})]
        (let [d (design/design-of "p" wt)]
          (is (some? d) "the override is where it looks")
          (is (= "src/main" (:src d)))
          (is (= (:cmd design/default-design) (:cmd d))
              "an override supplies only what it changes")))
      (finally (fs/delete-tree wt)))))

(deftest the-checkers-exit-code-is-the-verdict
  (let [wt (worktree-with {"canvas/bands.clj" "(ns canvas.bands)"})
        run (fn [out code]
              (with-redefs [project/get-project (constantly {:design {:cmd (answering out code)}})]
                (design/check "p" wt)))]
    (try
      (is (= :satisfied (:status (run "{:ok true :violations []}" 0))))

      (testing "a violation carries its named offender TUPLES through unflattened"
        (let [r (run (pr-str {:ok false
                              :violations [{:law "no undeclared edge"
                                            :offenders [["a.b" "c.d" "Review" "Vsdd"]]}]})
                     1)]
          (is (= :violated (:status r)))
          (is (= [["a.b" "c.d" "Review" "Vsdd"]] (:offenders (first (:violations r)))))))

      (testing "a checker that did not decide is never read as a clean bill of health"
        (is (= :undecidable (:status (run "{:undecidable true :error \"a law would not compile\"}" 2))))
        (is (= :undecidable (:status (run "this is not edn at all" 1)))
            "exit 1 with an unreadable report decided nothing either"))
      (finally (fs/delete-tree wt)))))

(deftest an-offender-row-is-labelled-by-the-law-s-own-var-names
  (testing "four names in a line read as a four-hop chain, which is not what they are. The
            labels come from the law, so nido never holds a second, drifting copy of what
            each law's columns mean."
    (let [text (design/violation-text
                {:status :violated
                 :violations [{:law "no undeclared edge"
                               :vars ["?from" "?to" "?from-band" "?to-band"]
                               :offenders [["a.b" "c.d" "Review" "Vsdd"]]}]})]
      (is (str/includes? text "from=a.b  to=c.d  from-band=Review  to-band=Vsdd"))))

  (testing "a law that named no vars still renders every column — the second half of an edge
            is the half that says what to do about it"
    (let [text (design/violation-text
                {:status :violated
                 :violations [{:law "no undeclared edge" :offenders [["a.b" "c.d"]]}]})]
      (is (str/includes? text "a.b · c.d"))))

  (is (= "" (design/violation-text {:status :satisfied}))
      "nothing to say renders as nothing, so a caller can splice it in unconditionally"))

(deftest an-oversized-declaration-is-truncated-out-loud
  (let [wt (worktree-with {"canvas/big.clj" (str/join (repeat 20000 "x"))})]
    (try
      (with-redefs [project/get-project (constantly nil)]
        (let [text (design/declaration-text (design/design-of "p" wt))]
          (is (< (count text) 20000))
          (is (str/includes? text "truncated") "never silently")))
      (finally (fs/delete-tree wt)))))
