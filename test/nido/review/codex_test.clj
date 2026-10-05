(ns nido.review.codex-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.review.claude :as claude]
   [nido.review.codex :as codex]
   [babashka.fs :as fs]))
(deftest codex-argv-feeds-prompt-via-stdin-not-argv
  (let [[opts & args] (codex/codex-argv {:cwd "/w" :schema-path "/s.json"
                                         :out-path "/o.json" :log-path "/l.log"
                                         :prompt "REVIEW INSTRUCTIONS\n\n<huge diff>"})]
    (is (= "REVIEW INSTRUCTIONS\n\n<huge diff>" (:in opts)) "prompt fed via stdin")
    (is (not-any? #(= "REVIEW INSTRUCTIONS\n\n<huge diff>" %) args)
        "prompt is NOT an argv element")
    (is (= "-" (last args)) "positional prompt is '-' (read from stdin)")
    (is (= "/w" (:dir opts)))))

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

;; ── When the reviewer could not be run at all ───────────────────────────────

(def usage-limit-log
  "The tail of a real review log that died on codex's billing quota. The whole
   remedy — where to buy credits, and the hour the window lifts — is in the one
   line, and this is the only place it exists."
  (str "thinking\n"
       "exec jj diff --name-only in /w\n"
       "ERROR: You've hit your usage limit. Visit"
       " https://chatgpt.com/codex/settings/usage to purchase more credits or"
       " try again at Sep 7th, 2026 9:42 AM.\n"))

(deftest a-quota-exhaustion-keeps-the-sentence-and-the-hour-it-lifts
  ;; The failure this exists for. Reported as "codex review failed", the remedy
  ;; and the reset time were stated nowhere a reader would look, so a standing
  ;; quota read as a broken review for as long as it stood.
  (let [u (codex/unavailability usage-limit-log)]
    (is (= :usage-limit (:signal u)))
    (is (= "Sep 7th, 2026 9:42 AM" (:retry-at u))
        "the reset hour decides whether to wait or to buy credits, so it is
         lifted out rather than left inside a sentence")
    (is (str/includes? (:message u) "purchase more credits")
        "the line is kept verbatim — it is the vendor's, and paraphrasing it
         would drop the URL that is half the remedy")))

(deftest a-credential-failure-names-no-hour-to-come-back-at
  ;; :retry-at is absent rather than invented. Nothing about an expired login
  ;; resolves on a clock, and a reader handed a time would wait for it.
  (let [u (codex/unavailability "stream error: unexpected status 401 Unauthorized\n")]
    (is (= :unauthorized (:signal u)))
    (is (not (contains? u :retry-at)))))

(deftest an-ordinary-failure-is-not-classified-as-an-absent-reviewer
  ;; The direction that must not go wrong. A reviewer reported as unavailable is
  ;; a reader told to wait for a quota instead of opening a diff that broke.
  (is (nil? (codex/unavailability
             "exec jj diff in /w\nERROR: unexpected EOF from model stream\n")))
  (is (nil? (codex/unavailability nil))))

(deftest the-line-that-ended-the-run-wins-over-the-ones-before-it
  ;; codex retries internally and narrates each attempt, so an early 429 it
  ;; recovered from is not what stopped the run.
  (let [u (codex/unavailability
           (str "stream error: unexpected status 429 Too Many Requests; retrying\n"
                "stream error: unexpected status 429 Too Many Requests; giving up\n"))]
    (is (str/includes? (:message u) "giving up"))))

;; ── Which reviewer judges ───────────────────────────────────────────────────

(deftest a-run-names-its-reviewer-before-its-project-does
  (is (= :codex (codex/reviewer-for nil nil)) "codex when nobody says")
  (is (= :claude (codex/reviewer-for nil :claude)) "the project's :reviewer")
  (is (= :codex (codex/reviewer-for "codex" :claude)) "the run's own choice wins")
  (is (= :claude (codex/reviewer-for 'claude nil)) "however a command line spells it"))

(deftest a-misspelled-reviewer-is-refused-rather-than-read-as-codex
  (let [e (try (codex/reviewer-for nil :cladue) nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= :unknown-reviewer (:reason (ex-data e))))
    (is (str/includes? (ex-message e) "claude, codex") "and says what it would accept")))

(defn- judged
  "`run-reviewer!` with both reviewers stubbed. Each stub writes `:log` to the
   log path it was handed and answers when `:answers?`. Returns the result and
   who ran, with the log each one wrote to."
  [{:keys [reviewer codex claude]}]
  (let [dir   (str (fs/create-temp-dir))
        calls (atom [])
        stub  (fn [who {:keys [log answers?]}]
                (fn [{:keys [log-path out-path]}]
                  (swap! calls conj [who (str (fs/file-name log-path))])
                  (when log (spit log-path log))
                  (when answers? (spit out-path "{\"findings\": []}"))
                  {:exit (if answers? 0 1)}))]
    (with-redefs [codex/run-codex!   (stub :codex codex)
                  claude/run-claude! (stub :claude claude)]
      (assoc (codex/run-reviewer! {:reviewer reviewer :cwd dir :prompt "p"
                                   :schema-path (str dir "/stack-round-1-schema.json")
                                   :out-path (str dir "/stack-round-1-out.json")
                                   :log-path (str dir "/stack-round-1.log")})
             :calls @calls))))

(deftest a-run-that-names-no-reviewer-is-judged-by-codex
  (let [{:keys [judged-by calls]} (judged {:codex {:answers? true} :claude {:answers? true}})]
    (is (= [[:codex "stack-round-1.log"]] calls))
    (is (= {:reviewer :codex} judged-by))))

(deftest a-codex-out-of-quota-hands-the-review-to-claude
  (let [{:keys [exit log-path judged-by calls]}
        (judged {:codex {:log usage-limit-log} :claude {:answers? true}})]
    (is (= [[:codex "stack-round-1.log"] [:claude "stack-round-1-claude.log"]] calls)
        "beside codex's log, never over it — that log holds why claude ran")
    (is (zero? exit))
    (is (str/ends-with? log-path "stack-round-1-claude.log")
        "a failure is classified from whoever ran last")
    (is (= :claude (:reviewer judged-by)))
    (is (= :codex (:instead-of judged-by)))
    (is (str/includes? (:because judged-by) "hit your usage limit"))))

(deftest only-a-quota-is-stood-in-for
  (testing "a credential is answered by logging in; a stand-in would hide the broken login"
    (let [{:keys [exit calls]}
          (judged {:codex {:log "stream error: unexpected status 401 Unauthorized\n"}
                   :claude {:answers? true}})]
      (is (= [[:codex "stack-round-1.log"]] calls))
      (is (= 1 exit))))
  (testing "a failure nothing classifies is this run breaking, not codex being absent"
    (let [{:keys [calls]} (judged {:codex {:log "ERROR: model stream closed\n"}
                                   :claude {:answers? true}})]
      (is (= [[:codex "stack-round-1.log"]] calls)))))

(deftest a-project-configured-for-claude-never-runs-codex
  (let [{:keys [judged-by calls]}
        (judged {:reviewer :claude
                 :codex {:answers? true}
                 :claude {:log "Claude AI usage limit reached|1790000000\n"}})]
    (is (= [[:claude "stack-round-1.log"]] calls)
        "codex is never asked, whatever claude's log says")
    (is (= {:reviewer :claude} judged-by))))

;; ── A vendor at capacity ────────────────────────────────────────────────────

(def capacity-log
  "The tail codex leaves when its model is shedding load — what ended four record runs and aborted a
   diff round holding two finished layer reviews."
  (str "exec jj diff in /w\n"
       "ERROR: Selected model is at capacity. Please try a different model.\n"
       "ERROR: Selected model is at capacity. Please try a different model.\n"))

(deftest a-model-at-capacity-is-a-reviewer-that-could-not-run
  (let [u (codex/unavailability capacity-log)]
    (is (= :capacity (:signal u))
        "unclassified, an absent reviewer reads as a failed review and a reader opens a diff that is fine")
    (is (= "ERROR: Selected model is at capacity. Please try a different model." (:message u))
        "verbatim, so the report quotes the vendor rather than a sentence of ours")))

(defn- attempted
  "`run-reviewer!` with codex answering each call from `attempts` in turn — `{:log <str>}` fails with
   that log, `{:answers? true}` answers — and claude answering always. Returns the result, who ran,
   and the waits it slept."
  [attempts]
  (let [dir    (str (fs/create-temp-dir))
        left   (atom attempts)
        calls  (atom [])
        slept  (atom [])
        stub   (fn [who]
                 (fn [{:keys [log-path out-path]}]
                   (swap! calls conj who)
                   (let [{:keys [log answers?]} (if (= who :claude) {:answers? true} (first @left))]
                     (when (= who :codex) (swap! left rest))
                     (spit log-path (or log ""))
                     (when answers? (spit out-path "{\"findings\": []}"))
                     {:exit (if answers? 0 1)})))]
    (with-redefs [codex/run-codex!   (stub :codex)
                  claude/run-claude! (stub :claude)
                  codex/sleep!       #(swap! slept conj %)]
      (assoc (codex/run-reviewer! {:cwd dir :prompt "p"
                                   :schema-path (str dir "/r-schema.json")
                                   :out-path (str dir "/r-out.json")
                                   :log-path (str dir "/r.log")})
             :calls @calls :slept @slept))))

(deftest a-capacity-refusal-is-retried-on-the-same-reviewer
  (let [{:keys [exit unavailable judged-by calls slept]}
        (attempted [{:log capacity-log} {:answers? true}])]
    (is (zero? exit) "one blip must not cost the run that a second request a moment later answers")
    (is (nil? unavailable))
    (is (= [:codex :codex] calls) "the same reviewer — capacity is no reason to change judges")
    (is (= {:reviewer :codex} judged-by))
    (is (= 1 (count slept)) "after a backoff, not straight back into the same wall")))

(deftest a-capacity-refusal-that-persists-is-reported-as-the-vendors-line
  (let [{:keys [exit unavailable calls slept]}
        (attempted (repeat 10 {:log capacity-log}))]
    (is (= 1 exit))
    (is (= [:codex :codex :codex] calls)
        "bounded — a vendor still refusing after the retries is a wait for a person to see, not a loop")
    (is (= 2 (count slept)))
    (is (= :capacity (:signal unavailable))
        "so the caller ends on reviewer-unavailable rather than a failure that sends a reader to the diff")
    (is (str/includes? (:message unavailable) "Selected model is at capacity"))))

(deftest only-a-transient-signal-is-retried
  ;; A quota lifts on a date and a credential on a login; re-running after seconds only spends them.
  (let [{:keys [calls slept]}
        (attempted [{:log "stream error: unexpected status 401 Unauthorized\n"} {:answers? true}])]
    (is (= [:codex] calls))
    (is (empty? slept))))

(deftest a-log-s-last-line-is-its-last-non-blank-one
  (let [f (str (fs/create-temp-file))]
    (spit f "thinking\nERROR: model stream closed\n\n   \n")
    (is (= "ERROR: model stream closed" (codex/last-line f))
        "the line a failed layer's row carries — trailing blank lines would leave it saying nothing")
    (is (nil? (codex/last-line (str f ".missing"))) "a reviewer that died before writing leaves nil")
    (is (nil? (codex/last-line nil)))))
