(ns nido.core.local
  "A core held in this process, over the same core-domain the daemon decides with, for a process
   that has no daemon to speak to: the bb test suite and the surface bench's measuring child,
   each working in a nido home of its own. It answers the requests core-api answers, so a client cannot tell them apart.

   One core per nido home, keyed by `nido.platform.core/nido-root` as it reads at each request, so
   a test that points the home elsewhere gets an empty core there. Nothing is durable: a process
   that ends takes its core with it. Never installed in a process that serves a person."
  (:require [clojure.string :as str]
            [nido.core.domain :as d]
            [nido.platform.core :as core]))

(defonce ^:private cores
  (atom {}))

(def ^:private empty-core
  {:states {} :entries {} :outcomes {} :pos 0})

(defn- decide-command
  "`c` with the command `req` decided against it, and the outcome."
  [c {:keys [key ws command]}]
  (if-let [o (get-in c [:outcomes key])]
    [c o]
    (let [st (get-in c [:states ws] d/empty-state)
          [st' evs refused] (d/step st command)
          o (if refused {:refused refused} {:events (vec evs)})]
      [(cond-> (assoc-in c [:outcomes key] o)
         (seq evs) (-> (assoc-in [:states ws] st')
                       (update :pos + (count evs))
                       (update :entries into (for [{:keys [event row content]} evs
                                                   :when (= :entry-appended event)]
                                               [[ws (:seq row)] content]))))
       o])))

(defn ^{:malli/schema [:=> [:cat :map] :any]} answer
  "Answer `req` as core-api would, against the core of the current nido home."
  [req]
  (let [root (str (core/nido-root))
        c    #(get @cores root empty-core)]
    (case (:op req)
      :command (let [res (volatile! nil)]
                 (swap! cores update root
                        (fn [x] (let [[x' o] (decide-command (or x empty-core) req)]
                                  (vreset! res o) x')))
                 @res)
      :state       {:state (get-in (c) [:states (:ws req)] d/empty-state)}
      :states      {:states (into {} (filter #(str/starts-with? (key %) (str (:prefix req))))
                                  (:states (c)))}
      :entries     {:entries (into {} (for [n (:seqs req)
                                            :let [t (get-in (c) [:entries [(:ws req) n]])]
                                            :when t]
                                        [n t]))}
      :workstreams {:workstreams (vec (sort (keys (:states (c)))))}
      {:error :unknown-op :op (:op req)})))

(defn ^{:malli/schema [:=> [:cat :string :any [:? [:map-of :int :string]]] :nil]} put!
  "Set workstream `k`'s state in the current home's in-process core to `(f state)`, and keep
   `contents` (seq → content) as its entries' content — history a test plants that no command may
   write any more, the way the ledger once held it. Never part of a served process."
  ([k f] (put! k f {}))
  ([k f contents]
   (swap! cores update (str (core/nido-root))
          (fn [c] (-> (or c empty-core)
                      (update-in [:states k] #(f (or % d/empty-state)))
                      (update :entries into (for [[n t] contents] [[k n] t])))))
   nil))
