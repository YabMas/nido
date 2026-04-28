#!/usr/bin/env bash
# =============================================================================
# nido playwright shard runner
#
# Orchestrates a per-shard Playwright run that needs project-specific worker
# org/admin setup before tests start. This is the generic version of brian's
# ci/playwright-entrypoint.sh, lifted out of any project tree so any nido
# CI step can call it from its :command.
#
# Steps:
#   1. Read the nREPL port from `--nrepl-port-file` (relative to cwd).
#   2. Eval `--setup-code` against that nREPL via the project-supplied
#      `--nrepl-eval-cmd` (e.g. `bb bin/ci/nrepl-eval.bb`). The eval is
#      expected to return a JSON string; nREPL wraps strings in quotes,
#      so we unwrap with `node -e JSON.parse` to get the raw JSON.
#   3. Write the JSON to `--out-file`.
#   4. cd into `--playwright-dir` and exec `npx playwright test` with all
#      remaining args after `--`.
#
# Usage:
#   playwright-shard.sh \
#     --nrepl-port-file .nrepl-port \
#     --nrepl-eval-cmd 'bb bin/ci/nrepl-eval.bb' \
#     --setup-code '(do (require (quote brian.ci.e2e-setup)) (brian.ci.e2e-setup/create-worker-orgs-json! 1))' \
#     --out-file e2e/worker-orgs.json \
#     --playwright-dir e2e \
#     -- --shard=1/3 --workers=1 --grep-invert '@lti'
#
# Designed to be called from a nido ci.edn :command (single project knows
# its setup function and worker count; nido just plumbs nREPL + Playwright).
# =============================================================================
set -euo pipefail

NREPL_PORT_FILE=".nrepl-port"
NREPL_EVAL_CMD=""
SETUP_CODE=""
OUT_FILE=""
PLAYWRIGHT_DIR="e2e"
SETUP_TIMEOUT_MS=120000

while [[ $# -gt 0 ]]; do
  case "$1" in
    --nrepl-port-file)  NREPL_PORT_FILE="$2"; shift 2 ;;
    --nrepl-eval-cmd)   NREPL_EVAL_CMD="$2";  shift 2 ;;
    --setup-code)       SETUP_CODE="$2";      shift 2 ;;
    --out-file)         OUT_FILE="$2";        shift 2 ;;
    --playwright-dir)   PLAYWRIGHT_DIR="$2";  shift 2 ;;
    --setup-timeout-ms) SETUP_TIMEOUT_MS="$2"; shift 2 ;;
    --) shift; break ;;
    *) echo "playwright-shard.sh: unknown arg '$1'" >&2; exit 2 ;;
  esac
done

if [[ -z "$NREPL_EVAL_CMD" ]]; then
  echo "playwright-shard.sh: --nrepl-eval-cmd is required" >&2; exit 2
fi
if [[ -z "$SETUP_CODE" ]]; then
  echo "playwright-shard.sh: --setup-code is required" >&2; exit 2
fi
if [[ -z "$OUT_FILE" ]]; then
  echo "playwright-shard.sh: --out-file is required" >&2; exit 2
fi
# `.nrepl-port` is written by `nrepl.cmdline` after user.clj's auto-init
# returns, which on brian-shaped apps is *after* the HTTP listener binds.
# Our :services :port-ready healthcheck fires on the HTTP port, so the
# step's :command can land here before the nREPL file exists. Poll for
# it, then proceed. (In session mode the file usually already exists in
# the cloned source — this loop is a no-op there.)
NREPL_PORT_TIMEOUT_SECS="${NREPL_PORT_TIMEOUT_SECS:-180}"
echo "[playwright-shard] waiting up to ${NREPL_PORT_TIMEOUT_SECS}s for $NREPL_PORT_FILE"
deadline=$(( $(date +%s) + NREPL_PORT_TIMEOUT_SECS ))
while [[ ! -s "$NREPL_PORT_FILE" ]] && (( $(date +%s) < deadline )); do
  sleep 1
done
if [[ ! -s "$NREPL_PORT_FILE" ]]; then
  echo "playwright-shard.sh: nREPL port file '$NREPL_PORT_FILE' did not appear within ${NREPL_PORT_TIMEOUT_SECS}s" >&2
  exit 2
fi

NREPL_PORT=$(cat "$NREPL_PORT_FILE")

echo "[playwright-shard] seeding worker orgs via nREPL on port $NREPL_PORT"

# Retry the setup eval — port-ready healthcheck fires when HTTP binds, but
# mount may still be wiring up *db* / RAD resolvers when the eval lands. A
# few rounds of "wait then try" is what brian's CI orchestrator does too.
RAW=""
for attempt in 1 2 3 4 5 6 7 8 9 10; do
  if RAW=$($NREPL_EVAL_CMD -p "$NREPL_PORT" --timeout "$SETUP_TIMEOUT_MS" "$SETUP_CODE" 2>&1); then
    if echo "$RAW" | grep -qE 'Execution error|Exception:|NullPointerException|IllegalArgument'; then
      echo "[playwright-shard] attempt $attempt: setup-code threw, mount likely still booting; retrying in 5s..."
      RAW=""
      sleep 5
      continue
    fi
    break
  fi
  echo "[playwright-shard] attempt $attempt: nREPL transport failed; retrying in 5s..."
  RAW=""
  sleep 5
done

if [[ -z "$RAW" ]]; then
  echo "playwright-shard.sh: setup-code never produced a clean result after 10 attempts" >&2
  exit 1
fi

# nREPL eval returns a printed Clojure value. When the value is a JSON
# string, the printed form is a quoted Clojure string. Unwrap with node:
# JSON.parse on the printed form gives the underlying string; pass-through
# for already-bare values.
mkdir -p "$(dirname "$OUT_FILE")"
node -e 'try { const s = process.argv[1]; const v = JSON.parse(s); process.stdout.write(typeof v === "string" ? v : s) } catch (e) { process.stdout.write(process.argv[1]) }' "$RAW" > "$OUT_FILE"

# Sanity check the result is a non-empty JSON array.
if ! node -e 'const d = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8")); if (!Array.isArray(d) || d.length === 0) process.exit(1)' "$OUT_FILE" 2>/dev/null; then
  echo "playwright-shard.sh: setup-code returned invalid JSON in $OUT_FILE:" >&2
  head -c 500 "$OUT_FILE" >&2
  echo >&2
  exit 1
fi

echo "[playwright-shard] wrote $(wc -c < "$OUT_FILE" | tr -d ' ') bytes to $OUT_FILE"

cd "$PLAYWRIGHT_DIR"
echo "[playwright-shard] exec npx playwright test $*"
exec npx playwright test "$@"
