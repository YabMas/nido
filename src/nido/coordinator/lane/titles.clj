(ns nido.coordinator.lane.titles
  "Generated display titles: one short sentence-case name per open workstream, so
   the board reads in one voice whether a row came from Notion, Slack, GitHub or a
   scratch session.

   A title is generated from the row's basis (`wsv/title-basis`) and stored with it,
   so a sweep regenerates only the titles whose basis moved — a new intent, a
   renamed ticket — and an unchanged board costs no model calls."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.view.workstreams :as wsv]
   [nido.platform.core :as core]
   [nido.session.state :as session-state]))

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
                          "--model" "haiku" "--tools" "" "--output-format" "text"
                          "--setting-sources" "user,project")
          res  (deref proc timeout-ms ::timeout)]
      (if (= ::timeout res)
        (do (p/destroy-tree proc) nil)
        (when (zero? (:exit res)) (clean (:out res)))))
    (catch Exception _ nil)))

(defn ^{:malli/schema [:=> [:cat :ProjectName [:? [:=> [:cat :string] [:maybe :string]]]] :int]}
  refresh-project!
  "Generate a title for every open workstream in `project` whose basis differs from
   the one its stored title was generated from, and clear the title of one that no
   longer has a basis — its stored title describes nothing it can still be checked
   against. Returns how many titles were written or cleared. `gen` is the
   generator (default `generate`); a nil from it skips that workstream until the
   next sweep."
  ([project] (refresh-project! project generate))
  ([project gen]
   (->> (ws/list-ids project)
        (keep #(ws/read-ws project %))
        (remove ws/closure)
        (keep (fn [w]
                (let [basis (wsv/title-basis w (session/list-sessions project (:id w)))]
                  (cond
                    (and (nil? basis) (:title w))
                    (ws/clear-title! project (:id w))

                    (and basis (not= basis (get-in w [:title :basis])))
                    (when-let [text (gen basis)]
                      (ws/set-title! project (:id w) text basis))))))
        count)))

;; ---------------------------------------------------------------------------
;; Terminal tab titles
;; ---------------------------------------------------------------------------

(defn ^{:malli/schema [:=> [:cat] :string]}
  tab-titles-path
  "~/.nido/tab-titles.tsv — one `<dir>\t<title>` line per session home and live
   worktree. A shell hook reads it at every prompt and names the terminal tab by the
   longest `<dir>` that prefixes its cwd, so a tab reads what the board reads."
  []
  (str (fs/path (core/nido-root) "tab-titles.tsv")))

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:vector [:tuple :string :string]]]}
  tab-titles
  "[dir title] pairs for every session of every open workstream in `project`: its
   session home, and its worktree when the live registry holds one. The title is the
   workstream's board label. Tabs and paths are what a shell matches on, so a title
   carrying either is flattened to spaces."
  [project]
  (let [worktrees (into {} (for [[dir {:keys [project-name session-name]}] (session-state/read-registry)
                                 :when session-name]
                             [[project-name session-name] dir]))]
    (into []
          (for [w     (keep #(ws/read-ws project %) (ws/list-ids project))
                :when (not (ws/closure w))
                :let  [sessions (session/list-sessions project (:id w))
                       title    (str/replace (wsv/label w sessions) #"[\t\r\n]+" " ")]
                s     sessions
                :let  [sname (:name s)]
                :when (seq sname)
                dir   (cons (session-state/session-home-dir (name project) sname)
                            (some-> (get worktrees [(name project) sname]) list))]
            [(str dir) title]))))

(defn ^{:malli/schema [:=> [:cat [:sequential :ProjectName]] :boolean]}
  write-tab-titles!
  "Rewrite `tab-titles-path` from every project in `projects`. Atomic (temp file then
   rename), so a prompt never reads half a file, and skipped when the content is
   unchanged, so a hook keyed on the file's mtime does no work. Returns whether it
   wrote."
  [projects]
  (let [path    (tab-titles-path)
        content (apply str (for [p projects, [dir title] (tab-titles p)]
                             (str dir "\t" title "\n")))]
    (if (= content (when (fs/exists? path) (slurp path)))
      false
      (let [tmp (str path "." (java.util.UUID/randomUUID) ".tmp")]
        (fs/create-dirs (fs/parent path))
        (spit tmp content)
        (fs/move tmp path {:replace-existing true})
        true))))
