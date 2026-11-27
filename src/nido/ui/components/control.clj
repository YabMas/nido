(ns nido.ui.components.control
  "The dashboard's house controls: one function per kind of control, and the one rule that
   styles each.

   HIDES how each kind of control is drawn — its element, its class, its variants and the rule
   that styles it. The kinds are the button (default, primary, danger), the quiet button and its
   icon-only form, the link drawn as a button, the selectable choice card, the sunken text input,
   textarea and select at one size, the labelled field, the form section with its heading, the
   meter that shows a fraction, and the hidden and file inputs.

   This is the only namespace that writes a button, input, textarea, select or meter element in
   the dashboard's markup; pages and blocks call these functions and own only the layout of the
   regions they compose. Each function takes the caller's own attributes — data-bind,
   data-on:*, placeholder, title, aria-* — and passes them through. A caller's `:class` is a
   layout hook, appended after the control's own: a rule elsewhere may place a control by it
   (margin, flex, width, position) but never restyle it, which `nido.ui.controls-test` holds.

   The rules are `css`, handed on by `nido.ui.stylesheet` beside its own, so they reach the one
   assembled stylesheet like every other rule nido emits."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Attributes

(defn- classes
  "The control's own classes, then the caller's layout hook."
  [own hook]
  (str/join " " (remove str/blank? (cons own (if (sequential? hook) hook [hook])))))

(defn- with-class
  [attrs own]
  (assoc attrs :class (classes own (:class attrs))))

(def ^:private emphasis-class
  {:default "btn" :primary "btn btn-primary" :danger "btn btn-danger"})

(defn- emphasis-of
  "A button's emphasis, one of `emphasis-class`'s keys. Anything else is a caller's mistake,
   refused rather than drawn as the default."
  [e]
  (let [e (or e :default)]
    (when-not (contains? emphasis-class e)
      (throw (ex-info (str "a button's emphasis is one of :default, :primary or :danger, not " e)
                      {:emphasis e})))
    e))

;; ---------------------------------------------------------------------------
;; Buttons

(defn ^{:malli/schema [:=> [:cat :map [:* :any]] :any]}
  button
  "A button over the caller's `attrs` and its `content`. `:emphasis` is :default, :primary — the
   one action a decision recommends — or :danger, an action that destroys or stops something."
  [{:keys [emphasis] :as attrs} & content]
  (into [:button (merge {:type "button"}
                        (with-class (dissoc attrs :emphasis) (emphasis-class (emphasis-of emphasis))))]
        content))

(defn ^{:malli/schema [:=> [:cat :map [:* :any]] :any]}
  link-button
  "A link drawn as a button: navigation that sits among actions. `attrs` carry its `:href`, and
   `:emphasis` as `button` takes it."
  [{:keys [emphasis] :as attrs} & content]
  (into [:a (with-class (dissoc attrs :emphasis) (emphasis-class (emphasis-of emphasis)))]
        content))

(defn ^{:malli/schema [:=> [:cat :map [:* :any]] :any]}
  quiet-button
  "A borderless button: a control that sits in chrome rather than among a decision's actions —
   a toggle, a tool, a row that opens. On while its `aria-pressed` is true (or `aria-current`
   names it), which the caller sets or binds."
  [attrs & content]
  (into [:button (merge {:type "button"} (with-class attrs "qbtn"))] content))

(defn ^{:malli/schema [:=> [:cat :map [:* :any]] :any]}
  icon-button
  "A quiet button showing only an icon or a glyph. `:label` is required: it is the button's
   accessible name, and its title unless the caller gives one. An icon-only button with no
   label is refused, since nothing would say what it does."
  [{:keys [label] :as attrs} & content]
  (when (str/blank? (str label))
    (throw (ex-info "an icon button needs a :label, its accessible name" {:attrs attrs})))
  (into [:button (merge {:type "button" :title label}
                        (with-class (dissoc attrs :label) "qbtn qbtn-icon")
                        {:aria-label label})]
        content))

(defn ^{:malli/schema [:=> [:cat :map [:* :any]] :any]}
  choice
  "A selectable option card. `:primary?` draws it as the recommended answer; `:chosen`, a page
   expression, marks it chosen while true; `:static?` draws it with no control at all — an
   option that cannot be acted on now. Its `content` is the option's own words."
  [{:keys [primary? chosen static?] :as attrs} & content]
  (let [attrs (dissoc attrs :primary? :chosen :static?)
        own   (str "choice" (when primary? " primary") (when static? " static"))]
    (if static?
      (into [:div (with-class (dissoc attrs "data-on:click") own)] content)
      (into [:button (cond-> (merge {:type "button"} (with-class attrs own))
                       chosen (assoc "data-class:chosen" chosen "data-attr:aria-pressed" chosen))]
            content))))

;; ---------------------------------------------------------------------------
;; Fields

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  text-input
  "A one-line text field, in the house sunken look at the house size. Its width is the layout's."
  [attrs]
  [:input (merge {:type "text"} (with-class attrs "field"))])

(defn ^{:malli/schema [:=> [:cat :map [:* :any]] :any]}
  textarea
  "A multi-line text field, in the same look and size as `text-input`; `content` is its
   initial text."
  [attrs & content]
  (into [:textarea (with-class attrs "field")] content))

(defn ^{:malli/schema [:=> [:cat :map [:sequential :any]] :any]}
  select
  "A choice among fixed values, in the same look and size as `text-input`. Each of `options` is
   a value, or `[value label]`."
  [attrs options]
  (into [:select (with-class attrs "field")]
        (for [o options
              :let [[v l] (if (sequential? o) o [o o])]]
          [:option {:value (str v)} (str l)])))

