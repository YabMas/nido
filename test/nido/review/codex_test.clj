(ns nido.review.codex-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.review.codex :as codex]
   [nido.review.prompts :as prompts]
   [nido.vsdd.jj :as jj]
   [nido.coordinator.record.state :as cstate]
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]))

(defn- strict-mode-violations
  "Every object node whose `properties` are not all listed in its `required`.
   Codex/gpt strict structured-output mode rejects such a schema with a 400
   invalid_json_schema, so the review turn never starts — fatal for the loop."
  [schema]
  (let [violations (atom [])]
    (letfn [(walk [node path]
              (when (map? node)
                (when-let [props (:properties node)]
                  (let [req     (set (map keyword (:required node)))
                        missing (remove req (keys props))]
                    (when (seq missing)
                      (swap! violations conj {:path path :missing (vec missing)}))))
                (doseq [[k v] node]
                  (walk v (conj path k)))))]
      (walk schema []))
    @violations))

(deftest findings-schema-is-strict-output-compatible
  (is (= [] (strict-mode-violations
             (json/parse-string
              (slurp (io/resource "review/findings_schema.json")) true)))
      "every object property must be listed in \"required\" (strict mode)"))

(deftest composition-schema-is-strict-output-compatible
  ;; The composition variant adds two properties, and adding a property without
  ;; adding it to "required" is the same fatal 400 — on the pass that has the
  ;; least chance of anyone noticing, since a stack of one layer never runs it.
  (is (= [] (strict-mode-violations
             (json/parse-string (codex/schema-json true) true)))))

