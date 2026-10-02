(ns nido.boot.attention
  "What wants a person right now, and which of it is NEW since the last look.

   Three things want a person: a gate (a workstream parked on a human — the same
   rows the gate inbox shows), a halted coordinator, and a trigger whose breaker
   tripped on its own. Each becomes one keyed notification; `arrivals` is the
   keys that were absent last time. Edge-triggered on purpose: a gate that sits
   for a day is announced once, and announced again only after it has left the
   set — a resumed session that parks again is a new question.

   Pure. The daemon owns the reading, the remembering and the posting."
  (:require
   [clojure.string :as str]))

(def ^:private sound "Glass")

(defn- gate-item [{:keys [project ws-id stage label resume-error]}]
  [[:gate project ws-id]
   {:title    "nido · waiting on you"
    :subtitle (str project (when stage (str " · " (name stage))))
    :message  (str (or (not-empty label) ws-id)
                   (when resume-error " — its last resume failed"))
    :sound    sound}])

(defn- halt-item [{:keys [source note halted-at]}]
  [[:halt halted-at]
   {:title   "nido · coordinator halted"
    :message (or (not-empty note)
                 (str "halted by " (name (or source :unknown)) " — nothing new will run"))
    :sound   sound}])

(defn- breaker-item [{:keys [project trigger info]}]
  [[:breaker (name project) (name trigger)]
   {:title    "nido · trigger paused"
    :subtitle (name project)
    :message  (str (name trigger) " tripped after "
                   (:consecutive-failures info 0) " consecutive failures")
    :sound    sound}])

(defn ^{:malli/schema [:=> [:cat :map] [:map-of :any :map]]}
  items
  "Everything wanting a person, as {key notification}. `:gates` is
   `work/all-gates`, `:halt` is the halt info or nil, `:breakers` is
   `breakers/auto-tripped-triggers` — a trigger a person paused on purpose is
   not news to them, so user-disabled breakers are not passed here."
  [{:keys [gates halt breakers]}]
  (into {}
        (concat (map gate-item gates)
                (when halt [(halt-item halt)])
                (map breaker-item breakers))))

(defn ^{:malli/schema [:=> [:cat [:maybe [:set :any]] :map] [:vector :map]]}
  arrivals
  "The notifications to post, given the keys seen last time (`seen`, nil before
   the first look) and the items now (`current`, as `items` returns them).

   The first look announces nothing individually: what is already waiting when
   the daemon starts is not news, and a restart must not replay every open
   gate. It posts one summary instead, so a person returning to a restarted
   daemon still learns that something is there."
  [seen current]
  (if (nil? seen)
    (if (empty? current)
      []
      (let [n (count current)]
        [{:title   "nido · waiting on you"
          :message (str n (if (= 1 n) " thing needs" " things need") " your attention")
          :sound   sound}]))
    (->> current
         (remove (fn [[k _]] (contains? seen k)))
         (sort-by (comp str key))
         (mapv val))))

(defn ^{:malli/schema [:=> [:cat [:vector :map]] [:vector :map]]}
  coalesce
  "At most `limit` notifications, the overflow folded into one. A burst — a
   review queue landing ten gates at once — posts three banners and a count,
   not ten banners a person dismisses unread."
  ([ns] (coalesce ns 3))
  ([ns limit]
   (if (<= (count ns) (inc limit))
     ns
     (let [overflow (drop limit ns)
           where    (distinct (keep :subtitle overflow))]
       (conj (vec (take limit ns))
             {:title   "nido · waiting on you"
              :message (str "and " (count overflow) " more"
                            (when (seq where) (str " — " (str/join ", " where))))})))))
