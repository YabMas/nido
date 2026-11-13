(ns nido.acp.transcript
  "ACP's session updates read as a transcript: the value a chat panel renders.

   HIDES ACP's update vocabulary — the `sessionUpdate` kinds, the chunk, tool-call and
   permission-request shapes, and which of them nido ignores (plans, usage, the command list,
   titles, mode changes). This is the one place an agent's message shape becomes trusted; nothing
   above it names an ACP field.

   A transcript is `{:items [...] :busy? bool}`. Items are only ever appended, and an item's `:id`
   is its position (\"i0\", \"i1\", …), so an id names the same item for the life of the
   transcript and a renderer can patch one item in place. Each item has a `:kind`:

     :user        `:text`, and `:context` — what the person's words came with, or nil
     :agent       `:text`, growing while `:streaming?`
     :thought     `:text`, growing while `:streaming?`
     :tool        `:title` `:status` (:pending :in-progress :completed :failed) `:tool-kind`
                  `:body` (its text output, or nil) `:diffs` ([{:path :old :new}])
     :permission  `:title` `:options` ([{:id :name :kind}]) `:request-id` `:answer` (an option id
                  once answered, else nil)
     :error       `:text`

   `fold` takes either an agent message exactly as `nido.acp.client` delivers it, or a local event
   — `{:local :user :text … :context …}`, `{:local :turn-ended}`, `{:local :error :text …}`,
   `{:local :answered :item-id … :option-id …}`. A message it does not read returns the
   transcript unchanged."
  (:require [clojure.string :as str]))

(def ^:private context-open "\n\n<nido-context>\n")
(def ^:private context-close "\n</nido-context>")

(defn ^{:malli/schema [:=> [:cat :string [:maybe :string]] :string]}
  prompt-text
  "The prompt the agent is sent for the person's `text` and the `context` it came with. The
   context follows the words in a marked block, so a replayed history reads back as the same
   words and the same context."
  [text context]
  (if (seq context) (str text context-open context context-close) text))

(defn- split-prompt
  "`prompt-text` read back: the words, and the context or nil."
  [s]
  (let [i (.lastIndexOf ^String s ^String context-open)]
    (if (and (>= i 0) (.endsWith ^String s ^String context-close))
      [(subs s 0 i) (subs s (+ i (count context-open)) (- (count s) (count context-close)))]
      [s nil])))

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
                                  [words context] (split-prompt raw)
                                  item      {:kind :user :text words :context context :raw raw
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
                    (append {:kind :user :text (:text e) :context (:context e)})
                    (assoc :busy? true))
    :turn-ended (-> (settle-streams t) (assoc :busy? false))
    :error      (-> (settle-streams t)
                    (append {:kind :error :text (:text e)})
                    (assoc :busy? false))
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
