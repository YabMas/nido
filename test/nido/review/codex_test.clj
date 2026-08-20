(ns nido.review.codex-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.review.codex :as codex]
   [nido.vsdd.jj :as jj]
   [nido.coordinator.state :as cstate]
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]))

(deftest findings-schema-is-strict-output-compatible
  ;; Codex/gpt strict structured-output mode requires every property of every
  ;; object node to also appear in that node's "required" list. A missing key
  ;; makes the model reject the request with 400 invalid_json_schema, so the
  ;; review turn never even starts — fatal for the whole loop.
  (let [schema     (json/parse-string
                    (slurp (io/resource "review/findings_schema.json")) true)
        violations (atom [])]
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
    (is (= [] @violations)
        "every object property must be listed in \"required\" (strict mode)")))

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
            :file "/w/pay.js" :line-start 4 :line-end 4}
           (first findings)))))

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

(deftest review!-empty-diff-is-clean
  (with-redefs [jj/jj! (fn [_dir & args]
                         (if (= "diff" (first args))
                           {:exit 0 :out "" :err ""}            ; empty manifest
                           {:exit 0 :out "BASEREV\n" :err ""}))] ; merge-base
    (is (= {:status :clean :findings [] :base-rev "BASEREV" :manifest ""}
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
