(ns canvas.claims.landing-restart
  "The claims the landing-restart design commits nido to: a landing owes the daemon a restart onto
   what it landed, and the daemon pays it itself once nothing live would be killed.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements it
   is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.boot.core :refer [boot-core tick!]]
            [canvas.coordinator.daemon.lifecycle :refer [daemon-restart]]
            [canvas.coordinator.control :refer [coordinator-control]]
            [canvas.coordinator.record.runs :refer [live?]]
            [canvas.tasks.nido-land :refer [nido-land restart-daemon!]]
            [canvas.ui.catalog-chat :refer [ui-catalog-chat]]
            [canvas.ui.surface :refer [ui-components-chat]]))




(Claim landing-rebases-checkout-now
  "After every push, whenever the daemon is installed under launchd, the landing rebases the daemon's checkout onto origin's main — bb nido:land has no option that skips this — whether or not it runs inside the daemon, runs are live or the daemon is running; a rebase that fails, conflicts or leaves the checkout without the landed commit is undone and records no debt."
  {:about [restart-daemon!] :evidence "round"})

(Claim restart-owed-is-recorded
  "A landing whose checkout rebase held records itself as the latest landing, owed: its commit, when, and its own process id, replacing whatever landing was recorded before; when that write fails, it keeps the rebase and exits non-zero naming the way out. No landing restarts, kickstarts, boots out or signals the daemon."
  {:about [daemon-restart restart-daemon!] :evidence "round"})

(Claim landing-record-is-typed
  "The latest landing is read from its file at one boundary, landing, which answers exactly one of: a record owed {commit, owed-at, by-pid} or running {commit, owed-at, since, pid}; none, when there is no file; or unreadable, with why. The file holds no wait: what an owed restart waits for is never stored. Every reader takes the landing from there and keeps unreadable apart from none, and a write that fails answers failed, with why, rather than a record."
  {:about [daemon-restart] :evidence "round"})

(Claim daemon-pays-owed-restart
  "A daemon loaded under launchd and running code that carries this change exits, for launchd to relaunch it, within one tick of the latest landing being owed and waits naming nothing. It never exits for a debt on a reading of waits that names anything — so never while a run whose agent is alive under the daemon is live — and it decides before starting any run in that tick."
  {:about [tick! daemon-restart live?] :evidence "round"})

(Claim one-landing-one-restart
  "A daemon whose process started after the debt was owed, and whose checkout holds the owed commit, never exits for that debt. At startup it records that landing as running with its own pid through running!, which replaces the record only while the record is still that owed landing — same commit and owed-at — comparing and writing under the lock owe! writes under; when the record is no longer that landing it writes nothing and leaves the record as it is; when the write fails it logs why and leaves the landing owed, which waits names as settled by the running daemon. A daemon that started before the debt was owed never records it running. This holds however the daemon came to start — relaunched by its own exit, or brought up by a person while the debt was owed — so one owed commit causes at most one restart."
  {:about [daemon-restart boot-core] :evidence "round"})

(Claim waits-said-in-one-place
  "What an owed restart waits for is decided by waits alone, from the owed landing it is given and what any process can read when it is asked: runs whose agent is alive under the daemon — those live? reports live, and those whose record says they are running and that the daemon itself executes, not on a background session, since live? does not see a run that takes no launch lock; the owing landing still running; the checkout not holding the commit; the daemon not running under launchd; and the running daemon having started after the debt was owed, which settles the debt rather than pays it. A run on a background session holds nothing, whatever its record says: its conversation outlives the restart and its own Stop settles it. The landing, the tick and Control each give waits the owed landing alone and take what an owed restart waits for from its answer; nothing stores it."
  {:about [daemon-restart] :evidence "round"})

(Claim landing-says-where-the-daemon-is
  "Every landing that pushed prints exactly one `land · coordinator …` line: the restart owed onto the landed commit and what it waits for, which always includes this landing finishing; or that none is owed, why, and the way out."
  {:about [nido-land] :evidence "round"})

(Claim publish-bar-shows-the-daemon
  "Whenever the catalog's chat renders its change bar — on its page and in each event its handle! sends with one — it asks Control for the latest landing at that render and hands the answer to the change bar in the publishing value; the change bar shows it whether or not there is a change or a Publish result: owed onto a commit with the waits Control's answer carries, running a commit since a time, or that the record cannot be read and why. The change bar reads nothing itself. This holds of the catalog chat served by code carrying this change; the daemon running when it lands serves one that does not, as its seam says."
  {:about [ui-catalog-chat ui-components-chat coordinator-control] :evidence "round"})

(Claim recipe-describes-the-debt
  "CLAUDE.md's 'Restart the coordinator onto the landing' says a landing rebases the daemon's checkout and owes a restart the daemon pays itself once no run is live, that nothing has to be run by hand, and where the person sees what it waits for. It also says that this holds once a daemon carrying it runs: the daemon running when this change lands is put onto it by one bb nido:coordinator:restart while no run is live."
  {:about [nido-land] :evidence "round"})

(Claim lands-through-the-gate
  "This change lands only through bb nido:land, whose gate runs bb nido:test and bb nido:land:check at the landed tip and refuses on either."
  {:about [nido-land] :evidence "round"})
