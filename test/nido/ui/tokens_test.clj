(ns nido.ui.tokens-test
  "The dashboard states each colour and font size once, as a :root custom property, and every rule
   reads the token. This holds the sweep: a literal written anywhere else is a second definition the
   next change will miss.

   The sources are every file under src/nido/ui, read off the tree. They were a literal list of two
   paths, which read as the set of places the dashboard's markup is written and was not: a third
   such file was unchecked until somebody remembered to add it, and nothing said so. A namespace
   added to this band is now checked on the day it arrives."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private sources
  (->> (file-seq (io/file "src/nido/ui"))
       (filter #(.isFile %))
       (map str)
       (filter #(str/ends-with? % ".clj"))
       sort
       vec))

(defn- outside-root
  "`s` with the :root token block cut out — the one place a literal belongs."
  [s]
  (if-let [i (str/index-of s ":root {")]
    (let [j (str/index-of s "\n        }" i)]
      (str (subs s 0 i) (subs s (+ j 10))))
    s))

(deftest no-colour-or-size-literal-outside-the-tokens
  (doseq [f sources]
    (testing f
      (let [s (outside-root (slurp f))]
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
             "src/nido/ui/components/ask.clj"]]
    (is (some #{f} sources) (str f " is not being checked"))))

(deftest the-tokens-are-declared-once
  ;; In nido.ui.stylesheet, which is the whole reason that namespace exists: two chromes emit
  ;; the block, and a second copy of it would be the second place a hue is stored.
  (let [s (slurp "src/nido/ui/stylesheet.clj")]
    (is (= 1 (count (re-seq #":root \{" s))))
    (is (empty? (mapcat #(re-seq #":root \{" (slurp %))
                        (remove #{"src/nido/ui/stylesheet.clj"} sources)))
        "and nowhere else")
    (is (= 4 (count (re-seq #"--fs-(xs|sm|md|lg):" s))) "a type scale of four sizes")))