(deftest composition-schema-demands-the-kind-and-the-span
  (let [item (get-in (json/parse-string (codex/schema-json true) true)
                     [:properties :findings :items])]
    (is (= (mapv :kind prompts/composition-kinds)
           (get-in item [:properties :kind :enum]))
        "the enum is the taxonomy the primer teaches — a kind the prompt names
         but the schema refuses is a 400 on every round")
    (is (= "array" (get-in item [:properties :layers :type])))
    (is (every? (set (:required item)) ["kind" "layers"]))))

(deftest schema-json-without-a-composition-is-the-plain-findings-schema
  (is (= (json/parse-string (slurp (io/resource "review/findings_schema.json")) true)
         (json/parse-string (codex/schema-json false) true))))

(def sample-output
  (str "{\"findings\":[{\"title\":\"[P1] Remove the extra accumulation\","
       "\"body\":\"Overcharges every payment.\",\"confidence_score\":0.9,"
       "\"priority\":1,\"reach\":\"structural\","
       "\"code_location\":{\"absolute_file_path\":\"/w/pay.js\","
       "\"line_range\":{\"start\":4,\"end\":4}}}],"
       "\"overall_correctness\":\"incorrect\"}"))

(deftest parse-output-normalizes-findings
  (let [{:keys [findings overall-correctness]} (codex/parse-output sample-output)]
    (is (= "incorrect" overall-correctness))
    (is (= 1 (count findings)))
    (is (= {:title "[P1] Remove the extra accumulation"
            :body "Overcharges every payment."
            :priority 1 :reach :structural :confidence 0.9
            :file "/w/pay.js" :line-start 4 :line-end 4
            :id (codex/finding-id {:file "/w/pay.js" :line-start 4
                                   :title "[P1] Remove the extra accumulation"})}
           (first findings)))))

(deftest finding-id-is-stable-and-position-independent
  ;; Indices into "this round's findings" cannot survive re-attribution across
  ;; layers, and leave a report that cannot say WHY a finding was dropped.
  (let [f {:file "a.clj" :line-start 3 :title "t"}]
    (is (= (codex/finding-id f) (codex/finding-id (assoc f :body "different"))))
    (is (not= (codex/finding-id f) (codex/finding-id (assoc f :line-start 4))))))

(deftest parse-output-handles-no-findings
  (let [{:keys [findings overall-correctness]}
        (codex/parse-output "{\"findings\":[],\"overall_correctness\":\"correct\"}")]
    (is (= [] findings))
    (is (= "correct" overall-correctness))))

(deftest codex-argv-feeds-prompt-via-stdin-not-argv
  (let [[opts & args] (codex/codex-argv {:cwd "/w" :schema-path "/s.json"
                                         :out-path "/o.json" :log-path "/l.log"
                                         :prompt "REVIEW INSTRUCTIONS\n\n<huge diff>"})]
    (is (= "REVIEW INSTRUCTIONS\n\n<huge diff>" (:in opts)) "prompt fed via stdin")
    (is (not-any? #(= "REVIEW INSTRUCTIONS\n\n<huge diff>" %) args)
        "prompt is NOT an argv element")
    (is (= "-" (last args)) "positional prompt is '-' (read from stdin)")
    (is (= "/w" (:dir opts)))))

(deftest review!-empty-diff-is-nothing-to-review-not-clean
  ;; :clean is a reviewer's verdict on code it read. An empty manifest means no
  ;; reviewer ran at all, and this is the last point the two can be told apart —
  ;; past here both are a target carrying no findings.
  (with-redefs [jj/jj! (fn [_dir & args]
                         (if (= "diff" (first args))
                           {:exit 0 :out "" :err ""}            ; empty manifest
                           {:exit 0 :out "BASEREV\n" :err ""}))] ; merge-base
    (is (= {:status :nothing-to-review :findings [] :base-rev "BASEREV" :manifest ""}
           (codex/review! {:cwd "/w" :from "BASEREV" :run-id "r1"})))))

(deftest review!-parses-codex-output
  (let [tmp (str (fs/create-temp-dir))]
    (with-redefs [jj/jj!         (fn [_dir & args]
                                   (if (= "diff" (first args))
                                     {:exit 0 :out "diff --git a/x b/x\n+bug" :err ""}
                                     {:exit 0 :out "BASEREV\n" :err ""}))  ; merge-base
                  cstate/run-dir (fn [_] tmp)
                  codex/run-codex! (fn [_opts]
                                     (spit (str (fs/path tmp "stack-round-1-out.json"))
                                           sample-output)
                                     {:exit 0})]
      (let [{:keys [status findings overall-correctness]}
            (codex/review! {:cwd "/w" :from "BASEREV" :run-id "r1"})]
        (is (nil? status))
        (is (= 1 (count findings)))
        (is (= "incorrect" overall-correctness))))))

(deftest review!-throws-on-codex-failure
  (let [tmp (str (fs/create-temp-dir))]
    (with-redefs [jj/jj!           (fn [_ & _] {:exit 0 :out "diff --git a/x b/x" :err ""})
                  cstate/run-dir   (fn [_] tmp)
                  codex/run-codex! (fn [_] {:exit 1})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (codex/review! {:cwd "/w" :from "BASEREV" :run-id "r1"}))))))

(deftest review!-fails-loud-on-jj-diff-error
  ;; A non-zero `jj diff` (e.g. cwd isn't a jj workspace, or a bad base) must
  ;; NOT read as a clean diff — that would silently pass review for code that
  ;; was never looked at. Fail loud as :review-failed instead.
  (with-redefs [jj/jj! (fn [& _]
                         {:exit 1 :out "" :err "Error: There is no jj repo in \".\""})]
    (let [reason (try (codex/review! {:cwd "/not-a-repo" :from "BASEREV" :run-id "r1"})
                      nil
                      (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))]
      (is (= :review-failed reason)))))

(deftest codex-argv-restricts-review-to-read-only-sandbox
  (let [[_opts & args] (codex/codex-argv {:cwd "/w" :schema-path "/s.json"
                                          :out-path "/o.json" :log-path "/l.log"
                                          :prompt "p"})]
    (is (some #{"read-only"} args)
        "codex exec runs under a read-only sandbox during review")))

(deftest codex-argv-captures-output-to-log-not-terminal
  ;; codex's raw streaming output must NOT inherit the terminal (it floods the
  ;; review TUI). Capture stdout to the per-round log, merge stderr into it.
  (let [[opts & _] (codex/codex-argv {:cwd "/w" :schema-path "/s.json"
                                      :out-path "/o.json" :log-path "/l.log"
                                      :prompt "p"})]
    (is (= :write (:out opts)) "stdout is redirected (not :inherit)")
    (is (= "/l.log" (str (:out-file opts))) "stdout goes to the log path")
    (is (= :out (:err opts)) "stderr merges into stdout")))

(deftest review!-explores-via-manifest-not-inlined-diff
  ;; The whole concatenated diff overflows codex's 1 MiB input limit. Instead the
  ;; prompt carries only the CHANGED-FILE MANIFEST (`jj diff --name-only`) plus
  ;; the base ref, and codex pulls per-file diffs itself. Assert the manifest is
  ;; built name-only and the changed files reach the prompt.
  (let [tmp      (str (fs/create-temp-dir))
        captured (atom nil)]
    (with-redefs [jj/jj!           (fn [_dir & args]
                                     (if (= "diff" (first args))
                                       (do (is (some #{"--name-only"} args)
                                               "manifest is built with --name-only")
                                           {:exit 0 :out "src/a.clj\nsrc/b.clj" :err ""})
                                       ;; merge-base resolution
                                       {:exit 0 :out "BASEREV\n" :err ""}))
                  cstate/run-dir   (fn [_] tmp)
                  codex/run-codex! (fn [opts]
                                     (reset! captured (:prompt opts))
                                     (spit (str (fs/path tmp "stack-round-1-out.json"))
                                           sample-output)
                                     {:exit 0})]
      (codex/review! {:cwd "/w" :from "BASEREV" :run-id "r1"})
      (is (re-find #"src/a\.clj" @captured) "changed files appear in the prompt")
      (is (re-find #"src/b\.clj" @captured)))))

(deftest merge-base-resolves-the-fork-point
  ;; `jj diff --from main --to @` is a 2-way tree diff: when main has advanced
  ;; since the branch forked, all of main's parallel work shows up as spurious
  ;; deletions (180 files instead of the PR's 29). The comparison point must be
  ;; the MERGE BASE (fork point) of @ and the base — matching what the PR's
  ;; "Files changed" shows. review! is now AIMED by its caller, so this is the
  ;; fn the caller uses to aim it.
  (let [revset (atom nil)]
    (with-redefs [jj/jj! (fn [_dir & args]
                           (reset! revset (second (drop-while #(not= "-r" %) args)))
                           {:exit 0 :out "MERGEBASE123\n" :err ""})]
      (is (= "MERGEBASE123" (codex/merge-base "/w" "main")))
      (is (= "heads(::@ & ::main)" @revset)))))

(deftest review!-aims-the-diff-and-the-prompt-at-the-given-range
  (let [tmp      (str (fs/create-temp-dir))
        diff-args (atom nil)
        captured (atom nil)]
    (with-redefs [jj/jj!           (fn [_dir & args]
                                     (reset! diff-args (vec args))
                                     {:exit 0 :out "src/a.clj" :err ""})
                  cstate/run-dir   (fn [_] tmp)
                  codex/run-codex! (fn [opts]
                                     (reset! captured (:prompt opts))
                                     (spit (str (fs/path tmp "drop-legacy-round-2-out.json"))
                                           sample-output)
                                     {:exit 0})]
      (codex/review! {:cwd "/w" :from "LOWTIP" :to "OWNTIP" :run-id "r1"
                      :iter 2 :label "drop-legacy"})
      (is (= "LOWTIP" (second (drop-while #(not= "--from" %) @diff-args))))
      (is (= "OWNTIP" (second (drop-while #(not= "--to" %) @diff-args))))
      (is (re-find #"Head revision \(use this exact value as <head>\): OWNTIP" @captured)
          "codex is told which revision to read file content at"))))

(deftest review!-scopes-its-artifacts-by-label-so-parallel-layers-cannot-collide
  ;; With one shared out-path the last layer to finish wins and every layer
  ;; reports its findings — silently, since nothing errors.
  (let [tmp   (str (fs/create-temp-dir))
        paths (atom [])]
    (with-redefs [jj/jj!           (fn [& _] {:exit 0 :out "src/a.clj" :err ""})
                  cstate/run-dir   (fn [_] tmp)
                  codex/run-codex! (fn [opts]
                                     (swap! paths conj (:out-path opts))
                                     (spit (:out-path opts) sample-output)
                                     {:exit 0})]
      (codex/review! {:cwd "/w" :from "A" :to "B" :run-id "r" :iter 1 :label "l1"})
      (codex/review! {:cwd "/w" :from "B" :to "C" :run-id "r" :iter 1 :label "l2"})
      (is (= 2 (count (distinct @paths))) "each layer writes its own output file"))))

(deftest parse-output-tolerates-a-finding-with-no-reach
  (let [out (str "{\"findings\":[{\"title\":\"t\",\"body\":\"b\","
                 "\"confidence_score\":0.5,\"priority\":2,"
                 "\"code_location\":{\"absolute_file_path\":\"/w/a.clj\","
                 "\"line_range\":{\"start\":1,\"end\":2}}}],"
                 "\"overall_correctness\":\"correct\"}")
        f   (first (:findings (codex/parse-output out)))]
    (is (nil? (:reach f))
        "the schema requires it, but a stale or hand-made payload must still parse")))


(deftest parse-output-carries-a-composition-findings-kind-and-span
  (let [out (str "{\"findings\":[{\"title\":\"t\",\"body\":\"b\","
                 "\"confidence_score\":0.5,\"priority\":2,\"reach\":\"structural\","
                 "\"kind\":\"misplaced-seam\",\"layers\":[\"series\",\"banner\"],"
                 "\"code_location\":{\"absolute_file_path\":\"/w/a.clj\","
                 "\"line_range\":{\"start\":1,\"end\":2}}}],"
                 "\"overall_correctness\":\"correct\"}")
        f   (first (:findings (codex/parse-output out)))]
    (is (= :misplaced-seam (:kind f)))
    (is (= ["series" "banner"] (:layers f)))))

(deftest parse-output-leaves-a-layer-finding-without-the-composition-keys
  ;; Stamping every finding with two nils would put the composition vocabulary
  ;; on findings that have no claim to it — and give the warden a `kind` field
  ;; to read on rows where it means nothing.
  (let [f (first (:findings (codex/parse-output sample-output)))]
    (is (not (contains? f :kind)))
    (is (not (contains? f :layers)))))

(def ^:private stack-of-two
  {:layers [{:label "series" :from "FORK" :tip "cA" :claim "the entity"}
            {:label "banner" :from "cA" :tip "cB" :claim "the UI"}]})

(deftest review!-primes-the-composition-pass-with-the-stack-and-its-revisions
  (let [tmp      (str (fs/create-temp-dir))
        captured (atom nil)
        schema   (atom nil)]
    (with-redefs [jj/jj!           (fn [& _] {:exit 0 :out "src/a.clj" :err ""})
                  cstate/run-dir   (fn [_] tmp)
                  codex/run-codex! (fn [opts]
                                     (reset! captured (:prompt opts))
                                     (reset! schema (slurp (:schema-path opts)))
                                     (spit (:out-path opts) sample-output)
                                     {:exit 0})]
      (codex/review! {:cwd "/w" :from "FORK" :to "@" :run-id "r" :iter 1
                      :label "stack" :composition stack-of-two})
      (is (re-find #"COMPOSITION PASS" @captured))
      (is (re-find #"--from cA --to cB" @captured) "the intermediate revisions")
      (is (re-find #"misplaced-seam" @captured) "the taxonomy")
      (is (re-find #"misplaced-seam" @schema)
          "the schema follows the primer: a reviewer taught the taxonomy is
           asked for it"))))

(deftest review!-of-one-layer-gets-neither-the-primer-nor-the-composition-schema
  ;; The two are exclusive by construction — a target is one layer or the
  ;; composition of several — and asking a reviewer that was never taught the
  ;; taxonomy to classify by it is a contract nothing can meet.
  (let [tmp      (str (fs/create-temp-dir))
        captured (atom nil)
        schema   (atom nil)]
    (with-redefs [jj/jj!           (fn [& _] {:exit 0 :out "src/a.clj" :err ""})
                  cstate/run-dir   (fn [_] tmp)
                  codex/run-codex! (fn [opts]
                                     (reset! captured (:prompt opts))
                                     (reset! schema (slurp (:schema-path opts)))
                                     (spit (:out-path opts) sample-output)
                                     {:exit 0})]
      (codex/review! {:cwd "/w" :from "cA" :to "cB" :run-id "r" :iter 1
                      :label "banner"
                      :brief {:claims "renders the banner"
                              :out-of-scope "the export"}})
      (is (re-find #"BOUNDED TO ONE LAYER" @captured))
      (is (nil? (re-find #"COMPOSITION PASS" @captured)))
      (is (nil? (re-find #"misplaced-seam" @schema))))))
