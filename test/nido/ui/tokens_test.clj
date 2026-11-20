(ns nido.ui.tokens-test
  "The dashboard states each colour, size, spacing, radius, shadow and duration once, in
   resources/nido/tokens.edn, and every rule reads the token. This holds the sweep: a literal
   written anywhere else is a second definition the next change will miss.

   The sources are every file under src/nido/ui, read off the tree. They were a literal list of two
   paths, which read as the set of places the dashboard's markup is written and was not: a third
   such file was unchecked until somebody remembered to add it, and nothing said so. A namespace
   added to this band is now checked on the day it arrives."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.libraries.ui.style :as style]
   [nido.ui.registry :as registry]
   [nido.ui.stylesheet :as stylesheet]))

(def ^:private sources
  (->> (file-seq (io/file "src/nido/ui"))
       (filter #(.isFile %))
       (map str)
       (filter #(str/ends-with? % ".clj"))
       sort
       vec))

(deftest no-colour-or-size-literal-outside-the-tokens
  (doseq [f sources]
    (testing f
      (let [s (slurp f)]
        (is (empty? (re-seq #"#[0-9a-fA-F]{3}\b|#[0-9a-fA-F]{6}\b" s))
            "every colour is a var(--…) token")
        (is (empty? (re-seq #"\brgba?\(|\bhsla?\(" s)))
        (is (empty? (remove #(str/includes? % "var(--")
                            (re-seq #"font-size\s*:\s*[^;\"}]+" s)))
            "every font size is a token, in any unit")))))

(deftest every-dashboard-source-is-checked
  ;; The list above is derived, so this is what keeps it honest: a band whose markup moved out
  ;; of two files has to be found in more than two.
  (is (<= 7 (count sources)) "every nido.ui source is read")
  (is (every? #(str/starts-with? % "src/nido/ui/") sources))
  (doseq [f ["src/nido/ui/views.clj" "src/nido/ui/server.clj" "src/nido/ui/stylesheet.clj"
             "src/nido/ui/components/state.clj" "src/nido/ui/components/row.clj"
             "src/nido/ui/components/ask.clj" "src/nido/ui/catalog.clj"
             "src/nido/ui/catalog/scenes.clj"]]
    (is (some #{f} sources) (str f " is not being checked"))))

(deftest the-tokens-are-declared-once
  ;; As data, which no namespace under nido.ui restates: the library emits them as the one :root
  ;; block, and a second block anywhere would be the second place a hue is stored.
  (is (empty? (mapcat #(re-seq #":root\s*\{" (slurp %)) sources)) "no source writes a :root block")
  (is (= 1 (count (re-seq #":root \{" (registry/assembled-stylesheet))))
      "the assembled stylesheet carries exactly one")
  (let [tokens (edn/read-string (slurp (io/resource "nido/tokens.edn")))]
    (is (= tokens (:tokens (stylesheet/stylesheet))) "the stylesheet hands over the file as it is")
    (is (= #{:fs-xs :fs-sm :fs-md :fs-lg}
           (set (filter #(re-matches #"fs-(xs|sm|md|lg)" (name %)) (keys (:type tokens)))))
        "a type scale of four sizes")))

(deftest the-assembled-stylesheet-reads-only-its-tokens
  ;; The library's lint over what every chrome emits: no literal colour, size, spacing, radius,
  ;; shadow or duration outside the :root block, no var() naming an undeclared token, and no
  ;; token nothing reads.
  (is (= [] (style/lint (registry/assembled-stylesheet) (:tokens (stylesheet/stylesheet))))))
