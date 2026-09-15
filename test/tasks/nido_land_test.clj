;; test/tasks/nido_land_test.clj
(ns tasks.nido-land-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.record.standing :as standing]
   [nido.coordinator.record.workstream :as cws]
   [nido.design.check :as design]
   [nido.platform.project :as project]
   [nido.review.stages :as stages]
   [nido.session.lifecycle :as lifecycle]
   [nido.vsdd.jj :as jj]
   [tasks.nido-design :as nido-design]
   [tasks.nido-land :as land]))

(def ^:private a-design {:format :design :seq 4 :summary "s"})

(defn- run
  "Drive the gate with both halves stubbed. `structure` defaults to nil = the cwd belongs to no
   registered project, which is what every standing-only case wants."
  [{:keys [session? design standing structure elements base]}]
  (let [out (java.io.StringWriter.)]
    (binding [*out* out]
      (with-redefs [lifecycle/worktree-from-cwd (fn [g] g)
                    stages/project+ws-from-cwd (fn [_] (when session? [:nido "ws-1"]))
                    cws/latest-entry (fn [& _] design)
                    standing/of-design (constantly standing)
                    nido-design/coords (fn [_] (when structure [:nido "/wt"]))
                    ;; :files rides on the result — the check resolved the config to run, so
                    ;; the gate is handed where the declaration lives rather than re-resolving it
                    design/check (constantly (cond-> structure
                                               (and structure (not= :unmodelled (:status structure)))
                                               (assoc :files ["/wt/canvas/bands.clj"])))
                    ;; `elements` is the branch's listing; `base` stands in for main's
                    design/elements (constantly (or elements {:status :unmodelled}))]
        (with-redefs-fn {#'land/base-listing (constantly (or base {:status :unmodelled}))}
          (fn [] [(land/check ":cwd" "/wt") (str out)]))))))

(deftest a-standing-approved-design-lands
  (let [[code out] (run {:session? true :design a-design
                         :standing {:decided? true :cleared? true :approved-by 7}})]
    (is (zero? code))
    (is (str/includes? out "ok"))
    (is (str/includes? out "approved at entry 7"))))

(deftest a-cleared-design-lands-without-a-grant
  ;; A design owing nobody a grant never gets one, so a gate asking :decided?
  ;; would refuse every design the round was built to let through.
  (let [[code out] (run {:session? true :design a-design
                         :standing {:decidable? true :decided? false
                                    :cleared? true :cleared-by 6}})]
    (is (zero? code))
    (is (str/includes? out "cleared at entry 6"))))

(deftest a-cleared-design-nobody-granted-lands
  ;; The arc let it through on a clearance, so refusing it here as unapproved
  ;; would re-impose the grant the round found nobody was owed.
  (let [[code out] (run {:session? true :design a-design
                         :standing {:decidable? true :decided? false
                                    :cleared? true :cleared-by 8}})]
    (is (zero? code))
    (is (str/includes? out "cleared at entry 8"))))

(deftest a-workstream-with-no-design-lands
  ;; Most have none — scratch workstreams, pickups mid-flight — and a gate that
  ;; demanded one of every branch would stop the work that has not reached a
  ;; design yet, which is not what this is for.
  (let [[code out] (run {:session? true :design nil})]
    (is (zero? code))
    (is (str/includes? out "design record holds nothing")
        "the workstream's design RECORD, named as a record — the project's declared
         design is the other subject on this gate and used to be called a structure
         only so the two lines could not be confused")))

(deftest a-cwd-that-is-no-session-lands
  (let [[code _] (run {:session? false})]
    (is (zero? code))))

(deftest a-design-nobody-granted-is-refused-and-told-where-to-grant-it
  (let [[code out] (run {:session? true :design a-design
                         :standing {:decidable? true :decided? false}})]
    (is (= 1 code))
    (is (str/includes? out "REFUSED"))
    (is (str/includes? out "not-approved"))
    (is (str/includes? out "gate inbox"))))

(deftest a-retracted-premise-is-refused-naming-the-entry-and-the-counterexample
  (let [[code out] (run {:session? true :design a-design
                         :standing {:decidable? false :decided? false
                                    :blocked {:reason :premise-retracted :seq 9
                                              :replaced-by 12
                                              :detail "the baseline at entry 2 was retracted by entry 9"}}})]
    (is (= 1 code))
    (is (str/includes? out "entry 9") "the entry responsible")
    (is (str/includes? out "found it FALSE") "and that this is not mere staleness")
    (is (str/includes? out "entry 12 corrects it") "and where to start from")))

(deftest an-unreadable-ledger-refuses-rather-than-waving-through
  (let [[code out] (run {:session? true :design a-design
                         :standing {:indeterminate? true
                                    :blocked {:reason :unreadable-ledger
                                              :detail "an entry could not be read"}}})]
    (is (= 1 code))
    (is (str/includes? out "fails closed"))))

(deftest every-refusal-standing-can-produce-names-a-way-out
  ;; The rule this gate is built on: an agent told only that it is blocked will
  ;; guess or stop. A reason with no route is a wall.
  (let [way-out #'land/way-out]
    (doseq [reason [:premise-unverified :premise-retracted :design-retracted
                    :design-invalidated :premise-superseded
                    :no-premise :not-approved :unreadable-ledger]]
      (let [txt (way-out {:reason reason :seq 3})]
        (is (not (str/includes? txt "No route recorded"))
            (str reason " must name what to do"))
        (is (< 40 (count txt)) (str reason " must say more than a sentence fragment"))))
    (testing "and an unrecognised one says so rather than pretending"
      (is (str/includes? (way-out {:reason :something-new}) "No route recorded")))))

;; ── the second question: does the code still obey the declared structure? ──────
;; Standing asks whether anyone still believes the premise this branch was written
;; against. Structure asks whether the branch left the codebase in the shape the
;; project says it has. A branch can pass one and fail the other in either
;; direction, so neither substitutes for the other.

(deftest a-project-with-no-declared-design-lands
  (let [[code out] (run {:session? true :design nil :structure {:status :unmodelled}})]
    (is (zero? code))
    (is (str/includes? out "declares no design")
        "one sentence, the seam's, shared by every terminal reading")))

(deftest code-that-broke-the-declared-structure-is-refused-with-the-offending-edge
  (let [[code out] (run {:session? true :design nil
                         :structure {:status :violated
                                     :violations [{:law "no undeclared edge"
                                                   :vars ["?from" "?to"]
                                                   :offenders [["a.b" "c.d"]]}]}})]
    (is (= 1 code))
    (is (str/includes? out "REFUSED"))
    (is (str/includes? out "from=a.b  to=c.d") "the finding, not just its count")
    (is (str/includes? out "canvas/bands.clj") "and where the declaration lives")))

(deftest a-structure-check-that-did-not-complete-refuses-rather-than-waving-through
  (let [[code out] (run {:session? true :design nil
                         :structure {:status :undecidable :error "a law would not compile"}})]
    (is (= 2 code) "distinct from a violation: nobody could tell, which is not a clean bill")
    (is (str/includes? out "did not complete"))
    (is (str/includes? out "Fix the checker"))))

(deftest both-questions-are-asked-even-when-the-first-one-refuses
  ;; An agent that has to discover its blockers one push at a time makes one trip
  ;; per blocker.
  (let [[code out] (run {:session? true :design a-design
                         :standing {:decidable? true :decided? false}
                         :structure {:status :violated
                                     :violations [{:law "no undeclared edge"
                                                   :vars ["?from" "?to"]
                                                   :offenders [["a.b" "c.d"]]}]}})]
    (is (= 1 code))
    (is (str/includes? out "not-approved") "the standing refusal")
    (is (str/includes? out "from=a.b  to=c.d") "and the structural one, in the same run")))

;; ── The declared claims ─────────────────────────────────────────────────────
;;
;; The third question: does the declaration carry what a round judged? A claim declared and
;; never judged, or judged and never declared, passes standing and structure untouched.

(def ^:private claimed-design
  (assoc a-design :model {:elements [{:id "canvas.a/m" :sort :module}]
                          :claims   [{:id "one-summing-path" :about ["canvas.a/m"]
                                      :statement "the aggregate is the only summing path"
                                      :evidence {:by :round}}]}))

(defn- claim-row [nm doc about]
  {:id (str "canvas.claims/" nm) :name nm :sort :canvas.vocab.claim/Claim
   :declaration (str (hash [nm doc about])) :doc doc :refs {:about about}})

(defn- listed [& rows] {:status :listed :elements (vec rows)})

(def ^:private stands {:cleared? true :cleared-by 5})

(defn- land-claims [over]
  (run (merge {:session? true :design claimed-design :standing stands
               :structure {:status :satisfied}}
              over)))

(deftest a-branch-declaring-its-designs-claims-lands
  (let [[code out] (land-claims
                    {:elements (listed (claim-row "one-summing-path"
                                                  "the aggregate is the only\n   summing path"
                                                  ["canvas.a/m"]))})]
    (is (zero? code) out)
    (is (str/includes? out "carries the 1 claim the design states")
        "and a docstring wrapped across lines is the same statement")))

(deftest a-project-with-no-canvas-lands-the-claims-its-design-states
  ;; Its claims live in its records alone — an empty declaration would refuse every one of them.
  (let [[code out] (land-claims {:structure {:status :unmodelled}})]
    (is (zero? code) out)
    (is (not (str/includes? out "REFUSED")) out)
    (is (str/includes? out "claims live in its design records alone"))))

(deftest a-claim-the-design-states-and-the-declaration-dropped-is-refused
  (let [[code out] (land-claims {:elements (listed)})]
    (is (= 1 code))
    (is (str/includes? out "one-summing-path — the design states it, and the declaration does not"))
    (is (str/includes? out "How to clear it"))))

(deftest a-claim-declared-and-never-judged-is-refused
  (let [[code out] (land-claims
                    {:elements (listed (claim-row "one-summing-path" "the aggregate is the only summing path"
                                                  ["canvas.a/m"])
                                       (claim-row "rounded-once" "a total is rounded once" ["canvas.a/m"]))})]
    (is (= 1 code))
    (is (str/includes? out "rounded-once — declared on this branch, and no design round judged it"))))

(deftest a-claim-declared-differently-is-refused-saying-how
  (testing "a different statement"
    (let [[code out] (land-claims {:elements (listed (claim-row "one-summing-path" "totals are summed"
                                                                ["canvas.a/m"]))})]
      (is (= 1 code))
      (is (str/includes? out "declared with a statement the design does not make"))))
  (testing "different subjects"
    (let [[code out] (land-claims {:elements (listed (claim-row "one-summing-path"
                                                                "the aggregate is the only summing path"
                                                                ["canvas.a/n"]))})]
      (is (= 1 code))
      (is (str/includes? out "declared about canvas.a/n; the design's is about canvas.a/m")))))

(deftest a-claim-carried-unchanged-from-main-is-another-designs
  (let [earlier (claim-row "an-earlier-claim" "landed with some other design" ["canvas.a/m"])
        [code out] (run {:session? true :design a-design :standing stands
                         :structure {:status :satisfied}
                         :elements (listed earlier) :base (listed earlier)})]
    (is (zero? code) out))
  (let [[code out] (run {:session? true :design a-design :standing stands
                         :structure {:status :satisfied}
                         :elements (listed (claim-row "an-earlier-claim" "restated here" ["canvas.a/m"]))
                         :base (listed (claim-row "an-earlier-claim" "landed with some other design"
                                                  ["canvas.a/m"]))})]
    (is (= 1 code) "but changing it on this branch makes it this branch's, and no round judged that")
    (is (str/includes? out "an-earlier-claim — declared on this branch"))))

(deftest a-claim-id-two-claims-declare-is-refused-naming-both
  ;; Keyed by id, one of them vanishes before the comparison — and when the one left is the
  ;; design's, the other lands unjudged.
  (let [elsewhere (assoc (claim-row "one-summing-path" "totals are summed twice" ["canvas.a/m"])
                         :id "canvas.other/one-summing-path")
        [code out] (land-claims
                    {:elements (listed elsewhere
                                       (claim-row "one-summing-path" "the aggregate is the only summing path"
                                                  ["canvas.a/m"]))})]
    (is (= 1 code) out)
    (is (str/includes? out "one-summing-path — declared as canvas.claims/one-summing-path, canvas.other/one-summing-path"))
    (is (str/includes? out "How to clear it"))))

(deftest an-id-main-gave-two-claims-carries-neither
  (let [earlier (claim-row "an-earlier-claim" "landed with some other design" ["canvas.a/m"])
        [code out] (run {:session? true :design a-design :standing stands
                         :structure {:status :satisfied}
                         :elements (listed earlier)
                         :base (listed (assoc (claim-row "an-earlier-claim" "landed elsewhere" ["canvas.a/m"])
                                              :id "canvas.other/an-earlier-claim")
                                       earlier)})]
    (is (= 1 code) "which of main's two this one repeats cannot be read, so it is this branch's")
    (is (str/includes? out "an-earlier-claim — declared on this branch"))))

(deftest mains-declaration-is-read-by-the-fukan-the-worktree-runs
  ;; Unstubbed down to fukan's command. A `:cmd` that answers only where the project's deps.edn
  ;; resolves its `:fukan` alias, and only over main's declaration, stands in for fukan.
  (let [wt      (fs/create-temp-dir)
        row     (claim-row "an-earlier-claim" "landed with some other design" ["canvas.a/m"])
        fukan   ["sh" "-c" (str "test -f deps.edn && grep -q as-on-main canvas/claims.clj"
                                " || { echo ':fukan alias not found' >&2; exit 1; }\n"
                                "cat <<'EOF'\n" (pr-str {:elements [row]}) "\nEOF")]
        at-main {["file" "list" "-r" "main"]                     "deps.edn\ncanvas/claims.clj\nsrc/a.clj\n"
                 ["file" "show" "-r" "main" "canvas/claims.clj"] ";; as-on-main"}]
    (doseq [[p content] {"deps.edn" "{:aliases {:fukan {:extra-paths [\".\"]}}}"
                         "canvas/claims.clj" ";; as-on-branch"
                         "src/a.clj" "(ns a)"}]
      (fs/create-dirs (fs/parent (fs/path wt p)))
      (spit (str (fs/path wt p)) content))
    (try
      (with-redefs [project/get-project (constantly {:design {:cmd fukan}})
                    jj/jj! (fn [_ & args]
                             (if-let [out (get at-main (vec args))] {:exit 0 :out out} {:exit 1 :out ""}))]
        (is (= {:status :listed :elements [row]} (#'land/base-listing :nido (str wt)))))
      (testing "and deleting what was read deletes none of the worktree it was linked from"
        (is (= ";; as-on-branch" (slurp (str (fs/path wt "canvas/claims.clj")))))
        (is (fs/exists? (fs/path wt "deps.edn")))
        (is (fs/exists? (fs/path wt "src/a.clj"))))
      (finally (fs/delete-tree wt)))))

(deftest a-declaration-nobody-could-read-refuses-rather-than-waving-through
  (let [[code out] (land-claims {:elements {:status :undecidable :error "extraction could not read src/x.clj"}})]
    (is (= 2 code))
    (is (str/includes? out "could not be read: extraction could not read src/x.clj")))
  (let [[code out] (land-claims {:elements (listed (claim-row "one-summing-path"
                                                              "the aggregate is the only summing path"
                                                              ["canvas.a/m"]))
                                 :base {:status :undecidable :error "main names no revision"}})]
    (is (= 2 code) "a base nobody could read cannot say which declared claims are this branch's")
    (is (str/includes? out "main names no revision"))))
