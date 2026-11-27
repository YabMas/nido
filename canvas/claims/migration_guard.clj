(ns canvas.claims.migration-guard
  "The claims the shared-cluster migration guard design (workstream ws-20261127-ddbcd7, design at
   entry 32) commits nido to, declared where they live on. Each Claim's name is the claim's id, its
   docstring the statement, and its :about the elements it is about — the same three the design
   record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.session.lifecycle :refer [isolate! share!]]
            [canvas.session.postgres :refer [session-shared-pg]]
            [canvas.session.provisioning :refer [session-engine start-session! sync-runtime!]]))

(Claim shared-checkout-holds-only-main-migrations
  "A runtime that runs out of a checkout of its own and whose effective pg mode is :shared never
   has its checkout written with a tree in which a file under the project's migrations directory
   is absent from main@origin or differs from main@origin's file at the same path; such a refresh
   is refused before the checkout's files change, and the checkout keeps the files it had."
  {:about [session-engine start-session! sync-runtime!] :evidence "round"})

(Claim refusal-routed-like-conflict
  "The migration refusal carries :refused :migration-on-shared-db. A cold start throws it before
   any of the runtime's services start; a member's join, whether it restarts the runtime or not,
   throws it before the joining member is recorded on the runtime and before any service is
   stopped; sync-runtime! throws it to its caller. A member's departure logs it as a warning and
   keeps the checkout unchanged."
  {:about [session-engine start-session! sync-runtime!] :evidence "round"})

(Claim refusal-says-how-to-isolate
  "The refusal's message names every offending migration path and the runtime's sessions, gives
   `bb nido:session:isolate :project <project> <session>` and its `nido session:isolate` form, and
   says what isolating does: the session restarts on a private copy-on-write clone of the
   project's template database, the shared cluster and every other session are untouched, it is
   refused while another session is on the same runtime, and `nido:session:share` returns the
   session to the shared cluster, dropping the private copy. It also names the other way out:
   take the migration out of the working copy, or land it on main first."
  {:about [session-shared-pg isolate! share!] :evidence "round"})

(Claim only-shared-runtimes-compared
  "A refresh of a runtime whose effective pg mode is :isolated or :clone is never compared with
   main and never refused for its migrations."
  {:about [session-engine] :evidence "round"})

(Claim offence-is-add-or-change
  "What session-shared-pg answers as offending for a revision is exactly the migration paths that
   revision adds or whose content it changes relative to main@origin, under the same migrations
   directory and main revision advance-to-main reads; a migration identical to main's, or one
   deleted, is not offending."
  {:about [session-shared-pg] :evidence "round"})

(Claim guard-verified-by-tests
  "With this change, tests run by `bb nido:test` exercise a :shared session refused by up and by
   sync for an added migration (its checkout still without the file) and for a changed one (its
   checkout's copy keeping the bytes it had), the refusal's message, an :isolated or :clone
   session coming up and syncing with its checkout holding the migration, and a :shared session
   with identical or only deleted migrations not refused; and `bb nido:test` and
   `bb nido:design:check` pass."
  {:about [session-engine session-shared-pg] :evidence "round"})
