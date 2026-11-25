(ns canvas.claims.ask-comments
  "The claims the ask-comments design (workstream ws-20261125-c09dbf) commits nido to, declared
   where they live on: a person's comment on one question of an ask, sent with a choice or on its
   own, and heard by whoever asked. Each Claim's name is the claim's id, its docstring the
   statement, and its :about the elements it is about."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.core.ask-tool :refer [core-ask-tool]]
            [canvas.core.buffer :refer [core-buffer]]
            [canvas.core.domain :refer [core-domain]]))

(Claim conductor-hears-the-answer
  "When the person answers an operator ask, the conductor's :ask-answered inbox item carries the answer value, and the conductor's brief prints it beside the ask's id. decide still never reads the value's content."
  {:about [core-domain core-buffer] :evidence "test"})

(Claim askers-told-notes-may-push-back
  "The ask guidance every ask tool and raise_operator_ask describes, and the conductor's brief, say that an answer may carry the person's comment on a question, with or without an option, and tell their reader to answer a comment that disputes the question or its options by re-asking or reframing, not by proceeding on an option nobody chose."
  {:about [core-ask-tool core-buffer] :evidence "test"})
