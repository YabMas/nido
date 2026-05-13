(ns nido.coordinator.events
  "Envelope routing: turn an incoming envelope into a fire request.

   Two envelope shapes (spec §Event sources / Two event-flow patterns):
     {:target {:project :p :trigger :t} :payload <m>}   — direct-target
     {:broadcast <event>}                               — broadcast (stage 5+)

   In Stage 1a only :target envelopes occur (from the :manual source).
   Broadcast routing is stubbed so the contract is in place."
  (:require [nido.coordinator.triggers :as triggers]))

(defn- route-direct
  [{:keys [target payload]} triggers-by-project]
  (let [{:keys [project trigger]} target
        ts (get triggers-by-project project)]
    (cond
      (nil? ts)
      {:error :unknown-project :project project}

      :else
      (if-let [t (triggers/find-by-name ts trigger)]
        {:project project :trigger t :payload payload}
        {:error :unknown-trigger :project project :trigger trigger}))))

(defn route
  "Resolve an envelope to a fire request map:
     {:project <kw> :trigger <trigger-map> :payload <m>}
   or an error map:
     {:error <kw> :project ... :trigger ...}

   `triggers-by-project` is `{:project [<trigger>, ...]}`."
  [envelope triggers-by-project]
  (cond
    (:target envelope)    (route-direct envelope triggers-by-project)
    (:broadcast envelope) {:error :broadcast-not-implemented}
    :else                 {:error :unknown-envelope}))
