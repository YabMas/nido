(ns canvas.session.chat
  "Self-spec: a conversation with a session's agent, and a preview of the session's code.

   Both are about one session and outlive any one request, which is why they sit here rather than
   in the dashboard: a page subscribes to a chat and frames a preview, and neither dies with it."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.integration.acp :as acp]
            [fukan.common.typing.malli]))

(Module session-chat
  "A conversation with an agent running in a session's worktree.

   HIDES the conversation's lifetime: which adapter command, the deny list and permission mode
   it starts under, where its ACP session id is kept, when its process is started, resumed or
   stopped, and how a change reaches whoever is watching. The transcript is never stored: a
   conversation whose process is gone is loaded again by id, and its history replays through
   the same fold."
  (Operation transcript "A chat's transcript as it stands."
    {:signature [:=> [:catn [:chat :map]] acp/Conversation]})
  (Operation started? "Whether the conversation has been started, here or by an earlier process."
    {:signature [:=> [:catn [:chat :map]] :boolean]})
  (Operation send!
    "Send the person's words, with the references they came with, starting or resuming the agent
     first when needed. Answers true when accepted, or nil, changing nothing, when refused
     because a turn is running. Every later failure — the agent not starting, a request
     refused, the process exiting — ends the turn as an error item in the transcript, never as
     a throw to the caller."
    {:signature [:=> [:catn [:chat :map] [:text :string] [:refs [:sequential :map]]] :any]
     :delegates [acp/connect! acp/new-session! acp/load-session! acp/set-mode! acp/prompt!
                 acp/fold acp/prompt-text]})
  (Operation cancel! "Cancel the running turn."
    {:signature [:=> [:catn [:chat :map]] :any] :delegates [acp/cancel!]})
  (Operation answer-permission!
    "Answer a permission request with one of its options. A request already answered, or one
     whose agent has gone, is left as it is."
    {:signature [:=> [:catn [:chat :map] [:item-id :string] [:option-id :string]] :any]
     :delegates [acp/respond!]})
  (Operation note-error!
    "Put an error its caller met on the chat's behalf into the transcript, under the latest turn."
    {:signature [:=> [:catn [:chat :map] [:text :string]] :any] :delegates [acp/fold]})
  (Operation subscribe! "Call `f` with each new transcript; a function that unsubscribes."
    {:signature [:=> [:catn [:chat :map] [:f :any]] :any]}))

(Module session-preview
  "A session's code, served as a dashboard by a process of its own.

   HIDES how a preview is built — its classpath from the session's runtime checkout and the
   serving process's dependency jars, its port — and that a refresh is a sync of that checkout
   followed by a restart.

   Both operations answer the URL a running preview serves at, or throw an ex-info naming the
   step that failed: the sync refused (the members' working copies conflict), the process
   exited while starting, or it did not start in time. A refresh whose sync refuses leaves the
   running preview as it was; one whose new process fails to start leaves none, and the next
   preview-url starts one from the checkout as it then is."
  (Operation preview-url "The preview's URL, starting it when it is not running."
    {:signature [:=> [:catn [:project :any] [:session :string]] :string]})
  (Operation refresh! "Sync the session's runtime checkout and restart its preview; its URL."
    {:signature [:=> [:catn [:project :any] [:session :string]] :string]
     :delegates [preview-url]}))
