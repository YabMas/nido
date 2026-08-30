(ns nido.coordinator.lane.brief
  "Put a picked-up ticket's own words on its workstream ledger, at provision
   time, from the daemon's credential.

   A ticket that reaches nido through `bb nido:pickup` bypasses triage by
   design, so the workstream it provisions starts with an empty ledger. Until
   this existed, the session that got handed that workstream was handed the
   ticket's TITLE and nothing else: `/continue-ticket` reads nido's ledgers, and
   nothing in the chain read the ticket. Sessions parked on \"the title does not
   say what this means\" — which was true, and answerable in one HTTP call.

   The call was already being made. `nido.coordinator.lane.pickup/resolve-ref`
   retrieves the page to learn its BR-#### and title, then keeps four fields and
   drops the body on the floor. This namespace stops dropping it.

   Two properties are the point, and both come from doing it HERE rather than
   in the skill:

   - **It does not depend on the session's Notion access.** The daemon reads the
     PAT from the Keychain; a coordinator-spawned Run under launchd never
     inherits `NOTION_TOKEN`, so a session that reaches for `bb notion:*` gets a
     missing-credential error and has historically read it as having no Notion
     access at all. The ledger entry is already there before the agent starts.
   - **It is best-effort, never a gate.** A ticket nido could not read is
     strictly no worse than today. Every failure path here returns nil and logs;
     none of them can fail the run.

   Only fires on an EMPTY ledger. A promoted ticket already carries a :triage
   entry stating the goal, and a second telling of the same ticket would be a
   second thing to reconcile, not a second source."
  (:require
   [clojure.string :as str]
   [nido.coordinator.record.workstream :as ws]
   [nido.notion.client :as client]
   [nido.notion.markdown :as md]))

(defn- warn [msg]
  (binding [*err* *err*]
    (.println ^java.io.PrintWriter *err* (str "nido coordinator: " msg))))

(defn- body-markdown
  "The page body, or nil if it could not be read."
  [page-id token]
  (try
    (let [blocks (client/walk-blocks page-id token {})
          text   (md/blocks->markdown blocks)]
      (when-not (str/blank? text) text))
    (catch Exception e
      (warn (str "ticket brief: body unreadable for " page-id " — " (ex-message e)))
      nil)))

(defn- comments-markdown
  "The page's comment thread, or nil if absent or unreadable. An unreadable
   thread is not worth a warning: the integration may simply lack the comment
   capability, which is a permission, not a fault."
  [page-id token]
  (try
    (let [{:keys [status results]} (client/list-comments page-id token)]
      (when (and (= 200 status) (seq results))
        (md/comments->markdown results)))
    (catch Exception _e nil)))

(defn ticket-brief
  "Render `ref`'s Notion page — body and comments — as a markdown ledger entry.
   Returns the markdown, or nil when there is nothing readable to record.

   The preamble says who wrote this and how faithful it is, because the entry
   sits in a ledger otherwise made of agent judgements and must not be mistaken
   for one. It is a transcription; where it and the ticket differ, the ticket
   wins, and the URL is right there."
  [{:keys [id page-id notion-page-id url title]} token]
  ;; Both keys, because the payload has carried the page id under either at
  ;; different times — :notion-page-id is what notify/on-plan-spawn! reads,
  ;; :page-id what spawn/external-ref reads, and pickup now emits both.
  (when-let [page-id (and (not (str/blank? token)) (or page-id notion-page-id))]
    (let [body     (body-markdown page-id token)
          comments (comments-markdown page-id token)]
      (when (or body comments)
        (str/join
         "\n"
         (concat
          [(str "# " (or title id "Ticket"))
           ""
           (str "_Transcribed from Notion by nido at provision time — this ticket "
                "reached nido through pickup, so no triage stage read it first. "
                "Rendering is partial; the ticket is authoritative._")
           ""
           (str "- **Ref:** " (or id "—"))
           (when url (str "- **Notion:** " url))
           ""
           "## Ticket body"
           ""
           (or body "_(the page has no body blocks)_")]
          (when comments
            ["" "## Comments" "" comments])))))))

(defn ensure-ticket-brief!
  "Append a `:ticket` entry carrying the ticket's body and comments to workstream
   `ws-id`, when — and only when — that workstream has no entries at all.

   Total by construction: returns the entry path on success and nil on every
   other outcome, including every failure. Callers run this on the provisioning
   path, where a thrown exception would be read as a spawn failure and fail a
   run that is otherwise fine.

   `:ticket` is deliberately not a registered entry kind. `report/entry-payload`
   stores an unregistered kind as verbatim markdown, which is exactly right for
   a transcription: there is no judgement here to hold to a schema, and typing
   it would mean nido asserting a shape the ticket never agreed to."
  [project ws-id ref token]
  (try
    (let [w (ws/read-ws project ws-id)]
      (cond
        (nil? w)
        (do (warn (str "ticket brief: no workstream " ws-id)) nil)

        (seq (:entries w))
        nil

        :else
        (when-let [content (ticket-brief ref token)]
          (ws/append-entry! project ws-id {:kind :ticket} content))))
    (catch Throwable t
      (warn (str "ticket brief: append failed for " ws-id " — " (ex-message t)))
      nil)))
