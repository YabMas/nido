(ns nido.ui.markdown-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [nido.ui.markdown :as md]))

(defn- html [s] (str (h/html (md/render s))))

(deftest renders-headings-paragraphs-lists
  (let [out (html "# Verdict\n\nbug — reproduced.\n\n- one\n- two")]
    (is (str/includes? out "<h3") "a # heading renders as an h-element")
    (is (str/includes? out "Verdict"))
    (is (str/includes? out "bug — reproduced."))
    (is (str/includes? out "<ul"))
    (is (str/includes? out "<li>one</li>"))))

(deftest renders-inline-code-and-bold
  (let [out (html "fix `cart/line-total` and **only** that")]
    (is (str/includes? out "<code>cart/line-total</code>"))
    (is (str/includes? out "<strong>only</strong>"))))

(deftest escapes-html-in-text
  ;; hiccup2 escapes strings by default; markup we emit as raw must stay safe.
  (let [out (html "a <script>x</script> & b")]
    (is (not (str/includes? out "<script>")) "raw HTML in the report is neutralized")
    (is (str/includes? out "&lt;script&gt;"))))

(deftest blank-input-is-empty
  (is (= "" (html nil)))
  (is (= "" (html ""))))
