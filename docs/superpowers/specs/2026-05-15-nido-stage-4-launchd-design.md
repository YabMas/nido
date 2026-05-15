# nido Stage 4 — launchd auto-start

**Status:** implemented
**Date:** 2026-05-15
**Parent spec:** [`2026-05-13-nido-coordination-layer-design.md`](./2026-05-13-nido-coordination-layer-design.md)

## Context

Stage 3 ([commits 9613a5c + earlier](../../..)) added a background-daemon harness: `bb nido:coordinator:up / down / status / logs`. The daemon survives terminal close but does not survive logout or reboot, and a crash leaves it gone until manually restarted.

Stage 4 adds a macOS `LaunchAgent` plist so the coordinator auto-starts at user login and respawns on crash. The plist is per-user (lives under `~/Library/LaunchAgents/`), so no admin privileges are needed.

The goal is *operational confidence*, not new features: nothing about triggers, Runs, or the daemon's behaviour changes. The daemon binary the plist exec's is the same `bb nido:coordinator:run` that Stage 3 spawns.

## Goals

- `bb nido:coordinator:install` writes the plist, loads it, and the daemon starts immediately.
- The daemon auto-starts at every subsequent login.
- The daemon respawns within seconds if it crashes.
- The existing Stage 3 verbs (`up` / `down` / `status`) keep working — unchanged when the plist is *not* installed, launchctl-wrapping when it *is*. A new `restart` verb lands as part of Stage 4.
- Status surface honestly tells the user which lifecycle is in charge.

## Non-goals

- System-level (root) `LaunchDaemon` — stays user-scoped.
- `SMAppService`-based registration (modern macOS API). The classic plist path is older but well-understood; revisit if Apple deprecates it.
- Auto-update of an already-installed plist when nido moves on disk. User re-runs `install` from the new checkout.
- Cross-platform service registration (systemd, Windows). Out of scope; nido is macOS-first.

## Design choices (decided)

These came out of brainstorming on 2026-05-15:

1. **Unified surface.** `up` / `down` / `restart` are launchctl-aware when the plist is installed; they keep their Stage 3 PID-file behaviour when it isn't. One developer vocabulary for both lifecycles.
2. **`KeepAlive=true`.** While loaded, launchd restarts the daemon if it crashes or is killed. `down` therefore unloads (`launchctl bootout`) — not just SIGTERM — so a stop is actually a stop.
3. **`install` refuses if a bare daemon is running.** No silent migration; user runs `bb nido:coordinator:down` then re-installs. One-time action, worth the explicit step.
4. **Plist exec's `bb` directly.** No wrapper script. `ProgramArguments` is `[<abs-bb>, "nido:coordinator:run"]`, with `WorkingDirectory` baked at install time so `bb` resolves `bb.edn` correctly.

## Plist contract

Location: `~/Library/LaunchAgents/dev.nido.coordinator.plist`.

Rendered contents (XML omitted; this is the value shape):

```
{
  "Label":                "dev.nido.coordinator",
  "ProgramArguments":     [<abs-bb>, "nido:coordinator:run"],
  "WorkingDirectory":     <abs-nido-checkout>,
  "RunAtLoad":            true,
  "KeepAlive":            true,
  "ThrottleInterval":     10,
  "StandardOutPath":      <abs ~/.nido/coordinator/coordinator.log>,
  "StandardErrorPath":    <abs ~/.nido/coordinator/coordinator.log>,
  "EnvironmentVariables": { "PATH": "/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin" }
}
```

Field rationale:

