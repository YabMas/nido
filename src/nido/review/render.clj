(ns nido.review.render
  "Pure projection of a review report to terminal text. No I/O, no ANSI cursor
   control (that lives in the frontend) — just the rendered block as a string."
  (:require
   [clojure.string :as str])
  (:import
   [java.time Instant Duration]))

(def ^:private spinner-frames
  ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(defn- spinner [^Instant now]
  (nth spinner-frames (mod (quot (.toEpochMilli now) 80) (count spinner-frames))))

(defn- elapsed [started ^Instant now]
  (when started
    (let [secs (max 0 (.getSeconds (Duration/between (Instant/parse started) now)))]
      (format "%02d:%02d" (quot secs 60) (mod secs 60)))))

(defn- glyph [ph now]
  (case (:status ph)
    "running" (spinner now)
    "ok"      "✓"
    "error"   "✗"
    "·"))

(defn- label [ph target]
  (case (:phase ph)
    "review" (str "reviewing"
                  (when (seq (:files target))
                    (str " diff (" (count (:files target)) " files)")))
    ;; "judge" — report.json written before the rename
    "warden" "checking layers"
    ("arbiter" "judge") "arbitrating"
    "fix"    "fixing"
    (:phase ph)))

(defn- summary [ph]
  (case (:phase ph)
    "review" (when (= "ok" (:status ph))
               (let [n       (count (:findings ph))
                     skipped (count (filter #(= "skipped" (:status %)) (:layers ph)))]
                 (str n " finding" (when (not= 1 n) "s")
                      (when (pos? skipped) (str " · " skipped " skipped")))))
    ("arbiter" "judge") (when (:decision ph)
               (str "→ " (:decision ph)
                    (when-let [n (seq (filter #(= "fix" (str (name (or (:disposition %) "")))) 
                                              (:rulings ph)))]
                      (str " (fix " (count n) ")"))))
    "warden" (when (= "ok" (:status ph))
               (let [n (count (:dispositions ph))]
                 (str n " disposition" (when (not= 1 n) "s"))))
    "fix"    (cond
               (seq (:fixes ph))     (str/join ", " (map #(str (or (:layer %) "branch") " "
                                                               (subs (str (:commit %)) 0
                                                                     (min 8 (count (str (:commit %))))))
                                                         (:fixes ph)))
               (= "ok" (:status ph)) "no changes")
    nil))

(defn- phase-line [ph target now]
  (let [running? (= "running" (:status ph))
        bits     (remove str/blank?
                         [(label ph target)
                          (when running? "…")
                          (when running? (elapsed (:started-at ph) now))
                          (summary ph)
                          (:error ph)])]
    (str "   " (glyph ph now) " " (str/join "  " bits))))

(def ^:private layer-col 21)

(defn- pad
  "Left-align into the label column, always leaving at least one space — a long
   label must not run into the text after it."
  [s]
  (let [s (str s)
        n (max 1 (- layer-col (count s)))]
    (str s (apply str (repeat n " ")))))

(defn- layer-glyph
  [status now]
  (case status
    ("skipped" "pending") "·"
    "running"             (spinner now)
    "error"               "✗"
    "✓"))

(defn- layer-text
  [{:keys [status findings]}]
  (case status
    "skipped" "unchanged since it converged"
    "pending" "queued"
    "running" "reviewing …"
    "error"   "failed"
    (str findings " finding" (when (not= 1 findings) "s"))))

(defn- layer-lines
  "One indented line per review target: what it found, that it has not been
   looked at yet, or that it was not looked at at all because its patch had
   already converged.

   A skipped layer is SHOWN rather than omitted. A reader who cannot see that a
   layer was passed over has to take on trust that it was safe to pass over —
   and silent truncation reads as coverage. A pending one is shown for the same
   reason from the other end: the round names every target before it reviews any
   of them, so a run interrupted mid-review still says what it set out to read."
  [ph now]
  (->> (:layers ph)
       (map (fn [{:keys [label stack? status] :as row}]
              (str "       "
                   (layer-glyph status now) " "
                   (pad (if stack? (str label " (composition)") label))
                   (layer-text row))))))

(defn- warden-lines
  [ph]
  (->> (:by-layer ph)
       (map (fn [[label n]]
              (str "       ✓ " (pad label) n " to rule on")))))

(defn- phase-block [ph target now]
  (let [detail (case (:phase ph)
                 ;; Gated on the rows EXISTING, not on the phase being over.
                 ;; They are seeded when targets resolve, and a phase that runs
                 ;; for minutes is precisely the one worth showing detail for. A
                 ;; finished phase has already replaced them with its own rows,
                 ;; so what a COMPLETED run renders is unchanged by this.
                 "review" (when (seq (:layers ph)) (layer-lines ph now))
                 "warden" (when (= "ok" (:status ph)) (warden-lines ph))
                 nil)]
    (str/join "\n" (cons (phase-line ph target now) (remove str/blank? detail)))))

(defn- round-block [round target now]
  (str "  Round " (:round round) "\n"
       (str/join "\n" (map #(phase-block % target now) (:phases round)))))

(defn- header [report]
  (let [t (:target report)
        n (:layers t)]
    (str "  review-loop · " (last (str/split (str (:cwd t)) #"/"))
         " · base " (:base t)
         (when (and n (pos? (long n)))
           (str " · " n " layer" (when (not= 1 n) "s"))))))

(defn frame
  "The live block: header + compact per-round lines. Bounded (no finding bodies)."
  [report ^Instant now]
  (str (header report) "\n"
       (str/join "\n\n" (map #(round-block % (:target report) now) (:rounds report)))))

(defn- finding-line
  "A finding, with where it was seen and what was decided about it. The
   disposition is shown because a finding that is merely absent from the fix
   list reads as forgotten; naming it closed, and by what authority, is the
   difference between a decision and a shrug."
  [f]
  (str "    • [P" (:priority f) "] " (:title f)
       "\n      " (:file f) ":" (:line-start f) "-" (:line-end f)
       (when-let [d (:disposition f)]
         (str "\n      → " (name d)
              (when-let [o (:owner-layer f)] (str " · " o))
              (when-let [a (:authority f)] (str " (" a ")"))
              (when (and (:from-layer f) (:owner-layer f)
                         (not= (:from-layer f) (:owner-layer f)))
                (str " · reported by " (:from-layer f)))))))

(defn final
  "Printed once at the end: the live frame's static form + findings detail +
   terminal status + where the full report lives."
  [report]
  (let [findings (->> (:rounds report)
                      (mapcat :phases)
                      (filter #(= "review" (:phase %)))
                      (mapcat :findings)
                      distinct)]
    (str (frame report (Instant/parse (or (:ended-at report)
                                          (:started-at report))))
         "\n\n  Findings:\n"
         (if (seq findings)
           (str/join "\n" (map finding-line findings))
           "    (none)")
         "\n\n  Status: " (:status report)
         (when-let [s (:summary report)]
           (str "  ·  " (:rounds s) " round(s), " (:findings-fixed s) " fixed"))
         "\n  Full report: <run-dir>/report.json")))

(defn plain-line
  "One line per transition for non-TTY mode, or nil to stay silent."
  [_report {:keys [event iter phase status label findings]}]
  (case event
    ;; Only completions narrate. A start line per target would double the log
    ;; for a fan-out that reports out of order anyway, and plain mode is read
    ;; after the fact.
    :target-moved   (when (= "reviewed" status)
                      (str "round " iter " · " label " ✓ "
                           findings " finding" (when (not= 1 findings) "s")))
    :phase-started  (str "round " iter " · " (name phase) " …")
    :phase-finished (str "round " iter " · " (name phase) " ✓")
    :phase-errored  (str "round " iter " · " (name phase) " ✗")
    :run-finalized  (str "done · " (name status))
    nil))
