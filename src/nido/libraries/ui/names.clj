(ns nido.libraries.ui.names
  "Names scoped to one instance of a block.

   HIDES how a DOM id, a data-scope/data-part pair, a selector and a signal path are derived from
   a block's scope and one instance id. Every name a block's markup carries and every target a
   server patches is read off these, so two instances on one page share nothing and nobody retypes
   a target.

   An instance id is any string the caller chooses — a workstream id, a panel's name. It appears
   verbatim in DOM ids, which allow anything but whitespace. In a signal path the scope and the id
   both have every character a signal name cannot carry replaced by `_`: a `-` there would read
   as a subtraction in a Datastar expression."
  (:require [clojure.string :as str]))

(defn ^{:malli/schema [:=> [:cat :keyword :string :keyword] :string]}
  part-id
  "The DOM id of one part of one instance: <scope>:<id>:<part>."
  [scope id part]
  (str (name scope) ":" id ":" (name part)))

(defn ^{:malli/schema [:=> [:cat :keyword :string :keyword] :string]}
  selector
  "A CSS selector for one part of one instance, its id escaped so the `:` separators and any
   character of the instance id survive a querySelector."
  [scope id part]
  (str "#" (str/replace (part-id scope id part) #"([^A-Za-z0-9_-])" "\\\\$1")))

(defn ^{:malli/schema [:=> [:cat :keyword :string :keyword] :map]}
  part-attrs
  "The attrs one part spreads: its id, data-scope and data-part. A caller merges its own attrs
   over these, never under them."
  [scope id part]
  {:id (part-id scope id part) "data-scope" (name scope) "data-part" (name part)})

(defn- signal-segment [id] (str/replace (str id) #"[^A-Za-z0-9]" "_"))

(defn ^{:malli/schema [:=> [:cat :keyword :string [:or :keyword [:sequential :keyword]]] :string]}
  signal
  "The path of one signal in an instance's namespace, as Datastar names it without the `$`:
   <scope>.<id>.<k…>. `k` is a keyword, or a vector of them for a nested signal."
  [scope id k]
  (str/join "." (concat [(signal-segment (name scope)) (signal-segment id)]
                        (map name (if (keyword? k) [k] k)))))

(defn ^{:malli/schema [:=> [:cat :keyword :string] [:vector :string]]}
  signal-root
  "The path of an instance's whole signal namespace, as the keys a JSON object nests it under."
  [scope id]
  [(signal-segment (name scope)) (signal-segment id)])
