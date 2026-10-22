(ns canvas.claims.test-home
  "The claims the test-home design commits nido to: a `bb nido:test` run sees a nido home made
   for it, never the machine's, so no test can reach a live session's pids, ports or registry.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.tasks.nido-test :refer [run home-refusal]]
            [canvas.platform.core :refer [nido-home nido-root]]))

(Claim suite-runs-in-own-home
  "Every `bb nido:test` runs its tests in a child bb whose NIDO_HOME is a temporary home made for that run under /tmp, short enough that a Unix socket bound in a temp directory nested inside it stays under the platform's path cap, and whose CODEX_HOME and temp directory (TMPDIR and java.io.tmpdir) lie inside it, so every nido-home read, every legacy-registry read and every temp home a test makes for itself resolves inside that home, as does each process the child starts that inherits its environment; the home is removed when the child exits, and the parent exits with the child's code."
  {:about [run] :evidence "test"})

(Claim foreign-home-refused
  "The test process runs no test unless its home is the one NIDO_TEST_HOME names, carries the marker the run wrote into it, and is not ~/.nido; otherwise it exits 2 with a message naming why."
  {:about [run home-refusal] :evidence "test"})

(Claim test-home-holds-only-fixtures
  "A test home starts holding only the run's marker and a registry of the fixture projects brian and nido, each with its checkout directory inside the home; nothing in it names a real checkout, session, pid or port."
  {:about [run] :evidence "test"})

(Claim two-redirect-seams
  "Redefining nido-root moves no session runtime state: the per-instance state directory and the canonical registry are built from nido-home, the legacy registries from $CODEX_HOME, and locks from locks-dir's own reading of $NIDO_HOME, so a test redirecting only nido-root reads and acts on the sessions of the home its environment names — under `bb nido:test`, the run's own test home. nido-root's docstring and canvas Operation say so, and no longer say that this keeps a redirected test looking at real session state."
  {:about [nido-root nido-home] :evidence "round"})

(Claim suite-green-in-test-home
  "The revision that lands has `bb nido:test` and `bb nido:land:check` exiting 0, with every test run in the test home; a test that asserts a path under the real ~/.nido, as nido.coordinator.record.state-test/paths does, asserts it under nido-home instead, and a test that pinned its temp directory outside the run home, as nido.coordinator.agent.substrate-test/send-writes-one-user-message-line-to-the-inbox did with /tmp, makes it in the run's temp directory instead."
  {:about [run] :evidence "round"})

(Claim stop-trace-named
  "The landing commit's brief names the test and code path that stopped blob-hash-dedup's REPL: nido.coordinator.e2e-test/manual-trigger-end-to-end → boot.core/tick! → maybe-adopt! → work/adopt-orphans! → (on rwqolrys) lane.scratch/settle-runtimes! → lifecycle/restart! → lifecycle/down! → engine/stop-session!."
  {:about [run] :evidence "round"})
