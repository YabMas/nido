(ns nido.test.sessions
  "A unit whose session is ready, as tests need one. The core refuses an :open-unit carrying
   :session-cwd — a unit's session cwd is set only by an :up outcome — so a fixture that wants a
   unit with a working copy opens it and records its session brought up there."
  (:require [nido.core.domain :as d]))

(defn ^{:malli/schema [:=> [:cat :map :string] [:vector :map]]} ready
  "The commands that open unit `open` (an :open-unit) and record its session up at `cwd`."
  [open cwd]
  (let [u (:unit open)]
    [(dissoc open :session-cwd)
     {:command :record-provision-attempt :unit u :attempt (str "ready-" u) :want :up}
     {:command :record-provision-outcome :unit u :attempt (str "ready-" u) :outcome :done :cwd cwd}]))

(defn ^{:malli/schema [:=> [:cat [:sequential :map]] [:vector :map]]} expand
  "`commands` with every :open-unit that names a :session-cwd replaced by `ready` at that cwd."
  [commands]
  (vec (mapcat #(if (and (= :open-unit (:command %)) (:session-cwd %))
                  (ready % (:session-cwd %))
                  [%])
               commands)))

(defn- step! [state c]
  (let [[s2 _ r] (d/step state c)]
    (when r (throw (ex-info (str "fixture refused: " (pr-str r)) {:command c :refused r})))
    s2))

(defn ^{:malli/schema [:=> [:cat :map [:sequential :map]] :map]} settle
  "Apply `commands` to `state`, throwing on a refusal, each :open-unit naming a :session-cwd
   brought up there by `ready` — and, when that left the conductor a :unit-ready item, heard by a
   conductor turn of its own that completes, so the unit reads ready with nothing owed for it, as
   a unit given a working copy used to."
  [state commands]
  (reduce (fn [s c]
            (if (and (= :open-unit (:command c)) (:session-cwd c))
              (let [s2    (reduce step! s (ready c (:session-cwd c)))
                    items (vec (keep (fn [[id i]] (when (and (= :unit-ready (:kind i)) (= (:unit c) (:unit i))) id))
                                     (:inbox s2)))
                    t     (str "ready-hears-" (:unit c))]
                (if (seq items)
                  (reduce step! s2 [{:command :start-turn :turn t :unit nil :hears items
                                     :spec {:executor :fake :instructions "hear the unit come up"}}
                                    {:command :record-outcome :turn t :run 1 :outcome :completed}])
                  s2))
              (step! s c)))
          state commands))
