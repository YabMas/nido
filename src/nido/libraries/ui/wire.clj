(ns nido.libraries.ui.wire
  "The Datastar wire: the runtime a page loads, and the server-sent events a stream sends it.

   HIDES which bundle — which pinned version, from where — and how an event is spelled. Named
   once because a block's data- attributes are written against one version's semantics: two
   chromes naming two bundles would be two runtimes, and a tag that floats would change how every
   block behaves without any of them changing."
  (:require [clojure.string :as str]))

(def ^:private bundle
  "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.1/bundles/datastar.js")

(defn ^{:malli/schema [:=> [:cat] :any]}
  script
  "The script element a page loads Datastar with, as hiccup for its <head>."
  []
  [:script {:type "module" :src bundle}])

(defn ^{:malli/schema [:=> [:cat :string [:? :map]] :string]}
  patch-elements-event
  "One event patching `html` into the page. `opts` may carry `:selector` and `:mode` — \"outer\",
   the runtime's default, morphs the elements with the same ids; \"append\" adds them as the last
   children of `:selector`. Multi-line markup becomes one data line per line, as the protocol
   requires."
  ([html] (patch-elements-event html {}))
  ([html {:keys [selector mode]}]
   (str "event: datastar-patch-elements\n"
        (when mode (str "data: mode " mode "\n"))
        (when selector (str "data: selector " selector "\n"))
        (apply str (map #(str "data: elements " % "\n") (str/split-lines html)))
        "\n")))

(defn ^{:malli/schema [:=> [:cat :string] :string]}
  patch-signals-event
  "One event merging `json` — an object of signal names to values, as JSON text — into page
   state."
  [json]
  (str "event: datastar-patch-signals\ndata: signals " json "\n\n"))
