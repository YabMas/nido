;; src/nido/review/retreat.clj
(ns nido.review.retreat
  "What a superseding record GAVE UP relative to the one it supersedes.

   A record loop puts an agent in front of a judge with one instruction — make
   these findings go away — and the cheapest way to satisfy that is never to
   correct the record. It is to claim less. Drop the load-bearing property the
   code refuted, soften `:revisit` to `:within`, lower the effort, un-mark the
   observation whose `:invisibly-incomplete?` was the only thing stopping it from
   being spun out. Every one of those makes the next round quieter, and none of
   them is a repair.

   The diff review needs no equivalent, because its findings cite code that tests
   run against: a fixer that deletes the behaviour rather than fixing it breaks
   something. A record has no such floor. This namespace is the floor.

   Two deliberate limits:

   1. A retreat is NEVER treated as forbidden. Over-claiming is a real defect and
      the honest repair for it IS to claim less — a baseline that asserted a
      property the code does not have should lose it. So this reports, and the
      caller decides; what the caller must not do is let one pass silently.

   2. Pure, and dependency-free by choice. Whether a retreated record has fallen
      below the point where its own round would still run is a question for the
      predicates that own it (`record/baseline-round-worth-running?` and its
      design sibling), asked by the stage that has both records in hand. Reaching
      for them here would invert that ownership to save a line."
  (:require
   [clojure.string :as str]))

(defn- retreat
  [what detail]
  {:what what :detail detail})

(defn- fewer
  "A count that fell, as one retreat, or nil."
  [what label prev curr]
  (let [p (count prev) c (count curr)]
    (when (< c p)
      (retreat what (str label " " p " → " c)))))

(defn- rank-drop
  "A move DOWN an ordinal, as one retreat, or nil. `order` is cheapest-first, so
   an unknown value on either side yields nil rather than a false alarm — a
   vocabulary this does not know about is not evidence of a retreat."
  [what order prev curr]
  (let [idx (zipmap order (range))
        p   (idx prev) c (idx curr)]
    (when (and p c (< c p))
      (retreat what (str prev " → " curr)))))

;; ── Baseline ────────────────────────────────────────────────────────────────

