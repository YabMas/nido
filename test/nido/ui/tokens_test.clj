(ns nido.ui.tokens-test
  "The dashboard states each colour and font size once, as a :root custom property, and every rule
   reads the token. This holds the sweep: a literal written anywhere else is a second definition the
   next change will miss."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private sources ["src/nido/ui/views.clj" "src/nido/ui/server.clj"])

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

(deftest the-tokens-are-declared-once
  (let [s (slurp "src/nido/ui/views.clj")]
    (is (= 1 (count (re-seq #":root \{" s))))
    (is (= 5 (count (re-seq #"--fs-(xs|sm|md|lg|xl):" s))) "a type scale of five sizes")))
