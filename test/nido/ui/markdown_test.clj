(ns nido.ui.markdown-test
  (:require [clojure.test :refer [deftest is testing]]
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

(deftest renders-italic-without-eating-identifiers
  (let [out (html "an *aside* and an _emphasis_ beside snake_case_name and 2 * 3 * 4")]
    (is (str/includes? out "<em>aside</em>"))
    (is (str/includes? out "<em>emphasis</em>"))
    (is (str/includes? out "snake_case_name") "underscores inside a word are not emphasis")
    (is (str/includes? out "2 * 3 * 4") "a spaced asterisk is not emphasis")))

(deftest marks-nest-and-code-spans-are-literal
  (let [out (html "- **find `x`** — then `**not bold**`")]
    (is (str/includes? out "<li><strong>find <code>x</code></strong> — then <code>**not bold**</code></li>"))))

(deftest a-paragraph-keeps-its-lines-in-one-block
  (is (= "<div class=\"md\"><p>line one<br />line two</p></div>" (html "line one\nline two"))
      "one <p>, its line breaks kept — not a <p> per line, and not folded together"))

(deftest renders-numbered-lists
  (testing "1. and 1) items, a continuation line belonging to the item above"
    (let [out (html "1. first\n   carries on\n2) second")]
      (is (str/includes? out "<ol><li>first<br />carries on</li><li>second</li></ol>"))))
  (testing "a list starting past one keeps its number"
    (is (str/includes? (html "3. third") "<ol start=\"3\">"))))

(deftest an-inline-enumeration-reads-as-a-list
  (testing "(1) … (2) … in one paragraph is a numbered list, split only at the next number"
    (let [out (html "(1) Fix it (see pressure), retiring (standing 1). (2) Amend the record. (3) Decide.")]
      (is (str/includes? out "<ol><li>Fix it (see pressure), retiring (standing 1).</li><li>Amend the record.</li><li>Decide.</li></ol>"))))
  (testing "a lone (1) is just text"
    (is (str/includes? (html "(1) only one") "<p>(1) only one</p>"))))

(deftest renders-fenced-code-literally
  (let [out (html "```clojure\n(<b> **x**)\n  indented\n```\nafter")]
    (is (str/includes? out "<pre><code>(&lt;b&gt; **x**)\n  indented</code></pre>")
        "fence content is escaped and not parsed for marks; its lines and indent are kept")
    (is (str/includes? out "<p>after</p>"))))

(deftest renders-links-only-to-safe-targets
  (let [out (html "[docs](https://example.com/a?b=1) [here](/workstreams/x) [bad](javascript:alert) [rel](//evil.example) [data](data:text/html,hi)")]
    (is (str/includes? out "<a href=\"https://example.com/a?b=1\""))
    (is (str/includes? out "<a href=\"/workstreams/x\""))
    (is (not (str/includes? out "javascript:")) "a script URL never becomes an href")
    (is (not (str/includes? out "href=\"//evil")) "nor does a protocol-relative one")
    (is (not (str/includes? out "href=\"data:")) "nor data:")
    (is (str/includes? out "<span>bad</span>") "the unsafe link keeps its text")))

(deftest links-render-as-their-text-when-turned-off
  (let [out (str (h/html (md/render "See [the **docs**](https://example.com/a) and:\n\n- [here](/x)"
                                    {:links? false})))]
    (is (not (str/includes? out "<a")) "no link becomes an <a>, in a paragraph or a list item")
    (is (not (str/includes? out "href")))
    (is (str/includes? out "<span>the <strong>docs</strong></span>") "the text keeps its own marks")
    (is (str/includes? out "<li><span>here</span></li>"))))

(deftest escapes-html-in-text
  ;; hiccup2 escapes strings by default; markup we emit as raw must stay safe.
  (testing "in a paragraph"
    (let [out (html "a <script>x</script> & b")]
      (is (not (str/includes? out "<script>")) "raw HTML in the report is neutralized")
      (is (str/includes? out "&lt;script&gt;"))))
  (testing "in every construct that carries text"
    (let [out (html (str "# <script>h</script>\n\n- <img src=x onerror=alert(1)>\n1. <b>o</b>\n\n"
                         "**<i>b</i>** *<u>e</u>* `<s>c</s>` [<script>l</script>](https://e.com)\n\n"
                         "```\n<script>f</script>\n```\n\n(1) <script>a</script> (2) <script>b</script>"))]
      (is (not (re-find #"<(script|img|b|i|u|s)[ >]" out)) "no input tag survives as markup")
      (is (str/includes? out "&lt;img src=x onerror=alert(1)&gt;")))))

(deftest blank-input-is-empty
  (is (= "" (html nil)))
  (is (= "" (html ""))))
