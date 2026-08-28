(ns nido.session.fleet-test
  "The rules that decide what gets offered as a candidate.

   These are the tests the removed idle-watchdog needed and did not have. Its
   failure was never in the primitives — it was that a signal gap read as
   idleness, and nothing pinned down which way each signal is allowed to fail."
  (:require
   [clojure.test :refer [deftest is testing]]
   [nido.session.fleet :as sut]))

(def ^:private hour (* 60 60 1000))

(def ^:private all-answered
  "Every activity probe answered. The state a candidate must be judged in."
  {:transcripts? true :presence? true :sockets? true})

(defn- row
  "A snapshot row. Defaults describe a session nobody is driving and whose
   agent last spoke two days ago — the one shape that should be a candidate."
  [& kvs]
  (merge {:foreign 0 :nrepl? false
          :agent-seen-ms 1 :idle-ms (* 48 hour)}
         (apply hash-map kvs)))

;; ---------------------------------------------------------------------------
;; candidate? — every signal is a veto; only the clock promotes
;; ---------------------------------------------------------------------------

(deftest candidate-requires-a-long-silence
  (is (sut/candidate? (row) all-answered)
      "48h since the agent last spoke, nothing attached — a candidate")

  (testing "the threshold is a day, not the half-hour that got the watchdog removed"
    (is (not (sut/candidate? (row :idle-ms (* 2 hour)) all-answered))
        "two hours idle is an ordinary gap in a session being worked on")
    (is (not (sut/candidate? (row :idle-ms (* 23 hour)) all-answered))
        "still inside the window at 23h")
    (is (sut/candidate? (row :idle-ms (* 25 hour)) all-answered)
        "past a full day it qualifies")))

(deftest process-signals-only-ever-veto
  (testing "a process sitting in the session vetoes however old the transcript is"
    (is (not (sut/candidate? (row :foreign 1 :idle-ms (* 200 hour)) all-answered))
        "an agent or shell in the worktree means hands off"))

  (testing "an attached nREPL vetoes"
    (is (not (sut/candidate? (row :nrepl? true) all-answered))))

  (testing "their absence is not evidence of anything"
    ;; A closed tab is not an abandoned session. Without this, every session
    ;; the user is not looking at RIGHT NOW becomes a candidate.
    (is (not (sut/candidate? (row :foreign 0 :nrepl? false :idle-ms (* 2 hour)) all-answered))
        "no processes attached, but the agent spoke two hours ago — not a candidate")))

(deftest a-blind-signal-yields-no-candidates
  ;; The dangerous direction. If Claude Code moves where it writes transcripts,
  ;; every session reads as never-driven; that must produce silence, not a list
  ;; naming the whole fleet.
  (is (not (sut/candidate? (row) (assoc all-answered :transcripts? false)))
      "unreadable transcript signal disqualifies every row")
  (is (not (sut/candidate? (row :agent-seen-ms nil :idle-ms nil) (assoc all-answered :transcripts? false)))
      "including the rows that look most abandoned"))

(deftest a-blind-veto-probe-yields-no-candidates
  ;; The direction that shipped broken. A blind transcript merely withholds a
  ;; promotion; a blind `lsof` DELETES the veto — `foreign` reads 0 and `nrepl?`
  ;; false for every session at once. Measured against the live fleet with lsof
  ;; stubbed out, a session with 25 agent processes working in it reported
  ;; foreign=0, and this predicate promoted it.
  (testing "process-presence unreadable"
    (is (not (sut/candidate? (row) (assoc all-answered :presence? false)))))
  (testing "socket probe unreadable"
    (is (not (sut/candidate? (row) (assoc all-answered :sockets? false)))))
  (testing "a row that LOOKS idle is exactly the row this must refuse"
    ;; foreign 0 / nrepl? false is what a blind probe reports for everything,
    ;; so the most-abandoned-looking row is the least trustworthy one.
    (is (not (sut/candidate? (row :foreign 0 :nrepl? false :idle-ms (* 200 hour))
                             (assoc all-answered :presence? false))))))

(deftest signals-ok?-reports-snapshot-wide-probe-health
  (is (sut/signals-ok? [{:signals-ok? true} {:signals-ok? true}]))
  (is (not (sut/signals-ok? [{:signals-ok? true} {:signals-ok? false}])))
  (is (sut/signals-ok? []) "an empty fleet is not a broken probe"))

(deftest a-session-no-agent-ever-touched-is-a-candidate
  (is (sut/candidate? (row :agent-seen-ms nil :idle-ms nil) all-answered)
      "signal readable and this session has no transcript at all"))

;; ---------------------------------------------------------------------------
;; totals / over-budget?
;; ---------------------------------------------------------------------------

(def ^:private gb (* 1024 1024 1024))

(deftest typical-is-the-median-of-the-same-project
  (let [rows [{:project "brian" :bytes (* 2 gb)}
              {:project "brian" :bytes (* 4 gb)}
              {:project "brian" :bytes (* 3 gb)}
              {:project "nido"  :bytes (* 30 1024 1024)}]]
    (is (= (* 3 gb) (:typical (sut/totals rows "brian")))
        "median of brian's rows, unaffected by the tiny nido session")
    (is (= 4 (:sessions (sut/totals rows "brian"))))
    (is (= (+ (* 9 gb) (* 30 1024 1024)) (:fleet (sut/totals rows "brian")))
        "fleet total spans every project")))

(deftest typical-is-nil-below-two-samples
  (is (nil? (:typical (sut/totals [{:project "brian" :bytes (* 2 gb)}] "brian")))
      "one sample is not a median")
  (is (nil? (:typical (sut/totals [] "brian")))))

(deftest over-budget-projects-the-incoming-session
  (let [machine (* 48 gb)]
    (is (not (sut/over-budget? {:in-use (* 20 gb) :machine machine :typical (* 3 gb)}))
        "23 of 48 GB projected is well under the line")
    (is (sut/over-budget? {:in-use (* 32 gb) :machine machine :typical (* 3 gb)})
        "35 of 48 GB crosses 70%")
    (testing "the incoming session is what tips it — that is the whole point"
      (is (not (sut/over-budget? {:in-use (* 33 gb) :machine machine :typical nil}))
          "33 GB alone is under")
      (is (sut/over-budget? {:in-use (* 33 gb) :machine machine :typical (* 2 gb)})
          "the same machine with one more session is over"))))

(deftest unreadable-machine-facts-never-warn
  ;; sysctl or vm_stat moving must not manufacture a question in front of work.
  (is (not (sut/over-budget? {:in-use nil :machine (* 48 gb) :typical (* 3 gb)})))
  (is (not (sut/over-budget? {:in-use (* 40 gb) :machine nil :typical (* 3 gb)})))
  (is (not (sut/over-budget? {:in-use (* 40 gb) :machine 0 :typical (* 3 gb)}))))

(deftest candidates-are-ordered-by-what-they-free
  (let [rows [{:candidate? true  :bytes (* 1 gb) :instance-id "small"}
              {:candidate? false :bytes (* 9 gb) :instance-id "busy"}
              {:candidate? true  :bytes (* 4 gb) :instance-id "big"}]]
    (is (= ["big" "small"] (mapv :instance-id (sut/candidates rows)))
        "dearest first, and the non-candidate is absent whatever it holds")))
