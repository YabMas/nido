(ns nido.acp.transcript
  "ACP's session updates read as a transcript: the value a chat panel renders.

   HIDES ACP's update vocabulary — the `sessionUpdate` kinds, the chunk, tool-call and
   permission-request shapes, and which of them nido ignores (plans, usage, the command list,
   titles, mode changes) — and the form a message's references take in the prompt the agent
   receives. This is the one place an agent's message shape becomes trusted; nothing above it
   names an ACP field.

   A transcript is `{:items [...] :busy? bool}`, plus `:started-at` (epoch milliseconds) while a
   turn the person started here is running. Items are only ever appended, and an item's `:id`
   is its position (\"i0\", \"i1\", …), so an id names the same item for the life of the
   transcript and a renderer can patch one item in place. Each item has a `:kind`:

     :user        `:text`, and `:refs` — the references the words came with, each `{:label}`,
                  in the order they were numbered
     :agent       `:text`, growing while `:streaming?`
     :thought     `:text`, growing while `:streaming?`
     :tool        `:title` `:status` (:pending :in-progress :completed :failed) `:tool-kind`
                  `:body` (its text output, or nil) `:diffs` ([{:path :old :new}])
     :permission  `:title` `:options` ([{:id :name :kind}]) `:request-id` `:answer` (an option id
                  once answered, else nil)
     :error       `:text`

   `fold` takes either an agent message exactly as `nido.acp.client` delivers it, or a local event
   — `{:local :user :text … :refs [{:label :detail}] :at ms}`, `{:local :turn-ended}`,
   `{:local :error :text …}`, `{:local :answered :item-id … :option-id …}`. A message it does not
   read returns the transcript unchanged."
  (:require [clojure.string :as str]))

(def ^:private context-open "\n\n<nido-context>\n")
(def ^:private context-close "\n</nido-context>")

(defn ^{:malli/schema [:=> [:cat :string [:sequential [:map [:label :string] [:detail {:optional true} :string]]]]
                       :string]}
  prompt-text
  "The prompt the agent is sent for the person's `text` and the references it came with, each
   `{:label :detail}`. The references follow the words in a marked block, one numbered from 1 per
   reference — its label on the `[n]` line, its detail on the lines after — so a replayed history
   reads back as the same words and the same labels, in the same order."
  [text refs]
  (if (seq refs)
    (str text context-open
         (str/join "\n\n" (map-indexed (fn [i {:keys [label detail]}]
                                          (str "[" (inc i) "] " label (when (seq detail) (str "\n" detail))))
                                        refs))
         context-close)
    text))

(defn- split-prompt
  "`prompt-text` read back: the words, and the references' labels in order."
  [s]
  (let [i (.lastIndexOf ^String s ^String context-open)]
    (if (and (>= i 0) (.endsWith ^String s ^String context-close))
      [(subs s 0 i)
       ;; A label is the first line of a paragraph and carries the number that comes next, so a
       ;; detail line that happens to start with a bracketed number stays detail.
       (reduce (fn [refs para]
                 (let [[_ n label] (re-matches #"\[(\d+)\] (.*)" (or (first (str/split-lines para)) ""))]
                   (if (and n (= (parse-long n) (inc (count refs)))) (conj refs {:label label}) refs)))
               []
               (str/split (subs s (+ i (count context-open)) (- (count s) (count context-close)))
                          #"\n\n"))]
      [s []])))

(defn ^{:malli/schema [:=> [:cat] :Conversation]}
  empty-transcript
  "A conversation with nothing in it."
  []
  {:items [] :busy? false})

(defn- append [t item]
  (update t :items conj (assoc item :id (str "i" (count (:items t))))))

(defn- index-where [t pred]
  (first (keep-indexed (fn [i item] (when (pred item) i)) (:items t))))

(defn- settle-streams
  "Mark every streaming text item finished — what a turn ending, or a new kind of item arriving,
   means for the text before it."
  [t]
  (update t :items (fn [items] (mapv #(dissoc % :streaming?) items))))

(defn- chunk-text [content]
  (when (= "text" (:type content)) (:text content)))

(defn- add-chunk
  "Grow the last item when it is a streaming item of this kind from the same message, else start
   one. Replayed history carries a message id; a live chunk may not, and then consecutive chunks of
   one kind are one message."
  [t kind msg-id text]
  (let [last-item (peek (:items t))]
    (if (and last-item (= kind (:kind last-item)) (:streaming? last-item)
             (= msg-id (:msg-id last-item)))
      (update-in t [:items (dec (count (:items t))) :text] str text)
      (-> (settle-streams t)
          (append {:kind kind :text text :msg-id msg-id :streaming? true})))))

(def ^:private statuses
  {"pending" :pending "in_progress" :in-progress "completed" :completed "failed" :failed})

(defn- tool-fields
  "The fields of a tool call or tool-call update that nido reads, each only when it is present —
   an update carries just what changed."
  [u]
  (let [content (:content u)
        texts   (keep #(when (= "content" (:type %)) (chunk-text (:content %))) content)
        diffs   (vec (for [c content :when (= "diff" (:type c))]
                       {:path (:path c) :old (:oldText c) :new (:newText c)}))
        raw-out (:rawOutput u)]
    (cond-> {}
      (:title u)                 (assoc :title (:title u))
      (:kind u)                  (assoc :tool-kind (keyword (:kind u)))
      (statuses (:status u))     (assoc :status (statuses (:status u)))
      (seq texts)                (assoc :body (str/join "\n" texts))
      (and (empty? texts) (string? raw-out)) (assoc :body raw-out)
      (seq diffs)                (assoc :diffs diffs))))

(defn- fold-update [t {:keys [sessionUpdate] :as u}]
  (case sessionUpdate
    "agent_message_chunk" (if-let [s (chunk-text (:content u))] (add-chunk t :agent (:messageId u) s) t)
    "agent_thought_chunk" (if-let [s (chunk-text (:content u))] (add-chunk t :thought (:messageId u) s) t)
    "user_message_chunk"  (if-let [s (chunk-text (:content u))]
                            (let [last-item (peek (:items t))
                                  same?     (and (= :user (:kind last-item)) (:msg-id last-item)
                                                 (= (:messageId u) (:msg-id last-item)))
                                  raw       (str (when same? (:raw last-item)) s)
                                  [words refs] (split-prompt raw)
                                  item      {:kind :user :text words :refs refs :raw raw
                                             :msg-id (:messageId u)}]
                              (if same?
                                (update-in t [:items (dec (count (:items t)))] merge item)
                                (-> (settle-streams t) (append item))))
                            t)
    "tool_call"           (-> (settle-streams t)
                              (append (merge {:kind :tool :tool-id (:toolCallId u) :title "Tool call"
                                              :status :pending :body nil :diffs []}
                                             (tool-fields u))))
    "tool_call_update"    (if-let [i (index-where t #(and (= :tool (:kind %))
                                                          (= (:toolCallId u) (:tool-id %))))]
                            (update-in t [:items i] merge (tool-fields u))
                            t)
    t))

(defn- fold-permission [t {:keys [id params]}]
  (-> (settle-streams t)
      (append {:kind       :permission
               :title      (or (get-in params [:toolCall :title]) "The agent asks to continue")
               :request-id id
               :answer     nil
               :options    (mapv (fn [o] {:id (:optionId o) :name (:name o)
                                          :kind (some-> (:kind o) keyword)})
                                 (:options params))})))

(defn- fold-local [t {:keys [local] :as e}]
  (case local
    :user       (-> (settle-streams t)
                    (append {:kind :user :text (:text e)
                             :refs (mapv #(select-keys % [:label]) (:refs e))})
                    (assoc :busy? true)
                    (cond-> (:at e) (assoc :started-at (:at e))))
    :turn-ended (-> (settle-streams t) (assoc :busy? false) (dissoc :started-at))
    :error      (-> (settle-streams t)
                    (append {:kind :error :text (:text e)})
                    (assoc :busy? false)
                    (dissoc :started-at))
    :answered   (if-let [i (index-where t #(= (:item-id e) (:id %)))]
                  (assoc-in t [:items i :answer] (:option-id e))
                  t)
    t))

(defn ^{:malli/schema [:=> [:cat :Conversation :Event] :Conversation]}
  fold
  "`transcript` with one event folded in: an agent message as `nido.acp.client` delivers it, or a
   local event (see the namespace docstring)."
  [transcript event]
  (cond
    (:local event)                                     (fold-local transcript event)
    (= "session/update" (:method event))               (fold-update transcript (get-in event [:params :update]))
    (= "session/request_permission" (:method event))   (fold-permission transcript event)
    :else                                              transcript))
