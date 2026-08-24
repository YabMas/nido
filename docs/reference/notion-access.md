# Notion access from an agent session

The one rule: **reach Notion through the `notion` CLI.** It is on `PATH`, it
carries its own credential, and `Bash(notion:*)` is allowlisted, so calls run
without a permission prompt.

**Never `WebFetch` a Notion URL.** Notion serves an empty JavaScript app shell to
an unauthenticated fetch — you get a page skeleton, not the ticket, and it looks
enough like content to be believed.

## If a Notion command says you have no token

Almost always the shell is wrong, not the credential. `bb notion:*` — a
project's own tasks — authenticate from `NOTION_TOKEN` / `NOTION_API_TOKEN`, and
projects load those with direnv from a gitignored secrets file. direnv hooks
interactive shells, and `claude` starts from the session home rather than the
project checkout, so the repo's `.envrc` never runs in the shell the agent
inherits its environment from. The variables are then simply absent, and

    NOTION_TOKEN or NOTION_API_TOKEN env var required

means *"this shell carries no token"*, not *"you have no Notion access"*. Reach
for `notion` and carry on.

The durable fix is the `~/.zshrc` export documented in nido's CLAUDE.md §
"Notion token in the agent's environment", which publishes the keychain PAT as
`NOTION_TOKEN` into every shell an agent is launched from. An agent inherits the
environment of the shell that started it, so the export reaches sessions started
after it was added — an older session, or a coordinator-spawned Run under
launchd, can still hit the error above.

### Where the credential actually lives

| store | read by | present |
|---|---|---|
| `~/.config/notion-cli/config.json` (profile `default`) | `notion` CLI | yes |
| macOS Keychain, service `nido-notion` | nido daemon, `bb nido:*`, and the `~/.zshrc` export | yes |
| `NOTION_TOKEN` / `NOTION_API_TOKEN` | a project's `bb notion:*`, CI | only where that export ran |

The first two hold the **same** Personal Access Token (the user-owned PAT named
"Nido", workspace *Brian*), and the third is a copy of it. Nothing needs
reconciling between them; they are one credential stored in more than one
place.

Check the keychain copy with `bb nido:notion:auth:check`; set it with
`bb nido:notion:auth:set`. There is no nido task for the CLI's own config —
that's `notion auth`.

## Command map

| you need | run |
|---|---|
| page body as markdown | `notion page markdown <id-or-url>` |
| page properties | `notion page props <id> --format json` |
| one property | `notion page property <id> <Prop> --format json` |
| body blocks (bounded) | `notion block list <id> --md --depth 3` |
| comments | `notion comment list <id> --all` |
| database schema | `notion db view <db-id>` |
| query rows | `notion db query <db-id-or-url> --filter 'Status=Done'` |
| resolve `BR-####` | `notion db query <task-db> --filter-json '{"property":"ID","unique_id":{"equals":5942}}' --format json` |
| create a page | `notion page create …` / `notion db add …` |
| set properties | `notion page set <id> "GitHub PR=<url>"` |
| append content | `notion block append <id> …` / `notion page set-markdown <id> …` |
| anything unsupported | `notion api <METHOD> <path> --body -` |

brian's Task Database is `124fca9f-403c-80d4-896f-fc857e105e35`; ticket IDs are a
`unique_id` property named `ID` with prefix `BR`, so `BR-5942` reads out of
`page props` as `.unique_id.prefix` + `-` + `.unique_id.number`.

## Gotchas

- **URL forms are not interchangeable.** `www.notion.so/...` URLs are accepted;
  `app.notion.com/p/...` URLs are rejected with `invalid_request_url`. When a URL
  fails, pass the bare 32-hex page id instead — every command takes one.
- **`unique_id` needs `--filter-json`.** The simple `--filter 'ID=5942'` form
  fails with a property-type validation error. Use the raw Notion filter JSON
  shown above.
- **Rich text caps at 2000 characters per run.** One oversized run rejects the
  *whole* request, so split long bodies into multiple runs/blocks before writing.
- **The API moved properties to data sources** (version `2025-09-03`).
  `/v1/databases/<id>` no longer returns `:properties`; read
  `/v1/data_sources/<ds-id>`, and create pages with a
  `{:type "data_source_id" :data_source_id …}` parent. This bites raw
  `notion api` calls; the CLI's own subcommands already handle it.

## Who writes to Notion

In the triage and ticket flows, **skills read and nido writes.** `/triage-bug`
and `/triage-slack` gather context with the commands above and park a verdict;
the Notion mutation happens deterministically in nido's apply path
(`bb nido:ticket:apply`, `nido.work/apply!`). If you find yourself
composing a Notion write inside one of those skills, the design has drifted.

Outside those flows — `/prepare-draft-pr` stamping a PR URL onto a ticket, an
ad-hoc lookup — writing directly with the CLI is fine.
