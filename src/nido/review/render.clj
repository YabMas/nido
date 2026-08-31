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
    ;; "arbiter"/"judge" — report.json written before the two renames. A
    ;; per-layer "warden" phase from the stage that was folded in also lands
    ;; here; it carries no :decision, so it renders as a bare line.
    ("warden" "arbiter" "judge") "ruling"
    "fix"     "fixing"
    "reshape" "reshaping"
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
    ("warden" "arbiter" "judge") (when (:decision ph)
               (str "→ " (:decision ph)
                    (when-let [n (seq (filter #(= "fix" (str (name (or (:disposition %) "")))) 
                                              (:rulings ph)))]
                      (str " (fix " (count n) ")"))))
    "fix"    (cond
               (seq (:fixes ph))     (str/join ", " (map #(str (or (:layer %) "branch") " "
                                                               (subs (str (:commit %)) 0
                                                                     (min 8 (count (str (:commit %))))))
                                                         (:fixes ph)))
               (= "ok" (:status ph)) "no changes")
    ;; Counted by outcome rather than summed, because "1 recut" says nothing a
    ;; reader can act on and "1 span-has-holes" says the whole of it.
    "reshape" (when (seq (:reshapes ph))
                (->> (frequencies (map :outcome (:reshapes ph)))
                     (sort-by (juxt (comp - val) key))
                     (map (fn [[o n]] (str n " " o)))
                     (str/join ", ")))
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
   instead of widening every row.

   The cap buys legibility, not correctness: `frontend/fit` cuts every line to
   the terminal's width before it is painted, so an over-wide row costs the text
   after it rather than the repaint. Widening every row to suit one slug would
   spend that budget on all of them."
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
   rows this block has. That gap is why the count is threaded through with the
   rule gone — `row-name` reads it to tell a composition from a branch with
   nothing under it. nil falls back to counting the rows, which is what a report
   written before the target carried a layer count has to rely on."
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

(defn- reshape-lines
  "One indented line per recut the reshape stage held, plus its reason.

   The reason is the point. Every one of these findings reached this stage
   because the warden ruled the remedy was the stack's shape and kept it from
   the fixers, so this phase is the only place a recut can be said to have been
   answered — and `span-has-holes`, `already-attempted` and a jj rollback ask
   three different things of a reader who has to finish the job by hand."
  [ph]
  (into []
        (mapcat (fn [{:keys [title outcome because lower upper]}]
                  (cond-> [(str indent "↯ " outcome
                                (when (and lower upper) (str "  ·  " lower " … " upper))
                                "  ·  " title)]
                    because (conj (str indent "  " because)))))
        (:reshapes ph)))

