(ns canvas.claims.turn-end-routing
  "The claims the turn-end-routing design (ws-20261112-56f0a4) commits nido to: every turn end
   of a working session reaches one reader — the conductor on an activated workstream, a person's
   own turns included, otherwise a desktop notification.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.boot.attention :refer [boot-attention blocked-notifications]]
            [canvas.boot.core :refer [boot-core]]
            [canvas.coordinator.lane.conduct :refer [lane-conduct decide]]
            [canvas.coordinator.lane.conductor :refer [hand-backs! decide! lane-conductor conductors! carry!]]
            [canvas.coordinator.lane.drive :refer [handle-ship!]]
            [canvas.coordinator.lane.seats :refer [lane-seats]]
            [canvas.coordinator.record.session :refer [record-session]]
            [canvas.coordinator.record.session-events :refer [conversations]]
            [canvas.coordinator.view.workstreams :refer [view-workstreams]]))

(Claim turn-end-has-one-reader
  "A conversation belongs to the session whose worktree or home its recorded cwd (conversations) lies in. Each Stop of a conversation belonging to a session on a nido workstream other than the conductor, ending no instruction recorded for a Run, recorded after the instant conduct began reading that session under this design, and whose session's record still stands when conduct next reads it, reaches exactly one reader once: a hand-back to the workstream's conductor when the workstream is activated, otherwise one desktop notification to the person. Conduct keeps on the session record that instant and one map of read positions keyed by conversation id, holding the position of every conversation of the session, the one a facet drives included; the facet names only which conversation it drives. Conduct reads every conversation of the session from its own entry, the instruction outstanding on the driven conversation included, and seeds a conversation with no entry at its first event recorded after that instant. While the record stands, no operation removes, resets or moves an entry past a Stop not yet read: start! and adopt! change only which conversation the facet names. The read state lives on the record and goes with it: deleting a workstream, which removes every session record under it, gives up what their entries left unread. A hand-back keeps its once through its post key, a notification through the conversation's entry."
  {:about [hand-backs! lane-conduct boot-attention record-session conversations] :evidence "round"})

(Claim person-answers-in-place
  "An escalation the conductor decides on a hand-back marked as a person's turn, and the blocker it parks, is answered by nido when the person next prompts that same session, citing that prompt; no other escalation is answered by a prompt."
  {:about [decide! lane-conductor] :evidence "round"})

(Claim person-turn-stays-persons
  "The commit gate's refusal of a person's act in a session other than the conductor's reads the turn the command runs in from that conversation's own recorded prompts since its previous Stop: it refuses only in a turn whose latest such prompt carries the token of an instruction the conductor answers for. A turn whose latest such prompt carries no such token — a person's — keeps the person's acts allowed, whatever is outstanding on the session and whether or not an earlier turn of it was handed back."
  {:about [lane-conductor] :evidence "round"})

(Claim owed-hand-back-starts-conductor
  "A workstream owes its conductor a hand-back when one posted after its latest :closed entry, or ever when it has none, is answered by no decision. answers! posts no hand-back for the :closed entry that closes the workstream as its ledger stands: closing settles what came before it, and is itself nothing for a conductor to read. On a pass whose conductor/prompted reading answered a set, conductors! tries to start the conductor of an activated workstream that owes it a hand-back, open or closed, when its session is not running, and retires none such. A start that throws is logged and leaves the start to a later pass."
  {:about [conductors! lane-conductor] :evidence "round"})

(Claim blocked-banner-unless-conductor
  "A newly blocked session conduct reports is withheld from the desktop as the conductor's only when its workstream is activated; on a workstream not activated each one conduct reports is announced once."
  {:about [boot-core blocked-notifications] :evidence "round"})

(Claim brief-names-a-real-conductor
  "An occupant's brief says the workstream's conductor reads where it stopped only when the workstream is activated."
  {:about [lane-seats carry!] :evidence "round"})

(Claim a-person-speaking-is-a-turn
  "Every instruction nido gives a session with a :claude facet carries a token recorded as pending before it is given. A prompt nido did not give — a person's — changes nothing of who drives the session and sets no hold. Whether nido delivers an instruction reads no record of who gave a prompt: it is decided by what sends-only-when-driving-and-done and one-launch-baseline read — the permission mode a person's prompt reports among it — and by the pause the daemon sets while the account is at its usage limit. A person's turn end goes to the conductor as hand-back-to-instructor routes it. A person who wants nido out of a session stops it with release!."
  {:about [lane-conduct decide hand-backs!] :evidence "round"})

(Claim no-hold-anywhere
  "No session record carries a hold that anything reads: start!, adopt! and the merge lane refuse no session for one, and the board's session rows show none. A facet written before this change has its hold ignored, and its driver, when :person — which release! left on a held session it released, as the hold did on one it did not — is read as nobody: nido gives it nothing until a start or an adoption drives it."
  {:about [lane-conduct record-session handle-ship! view-workstreams] :evidence "round"})
