(ns nido.core.feed
  "The change feed: what each commit changed, delivered to subscribers in commit order, keyed by
   commit position.

   A message is {:ws :pos :state :awaiting}: a workstream's whole state at its commit, and the
   asks awaiting the person in it (`nido.core.domain/awaiting-person`). A message replaces what
   its subscriber held for that workstream, so a surface renders committed state and derives
   nothing.

   Each subscriber has a bounded buffer of its own. Publishing never blocks the writer: a
   subscriber whose buffer is full is cut off, and its next take answers {:cut-off true
   :resume-from pos}. It then subscribes again from the last position it rendered, and is sent
   each workstream changed since then, at its latest commit, before anything live."
  (:require [nido.core.domain :as d]
            [nido.core.store :as store])
  (:import [java.util.concurrent ArrayBlockingQueue TimeUnit]))

(defn ^{:malli/schema [:=> [:cat] :map]} create
  "A feed with no subscribers."
  []
  {:subs (atom #{})})

(defn- message [{:keys [ws pos state]}]
  {:ws ws :pos pos :state state :awaiting (vec (d/awaiting-person state))})

(defn ^{:malli/schema [:=> [:cat :map [:sequential :map]] :nil]} publish!
  "Offer `changes` [{:ws :pos :state} ...], in commit order, to every subscriber. Never blocks;
   cuts off a subscriber that has no room for them. Called by the writer after a durable commit."
  [feed changes]
  (doseq [{:keys [^ArrayBlockingQueue queue cut?] :as sub} @(:subs feed)]
    (when-not (every? #(.offer queue (message %)) changes)
      (reset! cut? true)
      (swap! (:subs feed) disj sub)))
  nil)

(defn ^{:malli/schema [:=> [:cat :map :map :int :map] :map]} subscribe!
  "Subscribe to `feed` from position `from` (0 for everything). `reader` is a store handle the
   subscription reads its catch-up from: each workstream changed after `from`, at its latest
   commit. Options: :buffer, the messages it may hold before it is cut off (default 1024)."
  [feed reader from {:keys [buffer] :or {buffer 1024}}]
  (let [sub {:queue (ArrayBlockingQueue. (int (max 1 buffer))) :cut? (atom false)}]
    ;; Registered before the catch-up is read, so nothing committed in between is missed; a live
    ;; message the catch-up already covered is dropped by `take!`.
    (swap! (:subs feed) conj sub)
    (assoc sub
           :feed feed
           :catch-up (atom (mapv message (store/changed-since reader from)))
           :sent (atom {})
           :last-pos (atom from))))

(defn- deliver-message [{:keys [sent last-pos]} m]
  (swap! sent assoc (:ws m) (:pos m))
  (reset! last-pos (max @last-pos (:pos m)))
  m)

(defn ^{:malli/schema [:=> [:cat :map :int] [:maybe :map]]} take!
  "The subscription's next message, waiting up to `timeout-ms`; nil when none arrived in time.
   Once the subscriber has been cut off and has taken everything buffered before that, answers
   {:cut-off true :resume-from pos}, pos being the last position it was given."
  [{:keys [^ArrayBlockingQueue queue cut? catch-up sent last-pos] :as sub} timeout-ms]
  (if-let [m (first @catch-up)]
    (do (swap! catch-up subvec 1) (deliver-message sub m))
    (loop []
      (if-let [m (if @cut? (.poll queue) (.poll queue timeout-ms TimeUnit/MILLISECONDS))]
        (if (<= (:pos m) (get @sent (:ws m) -1))
          (recur)
          (deliver-message sub m))
        (when @cut? {:cut-off true :resume-from @last-pos})))))

(defn ^{:malli/schema [:=> [:cat :map] :nil]} unsubscribe!
  "Stop delivering to `sub`."
  [sub]
  (swap! (:subs (:feed sub)) #(into #{} (remove (fn [s] (identical? (:queue s) (:queue sub)))) %))
  nil)

(defn ^{:malli/schema [:=> [:cat :map :string] :map]} session
  "A read session for one api connection: committed state through a read-only handle of its own
   on the store at `db-path`, and subscriptions to `feed` that catch up through it. See
   `nido.core.api` for its shape."
  [feed db-path]
  (let [reader (store/open-reader db-path)]
    {:state       #(store/state reader %)
     :workstreams #(store/workstreams reader)
     :subscribe   (fn [from]
                    (let [sub (subscribe! feed reader from {})]
                      {:next  #(take! sub %)
                       :close #(unsubscribe! sub)}))
     :close       #(store/close reader)}))
