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
    "judge"  "judging"
    "fix"    "fixing"
    (:phase ph)))

(defn- summary [ph]
  (case (:phase ph)
    "review" (when (= "ok" (:status ph))
               (let [n (count (:findings ph))]
                 (str n " finding" (when (not= 1 n) "s"))))
    "judge"  (when (:decision ph)
               (str "→ " (:decision ph)
                    (when (seq (:fix-findings ph))
                      (str " (fix " (str/join "," (:fix-findings ph)) ")"))))
    "fix"    (cond
               (:commit ph) (str "commit " (subs (:commit ph) 0 (min 8 (count (:commit ph)))))
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

(defn- round-block [round target now]
  (str "  Round " (:round round) "\n"
       (str/join "\n" (map #(phase-line % target now) (:phases round)))))

(defn- header [report]
  (let [t (:target report)]
    (str "  review-loop · " (last (str/split (str (:cwd t)) #"/"))
         " · base " (:base t))))

(defn frame
  "The live block: header + compact per-round lines. Bounded (no finding bodies)."
  [report ^Instant now]
  (str (header report) "\n"
       (str/join "\n\n" (map #(round-block % (:target report) now) (:rounds report)))))

(defn- finding-line [f]
  (str "    • [P" (:priority f) "] " (:title f)
       "\n      " (:file f) ":" (:line-start f) "-" (:line-end f)))

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
  [_report {:keys [event iter phase status]}]
  (case event
    :phase-started  (str "round " iter " · " (name phase) " …")
    :phase-finished (str "round " iter " · " (name phase) " ✓")
    :phase-errored  (str "round " iter " · " (name phase) " ✗")
    :run-finalized  (str "done · " (name status))
    nil))
