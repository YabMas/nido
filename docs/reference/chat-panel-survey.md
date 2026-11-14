# The catalog chat: what prior art says, and what nido takes from it

A survey made on 2026-11-14 for the catalog chat's refinement (workstream
`ws-20261113-b0e476`, findings round 1). It answers two questions. What should an
agent chat embedded beside a product's own UI look and behave like? And how do
the open-source implementations build it? The conclusions at the end are what
the design record commits to. Sources are listed at the bottom, and each claim
names the sources it comes from.

Most of what is written about this is practitioner writing and vendor
documentation, not research. Where only one product does something, that is
stated.

## 1. Where the chat lives

**Beside the work, not instead of it.** The agentic coding tools put the chat in
a side panel next to the thing being changed: Cursor's agent panel with its
browser, Zed's agent panel, and VS Code's chat view [cursor-design,
zed-panel, vscode-tools]. Pattern writers suggest a hybrid: a side panel for
anything conversational, and inline affordances only for one-shot actions
[metacto]. Notion's inline AI is the counter-example, and it fits a document
editor, not a gallery of components [metacto].

**A side panel the work stays usable beside pushes the content; it does not
cover it.** Design systems separate an *overlay* drawer, which is modal by
default and covers part of the page, from an *inline* (push) drawer, which
narrows the content so both stay usable [fluent-drawer, patternfly-drawer,
visa-panel, equinor-sheet]. Use the push form when a person works in the panel
and the page together, which is exactly what picking elements while talking
needs. Below roughly 1000px, push drawers commonly fall back to an overlay
[alberta-push].

**Closed means gone.** The tools that add an element picker add it as a mode of
the open chat, not as controls on the page. Stagewise turns selection on from
its toolbar or with ⌘I, and Cursor's Design Mode is toggled from the browser
[stagewise-dom, cursor-design]. react-grab loads only in development, so the
production page carries nothing [react-grab].

## 2. Showing the agent's work

**The waiting state is a state.** An empty wait after sending reads as failure.
Show that the request is under way from the moment it is sent, before the first
token [aiux-streaming, uxp-streaming].

**Name the step, not just "thinking".** A status that names what the agent is
doing now ("running the tests") earns more trust than a generic "thinking…"
[metacto]. Stream of Thought treats each step as a state: queued, running,
waiting for approval, error or completed. It keeps the plan, the execution and
the evidence apart [shapeof-sot]. Progressive disclosure means a condensed
indicator by default, expandable detail, an elapsed time, and a clear done
state [metacto].

**Reasoning opens while it streams and closes when it is done.** AI Elements'
Reasoning component opens automatically while it streams, closes when the turn
finishes, and its label carries the duration [ai-elements-reasoning]. Zed
coalesces thinking chunks into one block [zed-thinking]. VS Code collapses tool
calls by default and expands a failed one automatically [vscode-tools].

**Tool calls are the trust feature.** A tool call is shown inline as it runs,
with its result, so the chain can be audited afterwards [metacto]. Long tool
output is capped behind an expander [zed-pr-output]. A side-effecting action
asks with explicit buttons [metacto, pydantic-acp].

**Streaming text.** Stream into a stable block. A subtle caret marks the live
edge. Partial text stays readable, copyable and stoppable, and Stop keeps what
has arrived [aiux-streaming]. Streaming markdown has to survive incomplete
syntax; Streamdown exists for that in React [21st-kits].

**Accessibility.** Do not announce every token. Announce the start and the end
through a polite live region [aiux-streaming].

## 3. Selection as context

**Several elements, as attachments on the next message.** Stagewise lets a
person click several elements before sending, and they appear as attachments on
the message [stagewise-dom]. Cursor's picker at first allowed only one, and
users reported that as a regression to fix [cursor-multi]. Cursor's Design Mode
names multiple references as a feature [cursor-design].

**What travels with a pick.** Stagewise sends the element's HTML structure, key
computed styles, its bounding box and a unique selector path [stagewise-dom].
Cursor sends the element's identity (XPath, component, attributes, styles,
props) and a screenshot [cursor-design]. react-grab sends the element and its
component stack with source locations [react-grab].

**The picker excludes its own UI.** react-grab's primitives cover hit-testing
and page freezing, with an ignore attribute so the picker's own chrome is never
picked [react-grab].

## 4. Implementations

| project | what it is | taken | not taken, and why |
|---|---|---|---|
| Vercel AI Elements | React components (Conversation, Message, Reasoning, Tool, PromptInput) installed as source | the state vocabulary: reasoning open while streaming, a submit control with a ready/submitted/streaming status, a scroll-to-latest button | React on the client: nido renders on the server and patches with Datastar, and a second rendering model would mean a second copy of every block |
| assistant-ui | React chat primitives that map tool calls to components | tool calls as first-class items with their own rendering | same |
| Stagewise | an AGPL browser toolbar that picks DOM elements for an agent | multi-select, attachments on the message, a shortcut toggle, the shape of pick context | it is a React toolbar with its own agent; nido already has the agent and the page |
| react-grab | an MIT hover-and-copy element picker | excluding the picker's own UI from hit-testing | it reads React fibre data; nido's scenes are server-rendered markup with a stable path |
| Zed agent panel | an ACP client in Rust | coalesced thinking, capped tool output | n/a: the same protocol, rendered natively |
| Datastar | SSE that patches server-rendered fragments | streaming a transcript as per-item patches, which is what nido does now | n/a |

