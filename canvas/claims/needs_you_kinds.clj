(ns canvas.claims.needs-you-kinds
  "The claims the needs-you-kinds design commits nido to: the dashboard says what needs the person
   as four kinds — ask, landing, system error and unread chat — each with its own top-bar icon,
   count and list, scoped to the focused project.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements it
   is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.work :refer [read-gates Gate all-gates]]
            [canvas.boot.attention :refer [boot-attention]]
            [canvas.ui.side-chat :refer [ui-side-chat unseen]]
            [canvas.ui.surface :refer [ui-components-state ui-components-row needs-page needs-fragment]]))

(Claim kind-marks-in-state-level
  "Each kind has one mark in the marks level, and every surface says that kind with it: a raised hand for an ask, a rocket for a landing, an octagon with an exclamation for a system error, and a speech bubble for an unread chat. Each is a 24-unit stroked SVG in currentColor, coloured --needs, titled with the kind's needs-words."
  {:about [ui-components-state ui-components-row] :evidence "round"})

(Claim gate-kinds-unchanged
  "read-gates and its Gate are unchanged by this: every card carries its :project and exactly one :needs of :ask, :landing or :system-error, and the three gate kinds the chrome counts and lists are those cards grouped by :needs."
  {:about [read-gates Gate] :evidence "round"})

(Claim chat-kind-is-unseen
  "The unread-chat kind is exactly what unseen publishes, unchanged: one item per workstream with a side chat pinned to it that ended a turn, or began waiting on a permission, after the person last had it open, counted under that workstream's project. Having the chat open in the side panel is what reads it; an item also leaves when nothing is left for the person there — its permission answered — or when its chat no longer belongs to the workstream, closed or unpinned. A chat that follows the person or is pinned to a page, and the conductor's workstream chat, count nowhere."
  {:about [unseen ui-side-chat] :evidence "round"})

(Claim list-per-kind
  "The home page lists the focused project's items only. With no kind named, it shows one section per kind, in the icons' order, each headed by its kind's mark and count; with a kind named in the request, it shows that kind's section alone. An unread-chat item links to the workstream its chat is pinned to."
  {:about [needs-page needs-fragment] :evidence "round"})

(Claim notifications-read-all-gates
  "Desktop notifications are announced by the daemon from all-gates, every project's, and read nothing of the dashboard: no focus, no scope, no tally and no side chat's news reaches them."
  {:about [boot-attention all-gates] :evidence "round"})
