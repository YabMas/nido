(ns nido.coordinator.shim
  "Resume shim + run-link symlink for Run-owned sessions.

   Writes <session-home>/bin/claude (executable) and
   <session-home>/run-link symlink → <run-dir>.

   The shim reads run-link/run.edn at invocation time to discover the
   claude session-id, so typing `claude` from the session-home resumes
   the autonomous conversation. Falls through to a normal `claude` if
   the id is absent. Resumes with --dangerously-skip-permissions to match
   how the coordinator launched the autonomous run (so a human picking up the
   parked session to apply/skip isn't prompted on every tool call).

   For provision-only impl sessions (promote → :full, no autonomous burst), a
   one-shot `.continue-on-first-enter` marker makes the shim launch
   `claude --session-id <uuid> /continue-ticket` the FIRST time the human enters
   — so the agent picks up the triage findings on turn 1 — then resumes that
   same session (`--resume <uuid>`) on later entries. See spec §Agent launch /
   Resume from the session-home."
  (:require
   [babashka.fs :as fs]))

(def ^:private continue-marker-name ".continue-on-first-enter")

(def ^:private shim-script
  ;; `bb -e EXPR` prints the result with pr-str semantics, so a string
  ;; value gets surrounded by literal " characters. Use (print …) so the
  ;; bare session-id ends up in SESSION_ID; bb suppresses the nil return.
  "#!/usr/bin/env bash
set -euo pipefail
HOME_DIR=\"$(dirname \"$0\")/..\"
RUN_EDN=\"$HOME_DIR/run-link/run.edn\"
MARKER=\"$HOME_DIR/.continue-on-first-enter\"
SESSION_ID=\"\"
if [ -f \"$RUN_EDN\" ]; then
  SESSION_ID=$(bb -e \"(print (or (-> (slurp \\\"$RUN_EDN\\\") clojure.edn/read-string :claude-session-id) \\\"\\\"))\" 2>/dev/null || true)
fi
# First entry of a provision-only impl session: kick off /continue-ticket once,
# under the pre-generated session-id so later entries resume this conversation.
if [ -f \"$MARKER\" ]; then
  rm -f \"$MARKER\"
  if [ -n \"$SESSION_ID\" ]; then
    exec command claude --session-id \"$SESSION_ID\" --dangerously-skip-permissions \"/continue-ticket\" \"$@\"
  fi
  exec command claude --dangerously-skip-permissions \"/continue-ticket\" \"$@\"
fi
if [ -n \"$SESSION_ID\" ]; then
  exec command claude --resume \"$SESSION_ID\" --dangerously-skip-permissions \"$@\"
fi
exec command claude --dangerously-skip-permissions \"$@\"
")

(defn write!
  "Write the shim + run-link in the given session-home pointing at run-dir."
  [session-home run-dir]
  (let [bin-dir   (fs/path session-home "bin")
        shim-path (fs/path bin-dir "claude")
        link-path (fs/path session-home "run-link")]
    (fs/create-dirs bin-dir)
    (spit (str shim-path) shim-script)
    (fs/set-posix-file-permissions shim-path "rwxr-xr-x")
    (when (fs/exists? link-path) (fs/delete link-path))
    (fs/create-sym-link link-path run-dir)))

(defn mark-continue-on-first-enter!
  "Drop the one-shot marker so the shim launches `claude /continue-ticket` the
   first time the human enters this provision-only session (then resumes the
   resulting conversation afterwards). Idempotent."
  [session-home]
  (spit (str (fs/path session-home continue-marker-name)) ""))
