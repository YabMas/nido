(ns canvas.claims.slack-ticket-filing
  "The claims the Slack ticket-filing design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.work :refer [claim-and-drive! pickup!]]
            [canvas.coordinator.report :refer [coordinator-report]]
            [canvas.coordinator.work :refer [apply! conclude!]]
            [canvas.integration.notion :refer [notion-client]]))

(Claim product-page-has-no-analysis
  "The body filing writes for a proposal carrying :product, from conclude! or apply!, is one callout block holding the :product sections and the Slack source link. It contains no technical field's text (:problem, :root-cause, :fix, :request, :proposed-change, :rationale, :watch-out) and no value it also wrote as a property (title, type, priority, status, lifecycle, app domain); what a :product section may hold is bounded by one-text-per-reader."
  {:about [conclude! apply!] :evidence "round"})

(Claim filed-for-verification
  "Filing a proposal carrying :product creates one page at Status \"Needs verification\", Lifecycle \"Development\", App Domain set to the proposal's :app-domain and Ball Holder set to report/owner->user-id :jaap, add-ref!s its BR to the workstream as :notion, and posts the page URL as a reply in the reported message's thread, recording on the slack-keyed ticket record that the link is posted once Slack answers {:ok true}. conclude! and apply! on a filed workstream with no link recorded post it then, so a failed link-back is retried by the next call of either."
  {:about [conclude! apply!] :evidence "round"})

(Claim filed-at-most-once
  "No slack-keyed workstream gets a second Notion page, whichever era its proposal is from. Every Slack filing — conclude!'s, apply!'s, and apply!'s for a pre-change proposal — creates only when the workstream has no :notion ref, records a filing-started mark on the slack-keyed ticket record before it calls create, and clears it only when Notion answers with a definite error; a mark with no ref makes every filing refuse with an error naming the interrupted filing, leaving the gate parked for a person to check Notion."
  {:about [conclude! apply!] :evidence "round"})

(Claim no-drive-before-accept
  "While the BR's ticket record is held by an unaccepted Slack proposal, nothing but apply! starts its drive: conclude! never enqueues :plan-bug or sets a ticket record :planning, and pickup! — the other verb that claims and enqueues a BR — refuses such a BR, saying the analysis waits in the gate."
  {:about [conclude! apply! pickup!] :evidence "round"})

(Claim accept-writes-then-drives
  "Accepting a product proposal appends the technical sections to the filed page as a collapsed toggle titled \"Engineering notes & history\" — appended into an existing one of that title rather than doubled — and only then claims the BR :planning and enqueues :plan-bug with its page id, URL and title; a failed Notion write enqueues nothing and leaves the gate as it was."
  {:about [apply! claim-and-drive!] :evidence "round"})

(Claim doubt-replies-and-files-nothing
  "conclude! on a :doubt proposal posts one threaded reply that opens with the Slack mention of report/owner->slack-id :jaap followed by the doubt, and creates no page; apply! refuses a :doubt proposal, so a doubt is overridden only by a reply that yields a product proposal. Jaap's reply reaches the parked workstream as the gate's Reply, which resolve-gate! passes to resume! as revise feedback for the triage-slack session."
  {:about [conclude! apply!] :evidence "round"})

(Claim one-text-per-reader
  "A ProposedTicket written from now on holds exactly one of :product and :doubt, a :product proposal also holds an :app-domain from report/AppDomain, and report refuses to accept a :product section containing a backtick, a path with a source-file extension or a name:number coordinate; report renders the product text and the technical text separately; the gate card shows both, the page filed for a :product proposal shows only the product text. A proposal written before this change carries neither, still reads, and is filed as pre-change-proposal-filed-as-written says."
  {:about [coordinator-report] :evidence "round"})

(Claim notion-triage-stands-down
  "Once filing has recorded its page — the :notion ref and the BR ticket record at :awaiting-input, both written before conclude! or apply! returns — tickets/gate-decision answers :skip-active for that BR, so :triage-new spawns no /triage-bug for it; accepting moves the record to :planning, which the gate also skips. The one gap is the window between Notion creating the page and that record being written, which Jaap accepted at entry 55."
  {:about [conclude!] :evidence "round"})

(Claim pre-change-proposal-filed-as-written
  "apply! accepts a proposal holding neither :product nor :doubt, the shape the read schema admits from before this change, as it was accepted when written: it files one page at Status \"Not started\" whose body is report->markdown of the proposal, add-ref!s its BR to the workstream as :notion, posts the page URL in the thread best-effort and completes the slack-keyed ticket record, its create guarded as filed-at-most-once says."
  {:about [apply!] :evidence "round"})

(Claim adapter-knows-no-proposal
  "The Notion adapter builds blocks from titled sections (a heading and paragraphs or list items) and knows nothing of ProposedTicket; which sections a proposal yields is decided in report."
  {:about [notion-client] :evidence "round"})

(Claim slack-ids-one-source
  "Slack member ids for owners are defined once, in report's owner->slack-id beside owner->user-id, and every mention nido posts resolves through it."
  {:about [coordinator-report] :evidence "round"})
