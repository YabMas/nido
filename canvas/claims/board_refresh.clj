(ns canvas.claims.board-refresh
  "The claims the board-refresh design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.work :refer [board-stamp grouped]]
            [canvas.platform.io :refer [update-edn!]]
            [canvas.ui.surface :refer [ui-tui]]))

(Claim board-stamp-moves-with-what-grouped-reads
  "board-stamp returns a different value after any of these writes: a ledger entry appended, a workstream created, a session record written with new content, a registered run's record written or a file created in or removed from its locks directory, the session registry changed, or a project page's Notion facts changed. It is taken from the mtimes of the directories holding those records and from the registry and Notion page facts as values, so it moves with any file created in, removed from or renamed within those directories, whatever that file records, and two calls between which none was and those values are unchanged return equal values. A lock its holder releases by exiting, with no file written, does not move it."
  {:about [board-stamp grouped] :evidence "test"})

(Claim tick-rebuilds-only-on-change
  "A board tick rebuilds the board's rows when board-stamp differs from the value the last rebuild took, or when 30s have passed since that rebuild, and otherwise leaves the rows as they are; every other screen re-reads its rows every tick."
  {:about [ui-tui board-stamp] :evidence "test"})

(Claim update-edn-writes-only-change
  "update-edn! leaves an existing file untouched when f returns a value equal to the one it read, and writes it otherwise."
  {:about [update-edn!] :evidence "test"})
