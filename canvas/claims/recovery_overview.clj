(ns canvas.claims.recovery-overview
  "The claims the session-recovery overview design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the modules
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.source.start-failures :refer [source-start-failures]]
            [canvas.coordinator.view.recoveries :refer [view-recoveries]]
            [canvas.coordinator.work :refer [coordinator-work]]
            [canvas.ui.surface :refer [ui-server ui-views]]))

(Claim overview-derived-on-read
  "work/session-recovery reads kept failures, recovery workstreams with their entries, recovery Runs and sessions on each call and derives the whole overview from them through view-recoveries, which reads nothing itself and stores nothing."
  {:about [view-recoveries coordinator-work] :evidence "round"})

(Claim overview-uses-the-sources-decisions
  "The overview's owed failures, in-flight causes and next-due times are the recovery source's owed, in-flight? and due-at applied to the same records and the pacing the live recovery trigger declares."
  {:about [view-recoveries source-start-failures] :evidence "round"})

(Claim one-row-one-state
  "Each cause with an owed failure or an open recovery workstream is one row with exactly one state — needs-you when a recovery session of it is parked on an open workstream, else recovering when a recovery of it is in flight, else waiting when its next due time is after now, else owed — and a cause with neither is a row only when its latest recovery closed within the last seven days, restored when that closure was :done and dismissed otherwise."
  {:about [view-recoveries] :evidence "round"})

(Claim feed-is-the-records-in-order
  "The activity feed holds one event for each failure kept, recovery Run fired, recovery Run ended :done or :failed, :session-diagnosis, :session-restored, :blocker, :merged and :pr-opened entry and recovery closure there is, each at the time its own record carries, ordered newest first with equal times in a fixed order; a page of it is the at most eighty events that follow a position in that order, from the newest when there is none, and names the position of the next older page when events remain, so over the same records following older pages from the newest reaches every event exactly once."
  {:about [view-recoveries] :evidence "round"})

(Claim due-at-answers-when
  "due-at returns the moment a cause's open-workstream recovery Runs next let it be recovered, nil when the latest of them did not fail, and due? holds exactly when due-at is nil or not after the given time."
  {:about [source-start-failures] :evidence "round"})

(Claim operations-carries-recovery-in-one-poll
  "The Operations page and its five-second poll render the recovery section from work/session-recovery beside the proposals, the poll as one SSE event patching #recovery and #operations; when recovery cannot be read the section says so and the proposals are still shown."
  {:about [ui-server ui-views] :evidence "round"})

(Claim feed-page-kept-across-polls
  "The Operations page shows the feed page at the position its URL names, the newest when it names none, and links to the next older page when the view names one and to the newest when it is not on it; its five-second poll requests that same position, so a person reading an older page is not returned to the newest."
  {:about [ui-server ui-views] :evidence "round"})
