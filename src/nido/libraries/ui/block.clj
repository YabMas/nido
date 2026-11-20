(ns nido.libraries.ui.block
  "Blocks as values.

   A block is a map built once, at registry time:
     :id       a keyword, which is also the scope every name it emits is derived under
     :render   (fn [kit] hiccup) — `kit` holds the instance's :id, its :props, the explicit :ctx
               (now, locale), the caller's :router, and three derivations: (part p) the attrs a
               part spreads, (signal k) a signal path in the instance's namespace, and
               (action k opts?) the Datastar expression for one of the block's declared actions
     :props    the malli schema its props satisfy
     :css      its own rules, assembled into the app's stylesheet rather than sent with a response
     :actions  {k Action} — see nido.libraries.ui.action
     :morph    what the client owns: {:signals {k default}} seeded only when missing, by a hidden
               element just before the root part — or a function of the props answering that
               map, for signals shaped by the props —
               {:preserve {part [attr …]}} kept across a patch, {:ignore #{part}} never morphed
     :scenes   one or more {:title :note :props}, each satisfying :props

   HIDES how a block is checked when it is built, how its morph contract becomes the attributes
   that keep a server patch from resetting what the client owns, and how one instance renders.
   A block's markup reads its kit and nothing else, so the same props, context and router render
   the same markup."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [nido.libraries.ui.action :as action]
            [nido.libraries.ui.names :as names]))

