(ns canvas.integration.notion
  "Self-spec: `nido.notion.*` — the Notion adapter.

   The largest outbound adapter, and the one with the most surface: a REST client, a view
   registry the API cannot answer for itself, a markdown renderer, a follow-up database, and a
   video preprocessor. All of it is OUTBOUND — nothing here knows what a workstream is."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [Path]]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Kind NotionToken
  "The Notion integration token, read from the user's macOS Keychain.

   Never nido's to store. It lives in the Keychain scoped per user, is read on demand, and is
   passed explicitly to every call that needs it rather than held in a var — which is what keeps
   it out of a process image and out of a stack trace."
  :string)

(Kind NotionView
  "One saved view's definition — the database it reads and the filter it applies.

   Registered LOCALLY because the Notion REST API does not expose view filters at all. A trigger
   refers to a view by keyword and this registry is what turns that into a query, which means the
   registry can drift from the real view and there is a checker for exactly that."
  [:map [:database :string] [:filter :map]])

(Module notion-client
  "The Notion REST client, and the Keychain the token comes from.

   Two redef seams — `sh!` and `http-request` — so every call above them is testable without a
   network or a Keychain. That is why they are public despite being wrappers."
  {:child [NotionToken]}
  (Operation sh! "Shell out, wrapped so tests can stub the Keychain."
    {:signature [:=> [:catn [:args :any]] :map]})
  (Operation keychain-token "The Notion token, or nil when none is stored."
    {:signature [:=> [:catn] [:maybe NotionToken]] :delegates [sh!]})
  (Operation keychain-set! "Store or replace the Notion token."
    {:signature [:=> [:catn [:token NotionToken]] :any] :delegates [sh!]})
  (Operation http-request "One HTTP call, wrapped so tests can stub the network."
    {:signature [:=> [:catn [:method :keyword] [:url :string] [:opts :map]] :map]})
  (Operation clear-data-source-cache! "Drop the cached database-to-data-source mapping."
    {:signature [:=> [:catn] :any]})
  (Operation retrieve-database "One database's metadata."
    {:signature [:=> [:catn [:database-id :string] [:token NotionToken]] :map] :delegates [http-request]})
  (Operation retrieve-data-source "One data source's metadata."
    {:signature [:=> [:catn [:data-source-id :string] [:token NotionToken]] :map] :delegates [http-request]})
  (Operation resolve-data-source-id "The data-source id behind a database id, cached."
    {:signature [:=> [:catn [:database-id :string] [:token NotionToken]] [:maybe :string]]
     :delegates [retrieve-database]})
  (Operation data-source-query "Query a data source with a filter and sorts."
    {:signature [:=> [:catn [:data-source-id :string] [:token NotionToken] [:opts :map]] :map]
     :delegates [http-request]})
  (Operation retrieve-page "One page, with its properties."
    {:signature [:=> [:catn [:page-id :string] [:token NotionToken]] :map] :delegates [http-request]})
  (Operation update-page-properties! "Patch a page's properties."
    {:signature [:=> [:catn [:page-id :string] [:properties :map] [:token NotionToken]] :map]
     :delegates [http-request]})
  (Operation update-page-status! "Set one status property by its display name."
    {:signature [:=> [:catn [:page-id :string] [:property-name :string] [:status-name :string]
                            [:token NotionToken]] :map]
     :delegates [update-page-properties!]})
  (Operation normalise-property-name "A Notion display name as a keyword — `Ticket ID` becomes `:ticket-id`."
    {:signature [:=> [:catn [:s :string]] :keyword]})
  (Operation normalise-page "A Notion page object as the event payload the coordinator consumes."
    {:signature [:=> [:catn [:page :map]] :map] :delegates [normalise-property-name]})
  (Operation split-rich-text
    "Text split at safe boundaries into chunks Notion will accept. Notion caps a rich-text run,
     and a caller that did not split lost the tail silently."
    {:signature [:=> [:catn [:text :string]] [:vector :string]]})
  (Operation rich-text-runs "Text as a rich-text array."
    {:signature [:=> [:catn [:text :string]] [:vector :map]] :delegates [split-rich-text]})
  (Operation paragraph-blocks "Text as paragraph blocks, each within the cap."
    {:signature [:=> [:catn [:text :string]] [:vector :map]]})
  (Operation create-page-with-properties! "Create a page with properties and a description."
    {:signature [:=> [:catn [:data-source-id :string] [:token NotionToken] [:properties :map]
                            [:description [:maybe :string]]] :map]
     :delegates [http-request paragraph-blocks]})
  (Operation create-page! "Create a task page from semantic fields."
    {:signature [:=> [:catn [:data-source-id :string] [:token NotionToken] [:fields :map]] :map]
     :delegates [create-page-with-properties!]})
  (Operation retrieve-block-children "One page of a block's children."
    {:signature [:=> [:catn [:block-id :string] [:token NotionToken] [:opts :map]] :map]
     :delegates [http-request]})
  (Operation delete-block! "Archive a block."
    {:signature [:=> [:catn [:block-id :string] [:token NotionToken]] :map] :delegates [http-request]})
  (Operation prepend-block-children! "Insert children at the top of a page."
    {:signature [:=> [:catn [:page-id :string] [:children [:vector :map]] [:token NotionToken]] :map]
     :delegates [http-request]})
  (Operation walk-blocks
    "A page's whole block tree, bounded by depth and total. Both bounds exist because a page can
     nest arbitrarily and a walk without them is a request to fetch someone's entire wiki."
    {:signature [:=> [:catn [:root-id :string] [:token NotionToken] [:opts :map]] [:vector :map]]
     :delegates [retrieve-block-children]})
  (Operation list-comments "Every comment on a page, following pagination."
    {:signature [:=> [:catn [:page-id :string] [:token NotionToken]] [:vector :map]]
     :delegates [http-request]}))

