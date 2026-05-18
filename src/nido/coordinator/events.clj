(ns nido.coordinator.events
  "Envelope routing: turn an incoming envelope into a vector of fire
   requests. Two envelope shapes (spec §Event sources):
     {:target {:project :p :trigger :t} :payload <m>}   — direct-target
     {:broadcast {:type :<src-type>
                  :source-config <m>
                  :payload <event-payload>}}            — broadcast"
  (:require
   [nido.coordinator.filter :as f]
   [nido.coordinator.triggers :as triggers]))

(defn- route-direct
  [{:keys [target payload]} triggers-by-project]
  (let [{:keys [project trigger]} target
        ts (get triggers-by-project project)]
    (cond
      (nil? ts)
      [{:error :unknown-project :project project}]

      :else
      (if-let [t (triggers/find-by-name ts trigger)]
        [{:project project :trigger t :payload payload :priority (or (:priority t) 0)}]
        [{:error :unknown-trigger :project project :trigger trigger}]))))

(defn- source-config-match?
  "Compare source-configs by value, ignoring :type."
  [a b]
  (= (dissoc a :type) (dissoc b :type)))

(defn- route-broadcast
  [{:keys [broadcast]} triggers-by-project]
  (let [{:keys [type source-config payload]} broadcast]
    (vec
      (for [[project ts] triggers-by-project
            t ts
            :when (= type (-> t :source :type))
            :when (source-config-match? source-config (:source t))
            :when (f/accept? (:filter t) payload)]
        {:project project :trigger t :payload payload :priority (or (:priority t) 0)}))))

(defn route
  "Resolve an envelope to a vector of fire-requests:
     [{:project <kw> :trigger <trigger-map> :payload <m> :priority <int>} ...]
   Or, on routing errors for :target envelopes, a vector of error maps:
     [{:error <kw> :project ... :trigger ...}]
   Broadcast envelopes with no matches return an empty vector."
  [envelope triggers-by-project]
  (cond
    (:target envelope)    (route-direct envelope triggers-by-project)
    (:broadcast envelope) (route-broadcast envelope triggers-by-project)
    :else                 [{:error :unknown-envelope}]))
