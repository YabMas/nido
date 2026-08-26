(ns nido.notion.markdown-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.notion.markdown :as md]))

(defn- rt
  ([s] (rt s nil))
  ([s href] [{:plain_text s :href href :type (if href "mention" "text")}]))

(defn- b
  ([type body] (b type body 0))
  ([type body depth] {:block (merge {:type type} {(keyword type) body}) :depth depth}))

(deftest renders-the-ordinary-block-types
  ;; A bullet may interrupt a paragraph in CommonMark, so the list needs no
  ;; blank line above it; a paragraph after a list item does, and gets one.
  (is (= "# Title\n\nsome words\n- one\n- two\n\n- [x] done\n- [ ] todo"
         (md/blocks->markdown
          [(b "heading_1" {:rich_text (rt "Title")})
           (b "paragraph" {:rich_text (rt "some words")})
           (b "bulleted_list_item" {:rich_text (rt "one")})
           (b "bulleted_list_item" {:rich_text (rt "two")})
           (b "paragraph" {:rich_text (rt "")})
           (b "to_do" {:rich_text (rt "done") :checked true})
           (b "to_do" {:rich_text (rt "todo") :checked false})]))))

(deftest nests-list-items-by-depth
  (is (= "- outer\n  - inner"
         (md/blocks->markdown
          [(b "bulleted_list_item" {:rich_text (rt "outer")} 0)
           (b "bulleted_list_item" {:rich_text (rt "inner")} 1)]))))

(deftest a-mention-keeps-its-link
  (testing "a related ticket is a pointer; without the href it is only a name"
    (is (= "see Other ticket (https://notion.so/abc)"
           (md/blocks->markdown
            [(b "paragraph" {:rich_text (into (rt "see ")
                                              (rt "Other ticket" "https://notion.so/abc"))})])))))

(deftest a-table-gets-its-header-separator
  (testing "always — Notion's has_column_header is off even on tables with a
            hand-bolded header row, and a separator-less table is not a table"
    (let [out (md/blocks->markdown
               [(b "table" {:table_width 2 :has_column_header false})
                (b "table_row" {:cells [(rt "Sprache") (rt "Text")]} 1)
                (b "table_row" {:cells [(rt "Deutsch") (rt "Hallo")]} 1)])]
      (is (str/includes? out "| Sprache | Text |"))
      (is (str/includes? out "| --- | --- |"))
      (is (str/includes? out "| Deutsch | Hallo |"))
      (is (= 1 (count (re-seq #"\| --- \|" out))) "one separator, under the first row only"))))

(deftest a-cell-pipe-is-escaped
  (is (str/includes? (md/blocks->markdown
                      [(b "table" {:table_width 1})
                       (b "table_row" {:cells [(rt "a|b")]} 1)])
                     "a\\|b")))

(deftest a-notion-hosted-file-loses-its-signature-and-says-so
  (let [out (md/blocks->markdown
             [(b "image" {:type "file"
                          :file {:url "https://s3/img.png?X-Amz-Signature=deadbeef&more=junk"}
                          :caption (rt "the wizard step")})])]
    (is (str/includes? out "the wizard step"))
    (is (str/includes? out "https://s3/img.png"))
    (is (not (str/includes? out "X-Amz-Signature")) "an hour-long signature is noise in a ledger")
    (is (str/includes? out "link expires"))))

(deftest an-external-image-keeps-its-url-whole
  (let [out (md/blocks->markdown
             [(b "image" {:type "external" :external {:url "https://example.com/a.png"} :caption []})])]
    (is (str/includes? out "https://example.com/a.png"))
    (is (not (str/includes? out "link expires")))))

(deftest an-unknown-block-is-marked-not-dropped
  (testing "a reader who cannot see it believes the page is shorter than it is"
    (is (= "_[unsupported_widget block — not rendered; open the ticket]_"
           (md/blocks->markdown [(b "unsupported_widget" {})])))))

(deftest structural-containers-contribute-nothing-of-their-own
  (is (= "- in a column"
         (md/blocks->markdown
          [(b "column_list" {})
           (b "column" {} 1)
           (b "bulleted_list_item" {:rich_text (rt "in a column")} 2)]))
      "the container adds no line; its child carries the text at its own depth")
  (is (str/blank? (md/blocks->markdown [(b "table_of_contents" {})]))))

(deftest blank-runs-collapse
  (is (= "a\n\nb"
         (md/blocks->markdown
          [(b "paragraph" {:rich_text (rt "a")})
           (b "paragraph" {:rich_text (rt "")})
           (b "paragraph" {:rich_text (rt "")})
           (b "paragraph" {:rich_text (rt "")})
           (b "paragraph" {:rich_text (rt "b")})]))))

(deftest comments-render-dated-and-unattributed
  (is (= "- bumped this to must  _(2026-06-14)_"
         (md/comments->markdown
          [{:rich_text (rt "bumped this to must") :created_time "2026-06-14T10:00:00.000Z"}]))))