- `<abs-bb>` resolved at install time via `which bb` (fall back to `bb` on PATH if `which` fails — surface this as an install error since the plist would then be broken). Re-install if the bb binary moves.
- `<abs-nido-checkout>` resolved at install time via `git rev-parse --show-toplevel` from the current working directory. Install must be run from inside the nido checkout — surface as install error otherwise.
- `KeepAlive=true` means "restart unconditionally while loaded." Pairs with `down` → `bootout`. A `:dict` form (`SuccessfulExit=false`) was considered and rejected: it conflates "the daemon crashed" with "the user pressed Ctrl-C" once signals enter the picture.
- `ThrottleInterval=10` is launchd's default. Mentioned explicitly so the design doesn't drift into "we said nothing, who knows."
- `PATH` is a deliberately minimal fallback. The daemon spawns `claude`, `git`, `bb`, and (transitively) project binaries. We do not bake the user's full PATH at install — too easy to capture stale state. The four entries above cover Homebrew (Intel + Apple Silicon) and system binaries.
- Both stdout and stderr go to `coordinator.log` so the file matches what `bb nido:coordinator:logs` tails today. The daemon's existing logging continues to write there too — there's no append race because the daemon process is the only writer (launchd does redirection at fork, the daemon doesn't double-open).

## Module layout

New file: `src/nido/coordinator/launchctl.clj`.

```clojure
(ns nido.coordinator.launchctl
  "Wrap macOS launchctl + the dev.nido.coordinator plist.
   Pure rendering helpers + small shell wrappers around `launchctl`.
   No nio2 watchers, no in-process state.")

(defn plist-path []  "~/Library/LaunchAgents/dev.nido.coordinator.plist")
(defn label    []  "dev.nido.coordinator")
(defn target   []  "gui/<uid>/dev.nido.coordinator")  ; <uid> resolved via `id -u` at call time

(defn installed? [] ...)                 ; plist file exists
(defn loaded?    [] ...)                 ; launchctl print <target> exits 0
(defn render-plist [{:keys [bb-path nido-dir log-path path-env]}] ...)
(defn write-plist!  [contents] ...)
(defn remove-plist! []          ...)
(defn bootstrap! []  ...)                ; launchctl bootstrap gui/<uid> <plist>
(defn bootout!   []  ...)                ; launchctl bootout    gui/<uid>/<label>
(defn kickstart! []  ...)                ; launchctl kickstart -k gui/<uid>/<label>
```

The shell wrappers each return `{:exit n :out s :err s}` rather than throwing, so the task layer can format launchctl errors uniformly.

## CLI changes

### New tasks

```
bb nido:coordinator:install     # write plist, bootstrap, daemon starts now
bb nido:coordinator:uninstall   # bootout, delete plist (no-op if not installed)
bb nido:coordinator:restart     # kickstart -k (when installed); error otherwise
```

`install` flow:

1. Refuse if a bare daemon is running (`pid/alive?` true *and* plist not installed). Print migration hint.
2. Refuse if not inside a nido checkout (`git rev-parse --show-toplevel` fails).
3. Refuse if `which bb` fails.
4. Render plist with absolute paths.
5. Overwrite `~/Library/LaunchAgents/dev.nido.coordinator.plist`.
6. If already loaded, `bootout` first (so the bootstrap picks up the new plist).
7. `bootstrap gui/<uid> <plist-path>`. `RunAtLoad=true` means the daemon starts now.
8. Print "Installed. Daemon running (pid <X>). Will auto-start at login."

`uninstall` flow:

1. If loaded, `bootout`. (Tolerate "not loaded" — exit 0.)
2. If plist file exists, `rm` it.
3. Print "Uninstalled. Run bb nido:coordinator:up to start manually."

`restart` flow:

- Installed + loaded → `kickstart -k`. Prints new pid.
- Installed + not loaded → `bootstrap`.
- Not installed → exit non-zero with "Not installed. Use bb nido:coordinator:down then up."

### Modified tasks

`up` adds a launchctl branch at the top:

```
if (installed?)
  if (loaded?)  → "already managed by launchd (pid <X>)"  exit 0
  else          → bootstrap  →  "Daemon started via launchd (pid <X>)"
else            → existing Stage 3 spawn path
```

`down` similarly:

```
if (installed?)  → bootout                       → "Stopped via launchd."
else             → existing SIGTERM + PID cleanup path
```

`status` adds two new lines above the existing process probe:

```
Launchd:      installed (loaded) | installed (not loaded) | not installed
Managed by:   launchd | none
Process:      alive (pid X) | not running (no PID file) | stale PID …
Coordinator:  running | stopped | …       ← unchanged
Heartbeat:    …                            ← unchanged
Slots:        …                            ← unchanged
```

When `Managed by: launchd`, the `Process:` line still reads the PID file the daemon writes — that's the same daemon, just supervised by launchd, so the existing probe Just Works.

`restart` is **new** for the launchctl path — Stage 3 deliberately did not ship a restart command (the design said "use down then up"). Stage 4 adds it because launchctl gives us atomic kickstart-with-kill. When the plist isn't installed, `restart` refuses and points at down/up.

## State surface (extension of Stage 3)

No new files under `~/.nido/coordinator/`. The plist lives under `~/Library/LaunchAgents/`. PID file, status.edn, halted.edn, queue/ all unchanged.

```
~/.nido/coordinator/
├── coordinator.pid       # daemon writes; unchanged
├── status.edn            # daemon writes; unchanged
├── halted.edn            # halt skill writes; unchanged
├── coordinator.log       # daemon + launchd redirect both write here
└── queue/                # unchanged

~/Library/LaunchAgents/
└── dev.nido.coordinator.plist   # new — written by install, removed by uninstall
```

## Failure modes & user-visible behavior

| Situation | What user sees |
|---|---|
| `install` while bare daemon up | "Bare coordinator running (pid X). Run `bb nido:coordinator:down`, then re-install." |
| `install` outside a nido checkout | "Run install from inside the nido git checkout." |
| `install` and `which bb` fails | "bb not found on PATH. Install babashka first." |
| `install` already installed | bootout existing, overwrite plist, re-bootstrap. Idempotent. |
| `uninstall` not installed | "Not installed. Nothing to do." exit 0. |
| `up` while plist installed + loaded + running | "Already managed by launchd (pid X)." exit 0. |
| `up` while plist installed + not loaded | bootstrap. |
| `down` while plist installed | bootout. |
| `restart` not installed | "Not installed. Use `bb nido:coordinator:down && bb nido:coordinator:up`." exit 1. |
| Daemon crashes | launchd waits `ThrottleInterval` (10s), respawns. Daemon's own startup-reconciliation marks orphaned Runs `:failed`. |
| User edits plist manually | Next `install` overwrites. Next `up` / `down` may misbehave; not supported. |

## Testing

- **Pure tests** (in `test/nido/coordinator/launchctl_test.clj`):
  - `render-plist` produces the expected XML for known inputs (golden file).
  - `installed?` / `loaded?` return correct boolean for fixture filesystem + stubbed `launchctl print` result.
- **Task-layer tests** (in `test/tasks/nido_coordinator_test.clj`, new): parameterize the launchctl-call function so install/uninstall/restart can be exercised against a test double that records `bootstrap` / `bootout` / `kickstart` invocations.
- **Manual smoke test** (added to `docs/skill-conventions-for-triggers.md` or a Stage 4 readme): install, log out + back in, verify daemon running, force-crash with `kill -9`, verify respawn within 10s.

No CI integration for real `launchctl` — it's macOS-only and per-user.

## Out of scope (for reviewer)

Deferred:

- Auto-detecting when the user moves nido on disk and updating the plist. Re-install is fine.
- Capturing the install-time PATH (richer than the minimal fallback) into the plist. The minimal PATH covers known cases; expand if a user reports a missing binary.
- `bb nido:coordinator:logs --since <ts>` — Stage 3 surfaces last-N lines + follow; if launchd-managed crashes start being interesting, slice by timestamp later.
- A nido-side "is launchd healthy?" probe distinct from the daemon's own heartbeat. KeepAlive + ThrottleInterval is the supervision; the daemon's status.edn is the liveness signal.

## Implementation order

1. `launchctl.clj` with pure functions + tested `render-plist`.
2. `install` / `uninstall` tasks; smoke-test by hand.
3. `up` / `down` launchctl branches.
4. `restart` task.
5. `status` lines.
6. Update `CLAUDE.md` with the Stage 4 verbs and the "wrap launchctl when installed" rule.
7. Manual smoke: install → reboot → verify auto-start → kill -9 → verify respawn.