(def ^:private parts [:id :render :props :css :actions :morph :scenes])

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  block
  "`spec` as a block value, or an ex-info naming what is wrong: a missing part, an action that is
   not an `nido.libraries.ui.action/Action`, no scene, or a scene whose props do not satisfy the
   block's schema."
  [spec]
  (let [missing (remove #(contains? spec %) parts)]
    (when (seq missing)
      (throw (ex-info (str "block " (:id spec) " lacks " (vec missing))
                      {:block (:id spec) :missing (vec missing)}))))
  (doseq [[k a] (:actions spec)]
    (when-not (m/validate action/Action a)
      (throw (ex-info (str "block " (:id spec) " declares action " k " malformed")
                      {:block (:id spec) :action k
                       :why (me/humanize (m/explain action/Action a))}))))
  (when (empty? (:scenes spec))
    (throw (ex-info (str "block " (:id spec) " has no scene") {:block (:id spec)})))
  (doseq [{:keys [title props]} (:scenes spec)]
    (when-not (m/validate (:props spec) props)
      (throw (ex-info (str "block " (:id spec) " scene " (pr-str title) " has props its schema refuses")
                      {:block (:id spec) :scene title
                       :why (me/humanize (m/explain (:props spec) props))}))))
  (into {} (select-keys spec (conj parts :doc))))

;; ---------------------------------------------------------------------------
;; The morph contract, as attributes

(defn- element? [x] (and (vector? x) (keyword? (first x))))

(defn- attrs-of [el] (when (map? (second el)) (second el)))

(defn- with-attrs [el f]
  (if-let [a (attrs-of el)]
    (assoc el 1 (f a))
    (into [(first el) (f {})] (rest el))))

(defn- walk-elements [f x]
  (cond
    (element? x) (let [el (f x)
                       a  (attrs-of el)]
                   (into (if a [(first el) a] [(first el)])
                         (map #(walk-elements f %))
                         (if a (drop 2 el) (rest el))))
    (seq? x)     (map #(walk-elements f %) x)
    :else        x))

(defn- seed-json [scope id signals]
  (json/generate-string (assoc-in {} (names/signal-root scope id) signals)))

(defn- part-of
  "The part `el` is of instance `id`, or nil when it is none of that instance's parts."
  [el scope id]
  (let [a (attrs-of el)]
    (when (and a (= (name scope) (get a "data-scope"))
               (= (names/part-id scope id (keyword (get a "data-part"))) (:id a)))
      (keyword (get a "data-part")))))

(defn- seed
  "The element seeding instance `id`'s signals where they are missing: hidden, and placed just
   before the root. Before it, not on it, because Datastar evaluates an element's attributes in
   their order and hiccup writes them sorted, so a seed on the root would come after any
   expression the root reads its own signals with — and an expression reading a signal nested
   below one that does not exist yet throws, and never runs again."
  [scope id signals]
  [:div (assoc (names/part-attrs scope id :signals)
               :hidden true "data-signals__ifmissing" (seed-json scope id signals))])

(defn- seed-before-root [x scope id signals]
  (cond
    (and (element? x) (= :root (part-of x scope id)))
    (list (seed scope id signals) x)

    (element? x)
    (let [a (attrs-of x)]
      (into (if a [(first x) a] [(first x)])
            (map #(seed-before-root % scope id signals))
            (if a (drop 2 x) (rest x))))

    (seq? x) (map #(seed-before-root % scope id signals) x)
    :else    x))

(defn- apply-morph
  "Add, to the parts of instance `id` the contract names, the attributes that keep a patch from
   resetting them, and seed the instance's signals, only where they are missing, before its root
   part (see `seed`)."
  [hiccup {:keys [signals preserve ignore]} scope id props]
  (let [signals (if (fn? signals) (signals props) signals)
        marked  (walk-elements
                 (fn [el]
                   (if-let [part (part-of el scope id)]
                     (with-attrs el
                       #(cond-> %
                          (seq (get preserve part))
                          (assoc "data-preserve-attr" (str/join " " (map name (get preserve part))))
                          (contains? (set ignore) part)
                          (assoc "data-ignore-morph" true)))
                     el))
                 hiccup)]
    (if (seq signals) (seed-before-root marked scope id signals) marked)))

;; ---------------------------------------------------------------------------
;; Rendering one instance

(defn ^{:malli/schema [:=> [:cat :map :string :map :map :any] :map]}
  kit
  "The kit instance `id` of `blk` renders with (see the namespace doc), over `props`, `ctx` and
   `router`, unchecked. For a block that renders one of its parts on its own — a stream patching
   that part in place — so the part carries the names a whole render gives it; `render` is the
   way to render the whole block."
  [blk id props ctx router]
  (let [scope (:id blk)]
    {:id     id
     :props  props
     :ctx    ctx
     :router router
     :part   (fn [p] (names/part-attrs scope id p))
     :signal (fn [k] (names/signal scope id k))
     :action (fn
               ([k] (action/expression (get-in blk [:actions k]) router {:scope scope :id id}))
               ([k opts] (action/expression (get-in blk [:actions k]) router
                                            (merge {:scope scope :id id} opts))))}))

(defn ^{:malli/schema [:=> [:cat :map :string :map :map :any] :any]}
  render
  "Instance `id` of `blk` as hiccup, over `props`, the explicit context `ctx` and the caller's
   `router` — a function of an action name and its params returning a URL. Props the block's
   schema refuses are an ex-info, never a half-rendered block."
  [blk id props ctx router]
  (when-not (m/validate (:props blk) props)
    (throw (ex-info (str "block " (:id blk) " refuses its props")
                    {:block (:id blk) :why (me/humanize (m/explain (:props blk) props))})))
  (apply-morph ((:render blk) (kit blk id props ctx router)) (:morph blk) (:id blk) id props))

(defn ^{:malli/schema [:=> [:cat :map :keyword [:maybe :string] :string]
                       [:or [:map {:closed true} [:ok :map]] [:map {:closed true} [:failure [:= :unparseable]]] [:map {:closed true} [:failure [:= :missing]] [:key :keyword]] [:map {:closed true} [:failure [:= :invalid]] [:key :keyword] [:why :some]]]]}
  decode-action
  "A request body read against one of `blk`'s declared actions for instance `id` — what
   nido.libraries.ui.action/decode answers, the instance's scope supplied."
  [blk k body id]
  (action/decode (get-in blk [:actions k]) body {:scope (:id blk) :id id}))