## 5. Conclusions for the catalog chat

1. **Closed by default, one launcher.** With the chat closed, `/_catalog` is the
   catalog. The only chat control is a single launcher in the catalog's own
   chrome, also reachable with ⌘I. Opening it slides a panel in from the right
   that pushes the frame narrower (inline, non-modal). Closing it removes the
   panel and every mark the chat put on the scenes. The open state survives a
   reload. (§1)
2. **A turn has a visible state from send to end.** A status row at the foot of
   the log says the agent is working and names the current phase: *waiting* (no
   update yet), *thinking*, *running* (with the running tool's title) or
   *writing*. It shows the elapsed time, and it is a polite live region that
   speaks when the turn starts and ends. While the turn runs the composer shows
   Stop, and stopping keeps what has arrived. (§2)
3. **Reasoning and tools follow the progressive-disclosure defaults.** A thought
   is open while it streams and collapses when the next item starts. A tool call
   is collapsed, and opens itself when it fails. Long output scrolls inside a
   capped box. (§2)
4. **Several picks, each a removable chip, each marked on the page.** Select mode
   stays on until it is turned off or Esc is pressed. Each click toggles an
   element in or out. Picked elements carry a numbered outline on the page that
   matches a numbered chip in the composer. Removing a chip unmarks its element.
   The message carries every pick, and shows them as chips once sent. The
   picker's own UI is never pickable. (§3)
5. **Stay server-rendered.** Take the patterns, not the React libraries: the
   panel remains a block over a transcript value, streamed as Datastar patches.
   (§4)

## Sources

- [metacto] metaCTO, *AI Chat UX Patterns: What Production Interfaces Get Right* — https://www.metacto.com/blogs/ai-chat-ux-patterns-production
- [shapeof-sot] Emily Campbell, *Shape of AI: Stream of Thought* — https://www.shapeof.ai/patterns/stream-of-thought
- [aiux-streaming] AI UX Playground, *Streaming* pattern — https://www.aiuxplayground.com/pattern/streaming
- [uxp-streaming] UX Patterns for Developers, *Streaming Response* — https://uxpatterns.dev/patterns/ai-intelligence/streaming-response
- [ai-elements-reasoning] Vercel AI Elements, *Reasoning* — https://elements.ai-sdk.dev/components/reasoning
- [21st-kits] 21st.dev, *AI app UI kits* — https://21st.dev/blog/ai-app-ui-kits
- [vscode-tools] VS Code docs, *Use tools with agents* — https://code.visualstudio.com/docs/copilot/agents/agent-tools
- [zed-panel] Zed docs, *Agent Panel* — https://zed.dev/docs/ai/agent-panel
- [zed-thinking] Zed commit log, *acp-thinking* — https://git.secluded.site/zed/commits/acp-thinking
- [zed-pr-output] Zed PR #59242, capped tool output — https://github.com/zed-industries/zed/pull/59242
- [pydantic-acp] Pydantic AI, *ACP harness* — https://pydantic.dev/docs/ai/harness/acp/index.md
- [stagewise-dom] Stagewise docs, *DOM context selector* — https://docs.stagewise.io/reference/dom-context-selector.md
- [cursor-design] Cursor blog, *Design Mode* — https://cursor.com/blog/design-mode
- [cursor-multi] Cursor forum, *Select Element feature does not allow multiple element selection* — https://forum.cursor.com/t/select-element-feature-does-not-allow-multiple-element-selection/145992
- [react-grab] react-grab README — https://cdn.jsdelivr.net/gh/aidenybai/react-grab@main/README.md
- [fluent-drawer] Microsoft Fluent 2, *Drawer usage* — https://fluent2.microsoft.design/components/web/react/core/drawer/usage
- [patternfly-drawer] PatternFly, *Drawer design guidelines* — https://www.patternfly.org/components/drawer/design-guidelines
- [visa-panel] Visa Product Design System, *Panel usage* — https://design.visa.com/components/panel/usage/
- [equinor-sheet] Equinor Design System, *Side sheet* — https://eds.equinor.com/docs/components/surfaces/side_sheet
- [alberta-push] Government of Alberta Design System, *Push drawer* — https://design.alberta.ca/components/push-drawer
- [datastar-essay] Datastar, *Event streams all the way down* — https://data-star.dev/essays/event_streams_all_the_way_down
