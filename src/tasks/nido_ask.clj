(ns tasks.nido-ask
  "bb-task entrypoint: the guidance an author of a question for the person is briefed with.

   For an author nido does not brief itself — a seat about to halt on a blocker, a skill about to
   park a report — so every question for the person is written from the same guidance the
   conductor and the design round are given."
  (:require
   [clojure.string :as str]
   [nido.coordinator.report.ask :as ask]
   [nido.platform.task-args :as task-args]))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  guidance-cmd
  "Print the asking guidance for `:kind` (default the generic guidance alone); an unknown kind
   prints the registered ones and exits 1."
  [& args]
  (let [[_ {:keys [kind]}] (task-args/split-args args)
        k     (keyword (str/replace (str (or kind "generic")) #"^:" ""))
        known (conj (set (ask/kinds)) :escalation)]
    (if (known k)
      (println (ask/guidance k))
      (do (println (str "No question kind " k " — one of: "
                        (str/join ", " (map name (sort known)))))
          (System/exit 1)))))
