(ns nido.ui.controls-test
  "The dashboard is built from its house controls, and this is what holds it.

   `nido.ui.components.control` is the one writer of a button, input, textarea, select or meter
   element in the dashboard's markup, and its rules are the one place a control is styled. The
   lint and the token test read CSS; neither can see where a control's markup comes from, so an
   unstyled control in a new place was invisible to every gate. This test reads the sources under
   src/nido/ui as Clojure forms and fails on:

   - a control element written as a hiccup literal outside the control set;
   - a :style attribute anywhere in that hiccup, custom properties included — a value the page's
     own script measures from the live layout at runtime (the chat log's --chat-tail) is outside
     the rule, and script is not hiccup, so nothing here reads it;
   - a <style> element carrying anything but the assembled stylesheet;
   - a rule nido emits outside the set — in ui-stylesheet or a block registration — that styles a
     control: one whose subject is a control element, a class the set writes, or a layout hook a
     caller hands a control, and which sets its background, border, padding, font, colour or
     cursor, or styles it on hover or focus;
   - a class written on a control, in a scene or by a caller, that no rule in the assembled
     stylesheet names, and a visible control with no class at all.

   Its reach is the hiccup in nido's Clojure sources. A control made by client script is still
   governed by the rule; this test does not see it. tui.clj is the terminal's, not dashboard
   markup."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [clojure.walk :as walk]
   [edamame.core :as e]
   [hiccup2.core :as h]
   [nido.ui.catalog.scenes :as scenes]
   [nido.ui.components.control :as c]
   [nido.ui.registry :as registry]
   [nido.ui.stylesheet :as stylesheet]))

;; ---------------------------------------------------------------------------
;; Reading the sources

