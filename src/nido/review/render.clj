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
                      (when (pos? skipped)
                        (str " · " skipped " layer" (when (not= 1 skipped) "s")
                             " converged")))))
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

(def ^:private indent "       ")
(def ^:private min-name-col 21)
(def ^:private max-name-col 34)

(defn- name-col
  "How wide the name field is for THIS block: its longest name plus a space.

   Floored so a stack of short slugs still lines its text up with the rest of
   the display, and capped so one long slug pushes only its own line right
   instead of widening every row. The cap is not cosmetic — `frontend/repaint!`
   counts the frame's NEWLINES and moves the cursor up that many rows, so a line
   that wraps is a row the repaint does not know about and the frame walks down
   the screen."
  [names]
  (-> (reduce max 0 (map count names))
      inc
      (max min-name-col)
      (min max-name-col)))

(defn- pad
  "Left-align into the name field, always leaving at least one space — a long
   name must not run into the text after it."
  [s col]
  (let [s (str s)]
    (str s (apply str (repeat (max 1 (- col (count s))) " ")))))

(defn- row-name
  "A row's name, in the shape that says what kind of thing it is.

   A layer is numbered by its place in the stack. The number comes from jj, not
   from where the row happens to sit, so a layer keeps it when a round reviews
   only a subset — a number that renumbered itself between rounds would be worse
   than none.

   The composition pass has no number because it is not a layer, and is named
   for the question it asks rather than for the range it reads. Earlier names
   described the range — every layer at once, the layers in sequence — and both
   pointed at a wider diff, which is the one thing this pass is not. What it
   asks is whether the change was cut into the right pieces and whether those
   pieces hold together.

   The layer count no longer appears in the name, but it still decides it: with
   no layers beneath it there is nothing to compose, the pass is not primed as a
   composition at all, and it is simply the branch."
  [{:keys [index stack? label]} layer-count]
  (cond
    (and stack? (pos? layer-count)) "Composition"
    stack?                          "Whole branch"
    index                           (str "Layer " index " · " label)
    :else                           (str label)))

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
    "skipped" "converged"
    "pending" "queued"
    "running" "reviewing …"
    "error"   "failed"
    (str findings " finding" (when (not= 1 findings) "s"))))

(defn- block-lines
  "Render rows into aligned lines: the numbered layers, then the composition
   pass last.

   A captioned rule used to separate the two, on the reasoning that the
   composition row is not a seventh sibling of six layers. The name carries that
   now — `Composition` next to `Layer 3 · slug` is already a different kind of
   thing — and a section heading over a single row was more furniture than the
   distinction needed.

   `layers` is how wide the STACK is, which is not the same as how many layer
   rows this block has: the warden block holds only the layers that reported
   something, and the composition pass still read all of them. That gap is why
   the count is still threaded through with the rule gone — `row-name` reads it
   to tell a composition from a branch with nothing under it, and a warden block
   holding only the composition row would otherwise count zero layers and call
   it the whole branch. nil falls back to counting the rows, which is what a
   report written before the target carried a layer count has to rely on."
  [rows layers glyph-fn text-fn]
  (let [rows   (vec rows)
        layers (or layers (count (remove :stack? rows)))
        names  (mapv #(row-name % layers) rows)
        col    (name-col names)]
    (into []
          (map-indexed
           (fn [i r]
             (str indent (glyph-fn r) " " (pad (nth names i) col) (text-fn r))))
          rows)))

(defn- layer-lines
  "One indented line per review target: what it found, that it has not been
   looked at yet, or that it was not looked at at all because its patch had
   already converged.

   A converged layer holds its place in the stack and says `converged` where its
   finding count would go. Moving it to the end of the list — which is what the
   split between reviewed and skipped targets used to do — made a layer look as
   though it had left the stack in the round it stopped changing.

   It is SHOWN rather than omitted for the older reason: a reader who cannot see
   that a layer was passed over has to take on trust that it was safe to pass
   over, and silent truncation reads as coverage. A pending one is shown for the
   same reason from the other end: the round names every target before it
   reviews any of them, so a run interrupted mid-review still says what it set
   out to read."
  [ph layers now]
  (block-lines (:layers ph) layers
               #(layer-glyph (:status %) now)
               layer-text))

(defn- warden-rows
  "The warden block's rows, tolerating the label→count map older reports carry."
  [by-layer]
  (if (map? by-layer)
    (mapv (fn [[label n]] {:label label :count n}) by-layer)
    (vec by-layer)))

(defn- warden-lines
  "One line per target that reported something, numbered and ordered like the
   review block above it.

   The composition pass gets a line but never a warden: its findings belong to
   no single layer by construction, so they go straight to the arbiter. Saying
   where they went is the difference between a reader seeing a routing decision
   and a reader seeing a layer that nobody ruled on."
  [ph layers]
  (block-lines (warden-rows (:by-layer ph)) layers
               #(if (:stack? %) "·" "✓")
               #(str (:count %)
                     (if (:stack? %) " to the arbiter" " to rule on"))))

(defn- phase-block [ph target now]
  (let [detail (case (:phase ph)
                 ;; Gated on the rows EXISTING, not on the phase being over.
                 ;; They are seeded when targets resolve, and a phase that runs
                 ;; for minutes is precisely the one worth showing detail for. A
                 ;; finished phase has already replaced them with its own rows,
                 ;; so what a COMPLETED run renders is unchanged by this.
                 "review" (when (seq (:layers ph))
                            (layer-lines ph (:layers target) now))
                 "warden" (when (= "ok" (:status ph))
                            (warden-lines ph (:layers target)))
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
   difference between a decision and a shrug.

   A composition finding also shows its kind and the layers it spans, next to
   the location rather than inside the disposition — it is a property of the
   defect, not of what was decided. It is what makes a `park` legible: parked
   for contradicting an invariant and parked because the CUT is wrong ask very
   different things of whoever reads this, and the word alone cannot tell them
   apart."
  [f]
  (str "    • [P" (:priority f) "] " (:title f)
       "\n      " (:file f) ":" (:line-start f) "-" (:line-end f)
       (when-let [k (:kind f)]
         (str "  ·  " (name k)
              (when (seq (:layers f))
                (str " across " (str/join " + " (:layers f))))))
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