(def ^:private ref-re
  #"([^\s:]+\.[A-Za-z]+):(\d+)(?:-(\d+))?")

(defn- locations
  "Every PLACE an evidence string points at — [file first-line last-line].

   More than one, because an annotated citation names its file once and then
   refers to further lines in it bare: `foo.clj:732 (ladder inlined) joined to
   :746 at :760-765` points at three places in foo.clj, and reading only the
   first would call the other two lost the moment they stopped being written out
   in full.

   Compared by place rather than by text because the text is not stable and is
   not meant to be: an amender that keeps a citation and explains it turns
   `foo.clj:669` into `foo.clj:669 (period_dialogue_time groups on ...)`, and one
   that sharpens a line into the range around it turns `:202` into `:198-205`.
   Both still point where they pointed. Comparing the strings called eight such
   improvements a loss in one round, which is this detector inverted — a human
   reading eight non-events is a human who will miss the ninth."
  [s]
  (let [s (str s)]
    (when-let [[m file _ _] (re-find ref-re s)]
      (let [tail (subs s (+ (.indexOf s ^String m) (count file)))]
        (distinct
         (for [[_ a b] (re-seq #":(\d+)(?:-(\d+))?" tail)]
           [file (parse-long a) (parse-long (or b a))]))))))

(defn- covers?
  [[file start end] [f s _]]
  (and (= file f) (<= start s end)))

(defn- baseline-evidence
  [b]
  (mapcat locations (mapcat :evidence (:load-bearing b))))

(defn- evidence-lost
  "Places the old record cited that the new one no longer points at. A place is
   still cited when ANY remaining reference in that file covers its line, so a
   widened range keeps everything inside it."
  [prev curr]
  (let [now (vec (baseline-evidence curr))]
    (->> (baseline-evidence prev)
         (remove (fn [loc] (some #(covers? % loc) now)))
         distinct
         (sort-by (juxt first second)))))

(defn baseline-retreats
  "Everything the superseding baseline claims less of than the one before it.

   Health observations are compared by :id, which is stable by schema — that is
   what :id is for, and what lets a dropped observation be named rather than
   counted. Load-bearing properties have no id, so they are compared two ways
   that survive rewording: how many there are, and which file:line references
   nothing cites any more. A property genuinely corrected keeps pointing at the
   code that corrected it; one quietly dropped takes its evidence with it."
  [prev curr]
  (let [pmods (into {} (map (juxt :module identity)) (:modules prev))
        cmods (set (map :module (:modules curr)))
        mods-gone (remove cmods (keys pmods))
        ;; Readings are where the analysis lives, so losing one loses analysis
        ;; whatever the prose still says. There is no id on a claim to track a
        ;; reading through a rewrite, so what is counted is how many readings
        ;; the survey carries and which perspectives it still applies at all —
        ;; both of which survive an amender rewriting every word.
        readings (fn [b] (concat (mapcat :readings (:load-bearing b))
                                 (mapcat :readings (:modules b))))
        plenses (set (map :lens (readings prev)))
        clenses (set (map :lens (readings curr)))
        pids  (into {} (map (juxt :id identity)) (:health prev))
        cids  (into {} (map (juxt :id identity)) (:health curr))
        gone  (remove (set (keys cids)) (keys pids))
        unveiled (for [[id p] pids
                       :let [c (cids id)]
                       :when (and c (:invisibly-incomplete? p)
                                  (not (:invisibly-incomplete? c)))]
                   id)
        ev-gone (evidence-lost prev curr)]
    (vec
     (concat
      (keep identity
            [(fewer :load-bearing-fewer "load-bearing" (:load-bearing prev) (:load-bearing curr))
             (fewer :modules-fewer "modules" (:modules prev) (:modules curr))
             (fewer :read-narrowed "read" (:read prev) (:read curr))])
      ;; Named only when the count also fell. A module's identity is its own
      ;; descriptive name, which the amender rewrites like everything else —
      ;; "codex — the judge launch" became "codex — the read-only judge launch"
      ;; while the decomposition GREW by two, and comparing the strings called
      ;; that a module lost. The count is the signal that survives a rewording;
      ;; the names are the best detail available once it fires, and offering
      ;; them when it has not is asserting a loss the data does not support.
      (when (< (count (:modules curr)) (count (:modules prev)))
        (for [m (sort mods-gone)]
          (retreat :module-dropped
                   (str "module " m " is no longer part of the decomposition"))))
      (keep identity
            [(fewer :readings-fewer "readings" (readings prev) (readings curr))])
      (for [l (sort (remove clenses plenses))]
        (retreat :lens-abandoned
                 (str "nothing is read through " (namespace l) "/" (name l)
                      " any more; a perspective dropped is analysis dropped")))
      (for [id (sort gone)]
        (retreat :health-dropped (str "observation " id " is no longer recorded")))
      ;; The veto is the whole reason :invisibly-incomplete? exists — an
      ;; observation carrying it can never be spun out. Clearing the flag is
      ;; therefore not a survey correction with a side effect; it is the one
      ;; edit that converts a defect into a deferrable.
      (for [id (sort unveiled)]
        (retreat :veto-lifted
                 (str "observation " id " was invisibly-incomplete? and no longer is")))
      (for [[file start end] ev-gone]
        (retreat :evidence-dropped
                 (str file ":" start (when (not= start end) (str "-" end))
                      " is cited by no load-bearing property any more")))))))

;; ── Design ──────────────────────────────────────────────────────────────────

(def effort-order   [:XS :S :M :L :XL])
(def baseline-order [:within :extends :revisit])
(def standing-order [:conforms :extends :challenges])

(defn design-retreats
  "Everything the superseding design commits to less strongly than the one
   before it.

   The three relations are ordinals, cheapest first, and every one of them is a
   claim about how much of the existing system this change is willing to move.
   Sliding down one is exactly how a design stops being refutable while still
   reading like a design.

   Routes are read in one direction only. :fix-here is the conservative
   destination — it says you are doing the work — so moving TO it is not a
   retreat, and moving AWAY from it to any form of not-doing-it is. That this
   also happens to be the direction that makes `design-round-worth-running?`
   fall silent is a separate question, and one for the caller: quieting the
   round by promising MORE work is not something this can call a retreat without
   lying about which way the doctrine points."
  [prev curr]
  (let [proutes (into {} (map (juxt :health-id :to)) (:routes prev))
        croutes (into {} (map (juxt :health-id :to)) (:routes curr))]
    (vec
     (concat
      (keep identity
            [(rank-drop :effort-lowered effort-order (:effort prev) (:effort curr))
             (rank-drop :baseline-relation-softened baseline-order
                        (get-in prev [:baseline :relation]) (get-in curr [:baseline :relation]))
             (rank-drop :standing-softened standing-order
                        (get-in prev [:standing :relation]) (get-in curr [:standing :relation]))
             (fewer :invariants-fewer "invariants" (:invariants prev) (:invariants curr))
             (fewer :rejected-fewer "rejected alternatives" (:rejected prev) (:rejected curr))
             (when (and (seq (:phases prev)) (empty? (:phases curr)))
               (retreat :phases-dropped
                        (str (count (:phases prev)) " phases → none; the change now claims one landing")))])
      (for [[hid to] (sort-by key croutes)
            :let [was (proutes hid)]
            :when (and was (= :fix-here was) (not= :fix-here to))]
        (retreat :route-deferred (str hid ": :fix-here → " to)))))))

;; ── Reporting ───────────────────────────────────────────────────────────────

(defn summary
  "One line per retreat, for the terminal and for the escalation. Empty vector
   renders as nil rather than as an empty heading — 'nothing was weakened' is
   worth saying once, by the caller, not implied by a blank section."
  [retreats]
  (when (seq retreats)
    (str/join "\n" (map #(str "  ! " (name (:what %)) " — " (:detail %)) retreats))))
