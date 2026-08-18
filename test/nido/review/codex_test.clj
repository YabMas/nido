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
       "\"priority\":1,\"layer\":\"structural\","
       "\"code_location\":{\"absolute_file_path\":\"/w/pay.js\","
       "\"line_range\":{\"start\":4,\"end\":4}}}],"
       "\"overall_correctness\":\"incorrect\"}"))

(deftest parse-output-normalizes-findings
  (let [{:keys [findings overall-correctness]} (codex/parse-output sample-output)]
    (is (= "incorrect" overall-correctness))
    (is (= 1 (count findings)))
    (is (= {:title "[P1] Remove the extra accumulation"
            :body "Overcharges every payment."
            :priority 1 :layer :structural :confidence 0.9
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
    (is (= {:status :clean :findings []}
           (codex/review! {:cwd "/w" :base "main" :run-id "r1"})))))

(deftest review!-parses-codex-output
  (let [tmp (str (fs/create-temp-dir))]
    (with-redefs [jj/jj!         (fn [_dir & args]
                                   (if (= "diff" (first args))
                                     {:exit 0 :out "diff --git a/x b/x\n+bug" :err ""}
                                     {:exit 0 :out "BASEREV\n" :err ""}))  ; merge-base
                  cstate/run-dir (fn [_] tmp)
                  codex/run-codex! (fn [_opts]
                                     (spit (str (fs/path tmp "review-out.json"))
                                           sample-output)
                                     {:exit 0})]
      (let [{:keys [status findings overall-correctness]}
            (codex/review! {:cwd "/w" :base "main" :run-id "r1"})]
        (is (nil? status))
        (is (= 1 (count findings)))
        (is (= "incorrect" overall-correctness))))))

(deftest review!-throws-on-codex-failure
  (let [tmp (str (fs/create-temp-dir))]
    (with-redefs [jj/jj!           (fn [_ & _] {:exit 0 :out "diff --git a/x b/x" :err ""})
                  cstate/run-dir   (fn [_] tmp)
                  codex/run-codex! (fn [_] {:exit 1})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (codex/review! {:cwd "/w" :base "main" :run-id "r1"}))))))

(deftest review!-fails-loud-on-jj-diff-error
  ;; A non-zero `jj diff` (e.g. cwd isn't a jj workspace, or a bad base) must
  ;; NOT read as a clean diff — that would silently pass review for code that
  ;; was never looked at. Fail loud as :review-failed instead.
  (with-redefs [jj/jj! (fn [& _]
                         {:exit 1 :out "" :err "Error: There is no jj repo in \".\""})]
    (let [reason (try (codex/review! {:cwd "/not-a-repo" :base "main" :run-id "r1"})
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
                                     (spit (str (fs/path tmp "review-out.json"))
                                           sample-output)
                                     {:exit 0})]
      (codex/review! {:cwd "/w" :base "main" :run-id "r1"})
      (is (re-find #"src/a\.clj" @captured) "changed files appear in the prompt")
      (is (re-find #"src/b\.clj" @captured)))))

(deftest review!-diffs-from-merge-base-not-tip-of-base
  ;; `jj diff --from main --to @` is a 2-way tree diff: when main has advanced
  ;; since the branch forked, all of main's parallel work shows up as spurious
  ;; deletions (180 files instead of the PR's 29). Review must diff from the
  ;; MERGE BASE (fork point) of @ and the base — matching the PR's "Files
  ;; changed" — and tell codex to explore against that same revision.
  (let [tmp       (str (fs/create-temp-dir))
        diff-from (atom nil)
        captured  (atom nil)]
    (with-redefs [jj/jj!           (fn [_dir & args]
                                     (cond
                                       (= "log" (first args))    ; merge-base resolution
                                       {:exit 0 :out "MERGEBASE123\n" :err ""}
                                       (= "diff" (first args))
                                       (do (reset! diff-from
                                                   (second (drop-while #(not= "--from" %) args)))
                                           {:exit 0 :out "src/a.clj" :err ""})
                                       :else {:exit 0 :out "" :err ""}))
                  cstate/run-dir   (fn [_] tmp)
                  codex/run-codex! (fn [opts]
                                     (reset! captured (:prompt opts))
                                     (spit (str (fs/path tmp "review-out.json"))
                                           sample-output)
                                     {:exit 0})]
      (codex/review! {:cwd "/w" :base "main" :run-id "r1"})
      (is (= "MERGEBASE123" @diff-from)
          "manifest diffs from the resolved merge base, not the raw base bookmark")
      (is (re-find #"MERGEBASE123" @captured)
          "codex prompt points codex at the merge base for its exploration"))))

(deftest parse-output-tolerates-a-finding-with-no-layer
  (let [out (str "{\"findings\":[{\"title\":\"t\",\"body\":\"b\","
                 "\"confidence_score\":0.5,\"priority\":2,"
                 "\"code_location\":{\"absolute_file_path\":\"/w/a.clj\","
                 "\"line_range\":{\"start\":1,\"end\":2}}}],"
                 "\"overall_correctness\":\"correct\"}")
        f   (first (:findings (codex/parse-output out)))]
    (is (nil? (:layer f))
        "the schema requires it, but a stale or hand-made payload must still parse")))