(defn ^{:malli/schema [:=> [:cat :any :any] :any]}
  labelled
  "A field with its label above it; clicking the label focuses the field."
  [label control]
  [:label.labelled [:span.labelled-l label] control])

(defn ^{:malli/schema [:=> [:cat :any [:* :any]] :any]}
  form-section
  "A small form: its heading, then its fields and actions in a column."
  [heading & content]
  (into [:section.form-section [:h3.form-section-h heading]] content))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  meter
  "A fraction, as a bar: `:value` between 0 and 1, `:over?` when it passes what it is measured
   against, which draws it in the warning colour. The value is the element's own, so the bar
   needs no style computed into the markup."
  [{:keys [value over?] :as attrs}]
  (let [v (max 0.0 (min 1.0 (double (or value 0))))]
    [:meter (merge (with-class (dissoc attrs :value :over?) (str "meter" (when over? " over")))
                   {:min "0" :max "1" :value (format "%.3f" v)})]))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  hidden-input
  "A value the page's script writes and a bound signal reads: never shown."
  [attrs]
  [:input (merge attrs {:type "hidden"})])

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  file-input
  "The file picker a visible control opens: hidden, out of the tab order, and named to no
   assistive reader, since the control that opens it is what the person uses."
  [attrs]
  [:input (merge attrs {:type "file" :hidden true :aria-hidden "true" :tabindex "-1"})])

;; ---------------------------------------------------------------------------
;; The rules

(def ^{:malli/schema :string}
  css
  "The one rule set that styles every control above. Handed to the library by
   `nido.ui.stylesheet`, beside its own rules."
  "        .btn { background:var(--control-bg); color:var(--text); border:1px solid var(--control-bg-hover);
               padding:var(--space-4) var(--space-10); border-radius:var(--radius-3); font-size:var(--fs-xs);
               cursor:pointer; font-family:inherit; line-height:1.5; }
        .btn:hover { background:var(--control-bg-hover); text-decoration:none; }
        .btn-primary { background:var(--accent-bg); border-color:var(--accent-border); color:var(--accent-strong); }
        .btn-primary:hover { background:var(--accent-border); }
        .btn-danger { background:var(--failed-bg); border-color:var(--failed-border); color:var(--failed-strong); }
        .btn-danger:hover { background:var(--failed-border); }
        a.btn { display:inline-block; }
        .btn:disabled, .qbtn:disabled { opacity:.5; cursor:default; }
        .qbtn { display:inline-flex; align-items:center; gap:var(--space-5); background:none;
                border:1px solid transparent; border-radius:var(--radius-4); color:var(--text-muted);
                font:inherit; font-size:var(--fs-xs); padding:var(--space-3) var(--space-7); cursor:pointer; }
        .qbtn:hover { color:var(--text-strong); background:var(--surface-hover); }
        .qbtn[aria-pressed=\"true\"], .qbtn[aria-current=\"step\"] { background:var(--accent-bg);
                border-color:var(--accent-border); color:var(--accent-strong); }
        .qbtn-icon { justify-content:center; width:26px; height:26px; padding:0; font-size:var(--fs-sm); line-height:1; }
        .qbtn-icon svg { width:15px; height:15px; }
        .choice { display:block; width:100%; max-width:var(--measure); text-align:left; font:inherit;
                  color:var(--text); background:var(--surface); border:1px solid var(--border);
                  border-radius:var(--radius-6); padding:var(--space-9) var(--space-12); cursor:pointer; }
        .choice:hover { background:var(--surface-hover); }
        .choice.primary { background:var(--accent-bg); border-color:var(--accent-border); }
        .choice.chosen { background:var(--recommended-bg); border-color:var(--recommended); }
        .choice.static, .choice.static:hover { cursor:default; background:var(--surface); }
        .field { display:block; width:100%; background:var(--surface-sunken); color:var(--text-strong);
                 border:1px solid var(--border); border-radius:var(--radius-4); font:inherit;
                 font-size:var(--fs-sm); padding:var(--space-6) var(--space-9); }
        .field::placeholder { color:var(--text-muted); }
        .field:focus { outline:none; border-color:var(--accent-border); }
        textarea.field { resize:vertical; min-height:62px; }
        .labelled { display:flex; flex-direction:column; gap:var(--space-4); }
        .labelled-l { color:var(--text-muted); font-size:var(--fs-xs); }
        .form-section { display:flex; flex-direction:column; gap:var(--space-6); }
        .form-section-h { margin:0; color:var(--text-strong); font-size:var(--fs-xs); font-weight:normal;
                          text-transform:uppercase; letter-spacing:0.04em; }
        .meter { display:block; width:100%; height:4px; border:none; border-radius:var(--radius-2);
                 background:var(--border); appearance:none; overflow:hidden; }
        .meter::-webkit-meter-bar { background:var(--border); border:none; border-radius:var(--radius-2); height:4px; }
        .meter::-webkit-meter-optimum-value, .meter::-webkit-meter-suboptimum-value,
        .meter::-webkit-meter-even-less-good-value { background:var(--ok); }
        .meter::-moz-meter-bar { background:var(--ok); }
        .meter.over::-webkit-meter-optimum-value, .meter.over::-webkit-meter-suboptimum-value,
        .meter.over::-webkit-meter-even-less-good-value { background:var(--warning); }
        .meter.over::-moz-meter-bar { background:var(--warning); }
")