(Module notion-views
  "The per-project view registry: what a view keyword means.

   Local because the Notion API will not say — it does not expose view filter definitions at
   all. That makes drift possible, which is why the checker below is not optional politeness."
  (Operation load-registry "A project's raw view registry."
    {:signature [:=> [:catn [:project ProjectName]] [:maybe :map]]})
  (Operation board-views "The views that feed the board."
    {:signature [:=> [:catn [:project ProjectName]] :any]})
  (Operation board-poll "How often nido polls the board views on its own behalf."
    {:signature [:=> [:catn [:project ProjectName]] [:maybe :any]]})
  (Operation resolve-view "The database and filter behind a view keyword."
    {:signature [:=> [:catn [:project ProjectName] [:view-kw :keyword]] [:maybe NotionView]]
     :delegates [load-registry]})
  (Operation facet-properties "The project's configured facet property names."
    {:signature [:=> [:catn [:project ProjectName]] :any] :delegates [load-registry]}))

(Module notion-preprocess
  "Walk a ticket for videos and transcribe them, so an agent reads what was said rather than
   being handed a link it cannot open."
  (Operation classify "What kind of video a block holds, if any. Pure."
    {:signature [:=> [:catn [:block :map]] [:maybe :map]]})
  (Operation fetch-page-meta! "One page's metadata."
    {:signature [:=> [:catn [:page-id :string] [:token NotionToken]] :map]})
  (Operation shell-bb-task "Shell out to a bb task. A redef seam."
    {:signature [:=> [:catn [:args :any]] :map]})
  (Operation preprocess-ticket!
    "Transcribe every video on a ticket and write a manifest beside the transcripts."
    {:signature [:=> [:catn [:opts :map]] :map]
     :delegates [classify fetch-page-meta! shell-bb-task]}))

(Module notion-views-check
  "Validate the local registry against the live database.

   The registry encodes filters the API cannot return, so nothing keeps it honest except asking
   whether every property it names still exists."
  {:child [NotionView]}
  (Operation check-registry
    "Every property the registry references, checked against the database that must have them."
    {:signature [:=> [:catn [:project ProjectName] [:token NotionToken]] :map]}))

(Module notion-markdown
  "A Notion block tree as markdown — a ticket's own words, where an agent will read them."
  (Operation rich->text "The plain text of a rich-text array."
    {:signature [:=> [:catn [:rich :any]] :string]})
  (Operation block->md "One block as markdown lines."
    {:signature [:=> [:catn [:entry :map]] [:vector :string]] :delegates [rich->text]})
  (Operation blocks->markdown "A walked block tree as markdown."
    {:signature [:=> [:catn [:blocks [:vector :map]]] :string] :delegates [block->md]})
  (Operation comments->markdown "A page's comments as markdown."
    {:signature [:=> [:catn [:comments [:vector :map]]] :string] :delegates [rich->text]}))

(Module notion-followups
  "The personal follow-up database — where work that leaves a branch lands.

   The horizontal destination of the shipping doctrine: a change that should not hold up the
   branch it was found on goes here instead of into the branch or into nobody's memory."
  (Operation config "The follow-up configuration, or nil when it is not set up."
    {:signature [:=> [:catn [:nido-config :map]] [:maybe :map]]})
  (Operation config! "Like `config`, but refuses with a setup hint rather than answering nil."
    {:signature [:=> [:catn [:nido-config :map] [:where :any]] :map] :delegates [config]})
  (Operation validate "Everything wrong with an entry, in words. Empty means it is fileable."
    {:signature [:=> [:catn [:entry :map]] [:vector :string]]})
  (Operation ->properties
    "An entry's semantic keys as the Notion-shaped properties the API wants — the one place the
     follow-up vocabulary meets the database's column names."
    {:signature [:=> [:catn [:cfg :map] [:entry :map]] :map]})
  (Operation create! "File a follow-up."
    {:signature [:=> [:catn [:cfg :map] [:entry :map]] :map] :delegates [validate ->properties]})
  (Operation list-entries
    "Open follow-ups, ordered by decay pressure then effort — what is going stale fastest, and
     what could be cleared quickest."
    {:signature [:=> [:catn [:cfg :map] [:status [:? :string]]] [:vector :map]]})
  (Operation check-config
    "Cross-check the configured display names against nido's own vocabulary and the live
     database, so a rename in Notion surfaces here rather than as a silent no-op."
    {:signature [:=> [:catn [:cfg :map] [:token NotionToken]] :map]}))
