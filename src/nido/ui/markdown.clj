(ns nido.ui.markdown
  "A deliberately tiny markdown -> hiccup renderer for agent-written gate reports.
   Handles only what the reports use: # headings, blank-line paragraphs, -/* bullet
   lists, inline `code` and **bold**. Everything else passes through as plain text.
   Text is returned as plain strings so hiccup2 escapes it (raw HTML in a report is
   neutralized). NOT a general markdown engine — by design (see spec §Rendering)."
  (:require [clojure.string :as str]))

(defn- inline-nodes
  "Split one line into hiccup nodes, rendering `code` and **bold**; plain runs stay
   strings (hiccup2 escapes them)."
  [line]
  (let [pat #"`[^`]*`|\*\*[^*]*\*\*"
        toks (re-seq pat line)
        plains (str/split line pat -1)]
    (loop [ps plains, ts toks, acc []]
      (let [acc (if (seq (first ps)) (conj acc (first ps)) acc)]
        (if (seq ts)
          (let [t (first ts)
                node (cond
                       (str/starts-with? t "`")  [:code (subs t 1 (dec (count t)))]
                       (str/starts-with? t "**") [:strong (subs t 2 (- (count t) 2))]
                       :else t)]
            (recur (rest ps) (rest ts) (conj acc node)))
          acc)))))

(defn- heading-level [line]
  (count (re-find #"^#+" line)))

(defn ^{:malli/schema [:=> [:cat [:maybe :string]] :any]}
  render
  "Markdown string -> a hiccup [:div …] of block nodes. nil/blank -> \"\"."
  [s]
  (if (str/blank? s)
    ""
    (let [lines (str/split-lines s)]
      (loop [ls lines, blocks [], bullets nil]
        (let [flush (fn [bs] (if (seq bs) (conj blocks (into [:ul] bs)) blocks))]
          (if (empty? ls)
            (into [:div.md] (flush bullets))
            (let [line (first ls)]
              (cond
                (str/blank? line)
                (recur (rest ls) (flush bullets) nil)

                (re-find #"^#{1,6}\s+" line)
                (let [lvl (min 6 (max 3 (+ 2 (heading-level line))))   ; #→h3, ##→h4 … (dashboard scale)
                      txt (str/replace line #"^#{1,6}\s+" "")]
                  (recur (rest ls) (conj (flush bullets) (into [(keyword (str "h" lvl))] (inline-nodes txt))) nil))

                (re-find #"^\s*[-*]\s+" line)
                (let [txt (str/replace line #"^\s*[-*]\s+" "")]
                  (recur (rest ls) blocks (conj (or bullets []) (into [:li] (inline-nodes txt)))))

                :else
                (recur (rest ls) (conj (flush bullets) (into [:p] (inline-nodes line))) nil)))))))))
