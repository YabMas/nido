(ns canvas.claims.ask-comments
  "The claims the ask-comments design (workstream ws-20261125-c09dbf) commits nido to, declared
   where they live on: a person's comment on one question of an ask, sent with a choice or on its
   own, and heard by whoever asked. Each Claim's name is the claim's id, its docstring the
   statement, and its :about the elements it is about."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.work :refer [core-resolve! person-says!]]
            [canvas.core.ask-tool :refer [core-ask-tool]]
            [canvas.core.buffer :refer [core-buffer]]
            [canvas.core.domain :refer [core-domain]]
            [canvas.ui.surface :refer [ui-server]]))

(Claim conductor-hears-the-answer
  "When the person answers an operator ask, the conductor's :ask-answered inbox item carries the answer value, and the conductor's brief prints it beside the ask's id. decide still never reads the value's content."
  {:about [core-domain core-buffer] :evidence "test"})

(Claim askers-told-notes-may-push-back
  "The ask guidance every ask tool and raise_operator_ask describes, and the conductor's brief, say that an answer may carry the person's comment on a question, with or without an option, and tell their reader to answer a comment that disputes the question or its options by re-asking or reframing, not by proceeding on an option nobody chose."
  {:about [core-ask-tool core-buffer] :evidence "test"})

(Claim pick-is-choice-and-or-note
  "The dashboard decodes a Send answers post into one pick per question, in question order, each a map holding a letter, a note, both, or neither; a note is the trimmed words, and blank words are no note."
  {:about [ui-server] :evidence "test"})

(Claim answer-text-ties-note-to-question
  "core-resolve! answers Send answers with one string. A question with a note shows, under that question, the chosen option or an explicit statement that no option was chosen, then the note, marked as the person's comment on that question. A question with a note and no option counts as answered. The submit is refused only when no question has an option or a note. An answer whose questions carry no note reads exactly as before: for content without :questions, the chosen option's label alone, as its option action answered; for content with :questions, one or several, the per-question lines Send answers renders today."
  {:about [core-resolve!] :evidence "test"})

(Claim words-paths-unchanged
  "The ask-wide Answer box (:reply), tell and blocker:answer answer an ask with the same text the same words gave before this change, tied to no question."
  {:about [core-resolve! person-says!] :evidence "test"})
