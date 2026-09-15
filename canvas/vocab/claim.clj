(ns canvas.vocab.claim
  "nido's own grammar: a CLAIM made about the declared design, and a ROLE whose players are
   declared.

   A claim is what a baseline says the area relies on and what a design commits the area to —
   the part the two records have in common. It is declared here, in the model, whatever checks
   it: a claim only a round can judge is still a node on the graph, with its subjects as edges,
   and a claim that can be written as a law moves into a law without leaving the model. What
   checks it is its evidence, and that is recorded rather than inferred.

   A role is how a claim speaks about several elements at once without deriving who they are:
   'the readers that gate on a design's standing' is a Role whose players are listed. Membership
   is authored, never computed from the code, so a claim about a role says exactly which elements
   it binds.

   nido's rather than fukan.common's, and on purpose: fukan grows its shipped vocabulary when a
   second project needs it, and nido is the first consumer of either sort."
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.vocab.code.kind :refer [Kind]]))

(defstructure Role
  "A named set of declared elements a claim may speak about as one. Its players are authored;
   nothing derives them."
  {:plays [:+ Module Operation Kind]})

(defstructure Claim
  "One statement about the declared design, about the elements or roles it names. Its instance
   name is its id — the id a baseline or design record carries for it — and its docstring is its
   statement. `:evidence` says what checks it: a round's judgement, named tests, or a named law."
  {:about    [:+ Module Operation Kind Role]
   :evidence [:enum "round" "test" "law"]})
