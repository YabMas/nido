(ns nido.libraries.ui.style
  "The stylesheet, assembled, and the lint that holds it to its tokens.

   An app's tokens are one map of groups — :colour, :type, :space, :radius, :shadow, :motion —
   each from token name to value. A value is a CSS value string, or a keyword naming another
   token, which is the second tier: a semantic token reads a reference one by name, and emits as
   var(--that). Names are unique across groups; the group says what kind of value it holds.

   HIDES the cascade — which @layer each part sits in and in which order — how the token map
   becomes the one :root block, and how CSS is read to check it. A rule has one of two owners:
   the block whose registration carries it, or the app, which hands over every rule no
   registration carries. They meet here, once, at registry time, so a fragment patched in later
   is already styled."
  (:require [clojure.string :as str]))

(def ^:private layers
  "The cascade, lowest first. A later layer wins whatever the specificity, so an app's own rules
   can always adjust a block without out-specifying it."
  [:tokens :layout :blocks :app])

(def ^:private layout-css
  "The library's own layout floor: the box model every block is written against."
  "*, *::before, *::after { box-sizing: border-box; }")

(defn- token-entries [tokens]
  (for [[_group entries] tokens
        [k v] entries]
    [k v]))

(defn- token-value [v]
  (if (keyword? v) (str "var(--" (name v) ")") (str v)))

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  root-block
  "The token map as the single :root block."
  [tokens]
  (str ":root {\n"
       (apply str (for [[k v] (token-entries tokens)]
                    (str "  --" (name k) ": " (token-value v) ";\n")))
       "}"))

(defn ^{:malli/schema [:=> [:cat [:map [:tokens :map] [:blocks [:sequential :map]]
                                   [:rules {:optional true} [:maybe :string]]]] :string]}
  stylesheet
  "The whole stylesheet for an app: its tokens as the single :root block, the layout floor, every
   block's own rules in registration order, and the app's `:rules` — each in its layer."
  [{:keys [tokens blocks rules]}]
  (letfn [(layer [k body] (str "@layer " (name k) " {\n" body "\n}\n"))]
    (str "@layer " (str/join ", " (map name layers)) ";\n"
         (layer :tokens (root-block tokens))
         (layer :layout layout-css)
         (layer :blocks (str/join "\n" (keep #(some-> (:css %) str/trim not-empty) blocks)))
         (layer :app (or rules "")))))

;; ---------------------------------------------------------------------------
;; The lint

(defn- strip-comments [css] (str/replace css #"(?s)/\*.*?\*/" ""))

(defn- without-root [css]
  (str/replace css #"(?s):root\s*\{[^}]*\}" ""))

(defn- declarations
  "Every `prop: value` declaration in `css`. A selector's pseudo-class is not one: a declaration
   ends at `;` or `}`, a selector at `{`."
  [css]
  (for [[_ prop value] (re-seq #"([a-zA-Z-]+)\s*:\s*([^;{}]+?)\s*(?=[;}])" css)]
    [(str/lower-case prop) value]))

(defn- outside-var [value] (str/replace value #"var\([^)]*\)" ""))

(def ^:private spacing-props
  #{"margin" "margin-top" "margin-right" "margin-bottom" "margin-left" "margin-block"
    "margin-inline" "padding" "padding-top" "padding-right" "padding-bottom" "padding-left"
    "padding-block" "padding-inline" "gap" "row-gap" "column-gap"})

(def ^:private length #"(?<![\w.-])(?:\d*\.)?\d+(?:px|rem|em)\b")

(defn- nonzero-length? [v]
  (some #(not (re-matches #"(?:0*\.)?0+(?:px|rem|em)" %)) (re-seq length v)))

(defn- finding [kind prop value] {:kind kind :prop prop :value value})

(defn- literal-findings [[prop value]]
  (let [bare (outside-var value)]
    (cond-> []
      (re-find #"#[0-9a-fA-F]{3,8}\b|\b(?:rgba?|hsla?)\(" bare)
      (conj (finding :literal-colour prop value))

      (and (= "font-size" prop) (not (str/blank? (str/trim bare))))
      (conj (finding :literal-size prop value))

      (and (spacing-props prop) (nonzero-length? bare))
      (conj (finding :literal-spacing prop value))

      (and (str/includes? prop "radius") (nonzero-length? bare))
      (conj (finding :literal-radius prop value))

      (and (#{"box-shadow" "text-shadow"} prop) (nonzero-length? bare))
      (conj (finding :literal-shadow prop value))

      (and (#{"transition" "transition-duration" "transition-delay" "animation"
              "animation-duration" "animation-delay"} prop)
           (re-find #"(?<![\w-])\d*\.?\d+m?s\b" bare))
      (conj (finding :literal-duration prop value)))))

(defn ^{:malli/schema [:=> [:cat :string :map] [:vector :map]]}
  lint
  "What is wrong with `css` against `tokens`, as findings `{:kind :prop :value}` or
   `{:kind :token}`: a literal colour, font size, spacing, radius, shadow or duration where a
   var(--…) belongs; a var() naming an undeclared token (:undeclared-token); a declared token
   nothing reads (:unread-token). A var() carrying a fallback, var(--x, 0px), reads a property
   the page sets at runtime rather than a token, and is never undeclared. The :root block is where literals belong, and is not checked.
   Empty when nothing is wrong."
  [css tokens]
  (let [css      (strip-comments css)
        body     (without-root css)
        declared (set (map (comp name first) (token-entries tokens)))
        read     (set (concat (map second (re-seq #"var\(--([\w-]+)" css))
                              (keep (fn [[_ v]] (when (keyword? v) (name v))) (token-entries tokens))))]
    (vec (concat
          (mapcat literal-findings (declarations body))
          (for [t (sort (distinct (map second (re-seq #"var\(--([\w-]+)\s*\)" body))))
                :when (not (declared t))]
            {:kind :undeclared-token :token t})
          (for [t (sort declared) :when (not (read t))]
            {:kind :unread-token :token t})))))
