(ns canvas.claims.amender-canvas
  "The claims the amender-canvas design (workstream ws-20261127-ce17f2) commits nido to,
   declared where they live on. Each Claim's name is the claim's id, its docstring the statement,
   and its :about the elements it is about — the three the design record states, which the
   landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.core.domain :refer [code-held core-domain decide]]
            [canvas.core.executor-claude :refer [executor-claude]]
            [canvas.core.provision :refer [core-provision]]
            [canvas.design.check :refer [design-check elements]]
            [canvas.review.passes :refer [amender-trespass]]
            [canvas.review.record :refer [review-record]]
            [canvas.tasks.nido-review :refer [amend-check*]]))

(Claim broken-canvas-never-appended
  "This claim speaks only of a design round that moved a path under the declaration's spec-dirs, over a declaration that listed at the round's tree before the amender ran. Such a round appends its amendment only if design-check's element listing at the working copy after the amender's writes is :listed. The duplicate-operation case is covered by nido.review.record-test/a-duplicate-operation-is-refused-and-put-back. In that test the amender adds a second (Operation …) form whose name clashes with an existing one in the same canvas namespace. The test fails on today's code, which appends the amendment and leaves the broken canvas, and passes after the change, with no amendment appended and the canvas loading."
  {:about [review-record elements] :evidence "test"})

(Claim load-failure-is-a-refusal
  "When the declaration listed at the round's tree before the amender ran and the amender's writes leave a canvas namespace failing to load, the amendment is refused through the same re-ask channel as a ledger refusal. The amender is handed the load failure, keeps write access to spec-dirs, and its repaired answer is checked again, within the same re-ask limit."
  {:about [review-record] :evidence "round"})

(Claim timed-out-check-never-kept
  "A design round that moved a path under the declaration's spec-dirs and whose post-amend listing timed out appends no amendment and puts the amender's canvas writes back, without a re-ask, whatever the listing answered before the amender ran. The timeout is surfaced: the round's printed outcome and its report name the timed-out listing and the limit it ran under, and the round's run dir keeps the amender's canvas files as the amender left them, so the case can be listed again against fukan."
  {:about [review-record elements] :evidence "test"})

(Claim machinery-failure-blames-no-amender
  "When the declaration did not list at the round's tree before the amender ran, a post-amend load failure is not the amender's: the load gate neither refuses nor re-asks over it, and the next round reports the listing as today. Whether the amender's canvas writes outlive the round is then decided by canvas-writes-share-the-amendments-fate alone."
  {:about [review-record elements] :evidence "round"})

(Claim canvas-writes-share-the-amendments-fate
  "The paths under spec-dirs that the amender itself wrote this round outlive the round only if the round appended its amendment. On every other end of the round, whatever kept the amendment out (a load failure, a timed-out listing, a ledger refusal that outlived its re-asks, an answer carrying no record), and whether or not the declaration loads with them, they are put back to what they held before the amender launched, and the round's run dir keeps what was put back. A path that no longer holds what the amender left is not put back."
  {:about [review-record amender-trespass] :evidence "round"})

(Claim loading-canvas-edits-kept-as-before
  "When the amender's writes leave the declaration listing, the load gate adds nothing to the round: it refuses nothing and re-asks nothing, and an amendment the round appends keeps the amender's canvas edit exactly as today. A round that would have cleared or continued before the change ends the same way after it, unless its post-amend listing timed out."
  {:about [review-record] :evidence "test"})

(Claim load-error-names-its-cause
  "When a listing fails because a canvas namespace does not load, the error design-check hands its caller names the namespace and form that does not load (for a duplicate, that the module authors two instances of one name), not only the first namespace whose load failed."
  {:about [design-check] :evidence "test"})

(Claim dry-run-answers-loading
  "The amend dry-run a design amender is given refuses (exit 1) when the working copy's declaration does not list, whether a namespace fails to load or the listing times out, with the same cause the round's gate would give, besides answering whether the ledger would take the record."
  {:about [amend-check*] :evidence "round"})

(Claim held-turns-write-only-the-declaration
  "While code-held answers :design-not-cleared, every unit turn starts with :read-only? set, plus :writable as the declaration dirs its session's :up recorded. Its file-editing tools are admitted for a path under one of those dirs and denied for every other path inside its working copy. A session that recorded none (an unmodelled project, or one provisioned before this change) is held exactly as today. code-held still reads only the activation, design, clearance and retraction entries, and no grant bears on it."
  {:about [code-held decide executor-claude] :evidence "test"})

(Claim core-knows-no-canvas
  "The core reads no project's design configuration. The declaration dirs reach it only as opaque absolute paths in the provision verb's :up result, and no nido.core. or nido.executor. namespace requires a nido.design. one."
  {:about [core-domain core-provision] :evidence "round"})
