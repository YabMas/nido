(ns canvas.claims.landing-restart
  "The claims the landing-restart design commits nido to: a landing owes the daemon a restart onto
   what it landed, and the daemon pays it itself once nothing live would be killed.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements it
   is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.control :refer [coordinator-control]]
            [canvas.coordinator.record.runs :refer [live?]]
            [canvas.tasks.nido-land :refer [nido-land restart-daemon!]]
            [canvas.ui.catalog-chat :refer [ui-catalog-chat]]
            [canvas.ui.surface :refer [ui-components-chat]]))




(Claim landing-rebases-checkout-now
  "After every push, whenever the daemon is installed under launchd, the landing rebases the daemon's checkout onto origin's main — bb nido:land has no option that skips this — whether or not it runs inside the daemon, runs are live or the daemon is running; a rebase that fails, conflicts or leaves the checkout without the landed commit is undone and records no debt."
  {:about [restart-daemon!] :evidence "round"})

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
