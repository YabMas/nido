(ns canvas.claims.blocker-wording
  "The claims the blocker-wording design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.conductor :refer [lane-conductor close-unanswered!]]
            [canvas.coordinator.lane.seats :as seats]))



(Claim hand-back-words-fixed-at-post
  "Every hand-back post-hand-back! logs carries, beside the :text the conductor reads, its :origin — one of :turn, :person-turn, :act, :chat, :stall — and the plain :said text where its origin has one, and an :act one the :act-seq of the ledger entry it hands back. The :text is composed as before."
  {:about [lane-conductor seats/post!] :evidence "test"})

(Claim undecided-escalation-asks-the-next-move
  "The :needs of the blocker close-unanswered! parks is one question ending in '?' within the blocker contract's 240 characters, and is never 'What should the seat do next, given the report below that the conductor could not decide on?'. Where the position has a next stage, it asks whether the seat should make the move that stage names, in words of that move — for :implement, whether the seat should start building on the governing design, naming its entry. Where the position has no next stage, it names the position in plain words and asks whether the workstream should close as it stands or the seat do something more."
  {:about [close-unanswered!] :evidence "test"})

(Claim undecided-escalation-tells-the-report
  "The :summary of that blocker says in prose where the report came from — the seat's turn, the person's own turn, the person's act at entry N, the chat, a stall — and what it said: a turn's, chat's or stall's :said quoted, excerpted and rendered plain; an act described from its ledger entry's kind and its plain-text fields. A message without an :origin whose :key is act:<seq> is worded as that act; any other says its report is in the conductor's hand-back and quotes nothing of it. What nido composes into the :summary names no session and carries neither the hand-back's :text nor the governing design's summary; words quoted from a seat or the person are theirs."
  {:about [close-unanswered!] :evidence "test"})

(Claim undecided-escalation-has-no-edn
  "Neither the :needs nor the :summary of that blocker contains EDN — no map or vector bracket and no keyword token — for a hand-back of every origin, including one logged before origins were kept, and including one whose quoted words themselves carry EDN: quoted words are rendered plain before they are placed."
  {:about [close-unanswered!] :evidence "test"})
