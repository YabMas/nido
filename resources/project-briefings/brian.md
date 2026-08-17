## Project: brian

### Routing (delegate before editing)

- `src/main/brian/ui/*.clj`, `hc/compile`, `data-signals`, `@get`/`@post`,
  `patch-elements!`, chassis HTML → `datastar-dev`
- `rt/process-event!`, statechart working memory reads, chart-state
  response selection, statechart EDN → `statechart-dev`
- `deftest`, fixtures, test refactors → `test-dev`

If a handler matches both Datastar and statechart bullets (e.g. calls
`rt/process-event!` and emits `patch-elements!`), `statechart-dev` leads.

If a task spans domains, delegate to the primary owner. The primary
owner decides if peer handoff is needed (typically: a contract change
between the domains, like statechart ops producing signals the Datastar
side must consume). Sequential work where each agent owns a complete
slice does not require coordination.

When you don't know existing patterns or the file you're about to edit,
run `Agent` with `subagent_type=Explore` first.

### Core rules

1. Server is the single source of truth — rendering, state, and
   validation all live on the server. The client captures interaction
   intent; it does not mirror authoritative state.
2. Verify code in the REPL before calling it done (see REPL section
   below).
3. For bug fixes, start with a failing test.
4. Browser tooling only after REPL/tests, and only for visual or
   interaction checks.
5. Before querying Postgres tables, inspect `information_schema.columns`.
6. Use `bb notion:*` tasks for Notion. Do not use WebFetch on Notion
   URLs.

### Clojure REPL execution

The nREPL port for this session is in the briefing at the top of this
file (`- nrepl port: <N>`). That is the authoritative Clojure
environment — do NOT start a fresh JVM, Kaocha runner, or
`clojure -M:*` process to run focused tests or verify changed code.

- Eval through `clj-nrepl-eval -p <port> ...` against the briefing's
  nrepl port.
- Reload changed namespaces with `:reload` before evaluation. Never
  `:reload-all` (breaks Pathom3 protocol identity).
- If a reload/test failure smells like stale state (unrelated
  namespaces failing, errors that don't match the diff), bring the
  session down and back up with `bb nido:session:down :project brian
  <session>` then `bb nido:session:up :project brian <session>`, and
  retry. Do not fall back to host-local Clojure.

### References

- `~/Code/brian/docs/reference/agent-delegation.md`
- `~/Code/brian/docs/reference/agent-ownership.md`
- `~/Code/brian/docs/reference/repl.md`
- `~/Code/brian/docs/guidelines/testing-requirements.md`
- `~/Code/brian/docs/reference/notion-context.md`

## Layer boundaries and review lanes

When cutting a change into stacked layers (see `/stack`), brian's review lanes
are the concrete tiebreaker for "would these go to the same specialist?":

| lane | owns |
|---|---|
| `lane-db-deploy` | Flyway migrations, schema-touching code, env config |
| `lane-malli` | schemas, validators, transformers, registry |
| `lane-authz` | routes, middleware, `authz/check` call sites |
| `lane-datastar` | `hc/compile`, signals, `patch-elements!`, chassis HTML |
| `lane-missionary` | `m/sp`, `m/ap`, supervision, backpressure |
| `lane-statechart` | charts, `rt/process-event!`, working memory |

A migration stratum is a `lane-db-deploy` layer; a substantial-UI stratum is a
`lane-datastar` layer. Name the lane in each layer's review brief so per-layer
review can dispatch the right specialist.
