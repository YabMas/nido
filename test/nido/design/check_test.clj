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

(deftest the-design-document-comes-from-fukan-not-from-the-files
  (testing "fukan is what knows what a design IS — which vocabularies were instantiated, which
            nodes are the project's rather than the meta-grammar's, how an instance was
            authored. Reading canvas/**.clj worked only where a design happens to be one
            hand-written file, which is a property of one project, not of designs."
    (let [wt (worktree-with {"canvas/bands.clj" ";; requires and helper fns live here too"})]
      (try
        (with-redefs [project/get-project
                      (constantly {:design {:cmd (answering "(Band Platform \"the floor\" {})" 0)}})]
          (is (= {:status :described :document "(Band Platform \"the floor\" {})"}
                 (design/describe "p" wt))
              "what fukan rendered, not what the file said"))
        (finally (fs/delete-tree wt))))))

(deftest a-project-with-no-canvas-is-unmodelled-rather-than-undecidable
  (let [wt (worktree-with {"src/a.clj" "(ns a)"})]
    (try
      (with-redefs [project/get-project (constantly nil)]
        (is (= {:status :unmodelled} (design/describe "p" wt))
            "nothing to render, and nothing went wrong — the two must not share an answer"))
      (finally (fs/delete-tree wt)))))

(deftest a-renderer-that-failed-says-so-rather-than-answering-unmodelled
  (testing "the failure that used to be invisible. Both cases produced nil, so a briefing
            omitted its section either way and a modelled project whose render broke was
            briefed exactly like a project that declares nothing — while the landing gate went
            on refusing violations of the declaration nobody was shown."
    (let [wt (worktree-with {"canvas/bands.clj" "(ns canvas.bands)"})]
      (try
        (with-redefs [project/get-project
                      (constantly {:design {:cmd (answering "{:undecidable true}" 2)}})]
          (is (= :undecidable (:status (design/describe "p" wt)))
              "a render that exited non-zero is nobody being able to tell, not an absent design")
          (is (seq (:error (design/describe "p" wt)))
              "and it carries why, since the reader's next move depends on which failure it was"))
        (finally (fs/delete-tree wt))))))

(deftest a-render-that-runs-out-of-time-names-selection-as-the-way-out
  (testing "the case that actually bit nido: unscoped, a large model cannot render inside a
            session start's budget, and the fix is to ask for less rather than to wait longer.
            Said only when nothing narrowed it — a reader who already set a scope must not be
            sent after a knob they turned."
    (let [wt (worktree-with {"canvas/bands.clj" "(ns canvas.bands)"})]
      (try
        (with-redefs [project/get-project
                      (constantly {:design {:cmd ["sh" "-c" "sleep 30"]}})]
          ;; var-quoted because the budget is private: it is a property of the seam, not a knob
          (with-redefs-fn {#'design/describe-timeout-ms 200}
            (fn []
              (let [{:keys [status error]} (design/describe "p" wt)]
                (is (= :undecidable status))
                (is (str/includes? error "--select")
                    "the message names the escape — the reader is an agent or a hurried human")))))
        (finally (fs/delete-tree wt))))))

(deftest the-document-is-whole-however-large-it-is
  (testing "the seam renders, it does not budget. A cap here was inherited by every reader,
            including the one that compares two renderings — for which a truncated document is
            not a smaller answer but a wrong one, since every change past the cut reads as no
            change. The briefing that has a budget applies its own."
    (let [wt (worktree-with {"canvas/big.clj" "(ns canvas.big)"})]
      (try
        (with-redefs [project/get-project
                      (constantly {:design {:cmd (answering (str/join (repeat 20000 "x")) 0)}})]
          (let [{:keys [status document]} (design/describe "p" wt)]
            (is (= :described status))
            (is (= 20000 (count document)) "whole, not capped")))
        (finally (fs/delete-tree wt))))))

(defn- echoing-args
  "A `:cmd` that prints the arguments nido appended to it — the verb lands in `$0`, its flags
   in `$*` — so a test can assert what was actually asked of fukan."
  []
  ["sh" "-c" "echo \"$0 $*\"; exit 0"])

(deftest a-configured-selection-reaches-the-renderer
  (testing "a design that has outgrown a briefing is SCOPED rather than cut: `:select` carries
            datalog clauses through to fukan, which answers a narrower question in full instead
            of a wide one truncated"
    (let [wt (worktree-with {"canvas/bands.clj" "(ns canvas.bands)"})]
      (try
        (with-redefs [project/get-project
                      (constantly {:design {:cmd (echoing-args) :select '[(Band ?n)]}})]
          (let [asked (:document (design/describe "p" wt))]
            (is (str/includes? asked "describe"))
            (is (str/includes? asked "--select [(Band ?n)]")
                "the clauses arrive as one argument, readable by fukan's edn parse")))
        (finally (fs/delete-tree wt))))))

(deftest no-selection-asks-for-the-whole-design
  (testing "the default is everything. Most projects declare a design small enough to read
            whole, and a default that scoped it would hide the rest without saying so"
    (let [wt (worktree-with {"canvas/bands.clj" "(ns canvas.bands)"})]
      (try
        (with-redefs [project/get-project (constantly {:design {:cmd (echoing-args)}})]
          (is (not (str/includes? (:document (design/describe "p" wt)) "--select"))))
        (finally (fs/delete-tree wt))))))

(deftest an-explicit-scope-beats-the-projects-configured-default
  (testing "the caller with a scope got it from a baseline that read the code; the project
            default was set by someone who had not"
    (let [wt (worktree-with {"canvas/bands.clj" "(ns canvas.bands)"})]
      (try
        (with-redefs [project/get-project
                      (constantly {:design {:cmd (echoing-args) :select '[(Band ?n)]}})]
          (is (str/includes? (:document (design/describe "p" wt '[(Module ?n)])) "--select [(Module ?n)]")
              "the workstream's scope, not the project's"))
        (finally (fs/delete-tree wt))))))

(deftest no-scope-and-no-default-asks-for-everything
  (testing "before its workstream is baselined a session has no scope, and that is right: the
            round whose job is to establish one cannot be handed one"
    (let [wt (worktree-with {"canvas/bands.clj" "(ns canvas.bands)"})]
      (try
        (with-redefs [project/get-project (constantly {:design {:cmd (echoing-args)}})]
          (is (not (str/includes? (:document (design/describe "p" wt nil)) "--select"))))
        (finally (fs/delete-tree wt))))))

;; ── diff: what a branch changes about the declaration ────────────────────────

(defn- rendering
  "A `describe` that answers `by-dir`: {<dir> <document>}. A dir it does not know declares
   nothing, which is how the adoption case is set up."
  [by-dir]
  (fn [_project dir & _]
    (if-let [doc (get by-dir dir)]
      {:status :described :document doc}
      {:status :unmodelled})))

(deftest a-branch-that-changed-nothing-says-so-rather-than-showing-an-empty-diff
  (testing "unchanged and unmodelled are different answers, and only the first is a fact about
            the branch — a project with no design never had one to leave alone"
    (with-redefs [design/describe (rendering {"/head" "(Band A)" "/base" "(Band A)"})]
      (let [{:keys [status digest diff]} (design/diff "p" "/head" "/base")]
        (is (= :unchanged status))
        (is (seq digest) "and still names what it read, so an approval can quote it")
        (is (nil? diff))))))

(deftest a-changed-declaration-comes-back-as-a-diff-of-fukans-own-renderings
  (with-redefs [design/describe (rendering {"/head" "(Band A)\n(Band B)" "/base" "(Band A)"})]
    (let [{:keys [status diff digest]} (design/diff "p" "/head" "/base")]
      (is (= :changed status))
      (is (str/includes? diff "+(Band B)") "the addition, in fukan's words rather than the file's")
      (is (str/includes? diff "the design at the base"))
      (is (seq digest)))))

(deftest adopting-fukan-is-a-change-rather-than-an-error
  (testing "a branch that adds a canvas changes the declared design from nothing to something,
            which is exactly what a reviewer wants to see"
    (with-redefs [design/describe (rendering {"/head" "(Band A)"})]
      (let [{:keys [status diff]} (design/diff "p" "/head" "/base")]
        (is (= :changed status))
        (is (str/includes? diff "+(Band A)"))))))

(deftest a-project-with-no-design-here-has-no-diff-to-show
  (with-redefs [design/describe (rendering {})]
    (is (= {:status :unmodelled} (design/diff "p" "/head" "/base")))))

(deftest an-end-that-would-not-render-is-undecidable-and-says-which-end
  (testing "the two failures need different fixes — a branch that broke its own canvas and a
            base that cannot be rendered are not the same problem"
    (with-redefs [design/describe (fn [_ dir & _]
                                    (if (= dir "/head")
                                      {:status :undecidable :error "boom"}
                                      {:status :described :document "(Band A)"}))]
      (is (str/includes? (:error (design/diff "p" "/head" "/base")) "this branch's")))
    (with-redefs [design/describe (fn [_ dir & _]
                                    (if (= dir "/base")
                                      {:status :undecidable :error "boom"}
                                      {:status :described :document "(Band A)"}))]
      (let [{:keys [status error]} (design/diff "p" "/head" "/base")]
        (is (= :undecidable status))
        (is (str/includes? error "the base's"))))))
