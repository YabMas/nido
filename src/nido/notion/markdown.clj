(ns nido.notion.markdown
  "Render a Notion block tree as markdown, for putting a ticket's own words
   somewhere an agent will read them.

   Input is `nido.notion.client/walk-blocks` output — a flat vector of
   `{:block <notion-block> :depth n}` in document order — rather than a tree,
   because that is what the walker already produces and depth is all the
   nesting markdown needs.

   Fidelity is deliberately partial. A block type this does not know renders as
   a marker naming its type rather than vanishing: a reader who can see that
   something was there can go open the ticket, and a reader who cannot see it
   believes the page is shorter than it is. That failure mode is the whole
   reason this namespace exists."
  (:require
   [clojure.string :as str]))

(defn- run->text
  "One rich-text run. Formatting is dropped — a bold run that lost its asterisks
   still says what it said — but a MENTION keeps its href. A mention renders as
   the mentioned page's title, and on a ticket that title is usually a related
   ticket the reader needs to be able to open; dropping the link turns a
   pointer into a name."
  [{:keys [plain_text href type]}]
  (if (and href (= "mention" type))
    (str plain_text " (" href ")")
    plain_text))

(defn rich->text
  "The plain text of a Notion rich-text array."
  [rich]
  (->> rich (map run->text) (apply str)))

(defn- indent [depth] (str/join (repeat depth "  ")))

(defn- url-of
  "A file-ish block's URL, plus whether it will still resolve later.
   Notion-hosted files come back as S3 URLs signed for about an hour, so the
   query string is both enormous and worthless by the time anyone reads the
   ledger — it is stripped, and the caller says the link has expired."
  [f]
  (if-let [ext (get-in f [:external :url])]
    {:url ext :expiring? false}
    (when-let [fl (get-in f [:file :url])]
      {:url (str/replace fl #"\?.*$" "") :expiring? true})))

(defn- media-line
  "One line for an image / video / file / pdf block."
  [kind f]
  (let [{:keys [url expiring?]} (url-of f)
        cap (str/trim (rich->text (:caption f)))]
    (str "_[" (name kind) (when-not (str/blank? cap) (str ": " cap))
         (cond
           (nil? url)  "]_"
           expiring?   (str " — " url " (Notion-hosted; link expires, open the ticket to view)]_")
           :else       (str " — " url "]_")))))

(defn- table-cells [b]
  (->> (get-in b [:table_row :cells])
       (map #(-> (rich->text %) (str/replace "|" "\\|") (str/replace "\n" " ") str/trim))))

(defn- table-row-line [b]
  (str "| " (str/join " | " (table-cells b)) " |"))

(defn- table-separator
  "The `|---|---|` a markdown table needs under its header row. Without it the
   rows are literal pipes, not a table, in every renderer that matters.

   Emitted under the first row of EVERY table, regardless of Notion's
   `has_column_header`. That flag is off far more often than tables lack a
   header — authors bold the top row by hand instead of toggling it, which is
   exactly what the tooltip-copy table on BR-5099 does. Honouring the flag
   therefore renders the common case as a pile of pipes. Promoting the first row
   costs one row of styling when it really was data; every word survives either
   way, which is what a transcription owes the reader."
  [b]
  (str "| " (str/join " | " (repeat (count (table-cells b)) "---")) " |"))

(defn block->md
  "One block as zero or more markdown lines (a vector of strings)."
  [{:keys [block depth]}]
  (let [t    (:type block)
        body (get block (keyword t))
        text #(rich->text (:rich_text body))
        pad  (indent depth)]
    (case t
      ;; Block-level elements open with a blank line, list items do not. Without
      ;; it a paragraph following a list item is a lazy continuation of that item
      ;; in most renderers — the text survives but lands inside the bullet above
      ;; it, which on a ticket body silently reassigns whole requirements to the
      ;; wrong list entry. Runs of blanks collapse afterwards.
      "paragraph"          (let [s (text)] (if (str/blank? s) [""] ["" (str pad s)]))
      "heading_1"          ["" (str "# " (text))]
      "heading_2"          ["" (str "## " (text))]
      "heading_3"          ["" (str "### " (text))]
      "bulleted_list_item" [(str pad "- " (text))]
      "numbered_list_item" [(str pad "1. " (text))]
      "to_do"              [(str pad "- [" (if (:checked body) "x" " ") "] " (text))]
      "toggle"             [(str pad "- " (text))]
      "quote"              ["" (str pad "> " (text))]
      "callout"            ["" (str pad "> " (some-> body :icon :emoji (str " ")) (text))]
      "code"               (concat ["" (str "```" (or (:language body) ""))]
                                   (str/split-lines (text))
                                   ["```"])
      "divider"            ["" "---" ""]
      "equation"           [(str pad "$$" (:expression body) "$$")]
      "image"              [(str pad (media-line :image body))]
      "video"              [(str pad (media-line :video body))]
      "pdf"                [(str pad (media-line :pdf body))]
      "file"               [(str pad (media-line :file body))]
      "bookmark"           [(str pad "<" (:url body) ">")]
      "embed"              [(str pad "<" (:url body) ">")]
      "link_preview"       [(str pad "<" (:url body) ">")]
      "table"              [""]
      "table_row"          [(str pad (table-row-line block))]
      "child_page"         [(str pad "- _[sub-page: " (:title body) "]_")]
      "child_database"     [(str pad "- _[database: " (:title body) "]_")]
      ;; Structural containers carry no text of their own; their children are
      ;; walked separately and appear at the next depth.
      ("column_list" "column" "synced_block"
       "table_of_contents" "breadcrumb")  []
      ;; Anything unrecognised: say it was here rather than drop it silently.
      [(str pad "_[" t " block — not rendered; open the ticket]_")])))

(defn- with-table-separators
  "Expand blocks to lines, inserting a markdown header separator under the first
   row of each table that declares a column header. A `table_row` cannot know
   its own position, so the state lives here: a `table` block arms the flag and
   the next row it sees spends it."
  [blocks]
  (:lines
   (reduce (fn [{:keys [armed] :as acc} {:keys [block] :as b}]
             (let [lines (block->md b)]
               (case (:type block)
                 "table"     (-> acc (update :lines into lines) (assoc :armed true))
                 "table_row" (-> acc
                                 (update :lines into
                                         (cond-> lines
                                           armed (conj (str (indent (:depth b))
                                                            (table-separator block)))))
                                 (assoc :armed false))
                 (update acc :lines into lines))))
           {:lines [] :armed false}
           blocks)))

(defn blocks->markdown
  "Render `walk-blocks` output as markdown. Collapses runs of blank lines so a
   page built out of Notion's empty paragraphs does not arrive mostly empty."
  [blocks]
  (->> (with-table-separators blocks)
       (reduce (fn [acc line]
                 (if (and (str/blank? line) (str/blank? (peek acc)))
                   acc
                   (conj acc line)))
               [""])
       (drop 1)
       (str/join "\n")
       str/trim))

(defn comments->markdown
  "Render `nido.notion.client/list-comments` results as markdown. Notion's API
   returns an author id rather than a name, so comments are dated, not
   attributed — a comment whose author matters names them in its own text."
  [comments]
  (->> comments
       (map (fn [c]
              (str "- " (str/replace (str/trim (rich->text (:rich_text c))) "\n" "\n  ")
                   (when-let [at (:created_time c)]
                     (str "  _(" (subs at 0 (min 10 (count at))) ")_")))))
       (str/join "\n")))
