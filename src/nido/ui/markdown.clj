(ns nido.ui.markdown
  "The dashboard's one Markdown renderer: agent-written prose -> hiccup. Every dashboard field
   that shows agent text as Markdown calls `render`, and nothing else on the dashboard turns
   Markdown marks into markup.

   A small, hand-written subset rather than a general engine — what agents write in gate and
   ledger text, no more:
   blocks  — # headings, paragraphs (lines kept apart by <br>, not folded together), -/*/+
             bullets, 1. / 1) numbered lists, ``` fenced code, and a paragraph enumerating
             (1) … (2) … inline, which reads as the numbered list it is;
   inline  — `code`, **bold**, *italic* / _italic_, [text](url).
   Anything else passes through as text.

   Agent text is untrusted. Every piece of it reaches the hiccup as a string child, which
   hiccup2 escapes, so raw HTML in the input renders as text and never as markup. A link keeps
   its href only for http(s), mailto, or a same-site path/fragment; any other scheme
   (javascript:, data:, …) renders as its text alone.

   `{:links? false}` renders every link as its text alone, for a host that is itself interactive
   — an option rendered inside its <button> — where an <a> would be invalid HTML and a click on
   it ambiguous."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Inline

(def ^:private inline-pattern
  ;; Alternatives in priority order: code spans first, so nothing inside one is read as a mark.
  #"`([^`]+)`|\[([^\]]+)\]\(([^)\s]+)\)|\*\*(.+?)\*\*|\*([^*\s](?:[^*]*?[^*\s])?)\*|(?<![\p{L}\p{N}_])_([^_\s](?:[^_]*?[^_\s])?)_(?![\p{L}\p{N}_])")

(defn- safe-href
  "`url` when following it cannot run anything — http(s), mailto, or a same-site path or
   fragment — else nil."
  [url]
  (let [u (str/trim url)]
    (when (or (re-find #"(?i)^(https?:|mailto:)" u)
              (re-find #"^(/(?!/)|#)" u))
      u)))

(declare inline-nodes)

(defn- inline-node [{:keys [links?] :as opts} [_ code link-text href bold em em_]]
  (cond
    code      [:code code]
    link-text (if-let [h (and links? (safe-href href))]
                (into [:a {:href h :rel "noopener noreferrer" :target "_blank"}] (inline-nodes opts link-text))
                (into [:span] (inline-nodes opts link-text)))
    bold      (into [:strong] (inline-nodes opts bold))
    em        (into [:em] (inline-nodes opts em))
    em_       (into [:em] (inline-nodes opts em_))))

(defn- inline-nodes
  "One run of text as hiccup nodes: marks become elements, plain runs stay strings (hiccup2
   escapes them)."
  [opts s]
  (let [m (re-matcher inline-pattern s)]
    (loop [from 0, acc []]
      (if (.find m)
        (let [start (.start m)
              acc   (cond-> acc (< from start) (conj (subs s from start)))]
          (recur (.end m) (conj acc (inline-node opts (re-groups m)))))
        (cond-> acc (< from (count s)) (conj (subs s from)))))))

(defn- lines-nodes
  "Several source lines of one block, each inline-rendered, kept apart by <br>."
  [opts lines]
  (vec (apply concat (interpose [[:br]] (map #(inline-nodes opts %) lines)))))

;; ---------------------------------------------------------------------------
;; Blocks

(def ^:private fence-re #"^\s*```")
(def ^:private heading-re #"^(#{1,6})\s+(.*)$")
(def ^:private bullet-re #"^\s*[-*+]\s+(.*)$")
(def ^:private ordered-re #"^\s*(\d{1,9})[.)]\s+(.*)$")

(defn- item-start [line]
  (if-let [[_ t] (re-matches bullet-re line)]
    [:ul nil t]
    (when-let [[_ n t] (re-matches ordered-re line)]
      [:ol (parse-long n) t])))

(defn- block-start? [line]
  (or (str/blank? line) (re-find fence-re line) (re-matches heading-re line) (item-start line)))

(defn- inline-enumeration
  "A paragraph that enumerates (1) … (2) … in running text, split at each next number in turn —
   so a parenthetical like (see below) or (standing 1) never splits it. nil unless it opens with
   (1) and reaches (2)."
  [text]
  (when (str/starts-with? text "(1) ")
    (loop [n 2, from 4, starts [4]]
      (let [marker (str " (" n ") ")
            i      (str/index-of text marker from)]
        (if i
          (recur (inc n) (+ i (count marker)) (conj starts i (+ i (count marker))))
          (when (> n 2)
            (let [bounds (partition 2 (conj starts (count text)))]
              (mapv (fn [[a b]] (str/trim (subs text a b))) bounds))))))))

(defn- list-block [opts tag start items]
  (into [tag (if (and (= :ol tag) start (not= 1 start)) {:start start} {})]
        (map #(into [:li] (lines-nodes opts %)))
        items))

(defn- read-list
  "The list beginning at `ls`: items of one kind, an indented non-marker line continuing the item
   above it. Returns [block rest-lines]."
  [opts ls]
  (let [[tag start first-text] (item-start (first ls))]
    (loop [ls (rest ls), items [[first-text]]]
      (let [line (first ls)]
        (cond
          (nil? line) [(list-block opts tag start items) ls]

          (when-let [[t] (item-start line)] (= t tag))
          (recur (rest ls) (conj items [(nth (item-start line) 2)]))

          (and (not (block-start? line)) (re-find #"^\s+\S" line))
          (recur (rest ls) (update items (dec (count items)) conj (str/trim line)))

          :else [(list-block opts tag start items) ls])))))

(defn- read-fence [ls]
  (let [[body more] (split-with #(not (re-find fence-re %)) (rest ls))]
    [[:pre [:code (str/join "\n" body)]] (rest more)]))

(defn- read-paragraph [opts ls]
  (let [[para more] (split-with #(not (block-start? %)) ls)
        items       (when (= 1 (count para)) (inline-enumeration (str/trim (first para))))]
    [(if items
       (list-block opts :ol nil (map vector items))
       (into [:p] (lines-nodes opts (map str/trim para))))
     more]))

(defn ^{:malli/schema [:function
                       [:=> [:cat [:maybe :string]] :any]
                       [:=> [:cat [:maybe :string] [:map [:links? {:optional true} :boolean]]] :any]]}
  render
  "Markdown string -> a hiccup [:div.md …] of block nodes. nil/blank -> \"\". `opts`
   {:links? false} renders links as their text (see the ns doc); links render by default."
  ([s] (render s {}))
  ([s opts]
   (if (str/blank? s)
     ""
     (let [opts (merge {:links? true} opts)]
       (loop [ls (str/split-lines s), blocks [:div.md]]
         (if-let [line (first ls)]
           (cond
             (str/blank? line) (recur (rest ls) blocks)

             (re-find fence-re line)
             (let [[b more] (read-fence ls)] (recur more (conj blocks b)))

             (re-matches heading-re line)
             (let [[_ hashes txt] (re-matches heading-re line)
                   lvl (min 6 (+ 2 (count hashes)))]   ; #→h3, ##→h4 … (dashboard scale)
               (recur (rest ls) (conj blocks (into [(keyword (str "h" lvl))] (inline-nodes opts txt)))))

             (item-start line)
             (let [[b more] (read-list opts ls)] (recur more (conj blocks b)))

             :else
             (let [[b more] (read-paragraph opts ls)] (recur more (conj blocks b))))
           blocks))))))
