(ns nido.ui.datastar
  "The one place a dashboard chrome loads Datastar from, and the events a stream sends it.

   HIDES which bundle — which pinned version, from where — and how its server-sent events are
   spelled. A namespace of its own because there
   are two chromes now, a dashboard page and the component catalog, and a pinned CDN URL written
   in each is the same defect as a hue written in each: two copies, one of which will be the one
   nobody updates. The catalog exists to render blocks in the app's real runtime, and it can only
   claim that while there is one runtime to name.

   Beside the visual language rather than inside it, and in no stratum. A block's classes are its
   vocabulary and `nido.ui.stylesheet` is the level they are written in; the Datastar bundle is
   not vocabulary at all — it is the runtime a block's `data-` attributes are interpreted by, and
   nothing above is written in terms of it."
  (:require [clojure.string]))

(def ^:private bundle
  "The pinned bundle. Pinned and not floating: the markup's `data-` attributes are written
   against one version's semantics, and a tag that moves would change how every block behaves
   without any of them changing."
  "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.1/bundles/datastar.js")

(defn ^{:malli/schema [:=> [:cat] :any]}
  datastar-script
  "The script element every dashboard chrome loads Datastar with, as hiccup for a page's <head>."
  []
  [:script {:type "module" :src bundle}])

(defn ^{:malli/schema [:=> [:cat :string [:? :map]] :string]}
  patch-elements-event
  "One SSE event telling the runtime to patch `html` into the page. `opts` may carry `:selector`
   and `:mode` (\"outer\", the default, morphs the elements with the same ids; \"append\" adds the
   elements as the last children of `:selector`). Multi-line markup is split into one data line
   each, as the protocol requires."
  ([html] (patch-elements-event html {}))
  ([html {:keys [selector mode]}]
   (str "event: datastar-patch-elements\n"
        (when mode (str "data: mode " mode "\n"))
        (when selector (str "data: selector " selector "\n"))
        (apply str (map #(str "data: elements " % "\n") (clojure.string/split-lines html)))
        "\n")))

(defn ^{:malli/schema [:=> [:cat :string] :string]}
  patch-signals-event
  "One SSE event telling the runtime to merge `json` — an object of signal names to values, as
   JSON text — into page state."
  [json]
  (str "event: datastar-patch-signals\ndata: signals " json "\n\n"))