(def ^:private control-ns 'nido.ui.components.control)
(def ^:private control-file "src/nido/ui/components/control.clj")

(def ^:private sources
  (->> (file-seq (io/file "src/nido/ui"))
       (filter #(.isFile ^java.io.File %))
       (map str)
       (filter #(str/ends-with? % ".clj"))
       (remove #(str/ends-with? % "/tui.clj"))
       sort
       vec))

(defn- read-forms
  "Every top-level form of the Clojure source text `s`."
  [s]
  (e/parse-string-all s {:all true
                         :auto-resolve (fn [k] (if (= :current k) 'user (symbol (name k))))
                         :readers (fn [_] identity)}))

(def ^:private control-tag
  #"(button|input|textarea|select|meter)([.#].*)?")

(defn- hiccup-vectors
  "Every vector in `forms` whose head is a keyword — the shape a hiccup element has. A map's
   entries are vectors too, and are not elements."
  [forms]
  (let [acc (volatile! [])]
    (walk/postwalk (fn [x] (when (and (vector? x) (not (map-entry? x)) (keyword? (first x)))
                             (vswap! acc conj x))
                     x)
                   forms)
    @acc))

(defn- attrs-of [v] (let [a (second v)] (when (map? a) a)))

(defn- control-literals [forms]
  (filter #(re-matches control-tag (name (first %))) (hiccup-vectors forms)))

(defn- inline-styles
  "Every map literal in `forms` carrying a style attribute — whether it sits in a hiccup vector's
   attribute position or is merged into one. A :style whose value is a keyword is not markup: an
   action descriptor's emphasis (`{:style :primary}`) is spelled that way."
  [forms]
  (filter (fn [m] (some (fn [k] (and (contains? m k) (not (keyword? (get m k))))) [:style "style"]))
          (filter map? (tree-seq coll? seq forms))))

(defn- foreign-style-elements
  "<style> elements whose content is not the assembled stylesheet."
  [forms]
  (remove #(= '[(h/raw (registry/assembled-stylesheet))] (vec (rest %)))
          (filter #(= :style (first %)) (hiccup-vectors forms))))

(defn- control-alias
  "The alias the ns form of `forms` gives the control set, or nil."
  [forms]
  (let [ns-form (first (filter #(and (seq? %) (= 'ns (first %))) forms))]
    (some (fn [spec] (when (and (vector? spec) (= control-ns (first spec)))
                       (second (drop-while #(not= :as %) spec))))
          (tree-seq coll? seq ns-form))))

(defn- hook-classes
  "The layout hooks callers hand the control set: every literal `:class` in a map within the
   first argument of a call to one of its functions."
  [forms]
  (when-let [alias (control-alias forms)]
    (let [calls (volatile! [])]
      (walk/postwalk (fn [x]
                       (when (and (seq? x) (symbol? (first x)) (= (str alias) (namespace (first x))))
                         (vswap! calls conj (second x)))
                       x)
                     forms)
      (set (for [arg @calls
                 m (filter map? (tree-seq coll? seq arg))
                 :let [cl (:class m)]
                 :when (string? cl)
                 k (str/split (str/trim cl) #"\s+")]
             k)))))

(def ^:private read-sources
  (delay (into {} (for [f sources] [f (read-forms (slurp f))]))))

;; ---------------------------------------------------------------------------
;; Reading CSS

(defn- strip-comments [css] (str/replace css #"(?s)/\*.*?\*/" ""))

(defn- css-rules
  "The style rules of `css` as {:selector :decls}, descending into @media, @supports and @layer
   blocks and skipping @keyframes."
  [css]
  (let [s (strip-comments css)
        n (count s)]
    (loop [i 0 out []]
      (let [open (str/index-of s "{" i)]
        (if (nil? open)
          out
          (let [prelude (str/trim (subs s i open))
                close   (loop [j (inc open) depth 1]
                          (cond (>= j n) n
                                (= \{ (.charAt s j)) (recur (inc j) (inc depth))
                                (= \} (.charAt s j)) (if (= 1 depth) j (recur (inc j) (dec depth)))
                                :else (recur (inc j) depth)))
                body    (subs s (inc open) (min close n))
                ;; a statement like `@layer a, b;` before the block
                prelude (str/trim (last (str/split prelude #";")))]
            (recur (inc close)
                   (cond
                     (str/starts-with? prelude "@keyframes") out
                     (str/starts-with? prelude "@") (into out (css-rules body))
                     :else (conj out {:selector prelude :decls body})))))))))

(defn- subject
  "The last compound of a selector: what the rule styles."
  [sel]
  (last (str/split (str/trim (str/replace sel #"\s*[>+~]\s*" " ")) #"\s+")))

(def ^:private appearance-prop
  #"(?i)\s*(background[\w-]*|border[\w-]*|padding[\w-]*|font[\w-]*|outline[\w-]*|text-decoration[\w-]*|box-shadow|color|cursor|line-height|letter-spacing|text-transform|appearance)\s*:")

(defn- styles-appearance? [{:keys [decls]}]
  (some #(re-matches (re-pattern (str appearance-prop ".*")) %) (str/split decls #";")))

(defn- names-class? [compound cls]
  (boolean (re-find (re-pattern (str "\\." (java.util.regex.Pattern/quote cls) "(?![\\w-])")) compound)))

(defn- control-subject?
  "Whether a selector's subject compound is a control: a control element, or one of `classes`."
  [compound classes]
  (or (re-find #"^(button|input|textarea|select|meter)(?![\w-])" compound)
      (some #(names-class? compound %) classes)))

(defn- restyled-controls
  "Rules of `css` (outside the control set) that style a control by its element or by one of
   `classes`: setting an appearance property, or styling it in a hover or focus state."
  [css classes]
  (for [rule (css-rules css)
        sel  (str/split (:selector rule) #",")
        :let [subj (subject sel)]
        :when (and (control-subject? subj classes)
                   (or (styles-appearance? rule)
                       (re-find #":(hover|focus|active|focus-visible|focus-within)|::placeholder" subj)))]
    (str (str/trim sel) " { " (str/trim (:decls rule)) " }")))

;; ---------------------------------------------------------------------------
;; Reading rendered markup

(defn- rendered-controls
  "Every control element in `html`: its tag, its classes, and whether it is shown."
  [html]
  (for [[tag-html tag] (re-seq #"<(button|input|textarea|select|meter)\b[^>]*>" html)
        :let [cls (second (re-find #"\sclass=\"([^\"]*)\"" tag-html))]]
    {:tag     tag
     :html    tag-html
     :classes (when cls (remove str/blank? (str/split cls #"\s+")))
     :hidden? (boolean (or (re-find #"\shidden(=|\s|>|/)" tag-html)
                           (re-find #"\stype=\"hidden\"" tag-html)))}))

(defn- scene-html []
  (for [{:keys [blocks]} (scenes/groups)
        {:keys [name scenes]} blocks
        {:keys [title render]} scenes]
    [(str name " · " title) (str (h/html (render)))]))

(defn- set-classes
  "Every class a control the set renders in a scene carries."
  []
  (set (for [[_ html] (scene-html) {:keys [classes]} (rendered-controls html) cl classes] cl)))

(defn- unstyled-classes [classes css]
  (remove #(names-class? css %) classes))

;; ---------------------------------------------------------------------------
;; The checks, on the tree

(deftest no-control-is-written-outside-the-set
  (doseq [[f forms] @read-sources
          :when (not= f control-file)]
    (testing f
      (is (empty? (map #(pr-str (vec (take 2 %))) (control-literals forms)))
          "a control is drawn by a function of nido.ui.components.control, never as a literal"))))

(deftest no-markup-carries-an-inline-style
  (doseq [[f forms] @read-sources]
    (testing f
      (is (empty? (map pr-str (inline-styles forms)))
          "spacing and type are classes over the tokens; a computed value is a control's own"))))

(deftest every-chrome-emits-only-the-assembled-stylesheet
  (doseq [[f forms] @read-sources]
    (testing f
      (is (empty? (map pr-str (foreign-style-elements forms)))
          "a <style> under src/nido/ui is the assembled stylesheet, and nothing else"))))

(defn- nido-rules-outside-the-set
  "The CSS nido emits that is not the control set's: ui-stylesheet's own rules and every block
   registration's."
  []
  (let [rules (:rules (stylesheet/stylesheet))]
    (is (str/includes? rules c/css) "the control set's rules reach the stylesheet through ui-stylesheet")
    (str (str/replace rules c/css "")
         "\n" (str/join "\n" (keep :css (:blocks (registry/registry)))))))

(defn- all-hook-classes []
  (into #{} (mapcat (comp hook-classes val)) @read-sources))

(deftest no-rule-outside-the-set-styles-a-control
  (let [classes (into (set-classes) (all-hook-classes))]
    (is (seq classes))
    (is (= [] (vec (restyled-controls (nido-rules-outside-the-set) classes)))
        "a control is styled by the set's own rule; a rule elsewhere may only place it")))

(deftest every-class-on-a-control-is-styled
  (let [css (registry/assembled-stylesheet)]
    (testing "the classes the set writes in its scenes"
      (is (= [] (vec (unstyled-classes (set-classes) css)))))
    (testing "the layout hooks callers hand a control"
      (is (= [] (vec (unstyled-classes (all-hook-classes) css)))))))

(deftest every-visible-control-in-a-scene-has-a-styled-class
  (doseq [[label html] (scene-html)
          {:keys [classes hidden?] tag-html :html} (rendered-controls html)
          :when (not hidden?)]
    (testing label
      (is (seq classes) (str "a visible control with no class: " tag-html)))))

(deftest the-icon-button-cannot-be-drawn-without-its-label
  (is (thrown? clojure.lang.ExceptionInfo (c/icon-button {} "✕")))
  (is (str/includes? (str (h/html (c/icon-button {:label "Close"} "✕"))) "aria-label=\"Close\"")))

(deftest a-button-has-only-the-three-emphases
  (is (thrown? clojure.lang.ExceptionInfo (c/button {:emphasis :large} "x"))))

;; ---------------------------------------------------------------------------
;; The checks catch what they are for
;;
;; Each counterexample the design names, fed to the same functions the checks above run on the
;; tree: a check that could not fail would hold nothing.

(deftest the-checks-fail-on-their-counterexamples
  (let [src "(ns nido.ui.views (:require [nido.ui.components.control :as c]))
             (defn page [] [:div
                            [:input {\"data-bind\" \"x\"}]
                            [:button.btn {:type \"button\"} \"Go\"]
                            [:div {:style \"margin-top:6px\"}]
                            [:span (merge {:id \"w\"} {:style \"--w:62%\"})]
                            (c/button {:style :primary} \"descriptor, not markup\")
                            [:style \"p { color: red }\"]
                            (c/button {:class \"go-hook\"} \"Go\")])"
        forms (read-forms src)]
    (is (= 2 (count (control-literals forms))) "a raw input and a raw button")
    (is (= 2 (count (inline-styles forms))) "a literal margin, and a custom property merged in")
    (is (= 1 (count (foreign-style-elements forms))))
    (is (= #{"go-hook"} (hook-classes forms))))
  (testing "a rule elsewhere restyling a control"
    (is (= 4 (count (restyled-controls
                     ".pickup input { background:var(--surface-sunken); }
                      .chat-compose textarea:focus { outline:none; }
                      .dlg-x { padding:0; }
                      .reply .field { font-size:var(--fs-sm); }
                      .pause-ctl { margin-left:auto; }
                      .chat-toggle svg { width:18px; }"
                     #{"dlg-x" "field" "pause-ctl" "chat-toggle"})))
        "four restyle a control; a margin that places one, and a rule on a child, do not"))
  (testing "a control class no rule names, and a classless visible control"
    (is (= ["qc-words"] (vec (unstyled-classes ["field" "qc-words"] ".field { color:var(--text); }"))))
    (is (= [nil false]
           (let [[ctl] (rendered-controls "<button type=\"button\">Go</button>")]
             [(:classes ctl) (:hidden? ctl)]))))
  (testing "a hidden control needs no class"
    (is (every? :hidden? (rendered-controls "<input type=\"hidden\" /><button hidden=\"hidden\"></button>")))))
