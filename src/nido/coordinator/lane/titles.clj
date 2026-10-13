(ns nido.coordinator.lane.titles
  "Generated display titles: one short sentence-case name per open workstream, so
   the board reads in one voice whether a row came from Notion, Slack, GitHub or a
   scratch session.

   A title is generated from the row's basis (`wsv/title-basis`) and stored with it,
   so a sweep regenerates only the titles whose basis moved — a new intent, a
   renamed ticket — and an unchanged board costs no model calls."
  (:require
   [babashka.process :as p]
   [clojure.string :as str]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.view.workstreams :as wsv]))

(def ^:private timeout-ms 60000)

(def ^:private title-max
  "Longer than this is not a title; the generator ignored its brief."
  80)

(defn- prompt [basis]
  (str "Write a title for this piece of software work, for a list of work items.\n"
       "Rules: 3 to 7 words, sentence case, no ticket ids, no quotes, no trailing "
       "punctuation, no prefixes like \"Fix:\". Reply with the title only.\n\n"
       "Work:\n" basis))

(defn- clean
  "The model's reply as a title, or nil when it is not one."
  [reply]
  (let [t (some-> reply str/trim (str/split-lines) first str/trim
                  (str/replace #"^[\"'`]+|[\"'`.]+$" ""))]
    (when (and (seq t) (<= (count t) title-max)) t)))

(defn ^{:malli/schema [:=> [:cat :string] [:maybe :string]]}
  generate
  "Ask haiku for a title summarising `basis`. nil when the call fails, times out
   or replies with something that is not a title — the row then keeps its
   fallback label, and the next sweep tries again."
  [basis]
  (try
    (let [proc (p/process {:env (dissoc (into {} (System/getenv)) "CLAUDECODE")
                           :in "" :out :string :err :string}
                          "claude" "-p" (prompt basis)
                          "--model" "haiku" "--tools" "" "--output-format" "text")
          res  (deref proc timeout-ms ::timeout)]
      (if (= ::timeout res)
        (do (p/destroy-tree proc) nil)
        (when (zero? (:exit res)) (clean (:out res)))))
    (catch Exception _ nil)))

(defn ^{:malli/schema [:=> [:cat :ProjectName [:? [:=> [:cat :string] [:maybe :string]]]] :int]}
  refresh-project!
  "Generate a title for every open workstream in `project` whose basis differs from
   the one its stored title was generated from. Returns how many titles were
   written. `gen` is the generator (default `generate`); a nil from it skips that
   workstream until the next sweep."
  ([project] (refresh-project! project generate))
  ([project gen]
   (->> (ws/list-ids project)
        (keep #(ws/read-ws project %))
        (remove :closed)
        (keep (fn [w]
                (let [basis (wsv/title-basis w (session/list-sessions project (:id w)))]
                  (when (and basis (not= basis (get-in w [:title :basis])))
                    (when-let [text (gen basis)]
                      (ws/set-title! project (:id w) text basis))))))
        count)))
