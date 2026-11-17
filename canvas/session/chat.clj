(ns canvas.session.chat
  "Self-spec: a conversation with a session's agent, and a preview of the session's code.

   Both are about one session and outlive any one request, which is why they sit here rather than
   in the dashboard: a page subscribes to a chat and frames a preview, and neither dies with it."
  (:refer-clojure :exclude [reset!])
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.integration.acp :as acp]
            [fukan.common.typing.malli]))

(Module session-chat
  "A conversation with an agent running in a session's worktree.

   HIDES the conversation's lifetime: which adapter command, the deny list and permission mode
   it starts under, where its ACP session id is kept, when its process is started, resumed or
   stopped, when the conversation is ended, and how a change reaches whoever is watching. Its
   session is brought up before each agent start — by the function the chat's caller names for
   it, or on the project's defaults. The transcript is never stored: a
   conversation whose process is gone is loaded again by id, and its history replays through
   the same fold."
  (Operation transcript "A chat's transcript as it stands."
    {:signature [:=> [:catn [:chat :map]] acp/Conversation]})
  (Operation image
    "The image with a digest the transcript shows, or one a send or a load carries while the
     transcript lacks it — a send's from its acceptance to its turn's end, a replaced transcript's
     while its load replays — or nil. Held only for the handoff, never stored."
    {:signature [:=> [:catn [:chat :map] [:digest :string]] [:maybe :map]]})
  (Operation started? "Whether the conversation has been started, here or by an earlier process."
    {:signature [:=> [:catn [:chat :map]] :boolean]})
  (Operation send!
    "Send the person's words, with the references and images they came with, starting or resuming
     the agent first when needed. Answers true when accepted, or nil, changing nothing, when refused
     because a turn is running or an earlier send has not yet begun its turn. Every later failure — the agent not starting, a request
     refused, the process exiting, images to an agent that does not accept them — ends the turn as an error item in the transcript, never as
     a throw to the caller."
    {:signature [:=> [:catn [:chat :map] [:text :string] [:refs [:sequential :map]]
                            [:images [:sequential :map]]] :any]
     :delegates [acp/connect! acp/new-session! acp/load-session! acp/set-mode! acp/prompt!
                 acp/accepts-images? acp/fold acp/prompt-blocks]})
  (Operation cancel! "Cancel the running turn."
    {:signature [:=> [:catn [:chat :map]] :any] :delegates [acp/cancel!]})
  (Operation reset!
    "End the conversation, so the next send starts a new one: stop its agent, forget its ACP
     session id and empty its transcript, telling every subscriber. Answers true when done, or
     nil, changing nothing, while a turn runs or a send has not yet started its turn."
    {:signature [:=> [:catn [:chat :map]] :any] :delegates [acp/close!]})
  (Operation answer-permission!
    "Answer a permission request with one of its options. A request already answered, or one
     whose agent has gone, is left as it is."
    {:signature [:=> [:catn [:chat :map] [:item-id :string] [:option-id :string]] :any]
     :delegates [acp/respond!]})
  (Operation note-error!
    "Put an error its caller met on the chat's behalf into the transcript, under the latest turn."
    {:signature [:=> [:catn [:chat :map] [:text :string]] :any] :delegates [acp/fold]})
  (Operation note!
    "Put a note about something done to the chat's worktree from outside a turn into the transcript."
    {:signature [:=> [:catn [:chat :map] [:text :string]] :any] :delegates [acp/fold]})
  (Operation subscribe! "Call `f` with each new transcript; a function that unsubscribes."
    {:signature [:=> [:catn [:chat :map] [:f :any]] :any]}))

(Module session-preview
  "A session's code, served as a dashboard by a process of its own.

   HIDES how a preview is built — its classpath from the session's runtime checkout and the
   serving process's dependency jars, its port — and that a refresh is a sync of that checkout
   followed by a restart. No caller learns the port: a request reaches the preview by being
   forwarded, so whoever shows it keeps one origin.

   A refresh whose sync refuses (the members' working copies conflict) throws and leaves the
   running preview as it was; one whose new process fails to start throws and leaves none, and
   the next forward starts one from the checkout as it then is."
  (Operation forward!
    "Answer a ring request as the session's preview answers it, starting the preview when it is not
     running; a 502 saying why when it will not start or answer, and nil in a preview's own process."
    {:signature [:=> [:catn [:project :any] [:session :string] [:req :map]] [:maybe :map]]})
  (Operation refresh! "Sync the session's runtime checkout and restart its preview."
    {:signature [:=> [:catn [:project :any] [:session :string]] :any]}))

(Module session-change
  "A session's unlanded change: what it is, landing it, and throwing it away.

   HIDES the revsets that say what a session has not landed — everything between origin's main
   and its working copy — how a revert is made, and that publishing is the landing verb run as a
   process in the session's worktree."
  (Operation pending
    "The change the session holds that origin's main does not — {:description :files} — or nil."
    {:signature [:=> [:catn [:project :any] [:session :string]] [:maybe :map]]})
  (Operation revert!
    "Throw the session's unlanded change away, leaving its working copy empty on origin's main."
    {:signature [:=> [:catn [:project :any] [:session :string]] :any]})
  (Operation publish!
    "Land the session's change through bb nido:land, calling `on-line` with each line it prints;
     its exit code, zero when it landed."
    {:signature [:=> [:catn [:project :any] [:session :string] [:on-line :any]] :int]}))
