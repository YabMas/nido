(ns canvas.claims.slack-thread-intake
  "The claims the Slack thread-intake design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.source.core :as source]
            [canvas.coordinator.source.slack-events :as events]
            [canvas.integration.slack :as slack]
            [canvas.coordinator.lane.drive :as drive]
            [canvas.coordinator.work :as work]
            [canvas.coordinator.report :as report]
            [canvas.coordinator.lane.work :as lane]
            [canvas.boot.core :as boot]))

(Claim owl-anywhere-the-bot-is
  "The :slack-events source's config names no channel: it emits for a reaction of its :emoji on any message — top-level or reply — in any conversation whose events reach the bot, and it never joins a conversation or invites itself."
  {:about [events/source-slack-events slack/slack-socket] :evidence "round"})

(Claim thread-is-the-unit
  "An emitted event's :id is the thread's slack id — slack-<channel>-<root ts>, the root being the reacted message itself when it has no thread — and its brief text is the thread's transcript, root first then every reply in order, each under its author's Slack display name, resolved through users.info and falling back to the user id only when Slack answers no name; the reacted message's ts rides along beside it."
  {:about [events/poll-once! slack/slack-client] :evidence "round"})

(Claim socket-acks-everything
  "Every envelope carrying an envelope_id is acknowledged on receipt, whether or not it is kept, so Slack never redelivers an envelope the source has seen; a dropped connection is reopened with backoff, and an auth refusal opens the source's breaker instead of retrying."
  {:about [slack/slack-socket] :evidence "round"})

(Claim one-connection-per-daemon
  "Within one daemon at most one :slack-events instance holds a Socket Mode connection, and only that instance emits, under its own config's :project, so an owl is emitted under at most one project; the holder is always an instance some enabled trigger names, since no instance starts for a config only disabled triggers name. An instance started while another holds the connection opens none and emits nothing, and its state says it is refused, which the coordinator's source health shows."
  {:about [events/source-slack-events] :evidence "round"})

(Claim reaction-identity-is-reacted-message
  "The source remembers each kept reaction by channel and reacted ts in persisted state for a retention age, and emits at most once per channel and reacted ts within it: a reaction Slack delivers again, or a second owl on the same message, inside that age emits nothing new. A reaction on a message whose entry has aged out is new to the source and is emitted again — an accepted limit of this design; it starts no second run, by fire-dedups-on-ref."
  {:about [events/poll-once!] :evidence "round"})

(Claim offline-gap-is-visible
  "While the source holds no connection its state says so with the time it was last connected, and the coordinator's source health shows it; reactions made while disconnected are not recovered."
  {:about [events/source-slack-events] :evidence "round"})

(Claim fire-dedups-on-ref
  "ensure-workstream! looks a fire's workstream up by its payload's external ref and mints one carrying that ref only when the lookup finds none. ref-has-pending-run? is true for a fire with an external ref whose workstream holds a run of the same trigger queued, preprocessing, running or awaiting review — and, when the fire's payload carries :once-per-ref? true, whenever its ref resolves to a workstream at all, whatever runs or verdicts it holds and whichever intake minted it. The :slack-events source sets :once-per-ref? true on every event it emits; the spawn lane names no source type. The daemon's process-envelope! calls spawn-and-submit! only in its last branch, after ref-has-pending-run? has answered false for the fire."
  {:about [drive/ensure-workstream! drive/ref-has-pending-run?] :evidence "round"})

(Claim slack-id-names-the-message
  "A Slack workstream's :slack-message external ref is either a thread id, slack-<channel>-<root ts>, or a ticket id scoping a thread id by a ticket key; both are made and parsed only by the Slack adapter, whose parse answers nil for anything that is not exactly one of the two forms. No code outside it reads a slack id's parts; the one test over its text outside the adapter is the record band's slack- prefix check, which tells a Slack ref from a Notion one when it mints a workstream for a bare id — the record band may not depend on the adapter, and both forms carry the prefix. The ref is the key of the workstream's Slack ticket record, and the channel and root ts it parses to are the thread conclude! and apply! post into."
  {:about [slack/slack-client work/conclude!] :evidence "round"})

(Claim thread-verdict-proposes-many
  "A :proposed-tickets record holds either a non-empty vector of tickets — each a ProposedTicket carrying :product under a key unique within the record — or a :doubt about the whole thread, never both; each ticket is validated by the existing ProposedTicket rules."
  {:about [report/coordinator-report] :evidence "round"})

(Claim thread-triage-writes-many
  "The skill the documented :slack-events trigger stanza names appends its verdict as one :proposed-tickets under the event's :id, never as a :proposed-ticket. A thread workstream is one holding a run whose :source :type is :slack-events — read off how it came to exist, since by fire-dedups-on-ref such a run lands only on a workstream its own fire minted. conclude! and apply! on a thread workstream whose verdict is a :proposed-ticket answer {:decision :error :error :wrong-kind}, and file, link and accept nothing."
  {:about [work/conclude! work/apply!] :evidence "round"})

(Claim conclude-fans-out
  "conclude! on a thread workstream whose verdict proposes tickets brings every ticket key to an initialised ticket workstream: it finds the key's workstream by its ticket-scoped slack id, minting one only when none exists, opens its ticket record and appends that ticket as its :proposed-ticket wherever either is missing, and concludes it. The thread workstream files nothing itself and holds no :notion ref, and is closed only once every key's workstream holds its ticket record and its :proposed-ticket. Re-running it mints no second workstream for a key and completes what an interrupted run left undone."
  {:about [work/conclude!] :evidence "round"})

(Claim doubt-stays-on-the-thread
  "conclude! on a thread verdict that is a doubt posts it in the thread mentioning the owner and mints nothing; the thread workstream stays at the gate, where a Reply resumes its session to revise."
  {:about [work/conclude!] :evidence "round"})

(Claim thread-verdict-is-never-accepted
  "apply! on a thread workstream records no acceptance there: on a doubt it answers {:decision :error :error :doubted}, and on proposed tickets it carries out conclude!'s fan-out, each ticket being accepted only on its own workstream."
  {:about [work/apply!] :evidence "round"})

(Claim ticket-workstream-is-todays-slack-workstream
  "A ticket workstream is filed, gated, accepted and driven by the same conclude!/apply! path a single Slack proposal uses, with the guarantees slack-ticket-filing declares; its page link is posted in the originating thread; it is gated independently of its siblings, and accepting it drives it on the ticket workstream."
  {:about [work/conclude! work/apply! work/gates lane/claim-and-drive!] :evidence "round"})

(Claim single-message-proposals-still-close
  "A workstream minted by the old :slack-reaction intake, keyed by a message and holding a :proposed-ticket, is concluded and accepted as before: its ref resolves to it, so no :slack-events fire runs on it, by fire-dedups-on-ref, and its verdict stays that :proposed-ticket."
  {:about [work/conclude! work/apply! drive/ref-has-pending-run?] :evidence "round"})

(Claim thread-intake-ships-disabled-and-capped
  "The :slack-events trigger stanza nido documents declares :enabled? false and a :max-in-flight, each a key the Trigger schema accepts. The daemon starts no source instance for a config only triggers declaring :enabled? false name, so a disabled stanza opens no connection; route resolves no fire from a broadcast for a trigger declaring :enabled? false; and spawn-and-submit! hands the trigger's :max-in-flight to the executor, which starts no run of that trigger while its in-progress runs already number that cap."
  {:about [source/route drive/spawn-and-submit! boot/tick!] :evidence "round"})
