(ns nido.test.fake-core
  "An in-memory core for tests of what speaks to it: the work plane's requests answered by the
   core's own domain, each workstream's state held in an atom, keyed commands answered once.
   What a surface reads of it is what the feed would have delivered — each workstream's state and
   the asks awaiting the person in it."
  (:require [nido.core.domain :as d]
            [nido.coordinator.work.core :as wcore]))

(defn- answer [{:keys [states outcomes sent]} {:keys [op ws command key] :as req}]
  (case op
    :state   {:state (get @states ws d/empty-state)}
    :command (do (swap! sent conj req)
                 (or (get @outcomes key)
                     (let [[s2 evs r] (d/step (get @states ws d/empty-state) command)
                           o (if r {:refused r} {:events (vec evs)})]
                       (when-not r (swap! states assoc ws s2))
                       (swap! outcomes assoc key o)
                       o)))
    {:error :unknown-op}))

(defn ^{:malli/schema [:=> [:cat fn?] :any]} with-fake-core
  "Call `(f core)` with the work plane speaking to a fresh in-memory core. `core` is
   {:states (atom {ws-key state}) :sent (atom [requests])}."
  [f]
  (let [core {:states (atom {}) :outcomes (atom {}) :sent (atom [])}]
    (with-redefs [wcore/request! #(answer core %)
                  wcore/held     (fn [] (into {} (map (fn [[k s]] [k {:state s :pos 0
                                                                     :awaiting (vec (d/awaiting-person s))}]))
                                              @(:states core)))
                  wcore/mirror   (atom {:pos 0 :ws {} :following? true :connected? true})]
      (f core))))

(defn ^{:malli/schema [:=> [:cat :map :any :string [:sequential :map]] :map]} seed!
  "Commit `commands` on workstream `ws-id` of `project` in fake `core`, as the daemon's own rules
   would; throws on a refusal. Answers the state after them."
  [core project ws-id commands]
  (let [k (wcore/ws-key project ws-id)]
    (doseq [c commands]
      (let [[s2 _ r] (d/step (get @(:states core) k d/empty-state) c)]
        (when r (throw (ex-info (str "seed refused: " (pr-str r)) {:command c})))
        (swap! (:states core) assoc k s2)))
    (get @(:states core) k)))

(def ^{:doc "Commands that leave a workstream with a unit whose conductor turn asks the person."}
  asking
  [{:command :open-unit :unit "u" :project "brian" :goal "g" :session-cwd "/w"}
   {:command :start-turn :turn "c1" :unit nil :spec {:executor :fake :instructions "i"}}
   {:command :record-outcome :turn "c1" :run 1 :outcome :asked
    :call {:id "call-1" :question "Ship it?" :explored "Looked at it." :options ["Yes" "No"]
           :recommendation "Yes"}}
   {:command :raise-operator-ask :ask "a1" :binds #{"c1"}
    :content {:question "Ship it?" :explored "Looked at it."
              :options [{:option "Yes" :consequence "It ships."} "No"] :recommendation "Yes"}}])
