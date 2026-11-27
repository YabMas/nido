(ns canvas.claims.dashboard-read-path
  "The claims the dashboard read-path design commits nido to — the census that measures every page,
   and the history read the Operations pages share — declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.work :refer [coordinator-work proposals improvement-holds session-recovery]]
            [canvas.core.client :refer [core-client]]
            [canvas.tasks.nido-bench :refer [nido-bench]]
            [canvas.ui.surface :refer [ui-server]]))

(Claim census-every-page
  "The census measures every page ui-server lists, each parameterised route by at least one concrete instance, and prints every page it measured with its median and its samples. ui-server's list holds every page route handle-get renders to a browser, and names each route it leaves out with the reason; the census task's source names each route the census leaves out with the reason too."
  {:about [nido-bench ui-server] :evidence "round"})

(Claim census-browser-median
  "A page's figure is the median of at least three loads in a browser driven through agent-browser, each sample read from the browser's navigation timing of that load."
  {:about [nido-bench] :evidence "round"})

(Claim census-bounded
  "A census run ends within 10 minutes on this host: every load has a deadline, and a page whose load misses it is reported as timed out with the samples it has, never dropped."
  {:about [nido-bench] :evidence "round"})

(Claim archive-read-once
  "Between two writes of a project's archive by the same process (records/archive!, which no runtime path calls), the process asks each core for that project's archived holders at most once, and answers every other history read against that core from what it holds of that core's archive; a write drops what the process holds for that project, so the next read asks again."
  {:about [proposals improvement-holds session-recovery core-client] :evidence "round"})

(Claim history-read-once-per-request
  "Within the request read a surface opens around one render, every history derivation of a project — proposals, improvement holds, session recovery, and the reads within each — answers from one read of that project's runs. A runs read is never answered from a value an earlier request read: nothing live is held between requests."
  {:about [coordinator-work proposals improvement-holds session-recovery] :evidence "round"})