(defn- phase-block [ph target now]
  (let [detail (case (:phase ph)
                 ;; Gated on the rows EXISTING, not on the phase being over.
                 ;; They are seeded when targets resolve, and a phase that runs
                 ;; for minutes is precisely the one worth showing detail for. A
                 ;; finished phase has already replaced them with its own rows,
                 ;; so what a COMPLETED run renders is unchanged by this.
                 "review" (when (seq (:layers ph))
                            (layer-lines ph (:layers target) now))
                 ;; The reason, not just the verdict. A reshape refused for the
                 ;; shape of the span and a reshape jj rolled back are the same
                 ;; word to the loop and different work for whoever reads this.
                 "reshape" (reshape-lines ph)
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

(defn ^{:malli/schema [:=> [:cat :ReviewReport :any] :string]}
  frame
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

(defn- rulings-by-id
  "Every ruling the run made, by finding id, last round winning.

   The report keeps a review phase's findings as the REVIEW stage left them —
   the warden has not run when that phase folds — and keeps what it decided
   separately, under its own phase. So a finding read out of a review phase
   carries no disposition, and the line that renders one never had a value:
   every finding of every round printed as though nothing had been decided about
   it. Joining the two here rather than stamping the fold keeps each phase a
   record of what that stage actually said."
  [report]
  (into {}
        (comp (mapcat :phases)
              (filter #(= "warden" (:phase %)))
              (mapcat :rulings)
              (map (juxt :id identity)))
        (:rounds report)))

(defn- fate-tally
  "One line of what became of the findings, by disposition, commonest first.

   The status says how the run ended; this says what it did. A run can end
   :unresolved holding one finding out of thirty and the difference between that
   and holding thirty is the whole of what a reader wants to know next."
  [findings]
  (let [by (frequencies (keep #(some-> (:disposition %) name) findings))]
    (when (seq by)
      (str "  ·  "
           (->> by
                (sort-by (juxt (comp - val) key))
                (map (fn [[d n]] (str n " " d)))
                (str/join ", "))))))

(defn ^{:malli/schema [:=> [:cat :ReviewReport] :string]}
  final
  "Printed once at the end: the live frame's static form + findings detail +
   terminal status + where the full report lives."
  [report]
  (let [ruled    (rulings-by-id report)
        findings (->> (:rounds report)
                      (mapcat :phases)
                      (filter #(= "review" (:phase %)))
                      (mapcat :findings)
                      distinct
                      (map (fn [f] (merge f (get ruled (:id f))))))]
    (str (frame report (Instant/parse (or (:ended-at report)
                                          (:started-at report))))
         "\n\n  Findings:\n"
         (if (seq findings)
           (str/join "\n" (map finding-line findings))
           "    (none)")
         "\n\n  Status: " (:status report)
         (when-let [s (:summary report)]
           (str "  ·  " (:rounds s) " round(s), " (:findings-fixed s) " fixed"))
         (fate-tally findings)
         "\n  Full report: <run-dir>/report.json")))

(defn ^{:malli/schema [:=> [:cat :any :map] [:maybe :string]]}
  plain-line
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

;; ── A loop over a ledger record ─────────────────────────────────────────────
;;
;; Two stages, no targets, no layers — so this is a much smaller projection than
;; `frame`. What it has to get right is the thing a blocking round got wrong: a
;; judge that reads code for minutes must look like it is working, and an
;; amendment that gave something up must say so where it happened rather than
;; only in the summary.

(def ^:private title-cap 72)

(defn- judge-detail
  "A verdict, or — when there is none — why there is none.

   Never blank. A ✓ with nothing after it reads as `judged, found nothing`, and
   that is the one reading a round which could not run must never invite."
  [ph]
  (if-let [v (:verdict ph)]
    (let [n (count (:findings ph))]
      (str v (when (pos? n)
               (str " · " n " finding" (when (not= 1 n) "s")))))
    (when-let [o (:outcome ph)]
      (str o " — no judgment"))))

(defn- amend-detail
  "What the round did, in the order it did it.

   `amended` is asserted only when a record was actually appended. A round that
   re-surveyed and got no further, or that spent itself objecting, has not
   amended anything, and a line saying it did is the same class of lie as a ✓
   on a judge that never ran."
  [ph]
  (let [n (count (:retreats ph))
        d (count (:disputes ph))
        parts (cond-> []
                (:resurveyed ph) (conj (str "re-surveyed " (:resurveyed ph)))
                (:amended? ph)   (conj "amended")
                (pos? n)         (conj (str n " weakening" (when (not= 1 n) "s")))
                (pos? d)         (conj (str d " objection" (when (not= 1 d) "s"))))]
    (if (seq parts) (str/join " · " parts) "nothing to amend")))

(defn- record-phase-line
  [ph ^Instant now]
  (let [detail (if (= "running" (:status ph))
                 (elapsed (:started-at ph) now)
                 (case (:phase ph)
                   "judge" (judge-detail ph)
                   "amend" (amend-detail ph)
                   nil))]
    (str/trimr (str "    " (glyph ph now) " " (pad (:phase ph) 7) (or detail "")))))

(defn ^{:malli/schema [:=> [:cat :ReviewReport :any :map] :string]}
  record-frame
  "The live block: a title line, then one block per round.

   The title is passed in rather than read off the report, because a record loop
   is identified by what it is judging — a workstream and a record kind — and the
   report carries a diff review's :target instead. Capped for the reason
   `name-col` is: `frontend/fit` would otherwise cut the title at the terminal's
   edge, and a workstream id ending in `…` names nothing."
  [report ^Instant now {:keys [title]}]
  (str "  " (let [t (str title)]
              (if (> (count t) title-cap) (str (subs t 0 title-cap) "…") t))
       "\n"
       (str/join "\n"
                 (for [r (:rounds report)]
                   (str "  round " (:round r) "\n"
                        (str/join "\n" (map #(record-phase-line % now) (:phases r))))))))

(defn- all-phases
  [report]
  (mapcat :phases (:rounds report)))

(defn- all-retreats
  [report]
  (mapcat :retreats (all-phases report)))

(defn- all-disputes
  [report]
  (mapcat :disputes (all-phases report)))

(defn ^{:malli/schema [:=> [:cat :ReviewReport :map] :string]}
  record-final
  "Printed once at the end: the live frame's static form, then what the run gave
   up, then the terminal status.

   The weakenings section prints even when empty, and says so. That a loop
   converged WITHOUT claiming less is the single most important fact about it,
   and a section that simply vanishes when there is nothing to report leaves the
   reader unable to tell 'nothing was given up' from 'nobody looked'."
  [report {:keys [title]}]
  (let [rs       (all-retreats report)
        amended? (boolean (some #(= "amend" (:phase %)) (all-phases report)))]
    (str (record-frame report
                       (Instant/parse (or (:ended-at report) (:started-at report)))
                       {:title title})
         "\n\n  Weakened:\n"
         (cond
           (seq rs)   (str/join "\n" (map #(str "    ! " (name (:what %)) " — " (:detail %)) rs))
           ;; The distinction this section exists to preserve, applied to itself:
           ;; a run that never reached an amendment did not decline to weaken the
           ;; record, it never got as far as touching it.
           amended?   "    (nothing — the record claims everything it claimed at the start)"
           :else      "    (no amendment ran — nothing here was even attempted)")
         ;; Only when there were any. Unlike Weakened — which answers a question
         ;; asked of every run — an objection is an event, and a heading saying
         ;; none occurred would be noise on the overwhelming majority of runs.
         (when-let [ds (seq (all-disputes report))]
           (str "\n\n  Disputed by the amender:\n"
                (str/join "\n"
                          (map #(str "    ? " (:claim %) "\n      because " (:because %)
                                     (when (seq (:evidence %))
                                       (str "\n      cites " (str/join ", " (:evidence %)))))
                               ds))))
         "\n\n  Status: " (:status report)
         (when-let [s (:summary report)]
           (str "  ·  " (:rounds s) " round(s)"))
         "\n  Full report: <run-dir>/report.json")))
