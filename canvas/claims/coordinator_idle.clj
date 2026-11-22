(ns canvas.claims.coordinator-idle
  "The claims the coordinator-idle design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.record.live :refer [census! record-live subjects]]))

(Claim subjects-are-live
  "A workstream is a subject while it can change, or a decision on another subject reads it, without anything writing to it: it is open and activated, it is an open drain or a slice an open drain has started, a session record under it names a session the registry or the agent listing holds live, or an unfinished Run names it. Open alone does not make one a subject, and neither does an activation a close has ended."
  {:about [record-live subjects] :evidence "round"})

(Claim picture-derived
  "record-live's picture is derived only, from the files and the agent listing a pass reads: built by one full read at start and corrected by each census for every workstream, conversation and Run whose stamps moved or that is new; nothing is written from it, a restart rebuilds it, and a process that has not started it is answered from disk."
  {:about [record-live] :evidence "round"})

(Claim census-sees-writes
  "The census stats, every tick, the stamps the record layer keys its own reads on, and the directories new records appear in: each workstream's directory (workstream.edn and seat-messages.edn are renamed into it) and its workstream.edn, entries/ and entries.meta/, the stamps read-ws's reconciled index is memoised on; each session directory; each run directory; and the events directory, which append! moves, with each live conversation's log, or every log when the events directory moved. So every write nido makes that a pass acts on — an append that died before its index row among them — moves a stamp the census reads."
  {:about [census!] :evidence "round"})

